package com.fleetflow.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.Map;

import com.fleetflow.tracking.TrackingHub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class OperationalMetersTests {
    @Test
    void publishesLowCardinalityOperationalSnapshotsAndMarksRedisOutageWithoutDbReadinessLoss() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        TrackingHub tracking = mock(TrackingHub.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(redis.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new DataAccessResourceFailureException("synthetic Redis outage"));
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of(
                "overdue_offers", 2L,
                "due_recovery", 3L,
                "open_incidents", 4L,
                "oldest_incident_seconds", 120L,
                "quarantined_events", 5L,
                "outbox_pending", 6L,
                "outbox_failed", 7L,
                "outbox_oldest_seconds", 45L));
        when(tracking.activeStreams()).thenReturn(8);

        OperationalMeters meters = new OperationalMeters(jdbc, redis, tracking, registry);
        meters.refresh();

        assertThat(registry.get("fleetflow.operations.database.available").gauge().value()).isEqualTo(1);
        assertThat(registry.get("fleetflow.operations.redis.available").gauge().value()).isZero();
        assertThat(registry.get("fleetflow.outbox.pending").gauge().value()).isEqualTo(6);
        assertThat(registry.get("fleetflow.outbox.failed").gauge().value()).isEqualTo(7);
        assertThat(registry.get("fleetflow.outbox.oldest.seconds").gauge().value()).isEqualTo(45);
        assertThat(registry.get("fleetflow.events.quarantined.rows").gauge().value()).isEqualTo(5);
        assertThat(registry.get("fleetflow.incidents.oldest.seconds").gauge().value()).isEqualTo(120);
        assertThat(registry.get("fleetflow.tracking.active").gauge().value()).isEqualTo(8);
        registry.close();
    }
}
