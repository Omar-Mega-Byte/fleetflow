package com.fleetflow.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
public class IdentityService {
    private final ActorRepository actors;
    private final Clock clock;

    IdentityService(ActorRepository actors, Clock clock) {
        this.actors = actors;
        this.clock = clock;
    }

    @Transactional
    public ActorView create(String issuer, String subject) {
        Instant now = clock.instant();
        ActorEntity actor = new ActorEntity(UUID.randomUUID(), issuer.strip(), subject.strip(), now);
        actors.saveAndFlush(actor);
        return view(actor);
    }

    @Transactional(readOnly = true)
    public ActorView get(UUID id) {
        return view(actors.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "ACTOR_NOT_FOUND", "Actor not found")));
    }

    @Transactional(readOnly = true)
    public void requireActive(UUID id) {
        ActorEntity actor = actors.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "ACTOR_NOT_FOUND", "Actor not found"));
        if (!actor.active) {
            throw new ApiFailure(HttpStatus.CONFLICT, "ACTOR_INACTIVE", "Actor is inactive");
        }
    }

    private ActorView view(ActorEntity actor) {
        return new ActorView(actor.id, actor.active);
    }

    public record ActorView(UUID id, boolean active) { }
}

@Entity
@Table(name = "actors")
class ActorEntity {
    @Id UUID id;
    @Column(nullable = false, length = 255) String issuer;
    @Column(nullable = false, length = 255) String subject;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected ActorEntity() { }

    ActorEntity(UUID id, String issuer, String subject, Instant now) {
        this.id = id;
        this.issuer = issuer;
        this.subject = subject;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }
}

interface ActorRepository extends JpaRepository<ActorEntity, UUID> {
    java.util.Optional<ActorEntity> findByIssuerAndSubject(String issuer, String subject);
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/actors")
class ActorController {
    private final IdentityService service;

    ActorController(IdentityService service) {
        this.service = service;
    }

    record Request(@NotBlank @Size(max = 255) String issuer,
                   @NotBlank @Size(max = 255) String subject) { }

    @PostMapping
    ResponseEntity<IdentityService.ActorView> create(@Valid @RequestBody Request request) {
        var created = service.create(request.issuer(), request.subject());
        return ResponseEntity.created(java.net.URI.create("/api/v1/actors/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    IdentityService.ActorView get(@PathVariable UUID id) {
        return service.get(id);
    }
}
