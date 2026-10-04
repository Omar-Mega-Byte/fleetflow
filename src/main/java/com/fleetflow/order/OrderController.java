package com.fleetflow.order;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test", "default", "prod"})
@RequestMapping("/api/v1/orders")
@Tag(name = "Customer orders", description = "CUSTOMER role; ownership is derived from the authenticated actor.")
class OrderController {
    private static final Set<String> FILTERS = Set.of("branchId", "status", "from", "to", "page", "size", "sort");
    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get my order", description = "Returns only an order owned by the authenticated customer. "
            + "A foreign order and an unknown order both return 404.")
    @ApiResponse(responseCode = "200", description = "Owned order with its item and address snapshots.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderService.OrderView.class)))
    OrderService.OrderView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    @Operation(summary = "List my orders", description = "Customer scope is enforced in SQL. Filters are bounded, "
            + "page is zero-based, and sort uses an ID tie-breaker.", parameters = {
            @Parameter(name = "branchId", in = ParameterIn.QUERY,
                    description = "Optional branch filter within my orders.", schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = "status", in = ParameterIn.QUERY,
                    description = "Only the current CREATED state is supported.", schema = @Schema(allowableValues = "CREATED")),
            @Parameter(name = "from", in = ParameterIn.QUERY,
                    description = "Inclusive UTC creation time.", schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "to", in = ParameterIn.QUERY,
                    description = "Exclusive UTC creation time.", schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "page", in = ParameterIn.QUERY,
                    description = "Zero-based page index.", schema = @Schema(type = "integer", defaultValue = "0", minimum = "0")),
            @Parameter(name = "size", in = ParameterIn.QUERY,
                    description = "Page size from 1 to 100.", schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100")),
            @Parameter(name = "sort", in = ParameterIn.QUERY,
                    description = "Allowed: createdAt,asc/desc or id,asc/desc.",
                    schema = @Schema(type = "string", defaultValue = "createdAt,desc",
                            allowableValues = {"createdAt,asc", "createdAt,desc", "id,asc", "id,desc"}))
    })
    @ApiResponse(responseCode = "200", description = "Page of my order summaries.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderService.PageView.class)))
    OrderService.PageView list(@Parameter(hidden = true) @RequestParam Map<String, String> params) {
        if (!FILTERS.containsAll(params.keySet())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Unsupported filter parameter");
        }
        return service.list(uuid(params.get("branchId")), params.get("status"),
                instant(params.get("from")), instant(params.get("to")),
                number(params.getOrDefault("page", "0")), number(params.getOrDefault("size", "20")),
                params.getOrDefault("sort", "createdAt,desc"));
    }

    private UUID uuid(String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }

    private int number(String text) {
        return Integer.parseInt(text);
    }
}

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/orders")
@Tag(name = "Customer orders", description = "CUSTOMER role; ownership is derived from the authenticated actor.")
class LocalOrderCreateController {
    private final OrderService service;

    LocalOrderCreateController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a local test order", description = "Local/test on loopback only, with CUSTOMER role. "
            + "The customer comes from the bearer token's local actor. Submitted item prices are untrusted; "
            + "this route is unavailable in default/prod.")
    @ApiResponse(responseCode = "201", description = "Created order and immutable snapshots.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = OrderService.OrderView.class)))
    ResponseEntity<OrderService.OrderView> create(@Valid @RequestBody OrderService.CreateRequest request) {
        var created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + created.id())).body(created);
    }
}
