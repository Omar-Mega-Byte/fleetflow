package com.fleetflow.driver;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.lifecycle.DriverTransition;
import com.fleetflow.security.ActorAccess;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverLifecycleService {
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final ActorAccess access;
    private final LifecycleHistory history;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    DriverLifecycleService(DriverRepository drivers, VehicleRepository vehicles, ActorAccess access,
                           LifecycleHistory history, Clock clock, JdbcTemplate jdbc,
                           ApplicationEventPublisher events) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.access = access;
        this.history = history;
        this.clock = clock;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public StateView online() { return apply(DriverTransition.ONLINE); }

    @Transactional
    public StateView available() { return apply(DriverTransition.AVAILABLE); }

    @Transactional
    public StateView pause() { return apply(DriverTransition.PAUSE); }

    @Transactional
    public StateView offline() { return apply(DriverTransition.OFFLINE); }

    private StateView apply(DriverTransition command) {
        UUID actorId = access.require(Role.DRIVER).id();
        DriverEntity driver = drivers.lockForActor(actorId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found"));
        if (!driver.active || driver.suspended) {
            throw new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Driver action is not permitted");
        }
        if (!command.allows(driver.operationalStatus)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT",
                    "Driver state does not allow this action");
        }
        if (command == DriverTransition.AVAILABLE &&
                !vehicles.existsByDriverIdAndActiveTrueAndCapacityGreaterThan(driver.id, 0)) {
            throw new ApiFailure(HttpStatus.CONFLICT, "LIFECYCLE_CONFLICT",
                    "An active suitable vehicle is required");
        }
        String previous = driver.operationalStatus;
        Instant now = clock.instant();
        driver.operationalStatus = command.next();
        driver.version++;
        driver.updatedAt = now;
        history.driver(driver.id, driver.version, previous, command.next(), command.name(), now, actorId);
        jdbc.update("""
                insert into driver_status_history (id, driver_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), driver.id, actorId, previous, command.next(), Timestamp.from(now));
        if (command == DriverTransition.PAUSE || command == DriverTransition.OFFLINE) {
            events.publishEvent(new DriverBecameIneligible(driver.id));
        }
        return new StateView(driver.id, driver.operationalStatus, driver.version);
    }

    public record StateView(UUID id, String operationalStatus, long version) { }
    public record DriverBecameIneligible(UUID driverId) { }
}
