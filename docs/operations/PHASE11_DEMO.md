# Phase 11 synthetic local demo

This guide reproduces the 2026-10-07 Phase 11 exercise on a **new, disposable** Compose project. It uses real Keycloak-signed synthetic actors, PostgreSQL/PostGIS, Redis, Kafka, the local fixture profile, the `prod,monitoring` app overlay, and an encrypted backup. It is not a public deployment guide. Never point these commands at an existing developer project or remove its volumes.

## Preparation and clean startup

Install Java 21, PowerShell 7, Python 3, Docker Compose, and the Maven wrapper prerequisites. Inspect cached pinned images before pulling a missing image. Copy `.env.example` to the ignored `.env` and fill every required private value. In particular set `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_URL`, `OIDC_ISSUER`, `OIDC_JWKS_URI`, `OIDC_AUDIENCE`, `KC_ADMIN_USER`, `KC_ADMIN_PASSWORD`, `GRAFANA_ADMIN_USER`, `GRAFANA_SECRET_FILE`, and loopback ports. Place the Grafana credential in the ignored file named by `GRAFANA_SECRET_FILE`. The `.env` issuer and JWKS URI must refer to the same local Keycloak realm. Keep all values, tokens, and backup keys outside Git and terminal logs.

Use a new project name, such as `fleetflow-phase11-demo`, after checking that it has no existing containers or volumes. From the repository root:

```powershell
docker volume ls --filter label=com.docker.compose.project=fleetflow-phase11-demo
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml up -d --no-build --pull never postgres redis kafka keycloak
./scripts/Run-Local.ps1
```

Run `Run-Local.ps1` in a second terminal and wait for its readiness endpoint to return 200. The local profile permits fixture administration and synthetic order intake only on loopback. The smoke script creates enabled Keycloak users with `@example.invalid` email, maps them to PostgreSQL actors, and obtains signed tokens; no static bearer token or fixture identity header is accepted. In a third terminal, run:

```powershell
./scripts/Phase6-Local-Smoke.ps1 -IncludePhase7 -IncludePhase8 -IncludePhase9 -IncludePhase10 -IncludePhase11 -ExerciseBrokerOutage -EnvironmentFile .env -ComposeProject fleetflow-phase11-demo
```

The script asserts zone denial, serviceable order and fresh candidate ranking, guarded offer/acceptance and live customer tracking/SSE, pickup/transit/completion, rejection with the next driver, timeout recovery, stale-driver exclusion, a 201/409 concurrent offer race, failed delivery with a dispatcher-visible incident, Kafka outage with eventual outbox projections, and Redis outage with hidden position followed by a fresh fix. It also asserts foreign-order privacy and role boundaries inherited from the earlier phase smoke. Its final line must say `Phase 11 signed demo scenarios passed`. Inspect the signed smoke output for the scenario markers, without saving tokens.

Stop the host `Run-Local.ps1` process. Start the Compose app and monitoring overlay on the **same** project and volumes:

```powershell
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml -f compose.monitoring.yaml --profile monitoring up -d --build --pull never app prometheus grafana
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml -f compose.monitoring.yaml --profile monitoring ps
```

Require the app and dependency health checks to pass. Prometheus must show the `fleetflow` scrape target `up`; Grafana at `http://127.0.0.1:${GRAFANA_PORT:-3000}` must contain **FleetFlow / local operations**. Inspect the scrape, database, Redis, HTTP, outbox, consumer, and SSE panels. The monitoring and alert commands and expected transitions are in [MONITORING.md](MONITORING.md). Stop Kafka in this disposable project, commit a signed order transition (the signed verifier can use a synthetic `CONFIRMED` order), wait for `FleetFlowOutboxBacklog` to fire, restart Kafka, and require a published event, one notification effect, analytics effect, and alert recovery. Do not delete the pending event to make the alert clear.

With signed traffic present and the private Grafana credential file configured, run `./scripts/Test-Phase11-Dashboard.ps1 -EnvironmentFile .env -RequireSamples`. It checks every dashboard query through Grafana's datasource API without displaying credentials. Also inspect the panels in a browser where one is available; API query success alone does not establish browser rendering.

## Backup, load, and recovery

Create a 32-byte key outside the repository as shown in [BACKUP_RESTORE.md](BACKUP_RESTORE.md). While the app and Keycloak are running, take a quiesced encrypted backup:

```powershell
./scripts/Backup-FleetFlow.ps1 -Project fleetflow-phase11-demo -EnvironmentFile .env `
  -Destination (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backups') `
  -KeyFile (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backup-key.bin') `
  -RemoteDestination 'gdrive:FleetFlow-Backups' -RetentionDays 7
```

`-RemoteDestination` requires an already authorized remote and fails the command if upload, independent listing, retrieval, or hash verification fails. A local-only backup can omit that argument, but cannot prove off-host recovery. The 2026-10-07 remote attempt failed with `invalid_grant`; see the evidence record before claiming completion.

With an app running against populated synthetic data, the bounded workload is:

```powershell
python scripts/Phase11-Load.py --project fleetflow-phase11-demo --env-file .env --duration-seconds 600
```

The script samples five signed customer/driver reads per second, one signed heartbeat per second, and two signed fixes every ten seconds through at most 12 worker threads. It requires one existing customer order and two enabled synthetic drivers. It exits nonzero on an unexpected error rate of at least 1%, any 5xx, read p95 of at least 1 second, or command p95 of at least 2 seconds. Inspect its JSON output and repeat readiness plus outbox/assignment invariant checks after the run. It does not simulate ten drivers or ten concurrent SSE streams.

Stop only this project, preserving volumes, then restore the selected encrypted archive into a **different, empty** project. Use a remotely retrieved file if off-host recovery is the gate being tested:

```powershell
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml -f compose.monitoring.yaml --profile monitoring stop
./scripts/Restore-FleetFlow.ps1 -Archive '<absolute path to encrypted archive>' `
  -KeyFile (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backup-key.bin') `
  -TargetProject fleetflow-phase11-demo-restore -EnvironmentFile .env
```

The restore refuses a nonempty target, checks image IDs and hashes, validates app and Keycloak readiness, matches the restored identity, then performs a signed read of the pre-backup `CONFIRMED` order and a signed cancellation with a PostgreSQL result check. Its final timestamps distinguish service readiness from signed functional recovery. Inspect the twelve successful Flyway migrations, PostGIS version, outbox state, and projections. The [recovery record](BACKUP_RESTORE.md) gives measured RPO/RTO and the pending replay limitation.

When finished, stop only the named synthetic projects with `docker compose ... down` **without** `--volumes`. Preserve source and restore volumes for review unless their disposable identity has been verified and their removal separately intended. Keep `.env`, the key, local archives, and generated logs out of the repository.
