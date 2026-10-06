package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.location.LocationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchCommit {
    private final JdbcTemplate jdbc;
    private final LifecycleHistory lifecycle;
    private final RecoveryQueue recovery;

    DispatchCommit(JdbcTemplate jdbc, LifecycleHistory lifecycle, RecoveryQueue recovery) {
        this.jdbc = jdbc;
        this.lifecycle = lifecycle;
        this.recovery = recovery;
    }

    @Transactional
    public DispatchService.StartResult save(UUID orderId, UUID actorId, String fromStatus,
            String reason, Instant startedAt,
            Instant decisionAt, Instant completedAt, String outcome, List<DispatchService.Candidate> candidates,
            MatchingProperties config, LocationProperties location, int geoCap) {
        var claimed = jdbc.query("""
                update orders set status = 'DISPATCHING', version = version + 1, updated_at = ?
                where id = ? and status = ? returning version
                """, (rs, row) -> rs.getLong("version"), Timestamp.from(completedAt), orderId, fromStatus);
        if (claimed.size() != 1) throw DispatchService.conflict();
        history(orderId, actorId, fromStatus, "DISPATCHING", completedAt,
                claimed.getFirst(), actorId == null ? "AUTO_RETRY" :
                        reason == null ? "MATCH" : "MANUAL_RETRY", reason);
        if (actorId != null && reason != null) {
            jdbc.update("""
                    update dispatch_incidents set status = 'RESOLVED', resolved_at = clock_timestamp(),
                        resolved_by_actor_id = ?, resolution = ?
                    where order_id = ? and code = 'NO_DRIVER' and status = 'OPEN'
                    """, actorId, reason, orderId);
        }
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
            var failed = jdbc.query("""
                    update orders set status = 'DISPATCH_FAILED', version = version + 1, updated_at = ?
                    where id = ? and status = 'DISPATCHING' returning version
                    """, (rs, row) -> rs.getLong("version"), Timestamp.from(completedAt), orderId);
            if (failed.size() != 1) throw DispatchService.conflict();
            history(orderId, actorId, "DISPATCHING", "DISPATCH_FAILED", completedAt,
                    failed.getFirst(), "MATCH_FAILED", outcome);
            if (sequence >= 3) recovery.schedule(orderId, "MATCH_EXHAUSTED", 0);
            else recovery.schedule(orderId, outcome, sequence * 30);
        } else if (!"READY_FOR_PICKUP".equals(fromStatus)) {
            recovery.schedule(orderId, "RANKED_RETRY", 0);
        }
        return new DispatchService.StartResult(attemptId, outcome);
    }

    private void history(UUID orderId, UUID actorId, String from, String to, Instant when,
                         long version, String action, String reason) {
        jdbc.update("""
                insert into order_status_history
                (id, order_id, actor_id, source, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, actorId, actorId == null ? "SYSTEM" : "ACTOR",
                from, to, Timestamp.from(when));
        if (actorId == null) lifecycle.orderSystem(orderId, version, from, to, action, when, reason);
        else lifecycle.order(orderId, version, from, to, action, when, actorId, reason);
    }
}
