package com.fleetflow.driver;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.IdentityService;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
public class DriverService {
    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final IdentityService identity;
    private final Clock clock;
    private final ActorAccess access;

    DriverService(DriverRepository drivers, VehicleRepository vehicles, IdentityService identity, Clock clock,
                  ActorAccess access) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.identity = identity;
        this.clock = clock;
        this.access = access;
    }

    @Transactional
    public DriverView createDriver(UUID actorId) {
        identity.requireActive(actorId);
        DriverEntity driver = new DriverEntity(UUID.randomUUID(), actorId, clock.instant());
        drivers.saveAndFlush(driver);
        return view(driver);
    }

    @Transactional(readOnly = true)
    public DriverView getDriver(UUID id) {
        return view(drivers.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Driver not found")));
    }

    @Transactional(readOnly = true)
    public DriverSelfView me() {
        UUID actorId = access.require(Role.DRIVER).id();
        DriverEntity driver = drivers.findByActorId(actorId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found"));
        return new DriverSelfView(driver.id, driver.active, driver.suspended, driver.status);
    }

    @Transactional
    public VehicleView createVehicle(UUID driverId, String type, int capacity) {
        DriverEntity driver = drivers.findById(driverId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Driver not found"));
        if (!driver.active || driver.suspended) {
            throw new ApiFailure(HttpStatus.CONFLICT, "DRIVER_INACTIVE", "Driver is inactive");
        }
        VehicleEntity vehicle = new VehicleEntity(UUID.randomUUID(), driverId, type.strip(), capacity, clock.instant());
        vehicles.saveAndFlush(vehicle);
        return view(vehicle);
    }

    @Transactional(readOnly = true)
    public VehicleView getVehicle(UUID id) {
        return view(vehicles.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND", "Vehicle not found")));
    }

    private DriverView view(DriverEntity driver) {
        return new DriverView(driver.id, driver.actorId, driver.active, driver.suspended, driver.status);
    }

    private VehicleView view(VehicleEntity vehicle) {
        return new VehicleView(vehicle.id, vehicle.driverId, vehicle.type, vehicle.capacity, vehicle.active);
    }

    public record DriverView(UUID id, UUID actorId, boolean active, boolean suspended, DriverStatus status) { }
    public record DriverSelfView(UUID id, boolean active, boolean suspended, DriverStatus status) { }
    public record VehicleView(UUID id, UUID driverId, String type, int capacity, boolean active) { }
}

@Entity
@Table(name = "drivers")
class DriverEntity {
    @Id UUID id;
    @Column(name = "actor_id", nullable = false) UUID actorId;
    @Column(nullable = false) boolean active;
    @Column(nullable = false) boolean suspended;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) DriverStatus status;
    @Version
    @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected DriverEntity() { }
    DriverEntity(UUID id, UUID actorId, Instant now) {
        this.id = id; this.actorId = actorId; this.active = true; this.suspended = false;
        this.status = DriverStatus.OFFLINE;
        this.createdAt = now; this.updatedAt = now;
    }
}

@Entity
@Table(name = "vehicles")
class VehicleEntity {
    @Id UUID id;
    @Column(name = "driver_id", nullable = false) UUID driverId;
    @Column(nullable = false, length = 32) String type;
    @Column(nullable = false) int capacity;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected VehicleEntity() { }
    VehicleEntity(UUID id, UUID driverId, String type, int capacity, Instant now) {
        this.id = id; this.driverId = driverId; this.type = type; this.capacity = capacity;
        this.active = true; this.createdAt = now; this.updatedAt = now;
    }
}

interface DriverRepository extends JpaRepository<DriverEntity, UUID> {
    java.util.Optional<DriverEntity> findByActorId(UUID actorId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from DriverEntity d where d.actorId = :actorId")
    java.util.Optional<DriverEntity> findByActorIdForUpdate(@org.springframework.data.repository.query.Param("actorId") UUID actorId);
}
interface VehicleRepository extends JpaRepository<VehicleEntity, UUID> {
    boolean existsByDriverIdAndActiveTrue(UUID driverId);
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/drivers")
@Tag(name = "Driver fixtures", description = "Local/test loopback ADMIN routes for driver profiles.")
class DriverController {
    private final DriverService service;
    DriverController(DriverService service) { this.service = service; }
    @Schema(name = "CreateDriverRequest", description = "Creates a driver profile for an existing active actor.")
    record Request(@NotNull UUID actorId) { }
    @PostMapping
    @Operation(summary = "Create driver fixture", description = "Local/test ADMIN only. The DRIVER role is "
            + "provisioned separately; this route does not assign it.")
    @ApiResponse(responseCode = "201", description = "Driver profile created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverService.DriverView.class)))
    ResponseEntity<DriverService.DriverView> create(@Valid @RequestBody Request request) {
        var created = service.createDriver(request.actorId());
        return ResponseEntity.created(java.net.URI.create("/api/v1/drivers/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    @Operation(summary = "Read driver fixture", description = "Local/test ADMIN only. This administrative "
            + "lookup is not available to a DRIVER actor.")
    @ApiResponse(responseCode = "200", description = "Driver profile and active/suspended status.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverService.DriverView.class)))
    DriverService.DriverView get(@PathVariable UUID id) { return service.getDriver(id); }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Vehicle fixtures", description = "Local/test loopback ADMIN routes for driver vehicles.")
class VehicleController {
    private final DriverService service;
    VehicleController(DriverService service) { this.service = service; }
    @Schema(name = "CreateVehicleRequest", description = "Vehicle for an existing active, unsuspended driver.")
    record Request(@NotNull UUID driverId,
                   @Schema(example = "BIKE") @NotBlank @Size(max = 32) String type,
                   @Schema(example = "1") @Min(1) int capacity) { }
    @PostMapping
    @Operation(summary = "Create vehicle fixture", description = "Local/test ADMIN only. The referenced "
            + "driver must exist, be active, and not be suspended.")
    @ApiResponse(responseCode = "201", description = "Vehicle created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverService.VehicleView.class)))
    ResponseEntity<DriverService.VehicleView> create(@Valid @RequestBody Request request) {
        var created = service.createVehicle(request.driverId(), request.type(), request.capacity());
        return ResponseEntity.created(java.net.URI.create("/api/v1/vehicles/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    @Operation(summary = "Read vehicle fixture", description = "Local/test ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Vehicle and active status.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverService.VehicleView.class)))
    DriverService.VehicleView get(@PathVariable UUID id) { return service.getVehicle(id); }
}

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/drivers/me")
@Tag(name = "Driver self", description = "DRIVER role; the profile is selected from the authenticated actor.")
class DriverSelfController {
    private final DriverService service;
    private final DriverLifecycleService lifecycle;
    DriverSelfController(DriverService service, DriverLifecycleService lifecycle) {
        this.service = service;
        this.lifecycle = lifecycle;
    }
    @GetMapping
    @Operation(summary = "Read my driver status", description = "Returns only the caller's own minimal driver "
            + "status. A suspended driver may read this status; no driver lifecycle action is exposed here.")
    @ApiResponse(responseCode = "200", description = "My active and suspended flags.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverService.DriverSelfView.class)))
    DriverService.DriverSelfView me() { return service.me(); }

    @PostMapping("/online")
    @Operation(summary = "Go online", description = "DRIVER self action; requires OFFLINE and an active unsuspended profile.")
    @ApiResponse(responseCode = "204", description = "Driver is ONLINE.")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void online() { lifecycle.goOnline(); }

    @PostMapping("/available")
    @Operation(summary = "Become available", description = "DRIVER self action; requires ONLINE or PAUSED and an active vehicle.")
    @ApiResponse(responseCode = "204", description = "Driver is AVAILABLE.")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void available() { lifecycle.becomeAvailable(); }

    @PostMapping("/pause")
    @Operation(summary = "Pause work", description = "DRIVER self action from ONLINE or AVAILABLE.")
    @ApiResponse(responseCode = "204", description = "Driver is PAUSED.")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void pause() { lifecycle.pause(); }

    @PostMapping("/offline")
    @Operation(summary = "Go offline", description = "DRIVER self action from ONLINE, AVAILABLE, or PAUSED.")
    @ApiResponse(responseCode = "204", description = "Driver is OFFLINE.")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    void offline() { lifecycle.goOffline(); }
}
