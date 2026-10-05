CREATE TABLE dispatch_attempts (
    id uuid CONSTRAINT pk_dispatch_attempts PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES orders(id),
    sequence integer NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz NOT NULL,
    decision_at timestamptz NOT NULL,
    outcome varchar(32) NOT NULL,
    config_version varchar(32) NOT NULL,
    radius_meters integer NOT NULL,
    candidate_limit integer NOT NULL,
    geo_cap integer NOT NULL,
    max_gps_age_millis bigint NOT NULL,
    heartbeat_age_millis bigint NOT NULL,
    distance_weight numeric(5,4) NOT NULL,
    freshness_weight numeric(5,4) NOT NULL,
    estimate_metres_per_second numeric(8,3) NOT NULL,
    CONSTRAINT uk_dispatch_attempt_order_sequence UNIQUE (order_id, sequence),
    CONSTRAINT ck_dispatch_attempt_sequence CHECK (sequence > 0),
    CONSTRAINT ck_dispatch_attempt_outcome CHECK (outcome IN
        ('RANKED', 'NO_NEARBY_CANDIDATE', 'NO_ELIGIBLE_CANDIDATE')),
    CONSTRAINT ck_dispatch_attempt_bounds CHECK (radius_meters > 0 AND candidate_limit > 0
        AND geo_cap >= candidate_limit AND max_gps_age_millis > 0 AND heartbeat_age_millis > 0
        AND distance_weight >= 0 AND freshness_weight >= 0
        AND distance_weight + freshness_weight = 1 AND estimate_metres_per_second > 0),
    CONSTRAINT ck_dispatch_attempt_time CHECK (started_at <= decision_at AND decision_at <= completed_at)
);
CREATE INDEX idx_dispatch_attempt_order_sequence_desc
    ON dispatch_attempts (order_id, sequence DESC);

CREATE TABLE dispatch_candidates (
    id uuid CONSTRAINT pk_dispatch_candidates PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES dispatch_attempts(id),
    driver_id uuid NOT NULL,
    reason varchar(32) NOT NULL,
    distance_meters numeric(12,3),
    gps_age_millis bigint,
    distance_factor numeric(8,6),
    freshness_factor numeric(8,6),
    score numeric(8,6),
    rank integer,
    straight_line_seconds integer,
    CONSTRAINT uk_dispatch_candidate_driver UNIQUE (attempt_id, driver_id),
    CONSTRAINT uk_dispatch_candidate_rank UNIQUE (attempt_id, rank),
    CONSTRAINT ck_dispatch_candidate_reason CHECK (reason IN
        ('ELIGIBLE', 'STALE_HEARTBEAT', 'STALE_GPS', 'OUTSIDE_RADIUS',
         'DRIVER_NOT_AVAILABLE', 'ACTOR_INACTIVE', 'ROLE_MISSING', 'VEHICLE_INACTIVE',
         'CANDIDATE_LIMIT')),
    CONSTRAINT ck_dispatch_candidate_ranked CHECK (
        (reason = 'ELIGIBLE' AND score IS NOT NULL AND rank > 0 AND straight_line_seconds >= 0
         AND distance_factor IS NOT NULL AND freshness_factor IS NOT NULL)
        OR (reason <> 'ELIGIBLE' AND score IS NULL AND rank IS NULL
            AND straight_line_seconds IS NULL AND distance_factor IS NULL
            AND freshness_factor IS NULL)),
    CONSTRAINT ck_dispatch_candidate_measure CHECK (
        (distance_meters IS NULL OR distance_meters >= 0) AND
        (gps_age_millis IS NULL OR gps_age_millis >= 0))
);
CREATE INDEX idx_dispatch_candidate_attempt_rank ON dispatch_candidates (attempt_id, rank);

CREATE TRIGGER trg_dispatch_attempt_immutable BEFORE UPDATE OR DELETE ON dispatch_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_phase5_history_change();
CREATE TRIGGER trg_dispatch_candidate_immutable BEFORE UPDATE OR DELETE ON dispatch_candidates
    FOR EACH ROW EXECUTE FUNCTION reject_phase5_history_change();
