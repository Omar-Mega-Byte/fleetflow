package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fleetflow.location.LocationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchCommit {
    private final JdbcTemplate jdbc;

    DispatchCommit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public DispatchService.StartResult save(UUID orderId, UUID actorId, Instant startedAt,
            Instant decisionAt, Instant completedAt, String outcome, List<DispatchService.Candidate> candidates,
            MatchingProperties config, LocationProperties location, int geoCap) {
        int claimed = jdbc.update("""
                update orders set status = 'DISPATCHING', updated_at = ?
                where id = ? and status = 'READY_FOR_PICKUP'
                """, Timestamp.from(completedAt), orderId);
        if (claimed != 1) throw DispatchService.conflict();
        history(orderId, actorId, "READY_FOR_PICKUP", "DISPATCHING", completedAt);
        UUID attemptId = UUID.randomUUID();
        int sequence = jdbc.queryForObject("select coalesce(max(sequence), 0) + 1 from dispatch_attempts where order_id = ?",
                Integer.class, orderId);
        jdbc.update("""
                insert into dispatch_attempts (id, order_id, sequence, started_at, completed_at, decision_at,
                  outcome, config_version, radius_meters, candidate_limit, geo_cap, max_gps_age_millis,
                  heartbeat_age_millis, distance_weight, freshness_weight, estimate_metres_per_second)
                values (?, ?, ?, ?, ?, ?, ?, 'v1', ?, ?, ?, ?, ?, ?, ?, ?)
                """, attemptId, orderId, sequence, Timestamp.from(startedAt), Timestamp.from(completedAt),
                Timestamp.from(decisionAt), outcome, config.radiusMeters(), config.candidateLimit(), geoCap,
                location.maxGpsAge().toMillis(), location.heartbeatTtl().toMillis(), config.distanceWeight(),
                config.freshnessWeight(), config.metresPerSecond());
        for (var candidate : candidates) {
            jdbc.update("""
                    insert into dispatch_candidates (id, attempt_id, driver_id, reason, distance_meters,
                      gps_age_millis, distance_factor, freshness_factor, score, rank, straight_line_seconds)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), attemptId, candidate.driverId(), candidate.reason(),
                    candidate.distanceMeters(), candidate.gpsAgeMillis(), candidate.distanceFactor(),
                    candidate.freshnessFactor(), candidate.score(), candidate.rank(), candidate.straightLineSeconds());
        }
        if (!"RANKED".equals(outcome)) {
            int failed = jdbc.update("update orders set status = 'DISPATCH_FAILED', updated_at = ? "
                    + "where id = ? and status = 'DISPATCHING'", Timestamp.from(completedAt), orderId);
            if (failed != 1) throw DispatchService.conflict();
            history(orderId, actorId, "DISPATCHING", "DISPATCH_FAILED", completedAt);
        }
        return new DispatchService.StartResult(attemptId, outcome);
    }

    private void history(UUID orderId, UUID actorId, String from, String to, Instant when) {
        jdbc.update("""
                insert into order_status_history (id, order_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, actorId, from, to, Timestamp.from(when));
    }
}
