# FleetFlow state machines

Planned transitions only. API commands must check actor, current state, ownership, version/deadline and side effects in a database transaction. A stable conflict error is returned for invalid transitions. Administrative overrides are explicit, reasoned actions with an audit record, not direct status setters. Transition rules and cancellation cutoffs are defaults to confirm in Phase 3.

## Order

Normal path: `CREATED → CONFIRMED → PREPARING → READY_FOR_PICKUP → DISPATCHING → DRIVER_ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED`.

| From → to | Actor/trigger | Required guard and side effects |
| --- | --- | --- |
| none → CREATED | Customer | Authorized customer; active branch; valid address/items; save order, snapshots and history |
| CREATED → CONFIRMED | Merchant branch staff | Same branch, active order; record confirmation |
| CONFIRMED → PREPARING | Merchant branch staff | Same branch; record start time |
| PREPARING → READY_FOR_PICKUP | Merchant branch staff | Same branch; record ready time |
| READY_FOR_PICKUP → DISPATCHING | Dispatch service or dispatcher | One dispatch workflow claim; persist start and attempt history |
| DISPATCHING → DRIVER_ASSIGNED | Offered driver accepts | Offer still valid; atomic acceptance, driver and delivery change |
| DRIVER_ASSIGNED → PICKED_UP | Assigned driver | Correct order/assignment; delivery pickup in same transaction |
| PICKED_UP → IN_TRANSIT | Assigned driver | Pickup exists; transit start/history in same transaction |
| IN_TRANSIT → DELIVERED | Assigned driver | Delivery proof or completion fields required; mark delivery and driver available atomically |
| CREATED/CONFIRMED → CANCELLED | Customer or merchant staff | Customer cutoff before preparation; merchant allowed before pickup per policy; record actor/reason |
| PREPARING/READY_FOR_PICKUP/DISPATCHING → CANCELLED | Merchant staff or dispatcher | Explicit reason; cancel active offer and release driver if reserved in one transaction |
| DRIVER_ASSIGNED → CANCELLED | Dispatcher or merchant staff under pre-pickup policy | Explicit reason; cancel accepted assignment/delivery and release driver in one transaction; inform affected parties |
| DISPATCHING → DISPATCH_FAILED | Dispatch worker | Bounded candidate/retry policy exhausted; operator visible and order remains recoverable by explicit action |
| DRIVER_ASSIGNED → DISPATCHING | Dispatcher or pre-pickup incident worker | Cancel current assignment, release driver, preserve attempt and reason; never silently after pickup |
| PICKED_UP/IN_TRANSIT → DELIVERY_FAILED | Assigned driver or dispatcher incident action | Failure reason required; delivery outcome and operator visibility |
| DISPATCH_FAILED → DISPATCHING/CANCELLED | Dispatcher | Explicit recovery or cancellation reason and audit; no automatic infinite retry |

`DISPATCH_FAILED` is proposed as a recoverable operational state missing from the source's example. `CANCELLED`, `DELIVERED` and `DELIVERY_FAILED` are terminal for ordinary commands. Customer cancellation after confirmation, and merchant cancellation after assignment, are policy decisions; the safe early default above permits customer cancellation only before preparation and requires controlled handling of any reserved or assigned driver. Reject `DELIVERED → PREPARING`, `CANCELLED → DISPATCHING` and skipped states. No order is assigned when an offer is merely created; assignment occurs on acceptance.

## Driver

`OFFLINE → ONLINE → AVAILABLE → RESERVED → DELIVERING → AVAILABLE` is the default. `ONLINE` means authenticated/operational but not yet eligible; a fresh heartbeat and usable location are additionally required for dispatch. Driver durable status and live heartbeat are separate facts.

| From → to | Actor/trigger | Guard and side effects |
| --- | --- | --- |
| OFFLINE → ONLINE | Driver | Active, not suspended; status history |
| ONLINE → AVAILABLE | Driver | Active vehicle and required capability; status history; live eligibility still needs heartbeat/location |
| AVAILABLE → RESERVED | Dispatch transaction | Conditional DB update and offer creation commit together |
| RESERVED → DELIVERING | Driver accepts offer | Valid offered assignment; delivery and order assigned in same transaction |
| RESERVED → AVAILABLE | Reject, timeout, cancellation | Only if no accepted delivery; finalize offer, release conditionally |
| DELIVERING → AVAILABLE | Delivery completion | Current delivery terminal and driver remains active; otherwise OFFLINE/PAUSED as explicitly chosen |
| AVAILABLE/ONLINE → PAUSED | Driver | No active offer/delivery; remove dispatch eligibility |
| PAUSED → AVAILABLE | Driver | Active, vehicle valid, fresh heartbeat/location before dispatch |
| AVAILABLE/ONLINE/PAUSED → OFFLINE | Driver or liveness policy | No active assignment; going offline while reserved/delivering creates incident, not silent release |
| Any nonterminal → SUSPENDED | Admin | Reason and audit; active assignment requires dispatcher incident handling |
| SUSPENDED → OFFLINE | Admin | Reinstatement reason/audit; driver must explicitly come online |

`RESERVED` and `DELIVERING` cannot self-pause/offline to evade an assignment. A controlled pre-pickup cancellation may release a `DELIVERING` driver only after the accepted assignment and delivery are cancelled in the same transaction. Heartbeat expiry makes the driver ineligible immediately even if durable status still says AVAILABLE; reconciliation may change status later with a recorded reason. Reject `OFFLINE → RESERVED`, `PAUSED → RESERVED`, `SUSPENDED → AVAILABLE`, and a second `RESERVED` transition.

## Assignment offer

`OFFERED → ACCEPTED | REJECTED | TIMED_OUT | CANCELLED`. `FAILED` is reserved for an explicit operational incident after acceptance, not a generic catch-all status.

| From → to | Actor/trigger | Guard and side effects |
| --- | --- | --- |
| none → OFFERED | Dispatch transaction | Driver reservation won; order still dispatching; unique active offer per order and driver; deadline saved |
| OFFERED → ACCEPTED | Offered driver | Actor is exact driver, server time before deadline, no cancellation; atomic driver/order/delivery update |
| OFFERED → REJECTED | Offered driver | Exact owner and still live; record reason if provided, release driver, queue next candidate |
| OFFERED → TIMED_OUT | Scheduled worker | Server time at/after deadline; conditional status update wins over acceptance; release and retry |
| OFFERED → CANCELLED | Cancellation/dispatcher | Reason and audit, conditional release; no new offer on cancelled order |
| ACCEPTED → FAILED | Dispatcher incident workflow | Pre-pickup only in first version; invalidate assignment, return order to dispatching, record reason |

Only one terminal transition can win. A late accept, duplicate accept, or accept by another driver fails without extra assignment or delivery. The first version should choose a documented duplicate-command policy: either idempotent success for the exact same accepted offer or a stable conflict; it must be tested. Rejection and timeout retain immutable dispatch attempt history.

## Delivery

Separate delivery state is useful for driver responsibility and failure reason, while order status remains the customer-facing summary. Proposed path: `PENDING → ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED`; alternate `IN_TRANSIT → FAILED`, and `PENDING/ASSIGNED → CANCELLED` for pre-pickup cancellation/reassignment. A delivery record can be created when dispatch starts; an accepted assignment sets `ASSIGNED`.

| From → to | Actor/trigger | Guard and side effects |
| --- | --- | --- |
| none → PENDING | Order ready/dispatch starts | One current delivery per order; durable creation |
| PENDING → ASSIGNED | Offered driver accepts | Assignment acceptance commits with order/driver state |
| ASSIGNED → PICKED_UP | Assigned driver | Record pickup time and order status |
| PICKED_UP → IN_TRANSIT | Assigned driver | Record transit time and order status |
| IN_TRANSIT → DELIVERED | Assigned driver | Record completion and order status; release driver |
| PICKED_UP/IN_TRANSIT → FAILED | Assigned driver/dispatcher | Required reason, incident data and order failure; driver release is explicit |
| PENDING/ASSIGNED → CANCELLED | Order cancellation or pre-pickup reassignment | Cancel offer/assignment and release driver as applicable |

If pre-pickup reassignment follows an accepted assignment, prefer a new delivery attempt or explicit replacement record rather than rewriting the prior driver. Decide the exact schema in Phase 6–7. Reject delivery completion by unassigned driver, pickup after cancellation, and ordinary changes after terminal outcome. All cross-aggregate transition writes and history inserts are transactional.

## Cross-machine consistency gate

At all commit points: `assignment=OFFERED` implies `driver=RESERVED` and `order=DISPATCHING`; `assignment=ACCEPTED` implies `driver=DELIVERING`, `delivery=ASSIGNED` and `order=DRIVER_ASSIGNED`; pickup/transit/delivery outcomes must agree between Order and Delivery. A reconciliation query should detect violations after failures or manual intervention; tests verify these tuples under races. A Redis key, SSE event or Kafka message never authorizes a transition by itself.
