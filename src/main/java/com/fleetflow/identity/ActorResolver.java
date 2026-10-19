package com.fleetflow.identity;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActorResolver {
    private final ActorRepository actors;
    private final JdbcTemplate jdbc;

    ActorResolver(ActorRepository actors, JdbcTemplate jdbc) {
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public AuthenticatedActor resolve(String issuer, String subject) {
        ActorEntity actor = actors.findByIssuerAndSubject(issuer, subject)
                .orElseThrow(ActorRejectedException::new);
        return resolveActor(actor);
    }

    @Transactional(readOnly = true)
    public AuthenticatedActor resolveById(UUID id) {
        ActorEntity actor = actors.findById(id).orElseThrow(ActorRejectedException::new);
        return resolveActor(actor);
    }

    private AuthenticatedActor resolveActor(ActorEntity actor) {
        if (!actor.active) {
            throw new ActorRejectedException();
        }
        Set<Role> roles = jdbc.queryForList("select role from actor_roles where actor_id = ?", String.class,
                actor.id).stream().map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedActor(actor.id, roles);
    }

    public static class ActorRejectedException extends RuntimeException { }
}
