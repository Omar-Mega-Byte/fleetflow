# Release Phase 10 — Target operations, backup and recovery

> Current Day 1 assessment: **READY for preparation; target execution remains open**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P0.** Encrypted local backup and a fresh disposable restore passed on 2026-10-15, including PostGIS, restored Keycloak identity, signed order read/cancel and a 56-second local functional RTO. The Linux production-overlay backup/age-check implementation prepares capture of application and Keycloak data and independent encrypted retrieval verification, but has not run with target credentials. Scheduling, failure alerts, sustained RPO and isolated Azure-target restore remain unproven.

## Objective

Operate the on-demand host safely and show that a VM/disk loss or bad release can be recovered from separately stored data within the agreed target.

## Why this phase comes now

The actual Azure resource boundary, secrets, DNS, persistent volumes and identity mode are established in Phase 9. A local restore is not a substitute for recovery on the target.

## Existing state and disposition

- **KEEP:** encrypted PostgreSQL backup, remote copy/check, restore tooling, local signed restore evidence and health/metrics baseline.
- **HARDEN:** scheduled run result, missed-backup alert, key custody, independent retention, identity/data consistency and target recovery procedure.
- **VERIFY:** whether a backup is necessary during deallocated windows. Synthetic data may be rebuilt only if the full identity/schema/seed workflow meets the same RTO.
- Redis locations are ephemeral and must be refreshed. Kafka remains at-least-once; reconcile outbox/consumer state during restore.

## Existing features to upgrade

- Extend encrypted backup, remote copy, restore scripts, health/metrics and local recovery evidence to the Azure target and current identity state.

## New features to add

- Add target backup-age alerts, missed-schedule reporting, a fresh-host recovery record and the owner runbooks absent from the local exercise.

## Work included

### Backup and disaster recovery

- **P0:** define recovery set: PostgreSQL/PostGIS/Flyway history, required Keycloak identity state/config, deployment manifests, image digests and separate secret/key retrieval instructions. Never put decryption keys beside archives.
- **P0:** keep a scheduled encrypted off-VM copy while the VM is active; report missed schedule, failed transfer, integrity failure and backup age over 24 hours. Define how an inactive/deallocated host is treated.
- **P0:** restore into a new isolated Azure target with no source volumes mounted; verify identity-to-actor mapping, schema, zones, assignments, incidents, outbox and one signed persona workflow.
- **P0:** measure RPO <=24 hours and RTO <=1 hour from timestamps, or prove synthetic data plus identity can be reconstructed within one hour.
- **P0:** practice release rollback as restore/forward-fix/cutover; do not attempt unsupported destructive Flyway downgrade.
- **P1:** set volume capacity alerts, log rotation, disk-full response, key/credential rotation, certificate expiry check, VM deallocate and monthly credit check.
- **P1:** keep Grafana/Prometheus and operator endpoints private; exercise alert delivery and recovery, not only dashboard appearance.

### Runbooks

- Start, check, deallocate, deploy, rollback, rotate secret/certificate, missed backup, restore, dependency outage, outbox quarantine and budget alert.
- State which actions are safe while the VM is deallocated and who owns each credential.
- Day 1 production backup and isolated restore drafts are in `ops/production/backup.sh`, `ops/production/check-backup-age.sh`, `ops/production/RESTORE.md`, and `ops/production/README.md`. These are not yet target-verified automation.

## Tests required

- Scheduled backup success/failure/missed execution and notification tests.
- Archive integrity, wrong key, independent retrieval and fresh-host restore.
- Signed end-to-end workflow after restore; PostGIS and Keycloak identity verification; outbox/dedupe reconciliation.
- Restart/deallocate/start, disk-capacity alert, secret rotation and rollback rehearsal.

## Failure scenarios to verify

- VM/disk loss, corrupt/missing archive, wrong decryption key, stale backup, failed upload, Keycloak/database mismatch, disk full, alert route down, bad migration, interrupted restore and app restart during restore.

## Verification

- Use a separate disposable target and exact image/config digests; do not mount or erase the source host's persistent volume.
- Record backup timestamp, restore start/end, verified data, recovery-point window, elapsed recovery time, exceptions and alert delivery.

## Exit criteria

- At least one scheduled backup is independently retrievable and integrity-checked; missed/failed backup generates an owner-visible signal.
- Fresh-target restore or deterministic synthetic reconstruction meets the agreed RPO/RTO.
- A signed customer/merchant/driver/dispatcher flow succeeds after recovery with no duplicate outbox effect.
- Start/deallocate, credential/certificate rotation, incident and rollback runbooks have been exercised and match actual host state.
- Budget and disk signals are configured; no secret or decryption key shares the backup location.

## Deliverables

- Target backup/restore evidence, alert evidence, owner operations/rollback runbooks and updated reviewer availability statement.

## Features explicitly not added

- No continuous failover, enterprise backup platform, multi-region DR, or claim of zero data loss.

## Risk

HIGH. A single VM can be lost, and a local backup can be unusable on a new host.

## Depends On

- Phases 4, 5, 8 and 9.

## Unlocks

- Target performance/failure qualification and final acceptance.


## Consolidation audit — 2026-10-14

Production backup serializes work, validates candidate/CI/scan/SBOM and all six image references before stopping, encrypts the outer recovery set and both DB streams, verifies remote retrieval and marks success only after healthy restart. Eight preparation unit tests cover measurement/realm and backup age/provenance guards. Local PowerShell AES-GCM/tamper and retention checks pass; restore preflight requires a new empty destination. Production age/rclone off-host execution, schedule/notification and fresh-target restore remain NOT_RUN.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
