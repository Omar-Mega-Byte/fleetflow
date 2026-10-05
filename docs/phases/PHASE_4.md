# Phase 4 — live driver location and Redis GEO

**Status: IN_PROGRESS.** The implementation is being verified against this acceptance gate. [ROADMAP.md](../ROADMAP.md) controls delivery order. The Phase 3 driver prerequisite is implemented in [PHASE_3.md](PHASE_3.md); the remaining order and delivery lifecycle is still planned. The [architecture plan](../architecture/ARCHITECTURE_PLAN.md), [state machines](../architecture/STATE_MACHINES.md), and [product context](../../PROJECT_CONTEXT.md) provide the broader rules.

## Objective and boundary

Let an authenticated driver report a current position and stay live through short-lived heartbeats. Store only the latest operational GPS fix in Redis, index it for nearby search, and return candidates to internal dispatch code only after checking location age, heartbeat age, and durable driver eligibility. A GEO hit is a search hint, never proof that a driver can be offered an order.

The end-to-end demonstration is: a driver reaches Phase 3 `AVAILABLE` → sends a valid location → appears in an internal nearby query → stops heartbeating and disappears from eligible results even while its GEO member remains → resumes with a fresh valid update and becomes eligible again. A `PAUSED`, `OFFLINE`, inactive, or suspended driver must be excluded despite a recent Redis position.

Phase 4 does not create dispatch attempts, rank candidates, reserve drivers, offer assignments, expose anyone's position to customers, persist GPS history, or add PostGIS/Kafka/SSE. Phase 5 consumes the internal candidate lookup and performs matching; Phase 6 rechecks eligibility inside the durable reservation workflow.

## Verified starting point and prerequisite

- Phase 2 exposes authenticated `GET /api/v1/drivers/me`, resolving the `DRIVER` role and exact `drivers.actor_id` mapping. The driver record has `active` and `suspended` flags. Local/test fixture driver and vehicle routes require `ADMIN` and loopback; public order creation is still restricted because pricing is client supplied.
- At implementation start, the driver state prerequisite was absent. Migration V3 and guarded self actions now provide `OFFLINE`, `ONLINE`, `AVAILABLE`, and `PAUSED` with append-only history. `DriverLifecycleTests` proves the signed-token path against PostgreSQL Testcontainers. The full Phase 3 lifecycle remains in progress.
- Redis is now a separate service in one local Compose project alongside the app, PostgreSQL, and Keycloak. No driver availability is synthesized from flags or Redis alone.
- Reuse the verified `(issuer, subject)` → local actor → self driver path. A driver ID in a request body, path, or token claim is never the authority for a location write.

## Contract to settle at implementation start

| Decision | Proposed Phase 4 contract | Required proof |
| --- | --- | --- |
| HTTP write | `POST /api/v1/drivers/me/location` accepts latitude, longitude, optional heading/speed, and a client-reported UTC timestamp. Response is `204` for an accepted update. A separate `POST /api/v1/drivers/me/heartbeat` has no coordinates and cannot make an absent or stale GPS fix usable. | MVC/security tests for both routes and status codes. |
| Coordinates | WGS84 latitude `[-90, 90]`, longitude `[-180, 180]`, finite numeric values only. Document longitude/latitude ordering at the Redis adapter. Heading, speed, and payload size have explicit bounded validation. | Boundary and malformed/nonfinite tests. |
| Clocks | Store both `reportedAt` and server `receivedAt` as UTC instants. Reject future fixes beyond a configured skew and fixes older than a configured maximum ingest age. Freshness at lookup uses **server time** and bounded reported age; the client clock alone cannot extend eligibility. | Fixed-clock and skew/age tests. |
| Expiry | Configure heartbeat TTL, latest-location TTL, and maximum usable GPS age separately. Start with a 30-second heartbeat TTL as a tunable example; choose and record exact values before code. A heartbeat extends liveness only; it never refreshes the GPS timestamp. | Real Redis TTL and heartbeat-only stale-GPS tests. |
| Ordering | A location with `reportedAt` not newer than the stored fix is rejected with a stable conflict code. Validation and ordering failure must not refresh heartbeat, location TTL, or GEO position. Use one atomic Redis operation to compare and update the location, heartbeat, and GEO member. | Concurrent and out-of-order Redis tests, including unchanged TTL. |
| Rate limit | Bound updates per authenticated driver with a configurable Redis-backed policy and `429` response. Decide whether heartbeat and GPS share or have separate budgets. A rejected request must not refresh liveness. | Burst/boundary tests with a real Redis instance. |
| Nearby lookup | Internal service operation takes center, radius, and a capped result count. It reads GEO hits, fetches latest fix and heartbeat, rejects stale/missing data, then checks durable driver state and required active vehicle facts through a narrow driver service/query. Return distance and current fix only to the internal caller. | GEO order/radius, stale member, paused/suspended/inactive, and result-cap tests. |
| Redis failure | A Redis write/query outage produces a safe correlated `503` for the location operation or prevents an internal location-based decision. Do not fall back to an old DB coordinate or mark a driver available. Existing PostgreSQL orders, states, and assignments remain unchanged. | Stop/restart Redis integration test and DB invariance check. |

Freeze precise durations, units, response error codes, and whether the separate heartbeat route is needed in the implementation PR. Keep those choices configuration-bound and test them at their boundaries. If Phase 3 chooses a different state model, update this contract before writing the adapter.

## Data ownership and Redis behavior

PostgreSQL owns actor identity, driver active/suspended and operational state, vehicle eligibility, and eventual assignment history. Redis owns only the latest fix, heartbeat, spatial index, and update rate counters. No Phase 4 migration is needed unless Phase 3's durable status or lookup index is missing; add a new forward-only migration if one is required. Do not edit V1 or V2.

Use a namespaced key layout consistent with the architecture plan:

```text
fleetflow:driver:location:{driverId}   latest validated fix; expiring
fleetflow:driver:heartbeat:{driverId}  server-received liveness; expiring
fleetflow:geo:available                GEO members by driver UUID; no member TTL
fleetflow:rate:location:{driverId}     bounded update counter; expiring
```

Redis GEO members do **not** expire when a related key expires. Each lookup must intersect GEO results with live heartbeat and location values, validate their timestamps, and recheck PostgreSQL state. Remove a member when an explicit Phase 3 transition makes the driver ineligible, but preserve lookup filtering because removal can race or fail. Add a bounded reconciliation/cleanup strategy for orphaned GEO members; cleanup is operational hygiene, not a correctness prerequisite.

An accepted location update must atomically compare the existing timestamp and write the new fix, heartbeat, TTLs, and GEO position. Lua or an equivalent atomic Redis operation is appropriate; document its return outcomes. A heartbeat-only update must check authenticated driver state and refresh the heartbeat without changing the fix, reported timestamp, location TTL, or GEO position. Define the exact behavior when a driver is `ONLINE` but not `AVAILABLE`: recording a valid fix may be useful, yet the driver cannot appear as an eligible candidate. Do not hold a PostgreSQL transaction or row lock during Redis network calls.

The internal lookup should overfetch within a bounded radius/count if filtering can remove GEO hits; it must also bound Redis and DB work. The result is a candidate snapshot, not an assignment decision. Phase 5 must recheck durable state when ranking, and Phase 6 must recheck before reservation. If the status changes between Redis and PostgreSQL reads, exclude the driver or fail closed.

## Authorization, privacy, and HTTP failures

The new routes require a valid bearer token, a provisioned active actor with `DRIVER`, and a driver profile belonging to that actor. For a location command, also require active, unsuspended driver facts and an allowed Phase 3 state. A non-driver gets `403`; missing/invalid authentication gets `401`; a `DRIVER` without a self profile follows the established non-enumerating `404` policy. No public route accepts a target driver ID. No customer, merchant, or other driver can retrieve live positions through Phase 4.

Use the existing safe API error shape and correlation ID. Specify stable `400` codes for body/coordinate/time validation, `409` for an out-of-order fix or disallowed durable state, `429` for the rate limit, and `503` for Redis unavailability. If durable driver lookup fails, use the established safe database-outage behavior. Never log raw coordinates, full request bodies, tokens, subjects, or Redis values. Bound request bytes before parsing and avoid putting GPS in exception/metric labels.

## Reviewable implementation slices

| Slice | Output | Acceptance question |
| --- | --- | --- |
| 1. Prerequisite and config | Confirm Phase 3 state contract; add pinned Redis Compose service, external config, dependency and health/failure policy. | Can local/CI run Redis reproducibly without opening it publicly? |
| 2. Location write | Self-authorized DTO, coordinate/time validation, atomic latest-fix/heartbeat/GEO adapter, rate limit. | Can old or invalid fixes leave every Redis eligibility fact unchanged? |
| 3. Heartbeat and lifecycle link | Heartbeat-only command if selected; ineligible-state GEO removal and safe re-entry. | Can heartbeat alone ever revive stale GPS or a paused driver? |
| 4. Internal nearby lookup | Bounded GEO prefilter, Redis freshness filter, PostgreSQL eligibility recheck. | Can a stale GEO member or durable state change leak into the eligible list? |
| 5. Failure and delivery evidence | Real Redis outage/recovery, metrics, docs and full CI. | Does failure block a new location-based decision without changing durable truth? |

## Verification matrix

| Level | Required meaningful cases |
| --- | --- |
| Validation and security | Missing/wrong-role token; driver A cannot write for driver B; missing/inactive/suspended profile; coordinate edges and nonfinite values; heading/speed bounds; future/old timestamps; oversized body; safe error response. |
| Real Redis integration | Latest fix and GEO coordinate agree; TTL expiry; heartbeat-only does not extend GPS; equal/older fix cannot replace latest or extend any TTL; concurrent newer/older writes preserve the newest; rate limit blocks refresh; stale GEO member excluded. |
| PostgreSQL plus Redis | `AVAILABLE` driver included; `ONLINE`, `PAUSED`, `OFFLINE`, inactive, suspended, or vehicle-ineligible driver excluded; status change between search and eligibility check is safe; radius and cap respected. |
| Outage and recovery | Stop real Redis after a successful update; next write and lookup fail closed; PostgreSQL state and orders remain unchanged; after restart, only fresh updates restore eligibility. |
| Runtime and CI | Fresh local Compose startup and health check, signed-driver loopback smoke, full Maven `verify`, CI Redis Testcontainers gate, no secrets or GPS traces in logs. Record exact commands, image digest, test counts and failures/skips. |

Use a real Redis Testcontainer for TTL, GEO, atomic ordering, rate, and outage claims. A mock can help test validation but cannot prove Redis expiration or GEO behavior. Prefer a controlled test clock and short deterministic TTLs to long sleeps. Do not mark the phase complete based on compilation or mock-only tests.

## Acceptance gate

Mark Phase 4 `COMPLETE` only when:

- [x] Phase 3 durable driver state and self-authorization are present and verified.
- [x] Authenticated drivers can update only their own validated latest fix; invalid, too-old, out-of-order, or rate-limited writes do not refresh liveness or GEO.
- [x] Heartbeat and GPS freshness are independent, configurable, and proven with real Redis expiry.
- [x] Nearby lookup is internal, bounded, and excludes stale, paused, offline, inactive, suspended, and otherwise durable-ineligible drivers even when a GEO member remains.
- [x] Redis outage fails closed for location-based decisions without corrupting PostgreSQL state, and recovery requires a fresh valid update.
- [ ] Local Compose and CI run pinned Redis; full Maven verification and a signed-driver smoke pass with exact evidence recorded here and in the roadmap.
- [x] No GPS history, public position read, dispatch offer, reservation, or false production-readiness claim is introduced.

## Learning and review questions

The developer should be able to explain why GEO membership needs a separate heartbeat check; why a heartbeat cannot make old GPS usable; why Redis is not the authority for `AVAILABLE`; how an older update is rejected atomically; how a Redis outage affects a dispatch candidate search; and why Phase 6 still rechecks eligibility before a durable reservation.

## Implementation notes and current evidence

The local development deployment is one Compose project with four separate services: app, PostgreSQL, Keycloak, and Redis. The Redis image is `redis:7.4.7-alpine@sha256:02f2cc4882f8bf87c79a220ac958f58c700bdec0dfb9b9ea61b62fb0e8f1bfcf`. Redis has no durable snapshot/AOF and its only host port is loopback. The app's prod profile uses the Compose service names for PostgreSQL, JWKS, and Redis; its readiness probe includes Redis and PostgreSQL. `scripts/Run-Local.ps1` remains available for local/test fixture routes with the same infrastructure stack.

All policy values come from external configuration. Defaults: heartbeat TTL 30 seconds, location TTL 2 minutes, usable GPS age 45 seconds, maximum ingest age 2 minutes, accepted future skew 5 seconds, maximum lookup radius 20 km, maximum returned candidates 50, overfetch factor 4 with a hard cap of 200, location budget 30 updates per minute, and a separate heartbeat budget 60 per minute. The separate budgets let frequent heartbeats preserve liveness without consuming the GPS fix budget. Each rejected request leaves location and heartbeat untouched. Rate counters are per authenticated driver and expire after the configured window.

`POST /api/v1/drivers/me/location` and `/heartbeat` require the local `DRIVER` actor/profile mapping and an active, unsuspended driver in `ONLINE` or `AVAILABLE`. `OFFLINE` and `PAUSED` receive `409 DRIVER_STATE_CONFLICT`; missing self profile receives the existing non-enumerating `404`. `ONLINE` may report location/heartbeat, but only durable `AVAILABLE` can enter the internal nearby result. No public read endpoint was added. A location body is capped at 1 KiB. Latitude and longitude use WGS84, heading is `[0,360)`, and speed is `[0,100]` metres per second. The stored fix has both client `reportedAt` and server `receivedAt`; lookup checks both ages against the server clock. A heartbeat changes only the heartbeat key/TTL.

The location Lua script checks the stored reported seconds/nanoseconds **before** incrementing its limiter. It returns `0` accepted, `1` old or equal fix, or `2` rate limited. An accepted call updates the latest fix hash, its TTL, the heartbeat and its TTL, and GEO membership atomically. The heartbeat script returns `0` accepted or `2` rate limited. Redis GEO requires **longitude, latitude** order and supports latitudes only to about ±85.0511°. The true WGS84 latitude remains in the latest fix; the GEO indexing latitude is clamped at that bound, and lookup applies an exact distance check to returned candidates. Near-pole discovery can omit candidates when the bounded GEO prefilter fills with clamped points; this remains a documented geographic limit.

Internal nearby search bounds radius and count, overfetches at most 200 GEO hits, checks latest fix and heartbeat keys and timestamps, then queries PostgreSQL in one batch for `AVAILABLE`, active, unsuspended drivers with active actors, `DRIVER` role, and an active positive-capacity vehicle. Redis is only a hint. Missing latest-location keys trigger an atomic orphan-member removal; an ineligible durable transition removes GEO membership after commit as hygiene. If Redis fails, the write returns correlated `503 REDIS_UNAVAILABLE` and nearby search throws the same failure rather than returning stale candidates. The outage test uses a real stopped/restarted Redis container with persistence disabled and requires a new fix after restart.

Verification: `./mvnw.cmd clean verify --no-transfer-progress` passed on 2026-10-05 with **46 tests, 0 failures, 0 errors, 0 skips**. `DriverLifecycleTests` has 3 PostgreSQL-backed tests. `Phase4LocationTests` has 7 PostgreSQL/Redis-backed tests, including real TTL expiration, atomic ordering/rate limiting, concurrent writes, durable rechecks, and stop/start outage recovery. `KeycloakProviderTests` has 2 real-provider tests; one obtains a Keycloak-signed DRIVER token, moves that driver through ONLINE and AVAILABLE, posts a location, and finds them in internal nearby search.

`docker compose --env-file .env -p fleetflow up -d --build --pull never` built the app and started the four-service stack from cached PostgreSQL, Keycloak, Maven, and Java images plus the newly pulled pinned Redis image. `docker compose ... ps` showed app, PostgreSQL, and Redis healthy and Keycloak running. App readiness returned HTTP 200, Redis `redis-cli ping` returned `PONG`, and the realm discovery endpoint returned HTTP 200. The local Keycloak bootstrap credentials were generated into ignored `.env` and are not committed. CI execution for this branch remains to be confirmed; the existing `verify` workflow runs Maven `verify`, including the Redis tests.
