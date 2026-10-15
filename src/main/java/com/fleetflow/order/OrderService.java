package com.fleetflow.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.security.MessageDigest;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.customer.CustomerService;
import com.fleetflow.merchant.MerchantService;
import com.fleetflow.identity.Role;
import com.fleetflow.lifecycle.LifecycleHistory;
import com.fleetflow.security.ActorAccess;
import com.fleetflow.zone.ZoneService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final CustomerService customers;
    private final MerchantService merchants;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ActorAccess access;
    private final LifecycleHistory history;
    private final ZoneService zones;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    OrderService(OrderRepository orders, CustomerService customers, MerchantService merchants,
                 EntityManager entityManager, Clock clock, ActorAccess access, LifecycleHistory history,
                 ZoneService zones, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.orders = orders;
        this.customers = customers;
        this.merchants = merchants;
        this.entityManager = entityManager;
        this.clock = clock;
        this.access = access;
        this.history = history;
        this.zones = zones;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderView create(CreateRequest request, String idempotencyKey) {
        UUID actorId = access.require(Role.CUSTOMER).id();
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw invalid("Idempotency-Key must contain 1 to 128 letters, digits, dots, underscores, tildes, or hyphens");
        }
        String requestHash = requestHash(request);
        int reserved = jdbc.update("""
                insert into command_idempotency
                  (actor_id, operation, idempotency_key, request_hash, created_at)
                values (?, 'ORDER_CREATE', ?, ?, clock_timestamp())
                on conflict (actor_id, operation, idempotency_key) do nothing
                """, actorId, idempotencyKey, requestHash);
        if (reserved == 0) {
            var existing = jdbc.query("""
                    select request_hash, order_id, response_body::text
                    from command_idempotency
                    where actor_id = ? and operation = 'ORDER_CREATE' and idempotency_key = ?
                    for update
                    """, (rs, row) -> new IdempotencyResult(rs.getString(1),
                    (UUID) rs.getObject(2), rs.getString(3)), actorId, idempotencyKey);
            if (existing.isEmpty()) throw new IllegalStateException("Idempotency reservation disappeared");
            IdempotencyResult prior = existing.getFirst();
            if (!prior.requestHash().equals(requestHash)) {
                throw new ApiFailure(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            if (prior.resourceId() == null || prior.responseBody() == null) {
                throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "IDEMPOTENCY_RESULT_UNAVAILABLE",
                        "The original command result is not available");
            }
            return readOrderResult(prior.responseBody());
        }

        CustomerService.CustomerView customer = customers.forActor(actorId);
        MerchantService.BranchView branch = merchants.branchForOrder(request.branchId());
        Currency.getInstance(request.currency());
        ZoneService.Decision coverage = zones.forNewOrder(branch.id(), request.delivery().latitude(),
                request.delivery().longitude());
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        java.util.ArrayList<OrderItemEntity> lines = new java.util.ArrayList<>();
        int position = 1;
        for (ItemInput input : request.items()) {
            BigDecimal unit = amount(input.unitAmount());
            BigDecimal line = amount(unit.multiply(BigDecimal.valueOf(input.quantity())));
            total = amount(total.add(line));
            lines.add(new OrderItemEntity(UUID.randomUUID(), position++, input.description().strip(),
                    input.quantity(), unit, line));
        }
        Instant now = clock.instant();
        UUID orderId = UUID.randomUUID();
        OrderEntity order = new OrderEntity(orderId, customer.id(), branch.id(),
                customer.displayName(), branch.merchantName(), branch.name(), branch.pickupLine1(),
                branch.pickupCity(), branch.pickupLatitude(), branch.pickupLongitude(),
                request.delivery().line1().strip(), request.delivery().city().strip(),
                request.delivery().latitude(), request.delivery().longitude(), request.currency(), total, now,
                coverage.mode(), coverage.policyVersion(), coverage.zoneId());
        lines.forEach(order::addItem);
        entityManager.persist(order);
        entityManager.flush();
        history.order(order.id, 0, null, "CREATED", "CREATE", now,
                actorId, null);
        OrderView created = detail(order);
        int stored = jdbc.update("""
                update command_idempotency set order_id = ?, response_body = cast(? as jsonb)
                where actor_id = ? and operation = 'ORDER_CREATE' and idempotency_key = ?
                  and order_id is null and zone_id is null and response_body is null
                """, order.id, writeOrderResult(created), actorId, idempotencyKey);
        if (stored != 1) throw new IllegalStateException("Could not store idempotent order result");
        return created;
    }

    private String requestHash(CreateRequest request) {
        CanonicalOrder canonical = new CanonicalOrder(request.branchId(),
                new CanonicalAddress(request.delivery().line1().strip(), request.delivery().city().strip(),
                        decimal(request.delivery().latitude()), decimal(request.delivery().longitude())),
                request.currency(), request.items().stream().map(item -> new CanonicalItem(
                        item.description().strip(), item.quantity(), decimal(item.unitAmount()))).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not fingerprint order request", failure);
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String writeOrderResult(OrderView result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not store order result", failure);
        }
    }

    private OrderView readOrderResult(String json) {
        try {
            return objectMapper.readValue(json, OrderView.class);
        } catch (Exception failure) {
            throw new ApiFailure(HttpStatus.SERVICE_UNAVAILABLE, "IDEMPOTENCY_RESULT_UNAVAILABLE",
                    "The original command result is not available");
        }
    }

    private record IdempotencyResult(String requestHash, UUID resourceId, String responseBody) { }
    private record CanonicalAddress(String line1, String city, String latitude, String longitude) { }
    private record CanonicalItem(String description, int quantity, String unitAmount) { }
    private record CanonicalOrder(UUID branchId, CanonicalAddress delivery, String currency,
                                  List<CanonicalItem> items) { }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        UUID customerId = customers.forActor(access.require(Role.CUSTOMER).id()).id();
        OrderEntity order = orders.findDetail(id, customerId, activeDemoGeneration()).orElseThrow(() -> notFound());
        return detail(order);
    }

    @Transactional(readOnly = true)
    public PageView list(UUID branchId, String status, Instant from, Instant to,
                         int page, int size, String sort) {
        UUID customerId = customers.forActor(access.require(Role.CUSTOMER).id()).id();
        long activeGeneration = activeDemoGeneration();
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Page must be nonnegative and size must be between 1 and 100");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("from must be before to");
        }
        if (status != null && !java.util.Set.of("CREATED", "CONFIRMED", "PREPARING",
                "READY_FOR_PICKUP", "CANCELLED", "DISPATCHING", "DISPATCH_FAILED",
                "DRIVER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "DELIVERED", "DELIVERY_FAILED").contains(status)) {
            throw invalid("Unsupported status");
        }
        Sort requestedSort = sortOrder(sort);
        Specification<OrderEntity> scope = (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
        scope = scope.and((root, query, cb) -> cb.or(cb.isNull(root.get("demoGeneration")),
                cb.equal(root.get("demoGeneration"), activeGeneration)));
        if (branchId != null) {
            scope = scope.and((root, query, cb) -> cb.equal(root.get("branchId"), branchId));
        }
        if (status != null) {
            scope = scope.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            scope = scope.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            scope = scope.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        Page<OrderEntity> found = orders.findAll(scope, PageRequest.of(page, size, requestedSort));
        return new PageView(found.getContent().stream().map(this::summary).toList(), page, size,
                found.getTotalElements(), found.getTotalPages());
    }

    private long activeDemoGeneration() {
        Long generation = jdbc.queryForObject("select generation from synthetic_demo_state where id = true", Long.class);
        return generation == null ? 0L : generation;
    }

    private Sort sortOrder(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 2 || !(parts[0].equals("createdAt") || parts[0].equals("id"))
                || !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) {
            throw invalid("Unsupported sort");
        }
        Sort.Direction direction = Sort.Direction.fromString(parts[1]);
        Sort requested = Sort.by(direction, parts[0]);
        return parts[0].equals("id") ? requested : requested.and(Sort.by(direction, "id"));
    }

    private BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw invalid("Amount must be nonnegative");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("Amount has more than two decimal places");
        }
        if (scaled.precision() > 18) {
            throw invalid("Amount exceeds numeric(18,2)");
        }
        return scaled;
    }

    private OrderView detail(OrderEntity order) {
        List<ItemView> items = order.items.stream().map(item -> new ItemView(item.lineNumber,
                item.description, item.quantity, item.unitAmount, item.lineAmount)).toList();
        return new OrderView(order.id, order.customerId, order.branchId, order.status, order.version,
                order.customerName, order.merchantName, order.branchName,
                new PublicAddress(order.pickupLine1, order.pickupCity),
                new PublicAddress(order.deliveryLine1, order.deliveryCity),
                order.currency, order.totalAmount, order.createdAt, items);
    }

    private Summary summary(OrderEntity order) {
        return new Summary(order.id, order.branchId, order.status, order.branchName, order.currency,
                order.totalAmount, order.createdAt);
    }

    private ApiFailure notFound() {
        return new ApiFailure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found");
    }

    private ApiFailure invalid(String message) {
        return new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @Schema(description = "Address snapshot stored with an order; coordinates use WGS84 degrees.")
    public record Address(@Schema(example = "12 Market Street") @NotBlank @Size(max = 200) String line1,
                          @Schema(example = "Cairo") @NotBlank @Size(max = 120) String city,
                          @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
                          @Schema(example = "30.044420") BigDecimal latitude,
                          @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
                          @Schema(example = "31.235712") BigDecimal longitude) { }
    @Schema(description = "Item and submitted unit amount for a local test order. The amount is not trusted pricing.")
    public record ItemInput(@Schema(example = "Small parcel") @NotBlank @Size(max = 200) String description,
                            @Schema(example = "2") @Min(1) int quantity,
                            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2)
                            @Schema(example = "12.50") BigDecimal unitAmount) { }
    @Schema(description = "Local/test order input. Customer identity is never accepted in this body.")
    public record CreateRequest(@Schema(description = "Target merchant branch ID.") @NotNull UUID branchId,
                                @NotNull @Valid Address delivery,
                                @Schema(example = "USD") @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                @NotEmpty @Size(max = 100) List<@Valid ItemInput> items) { }
    public record ItemView(int lineNumber, String description, int quantity,
                           BigDecimal unitAmount, BigDecimal lineAmount) { }
    public record PublicAddress(String line1, String city) { }
    @Schema(description = "Owned order with saved names, addresses, prices, and items.")
    public record OrderView(UUID id, UUID customerId, UUID branchId, String status, long version,
                            String customerName, String merchantName, String branchName,
                            PublicAddress pickup, PublicAddress delivery, String currency, BigDecimal totalAmount,
                            Instant createdAt, List<ItemView> items) { }
    @Schema(name = "CustomerOrderSummary", description = "Customer-owned order summary without item details.")
    public record Summary(UUID id, UUID branchId, String status, String branchName,
                          String currency, BigDecimal totalAmount, Instant createdAt) { }
    @Schema(name = "CustomerOrderPage", description = "Zero-based page of customer-owned order summaries.")
    public record PageView(List<Summary> content, int page, int size, long totalElements, int totalPages) { }
}
