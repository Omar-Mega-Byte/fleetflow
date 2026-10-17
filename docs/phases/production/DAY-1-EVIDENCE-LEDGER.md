# Day 1 authoritative evidence ledger — 2026-10-14

> Current Days 1-3 audit: [2026-10-17 full audit](DAYS-1-3-FULL-AUDIT-2026-10-17.md) supersedes stale current-state claims below. Historical commits, commands and results remain dated evidence. Day 3 remains PARTIAL / NOT COMPLETE; Phase 11 remains CLOSED_PARTIAL.

## Final Day 1 status

**DAY 1 CONDITIONALLY COMPLETE — EXTERNAL DEPENDENCY**

Repository-controlled Day 1 work passes. No unresolved Day 1 code/security/correctness P0 remains. Azure subscription/credit, approved region/SKU estimate, DNS/certificate control, private secret delivery and a reachable target alert destination remain unconfirmed. They block public provisioning/target acceptance. Day 2 local persona implementation may proceed.

No public deployment, commit/push, remote Actions/CodeQL run, registry publication or release-candidate approval occurred. Target restore, notification delivery and 30-minute Azure qualification remain open.

## Source and evidence identity

- Base commit: `4efc0b0067ff451d4328761fd04597d2a4d05493`; branch `codex/phase11-acceptance-closeout`; **dirty/uncommitted**.
- Executable source SHA-256: `a9e676bd9b200122176783324567c53701195ab65f96d9d2df505acc2615c3ba`.
- Source manifest: `%LOCALAPPDATA%\FleetFlowDay1Audit\2026-10-14\source-manifest.json` (nonignored files excluding docs; AUDIT_ONLY). Relevant executable sources were unchanged after their final verification.
- Full logs, scan reports, seven SBOMs, private scrape and screenshots: `%LOCALAPPDATA%\FleetFlowDay1Audit\2026-10-14`.
- [Redacted machine evidence](evidence/day1-consolidation-2026-10-14.json) contains every row's full source commit/hash, UTC timestamp, owner, command, evidence path, scan/SBOM hashes, runtime digests and complete changed-file list.
- [Audit reconciliation](DAY-1-CONSOLIDATION-AUDIT.md) records initial defects and repairs; [scan disposition](evidence/day1-security-disposition-2026-10-14.json) records 157 remediated initial finding entries with no ignore policy.
- [Prompt 3 ledger snapshot](evidence/DAY-1-PROMPT-3-SNAPSHOT.md) is preserved as history; its Docker-blocked result is superseded.

## Gate rows

Every row uses the base commit and executable manifest above; `PASS` means executed evidence, never an inferred remote/target result. UTC dates are 2026-10-14 unless shown otherwise.

| Phase | Requirement | Status | Owner | Command/job | UTC | Evidence | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1–5 | Full backend, concurrency/auth/limits/migrations/recovery | PASS | Engineering | `.\mvnw.cmd clean verify --batch-mode --no-transfer-progress` | 15:04:14Z | `clean-verify-consolidated.log` | 143 tests; 0 failures/errors/skips; BUILD SUCCESS; 6:18 elapsed |
| 2/5 | Final reset/async/JWKS/OpenAPI/listener regression | PASS | Engineering | `.\mvnw.cmd -Dtest=DemoResetSafetyTests,Phase8AsyncEventsTests,OidcOutageTests,OpenApiDocsTests,EventListenerStartupTests test --batch-mode --no-transfer-progress` | 15:21:01Z | `regression-final.log` | 20 tests; 0 failures/errors/skips; 1:04 elapsed |
| 1 | Fresh and supported-v11 schema upgrade | PASS | Engineering | `MigrationUpgradeTests, FleetFlowApplicationTests, Phase10UpgradeRestoreTests in clean verify` | 15:08:17Z | `backend-results.json` | 14 migrations; forward-only V13/V14; historical migration files unchanged |
| 2/3 | Route/object/JWT/role matrix and OpenAPI | PASS | Engineering | `Phase1ApiTests, Phase2SecurityTests, Phase11RouteBoundaryTests, OpenApiDocsTests, OidcOutageTests in clean verify` | 15:08:17Z | `backend-results.json` | 64 enabled test-profile operations; unknown/foreign/inactive/revoked/wrong-role/issuer/audience/expiry boundaries |
| 4 | Health/metric/log privacy semantics | PASS | Engineering | `DatabaseOutageTests, OperationalMetersTests, CorrelationIdFilterTests, ApiErrorsTests; real private scrape` | 14:55:06Z | `healthy.prom` | DB readiness is DOWN/503; liveness process-only; optional outage restart UP; fixed labels and safe request logs |
| 4 | Alert syntax and firing/recovery fixtures | PASS | Engineering | `Prometheus 3.15.0 promtool check rules /rules/alerts.yml; promtool test rules /rules/alerts.test.yml` | 14:07:01Z | `promtool-test.log` | 9 rules; DB/outbox/application-5xx/quarantine firing and clearing; not notification delivery |
| 4 | Dashboard query and signal mapping | PASS | Engineering | `Promtool PromQL parser plus live-scrape/source-meter reconciliation` | 15:13:51Z | `dashboard-signals.json` | 15 panels; 26 queries parse; native consumer lag present; unused counters may be absent before first operation |
| 5/6/9 | Real HTTPS Keycloak/customer/SSE and outage restart | PASS | Engineering | `python scripts/Run-Day1BrowserSmoke.py` | 14:57:11Z | `result.json` | 1 real browser test; scoped authorization; storage/reload/logout/privacy; 413; 49/100 edge-pressure rejections; generation 2 retained; outbox 0 and 4/4 processed markers |
| 6 | Clean locked install and dependency audit | PASS | Engineering | `cd web; npm ci; npm audit --audit-level=high` | 15:16:06Z | `npm-audit-final.log` | 240 packages installed; 0 audit findings |
| 6 | Frontend lint | PASS | Engineering | `cd web; npm run lint` | 15:15:42Z | `frontend-lint-final.log` | ESLint passed |
| 6 | Frontend typecheck | PASS | Engineering | `cd web; npm run typecheck` | 15:15:47Z | `frontend-typecheck-final.log` | TypeScript passed |
| 6 | Frontend behavior tests | PASS | Engineering | `cd web; npm run test` | 15:15:55Z | `frontend-test-final.log` | 39 tests in 9 files; auth/storage/guards/API/SSE/customer/states |
| 6 | Production frontend build | PASS | Engineering | `cd web; npm run build` | 15:15:59Z | `frontend-build-final.log` | 357.99 kB JS / 108.40 kB gzip; 12.49 kB CSS / 3.71 kB gzip |
| 6 | Transient signed PKCE smoke | PASS | Engineering | `cd web; npm run test:e2e` | 15:16:04Z | `frontend-test:e2e-final.log` | 1 passed; intercepted API is clearly separate from the real provider/backend test |
| 8 | Workflow syntax and pinned actions | PASS | Engineering | `actionlint 1.7.12 .github/workflows/verify.yml; official GitHub commit lookups` | 15:16:13Z | `action-pins.json` | 7 unique pinned action commits verified; Trivy 0.76.0 and Syft 1.54.1 pinned; six-image build-metadata script validated locally |
| 8 | Credential preflight | PASS | Engineering | `python scripts/Phase11-Secret-Audit.py` | 15:13:54Z | `secret-preflight-final.log` | No credential/private artifact finding; final path count is retained in the log |
| 8 | Strict scan: source | PASS | Engineering | `trivy 0.76.0 fs --scanners vuln,misconfig,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 15:15:46Z | `scan-source-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: app | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 14:59:03Z | `scan-app-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: keycloak | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 14:59:56Z | `scan-keycloak-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: edge | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 15:00:42Z | `scan-edge-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: postgis | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 15:00:49Z | `scan-postgis-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: redis | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 15:01:23Z | `scan-redis-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Strict scan: kafka | PASS | Engineering | `trivy 0.76.0 image --scanners vuln,secret --severity HIGH,CRITICAL --exit-code 1 --format json` | 15:01:32Z | `scan-kafka-final.json` | 0 high/critical vulnerabilities, detected secrets and high/critical source misconfigurations; no ignore policy |
| 8 | Source and all runtime SBOMs | PASS | Engineering | `Syft 1.54.1 dir:. / docker:<audit-image> -o cyclonedx-json=<file>` | 15:18:19Z | `source-final-sbom.cdx.json` | 7 valid CycloneDX artifacts; exact paths/hashes and image digests are in machine evidence |
| 8 | Remote Actions/CodeQL/candidate attestation | NOT_RUN | Release | `No commit/push or remote workflow dispatch performed` | 14:35:39Z | `CANDIDATE-METADATA.md` | Current tree is uncommitted; local checks do not prove remote CI or SAST findings |
| 9 | Local and production Compose render/isolation | PASS | Engineering | `.\scripts\Validate-ProductionCompose.ps1; docker compose --env-file .env.example -f compose.yaml config --quiet` | 14:53:07Z | `production-compose-final.log` | Only edge 80/443 published; internal services private; CPU/memory/log bounds; actual disposable startup also passed |
| 9 | Azure/DNS/certificate/secret/alert prerequisites | BLOCKED | Release owner | `Owner subscription/region/SKU/DNS/certificate/secret/alert checklist; no cloud command executed` | 15:18:27Z | `phase-09-azure-public-deployment.md` | Access and approved account/region cost inputs are unavailable; blocks provisioning/public acceptance, not local Day 2 persona work |
| 10 | Local encryption/tamper and retention integrity | PASS | Engineering | `.\scripts\Test-Phase11-Recovery-Preflight.ps1; .\scripts\Test-Phase11-RemoteRetention.ps1` | 14:53:08Z | `crypto-preflight-final.log` | AES-GCM round trip/tamper rejection; exact expired-project remote selector passed; no normal database restored |
| 10/11 | Realm/backup age/provenance/measurement guards | PASS | Engineering | `python -m unittest discover -s scripts -p test_day1_preparation.py -v` | 15:07:50Z | `preparation-tests-final.log` | 8 tests passed; unsafe candidate cannot stop services/create backup; age, hosts, expected rejection and percentile/signal accounting |
| 9/10 | Production shell syntax | PASS | Engineering | `bash -n ops/production/{fleetflow,backup,check-backup-age,app-entrypoint,keycloak-entrypoint,postgres-app-role}.sh` | 14:53:09Z | `bash-syntax-final.log` | All prepared shell scripts parse |
| 10 | Production age/rclone pipeline, schedule and fresh-host restore | NOT_RUN | Operations | `Prepared backup/age-check/isolated restore runbooks; no production credential execution` | 15:18:27Z | `phase-10-target-operations-backup-and-recovery.md` | Target credentials, exact candidate and isolated restore destination required |
| 11 | 30-minute Azure load and public failure qualification | NOT_RUN | Operations | `Phase11-Breadth.py --duration-seconds 1800 --reviewer-sessions 2 --business-transitions (future isolated target execution)` | 15:10:09Z | `phase-11-target-performance-and-failure-qualification.md` | 10 drivers/10 streams/>=100 operations prepared; current loopback harness is not an Azure/public adapter result |
| 4 | Target alert delivery and certificate/backup integration | BLOCKED | Operations | `Configure approved target notification destination and TLS/backup sentinel after provisioning` | 15:21:11Z | `phase-04-observability-and-operator-signals.md` | Rule evaluation and private signals pass; no target notification or certificate-expiry proof exists |

## Phase reassessment

| Phase/slice | Status | Remaining boundary |
| --- | --- | --- |
| 1 | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 2 | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 3 | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 4 | PARTIAL | Target notification delivery remains external; Day 1 signal/health/redaction/rule checks pass. |
| 5 Day-1 slice | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 6 Day-1 slice | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 8 Day-1 foundation | COMPLETE | Verified Day 1 scope; does not approve public deployment or a candidate. |
| 9 preparation | PARTIAL | Azure/DNS/certificate/secret/alert prerequisites are unconfirmed; repository preparation and private smoke pass. |
| 10 preparation | READY | Target execution/acceptance remains later work. |
| 11 preparation | READY | Target execution/acceptance remains later work. |

## Remaining work assignment

- **Must finish before Day 2 implementation:** none of the repository-controlled Day 1 P0 checks remains failing. Preserve and extend these tests with every new workflow.
- **Day 2:** the protected backend contract (Prompt 1) and one connected signed customer-to-delivery browser journey through operator, merchant, dispatcher and driver (Prompt 2) are complete for the verified local/test scope. Evidence: [`DAY-2-PROMPT-2-UI-ACCEPTANCE.md`](DAY-2-PROMPT-2-UI-ACCEPTANCE.md). Broader persona workflows remain a later scope.
- **External release-owner dependency:** subscription/credit and approved region/SKU, DNS/certificate path, runtime-secret delivery and target alert destination. Resolve before provisioning/public exposure.
- **Later release phases:** exact committed remote CI/CodeQL and published candidate, Azure boundary/issuer/cost checks, production off-host archive/schedule/notification and fresh-host restore, target load/failure/rollback qualification. No local result satisfies these gates.
- **Optional/post-release:** generic administration, maps, tracing, extra frameworks, decorative dashboards and speculative optimization.

## Day 2 contracts

Current PostgreSQL actor authorization, 403/404 policy, JSON ApiProblem/error/Retry-After semantics, durable scoped idempotency, conflict-based lifecycle commands, bounded SSE, same-transaction outbox, at-least-once/deduplicated projections, bounded audited replay and deterministic guarded five-persona reset are verified. The React shell uses memory tokens, runtime API decoders and bounded API/SSE cleanup. Seed is an ADMIN operation, not customer order creation. Other persona workspaces remain foundations. No generic fixture/wipe, arbitrary poison replay or exactly-once guarantee exists.

CI also retains `image-builds.json` with immutable local image IDs and any available registry digests. Its `BUILD_METADATA_ONLY` / `candidateApproved: false` label prevents it from being confused with an approved release candidate. The inline workflow script was executed against the six local audit images with explicit local-only fixture run values.

## Day 2 Prompt 1 — protected backend contract — 2026-10-15

**Status: COMPLETE for repository-controlled backend gates.** This closes the Day 2 backend correctness, authorization, API abuse, demo-data and event-recovery scope. It is not a public deployment or release-candidate approval.

### Source identity and inspection

- Base commit remains `4efc0b0067ff451d4328761fd04597d2a4d05493` on `codex/phase11-acceptance-closeout`; the checkout is still dirty and uncommitted.
- Prompt 1 verification snapshot (before Prompt 2 and Prompt 3 integration): base `4efc0b0067ff451d4328761fd04597d2a4d05493`, working branch `codex/phase11-acceptance-closeout`, dirty tree. Its audit-only non-document source fingerprint was `b589cdb978b0466ce42bbe7f97ef16ff31c3f6cc29b27cf84828ae5683f617bd`, recorded in `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\source-manifest-final.json`. That snapshot compared two backend files; later frontend and release work is recorded separately below.
- Inspected the Day 1 evidence ledger and consolidation audit, production roadmap and phase specifications, UI/API contract, all registered mutating routes, V1–V14 migrations, relevant security/identity/rate-limit/transaction/outbox/demo-seed code, and the corresponding integration tests. Compared the claimed Day 1 state to executable tests and current handlers.

### Defects found and changes made

- No backend runtime defect reproduced. Two evidence gaps were present: rate-limit tests did not assert recovery after the counter window expired or isolation between actors and route classes; the order rollback test did not assert that its idempotency reservation and related rows were also rolled back.
- Added a real Redis-backed route-budget boundary/expiry/isolation case. Strengthened the injected database-failure case to assert no order, item, lifecycle history, outbox event or `ORDER_CREATE` idempotency row remains.
- No schema or production behavior changed. No historical migration was edited.

### Verification

| Command/job | Result | Evidence |
| --- | --- | --- |
| `git diff --check` | PASS | No whitespace errors; line-ending notices only. |
| `.\mvnw.cmd -Dmaven.repo.local=<local Maven cache> -Dtest=Phase1ApiTests,Phase4LocationTests test --batch-mode --no-transfer-progress` | PASS; 24 tests, 0 failures/errors/skips | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\focused-final-contracts.log` |
| `.\mvnw.cmd -Dmaven.repo.local=<local Maven cache> clean verify --batch-mode --no-transfer-progress` | PASS; 144 tests, 0 failures/errors/skips; BUILD SUCCESS; 4:37 | `%LOCALAPPDATA%\FleetFlowDay2Audit\2026-10-15\clean-verify-final.log`; 2026-10-15 11:20:35–11:25:15 UTC |

The final full run reported 64 registered test-profile API operations in the live route-boundary sweep. It exercised disposable PostgreSQL/PostGIS, Redis, Kafka and Keycloak containers; the existing developer Compose project and persistent volumes were not reset.

### Phase status

| Scope | Status | Verified evidence and boundary |
| --- | --- | --- |
| Phase 1 — core correctness | COMPLETE | Same-key create replay, changed-payload conflict, concurrent duplicate create, rollback of domain/history/outbox/idempotency rows, guarded lifecycle and assignment races, database constraints, 100-contender assignment winner, fresh V1–V14 migration and populated supported-v11 upgrade pass. Non-idempotent lifecycle retries use truthful state conflicts. |
| Phase 2 — identity and object authorization | COMPLETE for backend contract | Signed issuer/audience/expiry/JWKS checks, current actor/role lookup, inactive actor rejection, foreign customer/branch/driver/order/offer/tracking scopes, and the 64-operation anonymous/wrong-role/inactive route sweep pass. Denied sweep leaves all public-table row counts unchanged. |
| Phase 3 — abuse and error semantics | COMPLETE for backend contract | Read/expensive-read/command/stream/demo/location budgets, first rejected boundary, `Retry-After`, actor/class isolation, window recovery, body limits, stable problem responses, Redis failure behavior and database/JWKS dependency errors pass. Route-limit Redis loss uses a bounded per-process fallback; location/heartbeat fail closed with 503. Public proxy/IP/login and request-deadline acceptance remains a later edge gate. |
| Phase 5 — safety-critical demo and async slice | COMPLETE | Deterministic authorized synthetic seed/reset, ownership collision checks, non-demo preservation, repeat/concurrent/interrupted reset, active-work refusal, failure audit, broker outage/recovery, duplicate delivery dedupe, bounded retries/quarantine and audited replay (maximum three replays per event) pass. Delivery remains at-least-once; arbitrary poison replay/skip is unsupported. |

### Frozen browser OIDC/backend contract

- Configure one exact trusted issuer, explicit JWKS URI and API audience `fleetflow-api`. Validate signature, exact `iss`, `aud`, expiry and nonblank `sub`; missing configuration prevents startup. The actor key is `(iss, sub)`.
- FleetFlow authorization roles come from the active server-side actor and current `actor_roles` rows. Token role claims, including their provider claim location, are ignored for authorization. `GET /api/v1/session` returns the effective sorted role list.
- `CUSTOMER` is scoped to its customer and orders; `MERCHANT` requires active membership in the exact branch; `DRIVER` is scoped to its own driver, offers and assigned deliveries; `DISPATCHER` is intentionally global in this single synthetic deployment; `ADMIN` controls demo and outbox recovery operations. This is not multi-tenant dispatcher isolation.
- Missing/invalid bearer tokens return 401 `UNAUTHENTICATED`; a valid token for an unknown/inactive actor or without a required role returns 403 `FORBIDDEN`; foreign and absent scoped objects use 404 where that prevents enumeration. Conflicts use 409, malformed input 400, oversized body 413, rate rejection 429 with `Retry-After`, and required-service failure 503. JSON errors use `ApiProblem` (`code`, `message`, `path`, `timestamp`, `correlationId`, `fields`).
- The API is stateless bearer auth and exposes no backend logout endpoint. The browser uses public OIDC client `fleetflow-web`, authorization code with PKCE S256, and the exact configured redirect/post-logout origins; it requests an access token for `fleetflow-api`. Access/refresh credentials stay in memory, silent renewal and session monitoring are disabled, expiration requires interactive reauthentication, and logout clears local credentials before IdP end-session. A 401 ends the request; mutations are not automatically retried. Only the PKCE transaction state uses tab session storage.
- Keycloak may include provider role claims such as `realm_access.roles`; FleetFlow does not authorize from them. Its effective role source is the database mapping returned by `/api/v1/session`.
- Provider-side user disable alone does not immediately revoke an already-issued JWT. Immediate FleetFlow revocation requires setting the local actor inactive or removing its role; otherwise the signed token remains usable until it expires. Keycloak provider and local-actor tests cover this contract.

### API handoff

All authenticated personas can call `GET /api/v1/session` to read their effective server-side roles.

| Persona | Backend operations available to the browser |
| --- | --- |
| Customer | `GET /api/v1/orders`, `GET /api/v1/orders/{id}`, `POST /api/v1/orders/{id}/cancel`, `GET /api/v1/orders/{id}/tracking`, `GET /api/v1/orders/{id}/tracking/stream`, `GET /api/v1/notifications/me`. `POST /api/v1/orders` exists only in `local`/`test`; it is not a default/prod order-intake contract. |
| Merchant | `GET /api/v1/merchant-branches/{branchId}/orders`, `/zones`, `/zone-policy`; `POST /api/v1/merchant-branches/{branchId}/orders/{orderId}/confirm`, `/start-preparation`, `/ready-for-pickup`, `/cancel`, plus branch `/zones` and `/serviceability`; `PUT /api/v1/merchant-branches/{branchId}/zones/{zoneId}` and branch `/zone-policy`. Exact active branch membership applies. |
| Driver | `GET /api/v1/drivers/me`, `/api/v1/drivers/me/offers`, `/api/v1/drivers/me/deliveries`; `POST /api/v1/drivers/me/online`, `/available`, `/pause`, `/offline`, `/location`, `/heartbeat`; `POST /api/v1/drivers/me/offers/{offerId}/accept` and `/reject`; delivery `POST /api/v1/drivers/me/deliveries/{deliveryId}/pickup`, `/start-transit`, `/complete`, `/fail`. All are self/assignment scoped. |
| Dispatcher | `POST /api/v1/dispatch/orders/{orderId}/match`, `/offers`, `/retry`, `/reassign`, `/cancel`, `/incidents/fail`; `GET /api/v1/dispatch/orders/{orderId}/attempts`, `/assignments`, `/incidents`; `POST /api/v1/dispatch/orders/{orderId}/incidents/{incidentId}/resolve`; `GET /api/v1/dispatch/analytics/daily`. Scope is global only for this single demo. |
| Operator (`ADMIN`) | `GET /api/v1/demo`; `POST /api/v1/demo/seed` with `Idempotency-Key` and `{reason}`; `POST /api/v1/demo/reset` with `Idempotency-Key`, confirmation `RESET FLEETFLOW DEMO` and a reason; `GET /api/v1/operations/outbox/failed`, `GET /api/v1/operations/events/quarantined`, and `POST /api/v1/operations/outbox/{eventId}/replay` with a reason and `Idempotency-Key`. Same-key/same-payload calls are safe; changed-payload key reuse conflicts. Reset touches only owned synthetic records and refuses active demo work with `DEMO_WORK_ACTIVE`. |

### Remaining release boundaries

- Day 2 Prompt 2 real-browser acceptance passed against disposable local/test services; see [`DAY-2-PROMPT-2-UI-ACCEPTANCE.md`](DAY-2-PROMPT-2-UI-ACCEPTANCE.md). This verifies the connected workflow only and does not establish production readiness or complete all persona workflows.
- Remote Actions/CodeQL and immutable candidate approval, Azure/DNS/TLS/alert setup, target backup and replacement-host restore, and 30-minute target load/failure qualification remain open exactly as recorded above. Phase 11 remains `CLOSED_PARTIAL`; local verification does not satisfy those gates.
- Treat local actor deactivation as required whenever an IdP account is disabled and immediate access revocation is required.

## Day 2 Prompt 3 — release acceptance and repository integration — 2026-10-15

**Status before remote CI:** local Day 2 release gates passed; source/image scan artifacts, SBOMs and exact-commit CI are pending the pushed PR. Git integration is not complete until the PR is green and merged. No public deployment was performed.

The detailed owner/command/source/result/evidence table, production config review, six-image CI plan, backup/restore evidence, hygiene disposition, open issue owners, gate matrix and Day 3 handoff are in [`DAY-2-PROMPT-3-RELEASE-ACCEPTANCE.md`](DAY-2-PROMPT-3-RELEASE-ACCEPTANCE.md). Its local evidence ran from working branch `codex/day2-release-closeout`, based on `4efc0b0067ff451d4328761fd04597d2a4d05493`, with the intended Day 1 and Day 2 Prompt 1–3 working changes present.

The latest repeatable local results are: Maven `clean verify` passed 144 tests; the frontend passed 47 unit/component tests and 3 fixture Playwright tests; the real signed browser journey ended with the persisted order `DELIVERED|8`; production Compose validation found only edge TCP 80/443 host listeners; repository credential preflight passed across 466 paths; and the encrypted backup plus fresh disposable signed restore passed with a measured 56-second local functional RTO. The exact evidence paths and limitations are listed in the Prompt 3 acceptance record.

The current Day 2 matrix is Phase 1 PASS, Phase 2 PASS, Phase 3 PASS, Phase 5 safety slice PASS, Phase 6 vertical-client readiness PASS, Phase 7 first workflow PASS, Phase 8 release preparation OPEN until remote scan/SBOM/CI review, and Git/repository integration OPEN until merge and clean target verification. Phase 11 remains `CLOSED_PARTIAL`; the disposable local restore does not close sustained RPO or replacement-host recovery.
