package com.fleetflow.dispatch;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Durable work state. Mutators join the caller's transaction when one is active. */
@Component
class RecoveryQueue {
    private final JdbcTemplate jdbc;

    RecoveryQueue(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void schedule(UUID orderId, String reason, int delaySeconds) {
        jdbc.update("""
                insert into dispatch_recovery
                (order_id, status, next_action_at, last_reason, updated_at)
                values (?, 'PENDING', clock_timestamp() + (? * interval '1 second'), ?, clock_timestamp())
                on conflict (order_id) do update set status = 'PENDING',
                    next_action_at = excluded.next_action_at, last_reason = excluded.last_reason,
                    lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
                """, orderId, delaySeconds, reason);
    }

    void resolve(UUID orderId, String reason) {
        jdbc.update("""
                update dispatch_recovery set status = 'RESOLVED', last_reason = ?,
                    lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
                where order_id = ?
                """, reason, orderId);
    }

    void exhaust(UUID orderId, String reason) {
        jdbc.update("""
                update dispatch_recovery set status = 'EXHAUSTED', last_reason = ?,
                    lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
                where order_id = ?
                """, reason, orderId);
    }

    List<UUID> due(int limit) {
        return jdbc.query("""
                select order_id from dispatch_recovery
                where status = 'PENDING' and next_action_at <= clock_timestamp()
                  and (lease_until is null or lease_until <= clock_timestamp())
                order by next_action_at, order_id limit ?
                """, (rs, row) -> (UUID) rs.getObject("order_id"), limit);
    }

    UUID claim(UUID orderId) {
        UUID token = UUID.randomUUID();
        int changed = jdbc.update("""
                update dispatch_recovery set lease_token = ?,
                    lease_until = clock_timestamp() + interval '30 seconds', updated_at = clock_timestamp()
                where order_id = ? and status = 'PENDING' and next_action_at <= clock_timestamp()
                  and (lease_until is null or lease_until <= clock_timestamp())
                """, token, orderId);
        return changed == 1 ? token : null;
    }

    void rescheduleClaim(UUID orderId, UUID token, String reason, int delaySeconds) {
        jdbc.update("""
                update dispatch_recovery set next_action_at = clock_timestamp() + (? * interval '1 second'),
                    last_reason = ?, lease_token = NULL, lease_until = NULL, updated_at = clock_timestamp()
                where order_id = ? and lease_token = ? and status = 'PENDING'
                """, delaySeconds, reason, orderId, token);
    }
}
