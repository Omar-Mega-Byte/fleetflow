# Release Phase 5 — Async recovery and synthetic demo data

> Current Day 1 assessment: **COMPLETE for Day 1 slice**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P0 for safe business effects; P1 for operator replay and demo reset.** The transactional outbox, deduplicated projections and quarantine records exist. The consolidation audit verifies bounded ADMIN outbox replay, payload-free quarantine inspection and deterministic seed/reset with transaction/concurrency/interruption evidence. Arbitrary poison disposition is unsupported; target recovery qualification remains open.

## Objective

Make delayed, duplicate, poison and replayed events safe to operate, and make the public showcase reproducible without direct database editing or exposing local fixtures.

## Why this phase comes now

Core transactions and authorization are stable, while observability now exists to show backlog and recovery. The operator UI in Phase 7 depends on these backend contracts.

## Existing state and disposition

- **KEEP:** PostgreSQL transactional outbox, at-least-once Kafka delivery, idempotent consumers/projections, durable quarantine, lease-based workers and bounded recovery.
- **HARDEN:** schema/version compatibility, payload bounds, retry/backoff, retention, backlog/poison disposition and operator audit trail.
- **COMPLETE:** add a narrowly scoped deterministic synthetic seed/reset workflow, replacing manual fixture/API/database steps for the public demo.
- **VERIFY:** event replay, consumer rebuild and demo reset cannot create duplicate business effects or cross environment boundaries.
- **REMOVE:** no second broker, exactly-once claim, external webhook, or public general-purpose fixture API.

## Existing features to upgrade

- Harden the transactional outbox, Kafka consumer dedupe, durable quarantine, leases, retry policy and recovery worker.

## New features to add

- Add narrowly scoped audited inspect/replay/skip actions and deterministic synthetic seed/reset where current APIs do not provide them.

## Work included

### Messaging and backend

- **P0:** document event envelope, version, compatibility rule, payload limits, producer/consumer ownership, retry schedule, lease behavior, dedupe retention and outbox retention.
- **P0:** make poison disposition terminal or bounded; record reason, attempts, timestamps and safe correlation reference without logging sensitive payloads.
- **P1:** add operator-authorized inspect/replay/skip or rebuild actions only where semantics are safe. Persist who/when/why; replay must re-enter the same idempotent handler and obey a bounded attempt policy.
- **P1:** define deterministic demo personas, orders, branches, zones and synthetic driver movement. Seed/reset is authorized to a demo operator, rate-limited, idempotent, audited and unavailable in local fixture profiles by accident.
- **P1:** make reset recoverable: explicit confirmation contract, single active reset, known baseline, interruption behavior and no deletion outside the synthetic demo dataset.
- **P2:** add retention/pruning only after proving it preserves the supported replay and dedupe windows and never deletes unpublished events.

### Tests and operations

- Add backend contracts for the later operator UI; document when a user-visible action is pending versus complete.

## Tests required

- Kafka unavailable/recovered; publish retry; lease expiry; duplicate delivery; old/new event version; oversized/malformed poison event; dedupe expiry boundary.
- Authorized replay/skip, forbidden operator action, audit record, repeated replay and lost-topic projection rebuild.
- Repeated seed/reset, simultaneous reset, reset interruption/restart and proof unrelated records remain untouched.
- Full backend verification plus focused outbox/consumer/recovery suites.

## Failure scenarios to verify

- Kafka down during a committed command, duplicate consumer delivery, app restart during lease, permanently failing handler, corrupted event, replay twice, reset interrupted, reset request repeated and stale operator authorization.

## Verification

- Use isolated PostgreSQL/Kafka/Redis services and signed personas; do not run reset against the user's ordinary local data.
- Inspect database and projection state before/after each failure; confirm alerts from Phase 4 fire and clear.

## Exit criteria

- Outbox events survive broker outage and drain once the broker returns; business state does not depend on Kafka availability.
- Poison/replay decisions are bounded, authorized, audited and idempotent; no unbounded retry loop.
- Event versions and retention/rebuild rules are written and tested.
- A clean target produces the documented synthetic dataset; repeating reset yields the same baseline and cannot affect non-demo records.
- Operator endpoints expose only the information/actions needed by Phase 7.

## Deliverables

- Event/replay policy, minimal migrations/APIs if needed, deterministic seed/reset service, integration tests and operator runbook contracts.

## Features explicitly not added

- No exactly-once delivery claim, new broker, generalized event platform, real notifications, third-party integrations or unrestricted data wipe.

## Risk

HIGH. An unsafe replay/reset can duplicate or destroy durable demo data.

## Depends On

- Phases 1–4.

## Unlocks

- Operator interface, repeatable E2E and fresh-host recovery.

## Historical Day 1 prompt evidence — 2026-10-14

- The existing same-transaction outbox, lease reclaim, bounded publisher backoff/failure, processed-event dedupe, quarantine and projection retention remain in place. Kafka is still at-least-once; a broker failure does not roll back the business commit.
- Added an ADMIN-only bounded failed-outbox inspection view that omits event payloads and an idempotent, reasoned replay endpoint. Replays return through the normal publisher and consumer dedupe path; the database caps operator replay at three attempts and records actor, target, idempotency key, reason, result and time.
- Added an ADMIN-only bounded quarantine inspection route returning only consumer/topic/partition/offset/error code/time. Poison payloads are intentionally not stored; this release cannot safely replay or skip quarantined payloads, so no such action is exposed.
- Added a feature-disabled-by-default synthetic demo contract. It deterministically owns the customer, merchant, branch, membership, driver, vehicle, zone and generation-specific order/line item. The baseline is one `CREATED` EGP 245 order with a truthful `NOT_ACTIVE_YET` tracking state. Repeating seed returns the current generation; reset requires exact confirmation, advances a serialized generation, archives prior demo orders from customer/detail/tracking reads and never deletes unrelated data. Failed actions are separately audited after rollback. Demo subject defaults are documented for operator provisioning; no login passwords or identity-provider users are created by the API.
- The intended integration tests cover seed repeat/reset, wrong confirmation, role denial, concurrent reset, interrupted rollback, unrelated-row preservation, audit, outbox replay/dedupe and normal broker outage. They compiled, but DB/Kafka/Testcontainers execution was blocked because Docker Desktop's named-pipe endpoint returned HTTP 500. Do not claim these scenarios passed until the focused suites run on a working Docker Engine.
- The standalone `promtool` alert test validates a quarantine alert's firing/clearing behavior, but does not simulate a Kafka poison record or prove its live operator route.
- Phase remains `IN_PROGRESS`: complete the container-backed recovery suite, operator provisioning and signed customer/API/database browser proof. No generic fixture or database wipe route was added.


## Consolidation audit — 2026-10-14

JDBC audit bindings and reset zone/history/state defects are fixed. Consumer handling uses three attempts before quarantine; no unbounded poison loop. Broker-DNS loss defers listener startup with capped backoff, and actual app restart with Kafka/Redis stopped becomes ready. A committed generation 2 persists and both consumers drain to four processed markers. Seven seed/reset tests cover repeat/concurrency/interruption/ownership/busy refusal/rate/audit/version safety. Five personas are explicit. Failed outbox replay returns through the normal dedupe path; arbitrary malformed quarantine replay/skip remains unsupported.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
