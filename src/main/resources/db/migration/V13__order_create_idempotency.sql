CREATE TABLE command_idempotency (
    actor_id uuid NOT NULL REFERENCES actors(id),
    operation varchar(80) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    order_id uuid REFERENCES orders(id),
    zone_id uuid REFERENCES delivery_zones(id),
    response_body jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_command_idempotency PRIMARY KEY (actor_id, operation, idempotency_key),
    CONSTRAINT ck_command_idempotency_operation CHECK (operation IN ('ORDER_CREATE', 'ZONE_CREATE')),
    CONSTRAINT ck_command_idempotency_key CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]{1,128}$'),
    CONSTRAINT ck_command_idempotency_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_command_idempotency_result CHECK (
        (response_body IS NULL AND order_id IS NULL AND zone_id IS NULL)
        OR (response_body IS NOT NULL AND operation = 'ORDER_CREATE'
            AND order_id IS NOT NULL AND zone_id IS NULL)
        OR (response_body IS NOT NULL AND operation = 'ZONE_CREATE'
            AND zone_id IS NOT NULL AND order_id IS NULL)
    )
);
