package com.fleetflow.events;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OutboxRecoveryService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    OutboxRecoveryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    FailedPage failed(int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Limit must be between 1 and 100");
        }
        return new FailedPage(jdbc.query("""
                select id, aggregate_id, event_type, attempts, replay_count, last_error, occurred_at
                from outbox_events where status = 'FAILED'
                order by occurred_at, id limit ?
                """, (rs, row) -> new FailedEvent((UUID) rs.getObject("id"),
                (UUID) rs.getObject("aggregate_id"), rs.getString("event_type"),
                rs.getInt("attempts"), rs.getInt("replay_count"), rs.getString("last_error"),
                rs.getTimestamp("occurred_at").toInstant()), limit));
    }

    @Transactional
    ReplayResult replay(UUID actorId, UUID eventId, String key, String suppliedReason) {
        if (key == null || !key.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid Idempotency-Key");
        }
        String reason = suppliedReason == null ? "" : suppliedReason.strip();
        if (reason.isEmpty() || reason.length() > 500)
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason must have 1 to 500 characters");
        String hash = sha256(eventId + "\n" + reason);
        jdbc.queryForObject("select id from actors where id = ? for update", UUID.class, actorId);
        var existing = jdbc.query("""
                select request_hash, result_code from operator_action_audit
                where actor_id = ? and action = 'OUTBOX_REPLAY' and target_id = ? and idempotency_key = ?
                for update
                """, (rs, row) -> new PriorAction(rs.getString(1), rs.getString(2)), actorId, eventId, key);
        if (!existing.isEmpty()) {
            PriorAction prior = existing.getFirst();
            if (!prior.requestHash().equals(hash)) {
                throw new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            return new ReplayResult(eventId, prior.resultCode());
        }

        var rows = jdbc.query("select status, replay_count from outbox_events where id = ? for update",
                (rs, row) -> new ReplayState(rs.getString(1), rs.getInt(2)), eventId);
        String result = "NOT_REPLAYABLE";
        if (!rows.isEmpty() && "FAILED".equals(rows.getFirst().status())) {
            if (rows.getFirst().replayCount() >= 3) result = "REPLAY_LIMIT_REACHED";
            else if (jdbc.update("""
                    update outbox_events set status = 'PENDING', attempts = 0,
                        replay_count = replay_count + 1, next_attempt_at = clock_timestamp(),
                        lease_token = null, lease_until = null, last_error = null
                    where id = ? and status = 'FAILED' and replay_count < 3
                    """, eventId) == 1) result = "REPLAYED";
        }
        jdbc.update("""
                insert into operator_action_audit
                  (id, actor_id, action, target_id, idempotency_key, request_hash,
                   reason, result_code, occurred_at)
                values (?, ?, 'OUTBOX_REPLAY', ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actorId, eventId, key, hash, reason, result, Timestamp.from(clock.instant()));
        return new ReplayResult(eventId, result);
    }

    private String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint operator action", failure);
        }
    }

    record FailedEvent(UUID eventId, UUID aggregateId, String eventType, int attempts,
                       int replayCount, String errorCode, Instant occurredAt) { }
    record FailedPage(List<FailedEvent> content) { }
    record ReplayResult(UUID eventId, String outcome) { }
    private record PriorAction(String requestHash, String resultCode) { }
    private record ReplayState(String status, int replayCount) { }
}
