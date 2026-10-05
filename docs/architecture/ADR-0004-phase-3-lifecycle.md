# ADR-0004: Phase 3 lifecycle commands

Status: accepted for Phase 3, 2026-10-05.

- Order cancellation: the owning CUSTOMER can cancel CREATED or CONFIRMED; exact active branch MERCHANT staff can cancel CREATED, CONFIRMED, PREPARING or READY_FOR_PICKUP. Every cancellation requires a trimmed reason of 1–500 characters. CANCELLED is terminal.
- A duplicate or otherwise invalid state command returns `409 LIFECYCLE_CONFLICT`. The first committed transition wins; no retry is reported as success and no second history row is inserted.
- The coordinator uses PostgreSQL row locks (`SELECT ... FOR UPDATE`) inside one transaction, then checks the current state and inserts one history event. Monotonic aggregate version starts at 0 for creation/backfill and increments once per transition. The version is returned by command responses. Row locks serialize competing requests; an invalid command does not increment it.
- Lifecycle routes are registered in default, local, test and prod. Local/test retain loopback-only fixture administration and client-priced order creation; default/prod expose neither. All lifecycle routes require a validated bearer token, local role, and current object scope.
- A driver may become AVAILABLE only with an active vehicle with positive capacity; Phase 4 must additionally require a fresh heartbeat/location before dispatch. Driver `active` and `suspended` remain separate guards. This phase has no administrative flag changes.
- Histories record aggregate ID, prior and new states, action, server `Clock` time, actor ID for real commands, explicit `SYSTEM` source for legacy backfill, optional reason, request correlation ID, and aggregate version. SQL backfill records only a baseline at migration time. Application code inserts but never updates or deletes events; a database trigger rejects updates/deletes.
- The validated `X-Correlation-ID` from the request filter is saved for HTTP commands. For a command without an HTTP request, a generated UUID is used. Neither the body nor an unvalidated header selects the stored value.
- Delivery is a nullable order-associated persistence skeleton. The dispatch workflow in Phase 5 may create PENDING with initial history; Phase 3 creates no delivery rows and exposes no delivery commands.
- Customer/merchant/driver history read APIs are deferred until a bounded, audience-specific view is needed. No unbounded history is exposed.
