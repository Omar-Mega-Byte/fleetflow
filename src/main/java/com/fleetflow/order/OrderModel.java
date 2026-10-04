package com.fleetflow.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "orders")
class OrderEntity {
    @Id UUID id;
    @Column(name = "customer_id", nullable = false) UUID customerId;
    @Column(name = "merchant_branch_id", nullable = false) UUID branchId;
    @Column(nullable = false, length = 32) String status;
    @Column(name = "customer_name_snapshot", nullable = false, length = 120) String customerName;
    @Column(name = "merchant_name_snapshot", nullable = false, length = 120) String merchantName;
    @Column(name = "branch_name_snapshot", nullable = false, length = 120) String branchName;
    @Column(name = "pickup_line1", nullable = false, length = 200) String pickupLine1;
    @Column(name = "pickup_city", nullable = false, length = 120) String pickupCity;
    @Column(name = "pickup_latitude", nullable = false, precision = 9, scale = 6) BigDecimal pickupLatitude;
    @Column(name = "pickup_longitude", nullable = false, precision = 9, scale = 6) BigDecimal pickupLongitude;
    @Column(name = "delivery_line1", nullable = false, length = 200) String deliveryLine1;
    @Column(name = "delivery_city", nullable = false, length = 120) String deliveryCity;
    @Column(name = "delivery_latitude", nullable = false, precision = 9, scale = 6) BigDecimal deliveryLatitude;
    @Column(name = "delivery_longitude", nullable = false, precision = 9, scale = 6) BigDecimal deliveryLongitude;
    @Column(nullable = false, length = 3) String currency;
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2) BigDecimal totalAmount;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("lineNumber ASC")
    List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() { }

    OrderEntity(UUID id, UUID customerId, UUID branchId, String customerName,
                String merchantName, String branchName, String pickupLine1, String pickupCity,
                BigDecimal pickupLatitude, BigDecimal pickupLongitude, String deliveryLine1,
                String deliveryCity, BigDecimal deliveryLatitude, BigDecimal deliveryLongitude,
                String currency, BigDecimal totalAmount, Instant now) {
        this.id = id; this.customerId = customerId; this.branchId = branchId; this.status = "CREATED";
        this.customerName = customerName; this.merchantName = merchantName; this.branchName = branchName;
        this.pickupLine1 = pickupLine1; this.pickupCity = pickupCity;
        this.pickupLatitude = pickupLatitude; this.pickupLongitude = pickupLongitude;
        this.deliveryLine1 = deliveryLine1; this.deliveryCity = deliveryCity;
        this.deliveryLatitude = deliveryLatitude; this.deliveryLongitude = deliveryLongitude;
        this.currency = currency; this.totalAmount = totalAmount;
        this.createdAt = now; this.updatedAt = now;
    }

    void addItem(OrderItemEntity item) {
        items.add(item);
        item.order = this;
    }
}

@Entity
@Table(name = "order_items")
class OrderItemEntity {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    OrderEntity order;
    @Column(name = "line_number", nullable = false) int lineNumber;
    @Column(name = "description_snapshot", nullable = false, length = 200) String description;
    @Column(nullable = false) int quantity;
    @Column(name = "unit_amount", nullable = false, precision = 18, scale = 2) BigDecimal unitAmount;
    @Column(name = "line_amount", nullable = false, precision = 18, scale = 2) BigDecimal lineAmount;

    protected OrderItemEntity() { }

    OrderItemEntity(UUID id, int lineNumber, String description, int quantity,
                    BigDecimal unitAmount, BigDecimal lineAmount) {
        this.id = id; this.lineNumber = lineNumber; this.description = description;
        this.quantity = quantity; this.unitAmount = unitAmount; this.lineAmount = lineAmount;
    }
}

interface OrderRepository extends JpaRepository<OrderEntity, UUID>, JpaSpecificationExecutor<OrderEntity> {
    org.springframework.data.domain.Page<OrderEntity> findByBranchId(
            UUID branchId, org.springframework.data.domain.Pageable pageable);
    @EntityGraph(attributePaths = "items")
    @Query("select o from OrderEntity o where o.id = :id and o.customerId = :customerId")
    java.util.Optional<OrderEntity> findDetail(@Param("id") UUID id, @Param("customerId") UUID customerId);
}
