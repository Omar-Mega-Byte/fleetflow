# Release Phase 9 — Azure public deployment boundary

> Current Day 1 assessment: **PARTIAL — external prerequisites**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS, PUBLIC DEPLOYMENT BLOCKED. Priority: P0.** The separate TLS ingress/production Compose overlay passes rendered topology, private-port, secret-file, OIDC and proxy-policy validation; only edge TCP 80/443 are host published. The owner supplied an enabled Azure for Students subscription, UAE North, two DuckDNS names and a $10/month ceiling. A 2026-10-18 live SKU/quota and retail-price preflight found no unrestricted VM SKU with at least 8 GB RAM and enough family quota in the approved subscription/region; even the hypothetical lower-bound VM/IP/two-disk cost is tight against the cap. See the [current cost and quota record](../../PROJECT-COMPLETION-REMAINING.md#uae-north-cost-preflight-2026-10-18). Azure and external-network acceptance remain unexecuted; DNS, certificate, production realm and target recovery/qualification are still open.

## Objective

Deploy the reviewed release artifact to one low-cost Azure Linux VM so a recruiter can reach the synthetic demo over a stable HTTPS URL during owner-started demo windows.

## Why this phase comes now

One real signed browser journey and the local release checks now pass. Exact-commit remote CI/security review and immutable candidate controls remain open. Public exposure before those gates would make the internet-facing host a debugging environment.

## Existing state and disposition

- **KEEP:** local Compose topology and loopback port bindings as a separate developer/demo environment.
- **KEEP:** the distinct public deployment overlay, TLS reverse proxy configuration, documented NSG/host firewall requirements, production OIDC contract, secret-file layout and owner start/stop/deallocate procedure; its target runtime remains unverified.
- **HARDEN:** application bind/forwarded-header behavior, Keycloak production mode, secrets, network isolation, volumes and restart/grace/resource budgets.
- **VERIFY:** live Azure estimates for VM, disks, IP, DNS/domain and backups before choosing region/SKU. Do not assume the $100 credit covers a continuously running host.
- The design is a single host with no automatic failover. Deallocation intentionally takes the demo offline; persistent storage and public-IP costs may continue.

## Existing features to upgrade

- Preserve local Compose and its loopback boundary; adapt the application bind and identity configuration only for the private proxy-to-service path.

## New features to add

- Add the reviewed public overlay, TLS ingress, production Keycloak configuration, Azure firewall policy and owner-controlled lifecycle procedure.

## Work included

### Infrastructure and networking

- **P0:** create an explicitly separate public deployment overlay; do not weaken or repurpose local Compose defaults.
- **P0:** place a TLS reverse proxy on public 80/443; route only the web app and browser-required identity endpoints. Bind the app to the private container interface reachable only by the proxy.
- **P0:** expose no host/public listener for PostgreSQL, Redis, Kafka, Prometheus, Grafana, Alertmanager, Keycloak admin or app management endpoints.
- **P0:** enforce Azure NSG and host firewall allowlists: HTTPS, redirect/renewal needs, and SSH only from the maintainer's current source IP. Test direct-port bypass externally.
- **P0:** use a stable DNS name and TLS certificate; record registration/renewal cost and certificate renewal procedure. Choose static/dynamic IP strategy based on stable URL and live cost, not guesswork.
- **P0:** run Keycloak in supported production mode with persistent identity state and an exact stable HTTPS issuer/JWKS URL. Keep admin routes private; register exact browser redirect/logout origins and audience.
- **P0:** deliver secrets from protected runtime files/secret mechanism with restrictive ownership; separate demo, database, identity and backup credentials. No secrets in image, frontend bundle, Git or command logs.
- **P1:** set container restart, stop grace, memory/CPU, disk, log rotation and health policies sized for one VM. Define what happens on VM stop/deallocate and on startup.
- **P1:** provide owner-operated start, readiness-check and deallocate commands with a cost reminder; verify database/identity data persists across both operations.
- **P1:** keep the one-VM non-HA limit and manual availability behavior on the reviewer page.

### Security and tests

- Test proxy and app forwarding trust so client-supplied forwarded headers cannot spoof identity/rate-limit IP.
- Verify browser OIDC, TLS, HSTS, CORS/CSP, certificate renewal and all public/private port boundaries from an external network.

## Tests required

- Production Compose config/render; clean start and stop; no direct app/dependency ports.
- TLS hostname/certificate and redirect checks; browser OIDC issuer/audience/redirect/logout.
- External port scan against the VM and a deliberate attempted DB/broker/management connection.
- NSG/firewall source allowlist, forwarded-header spoof and proxy-timeout tests.
- VM stop/deallocate/start persistence and immutable image-digest verification.

## Failure scenarios to verify

- Wrong issuer/audience, expired certificate, DNS/IP change, proxy outage, direct app-port attempt, forged forwarded address, missing/incorrect secret, Keycloak/JWKS outage, full disk and VM restart after an image update.

## Verification

- Render local and public Compose configurations separately; run the exact Phase 8 artifact by digest.
- Use external TLS and port checks from outside Azure; confirm only approved routes answer.
- Start and deallocate through the documented owner procedure and verify the public URL and persistent data behavior.
- Record actual Azure resource estimates and current credit/budget alert configuration before provisioning.

## Exit criteria

- Public access is limited to intended HTTPS routes; all internal ports are unreachable externally and app is reachable only through the proxy.
- Keycloak uses production settings and stable HTTPS issuer; signed login works through the public browser flow.
- Secrets are runtime-only, least-privileged and absent from repo/images/bundles/logs; admin/metrics paths are private.
- VM stop/deallocate/start preserves data; public availability during stop is explained clearly.
- Exact artifact digest, public hostname, resource costs and firewall rules are recorded.

## Day 1 preparation evidence

- `ops/production/compose.production.yaml`, `web/Dockerfile.release`, `web/nginx/fleetflow.conf.template`, the custom production Keycloak runtime, `ops/production/fleetflow.sh`, and `ops/production/README.md` prepare a private-network-first topology.
- `scripts/Validate-ProductionCompose.py` checks that only edge publishes 80/443, all other images are digest-pinned, database/broker/identity networks are internal, and management/admin paths are rejected by the proxy draft.
- No public TLS, Azure deployment, forwarded-header spoof, firewall-bypass, live production realm/client, public port or target container-start test has been run. The two supplied DNS names currently point outside the approved Azure deployment. This remains a draft and no paid resource was created.

## Deliverables

- Public Compose/proxy/deployment configuration, Azure network setup, secured identity config, owner start/stop runbook and external-boundary evidence.

## Features explicitly not added

- No Kubernetes, load balancer, multi-VM HA, multi-region, managed broker migration or commercial production SLA.

## Risk

CRITICAL. A proxy/issuer/firewall error can expose infrastructure or break every login.

## Depends On

- Phases 2, 6 and 8. Phase 8 itself requires application and browser acceptance from Phases 1–7.

## Unlocks

- Target operations, backup restore and real target qualification.


## Consolidation audit — 2026-10-14

Repository preparation and a real loopback-only production-topology smoke pass. TLS proxy, exact issuer, private stateful/admin/management routes, non-root image startup, non-superuser app DB role, readable scoped secrets, bounded resources/logs and body/rate/header controls execute. Public Azure subscription/credit, region/SKU approval, DNS/certificate and secret/alert access remain unconfirmed. No public deployment or firewall acceptance occurred.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
