# Local synthetic backup and restore

**Target:** the single-workstation [Phase 11 local demo](PHASE11_BASELINE.md). The procedure preserves PostgreSQL/PostGIS business state, Flyway history, and the stopped Keycloak development data volume containing users and credentials. The versioned realm import alone is not an identity backup. Redis fixes/heartbeats are intentionally disposable. Kafka volumes and offsets are not in the archive; only unpublished outbox rows can be replayed safely, with PostgreSQL processed-event markers preventing repeated derived effects within the retained marker window. A copy of the encrypted archive to a genuinely separate host or service is required before making a host-loss recovery claim.

## Capture and custody

`Backup-FleetFlow.ps1` requires PowerShell 7 and the pinned Compose project. It checks that the app, PostgreSQL, and Keycloak are running, stops the single app and Keycloak, runs PostgreSQL 17 `pg_dump -Fc`, verifies the dump table of contents with `pg_restore -l`, snapshots the stopped Keycloak volume with `tar`, hashes both payloads, writes a manifest with image IDs and tool version, creates a ZIP, encrypts it with AES-256-GCM, and decrypts it once to verify byte-for-byte integrity. The script restarts Keycloak and the app in `finally`. This is a **quiesced backup**, with brief local-demo downtime. Direct operator SQL or Keycloak writes must also be paused during capture. The entire ZIP is held in memory for encryption; archives larger than 512 MiB fail closed and need a streaming design before use at larger scale. `Test-Phase11-Recovery-Preflight.ps1` checks the AES-GCM round trip and tamper rejection without Docker.

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
  -RemoteDestination 'gdrive:FleetFlow-Backups' -RetentionDays 7
```

`-CopyDestination` is optional for an additional local copy; a local path or another directory on the same disk is **not** an off-host copy. `-RemoteDestination` requires a configured named rclone remote and a dedicated folder. The script uploads the encrypted file, lists the remote folder, retrieves the remote file independently into scratch space, and compares SHA-256 with the local encrypted artifact. An upload, listing, retrieval, or checksum failure returns a nonzero exit code, and the source services are restarted. Local and local-copy retention removes only the script's named archives after seven days; **remote retention is not yet automated**. The archive and rclone credentials must remain outside Git. Task Scheduler history or an external monitor must inspect the exit code. There is no configured pager or verified off-host destination in this repository.

For a daily 02:00 local schedule, register a Windows Scheduled Task under the backup operator account with the PowerShell 7 executable and the same arguments. Run it only while the named project is expected to be available. Configure Task Scheduler to retain task history and inspect `LastTaskResult` and archive age each morning; this repository does not claim an installed schedule or automatic failure notification. The initial target is one backup per day, seven-day retention, desired RPO 24 hours and desired RTO 60 minutes, subject to actual restore proof.

## Fresh-project restore rehearsal

The restore script refuses a target project if Docker already has its containers or volumes. It checks required private configuration, decrypts and checks both payload hashes before creating anything, then starts only a fresh PostgreSQL project, checks the exact database and Keycloak image IDs, restores the custom PostgreSQL archive, creates a fresh Keycloak volume, restores its stopped data, starts the remaining services, and waits for app and Keycloak readiness. By default it then verifies a restored synthetic actor against Keycloak, performs a signed order read requiring the pre-backup `CONFIRMED` state, performs a signed cancellation, and confirms the database result. Any failure returns nonzero and retains the target for inspection. It never runs `down --volumes`. Use the **same** loopback issuer and host ports; stop the source project first to release those ports, without removing its volumes. `-NoStartApp` is for database inspection only and cannot count as functional recovery.

```powershell
docker compose --env-file .env -p fleetflow-phase11-demo -f compose.yaml stop
./scripts/Restore-FleetFlow.ps1 -Archive 'X:\FleetFlowBackups\fleetflow-fleetflow-phase11-demo-YYYYMMDDTHHMMSSZ.ffbackup' `
  -KeyFile (Join-Path $env:LOCALAPPDATA 'FleetFlowPhase11/backup-key.bin') `
  -TargetProject fleetflow-phase11-restore -EnvironmentFile .env
```

Also check `flyway_schema_history` has twelve successful migrations and checksum validation in app startup, `postgis_full_version()`, actor issuer/subject links to restored Keycloak users, branch memberships, representative orders/zones/assignments/incidents/outbox rows. Reinspect `processed_events`, `notifications`, and `analytics_daily_order_transitions` around any replay; a restored Kafka broker starts without the source offsets, so `PUBLISHED` events are not automatically republished. A `PENDING` event may publish at least once and must be deduplicated by the restored markers. Do not delete pending outbox rows. Send new driver fixes before relying on any location or tracking view.

Record archive timestamp, start and finish of restore, signed operation time, observed latest data present, and any data-loss interval. Readiness alone is not the RTO endpoint: include identity and a signed command. Preserve source and target project volumes for review. A clean teardown of only the verified disposable target is `docker compose --env-file .env -p fleetflow-phase11-restore -f compose.yaml down` **without** `--volumes`; restart the source with its original Compose files and profile after the rehearsal.

## Historical partial proof and open gates

On 2026-10-07, AES-GCM round trip and tamper rejection passed. An initial empty synthetic archive verified the crypto path. A second, populated archive was taken from the isolated `fleetflow-phase11-monitoring` project at `2026-10-07T14:42:17.4211179Z`: `C:\Users\Tolis\AppData\Local\FleetFlowPhase11\backups\fleetflow-fleetflow-phase11-monitoring-20261007T144206Z.ffbackup` (SHA-256 `7902747CB746F2F5BD53642698BF84D6C632A2AF2ACE077D7854050D18777ACD`). The source project and volumes were preserved; no second host copy was configured.

The first fresh target, `fleetflow-phase11-restore`, stopped during PostGIS initialization because the script treated the temporary initdb server as the final ready server. The script was corrected to wait for the init-complete log before `pg_restore`. A second fresh target, `fleetflow-phase11-restore2`, restored PostgreSQL, then stopped when `tar` attempted to overwrite the read-only, versioned realm import. The script was corrected to exclude that import. The Keycloak data archive was then extracted manually into this isolated target, and its remaining services were started. **The corrected restore script has not yet passed uninterrupted end to end**; this is a valid independent data/identity restore with a documented manual recovery step, not a claim that automation is proven.

The restored database matched the backed-up snapshot for actors, memberships, orders, zones, assignments, incidents, outbox, processed markers, notifications, and Flyway rows: `9, 2, 5, 2, 2, 2, 26, 52, 18, 12` respectively. The target reported PostgreSQL 17.11 and PostGIS 3.5.7. Application startup validated all 12 Flyway migrations without checksum repair; readiness returned 200. `Verify-Restored-FleetFlow.ps1` confirmed a restored Keycloak subject matched the PostgreSQL actor, obtained a Keycloak-signed user token, read a restored order with HTTP 200, cancelled it with HTTP 200, and confirmed `CANCELLED` in PostgreSQL. The script resets only that synthetic user's password in the disposable target. After the command, outbox/processed/notification counts rose to `27/54/19`; the new order-status event was published and had two processed markers. This proves publication of a **new** post-restore event. Replay of a pre-existing `PENDING` event and duplicate suppression during restore remain unproven.

The backup snapshot had no observed loss during this earlier rehearsal. A true host-loss RPO was not measured because the archive remained on the same workstation. Restore start/end and signed-operation timestamps were not captured precisely enough to claim measured RTO at that time. Neither target used `down --volumes`; the original source volumes remain intact.

## New controlled recovery exercise, 2026-10-07 UTC

The source was the synthetic `fleetflow-phase11-monitoring` project. A known `CONFIRMED` order (`24e9cb5f-b78c-43d6-a088-97c0edb1ce51`) had its application `updated_at` marker set to `2026-10-07T18:54:39.220843Z` immediately before backup. `Backup-FleetFlow.ps1` generated `fleetflow-fleetflow-phase11-monitoring-20261007T185448Z.ffbackup` and passed its local AES-GCM decrypt/hash verification. The encrypted archive SHA-256 was `962724DD68B6705A560A9C69FC9F92F453468F6A45453A695412367A20EA6639`. Its manifest timestamp was `2026-10-07T18:54:55.6075239Z`. A subsequent **Keycloak-signed** read and cancellation changed the source order to `CANCELLED` at `18:55:39.450673Z`; the source project was stopped without removing volumes, with a recorded recovery test failure timestamp of `18:56:06.9786678Z`.

Two separate fresh targets passed the corrected script without prompts or manual edits. The second, `fleetflow-phase11-unattended2`, began restore at `18:58:02.7619515Z`, had app service readiness at `18:58:46.2457336Z`, and completed the signed read, signed cancellation, and PostgreSQL state check at `18:58:52.2574647Z`: **measured RTO 49.5 seconds** for this local exercise. The signed read explicitly saw the backed-up `CONFIRMED` status, proving the later source cancellation was absent. The latest known recoverable application marker was 87.8 seconds before the recorded source stop: **measured RPO 87.8 seconds** for this controlled quiesced-backup test. PostgreSQL reported PostGIS 3.5.7; app startup validated all 12 Flyway migrations. This does not establish host-loss recovery or an unattended schedule.

The new remote-copy path was exercised against the only configured rclone remote, `gdrive:FleetFlow-Backups`. `rclone` returned `invalid_grant` while refreshing its token; `Backup-FleetFlow.ps1` raised `Remote backup upload failed`, returned nonzero, and restarted Keycloak and the app. No off-host artifact was uploaded or retrieved. The operator must reconnect that remote (or configure another authorized off-host destination) and rerun upload, independent listing, retrieval/hash validation, and preferably a restore from the retrieved archive. Keep the Phase 11 off-host gate open. The daily schedule, remote retention, backup failure notification, and restored pre-existing pending-event replay also remain open.

### Integrated demo archive and unattended recovery

The clean `fleetflow-phase11-demo` project produced a second encrypted archive, `fleetflow-fleetflow-phase11-demo-20261007T191825Z.ffbackup`, with manifest timestamp `2026-10-07T19:18:31.2463631+00:00` and SHA-256 `39696A287E5B52F7C7EBA77F399E57D21448A59941B8C83996A8BA721C5910A3`. The backup script's local decrypt/hash validation succeeded and the source app recovered after the quiesced capture. After a later signed cancellation and Kafka backlog drill, the source Compose overlay was stopped without removing volumes. A new empty `fleetflow-phase11-demo-restore` target then ran the corrected restore script without prompts or manual changes. Restore began `19:24:40.7200048Z`, service readiness was observed `19:25:18.4880659Z`, and the signed read plus cancellation and PostgreSQL check finished `19:25:24.9324101Z`: **44.2 seconds to functional recovery**. The signed read saw the archived `CONFIRMED` state for order `21c2a92b-517f-4fe4-85f1-226cccd9d83a`, while the source had been cancelled after the backup. This archive was local; it was not retrieved from the failed remote.

The remote still returned `invalid_grant` on a read-only `rclone lsf gdrive:` check after this rehearsal. Until it can be independently listed, retrieved, hashed, and restored, the off-host and host-loss gates remain open.

## Pending-event replay across a fresh restore, 2026-10-08 UTC

The disposable `fleetflow-phase11-demo` source had Kafka stopped before a Keycloak-signed cancellation. Its new outbox event `fd238091-dc7a-4dfd-9da1-767a699599ca` remained `PENDING` with no processed markers or notification. For a deduplication check, the already published event `542600c1-78ee-456a-b5cf-abaf85667d87` was reset to `PENDING` in this synthetic source while its two consumer markers and one notification were retained. This direct SQL reset is test setup, not an application workflow. Both rows were captured by a quiesced local backup; Kafka and Redis were not included.

`Backup-FleetFlow.ps1` produced `fleetflow-fleetflow-phase11-demo-20261008T002401Z.ffbackup` at `2026-10-08T00:24:14.3579948Z`, SHA-256 `C5B6491AF767A7AB790FB4B5422085E7FA72B1A2C3F3228FA8DF111E25CF0AAB`; its AES-GCM decrypt/hash check passed. The source was stopped with volumes preserved. The fresh `fleetflow-phase11-replay` target began unattended restore at `00:24:42.8597770Z`, reached service readiness at `00:25:51.5256949Z`, and finished its signed read/cancellation and PostgreSQL check at `00:26:04.4935204Z`: **81.6 seconds to functional recovery**. The signed read saw the archived `CONFIRMED` order.

On the restored fresh broker, both archived pending events reached `PUBLISHED`. Event `fd238091-dc7a-4dfd-9da1-767a699599ca` then had two processed markers and one notification; requeued event `542600c1-78ee-456a-b5cf-abaf85667d87` still had exactly two markers and one notification. All 65 restored outbox rows were `PUBLISHED` after drain. This proves pending publication and retained-marker duplicate suppression for these two events on a local restore. It does not prove recovery of previously published Kafka records or expired deduplication markers. The archive was not copied off-host; the operator chose to defer that gate while completing local checks.
