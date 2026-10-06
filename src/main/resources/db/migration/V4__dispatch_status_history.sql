-- Phase 3 owns the canonical order and driver status columns and lifecycle history.
-- These narrow dispatch histories record the operational transitions used by
-- location, matching and assignment without replacing that lifecycle history.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN
    ('CREATED', 'CONFIRMED', 'PREPARING', 'READY_FOR_PICKUP', 'CANCELLED',
     'DISPATCHING', 'DISPATCH_FAILED'));

CREATE TABLE driver_status_history (
    id uuid CONSTRAINT pk_driver_status_history PRIMARY KEY,
    driver_id uuid NOT NULL REFERENCES drivers(id),
    actor_id uuid NOT NULL REFERENCES actors(id),
    from_status varchar(16) NOT NULL,
    to_status varchar(16) NOT NULL,
    changed_at timestamptz NOT NULL,
    CONSTRAINT ck_driver_status_history_from CHECK (from_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED')),
    CONSTRAINT ck_driver_status_history_to CHECK (to_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED')),
    CONSTRAINT ck_driver_status_history_change CHECK (from_status <> to_status)
);
CREATE INDEX idx_driver_status_history_driver_changed ON driver_status_history (driver_id, changed_at DESC, id DESC);

CREATE TABLE order_status_history (
    id uuid CONSTRAINT pk_order_status_history PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    actor_id uuid NOT NULL REFERENCES actors(id),
    from_status varchar(32) NOT NULL,
    to_status varchar(32) NOT NULL,
    changed_at timestamptz NOT NULL,
    CONSTRAINT ck_order_status_history_transition CHECK (
        (from_status = 'CREATED' AND to_status = 'CONFIRMED') OR
        (from_status = 'CONFIRMED' AND to_status = 'PREPARING') OR
        (from_status = 'PREPARING' AND to_status = 'READY_FOR_PICKUP') OR
        (from_status = 'READY_FOR_PICKUP' AND to_status = 'DISPATCHING') OR
        (from_status = 'DISPATCHING' AND to_status = 'DISPATCH_FAILED'))
);
CREATE INDEX idx_order_status_history_order_changed
    ON order_status_history (order_id, changed_at DESC, id DESC);

CREATE FUNCTION reject_dispatch_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'History is append only';
END;
$$;
CREATE TRIGGER trg_order_status_history_immutable BEFORE UPDATE OR DELETE ON order_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_dispatch_history_change();
CREATE TRIGGER trg_driver_status_history_immutable BEFORE UPDATE OR DELETE ON driver_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_dispatch_history_change();
