# Phase 11 baseline and local demo target

**Recorded 2026-10-07. Target: local reproducible demo.** This is the target for [Phase 11](../phases/phase11.md), not an internet-facing production deployment or a measured result. Recheck Git refs, service versions, and machine resources when running the acceptance exercises.

**Delivery update, 2026-10-10:** The branch and PR states below are the dated assessment snapshot. PR #11 subsequently merged Phases 9/10 to `main`, and PR #13 merged Phase 11 to `main`. Phase 11 remains `CLOSED_PARTIAL`; integration did not satisfy its remaining acceptance gates.

## Repository and prerequisite reconciliation

At assessment, the working checkout was `8ce456eedecf8d2f7d172d4476ea7e960c8a1c64` on `codex/phase10-postgis-zones`. A later successful fetch and GitHub PR inspection on 2026-10-07 found `origin/main` at `06c2c203ea22935878cdaa0d23f4863237e3d756` (Phase 8 merge). Phase 10 PR #12 had merged into the **Phase 9 branch** at `c0358a1`, while Phase 9 PR #11 was open against `main`; both of its GitHub `verify` jobs and GitGuardian check passed at that inspection. Thus Phases 9 and 10 were present in the stacked code but not yet integrated into `main` at that time.

| Phase | Evidence in this checkout | Reconciled status |
| --- | --- | --- |
| 0–3 | Phase documents have completed gates; foundation, core, OIDC, and lifecycle code are integrated. | `COMPLETE` as previously recorded. |
| 4 | [Phase 4](../phases/PHASE_4.md) records 47 passing tests, real Redis outage/recovery, signed smoke, and a CI run for its delivery commit. Its code is included in the Phase 7 integration merge on local `main`. | `COMPLETE`; roadmap integration-pending label was stale. |
| 5 | [Phase 5](../phases/PHASE_5.md) records 62 passing tests, signed matching smoke, and CI. Its code is included in the Phase 7 integration merge on local `main`. | `COMPLETE`; roadmap integration-pending label was stale. |
| 6 | [Phase 6](../phases/PHASE_6.md) records 72 passing tests, repeated 100-contender PostgreSQL races, signed smoke, and CI. Its code is included in the Phase 7 integration merge on local `main`. | `COMPLETE`; roadmap integration-pending label was stale. |
| 7 | [Phase 7](../phases/PHASE_7.md) records its accepted recovery/delivery slice; local `main` contains merge commit `9790e92` for PR #9. Its prose still describes the then-open draft PR. | `COMPLETE` on local `main`; historical document prose needs a factual update. |
| 8 | [Phase 8](../phases/PHASE_8.md) records local and CI acceptance; local `origin/main` contains merge commit `06c2c20` for PR #10. | `COMPLETE` on last fetched `origin/main`. |
| 9 | [Phase 9](../phases/Phase9.md) records its acceptance; PR #11 is open against `main` with passing checks on 2026-10-07. | `COMPLETE` for its review branch; **not merged to main**. |
| 10 | [Phase 10](../phases/Phase10.md) records its acceptance; PR #12 merged into the Phase 9 branch at `c0358a1`. | `COMPLETE` for its review branch; **not yet on main** because Phase 9 PR #11 is open. |

No applicable `AGENTS.md` was found in the workspace or its parent directories. The current working tree began with only the previously requested Phase 11 plan and roadmap link. Phase 11 starts from the Phase 10 code head on a new branch so it can exercise PostGIS, tracking, and events together. Phase 9/10 integration into `main` remains a release dependency.

## Current capabilities and gaps

| Area | Present in the assessed checkout | Phase 11 gap |
| --- | --- | --- |
| Operations | Pinned Compose app/PostGIS/Redis/Kafka/Keycloak stack on host loopback; health probes; PostgreSQL recovery/offer workers. | A private monitoring overlay, provisioned dashboard and local alerts were added and partly exercised; no external alert receiver, load report, or complete failure-drill record. Keycloak uses `start-dev`; Kafka listeners are plaintext inside the local stack. |
| Observability | Correlation ID filter, structured request logs, standard Actuator HTTP/JVM/Hikari meters, location counters, and outbox pending/failed/oldest gauges. | Private scrape and dependency/readiness alerts were exercised; a complete command-to-consumer trace, dashboard panel rendering, and backlog alert drill remain open. |
| Security | Signed JWT issuer/audience/JWKS validation, PostgreSQL actor roles, service-level object scope, default-deny route rules, local/test-only fixture and unsafe order intake; metrics GET requires `DISPATCHER`. | Full generated endpoint matrix, vulnerability/image and secret-scan evidence, privacy audit, rate classes beyond location/heartbeat and SSE connection caps, and a private metrics scrape design. |
| Recovery | Phase 10 migration/restore rehearsal covers a populated V11 data upgrade to PostGIS V12. Outbox uses leased PostgreSQL claims; consumers dedupe by `(consumer_name,event_id)`. A populated encrypted archive and isolated PostgreSQL/Keycloak restore supported a signed read and command, with a manual correction to the restore procedure. | Unattended restore, installed schedule, off-host copy, replay of a pre-existing pending event, and measured host-loss RPO/RTO remain open. |
| Verification | Maven CI workflow runs `verify`; Testcontainers suites cover PostgreSQL/PostGIS, Redis, Kafka, signed Keycloak, security, assignment races, and SSE. Existing signed smoke scripts cover subsets. | The final local Phase 11 `clean verify` passed 121 tests; a signed integrated smoke and partial isolated restore also passed. Full clean-start demo, load, dependency/secret scan, and final delivered-commit CI remain open. Docker Engine was unavailable only at the initial assessment. |

## Deployment boundary and trust model

One developer/operator runs one Spring app and one Compose project on a Windows workstation with Docker Desktop. The app's Compose `prod` profile is exercised for normal runtime behavior; for local/test fixture setup, the Compose app stops and the host-run `local` profile binds to loopback. This is a **synthetic-data demo only**. Do not import real customers, addresses, GPS histories, payments, or production credentials.

Host-published ports are loopback only: app `8080`, Keycloak `8081`, PostGIS `5433`, Redis `6379`, and Kafka `9094` by default. The container network carries plaintext service traffic and is trusted only within this workstation. A browser/client and any local process are outside the application trust boundary; every business request still requires a Keycloak-signed bearer token and PostgreSQL actor/scope checks. Redis and Kafka never authorize a business action. Nothing is intentionally exposed to the public internet.

The operator controls `.env`, Docker Desktop, database/Keycloak administration, and backup destinations. `.env` and transient tokens stay outside Git. Versioned Keycloak realm JSON provides configuration but no user backup. Logs stay on the local host with restricted access; metrics currently require a dispatcher token and are not yet a Prometheus scrape interface. Backup archives must use a configured path outside the repository; an off-host destination is not assumed to exist. Any claim of host-loss recovery requires a tested copy on a different host or service.

## Initial exercise targets, not results

These are deliberately modest targets for one app process on a workstation with 12 logical processors and about 16 GiB physical memory. Docker's actual allocated CPU/RAM and the database size must be recorded during load. They are acceptance goals for the defined workload, not service-level promises.

| Dimension | Initial target and reason |
| --- | --- |
| HTTP | Ten-minute steady workload: 5 authenticated read requests/s and 1 guarded business command/s across customer, merchant, driver, and dispatcher flows. This is a small interactive demo, not a traffic forecast. Exclude intentionally rejected commands from unexpected-error calculation. |
| Driver/location | 10 active drivers, one location fix every 10 seconds per driver and heartbeat every 15 seconds. These rates are below existing per-driver 30 fixes/minute and 60 heartbeats/minute limits. |
| SSE | 10 concurrent customer streams, at most one per actor, within current 100 global and 3 per-actor bounds. Reconnect and expiry are exercised separately. |
| Latency/errors | For healthy steady load: p95 under 1 second for reads/location, p95 under 2 seconds for guarded commands, no unexpected 5xx, and under 1% unexpected non-2xx. Report actual percentile distribution and every error; do not weaken correctness to hit these goals. |
| PostgreSQL | One app with the existing Hikari maximum pool size 10 and 5-second connection timeout. Watch active/pending/timeout meters and record database CPU/memory; change pool size only if measurement justifies it. |
| Redis | Existing 2-second command timeout. Redis is ephemeral; outage hides/blocks location decisions, and drivers must send fresh fixes after recovery. Bound the demo to existing location limits and measure timeout/error behavior. |
| Kafka/outbox | One local broker with at-least-once transport, 5-second publisher send timeout, and 5-second worker interval. Healthy pending age target under 30 seconds; after a short planned outage, target drain within 2 minutes without duplicate derived effects. Broker availability is not a business-commit prerequisite. |
| Backup | One encrypted PostgreSQL backup per day, seven daily copies retained for this synthetic demo. An external second copy is an optional configured destination until it is actually available and verified; no host-loss recovery claim without it. |
| Recovery | Desired RPO at most 24 hours and RTO at most 60 minutes for this local demo, including restoring identity prerequisites and signed operation. Measure both with an isolated restore; any miss remains a recorded limitation. |

## Release blockers at baseline

1. Docker Engine was unavailable at the initial assessment, then started. Testcontainers, a disposable monitoring stack, signed integrated smoke, and an independent restore with a manual correction have run; full Phase 11 demo, load, and unattended restore remain open.
2. At baseline, Phase 9 PR #11 was open against `main`, with Phase 10 merged into that branch through PR #12. Phase 11 was not integrated into `main` while that stack remained open.
3. Off-host and scheduled backup, unattended whole-service restore, complete alert/dashboard drills, representative load, full route audit, and clean-start scenario evidence remain to be implemented or exercised.
4. The local Compose topology, Keycloak dev mode, plaintext broker, and unsafe local/test intake prevent any public-production readiness claim.

## Later exercise update, 2026-10-07

The baseline sections above are the starting snapshot. Later on this branch, two fresh unattended signed restores passed, a controlled local drill measured 87.8-second RPO and 49.5-second RTO, a 600-arrival-second signed workload completed 3,720 requests without unexpected errors, and a clean-start Phase 11 signed scenario run passed. The monitoring overlay also fired and recovered its outbox backlog alert on a real signed command. Exact timestamps, scope, and remaining gaps are in [Phase 11](../phases/phase11.md). The only configured off-host rclone remote still returned `invalid_grant`; no external artifact or installed daily schedule has been verified. The 10-driver/10-SSE breadth target was not exercised by the two-driver load mix. The public-production and review-branch integration boundaries remain unchanged.
