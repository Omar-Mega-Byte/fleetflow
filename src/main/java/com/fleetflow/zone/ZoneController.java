package com.fleetflow.zone;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/merchant-branches/{branchId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Delivery zones", description = "Signed MERCHANT and current exact-branch membership required")
class ZoneController {
    private final ZoneService zones;

    ZoneController(ZoneService zones) { this.zones = zones; }

    record ZoneInput(@NotBlank @Size(max = 120) String name, @NotNull JsonNode geometry,
                     @NotNull Boolean active) { }
    record ZoneReplace(@NotBlank @Size(max = 120) String name, @NotNull JsonNode geometry,
                       @NotNull Boolean active, @NotNull @Min(0) Long expectedVersion) { }
    record ModeInput(@NotBlank String mode, @NotNull @Min(0) Long expectedVersion) { }
    record PointInput(@NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
                      BigDecimal latitude,
                      @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
                      BigDecimal longitude) { }

    @GetMapping("/zones")
    @Operation(summary = "List my branch's delivery zones, including inactive zones")
    List<ZoneService.ZoneView> list(@PathVariable UUID branchId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return zones.list(branchId, limit);
    }

    @PostMapping("/zones")
    @Operation(summary = "Create a validated branch delivery zone")
    @ApiResponse(responseCode = "201", description = "Created zone.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ZoneService.ZoneView.class)))
    ResponseEntity<ZoneService.ZoneView> create(@PathVariable UUID branchId,
                                                 @Valid @RequestBody ZoneInput request) {
        var created = zones.create(branchId, request.name(), request.geometry(), request.active());
        return ResponseEntity.created(URI.create("/api/v1/merchant-branches/" + branchId
                + "/zones/" + created.id())).body(created);
    }

    @PutMapping("/zones/{zoneId}")
    @Operation(summary = "Replace a zone's name, shape and active state with optimistic version checking")
    ZoneService.ZoneView replace(@PathVariable UUID branchId, @PathVariable UUID zoneId,
                                 @Valid @RequestBody ZoneReplace request) {
        return zones.replace(branchId, zoneId, request.name(), request.geometry(),
                request.active(), request.expectedVersion());
    }

    @GetMapping("/zone-policy")
    @Operation(summary = "Read my branch's serviceability mode and policy version")
    ZoneService.Policy policy(@PathVariable UUID branchId) { return zones.policy(branchId); }

    @PutMapping("/zone-policy")
    @Operation(summary = "Change my branch's explicit serviceability mode")
    ZoneService.Policy mode(@PathVariable UUID branchId, @Valid @RequestBody ModeInput request) {
        return zones.changeMode(branchId, request.mode(), request.expectedVersion());
    }

    @PostMapping("/serviceability")
    @Operation(summary = "Preview whether my branch covers a WGS84 destination")
    ZoneService.Preview preview(@PathVariable UUID branchId, @Valid @RequestBody PointInput point) {
        return zones.preview(branchId, point.latitude(), point.longitude());
    }
}
