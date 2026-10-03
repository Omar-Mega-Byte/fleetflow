# Phase 0 — foundation and project engineering

**Status: COMPLETE.** Foundation implementation and its acceptance gate passed on 2026-10-03. [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) remains the living context; decisions and later phases are in [ROADMAP.md](../ROADMAP.md) and [ARCHITECTURE_PLAN.md](../architecture/ARCHITECTURE_PLAN.md).

## Objective and business scope

Produce a reproducible, secure-by-default Spring backend foundation that a developer can build, run, test and explain before product features begin. There are no customer, merchant, driver, order, dispatch, location or delivery APIs in this phase. A health endpoint is infrastructure, not a product feature.

## Engineering sequence and deliverables

1. **Repository and toolchain.** Initialize Git only after checking the directory is still uninitialized; add a focused `.gitignore` for Maven output, IDE files, local `.env`, secrets and logs. Create a single-module Maven Wrapper project with `groupId=com.fleetflow`, `artifactId=fleetflow`, Java 21 and a pinned supported Spring Boot 4.1.x patch. Pin Maven Wrapper distribution and checksum and record `java -version`, wrapper version and expected Docker/Compose versions in README. Use UTF-8 and deterministic test settings. No multi-module build.
2. **Minimal application.** Add the Spring Boot entry point under `com.fleetflow`, a thin package layout or package-boundary documentation for future modules, Spring MVC, validation, JPA, PostgreSQL driver, Flyway and Actuator dependencies as needed for the foundation. Avoid empty controller/service/entity packages created just to show architecture. Add a startup smoke test. Do not add Kafka, Redis, security role logic or OpenAPI until their first use. Check the Boot 4.1 dependency names and compatibility at execution time.
3. **Configuration.** Use typed external configuration where values exist; shared defaults plus `local`, `test`, `prod` profiles. Require DB URL/user/password from environment; `.env.example` contains placeholders only. No production secrets/default passwords. Set application time handling to UTC, bounded HTTP/request settings, useful SQL logging only in local profile, and `ddl-auto=validate` when entity mappings arrive. Define a stable error shape and correlation ID for later APIs, with a simple handler only if a real endpoint needs it; do not invent a catalog of unimplemented domain errors.
4. **PostgreSQL and Flyway.** Provide local Compose for PostgreSQL 17 only, with a health check, loopback port binding and persistent local volume. Wire Flyway into startup and tests. Because this is a new empty database, do **not** use `baselineOnMigrate` or a fake no-op migration. The first versioned migration belongs to Phase 1 when real domain tables exist. Document naming (`Vn__description.sql`), forward-only edits, lower snake case, UTC timestamps, explicit constraints and indexes. Confirm clean database startup and migration validation; confirm production profile cannot silently create schema from Hibernate.
5. **Test infrastructure.** Add JUnit/Boot test baseline and one useful context/configuration smoke test. Add PostgreSQL Testcontainers configuration only if it runs and proves DB/Flyway wiring; otherwise introduce it with Phase 1's first real migration. Keep tests independent of a developer's installed PostgreSQL. Define a test naming scheme and separation for unit, repository, MVC/security and integration tests. Record Docker prerequisites and failure diagnostics.
6. **CI and code quality.** Add a GitHub Actions job that uses Java 21 and the Maven Wrapper, compiles and runs the tests on each PR/push; include Docker/Testcontainers only if the Phase 0 suite needs it. Cache Maven dependencies, fail on tests, and avoid leaking env values. Add simple formatting/checkstyle only if maintainable. Do not defer the first CI gate to Phase 11. Document branch/PR workflow without asserting branch protection exists until configured and verified.
7. **Docs and architecture baseline.** Add README with purpose, current capability list (foundation only), prerequisites, exact local build/run/test commands, configuration table, health check and troubleshooting. Keep PROJECT_CONTEXT living and update roadmap status only after evidence. Record short ADRs for Java/Boot/PostgreSQL, modular monolith and migration policy; update conceptual ERD from [DOMAIN_MODEL.md](../architecture/DOMAIN_MODEL.md), clearly marked planned. Document API version prefix and future module dependency rule. Do not claim planned endpoints are live.
8. **Operations baseline.** Make liveness/readiness semantics explicit: readiness depends on required DB availability, liveness indicates the process. Expose only health/info necessary for local operations; production endpoint exposure/access must be intentional. Establish request correlation and log redaction conventions. Verify a clean start and stop with PostgreSQL, and a clear failure when PostgreSQL is unavailable. Preserve a reproducible command transcript in the Phase 0 completion note.

## Scope by concern

| Concern | Phase 0 result |
| --- | --- |
| Java/Spring learning | Developer explains startup, dependency injection, profiles/configuration properties, MVC versus Actuator, Maven lifecycle, test context, Flyway purpose and package boundaries |
| Domain changes | None; conceptual ownership and ERD only |
| Database changes | PostgreSQL local database and Flyway wiring; no business tables or no-op migration |
| API changes | Health/readiness only; reserve `/api/v1` for later business API |
| Infrastructure | One PostgreSQL Compose service; JDK, Maven Wrapper, CI baseline |
| Security | Secret-free config, loopback DB binding, narrow Actuator exposure, no anonymous business endpoints |
| Tests | Application startup/configuration and, if included, PostgreSQL/Flyway integration smoke test |
| Failure cases | Missing DB credentials, DB unavailable, invalid config and failed migration startup must fail visibly |

## Acceptance gate

- From a clean checkout with documented prerequisites, `./mvnw verify` (Windows: `./mvnw.cmd verify`) succeeds using the wrapper, and CI runs the same gate. Report actual test counts, not estimated ones.
- Local Compose starts only PostgreSQL; health check passes; application starts with explicit local config and reports readiness. A fresh database and a restarted database behave consistently.
- DB startup/migration validation fails clearly for bad credentials/unavailable DB. Hibernate cannot create business tables automatically in deployable profiles. No real secrets or local database files are tracked.
- README commands work on the developer's platform, and architectural docs accurately label future features as planned. Git status is clean after normal build except ignored outputs.
- Developer can explain why a migration is not created without schema work, how profile and environment precedence works, and how package boundaries will prevent repository cross-access.

## Dependencies, non-goals and risks

Dependencies: a JDK 21 distribution, Docker availability for local Postgres and any Testcontainers, supported Maven Wrapper, Git and a reachable dependency repository. Check installed versions and existing Docker images before any download. Exact patch/image digests are chosen when implementation starts, based on supported upstream releases.

Explicit non-goals: entities, domain migrations, CRUD, authentication policy, Redis, Kafka, PostGIS, dispatch, SSE, simulators, dashboards, production deployment and generic abstraction frameworks. Avoid building an empty package forest, custom security system, or fake migration. Main risks: Boot 4 starter/dependency mismatch, CI Docker availability, Windows path and shell differences, secrets accidentally committed, and documentation claiming business behavior too early.

## Phase 0 completion evidence template

Record: exact commit/PR if used; Java/Maven/Boot/PostgreSQL versions and pinned image; clean-checkout commands and exit codes; test count; startup and DB-failure behavior; CI URL/result; health/readiness checks; files created; unresolved constraints. Only then update status to `COMPLETE`. If an environment check cannot run, mark it unverified and keep the phase incomplete or explicitly narrow its acceptance with a documented reason.

## Execution evidence, 2026-10-03

- Toolchain: Temurin Java 21.0.12; Maven Wrapper 3.9.16 with a checked SHA-512 distribution; Spring Boot 4.1.1; Docker Engine 29.6.2 and Compose v5.3.1. Local `postgres:17.11-alpine3.24` was already cached and Compose started it with `--pull never` from pinned digest `sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24`. Testcontainers used the same cached PostgreSQL image. It downloaded `testcontainers/ryuk:0.14.0` because that helper tag was absent.
- `./mvnw.cmd verify --no-transfer-progress` passed with 3 tests, 0 failures, 0 errors, 0 skipped. The tests checked PostgreSQL JDBC and Flyway wiring plus correlation ID safety. The clean-checkout [GitHub Actions verify run](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37122294501) passed for commit `947080ac93c637db5816a07e0d1d6c8e90923279`.
- `docker compose --env-file .env -p fleetflow up -d --no-build --pull never` exited 0 and PostgreSQL became healthy on `127.0.0.1:5433`. `./scripts/Run-Local.ps1` started the local profile. Liveness and readiness both returned HTTP 200; `X-Correlation-ID` was present.
- Stopping PostgreSQL produced liveness HTTP 200 and readiness HTTP 503. Restarting the same container returned readiness to HTTP 200. `flyway_schema_history` existed with 0 applied migration rows, and no business tables were introduced.
- A separate startup with bad credentials exited 1 with PostgreSQL authentication failure. A startup pointing to an unavailable port exited 1 with a Flyway connection-refused error. Missing DB configuration also exited 1. There is no real migration to fail in Phase 0; migration validation of a substantive schema is deferred to Phase 1.
- The [GitHub repository](https://github.com/Omar-Mega-Byte/fleetflow) was verified `PRIVATE`. Local `.env` is ignored and contains a generated password; `git ls-files .env target` returned no paths. `git diff --cached --check` passed before the initial commit, and `git rev-list --left-right --count origin/main...main` returned `0 0` after its push. The working tree was clean apart from ignored build and local config output.
