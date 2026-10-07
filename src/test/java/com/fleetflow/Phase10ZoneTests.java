package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import com.fleetflow.location.LocationFix;
import com.fleetflow.location.RedisLocationStore;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
class Phase10ZoneTests {
    private static final PostgreSQLContainer postgres = PostgisTestDatabase.create();
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort());
    static { postgres.start(); redis.start(); }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
        registry.add("fleetflow.recovery.enabled", () -> false);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
    }

    private static final String CORE = """
            {"type":"Polygon","coordinates":[
              [[31,30],[32,30],[32,31],[31,31],[31,30]],
              [[31.4,30.4],[31.6,30.4],[31.6,30.6],[31.4,30.6],[31.4,30.4]]
            ]}
            """;
    private static final String FAR = """
            {"type":"MultiPolygon","coordinates":[[[[33,30],[34,30],[34,31],[33,31],[33,30]]]]}
            """;
    private static final String BOW_TIE = """
            {"type":"Polygon","coordinates":[[[31,30],[32,31],[31,31],[32,30],[31,30]]]}
            """;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired RedisLocationStore locations;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired PlatformTransactionManager transactions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void signedManagementAndSpatialBoundaryPolicyControlOrderIntake() throws Exception {
        Fixture f = fixture();
        Actor foreign = actor("MERCHANT");
        Actor wrongRole = actor("CUSTOMER");
        String zones = "/api/v1/merchant-branches/" + f.branch() + "/zones";
        mvc.perform(get(zones)).andExpect(status().isUnauthorized());
        mvc.perform(get(zones).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(zones).header("Authorization", bearer(wrongRole)))
                .andExpect(status().isForbidden());
        mvc.perform(get(zones).header("Authorization", bearer(foreign)))
                .andExpect(status().isNotFound());
        mvc.perform(get(zones).header("Authorization", bearer(f.merchant())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(put(policyPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ENFORCED\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_EMPTY"));

        mvc.perform(post(zones).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(zoneBody("Bad", BOW_TIE, true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(zones).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(zoneBody("Wrong CRS",
                        CORE.replace("\"coordinates\"", "\"crs\":{},\"coordinates\""), true)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from delivery_zones where branch_id = ?",
                Integer.class, f.branch())).isZero();

        UUID zoneId = createZone(f, CORE);
        assertThat(jdbc.queryForObject("select zone_policy_version from merchant_branches where id = ?",
                Long.class, f.branch())).isEqualTo(1L);
        mvc.perform(put(policyPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ENFORCED\",\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.2, 31.2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.zoneId").value(zoneId.toString()));
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.0, 31.5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(true));
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.5, 31.5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(false));
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(31.1, 31.5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(false));

        int before = jdbc.queryForObject("select count(*) from orders where merchant_branch_id = ?",
                Integer.class, f.branch());
        int beforeItems = jdbc.queryForObject("select count(*) from order_items", Integer.class);
        int beforeHistory = jdbc.queryForObject("select count(*) from order_status_history", Integer.class);
        mvc.perform(post("/api/v1/orders").header("Authorization", bearer(f.customer()))
                .contentType(MediaType.APPLICATION_JSON).content(orderBody(f.branch(), 30.5, 31.5)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_UNSERVICEABLE"));
        assertThat(jdbc.queryForObject("select count(*) from orders where merchant_branch_id = ?",
                Integer.class, f.branch())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isEqualTo(beforeItems);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history", Integer.class))
                .isEqualTo(beforeHistory);
        UUID order = createOrder(f, 30.0, 31.5);
        var snapshot = jdbc.queryForMap("select zone_mode_at_create, zone_policy_version_at_create, "
                + "zone_id_at_create from orders where id = ?", order);
        assertThat(snapshot.get("zone_mode_at_create")).isEqualTo("ENFORCED");
        assertThat(snapshot.get("zone_policy_version_at_create")).isEqualTo(2L);
        assertThat(snapshot.get("zone_id_at_create")).isEqualTo(zoneId);

        mvc.perform(put(zones + "/" + zoneId).header("Authorization", bearer(foreign))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Far", FAR, false, 0)))
                .andExpect(status().isNotFound());
        mvc.perform(put(zones + "/" + zoneId).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Core", CORE, false, 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(zones + "/" + zoneId).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Core", CORE, true, 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_VERSION_CONFLICT"));
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.2, 31.2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(false));
        assertThat(jdbc.queryForObject("select serviceability_mode from merchant_branches where id = ?",
                String.class, f.branch())).isEqualTo("ENFORCED");
        assertThat(jdbc.queryForObject("""
                select ST_Covers(zone_boundary_after, ST_SetSRID(ST_MakePoint(31.2, 30.2), 4326))
                from zone_policy_history where branch_id = ? and policy_version = 1
                """, Boolean.class, f.branch())).isTrue();
        assertThat(jdbc.queryForObject("select zone_active_after from zone_policy_history "
                + "where branch_id = ? and policy_version = 3", Boolean.class, f.branch())).isFalse();
        assertThatThrownBy(() -> jdbc.update("delete from zone_policy_history where branch_id = ?", f.branch()))
                .hasMessageContaining("append-only");
    }

    @Test
    void overlappingZonesHaveStableEvidenceAndMembershipRevocationTakesEffect() throws Exception {
        Fixture f = fixture();
        Actor otherMerchant = actor("MERCHANT");
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", f.merchant().id());
        String zones = "/api/v1/merchant-branches/" + f.branch() + "/zones";
        UUID first = createZone(f, CORE);
        UUID second = createZone(f, CORE);
        enforce(f, 2);
        UUID expectedZone = jdbc.queryForObject("select id from delivery_zones where id in (?, ?) "
                + "order by id limit 1", UUID.class, first, second);
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.2, 31.2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.zoneId").value(expectedZone.toString()));
        mvc.perform(get(zones).header("Authorization", bearer(otherMerchant)))
                .andExpect(status().isNotFound());
        mvc.perform(put(zonePath(f, first)).header("Authorization", bearer(otherMerchant))
                .contentType(MediaType.APPLICATION_JSON)
                .content(replaceBody("Foreign", FAR, true, 0)))
                .andExpect(status().isNotFound());
        mvc.perform(put(zonePath(f, first)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(zoneBody("Missing version", FAR, true)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select name from delivery_zones where id = ?",
                String.class, first)).isEqualTo("Core");

        mvc.perform(post(zones).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(zoneBody("Out of range",
                        CORE.replace("[31,30]", "[181,30]"), true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(zones).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(zoneBody("3D",
                        CORE.replace("[31,30]", "[31,30,2]"), true)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.1234567, 31.2)))
                .andExpect(status().isBadRequest());

        jdbc.update("update merchant_staff_memberships set active = false where actor_id = ? and branch_id = ?",
                f.merchant().id(), f.branch());
        mvc.perform(get(zones).header("Authorization", bearer(f.merchant())))
                .andExpect(status().isNotFound());
        mvc.perform(post(previewPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(point(30.2, 31.2)))
                .andExpect(status().isNotFound());
        mvc.perform(put(policyPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"UNRESTRICTED\",\"expectedVersion\":3}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select serviceability_mode from merchant_branches where id = ?",
                String.class, f.branch())).isEqualTo("ENFORCED");
    }

    @Test
    void zoneEditBlocksMatchAndOfferWithoutReservingDriver() throws Exception {
        Fixture f = fixture();
        UUID zone = createZone(f, CORE);
        enforce(f, 1);
        UUID first = createOrder(f, 30.2, 31.2);
        ready(f, first);
        Actor driverActor = actor("DRIVER");
        UUID driver = driver(driverActor);
        assertThat(locations.accept(new LocationFix(driver, 30.001, 31.001, null, null,
                Instant.now(), Instant.now()))).isZero();
        MvcResult matched = mvc.perform(post("/api/v1/dispatch/orders/{id}/match", first)
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("RANKED"))
                .andReturn();
        String attempt = JsonPath.read(matched.getResponse().getContentAsString(), "$.attemptId");
        mvc.perform(put(zonePath(f, zone)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Core", CORE, false, 0)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", first)
                .header("Authorization", bearer(f.dispatcher()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + attempt + "\",\"driverId\":\"" + driver + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_UNSERVICEABLE"));
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, first))
                .isEqualTo("DISPATCH_FAILED");
        assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?",
                String.class, driver)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, first)).isZero();
        assertThat(jdbc.queryForObject("select last_reason from dispatch_recovery where order_id = ?",
                String.class, first)).isEqualTo("ZONE_UNSERVICEABLE");
        mvc.perform(get("/api/v1/dispatch/orders/{id}/incidents", first)
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("ZONE_UNSERVICEABLE"));

        mvc.perform(put(zonePath(f, zone)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Core", CORE, true, 1)))
                .andExpect(status().isOk());
        UUID second = createOrder(f, 30.2, 31.2);
        ready(f, second);
        mvc.perform(put(zonePath(f, zone)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Far", FAR, true, 2)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", second)
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("ZONE_UNSERVICEABLE"));
        assertThat(jdbc.queryForObject("select count(*) from dispatch_candidates c join dispatch_attempts a "
                + "on a.id = c.attempt_id where a.order_id = ?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("select status from dispatch_recovery where order_id = ?",
                String.class, second)).isEqualTo("EXHAUSTED");
        mvc.perform(put(zonePath(f, zone)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(replaceBody("Core", CORE, true, 3)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/retry", second)
                .header("Authorization", bearer(f.dispatcher()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Coverage restored\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("RANKED"));
        assertThat(jdbc.queryForObject("select status from dispatch_incidents where order_id = ? "
                + "and code = 'ZONE_UNSERVICEABLE'", String.class, second)).isEqualTo("RESOLVED");
    }

    @Test
    void concurrentZoneChangeSerializesWithOfferAndKeepsAcceptedWork() throws Exception {
        Fixture f = fixture();
        UUID zone = createZone(f, CORE);
        enforce(f, 1);
        Actor driverActor = actor("DRIVER");
        UUID driver = driver(driverActor);
        assertThat(locations.accept(new LocationFix(driver, 30.001, 31.001, null, null,
                Instant.now(), Instant.now()))).isZero();
        UUID acceptedOrder = createOrder(f, 30.2, 31.2);
        ready(f, acceptedOrder);
        String acceptedAttempt = match(f, acceptedOrder);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", acceptedOrder)
                .header("Authorization", bearer(f.dispatcher()))
                .contentType(MediaType.APPLICATION_JSON).content(offerBody(acceptedAttempt, driver)))
                .andExpect(status().isCreated());
        UUID acceptedOffer = jdbc.queryForObject(
                "select id from driver_assignments where order_id = ?", UUID.class, acceptedOrder);
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", acceptedOffer)
                .header("Authorization", bearer(driverActor))).andExpect(status().isOk());

        Actor nextDriverActor = actor("DRIVER");
        UUID nextDriver = driver(nextDriverActor);
        assertThat(locations.accept(new LocationFix(nextDriver, 30.001, 31.002, null, null,
                Instant.now(), Instant.now()))).isZero();
        UUID pendingOrder = createOrder(f, 30.2, 31.2);
        ready(f, pendingOrder);
        String pendingAttempt = match(f, pendingOrder);
        CountDownLatch edited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var edit = pool.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                try {
                    mvc.perform(put(zonePath(f, zone)).header("Authorization", bearer(f.merchant()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceBody("Far", FAR, true, 0)))
                            .andExpect(status().isOk());
                    edited.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Edit not released");
                    return null;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(edited.await(5, TimeUnit.SECONDS)).isTrue();
            var offer = pool.submit(() -> mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", pendingOrder)
                    .header("Authorization", bearer(f.dispatcher()))
                    .contentType(MediaType.APPLICATION_JSON).content(offerBody(pendingAttempt, nextDriver)))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(150);
            release.countDown();
            edit.get(5, TimeUnit.SECONDS);
            assertThat(offer.get(5, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, pendingOrder))
                .isEqualTo("DISPATCH_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, pendingOrder)).isZero();
        assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?",
                String.class, nextDriver)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, acceptedOrder))
                .isEqualTo("DRIVER_ASSIGNED");
        assertThat(jdbc.queryForObject("select status from driver_assignments where id = ?",
                String.class, acceptedOffer)).isEqualTo("ACCEPTED");
    }

    private String match(Fixture f, UUID order) throws Exception {
        MvcResult matched = mvc.perform(post("/api/v1/dispatch/orders/{id}/match", order)
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("RANKED"))
                .andReturn();
        return JsonPath.read(matched.getResponse().getContentAsString(), "$.attemptId");
    }

    private String offerBody(String attempt, UUID driver) {
        return "{\"attemptId\":\"" + attempt + "\",\"driverId\":\"" + driver + "\"}";
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER");
        Actor merchant = actor("MERCHANT");
        Actor dispatcher = actor("DISPATCHER");
        UUID customerId = UUID.randomUUID(), merchantId = UUID.randomUUID(), branch = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", customerId, customer.id());
        jdbc.update("insert into merchants (id, name, created_at, updated_at) "
                + "values (?, 'Merchant', now(), now())", merchantId);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30, 31, now(), now())
                """, branch, merchantId);
        jdbc.update("insert into merchant_staff_memberships (id, actor_id, branch_id, created_at) "
                + "values (?, ?, ?, now())", UUID.randomUUID(), merchant.id(), branch);
        return new Fixture(customer, merchant, dispatcher, branch);
    }

    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, created_at, updated_at) "
                + "values (?, ?, ?, now(), now())", actor.id(), TestOidc.ISSUER, actor.subject());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id(), role);
        return actor;
    }

    private UUID driver(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, operational_status, created_at, updated_at) "
                + "values (?, ?, 'AVAILABLE', now(), now())", id, actor.id());
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, created_at, updated_at) "
                + "values (?, ?, 'BIKE', 1, now(), now())", UUID.randomUUID(), id);
        return id;
    }

    private UUID createZone(Fixture f, String geometry) throws Exception {
        MvcResult response = mvc.perform(post("/api/v1/merchant-branches/{id}/zones", f.branch())
                .header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON).content(zoneBody("Core", geometry, true)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(response.getResponse().getContentAsString(), "$.id"));
    }

    private void enforce(Fixture f, long expectedVersion) throws Exception {
        mvc.perform(put(policyPath(f)).header("Authorization", bearer(f.merchant()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ENFORCED\",\"expectedVersion\":" + expectedVersion + "}"))
                .andExpect(status().isOk());
    }

    private UUID createOrder(Fixture f, double latitude, double longitude) throws Exception {
        MvcResult response = mvc.perform(post("/api/v1/orders")
                .header("Authorization", bearer(f.customer()))
                .contentType(MediaType.APPLICATION_JSON).content(orderBody(f.branch(), latitude, longitude)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(response.getResponse().getContentAsString(), "$.id"));
    }

    private void ready(Fixture f, UUID order) throws Exception {
        for (String action : new String[] {"confirm", "start-preparation", "ready-for-pickup"}) {
            mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{order}/{action}",
                    f.branch(), order, action).header("Authorization", bearer(f.merchant())))
                    .andExpect(status().isOk());
        }
    }

    private String policyPath(Fixture f) { return "/api/v1/merchant-branches/" + f.branch() + "/zone-policy"; }
    private String previewPath(Fixture f) { return "/api/v1/merchant-branches/" + f.branch() + "/serviceability"; }
    private String zonePath(Fixture f, UUID zone) {
        return "/api/v1/merchant-branches/" + f.branch() + "/zones/" + zone;
    }
    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject()); }
    private String point(double latitude, double longitude) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}";
    }
    private String zoneBody(String name, String geometry, boolean active) {
        return "{\"name\":\"" + name + "\",\"geometry\":" + geometry
                + ",\"active\":" + active + "}";
    }
    private String replaceBody(String name, String geometry, boolean active, int version) {
        return "{\"name\":\"" + name + "\",\"geometry\":" + geometry
                + ",\"active\":" + active + ",\"expectedVersion\":" + version + "}";
    }
    private String orderBody(UUID branch, double latitude, double longitude) {
        return """
                {"branchId":"%s","delivery":{"line1":"Road","city":"Cairo",
                 "latitude":%s,"longitude":%s},"currency":"EGP",
                 "items":[{"description":"Bread","quantity":1,"unitAmount":1.00}]}
                """.formatted(branch, latitude, longitude);
    }

    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor merchant, Actor dispatcher, UUID branch) { }
}
