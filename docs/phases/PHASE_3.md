# Phase 3 — lifecycle implementation status

**Status: IN_PROGRESS.** The full Phase 3 order and delivery lifecycle remains unfinished. Phase 4 requires durable driver availability, so the planned driver slice was implemented first.

## Driver prerequisite implemented

Migration `V3__driver_operational_state.sql` adds a durable `OFFLINE` default, `ONLINE`, `AVAILABLE`, and `PAUSED` state, an optimistic version, and append-only `driver_status_history`. Existing drivers migrate to `OFFLINE`. The `DRIVER` self routes `POST /api/v1/drivers/me/online`, `/available`, `/pause`, and `/offline` return `204` on guarded transitions and `409 DRIVER_STATE_CONFLICT` on invalid or ineligible transitions. `AVAILABLE` requires an active vehicle. A driver cannot choose another driver ID. The state and history write share a transaction. On commit to `PAUSED` or `OFFLINE`, Redis GEO membership is removed best effort; every nearby search still rechecks PostgreSQL.

The implemented transition set is `OFFLINE → ONLINE`, `ONLINE/PAUSED → AVAILABLE`, `ONLINE/AVAILABLE → PAUSED`, and `ONLINE/AVAILABLE/PAUSED → OFFLINE`. `RESERVED` and `DELIVERING` remain later-phase assignment states. This slice does not implement the planned order or delivery state machines, cancellation policy, or privileged overrides. Phase 3 is therefore not complete.

`DriverLifecycleTests` verifies the signed-token self path, invalid transitions, vehicle gating, suspension, durable state and history, and identity spoofing against PostgreSQL Testcontainers.
