# Phase 11 observability exercise record

**2026-10-07 UTC; isolated project `fleetflow-phase11-monitoring`; synthetic empty database.** This is evidence for the monitoring implementation on the current Phase 11 review branch, not a complete Phase 11 acceptance record. The project used the digest-pinned images in `compose.yaml` and `compose.monitoring.yaml`. Docker Engine was 29.6.2. No normal developer volume was used or removed.

| Exercise | Trigger and observed result | Recovery and limit |
| --- | --- | --- |
| Private metrics scrape | `docker compose ... --profile monitoring up -d --no-build --pull never`; in-container `GET app:9095/actuator/prometheus` exposed database/Redis gauges; Prometheus target API reported `health: up`. Host business port `GET /actuator/prometheus` returned 404. | Provisioned Grafana folder and `fleetflow-local-ops` dashboard were present through the authenticated Grafana API. Dashboard panel rendering in a browser is not yet verified. |
| Rule validity | `promtool check config /etc/prometheus/prometheus.yml` succeeded and loaded four alert rules. | The rules are local Prometheus alerts. No external notification receiver is configured. |
| Redis dependency outage | Stopped only the isolated project's Redis container. The `fleetflow_operations_redis_available` gauge changed from 1 to 0, readiness returned HTTP 503, and `FleetFlowDependencyUnavailable` advanced from pending to firing after its 30 second window. Liveness was not made dependent on Redis. | Restarted that Redis container. Readiness returned HTTP 200, the gauge returned to 1, and the alert list became empty. No driver fixes existed in this empty dataset, so fresh-fix behavior remains unverified. |
| Scrape loss and app restart | Stopped only the isolated app container. `FleetFlowReadinessUnobservable` fired after the 30 second scrape-loss window. | Restarted the app. Readiness returned HTTP 200 and the Prometheus alert list became empty after the next scrape/evaluation. |

`./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --no-transfer-progress` passed 120 tests, 0 failures, 0 errors, 0 skipped before the final documentation-only edits to this slice. The signed command → outbox → Kafka → projection trace and outbox backlog alert have not yet been exercised. Those remain open Phase 11 gates.
