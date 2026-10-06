# Phase 8 — asynchronous events, outbox and idempotency

**Status: IN_PROGRESS (2026-10-06).** [Phase 7](PHASE_7.md) supplies the synchronous order, offer and delivery transitions. This phase adds asynchronous in-app notification and derived analytics after those transitions commit. The [execution roadmap](../ROADMAP.md) remains the source for the phase acceptance gate. Real broker tests, the final clean verify, and signed Compose smoke have passed; GitHub CI evidence is still pending.

## Objective and boundary

Business commands remain synchronous and PostgreSQL remains the source of order, assignment and delivery truth. A successful command commits its state, history and any corresponding outbox event in one PostgreSQL transaction. Kafka transports the committed event to notification and analytics consumers later. Broker failure may delay those derived views but must not change the command result, create an untracked event gap, or prevent accepted business writes. Publication and consumption are **at least once**; deduplication makes each consumer's database effect happen once per event.

This phase uses one versioned, order-keyed Kafka topic for meaningful order status transitions and driver offers. It does not stream GPS, replace the Phase 7 PostgreSQL scheduler, introduce a second business command path, or claim exactly-once Kafka delivery. In-app records are the notification channel. Email, SMS, push, a third-party provider, full CQRS, a schema registry, Debezium and Phase 9 tracking remain outside this slice.

## Event contract

The first topic is `fleetflow.domain-events.v1`. Use the **order UUID as the Kafka key** for both event types so events for one order reach the same partition. The JSON envelope contains `eventId` (stable UUID), `eventType`, `schemaVersion = 1`, `aggregateType = ORDER`, `aggregateId` (order UUID), `occurredAt` (UTC) and a type-specific `payload`. A consumer rejects an unknown event type or version through the explicit poison-event policy; it must not guess how to interpret a changed payload. Event IDs are generated once with the outbox row and reused on every publish retry.

| Type | Payload fields | Created with | Consumer use |
| --- | --- | --- | --- |
| `ORDER_STATUS_CHANGED` | `orderId`, `previousStatus`, `newStatus`, `orderVersion` | Every committed order status transition from the existing lifecycle, dispatch, recovery and delivery commands | Customer notification for selected resulting states; UTC daily counts by resulting state. |
| `ASSIGNMENT_OFFERED` | `orderId`, `assignmentId` | Each committed new driver offer, including a later offer from recovery | In-app notification to the offered driver's actor, resolved from the durable assignment and driver rows. |

Do not emit for a rejected/rolled-back command, a duplicate command that made no transition, GPS/heartbeat, a timer sweep that made no change, or an unrelated local method call. Each committed status transition has its own order version; do not infer a transition from the current order row after the fact. The notification consumer resolves the customer's actor from the immutable order/customer relationship and the offered driver's actor from the assignment/driver relationship. The Kafka payload contains no driver ID, name, address, coordinates, price, token, free-text incident reason or other personal detail. Notification content uses a fixed event/status template rather than copying an untrusted request body.

Customer notifications cover `CONFIRMED`, `READY_FOR_PICKUP`, `DRIVER_ASSIGNED`, `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`, `DISPATCH_FAILED` and `DELIVERY_FAILED`. The `ASSIGNMENT_OFFERED` event notifies the exact offered driver. Other status transitions still contribute to analytics without creating a notification. An offer notification is an alert, not authorization to accept: the existing offer command rechecks owner, state and deadline in PostgreSQL.

## Storage, publisher and consumers

The forward Flyway migration follows V10 and adds outbox, processed-event, notification and daily analytics tables with primary/foreign keys, check constraints, the unique `(consumer_name, event_id)` processed key and indexes for polling and recipient reads. An outbox row stores the full immutable envelope, the order key, creation time, publish state, attempt count, next-attempt time, lease and publication time. Notification uniqueness is also tied to recipient and source event. The analytics key is `(UTC day, resulting order status)` and stores a transition count. This is a **derived count of transitions**, not the authoritative number of orders currently in each state. `CREATED` intake is not an order status transition in this contract.

The publisher claims a bounded batch of due rows using PostgreSQL row locking and `SKIP LOCKED`, with a lease that another worker can reclaim after a crash. It sends to Kafka **after releasing database locks**, waits for broker acknowledgement, then marks a row published. A failed or timed-out send leaves the row retryable with a bounded backoff and visible attempt/error summary. A crash after broker acknowledgement and before the published marker may send the same event again. Later events for the same order must not overtake an earlier unpublished event; partition key alone cannot fix publisher-side reordering. A permanently failing row stays operator visible and must not make the queue grow silently.

Notification and analytics use separate Kafka consumer groups and separate processed markers. Each consumer inserts its marker and applies its notification or counter update in the **same PostgreSQL transaction**. A duplicate `(consumer_name, event_id)` leaves the derived table unchanged. The Kafka offset is acknowledged only after this transaction commits, so a crash before commit retries safely. Analytics increments use the event's UTC `occurredAt` date and `newStatus`; duplicate or out-of-order delivery cannot inflate a count. Consumers must not mutate orders, driver assignments or deliveries.

Malformed or unsupported messages must not be silently treated as processed. Retry transient database failures; after a bounded poison-message policy, make the failed event, reason and partition/offset visible to an operator, using a dead-letter topic if one is justified and implemented. Document the chosen policy and prove that a poison event does not cause silent loss or an unbounded retry loop. Published-outbox retention and processed-marker retention must preserve the supported Kafka replay window; cleanup must never remove pending events.

## API and authorization

`GET /api/v1/notifications/me?limit=20` returns the authenticated actor's newest in-app notifications, with a bounded limit and stable ordering. The SQL query must bind the resolved active actor ID; no caller-supplied recipient ID may widen scope. CUSTOMER and DRIVER roles may read their own records. A stale offer alert may remain in history, but it cannot grant access to the offer or override its current state.

`GET /api/v1/dispatch/analytics/daily?from=YYYY-MM-DD&to=YYYY-MM-DD` returns UTC daily resulting-status transition counts for a **DISPATCHER**. Use an inclusive date range of at most 31 days, validate reversed or excessive ranges, and return only aggregate counts. Missing/invalid bearer tokens return `401`; a valid actor without the role returns `403`. Test cross-actor notification isolation, including an actor with multiple roles. These are read-only derived views and may lag the committed business state.

## Reviewable slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Schema and event insertion | Forward migration, stable v1 envelope and outbox inserts inside existing command transactions | Does a committed transition/offer have exactly one durable event, and does rollback leave neither state nor event? |
| 2. Kafka and publisher | Pinned local Compose broker, topic/configuration, bounded PostgreSQL claim, acknowledgement, retry and lease recovery | Can the business command commit while Kafka is down, then drain the outbox after recovery? |
| 3. Consumers | Separate notification and analytics groups, atomic processed markers and effects | Do duplicate and crash/restart deliveries leave one notification and one count increment? |
| 4. Read and operator surface | Actor-scoped notification read, dispatcher daily report, backlog/lag metrics and failure runbook | Can an unrelated actor see no notification or private payload, and can an operator find stuck work? |
| 5. Acceptance evidence | Real PostgreSQL/Kafka Testcontainers, signed OIDC reads, fresh/upgrade migrations, local smoke and CI | Are outage, duplicate, poison, ordering and restart claims backed by observed results? |

## Verification and completion gate

Use real PostgreSQL and Kafka in Testcontainers. Test the database transaction boundary, a rejected/rolled-back command, broker unavailable at commit, failed send and recovery, two publisher workers, lease expiry after a stopped worker, broker acknowledgement followed by a simulated publisher crash, duplicate Kafka delivery to each consumer, consumer crash before offset acknowledgement, and per-order ordering across partitions/workers. Assert final tables directly. Test unknown event version and malformed payload without silent acknowledgement. Exercise authorized notification and report reads with signed OIDC tokens, including wrong role, wrong actor and invalid range. Check fresh and upgrade Flyway migrations, full Maven verify, pinned Compose configuration, a signed local lifecycle flow, and final GitHub CI on the delivered commit.

- [x] Business state/history and the corresponding outbox event commit or roll back together.
- [x] A broker outage leaves synchronous commands correct; retry drains the outbox after recovery.
- [x] Duplicate delivery, including simulated acknowledgement/offset crash windows, has one notification and one analytics effect per consumer/event.
- [x] Event envelope/version, order-key partitioning and per-order publication assumptions are verified.
- [x] Read endpoints enforce actor ownership/dispatcher role and reveal no sensitive event payload.
- [x] Backlog/lag and poison-event handling are observable; retry and retention policy are documented.
- [ ] Real broker integration tests, fresh/upgrade migrations, signed local flow and CI pass; exact test counts, commit and CI links are recorded here before changing this document and the roadmap to `COMPLETE`.

## Operator runbook

The `fleetflow.domain-events.v1` topic has three partitions, one replica in the local single-broker Compose stack, and seven-day Kafka retention. Both event types use the order UUID key. The groups are `fleetflow-notification-v1` and `fleetflow-analytics-v1`. Production needs an appropriately replicated broker and alerting before a production-readiness claim. Kafka is excluded from app readiness so its outage does not reject a synchronous business write.

The local/prod publisher is enabled by default with `OUTBOX_ENABLED=true`; tests disable it except explicit integration runs. Defaults are `OUTBOX_BATCH_SIZE=50`, `OUTBOX_SWEEP_INTERVAL_MS=5000`, `OUTBOX_LEASE_DURATION=30s`, `OUTBOX_SEND_TIMEOUT=5s`, and `OUTBOX_MAX_ATTEMPTS=20`. Retries wait 5, 10, 20, 40, 80, 160, then at most 300 seconds. After 20 failures a row becomes `FAILED` and blocks later events for the same order until an operator investigates and requeues it. A lease from a stopped publisher can be reclaimed after 30 seconds. Kafka send happens outside the database claim transaction; a broker acknowledgement before a publisher crash can cause a duplicate, which consumers deduplicate.

`OUTBOX_PUBLISHED_RETENTION=30d`, `OUTBOX_PRUNE_BATCH_SIZE=500`, and `OUTBOX_PRUNE_INTERVAL_MS=3600000` control cleanup. The application refuses a retention below 14 days. Only published rows older than the configured age **with both consumer markers** are pruned, together with those markers in one transaction. Pending, failed, and incompletely projected events remain available for investigation. Notifications and daily counts remain. Kafka retention is shorter than PostgreSQL marker retention; changing Kafka retention requires revisiting this safety margin.

The DISPATCHER-authenticated `/actuator/metrics/fleetflow.outbox.pending`, `/actuator/metrics/fleetflow.outbox.failed`, and `/actuator/metrics/fleetflow.outbox.oldest.seconds` gauges update after each publisher sweep. Pending count and oldest age rising during a broker outage are expected; the command must still commit. Check the database and each consumer group's `LAG` separately:

```sql
SELECT status, count(*), max(attempts) AS max_attempts,
       min(occurred_at) AS oldest_event
FROM outbox_events GROUP BY status ORDER BY status;

SELECT id, aggregate_id, attempts, next_attempt_at, lease_until, last_error
FROM outbox_events WHERE status IN ('PENDING','FAILED')
ORDER BY next_attempt_at, sequence LIMIT 50;

SELECT consumer_name, topic, partition_number, record_offset, error_code, quarantined_at
FROM quarantined_events ORDER BY quarantined_at DESC LIMIT 50;
```

```text
docker compose --env-file .env -p <project> exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group fleetflow-notification-v1
docker compose --env-file .env -p <project> exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group fleetflow-analytics-v1
```

Malformed, unsupported-version, and unknown events are immediately quarantined by consumer group with topic/partition/offset and a specific safe code such as `INVALID_EVENT_JSON`, `UNSUPPORTED_EVENT_ENVELOPE`, or `UNKNOWN_EVENT`; their offsets advance only after the quarantine row commits. The record can be inspected at that Kafka offset while retained. Transient database errors are retried without offset acknowledgement, so group lag remains visible and the record cannot silently disappear. Logs omit event payloads and credentials. If a row is `FAILED`, fix the broker or configuration first, then requeue only that reviewed event with `status='PENDING'`, `attempts=0`, `next_attempt_at=clock_timestamp()`, and cleared lease fields in a controlled database transaction. Do not mark an event published, erase a processed marker, or edit a business row to clear a dashboard.

An external notification provider is not part of this in-app slice; any future provider must use its own idempotency key or durable delivery/reconciliation record before claiming one external effect.

## Implementation evidence

V11 (`V11__async_events.sql`) adds the outbox, consumer markers, quarantine, notifications, and UTC daily transition counts. `LifecycleHistory` inserts status events with the command history, and `AssignmentCommit` inserts offer events in its reservation transaction. `OutboxStore`, `OutboxPublisher`, and `OutboxWorker` claim one due row per broker call, retain stable IDs, retry, expose gauges, and prune only fully projected old published rows. `EventListeners` and `EventProjector` use separate consumer groups and atomic PostgreSQL markers. `EventReadController` exposes signed actor-scoped notifications and dispatcher-only daily counts. The local Compose broker is pinned, and `Run-Local.ps1` accepts its configuration.

The final `./mvnw.cmd clean verify --no-transfer-progress` passed **108 tests, 0 failures, 0 errors, 0 skipped** on 2026-10-06, including **nine real PostgreSQL/Kafka Phase 8 tests**. They cover command rollback/rejection, broker send failure/recovery, two worker claims and lease reclamation, publisher acknowledgement before its marker, duplicate and poison delivery, atomic consumer rollback, retention, ordered version drain, signed read scope, and protected metrics. Fresh V11 and V1/V2 upgrade migration tests passed. `docker compose --env-file .env.example config --quiet`, PowerShell script parsing, and `git diff --check` passed.

The isolated `fleetflow-phase8-smoke` Compose project used a fresh PostgreSQL/Kafka/Keycloak volume set and real Keycloak-signed customer, merchant, dispatcher, and driver tokens. The flow reached `COMPLETED|AVAILABLE|DELIVERED|DELIVERED`; customer and driver saw their own notifications, the dispatcher saw UTC analytics and the secured backlog metric, and a customer was denied the dispatcher report. With Kafka stopped, a new merchant confirmation returned success and left `CONFIRMED|PENDING` in PostgreSQL. After Kafka restarted, that event became `PUBLISHED` with both processed markers. Final direct queries found **19 published outbox rows, 19 notification markers, 19 analytics markers, 15 notification rows, zero quarantined rows**, and no pending/failed rows. Both consumer groups had **zero lag on the two nonempty partitions**; the third partition was empty. The broker described three partitions, one local replica, and `retention.ms=604800000`. The isolated containers and only their new volumes were removed afterward; the existing `fleetflow` project was untouched.

The final gate remains open for a delivered commit and GitHub CI. The local `gh` credential currently reports an invalid token, so remote delivery and CI cannot be claimed yet. Keep this phase and the roadmap `IN_PROGRESS` until that evidence exists.
