# Local monitoring and alert runbook

**Scope:** Phase 11 synthetic local demo. The private scrape path, provisioning APIs, dependency/readiness alerts, and a signed outbox backlog drill were exercised on 2026-10-07; see [observability evidence](PHASE11_OBSERVABILITY_EVIDENCE.md). This is not a public production monitoring deployment.

## Dashboard design decision

The operator question is “is a customer command working, and if not, is the cause the API, a dependency, or delayed derived work?” The reference lock uses Grafana's native dark operator dashboard as the primary surface and [Grafana's dashboard guidance](https://grafana.com/docs/grafana/latest/visualizations/dashboards/build-dashboards/best-practices/) for a general-to-specific order. The bundled Refero craft color guide supplies the narrow secondary rule: neutrals carry most information; warning and danger colors signal thresholds only. Live Refero research was unavailable because its connector returned `NO_SUBSCRIPTION`.

The first row shows six small status numbers: scrape, PostgreSQL, Redis, oldest pending outbox age, failed outbox rows, and active SSE streams. Lower rows show API symptoms, outbox/consumer progress, then offer/recovery and incident queues. Each panel has a description naming its source meter or PromQL. No order IDs, actor IDs, coordinates, or exception text appear in metric labels. Grafana's own theme, type, and keyboard behavior are preserved; this dashboard adds no custom decorative palette or imagery.

## Start and access

1. Put a private `GRAFANA_ADMIN_USER` and `GRAFANA_SECRET_FILE=.env.grafana-credential` in ignored `.env`, alongside the existing local stack values. Create `.env.grafana-credential` as an ignored file containing a long random password, with access restricted to the operator. Grafana reads it through `/run/secrets/grafana_admin_credential` using its documented `GF_SECURITY_ADMIN_PASSWORD__FILE` setting. Do not put the password itself in `.env` or `.env.example`.
2. Inspect cached images before any pull. The optional overlay uses the cached, digest-pinned `prom/prometheus:v3.15.0` and `grafana/grafana:13.1.6` images. Pull only an unavailable image, and recheck the digest after any intentional version change.
3. Start **one** isolated Compose project with `docker compose --env-file .env -f compose.yaml -f compose.monitoring.yaml --profile monitoring -p fleetflow-phase11-demo up -d --build --pull never`. Do not point this project at an existing data volume. The overlay changes only this project's app to `prod,monitoring` and moves its Actuator endpoints to container port `9095`.
4. Grafana is reachable only at `http://127.0.0.1:${GRAFANA_PORT:-3000}` on the host. Prometheus and the management port have no published host port. Log in with the private Grafana credentials. Open **FleetFlow / local operations** in the FleetFlow folder.
5. For a clean teardown of only this disposable project, stop it with `docker compose --env-file .env -f compose.yaml -f compose.monitoring.yaml --profile monitoring -p fleetflow-phase11-demo down`. Preserve volumes unless this project's identity, path, and disposable status have been verified explicitly. Never issue a `down --volumes` against the normal `fleetflow` project.

For optional Discord delivery, create a webhook for a private operator channel and save its URL in a private file. On the tested Windows Docker Desktop host, binding a file directly from `%LOCALAPPDATA%` made the container see a directory, so copy it to a Git-ignored `.env.discord-webhook` file in the repository root and verify `git check-ignore .env.discord-webhook`. Do not paste the URL into Compose, an environment variable, logs, or Git. Set `$env:DISCORD_WEBHOOK_FILE='.env.discord-webhook'`, then include `-f compose.alerting.yaml` after `compose.monitoring.yaml` in the same Compose project and use `--profile monitoring`. This adds private Alertmanager and points Prometheus at it; no Alertmanager host port is published. The webhook is mounted as a file secret at `/run/secrets/discord_webhook_url`. The base monitoring overlay remains usable without a Discord destination.

Validate the optional configuration with `docker compose ... -f compose.monitoring.yaml -f compose.alerting.yaml config --quiet`, then run `promtool check config /etc/prometheus/prometheus-alerting.yml` inside the running Prometheus container and `amtool check-config /etc/alertmanager/alertmanager.yml` inside the running Alertmanager container. To exercise delivery on a **disposable** project, stop only its app, wait for `FleetFlowReadinessUnobservable` to fire, check Alertmanager's Discord notification counters, restart the app, and confirm alert resolution. HTTP success and counters prove the Discord API accepted delivery; an operator must also confirm the intended channel displays the message. `Invoke-Phase11ScheduledBackup.ps1` uses the same private URL file for a generic backup-failure notification and keeps a failed backup nonzero.

The Prometheus scrape endpoint is anonymous **only within the `monitoring` profile** and is intended for the private, unexposed Compose management port. Business routes still require Keycloak-signed OIDC and PostgreSQL scope checks. The normal app profile exposes no Prometheus endpoint; `/actuator/metrics` continues to require `DISPATCHER`. The optional overlay must not be used on an internet-facing host without a separate network and authentication review.

## Signal map

| Operator question | Meter/PromQL source | Expected interpretation |
| --- | --- | --- |
| Is the app scrape reachable? | `up{job="fleetflow"}` | `1` means the private management endpoint answered; it is not proof that every business dependency works. |
| Can the database and Redis answer? | `fleetflow_operations_database_available`, `fleetflow_operations_redis_available` | Refreshed every 15 seconds. `0` means a failed probe; the DB-dependent queue gauges become `-1` when PostgreSQL is unavailable. |
| Are requests healthy? | `http_server_requests_seconds_count`, `http_server_requests_seconds_bucket` | Business route 5xx/s and five-minute p95. The histogram is enabled only in the monitoring profile. Exclude expected `409` and `429` from the 5xx symptom panel. |
| Is durable async work draining? | `fleetflow_outbox_pending`, `fleetflow_outbox_failed`, `fleetflow_outbox_oldest_seconds`, `fleetflow_outbox_publish_total` | Pending/failed/age come from the existing publisher sweep. The new publish counter records actual acknowledged publications and bounded failures, not business commits. |
| Are consumers making effects? | `fleetflow_events_projected_total`, `fleetflow_events_quarantined_rows` | Fixed labels `consumer` and `outcome`; processed effects count only after their PostgreSQL transaction commits. Quarantined rows need operator review. |
| Are recovery and tracking bounded? | `fleetflow_offers_overdue`, `fleetflow_recovery_due`, `fleetflow_incidents_open`, `fleetflow_tracking_active` | The first three are durable snapshots; active streams are in-process and reset on restart. |

`X-Correlation-ID` is validated or generated for HTTP requests. JSON application logs carry it through the synchronous command and after-commit outbox insertion. Search the committed `eventId` and `orderId` in the `outbox.committed`, `outbox.published`, and `event.projected` records to follow asynchronous work. They are safe identifiers in logs, never metric labels. A Kafka outage can delay publication while leaving the business command committed.

## Alert exercises and operator actions

Every rule is owned by the local operator and has a bounded evaluation window. In the base local demo, alerts appear in Prometheus/Grafana. The optional Alertmanager overlay can send them to Discord. Capture the transition from pending to firing and back to inactive during the Phase 11 drill before marking an alert verified.

### Outbox backlog

`FleetFlowOutboxBacklog` fires after pending age exceeds 30 seconds for one minute; `FleetFlowOutboxFailed` fires after a failed row exists for 30 seconds. Stop Kafka in the **disposable** project, commit a business transition, and observe pending/age increase. Restore Kafka, confirm publication and one notification/analytics effect per event, then confirm the alert resolves. Inspect `outbox_events` status/attempts/last_error and the publisher logs. Never delete pending or failed rows just to clear the alert.

### Dependency outage

`FleetFlowDependencyUnavailable` fires when the PostgreSQL or Redis probe is `0` for 30 seconds. Stop one dependency only in the disposable project and verify expected readiness/business behavior. Restore it and require new driver location fixes after Redis loss. Keep liveness separate from readiness so a recoverable dependency outage does not restart-loop the app.

### Readiness and scrape

`FleetFlowReadinessUnobservable` fires when Prometheus cannot scrape the app for 30 seconds. Check the app healthcheck at the private management port, then inspect app logs, the Compose network, and Prometheus target status. A lost scrape is not by itself proof of a business outage. The Phase 11 drill must check `/actuator/health/readiness` separately and record whether it agrees with the dependency probes.

## Known limits

The dashboard does not directly test a Keycloak login or JWKS rotation; signed OIDC smoke and invalid/expired token tests provide separate identity evidence. Local Prometheus stores seven days of synthetic metrics in its own disposable volume. Grafana's account store is a local demo volume. Neither is a durable business backup. The signed backlog transition was exercised, but browser rendering of every dashboard panel remains unverified. On 2026-10-08, a real stopped-app readiness alert fired and resolved through the optional Alertmanager overlay; its Discord counter recorded two attempts with zero failures after the secret mount was corrected. A deliberately missing backup key produced a nonzero wrapper exit and a successful Discord HTTP response. The operator subsequently supplied a Discord channel screenshot showing the `FIRING` and `RESOLVED` readiness cards and backup `FAILED` notices. A private copy is `%LOCALAPPDATA%\FleetFlowPhase11\evidence\discord-operator-visible-20261008.png`; the receiver-visibility gate is now verified for these messages. A later read of the running Prometheus `/api/v1/alerts` returned an empty alert list, so the screenshot's older firing cards are not evidence of an alert still firing. On 2026-10-09, the actual 02:00 daily backup failed before archive creation; the operator supplied a Discord screenshot of the corresponding failure notice. The private incident screenshot is `%LOCALAPPDATA%\FleetFlowPhase11\evidence\daily-backup-failure-20261009-0200.png`. A repaired manual task rerun succeeded, but the next scheduled trigger remains unobserved.
