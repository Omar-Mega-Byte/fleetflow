# FleetFlow execution roadmap

[PROJECT_CONTEXT.md](../PROJECT_CONTEXT.md) is the primary living context. This roadmap tracks implementation status; the later phases remain plans. Status vocabulary: `NOT_STARTED` means planned only, `IN_PROGRESS` means implementation under way, `BLOCKED` means an identified prerequisite prevents completion, and `COMPLETE` means the phase acceptance gate passed with recorded evidence. As of 2026-10-03 Phase 0 is in progress; later phases are `NOT_STARTED`.

| Phase | Status | Purpose |
| --- | --- | --- |
| [0](phases/PHASE_0.md) Foundation | IN_PROGRESS | Reproducible Spring, PostgreSQL, Flyway and CI baseline |
| 1 Core model | NOT_STARTED | Durable actors, branches, drivers and order intake with REST/JPA fundamentals |
| 2 Security | NOT_STARTED | OIDC authentication and object-level authorization before exposed business actions |
| 3 Lifecycles | NOT_STARTED | Explicit order/driver/delivery transitions and durable history |
| 4 Live location | NOT_STARTED | Redis heartbeat, latest GPS, GEO and stale-driver exclusion |
| 5 Matching | NOT_STARTED | Deterministic candidate eligibility and ranking, without assignment offers |
| 6 Assignment | NOT_STARTED | Atomic reservation, offer, acceptance and 100-contender proof |
| 7 Recovery | NOT_STARTED | Rejection, timeout, reassignment, cancellation and delivery incidents |
| 8 Async events | NOT_STARTED | Kafka, transactional outbox, idempotent notification and analytics |
| 9 Live tracking | NOT_STARTED | Authorized SSE tracking and driver simulator |
| 10 Zones | NOT_STARTED | PostGIS serviceability and geofencing after basic dispatch |
| 11 Hardening | NOT_STARTED | Operations, resilience, backup/restore, security and demo proof |

## Why this order differs from the source outline

Phase 2 security precedes business state-changing endpoints; Phase 1 is a loopback/test-only learning slice until protected. Phase 5 stops at ranking, because offering a driver before atomic reservation would create a knowingly unsafe system. Phase 6 introduces reservation and offers together. CI and basic health move into Phase 0; Phase 11 expands them rather than starting them. Durable audit starts with lifecycle and operational transitions and grows as overrides appear. The first live tracking stream need not wait on Kafka: Phase 8 and 9 can proceed independently once Phase 7 is stable. PostGIS zones follow functional dispatch so spatial complexity answers a real serviceability question.

```text
0 → 1 → 2 → 3 → 4 → 5 → 6 → 7
                              ├→ 8 (async notifications/analytics) ─┐
                              ├→ 9 (SSE/tracking) ───────────────────┼→ 11
                              └→ 10 (PostGIS zones; also needs 4/5) ┘
```

Every phase requires a reviewable slice: behavior and negative cases verified, relevant migration and authorization tests passing, CI green, docs accurately updated, no committed secrets, and the developer able to explain the designated learning topics. Do not mark a phase complete based only on compilation or plans. Keep actual verification evidence with each phase completion note.

## Phase 1 — Core model, persistence and REST

- **Objective:** Make durable order intake and actor/branch/driver data a small, correct Spring/JPA slice.
- **Business scope:** Admin-managed customer, merchant, branch, driver and vehicle records; create/read/list orders and items. No fulfillment action yet.
- **Engineering scope:** DTO validation/mapping, service/repository separation, pagination/filter/sort allowlist, transaction boundaries, N+1 review and useful query plans.
- **Java/Spring concepts learned:** MVC request path, DI, JPA ownership/fetch plans, SQL joins/indexes, transactions, records versus entities. Developer explains a query plan and one N+1 fix.
- **Domain changes:** User/actor mapping shell, Customer, Merchant, MerchantBranch, MerchantStaffMembership, Driver, Vehicle, Order, OrderItem, immutable address/price snapshots. Initial order state `CREATED`.
- **Database changes:** First Flyway versioned migrations for these tables, FKs, uniqueness, non-null/check constraints and indexes matching list queries; UTC timestamps and UUID policy. No Flyway baseline on an empty DB.
- **API changes:** `/api/v1/orders` create/get/list and narrowly scoped admin onboarding endpoints as needed; no status setters. Until Phase 2, bind these learning endpoints to local/test only and prohibit public deployment.
- **Infrastructure changes:** PostgreSQL Testcontainers; no Redis/Kafka.
- **Security work:** Document ownership fields and deny-by-default deployment posture; do not invent a fake trusted user header for production.
- **Testing requirements:** Repository constraint/query tests, DTO validation and HTTP semantics tests, fresh migration test, pagination/filter and rollback proof.
- **Failure cases:** Bad order/branch/customer references, invalid quantity/coordinates, duplicate actor mapping, DB conflict and inaccessible DB.
- **Acceptance criteria:** Fresh database migrates; order snapshot survives profile edits; list queries are bounded and scoped by explicit actor fixture; all write endpoints are unavailable outside local/test; CI passes.
- **Dependencies:** Phase 0, decision on ID/money/clock/schema conventions.
- **Explicit non-goals:** Dispatch, location, auth claims of production readiness, state transition endpoints.
- **Risks:** Large CRUD surface, entities leaking through API, early public exposure, `CascadeType.ALL`, `EAGER`, and speculative indexes.
- **Deliverables:** Migrations, minimal domain/API code and meaningful tests, ERD update, endpoint contract and query evidence.

## Phase 2 — Authentication and authorization

- **Objective:** Make subsequent business commands safe for real actors.
- **Business scope:** Customer own-order access, merchant staff branch access, driver self access, narrow dispatcher/support/admin policies.
- **Engineering scope:** Spring Security resource server validating issuer/signature/audience/expiry, subject-to-local-actor mapping, authorization service and consistent 401/403/404 policy.
- **Java/Spring concepts learned:** Security filter chain, principal versus authorities, method versus object authorization and JWT/OIDC trust. Developer explains a cross-tenant denial trace.
- **Domain changes:** Actor-role and staff-branch memberships; active/suspended actor rule.
- **Database changes:** Mapping/role/membership constraints and indexes if Phase 1 did not already create them.
- **API changes:** Protect all existing business endpoints; `/me` only if useful; no public registration by default.
- **Infrastructure changes:** Local/test OIDC issuer and CI fixture, not a custom token issuer in the application.
- **Security work:** Endpoint matrix and object-level checks, least privilege for support, no local identity override in prod.
- **Testing requirements:** Signed token and invalid/expired token tests, missing/extra role tests, customer/merchant/driver isolation, disabled actor tests, coverage of every endpoint class.
- **Failure cases:** Unknown issuer/subject, stale membership, another user's order, inactive driver, missing token.
- **Acceptance criteria:** 401/403/404 semantics documented; no anonymous business mutation; cross-tenant reads/writes denied with integration evidence; local issuer cannot bypass prod policy.
- **Dependencies:** Phase 1 actor ownership fields; choose OIDC provider and claim mapping.
- **Explicit non-goals:** Password storage, public registration, dispatch permissions for ordinary drivers.
- **Risks:** Role-only checks, trusting unverified JWT claims, test shortcuts leaking to production.
- **Deliverables:** Security configuration, policy matrix, authorization tests and local identity instructions.

## Phase 3 — Explicit lifecycles

- **Objective:** Turn order and driver data into guarded business workflows.
- **Business scope:** Confirm, prepare, ready, eligible cancellation; driver online/available/pause/offline; delivery lifecycle skeleton and status history.
- **Engineering scope:** Domain transition methods, application transaction coordinator, clock abstraction, stable conflict errors and audited changes.
- **Java/Spring concepts learned:** Invariants, enums, service/domain split, optimistic versioning and rollback. Developer explains why arbitrary status setters are unsafe.
- **Domain changes:** Order/Driver/Delivery state machines and histories per [state plan](architecture/STATE_MACHINES.md); no assignment acceptance yet.
- **Database changes:** Status/history tables, version fields, transition timestamps, relevant checks/indexes.
- **API changes:** Explicit order action endpoints and driver self-state endpoints; bounded order reads.
- **Infrastructure changes:** PostgreSQL only.
- **Security work:** Customer cancellation cutoff, merchant branch membership, driver self action, privileged override with reason.
- **Testing requirements:** Exhaustive valid/invalid transition unit tests, transactional history tests, authorization and cancellation race tests.
- **Failure cases:** Duplicate action, skipped transition, unauthorized merchant, cancellation during preparation, suspended driver command.
- **Acceptance criteria:** Every transition has actor, guard and history; invalid command leaves no partial write; terminal states cannot regress; security tests pass.
- **Dependencies:** Phase 2 security and Phase 1 data model.
- **Explicit non-goals:** Auto dispatch, Redis, assignment offers.
- **Risks:** Two independently mutable order/delivery statuses; too many states without use; huge switch statements.
- **Deliverables:** State machine code/tests, migration, transition/API matrix and updated docs.

## Phase 4 — Driver location and Redis GEO

- **Objective:** Introduce operational liveness and spatial candidate discovery.
- **Business scope:** Driver self location/heartbeat; nearby available-driver lookup for dispatch internals.
- **Engineering scope:** Validated coordinate/time model, Redis latest-location TTL, heartbeat TTL, GEO membership and stale-member filtering; explicit outage behavior.
- **Java/Spring concepts learned:** Redis keys/TTL, high-frequency writes, cache versus truth, GEO search. Developer explains why expired heartbeat must filter a GEO hit.
- **Domain changes:** Ephemeral DriverLocation and liveness policy; durable availability remains in Driver.
- **Database changes:** None unless a durable driver status field/index is missing; no GPS history table.
- **API changes:** `POST /api/v1/drivers/me/location` and heartbeat if separate; no public nearby-driver endpoint.
- **Infrastructure changes:** Add Redis to local Compose and Redis Testcontainers tests.
- **Security work:** Driver may update only own location; rate and payload limits; no location access for unrelated customers.
- **Testing requirements:** TTL expiry, out-of-order timestamps, malformed/nonfinite coordinates, stale GEO member, paused driver and Redis outage integration tests.
- **Failure cases:** No heartbeat, stale GPS, invalid coordinates, excessive update rate, Redis down.
- **Acceptance criteria:** Stale/paused drivers never emerge as eligible; a Redis outage blocks new location-based decisions without corrupting DB state; CI runs real Redis tests.
- **Dependencies:** Phase 3 driver states and Phase 2 identity.
- **Explicit non-goals:** Reservation, persisted GPS trail, PostGIS zones, Kafka GPS events.
- **Risks:** Treating GEO set as auto-expiring, using Redis to decide durable availability, sensitive location logging.
- **Deliverables:** Redis adapter/config, API, tests, key/TTL documentation and failure metrics.

## Phase 5 — Candidate eligibility and matching

- **Objective:** Rank explainable dispatch candidates without allocating a driver.
- **Business scope:** Ready order can start dispatch; candidate list, eligibility reasons and deterministic score; no offer yet.
- **Engineering scope:** Redis GEO prefilter, DB eligibility recheck, configurable normalized factors, stable tie-break and basic ETA; record dispatch start/attempt explanations.
- **Java/Spring concepts learned:** Pure scoring functions, strategy boundary only if needed, configuration validation and deterministic tests. Developer explains hard filter versus weighted score.
- **Domain changes:** DispatchAttempt/score snapshot, `DISPATCHING` and `DISPATCH_FAILED` policy draft.
- **Database changes:** Dispatch attempt table and indexes on order/sequence; no active assignment yet.
- **API changes:** Dispatcher read-only candidate/attempt view or internal service; ready-order dispatch action guarded by role.
- **Infrastructure changes:** Existing Postgres/Redis only.
- **Security work:** Only dispatcher/worker sees candidate detail; customer sees coarse order status.
- **Testing requirements:** Scoring, tie-break, stale exclusion, vehicle/zone constraints where available, no-candidate and query tests.
- **Failure cases:** Empty candidate pool, vanished heartbeat, malformed configuration, Redis outage.
- **Acceptance criteria:** Repeated same inputs yield same rank and reason; ineligible drivers score nowhere; no driver is offered or marked reserved in this phase.
- **Dependencies:** Phases 3 and 4.
- **Explicit non-goals:** Assignment offer, auto retries, PostGIS, external route service.
- **Risks:** Scoring before hard eligibility, invented ratings/acceptance data, nearest-driver-only algorithm.
- **Deliverables:** Ranking logic, attempt history, configuration/test evidence and documented factor defaults.

## Phase 6 — Atomic assignment and acceptance

- **Objective:** Make the first actual driver offer concurrency safe.
- **Business scope:** Reserve one driver, create offer, let exact driver accept or reject; order becomes assigned only on acceptance.
- **Engineering scope:** Conditional PostgreSQL status update/lock, same-transaction offer insert, uniqueness for active driver/order, deadline and rollback; recheck eligibility after ranking.
- **Java/Spring concepts learned:** Race windows, affected-row semantics, unique indexes, transaction isolation and lock scope. Developer explains the 100-contender result.
- **Domain changes:** DriverAssignment state machine, Driver `RESERVED/DELIVERING`, Delivery `ASSIGNED`, Order `DRIVER_ASSIGNED`.
- **Database changes:** Assignments table, active-offer/assignment partial unique indexes, deadline/status indexes and version fields as needed.
- **API changes:** Offered-driver `GET`/accept/reject actions; dispatcher assignment read view.
- **Infrastructure changes:** PostgreSQL concurrency test harness; no distributed lock service.
- **Security work:** Exact offer owner, expiry and order/driver state checks in command transaction.
- **Testing requirements:** PostgreSQL Testcontainers 100 simultaneous attempts for one driver: one committed winner, 99 safe losers, one active offer, valid states; accept/accept, accept/reject, wrong driver and rollback tests.
- **Failure cases:** Two orders race, duplicate accept, late accept, driver loses eligibility, DB conflict.
- **Acceptance criteria:** Database constraints backstop application checks; all cross-machine tuples consistent; losing callers never see false success; repeated race suite and CI pass.
- **Dependencies:** Phase 5 matching; final reservation and duplicate-command policy.
- **Explicit non-goals:** Redis distributed lock, automated timeout worker, Kafka notifications.
- **Risks:** Offer and reservation in different transactions, blocking on Redis under DB lock, relying only on Java synchronization.
- **Deliverables:** Reservation/offer flow, migration, concurrency and security proof, transaction diagram.

## Phase 7 — Timeouts, reassignment and delivery incidents

- **Objective:** Recover correctly when offers fail or deliveries encounter trouble.
- **Business scope:** Scheduled offer expiry, rejection/timeout next candidate, bounded no-driver retry, pre-pickup reassignment, order cancellation with active offer, pickup/transit/completion/failure.
- **Engineering scope:** Database-backed scheduled worker with claim/locking, idempotent transitions, backoff, incident history and operator visibility. No Redis TTL as authoritative timeout clock.
- **Java/Spring concepts learned:** Scheduling, idempotent jobs, deadline races and failure-state modeling. Developer explains accept-versus-timeout serialization.
- **Domain changes:** Assignment terminal paths, Delivery outcome/reason, order `DISPATCH_FAILED`/`DELIVERY_FAILED`, audit of manual override.
- **Database changes:** Deadline/worker indexes, attempt outcomes, delivery failure metadata, audit records.
- **API changes:** Driver pickup/transit/complete/fail actions; dispatcher retry/override/incident reads.
- **Infrastructure changes:** Existing PostgreSQL scheduler; no delayed queue.
- **Security work:** Assigned-driver proof, dispatcher override reason, customer/merchant cancellation cutoff.
- **Testing requirements:** Two-worker timeout claim, accept-versus-timeout race, rejection then next candidate, candidate exhaustion, cancellation race, failed delivery and history preservation.
- **Failure cases:** Driver disconnect, timeout, reject, merchant/customer cancel, no candidate, vehicle failure.
- **Acceptance criteria:** One terminal offer outcome, no leaked reservation, bounded retries, complete attempt history, no automatic reassignment after pickup, operator sees unresolved cases.
- **Dependencies:** Phase 6 atomic assignment.
- **Explicit non-goals:** Kafka as scheduler, post-pickup auto redelivery, global route optimization.
- **Risks:** Lost job after crash, infinite retry loop, accidental driver release after accepted delivery.
- **Deliverables:** Worker, incident/API flow, migrations, race/failure tests and runbook note.

## Phase 8 — Asynchronous events, outbox and idempotency

- **Objective:** Decouple notification and analytics from committed business commands.
- **Business scope:** In-app notification for meaningful lifecycle changes; small derived analytics view.
- **Engineering scope:** Versioned event envelope, transactional outbox, polling publisher, Kafka topics/consumers, processed-event marker and retry/backlog policy.
- **Java/Spring concepts learned:** At-least-once delivery, partitions, eventual consistency, transactional outbox, consumer idempotency. Developer explains the DB-commit/Kafka-failure case.
- **Domain changes:** OutboxEvent, ProcessedEvent, Notification, minimal AnalyticsAggregate.
- **Database changes:** Outbox, processed event uniqueness, notification/aggregate tables and polling indexes.
- **API changes:** Authorized notification/report read endpoints if useful; no Kafka-facing business API.
- **Infrastructure changes:** Add Kafka to Compose and Testcontainers; metrics for backlog/lag.
- **Security work:** Notification recipient ownership, safe event payloads, consumer least privilege.
- **Testing requirements:** Commit creates outbox, publish failure/retry, duplicate delivery without duplicate effect, consumer crash/restart and ordering assumptions.
- **Failure cases:** Kafka down, outbox publish failure, duplicate/poison event, notification provider down.
- **Acceptance criteria:** Business transaction remains correct during broker outage; outbox drains after recovery; duplicate event has one business effect; CI covers real broker tests.
- **Dependencies:** Phase 7 stable synchronous events.
- **Explicit non-goals:** GPS through Kafka, full CQRS, schema registry, Debezium, exactly-once marketing claims.
- **Risks:** Direct DB/Kafka dual write, unbounded outbox, non-atomic dedupe, sensitive payloads.
- **Deliverables:** Event contract, publisher/consumers, migrations, integration proof and failure runbook.

## Phase 9 — Live tracking and simulator

- **Objective:** Let an authorized customer observe a live active delivery.
- **Business scope:** Tracking snapshot and SSE stream; driver simulator with location, movement and disconnect scenarios.
- **Engineering scope:** Snapshot then stream, authorization per connection/reconnect, bounded buffers, heartbeats, cleanup and delivery state updates; direct location-to-tracking path is sufficient.
- **Java/Spring concepts learned:** SSE lifecycle, thread/resource limits, reconnect and stream security. Developer explains why a disconnect does not change an order.
- **Domain changes:** Ephemeral TrackingSession; no GPS history assumption.
- **Database changes:** None unless an explicit delivery tracking access record is justified.
- **API changes:** `GET /api/v1/orders/{id}/tracking` and `/stream`.
- **Infrastructure changes:** Reuse Redis; simulator local-only and isolated from production credentials.
- **Security work:** Customer order ownership, assigned driver/dispatcher policy as needed, no public coordinates.
- **Testing requirements:** Stream ownership, snapshot/live sequence, reconnect, slow/disconnected client, stale location and terminal stream close.
- **Failure cases:** SSE disconnect, Redis outage, expired token, simulator disconnect.
- **Acceptance criteria:** Another customer cannot subscribe; reconnect gets current truth; resources are released; demo follows driver movement without persisting every point.
- **Dependencies:** Phase 7 and 4; independent of Phase 8 event transport.
- **Explicit non-goals:** WebSocket, public tracking links, long-term GPS archive.
- **Risks:** Unbounded per-client queues, treating SSE as durable delivery, location privacy leak.
- **Deliverables:** Snapshot/stream endpoints, simulator, tests and demo instructions.

## Phase 10 — PostGIS zones and geofencing

- **Objective:** Enforce durable branch service areas and zone-aware eligibility.
- **Business scope:** DeliveryZone management, destination serviceability and zone filter for dispatch.
- **Engineering scope:** PostGIS polygon/multipolygon storage, SRID 4326, spatial index, boundary/tolerance policy and query plan review.
- **Java/Spring concepts learned:** Spatial SQL, geometry validity, Redis GEO versus PostGIS, indexing and GPS precision. Developer explains a boundary-point test.
- **Domain changes:** DeliveryZone, branch-zone relation and serviceability rule.
- **Database changes:** PostGIS extension/migration, zone tables, geometry constraints and GiST index.
- **API changes:** Restricted zone management and serviceability check; order create rejects unsupported destination.
- **Infrastructure changes:** Compatible PostGIS-enabled PostgreSQL image, only now; preserve migration/restore path.
- **Security work:** Only authorized merchant/admin may manage own zones; no cross-merchant zone access.
- **Testing requirements:** Real PostGIS inside Testcontainers, inside/outside/boundary/invalid polygon, overlap and index/query tests.
- **Failure cases:** Unsupported destination, invalid polygon/SRID, ambiguous overlap, precision edge.
- **Acceptance criteria:** New orders honor durable zones; dispatch respects same rule; old data migration and restore verified; location GEO remains operational search.
- **Dependencies:** Phases 4, 5 and 7; final branch serviceability policy.
- **Explicit non-goals:** Route optimization, arbitrary global geofencing, paid maps.
- **Risks:** Coordinate order/SRID errors, geometry boundary surprises, changing DB image without migration proof.
- **Deliverables:** Zone schema/API, spatial tests, policy and query evidence.

## Phase 11 — Production hardening and demo proof

- **Objective:** Make the completed slice operable, secure and accurately presentable.
- **Business scope:** End-to-end demo with success, rejection, timeout, stale-driver exclusion, concurrency and failed delivery.
- **Engineering scope:** Metrics/dashboards, structured logs, bounded rate limits, optional routing adapter with timeout/fallback, backup/restore, load and failure exercises, security review, release documentation.
- **Java/Spring concepts learned:** Actuator/Micrometer, production debugging, resilience, configuration/secrets and operational evidence. Developer explains one real failure trace end to end.
- **Domain changes:** Only corrections justified by exercised workflows.
- **Database changes:** Performance/retention migrations or indexes justified by measured queries; backup and restore validation.
- **API changes:** Final OpenAPI and stable error contract review; no new feature family by default.
- **Infrastructure changes:** Prometheus/Grafana, production-oriented Compose/deployment only if useful, expanded CI checks and smoke tests.
- **Security work:** Full endpoint matrix, token/secret review, rate-limit policy, Actuator exposure and location/privacy review.
- **Testing requirements:** End-to-end scenario, recovery/restore, load under stated limits, broker/Redis/provider outage drills, security and migration checks.
- **Failure cases:** Backlog growth, partial infrastructure outage, slow provider, DB restore, lost SSE client, rate limit pressure.
- **Acceptance criteria:** Dashboards match exercised metrics; backup restores independently; CI and smoke checks pass; README claims only verified features and records known limits; demo scenarios run from clean documented setup.
- **Dependencies:** Phases 8, 9 and 10 plus completion of all previous gates.
- **Explicit non-goals:** Kubernetes, multi-region, microservice split, ML matching or invented hyperscale targets.
- **Risks:** Treating dashboards or green tests as production proof, adding infrastructure with no operator question, unbounded scope.
- **Deliverables:** Runbooks, dashboards, release/demo guide, verified backup/recovery record and final architecture notes.
