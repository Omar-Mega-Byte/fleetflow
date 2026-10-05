package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class Phase3LifecycleTests extends ProfileTestDatabase {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    Actor admin;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        admin = actor("ADMIN");
    }

    @Test
    void orderTransitionsAreGuardedAuditedAndCreateNoDelivery() throws Exception {
        Actor customer = actor("CUSTOMER");
        customer(customer);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch();
        membership(merchant, branch);
        UUID order = createOrder(customer, branch);
        assertOrder(order, "CREATED", 0, 1);
        assertThat(jdbc.queryForObject("select actor_id from order_lifecycle_history where order_id = ? and version = 0",
                UUID.class, order)).isEqualTo(customer.id);
        postMerchant(merchant, branch, order, "start-preparation", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIFECYCLE_CONFLICT"));
        postMerchant(merchant, branch, order, "confirm", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        postMerchant(merchant, branch, order, "confirm", null)
                .andExpect(status().isConflict());
        postMerchant(merchant, branch, order, "start-preparation", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/v1/orders/{id}/cancel", order).header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"late\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LIFECYCLE_CONFLICT"));
        postMerchant(merchant, branch, order, "ready-for-pickup", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));
        assertOrder(order, "READY_FOR_PICKUP", 3, 4);
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(customer))
                        .param("status", "READY_FOR_PICKUP"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject("select ready_at is not null from orders where id = ?", Boolean.class, order))
                .isTrue();
        assertThat(jdbc.queryForObject("select count(*) from deliveries", Integer.class)).isZero();
        postMerchant(merchant, branch, order, "cancel", "{\"reason\":\"  kitchen closed  \"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        assertOrder(order, "CANCELLED", 4, 5);
        assertThat(jdbc.queryForObject("select reason from order_lifecycle_history where order_id = ? and version = 4",
                String.class, order)).isEqualTo("kitchen closed");
        assertThat(jdbc.queryForObject("select actor_id from order_lifecycle_history where order_id = ? and version = 4",
                UUID.class, order)).isEqualTo(merchant.id);
        postMerchant(merchant, branch, order, "ready-for-pickup", null).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from deliveries", Integer.class)).isZero();
    }

    @Test
    void customerCancellationAndReasonValidationAreScoped() throws Exception {
        Actor owner = actor("CUSTOMER");
        Actor outsider = actor("CUSTOMER");
        customer(owner); customer(outsider);
        UUID order = createOrder(owner, branch());
        String path = "/api/v1/orders/" + order + "/cancel";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        for (String bad : List.of("{}", "{\"reason\":\"   \"}",
                "{\"reason\":\"" + "a".repeat(501) + "\"}")) {
            mvc.perform(post(path).header("Authorization", bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertOrder(order, "CREATED", 0, 1);
        mvc.perform(post(path).header("Authorization", bearer(owner))
                        .header("X-Correlation-ID", "customer-cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("select correlation_id from order_lifecycle_history where order_id = ? and version = 1",
                String.class, order)).isEqualTo("customer-cancel");
        mvc.perform(post(path).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        assertOrder(order, "CANCELLED", 1, 2);
    }

    @Test
    void merchantNeedsCurrentExactBranchMembership() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch();
        UUID other = branch();
        membership(merchant, other);
        UUID order = createOrder(owner, branch);
        postMerchant(merchant, branch, order, "confirm", null)
                .andExpect(status().isNotFound());
        membership(merchant, branch);
        postMerchant(merchant, other, order, "confirm", null)
                .andExpect(status().isNotFound());
        jdbc.update("update merchant_staff_memberships set active = false where actor_id = ? and branch_id = ?",
                merchant.id, branch);
        postMerchant(merchant, branch, order, "confirm", null)
                .andExpect(status().isNotFound());
        jdbc.update("update merchant_staff_memberships set active = true where actor_id = ? and branch_id = ?",
                merchant.id, branch);
        postMerchant(merchant, branch, order, "confirm", null).andExpect(status().isOk());
        jdbc.update("update actors set active = false where id = ?", merchant.id);
        postMerchant(merchant, branch, order, "start-preparation", null)
                .andExpect(status().isForbidden());
        assertOrder(order, "CONFIRMED", 1, 2);
    }

    @Test
    void driverStateVehicleSuspensionAndSelfScope() throws Exception {
        Actor driverActor = actor("DRIVER");
        Actor otherActor = actor("DRIVER");
        UUID driver = createDriver(driverActor);
        UUID other = createDriver(otherActor);
        assertThat(jdbc.queryForObject("select actor_id from driver_lifecycle_history where driver_id = ? and version = 0",
                UUID.class, driver)).isEqualTo(admin.id);
        driverAction(driverActor, "available").andExpect(status().isConflict());
        driverAction(driverActor, "online").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        driverAction(driverActor, "available").andExpect(status().isConflict());
        createVehicle(driver);
        driverAction(driverActor, "available").andExpect(status().isOk());
        driverAction(driverActor, "pause").andExpect(status().isOk());
        driverAction(driverActor, "available").andExpect(status().isOk());
        driverAction(driverActor, "offline").andExpect(status().isOk());
        driverAction(driverActor, "offline").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from driver_lifecycle_history where driver_id = ?",
                Integer.class, driver)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?", String.class, other))
                .isEqualTo("OFFLINE");
        jdbc.update("update drivers set suspended = true where id = ?", driver);
        driverAction(driverActor, "online").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        jdbc.update("update drivers set suspended = false, active = false where id = ?", driver);
        driverAction(driverActor, "online").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from deliveries", Integer.class)).isZero();
    }

    @Test
    void remainingOrderCancellationAndDriverEdgesAreExplicit() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch(); membership(merchant, branch);
        UUID created = createOrder(owner, branch);
        postMerchant(merchant, branch, created, "cancel", "{\"reason\":\"closed\"}")
                .andExpect(status().isOk());
        assertOrder(created, "CANCELLED", 1, 2);

        UUID confirmed = createOrder(owner, branch);
        postMerchant(merchant, branch, confirmed, "confirm", null).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/{id}/cancel", confirmed).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
                .andExpect(status().isOk());
        assertOrder(confirmed, "CANCELLED", 2, 3);

        UUID preparing = createOrder(owner, branch);
        postMerchant(merchant, branch, preparing, "confirm", null).andExpect(status().isOk());
        postMerchant(merchant, branch, preparing, "start-preparation", null).andExpect(status().isOk());
        postMerchant(merchant, branch, preparing, "cancel", "{\"reason\":\"incident\"}")
                .andExpect(status().isOk());
        assertOrder(preparing, "CANCELLED", 3, 4);

        Actor driverActor = actor("DRIVER");
        UUID driver = createDriver(driverActor);
        createVehicle(driver);
        driverAction(driverActor, "online").andExpect(status().isOk());
        driverAction(driverActor, "pause").andExpect(status().isOk());
        jdbc.update("update vehicles set active = false where driver_id = ?", driver);
        driverAction(driverActor, "available").andExpect(status().isConflict());
        jdbc.update("update vehicles set active = true where driver_id = ?", driver);
        driverAction(driverActor, "available").andExpect(status().isOk());
        driverAction(driverActor, "pause").andExpect(status().isOk());
        driverAction(driverActor, "offline").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select version from drivers where id = ?", Long.class, driver)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from driver_lifecycle_history where driver_id = ?",
                Integer.class, driver)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from deliveries", Integer.class)).isZero();
    }

    @Test
    void wrongRolesAndMalformedIdsNeverChangeState() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch(); membership(merchant, branch);
        UUID order = createOrder(owner, branch);
        postMerchant(owner, branch, order, "confirm", null).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/{id}/cancel", order).header("Authorization", bearer(merchant))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/not-a-uuid/confirm", branch)
                        .header("Authorization", bearer(merchant)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertOrder(order, "CREATED", 0, 1);
    }

    @Test
    void concurrentCancellationAndPreparationHaveOneWinner() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch(); membership(merchant, branch);
        UUID order = createOrder(owner, branch);
        postMerchant(merchant, branch, order, "confirm", null).andExpect(status().isOk());
        List<Integer> results = race(
                () -> mvc.perform(post("/api/v1/orders/{id}/cancel", order)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cancel\"}")).andReturn().getResponse().getStatus(),
                () -> postMerchant(merchant, branch, order, "start-preparation", null)
                        .andReturn().getResponse().getStatus());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        String state = jdbc.queryForObject("select status from orders where id = ?", String.class, order);
        assertThat(state).isIn("CANCELLED", "PREPARING");
        assertThat(jdbc.queryForObject("select count(*) from order_lifecycle_history where order_id = ?",
                Integer.class, order)).isEqualTo(3);
    }

    @Test
    void concurrentDuplicateMerchantAndDriverCommandsCreateOneEvent() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch(); membership(merchant, branch);
        UUID order = createOrder(owner, branch);
        assertThat(race(
                () -> postMerchant(merchant, branch, order, "confirm", null).andReturn().getResponse().getStatus(),
                () -> postMerchant(merchant, branch, order, "confirm", null).andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        assertOrder(order, "CONFIRMED", 1, 2);
        Actor driverActor = actor("DRIVER");
        UUID driver = createDriver(driverActor);
        assertThat(race(
                () -> driverAction(driverActor, "online").andReturn().getResponse().getStatus(),
                () -> driverAction(driverActor, "online").andReturn().getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from driver_lifecycle_history where driver_id = ?",
                Integer.class, driver)).isEqualTo(2);
    }

    @Test
    void failedHistoryInsertRollsBackOrderState() throws Exception {
        Actor owner = actor("CUSTOMER"); customer(owner);
        Actor merchant = actor("MERCHANT");
        UUID branch = branch(); membership(merchant, branch);
        UUID order = createOrder(owner, branch);
        jdbc.execute("""
                create function fail_phase3_confirm() returns trigger language plpgsql as $$
                begin
                    if new.order_id = '%s'::uuid and new.action = 'CONFIRM' then
                        raise exception 'test history failure';
                    end if;
                    return new;
                end;
                $$
                """.formatted(order));
        jdbc.execute("create trigger fail_phase3_confirm before insert on order_lifecycle_history "
                + "for each row execute function fail_phase3_confirm()");
        try {
            postMerchant(merchant, branch, order, "confirm", null).andExpect(status().is5xxServerError());
            assertOrder(order, "CREATED", 0, 1);
        } finally {
            jdbc.execute("drop trigger fail_phase3_confirm on order_lifecycle_history");
            jdbc.execute("drop function fail_phase3_confirm()");
        }
    }

    private List<Integer> race(Action first, Action second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> { start.await(); return first.run(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            return List.of(a.get(), b.get());
        }
    }

    private void assertOrder(UUID order, String state, long version, int events) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order)).isEqualTo(state);
        assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, order)).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from order_lifecycle_history where order_id = ?",
                Integer.class, order)).isEqualTo(events);
    }

    private org.springframework.test.web.servlet.ResultActions postMerchant(Actor merchant, UUID branch,
            UUID order, String action, String body) throws Exception {
        var request = post("/api/v1/merchant-branches/{branch}/orders/{order}/{action}", branch, order, action)
                .header("Authorization", bearer(merchant));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions driverAction(Actor driver, String action) throws Exception {
        return mvc.perform(post("/api/v1/drivers/me/{action}", action).header("Authorization", bearer(driver)));
    }

    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id, TestOidc.ISSUER, actor.subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id, role);
        return actor;
    }

    private void customer(Actor actor) {
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", UUID.randomUUID(), actor.id);
    }

    private UUID branch() {
        UUID merchant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchant);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                    pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30, 31, true, now(), now())
                """, branch, merchant);
        return branch;
    }

    private void membership(Actor actor, UUID branch) {
        jdbc.update("insert into merchant_staff_memberships (id, actor_id, branch_id, active, created_at) "
                + "values (?, ?, ?, true, now())", UUID.randomUUID(), actor.id, branch);
    }

    private UUID createOrder(Actor customer, UUID branch) throws Exception {
        String body = """
                {"branchId":"%s","delivery":{"line1":"Road","city":"Cairo",
                "latitude":30,"longitude":31},"currency":"EGP",
                "items":[{"description":"Item","quantity":1,"unitAmount":1.00}]}
                """.formatted(branch);
        MvcResult result = mvc.perform(post("/api/v1/orders").header("Authorization", bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID createDriver(Actor driver) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/drivers").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"actorId\":\"" + driver.id + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private void createVehicle(UUID driver) throws Exception {
        mvc.perform(post("/api/v1/vehicles").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"" + driver + "\",\"type\":\"BIKE\",\"capacity\":1}"))
                .andExpect(status().isCreated());
    }

    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject); }
    private record Actor(UUID id, String subject) { }
    @FunctionalInterface private interface Action { int run() throws Exception; }
}
