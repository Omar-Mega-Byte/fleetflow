package com.fleetflow.events;

import java.util.UUID;

import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import com.fleetflow.api.ApiFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/operations/outbox", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Outbox recovery", description = "Bounded inspection and audited replay of terminal publisher failures.")
class OutboxRecoveryController {
    private final OutboxRecoveryService service;
    private final ActorAccess access;

    OutboxRecoveryController(OutboxRecoveryService service, ActorAccess access) {
        this.service = service;
        this.access = access;
    }

    record ReplayRequest(@NotBlank @Size(max = 500) String reason) { }

    @GetMapping("/failed")
    @Operation(summary = "Inspect bounded outbox failures without exposing event payloads")
    @ApiResponse(responseCode = "200", description = "At most 100 failed rows; no envelope or customer data.")
    OutboxRecoveryService.FailedPage failed(@RequestParam(defaultValue = "50") int limit) {
        access.require(Role.ADMIN);
        return service.failed(limit);
    }

    @PostMapping(value = "/{eventId}/replay", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Requeue one failed event through the normal publisher and dedupe path")
    @ApiResponse(responseCode = "200", description = "Replay accepted; publication is asynchronous.")
    OutboxRecoveryService.ReplayResult replay(@PathVariable UUID eventId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody ReplayRequest request) {
        UUID actorId = access.require(Role.ADMIN).id();
        var result = service.replay(actorId, eventId, idempotencyKey, request.reason());
        if (!"REPLAYED".equals(result.outcome())) {
            throw new ApiFailure(HttpStatus.CONFLICT, result.outcome(), "Outbox event is not replayable");
        }
        return result;
    }
}
