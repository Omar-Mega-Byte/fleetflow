package com.fleetflow.security;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.AuthenticatedActor;
import com.fleetflow.identity.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ActorAccess {
    public AuthenticatedActor require(Role role) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof AuthenticatedActor actor)) {
            throw new ApiFailure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        actor.require(role);
        return actor;
    }
}
