package com.fleetflow.merchant;

import java.math.BigDecimal;
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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
public class MerchantService {
    private final MerchantRepository merchants;
    private final BranchRepository branches;
    private final MembershipRepository memberships;
    private final IdentityService identity;
    private final Clock clock;

    MerchantService(MerchantRepository merchants, BranchRepository branches,
                    MembershipRepository memberships, IdentityService identity, Clock clock) {
        this.merchants = merchants;
        this.branches = branches;
        this.memberships = memberships;
        this.identity = identity;
        this.clock = clock;
    }

    @Transactional
    public MerchantView createMerchant(String name) {
        MerchantEntity entity = new MerchantEntity(UUID.randomUUID(), name.strip(), clock.instant());
        merchants.saveAndFlush(entity);
        return new MerchantView(entity.id, entity.name, entity.active);
    }

    @Transactional(readOnly = true)
    public MerchantView getMerchant(UUID id) {
        MerchantEntity entity = merchants.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
        return new MerchantView(entity.id, entity.name, entity.active);
    }

    @Transactional
    public BranchView createBranch(BranchRequest request) {
        MerchantEntity merchant = merchants.findById(request.merchantId()).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
        if (!merchant.active) {
            throw new ApiFailure(HttpStatus.CONFLICT, "MERCHANT_INACTIVE", "Merchant is inactive");
        }
        BranchEntity entity = new BranchEntity(UUID.randomUUID(), merchant.id, request.name().strip(),
                request.pickupLine1().strip(), request.pickupCity().strip(),
                request.pickupLatitude(), request.pickupLongitude(), clock.instant());
        branches.saveAndFlush(entity);
        return view(entity, merchant);
    }

    @Transactional(readOnly = true)
    public BranchView getBranch(UUID id) {
        BranchEntity branch = branches.findById(id).orElseThrow(() ->
                new ApiFailure(HttpStatus.NOT_FOUND, "BRANCH_NOT_FOUND", "Branch not found"));
        return view(branch, merchants.findById(branch.merchantId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public BranchView branchForOrder(UUID id) {
        BranchView branch = getBranch(id);
        if (!branch.active() || !branch.merchantActive()) {
            throw new ApiFailure(HttpStatus.CONFLICT, "BRANCH_INACTIVE", "Branch is inactive");
        }
        return branch;
    }

    @Transactional
    public MembershipView addMembership(UUID actorId, UUID branchId) {
        identity.requireActive(actorId);
        getBranch(branchId);
        MembershipEntity entity = new MembershipEntity(UUID.randomUUID(), actorId, branchId, clock.instant());
        memberships.saveAndFlush(entity);
        return new MembershipView(entity.id, entity.actorId, entity.branchId, entity.active);
    }

    private BranchView view(BranchEntity branch, MerchantEntity merchant) {
        return new BranchView(branch.id, branch.merchantId, merchant.name, merchant.active,
                branch.name, branch.pickupLine1, branch.pickupCity, branch.pickupLatitude,
                branch.pickupLongitude, branch.active);
    }

    public record MerchantView(UUID id, String name, boolean active) { }
    public record BranchView(UUID id, UUID merchantId, String merchantName, boolean merchantActive,
                             String name, String pickupLine1, String pickupCity,
                             BigDecimal pickupLatitude, BigDecimal pickupLongitude, boolean active) { }
    public record MembershipView(UUID id, UUID actorId, UUID branchId, boolean active) { }
    public record BranchRequest(@NotNull UUID merchantId, @NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 200) String pickupLine1,
                                @NotBlank @Size(max = 120) String pickupCity,
                                @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
                                BigDecimal pickupLatitude,
                                @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
                                BigDecimal pickupLongitude) { }
}

@Entity
@Table(name = "merchants")
class MerchantEntity {
    @Id UUID id;
    @Column(nullable = false, length = 120) String name;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected MerchantEntity() { }
    MerchantEntity(UUID id, String name, Instant now) {
        this.id = id; this.name = name; this.active = true; this.createdAt = now; this.updatedAt = now;
    }
}

@Entity
@Table(name = "merchant_branches")
class BranchEntity {
    @Id UUID id;
    @Column(name = "merchant_id", nullable = false) UUID merchantId;
    @Column(nullable = false, length = 120) String name;
    @Column(name = "pickup_line1", nullable = false, length = 200) String pickupLine1;
    @Column(name = "pickup_city", nullable = false, length = 120) String pickupCity;
    @Column(name = "pickup_latitude", nullable = false, precision = 9, scale = 6) BigDecimal pickupLatitude;
    @Column(name = "pickup_longitude", nullable = false, precision = 9, scale = 6) BigDecimal pickupLongitude;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected BranchEntity() { }
    BranchEntity(UUID id, UUID merchantId, String name, String line1, String city,
                 BigDecimal latitude, BigDecimal longitude, Instant now) {
        this.id = id; this.merchantId = merchantId; this.name = name; this.pickupLine1 = line1;
        this.pickupCity = city; this.pickupLatitude = latitude; this.pickupLongitude = longitude;
        this.active = true; this.createdAt = now; this.updatedAt = now;
    }
}

@Entity
@Table(name = "merchant_staff_memberships")
class MembershipEntity {
    @Id UUID id;
    @Column(name = "actor_id", nullable = false) UUID actorId;
    @Column(name = "branch_id", nullable = false) UUID branchId;
    @Column(nullable = false) boolean active;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    protected MembershipEntity() { }
    MembershipEntity(UUID id, UUID actorId, UUID branchId, Instant now) {
        this.id = id; this.actorId = actorId; this.branchId = branchId;
        this.active = true; this.createdAt = now;
    }
}

interface MerchantRepository extends JpaRepository<MerchantEntity, UUID> { }
interface BranchRepository extends JpaRepository<BranchEntity, UUID> { }
interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> { }

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchants")
class MerchantController {
    private final MerchantService service;
    MerchantController(MerchantService service) { this.service = service; }
    record Request(@NotBlank @Size(max = 120) String name) { }
    @PostMapping
    ResponseEntity<MerchantService.MerchantView> create(@Valid @RequestBody Request request) {
        var created = service.createMerchant(request.name());
        return ResponseEntity.created(java.net.URI.create("/api/v1/merchants/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    MerchantService.MerchantView get(@PathVariable UUID id) { return service.getMerchant(id); }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchant-branches")
class BranchController {
    private final MerchantService service;
    BranchController(MerchantService service) { this.service = service; }
    @PostMapping
    ResponseEntity<MerchantService.BranchView> create(@Valid @RequestBody MerchantService.BranchRequest request) {
        var created = service.createBranch(request);
        return ResponseEntity.created(java.net.URI.create("/api/v1/merchant-branches/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    MerchantService.BranchView get(@PathVariable UUID id) { return service.getBranch(id); }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchant-staff-memberships")
class MembershipController {
    private final MerchantService service;
    MembershipController(MerchantService service) { this.service = service; }
    record Request(@NotNull UUID actorId, @NotNull UUID branchId) { }
    @PostMapping
    ResponseEntity<MerchantService.MembershipView> create(@Valid @RequestBody Request request) {
        var created = service.addMembership(request.actorId(), request.branchId());
        return ResponseEntity.created(java.net.URI.create("/api/v1/merchant-staff-memberships/" + created.id()))
                .body(created);
    }
}
