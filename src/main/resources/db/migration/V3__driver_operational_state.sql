ALTER TABLE drivers ADD COLUMN status varchar(16) NOT NULL DEFAULT 'OFFLINE';
ALTER TABLE drivers ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE drivers ADD CONSTRAINT ck_drivers_status
    CHECK (status IN ('OFFLINE', 'ONLINE', 'AVAILABLE', 'PAUSED'));

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
