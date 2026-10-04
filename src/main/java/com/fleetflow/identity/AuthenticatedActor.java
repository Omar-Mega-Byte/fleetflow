package com.fleetflow.identity;

import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import org.springframework.http.HttpStatus;

public record AuthenticatedActor(UUID id, Set<Role> roles) {
    public AuthenticatedActor {
        roles = Set.copyOf(roles);
    }

    public void require(Role role) {
        if (!roles.contains(role)) {
            throw new ApiFailure(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
        }
    }
}
