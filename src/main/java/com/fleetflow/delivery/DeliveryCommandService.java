package com.fleetflow.delivery;

import java.util.UUID;
import java.util.List;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.RecoveryTransitions;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryCommandService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final RecoveryTransitions transitions;

    DeliveryCommandService(JdbcTemplate jdbc, ActorAccess access, RecoveryTransitions transitions) {
        this.jdbc = jdbc;
        this.access = access;
        this.transitions = transitions;
    }

    @Transactional(readOnly = true)
    public DeliveryPage myDeliveries(int limit) {
        UUID actor = access.require(Role.DRIVER).id();
        if (limit < 1 || limit > 20) throw new ApiFailure(HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST", "Invalid limit");
        var content = jdbc.query("""
                select d.id, d.order_id, d.sequence, d.status,
                    o.pickup_line1, o.pickup_city, o.delivery_line1, o.delivery_city
                from deliveries d join driver_assignments a on a.id = d.accepted_assignment_id
                  join drivers v on v.id = a.driver_id join orders o on o.id = d.order_id
                where v.actor_id = ? order by d.created_at desc, d.id desc limit ?
                """, (rs, row) -> new DeliveryView((UUID) rs.getObject("id"),
                (UUID) rs.getObject("order_id"), rs.getInt("sequence"), rs.getString("status"),
                rs.getString("pickup_line1"), rs.getString("pickup_city"),
                rs.getString("delivery_line1"), rs.getString("delivery_city")), actor, limit);
        return new DeliveryPage(content);
    }

    @Transactional
    public DeliveryState pickup(UUID deliveryId) {
        return driverAction(deliveryId, "ASSIGNED", "PICKED_UP", "DRIVER_ASSIGNED", "PICKED_UP", null);
    }

    @Transactional
    public DeliveryState transit(UUID deliveryId) {
        return driverAction(deliveryId, "PICKED_UP", "IN_TRANSIT", "PICKED_UP", "IN_TRANSIT", null);
    }

    @Transactional
    public DeliveryState complete(UUID deliveryId) {
        return driverAction(deliveryId, "IN_TRANSIT", "DELIVERED", "IN_TRANSIT", "DELIVERED", null);
    }

    @Transactional
    public DeliveryState fail(UUID deliveryId, String reason) {
        return driverAction(deliveryId, null, "FAILED", null, "DELIVERY_FAILED", validReason(reason));
    }

    @Transactional
    public DeliveryState dispatcherFail(UUID orderId, String reason) {
        UUID actor = access.require(Role.DISPATCHER).id();
        jdbc.execute("set local lock_timeout = '2s'");
        var rows = jdbc.query("""
                select a.id assignment_id, a.order_id, a.driver_id, a.status assignment_status,
                    d.id delivery_id, d.status delivery_status
                from driver_assignments a join deliveries d on d.accepted_assignment_id = a.id
                where a.order_id = ? and a.status = 'ACCEPTED'
                for update of a
                """, (rs, row) -> assignment(rs), orderId);
        if (rows.isEmpty()) throw missing();
        return apply(rows.getFirst(), actor, null, "FAILED", null, "DELIVERY_FAILED", validReason(reason));
    }

    private DeliveryState driverAction(UUID deliveryId, String deliveryFrom, String deliveryTo,
                                       String orderFrom, String orderTo, String reason) {
        UUID actor = access.require(Role.DRIVER).id();
        jdbc.execute("set local lock_timeout = '2s'");
        var rows = jdbc.query("""
                select a.id assignment_id, a.order_id, a.driver_id, a.status assignment_status,
                    d.id delivery_id, d.status delivery_status
                from deliveries d join driver_assignments a on a.id = d.accepted_assignment_id
                  join drivers v on v.id = a.driver_id
                where d.id = ? and v.actor_id = ? for update of a
                """, (rs, row) -> assignment(rs), deliveryId, actor);
        if (rows.isEmpty()) throw missing();
        return apply(rows.getFirst(), actor, deliveryFrom, deliveryTo, orderFrom, orderTo, reason);
    }

    private DeliveryState apply(Assignment assignment, UUID actor, String deliveryFrom, String deliveryTo,
                                String orderFrom, String orderTo, String reason) {
        if (!"ACCEPTED".equals(assignment.assignmentStatus())) throw conflict();
        String actualDelivery = assignment.deliveryStatus();
        if ("FAILED".equals(deliveryTo)) {
            if (!"PICKED_UP".equals(actualDelivery) && !"IN_TRANSIT".equals(actualDelivery)) throw conflict();
            deliveryFrom = actualDelivery;
            orderFrom = actualDelivery;
        } else if (!actualDelivery.equals(deliveryFrom)) throw conflict();
        transitions.delivery(assignment.deliveryId(), deliveryFrom, deliveryTo, actor,
                "DELIVERY_" + deliveryTo, reason);
        transitions.order(assignment.orderId(), orderFrom, orderTo, actor,
                "DELIVERY_" + deliveryTo, reason);
        if ("DELIVERED".equals(deliveryTo) || "FAILED".equals(deliveryTo)) {
            transitions.driver(assignment.driverId(), "DELIVERING", "AVAILABLE", actor,
                    "DELIVERY_" + deliveryTo);
            int updated = jdbc.update("""
                    update driver_assignments set status = ?, closed_at = clock_timestamp(),
                        closed_by_actor_id = ?, close_reason = ?
                    where id = ? and status = 'ACCEPTED'
                    """, "DELIVERED".equals(deliveryTo) ? "COMPLETED" : "FAILED",
                    actor, "DELIVERED".equals(deliveryTo) ? "DELIVERED" : reason,
                    assignment.id());
            if (updated != 1) throw conflict();
        }
        if ("FAILED".equals(deliveryTo)) {
            jdbc.update("""
                    insert into dispatch_incidents
                    (id, order_id, delivery_id, assignment_id, code, reason, status, opened_at,
                     opened_by_actor_id)
                    values (?, ?, ?, ?, 'DELIVERY_FAILED', ?, 'OPEN', clock_timestamp(), ?)
                    """, UUID.randomUUID(), assignment.orderId(), assignment.deliveryId(),
                    assignment.id(), reason, actor);
        }
        return new DeliveryState(assignment.deliveryId(), assignment.orderId(), deliveryTo, orderTo);
    }

    private static Assignment assignment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Assignment((UUID) rs.getObject("assignment_id"), (UUID) rs.getObject("order_id"),
                (UUID) rs.getObject("driver_id"), rs.getString("assignment_status"),
                (UUID) rs.getObject("delivery_id"), rs.getString("delivery_status"));
    }
    private static String validReason(String reason) {
        if (reason == null || reason.strip().isEmpty() || reason.strip().length() > 500) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason must have 1 to 500 characters");
        }
        return reason.strip();
    }
    private static ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }
    private static ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "DELIVERY_CONFLICT", "Delivery state conflicts with this action");
    }
    private record Assignment(UUID id, UUID orderId, UUID driverId, String assignmentStatus,
                              UUID deliveryId, String deliveryStatus) { }
    public record DeliveryState(UUID deliveryId, UUID orderId, String deliveryStatus, String orderStatus) { }
    public record DeliveryView(UUID deliveryId, UUID orderId, int sequence, String status,
                               String pickupLine1, String pickupCity, String deliveryLine1, String deliveryCity) { }
    public record DeliveryPage(List<DeliveryView> content) { }
}
