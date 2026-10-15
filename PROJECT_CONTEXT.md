# FleetFlow — PROJECT_CONTEXT.md

> Living technical context for the FleetFlow project.
>
> This file is intended for the developer, Codex, and any other coding assistant working inside the repository.
> It defines the current product direction, engineering priorities, expected depth, implementation philosophy,
> and the learning goals of the project.
>
> **This is not a frozen specification.**
> New explicit developer instructions always take priority over this document.

> **Current status (2026-10-15):** The local demo now includes React customer, merchant, dispatcher, driver and operator workspaces, with one signed real-service order-to-delivery workflow verified. The public deployment is not ready, and Phase 11 remains `CLOSED_PARTIAL`. [docs/PRODUCTION-ROADMAP.md](docs/PRODUCTION-ROADMAP.md) is the authoritative remaining-work plan and launch gate.

---

# 1. Project Identity

## Name

**FleetFlow**

## Working Description

FleetFlow is a production-inspired Java/Spring backend for real-time delivery and logistics orchestration.

It is inspired by the engineering problems behind:

- courier platforms,
- last-mile delivery systems,
- food delivery dispatch,
- same-day shipping,
- fleet operations,
- merchant delivery networks,
- and real-time driver assignment platforms.

FleetFlow is **not** intended to become a basic food-ordering CRUD application.

The core identity of the project is:

- real-time driver state,
- geospatial search,
- delivery orchestration,
- concurrency-safe driver assignment,
- explicit business state machines,
- matching and ranking,
- assignment timeouts,
- reassignment,
- asynchronous workflows,
- live tracking,
- operational observability,
- and production-style reliability.

---

# 2. Primary Goal

The main purpose of FleetFlow is to become a serious portfolio project that demonstrates the kind of backend engineering expected from a strong junior Java/Spring developer working on a real production system.

The project should deliberately teach and exercise both:

## Core junior backend fundamentals

- Java
- OOP
- interfaces
- records
- enums
- collections
- streams
- exceptions
- immutability
- clean domain modeling
- Spring Boot
- dependency injection
- Spring MVC
- REST API design
- Spring Data JPA
- PostgreSQL
- SQL
- transactions
- validation
- exception handling
- pagination
- filtering
- sorting
- authentication
- authorization
- Flyway migrations
- testing
- Git
- Maven
- Docker
- configuration
- logging
- debugging

## Advanced but approachable production concepts

- Redis
- Redis GEO
- distributed ephemeral state
- geospatial search
- PostGIS
- optimistic/pessimistic concurrency
- atomic reservation
- state machines
- scheduled jobs
- Kafka
- transactional outbox
- idempotent consumers
- WebSocket / SSE
- rate limiting
- resilience
- observability
- Prometheus
- Grafana
- Testcontainers
- CI/CD
- production deployment practices

The project should remain understandable enough that the developer can explain every major design decision during an interview.

---

# 3. Engineering Philosophy

Prefer:

- correctness over feature count,
- realistic backend problems,
- explicit state transitions,
- clear transaction boundaries,
- good SQL and schema design,
- small focused services/modules,
- meaningful use of technology,
- strong automated testing,
- observable behavior,
- clear failure handling,
- a smaller correct system over a huge broken one,
- production-style reasoning,
- maintainability,
- interview explainability.

Avoid:

- adding technologies only for the README,
- creating many microservices only to say the project uses microservices,
- putting business logic inside controllers,
- exposing JPA entities directly from REST APIs,
- using `CascadeType.ALL` everywhere,
- using `FetchType.EAGER` everywhere,
- ignoring N+1 problems,
- writing every GPS update directly into PostgreSQL,
- using Redis as the only durable source of truth,
- assuming the nearest driver is always the best driver,
- ignoring double-assignment races,
- using Kafka for every local method call,
- adding Kubernetes too early,
- abstracting everything prematurely,
- implementing machine learning where a deterministic algorithm is enough,
- hiding business rules inside infrastructure code,
- allowing arbitrary status mutation.

---

# 4. Recommended Architecture

FleetFlow should begin as a **modular monolith**.

Do not start by creating many distributed services.

Suggested high-level runtime:

```text
                         ┌─────────────────┐
                         │ REST / SSE API  │
                         └────────┬────────┘
                                  │
                    ┌─────────────▼─────────────┐
                    │        FleetFlow          │
                    │    Spring Boot App        │
                    │                           │
                    │ Identity                  │
                    │ Customer                  │
                    │ Merchant                  │
                    │ Order                     │
                    │ Driver                    │
                    │ Dispatch                  │
                    │ Delivery                  │
                    │ Tracking                  │
                    │ Notification              │
                    │ Audit                     │
                    │ Analytics                 │
                    └──────┬───────┬──────┬────┘
                           │       │      │
                           ▼       ▼      ▼
                     PostgreSQL  Redis  Kafka
                           │       │
                           ▼       ▼
                       PostGIS  Redis GEO
```

Suggested package direction:

```text
com.fleetflow
├── identity
├── customer
├── merchant
├── order
├── driver
├── dispatch
├── delivery
├── tracking
├── notification
├── analytics
├── audit
└── shared
```

Each module may contain:

```text
controller
service
repository
domain
dto
mapper
exception
config
```

Package design may evolve.

Do not freeze it prematurely.

---

# 5. Preferred Technology Stack

## Core

- Java 21+ or Java 25 if ecosystem compatibility is stable
- Spring Boot
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Validation
- Spring Actuator
- Spring Kafka
- Spring WebSocket or Server-Sent Events
- PostgreSQL
- Flyway
- Redis
- Redis GEO
- Apache Kafka
- Docker
- Docker Compose
- OpenAPI / Swagger

## Geospatial

- Redis GEO for fast nearby-driver lookup
- PostGIS for durable geospatial rules and zones

## Testing

- JUnit 5
- Mockito
- Spring Boot Test
- MockMvc / WebTestClient where appropriate
- Testcontainers

## Observability

- Micrometer
- Prometheus
- Grafana
- OpenTelemetry when useful

## Resilience

- Resilience4j where meaningful

## CI/CD

- GitHub Actions

## Possible later additions

Only if justified:

- Kubernetes
- Helm
- external routing provider
- external map provider
- schema registry
- Debezium
- dedicated analytics storage
- advanced streaming

---

# 6. Main Actors

FleetFlow may contain these actors:

## CUSTOMER

Can:

- create orders,
- view own orders,
- cancel eligible own orders,
- view tracking for own active deliveries.

Cannot:

- view other customers' orders,
- control drivers,
- access merchant/admin data.

## DRIVER

Can:

- update own location,
- change eligible driver state,
- view assignments offered to them,
- accept or reject own assignments,
- update eligible delivery states after assignment.

Cannot:

- update another driver's location,
- accept another driver's assignment,
- arbitrarily assign themselves to orders.

## MERCHANT

Can:

- manage merchant branches,
- view merchant-owned orders,
- move eligible orders through merchant preparation states,
- mark orders ready for pickup.

Cannot:

- view another merchant's orders.

## DISPATCHER

Can:

- inspect dispatch activity,
- manually override or reassign where allowed,
- inspect driver availability,
- manage exceptional operational situations.

## SUPPORT

Can:

- inspect operational information,
- review delivery problems,
- assist with incidents.

Permissions should remain narrower than ADMIN.

## ADMIN

Can:

- manage system-level configuration,
- suspend drivers,
- inspect all entities,
- perform restricted operational overrides.

---

# 7. Core Domain Concepts

Expected major domain concepts include:

- User
- Role
- Permission
- Customer
- Merchant
- MerchantBranch
- Order
- OrderItem
- Address
- Driver
- Vehicle
- DriverStatus
- DriverStatusHistory
- DriverLocation
- Delivery
- DispatchAttempt
- DriverAssignment
- DeliveryZone
- TrackingSession
- Notification
- AuditLog
- OutboxEvent
- ProcessedEvent
- AnalyticsAggregate

Not every concept must exist from the first phase.

---

# 8. Merchant and Branch Model

A merchant may have multiple branches.

Example:

```text
Merchant
    │
    ├── Cairo Branch
    ├── Giza Branch
    └── Nasr City Branch
```

A branch may contain:

- name,
- location,
- operating hours,
- active/inactive state,
- delivery zone,
- preparation settings,
- operational capacity.

Orders should belong to a `MerchantBranch`, not just a merchant.

Later, PostGIS may determine which branch can serve a destination.

---

# 9. Order Model

Potential order concepts:

```text
Order
├── Customer
├── MerchantBranch
├── DeliveryAddress
├── OrderItems
├── Total
├── Status
├── CreatedAt
└── Delivery
```

Potential endpoints:

```text
POST /api/v1/orders
GET  /api/v1/orders/{id}
GET  /api/v1/orders
POST /api/v1/orders/{id}/confirm
POST /api/v1/orders/{id}/prepare
POST /api/v1/orders/{id}/ready
POST /api/v1/orders/{id}/cancel
```

Search and filtering should eventually support combinations such as:

```text
GET /api/v1/orders
    ?status=IN_TRANSIT
    &merchantId=...
    &from=...
    &to=...
    &page=0
    &size=20
    &sort=createdAt,desc
```

This module should exercise:

- DTOs,
- Bean Validation,
- pagination,
- sorting,
- filtering,
- Specifications,
- JPQL,
- projections,
- indexes,
- global error handling,
- HTTP semantics.

---

# 10. Order State Machine

Potential states:

- CREATED
- CONFIRMED
- PREPARING
- READY_FOR_PICKUP
- DISPATCHING
- DRIVER_ASSIGNED
- PICKED_UP
- IN_TRANSIT
- DELIVERED
- CANCELLED
- DELIVERY_FAILED

Potential normal flow:

```text
CREATED
   ↓
CONFIRMED
   ↓
PREPARING
   ↓
READY_FOR_PICKUP
   ↓
DISPATCHING
   ↓
DRIVER_ASSIGNED
   ↓
PICKED_UP
   ↓
IN_TRANSIT
   ↓
DELIVERED
```

Possible alternate transitions:

```text
CREATED -> CANCELLED
CONFIRMED -> CANCELLED
DISPATCHING -> CANCELLED
DRIVER_ASSIGNED -> DISPATCHING
IN_TRANSIT -> DELIVERY_FAILED
```

Do not allow:

```java
order.setStatus(request.status());
```

Prefer explicit domain actions:

```java
order.confirm();
order.startPreparing();
order.markReadyForPickup();
order.assignDriver(driverId);
order.markPickedUp();
order.startDelivery();
order.deliver();
```

Each transition must validate the current state.

Invalid state transitions must fail with stable domain errors.

---

# 11. Driver State Machine

Potential driver states:

- OFFLINE
- ONLINE
- AVAILABLE
- RESERVED
- DELIVERING
- PAUSED
- SUSPENDED

Potential normal lifecycle:

```text
OFFLINE
   ↓
ONLINE
   ↓
AVAILABLE
   ↓
RESERVED
   ↓
DELIVERING
   ↓
AVAILABLE
```

Driver state should never be changed arbitrarily.

Invalid transitions must be rejected.

---

# 12. Real-Time Driver Location

Drivers may send location updates every few seconds.

Potential endpoint:

```text
POST /api/v1/drivers/me/location
```

Potential payload:

```json
{
  "latitude": 30.0131,
  "longitude": 31.2089,
  "heading": 120,
  "speed": 8.4,
  "timestamp": "2026-10-03T01:00:00Z"
}
```

Do not assume every location update belongs in PostgreSQL.

Preferred direction:

```text
PostgreSQL
    ↓
durable driver data
driver state
vehicle
assignment history
delivery history

Redis
    ↓
latest location
heartbeat
availability metadata
Redis GEO position
```

Optional location history may later be persisted asynchronously.

Need to handle:

- invalid coordinates,
- stale timestamps,
- impossible update frequency,
- stale driver location,
- unavailable drivers,
- expired heartbeat.

---

# 13. Driver Heartbeat

Maintain driver liveness separately from durable driver state.

Possible Redis key:

```text
driver:heartbeat:{driverId}
```

Example TTL:

```text
30 seconds
```

Every valid driver heartbeat or location update may refresh it.

Example interpretation:

```text
5 seconds old   -> fresh
15 seconds old  -> fresh
2 minutes old   -> stale
```

Dispatch must exclude stale drivers.

The exact TTL must remain configurable.

---

# 14. Redis GEO

Potential uses:

- nearby available-driver lookup,
- candidate pre-filtering,
- current driver distance,
- current active-driver position.

Need to consider:

- stale GPS,
- heartbeat expiration,
- unavailable drivers,
- invalid coordinates,
- driver zone,
- location TTL,
- active delivery state.

Redis GEO is an operational search mechanism.

It is not the durable source of business truth.

---

# 15. Driver Matching Engine

The dispatch engine must not simply assign the nearest driver.

Potential candidate factors:

- distance,
- availability,
- heartbeat freshness,
- active deliveries,
- workload,
- vehicle type,
- zone,
- driver rating,
- acceptance rate,
- recent rejections,
- estimated arrival time,
- delivery capacity,
- merchant constraints.

Potential interface:

```java
public interface DriverScoringStrategy {
    DriverScore score(
        DriverCandidate candidate,
        DeliveryRequest request
    );
}
```

Potential weighted score:

```text
Distance          50%
Current workload  20%
Acceptance rate   15%
Rating            10%
Zone affinity      5%
```

Weights must be configurable.

Example:

```yaml
fleetflow:
  dispatch:
    distance-weight: 0.50
    workload-weight: 0.20
    acceptance-weight: 0.15
    rating-weight: 0.10
    zone-weight: 0.05
```

The exact algorithm is expected to evolve.

The first implementation should remain deterministic, explainable, and heavily unit-tested.

---

# 16. Dispatch Workflow

Potential flow:

```text
Order READY_FOR_PICKUP
        ↓
DISPATCHING
        ↓
Find nearby candidates
        ↓
Filter ineligible drivers
        ↓
Rank candidates
        ↓
Reserve best candidate
        ↓
Create assignment offer
        ↓
Wait for response
        ↓
ACCEPTED
        ↓
DRIVER_ASSIGNED
```

If driver rejects or times out:

```text
Assignment rejected/expired
        ↓
Release driver
        ↓
Record dispatch attempt
        ↓
Try next candidate
        ↓
or mark dispatch failure
```

---

# 17. Assignment Model

Potential assignment states:

- OFFERED
- ACCEPTED
- REJECTED
- TIMED_OUT
- CANCELLED
- FAILED

Potential endpoints:

```text
POST /api/v1/assignments/{id}/accept
POST /api/v1/assignments/{id}/reject
```

Authorization rules:

- driver may only accept an assignment offered to that driver,
- expired assignments cannot be accepted,
- already-finalized assignments cannot be accepted again,
- another driver cannot accept the assignment.

---

# 18. Assignment Concurrency

This is a critical project requirement.

Problem:

```text
Order A and Order B both see Driver X as AVAILABLE.
```

Only one valid reservation may succeed.

Possible strategies:

- atomic conditional PostgreSQL update,
- optimistic locking,
- pessimistic locking,
- Redis atomic reservation,
- hybrid approach.

Choose deliberately.

Do not add distributed locks automatically.

A simple first strategy may be:

```sql
UPDATE drivers
SET status = 'RESERVED'
WHERE id = ?
AND status = 'AVAILABLE';
```

Interpretation:

```text
1 affected row -> reservation won
0 affected rows -> another transaction won
```

The chosen strategy must be explainable and covered by concurrency tests.

---

# 19. Mandatory Concurrency Test

FleetFlow must eventually contain a test where:

```text
Driver X = AVAILABLE

100 simultaneous reservation attempts
```

Expected:

```text
Exactly 1 reservation succeeds.
99 fail safely.
Driver state remains valid.
Only one active assignment exists.
No corrupted records exist.
```

This test is one of the project's major portfolio features.

---

# 20. Dispatch Attempt History

Do not overwrite failed attempts.

Example:

```text
Order #491

Attempt 1
Driver 10
REJECTED

Attempt 2
Driver 16
TIMED_OUT

Attempt 3
Driver 8
ACCEPTED
```

Persist attempt history.

This supports:

- debugging,
- auditing,
- analytics,
- operational investigation,
- assignment quality analysis.

---

# 21. Assignment Timeout

Drivers may have a configurable amount of time to respond.

Example:

```text
30 seconds
```

Possible implementations:

- scheduled database polling,
- Redis TTL,
- delayed queue,
- Kafka-based timing,
- other timing infrastructure.

Start with the simplest correct solution.

Recommended first version:

- scheduled database worker.

Timeout must:

- mark assignment timed out,
- release the driver,
- preserve attempt history,
- create an event where needed,
- trigger the next candidate or fail dispatch.

---

# 22. Automatic Reassignment

Potential triggers:

- driver rejects,
- assignment expires,
- driver disconnects,
- driver cancels,
- driver becomes unavailable,
- vehicle problem,
- dispatcher override.

Potential transition:

```text
DRIVER_ASSIGNED -> DISPATCHING
```

Avoid reassignment after pickup unless it is explicitly modeled as an incident workflow.

---

# 23. Delivery Lifecycle

Potential flow:

```text
DRIVER_ASSIGNED
       ↓
PICKED_UP
       ↓
IN_TRANSIT
       ↓
DELIVERED
```

Possible failure:

```text
IN_TRANSIT
    ↓
DELIVERY_FAILED
```

Potential failure reasons:

- CUSTOMER_UNAVAILABLE
- WRONG_ADDRESS
- DAMAGED_PACKAGE
- CUSTOMER_REFUSED
- VEHICLE_FAILURE
- ADDRESS_UNREACHABLE
- OTHER

Failures should preserve reason and operational metadata.

---

# 24. Real-Time Tracking

Customers should eventually receive live delivery updates.

Preferred initial transport:

- Server-Sent Events.

Potential endpoint:

```text
GET /api/v1/orders/{id}/tracking/stream
```

Potential pipeline:

```text
Driver
  ↓
Location API
  ↓
Redis
  ↓
Tracking layer
  ↓
SSE
  ↓
Customer
```

Potential tracking events:

- driver_location,
- driver_arriving,
- order_picked_up,
- order_in_transit,
- delivery_completed,
- delivery_failed.

Not every GPS update must flow through Kafka.

A later iteration may compare SSE and WebSocket approaches.

---

# 25. PostGIS and Delivery Zones

PostGIS should be introduced after the main dispatch workflow works.

Potential concepts:

- DeliveryZone
- ServiceArea
- MerchantBranch

Potential uses:

- is customer inside a supported service area?
- which branch should serve the customer?
- which zone contains the driver?
- can the merchant accept the delivery?
- should a driver be considered for this zone?

Possible storage:

- polygon,
- multipolygon.

Potential query concept:

```text
ST_Contains(zone, customerPoint)
```

Do not invent fake precision.

GPS is approximate.

Geofencing should use tolerances where appropriate.

---

# 26. ETA

ETA is optional but useful.

First implementation may use:

- straight-line distance,
- configurable average speed,
- merchant preparation time.

Possible later implementation:

- routing provider,
- traffic information,
- historical zone/time data.

External routing must be hidden behind an interface.

Example:

```java
public interface RoutingProvider {
    Route estimate(
        Coordinates origin,
        Coordinates destination
    );
}
```

Development may use a fake provider.

---

# 27. Kafka

Kafka should not be introduced until the synchronous domain works correctly.

Potential events:

- ORDER_CREATED
- ORDER_CONFIRMED
- ORDER_READY
- DISPATCH_STARTED
- DRIVER_RESERVED
- ASSIGNMENT_OFFERED
- ASSIGNMENT_ACCEPTED
- ASSIGNMENT_REJECTED
- ASSIGNMENT_TIMED_OUT
- DRIVER_ASSIGNED
- ORDER_PICKED_UP
- ORDER_IN_TRANSIT
- ORDER_DELIVERED
- ORDER_CANCELLED
- DELIVERY_FAILED
- DRIVER_LOCATION_UPDATED

Use Kafka where asynchronous decoupling is meaningful.

Do not turn every local method call into an event.

Potential consumers:

- NotificationConsumer
- AnalyticsConsumer
- TrackingConsumer
- AuditConsumer

---

# 28. Transactional Outbox

Use when business state and event publication must remain consistent.

Problem:

```text
Database commits ORDER_DELIVERED.
Kafka publication fails.
```

Without protection, asynchronous consumers never see the event.

Preferred pattern:

```text
BEGIN
    update order
    insert outbox_event
COMMIT
```

Then:

```text
Outbox publisher -> Kafka
```

Potential outbox fields:

- id
- aggregate_type
- aggregate_id
- event_type
- payload
- created_at
- published_at
- status
- retry_count

Initial implementation may use polling.

---

# 29. Idempotent Kafka Consumers

Consumers must safely handle duplicate events.

Example:

```text
ORDER_DELIVERED
ORDER_DELIVERED
```

Duplicate delivery must not cause:

- duplicate notifications,
- duplicate analytics effects,
- duplicate business side effects.

Potential processed event store:

- event_id
- consumer_name
- processed_at

Each consumer should define its idempotency boundary clearly.

---

# 30. Notifications

Initial channels:

- IN_APP
- EMAIL

Optional later:

- SMS mock
- push notification mock

Potential notifications:

- order confirmed,
- order ready,
- driver assigned,
- driver approaching,
- order picked up,
- order delivered,
- delivery failed,
- assignment offer.

Notification delivery should eventually become asynchronous.

---

# 31. Security

Potential roles:

- CUSTOMER
- DRIVER
- MERCHANT
- DISPATCHER
- SUPPORT
- ADMIN

Potential permissions:

- order:create
- order:view-own
- order:cancel-own
- merchant:order:view
- merchant:order:update
- driver:location:update
- driver:assignment:accept
- driver:assignment:reject
- dispatch:view
- dispatch:override
- admin:view-all
- admin:driver:suspend

Object-level authorization is mandatory.

Examples:

- customer cannot see another customer's order,
- driver cannot update another driver's location,
- merchant sees only own branch orders,
- driver may accept only assignments offered to them,
- support may inspect data without necessarily receiving unrestricted modification permissions.

---

# 32. Rate Limiting

Redis may be used for:

- token bucket,
- sliding window,
- fixed window where justified.

Potential targets:

```text
Login
5/min

Order creation
20/min

Public tracking
60/min

Merchant API
configurable

Driver location
much higher rate
```

Location update rate limits should differ from authentication and order APIs.

---

# 33. Auditing

Audit important operational actions.

Examples:

- DRIVER_REASSIGNED
- ORDER_CANCELLED_BY_ADMIN
- DRIVER_SUSPENDED
- DELIVERY_OVERRIDE
- ORDER_STATE_CORRECTED
- DISPATCH_MANUAL_OVERRIDE

Potential fields:

- actor
- actorRole
- action
- resourceType
- resourceId
- previousValue
- newValue
- timestamp
- reason
- metadata

Auditing should not be confused with normal application logging.

---

# 34. Analytics

Potential analytics:

- orders per day,
- completion rate,
- cancellation rate,
- average preparation time,
- average dispatch time,
- average assignment attempts,
- average delivery time,
- driver acceptance rate,
- driver utilization,
- orders per zone,
- peak hours,
- failed deliveries.

Analytics should ideally consume events asynchronously.

Do not block order transactions to calculate reports.

Potential future aggregate tables:

```text
analytics_daily
analytics_driver
analytics_zone
```

---

# 35. Database and JPA Rules

Use:

- PostgreSQL
- Flyway
- explicit indexes
- database constraints
- deliberate fetch plans
- transactions
- pagination
- projections where useful
- JPQL
- native SQL where justified
- `EXPLAIN ANALYZE` for important queries

Avoid:

- exposing entities directly,
- giant object graphs,
- `CascadeType.ALL` everywhere,
- `EAGER` everywhere,
- silent N+1 queries,
- production `ddl-auto=create`,
- putting all logic in repository methods.

Potential indexes:

```text
orders(customer_id)
orders(status, created_at)
orders(merchant_branch_id, created_at)
driver_assignments(driver_id, status)
dispatch_attempts(order_id)
drivers(status)
```

Actual indexes must follow real query patterns.

---

# 36. Redis Data Direction

Potential Redis data:

```text
driver:location:{id}
driver:heartbeat:{id}
driver:reservation:{id}

geo:available-drivers

rate-limit:{key}
tracking:{orderId}
dispatch:candidates:{orderId}
```

Potential uses:

- latest driver location,
- driver heartbeat,
- driver availability cache,
- temporary reservation state,
- rate limiting,
- tracking session state,
- nearby-driver search.

Durable business history belongs in PostgreSQL.

---

# 37. Error Handling

Potential domain exceptions:

- ResourceNotFoundException
- InvalidStateTransitionException
- DriverUnavailableException
- AssignmentExpiredException
- UnauthorizedOperationException
- NoDispatchCandidateException
- InvalidCoordinatesException
- DeliveryZoneViolationException

Prefer stable application error codes.

Potential response shape:

```json
{
  "timestamp": "...",
  "status": 400,
  "code": "INVALID_STATE_TRANSITION",
  "message": "...",
  "path": "/api/v1/...",
  "traceId": "...",
  "details": []
}
```

Do not expose stack traces to API clients.

---

# 38. Logging

Useful structured context:

- traceId
- requestId
- eventId
- orderId
- driverId
- assignmentId
- merchantId
- customerId

Do not log:

- passwords,
- JWTs,
- refresh tokens,
- raw API secrets,
- unnecessarily sensitive personal data.

Logging should help reconstruct operational failures.

---

# 39. Retry and Resilience

Retry only transient failures.

Potential retryable failures:

- notification provider unavailable,
- routing provider timeout,
- temporary network outage,
- Kafka delivery issue.

Potential non-retryable failures:

- invalid state,
- expired assignment,
- unauthorized operation,
- invalid coordinates,
- impossible domain transition.

Use where justified:

- max attempts,
- backoff,
- jitter,
- DLQ,
- metrics,
- circuit breakers.

Resilience4j may be used around external adapters.

---

# 40. Testing Strategy

Testing is a major learning goal.

## Unit tests

Focus on pure Java logic:

- order state transitions,
- driver state transitions,
- matching/scoring,
- candidate eligibility,
- ETA calculations,
- cancellation rules,
- delivery failure rules.

## Repository tests

Test:

- important JPQL/native queries,
- constraints,
- fetch plans,
- transaction behavior,
- locking,
- PostGIS behavior,
- database-level invariants.

## Controller tests

Test:

- request validation,
- authorization,
- HTTP status codes,
- pagination,
- error response format,
- ownership boundaries.

## Integration tests

Use Testcontainers for:

- PostgreSQL
- Redis
- Kafka

## Mandatory high-priority scenarios

- order state transitions,
- invalid transitions,
- driver state transitions,
- nearby-driver lookup,
- driver scoring,
- double-assignment prevention,
- concurrent reservation,
- assignment timeout,
- rejection then next candidate,
- stale driver exclusion,
- duplicate Kafka event handling,
- outbox persistence,
- customer isolation,
- driver assignment ownership,
- merchant isolation,
- zone validation,
- SSE/WebSocket authorization,
- rate limiting.

---

# 41. Observability

Use:

- Spring Actuator
- Micrometer
- Prometheus
- Grafana

Potential metrics:

- `fleetflow.orders.created`
- `fleetflow.orders.completed`
- `fleetflow.orders.cancelled`
- `fleetflow.dispatch.started`
- `fleetflow.dispatch.duration`
- `fleetflow.dispatch.no_candidate`
- `fleetflow.dispatch.assignment_attempts`
- `fleetflow.driver.location.update`
- `fleetflow.driver.available.count`
- `fleetflow.assignment.accepted`
- `fleetflow.assignment.rejected`
- `fleetflow.assignment.timeout`
- `fleetflow.delivery.duration`
- Kafka consumer lag
- HTTP request latency/error rate

Potential dashboards:

- orders by status,
- active drivers,
- dispatch latency,
- assignment success rate,
- average assignment attempts,
- average delivery time,
- consumer lag,
- error rate.

---

# 42. External Integration

At least one external-style dependency should eventually exist.

Recommended candidate:

- routing provider.

Potential abstraction:

```java
public interface RoutingProvider {
    Route estimate(
        Coordinates origin,
        Coordinates destination
    );
}
```

Development implementation:

```text
FakeRoutingProvider
```

Later production-style implementation may call an external HTTP provider.

Wrap real external calls with:

- timeout,
- retry where appropriate,
- circuit breaker,
- metrics,
- structured logs.

---

# 43. Driver Simulator

Create a small driver simulator to make FleetFlow easy to demo and test.

Possible behavior:

1. register/select driver,
2. mark driver online,
3. send heartbeat,
4. send location,
5. move gradually along a generated route,
6. accept/reject assignments,
7. simulate disconnect,
8. simulate delivery completion.

Example movement:

```text
30.0444, 31.2357
30.0448, 31.2370
30.0452, 31.2390
...
```

FleetFlow should:

- update Redis,
- update Redis GEO,
- refresh heartbeat,
- publish tracking events,
- allow the customer to observe movement.

Other safe simulators:

- fake merchant status updates,
- fake notification provider,
- generated order load,
- fake routing provider.

---

# 44. API Design

Prefer versioned REST endpoints:

```text
/api/v1/...
```

Possible endpoints:

```text
POST /api/v1/orders
GET  /api/v1/orders/{id}
GET  /api/v1/orders

POST /api/v1/drivers/me/location

POST /api/v1/assignments/{id}/accept
POST /api/v1/assignments/{id}/reject

GET  /api/v1/orders/{id}/tracking
GET  /api/v1/orders/{id}/tracking/stream
```

Use HTTP semantics deliberately.

Do not force all actions into generic `PATCH` requests if explicit action endpoints better express domain behavior.

---

# 45. Configuration

Prefer typed externalized configuration.

Example:

```yaml
fleetflow:
  dispatch:
    assignment-timeout: 30s
    search-radius: 5km
    candidate-limit: 10
    distance-weight: 0.50
    workload-weight: 0.20
    rating-weight: 0.10
    acceptance-weight: 0.15
    zone-weight: 0.05
```

Do not bury tunable dispatch behavior in code.

Profiles should eventually include:

```text
application-local.yml
application-test.yml
application-prod.yml
```

Secrets must not be committed.

---

# 46. Docker and Local Development

A new developer should be able to run FleetFlow locally with minimal setup.

Potential Docker Compose services:

- PostgreSQL
- Redis
- Kafka
- FleetFlow application
- Prometheus
- Grafana

Provide:

- `docker-compose.yml`
- `.env.example`
- local configuration
- startup instructions
- health checks

Do not commit real secrets.

---

# 47. CI/CD

Recommended GitHub Actions pipeline:

```text
1. checkout
2. setup Java
3. compile
4. unit tests
5. integration tests
6. static checks
7. package
8. Docker build
```

Possible later:

- container registry push,
- staging deployment,
- migration verification,
- smoke tests,
- security scanning.

CI should fail on broken migrations or failing integration tests.

---

# 48. Production-Style Documentation

README should eventually contain:

- project overview,
- architecture,
- tech stack,
- major domain model,
- order lifecycle,
- driver lifecycle,
- dispatch flow,
- location flow,
- matching algorithm,
- concurrency strategy,
- Redis vs PostgreSQL decisions,
- Kafka event flow,
- outbox design,
- testing strategy,
- PostGIS design,
- real-time tracking design,
- local setup,
- Docker setup,
- environment variables,
- Swagger/OpenAPI,
- observability screenshots,
- demo screenshots,
- known limitations,
- architectural tradeoffs,
- future improvements.

Only document implemented behavior as completed.

---

# 49. Development Roadmap

FleetFlow should be developed in deliberate phases so each phase teaches a clear group of Java/Spring/backend concepts.

---

## Phase 0 — Architecture and Project Bootstrap

### Goal

Create a clean foundation without implementing advanced features prematurely.

### Work

- initialize repository,
- Maven structure,
- Spring Boot application,
- module/package boundaries,
- PostgreSQL,
- Flyway,
- Docker Compose,
- base configuration,
- error response model,
- logging conventions,
- test setup,
- architecture notes,
- ERD draft.

### Developer learning

Must understand:

- Spring Boot startup,
- dependency injection,
- configuration properties,
- profiles,
- Maven lifecycle,
- Docker Compose basics,
- Flyway purpose,
- database migrations,
- module boundaries.

### Do not add yet

- Kafka,
- Redis GEO,
- PostGIS,
- WebSockets,
- microservices,
- Kubernetes.

---

## Phase 1 — REST, JPA, SQL and Core Business Model

### Goal

Master the normal Spring backend workflow.

### Work

Implement:

- users,
- customers,
- merchants,
- merchant branches,
- drivers,
- vehicles,
- orders,
- order items,
- DTOs,
- validation,
- REST endpoints,
- pagination,
- sorting,
- filtering,
- global exception handling,
- Flyway migrations,
- indexes,
- repository/service/controller separation.

### Developer learning

Must understand:

- entity lifecycle,
- relationships,
- DTO mapping,
- validation,
- service boundaries,
- SQL joins,
- indexes,
- transactions,
- fetch behavior,
- N+1.

---

## Phase 2 — Domain State Machines

### Goal

Move from CRUD to real domain behavior.

### Work

Implement:

- order state machine,
- driver state machine,
- explicit transition methods,
- invalid transition handling,
- status history,
- audit of important state changes.

### Developer learning

Must understand:

- domain modeling,
- invariants,
- enums,
- state transitions,
- service vs domain responsibilities,
- why arbitrary status setters are dangerous.

---

## Phase 3 — Authentication and Authorization

### Goal

Implement production-style access control.

### Work

Implement:

- Spring Security,
- JWT or OIDC,
- roles,
- permissions,
- method authorization,
- object-level authorization,
- customer ownership,
- merchant ownership,
- driver ownership,
- dispatcher/admin access.

### Developer learning

Must understand:

- authentication,
- authorization,
- SecurityFilterChain,
- principals,
- authorities,
- method security,
- ownership checks,
- 401 vs 403.

---

## Phase 4 — Driver Location and Redis GEO

### Goal

Introduce real-time operational state.

### Work

Implement:

- driver location endpoint,
- Redis latest location,
- driver heartbeat,
- Redis GEO,
- nearby-driver lookup,
- stale-location exclusion,
- validation,
- configurable TTL,
- rate limits for location updates.

### Developer learning

Must understand:

- durable vs ephemeral state,
- Redis key design,
- TTL,
- GEO operations,
- caching vs persistence,
- high-frequency write paths.

---

## Phase 5 — Dispatch and Matching

### Goal

Build FleetFlow's core feature.

### Work

Implement:

- driver candidate lookup,
- eligibility filtering,
- configurable scoring,
- ranking,
- dispatch attempts,
- assignment offers,
- accept/reject flow,
- pure-Java scoring tests.

### Developer learning

Must understand:

- interfaces,
- Strategy Pattern,
- dependency injection,
- configuration properties,
- deterministic business algorithms,
- unit-testable domain logic.

---

## Phase 6 — Concurrency and Assignment Reliability

### Goal

Prevent double assignment correctly.

### Work

Implement:

- atomic driver reservation,
- clear concurrency strategy,
- transaction boundaries,
- concurrent reservation tests,
- exactly-one-winner test,
- failure behavior for losers,
- assignment ownership protection.

### Developer learning

Must understand:

- race conditions,
- atomic updates,
- optimistic locking,
- pessimistic locking,
- transaction isolation,
- why distributed locks are not automatically the answer.

---

## Phase 7 — Timeouts, Jobs and Reassignment

### Goal

Make dispatch reliable when users do not respond.

### Work

Implement:

- assignment timeout,
- scheduled processing,
- rejection flow,
- timeout flow,
- driver release,
- next-candidate selection,
- automatic reassignment,
- failure after candidate exhaustion,
- audit history.

### Developer learning

Must understand:

- scheduled jobs,
- retries,
- idempotent job execution,
- timeout modeling,
- failure states.

---

## Phase 8 — Kafka, Outbox and Idempotency

### Goal

Introduce asynchronous workflows for real reasons.

### Work

Implement:

- Kafka infrastructure,
- meaningful domain events,
- transactional outbox,
- outbox publisher,
- idempotent consumers,
- processed event store,
- notification consumer,
- analytics consumer,
- DLQ where justified.

### Developer learning

Must understand:

- producers,
- consumers,
- partitions,
- delivery semantics,
- duplicate delivery,
- outbox pattern,
- idempotency,
- eventual consistency.

---

## Phase 9 — Live Tracking

### Goal

Expose real-time delivery information to customers.

### Work

Implement:

- SSE tracking stream,
- authorization,
- tracking sessions,
- driver location updates,
- delivery state events,
- simulator-driven demo.

### Developer learning

Must understand:

- request/response vs streaming,
- SSE lifecycle,
- connection management,
- authorization for streams,
- real-time data flow.

---

## Phase 10 — PostGIS, Zones and Geofencing

### Goal

Introduce durable geospatial rules.

### Work

Implement:

- delivery zones,
- service areas,
- PostGIS migration,
- point-in-polygon queries,
- branch serviceability,
- zone-based dispatch constraints,
- geofencing tolerance.

### Developer learning

Must understand:

- spatial data,
- geospatial indexing,
- Redis GEO vs PostGIS,
- geofencing,
- coordinate precision limitations.

---

## Phase 11 — Production Hardening and Polish

### Goal

Make the project resemble something a team could operate.

### Work

Implement:

- Prometheus metrics,
- Grafana dashboards,
- structured logging,
- tracing where useful,
- rate limiting,
- Resilience4j external adapter protection,
- Docker production setup,
- CI/CD,
- backup/restore plan,
- load testing,
- security review,
- final README,
- architecture diagrams,
- demo scripts.

### Developer learning

Must understand:

- observability,
- reliability,
- operational metrics,
- deployment,
- environment configuration,
- secrets,
- smoke testing,
- production debugging.

---

# 50. Phase Completion Rule

A phase is **not complete** because the code compiles.

Each phase should require:

- implementation complete,
- unit tests complete,
- integration tests where relevant,
- migrations committed,
- API behavior verified,
- failure cases tested,
- authorization tested,
- README/context updated where architecture changed,
- no obviously dead code,
- no committed secrets,
- developer can explain the important design choices.

---

# 51. Codex Working Rules

When working on FleetFlow:

1. Read this file first.
2. Read README and existing code.
3. Verify what already exists.
4. Do not recreate completed features.
5. Follow the newest explicit developer instruction.
6. Keep the architecture coherent.
7. Add Flyway migrations when persistence changes.
8. Add or update tests for every meaningful change.
9. Think about transaction boundaries.
10. Think about concurrency.
11. Think about stale location data.
12. Think about duplicate events.
13. Think about object-level authorization.
14. Keep durable business truth in PostgreSQL.
15. Keep ephemeral/high-frequency operational state in Redis where appropriate.
16. Keep state transitions explicit.
17. Do not hide business logic inside controllers.
18. Do not expose entities directly.
19. Avoid unnecessary microservices.
20. Avoid unnecessary distributed locks.
21. Do not introduce infrastructure without a real use case.
22. Preserve backwards-compatible migrations unless explicitly instructed otherwise.
23. Clean dead code only after verifying it is actually unused.
24. Do not claim unfinished features as completed.
25. Keep the project explainable in interviews.

---

# 52. What Codex Must Not Do Automatically

Do not automatically:

- split the project into microservices,
- introduce Kubernetes,
- add service mesh infrastructure,
- add GraphQL,
- add CQRS/event sourcing,
- add machine learning,
- replace PostgreSQL with another database,
- turn every operation into Kafka messaging,
- add distributed locks without justification,
- persist every GPS point,
- create generic abstractions with no current need,
- rewrite large areas of stable code merely for style.

Prefer incremental, reviewable changes.

---

# 53. Good Demo Scenario

A strong final demo should be able to show:

1. Create customer.
2. Create merchant.
3. Create merchant branch.
4. Create driver.
5. Driver goes online.
6. Driver starts sending location.
7. Create order.
8. Merchant confirms order.
9. Merchant marks order ready.
10. Dispatch begins.
11. Redis GEO finds nearby drivers.
12. Candidate ranking is shown.
13. Best candidate is reserved.
14. Concurrent reservation attempts prove only one succeeds.
15. Driver receives assignment.
16. Driver accepts.
17. Customer opens live tracking.
18. Driver simulator moves location.
19. Customer receives SSE updates.
20. Driver picks up order.
21. Driver completes delivery.
22. Kafka events are visible.
23. Notification is delivered.
24. Analytics is updated.
25. Grafana metrics show dispatch and delivery behavior.
26. Separate scenario demonstrates rejection.
27. Separate scenario demonstrates timeout.
28. Separate scenario demonstrates reassignment.
29. Separate scenario demonstrates stale-driver exclusion.
30. Tests show concurrency and duplicate-event protection.

---

# 54. CV / Interview Goals

FleetFlow should eventually support accurate claims such as:

- Built a production-inspired real-time logistics backend using Java and Spring Boot.
- Implemented driver matching using Redis GEO and configurable candidate scoring.
- Prevented double driver assignment with concurrency-safe reservation and high-contention integration tests.
- Modeled order, driver, assignment, and delivery workflows with explicit state machines.
- Implemented assignment timeouts, rejection handling, and automatic reassignment.
- Built event-driven workflows with Kafka, transactional outbox, retries, and idempotent consumers.
- Used PostgreSQL and PostGIS for durable business state and delivery-zone rules.
- Used Redis for ephemeral driver location, heartbeat, GEO search, and rate limiting.
- Added SSE live tracking for active deliveries.
- Added integration, security, concurrency, Redis, PostgreSQL, and Kafka tests with Testcontainers.
- Added observability using Actuator, Micrometer, Prometheus, and Grafana.
- Containerized the full local stack with Docker Compose and automated verification using GitHub Actions.

Only claim features that are actually implemented.

The developer should be able to explain:

- why Redis GEO is used,
- what belongs in Redis vs PostgreSQL,
- how stale driver locations are handled,
- how candidate ranking works,
- how double assignment is prevented,
- why the chosen concurrency strategy is correct,
- what happens when a driver rejects,
- what happens when a driver times out,
- how reassignment works,
- why Kafka is used,
- why the outbox exists,
- how duplicate events are handled,
- how object-level authorization works,
- how live tracking works,
- why PostGIS exists in addition to Redis GEO,
- how the system is tested,
- how failures are observed in production.

---

# 55. Non-Goals for Now

Do not prioritize unless explicitly requested:

- full consumer frontend,
- native mobile app,
- real GPS hardware,
- real maps billing,
- perfect route optimization,
- machine-learning dispatch,
- dozens of microservices,
- multi-region deployment,
- large Kubernetes setup,
- advanced global routing,
- unrealistic "millions of requests per second" architecture.

---

# 56. Definition of Success

FleetFlow is successful if it demonstrates that the developer can build, test, explain, and operate a serious Spring backend.

It should feel like a production system rather than a tutorial application.

The project should demonstrate strong knowledge of:

```text
Java
Spring Boot
Spring MVC
Spring Security
JPA
SQL
PostgreSQL
Flyway
REST
Validation
Transactions
Concurrency
Redis
Redis GEO
Kafka
State Machines
Scheduled Jobs
PostGIS
SSE / WebSocket
Testing
Docker
CI/CD
Observability
Production Debugging
```

Most importantly:

> FleetFlow should demonstrate real logistics orchestration and backend engineering, not technology collection.

New requirements may change architecture, terminology, scope, or implementation details.

Always prefer the newest explicit developer instruction over this document.
