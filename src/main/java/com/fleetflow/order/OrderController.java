package com.fleetflow.order;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fleetflow.api.ApiFailure;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/v1/orders")
class OrderController {
    private static final Set<String> FILTERS = Set.of("branchId", "status", "from", "to", "page", "size", "sort");
    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<OrderService.OrderView> create(@RequestHeader("X-Dev-Actor-Id") UUID actorId,
                                                   @Valid @RequestBody OrderService.CreateRequest request) {
        var created = service.create(actorId, request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    OrderService.OrderView get(@RequestHeader("X-Dev-Actor-Id") UUID actorId, @PathVariable UUID id) {
        return service.get(actorId, id);
    }

    @GetMapping
    OrderService.PageView list(@RequestHeader("X-Dev-Actor-Id") UUID actorId,
                               @RequestParam Map<String, String> params) {
        if (!FILTERS.containsAll(params.keySet())) {
            throw new ApiFailure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Unsupported filter parameter");
        }
        return service.list(actorId, uuid(params.get("branchId")), params.get("status"),
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
