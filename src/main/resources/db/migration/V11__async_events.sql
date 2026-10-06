CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    aggregate_id uuid NOT NULL REFERENCES orders(id),
    aggregate_version bigint,
    event_type varchar(40) NOT NULL,
    schema_version smallint NOT NULL DEFAULT 1,
    envelope jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token uuid,
    lease_until timestamptz,
    published_at timestamptz,
    last_error varchar(120),
    CONSTRAINT ck_outbox_event_type CHECK (event_type IN ('ORDER_STATUS_CHANGED', 'ASSIGNMENT_OFFERED')),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_version CHECK
      ((event_type = 'ORDER_STATUS_CHANGED' AND aggregate_version IS NOT NULL AND aggregate_version > 0)
       OR (event_type = 'ASSIGNMENT_OFFERED' AND aggregate_version IS NULL)),
    CONSTRAINT ck_outbox_envelope CHECK (jsonb_typeof(envelope) = 'object'),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_outbox_lease CHECK ((lease_token IS NULL) = (lease_until IS NULL))
);

CREATE INDEX idx_outbox_due ON outbox_events (next_attempt_at, sequence)
    WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate_sequence ON outbox_events (aggregate_id, sequence)
    WHERE status <> 'PUBLISHED';
CREATE UNIQUE INDEX uq_outbox_order_version ON outbox_events (aggregate_id, aggregate_version)
    WHERE event_type = 'ORDER_STATUS_CHANGED';
CREATE UNIQUE INDEX uq_outbox_offer_assignment ON outbox_events
    ((envelope #>> '{payload,assignmentId}')) WHERE event_type = 'ASSIGNMENT_OFFERED';

CREATE TABLE processed_events (
    consumer_name varchar(32) NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_processed_consumer CHECK (consumer_name IN ('notification', 'analytics'))
);

CREATE TABLE quarantined_events (
    consumer_name varchar(32) NOT NULL,
    topic varchar(100) NOT NULL,
    partition_number integer NOT NULL,
    record_offset bigint NOT NULL,
    error_code varchar(40) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_quarantined_events PRIMARY KEY
      (consumer_name, topic, partition_number, record_offset)
);

CREATE TABLE notifications (
    id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    recipient_actor_id uuid NOT NULL REFERENCES actors(id),
    order_id uuid NOT NULL REFERENCES orders(id),
    assignment_id uuid,
    kind varchar(40) NOT NULL,
    state varchar(32),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_notification_kind CHECK (kind IN ('ORDER_STATUS_CHANGED', 'ASSIGNMENT_OFFERED')),
    CONSTRAINT ck_notification_state CHECK
      ((kind = 'ORDER_STATUS_CHANGED' AND state IS NOT NULL AND assignment_id IS NULL)
       OR (kind = 'ASSIGNMENT_OFFERED' AND state IS NULL AND assignment_id IS NOT NULL))
);
CREATE INDEX idx_notifications_recipient_created ON notifications
    (recipient_actor_id, created_at DESC, id DESC);

CREATE TABLE analytics_daily_order_transitions (
    day date NOT NULL,
    status varchar(32) NOT NULL,
    total bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_analytics_daily_order_transitions PRIMARY KEY (day, status),
    CONSTRAINT ck_analytics_total CHECK (total >= 0)
);
