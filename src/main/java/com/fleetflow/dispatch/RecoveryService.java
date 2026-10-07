package com.fleetflow.dispatch;

import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RecoveryService {
    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);
    private final JdbcTemplate jdbc;
    private final RecoveryQueue queue;
    private final RecoveryCommit commit;
    private final AssignmentService assignments;
    private final DispatchService dispatch;
    private final RecoveryProperties config;

    RecoveryService(JdbcTemplate jdbc, RecoveryQueue queue, RecoveryCommit commit,
                    AssignmentService assignments, DispatchService dispatch, RecoveryProperties config) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.commit = commit;
        this.assignments = assignments;
        this.dispatch = dispatch;
        this.config = config;
    }

    public int sweep() {
        int claimed = 0;
        for (UUID orderId : queue.due(config.batchSize())) {
            UUID token = queue.claim(orderId);
            if (token == null) continue;
            claimed++;
            try {
                process(orderId, token);
            } catch (RuntimeException failure) {
                log.atWarn().addKeyValue("event", "dispatch.recovery_failed")
                        .addKeyValue("orderId", orderId)
                        .addKeyValue("failureType", failure.getClass().getSimpleName())
                        .log("Dispatch recovery failed; it remains retryable");
                queue.rescheduleClaim(orderId, token, "RECOVERY_ERROR", 30);
            }
        }
        return claimed;
    }

    private void process(UUID orderId, UUID token) {
        var rows = jdbc.query("""
                select o.status, a.id attempt_id, a.sequence, a.outcome
                from orders o join dispatch_attempts a on a.order_id = o.id
                where o.id = ? and a.sequence =
                    (select max(sequence) from dispatch_attempts where order_id = o.id)
                """, (rs, row) -> new Work(rs.getString("status"),
                (UUID) rs.getObject("attempt_id"), rs.getInt("sequence"), rs.getString("outcome")), orderId);
        if (rows.isEmpty()) { queue.resolve(orderId, "ORDER_MISSING"); return; }
        Work work = rows.getFirst();
        if ("DISPATCH_FAILED".equals(work.status())) {
            if (work.sequence() >= 3) { commit.failedMatchExhausted(orderId); return; }
            try {
                dispatch.retrySystem(orderId);
            } catch (ApiFailure failure) {
                queue.rescheduleClaim(orderId, token, failure.code(), 30);
            }
            return;
        }
        if (!"DISPATCHING".equals(work.status())) {
            queue.resolve(orderId, "ORDER_CHANGED");
            return;
        }
        var active = jdbc.query("""
                select expires_at from driver_assignments where order_id = ? and status = 'OFFERED'
                """, (rs, row) -> rs.getTimestamp("expires_at").toInstant(), orderId);
        if (!active.isEmpty()) {
            queue.rescheduleClaim(orderId, token, "OFFER_ACTIVE", 5);
            return;
        }
        var next = jdbc.query("""
                select c.id, c.driver_id from dispatch_candidates c
                where c.attempt_id = ? and c.reason = 'ELIGIBLE' and c.rank is not null
                  and not exists (select 1 from driver_assignments a where a.candidate_id = c.id)
                  and not exists (select 1 from dispatch_candidate_recovery r where r.candidate_id = c.id)
                order by c.rank limit 1
                """, (rs, row) -> new Candidate((UUID) rs.getObject("id"),
                (UUID) rs.getObject("driver_id")), work.attemptId());
        if (next.isEmpty()) {
            commit.candidatesExhausted(orderId, work.sequence());
            return;
        }
        Candidate candidate = next.getFirst();
        try {
            AssignmentService.OfferReceipt offered = assignments.offerSystem(orderId, work.attemptId(),
                    candidate.driverId());
            int delay = Math.max(1, (int) Math.min(300,
                    offered.expiresAt().getEpochSecond() - Instant.now().getEpochSecond()));
            queue.rescheduleClaim(orderId, token, "OFFERED", delay);
        } catch (ApiFailure failure) {
            if ("ZONE_UNSERVICEABLE".equals(failure.code())) {
                // AssignmentService has already committed the zone block and exhausted this work item.
            } else if (failure.status().is5xxServerError()) {
                queue.rescheduleClaim(orderId, token, failure.code(), 30);
            } else if (activeOfferExists(orderId)) {
                queue.rescheduleClaim(orderId, token, "OFFER_ACTIVE", 5);
            } else {
                commit.skip(orderId, work.attemptId(), candidate.id(), failure.code());
            }
        }
    }

    private boolean activeOfferExists(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from driver_assignments
                    where order_id = ? and status in ('OFFERED', 'ACCEPTED'))
                """, Boolean.class, orderId));
    }

    private record Work(String status, UUID attemptId, int sequence, String outcome) { }
    private record Candidate(UUID id, UUID driverId) { }
}
