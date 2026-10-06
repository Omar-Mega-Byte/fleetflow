# Phase 9 — live tracking and simulator

**Status: IN_PROGRESS (2026-10-06).** This document is the implementation and acceptance contract for the Phase 9 slice. Phase 8 is merged; tracking reads durable order/delivery state from PostgreSQL and the latest fresh driver fix from Redis. It does not use Kafka as a GPS stream.

## Objective and scope

An authenticated customer can read and follow their own order's delivery progress. The HTTP snapshot and SSE stream expose the same bounded tracking view. A local-only simulator drives the existing signed driver location endpoint for a repeatable demonstration. No public tracking link, WebSocket, GPS history table, or production simulator is in scope.

## API contract

- `GET /api/v1/orders/{id}/tracking` returns an owner-scoped snapshot with order status/version, delivery status, observation time, and an optional fresh driver position. A foreign or unknown order returns the same `404`. The response omits driver identity, vehicle, heading, speed, internal dispatch data, and coordinates until an accepted assignment has an active delivery.
- `GET /api/v1/orders/{id}/tracking/stream` requires a bearer token and ownership before opening. It emits a `snapshot` first, then `tracking` updates when an accepted driver's location changes or committed order/delivery state changes. SSE `id` values are connection-local; `Last-Event-ID` is not durable replay. Reconnect obtains a new current snapshot. Do not put bearer tokens in query strings.
- A position is present only while its GPS report, Redis receipt, and heartbeat satisfy the configured freshness limits. An expired location or Redis outage cannot produce an old coordinate. A terminal order/delivery emits its final state and closes the stream.
- The stream has a finite lifetime no longer than the token lifetime, bounded global/per-customer connections and per-client pending work, periodic heartbeat comments, cleanup on timeout/error/disconnect, and a slow-client eviction policy. A disconnect does not mutate business state.

## Data path and security

PostgreSQL owns order ownership, assignment and delivery status. Redis owns only the latest fix and heartbeat; no per-point persistence is added. A driver update is accepted by the existing signed `/api/v1/drivers/me/location` endpoint before tracking fans it out. Committed lifecycle changes are published to active in-process subscriptions only after transaction commit. Each emission rechecks the current assignment and active state, so a former driver's fix cannot follow reassignment. No coordinates appear in notifications, logs, analytics, or the durable outbox.

Only `CUSTOMER` may use these endpoints, and the customer ID comes from the authenticated actor mapping. An existing connection ends at token expiry; a reconnect runs normal token validation and ownership checks. This first slice supports one Spring process; after a restart/reconnect the snapshot recovers current truth. Horizontal fan-out is a later design decision.

## Local simulator

A local-only script takes a bearer token for an existing assigned driver, generates a short route, and calls the normal location endpoint at a bounded interval. It can pause to demonstrate stale position and reconnect. It never bypasses authorization or writes Redis directly. Credentials stay in the local environment and are not recorded in output.

After assigning a driver in the local stack, set `FLEETFLOW_DRIVER_TOKEN` in that shell and run `./scripts/Simulate-Driver-Route.ps1 -PauseSeconds 50`. The script accepts only a loopback base URL and sends generated points through the signed driver location route. Use an HTTP client that can set an `Authorization: Bearer` header for the tracking stream; a browser `EventSource` alone cannot attach that header. The pause exceeds the default 30-second heartbeat TTL, so the snapshot should hide the position until the next accepted point. Clear the token environment variable when finished.

## Acceptance gate

- [x] Snapshot and stream enforce 401/403/404 and cross-customer isolation with signed tokens.
- [x] Stream starts with a current snapshot; accepted movement arrives; reconnect starts from current truth; stale GPS/heartbeat hides the position.
- [x] Assignment change, cancellation, completion and failure cannot leak a prior driver's position; terminal state closes the stream.
- [x] Bounded connections/work, heartbeat, token expiry, timeout/error/disconnect cleanup and Redis outage behavior are tested.
- [x] Local-only simulator exercises signed movement through the normal driver API; its bounded pause models disconnect, while stale/absent location behavior is covered by integration tests.
- [ ] Real PostgreSQL and Redis integration tests, full clean Maven verify, signed local smoke, and GitHub CI pass. Record exact evidence before marking Phase 9 complete.

## Verification evidence

The Phase 9 tests use signed JWTs, real PostgreSQL 17 and Redis 7 Testcontainers for scoped snapshots, movement, reconnect, stale/failing Redis, suspended drivers, role revocation, cancellation, failure, terminal completion, heartbeat, connection limits and token expiry. A separate bounded-work test forces a stalled read, verifies only one pending task per client, and evicts the stream after the slow-work deadline.

The isolated `fleetflow-phase9-smoke` Compose project used fresh PostgreSQL, Keycloak, Redis and Kafka volumes. A Keycloak-signed customer saw the assigned driver's snapshot and an SSE movement event; another signed customer received `404`. The signed simulator produced two further locations through the normal driver API, and the owner snapshot followed those points. After pickup, transit and completion, the durable tuple was `COMPLETED|AVAILABLE|DELIVERED|DELIVERED`, and terminal tracking exposed no position. The same signed smoke passed after rebuilding the app with the final assignment/role checks. Only this isolated project's containers and volumes were removed; its temporary credential file was deleted.

The final clean Maven result and GitHub CI links are still pending. Keep Phase 9 `IN_PROGRESS` until they are recorded here.
