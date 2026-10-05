# Phase 3 — Explicit order, driver, and delivery lifecycles

**Status: NOT_STARTED.** This is the implementation and acceptance plan, not evidence that lifecycle commands exist. [Phase 1](PHASE_1.md) supplies the core model and [Phase 2](PHASE_2.md) supplies OIDC and object authorization. The [execution roadmap](../ROADMAP.md) controls phase order; [PROJECT_CONTEXT.md](../../PROJECT_CONTEXT.md) uses older numbering. The broader [state plan](../architecture/STATE_MACHINES.md) includes later dispatch and fulfillment transitions; this document defines the Phase 3 slice.

## Objective and boundary

Replace arbitrary status changes with named, guarded commands that record who changed what and when. Every accepted transition updates the current state and appends immutable history in one PostgreSQL transaction. An invalid, unauthorized, duplicate, or racing command must leave both unchanged.

Phase 3 ends with an order ready for pickup and a driver expressing durable availability. It does not offer or assign a driver, start dispatch, require Redis location, accept pickup, or complete delivery. Those commands belong to Phases 4–7. A delivery state machine is specified and its persistence skeleton is prepared, but no delivery instance or delivery transition API is created before a dispatch workflow owns it. This prevents an empty delivery record from implying an assignment.

Existing `POST /api/v1/orders` accepts client-submitted prices and remains local/test on loopback. Lifecycle commands must not make that intake route public or claim that pricing is trusted. Decide and test the registered profiles for each new route explicitly; preserve the Phase 2 fail-closed authentication and object rules.

## Verified starting point

- `V1__core_model.sql` constrains `orders.status` to `CREATED`; there are no order history or version fields. Order creation persists a snapshot but no lifecycle event. Customer reads are owner scoped and merchant order summaries are branch scoped.
- `drivers` has `active` and `suspended` flags but no operational status or history. `GET /api/v1/drivers/me` returns the authenticated driver's own minimal flags. A vehicle may be attached to a driver.
- There is no delivery table or delivery endpoint. Applied V1 and V2 migrations must remain immutable. Flyway owns schema and Hibernate validates it.
- Phase 2 maps verified OIDC `(iss, sub)` to a local actor and checks active actor, role, customer ownership, exact active merchant branch membership, and driver self scope. Fixture administration and order intake remain local/test only.

## Phase 3 transition contract

The tables are proposed decisions to freeze in a short ADR or implementation PR before coding. Commands use server time from the injected `Clock`; request bodies never supply target status, actor ID, or transition time. A reason is trimmed, bounded, and required for cancellation or any privileged override. A successful command returns the resulting representation and version; the implementation must document whether an exact repeat returns a stable conflict or an idempotent replay. It must never append duplicate history.

### Order

| Current → next | Command and actor | Guard and durable effect |
| --- | --- | --- |
| none → `CREATED` | Existing customer intake, local/test only | Save order/items/snapshots and initial history together; derive customer from the authenticated actor. |
| `CREATED` → `CONFIRMED` | Confirm, `MERCHANT` staff | Active membership of the order's exact branch; save confirmation time and history. |
| `CONFIRMED` → `PREPARING` | Start preparation, `MERCHANT` staff | Same branch; save preparation time and history. This is the customer cancellation cutoff. |
| `PREPARING` → `READY_FOR_PICKUP` | Mark ready, `MERCHANT` staff | Same branch; save ready time and history. No dispatch starts here. |
| `CREATED`/`CONFIRMED` → `CANCELLED` | Cancel, owning `CUSTOMER` | Current owner, pre-preparation state, required reason and history. |
| `CREATED`/`CONFIRMED`/`PREPARING`/`READY_FOR_PICKUP` → `CANCELLED` | Cancel, exact-branch `MERCHANT` staff | Required reason; no offer or assignment exists in this phase. Revisit later-phase cancellation as one coordinated workflow. |

`CANCELLED` is terminal. No skipped or backward transition is allowed. `READY_FOR_PICKUP` is the Phase 3 handoff state; `DISPATCHING`, assignment, pickup, transit, delivery success, and failure remain future commands. An authorized customer who loses a cancellation race to preparation receives a conflict, not a success with stale state. No generic `PATCH status` endpoint exists.

### Driver

Durable operational state is separate from `active`, `suspended`, and the Phase 4 heartbeat/location. Initial state is `OFFLINE`. A persisted `AVAILABLE` value expresses willingness only; it never proves fresh location or dispatch eligibility. Suspension remains an orthogonal existing flag rather than a competing state value.

| Current → next | Command and actor | Guard and durable effect |
| --- | --- | --- |
| none → `OFFLINE` | Local/test driver fixture creation | Save profile and initial history together. |
| `OFFLINE` → `ONLINE` | Go online, owning `DRIVER` | Active actor and driver, not suspended; append history. |
| `ONLINE` → `AVAILABLE` | Become available, owning `DRIVER` | Active, unsuspended driver with an active suitable vehicle; append history. Phase 4 adds fresh heartbeat/location to dispatch eligibility. |
| `ONLINE`/`AVAILABLE` → `PAUSED` | Pause, owning `DRIVER` | Active, unsuspended driver; append history and make unavailable to future matching. |
| `PAUSED` → `AVAILABLE` | Resume, owning `DRIVER` | Recheck active actor/driver, suspension, and vehicle; append history. Fresh location remains a separate dispatch guard. |
| `ONLINE`/`AVAILABLE`/`PAUSED` → `OFFLINE` | Go offline, owning `DRIVER` | Append history and make unavailable. Later phases must forbid this while reserved or delivering. |

`OFFLINE → AVAILABLE`, commands from a suspended driver, and self-actions against another driver's ID are rejected. An administrator changing `active` or `suspended` is a separate controlled policy requiring reason and audit; do not smuggle it through a driver status setter. If a suspension occurs while operational state is `AVAILABLE`, matching must still reject the driver based on the current suspension flag.

### Delivery skeleton and later transitions

Define a delivery aggregate owned by one order and an append-only delivery history. Phase 3 adds its table, status constraint, order association, and history table, but writes no delivery rows and exposes no delivery command. When Phase 5 starts dispatch, its transaction may create `PENDING`; Phase 6 acceptance changes it to `ASSIGNED`; Phase 7 pickup/outcome commands follow. The exact attempt/reassignment shape is frozen with Phases 6–7 before those commands are added.

| Later transition | Required cross-aggregate rule |
| --- | --- |
| none → `PENDING` | Dispatch workflow begins for an eligible ready order; initial history is written. |
| `PENDING` → `ASSIGNED` | Valid offer acceptance atomically changes order, driver, assignment, delivery, and histories. |
| `ASSIGNED` → `PICKED_UP` → `IN_TRANSIT` → `DELIVERED` | Only the assigned driver; order and delivery states agree in each commit. |
| `PENDING`/`ASSIGNED` → `CANCELLED`; picked-up delivery → `FAILED` | Controlled cancellation or incident with reason, terminal history, and explicit driver release/recovery. |

The delivery skeleton must not pretend that these later transitions were implemented or tested in Phase 3. In particular, `READY_FOR_PICKUP` alone creates no delivery or assigned driver.

## Persistence and transaction design

Use one forward-only migration after V2. Expand the order status check to the Phase 3 states, add a constrained driver operational status, and add nonnegative version fields for order and driver. Add transition timestamps where they answer a query or invariant. Add delivery status/checks, an order association, and history without making a delivery row mandatory for each order. Avoid constraints that force future dispatch implementation choices before assignment and reassignment are designed.

Order, driver, and delivery history should include aggregate ID, prior state (nullable only for creation/backfill), new state, server timestamp, actor ID or explicit system source, action, reason when required, correlation ID, and a monotonic per-aggregate order (version or sequence). Index history by aggregate and sequence/time for bounded reads. Do not allow application updates or deletes to history. A data migration for existing `CREATED` orders and existing drivers must add honest baseline events marked `LEGACY_BACKFILL`/system; it must not invent the human who created an old row or claim transitions that were never recorded. New creations record their real actor and time in the same transaction.

Place object authorization and transition guards in an application service, then lock or compare the current database version before changing state. The state update and exactly one history insert share a transaction. A competing update, repeated command, or history insert failure rolls back the whole command. Choose optimistic version checks or row locks and prove their behavior with real PostgreSQL concurrency tests. Never infer success from a stale entity, and never write history before knowing which transition won. Keep histories append-only even when a later corrective transition occurs.

## HTTP and authorization contract

Use action routes such as `POST /api/v1/merchant-branches/{branchId}/orders/{orderId}/confirm`, `/start-preparation`, `/ready-for-pickup`, and `/cancel`; `POST /api/v1/orders/{id}/cancel` for the owning customer; and `POST /api/v1/drivers/me/online`, `/available`, `/pause`, `/offline` for the driver. The branch path is checked against both the order's branch and current active staff membership. Driver commands derive the driver from the principal, never a path/body driver ID. Expose bounded, owner/branch-scoped order history reads and driver self history only if the response needs them; in either case avoid unbounded history lists and sensitive actor identifiers in customer views. Document the exact method/path/profile and response schema in OpenAPI when implemented.

| Situation | Response and rule |
| --- | --- |
| Missing or invalid bearer token | `401 UNAUTHENTICATED`; no command reaches the service. |
| Valid actor without the required role, inactive actor, or suspended driver command | `403` with a safe stable authorization code. |
| Another customer's order, nonmember branch, or absent scoped resource | `404 RESOURCE_NOT_FOUND`; do not reveal another tenant's object. |
| Malformed UUID, missing/blank/oversized reason, or invalid request shape | `400 INVALID_REQUEST`; no mutation. |
| Wrong current state, duplicate action, stale version, or lost cancellation race | `409` with a stable lifecycle conflict code and safe message; no extra history. |
| Database failure | No false success; rollback state and history together, with the established safe error contract. |

Do not add a broad SUPPORT/DISPATCHER/ADMIN override as a shortcut. If a privileged exception is necessary, define its exact action, object scope, mandatory reason, audit event, and tests before exposing it. Keep default/prod profile registration explicit; local/test fixture routes and the untrusted-price order intake must remain unavailable there.

## Implementation sequence and verification

1. Freeze the cancellation policy, route/profile matrix, duplicate-command semantics, vehicle requirement, history shape, and delivery creation point in an ADR or implementation PR.
2. Add the forward migration and model mapping. Prove fresh and V1/V2-upgrade migrations on PostgreSQL, including honest baseline history, constraints, indexes, and Hibernate validation.
3. Add pure transition rules and the transactional application coordinator. Wire scoped order and driver commands without generic status setters. Update existing bounded order filters and responses for the new states.
4. Add HTTP authorization, safe error bodies, OpenAPI descriptions, and bounded history reads as needed. Preserve Phase 2 OIDC and route-profile guarantees.
5. Prove races and rollback on real PostgreSQL, run the Maven wrapper verification and CI, then record exact evidence before changing this document and the roadmap to `COMPLETE`.

| Test level | Required evidence |
| --- | --- |
| State rules | Each allowed edge and every skipped, backward, terminal, and wrong-actor edge; driver active/suspended/vehicle guards. |
| PostgreSQL transactions | State and one history event commit together; invalid input, failed insert, and lost version race leave no partial write; baseline backfill and constraints work on upgraded data. |
| Concurrency | Customer cancellation versus preparation, duplicate merchant command, and simultaneous driver actions have one winner and a deterministic conflict/duplicate policy. |
| Security | Signed-token customer ownership, exact active branch membership, driver self scope, revoked membership, inactive actor, suspended driver, and 401/403/404/409 responses. |
| Profiles/API | New route inventory and OpenAPI match actual handlers; default/prod have no fixture administration or client-priced intake; history lists are bounded and scoped. |
| Delivery boundary | No delivery row or assignment appears during intake, confirmation, preparation, ready, cancellation, or driver availability commands. |

## Acceptance gate

Keep Phase 3 `NOT_STARTED` until implementation begins; mark it `COMPLETE` only with recorded evidence for all items:

- [ ] Each implemented order and driver transition has an authorized actor, state guard, durable event, and stable HTTP contract.
- [ ] Current state and history commit atomically; invalid, duplicate, unauthorized, and racing commands leave no partial write.
- [ ] Customer cutoff, merchant branch membership, driver self scope, suspension, and vehicle guards pass real PostgreSQL and security tests.
- [ ] V3-style forward migration, existing-row baseline, fresh/upgrade paths, status checks, indexes, and schema validation pass.
- [ ] Delivery persistence and transition contract are prepared without claiming assignment or fulfillment functionality.
- [ ] Bounded reads, route profiles, OpenAPI, full Maven verification, and CI pass; exact counts and any environment limitations are recorded.

## Learning and review questions

Explain why a named transition is safer than a status setter; how one transaction prevents a status/history split; how a lost cancellation race is detected; why an `AVAILABLE` driver still needs a fresh Phase 4 heartbeat and location; why suspension is checked separately; and why an order ready for pickup does not yet imply a delivery or assignment.