package com.fleetflow.operations;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.fleetflow.tracking.TrackingHub;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small, bounded database snapshot for questions that event counters cannot answer after restart. */
@Component
public class OperationalMeters {
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final AtomicLong databaseAvailable = new AtomicLong();
    private final AtomicLong redisAvailable = new AtomicLong();
    private final AtomicLong overdueOffers = new AtomicLong(-1);
    private final AtomicLong dueRecovery = new AtomicLong(-1);
    private final AtomicLong openIncidents = new AtomicLong(-1);
    private final AtomicLong oldestIncidentSeconds = new AtomicLong(-1);
    private final AtomicLong quarantinedEvents = new AtomicLong(-1);
    private final AtomicLong outboxPending = new AtomicLong(-1);
    private final AtomicLong outboxFailed = new AtomicLong(-1);
    private final AtomicLong outboxOldestSeconds = new AtomicLong(-1);

    public OperationalMeters(JdbcTemplate jdbc, RedisConnectionFactory redis,
                             TrackingHub tracking, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.redis = redis;
        Gauge.builder("fleetflow.operations.database.available", databaseAvailable, AtomicLong::get)
                .register(metrics);
        Gauge.builder("fleetflow.operations.redis.available", redisAvailable, AtomicLong::get)
                .register(metrics);
        Gauge.builder("fleetflow.offers.overdue", overdueOffers, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.recovery.due", dueRecovery, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.incidents.open", openIncidents, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.incidents.oldest.seconds", oldestIncidentSeconds, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.events.quarantined.rows", quarantinedEvents, AtomicLong::get)
                .register(metrics);
        Gauge.builder("fleetflow.outbox.pending", outboxPending, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.outbox.failed", outboxFailed, AtomicLong::get).register(metrics);
        Gauge.builder("fleetflow.outbox.oldest.seconds", outboxOldestSeconds, AtomicLong::get)
                .register(metrics);
        Gauge.builder("fleetflow.tracking.active", tracking, TrackingHub::activeStreams)
                .register(metrics);
    }

    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    public void refresh() {
        try (var connection = redis.getConnection()) {
            redisAvailable.set("PONG".equals(connection.ping()) ? 1 : 0);
        } catch (RuntimeException unavailable) {
            redisAvailable.set(0);
        }
        try {
            Map<String, Object> counts = jdbc.queryForMap("""
                    select
                      (select count(*) from driver_assignments where status = 'OFFERED'
                         and expires_at <= clock_timestamp()) as overdue_offers,
                      (select count(*) from dispatch_recovery where status = 'PENDING'
                         and next_action_at <= clock_timestamp()) as due_recovery,
                      (select count(*) from dispatch_incidents where status = 'OPEN') as open_incidents,
                      (select coalesce(extract(epoch from clock_timestamp() - min(opened_at)), 0)
                         from dispatch_incidents where status = 'OPEN') as oldest_incident_seconds,
                      (select count(*) from quarantined_events
                         where quarantined_at > clock_timestamp() - interval '10 minutes') as quarantined_events,
                      (select count(*) from outbox_events where status = 'PENDING') as outbox_pending,
                      (select count(*) from outbox_events where status = 'FAILED') as outbox_failed,
                      (select coalesce(extract(epoch from clock_timestamp() - min(occurred_at)), 0)
                         from outbox_events where status = 'PENDING') as outbox_oldest_seconds
                    """);
            overdueOffers.set(((Number) counts.get("overdue_offers")).longValue());
            dueRecovery.set(((Number) counts.get("due_recovery")).longValue());
            openIncidents.set(((Number) counts.get("open_incidents")).longValue());
            oldestIncidentSeconds.set(((Number) counts.get("oldest_incident_seconds")).longValue());
            quarantinedEvents.set(((Number) counts.get("quarantined_events")).longValue());
            outboxPending.set(((Number) counts.get("outbox_pending")).longValue());
            outboxFailed.set(((Number) counts.get("outbox_failed")).longValue());
            outboxOldestSeconds.set(((Number) counts.get("outbox_oldest_seconds")).longValue());
            databaseAvailable.set(1);
        } catch (DataAccessException unavailable) {
            databaseAvailable.set(0);
            overdueOffers.set(-1);
            dueRecovery.set(-1);
            openIncidents.set(-1);
            oldestIncidentSeconds.set(-1);
            quarantinedEvents.set(-1);
            outboxPending.set(-1);
            outboxFailed.set(-1);
            outboxOldestSeconds.set(-1);
        }
    }
}
