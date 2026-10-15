# Production restore preparation

This procedure is intentionally not run against the local or production project. Do not restore into a project with existing containers or volumes. The recovery set is an encrypted tar archive containing separately age-encrypted FleetFlow and Keycloak PostgreSQL dumps, a manifest with source commit and service image IDs, the production Compose/proxy/build files, and the release candidate/SBOM/scan reports. It excludes secret values and the age private identity.

## Recovery set creation

On the Azure VM during a planned maintenance window, configure `BACKUP_AGE_RECIPIENT` as the offline age **public** recipient, `BACKUP_REMOTE_DESTINATION` as a dedicated rclone path, and the reviewed release metadata directory in `.env.production`. Then run:

```bash
FLEETFLOW_BACKUP_DIR=/var/backups/fleetflow ops/production/backup.sh
ops/production/check-backup-age.sh
```

The backup script stops the public edge, app and Keycloak to keep the actor/identity databases aligned, dumps both PostgreSQL databases to age-encrypted streams, includes the manifest/configuration/release metadata, uploads the archive, retrieves it again and compares SHA-256 and waits for healthy service restart before atomically updating the success marker. Concurrent backups are rejected by a file lock; candidate/CI/scan/SBOM identity is checked before stopping services. A trap attempts to restart services on success, error or interruption. If that restart fails, it emits a generic operator warning. The target directory is outside the repository and mode `0700`. No archive is deleted by this script. The archive hash proves transport integrity; only a separate-key decryption and isolated restore proves recoverability.

This script does not configure a schedule or alert route. Add it to a systemd timer after external backup credentials and host policy exist. Configure the timer to run `check-backup-age.sh` at least daily and deliver failures to the owner. A success marker older than 24 hours is a failure. Do not claim a sustained RPO from one run.

## Isolated restore rehearsal

1. Retrieve the archive and `.sha256` independently from the remote. Match the encrypted archive hash before decryption.
2. Obtain the age private identity from its separate offline escrow. Never copy it beside the archive or into the Compose secret directory.
3. Create a fresh isolated VM or disposable host with no source data disk mounted. Verify the target has no FleetFlow containers or named volumes. Keep the production VM and its disks untouched.
4. Decrypt the outer `fleetflow-production-<UTC>.tar.gz.age` to a root-only scratch directory using `age --decrypt --identity /offline/identity --output recovery.tar.gz <archive.age>`. Inspect `tar -tzf recovery.tar.gz` before extracting: reject absolute paths, `..` components, links or unexpected files. Extract only into a new empty scratch directory. Decrypt both inner `.dump.age` files separately and inspect their archive tables with `pg_restore --list`. Record all six runtime digests and source commit from the manifest. Transport SHA-256 alone does not prove decryption or database integrity.
5. Use a separate Compose project, unique named volumes and separate secret files with the recorded image digests and the numeric ownership/modes in [production preparation](README.md#secret-files). The schema belongs to the non-superuser `fleetflow_app` role created by the included PostgreSQL init script; preserve that role and grant layout when restoring. The recovery set includes patched PostGIS/Redis/Kafka Dockerfiles, proxy-header rules and the role-init script. Start only `postgres` and `keycloak-db`; restore the matching dumps into those empty volumes. Do not use `down --volumes` or a production project name.
6. Start Redis/Kafka/Keycloak/app in that isolated project, keep `edge` profile disabled, and verify Flyway history, synthetic actor-to-Keycloak subject links, zone/order state, outbox markers and readiness. Refresh Redis driver locations; Kafka offsets are not part of the recovery set.
7. From the private operator network, obtain a token from the restored realm and perform one signed customer order read and tracking snapshot. Confirm the restored state in PostgreSQL. Record backup timestamp, restore start/end, source commit, digests, verified state and data-loss window.
8. Stop only the isolated project without removing volumes until evidence is reviewed. Any cleanup must identify the exact disposable project by name and recheck it before action.

The existing PowerShell backup/restore tools target the local Compose stack and local Keycloak data volume. They are not production-overlay restore automation. Day 1 exercises encryption/tamper detection and remote-retention boundaries with the existing local PowerShell tools, plus production preflight/age guards in isolated unit checks. The production age/rclone archive pipeline and fresh-host restore remain unexecuted until approved target credentials exist. Only that separate target drill can establish production recovery end to end.
