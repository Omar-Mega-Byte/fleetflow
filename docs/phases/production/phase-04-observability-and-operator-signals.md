# Release Phase 4 — Observability and operator signals

> Current Day 1 assessment: **COMPLETE for Day 1 foundation; full release phase remains PARTIAL**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P1.** Structured logs, correlation IDs, health, Micrometer metrics and local Grafana/alerts exist; the signal-to-action path must be checked before failure drills and target qualification.

## Objective

Make core user workflows and critical dependency failures visible to the owner without exposing personal data or internal controls to reviewers.

## Why this phase comes now

Failure injection, async recovery, load work and public operations are not actionable without reliable signals. Adding tracing or more dashboards before a concrete question is answered is out of scope.

## Existing state and disposition

- **KEEP:** JSON logging/correlation ID, Actuator liveness/readiness, HTTP/outbox/consumer/tracking meters, Grafana panels and alert rules.
- **HARDEN:** dashboard coverage, alert thresholds/owners, target delivery, dependency/readiness semantics and data redaction.
- **VERIFY:** every dashboard panel maps to an actual meter/query and is exercised; health detail and metrics are private and least-privileged.
- **REMOVE:** no high-cardinality labels, customer-coordinate logs, public metrics endpoint or tracing added without demonstrated diagnostic need.

## Existing features to upgrade

- Complete the signal-to-runbook path for existing metrics, health endpoints, Grafana panels and alert rules.

## New features to add

- Add a missing low-cardinality meter or alert only when a named operator decision cannot be made from current signals.

## Work included

### Observability

- **P1:** inventory existing signals and fill only release-critical gaps: request latency/error by bounded route class, DB pool/readiness, Redis freshness/errors, dispatch/offer outcome, recovery/incident age, outbox oldest/pending, consumer lag/quarantine, and active/evicted SSE.
- **P1:** define thresholds, observation windows, owner and runbook link for backup age, readiness, backlog/quarantine, certificate expiry, disk/cost and sustained 429 pressure.
- **P1:** test logs and metrics for tokens, secrets, customer addresses, raw GPS, polygons and free-text incident leakage.
- **P1:** expose Prometheus/Grafana/Alertmanager only on private or owner-only paths; distinguish liveness from dependency readiness.

### Testing

- Add meter/health tests for newly added signals and one controlled alert fire-and-clear exercise per critical alert class.

## Tests required

- Unit/integration tests for meter increments and readiness transitions.
- Log-redaction checks with synthetic secrets/coordinates.
- Dashboard query review and alert fire/recovery drill for dependency outage and outbox backlog.
- Private-access test for metrics and management paths.

## Failure scenarios to verify

- PostgreSQL/Redis/Kafka/Keycloak unavailable; outbox backlog; consumer quarantine; SSE cap reached; missed backup signal; alert destination unavailable.

## Verification

- Run relevant Maven/Compose checks and the existing monitoring runbook.
- Trigger a controlled alert and resolve it; record timestamp, notification route, dashboard signal and operator action.

## Exit criteria

- Every critical failure in the phase 5/10 runbooks has a signal, an owner action, and a tested alert or explicitly documented manual check.
- Liveness remains process-focused; readiness and operation results reflect required dependency policy.
- Dashboard panels show real data at the selected test workload; alerts fire and clear through the intended private channel.
- Logs/metric labels contain none of the prohibited secrets or high-cardinality personal identifiers.

## Deliverables

- Signal inventory, corrected dashboard/alert rules, evidence of redaction and alert exercise, and linked operator runbooks.

## Features explicitly not added

- No tracing stack, hosted observability platform, or monitoring microservice unless a specific unresolved diagnostic problem justifies it.

## Risk

MEDIUM. Missing or noisy signals delay recovery; public diagnostic exposure leaks sensitive information.

## Depends On

- Phases 1–3.

## Unlocks

- Phase 5 recovery actions and target operational qualification.

## Historical Prompt 2 evidence — 2026-10-14

- Added/verified the low-cardinality inventory in [the monitoring runbook](../../operations/MONITORING.md): HTTP defaults, bounded rate classes, Hikari/DB readiness, Redis availability and tracking outcomes, dispatch/offer outcomes, incident age, outbox pending/failed/age, quarantine, and SSE active/connection outcomes. Kafka consumer lag is not exported; backup/certificate signals remain future work pending exporters.
- Added DB-backed outbox gauges to the 15-second operational snapshot so backlog remains visible while the publisher is paused. Added the database readiness alert and Prometheus fire/recovery rule cases for database loss, backlog, 5xx and quarantine.
- Liveness is process-only; readiness requires application state and PostgreSQL. Redis/Kafka/Keycloak are omitted from readiness to avoid restart loops; affected operations fail closed or report bounded stale/unavailable outcomes as described in the runbook.
- Added a structured request-log test with synthetic tokens, passwords, client secret, address, GPS, polygon and incident text. It asserts the log contains only the matched route template among request attributes.
- `CorrelationIdFilterTests`, `TrackingHubBoundsTests`, `OperationalMetersTests` and the pure JVM group passed (15 tests). Prometheus 3.15.0 `promtool check rules` found all 9 rules valid and `promtool test rules` passed fire/recovery cases for database readiness, outbox age, sustained application 5xx and quarantine. This is offline rule evaluation, not Alertmanager delivery.
- The Docker-backed PostgreSQL health transition test and live dashboard scrape remain unverified because Testcontainers could not reach Docker Desktop's named-pipe endpoint. Status remains `IN_PROGRESS` pending those runtime checks.


## Consolidation audit — 2026-10-14

Actual private scrape includes HTTP histograms, pool/DB/Redis, outbox, quarantine, incident age, SSE and native Kafka fetch lag. Fifteen dashboard panels reference concrete sources; all 26 PromQL queries parse and every metric name maps to the live scrape or actual source registration. Nine alert rules validate and firing/recovery fixtures pass. PostgreSQL readiness and optional-dependency restart behavior execute. Application/proxy redaction passes, including real logout-token canaries. Target notification destination/delivery, backup sentinel integration and certificate probe remain external/later gates.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
