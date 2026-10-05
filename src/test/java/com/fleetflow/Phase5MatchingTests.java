package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fleetflow.location.LocationFix;
import com.fleetflow.location.LocationService;
import com.fleetflow.location.DriverEligibility;
import com.fleetflow.location.RedisLocationStore;
import com.fleetflow.location.Wgs84Distance;
import com.fleetflow.dispatch.MatchingProperties;
import com.fleetflow.dispatch.MatchingScore;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase5MatchingTests extends ProfileTestDatabase {
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.7-alpine")
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort());
    static { redis.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired RedisLocationStore store;
    @Autowired LocationService locations;
    @Autowired DriverEligibility eligibility;
    @Autowired MatchingProperties matching;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @AfterEach
    void restoreRedis() throws Exception {
        if (!DockerClientFactory.instance().client().inspectContainerCmd(redis.getContainerId())
                .exec().getState().getRunning()) {
            DockerClientFactory.instance().client().startContainerCmd(redis.getContainerId()).exec();
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < end) {
                try { redisTemplate.opsForValue().get("ready"); return; }
                catch (Exception ignored) { Thread.sleep(50); }
            }
        }
    }

    @Test
    @Order(0)
    void missingAndNonReadyOrdersNeverStartSearchOrWriteAnAttempt() throws Exception {
        Fixture f = fixture();
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", UUID.randomUUID())
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dispatch/orders/{id}/attempts", UUID.randomUUID())
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from dispatch_attempts where order_id = ?",
                Integer.class, f.order)).isZero();
    }

    @Test
    @Order(1)
    void merchantGuardsAndSignedDispatcherRankedFlowPreserveCustomerPrivacy() throws Exception {
        Fixture f = fixture();
        Actor other = actor("MERCHANT");
        mvc.perform(post("/api/v1/merchant/orders/{id}/confirm", f.order).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/merchant/orders/{id}/ready", f.order).header("Authorization", bearer(f.merchant)))
                .andExpect(status().isConflict());
        jdbc.update("update merchant_staff_memberships set active = false where actor_id = ?", f.merchant.id);
        mvc.perform(post("/api/v1/merchant/orders/{id}/confirm", f.order).header("Authorization", bearer(f.merchant)))
                .andExpect(status().isNotFound());
        jdbc.update("update merchant_staff_memberships set active = true where actor_id = ?", f.merchant.id);
        ready(f);
        mvc.perform(post("/api/v1/merchant/orders/{id}/ready", f.order).header("Authorization", bearer(f.merchant)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, f.order)).isEqualTo(3);

        Actor driverActor = actor("DRIVER");
        UUID driver = driver(driverActor);
        fix(driver, Instant.now(), 30.001, 31.001);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        for (Actor denied : List.of(f.customer, f.merchant, driverActor)) {
            mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                    .header("Authorization", bearer(denied))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/dispatch/orders/{id}/attempts", f.order)
                    .header("Authorization", bearer(denied))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("RANKED"));
        mvc.perform(get("/api/v1/dispatch/orders/{id}/attempts", f.order)
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].candidates[0].driverId")
                        .value(driver.toString()))
                .andExpect(jsonPath("$.content[0].candidates[0].rank").value(1))
                .andExpect(jsonPath("$.content[0].candidates[0].reason").value("ELIGIBLE"));
        var saved = jdbc.queryForMap("""
                select c.distance_meters, c.gps_age_millis, c.distance_factor,
                       c.freshness_factor, c.score
                from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ?
                """, f.order);
        var reproduced = MatchingScore.score((java.math.BigDecimal) saved.get("distance_meters"),
                (Long) saved.get("gps_age_millis"), matching.radiusMeters(), 45_000, matching);
        assertThat(saved.get("distance_factor")).isEqualTo(reproduced.distanceFactor());
        assertThat(saved.get("freshness_factor")).isEqualTo(reproduced.freshnessFactor());
        assertThat(saved.get("score")).isEqualTo(reproduced.score());
        mvc.perform(get("/api/v1/orders/{id}", f.order).header("Authorization", bearer(f.customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPATCHING"))
                .andExpect(jsonPath("$.candidates").doesNotExist())
                .andExpect(jsonPath("$.pickup.latitude").doesNotExist())
                .andExpect(jsonPath("$.delivery.longitude").doesNotExist());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from dispatch_attempts where order_id = ?",
                Integer.class, f.order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, f.order)).isEqualTo(4);
    }

    @Test
    @Order(2)
    void emptyAndRejectedPoolsCommitDifferentOutcomesWithNoScoresForRejected() throws Exception {
        Fixture empty = fixture(); ready(empty);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", empty.order)
                .header("Authorization", bearer(empty.dispatcher)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("NO_NEARBY_CANDIDATE"));
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, empty.order))
                .isEqualTo("DISPATCH_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, empty.order)).isEqualTo(5);

        Fixture rejected = fixture(); ready(rejected);
        Actor actor = actor("DRIVER"); UUID driver = driver(actor);
        fix(driver, Instant.now(), 30, 31);
        jdbc.update("update drivers set status = 'PAUSED' where id = ?", driver);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", rejected.order)
                .header("Authorization", bearer(rejected.dispatcher)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("NO_ELIGIBLE_CANDIDATE"));
        mvc.perform(get("/api/v1/dispatch/orders/{id}/attempts", rejected.order)
                .header("Authorization", bearer(rejected.dispatcher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].candidates[0].reason")
                        .value("DRIVER_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.content[0].candidates[0].score").isEmpty())
                .andExpect(jsonPath("$.content[0].candidates[0].rank").isEmpty());
    }

    @Test
    @Order(3)
    void durableAndRedisReasonsHavePrecedenceAndNoRejectedRanking() throws Exception {
        Fixture f = fixture(); ready(f);
        Actor noRoleActor = actor("DRIVER"); UUID noRole = driver(noRoleActor);
        Actor inactiveActor = actor("DRIVER"); UUID inactive = driver(inactiveActor);
        Actor noVehicleActor = actor("DRIVER"); UUID noVehicle = driver(noVehicleActor);
        Actor staleGpsActor = actor("DRIVER"); UUID staleGps = driver(staleGpsActor);
        Actor staleBeatActor = actor("DRIVER"); UUID staleBeat = driver(staleBeatActor);
        Actor offlineActor = actor("DRIVER"); UUID offline = driver(offlineActor);
        Actor suspendedActor = actor("DRIVER"); UUID suspended = driver(suspendedActor);
        Actor driverInactiveActor = actor("DRIVER"); UUID driverInactive = driver(driverInactiveActor);
        UUID orphan = UUID.randomUUID();
        for (UUID id : List.of(noRole, inactive, noVehicle, staleGps, staleBeat,
                offline, suspended, driverInactive, orphan)) {
            fix(id, Instant.now(), 30.001, 31.001);
        }
        jdbc.update("delete from actor_roles where actor_id = ?", noRoleActor.id);
        jdbc.update("update actors set active = false where id = ?", inactiveActor.id);
        jdbc.update("update vehicles set active = false where driver_id = ?", noVehicle);
        jdbc.update("update drivers set status = 'OFFLINE' where id = ?", offline);
        jdbc.update("update drivers set suspended = true where id = ?", suspended);
        jdbc.update("update drivers set active = false where id = ?", driverInactive);
        redisTemplate.opsForHash().put("fleetflow:driver:location:" + staleGps,
                "reportedSeconds", Long.toString(Instant.now().minusSeconds(60).getEpochSecond()));
        redisTemplate.delete("fleetflow:driver:heartbeat:" + staleBeat);
        jdbc.update("update drivers set status = 'PAUSED' where id = ?", staleBeat);
        redisTemplate.opsForHash().put("fleetflow:driver:location:" + staleBeat,
                "reportedSeconds", Long.toString(Instant.now().minusSeconds(60).getEpochSecond()));
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outcome").value("NO_ELIGIBLE_CANDIDATE"));
        var reasons = jdbc.queryForList("""
                select c.reason from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ? order by c.reason
                """, String.class, f.order);
        assertThat(reasons).containsExactlyInAnyOrder("ROLE_MISSING", "ACTOR_INACTIVE", "VEHICLE_INACTIVE",
                "STALE_GPS", "STALE_HEARTBEAT", "DRIVER_NOT_AVAILABLE",
                "DRIVER_NOT_AVAILABLE", "DRIVER_NOT_AVAILABLE", "DRIVER_NOT_AVAILABLE");
        assertThat(jdbc.queryForObject("""
                select count(*) from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ? and (c.score is not null or c.rank is not null)
                """, Integer.class, f.order)).isZero();
    }

    @Test
    @Order(6)
    void exactWgs84RadiusAndBatchRecheckRejectChangedState() {
        Actor insideActor = actor("DRIVER"); UUID inside = driver(insideActor);
        Actor outsideActor = actor("DRIVER"); UUID outside = driver(outsideActor);
        Actor missingActor = actor("DRIVER"); UUID missing = driver(missingActor);
        fix(inside, Instant.now(), 30, longitudeAtDistance(4990));
        fix(outside, Instant.now(), 30, longitudeAtDistance(5010));
        fix(missing, Instant.now(), 30, 31.001);
        redisTemplate.delete("fleetflow:driver:location:" + missing);
        var hits = locations.inspect(30, 31, 5000, 50, Instant.now());
        assertThat(hits).extracting(LocationService.InspectedDriver::driverId)
                .contains(inside, outside);
        assertThat(hits.stream().filter(h -> h.driverId().equals(outside)).findFirst().orElseThrow()
                .redisReason()).isEqualTo("OUTSIDE_RADIUS");
        assertThat(hits.stream().filter(h -> h.driverId().equals(missing)).findFirst().orElseThrow()
                .redisReason()).isEqualTo("STALE_GPS");
        jdbc.update("update drivers set status = 'PAUSED' where id = ?", inside);
        assertThat(eligibility.matchingReasons(List.of(inside)).get(inside)).isEqualTo("DRIVER_NOT_AVAILABLE");
    }

    private double longitudeAtDistance(double metres) {
        double low = 31, high = 31.1;
        for (int index = 0; index < 50; index++) {
            double middle = (low + high) / 2;
            if (Wgs84Distance.metres(30, 31, 30, middle) < metres) low = middle;
            else high = middle;
        }
        return (low + high) / 2;
    }

    @Test
    @Order(7)
    void candidateLimitKeepsOverflowUnrankedAndReadLimitIsValidated() throws Exception {
        Fixture f = fixture(); ready(f);
        Instant now = Instant.now();
        for (int index = 0; index < 52; index++) {
            Actor actor = actor("DRIVER"); UUID driver = driver(actor);
            fix(driver, now, 30.001, 31.001);
        }
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("""
                select count(*) from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ?
                """, Integer.class, f.order)).isEqualTo(52);
        assertThat(jdbc.queryForObject("""
                select count(*) from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ? and c.reason = 'ELIGIBLE' and c.rank is not null
                """, Integer.class, f.order)).isEqualTo(50);
        assertThat(jdbc.queryForObject("""
                select count(*) from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where a.order_id = ? and c.reason = 'CANDIDATE_LIMIT' and c.score is null and c.rank is null
                """, Integer.class, f.order)).isEqualTo(2);
        mvc.perform(get("/api/v1/dispatch/orders/{id}/attempts", f.order).param("limit", "21")
                .header("Authorization", bearer(f.dispatcher))).andExpect(status().isBadRequest());
    }

    @Test
    @Order(9)
    void redisOutageLeavesReadyOrderAndAttemptTableUntouched() throws Exception {
        Fixture f = fixture(); ready(f);
        DockerClientFactory.instance().client().stopContainerCmd(redis.getContainerId()).exec();
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher)).header("X-Correlation-ID", "matching-outage"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("matching-outage"));
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, f.order))
                .isEqualTo("READY_FOR_PICKUP");
        assertThat(jdbc.queryForObject("select count(*) from dispatch_attempts where order_id = ?",
                Integer.class, f.order)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, f.order)).isEqualTo(3);
    }

    @Test
    @Order(8)
    void concurrentStartsHaveOnePostgresWinnerAndOneConflict() throws Exception {
        Fixture f = fixture(); ready(f);
        var first = CompletableFuture.supplyAsync(() -> matchStatus(f));
        var second = CompletableFuture.supplyAsync(() -> matchStatus(f));
        assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from dispatch_attempts where order_id = ?",
                Integer.class, f.order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ? "
                + "and from_status = 'READY_FOR_PICKUP'", Integer.class, f.order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from dispatch_candidates c "
                + "join dispatch_attempts a on a.id = c.attempt_id where a.order_id = ?",
                Integer.class, f.order)).isZero();
    }

    @Test
    @Order(4)
    void failedHistoryAttemptOrCandidateInsertRollsBackEntireClaim() throws Exception {
        Actor driverActor = actor("DRIVER"); UUID driver = driver(driverActor);
        fix(driver, Instant.now(), 30.001, 31.001);
        for (String table : List.of("order_status_history", "dispatch_attempts", "dispatch_candidates")) {
            Fixture f = fixture(); ready(f);
            jdbc.execute("""
                    create or replace function test_phase5_fail_insert() returns trigger language plpgsql as $$
                    begin raise exception 'injected write failure' using errcode = '23514'; end; $$
                    """);
            jdbc.execute("create trigger test_phase5_insert before insert on " + table
                    + " for each row execute function test_phase5_fail_insert()");
            try {
                mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                        .header("Authorization", bearer(f.dispatcher)))
                        .andExpect(status().isConflict());
                assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, f.order))
                        .isEqualTo("READY_FOR_PICKUP");
                assertThat(jdbc.queryForObject("select count(*) from dispatch_attempts where order_id = ?",
                        Integer.class, f.order)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                        Integer.class, f.order)).isEqualTo(3);
            } finally {
                jdbc.execute("drop trigger test_phase5_insert on " + table);
                jdbc.execute("drop function test_phase5_fail_insert()");
            }
        }
    }

    @Test
    @Order(5)
    void snapshotConstraintsAndImmutableRowsAreEnforcedByPostgres() throws Exception {
        Fixture f = fixture(); ready(f);
        Actor driverActor = actor("DRIVER"); UUID driver = driver(driverActor);
        fix(driver, Instant.now(), 30.001, 31.001);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher))).andExpect(status().isCreated());
        UUID attempt = jdbc.queryForObject("select id from dispatch_attempts where order_id = ?", UUID.class, f.order);
        assertThatThrownBy(() -> jdbc.update("update dispatch_attempts set outcome = 'NO_ELIGIBLE_CANDIDATE' where id = ?",
                attempt)).hasMessageContaining("History is append only");
        assertThatThrownBy(() -> jdbc.update("delete from dispatch_candidates where attempt_id = ?", attempt))
                .hasMessageContaining("History is append only");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_candidates "
                + "(id, attempt_id, driver_id, reason, distance_meters, gps_age_millis, "
                + "distance_factor, freshness_factor, score, rank, straight_line_seconds) "
                + "select ?, attempt_id, driver_id, reason, distance_meters, gps_age_millis, "
                + "distance_factor, freshness_factor, score, rank, straight_line_seconds "
                + "from dispatch_candidates where attempt_id = ?", UUID.randomUUID(), attempt))
                .hasMessageContaining("uk_dispatch_candidate_driver");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_candidates "
                + "(id, attempt_id, driver_id, reason, distance_meters, gps_age_millis, "
                + "distance_factor, freshness_factor, score, rank, straight_line_seconds) "
                + "select ?, attempt_id, ?, reason, distance_meters, gps_age_millis, "
                + "distance_factor, freshness_factor, score, rank, straight_line_seconds "
                + "from dispatch_candidates where attempt_id = ?", UUID.randomUUID(), UUID.randomUUID(), attempt))
                .hasMessageContaining("uk_dispatch_candidate_rank");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_attempts "
                + "(id, order_id, sequence, started_at, completed_at, decision_at, outcome, config_version, "
                + "radius_meters, candidate_limit, geo_cap, max_gps_age_millis, heartbeat_age_millis, "
                + "distance_weight, freshness_weight, estimate_metres_per_second) "
                + "select ?, order_id, sequence, started_at, completed_at, decision_at, outcome, config_version, "
                + "radius_meters, candidate_limit, geo_cap, max_gps_age_millis, heartbeat_age_millis, "
                + "distance_weight, freshness_weight, estimate_metres_per_second "
                + "from dispatch_attempts where id = ?", UUID.randomUUID(), attempt))
                .hasMessageContaining("uk_dispatch_attempt_order_sequence");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_attempts "
                + "(id, order_id, sequence, started_at, completed_at, decision_at, outcome, config_version, "
                + "radius_meters, candidate_limit, geo_cap, max_gps_age_millis, heartbeat_age_millis, "
                + "distance_weight, freshness_weight, estimate_metres_per_second) "
                + "select ?, order_id, sequence + 1, started_at, completed_at, decision_at, 'OFFERED', "
                + "config_version, radius_meters, candidate_limit, geo_cap, max_gps_age_millis, "
                + "heartbeat_age_millis, distance_weight, freshness_weight, estimate_metres_per_second "
                + "from dispatch_attempts where id = ?", UUID.randomUUID(), attempt))
                .hasMessageContaining("ck_dispatch_attempt_outcome");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_candidates "
                + "(id, attempt_id, driver_id, reason, distance_meters, gps_age_millis) "
                + "values (?, ?, ?, 'ELIGIBLE', 100, 100)", UUID.randomUUID(), attempt, UUID.randomUUID()))
                .hasMessageContaining("ck_dispatch_candidate_ranked");
        assertThatThrownBy(() -> jdbc.update("insert into dispatch_candidates "
                + "(id, attempt_id, driver_id, reason, distance_meters, gps_age_millis, "
                + "distance_factor, freshness_factor, score, straight_line_seconds) "
                + "values (?, ?, ?, 'ELIGIBLE', 100, 100, 0.5, 0.5, 0.5, 12)",
                UUID.randomUUID(), attempt, UUID.randomUUID()))
                .hasMessageContaining("ck_dispatch_candidate_ranked");
    }

    private int matchStatus(Fixture f) {
        try { return mvc.perform(post("/api/v1/dispatch/orders/{id}/match", f.order)
                .header("Authorization", bearer(f.dispatcher))).andReturn().getResponse().getStatus(); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private void ready(Fixture f) throws Exception {
        for (String action : List.of("confirm", "prepare", "ready")) {
            mvc.perform(post("/api/v1/merchant/orders/{id}/" + action, f.order)
                    .header("Authorization", bearer(f.merchant))).andExpect(status().isNoContent());
        }
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER");
        Actor merchant = actor("MERCHANT");
        Actor dispatcher = actor("DISPATCHER");
        UUID customerId = UUID.randomUUID(); UUID merchantId = UUID.randomUUID();
        UUID branch = UUID.randomUUID(); UUID order = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", customerId, customer.id);
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchantId);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30.000000, 31.000000, true, now(), now())
                """, branch, merchantId);
        jdbc.update("insert into merchant_staff_memberships (id, actor_id, branch_id, active, created_at) "
                + "values (?, ?, ?, true, now())", UUID.randomUUID(), merchant.id, branch);
        jdbc.update("""
                insert into orders (id, customer_id, merchant_branch_id, status, customer_name_snapshot,
                  merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city, pickup_latitude,
                  pickup_longitude, delivery_line1, delivery_city, delivery_latitude, delivery_longitude,
                  currency, total_amount, created_at, updated_at)
                values (?, ?, ?, 'CREATED', 'Customer', 'Merchant', 'Branch', 'Street', 'Cairo',
                  30.000000, 31.000000, 'Road', 'Cairo', 30.000000, 31.000000, 'EGP', 1.00, now(), now())
                """, order, customerId, branch);
        return new Fixture(customer, merchant, dispatcher, order);
    }

    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id, TestOidc.ISSUER, actor.subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id, role);
        return actor;
    }

    private UUID driver(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, active, suspended, status, created_at, updated_at) "
                + "values (?, ?, true, false, 'AVAILABLE', now(), now())", id, actor.id);
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                + "values (?, ?, 'BIKE', 1, true, now(), now())", UUID.randomUUID(), id);
        return id;
    }

    private void fix(UUID driver, Instant now, double latitude, double longitude) {
        assertThat(store.accept(new LocationFix(driver, latitude, longitude, null, null, now, now))).isZero();
    }

    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject); }
    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor merchant, Actor dispatcher, UUID order) { }
}
