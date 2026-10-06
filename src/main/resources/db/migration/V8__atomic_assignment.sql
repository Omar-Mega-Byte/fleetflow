ALTER TABLE drivers DROP CONSTRAINT ck_drivers_operational_status;
ALTER TABLE drivers ADD CONSTRAINT ck_drivers_operational_status
    CHECK (operational_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED', 'RESERVED', 'DELIVERING'));
ALTER TABLE driver_status_history DROP CONSTRAINT ck_driver_status_history_from;
ALTER TABLE driver_status_history DROP CONSTRAINT ck_driver_status_history_to;
ALTER TABLE driver_status_history ADD CONSTRAINT ck_driver_status_history_from
    CHECK (from_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED', 'RESERVED', 'DELIVERING'));
ALTER TABLE driver_status_history ADD CONSTRAINT ck_driver_status_history_to
    CHECK (to_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED', 'RESERVED', 'DELIVERING'));

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN
    ('CREATED', 'CONFIRMED', 'PREPARING', 'READY_FOR_PICKUP', 'DISPATCHING',
     'DISPATCH_FAILED', 'DRIVER_ASSIGNED', 'CANCELLED'));
ALTER TABLE order_status_history DROP CONSTRAINT ck_order_status_history_transition;
ALTER TABLE order_status_history ADD CONSTRAINT ck_order_status_history_transition CHECK (
    (from_status = 'CREATED' AND to_status = 'CONFIRMED') OR
    (from_status = 'CONFIRMED' AND to_status = 'PREPARING') OR
    (from_status = 'PREPARING' AND to_status = 'READY_FOR_PICKUP') OR
    (from_status = 'READY_FOR_PICKUP' AND to_status = 'DISPATCHING') OR
    (from_status = 'DISPATCHING' AND to_status IN ('DISPATCH_FAILED', 'DRIVER_ASSIGNED'))
);

ALTER TABLE dispatch_attempts ADD CONSTRAINT uk_dispatch_attempt_id_order UNIQUE (id, order_id);
ALTER TABLE dispatch_candidates ADD CONSTRAINT uk_dispatch_candidate_id_attempt_driver
    UNIQUE (id, attempt_id, driver_id);

CREATE TABLE driver_assignments (
    id uuid CONSTRAINT pk_driver_assignments PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    driver_id uuid NOT NULL REFERENCES drivers(id),
    attempt_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    offered_by_actor_id uuid NOT NULL REFERENCES actors(id),
    status varchar(16) NOT NULL,
    offered_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    responded_at timestamptz,
    responded_by_actor_id uuid REFERENCES actors(id),
    CONSTRAINT fk_assignment_attempt_order FOREIGN KEY (attempt_id, order_id)
        REFERENCES dispatch_attempts(id, order_id),
    CONSTRAINT fk_assignment_candidate FOREIGN KEY (candidate_id, attempt_id, driver_id)
        REFERENCES dispatch_candidates(id, attempt_id, driver_id),
    CONSTRAINT uk_assignment_id_order UNIQUE (id, order_id),
    CONSTRAINT ck_assignment_status CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_assignment_deadline CHECK (expires_at > offered_at),
    CONSTRAINT ck_assignment_response CHECK (
        (status = 'OFFERED' AND responded_at IS NULL AND responded_by_actor_id IS NULL) OR
        (status IN ('ACCEPTED', 'REJECTED') AND responded_at IS NOT NULL
            AND responded_by_actor_id IS NOT NULL AND responded_at >= offered_at))
);
CREATE UNIQUE INDEX uk_assignment_active_driver ON driver_assignments(driver_id)
    WHERE status IN ('OFFERED', 'ACCEPTED');
CREATE UNIQUE INDEX uk_assignment_active_order ON driver_assignments(order_id)
    WHERE status IN ('OFFERED', 'ACCEPTED');
CREATE INDEX idx_assignment_pending_deadline ON driver_assignments(status, expires_at)
    WHERE status = 'OFFERED';
CREATE INDEX idx_assignment_driver_offered ON driver_assignments(driver_id, offered_at DESC, id DESC);
CREATE INDEX idx_assignment_order_offered ON driver_assignments(order_id, offered_at DESC, id DESC);

ALTER TABLE deliveries ADD COLUMN sequence integer NOT NULL DEFAULT 1;
ALTER TABLE deliveries ADD COLUMN accepted_assignment_id uuid;
ALTER TABLE deliveries ADD CONSTRAINT uk_delivery_order_sequence UNIQUE (order_id, sequence);
ALTER TABLE deliveries ADD CONSTRAINT uk_delivery_accepted_assignment UNIQUE (accepted_assignment_id);
ALTER TABLE deliveries ADD CONSTRAINT fk_delivery_assignment_order
    FOREIGN KEY (accepted_assignment_id, order_id) REFERENCES driver_assignments(id, order_id);
ALTER TABLE deliveries ADD CONSTRAINT ck_delivery_sequence CHECK (sequence > 0);
ALTER TABLE deliveries ADD CONSTRAINT ck_delivery_assignment CHECK (
    (status = 'PENDING' AND accepted_assignment_id IS NULL) OR
    (status = 'ASSIGNED' AND accepted_assignment_id IS NOT NULL) OR
    status IN ('PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'FAILED'));
CREATE UNIQUE INDEX uk_delivery_active_order ON deliveries(order_id)
    WHERE status IN ('PENDING', 'ASSIGNED');
