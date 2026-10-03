# FleetFlow architecture plan

Planning only. [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) is the living product context; [ROADMAP.md](../ROADMAP.md) records execution order. This document records recommendations, not implemented behavior. Decisions should be revisited when their phase starts.

## Product boundary and primary flow

FleetFlow is a delivery orchestration backend for customers, merchant staff, drivers, dispatchers, support, and administrators. The demonstration flow is order intake → merchant preparation → nearby driver discovery → eligibility and ranking → exclusive offer → acceptance → pickup and delivery → customer tracking. Rejection, timeout, cancellation, stale location, and delivery failure are first-class paths. Merchant branches, rather than merchant accounts alone, own pickup locations and orders. A driver's identity, durable operational status, and assignment history are separate from current GPS and liveness.

The initial runtime is one Spring Boot process with package-based modules and one PostgreSQL database. Calls across modules go through narrow application interfaces, not another module's repositories or entities. PostgreSQL owns durable business truth. Redis is introduced when high-frequency location and nearby-driver search exist. Kafka is introduced only when notification and analytics work can be decoupled from committed business transactions. PostGIS comes later for durable zone rules. A routing provider is an optional adapter, never required for basic dispatch. No frontend, mobile app, microservices, Kubernetes, ML dispatch, or full GPS history is in scope.

## Module ownership

These are logical boundaries; only active packages need exist. The public interface names below describe responsibilities, not Java signatures.

| Module | Owns / public application interface | Depends on | Must not own |
| --- | --- | --- | --- |
| identity | External subject to local actor mapping, roles and policy lookup; `resolveActor`, `authorize` | customer, merchant, driver identifiers | Orders, passwords, issuer internals |
| customer | Customer profile; `getCustomer`, profile commands | identity | Order state or dispatch |
| merchant | Merchant, branch, branch staff membership; `getBranch`, `authorizeBranchStaff` | identity | Customer order lifecycle |
| order | Order, items, price snapshot, lifecycle and status history; `createOrder`, `advanceOrder`, `getAuthorizedOrder` | customer, merchant, delivery and dispatch through ports | Driver reservation or live GPS |
| driver | Driver, vehicle, durable status, status history; `getEligibleDriver`, `reserveDriver`, `releaseDriver` | identity | Assignment offers or Redis as truth |
| dispatch | Dispatch attempt, assignment offer, candidate scoring and retry orchestration; `startDispatch`, `respondToOffer`, `reassign` | order, driver, merchant, later Redis location lookup | Order preparation or delivery completion |
| delivery | Delivery, pickup, transit, outcome; `recordPickup`, `recordOutcome` | order, driver, dispatch | Candidate ranking |
| tracking | Authorized live location view and stream; `trackingSnapshot`, `subscribe` | order, delivery, driver location | Durable order or assignment state |
| notification | Delivery of assignment/customer messages and provider receipt; `queueNotification` | dispatch/order events | Business state transitions |
| analytics | Derived operational aggregates; `recordEvent`, reports | committed events | Transactional truth |
| audit | Durable privileged-action and transition evidence; `recordAction`, audit query | actor context from identity | Generic application logs |
| shared | Only genuinely shared primitives: clock, ID/coordinate value objects, error contract | none | A generic `common`/`utils` service layer |

Avoid bidirectional module dependencies. If order and dispatch appear to need each other's repositories, define an application port and pass IDs/snapshots. Keep the first codebase a single Maven module; package rules can be checked with architecture tests once packages exist. Cross-module database foreign keys are permitted, but ownership of writes remains singular.

## Prerequisites and decisions

The recommendations below are defaults for planning. Phase 0 must confirm available JDK, Docker and CI runner capacity and pin exact patch and image digests at implementation time. The version check for this plan used the [Spring Boot supported releases](https://spring.io/projects/spring-boot), [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html), [PostgreSQL release policy](https://www.postgresql.org/support/versioning/), and [PostGIS compatibility](https://www.postgis.net/development/compatibility/).

| Decision | Recommended option and reason | Alternative and tradeoff | Final by |
| --- | --- | --- | --- |
| Runtime architecture | One package-modular monolith; in-process transactions simplify core invariants | Microservices add network, tracing and distributed consistency before a need exists | 0 |
| Java/build | Java 21 LTS, Maven Wrapper; familiar ecosystem and learning path | Java 25 LTS has newer language/runtime but less value for this domain | 0 |
| Spring Boot | Supported 4.1.x stable line, pin patch in Phase 0; Java 21 is supported | 4.0.x is also supported; 3.5.x reached its last OSS release, so avoid for a new project | 0 |
| PostgreSQL | PostgreSQL 17, pin patch/image; mature, supported, PostGIS-compatible | 18 is supported and newer, with less benefit to this project | 0 |
| Package/API | `com.fleetflow` and `/api/v1`; one artifact, explicit request/response DTOs | Multi-module Maven adds ceremony; unversioned API complicates future evolution | 0 |
| IDs/times | UUID primary keys, generated by application or DB consistently; UTC instants, database timestamps for audit, explicit time zone at API | Bigint is smaller/faster but easier to enumerate and harder to generate outside DB; UUID costs larger indexes | 1 |
| Schema/migrations | Lower snake case, explicit constraints/indexes, Flyway forward migrations, Hibernate validate in deployed profiles | Automatic schema mutation is fast locally but makes migration proof unreliable | 0–1 |
| Enum persistence | String codes with validated transitions; renames require migration plan | Ordinals are compact but unsafe when enum order changes | 1 |
| DTO mapping | Explicit mapping at first; add mapper generation only when repetition is real | Reflection mapper hides fields and may mask ownership leaks | 1 |
| State machine | Explicit domain methods and transition tables/tests; no framework initially | State-machine library adds configuration before behavior is stable | 3 |
| Authentication | External OIDC issuer, Spring resource-server JWT validation; local issuer for development, subject-to-actor mapping in DB | Self-issued JWT/password store enlarges security scope; fixed dev identity risks leaks | 2 |
| Authorization | Role plus object ownership at application query/command boundary; staff-branch membership, driver self/offer ownership; deny by default | Controller-only role checks miss object access | 2 |
| Audit | Transactional status/attempt history plus separate append-only privileged action log; actor/reason/correlation ID | Logs alone can be lost/edited and are not business evidence | 3, expanded 7 |
| Reservation | PostgreSQL conditional update or row lock, assignment creation in same transaction, partial unique indexes for active offers/assignments | Redis lock adds failure modes and cannot protect DB without a second protocol | 6 |
| Redis/GEO | Latest location + heartbeat TTL and GEO candidate prefilter; verify liveness and durable eligibility before offer | PostgreSQL-only location is simpler but poor for frequent location writes; GEO cannot enforce assignment uniqueness | 4 |
| SSE | SSE for one-way customer updates, snapshot plus live stream and ownership check per connection | WebSocket enables two-way messaging but raises session complexity | 9 |
| Geospatial | WGS84 latitude/longitude validated at edges; Redis GEO for proximity; PostGIS polygons/branch serviceability in phase 10 | PostGIS-only live search increases DB write pressure; Redis-only zones cannot be durable authoritative rules | 4/10 |
| Events | Versioned event envelope with stable event ID, aggregate ID, occurred time and payload; start with meaningful notification/analytics events | Publishing every status/GPS change creates noise and coupling | 8 |
| Kafka/outbox | Polling transactional outbox + idempotent consumers when async side effects appear; at-least-once delivery | Direct publish has DB/Kafka dual-write gap; exactly-once claims exceed the design | 8 |
| Route estimates | First use an explainable straight-line estimate; provider port only when external route calls are implemented | A paid routing service early adds cost and outages | 5/11 |

### Open decisions with working defaults

| Question | Default | Decide by / what could change it |
| --- | --- | --- |
| Which OIDC provider and local issuer? | Standards-compliant OIDC test issuer locally; no proprietary claims in domain | Phase 2; existing organizational identity or CI constraints |
| Who can create and administer merchant/driver accounts? | Admin-controlled onboarding in early phases; no public registration | Phase 1–2; real demo user flow |
| Can customers cancel after merchant confirmation? | Before preparation only; later requires dispatcher intervention | Phase 3; business/refund policy |
| What happens if no driver is found? | Keep order `DISPATCHING` with explicit retry window, then dispatch failure for operator action | Phase 5–7; service-level target |
| Is driver `ONLINE` a separate durable state? | Yes, as connected but not yet eligible; heartbeat decides live eligibility | Phase 3–4; whether UI needs the distinction |
| What does `DELIVERY_FAILED` mean operationally? | Terminal delivery outcome requiring support resolution, no automatic reassignment after pickup | Phase 3/7; return or redelivery rules |
| Are location traces retained? | Latest only; preserve only lifecycle/incident evidence | Phase 9; privacy and diagnostic requirements |
| How many instances and what dispatch job coordination? | Single instance locally; database claim/lock for safe multi-instance jobs | Phase 7/11; deployment topology |
| Which external route provider? | None; fake adapter if demonstrating timeout behavior | Phase 11; budget and API terms |

## Data ownership and transaction lines

**PostgreSQL:** actors and profiles, branch membership, vehicles, orders/items and address snapshot, order/driver/delivery status and histories, dispatch attempts, offers, active assignment uniqueness, zones, audit, outbox, processed events, notifications and derived analytics. Each accepted business command commits all relevant durable changes together. The order aggregate records customer and branch IDs, avoiding mutable profile data as historical truth.

**Redis:** latest per-driver location and timestamp, heartbeat with TTL, GEO index, optional non-authoritative availability cache, rate limits and transient stream state. GEO members may outlive heartbeat TTL; lookup must intersect GEO results with heartbeat freshness and durable eligibility. Reservation may have a Redis hint, but PostgreSQL alone decides the winner. A Redis outage prevents new location-based dispatch rather than guessing from stale data; existing durable orders and assignments remain intact.

**Kafka:** event transport to notification and analytics consumers after the core transaction. It is not the order ledger, timeout clock or GPS transport. Outbox publication can repeat; consumers use `(consumer, event_id)` uniqueness and perform side effect plus processed marker in one transaction where possible. External provider side effects need provider idempotency keys or a delivery record/reconciliation policy.

Use short command transactions: read/check ownership and state, conditionally update, insert history/assignment/outbox, commit. Do not hold DB locks while calling Redis, Kafka or an HTTP provider. For dispatch, candidate discovery and ranking happen outside the reservation transaction; eligibility is rechecked when reserving. Reservation and offer creation must commit atomically. Acceptance checks assignment owner, status, deadline, driver reservation, and order state atomically. Post-commit notifications may be delayed without changing the accepted business outcome.

### Concrete prerequisites at the phase gates

| Area | Define and verify before |
| --- | --- |
| Application | JDK/Boot/Maven patch compatibility, `com.fleetflow` package, single Maven module, `/api/v1`, profile precedence and health exposure: Phase 0 |
| Persistence | PostgreSQL image, UTC/time source, UUID generator, lower snake case, migration order, `created_at`/`updated_at` semantics, string status codes, `version` where needed, FK/check/unique/index naming: Phases 0–1 |
| Domain | Who can onboard actors, branch membership, item/price snapshot, cancellation cutoff, delivery attempt representation, no-candidate deadline: Phases 1–3 and 5–7 |
| Security | OIDC issuer/audience/claim mapping, local issuer, actor activation, role and per-object policy matrix: Phase 2; test each new endpoint thereafter |
| Redis | Key namespace `fleetflow:driver:location:{id}`, `fleetflow:driver:heartbeat:{id}`, `fleetflow:geo:available`, `fleetflow:rate:{scope}:{id}`; TTL/clock skew, out-of-order updates, GEO removal/reconciliation: Phase 4. Example 30-second heartbeat is a tunable starting point, not a guarantee |
| Dispatch | Search radius, candidate limit, hard eligibility, score factors/normalization/tie-break, no-driver policy: Phase 5; reservation SQL/constraints, offer deadline and transaction boundaries: Phase 6; retry budget: Phase 7 |
| Asynchronous work | Which committed events need notification/analytics, event ID/version, outbox polling/retention and consumer dedupe: Phase 8; order/driver commands stay synchronous |
| Tracking and zones | SSE snapshot/reconnect/access and buffering: Phase 9; coordinate axis/SRID, polygon validity, point boundary/tolerance, zone overlap and branch selection: Phase 10 |
| Operations | Local-only DB exposure, secret source, CI Docker capacity, monitoring endpoint policy: Phase 0; alert recipients/backup target/recovery evidence: Phase 11 |

## Critical invariants and enforcement

The database backstops races that Java checks alone cannot prevent. Exact partial-index predicates are designed with the Phase 6 schema and tested against PostgreSQL.

| Invariant | Likely enforcement |
| --- | --- |
| One external `(issuer, subject)` maps to one active local actor | DB unique key; identity validation |
| Customer order belongs to one customer and one branch; item quantity positive | DB FK/check; domain creation validation |
| Merchant staff sees/changes only branch orders they belong to | Authorization in scoped query and command; security integration test |
| Customer reads/cancels only own order; driver changes only own state/location | Authorization from authenticated actor, never body-supplied owner ID |
| Every state transition starts in an allowed state and terminal states do not regress | Domain method + conditional version/status update + transaction |
| Status/history/audit entries correspond to the committed action | Same PostgreSQL transaction, append-only permissions |
| Stale/paused/suspended/vehicle-incompatible driver is not offered | Redis freshness and DB eligibility recheck just before reservation |
| A driver has at most one active offer/assignment; an order at most one active offer | Conditional DB update plus partial unique indexes and transactional offer insert |
| Only offered driver may accept before server-side deadline; one terminal offer outcome | Authorization + conditional DB update/lock + transaction |
| Acceptance changes driver, order, delivery and offer together | One PostgreSQL transaction and consistency assertions |
| Rejection/timeout/cancellation release only the reservation they own | Conditional status/version match; never blind `RESERVED → AVAILABLE` |
| Post-pickup reassignment is not automatic; failure reason is present | Domain guard; DB non-null/check for failed outcome |
| Dispatch attempts and privileged overrides remain inspectable | Durable append-only records, separate from Redis/logs |
| Invalid/out-of-order coordinate update does not extend liveness | Validation + Redis compare/update atomic operation |
| Outbox event and business state commit together; duplicate delivery has one effect | DB transaction/outbox; unique `(consumer,event_id)` processed marker |
| Tracking coordinates go only to authorized customer/assigned driver/dispatcher | Object authorization at snapshot and stream establishment/reconnect |
| Serviceable destination belongs to allowed branch zone once zones exist | PostGIS query + domain rule + integration tests |

## Security and API contract

Use `/api/v1` action endpoints for transitions and normal resource reads with bounded pagination and allowed sort fields. Reject unknown actors, branch membership mismatch, cross-customer reads, other-driver location updates, and other-driver offer responses. Role checks alone are insufficient. `401` means missing/invalid token; `403` means authenticated but forbidden; avoid leaking another user's object existence where appropriate. HTTP conflict (`409`) covers stale version or invalid transition; expired offer may use a stable conflict code. Validate coordinate range, timestamp skew, rate, body size and identifiers before side effects. Error body should have stable `code`, human message, request path and correlation ID; no stack trace or token. Never expose entities directly.

Phase 1 data endpoints must remain test/loopback-only until Phase 2 security is complete. No public unauthenticated business writes. A local identity shortcut must be explicitly limited to local/test profile and absent from deployable profiles. Disable or authorize operational endpoints beyond health. Sensitive data and credentials stay out of logs and committed configuration.

## Testing and operations strategy

Prioritize meaningful behavior over test counts. Pure unit tests cover transitions, cancellation, eligibility, scoring, ETA and normalization. PostgreSQL Testcontainers tests cover Flyway, constraints, indexes, transaction rollback, query shapes and locking; check N+1 and pagination with representative data. MVC/security tests cover validation, response codes, roles, cross-tenant object access and endpoint policies. Redis Testcontainers tests cover TTL expiry, stale GEO members, coordinate validation and outage behavior. Kafka Testcontainers tests cover outbox crash/retry, duplicate event idempotency and consumer recovery. Phase 6 must add the synchronized 100-contender, one-driver test against real PostgreSQL: exactly one committed reservation/active offer, 99 safe failures, valid final state. Phase 7 adds timeout-versus-accept and two-worker races. Phase 10 adds real PostGIS tests. Do not mock the database for concurrency claims.

Phase 0 establishes a small CI build/test gate. Every later phase extends it for its actual dependencies. Docker Compose grows PostgreSQL → Redis → Kafka → monitoring, without a full stack on day one. Local setup documents image/version pinning, environment variables and health checks. Actuator health and request correlation start early; dispatch, outbox, consumer lag and stream metrics arrive with their features, with dashboards in Phase 11. No secrets in repository or logs. Operational claims require actual smoke, backup/restore and failure exercise evidence, not a green compile alone.

## Failure catalogue

`Retry` means bounded retry of the operation, not endless client repetition. `Audit` means durable business evidence; routine validation can be logged with correlation ID instead.

| Failure | Response and state | Retry | Audit / visibility | Phase |
| --- | --- | --- | --- | --- |
| No driver available | Keep dispatch pending for bounded retry; eventually explicit failure/operator queue | Yes, bounded | Attempt reason; dispatcher | 5/7 |
| Stale location/heartbeat | Exclude candidate; no reservation | After fresh update | Metric; dispatcher if persistent | 4/5 |
| Reservation race | Losing transaction returns conflict; one winner only | Try next candidate | Attempt reason; dispatcher metric | 6 |
| Rejection | Finalize offer, release driver, keep order dispatching | Next candidate | Attempt history; merchant/dispatcher | 6/7 |
| Offer timeout | Finalize offer once, release, redispatch | Next candidate | Attempt history; dispatcher | 7 |
| Driver disconnect | Expire eligibility; active offer times out, accepted delivery becomes incident | Only pre-pickup | Incident audit; dispatcher/customer as applicable | 4/7/9 |
| Driver unavailable/suspended | Remove eligibility; pre-pickup offer cancelled or reassigned | Next candidate | Status/override audit; dispatcher | 3/7 |
| Customer/merchant cancellation | Enforce allowed cutoff; cancel pending offer atomically | No | Actor/reason; affected parties | 3/7 |
| Invalid transition | Stable conflict, no state change | No | Correlation log; audit repeated abuse if needed | 3 |
| Delivery failure | Terminal failure with reason, preserve assignment and incident | No automatic post-pickup retry | Audit; customer/support | 7 |
| Redis unavailable | Reject location-based dispatch safely; preserve DB truth | Yes, after recovery | Metric/alert; dispatcher | 4/5 |
| Kafka unavailable | Business transaction + outbox commit; publisher retries | Yes | Outbox backlog/alert | 8 |
| Duplicate Kafka event | Consumer deduplicates; no duplicate effect | Safe redelivery | Processed marker/metric | 8 |
| Outbox publish failure | Leave pending, backoff/retry; poison records surfaced | Yes, bounded per run | Backlog/alert | 8 |
| Notification provider failure | Record pending/failed delivery, retry by policy | Yes if transient | Delivery record; support | 8/11 |
| Route provider timeout | Use documented fallback ETA or omit estimate; dispatch remains correct | Bounded | Metric; operator if sustained | 11 |
| SSE disconnect | Release stream resources; snapshot/reconnect with reauthorization | Client reconnect | Connection metric; no business change | 9 |
| Malformed/stale coordinates | Validation error; do not refresh heartbeat or GEO | Correct payload | Rate/validation metric | 4 |
| Database conflict/outage | Roll back command; stable conflict or 503; never claim success | Conflict: maybe new candidate; outage: after recovery | Error metric/alert | 1/6 |
