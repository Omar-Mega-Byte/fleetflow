package com.fleetflow.merchant;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.identity.IdentityService;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
import com.fleetflow.order.MerchantOrderReader;
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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
    private final ActorAccess access;
    private final MerchantOrderReader orderReader;

    MerchantService(MerchantRepository merchants, BranchRepository branches,
                    MembershipRepository memberships, IdentityService identity, Clock clock,
                    ActorAccess access, MerchantOrderReader orderReader) {
        this.merchants = merchants;
        this.branches = branches;
        this.memberships = memberships;
        this.identity = identity;
        this.clock = clock;
        this.access = access;
        this.orderReader = orderReader;
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

    @Transactional(readOnly = true)
    public MerchantOrderReader.PageView branchOrders(UUID branchId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid page or size");
        }
        requireActiveBranchScope(branchId);
        return orderReader.listForBranch(branchId, page, size);
    }

    @Transactional(readOnly = true)
    public UUID requireActiveBranchScope(UUID branchId) {
        UUID actorId = access.require(Role.MERCHANT).id();
        if (!memberships.hasActiveScope(actorId, branchId)) {
            throw new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
        }
        return actorId;
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
    @Schema(description = "Local branch fixture with a fixed pickup address and WGS84 coordinates.")
    public record BranchRequest(@NotNull UUID merchantId,
                                @Schema(example = "Downtown branch") @NotBlank @Size(max = 120) String name,
                                @Schema(example = "12 Market Street") @NotBlank @Size(max = 200) String pickupLine1,
                                @Schema(example = "Cairo") @NotBlank @Size(max = 120) String pickupCity,
                                @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
                                @Schema(example = "30.044420") BigDecimal pickupLatitude,
                                @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
                                @Schema(example = "31.235712") BigDecimal pickupLongitude) { }
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
interface MembershipRepository extends JpaRepository<MembershipEntity, UUID> {
    @Query("select count(ms) > 0 from MembershipEntity ms, BranchEntity b, MerchantEntity m "
            + "where ms.actorId = :actorId and ms.branchId = :branchId and ms.active = true "
            + "and b.id = ms.branchId and b.active = true and m.id = b.merchantId and m.active = true")
    boolean hasActiveScope(@Param("actorId") UUID actorId, @Param("branchId") UUID branchId);
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchants")
@Tag(name = "Merchant fixtures", description = "Local/test loopback ADMIN routes for merchants.")
class MerchantController {
    private final MerchantService service;
    MerchantController(MerchantService service) { this.service = service; }
    @Schema(name = "CreateMerchantRequest")
    record Request(@Schema(example = "Example Merchant") @NotBlank @Size(max = 120) String name) { }
    @PostMapping
    @Operation(summary = "Create merchant fixture", description = "Local/test ADMIN only. Creates an active merchant.")
    @ApiResponse(responseCode = "201", description = "Merchant created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantService.MerchantView.class)))
    ResponseEntity<MerchantService.MerchantView> create(@Valid @RequestBody Request request) {
        var created = service.createMerchant(request.name());
        return ResponseEntity.created(java.net.URI.create("/api/v1/merchants/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    @Operation(summary = "Read merchant fixture", description = "Local/test ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Merchant profile.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantService.MerchantView.class)))
    MerchantService.MerchantView get(@PathVariable UUID id) { return service.getMerchant(id); }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchant-branches")
@Tag(name = "Branch fixtures", description = "Local/test loopback ADMIN routes for merchant branches.")
class BranchController {
    private final MerchantService service;
    BranchController(MerchantService service) { this.service = service; }
    @PostMapping
    @Operation(summary = "Create branch fixture", description = "Local/test ADMIN only. Requires an existing "
            + "merchant; the pickup address becomes an order snapshot on creation.")
    @ApiResponse(responseCode = "201", description = "Branch created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantService.BranchView.class)))
    ResponseEntity<MerchantService.BranchView> create(@Valid @RequestBody MerchantService.BranchRequest request) {
        var created = service.createBranch(request);
        return ResponseEntity.created(java.net.URI.create("/api/v1/merchant-branches/" + created.id())).body(created);
    }
    @GetMapping("/{id}")
    @Operation(summary = "Read branch fixture", description = "Local/test ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Branch and merchant status.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantService.BranchView.class)))
    MerchantService.BranchView get(@PathVariable UUID id) { return service.getBranch(id); }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/merchant-staff-memberships")
@Tag(name = "Staff membership fixtures", description = "Local/test loopback ADMIN routes for branch staff membership.")
class MembershipController {
    private final MerchantService service;
    MembershipController(MerchantService service) { this.service = service; }
    @Schema(name = "CreateStaffMembershipRequest", description = "Links an existing active actor to an exact branch.")
    record Request(@NotNull UUID actorId, @NotNull UUID branchId) { }
    @PostMapping
    @Operation(summary = "Create branch staff membership", description = "Local/test ADMIN only. A MERCHANT role "
            + "is provisioned separately; both that role and current active exact-branch membership are needed to read orders.")
    @ApiResponse(responseCode = "201", description = "Active membership created.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantService.MembershipView.class)))
    ResponseEntity<MerchantService.MembershipView> create(@Valid @RequestBody Request request) {
        var created = service.addMembership(request.actorId(), request.branchId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/merchant-branches/{branchId}/orders")
@Tag(name = "Merchant branch orders", description = "MERCHANT role and current active membership for the exact branch.")
class MerchantBranchOrdersController {
    private final MerchantService service;
    MerchantBranchOrdersController(MerchantService service) { this.service = service; }
    @GetMapping
    @Operation(summary = "List orders for my branch", description = "Returns narrow order summaries only when "
            + "the actor has active membership for this exact active branch. Other and absent branches both return 404.")
    @ApiResponse(responseCode = "200", description = "Branch-scoped page of order summaries.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = MerchantOrderReader.PageView.class)))
    MerchantOrderReader.PageView list(@Parameter(description = "Exact branch ID to which the actor belongs.")
                                      @PathVariable UUID branchId,
                                      @Parameter(description = "Zero-based page index, minimum 0.")
                                      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                      @Parameter(description = "Page size from 1 to 100.")
                                      @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return service.branchOrders(branchId, page, size);
    }
}
