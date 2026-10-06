ALTER TABLE driver_assignments DROP CONSTRAINT ck_assignment_status;
ALTER TABLE driver_assignments ADD COLUMN expiry_retry_at timestamptz;
ALTER TABLE driver_assignments ADD CONSTRAINT ck_assignment_status
    CHECK (status IN ('OFFERED', 'ACCEPTED', 'REJECTED', 'TIMED_OUT'));
ALTER TABLE driver_assignments DROP CONSTRAINT ck_assignment_response;
ALTER TABLE driver_assignments ADD CONSTRAINT ck_assignment_response CHECK (
    (status = 'OFFERED' AND responded_at IS NULL AND responded_by_actor_id IS NULL) OR
    (status IN ('ACCEPTED', 'REJECTED') AND responded_at IS NOT NULL
        AND responded_by_actor_id IS NOT NULL AND responded_at >= offered_at) OR
    (status = 'TIMED_OUT' AND responded_at IS NOT NULL
        AND responded_by_actor_id IS NULL AND responded_at >= offered_at)
);

ALTER TABLE driver_status_history ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE driver_status_history ADD COLUMN source varchar(10) NOT NULL DEFAULT 'ACTOR';
ALTER TABLE driver_status_history ADD CONSTRAINT ck_driver_status_history_source CHECK (
    (source = 'ACTOR' AND actor_id IS NOT NULL) OR
    (source = 'SYSTEM' AND actor_id IS NULL)
);
