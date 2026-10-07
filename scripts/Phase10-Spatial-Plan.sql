-- Run against a disposable PostGIS database with psql -v ON_ERROR_STOP=1 -f.
-- All 5,000 fixture zones are rolled back. Read the EXPLAIN output, not just its cost.
BEGIN;
SELECT gen_random_uuid() AS plan_merchant, gen_random_uuid() AS plan_branch \gset

INSERT INTO merchants (id, name, created_at, updated_at)
VALUES (:'plan_merchant'::uuid, 'Plan fixture', now(), now());

INSERT INTO merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
    pickup_latitude, pickup_longitude, created_at, updated_at)
VALUES (:'plan_branch'::uuid, :'plan_merchant'::uuid, 'Target branch', 'Street', 'Cairo',
    30, 31, now(), now());

INSERT INTO merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
    pickup_latitude, pickup_longitude, created_at, updated_at)
SELECT gen_random_uuid(), :'plan_merchant'::uuid, 'Other branch ' || n,
    'Street', 'Cairo', 30, 31, now(), now()
FROM generate_series(1, 49) AS n;

INSERT INTO delivery_zones (id, branch_id, name, boundary, active, version, created_at, updated_at)
SELECT gen_random_uuid(), b.id, 'Plan zone ' || n,
    ST_Multi(ST_MakeEnvelope(31 + n * 0.01, 30, 31.005 + n * 0.01, 30.005, 4326))::geometry(MultiPolygon, 4326),
    true, 0, now(), now()
FROM merchant_branches b CROSS JOIN generate_series(0, 99) AS n
WHERE b.merchant_id = :'plan_merchant'::uuid;

ANALYZE delivery_zones;
SELECT count(*) AS fixture_zones FROM delivery_zones WHERE branch_id IN
    (SELECT id FROM merchant_branches WHERE merchant_id = :'plan_merchant'::uuid);
EXPLAIN (ANALYZE, BUFFERS)
WITH destination AS (SELECT ST_SetSRID(ST_MakePoint(31.002, 30.002), 4326) AS point)
SELECT z.id FROM delivery_zones z CROSS JOIN destination d
WHERE z.branch_id = :'plan_branch'::uuid AND z.active AND z.boundary && d.point
    AND ST_Covers(z.boundary, d.point)
ORDER BY z.id LIMIT 1;
ROLLBACK;
