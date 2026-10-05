package com.fleetflow.driver;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverLifecycleService {
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final ActorAccess access;
    private final LifecycleHistory history;
    private final Clock clock;

    DriverLifecycleService(DriverRepository drivers, VehicleRepository vehicles, ActorAccess access,
                           LifecycleHistory history, Clock clock) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.access = access;
        this.history = history;
        this.clock = clock;
    }

    @Transactional
    public StateView online() { return apply(Command.ONLINE); }

    @Transactional
    public StateView available() { return apply(Command.AVAILABLE); }

    @Transactional
    public StateView pause() { return apply(Command.PAUSE); }

    @Transactional
    public StateView offline() { return apply(Command.OFFLINE); }

    private StateView apply(Command command) {
        UUID actorId = access.require(Role.DRIVER).id();
        DriverEntity driver = drivers.lockForActor(actorId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found"));
        if (!driver.active || driver.suspended) {
            throw new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Driver action is not permitted");
        }
        if (!command.from.contains(driver.operationalStatus)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT",
                    "Driver state does not allow this action");
        }
        if (command == Command.AVAILABLE &&
                !vehicles.existsByDriverIdAndActiveTrueAndCapacityGreaterThan(driver.id, 0)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT",
                    "An active suitable vehicle is required");
        }
        String previous = driver.operationalStatus;
        Instant now = clock.instant();
        driver.operationalStatus = command.to;
        driver.version++;
        driver.updatedAt = now;
        history.driver(driver.id, driver.version, previous, command.to, command.name(), now, actorId);
        return new StateView(driver.id, driver.operationalStatus, driver.version);
    }

    private enum Command {
        ONLINE(Set.of("OFFLINE"), "ONLINE"),
        AVAILABLE(Set.of("ONLINE", "PAUSED"), "AVAILABLE"),
        PAUSE(Set.of("ONLINE", "AVAILABLE"), "PAUSED"),
        OFFLINE(Set.of("ONLINE", "AVAILABLE", "PAUSED"), "OFFLINE");

        final Set<String> from;
        final String to;
        Command(Set<String> from, String to) { this.from = from; this.to = to; }
    }

    public record StateView(UUID id, String operationalStatus, long version) { }
}
