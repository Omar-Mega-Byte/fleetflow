# Day 1 release evidence ledger — 2026-10-14

**Decision: CONDITIONALLY COMPLETE — EXTERNAL BLOCKER.** The Day 1 implementation is in the working tree and the frontend/independent checks pass. Full container-backed Maven verification and a live browser-to-database run are blocked by Docker Desktop's Testcontainers named-pipe HTTP 500. No public deployment or remote CI run was attempted. This is not public-demo approval.

## Evidence identity

- Repository: FleetFlow local checkout (repository-relative evidence paths)
- Branch: `codex/phase11-acceptance-closeout`
- Starting/base commit: `4efc0b0067ff451d4328761fd04597d2a4d05493`
- Candidate commit: **none**; all Day 1 Prompt 1–3 changes remain uncommitted in the existing working tree.
- Run date: 2026-10-14. Commands ran on Windows with Java 21.0.12, PowerShell, npm, Python and the Maven wrapper. Docker-backed tests could not connect to Docker Desktop.
- Image digests: base images used by the production draft are pinned. App/edge release image digests and candidate metadata are not available; `.env.production.example` contains deliberately unusable placeholders. No release candidate exists.

## Gate record

| Gate | Result | Evidence and remaining condition |
| --- | --- | --- |
| Phase 0 scope | PASS for Day 1 scope | Synthetic showcase boundary retained; no public deployment, commercial claim, real-customer data or Phase 7 persona workflow was started. This is not a Phase 0 re-approval. |
| Phases 1–3 backend contracts | PARTIAL | Prompt 1 focused contract/OpenAPI suites and the 129-test clean verify are recorded in [backend contract evidence](day-1-backend-contract-evidence.md). A later full verify for Prompt 2/3 failed during Testcontainers initialization; rerun the complete current tree on a working Docker Engine. |
| Phase 4 observability | PARTIAL | 15 Docker-independent tests passed, including log redaction, meters and SSE bounds. Prometheus 3.15.0 validated 9 alert rules and passed offline firing/recovery fixtures for database readiness, outbox age, sustained application 5xx and quarantine. Live dependency transitions, dashboard scrape and Alertmanager delivery remain unverified. |
| Phase 5 async/recovery/demo contract | IMPLEMENTED, RUNTIME UNVERIFIED | Bounded audited outbox inspect/replay and quarantine inspection, plus deterministic generation-based synthetic seed/reset are implemented. Full Docker-backed broker outage, duplicate/restart/lease/poison/replay, authorization, simultaneous reset, interruption and unrelated-row preservation cases did not run. See [Phase 5 evidence](phase-05-async-recovery-and-demo-data.md). |
| Phase 6 frontend/auth foundation | PASS for local frontend checks; integration PARTIAL | React/Vite, PKCE, protected customer order/tracking slice, typed API and bounded SSE clients are present. `npm ci`, audit, lint, typecheck, unit/component tests, build and signed transient-OIDC Playwright smoke passed. The smoke intercepts API responses; no live Keycloak/backend/database browser proof exists. |
| Phase 8 CI/supply chain | CONFIGURED, UNVERIFIED | Backend, frontend, Playwright, Trivy source/image scans, CodeQL, SBOM and Compose gates are configured with pinned actions. Current-tree workflow has not run remotely; scan reports, SBOM artifacts, image build results and advisory disposition do not exist. Action pins and inputs follow the [Anchore SBOM Action v0.20.6](https://github.com/anchore/sbom-action/tree/v0.20.6) and [Trivy Action v0.36.0](https://github.com/aquasecurity/trivy-action/tree/v0.36.0) interfaces. |
| Phase 9 public boundary | DRAFT VALIDATED, NOT DEPLOYED | Production Compose structural validator passed: only edge publishes 80/443 and stateful/admin services remain private. No Docker start, TLS/DNS/Azure, forwarded-header, direct-port bypass, external scan or live realm/client check occurred. |
| Phase 10 backup/recovery | DRAFT, NOT EXERCISED | Backup, age-check and restore runbooks are present; shell syntax passed. No encrypted archive, remote retrieval, schedule/alert or fresh-host restore was executed. Azure subscription, DNS/certificate and region/SKU/cost evidence are absent. |
| Phase 11 target qualification | PREPARED, NOT RUN | 30-minute profile is defined for two reviewer sessions, ten drivers and ten SSE streams. No Azure or target load/failure qualification ran; the present runner remains disposable/loopback only. |

## Commands and results

### Passed in this Day 1 work

| Command/check | Result |
| --- | --- |
| `npm ci` | Passed; 240 packages installed, zero reported vulnerabilities. |
| `npm audit --audit-level=high` | Passed; zero findings. |
| `npm run lint` | Passed. |
| `npm run typecheck` | Passed. |
| `npm run test` | Passed; 21 tests across 7 files. |
| `npm run build` | Passed; production bundle emitted. |
| `npm run test:e2e` | Passed; 1 Playwright smoke for signed PKCE login, protected customer route and logout. The API was intercepted. |
| Focused Maven: `CorrelationIdFilterTests,TrackingHubBoundsTests,OperationalMetersTests,LifecycleRulesTests,MatchingScoreTests,OidcStartupTests,Wgs84DistanceTests,LocalApiBoundaryTests` | Passed; 15 tests, 0 failures/errors/skips. |
| `python scripts/Validate-ProductionCompose.py` via `scripts/Validate-ProductionCompose.ps1` | Passed; production Compose structural boundary. |
| `python -m py_compile scripts/Phase11-Breadth.py scripts/Validate-ProductionCompose.py` | Passed. |
| `bash -n ops/production/fleetflow.sh ops/production/backup.sh ops/production/check-backup-age.sh ops/production/app-entrypoint.sh ops/production/keycloak-entrypoint.sh` | Passed after the backup destination boundary fix. |
| `python scripts/Phase11-Secret-Audit.py` | Passed; 294 repository paths inspected. |
| `git diff --check` | Passed; only Git's line-ending conversion warnings were reported. |
| Prometheus 3.15.0 `promtool check rules ops/monitoring/alerts.yml` and `promtool test rules ops/monitoring/alerts.test.yml` | Passed in Prompt 2; 9 rules parsed and offline fire/recovery fixtures passed. Not Alertmanager delivery or live firing. |

### Blocked or not run

| Command/check | Result |
| --- | --- |
| `./mvnw.cmd --% clean verify --batch-mode --no-transfer-progress` | **Failed at Testcontainers startup**, not an assertion failure: Windows `NpipeSocketClientProviderStrategy` received HTTP 500 from Docker Desktop. 133 tests were discovered: 0 assertion failures, 118 container-backed setup errors, 0 skipped; the 15 independent tests above passed. |
| Remote GitHub Actions workflow | Not run; current working tree is uncommitted. |
| Trivy, CodeQL and source/container SBOM jobs | Configured only; no scan or artifact result exists for this tree. |
| Live PostgreSQL/Kafka recovery, seed/reset concurrency/interruption, real Keycloak login, browser-to-database flow, dashboard scrape or Alertmanager delivery | Not run; Docker Engine unavailable. |
| Azure deployment, TLS/DNS, external boundary scan, backup/restore, cost validation or target load | Not run; no deployment was attempted and account/region inputs are unavailable. |

## Required Day 2 continuation

1. Restore Docker Desktop/Testcontainers connectivity and rerun `./mvnw.cmd --% clean verify --batch-mode --no-transfer-progress`; resolve any actual assertion failures before continuing.
2. Run focused Phase 5 suites for broker outage after business commit, duplicate delivery, restart/lease reclaim, poison/quarantine, replay audit/dedupe, repeat seed, simultaneous reset, interruption rollback, authorization and unrelated-record preservation.
3. Start an isolated disposable stack, verify real Keycloak PKCE login and customer-to-API/database/order/tracking rendering, then exercise health, dependency and alert firing/recovery paths.
4. Validate the full CI workflow and review real Trivy/CodeQL/SBOM outputs on a committed candidate; record the resulting commit and run URL here.
5. Resolve Azure subscription, region/SKU, budget, DNS and certificate inputs before any public boundary action. Keep deployment disabled until reviewed.

## Phase status changes

Phases 4–6, 8–11 remain `IN_PROGRESS`; Phase 7 and Phase 12 remain `NOT_STARTED`. No phase is marked complete based on draft configuration, offline alert evaluation or intercepted browser tests. The Day 1 gate is conditionally complete as implementation work, with container-backed verification and deployment prerequisites outstanding.
