# FleetFlow: remaining steps to finish the public demo

**Checkpoint:** 2026-10-17, after the Day 4 local release work. **Current decision:** NOT READY for an on-demand public demo. This document is the execution handoff for the remaining gates; it is not release approval. The intended release is a synthetic showcase on one manually started Azure VM, with no commercial uptime or real-customer-data claim.

## What is already built

- The backend, five persona workspaces, signed private browser journeys, authorization boundaries, synthetic seed/reset, dispatch recovery, CI scans/SBOMs, and local encrypted restore have dated evidence in the [Days 1–3 audit](phases/production/DAYS-1-3-FULL-AUDIT-2026-10-17.md). Phase 4 and 5 private MUST slices are verified; their target acceptance remains open.
- Day 4 local preflight and the 27-CVE Keycloak vendor review are in [Day4.md](phases/production/Day4.md) and its [review CSV](phases/production/DAY-4-SUPPLY-CHAIN-REVIEW-2026-10-17.csv). All 50 Keycloak scan matches remain open.
- The web edge now supplies the public OIDC issuer at runtime, so the same scanned edge image can be deployed by digest. Production `up` now checks Compose structure, approved candidate metadata, the six pulled image IDs and exact CI evidence before starting services. A disposable signed browser rehearsal passed for the changed working tree, including failure/recovery checks; it was partial because owner alert delivery was not configured. The Day 4 source checkpoint `65b0c9d` passed [trusted push CI](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39044563681) and [PR CI](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39044567166), both 8/8 jobs; its trusted-push evidence verifies owner alert delivery and records the image archive, but `candidateApproved` is false. Any later commit must pass CI at its own full SHA before release use.

## Required sequence

Do these steps in order. A failed gate keeps the candidate private and returns the result to the owning phase. One person may hold several owner roles, but each decision and evidence record must still be explicit.

### 1. Close the changed-source CI gate — repository owner (Phase 8)

1. Freeze the final reviewed source SHA. Confirm the required push and PR checks on that exact SHA; inspect its `release-evidence-<SHA>` artifact, including six build IDs, seven SBOM hashes, seven Trivy reports, two CodeQL reports, signed browser/database results and owner alert result. The `65b0c9d` source checkpoint passed, but a later commit requires fresh exact-head checks.
2. Run the production Compose render and confirm the edge serves `/runtime-config.js` with the runtime issuer in the exact-candidate private signed browser rehearsal. Re-run any failed gate after a fix. Do not use the earlier `b197528` CI run or the working-tree rehearsal as exact-commit evidence.
3. **Exit:** green exact-commit CI and private runtime-config/login evidence, with no secret, unknown or high/critical scanner blocker. Retain the CI URL, artifact and SHA together.

### 2. Resolve the 50 Keycloak findings — security reviewer and release owner (Phase 8)

1. Re-scan the exact six runtime images and source. Review every remaining Keycloak SARIF result against the package/version and vendor record. The [Day 4 CSV](phases/production/DAY-4-SUPPLY-CHAIN-REVIEW-2026-10-17.csv) is an investigation record with `OPEN` decisions; it is not approval.
2. Prefer a vendor-supported image/package fix. If a finding remains, make a documented `NOT_AFFECTED` or `ACCEPTED_TIME_BOUND` decision for each distinct SARIF ID with owner, rationale and evidence; a time-bound acceptance also needs mitigation and future expiry. The release owner must check the underlying facts and policy. Rebuild and repeat CI/scans after any image change.
3. **Exit:** no unreviewed finding or scanner blocker, and a reviewed advisory CSV matching the final exact-image scan. If a supported disposition cannot be made, stop candidate promotion here.

### 3. Publish and approve one immutable candidate — release owner (Phase 8)

1. Select the approved registry and publishing identity. After the entire trusted-push CI run is green, download its `runtime-images-<SHA>` artifact within its 14-day retention window. On a trusted Linux release host, verify `fleetflow-runtime-images.tar.gz` against `runtime-images.json`, run `gzip -dc fleetflow-runtime-images.tar.gz | docker load`, and compare the six loaded image IDs with the manifest and exact CI evidence. Tag and push those same six images to the approved registry; do not rebuild from moving package sources. Capture each `repository@sha256:...` reference, pull it, and confirm its local image ID still equals the one scanned by CI.
2. Download the exact CI `release-evidence.json`, `runtime-images.json`, seven SBOMs, seven Trivy SARIF files and two CodeQL SARIF files into one private metadata directory, preserving the relative artifact paths. Prepare `candidate.json` following the [candidate metadata contract](phases/production/CANDIDATE-METADATA.md). Record the advisory review and the release owner's explicit dated decision.
3. Put the six approved digest references, full source commit and metadata directory in private `.env.production`. Run `python3 scripts/validate_release_candidate.py --env-file .env.production`. **Exit:** the validator passes, every artifact hash matches, the image IDs match after pull, and Phase 8 has an explicit approved candidate. No candidate exists at this checkpoint.

### 4. Supply and approve the Azure boundary — Azure, DNS and identity owners (Phase 9)

1. Identify the Azure subscription, resource group, region and VM SKU. Record current VM, persistent disk, public IP, DNS/domain, outbound traffic and backup costs for the intended owner-on hours. Confirm available credit, expiry, budget alert and who deallocates the VM. These values are account-specific and have not been supplied to this workspace.
2. Reserve two stable DNS hosts (demo and identity), certificate coverage and renewal, a production `fleetflow` realm/client with exact PKCE redirect/logout origins, runtime secret custody, an off-host encrypted backup destination and an owner alert destination. Create the private data disk and mount it before Docker starts.
3. Set NSG and host firewall default deny; allow public TCP 80/443 and SSH only from the maintainer's current `/32`. Keep database, Redis, Kafka, app, Keycloak admin and metrics private. Review [production preparation](../ops/production/README.md) and [Phase 9](phases/production/phase-09-azure-public-deployment.md) before provisioning. **Exit:** named owners, approved cost/budget, exact hostnames, secrets and firewall plan are recorded.

### 5. Deploy and prove the public boundary — infrastructure and product owners (Phase 9)

1. Render the production realm privately with `python3 ops/production/render-realm.py`, provision the secret files with the documented UID/mode, initialize the production identity mapping and set the approved `.env.production` values. From the VM, run `ops/production/fleetflow.sh config`, then `ops/production/fleetflow.sh up`. The `up` preflight blocks an unapproved or mismatched candidate.
2. From **outside** the VM/network, verify DNS, trusted TLS chain/renewal, HTTP-to-HTTPS redirect, HSTS, the served runtime OIDC issuer/JWKS, signed login/logout and all five persona workflows. Check direct connection attempts to internal/admin/metrics ports and routes, forwarded-header spoofing, rate limits, SSE reconnect, errors, CSP/CORS and browser storage. Record the exact source, six digests, hostname, resource IDs, date and result.
3. Stop containers and deallocate the VM through Azure; start it again and verify persistent PostgreSQL/Keycloak state and a signed browser workflow. Record remaining disk/IP charges while deallocated. **Exit:** all Phase 9 boundary and lifecycle criteria pass on the exact approved candidate. A healthy container alone does not pass this step.

### 6. Prove backup and recovery on the target — recovery owner (Phase 10)

1. Configure `ops/production/backup.sh`, age public recipient, separately held decryption identity, dedicated off-host rclone destination and scheduled execution. Run `ops/production/check-backup-age.sh` through the owner alert path. Capture one scheduled success plus missed/failed/age alert and recovery delivery.
2. Follow [RESTORE.md](../ops/production/RESTORE.md) on a **new isolated Azure target** with no source volumes. Independently retrieve and decrypt the archive, restore application and Keycloak state, verify PostGIS/Flyway/identity mapping, then complete a signed business flow and outbox/dedupe check.
3. Measure recovery point and functional recovery time from timestamps. Exercise start/deallocate, rollback/forward-fix, secret/certificate handling, disk and budget alerts. **Exit:** RPO at most 24 hours, RTO at most one hour, owner-visible alerts and documented recovery all pass. The earlier local 72.8-second restore is preparation evidence only.

### 7. Qualify the exact target — qualification owner (Phase 11)

1. Use the bounded target profile in [Phase 11 qualification](operations/PHASE11_TARGET_QUALIFICATION.md): two reviewer sessions, ten synthetic drivers, ten SSE streams, 30 minutes and at least 100 expected operations. Capture p50/p95/p99, expected-operation success, error classes, CPU/RAM/GC, DB pool, Redis, Kafka/outbox and SSE metrics, with the Azure SKU and running cost.
2. Run the documented bounded DB, Redis, Kafka, Keycloak, app restart, slow-stream and disk-alert failure cases against the approved target in a planned window. Verify user behavior, operator alert fire/clear and recovery without duplicate business effects. Re-scan the deployed image digests and repeat external ports/headers and signed authorization checks.
3. **Exit:** at least 99% of expected operations succeed, p95 non-stream API latency is at most one second, cold start is within 15 minutes, and actual spend fits the approved budget. Fix or resize only for a measured failure, then rerun affected checks.

### 8. Make the release decision — release owner (Phase 12)

1. Assemble one dated evidence packet linking source SHA, successful CI run, six deployed digests, advisory review, Azure resource inventory, public URL, persona/browser/network results, backup/restore timing, load/failure measurements and cost/alert results.
2. Check every P0 item in the [production roadmap](PRODUCTION-ROADMAP.md). Update the README and reviewer guide with the real on-demand URL, safe sign-in instructions, synthetic-only scope, manual online/offline behavior and single-VM limit. Verify the guide as a first-time reviewer.
3. Record exactly **APPROVED FOR ON-DEMAND PUBLIC DEMO** or **REJECTED — NOT READY** with the residual items and owner. Publish/advertise the URL only after approval. Do not infer Phase 12 completion from local CI or this document.

## Inputs still needed from the project owner

- Approved image registry/publishing identity. The project owner has taken responsibility for the Keycloak review and final candidate decision, but no finding disposition or candidate approval has been made.
- Azure subscription access, resource group, chosen region/SKU, current budget/credit and authority to create budget alerts and the single VM/data disk/IP.
- Demo and identity DNS names, certificate/DNS control, production OIDC realm/client owner, secret delivery and backup/alert destinations.
- Planned public test window and the maintainer's SSH source IP; target recovery and load drills may interrupt the demo.

Until those inputs and the ordered gates pass, the honest project status remains **locally demonstrated; public release incomplete**. Day 4 is partial, Phase 8–11 target gates remain open, Phase 12 is not started, and legacy Phase 11 remains `CLOSED_PARTIAL`.
