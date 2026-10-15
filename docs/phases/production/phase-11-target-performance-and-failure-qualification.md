# Release Phase 11 — Target performance and failure qualification

> Current Day 1 assessment: **READY for preparation; Azure qualification remains open**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P1, with P0 security/reliability failures blocking release.** The local breadth harness now represents two concurrent reviewer identities, ten drivers and ten SSE streams with a 30-minute command profile. It remains loopback/disposable-only; no current Azure-target capacity or public-failure evidence exists.

## Objective

Measure the exact release on the selected small Azure VM under the realistic recruiter-demo workload, then fix only demonstrated bottlenecks and verify bounded degradation.

## Why this phase comes now

Load testing is meaningful only with representative UI/API workflows, observability, the production artifact, and recovery procedures on the real target. Prior local numbers are not Azure capacity claims.

## Existing state and disposition

- **KEEP:** bounded Python/load harness and earlier local workload evidence as a baseline only.
- **HARDEN:** target-specific workload, resource measurements, dependency failure/recovery tests, SSE pressure and reproducible report.
- **VERIFY:** actual Azure VM, DB pool, memory/CPU, Redis, Kafka lag, request errors and cost against the Phase 0 reviewer workload.
- **REMOVE:** no speculative optimization, autoscaling, route-optimization work or unmeasured cache layer.

## Existing features to upgrade

- Adapt the bounded local load harness, dashboards, alerts and existing failure tests to the exact Azure artifact and reviewer workload.
- Workload definition and current runner restriction are recorded in `docs/operations/PHASE11_TARGET_QUALIFICATION.md`.

## New features to add

- Add a dated Azure-target performance/failure report and change code or sizing only for measured issues.

## Work included

### Performance and reliability

- **P0:** run the Phase 0 profile: two simultaneous reviewer sessions, ten synthetic drivers, ten SSE streams, 30 minutes and at least 100 scripted workflow operations. Record p50/p95/p99, expected-operation success rate, rejected/limited/conflict counts, CPU/memory/GC/DB pool/Redis/Kafka/SSE metrics.
- **P0:** confirm public demo requests stay within the Azure credit/cost guard and VM resource limits; document start-window cost, disk/IP continuation and expected headroom.
- **P0:** repeat critical authorization, duplicate-command, outbox, Redis recovery, Kafka recovery and app restart behavior on a disposable target.
- **P1:** exercise slow SSE clients, DB pool saturation, slow query, Redis timeout, Kafka backlog, Keycloak outage, expired login and 429 pressure; correlate each with user behavior and operator signal.
- **P1:** optimize only a measured query, pool, thread or payload bottleneck; rerun the same profile and report before/after data.
- **P1:** verify graceful shutdown/restart and bounded worker/stream cleanup; no request or retry storm may starve health or auth.

### Security validation

- **P0:** review current source and image scan disposition, deployed ports, headers, secret injection, browser storage, CSP/CORS, role boundaries, rate limits and logging against the exact target digest.
- **P1:** perform a safe external exposure scan and document findings; no destructive penetration testing against shared accounts or data.

## Tests required

- Repeatable target load and browser smoke at the agreed workload.
- Failure injection for DB, Redis, Kafka, Keycloak, app restart, slow clients, full-ish disk alert and notification path.
- Concurrency/duplicate request and signed object-scope tests against target build.
- Current security/dependency/image scans and external port/header review.

## Failure scenarios to verify

- Saturated connection pool, Redis unavailable/recovered, Kafka down/recovered, poison backlog, app restart mid-work, SSE cap, slow browser, expired token, quota exhaustion and backup/alert degradation.

## Verification

- Preserve dated report tied to commit, image digest, Azure SKU/region, Compose config, workload inputs and alert set.
- Use same test profile before/after each tuning change; clean up generated synthetic state safely.

## Exit criteria

- Measured target profile reaches at least 99% successful expected operations and p95 non-stream API latency at or below one second, and the documented cold-start check completes within 15 minutes. If the VM misses these targets, resize or formally revise Phase 0 before release; never waive the result silently.
- Run cost and fixed resource estimates fit the owner's approved Azure-credit budget and alert/deallocation procedure.
- No critical/high security issue remains without a named, time-bounded, mitigated exception; no critical finding is silently suppressed.
- Failures produce documented user-facing behavior, observable signal and verified recovery without duplicate business effects.
- Public port review, signed role/object checks, secret scan and image digests correspond to the exact candidate.
- Results clearly distinguish local historical evidence from current Azure measurements.

## Deliverables

- Dated target performance/failure report, current security disposition, fixes for measured issues and updated resource/cost estimate.

## Features explicitly not added

- No horizontal scaling, autoscaling, second host, multi-region, ML or optimization based solely on synthetic benchmark numbers.

## Risk

HIGH. A small VM may not fit the test envelope, and unbounded queues/retries can create cost or outage.

## Depends On

- Phases 3–4 and 8–10.

## Unlocks

- Final release decision.


## Consolidation audit — 2026-10-14

Existing bounded runner retains the 2-reviewer/10-driver/10-SSE profile, now defaulting to 1800 seconds and requiring >=100 successful expected operations. Nearest-rank latency percentiles and explicit expected negative-probe accounting are unit verified. Periodic CPU/memory, GC/heap, pool/DB, Redis, Kafka/outbox and SSE samples distinguish unavailable signals from zero. Safe focused outage/slow-stream/rate/poison suites and the private broker/app-restart drill execute. No 30-minute Azure run or public load adapter result is claimed.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
