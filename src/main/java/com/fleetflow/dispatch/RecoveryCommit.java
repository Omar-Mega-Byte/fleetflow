package com.fleetflow.dispatch;

import java.util.UUID;

import com.fleetflow.lifecycle.RecoveryTransitions;
import com.fleetflow.zone.ZoneService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecoveryCommit {
    private final JdbcTemplate jdbc;
    private final RecoveryQueue queue;
    private final RecoveryTransitions transitions;
    private final ZoneService zones;

    RecoveryCommit(JdbcTemplate jdbc, RecoveryQueue queue, RecoveryTransitions transitions,
                   ZoneService zones) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.transitions = transitions;
        this.zones = zones;
    }

    @Transactional
    public void skip(UUID orderId, UUID attemptId, UUID candidateId, String reason) {
        jdbc.execute("set local lock_timeout = '2s'");
        var states = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString("status"), orderId);
        if (states.isEmpty() || !"DISPATCHING".equals(states.getFirst()) || hasActiveOffer(orderId)) {
            queue.resolve(orderId, "ORDER_CHANGED");
            return;
        }
        jdbc.update("""
                insert into dispatch_candidate_recovery (candidate_id, order_id, reason, recorded_at)
                select c.id, a.order_id, ?, clock_timestamp()
                from dispatch_candidates c join dispatch_attempts a on a.id = c.attempt_id
                where c.id = ? and a.id = ? and a.order_id = ?
                  and a.sequence = (select max(sequence) from dispatch_attempts where order_id = ?)
                on conflict (candidate_id) do nothing
                """, reason, candidateId, attemptId, orderId, orderId);
        queue.schedule(orderId, "CANDIDATE_SKIPPED", 0);
    }

    @Transactional
    public void candidatesExhausted(UUID orderId, int attemptSequence) {
        jdbc.execute("set local lock_timeout = '2s'");
        var states = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString("status"), orderId);
        if (states.isEmpty() || !"DISPATCHING".equals(states.getFirst()) || hasActiveOffer(orderId)) {
            queue.resolve(orderId, "ORDER_CHANGED");
            return;
        }
        transitions.order(orderId, "DISPATCHING", "DISPATCH_FAILED", null,
                "CANDIDATES_EXHAUSTED", "All ranked candidates were tried or became ineligible");
        if (attemptSequence >= 3) exhaust(orderId, "CANDIDATES_EXHAUSTED");
        else queue.schedule(orderId, "CANDIDATES_EXHAUSTED", attemptSequence * 30);
    }

    @Transactional
    public void failedMatchExhausted(UUID orderId) {
        jdbc.execute("set local lock_timeout = '2s'");
        var states = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString("status"), orderId);
        if (states.isEmpty() || !"DISPATCH_FAILED".equals(states.getFirst())) {
            queue.resolve(orderId, "ORDER_CHANGED");
            return;
        }
        exhaust(orderId, "NO_DRIVER");
    }

    @Transactional
    public void zoneBlocked(UUID orderId) {
        jdbc.execute("set local lock_timeout = '2s'");
        var states = jdbc.query("select status from orders where id = ? for update",
                (rs, row) -> rs.getString(1), orderId);
        if (states.isEmpty() || !"DISPATCHING".equals(states.getFirst())) {
            queue.resolve(orderId, "ORDER_CHANGED");
            return;
        }
        if (zones.forLockedOrder(orderId).supported()) {
            queue.schedule(orderId, "ZONE_CHANGED_RETRY", 0);
            return;
        }
        transitions.order(orderId, "DISPATCHING", "DISPATCH_FAILED", null,
                "ZONE_UNSERVICEABLE", "Destination outside current branch zone");
        queue.schedule(orderId, "ZONE_UNSERVICEABLE", 0);
        queue.exhaust(orderId, "ZONE_UNSERVICEABLE");
        jdbc.update("""
                insert into dispatch_incidents (id, order_id, code, reason, status, opened_at)
                values (?, ?, 'ZONE_UNSERVICEABLE', 'Destination outside current branch zone',
                        'OPEN', clock_timestamp()) on conflict do nothing
                """, UUID.randomUUID(), orderId);
    }

    private void exhaust(UUID orderId, String reason) {
        queue.exhaust(orderId, reason);
        jdbc.update("""
                insert into dispatch_incidents
                (id, order_id, code, reason, status, opened_at)
                values (?, ?, 'NO_DRIVER', ?, 'OPEN', clock_timestamp())
                on conflict do nothing
                """, UUID.randomUUID(), orderId, reason);
    }

    private boolean hasActiveOffer(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from driver_assignments
                    where order_id = ? and status in ('OFFERED', 'ACCEPTED'))
                """, Boolean.class, orderId));
    }
}
