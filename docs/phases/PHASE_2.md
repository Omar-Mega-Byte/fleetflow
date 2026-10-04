# Phase 2 — OIDC authentication and object-level authorization

**Status: COMPLETE.** The implementation, local acceptance tests, signed-provider smoke, and CI gate passed. This file began as the Phase 2 specification. Its starting-point and implementation-sequence sections preserve the pre-change plan; the completion evidence below records observed behavior. Phase 2 does not establish general production readiness. [Phase 1](PHASE_1.md) completed the local/test core model. The [execution roadmap](../ROADMAP.md) places security before [Phase 3 lifecycles](../ROADMAP.md#phase-3--explicit-lifecycles). The older phase numbering in [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) is an outline; the execution roadmap controls delivery order.

## Objective and boundary

Replace the Phase 1 fixture actor selector with a verified OIDC identity, resolve that identity to one active local actor, and enforce role **and** object scope at every exposed business boundary. The demonstrable path is: signed bearer token → issuer/audience/time validation → `(issuer, subject)` actor lookup → active role/profile/membership check → scoped PostgreSQL query or command → safe response. A UUID, a client header, or a role alone never proves ownership.

Phase 2 protects the existing domain and establishes reusable policy for future commands. It does not add order or driver state transitions, dispatch, location, payments, public registration, password storage, a FleetFlow token issuer, or broad support/admin override endpoints. Phase 1 accepts **untrusted client-submitted prices**; `POST /api/v1/orders` must remain a loopback-only learning route until a separate, tested merchant-authoritative pricing policy exists. Authentication alone must not make that write publicly available.

## Verified starting point

- `V1__core_model.sql` has unique actor `(issuer, subject)`, one actor per customer/driver profile, active actor and membership flags, and branch-scoped staff memberships. Orders retain customer and branch IDs. There is no persisted actor role yet.
- All current `/api/v1` controllers are registered only in `local`/`test`; `LocalApiBoundary` rejects unsafe public binding. The order controller accepts spoofable `X-Dev-Actor-Id` for those fixtures. No authenticated production business route exists.
- Maven has Spring MVC, JPA, validation, PostgreSQL and Testcontainers, but no Spring Security resource-server dependency. The current `ApiErrors` advice is local/test-only; security filter failures need their own safe response handling.
- The Phase 0 correlation ID filter and Phase 1 error shape can be reused. Hibernate schema validation and Flyway migration ownership must remain intact. `V1` is applied/shared and must not be edited for Phase 2.

## Decisions to freeze before implementation

| Decision | Phase 2 default | Evidence to record |
| --- | --- | --- |
| Token trust | Spring Security OAuth2 resource server validates an external OIDC issuer, signature/JWKS, exact configured audience, expiry and not-before time. Trust is configuration, never taken from the token or actor row. Allow only the selected issuer for each environment. | Wrong issuer/audience/signature/time tests and issuer configuration review. |
| Local identity provider | Use a standards-compliant local OIDC provider, preferably Keycloak, for manual work and CI. The application never mints bearer tokens. Pin a provider image and test the actual signed-token path. | Reproducible local/CI setup, issued test access token and no committed credentials. |
| Local actor mapping | Match verified `iss` and `sub` to exactly one actor row; require `active=true`. No automatic actor creation on first request. | Unknown, duplicate-prevented and deactivated actor cases. |
| Roles and provisioning | Store a small allowlist of roles in PostgreSQL, associated with actors by a forward-only `V2` migration. Seed the first local/test administrator out of band; production provisioning requires a controlled operator path. Never grant roles from arbitrary token claims by default. | Role constraints, bootstrap procedure and privilege-change tests. |
| Object policy | Customer ownership comes from `customers.actor_id`; merchant access requires an **active** membership for the exact branch; driver self access comes from `drivers.actor_id` and checks active/not suspended where the action requires it. | Cross-actor and cross-branch PostgreSQL integration tests. |
| Denial semantics | Invalid/missing token: `401`; valid actor lacking a required role or disabled identity: `403`; an inaccessible specific order/branch resource: `404` to avoid confirming its existence. | Exact status, stable code and safe body tests. |
| Deployment | Any profile registering protected business routes requires issuer and audience configuration and fails closed when missing. Local/test fixture routes remain loopback-only. Production must not accept the Phase 1 header as identity. | Route inventory and startup tests for local, test, default and prod. |

Record the actual provider image/version, issuer URL, audience value, role storage, provisioning command and any changed decisions in a short ADR or the implementation PR. Use external configuration for issuer/audience; do not put tokens, client secrets, realm admin credentials or private keys in Git. If local and container issuer URLs differ, make the documented access path and exact `iss` comparison work without weakening issuer validation.

## Authentication and authorization design

1. **Security filter chain.** Use stateless bearer authentication for `/api/v1/**`; deny unmatched business requests by default. Do not create an HTTP session, cookie login fallback, trusted-user header, or permissive development filter chain. Keep the existing health probes available with hidden details. Decide and test the policy for `/actuator/info`, error routes and any future documentation endpoint explicitly.
2. **Principal resolution.** After JWT validation, construct a small application actor context from the local actor row and its roles. Keep external `iss`/`sub` separate from local UUIDs; do not use email, display name, `preferred_username`, or a request-supplied actor/customer ID as the identity key. Reject inactive or unprovisioned actors before business service execution.
3. **Role and object checks.** A role permits an action category; a database relationship decides which object it can affect. Place resource checks in application services and scoped repository predicates, so another controller or caller cannot skip them. Do not load all orders and filter ownership in memory. Do not treat a merchant-wide role as access to every branch.
4. **Current membership and suspension.** Check the current database facts on each protected action, or use a documented bounded cache with tested revocation semantics. A valid token must not preserve revoked branch membership or a suspended driver privilege. Deactivation cannot rewrite historical orders.
5. **Least privilege.** `SUPPORT`, `DISPATCHER` and `ADMIN` are reserved roles with no implicit all-record access. Add each privileged read/write only when an explicit route, scope, reason and test are justified. A controller annotation by itself is not object authorization.
6. **Error and logging boundary.** Security failures originate in the filter chain, so its authentication entry point and access-denied handler must produce the same safe code/message/path/timestamp/correlation-ID shape as API errors. Do not log bearer tokens, Authorization headers, subjects, issuer credentials, raw bodies or sensitive profile fields. Preserve useful denial and correlation metadata without exposing another tenant's existence.

## Minimal route and policy matrix

The table records the implemented route contract. Fixture details remain available only to local/test ADMIN actors; the actor response omits the external subject and issuer.

| Route or route family | Availability and principal | Object rule / result |
| --- | --- | --- |
| `GET /api/v1/orders/{id}` | Configured secured profiles; `CUSTOMER` | Query by both order ID and the authenticated actor's customer ID; foreign or absent order → `404`. |
| `GET /api/v1/orders` | Configured secured profiles; `CUSTOMER` | Retain Phase 1 bounded filters/sorts, with customer scope in SQL and stable pagination. Do not accept a client-supplied owner ID. |
| `GET /api/v1/merchant-branches/{branchId}/orders` | Configured secured profiles; `MERCHANT` | Narrow branch order summaries; require active exact-branch membership, then branch scope in SQL. No arbitrary merchant or all-branch listing. Unauthorized branch → `404`. |
| `GET /api/v1/drivers/me` | Configured secured profiles; `DRIVER` | Resolve the driver's own profile from the authenticated actor; no arbitrary driver ID lookup. A suspended driver may read minimal self status but cannot perform future operational commands. |
| `POST /api/v1/orders` | `local`/`test` on loopback, with a verified `CUSTOMER` token | Derive customer from token-mapped actor. Preserve Phase 1 validation, transaction and snapshots. **Do not expose in prod/default while prices are client-supplied.** |
| Phase 1 actor/customer/merchant/branch/membership/driver/vehicle fixture create/read routes | `local`/`test` on loopback; provisioned `ADMIN` | Fixture administration only. The first admin is bootstrapped out of band; there is no anonymous or self-assigning admin route. Review returned identity/contact fields. |
| `/actuator/health` probes | Existing health policy | No credentials or hidden detail disclosure. No business route is implicitly permitted by the actuator rule. |

The route conversion should use distinct secured controllers or an equally clear shared policy, without removing `@Profile` from the fixture administration surface as a shortcut. Remove `X-Dev-Actor-Id` from order handling rather than accepting it as an alternate identity. The branch order and driver self reads are intentionally narrow security demonstrators; they do not add lifecycle actions. Any route omitted from the matrix remains denied/unregistered until it receives an explicit policy and test.

## HTTP denial contract

| Situation | Expected response | Rule |
| --- | --- | --- |
| No token; malformed token; wrong issuer, audience, signature, or expired token | `401`, stable `UNAUTHENTICATED` | Do not reveal token parser or key details. |
| Valid token, no local actor; inactive actor; missing role; suspended driver for a prohibited future action | `403`, stable safe authorization code | No database mutation. Do not fall back to `X-Dev-Actor-Id`. |
| Customer requests another customer's order; staff requests a branch without active membership | `404`, stable `RESOURCE_NOT_FOUND` | Use scoped lookup so the response does not disclose whether the object exists. |
| Authorized request with invalid input or a business conflict | Existing `400`/`409` contract | Authentication and object checks do not bypass validation or transaction rollback. |
| Database unavailable after startup | `503` where an HTTP response is possible | No false success or accidental privilege fallback. |

Use a bounded response body with the existing correlation ID; tests must check it for both MVC and security-filter failures. A standard `WWW-Authenticate` challenge may be present on `401`, without diagnostic token contents.

## Implementation sequence

1. **Inventory and freeze.** Enumerate every registered endpoint and profile from code, not just documentation. Decide the provider, issuer/audience, bootstrap, role and 401/403/404 policy. Record the Phase 1 pricing block explicitly.
2. **Add the identity foundation.** Add only the dependencies/configuration and forward `V2` schema needed for roles or provisioning. Keep Flyway as schema owner and `ddl-auto=validate`. Verify existing data can migrate without changing `V1` or granting a role by default.
3. **Protect the HTTP boundary.** Configure token validation and fail-closed startup, replace the fixture actor header in order flows, integrate the actor context and safe security errors, and secure all fixture controllers on loopback.
4. **Enforce object scope.** Convert customer order detail/list to authenticated scope; add the narrow branch-staff and driver-self reads. Put ownership in repository predicates and service policy checks. Make future service methods require an actor context rather than a freely supplied owner UUID.
5. **Prove the boundary.** Add real signed-token integration tests, PostgreSQL-backed cross-tenant tests, route/profile tests, role-revocation tests and negative error cases. Run the full Maven wrapper gate and CI, then update README, API examples and this file with exact evidence.

Keep each slice reviewable and green. Do not promote Phase 1 order creation to a public route as part of the security refactor.

## Verification matrix

| Level | Required cases |
| --- | --- |
| OIDC/JWT | Real signed access token from the chosen local provider works. Missing, malformed, expired, not-yet-valid, wrong-issuer, wrong-audience and bad-signature tokens fail. Unknown key/rotation behavior is documented and tested where feasible. No application-minted token shortcut. |
| Actor/role persistence | Verified `(iss, sub)` maps to one actor; unknown or inactive actor denies access; no role is granted by default. Forward migration, constraints and Hibernate validation pass on real PostgreSQL. |
| Customer objects | Two actors/customers and orders prove own detail/list success, foreign detail `404`, no foreign list rows, stable pagination and no owner-ID/header spoofing. |
| Merchant objects | Two merchants with multiple branches and staff memberships prove exact branch access; same role without membership, inactive membership, and membership revocation deny access on the next request. |
| Driver objects | Two drivers prove `/me` cannot resolve another driver's profile; inactive/suspended facts affect future action eligibility and are not interpreted as a new lifecycle transition. |
| Routes and profiles | Local/test fixture routes require signed `ADMIN` and loopback; prod/default have no anonymous writes or fixture admin routes; unsafe local binding and mixed profiles still fail closed; missing issuer/audience never opens a business route. |
| HTTP/security errors | Exact 401/403/404 behavior, safe body and correlation ID; no stack trace, SQL, token, subject or contact data in denial responses/logs. Existing 400/409 and transactional rollback tests remain green. |
| Build and delivery | Maven wrapper `verify`, CI and a documented local OIDC smoke test pass; record test counts, provider image, migration, commits/PR and any environment-limited checks. |

## Acceptance gate

Mark Phase 2 `COMPLETE` only when all of these are demonstrated:

- [x] The chosen OIDC issuer and audience are configured per environment; invalid or missing tokens cannot reach business code.
- [x] A verified token maps to one active local actor with explicitly provisioned roles; no self-registration or implicit role grant exists.
- [x] Customer, merchant staff and driver object rules are enforced in services and scoped PostgreSQL queries; cross-tenant attempts are denied.
- [x] Membership/actor revocation takes effect according to the documented policy; a valid old token alone cannot restore access.
- [x] Every registered business route has an explicit profile, role and object policy, including the local/test fixture surface; `X-Dev-Actor-Id` is not a trust mechanism anywhere.
- [x] No anonymous business mutation or fixture onboarding is available in default/prod; unsafe local network configuration fails closed.
- [x] Public order creation is still disabled until trusted pricing is implemented and tested.
- [x] 401/403/404 and safe correlated security errors are proven through HTTP tests; old validation/transaction guarantees remain green.
- [x] Real-provider signed-token tests, PostgreSQL integration tests, Maven verification and CI pass, with exact evidence recorded here.

## Learning and review questions

The developer should be able to explain the difference between authentication and authorization; why `iss` plus `sub` maps to a local actor; what validates a JWT and why its audience matters; why a role does not grant access to an arbitrary order or branch; where SQL scope is applied; how 401, 403 and 404 are chosen; how membership revocation affects an existing token; and why Phase 1 submitted prices still block public order creation.

## Completion evidence, 2026-10-04

### Implemented

- **Provider and trust:** Spring Security OAuth2 Resource Server validates RS256 access tokens from the pinned local Keycloak `quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c`. Local Compose uses `OIDC_ISSUER=http://127.0.0.1:8081/realms/fleetflow`, `OIDC_JWKS_URI=http://127.0.0.1:8081/realms/fleetflow/protocol/openid-connect/certs`, and `OIDC_AUDIENCE=fleetflow-api`. The issuer/JWKS/audience values are external configuration. Expiry and not-before use zero clock skew; missing required configuration fails startup. The app never issues a bearer token.
- **Actor and roles:** A verified `iss` plus `sub` selects one active `actors` row; V1 enforces the unique pair. `V2__actor_roles.sql` adds `actor_roles(actor_id, role)` with composite primary key, actor FK with cascade delete, and a check allowlist of CUSTOMER, MERCHANT, DRIVER, SUPPORT, DISPATCHER, ADMIN. It also adds `idx_actor_roles_role` and `idx_orders_branch_created_id`. V2 grants no role. `scripts/Bootstrap-LocalAdmin.ps1` inserts the first loopback ADMIN actor and role out of band; subsequent grants/revocations and production provisioning are operator-controlled database changes. The actor and role rows are re-read on every business request.
- **Routes:** The actual registered API routes are customer `GET /orders`, `GET /orders/{id}` in local/test/default/prod; merchant `GET /merchant-branches/{branchId}/orders` in those profiles; driver `GET /drivers/me` in those profiles; and customer `POST /orders` only in local/test on loopback. Thirteen fixture method/path mappings cover actor, customer, merchant, branch, membership, driver, and vehicle create/read operations in local/test only, all requiring ADMIN. The security filter also denies order POST and fixture routes in default/prod, even if a handler is later added. The exact paths and policies are in the matrix above. No OpenAPI route exists yet. The `/error` path and anonymous health/info probe policy are explicit; production exposes health but not info.
- **Object policy:** Customer detail uses `id` plus authenticated customer ID in the repository query; list pages include customer ID in SQL and retain bounded Phase 1 filters and stable sorting. Merchant service checks an active exact-branch membership plus active branch/merchant, then queries orders by branch ID in SQL and returns a narrow summary. Driver self lookup uses `drivers.actor_id` and reports current active/suspended status. SUPPORT, DISPATCHER and ADMIN have no implicit cross-object read. `X-Dev-Actor-Id` is absent from production source and has no effect on requests.
- **Errors:** Missing/invalid token returns correlated `401 UNAUTHENTICATED`; unknown/inactive actor or missing role returns `403 FORBIDDEN`; foreign and absent specific order/branch return `404 RESOURCE_NOT_FOUND`. Security and MVC responses share the bounded error shape. Actor-resolution database failure returns a safe correlated `503 DATABASE_UNAVAILABLE`. The Phase 1 validation, conflict and rollback contract remains active.

### Tested and verified locally

- `./mvnw.cmd clean verify --no-transfer-progress` passed on Java 21.0.12: **34 tests across 12 classes, 0 failures, 0 errors, 0 skipped**. Docker Engine 29.6.2 and cached PostgreSQL 17.11/Keycloak 26.7.4 images were used. The existing Phase 0/1 tests remained in the suite.
- `Phase2SecurityTests` exercises valid and missing/malformed/expired/not-yet-valid/wrong-issuer/wrong-audience/bad-signature/unknown-key tokens; unknown and inactive actors; no default role; role revocation; spoofed identity header; customer A/B detail and list isolation; exact active branch membership, inactive membership and next-request revocation; two driver self profiles and suspended self-status; ADMIN/non-admin/anonymous fixture access; health/info policy; safe 401/403/404 bodies; and a correlated 503 actor-resolution outage. It also proves a claimed ADMIN token role is ignored.
- `KeycloakProviderTests` starts the pinned provider, imports the realm, creates a user with runtime-generated credentials, obtains a real Keycloak-signed access token, verifies issuer/audience, observes 403 before local actor provisioning, then 200 on the scoped list after explicit actor, role and customer provisioning. No provider credentials or token are committed.
- `DatabaseOutageTests` makes a successful signed customer request, stops its real PostgreSQL container, and confirms the next request returns correlated `503 DATABASE_UNAVAILABLE` without exposing the token, subject, or exception details. Both actor-resolution and MVC transaction-start failures map to the same safe status.
- Fresh PostgreSQL Testcontainer startup applied V1 and V2 and passed Hibernate schema validation. `MigrationUpgradeTests` applied only V1, retained an existing actor while upgrading to V2, found zero implicit roles, confirmed a repeat migrate applied zero migrations, and exercised the role primary-key constraint. The original V1 file is unchanged.
- Route tests cover local/test/default/prod registration, local-only fixture availability, no default/prod order POST handler, default/prod filter denial of order POST and fixture POST even for signed provisioned roles, and the existing unsafe local public-binding and mixed-profile startup failures. `OidcStartupTests` covers missing issuer, audience or JWKS URI across all four profiles. `docker compose --env-file .env.example --profile oidc config --quiet` passed.
- Live loopback smoke started isolated Compose PostgreSQL and Keycloak from the cached pinned images and ran the packaged app with profile `local` on `127.0.0.1:18080`. Discovery returned the exact configured issuer; readiness became UP; anonymous order list returned 401; a real signed token from a user without a local actor returned 403; after out-of-band actor/CUSTOMER/customer provisioning the list returned 200 and zero rows. A spoofed `X-Dev-Actor-Id` was ignored. The smoke services, temporary database volume and environment file were removed afterward. No existing local volume was modified.
- The `Bootstrap-LocalAdmin.ps1` procedure was separately exercised against isolated loopback PostgreSQL after applying V1 and V2: it inserted one actor and ADMIN role in a transaction, and a direct role query confirmed ADMIN. Its temporary Compose project and volume were removed.

### Delivery and limits

- Branch `feature/phase-2-oidc-authorization` contains implementation commit `4c820ba`, documentation commit `f01ec7e`, database-outage fix and test commit `77f66b5`, and completion-evidence commit `82ab650`. [Phase 1 PR #1](https://github.com/Omar-Mega-Byte/fleetflow/pull/1) merged into `main` as `f01f31c`; [Phase 2 PR #2](https://github.com/Omar-Mega-Byte/fleetflow/pull/2) was then retargeted to `main`. Its comparison contains the Phase 2 changes only.
- Both GitHub Actions `verify` runs for completion-evidence head `82ab650` passed: [branch push](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37205186159) and [PR](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37205189236). The GitGuardian Security Check passed. The retargeted PR receives a final CI check before merge.
- A production outage drill, external production identity provider, merchant-authoritative pricing, and a production deployment were not independently verified or implemented in this phase. The loopback Keycloak smoke demonstrates the local issuer path only.
