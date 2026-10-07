CREATE EXTENSION IF NOT EXISTS postgis;

ALTER TABLE merchant_branches
    ADD COLUMN serviceability_mode varchar(16) NOT NULL DEFAULT 'UNRESTRICTED',
    ADD COLUMN zone_policy_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_branch_serviceability_mode
        CHECK (serviceability_mode IN ('UNRESTRICTED', 'ENFORCED')),
    ADD CONSTRAINT ck_branch_zone_policy_version CHECK (zone_policy_version >= 0);

CREATE TABLE delivery_zones (
    id uuid CONSTRAINT pk_delivery_zones PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES merchant_branches(id),
    name varchar(120) NOT NULL,
    boundary geometry(MultiPolygon, 4326) NOT NULL,
    active boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_delivery_zone_name CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_delivery_zone_version CHECK (version >= 0),
    CONSTRAINT ck_delivery_zone_shape CHECK (
        NOT ST_IsEmpty(boundary) AND ST_IsValid(boundary) AND ST_NDims(boundary) = 2
        AND ST_NPoints(boundary) BETWEEN 4 AND 2048
        AND ST_XMin(boundary) >= -180 AND ST_XMax(boundary) <= 180
        AND ST_YMin(boundary) >= -90 AND ST_YMax(boundary) <= 90)
);
CREATE INDEX idx_delivery_zone_branch_active ON delivery_zones(branch_id, active, id);
CREATE INDEX idx_delivery_zone_boundary_gist ON delivery_zones USING gist(boundary);

CREATE TABLE zone_policy_history (
    id uuid CONSTRAINT pk_zone_policy_history PRIMARY KEY,
    branch_id uuid NOT NULL REFERENCES merchant_branches(id),
    zone_id uuid REFERENCES delivery_zones(id),
    actor_id uuid NOT NULL REFERENCES actors(id),
    action varchar(24) NOT NULL,
    policy_version bigint NOT NULL,
    mode_after varchar(16) NOT NULL,
    zone_name_after varchar(120),
    zone_active_after boolean,
    zone_boundary_after geometry(MultiPolygon, 4326),
    recorded_at timestamptz NOT NULL,
    CONSTRAINT uk_zone_policy_branch_version UNIQUE (branch_id, policy_version),
    CONSTRAINT ck_zone_policy_action CHECK (action IN ('ZONE_CREATED', 'ZONE_UPDATED', 'MODE_CHANGED')),
    CONSTRAINT ck_zone_policy_version CHECK (policy_version > 0),
    CONSTRAINT ck_zone_policy_mode CHECK (mode_after IN ('UNRESTRICTED', 'ENFORCED')),
    CONSTRAINT ck_zone_policy_snapshot CHECK (
        (action = 'MODE_CHANGED' AND zone_id IS NULL AND zone_name_after IS NULL
            AND zone_active_after IS NULL AND zone_boundary_after IS NULL)
        OR (action <> 'MODE_CHANGED' AND zone_id IS NOT NULL AND zone_name_after IS NOT NULL
            AND zone_active_after IS NOT NULL AND zone_boundary_after IS NOT NULL))
);
CREATE INDEX idx_zone_policy_history_branch ON zone_policy_history(branch_id, policy_version DESC);

CREATE FUNCTION reject_zone_policy_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Zone policy history is append-only';
END;
$$;
CREATE TRIGGER trg_zone_policy_history_immutable BEFORE UPDATE OR DELETE ON zone_policy_history
    FOR EACH ROW EXECUTE FUNCTION reject_zone_policy_history_change();

ALTER TABLE orders
    ADD COLUMN zone_mode_at_create varchar(16),
    ADD COLUMN zone_policy_version_at_create bigint,
    ADD COLUMN zone_id_at_create uuid REFERENCES delivery_zones(id),
    ADD CONSTRAINT ck_order_zone_decision CHECK (
        (zone_mode_at_create IS NULL AND zone_policy_version_at_create IS NULL AND zone_id_at_create IS NULL)
        OR (zone_mode_at_create = 'UNRESTRICTED' AND zone_policy_version_at_create >= 0
            AND zone_id_at_create IS NULL)
        OR (zone_mode_at_create = 'ENFORCED' AND zone_policy_version_at_create >= 0
            AND zone_id_at_create IS NOT NULL));

ALTER TABLE dispatch_attempts
    ADD COLUMN zone_mode varchar(16),
    ADD COLUMN zone_policy_version bigint,
    ADD COLUMN zone_id uuid REFERENCES delivery_zones(id),
    ADD CONSTRAINT ck_dispatch_attempt_zone_decision CHECK (
        (zone_mode IS NULL AND zone_policy_version IS NULL AND zone_id IS NULL)
        OR (zone_mode = 'UNRESTRICTED' AND zone_policy_version >= 0 AND zone_id IS NULL)
        OR (zone_mode = 'ENFORCED' AND zone_policy_version >= 0 AND
            ((outcome = 'ZONE_UNSERVICEABLE' AND zone_id IS NULL)
             OR (outcome <> 'ZONE_UNSERVICEABLE' AND zone_id IS NOT NULL))));
ALTER TABLE dispatch_attempts DROP CONSTRAINT ck_dispatch_attempt_outcome;
ALTER TABLE dispatch_attempts ADD CONSTRAINT ck_dispatch_attempt_outcome CHECK (outcome IN
    ('RANKED', 'NO_NEARBY_CANDIDATE', 'NO_ELIGIBLE_CANDIDATE', 'ZONE_UNSERVICEABLE'));

ALTER TABLE driver_assignments
    ADD COLUMN zone_mode_at_offer varchar(16),
    ADD COLUMN zone_policy_version_at_offer bigint,
    ADD COLUMN zone_id_at_offer uuid REFERENCES delivery_zones(id),
    ADD CONSTRAINT ck_assignment_zone_decision CHECK (
        (zone_mode_at_offer IS NULL AND zone_policy_version_at_offer IS NULL AND zone_id_at_offer IS NULL)
        OR (zone_mode_at_offer = 'UNRESTRICTED' AND zone_policy_version_at_offer >= 0
            AND zone_id_at_offer IS NULL)
        OR (zone_mode_at_offer = 'ENFORCED' AND zone_policy_version_at_offer >= 0
            AND zone_id_at_offer IS NOT NULL));

ALTER TABLE dispatch_incidents DROP CONSTRAINT ck_incident_code;
ALTER TABLE dispatch_incidents ADD CONSTRAINT ck_incident_code CHECK
    (code IN ('NO_DRIVER', 'PRE_PICKUP_REASSIGNMENT', 'DELIVERY_FAILED', 'ZONE_UNSERVICEABLE'));
CREATE UNIQUE INDEX uk_incident_open_zone ON dispatch_incidents(order_id, code)
    WHERE status = 'OPEN' AND code = 'ZONE_UNSERVICEABLE';
