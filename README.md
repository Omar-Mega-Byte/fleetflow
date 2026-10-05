# FleetFlow

FleetFlow is a Java/Spring learning backend for delivery orchestration. Phases 0, 1, and 2 are complete. Phase 2 adds OIDC bearer authentication, local actor roles, and object-scoped reads; its completion evidence is in [Phase 2](docs/phases/PHASE_2.md). The Phase 3 driver lifecycle prerequisite and Phase 4 live location are under implementation. Dispatch, assignment, payments, and trusted pricing are not implemented.

**Order prices are still submitted by the caller.** `POST /api/v1/orders` is available only with the `local` or `test` profile on a loopback server. Authentication does not make that write suitable for public production use. The `default` and `prod` profiles register only the secured read routes below.

## Prerequisites and verification

- Java 21 and the pinned Maven Wrapper 3.9.16.
- Docker Engine and Compose for the one FleetFlow stack: app, PostgreSQL, local Keycloak, and Redis. Images are pinned in `compose.yaml`; the app is built from pinned Maven and Java base images.
- `./mvnw.cmd clean verify --no-transfer-progress` on Windows, or `./mvnw clean verify --no-transfer-progress` on Linux/macOS. Tests start disposable PostgreSQL, Redis, and Keycloak containers. Check `docker image inspect` for the pinned images before pulling an absent image.

Flyway owns schema changes. `V1__core_model.sql` is immutable; `V2__actor_roles.sql` adds the explicit role table and branch-order index without assigning any role. Hibernate uses `ddl-auto=validate` in every profile.

## Local OIDC setup

1. Copy `.env.example` to `.env`. Set random local `DB_PASSWORD` and `KC_ADMIN_PASSWORD`; choose a local `KC_ADMIN_USER`. Keep `.env` private. Keep the default `OIDC_PORT=8081`, issuer, and JWKS URI aligned unless you change all three together.
2. Start the stack with `docker compose --env-file .env -p fleetflow up -d --build --pull never` when the pinned images are cached. Pull only an absent image first. One Compose project manages the app, PostgreSQL, Keycloak, and Redis. PostgreSQL, Keycloak, Redis, and the app publish only to loopback. Keycloak imports the versioned realm configuration without users or secrets, requires `KC_ADMIN_USER` and `KC_ADMIN_PASSWORD`, and persists local development data in its own volume. The containerized app runs the restricted `prod` profile.
3. Check `http://127.0.0.1:8081/realms/fleetflow/.well-known/openid-configuration`. Its `issuer` must equal `OIDC_ISSUER` exactly. The client `fleetflow-cli` puts `fleetflow-api` in access-token `aud`; `OIDC_AUDIENCE` must match exactly.
4. Sign in to the local Keycloak Admin Console with the private bootstrap credentials. In realm `fleetflow`, create an enabled development user with username, email, first and last names, a permanent password, and no outstanding required actions. Copy its Keycloak user ID (`sub`), which is a UUID. For the first application administrator run `./scripts/Bootstrap-LocalAdmin.ps1 -Subject <Keycloak-user-id>`. The script inserts an active actor and ADMIN role into local PostgreSQL in one transaction. It does not expose an anonymous bootstrap endpoint. The Keycloak bootstrap administrator is distinct from a FleetFlow ADMIN actor.
5. Use the app already in Compose at `127.0.0.1:8080`. For local/test fixture APIs and Swagger, stop the Compose app service and run `./scripts/Run-Local.ps1`; it binds to loopback and uses the same PostgreSQL, Keycloak, and Redis services. Health liveness and readiness are at `/actuator/health/liveness` and `/actuator/health/readiness`.
6. Obtain a development access token from Keycloak's `fleetflow-cli` client. For example, in PowerShell, set `$username`, read `$password = Read-Host -AsSecureString`, then call `Invoke-RestMethod -Method Post -Uri "$env:OIDC_ISSUER/protocol/openid-connect/token" -Body @{grant_type='password';client_id='fleetflow-cli';username=$username;password=($password | ConvertFrom-SecureString -AsPlainText)}`. Set `$env:OIDC_ISSUER` from `.env` first or use its literal loopback URL. Keep the returned access token in a temporary shell variable and send `Authorization: Bearer <token>` to the API. The password grant exists only in this local learning realm.
7. Open [local Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). Select **Authorize** and paste the access token value; Swagger UI adds the `Bearer` prefix. The token must map to an active FleetFlow actor with the role listed for that operation.

Use `docker compose --env-file .env -p fleetflow down` to stop the stack. The database volume persists; `down --volumes` would erase it.

### Swagger and OpenAPI

With the `local` or `test` profile on loopback, Swagger UI at `/swagger-ui/index.html` shows all **24 registered `/api/v1` operations**. The machine-readable contract is at `/v3/api-docs` (JSON) or `/v3/api-docs.yaml`. Operations are grouped by customer, merchant, driver and fixture purpose. Each lists its required role and profile, request and response schema where applicable, supported filters, and safe error responses.

The local/test page includes fixture ADMIN routes and `POST /api/v1/orders`. Those routes are absent from default/prod, where the OpenAPI and Swagger endpoints are disabled. Submitted order prices remain untrusted, so the create route is only for loopback learning. The Actuator health probes are operational endpoints documented above; they are intentionally outside the business API contract.

To try a customer read, provision an actor with a `CUSTOMER` role and customer profile, authorize Swagger with that actor's Keycloak token, then open **Customer orders**. To try a fixture route, use a separately provisioned `ADMIN` actor. An ADMIN role alone grants no customer or branch scope. The API never accepts `X-Dev-Actor-Id` as identity. Keep bearer tokens out of screenshots and committed examples.

### Provisioning and revocation

Only the local/test ADMIN fixture route creates an actor record. It returns the internal actor UUID and active flag; the OIDC issuer and subject remain distinct from that UUID. Creating an actor does **not** grant a role. An operator with database authority grants or revokes roles in `actor_roles`, for example:

```sql
INSERT INTO actor_roles (actor_id, role) VALUES ('<actor-uuid>', 'CUSTOMER');
DELETE FROM actor_roles WHERE actor_id = '<actor-uuid>' AND role = 'CUSTOMER';
UPDATE merchant_staff_memberships SET active = false WHERE id = '<membership-uuid>';
```

The allowlist is CUSTOMER, MERCHANT, DRIVER, SUPPORT, DISPATCHER, ADMIN. Role changes, actor deactivation, and branch membership revocation take effect on the next request, including while an old bearer token remains valid. SUPPORT, DISPATCHER, and ADMIN do not inherit customer or merchant object access. Production provisioning is an out-of-band operator responsibility; there is no registration, password database, role self-assignment, or FleetFlow token issuer.

## Configuration

| Variable | Meaning |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Required application database connection. |
| `DB_NAME`, `DB_PORT` | Compose database name and loopback host port; default port 5433. |
| `OIDC_ISSUER` | Exact trusted `iss`, required at startup in every profile. |
| `OIDC_JWKS_URI` | Trusted provider key endpoint, required at startup. |
| `OIDC_AUDIENCE` | Required access-token audience, `fleetflow-api` locally. |
| `OIDC_PORT` | Local Keycloak loopback host port, default 8081. |
| `KC_ADMIN_USER`, `KC_ADMIN_PASSWORD` | Private local Keycloak bootstrap credentials; required when starting the OIDC Compose profile. |
| `SPRING_PROFILES_ACTIVE`, `SERVER_PORT` | Active profile and HTTP port; local script sets `local`, default HTTP port 8080. |

Missing issuer, JWKS URI, or audience fails application startup. Signed tokens must have a trusted signature, exact issuer and audience, subject, expiry, and valid not-before time. The application reads roles only from PostgreSQL, not token role, email, or username claims. Business routes use stateless bearer authentication with no session, form login, basic auth, or trusted development header.

## Route and policy matrix

| Method and path | Profiles | Policy |
| --- | --- | --- |
| `GET /api/v1/orders/{id}` | local/test/default/prod | CUSTOMER; query includes order ID and the caller's customer ID. Foreign and absent orders both return `404 RESOURCE_NOT_FOUND`. |
| `GET /api/v1/orders` | local/test/default/prod | CUSTOMER; customer scope is in the SQL query, with bounded Phase 1 filters, sort, and pagination. No owner parameter. |
| `GET /api/v1/merchant-branches/{branchId}/orders` | local/test/default/prod | MERCHANT plus current active membership for that exact active branch; narrow summaries, branch-scoped SQL. Inaccessible branch returns `404 RESOURCE_NOT_FOUND`. |
| `GET /api/v1/drivers/me` | local/test/default/prod | DRIVER; resolves only the caller's profile, including durable operational state. |
| `POST /api/v1/drivers/me/online`, `/available`, `/pause`, `/offline` | local/test/default/prod | DRIVER self transitions with durable guards and history; `204` accepted, `409` invalid state. |
| `POST /api/v1/drivers/me/location`, `/heartbeat` | local/test/default/prod | DRIVER self operational updates; Redis liveness is separate from durable availability. `204` accepted. |
| `POST /api/v1/orders` | local/test, loopback | CUSTOMER; caller-derived customer and existing validation, snapshots, and rollback. Submitted prices are untrusted. No default/prod handler. |
| Fixture actor/customer/merchant/branch/membership/driver/vehicle create/read routes | local/test, loopback | Explicitly provisioned ADMIN only; no production fixture controllers. |
| `/actuator/health/**`, `/actuator/info` | Health in all profiles; info except prod | Anonymous probes, hidden health details. Other routes do not inherit this permit rule. |

The order list allows `branchId`, `status=CREATED`, inclusive `from`, exclusive `to`, zero-based `page`, `size` 1–100, and `sort=createdAt,asc/desc` or `id,asc/desc`. `POST /api/v1/orders` retains Phase 1's request body and exact decimal calculation; [Phase 1](docs/phases/PHASE_1.md) records that behavior. All API requests use `Authorization: Bearer <access-token>`; `X-Dev-Actor-Id` has no identity effect.

API and security errors include a stable code, safe message, path, timestamp, correlation ID, and bounded fields list. Missing or invalid tokens return `401 UNAUTHENTICATED`; a valid token without a provisioned active actor or required role returns `403 FORBIDDEN`; object-scope denial returns `404 RESOURCE_NOT_FOUND`. Database failure returns a safe `503` where a response can be sent. `X-Correlation-ID` is validated or generated and returned on both MVC and security-filter responses. Health details are hidden. Do not log tokens, credentials, subjects, request bodies, or SQL bind values.

## Delivery status

The [execution roadmap](docs/ROADMAP.md) tracks phase status. [Phase 2](docs/phases/PHASE_2.md) contains its test, provider, migration, CI, smoke, and delivery evidence, with any unverified gates called out. No production readiness claim follows from this security slice alone.
