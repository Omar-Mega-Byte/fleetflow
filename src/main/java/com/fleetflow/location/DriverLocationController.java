package com.fleetflow.location;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/drivers/me")
@Tag(name = "Driver self", description = "DRIVER role; live position is only writable by the authenticated driver.")
class DriverLocationController {
    private final LocationService location;

    DriverLocationController(LocationService location) { this.location = location; }

    @PostMapping("/location")
    @Operation(summary = "Update my location", description = "Accepts a newer WGS84 fix for the authenticated "
            + "ONLINE or AVAILABLE driver. No driver ID is accepted as identity.")
    @ApiResponse(responseCode = "204", description = "Latest fix accepted.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@RequestBody LocationService.LocationRequest request) { location.update(request); }

    @PostMapping("/heartbeat")
    @Operation(summary = "Refresh my heartbeat", description = "Renews liveness only; does not change GPS freshness.")
    @ApiResponse(responseCode = "204", description = "Heartbeat accepted.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void heartbeat() { location.heartbeat(); }
}
