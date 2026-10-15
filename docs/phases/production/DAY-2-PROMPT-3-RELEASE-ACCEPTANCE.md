# Day 2 Prompt 3 — release acceptance

**Date:** 2026-10-15 (Africa/Cairo)<br>
**Status at this ledger revision:** corrective CI passed on PR head `f2e01363f6cd25175e4893eed14baab1e9f0c489`; merge and repository cleanup remain pending.<br>
**Deployment boundary:** private local rehearsals only. No public deployment was made.

This ledger ties the Prompt 1 backend contract and Prompt 2 browser workflow to a reproducible release path. Phase 11 remains `CLOSED_PARTIAL`; this document does not claim production readiness or immutable candidate approval.

## Source identity and scope

- Repository: `Omar-Mega-Byte/fleetflow`; target branch: `main`.
- Working branch: `codex/day2-release-closeout`; base: `4efc0b0067ff451d4328761fd04597d2a4d05493`.
- The prior failed run used `a9c4813bde6bd13a39a094f504ba12c1d73ce11e`. Corrective PR head: `f2e01363f6cd25175e4893eed14baab1e9f0c489`. Successful GitHub Actions run: `38755514793`; its tested PR merge ref was `33b5e00273bc1193eb4639f497bd80d92330ff72` (base `4efc0b0067ff451d4328761fd04597d2a4d05493` plus the PR head).
- The intended Day 1 and Day 2 changes were preserved together. No unrelated tracked changes were present at initial inspection.

## CI and release tooling

`.github/workflows/verify.yml` now runs pinned, release-significant jobs:

- **Backend, migrations and API contracts:** repository credential preflight, Day 1 safety, Day 2 trace-sanitizer and Trivy policy-gate tests, local and production Compose validation, and clean Maven verification with Testcontainers.
- **Frontend checks and browser smoke:** clean `npm ci`, `npm audit --audit-level=high`, lint, typecheck, component/unit tests, production build and fixture Playwright tests.
- **Source security:** Trivy filesystem dependency, configuration and secret scan emits full-severity SARIF. A repository gate blocks HIGH/CRITICAL findings, secrets and unclassified results, while preserving a sanitized SARIF inventory for review.
- **Source SBOM:** CycloneDX JSON with its project identity and version taken from the Maven POM.
- **Runtime images:** build six release images, run the Day 1 private-edge smoke and the Day 2 real signed journey, scan all six images, emit image SBOMs and record immutable local image IDs. Build metadata is explicitly `BUILD_METADATA_ONLY` and `candidateApproved: false`.
- **CodeQL:** Java/Kotlin and JavaScript/TypeScript.

The live browser config retains traces and screenshots on failure. Before artifact upload, the harness removes authorization/cookie data, OAuth secrets, request bodies and captured network resources; sanitizer tests cover JWTs, code/state values, headers, post data and response bodies. Raw Trivy reports stay on the ephemeral runner; secret results are removed before safe reports are archived. Scanner execution and policy failures remain failures. No environment file is uploaded.

The corrective run passed all PR checks: backend/migrations, frontend and signed browser smoke, runtime image build and real browser journeys, source and image Trivy policy gates, source SBOM, Java/Kotlin and JavaScript/TypeScript CodeQL, GitGuardian, and the GitHub Advanced Security `CodeQL` alert aggregator. GitHub reported zero open code-scanning alerts after the CSRF correction. This run validates the code-bearing PR head above; the ledger-only update is also required to pass CI before merge.

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

The successful corrective run (`38755514793`) reported the same 57 Trivy findings across source and six images: **26 MEDIUM, 31 LOW, zero HIGH/CRITICAL, zero secrets and zero unclassified results**. Source and image SARIF were archived after secret-result redaction; downloaded copies are retained outside Git under `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\remote-ci-38755514793\`.

| Scan finding | Installed component / version | Severity and advisory | Affected status / fix version | Owner and disposition |
|---|---|---|---|---|
| Source Dockerfile policy (6 occurrences) | `Dockerfile`, `ops/production/kafka.Dockerfile`, `ops/production/keycloak.Dockerfile`, `ops/production/postgis.Dockerfile`, `ops/production/redis.Dockerfile`, `web/Dockerfile.release` | LOW `DS-0026` | Trivy reports no Dockerfile `HEALTHCHECK`; production Compose health checks exist and passed validation. No package fix applies. | Release/platform. Informational for the current Compose deployment; retain in SARIF and decide whether image-level checks are also required. |
| Application image | `libpng` `1.6.58-r1` | MEDIUM `CVE-2026-46675` | Package is present and Trivy marks it affected; fixed version `1.6.59-r0`. Application-level exploitability not separately assessed. | Runtime/platform. OPEN; validate base-image update path and rebuild. |
| Keycloak image | `coreutils-single` `8.32-41.el9_8.1` | MEDIUM `CVE-2026-56391` | Package is present and Trivy matches the advisory; vendor applicability/backport not verified. No fixed version reported. | Identity/platform. OPEN; review vendor status and record remediation or not-affected rationale. |
| Keycloak image | `glibc` `2.34-283.el9_8`; `glibc-common` `2.34-283.el9_8`; `glibc-langpack-en` `2.34-283.el9_8` | MEDIUM `CVE-2026-8674`, `CVE-2026-86805`, `CVE-2026-89092`, `CVE-2026-95818`; LOW `CVE-2026-97399` (each advisory matches all three packages) | Components are present and Trivy matches the advisories; vendor applicability/backport not verified. No fixed versions reported. | Identity/platform. OPEN; check Red Hat package advisories/backports for each component and record disposition. |
| Keycloak image | `java-21-openjdk-headless` `1:21.0.12.1.1-1.2.el9` | MEDIUM `CVE-2025-28164`, `CVE-2025-64505`, `CVE-2025-64506`, `CVE-2026-22693`, `CVE-2026-34757`, `CVE-2026-40930`, `CVE-2026-49919`, `CVE-2026-95512`; LOW `CVE-2026-27171` | Component is present and Trivy matches the advisories; vendor applicability/backport not verified. No fixed versions reported. | Identity/platform. OPEN; check vendor advisories and the supported Keycloak runtime update path. |
| Keycloak image | `libgcc` `11.5.0-14.el9` | MEDIUM `CVE-2026-102010`, `CVE-2026-95619`; LOW `CVE-2021-46195`, `CVE-2022-27943` | Component is present and Trivy matches the advisories; vendor applicability/backport not verified. No fixed versions reported. | Identity/platform. OPEN; verify vendor status for each advisory. |
| Keycloak image | `ncurses-base` and `ncurses-libs` `6.2-12.20210508.el9` | LOW `CVE-2023-50495` (both packages) | Components are present and Trivy matches the advisory; vendor applicability/backport not verified. No fixed version reported. | Identity/platform. OPEN; verify vendor status for both packages. |
| Keycloak image | `nspr` `4.39.0-5.el9_6`; `nss`, `nss-softokn`, `nss-softokn-freebl`, `nss-sysinit`, `nss-util` `3.124.0-5.el9_6` | LOW `CVE-2020-12413`, `CVE-2024-7531` (both advisories match each of the six packages) | Components are present and Trivy matches the advisories; vendor applicability/backport not verified. No fixed versions reported. | Identity/platform. OPEN; verify vendor status for each package/advisory pair. |
| Keycloak image | `pcre2` and `pcre2-syntax` `10.40-7.el9_8` | LOW `CVE-2022-41409` (both packages) | Components are present and Trivy matches the advisory; vendor applicability/backport not verified. No fixed version reported. | Identity/platform. OPEN; verify vendor status for both packages. |
| Keycloak image | `sed` `4.8-10.el9` | MEDIUM `CVE-2026-5958` | Package is present and Trivy matches the advisory; vendor applicability/backport not verified. No fixed version reported. | Identity/platform. OPEN; verify vendor status and runtime update path. |
| Keycloak image | `sqlite-libs` `3.34.1-11.el9_8` | MEDIUM `CVE-2026-50812`; LOW `CVE-2024-0232`, `CVE-2025-70873` | Package is present and Trivy matches the advisories; vendor applicability/backport not verified. No fixed versions reported. | Identity/platform. OPEN; verify vendor status for each advisory. |
| Keycloak image | `zlib` `1.2.11-40.el9` | LOW `CVE-2026-27171` | Package is present and Trivy matches the advisory; vendor applicability/backport not verified. No fixed version reported. | Identity/platform. OPEN; verify vendor status and update path. |

The grouped Keycloak rows account for 50 package/advisory findings (25 MEDIUM, 25 LOW). Other four runtime images had zero findings. These scanner matches establish package presence and advisory matching, not exploitability; none is marked not-affected without vendor evidence. The Day 2 policy gate blocks HIGH/CRITICAL, secrets and unclassified results, so these MEDIUM/LOW findings remain explicitly owned rather than suppressed.

The previous PR run reported CodeQL's HIGH `java/spring-disabled-csrf-protection` alert at the global `csrf.disable()` call. The configuration now keeps Spring CSRF enabled and excludes only stateless, bearer-only `/api/**` endpoints; local Phase 2 tests and the full Maven suite pass. The corrective CodeQL analyses and alert aggregator passed, with no open code-scanning alerts. CodeQL documents this rule as risky when CSRF is disabled for browser-facing services; API-only bearer routes remain excluded while CSRF protects non-API routes. [CodeQL rule guidance](https://codeql.github.com/codeql-query-help/java/java-spring-disabled-csrf-protection/).

Trivy's pinned action emitted MEDIUM/LOW SARIF despite the earlier severity input, so the gate now inventories all severities and applies policy separately. Only sanitized SARIF is uploaded; Trivy's SARIF represents secret matches under the `Secret` rule and includes match data in result content, which the checker removes before archival. [Trivy SARIF reporting](https://github.com/aquasecurity/trivy/blob/main/docs/guide/configuration/reporting.md), [Trivy SARIF implementation](https://github.com/aquasecurity/trivy/blob/main/pkg/report/sarif.go), [pinned Trivy Action source](https://github.com/aquasecurity/trivy-action/blob/ed142fd0673e97e23eac54620cfb913e5ce36c25/entrypoint.sh).

The run generated one CycloneDX source SBOM and six image CycloneDX SBOMs, plus sanitized source/image SARIF and the browser/build evidence. Application version is `0.0.1-SNAPSHOT` from `pom.xml`. The first source SBOM's metadata component was `.` with no version, so the source-SBOM job now supplies the project name/version from the POM through Syft configuration, and image-build metadata now records the same version; the next exact-head CI artifact must confirm this correction. Build identity metadata remains `BUILD_METADATA_ONLY`, `candidateApproved: false`; it contains local image IDs but no registry digests and is not immutable release provenance. The first run's image IDs and creation time are recorded in `image-builds.json`; it used source merge-ref `33b5e00273bc1193eb4639f497bd80d92330ff72` and completed at `2026-10-15T14:56:02Z`.

| Image | CI image ID |
|---|---|
| app | `sha256:094402c6ca45067521bd25a23bf799c8c30c2069df94a9b9b3668002508cd7dc` |
| Keycloak runtime | `sha256:50bca805144b340e7370006c0aea2597c564f25009a213e24c89d4e93492e452` |
| edge | `sha256:89f0362c6af03396b06a49bee2ac1d5f75224b5c410b98846165ab5d69b186d2` |
| PostGIS | `sha256:af09b7874068f449d8bb851c8a9814eececd50566972edd0225aeac8671db72d` |
| Redis | `sha256:2004d07cd91d9887d558e099e6975a7a48cc31f6b52aa8c8917707de7789de19` |
| Kafka | `sha256:d526bd0b6c1044bc7771ca84c2c977778f0740a7f57254ef2b0c664686255671` |

## Repository hygiene

- 33 ignored legacy root/`logs` text logs were moved out of the repository into the local sanitized archive at `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\repository-cleanup\legacy-logs\`; a manifest records the inventory. Candidate credential strings were redacted and checked against local environment values before archival.
- `.env`, `.env.*` except the two placeholder templates, build output, Playwright result output, logs, local keys/certificates and IDE-specific output are ignored. No local secret file is staged.
- Design research and approved v2 UI evidence remain because they document the locked frontend direction and its visual/accessibility checks. They are project evidence, not temporary browser output.
- Existing developer Docker projects, volumes, images and active worktrees were preserved. Only the uniquely named smoke resources were removed. No system-wide prune was run.
- Generated Maven/frontend output is reproducible and ignored; it will be removed after final verification. `web/node_modules` is retained as an ignored local dependency cache.

## Open issues and owners

| Issue | Owner | Status / next gate |
|---|---|---|
| App `libpng` MEDIUM CVE | Runtime/platform owner | Corrective run `38755514793` found `CVE-2026-46675` in `1.6.58-r1`, fixed version `1.6.59-r0`; OPEN to update/rebuild or record a justified disposition. |
| Keycloak package MEDIUM/LOW inventory (50 findings) | Identity/platform owner | Corrective run SARIF lists component, installed version, severity and CVE above; no fixed versions were reported. OPEN to verify vendor backports/applicability for each package/advisory pair and record remediation or not-affected evidence. |
| Six source LOW Dockerfile HEALTHCHECK notices | Release/platform owner | Corrective run `38755514793`; informational `DS-0026`. Compose-level health checks are configured and passed; decide whether image-level checks are also required. |
| Trivy, CodeQL and credential-scan gates | Release/security owner | PASS on corrective run `38755514793`; no HIGH/CRITICAL, secret, unclassified, or open CodeQL alert findings. |
| Preliminary source and six-image SBOMs, image IDs and build identity | Release engineering | Run `38755514793` retained all seven CycloneDX artifacts and `BUILD_METADATA_ONLY` IDs, but the source SBOM root lacked name/version. The workflow now supplies Maven project identity and adds `applicationVersion`; confirm the next exact-head artifacts. No registry digest or candidate approval is claimed. |
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
| Phase 8 — release preparation | OPEN | Local rehearsal, CI, scan policy and preliminary SBOM gates passed; full roadmap acceptance remains open, including medium/low vulnerability disposition, target deployment controls and immutable candidate provenance. |
| Git/repository integration | OPEN | Commit, push, PR, CI review, merge and clean target verification remain. |

## Git integration

- Working branch: `codex/day2-release-closeout`; target: `main`; PR #23.
- Four corrective commits are pushed after `a9c4813`: `85200c8` browser location gate, `e081e9b` sanitized Trivy policy, `8509661` scoped CSRF, and `f2e0136` release ledger update.
- PR run `38755514793` passed every listed required check against head `f2e01363f6cd25175e4893eed14baab1e9f0c489` and merge ref `33b5e00273bc1193eb4639f497bd80d92330ff72`.
- The source-SBOM identity and image-metadata correction are in the current follow-up; exact-head rerun, merge, branch cleanup, merged-target smoke and final clean-tree verification are pending.

## Day 3 handoff

Start from the merged target branch and this ledger. Complete the remaining live persona workflows and their negative/failure UI states; run the broader Playwright suite and accessibility checks; produce observability alert fire/recovery evidence; remediate any applicable scanner finding; verify target backup/recovery and cost controls; then qualify immutable candidate provenance and feature-freeze readiness. Preserve Phase 11 `CLOSED_PARTIAL` until its separately scoped recovery gates are satisfied.
