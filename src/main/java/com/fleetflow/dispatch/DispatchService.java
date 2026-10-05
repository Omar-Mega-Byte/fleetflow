package com.fleetflow.dispatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.location.DriverEligibility;
import com.fleetflow.location.LocationService;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatchService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final LocationService locations;
    private final DriverEligibility eligibility;
    private final MatchingProperties config;
    private final Clock clock;
    private final DispatchCommit commit;

    DispatchService(JdbcTemplate jdbc, ActorAccess access, LocationService locations,
                    DriverEligibility eligibility, MatchingProperties config, Clock clock, DispatchCommit commit) {
        this.jdbc = jdbc; this.access = access; this.locations = locations;
        this.eligibility = eligibility; this.config = config; this.clock = clock; this.commit = commit;
        if (config.radiusMeters() > locations.policy().maxRadiusMeters()
                || config.candidateLimit() > locations.policy().maxResults()) {
            throw new IllegalArgumentException("Matching search exceeds location policy");
        }
    }

    public StartResult start(UUID orderId) {
        UUID actorId = access.require(Role.DISPATCHER).id();
        var orders = jdbc.query("select status, pickup_latitude, pickup_longitude from orders where id = ?",
                (rs, row) -> new Pickup(rs.getString("status"), rs.getBigDecimal("pickup_latitude"),
                        rs.getBigDecimal("pickup_longitude")), orderId);
        if (orders.isEmpty()) throw missing();
        Pickup pickup = orders.getFirst();
        if (!"READY_FOR_PICKUP".equals(pickup.status())) throw conflict();
        Instant startedAt = clock.instant();
        Instant decisionAt = clock.instant();
        var inspected = locations.inspect(pickup.latitude().doubleValue(), pickup.longitude().doubleValue(),
                config.radiusMeters(), config.candidateLimit(), decisionAt);
        Map<UUID, String> durable = eligibility.matchingReasons(inspected.stream()
                .map(LocationService.InspectedDriver::driverId).toList());
        List<MatchingScore.Rankable> eligible = new ArrayList<>();
        Map<UUID, Candidate> snapshots = new HashMap<>();
        for (var hit : inspected) {
            BigDecimal distance = Double.isFinite(hit.distanceMeters())
                    ? BigDecimal.valueOf(hit.distanceMeters()).setScale(6, RoundingMode.HALF_UP) : null;
            Long age = hit.gpsAgeMillis() < 0 ? null : hit.gpsAgeMillis();
            String reason = hit.redisReason() != null ? hit.redisReason()
                    : durable.getOrDefault(hit.driverId(), "DRIVER_NOT_AVAILABLE");
            snapshots.put(hit.driverId(), new Candidate(hit.driverId(), reason, distance, age,
                    null, null, null, null, null));
            if ("ELIGIBLE".equals(reason)) {
                eligible.add(new MatchingScore.Rankable(hit.driverId(), distance, age,
                        MatchingScore.score(distance, age, config.radiusMeters(),
                                locations.policy().maxGpsAge().toMillis(), config)));
            }
        }
        int rank = 0;
        for (var ranked : MatchingScore.ranked(eligible)) {
            if (++rank > config.candidateLimit()) {
                Candidate prior = snapshots.get(ranked.driverId());
                snapshots.put(ranked.driverId(), new Candidate(prior.driverId(), "CANDIDATE_LIMIT",
                        prior.distanceMeters(), prior.gpsAgeMillis(), null, null, null, null, null));
                continue;
            }
            var score = ranked.result();
            snapshots.put(ranked.driverId(), new Candidate(ranked.driverId(), "ELIGIBLE",
                    ranked.distanceMeters(), ranked.gpsAgeMillis(), score.distanceFactor(),
                    score.freshnessFactor(), score.score(), rank, score.straightLineSeconds()));
        }
        String outcome = inspected.isEmpty() ? "NO_NEARBY_CANDIDATE"
                : eligible.isEmpty() ? "NO_ELIGIBLE_CANDIDATE" : "RANKED";
        Instant completedAt = clock.instant();
        return commit.save(orderId, actorId, startedAt, decisionAt, completedAt, outcome,
                snapshots.values().stream().sorted(java.util.Comparator.comparing(Candidate::driverId)).toList(),
                config, locations.policy(), locations.geoCap(config.candidateLimit()));
    }

    @Transactional(readOnly = true)
    public AttemptPage attempts(UUID orderId, int limit) {
        access.require(Role.DISPATCHER);
        if (limit < 1 || limit > 20) throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid limit");
        Boolean exists = jdbc.queryForObject("select exists(select 1 from orders where id = ?)", Boolean.class, orderId);
        if (!Boolean.TRUE.equals(exists)) throw missing();
        var headers = jdbc.query("""
                select id, sequence, started_at, completed_at, decision_at, outcome, config_version,
                       radius_meters, candidate_limit, geo_cap, max_gps_age_millis,
                       heartbeat_age_millis, distance_weight, freshness_weight, estimate_metres_per_second
                from dispatch_attempts where order_id = ? order by sequence desc limit ?
                """, (rs, row) -> new Header((UUID) rs.getObject("id"), rs.getInt("sequence"),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("completed_at").toInstant(),
                rs.getTimestamp("decision_at").toInstant(), rs.getString("outcome"),
                rs.getString("config_version"), rs.getInt("radius_meters"), rs.getInt("candidate_limit"),
                rs.getInt("geo_cap"), rs.getLong("max_gps_age_millis"),
                rs.getLong("heartbeat_age_millis"), rs.getBigDecimal("distance_weight"),
                rs.getBigDecimal("freshness_weight"), rs.getBigDecimal("estimate_metres_per_second")),
                orderId, limit);
        if (headers.isEmpty()) return new AttemptPage(List.of());
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        Map<UUID, List<Candidate>> byAttempt = new HashMap<>();
        named.query("""
                select attempt_id, driver_id, reason, distance_meters, gps_age_millis,
                       distance_factor, freshness_factor, score, rank, straight_line_seconds
                from dispatch_candidates where attempt_id in (:ids)
                order by attempt_id, rank asc nulls last, driver_id
                """, Map.of("ids", headers.stream().map(Header::id).toList()), rs -> {
            UUID id = (UUID) rs.getObject("attempt_id");
            Integer rank = (Integer) rs.getObject("rank");
            Integer seconds = (Integer) rs.getObject("straight_line_seconds");
            Long age = (Long) rs.getObject("gps_age_millis");
            byAttempt.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Candidate(
                    (UUID) rs.getObject("driver_id"), rs.getString("reason"),
                    rs.getBigDecimal("distance_meters"), age, rs.getBigDecimal("distance_factor"),
                    rs.getBigDecimal("freshness_factor"), rs.getBigDecimal("score"), rank, seconds));
        });
        return new AttemptPage(headers.stream().map(h -> new Attempt(h.id(), h.sequence(), h.startedAt(), h.completedAt(),
                h.decisionAt(), h.outcome(), h.configVersion(), h.radiusMeters(), h.candidateLimit(),
                h.geoCap(), h.maxGpsAgeMillis(), h.heartbeatAgeMillis(), h.distanceWeight(),
                h.freshnessWeight(), h.estimateMetresPerSecond(),
                List.copyOf(byAttempt.getOrDefault(h.id(), List.of())))).toList());
    }

    static ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "ORDER_STATE_CONFLICT", "Order state does not allow matching");
    }
    private static ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    private record Pickup(String status, BigDecimal latitude, BigDecimal longitude) { }
    private record Header(UUID id, int sequence, Instant startedAt, Instant completedAt, Instant decisionAt,
                          String outcome, String configVersion, int radiusMeters, int candidateLimit, int geoCap,
                          long maxGpsAgeMillis, long heartbeatAgeMillis, BigDecimal distanceWeight,
                          BigDecimal freshnessWeight, BigDecimal estimateMetresPerSecond) { }
    public record Candidate(UUID driverId, String reason, BigDecimal distanceMeters, Long gpsAgeMillis,
                            BigDecimal distanceFactor, BigDecimal freshnessFactor, BigDecimal score,
                            Integer rank, Integer straightLineSeconds) { }
    public record Attempt(UUID id, int sequence, Instant startedAt, Instant completedAt, Instant decisionAt,
                          String outcome, String configVersion, int radiusMeters, int candidateLimit, int geoCap,
                          long maxGpsAgeMillis, long heartbeatAgeMillis, BigDecimal distanceWeight,
                          BigDecimal freshnessWeight, BigDecimal estimateMetresPerSecond,
                          List<Candidate> candidates) { }
    public record StartResult(UUID attemptId, String outcome) { }
    public record AttemptPage(List<Attempt> content) { }
}
