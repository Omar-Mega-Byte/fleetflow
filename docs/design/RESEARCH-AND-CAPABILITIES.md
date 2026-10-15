# Research and implementation boundaries

Checkpoint: 2026-10-14, before design selection or application changes.

## Reference sources and bounded roles

| Source | Use in exploration | Limit |
| --- | --- | --- |
| [Linear product surfaces](https://linear.app/features) | Compact task hierarchy, consistent navigation, selected-object context | Inspiration; no screenshot clone or imported identity |
| [Mapbox on-demand logistics](https://www.mapbox.com/on-demand-logistics) | Map/detail relationship, operational overlays and selected delivery | No inference that FleetFlow has Mapbox routing, traffic or ETA capabilities |
| [Vercel Web Interface Guidelines](https://vercel.com/design/guidelines) | Keyboard/focus, feedback, labels, responsive behavior and motion | Interaction guidance, not FleetFlow branding |
| Bundled Refero typography/craft/visual-workflow references | Neutral dense product type, visible focus, real labels, reference lock and screenshot critique | Live Refero research returned inactive subscription; no style/screen results were retrieved |
| User brief | Three divergent compositions; emerald brand and semantic state colors; reusable tokens; operational detail | Explore and compare before final values are locked |

The user explicitly delegates design selection and small decisions. Select autonomously after comparing the three prototypes; the bundled visual-workflow suggestion to ask the user to choose is superseded by that instruction. A second design pass and repeated implementation QA remain mandatory.

## API and persona matrix

| Persona | Read surfaces | Actions | Important boundary |
| --- | --- | --- | --- |
| CUSTOMER | `/v1/orders`, owned detail, tracking snapshot/SSE, `/notifications/me` | Own cancellation; local/test creation with Idempotency-Key | No global orders, driver identity, or public intake |
| MERCHANT | Exact branch orders, zones, policy | Confirm/preparation/ready/cancel; zone create/replace; policy; serviceability preview | Current exact-branch membership; known branch UUID/context needed |
| DRIVER | Own operational state, bounded offers/deliveries, notifications | Online/available/pause/offline; own offer accept/reject; pickup/transit/complete/fail; location/heartbeat | No dispatcher impersonation; no automatic geolocation reporting without deliberate user action |
| DISPATCHER | Order-scoped attempts/candidates/assignments/incidents; daily analytics | Match, offer, retry, reassign, cancel, fail, resolve | No global order-list or all-driver-position API established |
| ADMIN | Failed outbox, quarantine, optional demo state | Audited replay, bounded seed/reset | No arbitrary quarantine replay; demo may be disabled |
| SUPPORT | Session role recognized | No workflow established by inspected contracts | Do not expose unsupported controls |

Sources: `SecurityConfiguration`, order/dispatch/driver/delivery/zone/event/demo controllers and related view records. Client API base remains same-origin `/api`; relative paths above are passed to its `/v1/` client.

## Critical contract details

- Order query accepts only `branchId,status,from,to,page,size,sort`. Unsupported `q` is rejected. Status validation must follow service, not only older OpenAPI annotation. Sort is `createdAt` or `id`, asc/desc; page size 1–100. Search can explicitly search loaded rows or accept an exact ID; do not imply server full-text search.
- `OrderView`: id, customerId, branchId, status, version, saved names, pickup/delivery **line1 and city only**, currency, totalAmount, createdAt, items. `PublicAddress` intentionally excludes coordinates. No driver identity, delivery ETA, routed geometry or lifecycle timestamps in this response.
- Tracking: orderId, orderStatus, orderVersion, deliveryStatus, observedAt, nullable `{latitude,longitude,reportedAt}`, terminal. Bearer-header fetch SSE; limited reconnects, snapshot refresh and abort on navigation. A missing/stale/terminal position must not be rendered as current.
- Order statuses and offer states are distinct. Lifecycle component must show cancellation/failure as exception outcomes, not fabricate completed intermediate timestamps.
- Matching candidates include reason, nullable distance/GPS age/factors/score/rank/straightLineSeconds. The last is a matching estimate, not a delivery ETA. No candidate coordinates in this DTO.
- Assignment history includes offerId, driverId, attemptId, rank, status, offeredAt, expiresAt, respondedAt. Current-driver offer view additionally provides orderId and address strings.
- Zones contain GeoJSON, active, version, branchId and timestamps. Create requires Idempotency-Key; replacement requires expectedVersion. Polygon editor must preserve GeoJSON coordinate order `[longitude, latitude]`, explicit validation and conflict recovery.
- Notifications are bounded per recipient and read by polling; they are not an exposed universal live Kafka stream. Daily analytics returns UTC day/status/transition totals; totals are transitions, not unique deliveries or on-time performance.
- Failed outbox view exposes eventId, aggregateId, eventType, attempts, replayCount, errorCode, occurredAt. Quarantine exposes safe consumer/topic/partition/offset/error/time metadata, no payload.
- Generic create-order controller is local/test-only. The default/prod showcase uses bounded synthetic seed/reset, not untrusted submitted pricing/public intake. Any local form needs explicit environment enablement and honest copy.
- Existing session endpoint supplies roles only, not branch memberships, operator name, global fleet scope or demo context. Avoid fabricated account identity and automatic branch discovery.

## Baseline findings and architecture priorities

Current customer pages are functional but sparse: one wide card occupies a mostly empty viewport, detail uses a long single-column lifecycle, shared navigation is basic, and most persona workspaces are placeholders. The CSS is global and has repeated literal tokens. Keep the OIDC/client/SSE foundation, introduce feature-oriented typed views and shared presentation primitives, and preserve focus/skip-link/reduced-motion behavior.

Nine baseline screenshots cover desktop/mobile login, empty orders, populated desktop orders, desktop/tablet/mobile detail and mobile service error. The signed-PKCE fixture test passed; APIs were intercepted. Screenshots must not be cited as proof of a running Spring/Keycloak stack.

Design target is pending. Do not implement a CSS makeover while the three directions are still being generated. First compare screenshots and interactions, correct contract drift, refine one direction and record exact token/role/layout commitments in `UI.md`.
