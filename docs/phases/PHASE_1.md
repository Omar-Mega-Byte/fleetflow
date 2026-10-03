# Phase 1 — core model, persistence and REST

**Status: COMPLETE.** The Phase 1 acceptance gate passed on 2026-10-03; the evidence is recorded below. Phase 0 is complete; see [its evidence](PHASE_0.md). [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) is the living product context, [ROADMAP.md](../ROADMAP.md) defines phase order, and the [domain model](../architecture/DOMAIN_MODEL.md) and [architecture plan](../architecture/ARCHITECTURE_PLAN.md) guide ownership and persistence.

## Objective and boundary

Build a small, durable Spring MVC/JPA slice for onboarding actors, merchants, branches, drivers and vehicles, then accepting and reading orders with item and address snapshots. Teach the complete request → validation → application service → repository → PostgreSQL → response path. An order is created only in `CREATED`; fulfillment, dispatch and arbitrary status updates belong to later phases.

Phase 2 adds authentication and object-level authorization. Until then, **every Phase 1 business endpoint must be reachable only in the `local` and `test` profiles on loopback/test infrastructure**. A deployable profile must not register these endpoints or otherwise serve unauthenticated business writes, and the application must reject an unsafe profile/network combination. Local actor fixtures or IDs support learning and tests only; they confer no authority and must not be described as security. Do not introduce a production trusted-user header or a custom token issuer.

The smallest end-to-end demonstration is: create local fixture actors and profiles → create a merchant and active branch → create a driver and vehicle → create an order for that branch → read it back and page through the customer's orders. An integration test then changes a source profile and confirms the order's historical snapshots have not changed; a public profile-edit API is not required. Driver records exist to establish the future dispatch model; they do not participate in order creation or matching yet.

### Phase boundary with later work

| Phase 1 establishes | Next owner |
| --- | --- |
| Stable actor IDs, external subject mapping and branch membership data | Phase 2 validates OIDC tokens and enforces role plus object access. |
| `CREATED` order with customer, branch, address, item and price facts | Phase 3 adds guarded transitions and durable status history. |
| Driver and vehicle profiles with active/suspended facts | Phase 3 adds operational driver state; Phase 4 adds Redis location and heartbeat. |
| PostgreSQL constraints and indexes for intake/list queries | Phases 5–7 add dispatch, reservation and incident schema. |
| Local-only price snapshot suitable for a learning flow | A trusted pricing source/policy must be decided before public order intake. Phase 2 security alone does not make client-supplied prices authoritative. |

## Deliverables and implementation sequence

1. **Freeze small conventions before schema work.** Choose one UUID generation strategy, UTC `Instant` storage/serialization convention, `created_at`/`updated_at` semantics, string status codes, constraint names and migration numbering. Decide the v1 currency and monetary precision/rounding policy, address fields, and whether order prices are supplied by a local fixture or copied from a minimal catalog. Document these decisions before treating an amount as trustworthy. No payment, tax or settlement behavior is implied.
2. **Create the first real Flyway migration.** Add `V1__...sql` for only the tables needed by this slice: actor/external identity mapping, customer, merchant, merchant branch, merchant staff membership, driver, vehicle, order and order item. Use PostgreSQL foreign keys, non-null, uniqueness and check constraints for invariant data, and indexes justified by actual list queries. Keep applied migrations forward-only; no `baselineOnMigrate`, Hibernate schema creation or speculative dispatch tables. Verify a fresh database and startup with `ddl-auto=validate`.
3. **Add domain and persistence code by owner.** Keep `identity`, `customer`, `merchant`, `driver` and `order` boundaries within the single Maven module. Pass IDs or narrow read models across owners rather than reaching into another package's JPA repository. Use entities for durable aggregates, immutable request/response DTOs for the API and explicit mapping. Choose relationship ownership and fetch plans deliberately; avoid exposing entities, broad `CascadeType.ALL`, eager graphs and Open Session in View.
4. **Implement narrowly scoped local onboarding.** Provide only the create/read operations needed to establish actor, customer, merchant, branch, membership, driver and vehicle fixtures for order intake. Admin-controlled onboarding is the planned policy; Phase 1 endpoints are local/test learning surfaces, not public registration. Persist external `(issuer, subject)` uniquely without storing passwords. Keep active/suspended fields durable, but defer driver operational transitions and histories to Phase 3.
5. **Implement order intake and reads.** `POST /api/v1/orders` validates a known customer, active branch, nonempty items, positive quantities, valid delivery address and finite WGS84 coordinates. Persist the order, items and immutable delivery/address/price snapshots in one transaction with initial status `CREATED`. `GET /api/v1/orders/{id}` returns a bounded DTO. `GET /api/v1/orders` uses a bounded page size, explicit fixture-actor scope, documented filters and an allowed sort list with a stable tie-break. Do not implement confirmation, preparation, cancellation, delivery or dispatch endpoints here.
6. **Complete HTTP behavior and errors.** Return `201` with a `Location` header for creation, `200` for reads, `400` for malformed/invalid requests, `404` for missing references or orders, and `409` for documented uniqueness/conflict cases. Use the Phase 0 correlation ID and a stable error body without stack traces or sensitive data. Make pagination limits, filter names and sort choices part of the endpoint contract; reject unsupported sorts rather than interpolating client input into SQL.
7. **Verify, document and close the gate.** Extend the existing PostgreSQL Testcontainers and CI suite with migration, repository, transaction and MVC tests. Capture one representative list-query plan and query-count/N+1 evidence. Update the README, ERD/domain model and endpoint examples from actual code, then record exact verification and CI results below before marking this phase complete.

## Decisions to record before coding

These are working recommendations, not already implemented facts. Write the chosen values and any changed rationale in the implementation PR or a short ADR before freezing `V1`.

| Decision | Working recommendation | Evidence needed |
| --- | --- | --- |
| Primary keys | UUID for each aggregate and child row; generate consistently in the application or database. Never derive authorization from an unguessable ID. | Fresh insert and JDBC/JPA round-trip tests. |
| Time | UTC instants in code and API; PostgreSQL `timestamptz`; database-generated audit time or injected clock, chosen consistently. | Round-trip across a non-UTC JVM time zone; deterministic test clock where domain time is used. |
| Names and migration | Lower snake case tables/columns, named `pk_`, `fk_`, `uk_`, `ck_`, `idx_` constraints/indexes, first substantive `V1__...sql`; no edits after application. | `flyway_schema_history`, `ddl-auto=validate` and catalog inspection. |
| Money | One ISO 4217 currency per order; decimal rather than floating point; positive/nonnegative rules, scale, rounding and total derivation recorded explicitly. | Boundary and arithmetic tests, including overflow or excessive scale. |
| Price source | For this local learning slice, either a controlled fixture price or a clearly labeled untrusted submitted amount; store the final snapshot once. Do not imply merchant-approved prices. | Snapshot test and a documented pre-public-intake pricing decision. |
| Address/coordinates | Required delivery address fields and validated latitude/longitude with WGS84 ordering; branch pickup point is a durable profile value copied into an order snapshot if historical pickup location matters. | Boundary, nonfinite and profile-edit tests. |
| Local actor context | A local/test-only fixture selector, preferably explicit in test setup and documented for manual calls; no selector in deployable profiles. The selector is spoofable and never an authentication result. | Tests with two fixture actors and a prod startup/route absence check. |
| Driver vehicle link | Decide whether `vehicle.driver_id` or `driver.vehicle_id` owns the v1 relation; enforce at most one active vehicle per driver if that rule is used. | FK/uniqueness and replacement behavior tests. |

## Proposed persistent model

The table names below make `V1` reviewable; exact optional profile fields can be trimmed if no Phase 1 endpoint uses them. Use separate migrations only when there is a real forward dependency. Keep mutable profile fields in their owner tables and immutable order facts in order tables or embeddables mapped to order columns.

| Table / owner | Minimum fields and relationships | Database rules to verify |
| --- | --- | --- |
| `actors` / identity | `id`, `issuer`, `subject`, `active`, timestamps; role representation only as needed for future mapping. | Unique `(issuer, subject)`; nonblank issuer/subject; no password columns. |
| `customers` / customer | `id`, unique `actor_id`, display/contact fields needed for fixtures. | FK to actor; one customer profile per actor in v1; preserve inactive profiles. |
| `merchants` / merchant | `id`, name, `active`, timestamps. | Nonblank name; deactivation does not delete branches/orders. |
| `merchant_branches` / merchant | `id`, `merchant_id`, name, pickup address and latitude/longitude, `active`. | FK to merchant; coordinate checks; branch belongs to one merchant. |
| `merchant_staff_memberships` / merchant | `id` or composite key, `actor_id`, `merchant_id`/`branch_id`, active scope and permission marker as needed. | Unique active scope; FKs; membership cannot point to an unrelated merchant/branch pair. Prefer deriving merchant through branch unless a merchant-wide scope is required. |
| `drivers` / driver | `id`, unique `actor_id`, active/suspended facts and the selected vehicle relation. | FK to actor; unique actor mapping; initial operational status only if Phase 3 will build on it. |
| `vehicles` / driver | `id`, driver relation, type, capacity, `active`. | Positive capacity, valid type, FK and chosen one-active-vehicle rule. |
| `orders` / order | `id`, `customer_id`, `merchant_branch_id`, `status=CREATED`, timestamps, currency, total, delivery address/point and relevant pickup/branch snapshots. | FKs, non-null snapshots, allowed initial status, coordinate and monetary checks. No client-set status. |
| `order_items` / order | `id`, `order_id`, item reference/description snapshot, quantity, unit amount, line amount or derivation rule. | FK, positive quantity, amount checks, optional unique line position. Delete only through the owning order lifecycle policy; no orphaned items. |

JPA mapping review: each relationship has one owning side; lazy to-one associations should be considered where a read does not need the related row; collections are not returned from controllers. Use narrow cascade for order-owned items only if the same transaction guarantees their lifecycle. Do not cascade deletes from customer/merchant/driver into historical orders. `@Version` may be introduced where Phase 1 profile edits need conflict handling; future lifecycle/assignment writes must revisit versioning in their phases.

Recommended starting indexes match concrete queries: customer plus creation time and ID for own-order pagination; branch plus creation time and ID for branch-order views only if exposed; unique actor/driver/customer mappings; membership lookup by actor and branch. A plain FK does not automatically create the right PostgreSQL child-side index. Measure before adding composite indexes for filters not implemented yet.

## Data and invariant checklist

| Area | Required durable behavior |
| --- | --- |
| Actor and ownership | Unique `(issuer, subject)` mapping; explicit customer/driver and staff-to-branch relations; deactivated records remain for history. Roles alone do not establish branch access. |
| Merchant and branch | A branch belongs to exactly one merchant. Pickup address and coordinates are validated. An order keeps its original branch ID and pickup snapshot even after profile edits. |
| Driver and vehicle | Driver links to an actor; vehicle type/capacity and active state are validated. The v1 active-vehicle rule is explicit. No GPS or heartbeat is stored in PostgreSQL as part of this phase. |
| Order and items | One customer and one branch per order; at least one item; quantity greater than zero; immutable item description/reference and monetary snapshot. Every line uses the order currency, and totals follow the recorded decimal/rounding rule. No client-writable status. |
| Address and time | Delivery address and coordinates are copied into the order. Latitude is within `[-90, 90]`, longitude within `[-180, 180]`, both finite. UTC timestamps are consistent in database and API. |
| Database | FKs, uniqueness, non-null and check constraints backstop validation. Indexes correspond to the chosen scoped list/filter/sort queries; inspect a representative `EXPLAIN (ANALYZE, BUFFERS)` result before claiming benefit. |

The [state plan](../architecture/STATE_MACHINES.md) describes later transitions. Phase 1 may store `CREATED` and a creation timestamp, but transition commands and durable status history are Phase 3 work unless a concrete Phase 1 intake requirement makes a creation record necessary. A mutable customer, branch or item profile must not rewrite an existing order's snapshots.

## API and query contract to settle during implementation

The following is the proposed route shape. Keep only onboarding routes actually needed for the demonstration and tests, and document the final contract once implemented. Every row is local/test-only in Phase 1.

| Route | Purpose | Minimum response/constraint |
| --- | --- | --- |
| `POST /api/v1/actors` and `GET /api/v1/actors/{id}` | Local fixture identity record; unique external subject. | `201`/`200`; never return secrets or a password. |
| `POST /api/v1/customers` and `GET /api/v1/customers/{id}` | Onboard and inspect a customer profile. | Requires an existing actor; duplicate mapping is `409`. |
| `POST /api/v1/merchants` and `GET /api/v1/merchants/{id}` | Onboard and inspect a merchant. | Active flag has a server default; no delete that erases order history. |
| `POST /api/v1/merchant-branches` and `GET /api/v1/merchant-branches/{id}` | Create/read a pickup branch. | Requires merchant and valid address/coordinates. |
| Local membership fixture operation | Establish staff-to-branch scope for Phase 2 tests. | It may be a narrow endpoint or test/local seed; avoid broad membership CRUD. |
| `POST /api/v1/drivers` and `POST /api/v1/vehicles` | Establish driver and vehicle data. | No online/location/availability command. Add reads only if a real flow needs them. |
| `POST /api/v1/orders` | Create one order with items and snapshots. | `201 Created`, `Location: /api/v1/orders/{id}`, response DTO with server-generated ID/status/time/total. |
| `GET /api/v1/orders/{id}` | Read one order. | Include line snapshots; a fixture actor outside scope gets a consistently chosen 404/403 policy, documented as nonsecurity behavior here. |
| `GET /api/v1/orders` | Read a bounded, scoped page. | Include stable sort and page metadata; avoid loading every item for all matching orders. |

An order request should carry a branch ID, delivery address/point and nonempty item list with positive quantities. Prefer deriving the customer from the local fixture actor rather than accepting an arbitrary owner ID in the request. The server sets `id`, `status`, `createdAt`, currency/total policy and snapshots. If a local-only price amount is submitted, label it untrusted and validate its decimal limits; public intake later needs an authoritative price source. Keep entity IDs and internal mapping details out of unrelated response DTOs.

### List behavior and response rules

- Define the fixture actor source and apply the customer/branch scope in the repository query, not by filtering a full unscoped page in memory. This exercises object-scoped query design but is **not** authorization; Phase 2 replaces the fixture with a verified principal and policy checks.
- Start with filters actually exercised by the demo, such as status, branch and creation interval. Status can return `CREATED` only in this phase. Validate `from <= to`, document inclusive/exclusive time boundaries, reject unknown filter fields, and never allow a client-selected customer to widen the fixture scope.
- Use zero-based pagination, a documented default and maximum page size, and an allowlist such as `createdAt` and `id`. Append `id` as a deterministic tie-break when sorting by a nonunique field. Reject negative pages, oversized sizes and unknown sorts with stable `400` codes.
- For one-order detail, fetch precisely the items and snapshots needed. For paged lists, page order IDs or a projection first, then load items for those IDs if the list includes items. Do not page an unbounded collection fetch join. Record SQL count for representative page sizes to catch N+1.
- Never serialize JPA entities directly. Use DTO records where appropriate, explicit mapping, Bean Validation at the edge, and service validation for cross-row rules. Open Session in View is already disabled by the Phase 0 configuration; keep mapping inside an appropriate transaction.

### HTTP and failure semantics

| Situation | Proposed response | Required state guarantee |
| --- | --- | --- |
| Valid create | `201`, `Location`, DTO | One committed order and its complete item set. |
| Invalid body, malformed UUID, unsupported sort/filter | `400` with field or parameter code | No write. |
| Unknown customer, branch or order | `404` | No write or leaked internal error. |
| Inactive merchant/branch, duplicate actor mapping or stale version where used | `409` with a stable domain code | No partial write; choose and document the exact inactive-reference code before implementing. |
| Unique/FK race after application validation | Deterministic `409` or `404` mapping as documented | Database constraint wins; no false success. |
| PostgreSQL unavailable | `503` for an already running app where a response is possible; startup fails visibly if unavailable | No claimed order creation. |

Reuse the Phase 0 `X-Correlation-ID` in error handling. The error body should have a stable `code`, safe message, path, timestamp and correlation ID; no stack trace, SQL text, subject identifier, contact data or raw request body. If validation rejects multiple fields, use a bounded, deterministic field-error list.

## Transaction and package design

The order application service validates the fixture actor/customer link and obtains narrow customer and branch facts, then creates the order aggregate and items in one PostgreSQL transaction. It computes/validates line and order totals before persist and copies every historical field needed for later explanation. A database error after the first insert rolls back the whole command. A controller coordinates HTTP only; it does not assemble JPA graphs or open independent repository transactions. Avoid holding a transaction across external calls; there are none in this phase.

Each owner exposes a small application operation for facts needed by another owner. For example, order intake asks customer for customer identity/active fact and merchant for branch/pickup facts; it does not mutate their entities. The order module owns order writes and mapping. The repository layer expresses stable queries rather than business policy hidden in query names. Where a cross-module read must happen in the order transaction, document the intended consistency and recheck FK/active state before commit if a concurrent deactivation is relevant to the accepted semantics.

Profiles may change after order creation. Test the resulting historical view, not just the foreign keys: an order still points to its original branch/customer and retains the address, pickup and item/price values that were agreed at intake. Do not delete referenced profiles to make a test pass.

## Suggested reviewable slices

These are implementation checkpoints, not permission to mark the phase complete early. Each slice should leave the build green and preserve a working fresh-database path.

| Slice | Concrete output | Review question |
| --- | --- | --- |
| 1. Conventions and migration | Decision note, `V1`, mapped entities, PostgreSQL constraint tests. | Can a new database migrate and reject bad rows without Hibernate altering it? |
| 2. Local fixture onboarding | Minimal create/read fixture flow, DTO validation and profile repository tests. | Are actor/branch/driver relationships valid, and are endpoints absent in deployable profiles? |
| 3. Order create | Request DTO, service transaction, order/items/snapshot persistence, error mapping. | Does an invalid item or concurrent reference failure leave no partial order? |
| 4. Order reads | Detail and scoped paged list, bounded filters/sorts, query evidence. | Do two fixture actors see only their own scoped results, and is the item fetch bounded? |
| 5. Documentation and gate | README examples, ERD update, exact test/CI evidence and limitations. | Can someone reproduce the demo and understand what remains unsafe for public use? |

The first migration may be split into sequential real migrations if that makes the slices independently runnable. Never add an empty migration simply to satisfy numbering, and never edit a migration once applied to a shared database.

## Verification matrix

| Level | Meaningful cases |
| --- | --- |
| Migration/repository on real PostgreSQL | Empty database applies `V1`; Hibernate validates; duplicate actor mapping, missing FK, invalid quantity and uniqueness constraints fail; indexes exist as documented. |
| Service/transaction | Valid intake creates exactly one order with item/address/price snapshots; edits to source profiles do not alter it; inactive branch or unknown customer fails; item-insert failure rolls back the whole order. |
| MVC/API | Required-field and coordinate validation, malformed UUID, creation `Location`, get/list shape, empty page, maximum page size, unsupported sort/filter, correct 400/404/409 responses and safe error body/correlation ID. |
| Query behavior | Representative seeded data proves actor-fixture scope, stable pagination without duplicates, supported filters and no N+1 regression; inspect actual SQL and one PostgreSQL query plan. |
| Deployment boundary | Business routes work only in local/test configuration on loopback/test infrastructure; prod has no anonymous onboarding/order writes; profile/network guard fails closed. |
| CI | `./mvnw.cmd verify --no-transfer-progress` and the same GitHub Actions gate pass with actual test counts recorded. Docker/Testcontainers are available in that gate. |

Use at least two customers under different actors, two branches under different merchants, and multiple orders sharing a `created_at` value in query tests. That fixture exposes accidental unscoped reads and unstable pagination. For the snapshot test, modify a customer or branch profile after intake and, if a mutable item/price fixture exists, modify it too; then compare the stored order response. For rollback, cause a real database constraint failure after the order insert and verify zero committed order/item rows; a mock exception alone does not prove the transaction boundary.

Test coordinate edges `-90`, `90`, `-180`, `180`, values just outside each bound, NaN/infinity at the deserialization or validation layer, missing address parts, empty items, zero/negative quantity, excessive money scale and arithmetic overflow. The exact DTO and database checks should agree. Constraint tests should use SQL/JPA against Testcontainers PostgreSQL, not only Bean Validation.

For query evidence, state the seeded row count, request/filter/sort, observed SQL count, representative SQL, index chosen by `EXPLAIN (ANALYZE, BUFFERS)` and whether the plan changes when the seed is small. A sequential scan on a tiny fixture is not automatically a failure; explain whether the index supports the expected larger scoped query. Do not use a brittle absolute latency assertion in CI.

The deployment-boundary test should start each relevant profile or inspect the actual route mappings: `local` and `test` serve the business routes only under their intended loopback/test configuration; `prod` and an unconfigured/default profile do not expose anonymous business writes. Also exercise a misconfigured local profile bound to a public interface and show that startup or business-route registration fails closed. This gate is separate from, and does not replace, Phase 2 authorization tests.

## Acceptance gate

- A clean PostgreSQL database migrates with the first real versioned migration, and the application starts with Hibernate validation. Migration and constraint failures are visible rather than silently repaired.
- Local/test order create/get/list and minimal onboarding work with documented DTOs and HTTP semantics. The created order keeps its original customer, branch, address, item and price facts after later profile changes.
- Invalid input and missing/inactive references produce stable failures with no partial writes. Bounded list results are scoped to the chosen actor fixture and have deterministic ordering. A measured query count and plan support the selected fetch/index design.
- No Phase 1 business write is exposed in a deployable profile. Documentation explicitly says Phase 2 security is pending and makes no production-readiness claim.
- PostgreSQL integration and MVC tests, CI and local wrapper verification pass. The developer can explain relationship ownership, JPA fetch behavior, one N+1 fix, transaction rollback and the index/query plan.

### Demonstration and evidence to attach

1. Record the exact fresh-database command and `flyway_schema_history` result, plus the Java/Maven/PostgreSQL versions used. A restart against the same database must not reapply `V1`.
2. Show one local fixture onboarding sequence, one successful order `POST` with `Location`, one detail `GET`, and one scoped list `GET` with page metadata. Redact fixture contact data where relevant.
3. Show the invalid-order response and the database rows proving that the failed transaction wrote nothing. Show the before/after source-profile edit and unchanged order snapshot.
4. Record the query count and plan for the list endpoint. State the pagination size and seed size so the evidence is interpretable.
5. Show prod/default route denial and the failed unsafe local bind, the exact Maven verification result, CI URL/status, changed docs, and remaining decisions that block public use.

## Dependencies, non-goals and risks

**Dependencies:** Phase 0 foundation; Java 21, the existing Maven Wrapper, PostgreSQL 17/Flyway and Docker-backed Testcontainers; the v1 choices for IDs, time, money, address and local fixture identity. Do not assume Phase 2's OIDC provider is already chosen.

**Non-goals:** public registration, production authentication/authorization, order transitions beyond creation, assignment/delivery, GPS, Redis, Kafka, PostGIS zones, payment processing, a full product catalog and broad CRUD for every table.

**Risks to review:** a large onboarding surface hiding the learning goal; trusting caller-supplied customer IDs; accidentally deploying anonymous business routes; overwriting historical order values through JPA relationships; eager loads or collection-fetch pagination; weak money rules; indexes added without measured queries. Prefer the smallest implementation that satisfies the acceptance gate.

## Completion evidence template

Record the commit/PR and CI URL; migration names and fresh-database result; exact wrapper command, test counts and exit code; local HTTP examples and prod-route denial; snapshot/rollback/query evidence; the chosen ID/time/money/fixture policies; documentation updated; and unresolved limitations. Use `COMPLETE` only after the gate passes with recorded evidence.

## Execution evidence, 2026-10-03

- **Schema and conventions:** [ADR 0002](../architecture/ADR-0002-phase-1-conventions.md) fixes application-generated UUIDs, UTC `Instant`/`timestamptz`, named constraints, decimal scale 2, WGS84 coordinates and the local fixture boundary. `V1__core_model.sql` creates exactly the nine Phase 1 tables. PostgreSQL Testcontainers applied it on an empty database; Hibernate `ddl-auto=validate` passed; a second Flyway migrate executed zero migrations.
- **Behavior:** Local/test onboarding creates actors, customers, merchants, branches, memberships, drivers and vehicles. An order command derives its customer from a local fixture actor, accepts an active branch, computes line/order totals, stores pickup/delivery/item snapshots in one transaction and returns `201` with `Location`. Detail and paged list reads are scoped in database queries. The integration suite proves another fixture actor receives an empty list and `404` on the order detail. Source customer/branch profile edits leave the order snapshot unchanged. A real `ck_order_items_quantity` violation after order insertion rolled the transaction back to its prior order count. A separate database-triggered item failure through HTTP returned a safe `409` and also left the order count unchanged.
- **Constraints and HTTP:** PostgreSQL tests exercise external-subject and profile/vehicle uniqueness, foreign keys, coordinate, quantity and money checks. MVC tests cover successful reads/writes, malformed input, missing references, inactive branches, currency/quantity/coordinate/money bounds, pagination/filter/sort errors, safe conflict output and correlation IDs.
- **Query evidence:** With five orders for one customer sharing the same creation time and a page size of two, the first list request prepared four SQL statements, including fixture actor/customer lookup, order page and count. A representative `EXPLAIN (ANALYZE, BUFFERS)` for the scoped page selected `Index Only Scan using idx_orders_customer_created_id`; the plan reported two returned rows, two heap fetches and two shared-buffer hits. Query/plan output is in the `Phase1ApiTests` Surefire report, with UUIDs redacted. The test also verifies distinct IDs and deterministic order across two pages. This tiny fixture is evidence of query shape, not a latency or large-scale performance claim.
- **Profile boundary:** Mock MVC and route-mapping tests show local/test endpoints present in their intended profiles, no `/api/v1` business routes in default/prod, and failed startup for a local profile on `0.0.0.0` or mixed local+prod profiles. The `X-Dev-Actor-Id` fixture selector is explicitly spoofable and never production identity.
- **Local verification:** `./mvnw.cmd verify --no-transfer-progress` passed with 23 tests, 0 failures, 0 errors, 0 skipped on Java 21.0.12 with cached `postgres:17.11-alpine3.24` and Docker Engine 29.6.2. README and the domain model now identify the implemented slice and its limits.
- **Live loopback smoke:** With the existing healthy PostgreSQL Compose service and `./scripts/Run-Local.ps1`, the app started on `127.0.0.1:8080` and migrated the local database. HTTP onboarding created an actor, customer, merchant and branch; order `POST` returned `201` and `Location`, followed by detail `GET` with `CREATED`, total `25.00` and one item, and a scoped list reporting one order. The app was stopped afterward; the local PostgreSQL volume was preserved.
- **Delivery:** Work is on branch `phase-1-core-model` in [PR #1](https://github.com/Omar-Mega-Byte/fleetflow/pull/1). Implementation commits `6d69195` and `2d1294d` have green GitHub Actions `verify` runs: [push run](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37138473924) and [PR run](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/37138476077), plus a passing GitGuardian check. The PR's current head checks remain the final merge gate; this document records the implementation gate, not a merged-main claim.
- **Remaining limitations:** Pricing is untrusted submitted learning data. Phase 2 must add verified identity and object authorization, and a trusted merchant price policy is required before public intake. No fulfillment or dispatch actions exist in Phase 1.
