# Historical Day 1 Prompts 1–2 backend evidence — 2026-10-14

> This historical snapshot is superseded by [the consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) and [audit reconciliation](DAY-1-CONSOLIDATION-AUDIT.md). Current code has 14 migrations, 64 enabled test-profile operations and a passing 143-test full suite. Earlier blockers below describe the earlier run.

**Historical scope:** backend workstream A/B from the five-day release schedule. This is an evidence snapshot for the current working tree, not phase approval. Phases 1, 2, 3 and 5 remain `IN_PROGRESS`; the public-demo acceptance decision is not made here.

## Checkout and baseline

- Checkout: `codex/phase11-acceptance-closeout`, starting commit `4efc0b0067ff451d4328761fd04597d2a4d05493`.
- The working tree already contained Phase 11 and production-roadmap changes before this Day 1 work; those changes were preserved.
- Runtime used for the baseline and Day 1 Maven work: Windows, Java 21.0.12, Maven wrapper, Testcontainers with Docker Desktop.
- Before Day 1 edits, `./mvnw.cmd clean verify` passed **125 tests, 0 failures, 0 errors, 0 skipped**. This is a pre-change baseline only. Final post-change verification is recorded below after it completes.
- The baseline did not change the prior Phase 11 disposition: `CLOSED_PARTIAL` remains partial, and its target RPO/RTO, replacement-host recovery and advisory gates are still open.

## Mutating route inventory

The application registers business handlers under `/api/v1/**`. `Phase11RouteBoundaryTests` enumerates the live Spring handler mapping instead of maintaining a second guessed route count. For each registered HTTP operation it sends an anonymous request, a signed wrong-role request and a signed inactive-actor request, then compares row counts for every public database table before and after the sweep. `OpenApiDocsTests` asserts that the local OpenAPI document matches all 57 registered method/path pairs. Resource-specific scope and state behavior are covered by the named integration tests below.

| Method and route | Handler | Profile / actor boundary | Durable effect and retry outcome | Evidence |
| --- | --- | --- | --- | --- |
| `POST /api/v1/actors`, `/customers`, `/merchants`, `/merchant-branches`, `/merchant-staff-memberships`, `/drivers`, `/vehicles` | `ActorController`, `CustomerController`, `MerchantController`, `BranchController`, `MembershipController`, `DriverController`, `VehicleController` | ADMIN; registered only in `local` and `test` | Local fixtures create independent rows. They have no request-key replay contract and are not part of the public workflow. | `Phase2SecurityTests`, `Phase11RouteBoundaryTests` |
| `POST /api/v1/orders` | `LocalOrderCreateController` | CUSTOMER; registered only in `local` and `test` | Actor + `ORDER_CREATE` + `Idempotency-Key` scope. Same key/payload replays the stored 201 body and `Location`; different payload returns 409 `IDEMPOTENCY_KEY_REUSED`. Reservation, order, items, lifecycle history and response commit together. | `Phase1ApiTests` |
| `POST /api/v1/orders/{orderId}/cancel` | `CustomerOrderLifecycleController` | CUSTOMER; owner only | Transactional state transition, history and outbox. A repeated or invalid transition conflicts; foreign and unknown order IDs share 404. | `Phase2SecurityTests`, `Phase3LifecycleTests` |
| `POST /api/v1/merchant-branches/{branchId}/orders/{orderId}/confirm`, `/start-preparation`, `/ready-for-pickup`, `/cancel` | `MerchantOrderLifecycleController` | MERCHANT; active membership for that exact branch | Locked transactional lifecycle transition, history and outbox. Competing or repeated invalid transitions conflict without a second durable transition. | `Phase2SecurityTests`, `Phase3LifecycleTests` |
| `POST /api/v1/drivers/me/online`, `/available`, `/pause`, `/offline` | `DriverLifecycleController` | DRIVER; actor's own driver | Guarded driver-state transition and history. State conflicts reject duplicates. | `Phase3LifecycleTests`, `Phase4LocationTests` |
| `POST /api/v1/drivers/me/location`, `/heartbeat` | `DriverLocationController` | DRIVER; actor's own active driver | Atomic Redis update. Location accepts strictly newer timestamps; equal/stale fixes return 409 and do not refresh location, heartbeat or GEO state. Limits reject without changing accepted state. | `Phase4LocationTests` |
| `POST /api/v1/drivers/me/offers/{offerId}/accept`, `/reject` | `DriverOfferController` | DRIVER; offer belongs to the authenticated driver | Transactional offer/driver/order/delivery update with history and outbox; locks and state guards prevent two winners. | `Phase6AssignmentTests`, `Phase11RouteBoundaryTests` |
| `POST /api/v1/drivers/me/deliveries/{deliveryId}/pickup`, `/start-transit`, `/complete`, `/fail` | `DeliveryCommandController` | DRIVER; delivery assigned to the authenticated driver | Guarded transactional delivery state, history and outbox. Repeated/illegal state transitions conflict. | `Phase6AssignmentTests`, `Phase3LifecycleTests` |
| `POST /api/v1/dispatch/orders/{orderId}/match` | `DispatchController` | DISPATCHER; intentionally global for this one synthetic deployment | Locks the eligible order, records one attempt/candidate snapshot and outbox effects. Invalid/repeated state conflicts; Redis failure leaves order and attempt state unchanged. | `Phase5MatchingTests` |
| `POST /api/v1/dispatch/orders/{orderId}/offers` | `AssignmentController` | DISPATCHER; intentionally global | Atomic assignment offer and pending delivery creation with uniqueness/eligibility checks, histories and outbox. Conflicts do not reserve a second driver. | `Phase6AssignmentTests`, `Phase10ZoneTests` |
| `POST /api/v1/dispatch/orders/{orderId}/retry`, `/reassign`, `/cancel`, `/incidents/fail`, `/incidents/{incidentId}/resolve` | `RecoveryController` | DISPATCHER; intentionally global | Guarded recovery/incident state changes. Repeated or invalid commands return conflicts; recovery claims and incident constraints bound duplicate effects. | `Phase7RecoveryTests` |
| `POST /api/v1/merchant-branches/{branchId}/zones` | `ZoneController` | MERCHANT; active membership for that exact branch | Actor + `ZONE_CREATE` + `Idempotency-Key` scope. Same key/payload replays the original zone snapshot and `Location`; a changed payload returns 409. Zone, policy version/history and response persist in one transaction. | `Phase10ZoneTests` |
| `PUT /api/v1/merchant-branches/{branchId}/zones/{zoneId}` | `ZoneController` | MERCHANT; exact branch | Optimistic `expectedVersion` update plus policy history in one transaction. A lost-response retry with the old version conflicts; it cannot overwrite a later edit. | `Phase10ZoneTests` |
| `PUT /api/v1/merchant-branches/{branchId}/zone-policy` | `ZoneController` | MERCHANT; exact branch | Optimistic policy-version change and append-only history. Same current mode is a no-op; stale versions conflict. | `Phase10ZoneTests` |
| `POST /api/v1/merchant-branches/{branchId}/serviceability` | `ZoneController` | MERCHANT; exact branch | Read-only PostGIS preview; no durable writes. Uses the expensive-read budget (30/10 seconds), with geometry size and input bounds. | `Phase10ZoneTests` |

Read routes are also included in the live role sweep: customer order list/detail and tracking snapshot/stream; branch order and zone reads; driver self/offers/deliveries; dispatcher attempts/assignments/incidents/analytics; and actor-owned notifications. Customer order, tracking, driver, offer, delivery, merchant branch and zone access use service/query scope checks. Dispatcher scope is intentionally global for the single synthetic showcase; this is not multi-tenant isolation.

## Phase 1 — correctness and command semantics

- Added migration `V13` for actor/operation/key request records. It is forward-only and historical migrations were not edited. The key is 1–128 ASCII letters/digits/dot/underscore/tilde/hyphen; the request fingerprint is SHA-256. No expiry/prune policy is implemented yet, so records persist until a future bounded retention policy is defined.
- Order creation and zone creation now preserve the original response for same-key replay and reject changed-payload reuse. Order creation is deliberately local/test only because submitted prices are untrusted. Zone creation is part of the signed branch-management API.
- Order intake caps each request at 100 items. All `/api/v1/**` write bodies are capped at 1 MiB; `/api/v1/drivers/me/location` has a stricter 1 KiB cap. Both declared-length and unknown-length bodies are checked. Exceeding a cap returns 413 `PAYLOAD_TOO_LARGE` with a correlation ID and no domain write.
- Existing lifecycle, dispatch, assignment, delivery and recovery commands use transaction locks/constraints and return conflicts for illegal or duplicate state transitions. Location updates require a strictly newer reported timestamp; a retry after accepted-but-lost response is safe from duplicate state effects but returns 409 rather than replaying 204.
- Migration evidence: fresh install applies 13 migrations; a populated v11 schema applies v12 and v13 while retaining the historical order. Exact final focused/full test totals are below.

## Phase 2 — identity and object scope

- JWT validation requires configured exact issuer, explicit JWKS URL, audience `fleetflow-api`, expiry and nonblank subject; signature verification uses Nimbus/JWKS. Missing configuration fails application startup. Token role claims are not used to authorize FleetFlow roles.
- After signature validation, each request resolves `(iss, sub)` to an active actor and reloads `actor_roles` from PostgreSQL. Unknown or inactive actors and wrong roles return the same safe 403 `FORBIDDEN`; malformed, expired, wrong-issuer, wrong-audience or bad-signature tokens return 401 `UNAUTHENTICATED`. Actor database failure returns 503 `DATABASE_UNAVAILABLE`.
- Customer ownership, merchant exact-branch membership, driver self/assignment and scoped tracking/resource access are checked in application services/queries. Foreign and absent owned resources use 404 where disclosed existence would enable enumeration. Membership changes apply on the next request.
- Browser contract frozen before Phase 6 implementation: dedicated public SPA client; authorization code + PKCE S256; no client secret, implicit flow or direct grant; exact HTTPS redirect and post-logout URLs for one stable origin; same-origin API preferred; access token in memory only; 401 ends the failed request and never automatically retries a mutation; interactive re-authentication; local token clear before IdP logout. Prompt 2 implemented the local browser client; see Phase 6 evidence. A deployed browser proof does not exist.
- The signed test JWKS outage path is fail-closed by the decoder, but its externally observed HTTP response during an actual JWKS outage has not been verified. Keep this as a release blocker until exercised.

## Phase 3 — bounded work and stable errors

| Class | Current policy | Failure contract |
| --- | --- | --- |
| Ordinary authenticated GET | 60 per actor / 10 seconds | 429 `RATE_LIMITED`, `Retry-After`; local process fallback if Redis is down |
| Expensive GET (dispatch, tracking snapshot, zones/policy) | 30 per actor / 10 seconds | 429 `RATE_LIMITED`, `Retry-After`; local process fallback if Redis is down |
| Authenticated write commands | 30 per actor / 10 seconds | 429 `RATE_LIMITED`, `Retry-After`; local process fallback if Redis is down |
| Tracking stream admission | 6 per actor / minute; 100 process-wide open streams, 3 per actor, five-minute lifetime, 15-second heartbeat, slow-client cutoff | 429 for budget/cap; route budget includes `Retry-After`; bounded stream cleanup |
| Driver location / heartbeat | Defaults: 30/minute and 60/minute per driver, respectively; atomic Redis fixed windows | 429 `LOCATION_RATE_LIMITED` / `HEARTBEAT_RATE_LIMITED`, conservative configured-window `Retry-After`; Redis failure is fail-closed 503 |
| Mutating JSON body | 1 MiB general; 1 KiB for location | 413 `PAYLOAD_TOO_LARGE`, correlation ID, no handler/domain effect |
| Zone geometry / order lines | 64 KiB geometry, 4–2,048 valid points; at most 100 order items | 400 stable validation error; geometry additionally uses WGS84/PostGIS checks |

Route budgets use shared Redis fixed windows. If Redis fails, the process-local fallback is bounded to 10,000 keys; capacity exhaustion returns 503. This fallback is not cross-instance. Location and heartbeat fail closed when Redis is unavailable. Error bodies use the stable `ApiProblem` fields `code`, `message`, `path`, `timestamp`, `correlationId`, and `fields`; unknown routes normalize to 404. A global streaming/body read cap exists in the servlet filter, but request-read deadlines, upload timeouts, trusted proxy/IP controls, login throttles and public-edge limits require the Phase 9 proxy and are not proven here. A process-level handler deadline is not established by the request-body cap.

The serviceability preview shares the expensive-read budget with dispatch, tracking snapshots and zone reads so geometry work cannot bypass that class by using POST.

## Phase 5 — async and demo-data inventory before Prompt 2

- Commands write business state and transactional outbox records in the same PostgreSQL transaction. Outbox claims are leased with `SKIP LOCKED`; order aggregate sequence blocks later events behind unpublished earlier events. Publisher failures use bounded exponential backoff (5 seconds growing to a 300-second cap) and terminal `FAILED` status after configured maximum attempts. Published rows are pruned in bounded batches after at least 14 days only after notification and analytics projectors recorded them.
- Kafka delivery is at-least-once. Notification and analytics consumers record processed event IDs; malformed/failed messages are quarantined. Existing tests cover match/assignment transactional effects, outage, repeated delivery, worker failures and projection behavior in Phase 5–8 suites; full post-change results are recorded after the final run.
- Current operator-facing APIs are read-only notifications and bounded daily analytics. There are no HTTP inspect/replay/skip/quarantine-disposition/rebuild endpoints, no deterministic seed/reset endpoint, no operator audit trail for these actions, and no reset interruption/recovery contract. Local fixture creation is individual ADMIN-only setup, not a public demo dataset workflow.
- Prompt 1 disposition: inventory and keep Phase 5 open. Prompt 2 then added the bounded operator contract and deterministic synthetic seed/reset boundary documented below; no general fixture or arbitrary database wipe route was added.

## Verification results

| Run | Command | Result |
| --- | --- | --- |
| Pre-change baseline | `./mvnw.cmd clean verify` | 125 tests passed; 0 failures, errors or skips. Historical baseline, before Day 1 edits. |
| Focused Day 1 suite | `./mvnw.cmd --% -Dtest=Phase1ApiTests,Phase2SecurityTests,Phase3LifecycleTests,Phase4LocationTests,Phase10ZoneTests,Phase10UpgradeRestoreTests,MigrationUpgradeTests,Phase11RouteBoundaryTests test` | 49 tests passed; 0 failures, errors or skips. |
| Contract/OpenAPI focused suite | `./mvnw.cmd --% -Dtest=Phase10ZoneTests,OpenApiDocsTests test` | 7 tests passed; 0 failures, errors or skips. Includes the expensive-read serviceability classification assertion and OpenAPI route comparison. |
| Prompt 1 post-change suite | `./mvnw.cmd clean verify` | **129 tests passed; 0 failures, 0 errors, 0 skipped. BUILD SUCCESS**, 6:56 elapsed. This included all then-existing Phase 5–9 integration suites. The later Prompt 2/3 full verify result below supersedes it as evidence for the current working tree. |

## Prompt 2 execution evidence — 2026-10-14

- Starting commit: `4efc0b0067ff451d4328761fd04597d2a4d05493`. Prompt 2 implementation and evidence remain in the working tree; no commit was created.
- `.\mvnw.cmd --% -Dtest=CorrelationIdFilterTests,TrackingHubBoundsTests,OperationalMetersTests,LifecycleRulesTests,MatchingScoreTests,OidcStartupTests,Wgs84DistanceTests,LocalApiBoundaryTests test` passed: 15 tests, 0 failures/errors/skips. It includes request-log redaction, meter snapshot behavior and SSE eviction bounds.
- `.\mvnw.cmd clean verify` compiled 91 production and 33 test sources, then failed in Testcontainers startup: `NpipeSocketClientProviderStrategy` returned HTTP 500. Summary: 133 tests, 0 assertion failures, 118 errors, 0 skipped; 15 Docker-independent tests passed and the container-backed suites could not initialize. This is an environment blocker, not a passing full verification.
- Prometheus 3.15.0 `promtool check rules ops/monitoring/alerts.yml` passed (9 rules); `promtool test rules ops/monitoring/alerts.test.yml` passed all firing/recovery cases for PostgreSQL readiness, outbox age, application 5xx and quarantine. Downloaded archive SHA-256 matched the official release asset: `5d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e`.
- Frontend: `npm ci`, lint, typecheck, 21 Vitest/Testing Library tests, production build, and 1 Playwright smoke passed. The browser smoke uses a transient locally signed RS256 OIDC provider, verifies the PKCE S256 challenge, protected customer route, keyboard login, empty orders response and logout; API responses are intercepted.
- Live Docker-backed recovery, seed/reset transaction/interruption/concurrency, quarantine API authorization, real Keycloak browser login, customer browser/database slice, PostgreSQL health transition, and live dashboard/Alertmanager behavior remain unverified until the Docker Engine named-pipe endpoint works. No public deployment was attempted.

## Remaining verification gates at Prompt 2

1. Finish Phase 1’s full transaction/route disposition table, exact OpenAPI header schema, final retry/idempotency retention decision, and rerun all change-sensitive tests.
2. Complete the foreign-resource matrix for every critical handler, prove the actual JWKS outage response, and keep browser OIDC proof blocked on a stable public issuer/client.
3. Add request timeout/body-read enforcement at the Phase 9 ingress and prove public proxy, IP, login and Redis outage policies.
4. Run the new Phase 4–5 container-backed alert/runtime and broker/replay/reset scenarios when Docker is available; the operator contracts are implemented, but those database/Kafka paths remain unverified.
5. Keep Phase 11 `CLOSED_PARTIAL`; local tests do not satisfy sustained RPO, fresh replacement-host recovery or current image advisory disposition.
