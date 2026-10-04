package com.fleetflow.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import com.fleetflow.customer.CustomerService;
import com.fleetflow.merchant.MerchantService;
import com.fleetflow.identity.Role;
import com.fleetflow.security.ActorAccess;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final OrderRepository orders;
    private final CustomerService customers;
    private final MerchantService merchants;
    private final EntityManager entityManager;
    private final Clock clock;
    private final ActorAccess access;

    OrderService(OrderRepository orders, CustomerService customers, MerchantService merchants,
                 EntityManager entityManager, Clock clock, ActorAccess access) {
        this.orders = orders;
        this.customers = customers;
        this.merchants = merchants;
        this.entityManager = entityManager;
        this.clock = clock;
        this.access = access;
    }

    @Transactional
    public OrderView create(CreateRequest request) {
        CustomerService.CustomerView customer = customers.forActor(access.require(Role.CUSTOMER).id());
        MerchantService.BranchView branch = merchants.branchForOrder(request.branchId());
        Currency.getInstance(request.currency());
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
        OrderEntity order = new OrderEntity(UUID.randomUUID(), customer.id(), branch.id(),
                customer.displayName(), branch.merchantName(), branch.name(), branch.pickupLine1(),
                branch.pickupCity(), branch.pickupLatitude(), branch.pickupLongitude(),
                request.delivery().line1().strip(), request.delivery().city().strip(),
                request.delivery().latitude(), request.delivery().longitude(), request.currency(), total, now);
        lines.forEach(order::addItem);
        entityManager.persist(order);
        entityManager.flush();
        return detail(order);
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        UUID customerId = customers.forActor(access.require(Role.CUSTOMER).id()).id();
        OrderEntity order = orders.findDetail(id, customerId).orElseThrow(() -> notFound());
        return detail(order);
    }

    @Transactional(readOnly = true)
    public PageView list(UUID branchId, String status, Instant from, Instant to,
                         int page, int size, String sort) {
        UUID customerId = customers.forActor(access.require(Role.CUSTOMER).id()).id();
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Page must be nonnegative and size must be between 1 and 100");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw invalid("from must be before to");
        }
        if (status != null && !status.equals("CREATED")) {
            throw invalid("Unsupported status");
        }
        Sort requestedSort = sortOrder(sort);
        Specification<OrderEntity> scope = (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
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
        return new OrderView(order.id, order.customerId, order.branchId, order.status,
                order.customerName, order.merchantName, order.branchName,
                new Address(order.pickupLine1, order.pickupCity, order.pickupLatitude, order.pickupLongitude),
                new Address(order.deliveryLine1, order.deliveryCity, order.deliveryLatitude,
                        order.deliveryLongitude), order.currency, order.totalAmount, order.createdAt, items);
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

    public record Address(@NotBlank @Size(max = 200) String line1,
                          @NotBlank @Size(max = 120) String city,
                          @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6)
                          BigDecimal latitude,
                          @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6)
                          BigDecimal longitude) { }
    public record ItemInput(@NotBlank @Size(max = 200) String description, @Min(1) int quantity,
                            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2)
                            BigDecimal unitAmount) { }
    public record CreateRequest(@NotNull UUID branchId, @NotNull @Valid Address delivery,
                                @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                @NotEmpty List<@Valid ItemInput> items) { }
    public record ItemView(int lineNumber, String description, int quantity,
                           BigDecimal unitAmount, BigDecimal lineAmount) { }
    public record OrderView(UUID id, UUID customerId, UUID branchId, String status,
                            String customerName, String merchantName, String branchName,
                            Address pickup, Address delivery, String currency, BigDecimal totalAmount,
                            Instant createdAt, List<ItemView> items) { }
    public record Summary(UUID id, UUID branchId, String status, String branchName,
                          String currency, BigDecimal totalAmount, Instant createdAt) { }
    public record PageView(List<Summary> content, int page, int size, long totalElements, int totalPages) { }
}
