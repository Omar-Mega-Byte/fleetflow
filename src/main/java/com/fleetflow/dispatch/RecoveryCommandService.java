package com.fleetflow.dispatch;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.RecoveryTransitions;
import com.fleetflow.merchant.MerchantService;
import com.fleetflow.order.OrderLifecycleService;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoveryCommandService {
    private final JdbcTemplate jdbc;
    private final ActorAccess access;
    private final MerchantService merchants;
    private final RecoveryTransitions transitions;
    private final RecoveryQueue queue;

    RecoveryCommandService(JdbcTemplate jdbc, ActorAccess access, MerchantService merchants,
                           RecoveryTransitions transitions, RecoveryQueue queue) {
        this.jdbc = jdbc;
        this.access = access;
        this.merchants = merchants;
        this.transitions = transitions;
        this.queue = queue;
    }

    @Transactional
    public OrderLifecycleService.StateView cancelMerchant(UUID branchId, UUID orderId, String reason) {
        UUID actor = merchants.requireActiveBranchScope(branchId);
        return cancel(orderId, branchId, actor, reason);
    }

    @Transactional
    public OrderLifecycleService.StateView cancelDispatcher(UUID orderId, String reason) {
        UUID actor = access.require(Role.DISPATCHER).id();
        return cancel(orderId, null, actor, reason);
    }

    @Transactional
    public RecoveryResult reassign(UUID orderId, String reason) {
        UUID actor = access.require(Role.DISPATCHER).id();
        String why = validReason(reason);
        jdbc.execute("set local lock_timeout = '2s'");
        Active active = active(orderId);
        if (active == null || !"ACCEPTED".equals(active.status())) throw conflict();
        var delivery = jdbc.query("""
                select id, status from deliveries where accepted_assignment_id = ?
                """, (rs, row) -> new Delivery((UUID) rs.getObject("id"), rs.getString("status")), active.id());
        if (delivery.size() != 1 || !"ASSIGNED".equals(delivery.getFirst().status())) throw conflict();
        closeAccepted(active.id(), "REASSIGNED", actor, why);
        transitions.driver(active.driverId(), "DELIVERING", "AVAILABLE", actor, "PRE_PICKUP_REASSIGN");
        transitions.delivery(delivery.getFirst().id(), "ASSIGNED", "CANCELLED", actor,
                "PRE_PICKUP_REASSIGN", null);
        transitions.order(orderId, "DRIVER_ASSIGNED", "DISPATCHING", actor,
                "PRE_PICKUP_REASSIGN", why);
        jdbc.update("""
                insert into dispatch_incidents
                (id, order_id, delivery_id, assignment_id, code, reason, status, opened_at,
                 opened_by_actor_id)
                values (?, ?, ?, ?, 'PRE_PICKUP_REASSIGNMENT', ?, 'OPEN', clock_timestamp(), ?)
                """, UUID.randomUUID(), orderId, delivery.getFirst().id(), active.id(), why, actor);
        queue.schedule(orderId, "PRE_PICKUP_REASSIGNMENT", 0);
        return new RecoveryResult(orderId, "DISPATCHING");
    }

    @Transactional(readOnly = true)
    public IncidentPage incidents(UUID orderId, int limit) {
        access.require(Role.DISPATCHER);
        if (limit < 1 || limit > 20) throw invalid();
        Boolean exists = jdbc.queryForObject("select exists(select 1 from orders where id = ?)",
                Boolean.class, orderId);
        if (!Boolean.TRUE.equals(exists)) throw missing();
        var content = jdbc.query("""
                select id, code, reason, status, opened_at, resolved_at, resolution
                from dispatch_incidents where order_id = ?
                order by opened_at desc, id desc limit ?
                """, (rs, row) -> new Incident((UUID) rs.getObject("id"), rs.getString("code"),
                rs.getString("reason"), rs.getString("status"),
                rs.getTimestamp("opened_at").toInstant(),
                instant(rs.getTimestamp("resolved_at")), rs.getString("resolution")), orderId, limit);
        return new IncidentPage(content);
    }

    @Transactional
    public Incident resolveIncident(UUID orderId, UUID incidentId, String resolution) {
        UUID actor = access.require(Role.DISPATCHER).id();
        String why = validReason(resolution);
        var rows = jdbc.query("""
                update dispatch_incidents set status = 'RESOLVED', resolved_at = clock_timestamp(),
                    resolved_by_actor_id = ?, resolution = ?
                where id = ? and order_id = ? and status = 'OPEN'
                returning id, code, reason, status, opened_at, resolved_at, resolution
                """, (rs, row) -> new Incident((UUID) rs.getObject("id"), rs.getString("code"),
                rs.getString("reason"), rs.getString("status"), rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("resolved_at").toInstant(), rs.getString("resolution")),
                actor, why, incidentId, orderId);
        if (rows.size() != 1) throw conflict();
        return rows.getFirst();
    }

    private OrderLifecycleService.StateView cancel(UUID orderId, UUID branchId, UUID actor, String reason) {
        String why = validReason(reason);
        jdbc.execute("set local lock_timeout = '2s'");
        Active active = active(orderId);
        if (active != null) {
            if ("OFFERED".equals(active.status())) {
                int changed = jdbc.update("""
                        update driver_assignments set status = 'CANCELLED', responded_at = clock_timestamp(),
                            responded_by_actor_id = ?, close_reason = ?
                        where id = ? and status = 'OFFERED'
                        """, actor, why, active.id());
                if (changed != 1) throw conflict();
                transitions.driver(active.driverId(), "RESERVED", "AVAILABLE", actor, "ORDER_CANCELLED");
            } else if ("ACCEPTED".equals(active.status())) {
                closeAccepted(active.id(), "CANCELLED", actor, why);
                transitions.driver(active.driverId(), "DELIVERING", "AVAILABLE", actor, "ORDER_CANCELLED");
            } else throw conflict();
        }
        var orders = jdbc.query("""
                select status, merchant_branch_id from orders where id = ? for update
                """, (rs, row) -> new OrderState(rs.getString("status"),
                (UUID) rs.getObject("merchant_branch_id")), orderId);
        if (orders.isEmpty() || (branchId != null && !branchId.equals(orders.getFirst().branchId()))) throw missing();
        String status = orders.getFirst().status();
        if (!"DISPATCHING".equals(status) && !"DRIVER_ASSIGNED".equals(status)
                && !"DISPATCH_FAILED".equals(status)) throw conflict();
        if (("DRIVER_ASSIGNED".equals(status)) != (active != null && "ACCEPTED".equals(active.status()))) {
            throw conflict();
        }
        if (active == null && hasActiveAssignment(orderId)) throw conflict();
        var deliveries = jdbc.query("""
                select id, status from deliveries where order_id = ? and status in ('PENDING', 'ASSIGNED')
                """, (rs, row) -> new Delivery((UUID) rs.getObject("id"), rs.getString("status")), orderId);
        for (Delivery delivery : deliveries) {
            transitions.delivery(delivery.id(), delivery.status(), "CANCELLED", actor,
                    "ORDER_CANCELLED", null);
        }
        transitions.order(orderId, status, "CANCELLED", actor, "ORDER_CANCELLED", why);
        queue.resolve(orderId, "ORDER_CANCELLED");
        return jdbc.queryForObject("""
                select id, status, version, confirmed_at, preparing_at, ready_at, cancelled_at
                from orders where id = ?
                """, (rs, row) -> new OrderLifecycleService.StateView((UUID) rs.getObject("id"),
                rs.getString("status"), rs.getLong("version"), instant(rs.getTimestamp("confirmed_at")),
                instant(rs.getTimestamp("preparing_at")), instant(rs.getTimestamp("ready_at")),
                instant(rs.getTimestamp("cancelled_at"))), orderId);
    }

    private Active active(UUID orderId) {
        var rows = jdbc.query("""
                select id, driver_id, status from driver_assignments
                where order_id = ? and status in ('OFFERED', 'ACCEPTED')
                for update
                """, (rs, row) -> new Active((UUID) rs.getObject("id"),
                (UUID) rs.getObject("driver_id"), rs.getString("status")), orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void closeAccepted(UUID assignmentId, String status, UUID actor, String reason) {
        int changed = jdbc.update("""
                update driver_assignments set status = ?, closed_at = clock_timestamp(),
                    closed_by_actor_id = ?, close_reason = ?
                where id = ? and status = 'ACCEPTED'
                """, status, actor, reason, assignmentId);
        if (changed != 1) throw conflict();
    }

    private boolean hasActiveAssignment(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from driver_assignments
                  where order_id = ? and status in ('OFFERED', 'ACCEPTED'))
                """, Boolean.class, orderId));
    }
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static String validReason(String reason) {
        if (reason == null || reason.strip().isEmpty() || reason.strip().length() > 500) throw invalid();
        return reason.strip();
    }
    private static ApiFailure missing() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }
    private static ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "RECOVERY_CONFLICT", "Recovery state conflicts with this action");
    }
    private static ApiFailure invalid() {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason or limit is invalid");
    }

    private record Active(UUID id, UUID driverId, String status) { }
    private record Delivery(UUID id, String status) { }
    private record OrderState(String status, UUID branchId) { }
    public record RecoveryResult(UUID orderId, String status) { }
    public record Incident(UUID id, String code, String reason, String status, Instant openedAt,
                           Instant resolvedAt, String resolution) { }
    public record IncidentPage(List<Incident> content) { }
}
