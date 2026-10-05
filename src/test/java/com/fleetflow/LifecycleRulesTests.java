package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import com.fleetflow.lifecycle.DriverTransition;
import com.fleetflow.lifecycle.OrderTransition;
import org.junit.jupiter.api.Test;

class LifecycleRulesTests {
    @Test
    void orderEdgesMatchThePhase3ContractIncludingTerminalAndCutoff() {
        Map<OrderTransition, Set<String>> allowed = Map.of(
                OrderTransition.CONFIRM, Set.of("CREATED"),
                OrderTransition.START_PREPARATION, Set.of("CONFIRMED"),
                OrderTransition.READY_FOR_PICKUP, Set.of("PREPARING"),
                OrderTransition.CANCEL_CUSTOMER, Set.of("CREATED", "CONFIRMED"),
                OrderTransition.CANCEL_MERCHANT,
                Set.of("CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP"));
        for (OrderTransition action : OrderTransition.values()) {
            for (String state : Set.of("CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "CANCELLED")) {
                assertThat(action.allows(state)).as("%s from %s", action, state)
                        .isEqualTo(allowed.get(action).contains(state));
            }
        }
        assertThat(OrderTransition.CONFIRM.next()).isEqualTo("CONFIRMED");
        assertThat(OrderTransition.START_PREPARATION.next()).isEqualTo("PREPARING");
        assertThat(OrderTransition.READY_FOR_PICKUP.next()).isEqualTo("READY_FOR_PICKUP");
        assertThat(OrderTransition.CANCEL_CUSTOMER.next()).isEqualTo("CANCELLED");
        assertThat(OrderTransition.CANCEL_MERCHANT.next()).isEqualTo("CANCELLED");
    }

    @Test
    void driverEdgesRejectSkippedBackwardAndDuplicateCommands() {
        Map<DriverTransition, Set<String>> allowed = Map.of(
                DriverTransition.ONLINE, Set.of("OFFLINE"),
                DriverTransition.AVAILABLE, Set.of("ONLINE", "PAUSED"),
                DriverTransition.PAUSE, Set.of("ONLINE", "AVAILABLE"),
                DriverTransition.OFFLINE, Set.of("ONLINE", "AVAILABLE", "PAUSED"));
        for (DriverTransition action : DriverTransition.values()) {
            for (String state : Set.of("OFFLINE", "ONLINE", "AVAILABLE", "PAUSED")) {
                assertThat(action.allows(state)).as("%s from %s", action, state)
                        .isEqualTo(allowed.get(action).contains(state));
            }
        }
        assertThat(DriverTransition.ONLINE.next()).isEqualTo("ONLINE");
        assertThat(DriverTransition.AVAILABLE.next()).isEqualTo("AVAILABLE");
        assertThat(DriverTransition.PAUSE.next()).isEqualTo("PAUSED");
        assertThat(DriverTransition.OFFLINE.next()).isEqualTo("OFFLINE");
    }
}
