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

    public ActorAuthenticationFilter(ActorResolver resolver, SecurityProblemWriter errors) {
        this.resolver = resolver;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            try {
                AuthenticatedActor actor = resolver.resolve(jwt.getToken().getIssuer().toString(),
                        jwt.getToken().getSubject());
                var authorities = actor.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(actor, null, authorities));
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
}
