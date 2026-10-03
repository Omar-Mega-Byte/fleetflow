# FleetFlow domain model plan

Conceptual model only; no table or Java class is specified here. Ownership and lifecycle take precedence over column lists. [State machines](STATE_MACHINES.md) define transitions. The initial ERD should be revised when Phase 1 validates actual query patterns.

## Identity and commercial side

| Concept (owner) | Responsibility, key data, relationships | Lifecycle, durability and invariants |
| --- | --- | --- |
| User / Actor (`identity`) | External OIDC subject, active flag, roles, mappings to customer, driver or merchant staff; no stored password if using external issuer | Durable. Unique `(issuer, subject)`; deactivation blocks commands; roles do not replace object ownership |
| Customer (`customer`) | Profile and contact reference; one actor may represent one customer in v1, customer owns many orders | Durable. Orders retain customer ID even if profile changes |
| Merchant (`merchant`) | Organization name, active flag; owns branches and staff memberships | Durable. Deactivation blocks new orders without erasing history |
| MerchantBranch (`merchant`) | Pickup address and WGS84 point, merchant ID, operating status, optional hours/capacity and later service zone | Durable. One merchant has many branches; an order binds one branch, never re-parents casually |
| MerchantStaffMembership (`merchant`/`identity`) | Actor-to-merchant/branch scope and permission, needed for object authorization | Durable. A role `MERCHANT` alone grants no arbitrary branch access |
| Address / Coordinates (`order` value object) | Snapshot delivery address plus validated latitude/longitude; branch pickup point also uses coordinates | Order address snapshot durable. Latitude [-90,90], longitude [-180,180], finite numbers; WGS84/SRID 4326; define boundary semantics in zone phase |

## Logistics side

| Concept (owner) | Responsibility, key data, relationships | Lifecycle, durability and invariants |
| --- | --- | --- |
| Driver (`driver`) | Actor ID, active/suspended flag, durable operational status, vehicle ID, capability/capacity attributes | Durable. May be eligible only if active, available, compatible and live; suspension overrides status |
| Vehicle (`driver`) | Type, capacity and active state, driver relationship | Durable. One active vehicle per driver in v1; historical assignment keeps chosen vehicle snapshot/ID |
| DriverStatusHistory (`driver`) | Prior/current state, actor, time, reason | Durable append-only evidence for important transitions |
| DriverLocation (`driver` operational projection) | Latest point, reported/received times, heading/speed and heartbeat | Ephemeral Redis. Old/out-of-order updates cannot replace newer location; no promise of GPS history |
| Order (`order` aggregate root) | Customer ID, branch ID, delivery address snapshot, item/price snapshot, status, timestamps and version | Durable. One branch and customer; transitions explicit; terminal states cannot return to preparation |
| OrderItem (`order` child) | Item description/reference, quantity and monetary snapshot | Durable as part of order; positive quantity; monetary values with currency and decimal precision; no payment flow implied |
| OrderStatusHistory (`order`) | Status change actor, time, reason and correlation | Durable append-only; recorded in transition transaction |
| Delivery (`delivery` aggregate root) | One order, assigned driver/vehicle reference, pickup and outcome times, failure reason | Durable. At most one current delivery per order; accepted assignment precedes pickup; terminal success/failure exclusive |
| DispatchAttempt (`dispatch`) | Order, candidate driver, sequence, score/eligibility snapshot, reason and outcome | Durable append-only attempt record; rejected/timed-out attempts remain available |
| DriverAssignment (`dispatch`) | Offer to exactly one driver for one order/delivery, deadline, status, acceptance time | Durable. At most one active offer for an order and driver; only offered driver accepts before deadline; accepted assignment is not silently replaced |
| DispatchPlan/score (`dispatch` value object) | Search radius, normalized factors, deterministic tie-break and candidate explanation | Computed. Weight sum/config validated; scoring cannot override hard eligibility |
| DeliveryZone (`merchant` or dedicated zone owner when needed) | Merchant/branch service polygon, validity and policy | Durable PostGIS from Phase 10. Version/activation control; point-in-polygon with defined boundary and tolerance |

## Supporting records

| Concept (owner) | Responsibility, key data, relationships | Lifecycle, durability and invariants |
| --- | --- | --- |
| Notification (`notification`) | Intended recipient/channel/template/event ID, status, attempts and provider receipt | Durable from asynchronous phase; retries cannot send duplicate external effects without idempotency/reconciliation |
| AuditLog (`audit`) | Actor/role, action, resource, time, reason, old/new summary, correlation ID | Durable append-only, especially privileged overrides; do not store secrets or raw GPS unnecessarily |
| OutboxEvent (`order`/`dispatch`/`delivery` writes, infrastructure owns publisher) | Stable event ID/type/version, aggregate ID, payload, occurrence time and publish state | Durable insert in same transaction as domain change; published flag can lag/repeat |
| ProcessedEvent (`notification`/`analytics` consumer) | `(consumer, event ID)` and processed time | Durable unique key; consumer side effect and marker are atomic when in same DB |
| AnalyticsAggregate (`analytics`) | Derived counters/durations by day or zone | Durable but rebuildable from retained event/business data; never source of order truth |
| TrackingSession (`tracking`) | Stream connection/authorization context | Ephemeral; each reconnect rechecks ownership and receives current snapshot |

`Role` and `Permission` may be configuration or persistent mappings, not necessarily separate tables. Avoid entities for every noun. Exact prices, cancellation economics, return logistics, vehicle capabilities, zone ownership and retention periods are phase decisions, not assumed facts.

## Aggregate and reference rules

- Order owns items and order status history. Delivery owns outcome; dispatch owns attempts and offers; driver owns availability. Each has one writer module.
- Commands pass IDs and immutable read models across modules. Do not expose or mutate another aggregate's JPA collection to cause a side effect.
- A business transition that changes two aggregates (for example accepting an offer and marking order assigned) is coordinated by an application service within one PostgreSQL transaction, with explicit checks and constraints.
- `Order.status` can summarize delivery progress but must not be an independently writable duplicate. The transition coordinator updates both order and delivery consistently or derives presentation state from delivery where feasible.
- Preserve historical branch, address, item and vehicle information needed to explain a completed order. Profile edits must not rewrite past truth.
- Keep current GPS and heartbeat out of the durable ERD. If later privacy/operations needs require sampled traces, define retention and authorization before storage.

## Initial relationship sketch

```text
User/Actor ── Customer ──< Order >── MerchantBranch >── Merchant
     ├────── Driver ────── Vehicle         │
     └────── MerchantStaffMembership ──────┘
                      Order ──< OrderItem
                        │
                        ├──── Delivery
                        ├────< DispatchAttempt
                        └────< DriverAssignment >── Driver

Domain transition ──> StatusHistory / AuditLog
Committed event ────> OutboxEvent ──> ProcessedEvent / Notification / AnalyticsAggregate
MerchantBranch ─────> DeliveryZone (Phase 10)
```
