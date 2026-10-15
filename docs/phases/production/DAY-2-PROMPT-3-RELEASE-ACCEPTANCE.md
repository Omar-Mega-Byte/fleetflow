# Day 2 Prompt 3 — release acceptance

**Date:** 2026-10-15 (Africa/Cairo)<br>
**Status before remote CI:** local release checks passed; branch CI, scanner artifacts, SBOMs, and Git integration are pending.<br>
**Deployment boundary:** private local rehearsals only. No public deployment was made.

This closes the Prompt 1 backend contract and Prompt 2 browser workflow into a reproducible release path. Phase 11 remains `CLOSED_PARTIAL`; this document does not claim production readiness or immutable candidate approval.

## Source identity and scope

- Repository: `Omar-Mega-Byte/fleetflow`; target branch: `main`.
- Working branch: `codex/day2-release-closeout`; base: `4efc0b0067ff451d4328761fd04597d2a4d05493`.
- The release checks ran against the Day 1 and Day 2 Prompt 1–3 working tree before commit. CI and image/SBOM artifact identities will bind to the exact pushed commit and are recorded in the Git integration section below after PR completion.
- The intended Day 1 and Day 2 changes were preserved together. No unrelated tracked changes were present at initial inspection.

## CI and release tooling

`.github/workflows/verify.yml` now runs pinned, release-significant jobs:

- **Backend, migrations and API contracts:** repository credential preflight, Day 1 safety and Day 2 trace-sanitizer tests, local and production Compose validation, and clean Maven verification with Testcontainers.
- **Frontend checks and browser smoke:** clean `npm ci`, `npm audit --audit-level=high`, lint, typecheck, component/unit tests, production build and fixture Playwright tests.
- **Source security:** Trivy filesystem dependency, configuration and secret scan at HIGH/CRITICAL severity, with a failing exit code.
- **Source SBOM:** CycloneDX JSON.
- **Runtime images:** build six release images, run the Day 1 private-edge smoke and the Day 2 real signed journey, scan all six images, emit image SBOMs and record immutable local image IDs. Build metadata is explicitly `BUILD_METADATA_ONLY` and `candidateApproved: false`.
- **CodeQL:** Java/Kotlin and JavaScript/TypeScript.

The live Day 2 Playwright config retains trace and screenshot on failure. Before artifact upload, the harness removes authorization/cookie data, OAuth secrets, request bodies and captured network resources; sanitizer tests cover JWTs, code/state values, headers, post data and response bodies. Scan failures remain failures; artifact-preservation steps run independently so diagnostics remain available. No environment file is uploaded.

## Local verification evidence

The command results below ran from the working tree based on `4efc0b0`; their output is retained under `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\` where noted.

| Gate / owner | Command or job | Result | Evidence |
|---|---|---|---|
| Phase 1–3 and Phase 5 backend contracts / backend owner | `./mvnw.cmd --% clean verify --batch-mode --no-transfer-progress` (default local Maven cache) | PASS — 144 tests, 0 failures, 0 errors, 0 skipped; fresh V1–V14 migration and populated v11→v14 upgrade both passed. The upgrade test started the application, returned readiness 200, confirmed no pending Flyway migration and preserved the historical order amount. | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\verification\maven-clean-verify.out.log`; Maven Surefire reports in ignored `target\surefire-reports\` |
| Frontend / UI owner | `npm ci`; `npm run lint`; `npm run typecheck`; `npm run test`; `npm run build`; `npm audit --audit-level=high` | PASS — 47 unit/component tests across 13 files; 0 npm audit findings. | Local command output; `web/package-lock.json` unchanged by install |
| Fixture browser checks / UI owner | `npm run test:e2e` | PASS — 3 tests. The default config excludes both separately managed live harness directories. | Ignored `web/test-results\` |
| Signed vertical workflow / UI and API owners | `python scripts/Run-Day2VerticalJourney.py` | PASS — real Keycloak PKCE login; operator seed and reset; customer order; merchant transitions; dispatcher assignment; driver acceptance, pickup, transit and completion; tracking SSE; logout; customer access denial; persisted final state. Six axe audits reported no violations; 390px customer and driver layouts had no horizontal overflow. | Runner-configured `FLEETFLOW_DAY2_AUDIT_DIR` (defaults under `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\vertical-journey\`); order `cdbc65cf-2b5b-4e18-b8b2-23b2227182f2`, PostgreSQL `DELIVERED|8`, nine lifecycle states, one tracking stream |
| Trace artifact handling / release engineering | `python scripts/test_day2_trace_sanitizer.py` | PASS — sanitizer unit test passed. Successful journey produced no failure trace; sanitizer still ran before evidence persistence. | `vertical-journey\trace-scrub.log` and test output |
| Repository secret preflight / security owner | `python scripts/Phase11-Secret-Audit.py` | PASS — 466 paths inspected. | Local command output; repeated before push |
| Production topology / platform owner | `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1` | PASS — rendered and validated; only edge TCP ports 80 and 443 are host published. PostgreSQL, Redis, Kafka, app, Keycloak/admin and management surfaces are private. No production service was started. | Local validator output; CI repeats the JSON-rendered configuration check |
| Backup and recovery / operations owner | `pwsh -NoLogo -NoProfile -File scripts/Run-Day2BackupRestoreSmoke.ps1` | PASS — encrypted archive round-trip and SHA-256; missing-project backup failure returned nonzero and made no archive; fresh empty target restored PostgreSQL/PostGIS and Keycloak; signed order read/cancel persisted; measured local functional RTO 56 seconds. | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\backup-restore\day2-disposable-backup-restore.json`; archive 147,010 bytes; hash recorded there |

The backup smoke used unique, loopback-only Compose projects and removed their exact containers, networks, volumes, temporary app image, key and scratch directory. It did not touch the existing development project or volumes. The backup contains PostgreSQL/PostGIS and Keycloak identity data; Redis location state and Kafka broker offsets are intentionally excluded by the existing backup design. This is disposable local preparation, not replacement-host recovery, sustained RPO, or Azure evidence.

The first integration attempt exposed an incomplete synthetic Keycloak user profile: the direct password grant returned `invalid_grant` because the test account was not fully set up. The harness now sets the required profile fields, clears pending actions, proves source login before backup, and then repeats the signed login against the restored identity. The final run passed; no application authentication behavior was weakened.

## Production configuration and secrets

The production Compose overlay was rendered and statically checked for exact service inventory, digest pins, private dependencies, health checks, restart/dependency order, resource bounds, read-only/scoped secret files, volumes/networks, OIDC issuer/audience/hostname, and edge redirect, TLS, HSTS, CSP and SSE proxy behavior. The only intended host listeners are edge TCP 80/443. The overlay was not started, DNS/TLS were not provisioned, and no public endpoint was contacted.

Production credentials are file-backed through the configured secret directory; templates remain placeholders. The committed-file credential preflight passed. `npm audit` reported zero vulnerabilities. Trivy, CodeQL, and Syft were not available as local executables; their pinned CI jobs are the source of current repository/image findings and preliminary source/image SBOMs. Results remain pending until the pushed PR run completes. Any applicable HIGH/CRITICAL finding must be fixed or explicitly classified before merge; no finding may be suppressed merely for green CI.

## Repository hygiene

- 33 ignored legacy root/`logs` text logs were moved out of the repository into the local sanitized archive at `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\repository-cleanup\legacy-logs\`; a manifest records the inventory. Candidate credential strings were redacted and checked against local environment values before archival.
- `.env`, `.env.*` except the two placeholder templates, build output, Playwright result output, logs, local keys/certificates and IDE-specific output are ignored. No local secret file is staged.
- Design research and approved v2 UI evidence remain because they document the locked frontend direction and its visual/accessibility checks. They are project evidence, not temporary browser output.
- Existing developer Docker projects, volumes, images and active worktrees were preserved. Only the uniquely named smoke resources were removed. No system-wide prune was run.
- Generated Maven/frontend output is reproducible and ignored; it will be removed after final verification. `web/node_modules` is retained as an ignored local dependency cache.

## Open issues and owners

| Issue | Owner | Status / next gate |
|---|---|---|
| Current source, dependency, secret and six-image scan findings | Release/security owner | Pending exact-commit PR CI artifacts. Applicable HIGH/CRITICAL findings block merge. |
| Preliminary source and six-image SBOMs, image IDs and build identity | Release engineering | Pending exact-commit CI artifacts; metadata must remain `BUILD_METADATA_ONLY`. |
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
