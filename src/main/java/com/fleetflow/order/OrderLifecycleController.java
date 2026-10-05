package com.fleetflow.order;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

record CancelRequest(@NotBlank @Size(max = 500) String reason) { }

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/merchant-branches/{branchId}/orders/{orderId}")
@Tag(name = "Merchant order lifecycle", description = "MERCHANT role and current exact active branch membership required.")
class MerchantOrderLifecycleController {
    private final OrderLifecycleService service;
    MerchantOrderLifecycleController(OrderLifecycleService service) { this.service = service; }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm a created order", description = "CREATED to CONFIRMED. Returns 409 LIFECYCLE_CONFLICT for a duplicate or invalid state.")
    @ApiResponse(responseCode = "200", description = "Order state and version after confirmation.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView confirm(@PathVariable UUID branchId, @PathVariable UUID orderId) {
        return service.confirm(branchId, orderId);
    }

    @PostMapping("/start-preparation")
    @Operation(summary = "Start order preparation", description = "CONFIRMED to PREPARING; this closes customer cancellation.")
    @ApiResponse(responseCode = "200", description = "Order state and version after preparation starts.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView start(@PathVariable UUID branchId, @PathVariable UUID orderId) {
        return service.startPreparation(branchId, orderId);
    }

    @PostMapping("/ready-for-pickup")
    @Operation(summary = "Mark order ready for pickup", description = "PREPARING to READY_FOR_PICKUP. Creates no delivery or assignment.")
    @ApiResponse(responseCode = "200", description = "Order state and version after ready.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView ready(@PathVariable UUID branchId, @PathVariable UUID orderId) {
        return service.readyForPickup(branchId, orderId);
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel a branch order", description = "CREATED, CONFIRMED, PREPARING or READY_FOR_PICKUP to CANCELLED. A trimmed reason of 1–500 characters is required.")
    @ApiResponse(responseCode = "200", description = "Cancelled order state and version.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView cancel(@PathVariable UUID branchId, @PathVariable UUID orderId,
                                           @Valid @RequestBody CancelRequest request) {
        return service.cancelMerchant(branchId, orderId, request.reason());
    }
}

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/orders/{orderId}")
@Tag(name = "Customer order lifecycle", description = "CUSTOMER role and current order ownership required.")
class CustomerOrderLifecycleController {
    private final OrderLifecycleService service;
    CustomerOrderLifecycleController(OrderLifecycleService service) { this.service = service; }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel my order", description = "CREATED or CONFIRMED to CANCELLED. A trimmed reason of 1–500 characters is required; preparation closes this action.")
    @ApiResponse(responseCode = "200", description = "Cancelled order state and version.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView cancel(@PathVariable UUID orderId, @Valid @RequestBody CancelRequest request) {
        return service.cancelCustomer(orderId, request.reason());
    }
}
