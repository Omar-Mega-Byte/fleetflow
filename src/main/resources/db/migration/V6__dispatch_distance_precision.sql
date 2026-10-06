-- Persist the same six-decimal WGS84 distance that the scorer uses.
ALTER TABLE dispatch_candidates ALTER COLUMN distance_meters TYPE numeric(12,6);
