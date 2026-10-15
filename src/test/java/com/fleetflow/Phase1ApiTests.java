package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.math.BigDecimal;
import java.time.Instant;
import jakarta.servlet.Filter;
import com.fleetflow.identity.AuthenticatedActor;
import com.fleetflow.identity.Role;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Set;

import com.jayway.jsonpath.JsonPath;
import com.fleetflow.order.OrderService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
class Phase1ApiTests {
    private static final PostgreSQLContainer postgres = PostgisTestDatabase.create();

    static { postgres.start(); }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired EntityManagerFactory entityManagerFactory;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class),
                        context.getBean("locationBodyLimit", org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                                .getFilter())
                .build();
        jdbc.update("""
                insert into actors (id, issuer, subject, active, created_at, updated_at)
                values (?, ?, 'phase1-admin', true, now(), now()) on conflict (issuer, subject) do nothing
                """, ADMIN_ID, TestOidc.ISSUER);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'ADMIN') on conflict do nothing", ADMIN_ID);
    }

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void onboardingOrderIntakeDetailAndScopedList() throws Exception {
        String actor = id(create("/api/v1/actors", """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        String customer = id(create("/api/v1/customers", """
                {"actorId":"%s","displayName":"Nadia"}
                """.formatted(actor)));
        String merchant = id(create("/api/v1/merchants", "{\"name\":\"Bakery\"}"));
        String branch = id(create("/api/v1/merchant-branches", """
                {"merchantId":"%s","name":"Downtown","pickupLine1":"Old Street","pickupCity":"Cairo",
                 "pickupLatitude":30.044400,"pickupLongitude":31.235700}
                """.formatted(merchant)));
        String otherActor = id(create("/api/v1/actors", """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        create("/api/v1/customers", "{\"actorId\":\"%s\",\"displayName\":\"Other\"}".formatted(otherActor));
        create("/api/v1/merchant-staff-memberships", "{\"actorId\":\"%s\",\"branchId\":\"%s\"}"
                .formatted(otherActor, branch));
        String driverActor = id(create("/api/v1/actors", """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        String driver = id(create("/api/v1/drivers", "{\"actorId\":\"%s\"}".formatted(driverActor)));
        String vehicle = id(create("/api/v1/vehicles", """
                {"driverId":"%s","type":"BIKE","capacity":1}
                """.formatted(driver)));
        mvc.perform(get("/api/v1/vehicles/{id}", vehicle).header("Authorization", adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driver));
        String orderBody = """
                {"branchId":"%s","delivery":{"line1":"New Street","city":"Cairo",
                  "latitude":30.050000,"longitude":31.240000},"currency":"EGP",
                 "items":[{"description":"Bread","quantity":2,"unitAmount":12.50},
                          {"description":"Tea","quantity":1,"unitAmount":2.00}]}
                """.formatted(branch);

        MvcResult created = mvc.perform(orderPost(actor)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(27.0))
                .andReturn();
        String orderId = id(created);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].lineAmount").value(25.0))
                .andExpect(jsonPath("$.items[1].description").value("Tea"))
                .andExpect(jsonPath("$.pickup.line1").value("Old Street"));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(otherActor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", bearer(otherActor)))
                .andExpect(status().isNotFound());
        assertThat(customer).isNotBlank();
    }

    @Test
    void orderCreateIdempotencyReplaysTheOriginalResultAndRejectsKeyReuse() throws Exception {
        Fixture fixture = fixture();
        String body = orderBody(fixture.branch(), "Bread", 1, "1.00");
        String key = "order-create-" + UUID.randomUUID();
        int ordersBefore = jdbc.queryForObject("select count(*) from orders", Integer.class);
        int itemsBefore = jdbc.queryForObject("select count(*) from order_items", Integer.class);
        int historyBefore = jdbc.queryForObject("select count(*) from order_lifecycle_history", Integer.class);
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox_events", Integer.class);

        mvc.perform(post("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore);

        MvcResult first = mvc.perform(orderPost(fixture.actor(), key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mvc.perform(orderPost(fixture.actor(), key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String orderId = id(first);
        assertThat(id(replay)).isEqualTo(orderId);
        assertThat(replay.getResponse().getHeader("Location")).isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isEqualTo(itemsBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from order_lifecycle_history", Integer.class))
                .isEqualTo(historyBefore + 1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(outboxBefore);

        mvc.perform(orderPost(fixture.actor(), key).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "2.00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore + 1);

        Fixture other = fixture();
        MvcResult otherActor = mvc.perform(orderPost(other.actor(), key).contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")))
                .andExpect(status().isCreated()).andReturn();
        assertThat(id(otherActor)).isNotEqualTo(orderId);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore + 2);
    }

    @Test
    void simultaneousOrderCreateRetriesCommitOneOrderAndBodyLimitReturnsStable413() throws Exception {
        Fixture fixture = fixture();
        String key = "concurrent-order-" + UUID.randomUUID();
        String body = orderBody(fixture.branch(), "Bread", 1, "1.00");
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> concurrentOrderPost(
                fixture.actor(), key, body, ready, start));
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> concurrentOrderPost(
                fixture.actor(), key, body, ready, start));
        ready.await();
        start.countDown();
        MvcResult firstResult = first.join();
        MvcResult secondResult = second.join();
        assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
        assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
        assertThat(id(firstResult)).isEqualTo(id(secondResult));

        int ordersBefore = jdbc.queryForObject("select count(*) from orders", Integer.class);
        mvc.perform(orderPost(fixture.actor()).contentType(MediaType.APPLICATION_JSON)
                        .content("x".repeat(1024 * 1024 + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore);
    }

    private MvcResult concurrentOrderPost(String actorId, String key, String body,
                                          java.util.concurrent.CountDownLatch ready,
                                          java.util.concurrent.CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return mvc.perform(orderPost(actorId, key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void validationUsesStableErrorAndCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/actors").header("Authorization", adminBearer())
                        .header("X-Correlation-ID", "phase1-test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"issuer\":\"\",\"subject\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("X-Correlation-ID", "phase1-test"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value("phase1-test"));
    }

    @Test
    void snapshotsSurviveProfileEditsAndDatabaseFailureRollsBack() throws Exception {
        Fixture fixture = fixture();
        String orderId = id(createOrder(fixture.actor(), fixture.branch(), "Bread", 2, "12.50"));
        jdbc.update("update customers set display_name = 'Changed customer' where id = ?",
                UUID.fromString(fixture.customer()));
        jdbc.update("update merchant_branches set name = 'Changed branch', pickup_line1 = 'Moved street' where id = ?",
                UUID.fromString(fixture.branch()));
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("Authorization", bearer(fixture.actor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customerName").value("Nadia"))
                .andExpect(jsonPath("$.branchName").value("Downtown"))
                .andExpect(jsonPath("$.pickup.line1").value("Old Street"))
                .andExpect(jsonPath("$.items[0].description").value("Bread"));

        Integer before = jdbc.queryForObject("select count(*) from orders where customer_id = ?", Integer.class,
                UUID.fromString(fixture.customer()));
        var invalid = new OrderService.CreateRequest(UUID.fromString(fixture.branch()),
                new OrderService.Address("New Street", "Cairo", new BigDecimal("30.05"),
                        new BigDecimal("31.24")), "EGP",
                List.of(new OrderService.ItemInput("Invalid at DB", 0, new BigDecimal("1.00"))));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedActor(UUID.fromString(fixture.actor()), Set.of(Role.CUSTOMER)), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))));
        try {
            assertThatThrownBy(() -> orderService.create(invalid, "invalid-" + UUID.randomUUID()))
                    .hasMessageContaining("ck_order_items_quantity");
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(jdbc.queryForObject("select count(*) from orders where customer_id = ?", Integer.class,
                UUID.fromString(fixture.customer()))).isEqualTo(before);
    }

    @Test
    void invalidOrderAndListInputsReturnBadRequest() throws Exception {
        Fixture fixture = fixture();
        String body = orderBody(fixture.branch(), "Bread", 0, "12.50");
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", -1, "12.50")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(fixture.branch(), "Bread", 1, "12.345")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("\"currency\":\"EGP\"", "\"currency\":\"ZZZ\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 2147483647, "9999999999999999.99")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("30.050000", "91.000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("31.240000", "181.000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("31.240000", "NaN")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("31.240000", "Infinity")))
                .andExpect(status().isBadRequest());
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("\"items\":[{\"description\":\"Bread\",\"quantity\":1,\"unitAmount\":1.00}]",
                                        "\"items\":[]")))
                .andExpect(status().isBadRequest());
        String item = "{\"description\":\"Bread\",\"quantity\":1,\"unitAmount\":1.00}";
        String tooManyItems = java.util.stream.IntStream.range(0, 101).mapToObj(ignored -> item)
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("\"items\":[{" + "\"description\":\"Bread\",\"quantity\":1,\"unitAmount\":1.00}]",
                                        "\"items\":[" + tooManyItems + "]")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor())).param("sort", "secret,desc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor())).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor())).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor())).param("unsupported", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("from", "2026-01-02T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders/{id}", "not-a-uuid").header("Authorization", bearer(fixture.actor())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void inactiveAndMissingReferencesFailWithoutWrites() throws Exception {
        Fixture fixture = fixture();
        int before = jdbc.queryForObject("select count(*) from orders", Integer.class);
        jdbc.update("update merchant_branches set active = false where id = ?", UUID.fromString(fixture.branch()));
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID().toString(), "Bread", 1, "1.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        String noCustomerActor = id(create("/api/v1/actors", """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        mvc.perform(orderPost(noCustomerActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
    }

    @Test
    void duplicateExternalIdentityIsConflict() throws Exception {
        String subject = UUID.randomUUID().toString();
        String body = """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(subject);
        create("/api/v1/actors", body);
        MvcResult conflict = mvc.perform(post("/api/v1/actors").header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andReturn();
        assertThat(conflict.getResponse().getContentAsString())
                .doesNotContain(subject, "uk_actors", "org.postgresql", "SQLException");
    }

    @Test
    void databaseChecksOrderCoordinatesAndMoney() throws Exception {
        Fixture fixture = fixture();
        UUID orderId = UUID.fromString(id(createOrder(fixture.actor(), fixture.branch(), "Bread", 1, "1.00")));
        assertThatThrownBy(() -> jdbc.update("update orders set delivery_latitude = 91 where id = ?", orderId))
                .hasMessageContaining("ck_orders_delivery_latitude");
        assertThatThrownBy(() -> jdbc.update("update orders set total_amount = -1 where id = ?", orderId))
                .hasMessageContaining("ck_orders_total");
        assertThatThrownBy(() -> jdbc.update("update order_items set unit_amount = -1, line_amount = -1 where order_id = ?", orderId))
                .hasMessageContaining("ck_order_items_unit");
    }

    @Test
    void databaseItemFailureReturnsConflictAndRollsBackHttpCommand() throws Exception {
        Fixture fixture = fixture();
        String key = "rollback-" + UUID.randomUUID();
        int before = jdbc.queryForObject("select count(*) from orders", Integer.class);
        int itemsBefore = jdbc.queryForObject("select count(*) from order_items", Integer.class);
        int historyBefore = jdbc.queryForObject("select count(*) from order_lifecycle_history", Integer.class);
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox_events", Integer.class);
        jdbc.execute("alter table order_items add constraint ck_order_items_test_reject check (description_snapshot <> 'FAIL_DB')");
        try {
            mvc.perform(orderPost(fixture.actor(), key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(fixture.branch(), "FAIL_DB", 1, "1.00")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
            assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from order_items", Integer.class)).isEqualTo(itemsBefore);
            assertThat(jdbc.queryForObject("select count(*) from order_lifecycle_history", Integer.class))
                    .isEqualTo(historyBefore);
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(outboxBefore);
            assertThat(jdbc.queryForObject("""
                    select count(*) from command_idempotency
                    where actor_id = ? and operation = 'ORDER_CREATE' and idempotency_key = ?
                    """, Integer.class, UUID.fromString(fixture.actor()), key)).isZero();
        } finally {
            jdbc.execute("alter table order_items drop constraint ck_order_items_test_reject");
        }
    }

    @Test
    void coordinateBoundariesAreAccepted() throws Exception {
        Fixture fixture = fixture();
        String boundary = orderBody(fixture.branch(), "Bread", 1, "1.00")
                .replace("30.050000", "90.000000").replace("31.240000", "-180.000000");
        mvc.perform(orderPost(fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(boundary))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery.latitude").doesNotExist())
                .andExpect(jsonPath("$.delivery.longitude").doesNotExist());
        assertThat(jdbc.queryForObject("select delivery_latitude from orders where customer_id = ?",
                java.math.BigDecimal.class, UUID.fromString(fixture.customer())))
                .isEqualByComparingTo("90.000000");
    }

    @Test
    void paginationIsScopedStableAndUsesBoundedQueries() throws Exception {
        Fixture fixture = fixture();
        for (int i = 0; i < 5; i++) {
            createOrder(fixture.actor(), fixture.branch(), "Item " + i, 1, "1.00");
        }
        jdbc.update("update orders set created_at = ? where customer_id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), UUID.fromString(fixture.customer()));
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        var statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        MvcResult first = mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5)).andReturn();
        long firstCount = statistics.getPrepareStatementCount();
        assertThat(firstCount).isBetween(1L, 5L);
        MvcResult second = mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andReturn();
        List<String> ids = List.of(JsonPath.read(first.getResponse().getContentAsString(), "$.content[0].id"),
                JsonPath.read(first.getResponse().getContentAsString(), "$.content[1].id"),
                JsonPath.read(second.getResponse().getContentAsString(), "$.content[0].id"),
                JsonPath.read(second.getResponse().getContentAsString(), "$.content[1].id"));
        assertThat(new HashSet<>(ids)).hasSize(4);
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("branchId", fixture.branch()).param("status", "CREATED")
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-01-02T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(fixture.actor()))
                        .param("branchId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        List<String> plan = jdbc.queryForList("""
                explain (analyze, buffers)
                select id from orders where customer_id = ? order by created_at desc, id desc limit 2
                """, String.class, UUID.fromString(fixture.customer()));
        assertThat(plan).isNotEmpty();
        String redactedPlan = String.join(" | ", plan).replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>");
        System.out.println("Phase 1 query count: " + firstCount + "; PostgreSQL plan: " + redactedPlan);
    }

    private Fixture fixture() throws Exception {
        String actor = id(create("/api/v1/actors", """
                {"issuer":"http://fleetflow-test-issuer","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        String customer = id(create("/api/v1/customers", """
                {"actorId":"%s","displayName":"Nadia"}
                """.formatted(actor)));
        String merchant = id(create("/api/v1/merchants", "{\"name\":\"Bakery\"}"));
        String branch = id(create("/api/v1/merchant-branches", """
                {"merchantId":"%s","name":"Downtown","pickupLine1":"Old Street","pickupCity":"Cairo",
                 "pickupLatitude":30.044400,"pickupLongitude":31.235700}
                """.formatted(merchant)));
        return new Fixture(actor, customer, branch);
    }

    private MvcResult createOrder(String actor, String branch, String description, int quantity, String amount)
            throws Exception {
        return mvc.perform(orderPost(actor)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(branch, description, quantity, amount)))
                .andExpect(status().isCreated()).andReturn();
    }

    private MockHttpServletRequestBuilder orderPost(String actorId) {
        return orderPost(actorId, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder orderPost(String actorId, String key) {
        return post("/api/v1/orders").header("Authorization", bearer(actorId))
                .header("Idempotency-Key", key);
    }

    private String orderBody(String branch, String description, int quantity, String amount) {
        return """
                {"branchId":"%s","delivery":{"line1":"New Street","city":"Cairo",
                  "latitude":30.050000,"longitude":31.240000},"currency":"EGP",
                 "items":[{"description":"%s","quantity":%d,"unitAmount":%s}]}
                """.formatted(branch, description, quantity, amount);
    }

    private record Fixture(String actor, String customer, String branch) { }

    private MvcResult create(String path, String body) throws Exception {
        MvcResult result = mvc.perform(post(path).header("Authorization", adminBearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        if (path.equals("/api/v1/actors")) {
            jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')",
                    UUID.fromString(id(result)));
        }
        return result;
    }

    private String bearer(String actorId) {
        String subject = jdbc.queryForObject("select subject from actors where id = ?", String.class,
                UUID.fromString(actorId));
        return "Bearer " + TestOidc.token(subject);
    }

    private String adminBearer() { return "Bearer " + TestOidc.token("phase1-admin"); }

    private String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
