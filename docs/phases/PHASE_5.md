# Phase 5 — candidate eligibility and matching

**Status: COMPLETE (2026-10-05).** The implementation, local verification, signed runtime smoke, and pull-request CI acceptance gate passed. The [execution roadmap](../ROADMAP.md) controls scope and order. The older Phase 5 outline in [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) includes assignment offers; those belong to Phase 6 under the execution roadmap.

## Objective and boundary

For an order that has reached `READY_FOR_PICKUP`, start one explainable dispatch search, reject ineligible drivers, and persist a deterministic ranking of the eligible candidates. A dispatcher can inspect the attempt and its reasons. Phase 5 never reserves a driver, creates an offer, or tells a driver that an assignment exists.

The demonstration path is: authorized merchant staff advances an order to ready through guarded Phase 3 actions → a dispatcher starts matching → Redis GEO supplies a bounded nearby pool → live location and heartbeat plus PostgreSQL facts exclude ineligible drivers → a pure scoring function ranks the rest → the order transition and immutable attempt snapshot commit together. A no-candidate run remains visible to a dispatcher with a reason and no offer.

## Verified starting point and prerequisite

- Phase 4 provides an internal `LocationService.nearby` lookup with GEO overfetch, exact distance check, GPS and heartbeat freshness checks, and a batched PostgreSQL recheck. It fails closed on Redis outage. Its result is a current candidate hint, not a reservation.
- The durable driver states implemented so far are `OFFLINE`, `ONLINE`, `AVAILABLE`, and `PAUSED`. Eligibility currently requires an active, unsuspended driver, active actor with `DRIVER` role, `AVAILABLE`, and an active positive-capacity vehicle. There is no `RESERVED` or `DELIVERING` state yet.
- Before this phase, orders had only `CREATED`, enforced by `ck_orders_status`; there was no guarded way to reach `READY_FOR_PICKUP`, no order status history, and no delivery record. The necessary Phase 3 order transition slice is now present. This does not make all of Phase 3 complete: cancellation and the full delivery lifecycle remain separate work.
- Before this phase, there was no dispatch table. Assignment, offers, zone polygons, route providers, workload/rating history, and order package-size requirements remain absent. Do not claim to filter by vehicle fit or zone, or score workload, acceptance rate, or rating from missing data.

## Decisions to freeze before implementation

| Decision | Proposed first contract | Evidence required |
| --- | --- | --- |
| Ready-order prerequisite | Add guarded `CREATED → CONFIRMED → PREPARING → READY_FOR_PICKUP` merchant actions with exact active branch membership, order status history, and transactional status/history writes. A dispatcher may start only a ready order. | Wrong branch, revoked membership, skipped/duplicate transition, and no partial history tests. |
| Start and duplicate policy | An explicit `DISPATCHER` command starts one matching run from `READY_FOR_PICKUP`. A second concurrent or repeated start gets a stable `409`; Phase 7 defines recovery and retry. | PostgreSQL concurrent-start test: one committed transition and one attempt. |
| No-candidate policy | Record an attempt with `NO_CANDIDATE` and an explicit reason, then move the order through `DISPATCHING` to `DISPATCH_FAILED` in the same transaction. Do not schedule automatic retries in Phase 5. | Empty GEO pool and all-filtered cases; customer sees only coarse order status. |
| Search bounds | Reuse configured Phase 4 radius, result limit, and overfetch cap. Document the actual chosen search radius and candidate limit in Phase 5 configuration. The bounded GEO pool is not an exhaustive citywide search. | Boundary, cap, radius, and query-count tests. |
| Hard eligibility | Require a fresh server-received heartbeat and usable GPS fix, exact WGS84 distance within radius, and current PostgreSQL driver/actor/role/vehicle facts. Apply hard filters **before** scoring. | Stale, vanished, paused, offline, inactive, suspended, missing role, and inactive-vehicle tests against real Redis/PostgreSQL. |
| Score | Use only available evidence: normalized pickup distance and remaining GPS freshness. Proposed defaults: distance weight `0.80`, freshness weight `0.20`; both nonnegative and sum to `1.00`. Validate the radius and weights at startup. | Pure Java score examples, boundaries, and invalid-configuration startup tests. |
| ETA | A labelled straight-line pickup estimate: `ceil(distance / configured metres-per-second)` with safe bounds. It is not a road-route or promised arrival time. | Zero, near-boundary, and maximum-radius tests. |
| Tie break | Sort by descending score, then ascending exact distance, then ascending driver UUID. Specify fixed precision and rounding so the same saved inputs/config produce the same ranks. | Identical-score and identical-distance tests with reversed input order. |
| Explanation | Save factor values, weights/config version, score, rank, distance, estimate, and stable eligibility/rejection reason codes. Keep raw GPS coordinates and bearer identity out of attempt history. | Database snapshot and read-view assertions; no sensitive fields in customer response/logs. |

The implemented choices and any differences from this proposal are recorded below. `PROJECT_CONTEXT.md` gives example weights involving workload, acceptance, rating, and zone; those are not usable until their data and semantics exist. A second scoring strategy interface is unnecessary for one algorithm.

## Matching pipeline and transaction boundary

1. Authorize the `DISPATCHER` actor and read the order's current state and saved pickup point. Reject unknown or non-ready orders without revealing candidate data.
2. Query Redis GEO around the pickup point within configured bounds. Revalidate latest fix, reported and received ages, heartbeat, and exact distance; collect stable rejection reason codes for observed GEO hits. Redis failure returns a safe `503` and leaves the order and attempt tables untouched.
3. Query PostgreSQL for current eligibility facts of the remaining IDs in a bounded batch. A driver that changed state after GEO lookup is excluded. Record only available, evidenced constraints; vehicle `capacity > 0` alone is not proof that it fits a specific order.
4. Score eligible candidates from one captured decision time and configuration snapshot. Normalize distance as `1 - min(distance / radius, 1)` and freshness as `1 - min(gpsAge / maxGpsAge, 1)`, clamped to `[0,1]`. Use fixed decimal precision and a documented rounding mode for persisted values. Rank with the tie break above; derive the simple pickup estimate from the same distance.
5. In a short PostgreSQL transaction, conditionally claim `READY_FOR_PICKUP → DISPATCHING`, insert status history and the attempt/candidate snapshots, and finish the no-candidate transition if needed. A stale order loses with `409` and leaves no attempt. No Redis call is made while holding an order row lock. Phase 6 must recheck live and durable eligibility inside its later reservation workflow; a Phase 5 rank is never authority to reserve.

Capture only candidates actually inspected within the GEO cap. Reasons such as `STALE_HEARTBEAT`, `STALE_GPS`, `OUTSIDE_RADIUS`, `DRIVER_NOT_AVAILABLE`, `ACTOR_INACTIVE`, `ROLE_MISSING`, `VEHICLE_INACTIVE`, and `ELIGIBLE` need precise, tested precedence. Empty GEO returns `NO_NEARBY_CANDIDATE`; an inspected pool with no eligible driver returns `NO_ELIGIBLE_CANDIDATE`. Do not imply that candidates outside the bounded pool were assessed.

## Durable data and API shape

Add forward-only migrations; do not edit V1–V3. The Phase 3 prerequisite migration expands the order status check and adds append-only order status history with actor, prior/next state, and server timestamp. The Phase 5 migration adds an attempt row keyed by order and sequence, a start/completion time, terminal matching outcome, search/config snapshot, and one bounded candidate snapshot row per inspected driver. Constrain FK relationships, positive sequence, unique `(order_id, sequence)` and `(attempt_id, driver_id)`, rank uniqueness within an attempt, and legal outcome/reason values. Index attempt reads by `(order_id, sequence DESC)` and candidates by `(attempt_id, rank)`. Keep score/explanation records immutable; later offer outcomes need their own explicitly designed Phase 6 linkage rather than rewriting the matching evidence.

Proposed routes are `POST /api/v1/dispatch/orders/{orderId}/match` and bounded `GET /api/v1/dispatch/orders/{orderId}/attempts`, both requiring a verified bearer token and `DISPATCHER`. The POST returns the committed attempt identifier and outcome, not an assignment. The read view exposes ranked score explanations and filtered reason codes only to dispatchers; omit raw GPS and driver subject/PII. The existing customer order read may show `DISPATCHING` or `DISPATCH_FAILED`, but no candidate IDs, coordinates, or operational reasons. Choose a clear policy for unknown order versus non-ready order (`404`/`409`) and use the existing correlated safe error shape. Do not add a public nearby-driver route or driver offer notification.

## Reviewable implementation slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Ready prerequisite | Guarded merchant order actions, forward migration, history, branch authorization. | Can a real authorized order reach ready without a direct status setter? |
| 2. Candidate inspection | Bounded Redis GEO search, explicit freshness and durable rejection reasons, batch DB query. | Can a GEO hit ever become ranked when any hard fact fails? |
| 3. Pure ranking | Validated properties, normalized score, stable sort, basic estimate. | Do reordered identical inputs/config yield byte-for-byte equivalent ranks and reasons? |
| 4. Dispatch record | Conditional order claim and immutable attempt/candidate history in one PostgreSQL transaction. | Can two starts or a failed write produce a duplicate or partial attempt? |
| 5. Authorized read and evidence | Dispatcher-only action/read, negative security tests, migration/query review, full CI and local smoke. | Can any ordinary customer, merchant, or driver obtain candidate detail or trigger matching? |

## Verification matrix

| Level | Required meaningful cases |
| --- | --- |
| Pure unit | Distance/freshness normalization, weight validation, score precision, estimate bounds, stable UUID tie break, input-order independence. |
| PostgreSQL Testcontainers | Fresh and upgrade migrations; order status/history rollback; uniqueness/check constraints; conditional concurrent start; immutable attempt snapshots; indexed bounded attempt read and batch eligibility query. |
| PostgreSQL plus real Redis | Fresh available candidate; expired heartbeat, stale/missing GPS, out-of-radius fix, paused/offline/suspended/inactive actor or vehicle, state change between GEO and DB, overfetch cap, no candidates, and Redis outage with no order/attempt mutation. |
| MVC/OIDC | Signed dispatcher can match/read; missing/invalid token `401`; wrong role `403`; cross-branch merchant action denied; missing/non-ready order handling; customer view contains no candidate details. |
| Runtime and CI | `./mvnw.cmd clean verify --no-transfer-progress`, CI verify, and a real signed-token local flow through ready → match → dispatcher read. Record counts, failures/skips, commit/run, configuration and limitations. |

## Acceptance gate

Mark Phase 5 `COMPLETE` only after all of the following have recorded evidence:

- [x] A guarded, authorized Phase 3 order path reaches `READY_FOR_PICKUP`, with durable transition history.
- [x] Matching starts only for a ready order, and duplicate/concurrent starts have one durable winner.
- [x] Redis/GPS/heartbeat and current PostgreSQL facts are hard filters; ineligible drivers have no rank or score.
- [x] The same captured inputs, decision time, and validated configuration yield the same score, order, estimate, and explanation.
- [x] Both ranked and no-candidate outcomes preserve bounded, inspectable attempt history without raw GPS trails.
- [x] Redis outage and failed transactions leave durable order/attempt state unchanged; no stale fallback decides a match.
- [x] Dispatcher authorization, customer privacy, migration upgrades, PostgreSQL/Redis integration tests, full Maven verify, CI, and signed local smoke pass.
- [x] No reservation, offer, driver status change to `RESERVED`, automatic retry, PostGIS zone decision, or external route claim is introduced.

Phase 6 consumes this ranking but must make its own current eligibility check and atomic PostgreSQL reservation/offer decision. Phase 7 owns retries, timeouts, and recovery from `DISPATCH_FAILED`.

## Implemented contract and evidence (2026-10-05)

The code keeps Phase 3 `IN_PROGRESS`: only the merchant order path needed for matching was added. `V4__order_ready_lifecycle.sql` expands the status check and creates append-only `order_status_history`. `OrderLifecycleService` exposes three exact-branch merchant actions: `/api/v1/merchant/orders/{id}/confirm`, `/prepare`, and `/ready`. Each conditional status update and actor-attributed history insert share a PostgreSQL transaction. Wrong-branch, revoked, inactive-branch, and absent scope produce non-enumerating `404`; skipped and repeated actions produce `409 ORDER_STATE_CONFLICT`.

`POST /api/v1/dispatch/orders/{orderId}/match` and `GET /api/v1/dispatch/orders/{orderId}/attempts?limit=20` require an issuer, audience, signature, and expiry-verified bearer token mapped to a current local `DISPATCHER` role. Missing/invalid authentication produces `401`, wrong role `403`, unknown order `404`, and non-ready or duplicate start `409 ORDER_STATE_CONFLICT`. A Redis outage produces correlated `503 REDIS_UNAVAILABLE` without an order or attempt write. The read limit is 1–20 and uses `(order_id, sequence DESC)` plus `(attempt_id, rank)` indexes. Customer order responses expose coarse status and address text, with no coordinates or candidate evidence. The local/test customer order create response follows that same output shape; request coordinates remain validated and stored for dispatch.

Default search values are **5,000 metres**, **50 ranked candidates**, GEO overfetch factor **4** with a **200-hit cap**, heartbeat TTL/freshness **30 seconds**, and GPS freshness **45 seconds** for both reported and server-received fix timestamps. The GEO prefilter radius is padded 1% to account for the Redis sphere; final inclusion uses a WGS84 ellipsoid distance and the configured 5,000-metre boundary. A GEO hit is inspected only within the cap, and an eligible hit beyond the 50-rank limit receives `CANDIDATE_LIMIT` with no persisted score/rank. This is a bounded search, not exhaustive coverage; the Phase 4 near-pole GEO clamping limit still applies.

Reason precedence is `STALE_HEARTBEAT` → `STALE_GPS` → `OUTSIDE_RADIUS` → `DRIVER_NOT_AVAILABLE` → `ACTOR_INACTIVE` → `ROLE_MISSING` → `VEHICLE_INACTIVE` → `ELIGIBLE`. A missing driver is represented by `DRIVER_NOT_AVAILABLE`; an absent or inactive positive-capacity vehicle by `VEHICLE_INACTIVE`. Redis observations are followed by one bounded PostgreSQL batch query for current driver, actor, role, and vehicle facts. Vehicle capacity is only a basic active-vehicle condition: there is no order size to compare it against.

Scoring uses distance weight **0.80** and GPS freshness weight **0.20**. The pure `MatchingScore` calculates `0.80 × (1 − min(distance/radius, 1)) + 0.20 × (1 − min(gpsAge/maxGpsAge, 1))`, clamped to `[0,1]`. Persisted WGS84 distance, factors, and score use **six decimal places** and `HALF_UP` rounding. The same saved distance and age reproduce the saved factors and score. Ranking sorts by score descending, saved WGS84 distance ascending, then UUID ascending. One `decision_at` is captured for the attempt. The only estimate is `ceil(distance / 8.33 metres/second)`, bounded from 0 to **601 seconds** for the default radius; it is explicitly straight-line travel time, with no route or traffic claim. Startup rejects invalid radii, limits, weights, precision, speed, and incompatible Phase 4 location bounds.

`V5__dispatch_matching_snapshot.sql` adds immutable terminal `dispatch_attempts` and `dispatch_candidates` with legal reason/outcome checks, uniqueness, sequence, and rank constraints. `V6__dispatch_distance_precision.sql` widens the distance evidence from three to six decimal places without rewriting the already-applied local V5 migration. `V7__dispatch_candidate_required_fields.sql` closes PostgreSQL's nullable `CHECK` behavior so an `ELIGIBLE` row must have rank, score, factors, and estimate. The candidate table intentionally does not require a driver FK: an orphan GEO member can be snapshotted as `DRIVER_NOT_AVAILABLE`. Neither table stores coordinates, raw GPS trails, bearer subjects, or secret tokens. Rejected candidates have no score/rank/estimate. Empty GEO produces `NO_NEARBY_CANDIDATE`; an inspected pool with no eligible driver produces `NO_ELIGIBLE_CANDIDATE`; both end in `DISPATCH_FAILED` without automatic retry.

`DispatchService` performs Redis inspection, durable revalidation, and pure scoring before calling `DispatchCommit`. The latter uses a conditional `READY_FOR_PICKUP → DISPATCHING` update as the short PostgreSQL claim, then inserts order history, attempt, and bounded candidate rows. A no-candidate run adds `DISPATCHING → DISPATCH_FAILED` and its history in that same transaction. A losing claim returns `409`; insert failure rolls back the claim and every associated write. No driver is reserved or assigned.

Verification before the final privacy change: `./mvnw.cmd clean verify --no-transfer-progress` passed **62 tests, 0 failures, 0 errors, 0 skips**. This includes pure scoring and WGS84 unit tests; fresh and V1/V3 upgrade migrations; PostgreSQL constraints and immutable triggers; injected failures in order history, attempt, and candidate inserts; actual PostgreSQL concurrent starts; real Redis and PostgreSQL hard-filter, limit, radius, and outage cases; and signed MVC/OIDC security and privacy checks. `docker compose --env-file .env -p fleetflow up -d --build --pull never app` rebuilt the app from cached images. The existing local PostgreSQL schema advanced to Flyway V7; app, PostgreSQL, and Redis were healthy. `./scripts/Phase5-Local-Smoke.ps1` then used live Keycloak-signed customer, merchant, dispatcher, and driver tokens: merchant confirm/prepare/ready each returned 204, driver online/available/location each returned 204, dispatcher match returned 201 `RANKED`, dispatcher attempt read returned 200 with rank 1, customer attempt read returned 403, and customer order read returned 200 without candidate detail. It generated temporary credentials only in process memory and printed no tokens. Smoke order before the privacy change: `81948aff-cf13-477b-9858-c2332e6ff98c`. The final privacy-change verification and smoke are recorded below.

The [GitHub Actions verify run for PR #7 on commit ca855e6](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37351422325) passed, and GitGuardian reported pass. The subsequent documentation and privacy commits require their own CI checks before the PR is marked ready. Open limits: no route ETA or package fit, no assignment/reservation/offer, no retry, and no Phase 3 cancellation or delivery lifecycle.

Final privacy verification: customer order output now omits pickup and delivery latitude/longitude while the stored snapshots remain available for dispatch. The local/test create response uses the same coordinate-free output. `./mvnw.cmd clean verify --no-transfer-progress` again passed **62 tests, 0 failures, 0 errors, 0 skips**, including the updated boundary and privacy assertions. A rebuilt Compose app passed `./scripts/Phase5-Local-Smoke.ps1` again with real Keycloak tokens; customer order read returned 200 without candidate data or raw coordinates, customer attempt read returned 403, and the dispatcher saw a ranked attempt. Final smoke order: `d05e1660-c39a-4f3c-85a1-58dffb25c29d`.
