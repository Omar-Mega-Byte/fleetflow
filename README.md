# FleetFlow

FleetFlow is a Java/Spring learning project for delivery orchestration. **Current capability: Phase 0 foundation only.** It starts a Spring Boot process with PostgreSQL, validates the schema through Flyway, and exposes health probes. There are no business APIs, entities, assignments, authentication rules, Redis, or Kafka yet. The [roadmap](docs/ROADMAP.md) and [domain model](docs/architecture/DOMAIN_MODEL.md) describe planned work.

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

Flyway runs at startup and rejects invalid migrations. Hibernate uses `ddl-auto=validate` in every profile and cannot create tables. Phase 0 has no entity schema, so `db/migration` contains no `V1` script. Phase 1 will add the first real migration using `V1__description.sql`; applied migrations are forward-only. Use lower snake case, UTC timestamps, explicit constraints, and indexes tied to actual queries. No `baselineOnMigrate` is used.

## Health and logs

`/actuator/health/liveness` reflects process liveness; `/actuator/health/readiness` includes database availability. Only `health` and `info` are exposed locally; production exposes `health` only. Details are hidden. The app echoes or generates a safe `X-Correlation-ID` on requests and includes it in log lines. Never log credentials, tokens, full request bodies, or SQL bind values; local SQL statement logging is for development only. `/api/v1` is reserved for future business APIs. Their error body will use a stable code, message, correlation ID, and timestamp once a real endpoint exists.

## Tests and workflow

`./mvnw.cmd verify` is the local gate; `.github/workflows/verify.yml` runs the same Maven lifecycle on pushes and pull requests. Test classes use `*Tests`; future unit tests stay local and fast, while repository/MVC/security/integration tests use their real dependencies. Phase 0's integration smoke starts PostgreSQL through Testcontainers and checks JDBC and Flyway without creating fictional domain tables. If it fails with `Could not find a valid Docker environment`, start Docker Desktop and rerun. If the local database port is occupied, change `DB_PORT` and `DB_URL` together.

Use short feature branches and pull requests for later phases. Branch protection is not claimed until configured and verified on the GitHub repository. The [Phase 0 evidence](docs/phases/PHASE_0.md) records what was actually checked.
