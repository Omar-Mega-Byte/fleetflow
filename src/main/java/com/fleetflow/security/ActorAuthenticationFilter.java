package com.fleetflow.security;

import java.io.IOException;

import com.fleetflow.api.SecurityProblemWriter;
import com.fleetflow.identity.ActorResolver;
import com.fleetflow.identity.AuthenticatedActor;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

public class ActorAuthenticationFilter extends OncePerRequestFilter {
    private final ActorResolver resolver;
    private final SecurityProblemWriter errors;
    private final LocalLoginService localLogin;

    public ActorAuthenticationFilter(ActorResolver resolver, SecurityProblemWriter errors, LocalLoginService localLogin) {
        this.resolver = resolver;
        this.errors = errors;
        this.localLogin = localLogin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (path.equals("/api/v1/auth/logout")) return true;
        return !(path.startsWith("/api/v1/") || path.equals("/actuator/metrics")
                || path.startsWith("/actuator/metrics/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String localToken = request.getHeader("X-FleetFlow-Session");
        if (localToken != null && request.getHeader("Authorization") != null) {
            errors.write(request, response, 400, "INVALID_AUTHENTICATION", "Choose one authentication method");
            return;
        }
        if (localToken != null) {
            try {
                var session = localLogin.authenticate(localToken);
                if (session == null) {
                    errors.write(request, response, 401, "UNAUTHENTICATED", "Authentication required");
                    return;
                }
                request.setAttribute("fleetflowTokenExpiresAt", session.expiresAt());
                setActor(session.actor());
            } catch (DataAccessException | PersistenceException | CannotCreateTransactionException unavailable) {
                SecurityContextHolder.clearContext();
                errors.write(request, response, 503, "DATABASE_UNAVAILABLE", "Database is unavailable");
                return;
            }
        } else if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            try {
                request.setAttribute("fleetflowTokenExpiresAt", jwt.getToken().getExpiresAt());
                AuthenticatedActor actor = resolver.resolve(jwt.getToken().getIssuer().toString(),
                        jwt.getToken().getSubject());
                setActor(actor);
            } catch (ActorResolver.ActorRejectedException rejected) {
                SecurityContextHolder.clearContext();
                errors.write(request, response, 403, "FORBIDDEN", "Access denied");
                return;
            } catch (DataAccessException | PersistenceException | CannotCreateTransactionException unavailable) {
                SecurityContextHolder.clearContext();
                errors.write(request, response, 503, "DATABASE_UNAVAILABLE", "Database is unavailable");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void setActor(AuthenticatedActor actor) {
        var authorities = actor.roles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, null, authorities));
    }
}
