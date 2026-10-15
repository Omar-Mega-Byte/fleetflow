package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.fleetflow.tracking.TrackingHub;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest
@ActiveProfiles("test")
class Phase9TrackingTests extends ProfileTestDatabase {
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499")
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort());
    static { redis.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("fleetflow.outbox.enabled", () -> false);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired TrackingHub hub;
    @Autowired RedisLocationStore locations;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void signedOwnershipAndFreshAssignmentControlCoordinates() throws Exception {
        Fixture f = fixture();
        String path = "/api/v1/orders/" + f.order() + "/tracking";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(f.driver())))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", bearer(f.otherCustomer())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/{id}/tracking", UUID.randomUUID())
                .header("Authorization", bearer(f.customer()))).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());

        Instant old = Instant.now().minusSeconds(60);
        assertThat(locations.accept(new LocationFix(f.driverId(), 30.0, 31.0, null, null, old, old)))
                .isZero();
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());

        move(f, 30.1, 31.1, Instant.now());
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.latitude").value(30.1))
                .andExpect(jsonPath("$.position.longitude").value(31.1))
                .andExpect(jsonPath("$.driverId").doesNotExist());

        assertThat(redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "3000", "ALL").getExitCode())
                .isZero();
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());
        Thread.sleep(1500);

        jdbc.update("update drivers set suspended = true where id = ?", f.driverId());
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());
        jdbc.update("update drivers set suspended = false where id = ?", f.driverId());

        redisTemplate.delete("fleetflow:driver:heartbeat:" + f.driverId());
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());
        jdbc.update("update driver_assignments set status = 'REASSIGNED', closed_at = now(), "
                + "close_reason = 'Changed driver' where id = ?", f.assignment());
        mvc.perform(get(path).header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").isEmpty());
    }

    @Test
    void streamStartsWithSnapshotThenMovementAndClosesAfterDelivery() throws Exception {
        Fixture f = fixture();
        String path = "/api/v1/orders/" + f.order() + "/tracking/stream";
        mvc.perform(get(path).accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", bearer(f.driver()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", bearer(f.otherCustomer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isNotFound());
        MvcResult stream = mvc.perform(get(path).header("Authorization", bearer(f.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:snapshot", "\"position\":null");
        assertThat(hub.activeStreams()).isGreaterThanOrEqualTo(1);

        move(f, 30.2, 31.2, Instant.now());
        awaitContent(stream, "\"latitude\":30.2");
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", f.delivery())
                .header("Authorization", bearer(f.driver()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/start-transit", f.delivery())
                .header("Authorization", bearer(f.driver()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/complete", f.delivery())
                .header("Authorization", bearer(f.driver()))).andExpect(status().isOk());
        awaitContent(stream, "\"orderStatus\":\"DELIVERED\"");
        assertThat(hub.activeStreams()).isZero();
        mvc.perform(get("/api/v1/orders/{id}/tracking", f.order())
                .header("Authorization", bearer(f.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.position").isEmpty());
    }

    @Test
    void reconnectGetsLatestSnapshotAndConnectionLimitReleasesOnDisconnect() throws Exception {
        Fixture f = fixture();
        String path = "/api/v1/orders/" + f.order() + "/tracking/stream";
        MvcResult first = mvc.perform(get(path).header("Authorization", bearer(f.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted()).andReturn();
        move(f, 30.3, 31.3, Instant.now());
        awaitContent(first, "\"latitude\":30.3");
        MvcResult second = mvc.perform(get(path).header("Authorization", bearer(f.customer()))
                .header("Last-Event-ID", "1").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(second.getResponse().getContentAsString()).contains("event:snapshot", "\"latitude\":30.3");
        MvcResult third = mvc.perform(get(path).header("Authorization", bearer(f.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(get(path).header("Authorization", bearer(f.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TRACKING_LIMIT"));
        first.getRequest().getAsyncContext().complete();
        second.getRequest().getAsyncContext().complete();
        third.getRequest().getAsyncContext().complete();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && hub.activeStreams() != 0) Thread.sleep(10);
        assertThat(hub.activeStreams()).isZero();
        assertThatThrownBy(() -> hub.open(f.order(), f.customer().id(), Instant.now().minusSeconds(1)))
                .isInstanceOf(com.fleetflow.api.ApiFailure.class);
        hub.open(f.order(), f.customer().id(), Instant.now().plusSeconds(1));
        assertThat(hub.activeStreams()).isEqualTo(1);
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(7);
        while (System.nanoTime() < deadline && hub.activeStreams() != 0) Thread.sleep(25);
        assertThat(hub.activeStreams()).isZero();
    }

    @Test
    void idleHeartbeatAndRevokedCustomerRoleCloseTheStream() throws Exception {
        Fixture f = fixture();
        MvcResult stream = mvc.perform(get("/api/v1/orders/{id}/tracking/stream", f.order())
                .header("Authorization", bearer(f.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted()).andReturn();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(21);
        while (System.nanoTime() < deadline
                && !stream.getResponse().getContentAsString().contains(":keepalive")) Thread.sleep(50);
        assertThat(stream.getResponse().getContentAsString()).contains(":keepalive");
        jdbc.update("delete from actor_roles where actor_id = ? and role = 'CUSTOMER'", f.customer().id());
        hub.refreshOrder(f.order());
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && hub.activeStreams() != 0) Thread.sleep(25);
        assertThat(hub.activeStreams()).isZero();
    }

    @Test
    void cancellationAndFailureHideCoordinatesAndCloseStreams() throws Exception {
        Fixture cancelled = fixture();
        move(cancelled, 30.4, 31.4, Instant.now());
        mvc.perform(get("/api/v1/orders/{id}/tracking/stream", cancelled.order())
                .header("Authorization", bearer(cancelled.customer()))
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted());
        mvc.perform(post("/api/v1/dispatch/orders/{id}/cancel", cancelled.order())
                .header("Authorization", bearer(cancelled.dispatcher()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer request\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/{id}/tracking", cancelled.order())
                .header("Authorization", bearer(cancelled.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.position").isEmpty());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && hub.activeStreams() != 0) Thread.sleep(25);
        assertThat(hub.activeStreams()).isZero();

        Fixture failed = fixture();
        move(failed, 30.5, 31.5, Instant.now());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/pickup", failed.delivery())
                .header("Authorization", bearer(failed.driver()))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drivers/me/deliveries/{id}/fail", failed.delivery())
                .header("Authorization", bearer(failed.driver()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Vehicle failed\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/{id}/tracking", failed.order())
                .header("Authorization", bearer(failed.customer())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("DELIVERY_FAILED"))
                .andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.position").isEmpty());
    }

    private void awaitContent(MvcResult result, String text) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !result.getResponse().getContentAsString().contains(text)) {
            Thread.sleep(25);
        }
        assertThat(result.getResponse().getContentAsString()).contains(text);
    }

    private void move(Fixture f, double lat, double lon, Instant when) throws Exception {
        mvc.perform(post("/api/v1/drivers/me/location")
                .header("Authorization", bearer(f.driver()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":" + lat + ",\"longitude\":" + lon
                        + ",\"reportedAt\":\"" + when + "\"}"))
                .andExpect(status().isNoContent());
    }

    private Fixture fixture() {
        Actor customer = actor("CUSTOMER"), other = actor("CUSTOMER"), driver = actor("DRIVER");
        Actor dispatcher = actor("DISPATCHER");
        UUID customerId = UUID.randomUUID(), merchant = UUID.randomUUID(), branch = UUID.randomUUID();
        UUID driverId = UUID.randomUUID(), vehicle = UUID.randomUUID();
        UUID order = UUID.randomUUID(), attempt = UUID.randomUUID(), candidate = UUID.randomUUID();
        UUID assignment = UUID.randomUUID(), delivery = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", customerId, customer.id());
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchant);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                  pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30, 31, true, now(), now())
                """, branch, merchant);
        jdbc.update("insert into drivers (id, actor_id, active, suspended, operational_status, "
                + "created_at, updated_at) values (?, ?, true, false, 'DELIVERING', now(), now())",
                driverId, driver.id());
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                + "values (?, ?, 'BIKE', 1, true, now(), now())", vehicle, driverId);
        jdbc.update("""
                insert into orders (id, customer_id, merchant_branch_id, status, version,
                  customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot,
                  pickup_line1, pickup_city, pickup_latitude, pickup_longitude,
                  delivery_line1, delivery_city, delivery_latitude, delivery_longitude,
                  currency, total_amount, created_at, updated_at)
                values (?, ?, ?, 'DRIVER_ASSIGNED', 1, 'Customer', 'Merchant', 'Branch',
                  'Street', 'Cairo', 30, 31, 'Road', 'Cairo', 30, 31, 'EGP', 1, now(), now())
                """, order, customerId, branch);
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
                values (?, ?, ?, 'ELIGIBLE', 1, 0, 1, 1, 1, 1, 0)
                """, candidate, attempt, driverId);
        jdbc.update("""
                insert into driver_assignments (id, order_id, driver_id, attempt_id, candidate_id,
                  offered_by_actor_id, status, offered_at, expires_at, responded_at, responded_by_actor_id)
                values (?, ?, ?, ?, ?, ?, 'ACCEPTED', now() - interval '20 seconds',
                  now() + interval '1 minute', now() - interval '10 seconds', ?)
                """, assignment, order, driverId, attempt, candidate, dispatcher.id(), driver.id());
        jdbc.update("""
                insert into deliveries (id, order_id, status, version, accepted_assignment_id, sequence,
                  created_at, updated_at) values (?, ?, 'ASSIGNED', 1, ?, 1, now(), now())
                """, delivery, order, assignment);
        return new Fixture(customer, other, driver, dispatcher, driverId, order, assignment, delivery);
    }

    private Actor actor(String role) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id(), TestOidc.ISSUER, actor.subject());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id(), role);
        return actor;
    }

    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject()); }
    private record Actor(UUID id, String subject) { }
    private record Fixture(Actor customer, Actor otherCustomer, Actor driver, Actor dispatcher, UUID driverId,
                           UUID order, UUID assignment, UUID delivery) { }
}
