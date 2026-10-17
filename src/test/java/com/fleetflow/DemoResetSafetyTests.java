package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"fleetflow.demo.enabled=true", "fleetflow.rate.demo-write-limit=5",
        "fleetflow.outbox.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false", "fleetflow.recovery.enabled=false"})
@ActiveProfiles("test")
class DemoResetSafetyTests {
    // A separate disposable database ensures cleanup cannot touch other suites or developer data.
    private static final PostgreSQLContainer database = PostgisTestDatabase.create();
    static { database.start(); }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", database::getJdbcUrl);
        registry.add("DB_USER", database::getUsername);
        registry.add("DB_PASSWORD", database::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private UUID operator;
    private String token;
    private static final String SEED = "{\"reason\":\"Synthetic test setup\"}";
    private static final String RESET = "{\"confirmation\":\"RESET FLEETFLOW DEMO\",\"reason\":\"Restore demo baseline\"}";

    @BeforeEach
    void setup() {
        jdbc.execute("truncate actors, merchants, synthetic_demo_state, synthetic_demo_ownership cascade");
        jdbc.update("insert into synthetic_demo_state(id, generation) values(true, 0)");
        operator = UUID.randomUUID();
        String subject = "reset-safety-" + operator;
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", operator, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'ADMIN')", operator);
        token = "Bearer " + TestOidc.token(subject);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void syntheticRouteIsReadOnlyAndLimitedToTheSeededActiveDriver() throws Exception {
        action("seed", "route-seed", SEED).andExpect(status().isOk());
        String path = "/api/v1/demo/driver-route";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer " + TestOidc.token("fleetflow-demo-driver")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.points.length()").value(5))
                .andExpect(jsonPath("$.points[0].latitude").value(30.0444))
                .andExpect(jsonPath("$.points[4].longitude").value(31.2422));
        UUID other = UUID.randomUUID();
        String subject = "other-driver-" + other;
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) values (?, ?, ?, true, now(), now())",
                other, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'DRIVER')", other);
        mvc.perform(get(path).header("Authorization", "Bearer " + TestOidc.token(subject))).andExpect(status().isForbidden());
        jdbc.update("update actors set active = false where id = ?", demoId("actor:driver"));
        mvc.perform(get(path).header("Authorization", "Bearer " + TestOidc.token("fleetflow-demo-driver")))
                .andExpect(status().isForbidden());
        assertThat(generation()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?", String.class, demoId("driver")))
                .isEqualTo("OFFLINE");
    }

    @Test
    void busyDriverPreventsResetAndRecordsFailureWithoutArchivingTheOrder() throws Exception {
        action("seed", "seed", SEED).andExpect(status().isOk());
        UUID driver = demoId("driver");
        jdbc.update("update drivers set operational_status = 'RESERVED' where id = ?", driver);
        action("reset", "busy", RESET).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMO_WORK_ACTIVE"));
        assertThat(generation()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, demoId("order:1")))
                .isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select result_code from synthetic_demo_failure_audit where idempotency_key = 'busy'",
                String.class)).isEqualTo("DEMO_WORK_ACTIVE");
        jdbc.update("update drivers set operational_status = 'AVAILABLE' where id = ?", driver);
        action("reset", "busy", RESET).andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(2));
        assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?", String.class, driver))
                .isEqualTo("OFFLINE");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class,
                demoId("order:1"))).isEqualTo(1);
    }

    @Test
    void interruptedInitialSeedRollsBackOwnershipPersonasAndAuditThenCanRepeatTheSameKey() throws Exception {
        jdbc.execute("create function reject_demo_seed() returns trigger language plpgsql as $$ "
                + "begin raise exception 'synthetic failure'; end; $$");
        jdbc.execute("create trigger reject_demo_seed before insert on orders for each row execute function reject_demo_seed()");
        try {
            action("seed", "interrupted", SEED).andExpect(status().is5xxServerError());
            assertThat(generation()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_ownership", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from actors", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_audit", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_failure_audit", Integer.class)).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger reject_demo_seed on orders");
            jdbc.execute("drop function reject_demo_seed()");
        }
        action("seed", "interrupted", SEED).andExpect(status().isOk()).andExpect(jsonPath("$.generation").value(1));
    }

    @Test
    void distinctConcurrentResetsSerializeAndLeaveOneActiveBaseline() throws Exception {
        action("seed", "seed", SEED).andExpect(status().isOk());
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return action("reset", "first", RESET).andReturn(); });
            var second = pool.submit(() -> { start.await(); return action("reset", "second", RESET).andReturn(); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(second.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(generation()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from orders where demo_generation is not null and status = 'CREATED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_audit where action = 'RESET'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void existingUnownedIdentityIsNeverAdoptedOrOverwritten() throws Exception {
        UUID collision = demoId("actor:customer");
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, 'unrelated-subject', true, now(), now())", collision, TestOidc.ISSUER);
        action("seed", "collision", SEED).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMO_RESOURCE_COLLISION"));
        assertThat(generation()).isZero();
        assertThat(jdbc.queryForObject("select subject from actors where id = ?", String.class, collision))
                .isEqualTo("unrelated-subject");
        assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_ownership", Integer.class)).isZero();
    }

    @Test
    void resetRestoresOwnedZoneAndPolicyWithoutReusingOptimisticVersions() throws Exception {
        action("seed", "seed", SEED).andExpect(status().isOk());
        UUID branch = demoId("branch"), zone = demoId("zone");
        String merchant = "Bearer " + TestOidc.token("fleetflow-demo-merchant");
        String changed = """
                {"name":"Edited zone","active":true,"expectedVersion":0,
                 "geometry":{"type":"Polygon","coordinates":[[[31,30],[31.1,30],[31.1,30.1],[31,30.1],[31,30]]]}}
                """;
        mvc.perform(put("/api/v1/merchant-branches/{branch}/zones/{zone}", branch, zone)
                .header("Authorization", merchant).contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        long policy = jdbc.queryForObject("select zone_policy_version from merchant_branches where id = ?", Long.class, branch);
        mvc.perform(put("/api/v1/merchant-branches/{branch}/zone-policy", branch).header("Authorization", merchant)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENFORCED\",\"expectedVersion\":" + policy + "}"))
                .andExpect(status().isOk());
        action("reset", "restore-zone", RESET).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select version from delivery_zones where id = ?", Long.class, zone)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select name from delivery_zones where id = ?", String.class, zone)).isEqualTo("Central Demo Zone");
        assertThat(jdbc.queryForObject("select active from delivery_zones where id = ?", Boolean.class, zone)).isTrue();
        long restored = jdbc.queryForObject("select zone_policy_version from merchant_branches where id = ?", Long.class, branch);
        assertThat(restored).isGreaterThan(policy);
        assertThat(jdbc.queryForObject("select zone_policy_version_at_create from orders where id = ?", Long.class, demoId("order:2")))
                .isEqualTo(restored);
        assertThat(jdbc.queryForObject("select serviceability_mode from merchant_branches where id = ?", String.class, branch))
                .isEqualTo("UNRESTRICTED");
        assertThat(jdbc.queryForObject("select count(*) from zone_policy_history where branch_id = ? and actor_id = ?",
                Integer.class, branch, operator)).isEqualTo(3);
        mvc.perform(put("/api/v1/merchant-branches/{branch}/zones/{zone}", branch, zone)
                .header("Authorization", merchant).contentType(MediaType.APPLICATION_JSON)
                .content(changed.replace("\"expectedVersion\":0", "\"expectedVersion\":1")))
                .andExpect(status().isConflict());
    }

    @Test
    void mutationsRequireCurrentAdminRoleAndEnforceTheFivePerMinuteBudget() throws Exception {
        jdbc.update("delete from actor_roles where actor_id = ?", operator);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'CUSTOMER')", operator);
        mvc.perform(post("/api/v1/demo/reset").header("Authorization", token).header("Idempotency-Key", "denied")
                .contentType(MediaType.APPLICATION_JSON).content(RESET)).andExpect(status().isForbidden());
        assertThat(generation()).isZero();
        jdbc.update("delete from actor_roles where actor_id = ?", operator);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'ADMIN')", operator);
        for (int index = 0; index < 5; index++) action("seed", "seed-" + index, SEED).andExpect(status().isOk());
        action("seed", "limited", SEED).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(generation()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from synthetic_demo_audit", Integer.class)).isEqualTo(5);
    }

    private org.springframework.test.web.servlet.ResultActions action(String name, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/demo/" + name).header("Authorization", token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private long generation() {
        return jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class);
    }
    private static UUID demoId(String name) {
        return UUID.nameUUIDFromBytes(("fleetflow-public-demo-day1-v1:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
