package com.fleetflow.driver;

import java.time.Clock;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriverLifecycleService {
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final ActorAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    DriverLifecycleService(DriverRepository drivers, VehicleRepository vehicles, ActorAccess access,
                           JdbcTemplate jdbc, Clock clock, ApplicationEventPublisher events) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public void goOnline() { change(DriverStatus.ONLINE); }

    @Transactional
    public void becomeAvailable() { change(DriverStatus.AVAILABLE); }

    @Transactional
    public void pause() { change(DriverStatus.PAUSED); }

    @Transactional
    public void goOffline() { change(DriverStatus.OFFLINE); }

    private void change(DriverStatus target) {
        UUID actorId = access.require(Role.DRIVER).id();
        DriverEntity driver = drivers.findByActorIdForUpdate(actorId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found"));
        if (!driver.active || driver.suspended) {
            throw conflict();
        }
        DriverStatus prior = driver.status;
        boolean allowed = switch (target) {
            case ONLINE -> prior == DriverStatus.OFFLINE;
            case AVAILABLE -> prior == DriverStatus.ONLINE || prior == DriverStatus.PAUSED;
            case PAUSED -> prior == DriverStatus.ONLINE || prior == DriverStatus.AVAILABLE;
            case OFFLINE -> prior == DriverStatus.ONLINE || prior == DriverStatus.AVAILABLE
                    || prior == DriverStatus.PAUSED;
            case RESERVED, DELIVERING -> false;
        };
        if (!allowed || (target == DriverStatus.AVAILABLE &&
                !vehicles.existsByDriverIdAndActiveTrue(driver.id))) {
            throw conflict();
        }
        driver.status = target;
        driver.updatedAt = clock.instant();
        drivers.saveAndFlush(driver);
        jdbc.update("""
                insert into driver_status_history (id, driver_id, actor_id, from_status, to_status, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), driver.id, actorId, prior.name(), target.name(),
                java.sql.Timestamp.from(driver.updatedAt));
        if (target == DriverStatus.PAUSED || target == DriverStatus.OFFLINE) {
            events.publishEvent(new DriverBecameIneligible(driver.id));
        }
    }

    private ApiFailure conflict() {
        return new ApiFailure(HttpStatus.CONFLICT, "DRIVER_STATE_CONFLICT", "Driver state does not allow this action");
    }

    public record DriverBecameIneligible(UUID driverId) { }
}
