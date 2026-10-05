package com.fleetflow.location;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LocationService {
    private final DriverEligibility eligibility;
    private final RedisLocationStore redis;
    private final LocationProperties settings;
    private final Clock clock;
    private final MeterRegistry metrics;

    LocationService(DriverEligibility eligibility, RedisLocationStore redis, LocationProperties settings,
                    Clock clock, MeterRegistry metrics) {
        this.eligibility = eligibility;
        this.redis = redis;
        this.settings = settings;
        this.clock = clock;
        this.metrics = metrics;
    }

    public void update(LocationRequest request) {
        UUID id = eligibility.requireSelfOperational();
        Instant now = clock.instant();
        validate(request, now);
        LocationFix fix = new LocationFix(id, request.latitude(), request.longitude(), request.heading(),
                request.speed(), request.reportedAt(), now);
        try {
            long outcome = redis.accept(fix);
            if (outcome == 1) {
                count("location", "old");
                throw new ApiFailure(HttpStatus.CONFLICT, "LOCATION_OUT_OF_ORDER", "Location fix is not newer");
            }
            if (outcome == 2) {
                count("location", "rate_limited");
                throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "LOCATION_RATE_LIMITED",
                        "Location update rate exceeded");
            }
            if (outcome != 0) throw unavailable();
            count("location", "accepted");
        } catch (DataAccessException failure) {
            count("redis", "failure");
            throw unavailable();
        }
    }

    public void heartbeat() {
        UUID id = eligibility.requireSelfOperational();
        try {
            long outcome = redis.heartbeat(id, clock.instant());
            if (outcome == 2) {
                count("heartbeat", "rate_limited");
                throw new ApiFailure(HttpStatus.TOO_MANY_REQUESTS, "HEARTBEAT_RATE_LIMITED",
                        "Heartbeat rate exceeded");
            }
            if (outcome != 0) throw unavailable();
            count("heartbeat", "accepted");
        } catch (DataAccessException failure) {
            count("redis", "failure");
            throw unavailable();
        }
    }

    // Internal snapshot only. Phase 5 must recheck eligibility before a decision.
    public List<NearbyDriver> nearby(double latitude, double longitude, int radiusMeters, int limit) {
        if (!validLatitude(latitude) || !validLongitude(longitude) || radiusMeters < 1
                || radiusMeters > settings.maxRadiusMeters() || limit < 1 || limit > settings.maxResults()) {
            throw new IllegalArgumentException("Invalid nearby search bounds");
        }
        int fetch = Math.min(settings.overfetchCap(), Math.max(limit, limit * settings.overfetchFactor()));
        Instant now = clock.instant();
        List<NearbyDriver> fresh = new ArrayList<>();
        try {
            List<RedisLocationStore.GeoHit> hits = redis.nearby(latitude, longitude, radiusMeters, fetch);
            for (var hit : hits) {
                LocationFix fix = redis.latest(hit.driverId());
                Instant beat = redis.lastHeartbeat(hit.driverId());
                if (fix == null) {
                    redis.removeOrphan(hit.driverId());
                    count("nearby_filtered", "missing_location");
                    continue;
                }
                if (beat == null || !fresh(fix.receivedAt(), now, settings.maxGpsAge())
                        || !fresh(fix.reportedAt(), now, settings.maxGpsAge())
                        || !fresh(beat, now, settings.heartbeatTtl())) {
                    count("nearby_filtered", "stale");
                    continue;
                }
                double actualDistance = distanceMeters(latitude, longitude, fix.latitude(), fix.longitude());
                if (actualDistance > radiusMeters) {
                    count("nearby_filtered", "radius");
                    continue;
                }
                fresh.add(new NearbyDriver(hit.driverId(), actualDistance, fix));
            }
        } catch (DataAccessException failure) {
            count("redis", "failure");
            throw unavailable();
        }
        // Query PostgreSQL only after Redis I/O is complete; keep its failure semantics distinct.
        Set<UUID> eligible = eligibility.eligibleIds(fresh.stream().map(NearbyDriver::driverId).toList());
        List<NearbyDriver> result = new ArrayList<>();
        fresh.sort(java.util.Comparator.comparingDouble(NearbyDriver::distanceMeters)
                .thenComparing(NearbyDriver::driverId));
        for (NearbyDriver candidate : fresh) {
            if (!eligible.contains(candidate.driverId())) {
                count("nearby_filtered", "durable");
                continue;
            }
            result.add(candidate);
            if (result.size() == limit) break;
        }
        count("nearby", "completed");
        return List.copyOf(result);
    }

    private void validate(LocationRequest request, Instant now) {
        if (request == null || request.latitude() == null || request.longitude() == null
                || request.reportedAt() == null || !validLatitude(request.latitude())
                || !validLongitude(request.longitude())
                || (request.heading() != null && (!Double.isFinite(request.heading())
                        || request.heading() < 0 || request.heading() >= 360))
                || (request.speed() != null && (!Double.isFinite(request.speed())
                        || request.speed() < 0 || request.speed() > 100))
                || request.reportedAt().isBefore(now.minus(settings.maxIngestAge()))
                || request.reportedAt().isAfter(now.plus(settings.futureSkew()))) {
            count("location", "invalid");
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", "Invalid location update");
        }
    }

    private boolean fresh(Instant timestamp, Instant now, Duration maxAge) {
        return !timestamp.isAfter(now.plus(settings.futureSkew()))
                && !timestamp.isBefore(now.minus(maxAge));
    }
    private boolean validLatitude(double value) { return Double.isFinite(value) && value >= -90 && value <= 90; }
    private boolean validLongitude(double value) { return Double.isFinite(value) && value >= -180 && value <= 180; }
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
    }
    private ApiFailure unavailable() {
        return new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "REDIS_UNAVAILABLE", "Location service unavailable");
    }
    private void count(String operation, String outcome) {
        metrics.counter("fleetflow.location.operations", "operation", operation, "outcome", outcome).increment();
    }

    public record LocationRequest(Double latitude, Double longitude, Double heading, Double speed,
                                  Instant reportedAt) { }
    public record NearbyDriver(UUID driverId, double distanceMeters, LocationFix fix) { }
}
