# Phase 10 — PostGIS zones and geofencing

**Status: COMPLETE; merged to `main` on 2026-10-10.** The [roadmap](../ROADMAP.md) schedules durable branch serviceability after basic dispatch. The implementation and acceptance evidence below passed on the delivered code head. [PR #12](https://github.com/Omar-Mega-Byte/fleetflow/pull/12) merged into the Phase 9 branch; [PR #11](https://github.com/Omar-Mega-Byte/fleetflow/pull/11) subsequently merged both phases to `main`.

## Objective and current boundary

Add durable delivery zones for merchant branches. A branch with zone enforcement enabled accepts a destination only when an active zone covers it; dispatch and offer creation must apply the same serviceability rule. PostgreSQL/PostGIS owns that rule. Redis GEO continues to find nearby drivers and must not become a source of zone truth.

Today, orders keep WGS84 pickup and delivery latitude/longitude as numeric snapshots, and dispatch ranks drivers near the pickup point. There is no zone schema or spatial rule. `POST /api/v1/orders` is a loopback local/test route because the submitted prices are untrusted; Phase 10 must not make that route public or imply that spatial validation fixes pricing. Current merchant branch creation and membership setup are also local/test fixtures, while real merchant reads use signed OIDC and exact branch membership.

## Serviceability policy

| Case | Rule |
| --- | --- |
| Existing branch after upgrade | Keep an explicit `UNRESTRICTED` mode so existing orders and branches do not silently change behavior. Record this as legacy compatibility, not as proof of zone coverage. |
| Branch with `ENFORCED` mode | Require at least one active, valid zone covering the destination. If the last active zone is disabled, deny new orders and new offers until coverage is restored or an authorized, audited mode change is made. |
| Point on exterior boundary | Include it, using a covers-style predicate consistently in preview, intake, match and offer checks. A point in a polygon hole is excluded. |
| Precision and tolerance | Interpret API coordinates as WGS84 latitude/longitude, construct the PostGIS point as **longitude, latitude**, SRID 4326, and retain the existing six-decimal input limit. Do not silently expand a polygon by an arbitrary number of metres. Start with zero expansion; any nonzero tolerance needs an explicit business rule, units, tests and an auditable policy version. |
| Overlap | Multiple zones on one branch act as a union for yes/no serviceability; choose a deterministic zone ID if evidence needs one. Different branches may overlap because the caller selects a branch. This phase does not auto-select a branch. |
| Zone changes and accepted work | A new order or offer uses the current committed policy. If a zone edit removes coverage before an offer, stop that offer safely and expose a reason to dispatch/recovery. Do not erase an already accepted assignment or delivery. Preserve the rule/version used for prior decisions. |

The branch starts in `UNRESTRICTED` mode to preserve existing behavior. A merchant with current exact-branch membership may switch to `ENFORCED` after creating an active zone. Delivery zones cover destinations, not driver operating areas. Dispatch checks the saved destination against the current branch policy at match and offer time. An unassigned order outside a changed zone fails dispatch with a durable incident and can be retried by a dispatcher after coverage is restored. An accepted assignment remains intact. Preview is advisory; it does not reserve coverage for a later write.

## Implemented API contract

All paths below require a signed `MERCHANT` actor with current active membership for the exact active branch. Foreign and absent branch or zone IDs return `404 RESOURCE_NOT_FOUND`; an invalid token returns `401` and a wrong role returns `403`.

| Method and path under `/api/v1/merchant-branches/{branchId}` | Request | Result |
| --- | --- | --- |
| `GET /zones?limit=50` | Limit 1–100 | Active and inactive zones with GeoJSON geometry. |
| `POST /zones` | `{name, geometry, active}` | `201`, new zone, branch policy version increases. |
| `PUT /zones/{zoneId}` | `{name, geometry, active, expectedVersion}` | Replaces zone using its optimistic version; stale version is `409 ZONE_VERSION_CONFLICT`. |
| `GET /zone-policy` | None | `{mode, version}`. |
| `PUT /zone-policy` | `{mode, expectedVersion}` | Sets `UNRESTRICTED` or `ENFORCED`; empty enforcement is `409 ZONE_EMPTY`, stale policy is `409 ZONE_POLICY_VERSION_CONFLICT`. |
| `POST /serviceability` | `{latitude, longitude}` | `{supported, mode, policyVersion, zoneId}` using the same PostGIS predicate as intake and dispatch. |

The geometry input is a WGS84 GeoJSON `Polygon` or `MultiPolygon` with longitude/latitude pairs, at most 64 KiB and 2048 points. Invalid shapes return `400 INVALID_ZONE`. A valid destination outside an enforced area returns `409 ZONE_UNSERVICEABLE` from local/test order intake or offer creation. The order intake endpoint remains loopback local/test only, and its prices remain untrusted.

## Storage and migration

- Compose and Testcontainers use `postgis/postgis:17-3.5-alpine@sha256:894f570c0cf0664ed5576a8fd5d5bfb8fb1b19d592885b686c3a88c8bd90c41f`. The local image reports PostgreSQL 17.11 and PostGIS 3.5. Keep the existing PostgreSQL volume when changing the image; stop application writes and take a verified backup first. The automated rehearsal restores V11 data dumped from plain PostgreSQL 17.11 into the PostGIS image and then applies V12. It does not certify a production volume in place.
- Forward Flyway migration V12 enables PostGIS and adds branch-owned zones with UUIDs, active state, optimistic version, timestamps and `geometry(MultiPolygon, 4326)`. It normalizes valid Polygon input. Database checks require a nonempty, valid two-dimensional shape in WGS84 bounds with 4–2048 points; branch and GiST spatial indexes support the lookup.
- Each branch stores an explicit serviceability mode and monotonically increasing policy version. Append-only history snapshots the changed zone's name, active state and boundary, with actor and mode. New order, dispatch attempt and offer rows store their decision mode, policy version and selected zone ID; older rows remain nullable. Enforcement never follows implicitly from a zone count.
- The API rejects malformed GeoJSON, self-intersections, wrong types/SRID, empty shapes and out-of-range coordinates; it does not repair shapes silently. `EXPLAIN (ANALYZE, BUFFERS)` evidence for the branch-scoped point query is recorded below.

## API, authorization and runtime seams

- Zone create/replace/list, mode changes and preview use the documented endpoints above. Preview and writes use the same predicate, and writes check coverage again in their own transactions.
- Zone reads and writes require a signed `MERCHANT` with **current active membership for the exact branch**. Missing/foreign branch or zone IDs return the same non-enumerating `404`; wrong role is `403`, invalid/expired token `401`. A local/test `ADMIN` fixture role has no global production zone override.
- Local/test order creation checks the destination in its transaction before persisting. An unsupported destination returns `ZONE_UNSERVICEABLE` and leaves no order, items or history. Pricing and the loopback intake boundary are unchanged.
- Match, retry and offer creation recheck the saved destination under PostgreSQL locks before committing an offer. A failed zone check leaves no new driver reservation or active offer and retains an operator-visible reason.
- Match and offer transactions lock the order row, then take a shared branch row lock before checking the current zone predicate. Zone writes take the branch row lock exclusively. Offer creation then conditionally reserves a driver. Redis preflight runs before these PostgreSQL locks. A zone block marks the order `DISPATCH_FAILED`, exhausts automatic recovery, and opens a `ZONE_UNSERVICEABLE` incident. After coverage is restored, a dispatcher can use the existing reasoned `/api/v1/dispatch/orders/{orderId}/retry` route; a successful coverage recheck resolves the zone incident.
- Redis GEO remains a bounded proximity prefilter with freshness, exact WGS84 distance and durable driver checks. PostgreSQL alone owns zone policy. Raw polygons, addresses and GPS coordinates are not logged by the zone path.

## Reviewable implementation slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Policy and upgrade | Finalized boundary/legacy/overlap rules, pinned image, forward migration and restore rehearsal | Can a populated PostgreSQL database upgrade and restore without losing existing orders or dispatch data? |
| 2. Zone management | Scoped API, geometry validation, activation/version audit and OpenAPI | Can one merchant manage only their own branch zones, including invalid and overlapping shapes? |
| 3. Serviceability | One authoritative PostGIS predicate, preview and local/test order intake check | Do inside, outside, exterior boundary, holes and enforced-with-no-active-zone return the documented result with atomic rollback? |
| 4. Dispatch integration | Match/retry and offer rechecks, durable skip/failure evidence | Can a concurrent zone edit prevent a stale offer without reserving a driver, while Redis proximity still works? |
| 5. Acceptance | Spatial query plan, real PostGIS tests, signed local flow, fresh/upgrade/restore proof and CI | Are the claims reproducible on the delivered commit? |

## Verification and completion gate

- [x] Real PostGIS Testcontainers tests cover fresh and upgrade Flyway paths, valid Polygon/MultiPolygon, inside/outside, exterior boundary, polygon hole, overlap, invalid geometry/SRID, coordinate order, precision edge and empty enforced coverage.
- [x] Signed OIDC tests cover own versus foreign branch/zone, missing/invalid token, wrong role, revoked membership and a multi-role actor. No cross-merchant geometry or zone metadata leaks.
- [x] Order tests prove unsupported destinations leave no order/items/history. Dispatch and offer tests prove zone changes, retries and concurrent edits leave no stale offer or leaked driver reservation; previously accepted work remains valid.
- [x] Spatial query plan is recorded against representative data, with the actual index and timing. Redis GEO and stale-location behavior still pass.
- [x] Pinned Compose/Testcontainers image compatibility, upgrade of existing data, backup/restore rehearsal, full clean Maven verify, signed local smoke, GitHub CI and security checks pass on the delivered code head. Exact commands, test counts, commit and CI links are recorded below.

## Acceptance evidence

The local `./mvnw.cmd clean verify --no-transfer-progress` passed **120 tests, 0 failures, 0 errors, 0 skipped**. This run includes 4 zone integration tests on an isolated real PostGIS database, V11 data migration and restore tests, existing Redis matching and stale-location tests, and the signed test-token security suites. The populated backup rehearsal uses `pg_dump --data-only --inserts` on plain PostgreSQL 17.11, restores into a PostGIS-backed V11 schema, applies V12, and checks that the order and branch remain linked with `UNRESTRICTED` mode. No existing user volume was modified.

The isolated `fleetflow-phase10-smoke` Compose project used the pinned PostGIS image, Keycloak, Redis, and Kafka on separate loopback ports. `./scripts/Phase6-Local-Smoke.ps1 -IncludePhase10 -EnvironmentFile <temporary-env> -ComposeProject fleetflow-phase10-smoke` passed with real Keycloak-signed merchant, customer, dispatcher, and driver actors: zone creation, enforcement, inside/outside preview, wrong-role denial, matching, offer, acceptance, and a subsequent zone edit that blocked a second dispatch with a durable incident while leaving the accepted assignment intact. The project, its named volumes, and temporary credential file were removed after the check.

`scripts/Phase10-Spatial-Plan.sql` creates 50 branches with 100 active zones each in a disposable transaction, analyzes the table, and runs the branch-scoped `ST_Covers` query. The observed plan used `idx_delivery_zone_boundary_gist` as an Index Scan, examined 50 spatial hits, discarded 49 from other branches, and returned one zone. `EXPLAIN (ANALYZE, BUFFERS)` reported **0.237 ms** execution, 54 shared buffer hits, and no reads for this warm local synthetic fixture; this is query-plan evidence, not a production latency guarantee. The fixture transaction rolled back and left the one smoke zone intact.

The delivered code head is `18d4aeb6c3d895f4c374b5596b2cca7c1e1f4844`, comprising `ec2f33c`, `6b448eb`, and `18d4aeb`. Both [push verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37552391676) and [PR verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37552407216) passed on that head; [GitGuardian Security Checks](https://github.com/Omar-Mega-Byte/fleetflow/pull/12/checks) passed. [PR #12](https://github.com/Omar-Mega-Byte/fleetflow/pull/12) carries the reviewable work. This acceptance is for the Phase 10 slice and does not establish public production readiness. Its subsequent integration to `main` is recorded above.

**Out of scope:** route optimization, paid maps, global arbitrary geofencing, full GPS history, production order intake/pricing changes and Phase 11 hardening.

## Prompt to use when I say start Phase 10

> Implement FleetFlow Phase 10 from `docs/phases/Phase10.md` and the Phase 10 row in `docs/ROADMAP.md`. First inspect the current code, migrations, tests, Compose images, Git state and any applicable `AGENTS.md`; reconcile the document's proposed business policies with the current architecture and tell me any decision that truly cannot be inferred. Work in small, independently reviewable slices. Use a forward PostGIS migration and a compatible pinned image, real PostGIS/Testcontainers integration tests, signed OIDC/object-scope tests, an upgrade and restore rehearsal, and concurrency checks at order/dispatch/offer boundaries. Keep the local/test-only order intake and existing Redis GEO role intact. Run the full clean verification and local signed smoke, check CI and security results, and record exact evidence before marking Phase 10 complete. Do not claim behavior or verification that has not happened. Complete only the implementation and delivery I explicitly authorize at that time.
