package com.fleetflow.order;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.customer.CustomerService;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.merchant.MerchantService;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
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

    OrderLifecycleService(OrderRepository orders, CustomerService customers, MerchantService merchants,
                          ActorAccess access, LifecycleHistory history, Clock clock) {
        this.orders = orders;
        this.customers = customers;
        this.merchants = merchants;
        this.access = access;
        this.history = history;
        this.clock = clock;
    }

    @Transactional
    public StateView confirm(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, Command.CONFIRM, null);
    }

    @Transactional
    public StateView startPreparation(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, Command.START_PREPARATION, null);
    }

    @Transactional
    public StateView readyForPickup(UUID branchId, UUID orderId) {
        return merchant(branchId, orderId, Command.READY_FOR_PICKUP, null);
    }

    @Transactional
    public StateView cancelMerchant(UUID branchId, UUID orderId, String reason) {
        String validated = reason(reason);
        return merchant(branchId, orderId, Command.CANCEL_MERCHANT, validated);
    }

    @Transactional
    public StateView cancelCustomer(UUID orderId, String reason) {
        String validated = reason(reason);
        UUID actorId = access.require(Role.CUSTOMER).id();
        UUID customerId = customers.forActor(actorId).id();
        OrderEntity order = orders.lockForCustomer(orderId, customerId).orElseThrow(OrderLifecycleService::notFound);
        return apply(order, Command.CANCEL_CUSTOMER, actorId, validated);
    }

    private StateView merchant(UUID branchId, UUID orderId, Command command, String reason) {
        UUID actorId = merchants.requireActiveBranchScope(branchId);
        OrderEntity order = orders.lockForBranch(orderId, branchId).orElseThrow(OrderLifecycleService::notFound);
        return apply(order, command, actorId, reason);
    }

    private StateView apply(OrderEntity order, Command command, UUID actorId, String reason) {
        if (!command.from.contains(order.status)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT", "Order state does not allow this action");
        }
        String previous = order.status;
        Instant now = clock.instant();
        order.status = command.to;
        order.version++;
        order.updatedAt = now;
        switch (command) {
            case CONFIRM -> order.confirmedAt = now;
            case START_PREPARATION -> order.preparingAt = now;
            case READY_FOR_PICKUP -> order.readyAt = now;
            case CANCEL_CUSTOMER, CANCEL_MERCHANT -> order.cancelledAt = now;
        }
        history.order(order.id, order.version, previous, order.status, command.name(), now, actorId, reason);
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

    private enum Command {
        CONFIRM(Set.of("CREATED"), "CONFIRMED"),
        START_PREPARATION(Set.of("CONFIRMED"), "PREPARING"),
        READY_FOR_PICKUP(Set.of("PREPARING"), "READY_FOR_PICKUP"),
        CANCEL_CUSTOMER(Set.of("CREATED", "CONFIRMED"), "CANCELLED"),
        CANCEL_MERCHANT(Set.of("CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP"), "CANCELLED");

        final Set<String> from;
        final String to;
        Command(Set<String> from, String to) { this.from = from; this.to = to; }
    }

    public record StateView(UUID id, String status, long version, Instant confirmedAt,
                            Instant preparingAt, Instant readyAt, Instant cancelledAt) { }
}
