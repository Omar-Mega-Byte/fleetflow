package com.fleetflow.customer;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.IdentityService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CustomerService {
    private final CustomerRepository customers;
    private final IdentityService identity;
    private final Clock clock;

    CustomerService(CustomerRepository customers, IdentityService identity, Clock clock) {
        this.customers = customers;
        this.identity = identity;
        this.clock = clock;
    }

    @Transactional
    public CustomerView create(UUID actorId, String name) {
        identity.requireActive(actorId);
        Instant now = clock.instant();
        CustomerEntity customer = new CustomerEntity(UUID.randomUUID(), actorId, name.strip(), now);
        customers.saveAndFlush(customer);
        return view(customer);
    }

    @Transactional(readOnly = true)
    public CustomerView get(UUID id) {
        return view(customers.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found")));
    }

    @Transactional(readOnly = true)
    public CustomerView forActor(UUID actorId) {
        identity.requireActive(actorId);
        return view(customers.findByActorId(actorId).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found")));
    }

    private CustomerView view(CustomerEntity entity) {
        return new CustomerView(entity.id, entity.actorId, entity.displayName);
    }

    public record CustomerView(UUID id, UUID actorId, String displayName) { }
}

@Entity
@Table(name = "customers")
class CustomerEntity {
    @Id UUID id;
    @Column(name = "actor_id", nullable = false) UUID actorId;
    @Column(name = "display_name", nullable = false, length = 120) String displayName;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected CustomerEntity() { }

    CustomerEntity(UUID id, UUID actorId, String displayName, Instant now) {
        this.id = id;
        this.actorId = actorId;
        this.displayName = displayName;
        this.createdAt = now;
        this.updatedAt = now;
    }
}

interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {
    java.util.Optional<CustomerEntity> findByActorId(UUID actorId);
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/customers")
class CustomerController {
    private final CustomerService service;

    CustomerController(CustomerService service) {
        this.service = service;
    }

    record Request(@NotNull UUID actorId, @NotBlank @Size(max = 120) String displayName) { }

    @PostMapping
    ResponseEntity<CustomerService.CustomerView> create(@Valid @RequestBody Request request) {
        var created = service.create(request.actorId(), request.displayName());
        return ResponseEntity.created(java.net.URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    CustomerService.CustomerView get(@PathVariable UUID id) {
        return service.get(id);
    }
}
