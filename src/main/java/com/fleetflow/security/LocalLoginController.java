package com.fleetflow.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "First-party demo credentials and short-lived opaque sessions.")
public class LocalLoginController {
    private final LocalLoginService service;

    LocalLoginController(LocalLoginService service) { this.service = service; }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sign in with a provisioned FleetFlow demo email and password")
    @ApiResponse(responseCode = "200", description = "A short-lived opaque session token.")
    ResponseEntity<LocalLoginService.LoginResult> login(@Valid @RequestBody Credentials body,
                                                         HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.login(body.email(), body.password(), request.getRemoteAddr()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a first-party demo session")
    @ApiResponse(responseCode = "204", description = "Session revoked or already absent.")
    ResponseEntity<Void> logout(@RequestHeader(value = "X-FleetFlow-Session", required = false) String token) {
        service.logout(token);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    record Credentials(@NotBlank String email, @NotBlank String password) { }
}
