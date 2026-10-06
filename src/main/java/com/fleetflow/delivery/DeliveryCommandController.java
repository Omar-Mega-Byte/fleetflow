package com.fleetflow.delivery;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers/me/deliveries/{deliveryId}")
@Tag(name = "Driver deliveries", description = "Progress the exact assigned delivery.")
class DeliveryCommandController {
    private final DeliveryCommandService deliveries;
    DeliveryCommandController(DeliveryCommandService deliveries) { this.deliveries = deliveries; }

    record ReasonBody(@NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/pickup")
    @Operation(summary = "Pick up my assigned delivery")
    @ApiResponse(responseCode = "200", description = "Delivery picked up.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryState.class)))
    DeliveryCommandService.DeliveryState pickup(@PathVariable UUID deliveryId) {
        return deliveries.pickup(deliveryId);
    }
    @PostMapping("/start-transit")
    @Operation(summary = "Start transit for my picked-up delivery")
    @ApiResponse(responseCode = "200", description = "Delivery in transit.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryState.class)))
    DeliveryCommandService.DeliveryState transit(@PathVariable UUID deliveryId) {
        return deliveries.transit(deliveryId);
    }
    @PostMapping("/complete")
    @Operation(summary = "Complete my in-transit delivery")
    @ApiResponse(responseCode = "200", description = "Delivery completed and driver released.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryState.class)))
    DeliveryCommandService.DeliveryState complete(@PathVariable UUID deliveryId) {
        return deliveries.complete(deliveryId);
    }
    @PostMapping("/fail")
    @Operation(summary = "Fail my picked-up delivery with a reason")
    @ApiResponse(responseCode = "200", description = "Delivery failed and incident opened.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryState.class)))
    DeliveryCommandService.DeliveryState fail(@PathVariable UUID deliveryId,
                                               @Valid @RequestBody ReasonBody body) {
        return deliveries.fail(deliveryId, body.reason());
    }
}

@RestController
@RequestMapping("/api/v1/drivers/me/deliveries")
@Tag(name = "Driver deliveries", description = "Read only the authenticated driver's deliveries.")
class DriverDeliveryReadController {
    private final DeliveryCommandService deliveries;
    DriverDeliveryReadController(DeliveryCommandService deliveries) { this.deliveries = deliveries; }

    @GetMapping
    @Operation(summary = "Read my bounded delivery history")
    @ApiResponse(responseCode = "200", description = "At most 20 own deliveries.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryPage.class)))
    DeliveryCommandService.DeliveryPage mine(@RequestParam(defaultValue = "20") int limit) {
        return deliveries.myDeliveries(limit);
    }
}
