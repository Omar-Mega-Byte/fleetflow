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
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine3.24");

    static { postgres.start(); }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orderService;
    @Autowired EntityManagerFactory entityManagerFactory;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new CorrelationIdFilter()).build();
    }

    @Test
    void onboardingOrderIntakeDetailAndScopedList() throws Exception {
        String actor = id(create("/api/v1/actors", """
                {"issuer":"local-fixture","subject":"%s"}
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
                {"issuer":"local-fixture","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        create("/api/v1/customers", "{\"actorId\":\"%s\",\"displayName\":\"Other\"}".formatted(otherActor));
        create("/api/v1/merchant-staff-memberships", "{\"actorId\":\"%s\",\"branchId\":\"%s\"}"
                .formatted(otherActor, branch));
        String driverActor = id(create("/api/v1/actors", """
                {"issuer":"local-fixture","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        String driver = id(create("/api/v1/drivers", "{\"actorId\":\"%s\"}".formatted(driverActor)));
        String vehicle = id(create("/api/v1/vehicles", """
                {"driverId":"%s","type":"BIKE","capacity":1}
                """.formatted(driver)));
        mvc.perform(get("/api/v1/vehicles/{id}", vehicle)).andExpect(status().isOk())
                .andExpect(jsonPath("$.driverId").value(driver));
        String orderBody = """
                {"branchId":"%s","delivery":{"line1":"New Street","city":"Cairo",
                  "latitude":30.050000,"longitude":31.240000},"currency":"EGP",
                 "items":[{"description":"Bread","quantity":2,"unitAmount":12.50},
                          {"description":"Tea","quantity":1,"unitAmount":2.00}]}
                """.formatted(branch);

        MvcResult created = mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(27.0))
                .andReturn();
        String orderId = id(created);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-Dev-Actor-Id", actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].lineAmount").value(25.0))
                .andExpect(jsonPath("$.items[1].description").value("Tea"))
                .andExpect(jsonPath("$.pickup.line1").value("Old Street"));
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", actor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId));
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", otherActor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-Dev-Actor-Id", otherActor))
                .andExpect(status().isNotFound());
        assertThat(customer).isNotBlank();
    }

    @Test
    void validationUsesStableErrorAndCorrelationId() throws Exception {
        mvc.perform(post("/api/v1/actors").header("X-Correlation-ID", "phase1-test")
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
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-Dev-Actor-Id", fixture.actor()))
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
        assertThatThrownBy(() -> orderService.create(UUID.fromString(fixture.actor()), invalid))
                .hasMessageContaining("ck_order_items_quantity");
        assertThat(jdbc.queryForObject("select count(*) from orders where customer_id = ?", Integer.class,
                UUID.fromString(fixture.customer()))).isEqualTo(before);
    }

    @Test
    void invalidOrderAndListInputsReturnBadRequest() throws Exception {
        Fixture fixture = fixture();
        String body = orderBody(fixture.branch(), "Bread", 0, "12.50");
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(fixture.branch(), "Bread", 1, "12.345")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("\"currency\":\"EGP\"", "\"currency\":\"ZZZ\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 2147483647, "9999999999999999.99")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("30.050000", "91.000000")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("31.240000", "NaN")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("31.240000", "Infinity")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")
                                .replace("\"items\":[{\"description\":\"Bread\",\"quantity\":1,\"unitAmount\":1.00}]",
                                        "\"items\":[]")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor()).param("sort", "secret,desc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor()).param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor()).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor()).param("unsupported", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .param("from", "2026-01-02T00:00:00Z").param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders/{id}", "not-a-uuid").header("X-Dev-Actor-Id", fixture.actor()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void inactiveAndMissingReferencesFailWithoutWrites() throws Exception {
        Fixture fixture = fixture();
        int before = jdbc.queryForObject("select count(*) from orders", Integer.class);
        jdbc.update("update merchant_branches set active = false where id = ?", UUID.fromString(fixture.branch()));
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BRANCH_INACTIVE"));
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(UUID.randomUUID().toString(), "Bread", 1, "1.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BRANCH_NOT_FOUND"));
        String noCustomerActor = id(create("/api/v1/actors", """
                {"issuer":"local-fixture","subject":"%s"}
                """.formatted(UUID.randomUUID())));
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", noCustomerActor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(fixture.branch(), "Bread", 1, "1.00")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
    }

    @Test
    void duplicateExternalIdentityIsConflict() throws Exception {
        String subject = UUID.randomUUID().toString();
        String body = """
                {"issuer":"local-fixture","subject":"%s"}
                """.formatted(subject);
        create("/api/v1/actors", body);
        MvcResult conflict = mvc.perform(post("/api/v1/actors")
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
    void coordinateBoundariesAreAccepted() throws Exception {
        Fixture fixture = fixture();
        String boundary = orderBody(fixture.branch(), "Bread", 1, "1.00")
                .replace("30.050000", "90.000000").replace("31.240000", "-180.000000");
        mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .contentType(MediaType.APPLICATION_JSON).content(boundary))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.delivery.latitude").value(90.0))
                .andExpect(jsonPath("$.delivery.longitude").value(-180.0));
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
        MvcResult first = mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .param("size", "2").param("page", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5)).andReturn();
        long firstCount = statistics.getPrepareStatementCount();
        assertThat(firstCount).isBetween(1L, 5L);
        MvcResult second = mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andReturn();
        List<String> ids = List.of(JsonPath.read(first.getResponse().getContentAsString(), "$.content[0].id"),
                JsonPath.read(first.getResponse().getContentAsString(), "$.content[1].id"),
                JsonPath.read(second.getResponse().getContentAsString(), "$.content[0].id"),
                JsonPath.read(second.getResponse().getContentAsString(), "$.content[1].id"));
        assertThat(new HashSet<>(ids)).hasSize(4);
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
                        .param("branchId", fixture.branch()).param("status", "CREATED")
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-01-02T00:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
        mvc.perform(get("/api/v1/orders").header("X-Dev-Actor-Id", fixture.actor())
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
                {"issuer":"local-fixture","subject":"%s"}
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
        return mvc.perform(post("/api/v1/orders").header("X-Dev-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(orderBody(branch, description, quantity, amount)))
                .andExpect(status().isCreated()).andReturn();
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
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    private String id(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }
}
