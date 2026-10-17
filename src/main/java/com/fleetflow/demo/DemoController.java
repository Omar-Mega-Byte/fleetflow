package com.fleetflow.demo;

import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/demo", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "fleetflow.demo", name = "enabled", havingValue = "true")
@Tag(name = "Synthetic demo", description = "Authorized, bounded public-demo dataset controls.")
class DemoController {
    private final DemoSeedService seed;
    private final ActorAccess access;

    DemoController(DemoSeedService seed, ActorAccess access) {
        this.seed = seed;
        this.access = access;
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) { }
    record ResetRequest(@NotBlank String confirmation,
                        @NotBlank @Size(max = 500) String reason) { }

    @GetMapping
    @Operation(summary = "Inspect the current synthetic demo generation")
    @ApiResponse(responseCode = "200", description = "Known demo personas, order IDs, zone, and tracking baseline.")
    DemoSeedService.DemoView state() {
        access.require(Role.ADMIN);
        return seed.state();
    }

    @GetMapping("/driver-route")
    @Operation(summary = "Read fixture positions for the seeded synthetic driver",
            description = "Demo-enabled environments only. Five straight-line fixture points from the "
                    + "current synthetic order; no road route, speed or ETA is generated. "
                    + "Location updates still use the authenticated driver location endpoint.")
    DemoSeedService.DriverRoute driverRoute() {
        return seed.driverRoute(access.require(Role.DRIVER).id());
    }

    @PostMapping(value = "/seed", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create the deterministic synthetic demo baseline once")
    @ApiResponse(responseCode = "200", description = "Baseline created or existing generation returned.")
    DemoSeedService.DemoView seed(@RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody ReasonRequest request) {
        UUID actor = access.require(Role.ADMIN).id();
        try {
            return seed.seed(actor, key, request.reason());
        } catch (ApiFailure failure) {
            seed.recordFailure(actor, "SEED", key, request.reason(), failure.code());
            throw failure;
        } catch (RuntimeException failure) {
            seed.recordFailure(actor, "SEED", key, request.reason(), "ACTION_FAILED");
            throw failure;
        }
    }

    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Roll the synthetic demo forward to a clean order generation")
    @ApiResponse(responseCode = "200", description = "A new generation is active; older demo orders remain archived.")
    DemoSeedService.DemoView reset(@RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody ResetRequest request) {
        UUID actor = access.require(Role.ADMIN).id();
        try {
            return seed.reset(actor, key, request.confirmation(), request.reason());
        } catch (ApiFailure failure) {
            seed.recordFailure(actor, "RESET", key, request.reason(), failure.code());
            throw failure;
        } catch (RuntimeException failure) {
            seed.recordFailure(actor, "RESET", key, request.reason(), "ACTION_FAILED");
            throw failure;
        }
    }
}
