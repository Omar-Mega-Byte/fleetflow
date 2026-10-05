package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.dispatch.AssignmentService;
import com.fleetflow.identity.AuthenticatedActor;
import com.fleetflow.identity.Role;
import com.fleetflow.location.LocationFix;
import com.fleetflow.location.RedisLocationStore;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.DockerClientFactory;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Phase6AssignmentTests extends ProfileTestDatabase {
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
    @Autowired RedisLocationStore locations;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired AssignmentService service;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void signedOfferAndAcceptKeepTheFourStatesAndHistoriesAtomic() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(o, f.driver()))
                .header("Authorization", bearer(f.customer()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(o, f.driver())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(o, f.driver()))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OFFERED"));
        UUID offer = offerId(o);
        assertTuple(o, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
        mvc.perform(post("/api/v1/drivers/me/heartbeat")
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/drivers/me/offers").header("Authorization", bearer(f.driverActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].offerId").value(offer.toString()))
                .andExpect(jsonPath("$.content[0].pickupLine1").value("Street"));
        Actor other = actor("DRIVER");
        UUID otherDriver = driver(other);
        mvc.perform(get("/api/v1/drivers/me/offers").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                .header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dispatch/orders/{id}/assignments", o.id())
                .header("Authorization", bearer(f.customer()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/dispatch/orders/{id}/assignments", o.id())
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].driverId").value(f.driver().toString()));
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                .header("Authorization", bearer(f.driverActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertTuple(o, f.driver(), "ACCEPTED", "DELIVERING", "DRIVER_ASSIGNED", "ASSIGNED");
        mvc.perform(post("/api/v1/drivers/me/heartbeat")
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select accepted_assignment_id from deliveries where order_id = ?",
                UUID.class, o.id())).isEqualTo(offer);
        assertThat(jdbc.queryForObject("select count(*) from driver_status_history where driver_id = ?",
                Integer.class, f.driver())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
        mvc.perform(get("/api/v1/orders").param("status", "DRIVER_ASSIGNED")
                .header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(o.id().toString()));
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offer)
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/pause")
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, otherDriver))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void rejectionAndDeadlinePreventLaterAcceptance() throws Exception {
        Fixture f = fixture();
        Order rejected = matchedOrder(f);
        offer(rejected, f);
        UUID offer = offerId(rejected);
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offer)
                .header("Authorization", bearer(f.driverActor())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertTuple(rejected, f.driver(), "REJECTED", "AVAILABLE", "DISPATCHING", "PENDING");
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offer)
                .header("Authorization", bearer(f.driverActor()))).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from driver_status_history where driver_id = ?",
                Integer.class, f.driver())).isEqualTo(2);

        Order expired = matchedOrder(f);
        offer(expired, f);
        UUID expiredOffer = offerId(expired);
        jdbc.update("update driver_assignments set offered_at = clock_timestamp() - interval '2 minute', "
                + "expires_at = clock_timestamp() - interval '1 minute' "
                + "where id = ?", expiredOffer);
        mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", expiredOffer)
                .header("Authorization", bearer(f.driverActor())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertTuple(expired, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void staleAndLostEligibilityFailWithoutAnyAssignmentWrites() throws Exception {
        Fixture f = fixture();
        Order stale = matchedOrder(f);
        redisTemplate.delete("fleetflow:driver:heartbeat:" + f.driver());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANDIDATE_INELIGIBLE"));
        assertNoWrites(stale);
        fix(f.driver());
        jdbc.update("update drivers set status = 'PAUSED', version = version + 1 where id = ?", f.driver());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(stale);
        jdbc.update("update drivers set status = 'AVAILABLE', version = version + 1 where id = ?", f.driver());
        jdbc.update("delete from actor_roles where actor_id = ? and role = 'DRIVER'", f.driverActor().id());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(stale);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'DRIVER')", f.driverActor().id());
        jdbc.update("update actors set active = false where id = ?", f.driverActor().id());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(stale);
        jdbc.update("update actors set active = true where id = ?", f.driverActor().id());
        jdbc.update("update vehicles set active = false where driver_id = ?", f.driver());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(stale);
        jdbc.update("update vehicles set active = true where driver_id = ?", f.driver());
        jdbc.update("update drivers set suspended = true where id = ?", f.driver());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(stale);
        jdbc.update("update drivers set suspended = false where id = ?", f.driver());
        Instant movedAt = Instant.now();
        assertThat(locations.accept(new LocationFix(f.driver(), 40, 40, null, null, movedAt, movedAt))).isZero();
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", stale.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(stale, f.driver()))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANDIDATE_INELIGIBLE"));
        assertNoWrites(stale);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void databaseConstraintAndFailedInsertRollbackProtectReservation() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        jdbc.execute("""
                create function phase6_fail_delivery() returns trigger language plpgsql as $$
                begin raise exception 'injected delivery failure'; end; $$
                """);
        jdbc.execute("create trigger phase6_fail_delivery before insert on deliveries "
                + "for each row execute function phase6_fail_delivery()");
        try {
            assertThatThrownBy(() -> asDispatcher(f.dispatcher(), () ->
                    service.offer(o.id(), o.attempt(), f.driver())))
                    .hasMessageContaining("injected delivery failure");
            assertNoWrites(o);
            assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, f.driver()))
                    .isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("select count(*) from driver_status_history where driver_id = ?",
                    Integer.class, f.driver())).isZero();
        } finally {
            jdbc.execute("drop trigger phase6_fail_delivery on deliveries");
            jdbc.execute("drop function phase6_fail_delivery()");
        }
        offer(o, f);
        UUID original = offerId(o);
        assertThatThrownBy(() -> jdbc.update("""
                insert into driver_assignments (id, order_id, driver_id, attempt_id, candidate_id,
                  offered_by_actor_id, status, offered_at, expires_at)
                select ?, order_id, driver_id, attempt_id, candidate_id, offered_by_actor_id,
                  'OFFERED', clock_timestamp(), clock_timestamp() + interval '1 minute'
                from driver_assignments where id = ?
                """, UUID.randomUUID(), original)).hasMessageContaining("uk_assignment_active_driver");
        Order another = matchedOrder(f);
        assertThatThrownBy(() -> jdbc.update("""
                insert into driver_assignments (id, order_id, driver_id, attempt_id, candidate_id,
                  offered_by_actor_id, status, offered_at, expires_at)
                select ?, ?, a.driver_id, ?, c.id, a.offered_by_actor_id,
                  'OFFERED', clock_timestamp(), clock_timestamp() + interval '1 minute'
                from driver_assignments a join dispatch_candidates c on c.attempt_id = ?
                where a.id = ?
                """, UUID.randomUUID(), another.id(), another.attempt(), another.attempt(), original))
                .hasMessageContaining("uk_assignment_active_driver");
        Actor secondActor = actor("DRIVER");
        UUID secondDriver = driver(secondActor), secondCandidate = UUID.randomUUID();
        jdbc.update("""
                insert into dispatch_candidates (id, attempt_id, driver_id, reason, distance_meters,
                  gps_age_millis, distance_factor, freshness_factor, score, rank, straight_line_seconds)
                values (?, ?, ?, 'ELIGIBLE', 1, 0, 1, 1, 1, 2, 1)
                """, secondCandidate, o.attempt(), secondDriver);
        assertThatThrownBy(() -> jdbc.update("""
                insert into driver_assignments (id, order_id, driver_id, attempt_id, candidate_id,
                  offered_by_actor_id, status, offered_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'OFFERED',
                  clock_timestamp(), clock_timestamp() + interval '1 minute')
                """, UUID.randomUUID(), o.id(), secondDriver, o.attempt(), secondCandidate, f.dispatcher().id()))
                .hasMessageContaining("uk_assignment_active_order");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void twoOffersForOneOrderHaveOneWinner() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> {
                start.await();
                try {
                    asDispatcher(f.dispatcher(), () -> service.offer(o.id(), o.attempt(), f.driver()));
                    return 201;
                } catch (ApiFailure failure) {
                    return failure.status().value();
                }
            };
            var results = pool.invokeAll(List.of(request, request));
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, o.id())).isEqualTo(1);
        assertTuple(o, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
    }

    @Test
    @org.junit.jupiter.api.Order(99)
    void staleRankingAndRedisOutageLeaveDatabaseUntouched() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + UUID.randomUUID() + "\",\"driverId\":\"" + f.driver() + "\"}")
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isConflict());
        assertNoWrites(o);
        DockerClientFactory.instance().client().stopContainerCmd(redis.getContainerId()).exec();
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(o, f.driver()))
                .header("Authorization", bearer(f.dispatcher())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE"));
        assertNoWrites(o);
        assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, f.driver()))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void acceptanceRollbackAndConflictingResponsesHaveOneTerminalWinner() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        offer(o, f);
        UUID offer = offerId(o);
        jdbc.execute("""
                create function phase6_fail_delivery_update() returns trigger language plpgsql as $$
                begin raise exception 'injected acceptance failure'; end; $$
                """);
        jdbc.execute("create trigger phase6_fail_delivery_update before update on deliveries "
                + "for each row execute function phase6_fail_delivery_update()");
        try {
            mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                    .header("Authorization", bearer(f.driverActor())))
                    .andExpect(status().isServiceUnavailable());
            assertTuple(o, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
            assertThat(jdbc.queryForObject("select count(*) from order_status_history where order_id = ?",
                    Integer.class, o.id())).isZero();
        } finally {
            jdbc.execute("drop trigger phase6_fail_delivery_update on deliveries");
            jdbc.execute("drop function phase6_fail_delivery_update()");
        }
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> accept = () -> {
                start.await();
                return mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                        .header("Authorization", bearer(f.driverActor())))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> reject = () -> {
                start.await();
                return mvc.perform(post("/api/v1/drivers/me/offers/{id}/reject", offer)
                        .header("Authorization", bearer(f.driverActor())))
                        .andReturn().getResponse().getStatus();
            };
            var results = pool.invokeAll(List.of(accept, reject));
            int first = results.get(0).get(), second = results.get(1).get();
            assertThat(List.of(first, second)).containsExactlyInAnyOrder(200, 409);
        }
        String terminal = jdbc.queryForObject("select status from driver_assignments where id = ?",
                String.class, offer);
        if ("ACCEPTED".equals(terminal)) {
            assertTuple(o, f.driver(), "ACCEPTED", "DELIVERING", "DRIVER_ASSIGNED", "ASSIGNED");
        } else {
            assertTuple(o, f.driver(), "REJECTED", "AVAILABLE", "DISPATCHING", "PENDING");
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void lockWaitCrossingDeadlineCannotAccept() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        offer(o, f);
        UUID offer = offerId(o);
        CountDownLatch locked = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(ignored -> {
                jdbc.queryForObject("select id from driver_assignments where id = ? for update", UUID.class, offer);
                jdbc.update("update driver_assignments set expires_at = clock_timestamp() "
                        + "+ interval '500 milliseconds' where id = ?", offer);
                locked.countDown();
                try { Thread.sleep(1200); } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            mvc.perform(post("/api/v1/drivers/me/offers/{id}/accept", offer)
                    .header("Authorization", bearer(f.driverActor())))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
            holder.get();
        }
        assertTuple(o, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void pauseVersusReserveSerializesOnDriverRow() throws Exception {
        Fixture f = fixture();
        Order o = matchedOrder(f);
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> reserve = () -> {
                start.await();
                return mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", o.id())
                        .contentType(MediaType.APPLICATION_JSON).content(body(o, f.driver()))
                        .header("Authorization", bearer(f.dispatcher())))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> pause = () -> {
                start.await();
                return mvc.perform(post("/api/v1/drivers/me/pause")
                        .header("Authorization", bearer(f.driverActor())))
                        .andReturn().getResponse().getStatus();
            };
            var results = pool.invokeAll(List.of(reserve, pause));
            int offered = results.get(0).get(), paused = results.get(1).get();
            assertThat((offered == 201 && paused == 409) || (offered == 409 && paused == 204)).isTrue();
        }
        String state = jdbc.queryForObject("select status from drivers where id = ?", String.class, f.driver());
        if ("RESERVED".equals(state)) {
            assertTuple(o, f.driver(), "OFFERED", "RESERVED", "DISPATCHING", "PENDING");
        } else {
            assertThat(state).isEqualTo("PAUSED");
            assertNoWrites(o);
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void oneHundredContendersHaveOneCommittedWinnerAndNinetyNineConflictsTwice() throws Exception {
        for (int repetition = 0; repetition < 2; repetition++) {
            Fixture f = fixture();
            List<Order> orders = new ArrayList<>();
            for (int i = 0; i < 100; i++) orders.add(matchedOrder(f));
            fix(f.driver());
            CyclicBarrier start = new CyclicBarrier(100);
            long began = System.nanoTime();
            try (var pool = Executors.newFixedThreadPool(100)) {
                List<Callable<Integer>> calls = orders.stream().<Callable<Integer>>map(o -> () -> {
                    start.await(15, TimeUnit.SECONDS);
                    try {
                        asDispatcher(f.dispatcher(), () -> service.offer(o.id(), o.attempt(), f.driver()));
                        return 201;
                    } catch (ApiFailure failure) {
                        return failure.status().value();
                    }
                }).toList();
                var results = pool.invokeAll(calls);
                List<Integer> statuses = new ArrayList<>();
                for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
                assertThat(statuses).filteredOn(code -> code == 201).hasSize(1);
                assertThat(statuses).filteredOn(code -> code == 409).hasSize(99);
            }
            long millis = Duration.ofNanos(System.nanoTime() - began).toMillis();
            assertThat(jdbc.queryForObject("select count(*) from driver_assignments where driver_id = ?",
                    Integer.class, f.driver())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from driver_assignments where driver_id = ? "
                    + "and status = 'OFFERED'", Integer.class, f.driver())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, f.driver()))
                    .isEqualTo("RESERVED");
            assertThat(jdbc.queryForObject("""
                    select count(*) from deliveries d join driver_assignments a on a.order_id = d.order_id
                    where a.driver_id = ? and d.status = 'PENDING'
                    """, Integer.class, f.driver())).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    select count(*) from deliveries where order_id in
                      (select id from orders where merchant_branch_id = ?)
                    """, Integer.class, f.branch())).isEqualTo(1);
            System.out.println("Phase 6 100-contender repetition " + (repetition + 1)
                    + ": 1 winner, 99 conflicts, " + millis + " ms");
        }
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER");
        Actor dispatcher = actor("DISPATCHER");
        Actor driverActor = actor("DRIVER");
        UUID driver = driver(driverActor);
        fix(driver);
        UUID customerId = UUID.randomUUID(), merchantId = UUID.randomUUID(), branch = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", customerId, customer.id());
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchantId);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30.000000, 31.000000, true, now(), now())
                """, branch, merchantId);
        return new Fixture(customer, dispatcher, driverActor, driver, customerId, branch);
    }

    private Order matchedOrder(Fixture f) {
        UUID order = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("""
                insert into orders (id, customer_id, merchant_branch_id, status, customer_name_snapshot,
                  merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city, pickup_latitude,
                  pickup_longitude, delivery_line1, delivery_city, delivery_latitude, delivery_longitude,
                  currency, total_amount, created_at, updated_at)
                values (?, ?, ?, 'DISPATCHING', 'Customer', 'Merchant', 'Branch', 'Street', 'Cairo',
                  30.000000, 31.000000, 'Road', 'Cairo', 30.000000, 31.000000, 'EGP', 1.00, now(), now())
                """, order, f.customerId(), f.branch());
        jdbc.update("""
                insert into dispatch_attempts (id, order_id, sequence, started_at, completed_at, decision_at,
                  outcome, config_version, radius_meters, candidate_limit, geo_cap, max_gps_age_millis,
                  heartbeat_age_millis, distance_weight, freshness_weight, estimate_metres_per_second)
                values (?, ?, 1, now(), now(), now(), 'RANKED', 'v1', 5000, 50, 200,
                  45000, 30000, 0.8000, 0.2000, 8.330)
                """, attempt, order);
        jdbc.update("""
                insert into dispatch_candidates (id, attempt_id, driver_id, reason, distance_meters,
                  gps_age_millis, distance_factor, freshness_factor, score, rank, straight_line_seconds)
                values (?, ?, ?, 'ELIGIBLE', 0, 0, 1, 1, 1, 1, 0)
                """, UUID.randomUUID(), attempt, f.driver());
        return new Order(order, attempt);
    }
    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id(), TestOidc.ISSUER, actor.subject());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id(), role);
        return actor;
    }
    private UUID driver(Actor actor) {
        UUID driver = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, active, suspended, status, created_at, updated_at) "
                + "values (?, ?, true, false, 'AVAILABLE', now(), now())", driver, actor.id());
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                + "values (?, ?, 'BIKE', 1, true, now(), now())", UUID.randomUUID(), driver);
        return driver;
    }
    private void fix(UUID driver) {
        Instant now = Instant.now();
        assertThat(locations.accept(new LocationFix(driver, 30, 31, null, null, now, now))).isZero();
    }
    private void offer(Order order, Fixture f) throws Exception {
        mvc.perform(post("/api/v1/dispatch/orders/{id}/offers", order.id())
                .contentType(MediaType.APPLICATION_JSON).content(body(order, f.driver()))
                .header("Authorization", bearer(f.dispatcher()))).andExpect(status().isCreated());
    }
    private UUID offerId(Order order) {
        return jdbc.queryForObject("select id from driver_assignments where order_id = ?", UUID.class, order.id());
    }
    private void assertNoWrites(Order order) {
        assertThat(jdbc.queryForObject("select count(*) from driver_assignments where order_id = ?",
                Integer.class, order.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from deliveries where order_id = ?",
                Integer.class, order.id())).isZero();
    }
    private void assertTuple(Order order, UUID driver, String assignment, String driverStatus,
                             String orderStatus, String delivery) {
        assertThat(jdbc.queryForObject("select status from driver_assignments where order_id = ?",
                String.class, order.id())).isEqualTo(assignment);
        assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, driver))
                .isEqualTo(driverStatus);
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, order.id()))
                .isEqualTo(orderStatus);
        assertThat(jdbc.queryForObject("select status from deliveries where order_id = ?",
                String.class, order.id())).isEqualTo(delivery);
    }
    private String body(Order o, UUID driver) {
        return "{\"attemptId\":\"" + o.attempt() + "\",\"driverId\":\"" + driver + "\"}";
    }
    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject()); }
    private void asDispatcher(Actor dispatcher, Runnable command) {
        var auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedActor(dispatcher.id(), Set.of(Role.DISPATCHER)), null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try { command.run(); } finally { SecurityContextHolder.clearContext(); }
    }
    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor dispatcher, Actor driverActor, UUID driver,
                           UUID customerId, UUID branch) { }
    private record Order(UUID id, UUID attempt) { }
}
