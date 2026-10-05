ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK
    (status IN ('CREATED', 'CONFIRMED', 'PREPARING', 'READY_FOR_PICKUP', 'CANCELLED'));
ALTER TABLE orders ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN confirmed_at timestamptz;
ALTER TABLE orders ADD COLUMN preparing_at timestamptz;
ALTER TABLE orders ADD COLUMN ready_at timestamptz;
ALTER TABLE orders ADD COLUMN cancelled_at timestamptz;
ALTER TABLE orders ADD CONSTRAINT ck_orders_version CHECK (version >= 0);

ALTER TABLE drivers ADD COLUMN operational_status varchar(32) NOT NULL DEFAULT 'OFFLINE';
ALTER TABLE drivers ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE drivers ADD CONSTRAINT ck_drivers_operational_status CHECK
    (operational_status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED'));
ALTER TABLE drivers ADD CONSTRAINT ck_drivers_version CHECK (version >= 0);

CREATE TABLE order_lifecycle_history (
    order_id uuid NOT NULL REFERENCES orders(id),
    version bigint NOT NULL,
    previous_state varchar(32),
    new_state varchar(32) NOT NULL,
    action varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    actor_id uuid REFERENCES actors(id),
    source varchar(10) NOT NULL,
    reason varchar(500),
    correlation_id varchar(64) NOT NULL,
    CONSTRAINT pk_order_lifecycle_history PRIMARY KEY (order_id, version),
    CONSTRAINT ck_order_history_version CHECK (version >= 0),
    CONSTRAINT ck_order_history_source CHECK
        ((source = 'ACTOR' AND actor_id IS NOT NULL) OR (source = 'SYSTEM' AND actor_id IS NULL)),
    CONSTRAINT ck_order_history_reason CHECK (reason IS NULL OR length(btrim(reason)) > 0)
);
CREATE INDEX idx_order_history_time ON order_lifecycle_history (order_id, occurred_at DESC, version DESC);

CREATE TABLE driver_lifecycle_history (
    driver_id uuid NOT NULL REFERENCES drivers(id),
    version bigint NOT NULL,
    previous_state varchar(32),
    new_state varchar(32) NOT NULL,
    action varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    actor_id uuid REFERENCES actors(id),
    source varchar(10) NOT NULL,
    reason varchar(500),
    correlation_id varchar(64) NOT NULL,
    CONSTRAINT pk_driver_lifecycle_history PRIMARY KEY (driver_id, version),
    CONSTRAINT ck_driver_history_version CHECK (version >= 0),
    CONSTRAINT ck_driver_history_source CHECK
        ((source = 'ACTOR' AND actor_id IS NOT NULL) OR (source = 'SYSTEM' AND actor_id IS NULL)),
    CONSTRAINT ck_driver_history_reason CHECK (reason IS NULL OR length(btrim(reason)) > 0)
);
CREATE INDEX idx_driver_history_time ON driver_lifecycle_history (driver_id, occurred_at DESC, version DESC);

CREATE TABLE deliveries (
    id uuid CONSTRAINT pk_deliveries PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    status varchar(32) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_deliveries_status CHECK
        (status IN ('PENDING', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_deliveries_version CHECK (version >= 0)
);
CREATE INDEX idx_deliveries_order ON deliveries (order_id);

CREATE TABLE delivery_lifecycle_history (
    delivery_id uuid NOT NULL REFERENCES deliveries(id),
    version bigint NOT NULL,
    previous_state varchar(32),
    new_state varchar(32) NOT NULL,
    action varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    actor_id uuid REFERENCES actors(id),
    source varchar(10) NOT NULL,
    reason varchar(500),
    correlation_id varchar(64) NOT NULL,
    CONSTRAINT pk_delivery_lifecycle_history PRIMARY KEY (delivery_id, version),
    CONSTRAINT ck_delivery_history_version CHECK (version >= 0),
    CONSTRAINT ck_delivery_history_source CHECK
        ((source = 'ACTOR' AND actor_id IS NOT NULL) OR (source = 'SYSTEM' AND actor_id IS NULL)),
    CONSTRAINT ck_delivery_history_reason CHECK (reason IS NULL OR length(btrim(reason)) > 0)
);
CREATE INDEX idx_delivery_history_time ON delivery_lifecycle_history (delivery_id, occurred_at DESC, version DESC);

-- Existing rows have only a known current state. Migration time is the baseline time,
-- not a claim about when a past human transition occurred.
INSERT INTO order_lifecycle_history
    (order_id, version, previous_state, new_state, action, occurred_at, source, correlation_id)
SELECT id, 0, NULL, status, 'LEGACY_BACKFILL', now(), 'SYSTEM', 'legacy-backfill'
FROM orders;
INSERT INTO driver_lifecycle_history
    (driver_id, version, previous_state, new_state, action, occurred_at, source, correlation_id)
SELECT id, 0, NULL, operational_status, 'LEGACY_BACKFILL', now(), 'SYSTEM', 'legacy-backfill'
FROM drivers;

CREATE FUNCTION reject_lifecycle_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Lifecycle history is append-only';
END;
$$;
CREATE TRIGGER order_history_immutable BEFORE UPDATE OR DELETE ON order_lifecycle_history
    FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_history_change();
CREATE TRIGGER driver_history_immutable BEFORE UPDATE OR DELETE ON driver_lifecycle_history
    FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_history_change();
CREATE TRIGGER delivery_history_immutable BEFORE UPDATE OR DELETE ON delivery_lifecycle_history
    FOR EACH ROW EXECUTE FUNCTION reject_lifecycle_history_change();
