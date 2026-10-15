ALTER TABLE orders ADD COLUMN demo_generation bigint;
ALTER TABLE orders ADD CONSTRAINT ck_orders_demo_generation
    CHECK (demo_generation IS NULL OR demo_generation > 0);

CREATE TABLE synthetic_demo_state (
    id boolean PRIMARY KEY DEFAULT true CHECK (id),
    generation bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by_actor_id uuid REFERENCES actors(id),
    CONSTRAINT ck_synthetic_demo_generation CHECK (generation >= 0)
);
INSERT INTO synthetic_demo_state (id, generation) VALUES (true, 0);

CREATE TABLE synthetic_demo_ownership (
    resource_type varchar(32) NOT NULL,
    resource_id uuid NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (resource_type, resource_id),
    CONSTRAINT ck_synthetic_demo_resource_type CHECK
        (resource_type IN ('ACTOR', 'CUSTOMER', 'MERCHANT', 'BRANCH', 'MEMBERSHIP',
                           'DRIVER', 'VEHICLE', 'ZONE', 'ORDER', 'ORDER_ITEM'))
);

CREATE TABLE synthetic_demo_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES actors(id),
    action varchar(16) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    reason varchar(500) NOT NULL,
    result_code varchar(32) NOT NULL,
    generation bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uk_synthetic_demo_action_key UNIQUE (actor_id, action, idempotency_key),
    CONSTRAINT ck_synthetic_demo_action CHECK (action IN ('SEED', 'RESET')),
    CONSTRAINT ck_synthetic_demo_key CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    CONSTRAINT ck_synthetic_demo_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_synthetic_demo_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_synthetic_demo_result CHECK
        (result_code IN ('SEEDED', 'ALREADY_SEEDED', 'RESET')),
    CONSTRAINT ck_synthetic_demo_audit_generation CHECK (generation >= 0)
);
CREATE INDEX idx_synthetic_demo_audit_time ON synthetic_demo_audit(occurred_at DESC);

CREATE TABLE synthetic_demo_failure_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES actors(id),
    action varchar(16) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    reason varchar(500) NOT NULL,
    result_code varchar(32) NOT NULL,
    generation bigint NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_synthetic_demo_failure_action CHECK (action IN ('SEED', 'RESET')),
    CONSTRAINT ck_synthetic_demo_failure_result CHECK (result_code ~ '^[A-Z0-9_]{1,32}$'),
    CONSTRAINT ck_synthetic_demo_failure_generation CHECK (generation >= 0)
);
CREATE INDEX idx_synthetic_demo_failure_time ON synthetic_demo_failure_audit(occurred_at DESC);

ALTER TABLE outbox_events ADD COLUMN replay_count integer NOT NULL DEFAULT 0;
ALTER TABLE outbox_events ADD CONSTRAINT ck_outbox_replay_count CHECK (replay_count BETWEEN 0 AND 3);

CREATE TABLE operator_action_audit (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES actors(id),
    action varchar(32) NOT NULL,
    target_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    reason varchar(500) NOT NULL,
    result_code varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uk_operator_action_key UNIQUE (actor_id, action, target_id, idempotency_key),
    CONSTRAINT ck_operator_action CHECK (action = 'OUTBOX_REPLAY'),
    CONSTRAINT ck_operator_action_key CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    CONSTRAINT ck_operator_action_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_operator_action_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_operator_action_result CHECK
        (result_code IN ('REPLAYED', 'REPLAY_LIMIT_REACHED', 'NOT_REPLAYABLE'))
);
CREATE INDEX idx_operator_action_audit_time ON operator_action_audit(occurred_at DESC);
