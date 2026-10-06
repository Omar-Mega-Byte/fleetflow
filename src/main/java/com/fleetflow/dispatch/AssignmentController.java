package com.fleetflow.dispatch;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch/orders/{orderId}")
@Tag(name = "Assignment dispatch", description = "Dispatcher-only offer command and bounded assignment history.")
class AssignmentController {
    private final AssignmentService assignments;
    AssignmentController(AssignmentService assignments) { this.assignments = assignments; }

    record OfferRequest(@NotNull UUID attemptId, @NotNull UUID driverId) { }

    @PostMapping("/offers")
    @Operation(summary = "Offer one ranked candidate for a dispatching order")
    @ApiResponse(responseCode = "201", description = "Reservation and offer committed.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentService.OfferReceipt.class)))
    ResponseEntity<AssignmentService.OfferReceipt> offer(@PathVariable UUID orderId,
            @Valid @RequestBody OfferRequest request) {
        var created = assignments.offer(orderId, request.attemptId(), request.driverId());
        return ResponseEntity.created(URI.create("/api/v1/dispatch/orders/" + orderId + "/assignments"))
                .body(created);
    }

    @GetMapping("/assignments")
    @Operation(summary = "Read bounded assignment history for an order")
    @ApiResponse(responseCode = "200", description = "At most 20 newest assignments.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentService.AssignmentPage.class)))
    AssignmentService.AssignmentPage assignments(@PathVariable UUID orderId,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return new AssignmentService.AssignmentPage(assignments.assignments(orderId, limit));
    }
}

@RestController
@RequestMapping("/api/v1/drivers/me/offers")
@Tag(name = "Driver offers", description = "Only the authenticated offered driver can read or respond.")
class DriverOfferController {
    private final AssignmentService assignments;
    DriverOfferController(AssignmentService assignments) { this.assignments = assignments; }

    @GetMapping
    @Operation(summary = "Read my bounded offer history")
    @ApiResponse(responseCode = "200", description = "At most 20 own offers.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentService.DriverOfferPage.class)))
    AssignmentService.DriverOfferPage offers(@RequestParam(defaultValue = "20") int limit) {
        return new AssignmentService.DriverOfferPage(assignments.myOffers(limit));
    }

    @PostMapping("/{offerId}/accept")
    @Operation(summary = "Accept my unexpired offer")
    @ApiResponse(responseCode = "200", description = "Offer accepted and assignment committed.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentService.OfferReceipt.class)))
    AssignmentService.OfferReceipt accept(@PathVariable UUID offerId) {
        return assignments.respond(offerId, true);
    }

    @PostMapping("/{offerId}/reject")
    @Operation(summary = "Reject my unexpired offer")
    @ApiResponse(responseCode = "200", description = "Offer rejected and reservation released.",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = AssignmentService.OfferReceipt.class)))
    AssignmentService.OfferReceipt reject(@PathVariable UUID offerId) {
        return assignments.respond(offerId, false);
    }
}
