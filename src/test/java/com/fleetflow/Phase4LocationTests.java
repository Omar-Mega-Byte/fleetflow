package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Duration;
import java.net.ServerSocket;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fleetflow.location.LocationService;
import com.fleetflow.location.LocationFix;
import com.fleetflow.location.RedisLocationStore;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

@SpringBootTest
@ActiveProfiles("test")
class Phase4LocationTests extends ProfileTestDatabase {
    private static final int redisHostPort = freePort();
    private static final RestartableRedisContainer redis = new RestartableRedisContainer(redisHostPort)
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort());
    static { redis.start(); }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
        registry.add("fleetflow.location.update-limit", () -> 3);
        registry.add("fleetflow.location.heartbeat-limit", () -> 2);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired LocationService locations;
    @Autowired RedisLocationStore store;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class),
                        context.getBean("locationBodyLimit", FilterRegistrationBean.class).getFilter())
                .build();
    }

    @AfterEach
    void ensureRedisRunning() {
        try {
            if (!DockerClientFactory.instance().client().inspectContainerCmd(redis.getContainerId())
                    .exec().getState().getRunning()) {
                DockerClientFactory.instance().client().startContainerCmd(redis.getContainerId()).exec();
            }
        } catch (Exception containerAlreadyGone) { /* Testcontainers owns final cleanup */ }
    }

    @Test
    void signedSelfAuthorizationAndInputValidation() throws Exception {
        Fixture driver = fixture("DRIVER", true, true);
        String body = body(30, 31, Instant.now());
        mvc.perform(post("/api/v1/drivers/me/location").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        Fixture customer = fixture("CUSTOMER", true, true);
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(customer))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        Fixture missing = fixture("DRIVER", false, false);
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(missing))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_STATE_CONFLICT"));
        onlineAvailable(driver);
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(90, -180, Instant.now())))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(-90, 180, Instant.now().plusNanos(1))
                        .replace("}", ",\"heading\":359.9,\"speed\":100}")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(91, 31, Instant.now())))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now().minusSeconds(200))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now().plusSeconds(20))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("}", ",\"padding\":\"" + "x".repeat(1100) + "\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("}", ",\"heading\":360}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("}", ",\"speed\":-1}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("}", ",\"heading\":-1,\"speed\":101}")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("30.0", "NaN")))
                .andExpect(status().isBadRequest());
        Fixture otherDriver = fixture("DRIVER", true, true);
        var spoof = mvc.perform(post("/api/v1/drivers/me/location")
                .header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())
                        .replace("}", ",\"driverId\":\"" + otherDriver.driverId + "\"}")))
                .andReturn();
        assertThat(spoof.getResponse().getStatus()).isIn(204, 400);
        assertThat(redisTemplate.hasKey("fleetflow:driver:location:" + otherDriver.driverId)).isFalse();
        jdbc.update("update drivers set active = false where id = ?", driver.driverId);
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                .contentType(MediaType.APPLICATION_JSON).content(body(30, 31, Instant.now())))
                .andExpect(status().isConflict());
        jdbc.update("update drivers set active = true, suspended = true where id = ?", driver.driverId);
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isConflict());
    }

    @Test
    void locationBodyLimitAlsoRejectsUnknownContentLength() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/drivers/me/location") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent("x".repeat(1025).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean();
        Filter filter = context.getBean("locationBodyLimit", FilterRegistrationBean.class).getFilter();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedController.set(true));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("INVALID_REQUEST");
        assertThat(reachedController.get()).isFalse();
    }

    @Test
    void atomicOrderingHeartbeatAndRateLimit() throws Exception {
        Fixture driver = fixture("DRIVER", true, true);
        onlineAvailable(driver);
        Instant first = Instant.now().minusSeconds(2);
        update(driver, 30, 31, first, 204);
        String locationKey = "fleetflow:driver:location:" + driver.driverId;
        String heartbeatKey = "fleetflow:driver:heartbeat:" + driver.driverId;
        assertThat(redisTemplate.getExpire(locationKey)).isPositive();
        assertThat(redisTemplate.getExpire(heartbeatKey)).isPositive();
        assertThat(redisTemplate.opsForHash().get(locationKey, "longitude")).isEqualTo("31.0");
        String heartbeat = redisTemplate.opsForValue().get(heartbeatKey);
        Long ttl = redisTemplate.getExpire(locationKey);
        Long heartbeatTtl = redisTemplate.getExpire(heartbeatKey);
        var geoBefore = redisTemplate.opsForGeo().position("fleetflow:geo:available", driver.driverId.toString());
        update(driver, 31, 32, first, 409);
        update(driver, 31, 32, first.minusSeconds(1), 409);
        assertThat(redisTemplate.opsForHash().get(locationKey, "longitude")).isEqualTo("31.0");
        assertThat(redisTemplate.opsForValue().get(heartbeatKey)).isEqualTo(heartbeat);
        assertThat(redisTemplate.getExpire(locationKey)).isLessThanOrEqualTo(ttl);
        assertThat(redisTemplate.getExpire(heartbeatKey)).isLessThanOrEqualTo(heartbeatTtl);
        assertThat(redisTemplate.opsForGeo().position("fleetflow:geo:available", driver.driverId.toString()))
                .isEqualTo(geoBefore);
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        assertThat(redisTemplate.opsForHash().get(locationKey, "longitude")).isEqualTo("31.0");
        assertThat(redisTemplate.getExpire(locationKey)).isLessThanOrEqualTo(ttl);
        update(driver, 30, 31.001, first.plusNanos(1), 204);
        update(driver, 30, 31.002, first.plusNanos(2), 204);
        String afterAccepted = redisTemplate.opsForValue().get(heartbeatKey);
        update(driver, 30, 31.003, first.plusNanos(3), 429);
        assertThat(redisTemplate.opsForHash().get(locationKey, "longitude")).isEqualTo("31.002");
        assertThat(redisTemplate.opsForValue().get(heartbeatKey)).isEqualTo(afterAccepted);
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        String beforeLimitedBeat = redisTemplate.opsForValue().get(heartbeatKey);
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("HEARTBEAT_RATE_LIMITED"));
        assertThat(redisTemplate.opsForValue().get(heartbeatKey)).isEqualTo(beforeLimitedBeat);
    }

    @Test
    void nearbyRechecksRedisAndPostgresFacts() throws Exception {
        Fixture near = fixture("DRIVER", true, true);
        Fixture far = fixture("DRIVER", true, true);
        Fixture onlineOnly = fixture("DRIVER", true, true);
        onlineAvailable(near);
        onlineAvailable(far);
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer(onlineOnly)))
                .andExpect(status().isNoContent());
        update(near, 30, 31, Instant.now(), 204);
        update(far, 30.01, 31.01, Instant.now(), 204);
        update(onlineOnly, 30.001, 31.001, Instant.now(), 204);
        assertThat(redisTemplate.opsForZSet().score("fleetflow:geo:available", near.driverId.toString())).isNotNull();
        assertThat(redisTemplate.opsForZSet().score("fleetflow:geo:available", far.driverId.toString())).isNotNull();
        assertThat(redisTemplate.opsForGeo().position("fleetflow:geo:available", near.driverId.toString())
                .getFirst().getX()).isCloseTo(31, org.assertj.core.data.Offset.offset(0.001));
        assertThat(locations.nearby(30, 31, 3000, 2)).extracting(LocationService.NearbyDriver::driverId)
                .containsExactly(near.driverId, far.driverId);
        assertThat(locations.nearby(30, 31, 100, 2)).hasSize(1);
        assertThat(locations.nearby(30, 31, 3000, 1)).hasSize(1);
        jdbc.update("update drivers set status = 'PAUSED' where id = ?", near.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).extracting(LocationService.NearbyDriver::driverId)
                .containsExactly(far.driverId);
        jdbc.update("update drivers set status = 'AVAILABLE', suspended = true where id = ?", near.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).extracting(LocationService.NearbyDriver::driverId)
                .containsExactly(far.driverId);
        jdbc.update("update drivers set suspended = false where id = ?", near.driverId);
        mvc.perform(post("/api/v1/drivers/me/pause").header("Authorization", bearer(near)))
                .andExpect(status().isNoContent());
        assertThat(redisTemplate.opsForZSet().score("fleetflow:geo:available", near.driverId.toString())).isNull();
        jdbc.update("update vehicles set active = false where driver_id = ?", far.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).isEmpty();
        jdbc.update("update vehicles set active = true where driver_id = ?", far.driverId);
        jdbc.update("update drivers set status = 'OFFLINE' where id = ?", far.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).isEmpty();
        jdbc.update("update drivers set status = 'AVAILABLE', active = false where id = ?", far.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).isEmpty();
        jdbc.update("update drivers set active = true where id = ?", far.driverId);
        redisTemplate.delete("fleetflow:driver:heartbeat:" + far.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).isEmpty();
        redisTemplate.delete("fleetflow:driver:location:" + far.driverId);
        assertThat(locations.nearby(30, 31, 3000, 2)).isEmpty();
        assertThat(redisTemplate.opsForZSet().score("fleetflow:geo:available", far.driverId.toString())).isNull();
    }

    @Test
    void expiredHeartbeatAndLocationNeverReappearFromGeoAlone() throws Exception {
        Fixture driver = fixture("DRIVER", true, true);
        onlineAvailable(driver);
        update(driver, 30, 31, Instant.now(), 204);
        String heartbeatKey = "fleetflow:driver:heartbeat:" + driver.driverId;
        redisTemplate.expire(heartbeatKey, Duration.ofMillis(300));
        awaitMissing(heartbeatKey);
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        assertThat(locations.nearby(30, 31, 500, 1)).hasSize(1);
        String locationKey = "fleetflow:driver:location:" + driver.driverId;
        redisTemplate.expire(locationKey, Duration.ofMillis(300));
        awaitMissing(locationKey);
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
    }

    @Test
    void freshlyReceivedButOldReportedFixCannotBecomeEligible() throws Exception {
        Fixture driver = fixture("DRIVER", true, true);
        onlineAvailable(driver);
        update(driver, 30, 31, Instant.now().minusSeconds(60), 204);
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
        mvc.perform(post("/api/v1/drivers/me/heartbeat").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
        update(driver, 30, 31, Instant.now(), 204);
        assertThat(locations.nearby(30, 31, 500, 1)).hasSize(1);
        redisTemplate.opsForHash().put("fleetflow:driver:location:" + driver.driverId,
                "receivedAtMillis", Long.toString(Instant.now().minusSeconds(60).toEpochMilli()));
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
    }

    @Test
    void concurrentFixesKeepTheNewestReportedTimestamp() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var older = new LocationFix(id, 30, 31, null, null, now.minusSeconds(2), now);
        var newer = new LocationFix(id, 30, 32, null, null, now.minusSeconds(1), now);
        var a = CompletableFuture.supplyAsync(() -> store.accept(older));
        var b = CompletableFuture.supplyAsync(() -> store.accept(newer));
        CompletableFuture.allOf(a, b).join();
        assertThat(store.latest(id).longitude()).isEqualTo(32);
        assertThat(a.join()).isIn(0L, 1L);
        assertThat(b.join()).isZero();
    }

    @Test
    void redisOutageFailsClosedAndRecoveryRequiresNewFix() throws Exception {
        Fixture driver = fixture("DRIVER", true, true);
        onlineAvailable(driver);
        update(driver, 30, 31, Instant.now().minusSeconds(1), 204);
        assertThat(locations.nearby(30, 31, 500, 1)).hasSize(1);
        int ordersBefore = jdbc.queryForObject("select count(*) from orders", Integer.class);
        DockerClientFactory.instance().client().stopContainerCmd(redis.getContainerId()).exec();
        try {
            mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(driver))
                    .header("X-Correlation-ID", "redis-outage")
                    .contentType(MediaType.APPLICATION_JSON).content(body(30, 31.1, Instant.now())))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("REDIS_UNAVAILABLE"))
                    .andExpect(jsonPath("$.correlationId").value("redis-outage"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> locations.nearby(30, 31, 500, 1))
                    .isInstanceOf(com.fleetflow.api.ApiFailure.class);
            assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, driver.driverId))
                    .isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(ordersBefore);
        } finally {
            DockerClientFactory.instance().client().startContainerCmd(redis.getContainerId()).exec();
        }
        assertThat(redis.execInContainer("redis-cli", "ping").getStdout()).contains("PONG");
        awaitRedisReady();
        assertThat(locations.nearby(30, 31, 500, 1)).isEmpty();
        update(driver, 30, 31, Instant.now(), 204);
        assertThat(locations.nearby(30, 31, 500, 1)).hasSize(1);
    }

    private void onlineAvailable(Fixture fixture) throws Exception {
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer(fixture)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/available").header("Authorization", bearer(fixture)))
                .andExpect(status().isNoContent());
    }

    private void update(Fixture fixture, double latitude, double longitude, Instant timestamp, int statusCode)
            throws Exception {
        mvc.perform(post("/api/v1/drivers/me/location").header("Authorization", bearer(fixture))
                .contentType(MediaType.APPLICATION_JSON).content(body(latitude, longitude, timestamp)))
                .andExpect(status().is(statusCode));
    }

    private String body(double latitude, double longitude, Instant timestamp) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude
                + ",\"reportedAt\":\"" + timestamp + "\"}";
    }

    private void awaitMissing(String key) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (Boolean.TRUE.equals(redisTemplate.hasKey(key)) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(redisTemplate.hasKey(key)).isFalse();
    }

    private void awaitRedisReady() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if ("PONG".equals(redisTemplate.execute((RedisCallback<String>) connection -> connection.ping()))) return;
            } catch (Exception notReady) { /* retry briefly */ }
            Thread.sleep(50);
        }
        throw new AssertionError("Redis did not become ready after restart");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (IOException unavailable) { throw new IllegalStateException("No test port available", unavailable); }
    }
    private static class RestartableRedisContainer extends GenericContainer<RestartableRedisContainer> {
        RestartableRedisContainer(int hostPort) {
            super("redis:7.4.7-alpine");
            addFixedExposedPort(hostPort, 6379);
        }
    }

    private Fixture fixture(String role, boolean profile, boolean vehicle) {
        UUID actor = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor, role);
        UUID driver = null;
        if (profile) {
            driver = UUID.randomUUID();
            jdbc.update("insert into drivers (id, actor_id, active, suspended, created_at, updated_at) "
                    + "values (?, ?, true, false, now(), now())", driver, actor);
            if (vehicle) {
                jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                        + "values (?, ?, 'BIKE', 1, true, now(), now())", UUID.randomUUID(), driver);
            }
        }
        return new Fixture(actor, driver, subject);
    }
    private String bearer(Fixture fixture) { return "Bearer " + TestOidc.token(fixture.subject); }
    private record Fixture(UUID actorId, UUID driverId, String subject) { }
}
