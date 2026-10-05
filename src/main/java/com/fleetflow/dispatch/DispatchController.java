package com.fleetflow.dispatch;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch/orders/{orderId}")
@Tag(name = "Dispatch matching", description = "DISPATCHER only; ranking evidence, without assignment.")
class DispatchController {
    private final DispatchService service;

    DispatchController(DispatchService service) { this.service = service; }

    @PostMapping("/match")
    @Operation(summary = "Start one match for a ready order")
    @ApiResponse(responseCode = "201", description = "Committed immutable matching attempt.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DispatchService.StartResult.class)))
    ResponseEntity<DispatchService.StartResult> match(@PathVariable UUID orderId) {
        var result = service.start(orderId);
        return ResponseEntity.created(URI.create("/api/v1/dispatch/orders/" + orderId + "/attempts"))
                .body(result);
    }

    @GetMapping("/attempts")
    @Operation(summary = "Read bounded matching attempts and inspected candidates")
    @ApiResponse(responseCode = "200", description = "At most 20 newest attempts.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DispatchService.AttemptPage.class)))
    DispatchService.AttemptPage attempts(@PathVariable UUID orderId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return service.attempts(orderId, limit);
    }
}
