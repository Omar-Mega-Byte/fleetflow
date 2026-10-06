package com.fleetflow.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Called only inside the owning business transaction. Never contacts Kafka. */
@Component
public class OutboxEvents {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxEvents(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void orderStatusChanged(UUID orderId, long version, String previous, String status,
                                   Instant occurredAt) {
        insert(new EventEnvelope(UUID.randomUUID(), 1, "ORDER_STATUS_CHANGED", "ORDER", orderId,
                occurredAt, Map.of("orderId", orderId.toString(), "previousStatus", previous,
                        "newStatus", status, "orderVersion", Long.toString(version))));
    }

    public void driverOfferCreated(UUID orderId, UUID assignmentId, Instant occurredAt) {
        insert(new EventEnvelope(UUID.randomUUID(), 1, "ASSIGNMENT_OFFERED", "ORDER", orderId,
                occurredAt, Map.of("orderId", orderId.toString(),
                        "assignmentId", assignmentId.toString())));
    }

    private void insert(EventEnvelope event) {
        jdbc.update("""
                insert into outbox_events
                  (id, aggregate_id, aggregate_version, event_type, schema_version, envelope, occurred_at)
                values (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, event.eventId(), event.aggregateId(),
                "ORDER_STATUS_CHANGED".equals(event.eventType())
                        ? Long.valueOf(event.payload().get("orderVersion")) : null,
                event.eventType(), event.schemaVersion(), mapper.writeValueAsString(event),
                Timestamp.from(event.occurredAt()));
    }
}
