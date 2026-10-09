package com.fleetflow.operations;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.AuthenticatedActor;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Shared per-actor route budgets. Location and heartbeat keep their atomic service-level limits. */
@Component
public class RouteRateLimits implements HandlerInterceptor {
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final DefaultRedisScript<Long> script;
    private final int readLimit;
    private final int expensiveReadLimit;
    private final int commandLimit;
    private final int streamLimit;
    private final boolean localFixtures;
    private final ConcurrentHashMap<String, LocalWindow> fallback = new ConcurrentHashMap<>();
    private static final Set<String> FIXTURE_COMMANDS = Set.of("/api/v1/orders",
            "/api/v1/actors", "/api/v1/customers", "/api/v1/merchants",
            "/api/v1/merchant-branches", "/api/v1/merchant-staff-memberships",
            "/api/v1/drivers", "/api/v1/vehicles");

    public RouteRateLimits(StringRedisTemplate redis, MeterRegistry metrics, Environment environment,
                           @Value("${fleetflow.rate.read-limit:60}") int readLimit,
                           @Value("${fleetflow.rate.expensive-read-limit:30}") int expensiveReadLimit,
                           @Value("${fleetflow.rate.command-limit:30}") int commandLimit,
                           @Value("${fleetflow.rate.stream-limit:6}") int streamLimit) {
        if (readLimit < 1 || expensiveReadLimit < 1 || commandLimit < 1 || streamLimit < 1) {
            throw new IllegalArgumentException("Route rate limits must be positive");
        }
        this.redis = redis;
        this.metrics = metrics;
        this.readLimit = readLimit;
        this.expensiveReadLimit = expensiveReadLimit;
        this.commandLimit = commandLimit;
        this.streamLimit = streamLimit;
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        this.localFixtures = profiles.equals(Set.of("local")) || profiles.equals(Set.of("test"));
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/route-rate-limit.lua"));
        script.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (!path.startsWith("/api/v1/")) return true;
        if (localFixtures && "POST".equals(request.getMethod()) && FIXTURE_COMMANDS.contains(path)) {
            return true;
        }
        ClassPolicy policy = policy(request.getMethod(), path);
        if (policy == null) return true;
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof AuthenticatedActor actor)) return true;
        String key = "fleetflow:rate:route:" + policy.name + ":" + actor.id();
        // A paused Redis must not spend the tracking snapshot's freshness window
        // before the handler can decide whether a position is still available.
        if ("GET".equals(request.getMethod()) && path.endsWith("/tracking")) {
            rejectIfLimited(response, policy, localFallback(key, policy));
            return true;
        }
        try {
            Long remaining = redis.execute(script, List.of(key), Integer.toString(policy.limit),
                    Long.toString(policy.window.toMillis()));
            if (remaining == null) throw new IllegalStateException("Redis rate script returned no result");
            rejectIfLimited(response, policy, remaining);
            return true;
        } catch (DataAccessException | IllegalStateException unavailable) {
            metrics.counter("fleetflow.route.limit", "class", policy.name, "outcome", "fallback")
                    .increment();
            long remaining = localFallback(key, policy);
            rejectIfLimited(response, policy, remaining);
            return true;
        }
    }

    private void rejectIfLimited(HttpServletResponse response, ClassPolicy policy, long remaining) {
        if (remaining <= 0) return;
        response.setHeader("Retry-After", Long.toString(Math.max(1, (remaining + 999) / 1000)));
        metrics.counter("fleetflow.route.limit", "class", policy.name, "outcome", "rejected")
                .increment();
        throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Request rate exceeded");
    }

    private long localFallback(String key, ClassPolicy policy) {
        synchronized (fallback) {
            long now = System.nanoTime();
            if (fallback.size() >= 10_000) {
                fallback.entrySet().removeIf(entry -> now >= entry.getValue().endsAt);
                if (fallback.size() >= 10_000 && !fallback.containsKey(key)) {
                    throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE",
                            "Request capacity control unavailable");
                }
            }
            LocalWindow window = fallback.compute(key, (ignored, previous) ->
                    previous == null || now >= previous.endsAt
                            ? new LocalWindow(now + policy.window.toNanos(), 1)
                            : new LocalWindow(previous.endsAt, previous.count + 1));
            return window.count > policy.limit ? Math.max(1, (window.endsAt - now) / 1_000_000) : 0;
        }
    }

    private ClassPolicy policy(String method, String path) {
        if ("GET".equals(method)) {
            if (path.endsWith("/tracking/stream")) {
                return new ClassPolicy("stream", streamLimit, Duration.ofMinutes(1));
            }
            if (path.contains("/tracking") || path.startsWith("/api/v1/dispatch/")
                    || path.endsWith("/zones") || path.endsWith("/zone-policy")) {
                return new ClassPolicy("expensive_read", expensiveReadLimit, Duration.ofSeconds(10));
            }
            return new ClassPolicy("read", readLimit, Duration.ofSeconds(10));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/drivers/me/location")
                || path.equals("/api/v1/drivers/me/heartbeat"))) return null;
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method)) {
            return new ClassPolicy("command", commandLimit, Duration.ofSeconds(10));
        }
        return null;
    }

    private record ClassPolicy(String name, int limit, Duration window) { }
    private record LocalWindow(long endsAt, int count) { }
}
