# Day 4 — Candidate and public-boundary plan

> **Later 2026-10-18 checkpoint:** the [exact six-image GHCR publication](PHASE-8-GHCR-PUBLICATION-2026-10-18.md), [completed Keycloak reconciliation](KEYCLOAK-SECURITY-RECONCILIATION-2026-10-18.md) and [separate owner-approved private candidate](PHASE-8-CANDIDATE-APPROVAL-2026-10-18.md) supersede this day's earlier no-registry/open-finding baseline. Protected PR/main delivery and Azure Phase 9 remain open.

**Status:** PARTIAL — local preflight is complete; candidate promotion and Azure deployment are blocked by external prerequisites.
**Plan baseline:** 2026-10-17. **Execution started:** 2026-10-17 (Africa/Cairo).
**Purpose:** follow the [five-day execution schedule](EXECUTION-SCHEDULE-5-DAY.md) by preparing and, only after all gates pass, deploying the exact release candidate behind the public edge.

This is a work plan, not approval to deploy or evidence that the public demo is ready. Day 4 must not change the existing `PARTIAL / NOT COMPLETE` or `CLOSED_PARTIAL` decisions by itself.

**Staffing assumption:** the five-day schedule assumes three implementation owners plus a release coordinator. If one person is doing the work, follow the dependency order below and report unfinished gates plainly; do not treat the calendar target as a reason to skip them.

## Starting point from Days 1–3

| Area | Verified starting state | Day 4 consequence |
| --- | --- | --- |
| Source and CI | Clean `codex/day3-persona-closeout` at `b19752849a0694d282b9e5a2856a951985d88dc5`. Exact-commit push and PR checks passed in [run 39024448144](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39024448144) and [run 39024451026](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39024451026). | Recheck source SHA and required checks before freezing any candidate. Later source changes invalidate the old evidence. |
| Product and private acceptance | Five signed persona journeys, zone/serviceability and dispatch recovery paths, authorization denials, accessibility/mobile checks, recovery signals and owner-alert delivery passed in disposable private environments. | Do not repeat these as a substitute for public-target checks. Run the deployed-browser smoke against the exact candidate. |
| Supply chain | Current evidence records 50 open Keycloak findings (25 MEDIUM, 25 LOW); the other scanned images and source had no findings. No finding was suppressed. | Refresh the image scan and establish a supported fix or explicit, policy-compliant disposition for every open finding before release promotion. A passing scan-policy job alone does not resolve these findings. |
| Candidate | Day 3 exact-run evidence says `PARTIAL_NOT_COMPLETE`, `candidateApproved: false`, and has no registry digests. The six recorded image IDs are build identities, not published candidate digests. | There is no deployable, approved immutable candidate yet. Create one only after all candidate gates pass. |
| Azure target | The Phase 9 brief says subscription/SKU/cost, DNS/certificate, and production realm initialization are not available in the current workspace; no external target acceptance has run. | Confirm each prerequisite with its owner. If any is missing, keep the environment private and record the dependency; do not improvise public exposure. |
| Later target gates | Local encrypted backup/restore evidence exists, but fresh Azure-host recovery, measured target load, sustained RPO and target RTO are open. | Day 4 may prepare the backup schedule and target smoke. Day 5 owns independent fresh-target recovery and full qualification. |

The [Days 1–3 audit](DAYS-1-3-FULL-AUDIT-2026-10-17.md) and [Day 3 acceptance ledger](DAY-3-PROMPT-3-RELEASE-ACCEPTANCE.md) are the current detailed evidence records.

## Day 4 execution record — 2026-10-17

### Completed readiness and local preflight

- Reconfirmed `codex/day3-persona-closeout` at `b19752849a0694d282b9e5a2856a951985d88dc5`. Exact-commit push CI passed all 8 jobs and PR CI passed in [39024448144](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39024448144) and [39024451026](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39024451026).
- Downloaded and reviewed the exact-commit Trivy image artifact from run 39024448144. It reports **50 Keycloak findings across 27 CVE IDs** (25 MEDIUM, 25 LOW); the source and other five runtime images report zero findings. The CI evidence still says `candidateApproved: false` and has no registry digests.
- Refreshed upstream/vendor review: [Keycloak 26.8.1](https://github.com/keycloak/keycloak/releases/tag/26.8.1), pinned by [the Keycloak runtime Dockerfile](../../../ops/production/keycloak.Dockerfile), is still the upstream latest release. Checked all 27 findings against Red Hat's CVE records. No matching fixed Red Hat Enterprise Linux 9 release was recorded for a scanned package; current states include `Fix deferred`, `Affected` and `Will not fix`. Some Red Hat Hardened Images advisories list fixes, but that is a different product and does not prove a fix in the pinned Keycloak image. The row-by-row review is in [Day 4 supply-chain review](DAY-4-SUPPLY-CHAIN-REVIEW-2026-10-17.csv). No package update, suppression or exception was introduced.
- Compared the live `quay.io/keycloak/keycloak:26.8.1` OCI index with the Dockerfile pin. Both resolve to `sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`; the upstream tag has not moved to a refreshed manifest.
- `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1` passed. It rendered the production config and confirmed only edge TCP 80/443 host ports, with app, database, Redis, Kafka, Keycloak and monitoring private. It did not start containers.
- `docker compose --env-file .env.example -f compose.yaml config --quiet` passed for the local topology.
- Rebuilt the app and edge from the Day 4 working tree and ran the disposable private production topology on loopback HTTPS. Both signed Playwright journeys passed (customer/order/SSE/logout and five-persona/driver/PostGIS/dispatch), as did broker, Redis and PostgreSQL loss/recovery assertions. The run's `result.json` remained `PARTIAL` because no owner alert webhook was supplied; it was not an exact-commit CI run or public-target test.
- Day 4 source checkpoint `65b0c9db4f220858ce796af27e39f128c8efbfe5` passed all eight jobs in [trusted push CI 39044563681](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39044563681) and [PR CI 39044567166](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39044567166). The trusted-push `release-evidence-65b0c9db4f220858ce796af27e39f128c8efbfe5` artifact records verified owner alert delivery, six image IDs, a 954,195,424-byte exact-image archive, seven SBOMs, seven Trivy reports, two CodeQL reports and `candidateApproved: false`. The new Keycloak SARIF has 50 results across the same 27 IDs in the Day 4 review CSV; no ID is missing or stale. This is build evidence for that checkpoint, not a published candidate. Any later source or documentation commit needs its own exact-head CI before release use.

### Deployment gate result

**Candidate promotion and Azure deployment are blocked.** Azure CLI and the Az PowerShell module are unavailable in this environment, and `ops/production/secrets` is absent. The current evidence also has no explicit candidate approval or registry digests, while the Keycloak findings remain open. Subscription access, approved cost/region/SKU, DNS/TLS, production issuer/realm and runtime secret custody cannot be verified here. No image was published, no Azure resource was provisioned and no public deployment was attempted.

Subsequent repository work removed the CI edge image's build-time `.localhost` issuer by serving the public issuer from validated edge runtime configuration. The earlier `b197528` runs apply to the prior Day 3 commit. The changed-source private browser rehearsal and exact-commit CI passed at the Day 4 checkpoint above; the still-open Keycloak review, registry publication, candidate approval and public-target gates prevent promotion.

Phase 8 and Phase 9 remain open. Continue only with safe local preparation until the candidate and external prerequisites are supplied and verified; do not treat the successful static render or CI scan-policy result as public acceptance.

## Carried-forward items — not waived

| Item | State | What must happen before resuming |
| --- | --- | --- |
| Keycloak image findings | 50 MEDIUM/LOW findings remain open; current pinned upstream image has no refreshed digest or supported package update identified. | A vendor-supported image/package fix, or an explicit release-owner disposition allowed by policy for every finding. Keep all rows visible and rerun scans after any image change. |
| Immutable candidate | No registry digests, provenance or candidate approval exist. | Confirm the approved registry and publishing identity; publish only the exact green build, verify all six digests/SBOMs, and record the release-owner decision. |
| Azure target access and budget | No Azure CLI/Az context is available here; subscription, region/SKU and budget are unverified. | Make authenticated deployment access available and approve the target subscription, resource group, region/SKU, cost estimate and budget alert. |
| Public identity and edge | DNS, TLS, production issuer/realm, runtime secret custody and external firewall are unverified. | Provide controlled DNS/certificate setup, production Keycloak realm/client, runtime secret delivery, SSH allowlist and NSG/firewall rules. |
| Public deployment smoke | Not run; there is no target. | After the candidate and target prerequisites pass, deploy by digest and run public TLS/OIDC, persona, external-port/header, persistence and lifecycle checks. |
| Target recovery and qualification | Not run on Azure. | Carry the independent fresh-target restore, RPO/RTO, measured load/failure profile and final approve/reject decision into Day 5/Phase 12. |

These items remain release gates. Carrying them forward does not close Day 4, approve the candidate, or waive any P0 criterion.

## Day 4 goal

Create a traceable, reviewed candidate and prove the Azure HTTPS/OIDC boundary on that same candidate. Close Phase 8 and Phase 9 only when their own exit criteria have dated evidence. Target restore, RPO/RTO, performance and final approval remain for Day 5 and Phase 12.

## Work sequence

### 1. Reconfirm release readiness

- Confirm branch, source SHA, clean working tree, exact successful CI runs and required artifacts.
- Refresh source and all six runtime-image scans. Review the 50 Keycloak rows against current vendor-supported package/image updates; record package, version, evidence, applicability and disposition. Apply a fix only when supported, then rebuild and rerun the affected checks on the new exact commit.
- If findings remain, keep them visible. No ignore rule or silent waiver. The release owner must resolve them through a supported disposition permitted by the project policy before candidate approval. Otherwise stop candidate promotion and report the release blocker.
- Verify all source/image SBOMs and scan reports match the frozen source and image identities. Produce registry digests and provenance only for that exact build; confirm the registry artifacts can be pulled by digest.
- Obtain an explicit candidate decision from the release owner. Until it is recorded as approved, the candidate is not deployable.

### 2. Pass the Azure dependency gate

Confirm and record the following before provisioning or exposing a host:

- Azure subscription access, named region/SKU, current resource estimate, available budget and budget alert.
- Controlled hostname and DNS changes, certificate issuance/renewal path, stable public OIDC issuer and initialized production realm/client.
- Runtime secret delivery and custody, persistent volumes, private backup destination, and a reachable owner alert destination.
- SSH source-IP restriction, NSG/firewall rules, and a planned demo window with stop/deallocate ownership.

If a prerequisite is unavailable, complete only the safe local/static preflights below. Do not publish a temporary URL, use a development identity issuer, expose a dependency port, or deploy unapproved build images.

### 3. Deploy the approved candidate

Only after Steps 1 and 2 pass:

- Render and review the separate production Compose overlay, then deploy the approved image digests and record the source SHA, CI run, image/SBOM digests, Azure resources and configuration identity.
- Configure HTTPS ingress on the edge. Keep app, database, Redis, Kafka, Keycloak administration, Prometheus, Grafana and Alertmanager private; restrict SSH to the approved source IP.
- Load secrets at runtime from the approved secret path. Confirm secrets do not appear in source, image layers, browser assets, command history or logs.
- Start services, verify migrations and readiness, then check public DNS, certificate chain, HTTPS redirect, HSTS, CSP/CORS and stable issuer/JWKS behavior.

### 4. Verify the public browser and network boundary

- Run signed login/logout and role-scoped smoke journeys for customer, merchant, driver, dispatcher and operator on the public hostname. Check refresh/re-authentication, API errors, rate limits, SSE reconnect, and truthful unavailable/stale states.
- From outside the host network, verify only approved edge ports and routes answer. Attempt direct app, database, broker, identity-admin and metrics access; verify forwarded-header spoofing does not change client identity or rate-limit decisions.
- Confirm the browser uses the deployed source/image digests and that the smoke result links to the exact candidate and target. A local fixture or private-stack pass cannot substitute for this evidence.
- Exercise owner start, readiness, deallocate and restart. Verify persistence after restart, expected URL unavailability while deallocated, and actual residual disk/IP charges.
- Configure off-host backup scheduling and missed/failed/age alerting. Capture the first target backup result if deployment is available; reserve independent fresh-target restore and measured RPO/RTO for Day 5.
- Begin target metric collection and load-harness warm-up only after the public boundary passes. Do not call this Day 5 qualification.

## Safe work if deployment is blocked

If candidate approval or Azure access is still unavailable, finish the release preflight without public exposure:

1. Refresh and disposition the image findings, or produce a precise blocker report if no supported disposition exists.
2. Verify candidate metadata and CI/SBOM/scan identity checks; document the missing registry, approval or provenance evidence.
3. Re-render the production overlay, check secret-file and private-port rules, and validate the deployment and lifecycle runbooks locally.
4. Finalize the Azure resource estimate and list the exact external owner/input needed for each blocked item.
5. Record Day 4 as **PARTIAL — DEPLOYMENT BLOCKED** with evidence. Keep public deployment, Phase 9 completion and release approval open.

This fallback prepares the next safe action; it does not turn local checks into target acceptance.

## Day 4 deliverables

- Dated readiness ledger with source SHA, exact CI runs, scanner/SBOM identities, finding dispositions, candidate decision and external prerequisites.
- If promoted: immutable registry digests/provenance, Azure resource inventory and cost estimate, deployed configuration identity, public TLS/OIDC and external port/header results, and signed browser-smoke evidence.
- Start/deallocate/restart and persistence evidence; off-host backup schedule and alert evidence if the target is available.
- Updated issue list naming each unresolved gate, owner, required evidence and next action.
- A clear decision: **Phase 8/9 verified for the exact candidate**, or **PARTIAL — DEPLOYMENT BLOCKED**. Do not mark Day 3 complete, close Phase 10/11, or approve the public demo here.

## Exit checks

| Gate | Day 4 pass condition |
| --- | --- |
| Phase 8 candidate | Exact source SHA has all required green checks; current scans and SBOMs match the build; every open image finding has a supported, recorded disposition; registry digests and provenance are verified; candidate approval is explicit. |
| Phase 9 boundary | Approved digests run on the target; stable public HTTPS/OIDC works; only intended edge routes are public; secrets remain runtime-only; private dependencies/admin/metrics routes resist external access; start/deallocate/restart behavior and cost are recorded. |
| Day 4 status | Every claim links to dated evidence for the same source, image digests and target. Any failed or unavailable P0 gate leaves deployment blocked and the result partial. |

Phase 10 does not pass on a first backup alone. Phase 11 does not pass on warm-up or private load. Phase 12 remains unapproved until every applicable Phase 0–11 MUST gate has exact-candidate evidence.

## Out of scope for Day 4

- New product features, broad redesign, or changes to the synthetic-only product boundary.
- Public deployment from mutable tags, an unscanned commit, a development OIDC issuer, or an unapproved image.
- Full replacement-host recovery, sustained RPO/RTO proof, target performance/failure qualification, or final approve/reject decision; these belong to Day 5/Phase 12.
- Suppressing scanner findings or weakening authorization, network, budget, backup, or acceptance thresholds to meet the calendar.

## References

- [Five-day schedule](EXECUTION-SCHEDULE-5-DAY.md)
- [Phase 8 — CI, release artifacts and supply chain](phase-08-ci-release-and-supply-chain.md)
- [Phase 9 — Azure public deployment boundary](phase-09-azure-public-deployment.md)
- [Phase 10 — Target operations, backup and recovery](phase-10-target-operations-backup-and-recovery.md)
- [Phase 11 — Target performance and failure qualification](phase-11-target-performance-and-failure-qualification.md)
- [Phase 12 — Final public-demo acceptance](phase-12-final-public-demo-acceptance.md)
