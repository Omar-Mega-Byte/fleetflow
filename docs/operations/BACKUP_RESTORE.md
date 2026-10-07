# Local synthetic backup and restore

**Target:** the single-workstation [Phase 11 local demo](PHASE11_BASELINE.md). The procedure preserves PostgreSQL/PostGIS business state, Flyway history, and the stopped Keycloak development data volume containing users and credentials. The versioned realm import alone is not an identity backup. Redis fixes/heartbeats are intentionally disposable. Kafka volumes and offsets are not in the archive; only unpublished outbox rows can be replayed safely, with PostgreSQL processed-event markers preventing repeated derived effects within the retained marker window. A copy of the encrypted archive to a genuinely separate host or service is required before making a host-loss recovery claim.

## Capture and custody

`Backup-FleetFlow.ps1` requires PowerShell 7 and the pinned Compose project. It checks that the app, PostgreSQL, and Keycloak are running, stops the single app and Keycloak, runs PostgreSQL 17 `pg_dump -Fc`, verifies the dump table of contents with `pg_restore -l`, snapshots the stopped Keycloak volume with `tar`, hashes both payloads, writes a manifest with image IDs and tool version, creates a ZIP, encrypts it with AES-256-GCM, and decrypts it once to verify byte-for-byte integrity. The script restarts Keycloak and the app in `finally`. This is a **quiesced backup**, with brief local-demo downtime. Direct operator SQL or Keycloak writes must also be paused during capture. The entire ZIP is held in memory for encryption; archives larger than 512 MiB fail closed and need a streaming design before use at larger scale.

Keep a 32-byte random key **outside the repository and outside the archive destination**, with access limited to the backup operator. The key is essential for restore and needs separate protected escrow. Do not put its bytes in `.env`, logs, CI, or Git. On Windows, one way to create a new key is:

```powershell
$backupHome = Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11'
New-Item -ItemType Directory -Path $backupHome -Force | Out-Null
$keyPath = Join-Path $backupHome 'backup-key.bin'
if (Test-Path -LiteralPath $keyPath) { throw 'Key already exists; preserve it.' }
$key = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($key)
[IO.File]::WriteAllBytes($keyPath, $key)
[Array]::Clear($key, 0, $key.Length)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $keyPath /inheritance:r /grant:r "${identity}:(F)"
```

From the repository root, with a private ignored `.env` file configured, run:

```powershell
./scripts/Backup-FleetFlow.ps1 -Project fleetflow-phase11-demo `
  -EnvironmentFile .env -Destination (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backups') `
  -KeyFile (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backup-key.bin') `
  -CopyDestination 'X:\FleetFlowBackups' -RetentionDays 7
```

`-CopyDestination` is optional for a workstation-only exercise. A local path or another directory on the same disk is **not** an off-host copy. The script compares SHA-256 after copying and keeps only its own `fleetflow-<project>-*.ffbackup` files for seven days in the configured destinations. The backup file and any copied archive must remain outside Git. Exit code is nonzero on failure; Task Scheduler history or an external monitor must inspect it. There is no configured pager or verified off-host destination in this repository.

For a daily 02:00 local schedule, register a Windows Scheduled Task under the backup operator account with the PowerShell 7 executable and the same arguments. Run it only while the named project is expected to be available. Configure Task Scheduler to retain task history and inspect `LastTaskResult` and archive age each morning; this repository does not claim an installed schedule or automatic failure notification. The initial target is one backup per day, seven-day retention, desired RPO 24 hours and desired RTO 60 minutes, subject to actual restore proof.

## Fresh-project restore rehearsal

The restore script refuses a target project if Docker already has its containers or volumes. It decrypts and checks both payload hashes before creating anything, then starts only a fresh PostgreSQL project, checks the exact database and Keycloak image IDs, restores the custom PostgreSQL archive, creates a fresh Keycloak volume, restores its stopped data, starts the remaining services, and waits for app readiness. It never runs `down --volumes` and retains a failed target for inspection. Use the **same** loopback issuer and host ports; stop the source project first to release those ports, without removing its volumes.

```powershell
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml stop
./scripts/Restore-FleetFlow.ps1 -Archive 'X:\FleetFlowBackups\fleetflow-fleetflow-phase11-demo-YYYYMMDDTHHMMSSZ.ffbackup' `
  -KeyFile (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backup-key.bin') `
  -TargetProject fleetflow-phase11-restore -EnvironmentFile .env
```

Then check `flyway_schema_history` has twelve successful migrations and checksum validation in app startup, `postgis_full_version()`, actor issuer/subject links to restored Keycloak users, branch memberships, representative orders/zones/assignments/incidents/outbox rows, and one **Keycloak-signed** read and business command. Reinspect `processed_events`, `notifications`, and `analytics_daily_order_transitions` around any replay; a restored Kafka broker starts without the source offsets, so `PUBLISHED` events are not automatically republished. A `PENDING` event may publish at least once and must be deduplicated by the restored markers. Do not delete pending outbox rows. Send new driver fixes before relying on any location or tracking view.

Record archive timestamp, start and finish of restore, signed operation time, observed latest data present, and any data-loss interval. Readiness alone is not the RTO endpoint: include identity and a signed command. Preserve source and target project volumes for review. A clean teardown of only the verified disposable target is `docker compose --env-file .env -p fleetflow-phase11-restore -f compose.yaml down` **without** `--volumes`; restart the source with its original Compose files and profile after the rehearsal.

## Current proof and open gates

On 2026-10-07, AES-GCM round trip and tamper rejection passed. An initial empty synthetic archive verified the crypto path. A second, populated archive was taken from the isolated `fleetflow-phase11-monitoring` project at `2026-10-07T14:42:17.4211179Z`: `C:\Users\Tolis\AppData\Local\FleetFlowPhase11\backups\fleetflow-fleetflow-phase11-monitoring-20261007T144206Z.ffbackup` (SHA-256 `7902747CB746F2F5BD53642698BF84D6C632A2AF2ACE077D7854050D18777ACD`). The source project and volumes were preserved; no second host copy was configured.

The first fresh target, `fleetflow-phase11-restore`, stopped during PostGIS initialization because the script treated the temporary initdb server as the final ready server. The script was corrected to wait for the init-complete log before `pg_restore`. A second fresh target, `fleetflow-phase11-restore2`, restored PostgreSQL, then stopped when `tar` attempted to overwrite the read-only, versioned realm import. The script was corrected to exclude that import. The Keycloak data archive was then extracted manually into this isolated target, and its remaining services were started. **The corrected restore script has not yet passed uninterrupted end to end**; this is a valid independent data/identity restore with a documented manual recovery step, not a claim that automation is proven.

The restored database matched the backed-up snapshot for actors, memberships, orders, zones, assignments, incidents, outbox, processed markers, notifications, and Flyway rows: `9, 2, 5, 2, 2, 2, 26, 52, 18, 12` respectively. The target reported PostgreSQL 17.11 and PostGIS 3.5.7. Application startup validated all 12 Flyway migrations without checksum repair; readiness returned 200. `Verify-Restored-FleetFlow.ps1` confirmed a restored Keycloak subject matched the PostgreSQL actor, obtained a Keycloak-signed user token, read a restored order with HTTP 200, cancelled it with HTTP 200, and confirmed `CANCELLED` in PostgreSQL. The script resets only that synthetic user's password in the disposable target. After the command, outbox/processed/notification counts rose to `27/54/19`; the new order-status event was published and had two processed markers. This proves publication of a **new** post-restore event. Replay of a pre-existing `PENDING` event and duplicate suppression during restore remain unproven.

The backup snapshot had no observed loss during this rehearsal. A true host-loss RPO was not measured because the archive remained on the same workstation. Restore start/end and signed-operation timestamps were not captured precisely enough to claim measured RTO; the 60-minute target remains unverified. A daily scheduled task, off-host copy, failure alert, and unattended restore run remain open. Neither target used `down --volumes`; the original source volumes remain intact.
