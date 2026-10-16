# Day 2 consolidation audit

**Audit date:** 2026-10-16 (Africa/Cairo)

**Baseline:** clean local `main` and `origin/main` at `6be435b7c6306af89b4531399340415d9ca1f20b`; code-bearing consolidation merged at `0e0a21e63db7c7e4a2099c251d51618391d56213`

**Scope:** Day 2 Prompts 1–3, PR #23, contrast follow-up PR #37, and consolidation PR #38

**Boundary:** private local rehearsals only; no public deployment or release candidate approval.

This document records what was observed rather than treating previous completion statements as proof. The [Prompt 3 release ledger](DAY-2-PROMPT-3-RELEASE-ACCEPTANCE.md) retains historical commands and the itemized scan inventory. Consolidation [PR #38](https://github.com/Omar-Mega-Byte/fleetflow/pull/38) merged on 2026-10-16. Its [exact merged-main run 38918855000](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38918855000) passed all seven jobs on `0e0a21e63db7c7e4a2099c251d51618391d56213`. The earlier [run 38907630136](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38907630136) covers the pre-consolidation baseline only.

## Expected versus actual

| Day 2 obligation | What the audit found | Disposition |
|---|---|---|
| Atomic order/idempotency behavior | Database key reservation, response persistence and order transition share a transaction. Concurrent same-key retry and rollback tests are in the clean Maven suite. | Verified locally; no new backend defect identified. |
| Race and database enforcement | Offer reservation, lifecycle guards, unique constraints and rollback tests run with PostgreSQL/PostGIS. | Verified locally; target load remains later work. |
| Fresh and supported upgrade migrations | `MigrationUpgradeTests` migrate fresh V1–V14 and populated V11→V14, then start the application against upgraded data. | Passed in clean Maven verification. |
| Signed authorization and object scope | Current database actor roles, issuer/audience validation, denied-route sweep, foreign-object and inactive-user tests run. | Verified locally; public issuer/client provisioning remains Phase 9. |
| API abuse and error contracts | Body, page, geometry, rate and SSE bounds have tests. The OpenAPI order status filter listed only five statuses while the handler accepted twelve. | Corrected OpenAPI status list; Maven suite passed. |
| Safe synthetic seed/reset | Synthetic-only ownership and generation locking, interrupted rollback and concurrent reset have database tests. | Verified locally; public demo fixture remains disabled. |
| Outbox/Kafka recovery | Broker outage, lease expiry, duplicate send/delivery, quarantine, capped replay and rollback cases have tests. | Verified locally; single-node plaintext broker is a local boundary. |
| Operator replay feedback | API can return `REPLAYED`, `REPLAY_LIMIT_REACHED` or `NOT_REPLAYABLE` with HTTP 200. UI previously announced acceptance for all three. | Fixed with a strict decoder, result-specific feedback and three UI cases. |
| Browser PKCE and signed vertical journey | The isolated consolidation rerun passed customer→merchant→dispatcher→driver→tracking against fresh real services, then verified PostgreSQL `DELIVERED|8` and removed its exact Compose project. | Verified locally; public OIDC remains a later gate. |
| Auth/SSE/client lifecycle | In-memory bearer handling, session PKCE state, typed decoders, bounded reconnect and cleanup exist, with focused tests. | Verified in code and frontend suite; public target acceptance remains later work. |
| Fixture Playwright reproducibility | Three fixture tests passed, but the capture helper rewrote tracked design screenshots and accessibility evidence. During the first signed-journey rerun, a concurrent performance fixture using Playwright's default root `test-results` directory deleted live trace files and caused an `ENOENT` teardown failure. | Fixture, performance, Day 1 live and Day 2 live runs now have distinct Playwright output directories; generated captures remain in ignored output. The isolated signed journey, fixture suite and performance fixture then passed; each result directory remained present. |
| Live browser artifact privacy | Config retained automatic failure screenshots; CI uploaded the entire result/evidence directories even if trace scrubbing failed. One harness could raise before removing its disposable stack. | Disabled automatic failure screenshots; CI requires a positive scrub output and allowlists result JSON plus scrubbed ZIP; cleanup now runs after scrub failure. The merged-main browser artifacts contained only three allowlisted JSON results and no logs, screenshots or traces. |
| Production Compose/private ports | Validator rendered the overlay and reported edge 80/443 only; app, Keycloak and data services private, with issuer/proxy/secret/resource checks. | Passed static rehearsal; not a public TLS/issuer test. |
| Source/image scans and SBOM | The merged-main scan on `0e0a21e` reports six LOW source Dockerfile HEALTHCHECK notes; one MEDIUM application image libpng advisory; and 25 MEDIUM plus 25 LOW Keycloak image matches. Edge, PostGIS, Redis and Kafka image scans are empty. | All seven jobs passed; zero HIGH/CRITICAL and no reported secrets. MEDIUM/LOW advisory applicability and update paths remain open. Source and six image CycloneDX SBOMs exist; build metadata is not approved release provenance. |
| Backup/restore guard portability | Windows `core.autocrlf=true` converted six production `.sh` files to CRLF; WSL Bash rejected `set -o pipefail`, so two guard tests failed before their assertions. | `*.sh text eol=lf` added. All six working-tree scripts now LF, `bash -n` passed, and all 11 Python guard/sanitizer tests passed. |
| Clean-source backup evidence | The working-tree backup/restore smoke passed, but the first exact-commit rerun reached restored PostGIS and then failed recording metadata: clean `git status --porcelain` emits no lines, so `.Trim()` was called on `$null`. The failure phase label was still `verify restored PostGIS`, obscuring the actual location. | Joined status output to a string before trimming and set a distinct metadata phase. The clean `6197b8b` rerun passed, recorded `sourceWorkingTreeDirty: false`, and measured 78.3-second local functional RTO. |
| Git integration and evidence | PR #23 and PR #37 merged, but release ledger and roadmap still described PR #37/exact-commit CI as pending. | Historical record corrected; PR #38 merged at `0e0a21e`, exact-main CI passed, and the consolidation branch was removed remotely and locally. This final evidence update corrects the pre-merge wording. |

## Verification record

| Command / check | Result on consolidation source |
|---|---|
| `./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --batch-mode --no-transfer-progress` | PASS: 145 tests, 0 failures/errors/skips; packaged JAR. |
| `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test`, `npm run build`, `npm audit --audit-level=high` in `web/` | PASS: 51 tests / 14 files, production build, zero npm vulnerabilities. |
| `npm run test:e2e` in `web/` | PASS: 3 fixture tests after final output-directory isolation; tracked design evidence stayed unchanged. |
| `npm run test:performance` in `web/` | PASS: 1 production-build fixture. Its output remained separate from fixture and Day 2 live directories. |
| `python -m unittest discover -s scripts -p 'test_*.py' -v` | PASS: 11 tests after LF correction. Initial pre-correction run had two expected CRLF failures. |
| `python -m py_compile scripts/Run-Day1BrowserSmoke.py scripts/Run-Day2VerticalJourney.py scripts/scrub_playwright_trace.py` | PASS. |
| `bash -n` for all six tracked production `.sh` scripts | PASS through WSL. |
| `python scripts/Phase11-Secret-Audit.py` | PASS: 469 tracked paths inspected at this point. Repeat immediately before push. |
| `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1` | PASS: rendered only edge 80/443 as host ports; no service started. |
| `python scripts/Run-Day2VerticalJourney.py` | First rerun failed with missing Playwright trace files because the concurrent performance fixture cleared the shared output root; its disposable Compose project was removed. Isolated rerun PASS: Playwright 1/1 in 35.0 seconds, seed generation 1/reset 2, one customer SSE stream, logout, order `fb53a433-9194-4096-a269-41bd915e2491` persisted `DELIVERED|8` with nine lifecycle rows. Trace scrub passed; no failure trace existed. The exact rerun project was removed. |
| `pwsh -NoLogo -NoProfile -File scripts/Run-Day2BackupRestoreSmoke.ps1` | PASS on clean `6197b8b`: AES-256-GCM archive round-trip and SHA-256, expected negative backup preflight, fresh PostGIS and Keycloak restore, signed scoped order read/cancel, SQL `CANCELLED`; local functional RTO 78.3 seconds. Metadata records `sourceWorkingTreeDirty: false`. A prior dirty-source pass measured 59.1 seconds; the first clean rerun exposed the null metadata bug above. All runs removed their exact source/target Compose projects and temporary image. |
| Consolidation PR, scans/SBOM and exact merged-main CI | PR #38 merged at `0e0a21e63db7c7e4a2099c251d51618391d56213`. PR run `38916969070` and exact-main run `38918855000` each passed all seven jobs. Exact-main Day 1 browser artifact reports PASS; Day 2 browser artifact records seed 1/reset 2, one tracking stream, confirmed logout, and order `3a7d1505-5884-46e6-b8cc-27eeba7fd8ee` persisted `DELIVERED|8` with nine lifecycle statuses. Exact-main source and image SARIF plus source and six image SBOMs were inspected. |

## Remaining scope and release boundary

The code-bearing Day 2 consolidation passed local checks, PR checks, exact merged-main checks and a clean synchronized `main` check after PR #38. The signed journey and clean-source disposable backup/restore smoke passed locally. Day 3 planned work includes the remaining persona/failure journeys, complete accessibility acceptance, alert fire/recovery evidence, Phase 5 operator recovery acceptance, broader Playwright suite, scanner advisory disposition and immutable candidate controls. Azure DNS/TLS/issuer provisioning, target recovery, measured target load and public acceptance remain later release gates. Phase 11 remains `CLOSED_PARTIAL`. This evidence-only follow-up records the observed PR #38 outcome; it does not approve a release candidate.
