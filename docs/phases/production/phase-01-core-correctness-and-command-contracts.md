# Release Phase 1 — Core correctness and command contracts

> Current Day 1 assessment: **COMPLETE**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: COMPLETE. Priority: P0.** Day 1 contract review and current-tree verification passed; public target acceptance remains a separate Phase 9 gate.

## Objective

Close any correctness gaps in the supported synthetic workflows before adding a browser client or public ingress. PostgreSQL remains durable business truth; retries and conflicts must never invent a second successful action.

## Why this phase comes now

Security tests, UI behavior, event replay, and recovery drills need stable state transitions and known duplicate-command semantics. The work finishes the existing design instead of adding a parallel workflow engine.

## Existing state and disposition

- **KEEP:** explicit order/driver/delivery state machines, PostgreSQL constraints and transactions, optimistic/concurrency checks, atomic offers, durable recovery records, and Flyway migrations.
- **HARDEN:** duplicate command outcomes, ambiguous client timeout behavior, foreign-reference errors, state invariants and migration upgrade evidence where gaps remain.
- **VERIFY:** existing successful/failed dispatch, assignment, recovery, delivery, zone and tracking flows from the checked-out source and tests.
- **REMOVE:** no feature in this phase; do not rewrite historical Flyway migrations.

## Existing features to upgrade

- Harden duplicate-command handling, transaction boundaries, concurrency invariants and migration upgrade evidence for existing workflows.

## New features to add

- Add actor/operation-scoped idempotency only for commands whose lost-response retry can create a duplicate durable effect.

## Work included

### Backend and database

- **P0:** inventory every state-changing route and map request → authorization → transaction → durable state → outbox effect.
- **P0:** define replay semantics per command. Use an idempotency key only where a retry after a lost response can otherwise create a second effect; scope keys to actor and operation, persist the original result for the documented retention period, and reject key reuse with a different payload.
- **P0:** verify failed/rolled-back commands leave no offer, assignment, delivery, incident, audit, or outbox residue.
- **P1:** verify pagination and list bounds on existing collection endpoints. Add only a query/index fix supported by a measured query plan or unbounded result.
- **P1:** prove a fresh schema and supported prior-release schema upgrade without editing applied migrations.
- **P1:** keep all intake and fixture routes restricted to local/test until the later dedicated synthetic-demo boundary exists.

### Testing and documentation

- Add focused integration/concurrency tests only for gaps found in the route-to-transaction inventory.
- Update the API contract and failure semantics in OpenAPI/runbooks when behavior changes.

## Tests required

- Unit/state-machine tests for legal and illegal transitions.
- PostgreSQL integration tests for transaction rollback, constraints, uniqueness and migration from the supported prior release.
- Concurrency tests for duplicate accept/reject, competing offers and recovery races.
- API tests for same-key replay, payload mismatch, conflict responses and no side effects after rejected commands.
- Existing full backend verification suite.

## Failure scenarios to verify

- Duplicate request after a lost response; same key with a different payload; database conflict; process termination before commit and after commit; worker restart; concurrent accept/reject; stale assignment; repeated recovery sweep.

## Verification

- Run focused Maven test classes for changed invariants, then `.\mvnw.cmd clean verify`.
- Start an isolated disposable PostgreSQL/PostGIS test stack for migration/transaction checks; never reset the user's persistent local Compose volume.
- Compare registered routes with OpenAPI and inspect database state after injected failures.

## Exit criteria

- Every mutating route has a documented transaction and duplicate-request outcome.
- All scoped idempotency cases return the original result or a stable conflict; none create duplicate durable effects.
- Competing assignments leave exactly one valid winner and valid aggregate state.
- Fresh-install and supported-upgrade migrations pass; no historical migration was modified.
- Intake/fixture/admin-only operations remain unavailable outside their authorized profiles.

## Deliverables

- Corrected application code or an evidence-backed no-change disposition, focused tests, migration evidence, and updated API/failure documentation.

## Features explicitly not added

- No generic workflow engine, second persistence store, distributed lock service, payment ledger, or speculative indexing project.

## Risk

HIGH. A duplicate or partial transaction can corrupt assignment and downstream recovery.

## Depends On

- Phase 0.

## Unlocks

- Authorization matrix, stable API handling, event replay and browser workflows.

## Historical Day 1 prompt evidence — 2026-10-14

- See [Day 1 backend contract evidence](day-1-backend-contract-evidence.md) for the current route inventory and retry outcomes.
- Added actor/operation-scoped same-key replay for local/test order creation and signed branch zone creation, plus same-payload concurrent order replay coverage. Added a 100-item order bound and the V13 forward migration. No historical migration was changed.
- The phase remains `IN_PROGRESS`: complete the idempotency retention decision and remaining process-termination/rollback cases before closure; focused and full Maven verification passed on this working tree.


## Consolidation audit — 2026-10-14

143-test clean verify passed; lifecycle/assignment/recovery concurrency, rollback, scoped idempotency and outbox invariants execute. Fresh and supported-v11-upgrade migrations pass with 14 migrations. Command keys remain durable for this deployment lifetime; reset never erases replay history. SQL statements and locks are bounded to 15 and 3 seconds. No historical migration was edited.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
