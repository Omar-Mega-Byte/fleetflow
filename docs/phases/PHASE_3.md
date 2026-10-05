# Phase 3 — lifecycle implementation status

**Status: IN_PROGRESS.** The full Phase 3 order and delivery lifecycle remains unfinished. Phase 4 required durable driver availability; Phase 5 required the guarded order path to `READY_FOR_PICKUP`.

## Driver prerequisite implemented

Migration `V3__driver_operational_state.sql` adds a durable `OFFLINE` default, `ONLINE`, `AVAILABLE`, and `PAUSED` state, an optimistic version, and append-only `driver_status_history`. Existing drivers migrate to `OFFLINE`. The `DRIVER` self routes `POST /api/v1/drivers/me/online`, `/available`, `/pause`, and `/offline` return `204` on guarded transitions and `409 DRIVER_STATE_CONFLICT` on invalid or ineligible transitions. `AVAILABLE` requires an active vehicle. A driver cannot choose another driver ID. The state and history write share a transaction. On commit to `PAUSED` or `OFFLINE`, Redis GEO membership is removed best effort; every nearby search still rechecks PostgreSQL.

The implemented transition set is `OFFLINE → ONLINE`, `ONLINE/PAUSED → AVAILABLE`, `ONLINE/AVAILABLE → PAUSED`, and `ONLINE/AVAILABLE/PAUSED → OFFLINE`. `RESERVED` and `DELIVERING` remain later-phase assignment states. This slice does not implement the planned order or delivery state machines, cancellation policy, or privileged overrides. Phase 3 is therefore not complete.

`DriverLifecycleTests` verifies the signed-token self path, invalid transitions, vehicle gating, suspension, durable state and history, and identity spoofing against PostgreSQL Testcontainers.

## Order prerequisite implemented for Phase 5

Migration `V4__order_ready_lifecycle.sql` expands the order status constraint and adds append-only `order_status_history`. Signed `MERCHANT` actors with current active membership in the order's exact active branch and merchant may call `POST /api/v1/merchant/orders/{id}/confirm`, `/prepare`, and `/ready`. Each action uses a conditional PostgreSQL update from its sole allowed prior status and inserts status history in one transaction. Missing or out-of-scope orders return `404`; invalid, skipped, or duplicate transitions return `409`. This supplies `CREATED → CONFIRMED → PREPARING → READY_FOR_PICKUP` for Phase 5. Cancellation and the delivery lifecycle are still outstanding, so Phase 3 remains `IN_PROGRESS`.
