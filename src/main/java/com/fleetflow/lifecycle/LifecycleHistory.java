package com.fleetflow.lifecycle;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Insert-only history writer. Call only from the aggregate's transaction. */
@Component
public class LifecycleHistory {
    private final JdbcTemplate jdbc;

    public LifecycleHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void order(UUID id, long version, String previous, String next, String action,
                      Instant when, UUID actorId, String reason) {
        jdbc.update("""
                insert into order_lifecycle_history
                (order_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, reason, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTOR', ?, ?)
                """, id, version, previous, next, action, Timestamp.from(when), actorId, reason, correlationId());
    }

    public void driver(UUID id, long version, String previous, String next, String action,
                       Instant when, UUID actorId) {
        jdbc.update("""
                insert into driver_lifecycle_history
                (driver_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTOR', ?)
                """, id, version, previous, next, action, Timestamp.from(when), actorId, correlationId());
    }

    private String correlationId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object value = attributes.getRequest().getAttribute("phase1CorrelationId");
            if (value instanceof String id && !id.isBlank()) {
                return id;
            }
        }
        return UUID.randomUUID().toString();
    }
}
