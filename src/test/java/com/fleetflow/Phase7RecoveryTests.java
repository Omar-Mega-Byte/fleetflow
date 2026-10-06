package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import com.fleetflow.dispatch.OfferExpiryService;
import com.fleetflow.dispatch.RecoveryService;
import com.fleetflow.location.LocationFix;
import com.fleetflow.location.RedisLocationStore;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest
@ActiveProfiles("test")
class Phase7RecoveryTests extends ProfileTestDatabase {
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
    @Autowired RedisLocationStore locations;
    @Autowired RecoveryService recovery;
    @Autowired OfferExpiryService expiry;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        jdbc.update("update dispatch_recovery set status = 'RESOLVED' where status = 'PENDING'");
        jdbc.update("""
                update driver_assignments set expires_at = clock_timestamp() + interval '1 hour'
                where status = 'OFFERED' and expires_at <= clock_timestamp()
                """);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void rejectedOfferAdvancesToNextRankedDriverAndReusesPendingDelivery() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        UUID first = offerId(o, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", first)
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());

        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, o.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from deliveries where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
        assertThat(assignment(o, f.secondDriver())).isEqualTo("OFFERED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(driverStatus(f.secondDriver())).isEqualTo("RESERVED");
        assertThat(recovery.sweep()).isZero();
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(o, f.secondDriver()))
                .header("Authorization", bearer(f.secondActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(orderStatus(o)).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void timeoutAdvancesAndTwoWorkersDoNotDuplicateTheOffer() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        UUID first = offerId(o, f.firstDriver());
        jdbc.update("update driver_assignments set offered_at = clock_timestamp() - interval '2 minute', "
                + "expires_at = clock_timestamp() - interval '1 minute' where id = ?", first);
        assertThat(expiry.sweep()).isEqualTo(1);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> worker = () -> { start.await(); return recovery.sweep(); };
            var results = pool.invokeAll(List.of(worker, worker));
            assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(1);
        }
        assertThat(assignment(o, f.firstDriver())).isEqualTo("TIMED_OUT");
        assertThat(assignment(o, f.secondDriver())).isEqualTo("OFFERED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, o.id())).isEqualTo(2);
    }

    @Test
    void staleNextCandidateIsSkippedAndExhaustionIsVisible() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        redisTemplate.delete("fleetflow:driver:heartbeat:" + f.secondDriver());
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from dispatch_candidate_recovery where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(orderStatus(o)).isEqualTo("DISPATCH_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
    }

    @Test
    void prePickupReassignmentPreservesOldDeliveryAndCreatesSequenceTwo() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        UUID firstDelivery = deliveryId(o, 1);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/reassign", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Vehicle unavailable"))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPATCHING"));
        assertThat(assignment(o, f.firstDriver())).isEqualTo("REASSIGNED");
        assertThat(deliveryStatus(firstDelivery)).isEqualTo("CANCELLED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(recovery.sweep()).isEqualTo(1);
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(o, f.secondDriver()))
                .header("Authorization", bearer(f.secondActor()))).andExpect(status().isOk());
        assertThat(deliveryId(o, 2)).isNotEqualTo(firstDelivery);
        assertThat(deliveryStatus(deliveryId(o, 2))).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("select count(*) from dispatch_incidents where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
    }

    @Test
    void merchantCancellationClosesOfferAndAssignedDeliveryBeforePickup() throws Exception {
        Fixture f = fixture();
        Order offered = order(f);
        offer(offered, f, f.firstDriver());
        mvc.perform(post("/api/v1/merchant-branches/{branch}/orders/{id}/cancel", f.branch(), offered.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Shop closed"))
                .header("Authorization", bearer(f.merchant())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(assignment(offered, f.firstDriver())).isEqualTo("CANCELLED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(deliveryStatus(deliveryId(offered, 1))).isEqualTo("CANCELLED");

        Order accepted = order(f);
        offer(accepted, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(accepted, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/cancel", accepted.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Customer called support"))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(assignment(accepted, f.firstDriver())).isEqualTo("CANCELLED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(deliveryStatus(deliveryId(accepted, 1))).isEqualTo("CANCELLED");
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", deliveryId(accepted, 1))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isConflict());
    }

    @Test
    void exactDriverProgressesDeliveryAndFailureCreatesIncident() throws Exception {
        Fixture f = fixture();
        Order completed = order(f);
        offer(completed, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(completed, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        UUID delivery = deliveryId(completed, 1);
        mvc.perform(get("/api/v1/drivers/me/deliveries")
                .header("Authorization", bearer(f.secondActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", delivery)
                .header("Authorization", bearer(f.secondActor()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", delivery)
                .header("Authorization", bearer(f.firstActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deliveryStatus").value("PICKED_UP"));
        mvc.perform(post("/api/v1/dispatch/orders/{id}/reassign", completed.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Too late"))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/start-transit", delivery)
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/complete", delivery)
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        assertThat(orderStatus(completed)).isEqualTo("DELIVERED");
        assertThat(assignment(completed, f.firstDriver())).isEqualTo("COMPLETED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");

        Order failed = order(f);
        offer(failed, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(failed, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        UUID failedDelivery = deliveryId(failed, 1);
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", failedDelivery)
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/fail", failedDelivery)
                .contentType(MediaType.APPLICATION_JSON).content(reason("Vehicle breakdown"))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        assertThat(orderStatus(failed)).isEqualTo("DELIVERY_FAILED");
        assertThat(assignment(failed, f.firstDriver())).isEqualTo("FAILED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        mvc.perform(get("/api/v1/dispatch/orders/{id}/incidents", failed.id())
                .header("Authorization", bearer(f.customer()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dispatch/orders/{id}/incidents", failed.id())
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("DELIVERY_FAILED"));
    }

    @Test
    void automaticRetriesStopAtThreeAttemptsAndDispatcherCanRetryWithReason() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        redisTemplate.delete("fleetflow:driver:heartbeat:" + f.secondDriver());
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(orderStatus(o)).isEqualTo("DISPATCH_FAILED");
        redisTemplate.delete("fleetflow:driver:heartbeat:" + f.firstDriver());
        for (int sequence = 2; sequence <= 3; sequence++) {
            due(o);
            assertThat(recovery.sweep()).isEqualTo(1);
            assertThat(orderStatus(o)).isEqualTo("DISPATCH_FAILED");
            assertThat(jdbc.queryForObject("select max(sequence) from dispatch_attempts where order_id = ?",
                    Integer.class, o.id())).isEqualTo(sequence);
        }
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from dispatch_recovery where order_id = ?",
                String.class, o.id())).isEqualTo("EXHAUSTED");
        assertThat(recovery.sweep()).isZero();
        mvc.perform(get("/api/v1/dispatch/orders/{id}/incidents", o.id())
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value("NO_DRIVER"));

        fix(f.firstDriver());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/retry", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("New driver location received"))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("RANKED"));
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ? "
                + "and driver_id = ? and status = 'OFFERED'", Integer.class,
                o.id(), f.firstDriver())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select max(sequence) from dispatch_attempts where order_id = ?",
                Integer.class, o.id())).isEqualTo(4);
    }

    @Test
    void cancellationAndAcceptanceRaceToOneTerminalOrder() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        UUID id = offerId(o, f.firstDriver());
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> { start.await(); return mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", id)
                            .header("Authorization", bearer(f.firstActor()))).andReturn().getResponse().getStatus(); },
                    () -> { start.await(); return mvc.perform(post("/api/v1/dispatch/orders/{id}/cancel", o.id())
                            .contentType(MediaType.APPLICATION_JSON).content(reason("Support cancellation"))
                            .header("Authorization", bearer(f.dispatcher()))).andReturn().getResponse().getStatus(); }
            ));
            assertThat(results.get(0).get()).isIn(200, 409);
            assertThat(results.get(1).get()).isEqualTo(200);
        }
        assertThat(orderStatus(o)).isEqualTo("CANCELLED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(deliveryStatus(deliveryId(o, 1))).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ? "
                + "and status in ('OFFERED', 'ACCEPTED')", Integer.class, o.id())).isZero();
    }

    @Test
    void pickupAndReassignmentRaceAtThePrePickupCutoff() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        UUID delivery = deliveryId(o, 1);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> { start.await(); return mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", delivery)
                            .header("Authorization", bearer(f.firstActor()))).andReturn().getResponse().getStatus(); },
                    () -> { start.await(); return mvc.perform(post("/api/v1/dispatch/orders/{id}/reassign", o.id())
                            .contentType(MediaType.APPLICATION_JSON).content(reason("Switch driver"))
                            .header("Authorization", bearer(f.dispatcher()))).andReturn().getResponse().getStatus(); }
            ));
            assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(609);
        }
        if ("PICKED_UP".equals(orderStatus(o))) {
            assertThat(deliveryStatus(delivery)).isEqualTo("PICKED_UP");
            assertThat(assignment(o, f.firstDriver())).isEqualTo("ACCEPTED");
        } else {
            assertThat(orderStatus(o)).isEqualTo("DISPATCHING");
            assertThat(deliveryStatus(delivery)).isEqualTo("CANCELLED");
            assertThat(assignment(o, f.firstDriver())).isEqualTo("REASSIGNED");
        }
    }

    @Test
    void expiredAcceptanceRacingTheTimeoutWorkerCannotReserveADriver() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        UUID id = offerId(o, f.firstDriver());
        jdbc.update("update driver_assignments set offered_at = clock_timestamp() - interval '2 minute', "
                + "expires_at = clock_timestamp() - interval '1 minute' where id = ?", id);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(List.<Callable<Integer>>of(
                    () -> { start.await(); return mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", id)
                            .header("Authorization", bearer(f.firstActor()))).andReturn().getResponse().getStatus(); },
                    () -> { start.await(); return expiry.sweep(); }
            ));
            assertThat(results.get(0).get()).isEqualTo(409);
            assertThat(results.get(1).get() + expiry.sweep()).isEqualTo(1);
        }
        assertThat(assignment(o, f.firstDriver())).isEqualTo("TIMED_OUT");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(orderStatus(o)).isEqualTo("DISPATCHING");
    }

    @Test
    void redisTimeoutLeavesRecoveryPendingWithoutSkippingCandidate() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        assertThat(redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "5000", "ALL").getExitCode())
                .isZero();
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from dispatch_recovery where order_id = ?",
                String.class, o.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from dispatch_candidate_recovery where order_id = ?",
                Integer.class, o.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
        Thread.sleep(3500);
        due(o);
        assertThat(recovery.sweep()).isEqualTo(1);
        assertThat(assignment(o, f.secondDriver())).isEqualTo("OFFERED");
    }

    @Test
    void dispatcherFailureAndReasonedResolutionKeepOrderTerminal() throws Exception {
        Fixture f = fixture();
        Order o = order(f);
        offer(o, f, f.firstDriver());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offerId(o, f.firstDriver()))
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        UUID delivery = deliveryId(o, 1);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/incidents/fail", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Too early"))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", delivery)
                .header("Authorization", bearer(f.firstActor()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/incidents/fail", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(reason("Vehicle breakdown"))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isOk());
        assertThat(orderStatus(o)).isEqualTo("DELIVERY_FAILED");
        assertThat(driverStatus(f.firstDriver())).isEqualTo("AVAILABLE");
        assertThat(recovery.sweep()).isZero();
        UUID incident = jdbc.queryForObject("select id from dispatch_incidents where order_id = ?",
                UUID.class, o.id());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/incidents/{incident}/resolve", o.id(), incident)
                .contentType(MediaType.APPLICATION_JSON).content(reason("Support contacted customer"))
                .header("Authorization", bearer(f.customer()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/incidents/{incident}/resolve", o.id(), incident)
                .contentType(MediaType.APPLICATION_JSON).content(reason("Support contacted customer"))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(orderStatus(o)).isEqualTo("DELIVERY_FAILED");
        mvc.perform(post("/api/v1/dispatch/orders/{id}/incidents/{incident}/resolve", o.id(), incident)
                .contentType(MediaType.APPLICATION_JSON).content(reason("Again"))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
    }

    private void due(Order o) {
        jdbc.update("update dispatch_recovery set next_action_at = clock_timestamp() - interval '1 second' "
                + "where order_id = ?", o.id());
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER"), dispatcher = actor("DISPATCHER"), merchantActor = actor("MERCHANT");
        Actor first = actor("DRIVER"), second = actor("DRIVER");
        UUID firstDriver = driver(first), secondDriver = driver(second);
        fix(firstDriver); fix(secondDriver);
        UUID customerId = UUID.randomUUID(), merchantId = UUID.randomUUID(), branch = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", customerId, customer.id());
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchantId);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30, 31, true, now(), now())
                """, branch, merchantId);
        jdbc.update("""
                insert into merchant_staff_memberships
                (id, actor_id, branch_id, active, created_at)
                values (?, ?, ?, true, now())
                """, UUID.randomUUID(), merchantActor.id(), branch);
        return new Fixture(customer, dispatcher, merchantActor, first, second,
                firstDriver, secondDriver, customerId, branch);
    }

    private Order order(Fixture f) {
        UUID id = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                insert into orders (id, customer_id, merchant_branch_id, status, customer_name_snapshot,
                  merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, delivery_line1, delivery_city,
                  delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at)
                values (?, ?, ?, 'DISPATCHING', 'Customer', 'Merchant', 'Branch', 'Street', 'Cairo',
                  30, 31, 'Road', 'Cairo', 30, 31, 'EGP', 1.00, now(), now())
                """, id, f.customerId(), f.branch());
        jdbc.update("""
                insert into dispatch_attempts (id, order_id, sequence, started_at, completed_at, decision_at,
                  outcome, config_version, radius_meters, candidate_limit, geo_cap, max_gps_age_millis,
                  heartbeat_age_millis, distance_weight, freshness_weight, estimate_metres_per_second)
                values (?, ?, 1, now(), now(), now(), 'RANKED', 'v1', 5000, 50, 200,
                  45000, 30000, 0.8000, 0.2000, 8.330)
                """, attempt, id);
        candidate(attempt, f.firstDriver(), 1);
        candidate(attempt, f.secondDriver(), 2);
        return new Order(id, attempt);
    }
    private void candidate(UUID attempt, UUID driver, int rank) {
        jdbc.update("""
                insert into dispatch_candidates (id, attempt_id, driver_id, reason, distance_meters,
                  gps_age_millis, distance_factor, freshness_factor, score, rank, straight_line_seconds)
                values (?, ?, ?, 'ELIGIBLE', ?, 0, 1, 1, 1, ?, 0)
                """, UUID.randomUUID(), attempt, driver, rank, rank);
    }
    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id(), TestOidc.ISSUER, actor.subject());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id(), role);
        return actor;
    }
    private UUID driver(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, active, suspended, operational_status, created_at, updated_at) "
                + "values (?, ?, true, false, 'AVAILABLE', now(), now())", id, actor.id());
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                + "values (?, ?, 'BIKE', 1, true, now(), now())", UUID.randomUUID(), id);
        return id;
    }
    private void fix(UUID driver) {
        Instant now = Instant.now();
        assertThat(locations.accept(new LocationFix(driver, 30, 31, null, null, now, now))).isZero();
    }
    private void offer(Order o, Fixture f, UUID driver) throws Exception {
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + o.attempt() + "\",\"driverId\":\"" + driver + "\"}")
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isCreated());
    }
    private UUID offerId(Order o, UUID driver) {
        return jdbc.queryForObject("select id from driver_assignments where order_id = ? and driver_id = ?",
                UUID.class, o.id(), driver);
    }
    private UUID deliveryId(Order o, int sequence) {
        return jdbc.queryForObject("select id from deliveries where order_id = ? and sequence = ?",
                UUID.class, o.id(), sequence);
    }
    private String assignment(Order o, UUID driver) {
        return jdbc.queryForObject("select status from driver_assignments where order_id = ? and driver_id = ?",
                String.class, o.id(), driver);
    }
    private String driverStatus(UUID driver) {
        return jdbc.queryForObject("select operational_status from drivers where id = ?", String.class, driver);
    }
    private String orderStatus(Order o) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, o.id());
    }
    private String deliveryStatus(UUID delivery) {
        return jdbc.queryForObject("select status from deliveries where id = ?", String.class, delivery);
    }
    private String reason(String value) { return "{\"reason\":\"" + value + "\"}"; }
    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject()); }

    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor dispatcher, Actor merchant, Actor firstActor,
                           Actor secondActor, UUID firstDriver, UUID secondDriver,
                           UUID customerId, UUID branch) { }
    private record Order(UUID id, UUID attempt) { }
}
