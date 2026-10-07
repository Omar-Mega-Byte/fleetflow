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
    private final AtomicLong quarantinedEvents = new AtomicLong(-1);

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
        Gauge.builder("fleetflow.events.quarantined.rows", quarantinedEvents, AtomicLong::get)
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
                      (select count(*) from quarantined_events) as quarantined_events
                    """);
            overdueOffers.set(((Number) counts.get("overdue_offers")).longValue());
            dueRecovery.set(((Number) counts.get("due_recovery")).longValue());
            openIncidents.set(((Number) counts.get("open_incidents")).longValue());
            quarantinedEvents.set(((Number) counts.get("quarantined_events")).longValue());
            databaseAvailable.set(1);
        } catch (DataAccessException unavailable) {
            databaseAvailable.set(0);
            overdueOffers.set(-1);
            dueRecovery.set(-1);
            openIncidents.set(-1);
            quarantinedEvents.set(-1);
        }
    }
}
