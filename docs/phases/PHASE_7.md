# Phase 7 — recovery, reassignment and delivery incidents

**Status: COMPLETE (implementation, 2026-10-06; merged in PR #9).** Phase 6 established atomic offers and acceptance. Phase 7 covers failed offers, bounded retry, pre-pickup cancellation and reassignment, delivery progress, and incidents.

## Objective and current boundary

An overdue offer must become terminal from PostgreSQL time, and its `RESERVED` driver must be released in the same transaction. Rejection and timeout then advance through the saved ranked candidates, with bounded fresh matching when those candidates are exhausted. A cancellation or pre-pickup reassignment closes the active assignment and delivery attempt without erasing their history. Pickup, transit, completion and failure require the exact assigned driver or a reasoned dispatcher incident action. After pickup, no automatic reassignment occurs.

V9 makes offer expiry a PostgreSQL-clock transition. V10 adds durable recovery work, immutable candidate skip evidence, bounded incident records, and delivery outcomes. An accepted assignment, order, driver and delivery change in one transaction. Subsequent offers can use a new candidate on the current match attempt, or a candidate on a new attempt; one candidate can never receive two offers.

## State and ownership contract

| Case | Transactional result | Later action |
| --- | --- | --- |
| Offer accepted before deadline | `OFFERED → ACCEPTED`, `RESERVED → DELIVERING`, `DISPATCHING → DRIVER_ASSIGNED`, `PENDING → ASSIGNED` | Assigned driver may start delivery. |
| Offer rejected | `OFFERED → REJECTED`; conditionally release its `RESERVED` driver | Try the next unused ranked candidate. |
| Offer deadline passed | `OFFERED → TIMED_OUT`; conditionally release its `RESERVED` driver | Same candidate advancement as rejection. |
| Candidate unavailable | Record an explicit skip/reason without rewriting the immutable Phase 5 ranking | Continue within a bounded budget; never force an ineligible reservation. |
| Candidates exhausted | Make a bounded new match after backoff, or move to `DISPATCH_FAILED` with an operator-visible reason | Dispatcher may request a reasoned retry. |
| Order cancelled before pickup | Terminalize any open offer/accepted assignment and active delivery, release only its driver, write actor and reason history | No later offer or pickup. |
| Accepted driver replaced before pickup | Terminalize old assignment and delivery attempt; release driver; preserve both records; create a new delivery attempt for the replacement | No rewrite of the original matching evidence. |
| Delivery fails after pickup | Record `DELIVERY_FAILED`, delivery `FAILED`, actor/reason and incident | Operator resolution; never automatic reassignment. |

The Phase 6 offer row is the serialization point for accept versus timeout. Both commands lock it and compare **PostgreSQL `clock_timestamp()` after the lock** with `expires_at`. The first committed terminal transition wins. A deadline passed during a lock wait cannot be accepted. A timeout worker may skip a locked row and retry on the next sweep; it must not hold a lock while calling Redis. Two workers can scan the same due offer but only one can finalize it. All releases use guarded `RESERVED → AVAILABLE` writes and never change a `DELIVERING` driver. A failed guard rolls the whole transition back for investigation; it is not silently treated as success.

Use the same offer-first lock order for offer response, timeout, cancellation and pre-pickup replacement, then the driver and order as required. Document and test any lock-order adjustment before adding those commands. An order cancellation must invalidate the offer before the order becomes terminal. A new offer must recheck current Redis and durable eligibility as Phase 6 does; a saved rank alone is never sufficient. The active assignment/order and delivery uniqueness constraints remain the database backstop.

## Retry and incident policy

- Each ranked candidate may receive at most one offer per matching attempt. Try remaining candidates in persisted rank order, with a bounded count and no GPS from the old snapshot used as live authority. Capture every attempted candidate and outcome in separate recovery evidence; do not edit `dispatch_attempts` or `dispatch_candidates`.
- A fresh match is a new immutable attempt with an incremented sequence. Automatic matching stops after attempt 3. Backoff is 30 seconds times the failed attempt sequence. A dispatcher can request a reasoned retry while failed, up to 5 total attempts. Failed external calls leave the durable item pending with a 30-second retry delay. A Redis outage cannot silently mark an order assigned or exhausted.
- Record automatic actions with `source = SYSTEM`, no fabricated actor ID, and a stable action/reason. Manual retry, override, cancellation and incident commands require a current authorized actor and a nonblank bounded reason. Incident reads are bounded and restricted to dispatchers. Customer reads continue to expose only coarse order state.
- A driver owns pickup/transit/complete/fail actions only through the active accepted assignment and matching delivery. Each guarded transition and history insert shares one PostgreSQL transaction. Driver failure requires a reason. A dispatcher may fail an active post-pickup delivery with a reason. Completion and failure release the driver to `AVAILABLE`; neither starts another assignment. Resolving an incident closes the incident record, not the terminal order state.
- Keep Kafka, SSE, PostGIS, delayed queues and route optimization outside this phase. PostgreSQL is the timeout and retry clock; Redis TTL is only live-location evidence.

## API and operator surface

The existing dispatcher-only `GET /api/v1/dispatch/orders/{orderId}/assignments` exposes `TIMED_OUT` and later terminal statuses. The offered driver's existing read exposes their own terminal offer. A missing/invalid token remains `401`, the wrong role `403`, and a different driver's offer response remains non-enumerating `404`.

Driver actions are `GET /api/v1/drivers/me/deliveries?limit=20` and `POST /api/v1/drivers/me/deliveries/{deliveryId}/pickup`, `/start-transit`, `/complete`, or `/fail` (failure requires `{"reason":"..."}`). Dispatcher actions are `POST /api/v1/dispatch/orders/{orderId}/retry`, `/reassign`, `/cancel`, `/incidents/fail`, and `/incidents/{incidentId}/resolve`, all with a nonblank reason of at most 500 characters. `GET /api/v1/dispatch/orders/{orderId}/incidents?limit=20` lists newest incidents; the limit is 1–20. Merchant cancellation extends through `DRIVER_ASSIGNED` but stops at pickup. Customer cancellation still stops before preparation. Stale or terminal commands return conflict; private candidate and location details remain out of customer reads.

Operators can inspect the overdue backlog with `select count(*) from driver_assignments where status = 'OFFERED' and expires_at <= clock_timestamp();` and due recovery with `select count(*) from dispatch_recovery where status = 'PENDING' and next_action_at <= clock_timestamp();`. A failed guarded release leaves the offer and reservation unchanged, with an `expiry_retry_at` delay. Investigate persistent rows, then use the reasoned dispatcher retry or incident action; do not change assignment states directly in SQL.

## Reviewable slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Expiry foundation | Forward migration for `TIMED_OUT` and system audit; scheduled, bounded PostgreSQL sweep; one-transaction finalization/release | Do two workers and accept versus timeout leave exactly one terminal offer and one correct driver state? |
| 2. Candidate advancement | Rejection/timeout recovery item, next-unused-ranked-candidate selection, Phase 6 offer reuse with live checks | Does a failed candidate advance without duplicate offers or stale eligibility? |
| 3. Bounded retry | Durable budget, backoff, new match attempts, exhaustion to `DISPATCH_FAILED`, dispatcher retry and unresolved read | Can a crash, Redis outage or empty pool recover without an infinite loop? |
| 4. Cancellation and pre-pickup replacement | Guarded customer/merchant/dispatcher cutoff, accepted assignment closure, new delivery sequence and explicit reason | Can cancellation race acceptance and can replacement preserve the old attempt? |
| 5. Delivery and incidents | Driver pickup/transit/complete/fail; reasoned dispatcher incident, audit and operator read | Can only the assigned driver change delivery, and does post-pickup failure stop automatic dispatch? |
| 6. Acceptance evidence | PostgreSQL/Redis/OIDC race suite, fresh and upgrade migration, signed local flow, CI and runbook | Are the final states, histories and unresolved cases observable after restart? |

## Verification and completion gate

Use real PostgreSQL for due scans, two-worker claims, lock waits, uniqueness, rollback and histories. Race acceptance against expiry on both sides of the deadline; run rejection against recovery, cancellation against accept, and replacement against pickup. Exercise stale GPS, lost durable eligibility, Redis outage, candidate exhaustion, restart/backoff, duplicate requests, cross-driver access and incorrect roles. Verify fresh and upgrade Flyway paths, then run the full Maven suite, signed local flow and CI. Record exact counts, commit and CI link before marking this document or the roadmap `COMPLETE`.

- [x] Expiry worker commits `TIMED_OUT` and releases the correct reservation once; an expired recovery lease is reclaimed after a stopped worker.
- [x] Rejection and timeout advance through eligible ranked candidates and stop at a bounded failure state.
- [x] Cancellation and pre-pickup reassignment preserve assignment/delivery records and append-only lifecycle history.
- [x] Pickup, transit, completion, failure and incident actions enforce assigned-driver/dispatcher scope.
- [x] Two-worker, accept-versus-timeout, cancellation and pickup races pass against PostgreSQL.
- [x] Full migration, API, runtime and CI evidence is recorded here.

## Operator runbook

The worker runs when `RECOVERY_ENABLED=true` (default). `RECOVERY_BATCH_SIZE` defaults to 50 and `RECOVERY_SWEEP_INTERVAL_MS` to 5000; test profile disables scheduling. Offer expiry uses the offer deadline, while `dispatch_recovery.next_action_at` controls candidate advancement and fresh matching. A claimed recovery row has a 30-second lease so a stopped process leaves work for another sweep.

Inspect an order's latest immutable dispatch attempt through `GET /api/v1/dispatch/orders/{orderId}/attempts` and its incidents through the dispatcher endpoint above. For a database backlog check:

```sql
select count(*) from driver_assignments
where status = 'OFFERED' and expires_at <= clock_timestamp();

select status, last_reason, next_action_at, lease_until
from dispatch_recovery where order_id = :order_id;

select code, reason, status, opened_at, resolution
from dispatch_incidents where order_id = :order_id
order by opened_at desc;
```

An `EXHAUSTED` recovery item accompanies `DISPATCH_FAILED` and an open `NO_DRIVER` incident. After restoring eligible drivers and live fixes, a dispatcher can use the reasoned `/retry` command. Redis failure leaves work pending; verify Redis health before retrying. A `DELIVERY_FAILED` order requires human handling, and incident resolution records that handling without changing the order's terminal state. Persistent overdue offers with a reserved driver indicate a guarded cleanup failure and require investigation before any manual repair.

An existing local `fleetflow` Compose volume created by an earlier, different V3/V4/V5/V8 migration set fails Flyway checksum validation on this branch. No `repair` was run and that volume was preserved. The signed Phase 7 runtime proof used a separate fresh Compose project and removed only its own volumes afterward. Reconcile the older migration chain and its data before reusing that existing volume; changing checksums alone would not prove compatible schema.

## Implementation record

Started 2026-10-06. V9 adds `TIMED_OUT`, system-attributed driver status history and retry timestamps for failed cleanup. `OfferExpiryService` and `OfferExpiryWorker` finalize due offers, release only their reservation, and enqueue durable recovery in one transaction. V10 expands order, assignment and delivery states, adds the recovery queue and immutable candidate skip evidence, and adds incident records. `RecoveryService` rechecks live location and durable eligibility before an automatic offer, starts bounded new matches after backoff, and marks no-driver exhaustion. `RecoveryCommandService` handles reasoned cancellation, reassignment, retry coordination and incident resolution. `DeliveryCommandService` handles assigned-driver delivery commands and dispatcher failure. All state writes have guarded rows and lifecycle history in their owning transaction.

The first V9 slice passed 86 tests, 0 failures, 0 errors and 0 skipped in a clean Maven verify. The expanded Phase 7 targeted suite passed **13 tests**, including two recovery workers, expired lease reclamation, stale candidate skip, retry exhaustion, cancellation versus acceptance, pickup versus reassignment, accept versus timeout, Redis interruption, driver scope, delivery failure, and incident resolution. Fresh V10 and V1/V2 upgrade migrations passed their targeted tests. The `./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --no-transfer-progress` run passed **98 tests, 0 failures, 0 errors, 0 skipped** on 2026-10-06 before the additional expired-lease test; that new test passed in the 13-test focused suite. `docker compose --env-file .env.example config --quiet` and PowerShell parsing of the extended signed smoke script passed. A fresh isolated Compose stack then passed a real Keycloak signed flow through merchant readiness, driver location, matching, offer acceptance, pickup, transit, and completion; PostgreSQL ended at `COMPLETED|AVAILABLE|DELIVERED|DELIVERED`. The isolated stack and its volumes were removed. GitHub [push verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37460048303) and [PR verify](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37460053356) both passed on the implementation commit; [PR #9](https://github.com/Omar-Mega-Byte/fleetflow/pull/9/checks) later merged the slice. GitGuardian also passed after its scanner no longer mistook a Compose variable reference for a password.
