package com.fleetflow.tracking;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.api.SecurityProblemWriter;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/orders/{orderId}/tracking")
@Tag(name = "Customer tracking", description = "Owned order snapshots and short-lived live delivery streams.")
class TrackingController {
    private final TrackingSnapshotReader reader;
    private final TrackingHub hub;
    private final ActorAccess access;
    private final SecurityProblemWriter errors;

    TrackingController(TrackingSnapshotReader reader, TrackingHub hub, ActorAccess access,
                       SecurityProblemWriter errors) {
        this.reader = reader;
        this.hub = hub;
        this.access = access;
        this.errors = errors;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Read my order's current tracking snapshot")
    @ApiResponse(responseCode = "200", description = "Current owner-scoped tracking view.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TrackingSnapshotReader.Snapshot.class)))
    ResponseEntity<TrackingSnapshotReader.Snapshot> snapshot(@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(reader.read(orderId, access.require(Role.CUSTOMER).id()));
    }

    @GetMapping("/stream")
    @Operation(summary = "Stream my order's tracking changes")
    @ApiResponse(responseCode = "200", description = "Snapshot first, followed by tracking events and heartbeats.",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = TrackingSnapshotReader.Snapshot.class)))
    ResponseEntity<SseEmitter> stream(@PathVariable UUID orderId, HttpServletRequest request) {
        UUID actorId = access.require(Role.CUSTOMER).id();
        Instant expiresAt = (Instant) request.getAttribute("fleetflowTokenExpiresAt");
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(hub.open(orderId, actorId, expiresAt));
    }

    @ExceptionHandler(ApiFailure.class)
    void streamError(ApiFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        errors.write(request, response, failure.status().value(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    void unavailable(RuntimeException failure, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        errors.write(request, response, 503, "DATABASE_UNAVAILABLE", "Database is unavailable");
    }
}
