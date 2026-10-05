package com.fleetflow.lifecycle;

import java.util.Set;

/** The Phase 3 order edge list. Actor scope and database locking live in the coordinator. */
public enum OrderTransition {
    CONFIRM(Set.of("CREATED"), "CONFIRMED"),
    START_PREPARATION(Set.of("CONFIRMED"), "PREPARING"),
    READY_FOR_PICKUP(Set.of("PREPARING"), "READY_FOR_PICKUP"),
    CANCEL_CUSTOMER(Set.of("CREATED", "CONFIRMED"), "CANCELLED"),
    CANCEL_MERCHANT(Set.of("CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP"), "CANCELLED");

    private final Set<String> from;
    private final String to;

    OrderTransition(Set<String> from, String to) { this.from = from; this.to = to; }
    public boolean allows(String current) { return from.contains(current); }
    public String next() { return to; }
}
