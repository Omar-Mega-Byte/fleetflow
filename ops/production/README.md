# Azure single-VM production draft

This is a **private deployment preparation**, not Azure acceptance. The consolidation audit exercises this topology only on a new disposable project with loopback HTTPS and synthetic data. Current results are in the [Day 1 ledger](../../docs/phases/production/DAY-1-EVIDENCE-LEDGER.md). It has not been exposed to the internet or tested against Azure. Local `compose.yaml` remains the supported developer stack. The production manifest is a separate topology with no dependency, application, metrics, or Keycloak-admin host port.

## Topology and trust boundaries

- Internet ingress is `edge` only, on TCP 80 and 443. The Nginx image serves the built web application, proxies `/api/` to the app, and proxies only `/realms/fleetflow/` plus `/resources/` on the identity hostname to Keycloak.
- Only `edge` is attached to the non-internal `public_front` network. `app_proxy`, `backend`, and `identity-data` are internal Docker networks. PostgreSQL/PostGIS, the separate Keycloak PostgreSQL database, Redis, Kafka, FleetFlow app and Keycloak have no published host ports.
- The proxy rejects unknown hosts, redirects known HTTP hosts to HTTPS, disables buffering for SSE, applies a 1 MiB body cap, sets bounded proxy timeouts and overwrites forwarded client-address headers with the socket peer address. The app and Keycloak accept forwarded headers only on their private proxy network.
- Actuator, Swagger, the Keycloak master realm, admin routes, health and metrics paths are not proxied. Prometheus, Grafana and Alertmanager are absent from the public stack. Operator access remains through restricted SSH and local Docker access.
- This is a single host and single broker. Kafka's plaintext listener is reachable only inside its private bridge network. It is not suitable for a shared or multi-host network.

## Images and release metadata

The `.env.production.example` file is deliberately non-runnable: it uses `example.invalid` hosts and zeroed digest examples. Create a private `.env.production` only after a release has passed Phase 1–7 and the Phase 8 checks. All six runtime images (application, web/edge, Keycloak, PostGIS, Redis and Kafka) must use reviewed immutable `@sha256:` references. PostGIS 17/3.5 and Redis 7.4.11 keep their service versions while signed Alpine packages are refreshed. PostGIS removes the obsolete Go-based gosu helper and runs as UID 70; Redis runs as UID 999. su-exec remains available for image-entrypoint compatibility. The fresh production broker uses Kafka 4.2.2 with maintenance patches for Jackson core/databind and lz4; do not reuse or upgrade the normal developer Kafka 3.9 volume with this image.

Build images only from the reviewed commit and record all output digests. The web image is host-independent: the edge serves `/runtime-config.js` with the public HTTPS issuer from its validated `IDENTITY_HOST` at startup. The browser bundle contains no client secret, and the public OIDC client ID remains `fleetflow-web`. Verify the served runtime config and login against the deployed hostname before accepting the candidate. The Keycloak runtime is built from the pinned Keycloak base and performs the optimized PostgreSQL build. The app entrypoint reads its database password from a mounted secret file; the Keycloak entrypoint reads the database and bootstrap-admin password files without printing them.

Before the first run, package the exact successful CI evidence, all seven SBOMs, Trivy and CodeQL reports, six registry digests, completed advisory review and explicit release-owner approval in the private metadata directory. Preserve the artifact paths recorded by CI. See the [candidate metadata contract](../../docs/phases/production/CANDIDATE-METADATA.md). No current Day 4 tree is a release candidate.

## Secret files

On the VM, create `/etc/fleetflow/secrets` owned by root with mode `0700`. Provision files through the approved operator channel. File-backed Compose secrets retain host ownership/mode, so use the following permissions; a blanket root `0600` makes the non-root application, Keycloak and edge unable to start. The root-only parent directory prevents host users reading files with container-readable mode. Each service receives only its declared file mounts. Never commit these files or place them in release images:

- `postgres_db_password`: owner `70:0`, mode `0400`; independent random bootstrap owner password (`DB_OWNER=fleetflow_owner`).
- `app_db_password`: root-owned mode `0444` inside the root-only directory; a different random password for `DB_USER=fleetflow_app`. The fresh PostgreSQL init script grants schema ownership without superuser, create-role or create-database privileges. This file is mounted only in the app and FleetFlow PostgreSQL containers.
- `keycloak_db_password`: owner `1000:0`, mode `0440`; separate random identity-database password. PostgreSQL runs as UID 70 with GID 0 for this read-only group access; Keycloak reads as UID 1000.
- `keycloak_admin_password`: owner `1000:0`, mode `0400`; strong bootstrap administrator password. It is read by the private Keycloak entrypoint and is not exposed through the proxy.
- `tls_fullchain.pem` and `tls_private_key.pem`: owner `101:101`, mode `0400`; current chain and private key for both stable hostnames. Nginx runs as UID 101 with all Linux capabilities dropped. Revalidate numeric IDs if a base image changes.

Keep the age decryption identity on separate offline storage. The VM needs only its public `BACKUP_AGE_RECIPIENT`; the rclone configuration must be available to the backup account without echoing its credential. Do not run `set -x` on these scripts. Docker-group access is effectively root access to these files and containers.

## Identity initialization

The first boot must not reuse the local development realm import. Configure a production `fleetflow` realm with:

- issuer exactly `https://<IDENTITY_HOST>/realms/fleetflow`;
- public SPA client `fleetflow-web`, Authorization Code + PKCE S256, no client secret and no direct-grant flow;
- exactly `https://<DEMO_HOST>/auth/callback` as redirect URI and `https://<DEMO_HOST>/auth/signed-out` as post-logout URI; exact web origin only;
- API audience `fleetflow-api`; effective FleetFlow roles and active memberships are loaded from PostgreSQL on every request. JWT role claims do not grant application permissions;
- five synthetic identities: customer, merchant, driver, dispatcher and operator. `DEMO_*_SUBJECT` values must equal the real Keycloak `sub`, not a username. Defaults are fixture values, not production identity provisioning. The seed API creates only FleetFlow records and never sets IdP passwords. Bootstrap one separately provisioned signed ADMIN actor through the private operator procedure. No real customer data.

Render the production browser realm without users, passwords or direct-grant clients:

```bash
python3 ops/production/render-realm.py --demo-host "$DEMO_HOST" --identity-host "$IDENTITY_HOST" --output /etc/fleetflow/realm.json
```

The renderer requires separate exact DNS hosts, enables PKCE S256 and brute-force protection, and refuses to overwrite output. Import it privately before opening ingress, then provision identities and their real subject mappings. The test runner adds a direct-grant client only to its generated disposable realm; that client is never in this production render.

Admin UI and `/admin/` are intentionally unavailable from the identity hostname. Initialize and operate the realm with the reviewed private procedure through `docker compose exec keycloak ...` from restricted SSH. Do not make admin routes public to complete bootstrap. Remove or rotate the bootstrap password under the identity-provider runbook after initial setup; no browser workflow should contain it.

## Local rendering and owner-operated commands

Copy the example to `.env.production`, replace the invalid host/image/release values, create all six secret files, then validate without printing resolved configuration:

```bash
chmod 0600 .env.production
ops/production/fleetflow.sh config
```

`ops/production/fleetflow.sh up` first validates the production network/hostname boundary and approved candidate metadata, pulls all six immutable digests, then checks each pulled image against the scanned CI config digest or the approved registry manifest digest, according to the Docker image store. After assembling the approval record and before deployment, the promotion host must run `python3 scripts/validate_release_candidate.py --env-file .env.production --check-registry-manifests --check-local-images` to prove that each single-platform registry manifest references the config digest scanned by CI. `up` repeats both checks after pulling on the VM, then starts services and waits up to 15 minutes for every healthcheck. `ready` repeats health checks; `status` prints Compose status; `stop` stops services but preserves named volumes. It does not provision resources, build/push images, run a migration rollback, or contact Azure. The `up` message asks for external DNS/TLS checks; a healthy container is not proof that public routing or identity works.

Before DNS is switched, validate the same config in an isolated disposable Compose project and run the browser OIDC flow against a disposable realm. The production realm/client, certificate, firewall, forwarded-header trust, direct-port bypass and external endpoint checks remain Phase 9 acceptance work.

## Azure prerequisites and cost check

The selected direction is one owner-started Azure Linux VM. Before provisioning, record and verify in the actual subscription and region:

| Item | Record before creating resources |
| --- | --- |
| VM | Region, Linux SKU, vCPU/RAM, current hourly/monthly price, expected owner-on hours and cold-start time |
| Storage | OS and persistent data disk type, size, IOPS/throughput, snapshot/backup price and disk retention after deallocation |
| Network | Static public IPv4 price while running and deallocated, outbound bandwidth, DNS/domain renewal, certificate issuance/renewal path |
| Recovery | Remote backup storage and transaction cost, encryption identity escrow, restore-target cost and a restore window |
| Budget | Current credit balance/expiry, subscription budget, email alert thresholds, and owner responsible for deallocating the VM |

Do not substitute a generic public price estimate for the subscription's selected SKU, disk and region. Deallocation makes the demo unavailable; it may not stop disk, static-IP, DNS/domain, snapshot, backup-storage or bandwidth charges. Set budget alerts before the first start.

### Network and lifecycle prerequisites

- NSG and host firewall inbound policy: TCP 80/443 from the internet; TCP 22 only from the maintainer's current public `/32`; default deny all other inbound traffic. Recheck after address changes. Do not expose PostgreSQL, Redis, Kafka, app port 8080, Keycloak 8080/9000, Prometheus 9090, Grafana 3000 or Alertmanager 9093.
- Use a persistent managed data disk for Docker data and verify mount-before-start. Do not place database volumes on the disposable OS disk. Keep backups off the VM.
- Create DNS A/AAAA records only for the selected demo and identity hosts. The certificate must cover both names; document renewal and verify expiry monitoring before opening traffic.
- Restrict SSH source; use key authentication, disable password SSH, patch the OS, retain Docker daemon local-only, and limit Docker access to the operator.
- Start: attach/mount persistent disk, verify host firewall, run `ops/production/fleetflow.sh up`, verify DNS and certificate externally, complete signed OIDC/browser smoke, then share the URL.
- Stop: announce maintenance, run `ops/production/fleetflow.sh stop`, verify containers stop cleanly, then deallocate the exact VM through Azure. Never delete disks or volumes to stop charges.
- Restart: start the same VM, verify the persistent data mount and image digests, then run `ops/production/fleetflow.sh ready` and the signed browser check before reopening the URL.

No Azure subscription, DNS zone, certificate or cost data was available to this repository task. These values must be filled with current account/region evidence before provisioning.
