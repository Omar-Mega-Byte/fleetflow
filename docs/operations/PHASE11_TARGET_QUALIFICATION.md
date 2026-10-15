# Phase 11 target qualification preparation

This is a workload definition and local harness preparation, not an Azure result. The target acceptance profile remains **two simultaneous signed customer review sessions, ten synthetic drivers, ten live SSE streams, 1,800 seconds, and at least 100 successful expected operations**.

The existing `scripts/Phase11-Breadth.py` keeps its disposable-project and loopback-issuer restrictions. It now runs API reads from two distinct signed customer personas concurrently, opens ten owner-scoped SSE streams, provisions ten drivers, and emits request counts/status distribution, success ratio, p50/p95/p99, stream gauge samples, app container CPU/memory snapshots, and business/outbox checks when `--business-transitions` is set. The qualification command on a disposable *local* Phase 11 project is:

```powershell
python scripts/Phase11-Breadth.py --project fleetflow-phase11-qualify --env-file .env.phase11 --duration-seconds 1800 --reviewer-sessions 2 --business-transitions
```

That command is safe only for a fresh disposable loopback project. It directly bootstraps Keycloak identities and synthetic rows. Do not point it at a public host, a production project, or data that must be retained. Keep a separate folder for the JSON report and append its exact commit/image identities and alerts to the evidence ledger.

## Target metrics to record

- expected success ratio and status counts, split into 2 reviewer reads, driver reads/commands, merchant transitions, 429s, 409s and 5xx;
- p50/p95/p99 for reviewer reads, API reads, commands and merchant transition;
- active and rejected/evicted SSE connections, reconnects and slow-client behavior;
- CPU, memory and JVM GC for the app; Hikari active/idle/pending/max; PostgreSQL pool/readiness;
- Redis reachability and location freshness; Kafka consumer lag/failures; outbox pending and oldest age; quarantine count;
- exact VM SKU/region, disk/Compose settings, image digests, tool versions, start/end UTC, source commit and cost window.

The local harness currently reads Docker container CPU/memory and the active-SSE gauge, not every target metric above. Capture the remaining bounded Micrometer/Prometheus panels and Azure VM metrics in the dated report. The Phase 11 acceptance thresholds remain p95 non-stream API latency ≤1 second, ≥99% expected-operation success and successful cold start ≤15 minutes.

## Target-runner blocker

The existing fixture bootstrap is deliberately restricted to loopback development Keycloak and local Compose. The production overlay has no host-published Keycloak/admin port and the public proxy blocks `/admin/` and the master realm. Before running the target profile, add a reviewed private operator bootstrap path or a disposable runner attached to the private identity/data networks, with a dedicated short-lived operator credential and cleanup record. Do not solve this by opening admin endpoints or removing the local safety checks. Docker-backed failure injections (PostgreSQL, Redis, Kafka, app restart, slow SSE, poison work and rate-limit pressure) remain local/disposable test suites until the Azure fault procedure and cleanup boundary are approved.
