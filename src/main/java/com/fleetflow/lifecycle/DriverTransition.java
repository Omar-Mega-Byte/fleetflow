package com.fleetflow.lifecycle;

import java.util.Set;

/** Durable driver edges; suspension and vehicle eligibility are separate guards. */
public enum DriverTransition {
    ONLINE(Set.of("OFFLINE"), "ONLINE"),
    AVAILABLE(Set.of("ONLINE", "PAUSED"), "AVAILABLE"),
    PAUSE(Set.of("ONLINE", "AVAILABLE"), "PAUSED"),
    OFFLINE(Set.of("ONLINE", "AVAILABLE", "PAUSED"), "OFFLINE");

    private final Set<String> from;
    private final String to;

    DriverTransition(Set<String> from, String to) { this.from = from; this.to = to; }
    public boolean allows(String current) { return from.contains(current); }
    public String next() { return to; }
}
