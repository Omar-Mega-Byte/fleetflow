# Release Phase 3 — API abuse limits and error contract

> Current Day 1 assessment: **COMPLETE**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: COMPLETE. Priority: P0.** Day 1 contract review and current-tree verification passed; public target acceptance remains a separate Phase 9 gate.

## Objective

Bound unauthenticated and authenticated work, make client-visible failures stable, and ensure rate limiting or validation cannot corrupt a workflow.

## Why this phase comes now

The UI and public edge need reliable 401/403/404/409/413/429/503 behavior. This phase extends existing controls after identity and command semantics are verified, without requiring a public host yet.

## Existing state and disposition

- **KEEP:** existing fixed-window per-actor budgets, location/heartbeat Lua limits, bounded SSE streams, request validation and ApiProblem response shape.
- **HARDEN:** route-class policy, retry hints, Redis outage semantics, payload/time bounds, trusted client-address rules and metrics.
- **ADD later in Phase 9:** reverse-proxy per-IP, concurrent connection, login, header/body and request-time limits before authentication.
- **REMOVE:** no duplicate rate-limit framework or trust of arbitrary forwarded-for headers.

## Existing features to upgrade

- Harden current rate classes, request validation, API problem responses, bounded SSE and Redis-loss behavior.

## New features to add

- Add only missing Retry-After behavior, request/work caps, metrics and proxy-layer limits required by the current threat boundary.

## Work included

### Backend/API

- **P0:** classify read, mutation, dispatch, zone geometry, location/heartbeat, SSE, seed/reset and authentication routes by cost and abuse impact.
- **P0:** define limit key, burst/window, response code, Retry-After, Redis-down policy and whether retries are safe for each class.
- **P0:** cap body size, request duration, collection size, stream count/lifetime and expensive geometry input. Preserve route-specific documented validation.
- **P1:** ensure 401/403/404/409/413/429/503 responses use a stable machine-readable problem shape without stack traces, token details, or foreign-resource existence leaks.
- **P1:** expose low-cardinality request/limit/failure meters for Phase 4. Never label by actor/order/token/coordinate.

### UI contract

- Define the client action for expiry, permission denial, conflict refresh, rate-limit wait and service outage. Do not add automatic retry for non-idempotent mutations.

## Tests required

- Limit boundary and simultaneous-caller tests for each expensive class.
- Redis available, slow and unavailable behavior; rejected mutation leaves state unchanged.
- Retry-After and problem-shape API tests; malformed and oversized request tests.
- Client-address spoofing tests at the proxy boundary in Phase 9.

## Failure scenarios to verify

- Redis loss or timeout, limit saturation, repeated dispatch request, malformed/oversized body, slow client, SSE connection flood, expired token and forged forwarding header.

## Verification

- Run focused API/rate-limit tests and full Maven verification.
- Verify all API operations document auth, validation and relevant error outcomes in OpenAPI.
- Exercise simultaneous pressure against a disposable instance and confirm no state mutation after rejection.

## Exit criteria

- Every public API class has a bounded request/work policy and a documented dependency-loss behavior.
- A limited request returns 429 and a usable Retry-After value where delay is meaningful.
- Rejected/oversized requests make no domain mutation; Redis-down behavior matches the documented fail-open/fail-closed choice.
- Stable error responses contain no internal stack, secret, token, raw coordinate or foreign-object detail.

## Deliverables

- API/rate policy, tests, updated OpenAPI/error guide and metrics contract.

## Features explicitly not added

- No global rate-limiter replacement, distributed quota service, generalized API gateway, or unmeasured retry library.

## Risk

HIGH. Public abuse can exhaust a single VM or turn a dependency outage into an unbounded retry storm.

## Depends On

- Phases 1–2.

## Unlocks

- UI error handling, observability and public edge configuration.

## Historical Day 1 prompt evidence — 2026-10-14

- See [Day 1 backend contract evidence](day-1-backend-contract-evidence.md) for the rate table, stable error contract, request caps and dependency-loss behavior.
- Day 1 adds a 1 MiB write-body cap (1 KiB for driver location), a 100-line order cap and `Retry-After` on driver location/heartbeat limits. Existing Redis outage tests confirm those writes fail closed without refreshing accepted state.
- The serviceability preview now shares the expensive-read budget, with a focused rate-class test. The phase remains `IN_PROGRESS`: request-read deadlines and public edge/IP/login controls require the Phase 9 proxy.


## Consolidation audit — 2026-10-14

Redis/location/fallback budgets, Retry-After, body/geometry/page/SSE limits and safe 400/401/403/404/409/413/429/503 handling execute. Generic exceptions no longer expose details. Private production Nginx validates 413 and sustained anonymous 429 despite spoofed X-Forwarded-For; spoofed Forwarded headers cannot change issuer. Final runtime pressure rejected 49 of 100 probe requests. Target internet qualification remains later work.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
