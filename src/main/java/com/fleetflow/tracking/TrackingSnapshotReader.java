package com.fleetflow.tracking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.location.LocationFix;
import com.fleetflow.location.LocationProperties;
import com.fleetflow.location.RedisLocationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The only tracking projection: PostgreSQL decides access/assignment, Redis supplies an optional fresh fix. */
@Component
public class TrackingSnapshotReader {
    private final JdbcTemplate jdbc;
    private final RedisLocationStore locations;
    private final LocationProperties policy;
    private final Clock clock;
    private final MeterRegistry metrics;

    TrackingSnapshotReader(JdbcTemplate jdbc, RedisLocationStore locations, LocationProperties policy,
                           Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.locations = locations;
        this.policy = policy;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Snapshot read(UUID orderId, UUID actorId) {
        Instant observedAt = clock.instant();
        List<OrderState> rows = jdbc.query("""
                select o.status order_status, o.version order_version, d.status delivery_status,
                       active_driver.id driver_id
                from orders o
                join customers c on c.id = o.customer_id
                join actors actor on actor.id = c.actor_id and actor.active = true
                join actor_roles role on role.actor_id = actor.id and role.role = 'CUSTOMER'
                left join lateral (
                    select status, accepted_assignment_id from deliveries
                    where order_id = o.id order by sequence desc limit 1
                ) d on true
                left join driver_assignments da on da.id = d.accepted_assignment_id
                    and da.status = 'ACCEPTED'
                left join drivers active_driver on active_driver.id = da.driver_id
                    and active_driver.active = true and active_driver.suspended = false
                    and active_driver.operational_status = 'DELIVERING'
                where o.id = ? and c.actor_id = ?
                  and (o.demo_generation is null or o.demo_generation =
                    (select generation from synthetic_demo_state where id = true))
                """, (rs, row) -> new OrderState(rs.getString("order_status"),
                rs.getLong("order_version"), rs.getString("delivery_status"),
                (UUID) rs.getObject("driver_id")), orderId, actorId);
        if (rows.isEmpty()) {
            positionOutcome("not_found");
            throw new ApiFailure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Order not found");
        }
        OrderState state = rows.getFirst();
        boolean terminal = switch (state.orderStatus()) {
            case "CANCELLED", "DELIVERED", "DELIVERY_FAILED" -> true;
            default -> false;
        };
        Position position = null;
        String outcome = terminal ? "terminal" : "not_active";
        if (!terminal && state.driverId() != null
                && List.of("DRIVER_ASSIGNED", "PICKED_UP", "IN_TRANSIT").contains(state.orderStatus())
                && List.of("ASSIGNED", "PICKED_UP", "IN_TRANSIT").contains(state.deliveryStatus())) {
            outcome = "stale";
            try {
                LocationFix fix = locations.latest(state.driverId());
                Instant heartbeat = locations.lastHeartbeat(state.driverId());
                if (fix != null && fresh(fix.reportedAt(), observedAt, policy.maxGpsAge())
                        && fresh(fix.receivedAt(), observedAt, policy.maxGpsAge())
                        && fresh(heartbeat, observedAt, policy.heartbeatTtl())
                        && Double.isFinite(fix.latitude()) && fix.latitude() >= -90 && fix.latitude() <= 90
                        && Double.isFinite(fix.longitude()) && fix.longitude() >= -180 && fix.longitude() <= 180) {
                    position = new Position(fix.latitude(), fix.longitude(), fix.reportedAt());
                    outcome = "fresh";
                }
            } catch (DataAccessException unavailable) {
                // A Redis outage cannot turn an old fix into an authorized position.
                outcome = "redis_error";
            }
        }
        positionOutcome(outcome);
        return new Snapshot(orderId, state.orderStatus(), state.orderVersion(), state.deliveryStatus(),
                observedAt, position, terminal);
    }

    private void positionOutcome(String outcome) {
        metrics.counter("fleetflow.tracking.position", "outcome", outcome).increment();
    }

    public List<UUID> activeOrdersForDriver(UUID driverId) {
        return jdbc.query("""
                select d.order_id from deliveries d
                join driver_assignments da on da.id = d.accepted_assignment_id
                join orders o on o.id = d.order_id
                where da.driver_id = ? and da.status = 'ACCEPTED'
                  and d.status in ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT')
                  and o.status in ('DRIVER_ASSIGNED', 'PICKED_UP', 'IN_TRANSIT')
                """, (rs, row) -> (UUID) rs.getObject(1), driverId);
    }

    private boolean fresh(Instant timestamp, Instant now, java.time.Duration maxAge) {
        return timestamp != null && !timestamp.isAfter(now.plus(policy.futureSkew()))
                && !timestamp.isBefore(now.minus(maxAge));
    }

    private record OrderState(String orderStatus, long orderVersion, String deliveryStatus, UUID driverId) { }

    public record Position(double latitude, double longitude, Instant reportedAt) { }

    public record Snapshot(UUID orderId, String orderStatus, long orderVersion, String deliveryStatus,
                           Instant observedAt, Position position, boolean terminal) {
        boolean sameContent(Snapshot other) {
            return other != null && orderStatus.equals(other.orderStatus)
                    && orderVersion == other.orderVersion
                    && java.util.Objects.equals(deliveryStatus, other.deliveryStatus)
                    && java.util.Objects.equals(position, other.position) && terminal == other.terminal;
        }
    }
}
