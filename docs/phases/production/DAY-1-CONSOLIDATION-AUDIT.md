# Day 1 consolidation audit — 2026-10-14

The source of current results is [the evidence ledger](DAY-1-EVIDENCE-LEDGER.md). Prompt 1–3 snapshots remain historical evidence. This audit is not a public release approval and does not start Phase 7 persona implementation.

## Repository reality

- Existing branch: `codex/phase11-acceptance-closeout`; base commit `4efc0b0067ff451d4328761fd04597d2a4d05493`.
- The inherited tree already contained implementation, infrastructure and historical Phase 11 edits. They were preserved. No commit, push, public deployment or normal developer-volume cleanup was performed.
- Source identity uses the base commit **and** the executable-source SHA-256 manifest. An uncommitted tree cannot be represented as a reviewed candidate commit or successful remote CI run.
- Docker was reachable during the audit. Earlier Docker-blocked claims did not explain JDBC, reset, OpenAPI, auth and startup defects revealed by executed checks.

## Expected versus actual reconciliation

Classification describes the initial audit finding. Current verification is in the ledger.

| Area | Expected | Actual / initial classification | Repair and evidence |
| --- | --- | --- | --- |
| Mutations and transactions | Guarded transitions, one durable race winner, rollback and outbox consistency | PARTIAL verification; mature state machines retained | Reviewed route/service/query flow and lock/constraint order; reran lifecycle, assignment, matching, recovery and rollback suites. No state-machine rewrite. |
| Idempotency | Actor/operation scope, changed-payload conflict, rollback-safe reservation | COMPLETE implementation, NEEDS VERIFICATION | Existing PostgreSQL unique key and same-transaction response retained; concurrent/same-key/changed-body tests rerun. Retention decision below. |
| Migrations and integrity | Fresh and v11 supported upgrade, no historical edits | INCORRECT migration-count assertion | Forward-only V13/V14 retained; corrected count to 14. Fresh, upgrade, PostGIS, references, checks and unique-index suites execute. |
| JWT and object authorization | Current actor roles/memberships; own/foreign/revoked/inactive/wrong-role matrix | PARTIAL; uncached JWKS failure escaped the safe problem boundary | Added safe identity-availability filter and actual JWKS outage/restoration/cached-key test. Enumerated 64 test-profile operations with demo enabled; compared durable table counts after denial sweep. |
| 403/404 policy | No foreign-resource enumeration | COMPLETE policy, NEEDS VERIFICATION | CUSTOMER ownership and driver/branch scope stay in services/queries. Wrong role/unknown actor is 403; foreign or absent scoped object is 404. Dispatcher is explicitly global for this single synthetic deployment. |
| Rate and request bounds | Atomic windows/TTL, safe fallback, bounded work, Retry-After | PARTIAL; pool acquisition alone did not bound SQL | Retained Redis Lua budgets and bounded local fallback; added 15-second statement and 3-second lock deadlines. Verified body caps, pagination/geometry/SSE bounds and real proxy 413/429/spoof resistance. |
| API problems/OpenAPI | One safe model and JSON schemas | INCORRECT new-controller media schemas and generic exception handling | Added JSON media types, 413/header schemas, safe missing-parameter/405/415/500 handling and redaction test; OpenAPI matches registered routes. |
| Async delivery | Durable commit independent of broker, dedupe, bounded retries/quarantine/replay | INCORRECT infinite consumer retry; FRAGILE startup if broker DNS disappeared | Three delivery attempts then durable quarantine; failed quarantine commit leaves offset retryable. Deferred consumer construction has bounded backoff on a separate scheduler. Actual stop/restart/recovery retains commit and drains both consumers. |
| Operator recovery | Narrow ADMIN inspect/replay, reason/key/audit, same idempotent path | INCORRECT JDBC Instant binding made replay fail | Use SQL Timestamp; retain three audited replay attempts and payload-free quarantine inspection. Unsupported arbitrary poison replay/skip remains absent. |
| Synthetic seed/reset | Deterministic, authorized, serialized, interruption-safe, unrelated rows preserved | INCORRECT JDBC audit binding; FRAGILE old live work and zone version/history | Five personas, collision/ownership checks, idle old-order cancellation, busy-work refusal, OFFLINE driver baseline, canonical outbox effects, monotonic zone/policy history. Six extra safety tests complement repeat/concurrent/interruption tests. |
| Observability/privacy | Real signals, private probes, bounded labels, safe logs | PARTIAL; inherited Nginx log leaked logout token hint | Live private scrape, native consumer lag, pool/queue/freshness panels, safe application and proxy logs. Nine alert rules pass syntax and firing/recovery fixtures. Delivery remains target work. |
| Frontend boundaries/auth | Real API, memory-backed tokens, exact role guards, runtime decoding | FRAGILE late callback/session races and unchecked nested JSON | One API boundary with decoders; credential epoch/revision guards; current backend roles; safe return paths and route focus. Actual Keycloak PKCE flow, reload and logout exercised. |
| Frontend SSE/UX/accessibility | Bounded reconnect/cleanup, honest tracking, useful errors and narrow layout | PARTIAL / NEEDS REFACTOR | Watchdogs, 64 KiB frame cap, four reconnects, cancellation and snapshot refresh. Safe common states, countdown, terminal tracking, explicit unavailable assigned-driver location, stronger contrast and inspected desktop/mobile screenshots. |
| CI and supply chain | Required backend/frontend/browser/migration/security/SBOM foundation | NEEDS VERIFICATION; stale image dependencies | Pinned actions/scanner tools, Java CodeQL setup, retained reports, private real-browser job, six image builds/scans/SBOMs. Local actionlint and equivalent checks run; remote CI/CodeQL remain NOT_RUN for the uncommitted tree. |
| Production proxy/Keycloak/secrets | Separate private topology, TLS, stable issuer, non-root processes, readable file secrets | INCORRECT Docker context/ARG scope, Kafka volume/health, Nginx directives/PID/logs and secret permissions | Real disposable topology started. Optimized Keycloak health/metrics and exact production realm renderer; non-superuser DB role, non-root services, bounded resources/log rotation and private management/admin routes. |
| Backup/restore preparation | Encryption, off-host retrieval, key separation, safe isolated target | FRAGILE provenance/serialization/marker and CRLF parsing | Backup checks all six deployed image identities before stopping, locks execution, encrypts the complete recovery set, verifies remote retrieval, and records success only after healthy restart. Age/preflight/retention guards execute; target pipeline remains unexecuted. |
| Load/failure preparation | 2 reviewers, 10 drivers/streams, 30 minutes, >=100 operations, accurate metrics | INCORRECT tail percentile for small samples; PARTIAL resource signals | 1800-second default and bounded duration; explicit expected negative-probe accounting; nearest-rank percentiles; periodic CPU/memory, GC, heap, DB pool, Redis, Kafka/outbox and SSE samples. Missing signals remain unknown. Local unit checks validate reporting. |
| Evidence/docs | Exact source, commands, result, time and path | INCORRECT stale Docker blocker and unsupported completion language | Current ledger supersedes snapshots; machine-readable redacted summary, source manifest, scan dispositions and external logs retained. Target results and remote CI are never inferred from local tests. |

## Fixed command and recovery contracts

- Idempotency records, operator audit and demo audit are retained for the lifetime of this synthetic deployment; there is **no automatic expiry or prune**. The release must not reuse a successfully committed key for another payload. Reset does not erase keys. A future retention change must explicitly preserve the supported replay/recovery window. This is a decision, not an unfinished background cleanup job.
- Normal lifecycle/delivery/recovery commands use state conflicts for duplicate/lost-response retries; they do not promise replayed success. The browser never blindly retries a mutation. Zone edits use expected versions. Create/seed/reset/replay use their documented scoped keys.
- Reset requires `RESET FLEETFLOW DEMO`, a reason and Idempotency-Key. The generation row serializes requests. Idle old demo orders close through canonical transitions; active offers/accepted assignments/delivering drivers refuse reset with `DEMO_WORK_ACTIVE`. Unowned IDs are never adopted. Old demo orders remain durable but are hidden from current customer/tracking reads.
- Replay only requeues a known FAILED outbox event. Consumers use their normal processed-event dedupe path. Malformed quarantine metadata has no retained raw payload and cannot be arbitrarily replayed or skipped. Delivery is **at least once**.
- Liveness is process-only; readiness requires PostgreSQL. Redis loss closes location/heartbeat writes and removes fresh-position claims; ordinary route budgets use a bounded process-local fallback. Kafka loss leaves durable work pending. Consumer startup must tolerate missing broker DNS. Uncached JWKS outage is safe 503; valid cached-key tokens still require current actor authorization.
- The UI distinguishes a connected status stream from a current delivery location. No position means no invented movement. Reload loses memory tokens and requires IdP re-authentication.

## Security disposition

All six runtime images have strict high/critical and secret scan gates and CycloneDX SBOMs. [Scan disposition](evidence/day1-security-disposition-2026-10-14.json) records initial affected package/version/severity and remediation. No scanner ignore policy or broad exception was added.

Keycloak is 26.8.1; Nginx is 1.30.5 with signed OS security refreshes. Fresh production Kafka is 4.2.2 with maintenance Jackson/lz4 patches. PostgreSQL 17/PostGIS 3.5 and Redis 7.4.11 service versions are retained with signed OS updates. The normal developer Kafka volume is not upgraded or reused. Published candidates must use reviewed output digests and SBOMs rather than rebuilding a moving security-package resolution.

## Remaining work and ownership

| Assignment | Work | Owner / gate |
| --- | --- | --- |
| Must finish before Day 2 implementation | Any failed current-tree backend/frontend/private-stack gate in the authoritative ledger. No Day 1 P0 may be deferred. Final repository-controlled checks pass; no unresolved Day 1 code defect remains. | Backend/frontend owner; see final ledger result. |
| External prerequisite | Azure subscription/credit, approved region/SKU estimate, DNS control, certificate/renewal path, private secret delivery and reachable target alert destination. Repository preparation does not substitute for these. | Release owner, before provisioning/public exposure. |
| Belongs to Day 2 | Merchant, driver, dispatcher and operator workflows; extend the verified customer slice and error/conflict/reconnect journeys. | Frontend/backend owners; Phase 7. |
| Belongs to later release phase | Exact committed remote CI/CodeQL run, registry publication/attestation, target firewall/TLS/issuer checks, age/rclone off-host backup, fresh-host restore, notification delivery and 30-minute Azure qualification. | Release/operations owner; Phases 8–12. |
| Optional / post-release | Broader admin actions, map vendor, tracing, global frontend state library, cosmetic panels and extra optimization. | No release dependency. |

Day 2 can use the verified contracts and command loops in the ledger. It cannot assume a published candidate, Azure availability, a sustained RPO, target performance or public release approval.
