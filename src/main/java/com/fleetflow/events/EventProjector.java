package com.fleetflow.events;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Each side effect and its processed marker commit in one PostgreSQL transaction. */
@Service
public class EventProjector {
    private static final Set<String> STATUSES = Set.of("CREATED", "CONFIRMED", "PREPARING",
            "READY_FOR_PICKUP", "DISPATCHING", "DISPATCH_FAILED", "DRIVER_ASSIGNED",
            "PICKED_UP", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED", "CANCELLED");
    private static final Set<String> CUSTOMER_NOTICES = Set.of("CONFIRMED",
            "READY_FOR_PICKUP", "DISPATCH_FAILED", "DRIVER_ASSIGNED", "PICKED_UP",
            "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventProjector(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void notification(String json) {
        EventEnvelope event = decode(json);
        if (!mark("notification", event.eventId())) return;
        if ("ORDER_STATUS_CHANGED".equals(event.eventType())) {
            String status = event.payload().get("newStatus");
            if (!CUSTOMER_NOTICES.contains(status)) return;
            UUID recipient = jdbc.queryForObject("""
                    select c.actor_id from orders o join customers c on c.id = o.customer_id
                    where o.id = ?
                    """, UUID.class, event.aggregateId());
            insertNotification(event, recipient, null, status);
        } else {
            UUID assignment = UUID.fromString(event.payload().get("assignmentId"));
            var recipients = jdbc.query("""
                    select d.actor_id from driver_assignments a
                      join drivers d on d.id = a.driver_id
                    where a.id = ? and a.order_id = ?
                    """, (rs, row) -> (UUID) rs.getObject("actor_id"),
                    assignment, event.aggregateId());
            if (recipients.size() != 1) throw new InvalidEventException("Missing offer recipient");
            insertNotification(event, recipients.getFirst(), assignment, null);
        }
    }

    @Transactional
    public void analytics(String json) {
        EventEnvelope event = decode(json);
        if (!mark("analytics", event.eventId())) return;
        if (!"ORDER_STATUS_CHANGED".equals(event.eventType())) return;
        LocalDate day = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        jdbc.update("""
                insert into analytics_daily_order_transitions(day, status, total)
                values (?, ?, 1)
                on conflict (day, status) do update
                  set total = analytics_daily_order_transitions.total + 1
                """, Date.valueOf(day), event.payload().get("newStatus"));
    }

    @Transactional
    public void quarantine(String consumer, String topic, int partition, long offset, String errorCode) {
        jdbc.update("""
                insert into quarantined_events
                  (consumer_name, topic, partition_number, record_offset, error_code)
                values (?, ?, ?, ?, ?) on conflict do nothing
                """, consumer, topic, partition, offset, errorCode);
    }

    private EventEnvelope decode(String json) {
        EventEnvelope event;
        try {
            event = mapper.readValue(json, EventEnvelope.class);
        } catch (RuntimeException failure) {
            throw new InvalidEventException("Invalid event JSON");
        }
        if (event == null || event.eventId() == null || event.aggregateId() == null
                || event.occurredAt() == null || event.schemaVersion() != 1
                || !"ORDER".equals(event.aggregateType())
                || event.payload() == null || event.eventType() == null
                || !("ORDER_STATUS_CHANGED".equals(event.eventType())
                    || "ASSIGNMENT_OFFERED".equals(event.eventType()))) {
            throw new InvalidEventException("Unsupported event envelope");
        }
        if ("ORDER_STATUS_CHANGED".equals(event.eventType())) {
            try {
                if (!event.aggregateId().toString().equals(event.payload().get("orderId"))
                        || !STATUSES.contains(event.payload().get("previousStatus"))
                        || !STATUSES.contains(event.payload().get("newStatus"))
                        || Long.parseLong(event.payload().get("orderVersion")) < 1) {
                    throw new InvalidEventException("Invalid order status event");
                }
            } catch (RuntimeException failure) {
                throw new InvalidEventException("Invalid order status event");
            }
        } else {
            try {
                if (!event.aggregateId().toString().equals(event.payload().get("orderId")))
                    throw new InvalidEventException("Invalid offer order");
                UUID.fromString(event.payload().get("assignmentId"));
            } catch (RuntimeException failure) {
                throw new InvalidEventException("Invalid offer event");
            }
        }
        Boolean known = jdbc.queryForObject("""
                select exists(select 1 from outbox_events where id = ? and aggregate_id = ?
                    and event_type = ? and schema_version = 1 and envelope = ?::jsonb)
                """, Boolean.class, event.eventId(), event.aggregateId(), event.eventType(), json);
        if (!Boolean.TRUE.equals(known)) throw new InvalidEventException("Unknown event");
        return event;
    }

    private boolean mark(String consumer, UUID eventId) {
        return jdbc.update("""
                insert into processed_events(consumer_name, event_id) values (?, ?)
                on conflict do nothing
                """, consumer, eventId) == 1;
    }

    private void insertNotification(EventEnvelope event, UUID recipient, UUID assignment, String state) {
        jdbc.update("""
                insert into notifications
                  (id, event_id, recipient_actor_id, order_id, assignment_id, kind, state, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), event.eventId(), recipient, event.aggregateId(), assignment,
                event.eventType(), state, Timestamp.from(event.occurredAt()));
    }
}
