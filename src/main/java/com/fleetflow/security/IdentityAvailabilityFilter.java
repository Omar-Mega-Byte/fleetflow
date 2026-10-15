package com.fleetflow.security;

import java.io.IOException;
import com.fleetflow.api.SecurityProblemWriter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Nimbus key-fetch failures are service failures; no unverified identity reaches the actor resolver. */
final class IdentityAvailabilityFilter extends OncePerRequestFilter {
    private final SecurityProblemWriter errors;
    private final MeterRegistry metrics;
    IdentityAvailabilityFilter(SecurityProblemWriter errors, MeterRegistry metrics) {
        this.errors = errors; this.metrics = metrics;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (AuthenticationServiceException unavailable) {
            SecurityContextHolder.clearContext();
            metrics.counter("fleetflow.identity.verification", "outcome", "unavailable").increment();
            errors.write(request, response, 503, "IDENTITY_UNAVAILABLE", "Identity verification is temporarily unavailable");
        }
    }
}
