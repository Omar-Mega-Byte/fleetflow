ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN
    ('CREATED', 'CONFIRMED', 'PREPARING', 'READY_FOR_PICKUP', 'DISPATCHING',
     'DISPATCH_FAILED', 'DRIVER_ASSIGNED', 'PICKED_UP', 'IN_TRANSIT',
     'DELIVERED', 'DELIVERY_FAILED', 'CANCELLED'));
ALTER TABLE order_status_history DROP CONSTRAINT ck_order_status_history_transition;
ALTER TABLE order_status_history ADD CONSTRAINT ck_order_status_history_transition CHECK (
    (from_status = 'CREATED' AND to_status = 'CONFIRMED') OR
    (from_status = 'CONFIRMED' AND to_status = 'PREPARING') OR
    (from_status = 'PREPARING' AND to_status = 'READY_FOR_PICKUP') OR
    (from_status = 'READY_FOR_PICKUP' AND to_status = 'DISPATCHING') OR
    (from_status = 'DISPATCHING' AND to_status IN ('DISPATCH_FAILED', 'DRIVER_ASSIGNED', 'CANCELLED')) OR
    (from_status = 'DISPATCH_FAILED' AND to_status IN ('DISPATCHING', 'CANCELLED')) OR
    (from_status = 'DRIVER_ASSIGNED' AND to_status IN ('DISPATCHING', 'PICKED_UP', 'CANCELLED')) OR
    (from_status = 'PICKED_UP' AND to_status IN ('IN_TRANSIT', 'DELIVERY_FAILED')) OR
    (from_status = 'IN_TRANSIT' AND to_status IN ('DELIVERED', 'DELIVERY_FAILED'))
);
ALTER TABLE order_status_history ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE order_status_history ADD COLUMN source varchar(10) NOT NULL DEFAULT 'ACTOR';
ALTER TABLE order_status_history ADD CONSTRAINT ck_order_status_history_source CHECK (
    (source = 'ACTOR' AND actor_id IS NOT NULL) OR
    (source = 'SYSTEM' AND actor_id IS NULL)
);

ALTER TABLE driver_assignments ALTER COLUMN offered_by_actor_id DROP NOT NULL;
ALTER TABLE driver_assignments ADD COLUMN offered_source varchar(10) NOT NULL DEFAULT 'ACTOR';
ALTER TABLE driver_assignments ADD COLUMN closed_at timestamptz;
ALTER TABLE driver_assignments ADD COLUMN closed_by_actor_id uuid REFERENCES actors(id);
ALTER TABLE driver_assignments ADD COLUMN close_reason varchar(500);
ALTER TABLE driver_assignments ADD CONSTRAINT uk_assignment_candidate_once UNIQUE (candidate_id);
ALTER TABLE driver_assignments ADD CONSTRAINT ck_assignment_offered_source CHECK (
    (offered_source = 'ACTOR' AND offered_by_actor_id IS NOT NULL) OR
    (offered_source = 'SYSTEM' AND offered_by_actor_id IS NULL)
);
ALTER TABLE driver_assignments DROP CONSTRAINT ck_assignment_status;
ALTER TABLE driver_assignments ADD CONSTRAINT ck_assignment_status CHECK
    (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'TIMED_OUT', 'CANCELLED', 'REASSIGNED', 'FAILED', 'COMPLETED'));
ALTER TABLE driver_assignments DROP CONSTRAINT ck_assignment_response;
ALTER TABLE driver_assignments ADD CONSTRAINT ck_assignment_response CHECK (
    (status = 'OFFERED' AND responded_at IS NULL AND responded_by_actor_id IS NULL
        AND closed_at IS NULL AND close_reason IS NULL) OR
    (status IN ('ACCEPTED', 'REJECTED') AND responded_at IS NOT NULL
        AND responded_by_actor_id IS NOT NULL AND responded_at >= offered_at
        AND closed_at IS NULL AND close_reason IS NULL) OR
    (status = 'TIMED_OUT' AND responded_at IS NOT NULL AND responded_by_actor_id IS NULL
        AND responded_at >= offered_at AND closed_at IS NULL AND close_reason IS NULL) OR
    (status = 'CANCELLED' AND responded_at IS NOT NULL AND responded_at >= offered_at
        AND close_reason IS NOT NULL AND length(btrim(close_reason)) BETWEEN 1 AND 500
        AND ((closed_at IS NULL AND responded_by_actor_id IS NOT NULL)
          OR (closed_at IS NOT NULL AND responded_by_actor_id IS NOT NULL))) OR
    (status IN ('REASSIGNED', 'FAILED', 'COMPLETED') AND responded_at IS NOT NULL
        AND responded_by_actor_id IS NOT NULL AND closed_at IS NOT NULL
        AND closed_at >= responded_at AND close_reason IS NOT NULL
        AND length(btrim(close_reason)) BETWEEN 1 AND 500)
);

ALTER TABLE deliveries ADD COLUMN failure_reason varchar(500);
ALTER TABLE deliveries DROP CONSTRAINT ck_delivery_assignment;
ALTER TABLE deliveries ADD CONSTRAINT ck_delivery_assignment CHECK (
    (status = 'PENDING' AND accepted_assignment_id IS NULL AND failure_reason IS NULL) OR
    (status IN ('ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED')
        AND accepted_assignment_id IS NOT NULL AND failure_reason IS NULL) OR
    (status = 'CANCELLED' AND failure_reason IS NULL) OR
    (status = 'FAILED' AND accepted_assignment_id IS NOT NULL
        AND failure_reason IS NOT NULL AND length(btrim(failure_reason)) BETWEEN 1 AND 500)
);
DROP INDEX uk_delivery_active_order;
CREATE UNIQUE INDEX uk_delivery_active_order ON deliveries(order_id)
    WHERE status IN ('PENDING', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT');

CREATE TABLE dispatch_recovery (
    order_id uuid PRIMARY KEY REFERENCES orders(id),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    next_action_at timestamptz NOT NULL,
    lease_token uuid,
    lease_until timestamptz,
    last_reason varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_recovery_status CHECK (status IN ('PENDING', 'EXHAUSTED', 'RESOLVED')),
    CONSTRAINT ck_recovery_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CONSTRAINT ck_recovery_reason CHECK (length(btrim(last_reason)) > 0)
);
CREATE INDEX idx_recovery_due ON dispatch_recovery(status, next_action_at)
    WHERE status = 'PENDING';

CREATE TABLE dispatch_candidate_recovery (
    candidate_id uuid PRIMARY KEY REFERENCES dispatch_candidates(id),
    order_id uuid NOT NULL REFERENCES orders(id),
    reason varchar(64) NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT ck_candidate_recovery_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX idx_candidate_recovery_order ON dispatch_candidate_recovery(order_id, recorded_at);
CREATE TRIGGER trg_candidate_recovery_immutable BEFORE UPDATE OR DELETE ON dispatch_candidate_recovery
    FOR EACH ROW EXECUTE FUNCTION reject_dispatch_history_change();

CREATE TABLE dispatch_incidents (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    delivery_id uuid REFERENCES deliveries(id),
    assignment_id uuid REFERENCES driver_assignments(id),
    code varchar(40) NOT NULL,
    reason varchar(500) NOT NULL,
    status varchar(10) NOT NULL DEFAULT 'OPEN',
    opened_at timestamptz NOT NULL,
    opened_by_actor_id uuid REFERENCES actors(id),
    resolved_at timestamptz,
    resolved_by_actor_id uuid REFERENCES actors(id),
    resolution varchar(500),
    CONSTRAINT ck_incident_code CHECK (code IN ('NO_DRIVER', 'PRE_PICKUP_REASSIGNMENT', 'DELIVERY_FAILED')),
    CONSTRAINT ck_incident_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_incident_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_incident_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by_actor_id IS NULL AND resolution IS NULL) OR
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolved_by_actor_id IS NOT NULL
            AND resolution IS NOT NULL AND length(btrim(resolution)) BETWEEN 1 AND 500))
);
CREATE INDEX idx_incident_order_opened ON dispatch_incidents(order_id, opened_at DESC, id DESC);
CREATE UNIQUE INDEX uk_incident_open_no_driver ON dispatch_incidents(order_id, code)
    WHERE status = 'OPEN' AND code = 'NO_DRIVER';
