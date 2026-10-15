# Five-day public-demo execution schedule

This is the execution order for Release Phases 1–12 in [PRODUCTION-ROADMAP.md](../../PRODUCTION-ROADMAP.md). Phase 0 remains complete as the audit and product-boundary decision only. This schedule does not claim that any release gate has passed.

## Feasibility and staffing assumptions

The five-day target is an aggressive sprint, not a solo-developer estimate. It assumes three implementation owners working concurrently (backend/security, frontend, and infrastructure/release), with a release owner coordinating evidence and acceptance. One person may perform the same sequence, but completing all required P0 gates in five or six calendar days is not a realistic commitment for one implementation owner.

Day 1 must start with Azure subscription access, an approved low-cost VM/region estimate, control of the demo DNS name, a certificate path, a private secret-delivery method, and a reachable alert destination. If any is unavailable, record the dependency immediately; do not substitute an unsafe public configuration or waive a gate. Reserve focused work time for each owner and start long-running checks as soon as their inputs exist.

The public demo is an on-demand single-VM synthetic showcase. Deallocating the VM takes the site offline; disk and possibly IP/DNS-related resources can still incur charges. No schedule step implies continuous availability or that the user's $100 credit will cover a particular resource configuration. Check the actual Azure estimate and remaining credit before provisioning and before each demo window.

## Workload reassessment

| Phase | Priority for this release | Existing work to reuse | Release work still required | Can start / must wait |
| --- | --- | --- | --- | --- |
| 1 — Core correctness | MUST, P0 | State machines, PostgreSQL constraints/transactions, concurrency controls, recovery records, Flyway, integration tests | Close duplicate-command/timeout gaps; prove rollback, races, same-key replay, no side effects, fresh and supported-upgrade migrations | Start Day 1; complete before freezing mutation contracts for UI and signed scope tests |
| 2 — Identity/object scope | MUST, P0 | OIDC validation, actor mapping, deny-by-default route allowlist, service/query authorization, existing 57-route sweep | Close resource-specific foreign-object and inactive/revoked actor tests; establish exact browser OIDC contract | Start Day 1 alongside Phase 1; final UI auth waits for contract; deployed browser proof waits for Phase 9 |
| 3 — API abuse/errors | MUST, P0 | Actor/location limits, bounded SSE, validation, structured problems | Verify route-class limits, Retry-After, payload/time bounds and Redis outage policy; add trusted proxy/IP controls in Phase 9 | API-side work starts Day 1; proxy-specific tests wait for Phase 9 |
| 4 — Observability | MUST for runbook signals; P1 polish | JSON/correlation logs, health, Micrometer, dashboards, alert rules | Verify real panel queries, private access, redaction, fire/recovery and owner action for critical failures | Start Day 1 after signal inventory; target alert delivery waits for Azure deployment |
| 5 — Async recovery/demo data | MUST for safe effects and deterministic seed/reset; P1 optional convenience beyond that | Transactional outbox, at-least-once Kafka, dedupe, quarantine, lease workers | Prove broker outage/replay/version behavior; implement narrowly scoped synthetic seed/reset and operator audit needed by browser demo | Tests start Day 1; mutation contracts depend on Phase 1; UI operator actions wait for safe stable endpoints |
| 6 — Frontend/auth | MUST, P0 | API/SSE are the product contract; no web app exists | Build one React/TypeScript app, OIDC PKCE with in-memory token storage, accessible shell, typed API/SSE clients, CI and tests | Shell/routing may start Day 1; real auth waits for Phase 2 contract and API error mapping waits for Phase 3 |
| 7 — Persona workflows | MUST, P0 | Backend business actions and driver simulation exist; no user-facing workflow exists | Customer, merchant, driver, dispatcher and operator journeys; safe seed/reset, conflict/error/reconnect states, browser and keyboard/mobile checks | Build a thin vertical slice on Day 1–2; complete only after Phases 5 and 6 contracts stabilize |
| 8 — CI/release/supply chain | MUST, P0 | Backend Actions, Compose validation, secret preflight, digest-pinned service images, non-root app image | Add frontend, critical browser, migration, source/image scan, SBOM, digest/provenance and immutable candidate gates | Extend CI on Day 1; final candidate waits for all Phase 1–7 artifacts |
| 9 — Azure public boundary | MUST, P0 | Local Compose stays loopback-only | Separate production overlay, HTTPS proxy, production Keycloak issuer, private dependencies, firewall/NSG, runtime secrets, lifecycle scripts and cost review | Design and provision preparation start Day 1; expose only the exact Phase 8 candidate after required gates pass |
| 10 — Target operations/recovery | MUST, P0 | Encrypted PostgreSQL backup, remote copy/check, signed local restore and health baseline | Scheduled-result/age alert, key custody, fresh Azure restore or proven reconstruction, identity/data consistency, lifecycle/rollback rotation rehearsal | Prepare runbooks/config Day 1; target restore requires Phase 9 deployment and Phase 8 artifact |
| 11 — Target qualification | MUST for stated Phase 0 thresholds and P0 security/reliability; optional optimization is P1/P2 | Existing bounded Python harness and local workload evidence | Measure actual target, public failure behavior, external ports/headers, load, cost and dependency recovery; issue a reproducible report | Prepare scripts Day 1–2; target execution waits for deployment, with recovery evidence from Phase 10 |
| 12 — Final acceptance | MUST, P0 | Prior local demo evidence is historical only | Re-run exact-candidate gates, decide APPROVED or REJECTED, publish truthful reviewer instructions | Assemble evidence continuously; approval waits for every applicable Phase 0–11 gate |

### Priority interpretation

- **MUST:** P0 requirements and any P1 slice directly required by the named release acceptance, dependency, or runbook (for example, tested alerts and measured target thresholds).
- **SHOULD:** beneficial hardening that can move only if it does not invalidate a stated exit criterion or safety policy.
- **COULD:** optional polish that can move after the public demo without changing security, correctness, recovery, or the accepted product boundary.

No P0 item moves to post-launch. A failed P0 gate means the candidate is not approved, even if the calendar target has elapsed.

| Phase | MUST | SHOULD | COULD |
| --- | --- | --- | --- |
| 1 | Durable transitions, duplicate/timeout outcomes, concurrency, migrations and no-side-effect proofs | Additional noncritical invariant diagnostics | Broad redesign or speculative optimization |
| 2 | Signed authentication, route roles, foreign-object denial, revocation and browser OIDC contract | Broader negative cases beyond the listed critical resource matrix | New identity provider or account-registration features |
| 3 | Bounded public work, safe problem responses, retry behavior, payload/time limits, dependency-loss behavior and trusted proxy controls | Extra per-route policy tuning not tied to a public risk | Analytics or a second rate-limit system |
| 4 | Private actionable signals, safe logs, correct health semantics and tested alerts/manual checks for required runbooks | Extra dashboard panels and noncritical alert tuning | Distributed tracing without a measured diagnostic need |
| 5 | No duplicate/lost business effects, bounded poison handling, and safe deterministic synthetic seed/reset required by reviewer journeys | Operator replay/skip UI unless a MUST recovery runbook depends on it | General fixture API or external event integrations |
| 6 | PKCE auth, memory-only token handling, typed API/SSE clients, protected navigation, accessible responsive shell and reproducible tests | Visual refinements beyond clear product quality | Theme system, animation library or broad component framework |
| 7 | All five persona journeys, wrong-persona denial, truthful mutation/error states and critical browser/accessibility acceptance | Extra convenience actions beyond the supported workflows | Advanced maps, charts or decorative motion |
| 8 | Required CI, migration, frontend/E2E, secret/source/image scans, SBOM, digest/provenance and immutable candidate | Extra scorecards or optional CI reporting | Unreviewed automatic production deployment |
| 9 | HTTPS/stable OIDC, private dependencies and management, network/secret safety, cost, exact artifact, start/deallocate | Repeat-deploy automation after the first safe runbook is exercised | Kubernetes, multi-region or autoscaling |
| 10 | Independent readable backup, missed/failed/age signal, fresh-target restore/reconstruction within agreed RPO/RTO, exercised critical runbooks | Additional restore drills beyond the required proof | Multi-region disaster recovery |
| 11 | Phase 0 target thresholds, public-edge/security checks, bounded failure recovery and approved cost | Additional capacity margin beyond accepted thresholds | Unmeasured caches or tuning |
| 12 | Exact-candidate evidence and explicit approve/reject outcome | None; acceptance has no optional gate | New feature work |

## Five-day main plan

### Day 1 — Freeze contracts; start all streams

**Consolidation result (2026-10-14): DAY 1 CONDITIONALLY COMPLETE — EXTERNAL DEPENDENCY.** Repository-controlled gates pass; Azure subscription/credit, approved region/SKU, DNS/certificate, secret-delivery and target alert access remain unconfirmed. See [current audit ledger](DAY-1-EVIDENCE-LEDGER.md). Day 2 persona implementation may proceed on the verified local contracts; public exposure remains gated.

**Primary goal:** turn the existing backend and operations foundation into a short, evidence-backed gap list while the web app and public deployment scaffolding begin immediately.

**Backend workstream evidence (2026-10-14):** see [Day 1 backend contract evidence](day-1-backend-contract-evidence.md). It records the live mutation inventory, idempotency/transaction contracts, signed identity and object-scope rules, API limits/errors, Phase 5 recovery gaps, and test results. This evidence does not close Phases 1–5 or the Day 1 release gate while the other workstreams and listed backend blockers remain open.

**Active phases:** 1, 2, 3, 4, 5, 6, 8, 9, 10, 11.

**Parallel workstreams:** A maps correctness/auth/API gaps; B inventories signals and async recovery; C scaffolds the browser app; D extends CI from the first frontend commit; E checks Azure/DNS/cost and prepares a separate public overlay; F turns the existing load and backup scripts into repeatable target commands.

**Backend tasks (A/B):** inventory every mutating route, transaction/idempotency behavior, API error/rate class, signed persona/object boundary, and Phase 5 replay/reset endpoint gap. Run the focused correctness, authorization and rate-limit tests against the current checkout. Add only missing tests and the smallest fixes. Freeze the browser OIDC contract, roles/claims, stable API error mapping, seed/reset safety boundary, and event disposition API. Start Phase 4 meter/redaction/alert checks and Phase 5 broker/replay failure checks once contracts are identified.

**Frontend tasks (C):** create the lockfile-based React/TypeScript app, accessible responsive shell, persona route guards, shared loading/empty/error/conflict states, typed API boundary from current OpenAPI, and browser test scaffolding. Add login/logout wiring against the agreed contract, using PKCE and memory-only browser token storage. Begin the first real customer order flow as soon as the API contract permits; do not spend the day on a standalone component library.

**Infrastructure / DevOps tasks (D/E):** make CI run backend verify, Compose validation and secret preflight on every change; add frontend `npm ci`, lint, typecheck, unit tests and build as soon as files exist. Review scan policy and required jobs. Separately prepare the production Compose overlay, proxy/HTTPS/issuer topology, private ports, NSG/firewall rules, runtime secret template, VM lifecycle/cost estimate and deployment scripts. Keep all secrets out of Git and command-line history. Confirm subscription permissions, domain/DNS, certificate issuance and alert delivery manually.

**Testing and background jobs:** run focused backend suites immediately; launch full Maven verification once Day 1 fixes compile. Run route inventory and OpenAPI consistency scripts; start source/secret scans and Compose config validation. Run frontend unit/type/build checks on every app change. Prepare (but do not yet claim target results for) migration, load, port, backup-age and restore automation.

**Required deliverables by end of day:** contract/gap ledger with each failing test tied to an owning phase; Phase 1–3 route and authorization matrix; frontend app and CI jobs green at least once; production overlay draft that keeps dependencies private; Azure cost/resource estimate and named external dependencies; target backup/load command checklist.

**Release gates closed:** Phase 0 scope remains unchanged. Close Phase 1 only if all acceptance evidence passes. Close Phase 2 only if signed foreign-object/role/revocation tests and browser contract are evidenced. Close Phase 3 only for the API-side bounds/error behavior; proxy edge portion remains open. Phase 8 frontend CI foundation is installed, not phase-complete. Phase 9–11 remain open.

**What must not slip:** signed object-denial coverage, same-key/competing-command correctness, frontend scaffold and CI on Day 1, and Azure/DNS/cost prerequisites. A critical auth, data-integrity, secret, or public-boundary finding blocks public release.

**Optional stretch:** remove low-risk UI friction or improve dashboards only after the contract ledger and mandatory checks are green.

**Risk / fallback:** if Phase 1–3 uncovers a systemic design defect, reproduce and patch the narrow failure, then rerun affected tests. No broad domain rewrite. If subscription, DNS, certificate, alert destination or budget access is unavailable, start no public exposure; the six-day clock is at risk and must be reported.

### Day 2 — Close backend release gaps; complete the first vertical workflow

**Primary goal:** prove the protected backend contracts and finish one real browser journey that exercises the system end to end.

**Active phases:** 1–8, with 9–11 preparation continuing.

**Parallel workstreams:** backend completes Phase 1–3 gap closure and Phase 5 seed/reset safety; frontend consumes frozen contracts; CI adds browser journeys as soon as the workflow exists; infra rehearses the public Compose render and identity/edge configuration without publishing it.

**Backend tasks:** complete PostgreSQL rollback/uniqueness/concurrency and migration checks; signed anonymous/wrong-role/inactive/foreign-object denials with no state or event side effects; route-class limit boundaries, Retry-After, oversized request and Redis-down behavior. Implement the minimum synthetic seed/reset operation with authorization, audit and interruption/repeat safety. Prove outbox recovery, duplicate delivery and bounded poison handling. Do not add a general fixture API.

**Frontend tasks:** complete the first integrated thin slice: reviewer login, deliberate seed/reset, customer order/tracking, merchant transition, dispatcher assignment and driver offer/accept/completion. Show truthful pending/success/conflict/unavailable outcomes and use live SSE with bounded reconnect/cleanup. Keep each screen workflow-focused; reuse the same shell and primitives.

**Infrastructure / DevOps tasks:** exercise production Compose config validation, private-port and volume checks, TLS/issuer/redirect configuration in a non-public environment, and runtime secret loading. Add migration fresh-install/upgrade and browser E2E jobs to CI. Generate preliminary SBOM/scans to identify remediation lead time. Confirm that start/deallocate behavior and resource charges are understood.

**Testing and background jobs:** focused backend integration/authorization tests after each fix; full Maven verify in the background; frontend lint/type/unit/build; Playwright vertical journey on each stable milestone; scan current image/dependency versions; backup archive integrity and restore command smoke on a disposable local target.

**Required deliverables by end of day:** green focused Phase 1–3 suites; safe repeatable seed/reset contract; one passing signed end-to-end browser path through order completion/tracking; CI browser job; rendered production config and a scan findings ledger with applicability/owner/status.

**Release gates closed:** Phases 1–3 if their complete exit criteria pass; Phase 5 only if event and seed/reset safety, audit and data-boundary criteria pass; Phase 6 only if auth/storage/accessibility/client test criteria pass; Phase 8 remains open until all persona flows and release artifact proof.

**What must not slip:** no fake successful mutation, no customer/driver cross-scope exposure, no seed/reset that touches non-demo records, and the complete vertical workflow in a real browser.

**Optional stretch:** sharpen copy and small responsive details after the integrated journey is stable.

**Risk / fallback:** if a full vertical flow is blocked by an unimplemented endpoint, either implement its smallest safe supported action or record the owning P0 defect; never mock production success. If seed/reset cannot safely reset only synthetic data, keep reset behind an owner-only boundary and do not expose it as a reviewer action until fixed.

### Day 3 — Finish all personas; feature freeze

**Primary goal:** make every required persona workflow usable and have the complete critical browser suite running in CI before the end of the day.

**Active phases:** 4–8 and completion of any still-open Phase 1–3/5 defects; 9–11 preparation.

**Parallel workstreams:** frontend completes remaining persona pages and negative/failure states; backend stabilizes only API gaps found by the browser suite and validates recovery/observability; release engineering closes scanner/SBOM/migration and immutable artifact automation; ops finishes runbooks, backup schedule/alert and target load/failure scripts.

**Backend tasks:** finish operator inspection and prove failure/recovery signals for database, Redis, Kafka and outbox backlog. Add bounded audited replay/skip controls only if a MUST recovery runbook depends on them; otherwise record them as SHOULD and leave them out of this sprint. Lock API and event schema versions. Re-run the signed authorization matrix after any shared service/security change. No speculative caching, redesign or new domain capabilities.

**Frontend tasks:** complete merchant, driver, dispatcher and operator workflows; role-aware navigation is only presentation and every action remains backend-authorized. Add safe conflict/duplicate, 401/403, 409, 429, 503, empty, expired-auth, stale location, SSE reconnect and interrupted-reset states. Finish keyboard-only, focus, reduced-motion and narrow mobile checks. Remove dead navigation, placeholder screens, raw JSON and undocumented operator steps.

**Infrastructure / DevOps tasks:** finish frontend and Playwright CI gates; scan source and every runtime image; produce SBOM and digest/provenance metadata; test migration on empty and supported-upgrade databases; verify Compose has no unintended host ports. Complete Azure start/deallocate, certificate/secret handling, cost and rollback runbooks. Prepare immutable candidate metadata and target smoke/load/restore job inputs.

**Testing and background jobs:** clean Maven verify; `npm ci`, lint, typecheck, unit tests and production build; full Playwright persona plus wrong-persona journey; secret scan including policy fixtures; migration and Compose validation; source/image vulnerability scans; SBOM generation. Start repeated alert fire/recovery checks and local backup/restore integrity. Capture command, timestamp, commit and result as each gate passes.

**Required deliverables by end of day:** five persona journeys implemented; critical UI/browser suite green in CI; closed Phase 4/5 evidence; all Phase 8 candidate controls operational; security findings dispositioned; production overlay/runbooks and target test scripts ready; feature freeze except release-blocking corrections.

**Release gates closed:** Phase 4 if private real metrics, redaction and owner alert/recovery are evidenced; close the Phase 5 MUST slice if its safe effects and deterministic demo-data criteria pass (leave optional replay/skip visibly open if deferred); Phases 6–7 if all client, persona, negative and accessibility criteria pass; Phase 8 if every required job and immutable artifact metadata pass. Do not mark complete from code presence alone.

**What must not slip:** all five user journeys, negative authorization journey, deterministic synthetic data, release CI gates and removal of unresolved critical/high findings under policy.

**Optional stretch:** visual polish or nonessential animation only after feature freeze and green browser/CI suites.

**Risk / fallback:** if UI time grows, reduce screen count and visual flourishes by composing shared workflow views, but preserve each persona's required actions and failure feedback. If one required persona remains a placeholder or needs Swagger, Phase 7 is open and deployment cannot be approved.

### Day 4 — Deploy the exact candidate behind the public edge

**Primary goal:** promote only the green immutable candidate into the Azure target and prove public TLS/OIDC and network isolation.

**Active phases:** 8–10, with 11 target smoke beginning after deployment.

**Parallel workstreams:** release owner freezes the commit/image digests and checks CI; infrastructure owner deploys the separately reviewed overlay; frontend/backend owners run smoke and fix only candidate-blocking defects; recovery owner configures off-host backup and alert delivery.

**Backend tasks:** no feature work. Verify signed public OIDC/browser login and logout, issuer/audience/JWKS paths, role scope, error and rate behavior, health/readiness, SSE and migration state against the deployed candidate. Keep management, admin, app, database, Redis, Kafka and metrics routes private.

**Frontend tasks:** test deployed login and all five persona navigation/actions on the public hostname; verify HTTPS redirect, cookie/token behavior, CSP/CORS, refresh/relogin and user-safe failure states. Fix only a release-blocking browser defect and rebuild a new immutable candidate if code changes.

**Infrastructure / DevOps tasks:** provision within the approved estimate; deploy exact digest-pinned images; configure DNS/TLS, proxy, NSG/host firewall, SSH restriction, private dependencies, runtime secrets, volumes, quotas/timeouts and lifecycle scripts. Run external port/header scan and spoofed-forwarded-address tests. Confirm public URL works and start/deallocate procedure actually stops compute; recheck remaining disk/IP charges and budget alerts.

**Testing and background jobs:** required CI must be green on the frozen commit; production Compose render; migration and health smoke; signed browser smoke; external port/header/TLS scan; image digest check; secret/log/bundle scan; start then deallocate and restart persistence smoke. Begin target metrics capture and load harness warm-up only after public boundary is verified.

**Required deliverables by end of day:** deployed hostname and resource inventory; source commit and image digests; exact CI evidence; successful public signed login/critical smoke; external-port results; cost/resource estimate and start/stop evidence; owner runbook and alert contact.

**Release gates closed:** Phase 8 only for the deployed immutable artifact; Phase 9 only for the complete public edge, production OIDC, private ports, runtime secrets, persistence, cost and lifecycle criteria. Phase 10/11 are still open pending fresh-target recovery and qualification.

**What must not slip:** no deployment from an unreviewed/unscanned commit; no direct public dependency or management port; correct stable public issuer; runtime-only secrets; measured cost and stop behavior.

**Optional stretch:** automate a repeat deploy from the exact runbook after first successful deployment, if it does not delay recovery or qualification.

**Risk / fallback:** if TLS, OIDC, public issuer, network isolation or budget cannot be proven, deallocate or keep the environment private and fix the boundary. If an image changes, invalidate its prior runtime evidence and rerun affected gates on the new digest.

### Day 5 — Prove target recovery and qualification; assemble approval packet

**Primary goal:** demonstrate that the actual Azure candidate meets recovery, cost, security and Phase 0 workload targets, then submit the exact evidence for Phase 12 review.

**Active phases:** 10–12, with 4/8/9 re-opened only if a target failure identifies a defect.

**Parallel workstreams:** recovery owner runs scheduled backup/independent fresh-target restore; qualification owner runs bounded load/failure tests on the deployed candidate; product owner performs reviewer-path and accessibility checks; release owner checks evidence identity, exceptions, cost and residual findings.

**Backend tasks:** no new feature work. Observe outbox, retry, consumer dedupe, DB/Redis/Kafka/Keycloak/app restart and SSE recovery under bounded failure drills. Verify signed business flow after restore, PostGIS and identity mapping, no duplicate effect and data freshness. Fix only a reproduced failure and rerun affected suites.

**Frontend tasks:** execute the full reviewer path from documented sign-in/seed to completed order and operator inspection; exercise conflicts, rate limits, dependency failure, reconnect and deallocate/offline messaging; record responsive and keyboard evidence. Confirm displayed data is synthetic and the URL availability statement is truthful.

**Infrastructure / DevOps tasks:** confirm an independently readable scheduled backup and owner-visible missed/failed/age alert; restore or deterministic reconstruction to an isolated fresh Azure target with no source volumes; verify key custody and disk alert; record measured RPO/RTO. Run cold start, stop/deallocate/start, rollback/recovery checklist, exact image/secret/resource state and final Azure spend estimate.

**Testing and background jobs:** target profile at Phase 0 workload (2 reviewer sessions, 10 simulated drivers, 10 SSE streams, 30 minutes/100 expected operations); require at least 99% expected operations, p95 non-stream API ≤1 second, cold start ≤15 minutes, and cost within approved budget. Inject DB, Redis, Kafka, Keycloak and app restart/slow-client/full-ish-disk conditions using the existing bounded harness. Run final browser smoke, signed scope checks, source/image scans, external port/header review and backup integrity. Do not run disruptive drills against the only public demo window without a recovery point and schedule notice.

**Required deliverables by end of day:** measured target load/failure report; fresh-target restore/reconstruction timings; alert fire/clear evidence; final security/image/port disposition; reviewer instructions; evidence ledger linking exact commit, CI run, image digests, Azure resource configuration, dates and results; Phase 12 decision draft.

**Release gates closed:** Phase 10 only if scheduled/independent backup and recovery, RPO/RTO, identity/data consistency and operational runbooks pass; Phase 11 only if target thresholds, cost, failure recovery and security findings pass; Phase 12 only after every applicable Phase 0–11 MUST criterion is evidenced on the same candidate.

**What must not slip:** fresh-target recovery, target performance against the declared workload, exact-candidate linkage, unresolved critical/high disposition, tested start/deallocate and honest recruiter-facing instructions.

**Optional stretch:** additional load margin or cosmetic improvements only after every acceptance criterion is complete.

**Risk / fallback:** a failed restore or unmet threshold is a release blocker. Apply the smallest correction, repeat the affected test, and use Day 6 if needed. If capacity is inadequate, resize within the approved budget or formally revise Phase 0 and rerun impacted acceptance; do not silently lower thresholds. If required proof remains absent at the end of Day 6, mark Phase 12 REJECTED — NOT READY.

### Day 6 — Contingency and release decision only

**Primary goal:** absorb a bounded, evidenced blocker and finish acceptance; do not use this day to defer planned feature work.

**Active phases:** only the phase owning a failed gate and Phase 12. No new scope.

**Parallel workstreams:** assign each reproduced blocker to its owning backend, frontend, or infrastructure owner; release owner tracks candidate identity and rerun matrix. Keep the public VM deallocated while an unresolved critical boundary or recovery issue is being repaired.

**Tasks:** reproduce the exact failure; classify configuration versus code; make the smallest correct change; run its focused test; rerun every affected phase/CI/target gate; rebuild and redeploy a new immutable candidate if anything changed; repeat final cost, port, browser, backup/restore or load evidence affected by that change. Resolve external provisioning blockers only if they were already identified and have an available owner. No new features, broad refactor or optional polish.

**Background jobs:** final full CI, scans/SBOM, critical Playwright journeys, deployed smoke, restore check, and any affected load/failure rerun. Record fresh timestamps and the new commit/digest.

**Required deliverable:** one complete acceptance packet for one exact candidate and an explicit Phase 12 outcome: **APPROVED FOR ON-DEMAND PUBLIC DEMO** or **REJECTED — NOT READY**.

**Gate rule:** Day 6 does not waive P0, security, public-edge, recovery, performance, budget or browser requirements. If any mandatory result is unknown or failed at the end of Day 6, reject and keep the demo offline until corrected.

## Parallel workstream matrix

| Workstream | Day 1 | Day 2 | Day 3 | Day 4 | Day 5 | Day 6 |
| --- | --- | --- | --- | --- | --- | --- |
| A — Backend correctness/security (1–3) | Inventory and close highest-risk gaps; freeze contracts | Finish signed matrix, concurrency, limits and migration checks | Fix only browser/recovery defects; freeze API | Deployed auth/edge smoke; no feature scope | Failure/recovery observation; targeted fix only | Repair reproduced gate failure; rerun affected suites |
| B — Reliability/observability/data (4–5) | Map signals; start alert/outbox/seed tests | Finish deterministic seed/reset and event recovery | Fire/clear alert, operator contract and audit complete | Configure target alert/backup jobs | Scheduled backup, fresh restore, failure drills | Repeat failed recovery/alert evidence |
| C — Frontend/product (6–7) | Scaffold, auth/API shell, first customer slice | Complete cross-persona vertical journey | All five personas, errors, accessibility and CI E2E; freeze | Public URL/login/persona smoke | Reviewer acceptance and offline/deallocate behavior | Only blocker fixes and reruns |
| D — CI/release/supply chain (8) | Add FE checks and route/config scans | Add migration/browser/scanner jobs | Close SBOM, digest, provenance, findings | Freeze/promote exact candidate | Reconfirm current scans and evidence | Rebuild only if fixed; rerun impacted release gates |
| E — Azure/deployment/operations (9–10) | Permissions/DNS/cost, overlay, secrets/runbooks | Validate production config privately, prep backup | Finish deployment/lifecycle/restore scripts | Deploy exact artifact; external boundary checks | Fresh target recovery, spend/operations checks | Redeploy/recover only for blocker fix |
| F — Qualification/acceptance (11–12) | Automate route/load/backup evidence collection | Prepare target scripts and acceptance ledger | Run local release gates; feature freeze evidence | Target smoke and warm-up | Full target workload/failure qualification; decision draft | Affected gate reruns and final decision |

## Phase-to-day mapping

“Completed” below means the planned exit evidence is expected by that day; it is not a claim that evidence exists now. A failure pushes that phase and every dependent gate forward.

| Phase | Priority | Start | Target complete | Safe overlap | Hard merge/wait point |
| --- | --- | --- | --- | --- | --- |
| 1 — Core correctness | MUST / P0 | Day 1 | Day 2 | 2, 3 inventory; 6 shell; 8 CI; 9–11 prep | Stable mutation and duplicate-command contracts before final auth/UI acceptance |
| 2 — Identity/object scope | MUST / P0 | Day 1 | Day 2 | 1, 3; 4; 6 shell | Signed role/object rules and browser OIDC contract before real UI auth; deployed proof after 9 |
| 3 — API abuse/errors | MUST / P0 | Day 1 | Day 2 API; Day 4 proxy edge | 1–2; frontend shell; CI | Error/limit contract before final UI states; trusted-IP/edge checks after 9 config exists |
| 4 — Observability | MUST for required signals | Day 1 | Day 3 app/local; Day 5 target | 1–3 inventory, 5, 6–9 preparation | Target alert fire/recovery before Phase 10/11 acceptance |
| 5 — Async recovery/demo data | MUST for safety and seed/reset | Day 1 | Day 3 | 1–4 after stable contract; UI shell | Phase 1 event semantics and Phase 4 signals before operator UI/E2E complete |
| 6 — Frontend/auth | MUST / P0 | Day 1 | Day 3 local; Day 4 public | 8 CI and 9 prep; 1–5 fixes | Auth/error contracts from 2–3; Phase 7 joins stable Phase 5 APIs |
| 7 — Persona workflows | MUST / P0 | Day 2 | Day 3 | 8 CI, 9–11 prep | Requires safe Phase 5 demo/operator APIs and Phase 6 shell/auth; then unlocks final 8 |
| 8 — CI/release/supply chain | MUST / P0 | Day 1 | Day 3 candidate gates; Day 4 promotion | 6–7 implementation, 9–11 prep | Candidate promotion waits for all 1–7 required checks and immutable digests |
| 9 — Azure public boundary | MUST / P0 | Day 1 prep | Day 4 | 8 workflow and 10–11 preparation | Public deploy waits for Phase 2/6 auth and Phase 8 exact candidate |
| 10 — Target operations/recovery | MUST / P0 | Day 1 prep | Day 5 | 4–5–8 prep; Phase 9 design | Actual independent target restore waits for exact 9 deployment and backup candidate |
| 11 — Target qualification | MUST for Phase 0 targets/P0 security | Day 1 prep | Day 5 | Existing harness, 8–10 prep | Azure execution waits for 9; recovery/failure conclusion waits for 10 |
| 12 — Final acceptance | MUST / P0 | Day 1 evidence ledger | Day 5 decision; Day 6 only if blocker | Evidence collection runs continuously | Exact same commit, images, identity config and target must satisfy all applicable gates |

## Dependency and critical path

**Hard dependencies** block final acceptance or safe integration. **Soft dependencies** allow scaffolding/preparation but not closure.

| Dependency | Type | Scheduling consequence |
| --- | --- | --- |
| Phase 1 command/transaction contract → Phase 2 final scope assertions and Phase 5 event assertions | Hard | Inventory can overlap; final mutation/no-side-effect proofs use stable behavior |
| Phases 1–2 → Phase 3 stable bounded/error contract | Hard for closure | Tests and UI shell can begin in parallel; final client behavior waits on response semantics |
| Phase 2 OIDC contract → Phase 6 auth | Hard for real auth; soft for shell/routes/components | Scaffold immediately, integrate PKCE/session/logout only against the frozen contract |
| Phase 3 problem/rate contract → Phase 6/7 client states | Hard for acceptance | Build generic states early; map exact 401/403/409/413/429/503 and retry behavior after contract freeze |
| Phases 1–4 → Phase 5 complete operator recovery/demo data | Hard | Seed/reset, event replay and alert paths require correct durable semantics and signals |
| Phases 5 + 6 → Phase 7 | Hard | UI can prototype earlier, but browser acceptance uses real safe data and real auth/API |
| Phases 1–7 → Phase 8 candidate | Hard | CI jobs are added continuously; candidate cannot be approved until all functional gates pass |
| Phases 2 + 6 + 8 → Phase 9 public deployment | Hard | Azure configuration can be prepared before Day 4; public rollout uses only the scanned immutable candidate |
| Phases 4 + 5 + 8 + 9 → Phase 10 target recovery | Hard | Scripts/runbooks can be prepared early; independent Azure restore must follow deployment |
| Phases 3–4 + 8–10 → Phase 11 target qualification | Hard | Harness and reports are prepared early; load/failure proof is against the actual candidate and target |
| Phases 0–11 → Phase 12 | Hard | Assemble evidence continuously, decide once the exact-candidate matrix is complete |

```text
Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 ───────┐
                   └──────────→ Phase 6 ───────────────┴→ Phase 7
                         Phases 1–7 → Phase 8 → Phase 9 → Phase 10 → Phase 11 → Phase 12
```

The longest mandatory chain is **1 → 2 → 3 → 4 → 5 → 7 → 8 → 9 → 10 → 11 → 12**, with Phase 6 as a parallel branch after the Phase 2/3 contracts and a hard join at Phase 7. Phase 8 CI work, Azure preparation, backup/runbook authoring, load-harness preparation, and evidence capture all start before their final dependencies are complete. They do not let a later gate pass early.

## Daily release gates

| Day | Gate to close before calling the day complete |
| --- | --- |
| 1 | Phase 0 scope unchanged; current blockers and owners recorded; contract and route matrices exist; frontend and CI loops run; Azure cost/access/DNS prerequisites are verified or explicitly blocking; focused backend evidence captured |
| 2 | Phases 1–3 close with evidence; Phase 5 seed/reset safety is proven or remains an explicit blocker; one signed end-to-end browser journey passes; required frontend CI jobs run |
| 3 | All five persona journeys and the wrong-persona denial pass in browser; Phase 4/5 critical signals/data gates pass; Phase 6/7 exit criteria pass; required Phase 8 jobs, scans and immutable candidate metadata pass; feature freeze |
| 4 | Exact candidate is publicly deployed over HTTPS; production OIDC works; only intended ports/routes are public; external boundary checks, cost, start/deallocate and persistence are recorded |
| 5 | Scheduled/independent fresh-target backup restore or reconstruction passes within RPO/RTO; target workload and failure thresholds pass; alerts/recovery/cost/security evidence assembled; Phase 12 packet is complete |
| 6 | Only failed mandatory gates are repaired and rerun; exact candidate is either approved or rejected; no open critical/P0 item is waived |

## Scope allowed after public-demo acceptance

Only items outside the defined acceptance or explicitly noncritical may move:

- Additional animation, decorative polish and convenience features beyond the accessible, responsive five-persona workflows.
- Extra dashboard panels/alerts that do not cover a required Phase 5/10 failure action.
- Performance tuning beyond measured Phase 0 thresholds; speculative caches and optimization without a measured bottleneck.
- Extra load margin, analytics and documentation polish beyond truthful reviewer, start/stop, recovery and security instructions.
- Broader product features already out of scope in Phase 0/roadmap: payments, real customer or driver data, external courier integrations, commercial multi-tenancy, Kubernetes, multi-region DR, ML and advanced analytics.

Do not defer: correctness, signed object authorization, abuse controls, public edge/TLS/OIDC/private ports, safe synthetic reset, five critical browser journeys, release scans and artifact identity, backup/recovery, target thresholds, cost controls, or final evidence.

## Five largest schedule risks

| Risk | Likelihood / impact | Early signal | Response within this schedule |
| --- | --- | --- | --- |
| Five-persona UI from a blank frontend | High / critical | First real browser slice is not working by Day 2 | Keep one shell and shared workflow states; finish all required actions before polish. If any persona still needs Swagger, reject rather than relabeling it done. |
| Azure public OIDC, DNS/TLS and network boundary | High / critical | No subscription permission, controlled hostname, certificate path, stable issuer or cost estimate on Day 1 | Resolve external prerequisites immediately; use the separate overlay and private network checks; no public fallback that bypasses TLS/auth/port gates. |
| Fresh-host restore and identity/event consistency | Medium-high / critical | Backup schedule/keys/identity data are unclear or restore fails in disposable target | Exercise exact independent retrieval early on local/disposable target; keep DB, Keycloak, PostGIS and outbox checks in the restore checklist; block approval on failure. |
| Small VM misses target workload or credit budget | Medium-high / high | CPU/memory/DB pool saturation or resource estimate exceeds approved credit | Run the prepared harness immediately after Day 4 smoke; resize within budget or formally revise Phase 0 and rerun impacted gates; never silently lower targets. |
| Current source/image advisories or candidate drift | Medium / critical | Scanner reports a critical/high issue, a mutable tag, digest mismatch or evidence from another commit | Start scans Day 1; verify affected versions and disposition, fix or time-bound mitigate only when policy allows; any candidate change triggers affected CI and runtime reruns. |

## Automation and evidence plan

| Automation | Start | Output / evidence |
| --- | --- | --- |
| Spring route inventory, role/object test matrix, mutating-route/idempotency inventory | Day 1 | Route-to-handler-to-test ledger; missing route or test is explicit |
| OpenAPI/handler comparison, API problem and Retry-After checks, oversized-body and proxy-address tests | Day 1; proxy cases after overlay exists | CI-readable contract failures with no domain side effect |
| Maven focused suites and clean `verify`; Flyway fresh/upgrade smoke | Day 1 | Timestamped command, commit, test/migration report |
| Frontend `npm ci`, lint, typecheck, unit/component test and production build | As soon as app exists Day 1 | Lockfile-reproducible CI status |
| Playwright login, persona workflows, wrong-persona denial, errors/reconnect and mobile/keyboard checks | Smoke Day 1–2; full suite Day 3 | Browser report/screenshots for failures; green required job tied to candidate commit |
| Secret scan, source/dependency/image scans and SBOM/digest/provenance | Begin Day 1; final on candidate | Findings with exact component/version, disposition, image digest and source commit |
| Compose render, intended-port inventory, health/TLS/header/OIDC smoke and external port scan | Local/private Day 1–3; Azure Day 4 | Rendered config and dated target findings; no private endpoint is exposed |
| Backup age/integrity alert, independent download, fresh restore and post-restore signed workflow | Prep Day 1; target Day 5 | Archive hash, alert fire/clear, restore timestamps, measured RPO/RTO and post-restore workflow |
| Bounded load/failure harness and report generation | Prepare Day 1–2; target Day 5 | p95, success ratio, SSE and resource profile; failure/recovery evidence |
| Acceptance ledger generator/checklist | Day 1 | One row per gate containing owner, command/job, timestamp, commit, image digest, result and evidence location; no secret values |

Evidence is captured at gate closure, not recreated on Day 5. Logs, browser traces and reports must be scrubbed of tokens/secrets and must identify the exact source commit and deployed image digest.

## Final Phase 12 acceptance sequence

Run in this order against one frozen commit and its exact immutable image digests; any change invalidates the affected downstream evidence:

1. Confirm the product boundary, synthetic data policy, approved cost estimate, domain, Azure resources and exact candidate identity.
2. Confirm required CI is green: clean backend verify, frontend lockfile install/lint/type/tests/build, critical Playwright, migrations, Compose, secret/source/image scans, SBOM and digest/provenance.
3. Verify all signed authorization and foreign-object denials, command duplicate/concurrency behavior, API abuse/error bounds and no-side-effect assertions.
4. Verify production config, TLS/DNS/HSTS/proxy trust, stable OIDC/browser login/logout, private dependencies/management, external ports, secret placement and image digests.
5. Complete the reviewer browser flow across customer, merchant, dispatcher, driver and operator; exercise safe reset, conflict/rate/outage states, reconnect, keyboard and mobile acceptance.
6. Verify real dashboard/health/log redaction and alert fire/recovery for required dependency/outbox/backup conditions.
7. Verify scheduled independent backup; restore or reconstruct on a clean isolated target; measure RPO/RTO; validate identity, schema/PostGIS, outbox state and a signed end-to-end business workflow.
8. Run the agreed Azure workload and failure matrix; record success ratio, p95, cold-start, resource profile, alert/recovery behavior and current credit/cost estimate.
9. Exercise start, deallocate, persistence and restart; confirm the documented URL-offline behavior and remaining resource charges.
10. Resolve every critical/high finding according to policy; ensure no unknown result is interpreted as a pass and no high-risk exception lacks owner, mitigation and expiry.
11. Publish the evidence ledger and reviewer instructions with exact commit, CI run, image digests, hostname, limitations and final **APPROVED FOR ON-DEMAND PUBLIC DEMO** or **REJECTED — NOT READY** status.

## Failure escalation

For each failure: reproduce it; identify whether it is implementation or configuration; apply the smallest correct fix; rerun the focused check; rerun all affected phase and target gates; update candidate identity and evidence. Do not use the schedule to justify an architecture rewrite, silent threshold change, security exception without policy support, or a partial approval.
