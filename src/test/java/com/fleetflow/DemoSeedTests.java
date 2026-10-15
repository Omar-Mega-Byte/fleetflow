package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

@SpringBootTest(properties = {"fleetflow.demo.enabled=true", "fleetflow.rate.demo-write-limit=100",
        "fleetflow.outbox.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles("test")
class DemoSeedTests extends ProfileTestDatabase {
    private static final String RESET = "RESET FLEETFLOW DEMO";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private UUID operatorId;
    private String operatorToken;
    private UUID unrelatedOrder;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        String operatorSubject = "demo-operator-" + UUID.randomUUID();
        operatorId = UUID.randomUUID();
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", operatorId, TestOidc.ISSUER, operatorSubject);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'ADMIN')", operatorId);
        operatorToken = "Bearer " + TestOidc.token(operatorSubject);
        unrelatedOrder = unrelatedOrder();
    }

    @Test
    void deterministicSeedResetAuthorizationAuditTrackingConcurrencyAndInterruption() throws Exception {
        String seedBody = "{\"reason\":\"Start the synthetic release demo\"}";
        mvc.perform(post("/api/v1/demo/seed").contentType(MediaType.APPLICATION_JSON).content(seedBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/demo/seed").header("Authorization", "Bearer " + TestOidc.token("missing"))
                        .header("Idempotency-Key", "seed-unauthorized")
                        .contentType(MediaType.APPLICATION_JSON).content(seedBody))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class))
                .isZero();

        mvc.perform(post("/api/v1/demo/seed").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "seed-one")
                        .contentType(MediaType.APPLICATION_JSON).content(seedBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.result").value("SEEDED"))
                .andExpect(jsonPath("$.orderIds.length()").value(1))
                .andExpect(jsonPath("$.personas.length()").value(5))
                .andExpect(jsonPath("$.trackingBaseline").value("NOT_ACTIVE_YET"));
        String seedReplay = mvc.perform(post("/api/v1/demo/seed").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "seed-one")
                        .contentType(MediaType.APPLICATION_JSON).content(seedBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.parse(seedReplay).read("$.result", String.class)).isEqualTo("SEEDED");
        mvc.perform(post("/api/v1/demo/seed").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "seed-two")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Repeat baseline\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.result").value("ALREADY_SEEDED"));

        UUID customerActor = UUID.nameUUIDFromBytes("fleetflow-public-demo-day1-v1:actor:customer"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String customerToken = "Bearer " + TestOidc.token("fleetflow-demo-customer");
        MvcResult customerOrders = mvc.perform(get("/api/v1/orders").header("Authorization", customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CREATED"))
                .andExpect(jsonPath("$.content[0].branchName").value("Downtown Branch"))
                .andReturn();
        UUID firstOrder = UUID.fromString(JsonPath.parse(customerOrders.getResponse().getContentAsString())
                .read("$.content[0].id", String.class));
        mvc.perform(get("/api/v1/orders/{id}", firstOrder).header("Authorization", customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].description")
                        .value("Synthetic parcel delivery"));
        mvc.perform(get("/api/v1/orders/{id}/tracking", firstOrder).header("Authorization", customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("CREATED"))
                .andExpect(jsonPath("$.deliveryStatus").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist());

        mvc.perform(post("/api/v1/demo/reset").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "reset-wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmation\":\"wrong\",\"reason\":\"Test confirmation guard\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEMO_RESET_CONFIRMATION_REQUIRED"));
        String resetBody = "{\"confirmation\":\"" + RESET + "\",\"reason\":\"Restore known baseline\"}";
        MvcResult reset = mvc.perform(post("/api/v1/demo/reset").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "reset-one")
                        .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.result").value("RESET")).andReturn();
        UUID secondOrder = UUID.fromString(JsonPath.parse(reset.getResponse().getContentAsString())
                .read("$.orderIds[0]", String.class));
        assertThat(secondOrder).isNotEqualTo(firstOrder);
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, firstOrder))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from orders where demo_generation = 1", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from orders where demo_generation = 2", Integer.class))
                .isEqualTo(1);
        mvc.perform(get("/api/v1/orders/{id}", firstOrder).header("Authorization", customerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders").header("Authorization", customerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(secondOrder.toString()));

        String concurrentBody = "{\"confirmation\":\"" + RESET + "\",\"reason\":\"Concurrent reset\"}";
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var action = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                return mvc.perform(post("/api/v1/demo/reset").header("Authorization", operatorToken)
                                .header("Idempotency-Key", "reset-simultaneous")
                                .contentType(MediaType.APPLICATION_JSON).content(concurrentBody))
                        .andReturn();
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult a = first.get(20, TimeUnit.SECONDS);
            MvcResult b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.getResponse().getStatus()).isEqualTo(200);
            assertThat(b.getResponse().getStatus()).isEqualTo(200);
            assertThat(JsonPath.parse(a.getResponse().getContentAsString()).read("$.generation", Integer.class))
                    .isEqualTo(JsonPath.parse(b.getResponse().getContentAsString()).read("$.generation", Integer.class));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_audit "
                + "where actor_id = ? and action = 'RESET' and idempotency_key = 'reset-simultaneous'",
                Integer.class, operatorId)).isEqualTo(1);

        long beforeInterrupted = jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class);
        jdbc.execute("""
                create function reject_test_demo_generation() returns trigger language plpgsql as $$
                begin
                  if new.demo_generation = %d then
                    raise exception 'synthetic interruption test';
                  end if;
                  return new;
                end;
                $$
                """.formatted(beforeInterrupted + 1));
        jdbc.execute("create trigger reject_test_demo_generation before insert on orders "
                + "for each row execute function reject_test_demo_generation()");
        String interruptedBody = "{\"confirmation\":\"" + RESET + "\",\"reason\":\"Verify rollback after interruption\"}";
        mvc.perform(post("/api/v1/demo/reset").header("Authorization", operatorToken)
                        .header("Idempotency-Key", "reset-interrupted")
                        .contentType(MediaType.APPLICATION_JSON).content(interruptedBody))
                .andExpect(status().is5xxServerError());
        jdbc.execute("drop trigger reject_test_demo_generation on orders");
        jdbc.execute("drop function reject_test_demo_generation()");
        assertThat(jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class))
                .isEqualTo(beforeInterrupted);
        assertThat(jdbc.queryForObject("select count(*) from orders where demo_generation = ?",
                Integer.class, beforeInterrupted + 1)).isZero();
        assertThat(jdbc.queryForObject("select result_code from synthetic_demo_failure_audit "
                        + "where actor_id = ? and action = 'RESET' and idempotency_key = 'reset-interrupted'",
                String.class, operatorId)).isEqualTo("ACTION_FAILED");
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, unrelatedOrder))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from customers where actor_id = ?", Integer.class,
                customerActor)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_audit where actor_id = ?",
                Integer.class, operatorId)).isGreaterThanOrEqualTo(4);
    }

    private UUID unrelatedOrder() {
        UUID actor = UUID.randomUUID(), customer = UUID.randomUUID(), merchant = UUID.randomUUID();
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID();
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, "unrelated-" + actor);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'CUSTOMER')", actor);
        jdbc.update("insert into customers(id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Unrelated customer', now(), now())", customer, actor);
        jdbc.update("insert into merchants(id, name, created_at, updated_at) values (?, 'Unrelated merchant', now(), now())",
                merchant);
        jdbc.update("""
                insert into merchant_branches(id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, created_at, updated_at)
                values (?, ?, 'Unrelated branch', 'Other Street', 'Cairo', 30, 31, now(), now())
                """, branch, merchant);
        jdbc.update("""
                insert into orders(id, customer_id, merchant_branch_id, status, version,
                  customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, delivery_line1, delivery_city, delivery_latitude,
                  delivery_longitude, currency, total_amount, created_at, updated_at)
                values (?, ?, ?, 'CREATED', 0, 'Unrelated customer', 'Unrelated merchant', 'Unrelated branch',
                  'Other Street', 'Cairo', 30, 31, 'Other Road', 'Cairo', 30.1, 31.1, 'EGP', 50.00, now(), now())
                """, order, customer, branch);
        return order;
    }
}
