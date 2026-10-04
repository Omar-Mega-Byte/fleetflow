package com.fleetflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantOrderReader {
    private final OrderRepository orders;

    MerchantOrderReader(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public PageView listForBranch(UUID branchId, int page, int size) {
        Page<OrderEntity> found = orders.findByBranchId(branchId, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        List<Summary> content = found.getContent().stream()
                .map(o -> new Summary(o.id, o.status, o.currency, o.totalAmount, o.createdAt)).toList();
        return new PageView(content, page, size, found.getTotalElements(), found.getTotalPages());
    }

    public record Summary(UUID id, String status, String currency, BigDecimal totalAmount, Instant createdAt) { }
    public record PageView(List<Summary> content, int page, int size, long totalElements, int totalPages) { }
}
