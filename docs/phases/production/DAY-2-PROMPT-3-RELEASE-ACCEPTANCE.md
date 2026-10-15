# Day 2 Prompt 3 — release acceptance

**Date:** 2026-10-15 (Africa/Cairo)<br>
**Status before corrective remote rerun:** local release checks pass; the prior PR run exposed browser, scan-policy and CodeQL gates. Local fixes are verified; exact-head CI, merge and repository cleanup remain pending.<br>
**Deployment boundary:** private local rehearsals only. No public deployment was made.

This ledger ties the Prompt 1 backend contract and Prompt 2 browser workflow to a reproducible release path. Phase 11 remains `CLOSED_PARTIAL`; this document does not claim production readiness or immutable candidate approval.

## Source identity and scope

- Repository: `Omar-Mega-Byte/fleetflow`; target branch: `main`.
- Working branch: `codex/day2-release-closeout`; base: `4efc0b0067ff451d4328761fd04597d2a4d05493`.
- The previous remote run used `a9c4813bde6bd13a39a094f504ba12c1d73ce11e`. Local corrective changes are currently uncommitted on top of that branch head; the corrective push SHA and its CI/SBOM identities will be recorded after the run completes.
- The intended Day 1 and Day 2 changes were preserved together. No unrelated tracked changes were present at initial inspection.

## CI and release tooling

`.github/workflows/verify.yml` now runs pinned, release-significant jobs:

- **Backend, migrations and API contracts:** repository credential preflight, Day 1 safety, Day 2 trace-sanitizer and Trivy policy-gate tests, local and production Compose validation, and clean Maven verification with Testcontainers.
- **Frontend checks and browser smoke:** clean `npm ci`, `npm audit --audit-level=high`, lint, typecheck, component/unit tests, production build and fixture Playwright tests.
- **Source security:** Trivy filesystem dependency, configuration and secret scan emits full-severity SARIF. A repository gate blocks HIGH/CRITICAL findings, secrets and unclassified results, while preserving a sanitized SARIF inventory for review.
- **Source SBOM:** CycloneDX JSON.
- **Runtime images:** build six release images, run the Day 1 private-edge smoke and the Day 2 real signed journey, scan all six images, emit image SBOMs and record immutable local image IDs. Build metadata is explicitly `BUILD_METADATA_ONLY` and `candidateApproved: false`.
- **CodeQL:** Java/Kotlin and JavaScript/TypeScript.

The live browser config retains traces and screenshots on failure. Before artifact upload, the harness removes authorization/cookie data, OAuth secrets, request bodies and captured network resources; sanitizer tests cover JWTs, code/state values, headers, post data and response bodies. Raw Trivy reports stay on the ephemeral runner; secret results are removed before safe reports are archived. Scanner execution and policy failures remain failures. No environment file is uploaded.

## Local verification evidence

The command results below ran from the working tree based on `4efc0b0`; their output is retained under `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\` where noted.

| Gate / owner | Command or job | Result | Evidence |
|---|---|---|---|
| Phase 1–3, Phase 5 backend contracts and CSRF policy / backend owner | `./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --batch-mode --no-transfer-progress` | PASS — 145 tests, 0 failures, 0 errors, 0 skipped; fresh V1–V14 migration and populated v11→v14 upgrade passed. CSRF remains enabled outside bearer-only `/api/**` routes; a regression test confirms unsafe non-API requests are rejected without a token. | Surefire reports in ignored `target\surefire-reports\`; full command output in this task |
| Frontend / UI owner | `npm ci`; `npm run lint`; `npm run typecheck`; `npm run test`; `npm run build`; `npm audit --audit-level=high` | PASS — 47 unit/component tests across 13 files; 0 npm audit findings. | Local command output; `web/package-lock.json` unchanged by install |
| Fixture browser checks / UI owner | `npm run test:e2e` | PASS — 3 tests. The default config excludes both separately managed live harness directories. | Ignored `web/test-results\` |
| Production-edge signed browser gate / release and UI owners | `python scripts/Run-Day1BrowserSmoke.py` with six `fleetflow-day2-repro` images | PASS — customer PKCE/order/SSE and all four live persona workspaces; driver geolocation available and authenticated location POST returned 204; six axe audits, responsive map checks, proxy/body/rate checks, broker/cache outage, app restart and outbox recovery passed. Loopback only. | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\local-repro-verified-app\browser\result.json` and `playwright.log`; app image `dd511e79e251`, edge image `ecd2e56bb586` |
| Signed vertical workflow / UI and API owners | `python scripts/Run-Day2VerticalJourney.py` | PASS — real Keycloak PKCE login; operator seed and reset; customer order; merchant transitions; dispatcher assignment; driver acceptance, pickup, transit and completion; tracking SSE; logout; customer access denial; persisted final state. Six axe audits reported no violations; 390px customer and driver layouts had no horizontal overflow. | Final rerun evidence under `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\vertical-journey-final\`; order `c47ab8fb-dbde-4435-b842-0494ff95c9ad`, PostgreSQL `DELIVERED|8`, nine lifecycle states, one tracking stream |
| Trace artifact handling / release engineering | `python scripts/test_day2_trace_sanitizer.py` | PASS — sanitizer unit test passed. Successful journey produced no failure trace; sanitizer still ran before evidence persistence. | `vertical-journey\trace-scrub.log` and test output |
| Repository secret preflight / security owner | `python scripts/Phase11-Secret-Audit.py` | PASS — 468 paths inspected. | Local command output; repeated immediately before push |
| Production topology / platform owner | `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1` | PASS — rendered and validated; only edge TCP ports 80 and 443 are host published. PostgreSQL, Redis, Kafka, app, Keycloak/admin and management surfaces are private. No production service was started. | Local validator output; CI repeats the JSON-rendered configuration check |
| Backup and recovery / operations owner | `pwsh -NoLogo -NoProfile -File scripts/Run-Day2BackupRestoreSmoke.ps1` | PASS — encrypted archive round-trip and SHA-256; missing-project backup failure returned nonzero and made no archive; fresh empty target restored PostgreSQL/PostGIS and Keycloak; signed order read/cancel persisted; measured local functional RTO 56 seconds. | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\backup-restore\day2-disposable-backup-restore.json`; archive 147,010 bytes; hash recorded there |

The backup smoke used unique, loopback-only Compose projects and removed their exact containers, networks, volumes, temporary app image, key and scratch directory. It did not touch the existing development project or volumes. The backup contains PostgreSQL/PostGIS and Keycloak identity data; Redis location state and Kafka broker offsets are intentionally excluded by the existing backup design. This is disposable local preparation, not replacement-host recovery, sustained RPO, or Azure evidence.

The first integration attempt exposed an incomplete synthetic Keycloak user profile: the direct password grant returned `invalid_grant` because the test account was not fully set up. The harness now sets the required profile fields, clears pending actions, proves source login before backup, and then repeats the signed login against the restored identity. The final run passed; no application authentication behavior was weakened.

## Production configuration and secrets

The production Compose overlay was rendered and statically checked for exact service inventory, digest pins, private dependencies, health checks, restart/dependency order, resource bounds, read-only/scoped secret files, volumes/networks, OIDC issuer/audience/hostname, and edge redirect, TLS, HSTS, CSP and SSE proxy behavior. The only intended host listeners are edge TCP 80/443. The overlay itself was not publicly started and no public endpoint was contacted. The disposable browser rehearsal bound edge 80/443 to loopback only.

The live driver flow initially failed because the edge sent `Permissions-Policy: geolocation=()`, contradicting the application's explicit location-sharing action. The policy now allows `geolocation=(self)` for the application origin while continuing to deny camera and microphone; the real browser verified the location POST response.

Production credentials are file-backed through the configured secret directory; templates remain placeholders. The committed-file credential preflight passed. `npm audit` reported zero vulnerabilities. Trivy, CodeQL, and Syft were not available as local executables; the pinned CI jobs provide scan and SBOM evidence.

The first remote run (`38745117962`, branch SHA `a9c4813`) reported 57 Trivy findings across source and six images: 26 MEDIUM, 31 LOW, zero HIGH/CRITICAL, zero secret findings and zero unclassified results. The source report contains six LOW `DS-0026` Dockerfile HEALTHCHECK notices; Compose health checks are defined and are checked in the production rehearsal. The app image contains `CVE-2026-46675`, MEDIUM, `libpng` `1.6.58-r1`, with fixed version `1.6.59-r0`; runtime/platform owner to validate the base-package applicability and update path. Keycloak has 25 MEDIUM and 25 LOW RHEL/JDK package findings with no fixed versions in that scan; identity/platform owner to check vendor backports and applicability per finding in the preserved SARIF. Other four images had no findings. None were suppressed. The previous SARIF action failed on MEDIUM/LOW because it emitted all severities despite the configured filter; the new explicit gate preserves inventory and blocks HIGH/CRITICAL, secrets and unclassified results.

The same PR run reported CodeQL's HIGH `java/spring-disabled-csrf-protection` alert at the global `csrf.disable()` call. The configuration now keeps Spring CSRF enabled and excludes only stateless, bearer-only `/api/**` endpoints; local Phase 2 tests and the full Maven suite pass. The exact corrective CodeQL result is pending remote rerun. No applicable HIGH/CRITICAL finding may remain before merge.

## Repository hygiene

- 33 ignored legacy root/`logs` text logs were moved out of the repository into the local sanitized archive at `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\repository-cleanup\legacy-logs\`; a manifest records the inventory. Candidate credential strings were redacted and checked against local environment values before archival.
- `.env`, `.env.*` except the two placeholder templates, build output, Playwright result output, logs, local keys/certificates and IDE-specific output are ignored. No local secret file is staged.
- Design research and approved v2 UI evidence remain because they document the locked frontend direction and its visual/accessibility checks. They are project evidence, not temporary browser output.
- Existing developer Docker projects, volumes, images and active worktrees were preserved. Only the uniquely named smoke resources were removed. No system-wide prune was run.
- Generated Maven/frontend output is reproducible and ignored; it will be removed after final verification. `web/node_modules` is retained as an ignored local dependency cache.

## Open issues and owners

| Issue | Owner | Status / next gate |
|---|---|---|
| App `libpng` MEDIUM CVE and Keycloak package MEDIUM/LOW inventory | Runtime/platform and identity owners | Prior-run findings are in the run-bound Trivy SARIF; confirm applicability/vendor backports and record the disposition on the corrective PR run. |
| Six source LOW Dockerfile HEALTHCHECK notices | Release/platform owner | Informational `DS-0026`; Compose-level health checks are configured and the local production topology passed. |
| Trivy, CodeQL and secret-scan gates | Release/security owner | Corrected locally; exact corrective PR CI result pending. No HIGH/CRITICAL or secret finding may remain. |
| Preliminary source and six-image SBOMs, image IDs and build identity | Release engineering | Prior-run artifacts exist; regenerate/bind to the corrective commit. Metadata must remain `BUILD_METADATA_ONLY`. |
| Remaining real persona journeys, UI failure/empty/recovery states and full Playwright suite | UI/API owners | Day 3 work; this gate proves one real signed customer-to-delivery path only. |
| Target DNS, certificate, approved secret delivery, firewall/NSG and notification destination | Deployment/operations owner | External provisioning and review remain open. No public deployment was authorized or attempted. |
| Sustained backup schedule/RPO and replacement-host restore | Operations owner | Open under Phase 11 `CLOSED_PARTIAL`; the local 56-second smoke is not target-host evidence. |
| Alert firing/recovery against the target, measured target capacity, immutable release provenance/signature | Operations/release engineering | Day 3/target release gates; not claimed by this Day 2 closeout. |

## Day 2 gate matrix

| Gate | Status | Boundary |
|---|---|---|
| Phase 1 — core correctness | PASS | Local clean Maven verification, including idempotency/concurrency/rollback and migrations. |
| Phase 2 — auth and object scope | PASS | Signed local OIDC, current actor roles and object-scope/route-denial coverage. |
| Phase 3 — abuse/error behavior | PASS | API limits, errors, recovery and dependency failures; target proxy qualification remains Day 3. |
| Phase 5 — synthetic seed/reset and async safety slice | PASS | Guarded seed/reset plus outbox and event recovery tests. |
| Phase 6 — vertical-client readiness | PASS | Implemented role workspaces and clean frontend/browser checks; the whole product is not yet live-journey complete. |
| Phase 7 — first vertical workflow | PASS | One real browser flow completes through all four operational personas. |
| Phase 8 — release preparation | OPEN | Local rehearsal passed; remote scans, SBOM and exact-commit CI are pending. |
| Git/repository integration | OPEN | Commit, push, PR, CI review, merge and clean target verification remain. |

## Day 3 handoff

Start from the merged target branch and this ledger. Complete the remaining live persona workflows and their negative/failure UI states; run the broader Playwright suite and accessibility checks; produce observability alert fire/recovery evidence; remediate any applicable scanner finding; verify target backup/recovery and cost controls; then qualify immutable candidate provenance and feature-freeze readiness. Preserve Phase 11 `CLOSED_PARTIAL` until its separately scoped recovery gates are satisfied.
