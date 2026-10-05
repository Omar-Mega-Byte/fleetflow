# Phase 6 — atomic assignment and acceptance

**Status: COMPLETE (2026-10-06).** The [execution roadmap](../ROADMAP.md) controls phase order. Phase 5 has committed, immutable ranking snapshots; Phase 6 is the first phase that may reserve a driver or create an offer. Phase 3's cancellation and delivery lifecycle remain incomplete, so this phase adds only the delivery state needed for acceptance and leaves pickup/completion to later work.

## Objective and boundary

Offer one ranked driver an order without double booking. The same PostgreSQL transaction must reserve the driver and create the offer. The order stays `DISPATCHING` while an offer is open and becomes `DRIVER_ASSIGNED` only when the exact offered driver accepts before the server-side deadline. Rejection finalizes the offer and releases the reservation it owns.

Phase 6 does not add automatic expiry workers, next-candidate retries, reassignment, cancellation of an active offer or delivery, pickup/completion, Kafka notifications, or a distributed lock. Those are Phase 7 and later work. An expired offer cannot be accepted even before the Phase 7 cleanup worker exists; the expired row and reservation remain visible for operator recovery until that worker is implemented. A rejected offer leaves the order in `DISPATCHING` with no active offer; Phase 7 must advance or fail it. Do not represent either state as completed delivery.

## Starting point and decisions to freeze

Before this phase, the schema had `orders`, `drivers`, driver/order status history, immutable `dispatch_attempts` and `dispatch_candidates`, but no assignments or deliveries. `dispatch_candidates.reason = 'ELIGIBLE'` and a saved rank prove what matching observed, but are not current authorization or availability. Durable offer eligibility requires an active, unsuspended `AVAILABLE` driver, active actor with `DRIVER` role, and an active vehicle with positive capacity. Phase 4 owns latest GPS and heartbeat in Redis.

| Decision | Phase 6 contract | Proof |
| --- | --- | --- |
| Offer selection | An authorized `DISPATCHER` names the latest `RANKED` attempt and one of its ranked, `ELIGIBLE` candidates. The saved rank is evidence, not a reservation. One offer is allowed for an order in this phase; a later candidate requires Phase 7 recovery. | Unknown, wrong-order, unranked and stale attempt commands fail without writes. |
| Live eligibility | Immediately before the database transaction, recheck the selected driver's latest GPS, heartbeat, distance to this order's saved pickup point, and the configured freshness/radius policy. Redis outage fails closed. Within the transaction, recheck all durable actor, role, driver and vehicle facts in the conditional reservation statement. | Changed/stale GPS or heartbeat, paused/suspended driver, revoked role, inactive actor/vehicle and Redis outage never create an offer. |
| Lock order | The offer transaction locks/claims the order before conditionally reserving the driver, then inserts the offer and any needed delivery row. The accept/reject transaction locks the offer, then performs guarded cross-aggregate writes in one transaction. Keep a single documented lock order for all Phase 6 commands and bound lock waits. | Two orders targeting one driver and two commands targeting one offer cannot deadlock or both win. |
| Deadline | Store a server-generated `offered_at` and `expires_at` based on a validated positive, bounded configuration. Acceptance checks PostgreSQL wall-clock time **after acquiring the offer lock**, strictly before `expires_at`; a transaction-start timestamp could admit an offer whose lock wait crossed the deadline. A client timestamp is never authoritative. | Just-before and at/after deadline tests, including a lock wait that crosses the deadline. |
| Duplicate commands | A repeated accept or reject returns a stable `409` conflict, even for the same actor. No second delivery, history row or status transition is written. | Accept/accept, reject/reject and accept/reject races. |
| Rejection | The exact offered driver may reject an unexpired `OFFERED` offer; record `REJECTED`, conditionally release only that offer's `RESERVED` driver, and retain the immutable matching attempt. Do not offer the next candidate in Phase 6. | Wrong driver and stale response fail; a rejected offer cannot later be accepted. |
| First delivery record | Create one `PENDING` delivery for the order with the first offer, then set it `ASSIGNED` and link it to the accepted assignment on acceptance. Include an attempt sequence and active-delivery uniqueness so Phase 7 can preserve an old delivery if reassignment is added. | Offer has pending delivery; accepted tuple has one assigned delivery; no duplicate active delivery. |

The command's live Redis check is a short preflight, not a distributed transaction with PostgreSQL. Document the captured check time and enforce a small freshness limit. PostgreSQL remains authoritative for exclusive assignment; a location/heartbeat change after the preflight cannot create a second reservation. No Redis call, network provider call or notification runs while a PostgreSQL row lock is held.

## Transaction design

```mermaid
sequenceDiagram
    participant D as Dispatcher
    participant R as Redis location
    participant P as PostgreSQL
    participant V as Offered driver
    D->>R: Recheck GPS, heartbeat and pickup distance
    R-->>D: Fresh candidate or fail closed
    D->>P: Begin offer transaction
    P->>P: Lock DISPATCHING order and validate ranked candidate
    P->>P: Conditional AVAILABLE → RESERVED with durable eligibility
    P->>P: Insert OFFERED assignment and PENDING delivery
    P-->>D: Commit offer ID and deadline
    V->>P: Begin accept transaction as exact offer owner
    P->>P: Lock OFFERED row; check deadline and current states
    P->>P: OFFERED → ACCEPTED; RESERVED → DELIVERING
    P->>P: PENDING → ASSIGNED; DISPATCHING → DRIVER_ASSIGNED
    P->>P: Append driver and order status history
    P-->>V: Commit accepted result
```

The offer transaction must roll back every write if the conditional driver update affects zero rows, a uniqueness constraint fails, or insertion fails. Never return success before commit. The acceptance transaction must require one affected row at each guarded transition and roll back the entire tuple on any mismatch. Use a fresh database transaction for each contender; a JVM mutex or mocked repository is not a concurrency proof. Avoid long waits by setting a documented transaction/lock timeout and map expected contention to a stable conflict response.

## Durable schema and invariants

Add a forward-only Flyway migration after V7; do not edit existing migrations or matching snapshots. Expand the `drivers.status` and `orders.status` checks to include `RESERVED`/`DELIVERING` and `DRIVER_ASSIGNED`, and expand the status-history checks for exactly the new transitions. Add `driver_assignments` with UUID, order, driver, attempt, ranked candidate, `OFFERED`/`ACCEPTED`/`REJECTED` state, offer/deadline/response timestamps and actor/reason fields where applicable. Foreign keys must prove the attempt belongs to the order and the ranked candidate belongs to that attempt; a service-level check alone is insufficient if a composite key can express this safely. Keep the matching rows immutable.

Add partial unique indexes for one active (`OFFERED` or `ACCEPTED`) assignment per driver and per order. An accepted assignment remains active until an explicit later-phase terminal transition; the offer deadline does not release it. Index pending offers by `(status, expires_at)` for Phase 7 recovery and driver offer reads by `(driver_id, status, offered_at DESC)`. Check that `expires_at > offered_at`, response timestamps fit the state, and status values are closed. Add `deliveries` with order, attempt sequence, optional accepted-assignment link, `PENDING`/`ASSIGNED` state, timestamps and a partial uniqueness constraint for one active delivery per order. Phase 7 may extend the state set and add historical attempts; do not overwrite an accepted assignment to represent a replacement.

Cross-table consistency is enforced by short transactions and tested reconciliation queries: `OFFERED` implies `RESERVED` driver, `DISPATCHING` order and `PENDING` delivery; `ACCEPTED` implies `DELIVERING` driver, `DRIVER_ASSIGNED` order and `ASSIGNED` delivery linked to that assignment. PostgreSQL constraints independently prevent two active offers for one driver or order. The existing JPA driver lifecycle commands must gain a database-guarded transition or row lock/version check so a pause/offline request that read `AVAILABLE` before a reservation cannot overwrite `RESERVED` afterward. Driver self-service transitions must reject `RESERVED` and `DELIVERING`; an administrative status change must not silently release an active assignment.

## API and authorization

- `POST /api/v1/dispatch/orders/{orderId}/offers`: verified `DISPATCHER` only; body contains the matching `attemptId` and ranked `driverId`. Return `201` with offer ID, status and deadline after commit. Do not expose GPS or the driver's OIDC subject.
- `GET /api/v1/drivers/me/offers`: verified `DRIVER`; resolve the caller's driver profile from the trusted OIDC actor and return only that driver's offers, newest first with a bounded limit. Return order pickup/delivery details only to the offered driver and only as needed to decide the offer. Define the exact response fields before implementation.
- `POST /api/v1/drivers/me/offers/{offerId}/accept` and `/reject`: verified `DRIVER`; resolve exact offer ownership inside the command transaction. A wrong owner receives a non-enumerating `404`; the owner receives a stable `409` for terminal, expired or incompatible state. Avoid a client-supplied driver ID.
- `GET /api/v1/dispatch/orders/{orderId}/assignments`: verified `DISPATCHER`; bounded history and current state, without raw GPS, token claims or profile PII. Customer order reads show only the coarse order status.

All routes use the existing safe, correlated API error shape. A missing/invalid bearer token is `401`; authenticated wrong role is `403`. Object ownership is checked in the SQL/service path, not solely in the controller. Add OpenAPI descriptions only for behavior actually implemented.

## Reviewable implementation slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Schema and history | Status extensions, assignment/delivery tables, partial unique indexes and upgrade tests. | Can PostgreSQL reject duplicate active ownership independently of Java? |
| 2. Offer command | Ranked-candidate validation, live preflight, conditional durable reservation and atomic offer insert. | Can every failed reservation leave no assignment, delivery or history fragment? |
| 3. Response commands | Exact-owner accept/reject with deadline and cross-machine transitions. | Can one terminal result win under duplicate and conflicting commands? |
| 4. Views and security | Driver self-offers and dispatcher assignment read with OIDC/object scope. | Can a different driver or role learn or change an offer? |
| 5. Concurrency and delivery evidence | PostgreSQL race suite, full verify, signed runtime smoke, CI run and reconciliation query. | Do 100 synchronized contenders yield exactly one committed offer and 99 safe losses? |

## Verification matrix and acceptance gate

Use real PostgreSQL through Testcontainers for migration, uniqueness, rollback and races. Create 100 independently matched `DISPATCHING` orders whose ranked candidate is the same available driver. Release 100 separate callers from a barrier so they enter the offer command concurrently. Assert **one** successful committed offer, **99** expected conflicts, one `RESERVED` driver, one active assignment for that driver, one active offer for the winning order, and no assignment or delivery fragments for losing orders. Repeat the suite to catch timing-dependent failures, record wall time and any lock timeout, and query the final tuples directly. Do not replace the 100 callers with one transaction, synchronized Java block, mocks or a database other than PostgreSQL.

Cover two simultaneous offers for one order, pause/offline versus reserve, stale ranking, lost eligibility, Redis outage, statement/insert rollback, and database unique-constraint enforcement. Cover accept/accept, accept/reject, reject/reject, wrong driver, expired accept, a lock wait crossing the exact deadline, incompatible order/driver state and acceptance rollback. Include signed OIDC MVC/API cases for each role and ownership boundary. Run fresh and upgrade Flyway tests, `./mvnw.cmd clean verify --no-transfer-progress`, a signed local offer/accept flow, and GitHub CI on the final commit. Record exact test counts, failures/skips, commit and CI run links in this document before changing its status or the roadmap to `COMPLETE`.

- [x] Forward migration and database constraints pass fresh and upgrade tests.
- [x] Offer commits reservation, assignment, pending delivery and history together, or none of them.
- [x] Exact owner can accept or reject; duplicate, late and conflicting commands have one terminal winner.
- [x] Acceptance commits assignment, driver, order, delivery and history as one consistent tuple.
- [x] The 100-contender PostgreSQL proof produces one winner and 99 safe losers on repeated runs.
- [x] Object-level OIDC authorization, Redis outage behavior, signed local smoke and CI pass.
- [x] Evidence is recorded here, then Phase 6 and the roadmap are marked `COMPLETE`.

## Open implementation limits

Phase 7 must add deterministic timeout cleanup, next-candidate selection, cancellation, reassignment and delivery incident handling. Until then, an expired offer can block a new one and needs explicit operator visibility; a rejection does not automatically redispatch. No route ETA, package-fit guarantee or PostGIS zone guarantee is introduced here.

## Implementation notes

`V8__atomic_assignment.sql` extends the legal order and driver states, adds composite foreign keys from assignments to their matching evidence and from deliveries to the accepted assignment/order pair, and creates partial unique indexes for active driver, order and delivery ownership. `AssignmentService` performs a direct Redis GPS/heartbeat/distance recheck for the selected ranked driver. `AssignmentCommit` takes the short PostgreSQL order lock, conditionally reserves the still-eligible driver, writes the offer, pending delivery and history together, and checks the preflight age again after acquiring the driver row. Accept and reject use the exact driver's database actor mapping, an offer row lock, PostgreSQL wall-clock deadline checks, guarded state updates and rollback on mismatch. The existing JPA driver lifecycle now locks its row before self-transition so pause/offline cannot overwrite a reservation.

The driver may continue GPS and heartbeat updates while `RESERVED` or `DELIVERING`; nearby matching still requires durable `AVAILABLE`. An offer is visible only to its owning driver and dispatchers through bounded, role-scoped reads. Partial unique indexes remain the durable backstop if a caller bypasses service checks. The selected offer TTL is 90 seconds, the maximum age between Redis preflight and durable reservation is five seconds, and offer/response transactions use a two-second PostgreSQL lock timeout. These defaults are validated at startup or fixed in the transaction code.

## Verification evidence

Commit [`84b036c`](https://github.com/Omar-Mega-Byte/fleetflow/commit/84b036c) passed `./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --no-transfer-progress` locally on 2026-10-06: **72 tests, 0 failures, 0 errors, 0 skipped**. The Phase 6 PostgreSQL/Redis/OIDC class ran 10 tests covering the atomic tuple, role and owner scope, rejection, expiry, injected rollback, Redis outage, durable eligibility loss, active order and driver uniqueness, same-order race, pause-versus-reserve race, accept-versus-reject race, and lock wait crossing the deadline. Its two synchronized 100-caller PostgreSQL repetitions each committed **one offer and 99 conflicts**, with measured elapsed times **1,059 ms** and **368 ms**; direct SQL assertions found one reserved driver, one active assignment, one pending delivery, and no losing-order delivery fragments. These timings describe this local run, not a latency service level.

The local Compose application built from cached images, applied Flyway through **V8**, and passed `scripts/Phase6-Local-Smoke.ps1` with real signed Keycloak tokens. The flow progressed from merchant readiness through driver liveness, ranked matching, dispatcher offer, owner-only read, acceptance and duplicate `409`. A direct PostgreSQL query returned `ACCEPTED|DELIVERING|DRIVER_ASSIGNED|ASSIGNED` for order `f1318f3f-8b5e-4583-9075-57336299f4ac`; the customer order read exposed only the coarse assigned state.

[PR #8](https://github.com/Omar-Mega-Byte/fleetflow/pull/8) checks for code commit `84b036c` passed: [push verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37377108993/job/111988972028) and [pull request verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37377163809/job/111989173069), with GitGuardian Security Checks passing. This local and CI evidence supports Phase 6 assignment and acceptance; it does not claim deployed production traffic or Phase 7 recovery.
