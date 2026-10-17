# FleetFlow production-readiness audit and roadmap

**Audit date:** 2026-10-17<br>
**Audit target:** Day 2 consolidation merged to `main` as PR #38 at `0e0a21e63db7c7e4a2099c251d51618391d56213`; exact-main run `38918855000` passed. This is a dated evidence checkpoint, not candidate approval.<br>
**Authority:** This is the source of truth for remaining product, hardening, and launch work. Existing phase documents remain implementation history and evidence.

> Current evidence: [Days 1-3 full audit](phases/production/DAYS-1-3-FULL-AUDIT-2026-10-17.md). Earlier merged-main checkpoints are historical and do not validate the updated branch. Day 3 remains PARTIAL / NOT COMPLETE; legacy Phase 11 remains CLOSED_PARTIAL.

The current ordered handoff for unfinished public-demo work is [PROJECT-COMPLETION-REMAINING.md](PROJECT-COMPLETION-REMAINING.md).

## Executive decision

FleetFlow is a substantial local delivery-orchestration demonstration. The backend implements guarded order and driver lifecycles, Redis-backed location and matching, PostgreSQL/PostGIS serviceability and atomic assignment, durable dispatch recovery, a transactional outbox, Kafka projections, role/object-scoped APIs, bounded SSE tracking, rate controls, and local operations tooling. The React/TypeScript client has PKCE auth, protected customer, merchant, dispatcher, driver and operator workspaces, typed API/SSE clients, and three signed live journeys across all five personas, zone/serviceability editing and dispatch recovery, proven against disposable services.

It is **not a finished product or a public-production deployment**. The current private browser gate verifies all five personas and documented negative/recovery cases. Exact updated-commit CI, current advisory disposition and immutable candidate controls remain required. Fixture Playwright checks remain separate from that live signed path. The local Compose stack remains loopback-bound, Keycloak remains in development mode and Kafka is single-node plaintext. The separate production proxy/Compose overlay has been rendered and statically validated, with only edge TCP 80/443 host ports; it has not been started or externally checked. Consolidation CI passed on exact merged `main` commit `0e0a21e` in [run 38918855000](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38918855000).

**Recommendation:** keep the modular monolith and verified core workflows; finish exact-candidate supply-chain review and then prove identity, edge, secrets, operator recovery, disaster recovery and complete browser-to-database flows on the actual hosting target. Do not call the result production-ready until the gates in this document pass.

The first public release is scoped as a **synthetic portfolio showcase**. It does not accept real customer orders or prices, payments, real driver/customer location data, merchant contracts, or commercial service guarantees. A single manually started Azure VM can host an on-demand portfolio demo; it is a single point of failure and does not establish a production availability SLA. A commercial logistics service needs a separate product and deployment decision.

## Audit basis and evidence limits

- Day 2 release work started from `4efc0b0067ff451d4328761fd04597d2a4d05493` and preserved the existing Day 1/Prompt 1/Prompt 2 work. Prompt 3, its contrast follow-up, and consolidation merged through PR #23, #37 and #38. Their evidence and remaining limits are tracked in the [Day 2 consolidation audit](phases/production/DAY-2-CONSOLIDATION-AUDIT.md), [release acceptance](phases/production/DAY-2-PROMPT-3-RELEASE-ACCEPTANCE.md) and [Day 1 evidence ledger](phases/production/DAY-1-EVIDENCE-LEDGER.md).
- Before the Day 1 backend contract edits, Phase 11 documents recorded a 2026-10-14 local clean verify with 125 tests and a signed local restore with a 114.9-second functional RTO. Day 1 Prompt 1–3 results, current blockers and commands are tracked in the [evidence ledger](phases/production/DAY-1-EVIDENCE-LEDGER.md) and [backend contract evidence](phases/production/day-1-backend-contract-evidence.md); neither local run proves hosted release acceptance.
- Merged `main` run `38918855000` passed all seven jobs on `0e0a21e63db7c7e4a2099c251d51618391d56213`. It covers consolidation PR #38, including the earlier Prompt 3 and contrast correction code. Its source/image scans, browser results and SBOMs were inspected; advisory disposition and release candidate controls remain open.
- GitHub issue listing returned no repository issues at audit time.
- The original pre-Day-1 inventory found no frontend. Prompt 2 added the `web/` app, lockfile, component tests and Playwright smoke; Prompt 3 added CI and the production deployment/operations drafts.
- GitHub, CI, and issue observations reflect the connected account at the audit time. Recheck them before release.
- “Not found” means absent from the inspected checkout. A proposed roadmap item is not an implemented control.

### Day 1 consolidation status

**DAY 1 CONDITIONALLY COMPLETE — EXTERNAL DEPENDENCY.** Repository-controlled Day 1 checks pass. The exact source, commands, results, images and remaining owners are in [the current ledger](phases/production/DAY-1-EVIDENCE-LEDGER.md); [audit reconciliation](phases/production/DAY-1-CONSOLIDATION-AUDIT.md) records defects and repairs. Azure subscription/credit, approved region/SKU, DNS/certificate, secret delivery and target alert access remain unconfirmed. No public release or candidate approval is implied.

### Status vocabulary

The roadmap uses the required maturity statuses: **COMPLETE** means the declared phase scope has passed with dated evidence; **IN_PROGRESS** means a meaningful implementation already exists but the phase's release acceptance is not met; **NOT_STARTED** means the principal capability is absent; **BLOCKED** means a named external prerequisite prevents progress. Status describes implementation maturity, not whether someone is coding today. Historical Phase 0–11 documents remain implementation history and are not the numbering of the release phases. **COMPLETE** never means public-production approval.

## 1. Product boundary and architecture

### Intended first release

FleetFlow's first complete product should be an interactive synthetic delivery-orchestration showcase. A reviewer should be able to sign in as a demo customer, merchant, driver, or dispatcher; follow a seeded order through dispatch and delivery; view scoped tracking; and understand what operators see when dependencies or asynchronous projections fail. Demo data must be clearly synthetic and isolated from real customer information.

The current API is the behavioral foundation, not the finished product. Generic order creation remains local/test-only. Day 1 added a separate bounded synthetic-data seed/reset contract; its ownership, interruption/concurrency safety and real-provider browser integration are verified locally; target acceptance remains open. Simply enabling the local profile or its fixture routes on an internet-facing host is not acceptable.

### Current topology

One Spring Boot 4.1.1 / Java 21 modular monolith serves REST and SSE. PostgreSQL 17 with PostGIS owns durable business state and migrations. Redis holds expiring location/GEO data and rate-limit windows. Kafka carries events from a PostgreSQL transactional outbox to notification/analytics projections. Keycloak supplies local OIDC. Optional Compose overlays run Prometheus, Grafana, and Alertmanager. Testcontainers exercises PostgreSQL/PostGIS and Kafka; Redis failure cases use the real container service.

The architecture is coherent for a backend engineering portfolio. Keep the modular monolith and PostgreSQL transaction boundary. Keep Redis as a disposable location index, never as the source of business authorization. Keep Kafka/outbox as a demonstrated asynchronous boundary, but describe it as a single-node demo broker until an actual production topology is designed and measured. Do not split this into microservices or introduce Kubernetes for the portfolio target.

## 2. Capability maturity assessment

| Capability | Status | Maturity | What exists and evidence | Remaining gap / risk | Decision |
|---|---|---|---|---|---|
| Delivery domain and APIs | PARTIAL | DEMO READY | Registered APIs cover order, merchant, driver, dispatch, delivery, zone, incident, notification, analytics, and tracking controllers. OpenAPI is tested against route registration. The client has all role workspaces; one signed customer-to-delivery flow across customer, merchant, dispatcher and driver is live-verified. | Other persona workflows, operational failure UX and broader UI acceptance remain. Generic public order creation is disabled; only the bounded synthetic demo contract is intended for the showcase. Real pricing and transactions are out of scope. | Complete the remaining role journeys; keep real commercial intake out. |
| PostgreSQL model and migrations | COMPLETE for demo | DEMO READY | Fourteen Flyway migrations define actor ownership, lifecycle history, dispatch, assignment constraints, recovery, outbox, zones and command idempotency. Clean verification exercised fresh install and a populated v11→v14 upgrade, then started the app against the upgraded database. | No public-environment migration/rollback rehearsal. Schema restore is the rollback strategy; forward-only DDL needs a release procedure. | KEEP; HARDEN release and recovery procedure. |
| Authentication | VERIFIED LOCAL CONTRACT | DAY 1 FOUNDATION | Exact JWT checks, current PostgreSQL actor roles and real Keycloak 26.8.1 PKCE browser interoperability pass. Uncached JWKS failure is safe 503; cached valid-key tokens still require active authorization. | Public hostname/client provisioning, secret rotation and target acceptance remain Phase 9 work. | KEEP verified contract; provision the exact target issuer later. |
| Authorization | VERIFIED LOCAL CONTRACT | DAY 1 FOUNDATION | Deny-by-default security and service/query object scope; 65-operation registration check and signed denial coverage, foreign-object and revoked/inactive/multi-role checks execute. | Dispatcher remains intentionally global for one synthetic deployment; this is not multi-tenant isolation. | KEEP explicit policy model and extend tests with new Phase 7 actions. |
| API errors and contracts | COMPLETE for demo | DEMO READY | Stable ApiProblem/OpenAPI contracts, correlation IDs, body caps and bounded order lines. The client maps 401/403/404/409/413/429/503, timeout, cancellation and malformed responses to safe UX states; reads are cancellable and mutations are not retried. | Public compatibility/version policy and ingress request-read time bounds need release evidence. | KEEP; extend only for observed contract gaps. |
| Rate limiting / abuse controls | VERIFIED LOCAL CONTRACT | DAY 1 FOUNDATION | Atomic actor/location budgets, bounded fallback, Retry-After, body/geometry/page/SQL/SSE bounds; private production edge/login budgets and forwarding-spoof tests execute. | Fallback and active SSE accounting are per process; target ingress/network qualification remains open. | KEEP single-host policy; measure target limits. |
| Redis location / cache | COMPLETE for demo | DEMO READY | TTL-bound GPS/GEO with authoritative PostgreSQL eligibility and PostGIS zones; stale fixes are never presented as fresh. Location/heartbeat fail closed when Redis is unavailable. | Readiness intentionally excludes Redis; other durable operations remain useful. Redis is ephemeral and single host. | KEEP degraded-operation semantics and refresh positions after recovery. |
| Dispatch / concurrency | COMPLETE for demo | DEMO READY | Guarded lifecycle transitions, conditional reservations, unique indexes, row locks, fresh-location preflight, timed offers, race and rollback tests. | Existing 100-contender and demo-load evidence is local; no Azure capacity result. Client retry semantics are conflict-based, not universal idempotent response replay. | KEEP; add public-target concurrency and retry-contract evidence. |
| Command idempotency | VERIFIED LOCAL CONTRACT | DAY 1 FOUNDATION | Actor/operation/key creates replay original responses; seed/reset/operator replay use scoped audited keys. Existing state guards keep other commands conflict-based. | Records persist for deployment lifetime; no automatic prune. Lost-response retries do not universally replay success. | KEEP narrow keys; frontend never blindly retries mutations. |
| Recovery workers | COMPLETE for demo | DEMO READY | Leases, row locks, bounded retries, incident records and audited failed-outbox inspection/replay pass. The operator workspace exposes recovery status and controlled actions. | Local recovery and reset journeys pass; target failure qualification remains open. Arbitrary malformed-poison skip/replay remains an unsupported optional SHOULD item. | KEEP bounded contract; finish the remaining live acceptance. |
| Outbox / Kafka / projections | VERIFIED LOCAL CONTRACT | DAY 1 FOUNDATION | Same-transaction outbox, lease reclaim, duplicate dedupe, three-attempt consumer quarantine, capped replay and actual broker-down app restart/drain execute. | Single private plaintext broker; fresh production Kafka 4.2.2 differs from the untouched local 3.9 volume. Target restore/retention qualification remains open. | KEEP at-least-once delivery; no exactly-once claim. |
| Live tracking / SSE | COMPLETE for demo | DEMO READY | Owner-scoped snapshots, bounded streams, server limits, privacy tests, and a browser fetch-stream client with cleanup, capped backoff, snapshot refresh and stale/unavailable state. | Reconnect and stream pressure are not tested on Azure; multi-instance stream accounting remains local. | KEEP bounded SSE; extend target-specific limits after measurement. |
| Frontend product | VERIFIED LOCAL WORKFLOWS | IN_PROGRESS | 66 unit/component tests, three fixture journeys, performance/reduced-motion fixture, three signed real-service journeys, zone/serviceability editing and synthetic movement without device GPS. | Exact updated-commit CI and public target acceptance remain required. | Preserve the approved design. |
| Observability | VERIFIED PRIVATE REHEARSAL | IN_PROGRESS | Private scrape/redaction, PostgreSQL stop/start readiness and alert fire/clear, Redis recovery and owner backlog-delivery evidence. | Target monitoring, backup-age and certificate signal integration remain open. | Keep receiver acceptance distinct from a human-read receipt. |
| Container/infrastructure | VERIFIED PRIVATE TOPOLOGY | DAY 1 FOUNDATION | Separate TLS edge, internal networks, scoped runtime files, non-root services, non-superuser app DB role, bounded resources/logs; production overlay render validates private dependencies and only edge TCP 80/443 host ports. | Azure DNS/TLS/NSG/cost and exact published-candidate runtime checks remain external/later gates. | KEEP local Compose and production preparation separate. |
| Secrets | NEEDS HARDENING | DEMO READY | env files, keys, archives and webhook files are ignored; backup archives use AES-256-GCM; Compose rejects empty Keycloak admin password; tracked-file credential preflight is in CI. | Environment variables and local Compose file secrets are not a managed production secret lifecycle. The tracked-file preflight has a deliberately narrow pattern set. | HARDEN secret delivery, rotation, access, and auditing; keep credentials out of browser bundles and logs. |
| Dependency/image security | NEEDS DISPOSITION | DAY 2 CI VERIFIED | `npm audit --audit-level=high` reported zero vulnerabilities locally; merged-main run `38918855000` passed Trivy, CodeQL and SBOM jobs. Its SARIF artifacts contain 26 MEDIUM and 31 LOW matches, zero HIGH/CRITICAL; see the consolidation audit and release acceptance for scope and ownership. | MEDIUM/LOW advisory applicability and update paths remain open; a new scan is required for any release candidate. | Review vendor advisories and updates; do not suppress applicable findings. |
| Backup / restore / DR | PARTIAL LOCAL EVIDENCE | DEMO READY | Encrypted backup and fresh disposable restore passed on clean `6197b8b` source on 2026-10-16, including checksum, failure exit, PostGIS, restored identity, signed order read/cancel and 78.3-second local functional RTO. | Sustained scheduled RPO and replacement-host recovery are not proven. This restore is local, not Azure. Key custody and target-specific recovery remain. | KEEP tested tools; qualify schedule monitoring and isolated target recovery. |
| External integrations | PARTIAL | DEMO READY | Keycloak/OIDC supports identity; rclone uploads encrypted backups to Google Drive; optional Alertmanager sends backup/monitoring alerts to Discord. | No payment, merchant, mapping, courier, email/SMS or customer-facing webhook provider is integrated. The operations integrations are not a commercial delivery integration. | KEEP current OIDC/backup/alert use; DEFER business integrations until scope requires them. |
| CI/CD | IN_PROGRESS | DAY 2 MERGED GATES VERIFIED | Pinned backend/frontend/fixture-browser/migration/Compose/safety/security jobs, six image builds/scans, source/image SBOMs, Day 2 live journey and CodeQL. Merged-main run `38918855000` passed all seven jobs on `0e0a21e`. | Registry publication, provenance attestation and immutable candidate approval are later gates. | Qualify candidate controls in a later phase. |
| Testing | VERIFIED DAYS 1-3 GATES | IN_PROGRESS | 146 backend tests, 66 client tests, 30 script checks, three fixture and three signed journeys, private production smoke and encrypted disposable restore. Exact-run CI/metadata and limits are in the full audit. | Azure recovery and sustained target load remain unverified. | Preserve verified contracts and qualify the target later. |
| Operations / runbooks | PARTIAL | DAY 1 PREPARATION | Safe local recovery preflight, production startup/stop/readiness, encrypted off-host backup/restore preparation, age guards and bounded load/failure commands. | Actual Azure operation, off-host age/rclone execution, notification delivery, fresh-host restore, rotation/rollback and cost evidence remain open. | Execute target runbooks only after external prerequisites and candidate approval. |

## 3. Feature completeness matrix

“Required” means required for the scoped synthetic showcase, not for a real commercial delivery service.

| Capability | Current state | Maturity | Evidence | Missing work | Recommended action | Priority |
|---|---|---|---|---|---|---|
| Create/inspect synthetic order | Read/list/detail exist; POST creation is local/test only | DEMO READY | OrderController, LocalApiBoundary, profile route tests | Safe public demo bootstrap, seeded data and reset boundary | EXTEND with isolated demo fixtures; never trust browser-supplied prices | P0 |
| Customer tracking | Owner-scoped snapshot and bounded SSE | DEMO READY | TrackingController, TrackingHub, tracking tests, live customer order view/SSE | Broader reconnect and failure UX acceptance | EXTEND | P0 |
| Merchant order workflow | Branch-scoped list and guarded confirm/prepare/ready/cancel | DEMO READY | MerchantOrderReader, lifecycle APIs/tests, merchant workspace and live confirm/prepare/ready path | Additional validation, error and empty-state live acceptance | EXTEND | P0 |
| Driver workflow | Self status/location/heartbeat, offers, delivery transitions | DEMO READY | Driver, location, assignment and delivery controllers/tests, driver workspace and live accept/pickup/transit/complete | Additional failure-state and full browser coverage | EXTEND; synthetic coordinates only | P0 |
| Dispatcher matching and recovery | Ranking, offer, retry, reassignment, cancel, incident views/actions | DEMO READY | Dispatch and recovery APIs/tests, dispatcher and operations workspaces, live match/offer path | Broader incident and recovery journey acceptance | EXTEND | P0 |
| Branch serviceability | PostGIS zones, versioned policy and preview UI | DEMO READY | Keyboard/map editing, lost create recovery, 409 reopen and actual coverage; external tiles through private edge | Public target tile/CSP acceptance | KEEP approved geometry workflow | P1 |
| Notifications / analytics | Asynchronous projections and bounded read APIs | DEMO READY | Event projectors, migration V11, event tests, operator activity/recovery views | External delivery integration is not implemented; target projection-delay acceptance remains | Complete minimum synthetic scope; operator visibility P1 | P1 |
| Identity and demo personas | OIDC and local Keycloak realm, admin fixture routes only in local/test | DEMO READY | Keycloak configuration and provider tests | Public-safe issuer/client, seeded synthetic users, reset/abuse policy | HARDEN | P0 |
| Product UI | Five persona workflows verified privately | IN_PROGRESS | Signed delivery, zone/conflict/coverage and dispatch recovery; 390px/focus/axe evidence; interrupted commands and SSE recovery. | Exact updated-commit CI and public target acceptance. | Preserve the approved design. |
| Production ingress | Host loopback and local HTTP | DEMO READY only | compose.yaml, prod profile and local boundary | Domain, HTTPS, reverse proxy, firewall and private internal services | HARDEN | P0 |
| Backups | Encrypted tooling and local/remote evidence | DEMO READY | BACKUP_RESTORE.md, scripts and phase evidence | Schedule health, target-host restore and RPO/RTO ownership | HARDEN | P0 |
| Commercial ordering, trusted pricing, payments, merchant onboarding, public registration | Explicitly outside implemented scope | — (deferred) | README project-scope declaration | Would require product, legal, fraud, tenant and support decisions | DEFER; do not imply it exists | Out of scope |
| High availability, Kubernetes, multi-region, microservices, ML dispatch, paid route optimization | Not implemented | — (deferred) | Architecture plan and phase out-of-scope notes | No user need or budget evidence | REMOVE from current plan; revisit only with a real service requirement | Out of scope |

### Scope and cleanup disposition

- **Required for the first showcase:** public-safe access, synthetic seeded workflow, the four role journeys, useful operator recovery, and repeatable evidence. These are P0/P1 above.
- **Production hardening with direct value:** edge security, dependency disposition, backup monitoring, external alerting, retry semantics, and target-specific performance. These are launch gates, not generic technology additions.
- **Optional/deferred:** commercial payments/pricing, real registration, live mapping, multi-tenant operations, and high availability until a commercial requirement exists.
- **Abandoned experiments:** no abandoned application module or Java source TODO/FIXME/HACK marker was found in the inspected tree. The architecture plan explicitly labels itself planning-only; keep it as historical decision context, but let this roadmap own current launch decisions. Ignored local phase logs are not product features and were not deleted.

## 4. Frontend and product UX audit

### Current finding

The frontend in `web/` now includes React, TypeScript, Vite and Router; memory-backed OIDC with PKCE; protected role-aware navigation; typed API errors; cancellable reads; bounded SSE reconnect; shared accessibility states; and customer, merchant, dispatcher, driver and operator workspaces. Component tests and fixture Playwright checks pass. A separate real Keycloak/API/PostGIS browser gate prepares synthetic data, creates an order and drives one signed delivery across the merchant, dispatcher and driver actions while the customer observes SSE, then verifies logout and persisted state. The default fixture run and this real-service workflow use separate Playwright configurations.

The README screenshots and SVGs remain documentation assets. Current private journeys cover all five personas and the negative/recovery cases itemized in the Days 1-3 audit; public-target acceptance remains separate. There is no public deployment.

### Backend-to-UI workflow map

| Persona / operator | Current backend capability | Product surface required | Important UX states |
|---|---|---|---|
| Demo customer | Own-order list/detail/cancel, owned tracking snapshot/SSE, notifications | Order list/detail, lifecycle timeline, location panel, cancel confirmation, notification state | Loading, no orders, stale/no location, reconnecting SSE, terminal order, conflict, forbidden/expired session |
| Demo merchant | Branch order list, lifecycle transitions, zones and serviceability preview | Branch queue, order detail, confirm/prepare/ready/cancel actions, serviceability view | Empty queue, validation, version conflict, permission denied, unavailable zone policy |
| Demo driver | Self profile/status, location/heartbeat, offers and delivery transitions | Availability controls, synthetic route/location simulator, offer countdown, delivery timeline | Stale fix, expired offer, duplicate action, network recovery |
| Demo dispatcher | Match/rank, offers, assignments, incidents, retry/reassign/cancel, daily analytics | Dispatch queue, candidate explanation, assignment state, incident review, deliberate destructive confirmation | No candidates, stale candidate, competing offer conflict, recovery delay, retry limit, unavailable dependencies |
| Operator | Health/metrics, Prometheus/Grafana alerts, backup/restore scripts | Minimal run status, failed/quarantined event list and recovery guide; infrastructure details remain operator-only | Dependency down, alert firing/resolved, restore in progress, cost/deployment state |
| Demo administrator | Local-only actor/entity/relationship fixture routes | One controlled reset/seed capability, never a public general-purpose admin API | Confirmation and explicit synthetic-data-reset consequence |

### Recommended frontend direction

Continue the focused client in `web/`; preserve its small route-local state, ApiProblem boundary, in-memory OIDC credentials, cancellable reads and bounded SSE. Keep APIs same-origin behind the planned proxy. Add only components required by real customer, merchant, driver, dispatcher and operator workflows.

The browser now uses OIDC Authorization Code with PKCE S256 and memory-backed credential storage; tests cover token-storage behavior. Keep tokens out of localStorage and sessionStorage, maintain exact redirect/logout origins and keep reset/operator credentials off the browser.

Before coding visual details, ground the design in a small set of relevant delivery/fleet workflow references and record the decisions. Build a restrained design system: semantic status colors, readable type, consistent spacing, keyboard-visible focus, responsive navigation, accessible forms, and consistent errors/loading/empty states. Do not make meaningless charts or expose backend internals as customer features.

## Test strategy

Keep the existing unit and Testcontainers integration tests; they already protect important domain invariants, PostgreSQL migrations, real Redis/Kafka behavior, signed identity, outage handling, concurrency, and SSE limits. The saved local suite is 125 tests, not a coverage percentage or production approval.

Add tests at the boundary where evidence is currently missing:

| Layer | Keep / add | High-value acceptance |
|---|---|---|
| Domain/unit | Keep current lifecycle, ranking, WGS84 and tracking-bound tests. | Invalid transition, boundary value, timeout and conflict leave state unchanged. |
| PostgreSQL integration | Keep real PostGIS/Testcontainers and migration-upgrade coverage. Add upgrade-from-release and query-plan evidence only where the release schema changes. | Fresh DB and prior version migrate; constraints, unique assignment, zone policy and transactional rollback hold. |
| OIDC / authorization | Keep token validation and the 57-route anonymous/wrong-role/inactive pass. Add signed foreign-object tests per critical resource and role combination where no representative test exists. | Customer, branch, driver, offer, delivery, incident, zone and stream ownership cannot cross actors; role revocation takes effect. |
| Redis / rate control | Keep script/outage tests. Add the proxy boundary and concurrent pressure cases after deployment config exists. | No spoofed client address, Redis outage cannot silently remove a critical cap, rejected command does not mutate state, and responses carry the documented retry signal. |
| Kafka / outbox / recovery | Keep duplicate-delivery, poison event, lease, retry, sequence, prune and broker-outage tests. Add replay/rebuild tests when operator action is implemented. | One durable business effect; recoverable outbox; no unbounded poison loop; replay is audited and idempotent. |
| API contract | Keep OpenAPI-to-route equality and stable ApiProblem checks. Add contract snapshot/diff only if an external client depends on a released API. | All registered operations document auth, request/response, and expected errors. |
| Browser component | Focused React Testing Library suites cover auth, route guards, API errors, shared states, SSE lifecycle and customer order screens. | Add coverage for later persona actions and prove real backend contracts without stubbing successful operations. |
| Browser E2E | Playwright smoke covers transient signed PKCE login, protected customer route, keyboard access and logout; API responses are intercepted. | Add disposable Keycloak/backend/DB run, seed/order, merchant transition, driver offer/completion, customer tracking, dispatcher recovery, wrong-persona denial and mobile navigation. |
| Performance | Keep the bounded Python local workload and recorded measurements. | Run a repeatable target workload; report p50/p95/p99, error rate, CPU/RAM/GC, DB pool/latency, Redis, Kafka lag and active SSE. Never call a local result an Azure capacity result. |
| Operations | Scripts and manual drills exist; target tests are absent. | Scheduled backup, failed notification, fresh-host restore, image upgrade, VM stop/start, rollback, cost cap and certificate/port checks. |

CI should run backend build/tests, frontend lint/component tests, critical Playwright flows against a disposable stack, Compose/public-overlay validation, secret checks, current dependency/image scan, and migration verification. Keep the workflow focused on release risks; do not add a broad suite merely to increase counts. No new test was added in this audit because the current deliverable is an evidence-based audit and roadmap; code and browser tests belong in the dependent implementation phases above.

## 5. Security assessment

### Controls already present

- OIDC resource-server validation is separate from local actor resolution. Required issuer, audience, signed subject, token validity, and active actor are enforced.
- Route access uses an explicit allowlist and final deny; object checks are performed in the service/query layer rather than trusting caller IDs.
- Local fixture intake and administration are profile-limited; LocalApiBoundary also requires a loopback process for fixture profiles.
- Production profile disables Swagger/OpenAPI; health responses hide details. Metrics require dispatcher role except Prometheus on the private monitoring network.
- PostgreSQL constraints, lifecycle transitions, unique active-assignment indexes, version checks, and transactional updates provide defense in depth.
- App container runs as non-root; images are digest-pinned; host-published service ports bind to loopback; private files and backup archives are ignored.

### Public-release gaps

1. **Identity / transport:** compose.yaml uses Keycloak start-dev and an HTTP localhost hostname. Public OIDC issuer and JWKS URLs need a stable TLS hostname and the exact issuer value expected in tokens. Browser login/logout, redirect URI and allowed-origin policy need a separate demo client and provider configuration.
2. **Edge boundary:** no reverse proxy, TLS, HSTS, host firewall policy, forwarding-header trust configuration or public ingress deployment is present. The app's production configuration binds to loopback inside its container; proxy reachability must be deliberately designed rather than exposing port 8080.
3. **Internal services:** PostgreSQL, Redis, Kafka, Keycloak admin, Prometheus, Grafana and Alertmanager must not have public listeners. Kafka is plaintext and single-node in local Compose. Keep internal ports private and document the trust boundary; choose broker auth/TLS if the production trust boundary requires it.
4. **Abuse:** actor limits do not protect unauthenticated login, token issuance, malformed requests or anonymous edge traffic. Add proxy-level per-IP and connection/body/time limits plus stricter Keycloak login limits. Never trust raw forwarded-for headers unless only the proxy can reach the app and trusted-proxy processing is configured.
5. **Authorization evidence:** extend tests from role rejection to foreign object IDs, branch membership changes, role combinations, inactive actors, reassignment, stream ownership, and every state-changing API. Keep dispatcher scope global only for this single synthetic deployment; do not call it tenant isolation.
6. **Secrets:** replace local env-file habits for hosted credentials with protected deployment-time secret delivery and documented rotation. Never bake secrets into images, frontend assets, CI logs, realm exports or sample accounts.
7. **Dependencies:** the Phase 11 report records current OSV matches for pinned Keycloak, Redis, Kafka, PostGIS, Prometheus, Grafana and Alertmanager images. Review current affected ranges and reachability, update or document accepted risk, rebuild, rerun scans and integration/restore tests. Scanner match counts are not exploitability conclusions.
8. **Supply chain:** CI uses version tags for GitHub Actions rather than immutable commit SHAs and has no source/container scan gate, SBOM, artifact provenance or release signature.
9. **Browser security:** no UI exists to audit. Before launch, define CSP, same-origin/API policy, CORS, token/session storage, safe rendering, dependency scanning, clickjacking protection, and XSS/CSRF testing for the chosen auth model.
10. **Data exposure:** treat addresses, customer identity and driver coordinates as sensitive even in synthetic data. Metrics labels already avoid IDs and coordinates; preserve that in logs, UI, browser telemetry and support captures.

### Rate-limit strategy for the public demo

- Keep current authenticated per-actor fixed-window budgets and location/heartbeat Lua limits as the business-layer guard.
- Add an ingress budget before authentication: per-IP request rate, concurrent connections, header/body size, request timeout, and stricter auth/Keycloak login limits.
- Keep costly dispatch, zone geometry and SSE classes separate. Use stricter limits for demo reset, fixture creation and any synthetic seed endpoint.
- Test boundary values, simultaneous callers, retry headers, rejected-command state, Redis-down behavior, forwarded-IP spoofing and a proxy bypass attempt.
- Document behavior during Redis loss. Existing per-process fallback is bounded but not a shared global limit. Either accept single-instance demo semantics and fail closed where required, or use an explicitly shared edge quota before horizontal scaling.
- Emit bounded-cardinality metrics by route class and outcome only. Alert on persistent 429 bursts and edge saturation without logging tokens or coordinates.

## 6. Resilience, messaging, and failure matrix

### Resilience assessment

PostgreSQL owns durable commands and recovery state. Redis is TTL-bound and can be rebuilt from fresh location updates. Kafka is intentionally excluded from API readiness so committed outbox work can wait through a broker outage. Liveness is separate from readiness. Database and slow-Redis failures have tests; outbox and recovery workers use leases and durable rows. SSE streams are bounded, short-lived and non-durable.

These are good local failure semantics. They are not proof of an Azure restart/recovery, broker data-loss replay, multi-node failover, sustained availability, or a production SLO. The production overlay now defines and statically validates restart, health, dependency, resource and private-network policy; the overlay itself has not been started. The operator UI exposes recovery information, but its complete target failure workflow remains unverified.

### Failure matrix

| Failure | Current behavior/evidence | User-visible risk | Required launch proof / response |
|---|---|---|---|
| PostgreSQL unavailable | Readiness fails; liveness remains process-only; database-outage tests exist. | Durable reads/writes fail; retries may duplicate user intent if clients lack an idempotency contract. | Error UI, bounded timeout, no false success; restore DB and verify schema and invariants. |
| Redis unavailable or slow | Location writes fail with REDIS_UNAVAILABLE; stale location is not promoted. Route-limit fallback is local; readiness is down. | Live matching/tracking is unavailable or less accurate; quota aggregation weakens. | Distinguish stale/unavailable from “no nearby drivers”; verify recovery with a new fix and no duplicate assignment. |
| Kafka unavailable | Business transaction commits to outbox; scheduled publisher retries. | Notifications/analytics lag; operator cannot currently replay/inspect quarantine in a UI. | Stop/restore broker; prove one projection effect per event, backlog alert, retention bound and operator action. |
| Poison/malformed event | Consumer records quarantine information and metric. | Projection is incomplete; no operator review/replay UI or documented safe repair path. | Show quarantine to operator; prove replay/skip is authorized, audited, deduplicated and cannot loop forever. |
| Keycloak/JWKS unavailable | Bearer validation cannot complete; business calls should fail closed. | New sessions and authenticated requests fail. | Verify deployed provider cache/failure semantics, generic error UX, alerting and recovery; do not weaken verification. |
| App/container restart | PostgreSQL outbox and dispatch recovery state persist; SSE is in-memory and ends. | Stream reconnect and operator-visible recovery matter. | Reboot/recreate app; resume due work once; show clients reconnect to a fresh scoped snapshot. |
| Concurrent matching or repeated action | Locks, constraints and transition guards return conflict; high-contention tests exist. | UI must refresh and explain conflict rather than report false success. | Repeat on target; no duplicate assignment/delivery/outbox effect. Define replay semantics for retried network requests. |
| Public abuse / rate spike | Actor budgets exist after auth. | Anonymous traffic and login abuse are not bounded at the current local-only edge. | Proxy/Keycloak pressure tests and cost/memory bounds; no direct backend port. |
| Host or disk loss | Remote encrypted backup and local restore have evidence; workstation/target-host recovery is not proven. | Demo may stay down or lose state. | Restore on fresh target host; verify database, identity, readiness, signed workflow and measured RPO/RTO. |
| Bad migration / release | Flyway validates at startup; migration upgrade tests exist. | Deployment may fail after partial release; schema downgrade is not automatic. | Backup-before-change, restore-based rollback, forward-fix rehearsal on a cloned target. |
| SSE client stalls/disconnects | Bounded queues, timeouts and maximum stream life exist. | Client may see delayed data or disconnect. | Browser reconnect/cleanup tests; protect server and database during many slow clients. |
| Alert destination unavailable | Prometheus/Grafana local; optional Alertmanager/Discord exercise documented. | Operator may not know a demo is degraded. | Test notification delivery/resolution on target and provide a second manual health check. |

### Messaging reliability decision

Keep PostgreSQL transactional outbox and deduplicated projections; they demonstrate a meaningful dual-write reliability problem. Describe delivery as retryable/at-least-once with idempotent consumers, not exactly once. The quarantined-event table is a useful safety boundary, but an operator must be able to inspect age/reason and perform controlled replay or terminal disposition. Before public release, specify event schema/version compatibility and payload limits; publisher backoff/lease/multi-worker semantics; outbox retention and alert thresholds; consumer dedupe retention relative to replay horizon; lost-topic/projection rebuild; and audited replay/skip authorization.

For this single-host demo, a single Kafka broker is acceptable only as a declared non-HA component behind private networking. If it makes the product too expensive or unmanageable, retain the outbox and replace Kafka only after a measured product/learning-value decision; do not silently remove the async feature.

## 7. Observability, operations, deployment, and recovery

### Observability

Keep structured JSON logs and X-Correlation-ID; keep identifiers out of metric labels. Existing health, DB/Redis probes, HTTP/outbox/consumer/tracking metrics, 12 Grafana panels, alert rules and optional Discord integration provide a useful local base.

Before release, define a small service objective for the on-demand demo window, for example: at least 99% successful synthetic workflow availability during a declared demo window, p95 API latency under 500 ms at agreed load, and successful alert notification within five minutes. Adjust after target measurement; these are proposed acceptance targets, not measured current performance. Measure health-check availability independently from user workflow success. Add tracing only if the single-process and Kafka/DB correlation trail cannot answer an actual operational question.

### Recommended budget-aware hosting boundary

For the synthetic portfolio showcase, retain one Linux VM and one Compose project to fit the user's manual start/deallocate preference and limited credit. Put a TLS reverse proxy on ports 80/443 and route only to the app and OIDC endpoints that the browser needs. Restrict SSH to the maintainer's source IP. Keep PostgreSQL, Redis, Kafka, management endpoints, Prometheus, Grafana and admin interfaces on private Compose networks without host-published ports. Persist PostgreSQL and Keycloak volumes; back up outside the VM. Keep demo and production credentials separate.

When the VM is deallocated, the demo is unavailable. VM compute stops, but managed disks and possibly a static IP continue to cost money. Verify the Azure subscription's actual estimate before running. This one-VM design has no host redundancy or automatic failover and must be presented as an on-demand portfolio demo, not a commercial production SLA. A real business deployment needs a separate costed target, resilient dependencies and higher availability.

The current Compose stack remains deliberately loopback-bound, Keycloak remains start-dev with an HTTP local issuer, and Kafka is plaintext. A separate production profile/overlay is present; its rendered service topology, OIDC issuer/audience/hostname and TLS/proxy rules passed static checks. Only the edge publishes host TCP 80/443. The proxy-to-app bind and public/internal OIDC issuer/JWKS paths still need target runtime/browser checks. Publish no database, Redis or broker ports.

### Backup and disaster recovery

Current evidence: encrypted archive, remote copy, independent retrieval/hash check and local signed restore; saved local functional RTO is 114.9 seconds. These measurements do not prove cloud-host recovery, sustained RPO or Keycloak/DB consistency under target failure. Phase 11 records a failed daily task result on 2026-10-12, so one manual successful backup is not evidence of the current schedule.

For synthetic demo state, proposed target is **RPO at most 24 hours** and **RTO at most 1 hour**, measured from a separately hosted backup and fresh target. It is acceptable to rebuild the synthetic sample dataset if the product explicitly makes it disposable; identity, schema and seed/reset procedures must then be included in the measured recovery. Before that gate:

1. Reconcile backup artifacts, encryption-key custody, retention, schedule, alert route and data/identity quiescence.
2. Prove a scheduled backup completes and is independently readable; alert on missed schedule, failed transfer and age beyond RPO.
3. Restore to a new isolated Azure target with no source volumes mounted.
4. Verify migration version, actor-to-identity mapping, PostGIS, a signed customer/merchant/driver/dispatcher workflow, outbox/consumer state, tracking behavior, and rollback procedure.
5. Measure RPO/RTO from timestamps; record exact target size, image digests and exceptions. Keep decryption keys separate from the backup target.

### Operational procedures still needed

- Provision, start, verify, stop/deallocate and cost-check the on-demand VM.
- Rotate identity, database, Grafana and backup secrets without exposing them in shell history or logs.
- Seed/reset only synthetic data, preserve a known-good dataset, and recover after a failed reset.
- Respond to DB/Redis/Kafka/Keycloak outage, outbox backlog, quarantine, high 429s, failed health check and failed backup.
- Release forward and roll back through a tested restore/cutover procedure.
- Check certificate expiry, DNS/public IP, disk capacity, image advisories, backup freshness and public-port exposure before each demo window.
- Keep a concise reviewer guide with demo personas, expected sample flows, and an honest availability disclaimer.

## 8. Priority blockers

### P0 — required before a public demo URL is advertised

1. Approve the synthetic-only product boundary and demo data/privacy policy.
2. Complete the remaining product UI workflows: the first signed live delivery path now traverses customer, merchant, dispatcher and driver; additional persona and operator recovery journeys remain.
3. Build a separate public-safe deployment boundary with HTTPS, stable OIDC issuer, reverse proxy, restricted SSH, private dependencies and no direct app/admin/DB ports.
4. Provide safe demo personas and synthetic seed/reset behavior without enabling local fixture/admin routes publicly.
5. Complete remaining negative/failure UI states and qualify CORS/session and public rate controls on the target; local API object-scope tests and one browser denial now pass.
6. Triage or remediate every applicable service-image finding from the exact-commit PR scan; current source/image scan gates are configured in CI.
7. Rehearse backup and signed restore on a replacement target host; establish the scoped RPO/RTO evidence or document the demo's recovery boundary.
8. Run CI on the exact final commit and inspect all status checks. Day 2 consolidation exact-main run `38918855000` passed on `0e0a21e`; the eventual release candidate needs its own checks.
9. Complete port, secret, TLS, health, cost and rollback review against the deployed target.

### P1 — required for a credible finished portfolio product

- Customer, merchant, driver and dispatcher workflows with success/error/empty/loading/reconnect states.
- Responsive and keyboard-accessible browser journeys; labels, focus, contrast and reduced-motion behavior.
- Safe operator visibility and audited procedure for failed/quarantined events and backup status.
- Retry/idempotency contract for commands and seed/reset workflows.
- Measured target load with agreed concurrency, p50/p95/p99, CPU, memory, DB pool, Redis, Kafka lag and error rate.
- Verified alert notification and recovery, service ownership, runbooks, secret rotation and upgrade/rollback rehearsal.

### P2 — defer unless measured need appears

- Multi-tenant business accounts, user registration, payments, trusted merchant pricing, external courier integrations, production-grade map navigation, multi-region DR, Kubernetes, microservices, generalized workflow engine, distributed tracing, advanced analytics, and ML/route optimization.

## 9. Ordered production-demo implementation plan

The phases below are the authoritative release sequence for a synthetic, on-demand recruiter demo. Each linked brief contains the implementation specification, current state, priorities, tests, failure cases, verification commands, exit criteria, deliverables, risk, dependencies and unlocks. Historical implementation phases remain in docs/phases/; release-phase briefs live in docs/phases/production/.

For a compressed, dependency-aware five-day execution plan with a Day 6 contingency, see [EXECUTION-SCHEDULE-5-DAY.md](phases/production/EXECUTION-SCHEDULE-5-DAY.md). The schedule does not change phase exit criteria or current statuses.

IN_PROGRESS means the capability already has a meaningful foundation but its release acceptance remains unmet; it does not claim active coding. Phase 0 is complete for the audit and scope lock only. No later phase is approved by that status.

| Phase | Name | Status | Priority | Depends On | Main Outcome |
| --- | --- | --- | --- | --- | --- |
| [0](phases/production/phase-00-baseline-and-product-contract.md) | Baseline and product contract | COMPLETE | P0 | — | Audit-backed synthetic showcase boundary and single-VM on-demand target |
| [1](phases/production/phase-01-core-correctness-and-command-contracts.md) | Core correctness and command contracts | COMPLETE | P0 | 0 | Durable state transitions and safe duplicate-command behavior |
| [2](phases/production/phase-02-identity-and-object-authorization.md) | Identity and object authorization | COMPLETE | P0 | 1 | Signed persona identity and proven foreign-object boundaries |
| [3](phases/production/phase-03-api-abuse-and-error-contract.md) | API abuse limits and error contract | COMPLETE | P0 | 1, 2 | Bounded API work and stable safe client errors |
| [4](phases/production/phase-04-observability-and-operator-signals.md) | Observability and operator signals | IN_PROGRESS | P1 | 1–3 | Actionable private signals and tested alert paths |
| [5](phases/production/phase-05-async-recovery-and-demo-data.md) | Async recovery and synthetic demo data | IN_PROGRESS | P0/P1 | 1–4 | Safe event disposition and repeatable synthetic seed/reset |
| [6](phases/production/phase-06-frontend-foundation-and-auth.md) | Frontend foundation and authentication | COMPLETE | P0 | 2, 3 | Verified PKCE/API/SSE client foundation and responsive/accessibility checks; target auth belongs to Phase 9 |
| [7](phases/production/phase-07-persona-workflows-and-browser-acceptance.md) | Persona workflows and browser acceptance | COMPLETE | P0 | 5, 6 | Three signed local journeys and exact-commit CI pass across all personas, zones and dispatch recovery; public target acceptance is Phase 9 |
| [8](phases/production/phase-08-ci-release-and-supply-chain.md) | CI, release artifacts and supply chain | IN_PROGRESS | P0 | 1–7 | Scanned, reproducible immutable candidate with browser and migration gates |
| [9](phases/production/phase-09-azure-public-deployment.md) | Azure public deployment boundary | IN_PROGRESS | P0 | 2, 6, 8 | Production overlay renders with private dependencies and edge-only host ports; prove target HTTPS route and production OIDC |
| [10](phases/production/phase-10-target-operations-backup-and-recovery.md) | Target operations, backup and recovery | IN_PROGRESS | P0 | 4, 5, 8, 9 | Exercised owner runbooks and fresh-target restore/reconstruction |
| [11](phases/production/phase-11-target-performance-and-failure-qualification.md) | Target performance and failure qualification | IN_PROGRESS | P0/P1 | 3, 4, 8–10 | Measured capacity, cost, security and failure behavior on the actual VM |
| [12](phases/production/phase-12-final-public-demo-acceptance.md) | Final public-demo acceptance | NOT_STARTED | P0 | 0–11 | Approve or reject the exact deployed candidate with dated evidence |

### Dependency graph

~~~text
0 → 1 → 2 → 3 → 4 → 5 ─┐
              └────→ 6 ─┴→ 7 → 8 → 9 → 10 → 11 → 12
~~~

Phase 5 also depends on Phases 1–3. Phase 6 depends on the identity and API contracts in Phases 2–3. Phase 7 joins the backend recovery/data contracts from Phase 5 with the browser foundation from Phase 6. Every later phase keeps the dependencies listed in its own brief.

### Critical path

~~~text
0 → 1 → 2 → 3 → 4 → 5 → 7 → 8 → 9 → 10 → 11 → 12
~~~

Phase 6 is a parallel branch after Phase 3 and must finish before Phase 7. Public deployment cannot start until backend/UI acceptance and immutable release gates pass. Target recovery and measurements cannot be accepted from local evidence alone.

### Work that can safely overlap

- After Phase 3, Phase 4 observability and Phase 6 frontend foundation can proceed independently. Keep auth/API contracts frozen while both advance.
- Phase 5 async recovery can overlap later Phase 6 shell work after Phase 4. The operator screens in Phase 7 wait for Phase 5's stable inspect/replay/reset contracts.
- Security scan workflow implementation in Phase 8 can start while Phase 7 UI work finishes, but release CI acceptance waits for the complete browser journeys.
- Do not parallelize public exposure with unfinished object authorization, browser auth, or release artifact controls.

### Release boundary

**Everything above this line is required before the on-demand public demo is advertised.**

Phases 0–12 are the required public-demo path. Phase 0 records an already completed audit/scope decision; it is not production approval. The release is a synthetic portfolio showcase on one manually started Azure VM. When the owner deallocates it, the demo is offline; no commercial uptime, high availability or real-customer-data claim is permitted.

**After this line: post-launch or future work, not release blockers.**

Payments, trusted commercial pricing, real public order intake, customer registration, real merchant onboarding, real driver data, external courier/webhook integrations, paid live maps/routing, multi-tenant accounts, high-availability Kafka/Redis, Kubernetes, microservices, multi-region disaster recovery, ML matching, and advanced analytics remain out of scope unless the product boundary changes.

### Phase execution and evidence rule

Start the next dependent phase only when the prior phase's exit criteria have evidence or a named, accepted dependency exception. Update the linked phase brief and this table as work completes; do not rewrite historical phase evidence. If a phase uncovers a correctness/security blocker, return it to the owning phase and keep the public release blocked. Final approval uses Phase 12 on one exact commit, immutable image set, Azure resource inventory and date.

## 10. Production release gates

The release is **not production-ready** unless each applicable gate has dated evidence for the exact candidate commit and deployment.

### Build, code and migration

- [ ] Clean Java 21 Maven build and full tests pass with zero failures/errors/skips.
- [ ] Frontend install is lockfile-reproducible; lint, component tests and critical Playwright journeys pass.
- [ ] All migrations pass on an empty target and upgrade from prior release; backup/restore or rollback path is rehearsed.
- [ ] No committed secrets/private artifacts; secret preflight and dedicated secret scanner pass.
- [ ] API/OpenAPI route and error contracts match registered handlers.

### Security and abuse

- [ ] TLS, DNS, HSTS, proxy trust and public-port review pass externally.
- [ ] No direct public route to app, database, Redis, Kafka, Keycloak admin, Prometheus, Grafana or Alertmanager.
- [ ] OIDC issuer/signature/audience/expiry, redirects/logout, role and actor deactivation are tested on deployed identity.
- [ ] Foreign-object, cross-branch, cross-driver, offer, delivery, incident, zone and stream boundaries pass signed tests.
- [ ] CORS, browser token/session handling, CSP, XSS/CSRF model, forwarding headers and safe errors are reviewed.
- [ ] Proxy, auth and business rate limits; request/body/time caps; SSE concurrency; abuse/quota behavior pass.
- [ ] Current source and all deployed images have SBOM and vulnerability results; critical/high findings are fixed or dispositioned with owner and expiry.
- [ ] Secrets are not in repo, image, browser bundle, deployment logs or same-location backup; rotation is exercised.

### Reliability and operations

- [ ] PostgreSQL, Redis, Kafka, Keycloak and app failures match the failure matrix.
- [ ] Concurrency, duplicate command, outbox retry, poison-event quarantine/replay and worker restart are proven.
- [ ] Health/readiness/liveness distinguish recoverable dependency outage from process failure.
- [ ] Metrics, dashboard, logs/correlation and alert notification work on target; alert recovery is observed.
- [ ] Scheduled backup completes; independent fresh-target restore meets RPO ≤24 h/RTO ≤1 h or synthetic reconstruction is proven.
- [ ] Release upgrade and rollback are rehearsed; current artifact/images are recorded.
- [ ] VM start/deallocate preserves data, URL behavior is understood, disk/cost alerts are enabled, and spend fits approved credit.

### Product quality

- [ ] Customer, merchant, driver, dispatcher and operator journeys map to working UI actions.
- [ ] Critical pages handle loading, empty, success, validation, forbidden, unauthenticated, conflict, rate-limited, unavailable and unexpected errors.
- [ ] No dead navigation, placeholder content, raw JSON errors, misleading statuses or destructive action without consequence/feedback.
- [ ] Keyboard-only and mobile-width checks pass; labels, focus, contrast and reduced-motion behavior are acceptable.
- [ ] No major workflow requires Swagger, direct SQL or an undocumented operator command.
- [ ] README says whether demo is online, how to sign in, what is synthetic, and what is intentionally not implemented.

## 11. Final product acceptance checklist

- [ ] A first-time reviewer understands the product and synthetic-only boundary within one minute.
- [ ] Reviewer can access a declared demo persona and complete an order from creation/seed through completion.
- [ ] Merchant, driver and dispatcher actions are visibly different and authorized by the same identity claims the backend enforces.
- [ ] Matching output explains candidate eligibility/ranking without revealing another actor's private data.
- [ ] Tracking distinguishes current, stale, missing and terminal state; reconnect does not duplicate or misrepresent events.
- [ ] A failed dependency produces a useful recoverable user message and an operator signal.
- [ ] Conflicts and duplicate submissions do not show false success; UI explains what refreshed.
- [ ] Operator can identify pending/failed/quarantined work, backup freshness and recovery steps without editing database rows.
- [ ] Demo reset is deliberate, synthetic-only, authorized and observable.
- [ ] UI is responsive, keyboard-operable and consistent; no screen feels like an unmodified admin template.
- [ ] Public URL availability and manual start/stop behavior are stated honestly; no high-availability or commercial claims are implied.

## 12. Deliberately deferred work

Do not add these to the current implementation plan without a new scope decision: payment processing, trusted commercial price calculation, real public order intake, customer registration, real merchant onboarding, real driver PII/location, external courier or webhook delivery, route optimization/live paid maps, multi-company tenancy, high-availability Kafka/Redis, Kubernetes, microservice split, multi-region DR, ML matching, or advanced analytics. These require separate product, cost, risk, privacy and operational justification.

## Evidence index

- Product/local demo boundary: README.md, PROJECT_CONTEXT.md, ROADMAP.md.
- Registered API and scope: OpenApiDocsTests.java, controllers under src/main/java/com/fleetflow.
- Authentication and route controls: SecurityConfiguration.java, OidcDecoderConfiguration.java, PHASE11_SECURITY_AUDIT.md.
- Rate/failure limits: RESILIENCE.md and PHASE11_SECURITY_AUDIT.md.
- Business workflows and schemas: src/main/resources/db/migration/ and docs/phases/.
- Monitoring: MONITORING.md, PHASE11_OBSERVABILITY_EVIDENCE.md and ops/monitoring/.
- Recovery: BACKUP_RESTORE.md, phase11.md and scripts/.
- Deployment/CI: compose.yaml, compose.monitoring.yaml, compose.alerting.yaml, Dockerfile and verify.yml.

## Audit outcome

Current status: **DEMO READY locally; NOT PUBLIC-LAUNCH READY; NOT PRODUCTION READY.** The [Days 1–3 audit](phases/production/DAYS-1-3-FULL-AUDIT-2026-10-17.md) records the signed five-persona private journeys and prior exact-commit CI. [Day 4](phases/production/Day4.md) adds a passing working-tree private rehearsal and local release guardrails, while the 50 Keycloak findings, changed-source CI, immutable candidate, Azure boundary, target recovery and measured capacity remain open. The [remaining-steps handoff](PROJECT-COMPLETION-REMAINING.md) gives the dependency order and exit evidence.
