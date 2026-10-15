package com.fleetflow.identity;

import java.util.List;

import com.fleetflow.security.ActorAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/session", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Session", description = "Effective application roles for the authenticated actor.")
class SessionController {
    private final ActorAccess access;

    SessionController(ActorAccess access) { this.access = access; }

    @GetMapping
    @Operation(summary = "Read my FleetFlow persona roles")
    @ApiResponse(responseCode = "200", description = "Roles resolved from the active server-side actor mapping.")
    SessionView me() {
        var actor = access.requireAny(Role.values());
        return new SessionView(actor.roles().stream().map(Enum::name).sorted().toList());
    }

    @Schema(name = "SessionView")
    record SessionView(List<String> roles) { }
}
