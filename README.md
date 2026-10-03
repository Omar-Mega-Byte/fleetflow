# FleetFlow

FleetFlow is a Java/Spring learning project for delivery orchestration. **Phases 0 and 1 are complete.** Phase 0 provides the Spring Boot/PostgreSQL/Flyway foundation and health probes. Phase 1 adds local/test-only fixture onboarding and order create/detail/list endpoints with durable snapshots. There is no production business API, authentication, dispatch, assignment, Redis, Kafka or payment handling. The [roadmap](docs/ROADMAP.md) and [Phase 1 evidence](docs/phases/PHASE_1.md) track verified work and later plans.

## Prerequisites

| Tool | Phase 0 version |
| --- | --- |
| JDK | Java 21; verified locally with Temurin 21.0.12 |
| Maven | Wrapper pins Maven 3.9.16 and verifies the distribution SHA-512; no global Maven needed |
| Spring Boot | 4.1.1 |
| Docker Engine / Compose | Verified locally with Engine 29.6.2 and Compose v5.3.1 |
| PostgreSQL | Cached image `postgres:17.11-alpine3.24`, pinned by digest in `compose.yaml` |

The first Maven run may download Java dependencies. Docker must be running for the integration test. Testcontainers may pull its small Ryuk helper image if its managed version is absent. The wrapper may download Maven on a clean machine, then verifies its checksum.

## Run locally on Windows PowerShell

1. Check the image cache with `docker image inspect postgres:17.11-alpine3.24`. If it is absent, use `docker pull postgres:17.11-alpine3.24`. The pinned digest is in `compose.yaml`.
2. Copy `.env.example` to `.env` and replace `DB_PASSWORD` with a random local password. Keep `.env` private. The default local database port is `5433`; if you change `DB_PORT`, change the port in `DB_URL` too.
3. Start the database: `docker compose --env-file .env -p fleetflow up -d --pull never` when the image is cached, or omit `--pull never` if a pull is required. Check `docker compose --env-file .env -p fleetflow ps` for `healthy`.
4. Run `./mvnw.cmd verify --no-transfer-progress`. The test uses a separate disposable PostgreSQL Testcontainer and does not depend on the local Compose database.
5. Run `./scripts/Run-Local.ps1` in one terminal. In another terminal, check `Invoke-RestMethod http://127.0.0.1:8080/actuator/health/liveness` and `Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness`. Both should report `UP` when the database is available.
6. Stop the app with Ctrl+C. Stop PostgreSQL with `docker compose --env-file .env -p fleetflow down`. The named volume persists; `down --volumes` would erase local data.

For Linux/macOS, export `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `SPRING_PROFILES_ACTIVE=local`, then run `./mvnw verify` and `./mvnw spring-boot:run`. Ensure `.env` and shell exports agree.

## Configuration

| Variable | Purpose | Required |
| --- | --- | --- |
| `DB_URL` | JDBC URL for application PostgreSQL | Yes |
| `DB_USER` | Database login and Compose initialization user | Yes |
| `DB_PASSWORD` | Database password | Yes |
| `DB_NAME` | Compose database name | For Compose |
| `DB_PORT` | Loopback host port for Compose, default `5433` | For Compose |
| `SPRING_PROFILES_ACTIVE` | `local`, `test`, or `prod`; script sets `local` | Recommended |
| `SERVER_PORT` | HTTP port, default `8080` | No |

Environment variables override shared `application.yml`; a profile file then contributes profile-specific settings. The test supplies ephemeral database values with `@DynamicPropertySource`, which takes precedence. There are no database credential defaults. Production must supply all three application database values. Local binds HTTP and PostgreSQL to loopback. The deployer must provide the production network and access policy before public deployment.

Flyway runs at startup and rejects invalid migrations. Hibernate uses `ddl-auto=validate` in every profile and cannot create tables. `V1__core_model.sql` is the first real migration, covering actors, profiles, branches, vehicles and orders/items. Applied migrations are forward-only. Schema naming, UUID, UTC, money and coordinate decisions are recorded in [ADR 0002](docs/architecture/ADR-0002-phase-1-conventions.md). No `baselineOnMigrate` is used.

## Health and logs

`/actuator/health/liveness` reflects process liveness; `/actuator/health/readiness` includes database availability. Only `health` and `info` are exposed locally; production exposes `health` only. Details are hidden. The app echoes or generates a safe `X-Correlation-ID` on requests and includes it in log lines. Never log credentials, tokens, full request bodies, or SQL bind values; local SQL statement logging is for development only. Phase 1 errors include a stable code, safe message, path, timestamp, correlation ID and bounded validation fields.

## Phase 1 local API

The following `/api/v1` controllers exist only with exactly one active profile, `local` or `test`, and a loopback `server.address`. `prod` and the default profile register no business routes; a local/test profile bound to a public address fails startup. The `X-Dev-Actor-Id` header selects a **spoofable local fixture**, not an authenticated user. Phase 2 will replace it with verified identity and object authorization. Never expose these endpoints publicly. Order item prices are submitted learning data and are not merchant-authoritative.

| Route | Local/test behavior |
| --- | --- |
| `POST/GET /api/v1/actors[/{id}]` | Create/read an external `(issuer, subject)` fixture, no password. |
| `POST/GET /api/v1/customers[/{id}]` | Create/read a customer linked to an actor. |
| `POST/GET /api/v1/merchants[/{id}]` | Create/read a merchant. |
| `POST/GET /api/v1/merchant-branches[/{id}]` | Create/read a pickup branch. |
| `POST /api/v1/merchant-staff-memberships` | Create a branch membership fixture. |
| `POST/GET /api/v1/drivers[/{id}]` and `POST/GET /api/v1/vehicles[/{id}]` | Create/read driver and vehicle fixtures; no availability action. |
| `POST /api/v1/orders` | Create a `CREATED` order for the fixture actor's customer; returns `201` and `Location`. |
| `GET /api/v1/orders/{id}` | Read one order with immutable snapshots; another fixture actor receives `404`. |
| `GET /api/v1/orders` | Scoped page; optional `branchId`, `status=CREATED`, inclusive `from`, exclusive `to`, zero-based `page`, `size` 1–100 and `sort=createdAt,asc/desc` or `id,asc/desc`. |

Example sequence after local startup: create an actor, then a customer with its `actorId`; create a merchant and branch with pickup address/coordinates; create an order with the actor ID in the header. These create endpoints return the new UUID and a `Location` header. Staff membership creation returns its UUID in the body without a `Location` header because it has no detail endpoint. The following body illustrates order intake; replace the UUIDs with locally created fixture IDs:

```json
{
  "branchId": "00000000-0000-0000-0000-000000000001",
  "delivery": {"line1": "New Street", "city": "Cairo", "latitude": 30.050000, "longitude": 31.240000},
  "currency": "EGP",
  "items": [{"description": "Bread", "quantity": 2, "unitAmount": 12.50}]
}
```

All prices use decimal scale 2. The server calculates line and order totals exactly and copies customer, branch, pickup, delivery and item facts into the order. The list response has page metadata and order summaries; detail includes items. Invalid input returns `400`, missing resources `404`, and stored-data conflicts `409` with the safe error body described above.

## Tests and workflow

`./mvnw.cmd verify --no-transfer-progress` is the local gate; `.github/workflows/verify.yml` runs the Maven `verify` lifecycle on pushes and pull requests. Test classes use `*Tests`. Phase 1 tests start PostgreSQL through Testcontainers for migration/constraint, service rollback, snapshot, API, query and profile-boundary checks. If they fail with `Could not find a valid Docker environment`, start Docker Desktop and rerun. If the local database port is occupied, change `DB_PORT` and `DB_URL` together.

Use short feature branches and pull requests. Branch protection is not claimed until configured and verified on the GitHub repository. The [Phase 0 evidence](docs/phases/PHASE_0.md) and [Phase 1 evidence](docs/phases/PHASE_1.md) record their completed gates.
