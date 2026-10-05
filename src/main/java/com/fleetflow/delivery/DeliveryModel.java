package com.fleetflow.delivery;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Mapping only. Phase 3 has no delivery creation or transition service. */
@Entity
@Table(name = "deliveries")
class DeliveryEntity {
    @Id UUID id;
    @Column(name = "order_id", nullable = false) UUID orderId;
    @Column(nullable = false, length = 32) String status;
    @Column(nullable = false) long version;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    protected DeliveryEntity() { }
}
