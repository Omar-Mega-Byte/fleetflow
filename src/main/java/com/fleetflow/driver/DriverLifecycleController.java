package com.fleetflow.driver;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/drivers/me")
@Tag(name = "Driver lifecycle", description = "DRIVER role; only the authenticated driver's own profile changes.")
class DriverLifecycleController {
    private final DriverLifecycleService service;
    DriverLifecycleController(DriverLifecycleService service) { this.service = service; }

    @PostMapping("/online")
    @Operation(summary = "Go online", description = "OFFLINE to ONLINE for an active, unsuspended driver. Duplicate/wrong-state commands return 409 LIFECYCLE_CONFLICT.")
    @ApiResponse(responseCode = "200", description = "Driver state and version.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverLifecycleService.StateView.class)))
    DriverLifecycleService.StateView online() { return service.online(); }

    @PostMapping("/available")
    @Operation(summary = "Become available or resume", description = "ONLINE or PAUSED to AVAILABLE. Requires an active suitable vehicle; fresh Phase 4 location is still required for dispatch.")
    @ApiResponse(responseCode = "200", description = "Driver state and version.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverLifecycleService.StateView.class)))
    DriverLifecycleService.StateView available() { return service.available(); }

    @PostMapping("/pause")
    @Operation(summary = "Pause availability", description = "ONLINE or AVAILABLE to PAUSED.")
    @ApiResponse(responseCode = "200", description = "Driver state and version.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverLifecycleService.StateView.class)))
    DriverLifecycleService.StateView pause() { return service.pause(); }

    @PostMapping("/offline")
    @Operation(summary = "Go offline", description = "ONLINE, AVAILABLE or PAUSED to OFFLINE.")
    @ApiResponse(responseCode = "200", description = "Driver state and version.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DriverLifecycleService.StateView.class)))
    DriverLifecycleService.StateView offline() { return service.offline(); }
}
