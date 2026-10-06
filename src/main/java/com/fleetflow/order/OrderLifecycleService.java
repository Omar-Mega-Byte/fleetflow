package com.fleetflow.order;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.customer.CustomerService;
import com.fleetflow.dispatch.RecoveryCommandService;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.lifecycle.OrderTransition;
import com.fleetflow.merchant.MerchantService;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderLifecycleService {
    private final OrderRepository orders;
    private final CustomerService customers;
    private final MerchantService merchants;
    private final ActorAccess access;
    private final LifecycleHistory history;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final RecoveryCommandService recovery;

    OrderLifecycleService(OrderRepository orders, CustomerService customers, MerchantService merchants,
                          ActorAccess access, LifecycleHistory history, Clock clock, JdbcTemplate jdbc,
                          RecoveryCommandService recovery) {
        this.orders = orders;
        this.customers = customers;
        this.merchants = merchants;
        this.access = access;
        this.history = history;
        this.clock = clock;
        this.jdbc = jdbc;
        this.recovery = recovery;
    }

    @Transactional
    public StateView confirm(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, OrderTransition.CONFIRM, null);
    }

    @Transactional
    public StateView startPreparation(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, OrderTransition.START_PREPARATION, null);
    }

    @Transactional
    public StateView readyForPickup(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, OrderTransition.READY_FOR_PICKUP, null);
    }

    @Transactional
    public StateView cancelMerchant(UUID branchId, UUID orderId, String reason) {
        String validated = reason(reason);
        var states = jdbc.query("select status from orders where id = ? and merchant_branch_id = ?",
                (rs, row) -> rs.getString("status"), orderId, branchId);
        if (!states.isEmpty() && ("DISPATCHING".equals(states.getFirst())
                || "DISPATCH_FAILED".equals(states.getFirst())
                || "DRIVER_ASSIGNED".equals(states.getFirst()))) {
            return recovery.cancelMerchant(branchId, orderId, validated);
        }
        return merchant(branchId, orderId, OrderTransition.CANCEL_MERCHANT, validated);
    }

    @Transactional
    public StateView cancelCustomer(UUID orderId, String reason) {
        String validated = reason(reason);
        UUID actorId = access.require(Role.CUSTOMER).id();
        UUID customerId = customers.forActor(actorId).id();
        OrderEntity order = orders.lockForCustomer(orderId, customerId).orElseThrow(OrderLifecycleService::notFound);
        return apply(order, OrderTransition.CANCEL_CUSTOMER, actorId, validated);
    }

    private StateView merchant(UUID branchId, UUID orderId, OrderTransition command, String reason) {
        UUID actorId = merchants.requireActiveBranchScope(branchId);
        OrderEntity order = orders.lockForBranch(orderId, branchId).orElseThrow(OrderLifecycleService::notFound);
        return apply(order, command, actorId, reason);
    }

    private StateView apply(OrderEntity order, OrderTransition command, UUID actorId, String reason) {
        if (!command.allows(order.status)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT", "Order state does not allow this action");
        }
        String previous = order.status;
        Instant now = clock.instant();
        order.status = command.next();
        order.version++;
        order.updatedAt = now;
        switch (command) {
            case CONFIRM -> order.confirmedAt = now;
            case START_PREPARATION -> order.preparingAt = now;
            case READY_FOR_PICKUP -> order.readyAt = now;
            case CANCEL_CUSTOMER, CANCEL_MERCHANT -> order.cancelledAt = now;
        }
        history.order(order.id, order.version, previous, order.status, command.name(), now, actorId, reason);
        if (command == OrderTransition.CONFIRM || command == OrderTransition.START_PREPARATION
                || command == OrderTransition.READY_FOR_PICKUP) {
            jdbc.update("""
                    insert into order_status_history (id, order_id, actor_id, from_status, to_status, changed_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), order.id, actorId, previous, order.status, Timestamp.from(now));
        }
        return new StateView(order.id, order.status, order.version, order.confirmedAt,
                order.preparingAt, order.readyAt, order.cancelledAt);
    }

    private static String reason(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 500) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reason must have 1 to 500 characters");
        }
        return value.strip();
    }

    private static ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    public record StateView(UUID id, String status, long version, Instant confirmedAt,
                            Instant preparingAt, Instant readyAt, Instant cancelledAt) { }
}
