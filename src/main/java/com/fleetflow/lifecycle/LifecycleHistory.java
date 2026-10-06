package com.fleetflow.lifecycle;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import com.fleetflow.events.OutboxEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Insert-only history writer. Call only from the aggregate's transaction. */
@Component
public class LifecycleHistory {
    private final JdbcTemplate jdbc;
    private final OutboxEvents outbox;

    public LifecycleHistory(JdbcTemplate jdbc, OutboxEvents outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void order(UUID id, long version, String previous, String next, String action,
                      Instant when, UUID actorId, String reason) {
        jdbc.update("""
                insert into order_lifecycle_history
                (order_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, reason, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTOR', ?, ?)
                """, id, version, previous, next, action, Timestamp.from(when), actorId, reason, correlationId());
        if (previous != null) outbox.orderStatusChanged(id, version, previous, next, when);
    }

    public void orderSystem(UUID id, long version, String previous, String next, String action,
                            Instant when, String reason) {
        jdbc.update("""
                insert into order_lifecycle_history
                (order_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, reason, correlation_id)
                values (?, ?, ?, ?, ?, ?, NULL, 'SYSTEM', ?, ?)
                """, id, version, previous, next, action, Timestamp.from(when), reason, correlationId());
        if (previous != null) outbox.orderStatusChanged(id, version, previous, next, when);
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

    public void driverSystem(UUID id, long version, String previous, String next, String action,
                             Instant when) {
        jdbc.update("""
                insert into driver_lifecycle_history
                (driver_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, correlation_id)
                values (?, ?, ?, ?, ?, ?, NULL, 'SYSTEM', ?)
                """, id, version, previous, next, action, Timestamp.from(when), correlationId());
    }

    public void delivery(UUID id, long version, String previous, String next, String action,
                         Instant when, UUID actorId) {
        jdbc.update("""
                insert into delivery_lifecycle_history
                (delivery_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTOR', ?)
                """, id, version, previous, next, action, Timestamp.from(when), actorId, correlationId());
    }

    public void deliverySystem(UUID id, long version, String previous, String next, String action,
                               Instant when) {
        jdbc.update("""
                insert into delivery_lifecycle_history
                (delivery_id, version, previous_state, new_state, action, occurred_at,
                 actor_id, source, correlation_id)
                values (?, ?, ?, ?, ?, ?, NULL, 'SYSTEM', ?)
                """, id, version, previous, next, action, Timestamp.from(when), correlationId());
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
