package com.fleetflow.order;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchant/orders")
@Tag(name = "Merchant order lifecycle", description = "MERCHANT role and active exact-branch membership.")
class OrderLifecycleController {
    private final OrderLifecycleService service;

    OrderLifecycleController(OrderLifecycleService service) { this.service = service; }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm a created order")
    @ApiResponse(responseCode = "204", description = "Order confirmed and history recorded.")
    ResponseEntity<Void> confirm(@PathVariable UUID id) { service.confirm(id); return ResponseEntity.noContent().build(); }

    @PostMapping("/{id}/prepare")
    @Operation(summary = "Start preparing a confirmed order")
    @ApiResponse(responseCode = "204", description = "Order preparing and history recorded.")
    ResponseEntity<Void> prepare(@PathVariable UUID id) { service.prepare(id); return ResponseEntity.noContent().build(); }

    @PostMapping("/{id}/ready")
    @Operation(summary = "Mark a preparing order ready for pickup")
    @ApiResponse(responseCode = "204", description = "Order ready and history recorded.")
    ResponseEntity<Void> ready(@PathVariable UUID id) { service.ready(id); return ResponseEntity.noContent().build(); }
}
