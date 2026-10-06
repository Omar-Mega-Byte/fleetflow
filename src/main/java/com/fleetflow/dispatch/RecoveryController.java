package com.fleetflow.dispatch;

import java.util.UUID;

import com.fleetflow.delivery.DeliveryCommandService;
import com.fleetflow.order.OrderLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch/orders/{orderId}")
@Tag(name = "Dispatch recovery", description = "Dispatcher recovery commands and bounded incident history.")
class RecoveryController {
    private final DispatchService dispatch;
    private final RecoveryCommandService recovery;
    private final DeliveryCommandService deliveries;

    RecoveryController(DispatchService dispatch, RecoveryCommandService recovery,
                       DeliveryCommandService deliveries) {
        this.dispatch = dispatch;
        this.recovery = recovery;
        this.deliveries = deliveries;
    }

    record ReasonBody(@NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/retry")
    @Operation(summary = "Retry failed dispatch with a reason")
    @ApiResponse(responseCode = "200", description = "New immutable matching attempt.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DispatchService.StartResult.class)))
    DispatchService.StartResult retry(@PathVariable UUID orderId, @Valid @RequestBody ReasonBody body) {
        return dispatch.retryManual(orderId, body.reason());
    }
    @PostMapping("/reassign")
    @Operation(summary = "Replace an assigned driver before pickup")
    @ApiResponse(responseCode = "200", description = "Previous delivery closed; replacement queued.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RecoveryCommandService.RecoveryResult.class)))
    RecoveryCommandService.RecoveryResult reassign(@PathVariable UUID orderId,
                                                   @Valid @RequestBody ReasonBody body) {
        return recovery.reassign(orderId, body.reason());
    }
    @PostMapping("/cancel")
    @Operation(summary = "Cancel a dispatching or assigned order before pickup")
    @ApiResponse(responseCode = "200", description = "Order, assignment and delivery closed.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = OrderLifecycleService.StateView.class)))
    OrderLifecycleService.StateView cancel(@PathVariable UUID orderId,
                                           @Valid @RequestBody ReasonBody body) {
        return recovery.cancelDispatcher(orderId, body.reason());
    }
    @PostMapping("/incidents/fail")
    @Operation(summary = "Fail an active delivery after pickup with a reason")
    @ApiResponse(responseCode = "200", description = "Delivery failed and incident opened.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = DeliveryCommandService.DeliveryState.class)))
    DeliveryCommandService.DeliveryState fail(@PathVariable UUID orderId,
                                               @Valid @RequestBody ReasonBody body) {
        return deliveries.dispatcherFail(orderId, body.reason());
    }
    @GetMapping("/incidents")
    @Operation(summary = "Read bounded incidents for an order")
    @ApiResponse(responseCode = "200", description = "At most 20 newest incidents.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RecoveryCommandService.IncidentPage.class)))
    RecoveryCommandService.IncidentPage incidents(@PathVariable UUID orderId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return recovery.incidents(orderId, limit);
    }
    @PostMapping("/incidents/{incidentId}/resolve")
    @Operation(summary = "Record a resolution for an open incident")
    @ApiResponse(responseCode = "200", description = "Incident resolution recorded.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RecoveryCommandService.Incident.class)))
    RecoveryCommandService.Incident resolve(@PathVariable UUID orderId, @PathVariable UUID incidentId,
                                             @Valid @RequestBody ReasonBody body) {
        return recovery.resolveIncident(orderId, incidentId, body.reason());
    }
}
