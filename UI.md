# UI / UX Master Specification

**Design target:** FleetFlow web experience over the backend at `120aa2a` on 2026-10-09. This is a specification, not a claim that a frontend exists. The repository has no frontend application or browser OIDC client. Phase 11 remains `IN_PROGRESS`; the current Compose setup is a loopback demonstration, not an internet deployment.

**Contract rule:** `src/main/java`, registered route policies, migrations, and tests govern what can ship. Architecture and phase documents explain intent and evidence, but proposed capabilities below are explicitly marked **backend prerequisite**. A UI route, a hidden control, or a token claim never grants access.

## 1. Product Understanding

FleetFlow coordinates a delivery from a customer order through branch preparation, ranked dispatch, an exclusive driver offer, pickup, transit, and delivery. PostgreSQL owns durable state, history, offers, incidents, and recovery; Redis holds ephemeral location/liveness and GEO prefilter data; Kafka carries committed outbox events to in-app notifications and UTC transition counts. Customer tracking combines owned PostgreSQL state with an optional fresh Redis position. There are no payments, trusted production prices, customer self-registration, production order intake, full GPS history, or production administration UI APIs.

The product should open on **the next decision**, not a collage of metrics: an order to prepare, an offer with a deadline, an incident to resolve, or a delivery step to complete. Every action returns to the durable state and explains what changed. Derived notifications and analytics may lag; never use them as proof a command committed.

### Evidence and design reference lock

Repository basis: [README](README.md), [architecture](docs/architecture/ARCHITECTURE_PLAN.md), [roadmap](docs/ROADMAP.md), [Phase 8](docs/phases/PHASE_8.md), [Phase 9](docs/phases/Phase9.md), [Phase 10](docs/phases/Phase10.md), [Phase 11](docs/phases/phase11.md), all 57 controller operations, `SecurityConfiguration`, DTOs/services, migrations, tracking implementation, operational meters, and relevant integration tests. The older `PROJECT_CONTEXT.md` describes a broader ambition; it is not evidence of a shipped API. No `.html`, `.css`, `.ts`, or `.tsx` frontend was found.

Live Refero style search was attempted but the connected account returned `NO_SUBSCRIPTION`. The reference lock therefore uses the bundled Refero craft guides (typography, color, motion, anti-template checks), the user's brief, [Carbon's data-table guidance](https://www.carbondesignsystem.com/building-blocks/core/components/data-table/guidelines), [Carbon's motion guidance](https://v10.carbondesignsystem.com/guidelines/motion/overview/), and [GOV.UK's consequence-led destructive buttons](https://design-system.service.gov.uk/components/button/). These references inform decisions; the design is not a copy of their component libraries.

| Decision | Source | FleetFlow adaptation |
| --- | --- | --- |
| Dense, neutral, one-family typography | Refero typography guide; operational scanning | Compact sans, tabular numerals, meaningful weight changes; no decorative serif. |
| Mostly open canvas and dividers | User brief; Refero anti-template guide | Tables and status rails carry hierarchy; cards only for isolated decisions. |
| Table toolbar and visible sort semantics | Carbon data table | Only controls supported by the exact endpoint appear; unsupported search and bulk actions stay absent. |
| Brief motion that explains state | Refero motion guide; Carbon motion | An offer opening, state transition, and inline confirmation have distinct, short transitions. |
| Confirmation describes consequence | GOV.UK button guidance; backend recovery rules | Cancel, reassign, fail, and enforce-zone dialogs name the affected order, state, and reason. |

**Reference lock:** a cool mineral canvas, near-black ink, a narrow blue-green action accent, hairline separators, compact numeric columns, and a persistent *state spine* on order/delivery details. Accent is reserved for current focus and primary action; semantic amber/red/green mean attention/failure/success. No gradient hero, glowing panel, decorative map, equal-card dashboard, or status color without text. Maps carry actual polygon or fresh position data only. A final visual implementation must be compared against this lock before acceptance.

## 2. User Roles

Roles are stored in PostgreSQL and re-read on requests. One actor can have several roles; the UI must choose a workspace while preserving each endpoint's object scope. `ADMIN`, `SUPPORT`, and `DISPATCHER` do not inherit a customer's orders or a merchant's branches.

| Role | Responsibility and starting screen | Frequent actions / essential information | Sensitive boundary |
| --- | --- | --- | --- |
| CUSTOMER | Follow own orders; **My orders** | Filter owned orders; inspect saved items/address/total; cancel only `CREATED` or `CONFIRMED`; follow tracking and read own notifications | No foreign order, driver identity, hidden/stale GPS, or production create. |
| MERCHANT | Prepare orders in an actively assigned branch; **Branch orders** | Page through narrow branch summaries; confirm, start preparation, mark ready, cancel; manage zones and serviceability mode | Exact active branch membership for each request; no customer detail endpoint. |
| DRIVER | Fulfil assigned work; **On shift** | Change durable duty state; see newest offers/deadlines; accept/reject; progress own deliveries; transmit location and heartbeat | Self only; an offer notification does not authorize acceptance. Position sharing must be explicit and consent-aware. |
| DISPATCHER | Handle known order IDs; **Open order** until a queue API exists | Match, inspect candidate evidence, offer a ranked driver, inspect assignments/incidents, retry/reassign/cancel/fail/resolve, view UTC daily transitions | No customer/merchant general read. No global queue or dispatcher order detail API exists. |
| ADMIN | Provision disposable local/test fixtures; **Local lab** | Create/read actors, customers, merchants, branches, memberships, drivers, vehicles | Routes absent in default/prod; role grants, deactivation, and Keycloak accounts remain out of band. |
| SUPPORT | No business route currently authorized; **Access note** | Explain how to reach an operator through established support channels | Do not invent a support console or borrow dispatcher/customer access. |

No account/profile settings API exists. A small account menu may display OIDC presentation claims, sign out, and link to the identity provider's supported account area, but must not imply FleetFlow can edit user data or roles.

## 3. Core User Journeys

| Journey | Entry → decision → committed result → follow-up |
| --- | --- |
| Customer order | My orders → open owned detail → view current stage/items → optional reasoned early cancellation → refetch detail/list; tracking stream shows current state only while owned. Creation is **local/test laboratory only**. |
| Branch preparation | Known branch → paged orders → confirm → start preparation → mark ready; every action is offered only in its valid state and server conflict always wins. Branch cancellation asks for a reason and remains possible through `DRIVER_ASSIGNED` before pickup via the recovery path. |
| Driver shift | Sign in → read self status → go online/available with vehicle prerequisite → keep heartbeat/GPS fresh when explicitly enabled → respond to an unexpired offer → pickup → transit → complete or reasoned fail. Pausing/offline is not offered while `RESERVED` or `DELIVERING`. |
| Dispatch | Paste/open a known order ID → run match on a ready order → inspect immutable ranked and rejected candidate evidence → choose one eligible candidate from the latest attempt → offer → watch assignment status → recover via reasoned retry or pre-pickup reassignment when needed. No queue is implied. |
| Merchant coverage | Known branch → list active/inactive zones and policy → draw/import valid GeoJSON polygon → preview a point → save with version → deliberately enable `ENFORCED` after at least one active zone. Show overlapping zones honestly. |
| Incident | Known dispatch order → see bounded incidents → inspect reason and state → fail an active post-pickup delivery or resolve an open incident with a resolution. Do not imply resolving repairs the order. |

**Action/state map for controls:** customer Cancel: `CREATED|CONFIRMED`; merchant Confirm: `CREATED`, Start preparation: `CONFIRMED`, Ready: `PREPARING`, Cancel: pre-pickup `CREATED|CONFIRMED|PREPARING|READY_FOR_PICKUP|DISPATCHING|DISPATCH_FAILED|DRIVER_ASSIGNED`. Dispatcher Match: `READY_FOR_PICKUP`; Retry: `DISPATCH_FAILED` within the attempt budget; Reassign: accepted `DRIVER_ASSIGNED` delivery still `ASSIGNED`; Cancel: `DISPATCHING|DISPATCH_FAILED|DRIVER_ASSIGNED` before pickup; Fail: accepted delivery after pickup. Driver Accept/Reject: own unexpired `OFFERED`; Pickup: `ASSIGNED`; Transit: `PICKED_UP`; Complete: `IN_TRANSIT`; Fail: `PICKED_UP|IN_TRANSIT`. Duty controls: Offline → Online, Online/Paused → Available (active vehicle required), Online/Available → Paused, Online/Available/Paused → Offline. Reservation/acceptance drive `RESERVED`/`DELIVERING` server-side. This map guides affordances only; a server conflict or current scope change overrides it.

## 4. Backend Capability Inventory

| Domain | Implemented read/write and limits | Durable/derived distinction |
| --- | --- | --- |
| Identity | Signed Keycloak JWT (`iss`, `aud`, expiry), active actor/roles in DB, exact branch and customer ownership. Local/test ADMIN fixtures only. | No `/me` role/branch discovery, FleetFlow login, role management API, or registration. |
| Customer orders | Owned detail; bounded page with `branchId`, `status` (only `CREATED` through `DRIVER_ASSIGNED`, plus `CANCELLED`/`DISPATCH_FAILED`), `from` inclusive, `to` exclusive, page 0+, size 1–100, sort `createdAt` or `id` asc/desc; early cancel. Local/test loopback create accepts caller prices. | Order/detail is durable. Create is unsafe outside lab. Later delivery states can be seen in unfiltered results but cannot be selected as a server status filter. |
| Merchant | Exact-branch paged narrow summaries; lifecycle commands; zones, policy and serviceability preview. | No branch list or merchant order detail read; zone policy history is stored but not exposed. |
| Driver | Own durable status, lifecycle; signed latest location/heartbeat writes; own newest 1–20 offers and deliveries; offer/delivery commands. | No driver-readable location freshness or vehicle list endpoint. GPS is ephemeral. |
| Dispatch | Per-order match/attempts, offers/assignments, retry/reassign/cancel, fail/resolve incidents; newest 1–20 histories. | No dispatcher order queue/list/detail, open-incident list, or global search. Recovery and offer expiry are background jobs. |
| Async | Own newest 1–20 notifications (customer/driver); dispatcher UTC daily transition counts up to 31 inclusive days. | No unread/write state, durable in-app delivery status, notification pagination, or current-order-state KPI. Analytics may lag. |
| Tracking | Owned snapshot and bearer-protected SSE; optional fresh position; terminal closes. | At most 100 streams globally, 3 per actor, 5 minutes per connection; no durable replay/GPS history. |
| Operations | Anonymous hidden-detail health probes; dispatcher `GET /actuator/metrics[/name]`; private monitoring profile Prometheus/Grafana, logs, runbooks, backup scripts. | No business API for outbox rows, queue jobs, alert management, backup operation, or audit log browsing. |

## 5. Backend → UI Coverage Matrix

This accounts for the **57 current `/api/v1` operations**: 43 cross-profile operations and 14 local/test-only operations (13 ADMIN fixtures plus CUSTOMER test order creation). “Lab” means enabled only when the server actually registers the route and the actor has `ADMIN` (or `CUSTOMER` for lab order creation); a build flag alone is insufficient. Endpoints in a row are separately authorized calls.

| Backend capability | Role | UI surface and main interaction | API/endpoint | State |
| --- | --- | --- | --- | --- |
| Owned order page and detail | CUSTOMER | My orders table → owned detail; real server filters/sort/page | `GET /orders`, `GET /orders/{id}` | Live |
| Early customer cancellation | CUSTOMER | Detail consequence dialog with 1–500 character reason | `POST /orders/{id}/cancel` | Live |
| Current tracking and stream | CUSTOMER | Detail stage/optional map; bearer stream reconnects via fresh snapshot | `GET /orders/{id}/tracking`, `/tracking/stream` | Live |
| Own notification history | CUSTOMER, DRIVER | Activity popover/page; newest 1–20, no unread badge | `GET /notifications/me` | Live, derived |
| Branch order page | MERCHANT plus membership | Branch orders table using known branch ID, server page | `GET /merchant-branches/{branchId}/orders` | Live |
| Prepare and cancel | MERCHANT plus membership | Contextual row action; cancellation reason | `POST /merchant-branches/{branchId}/orders/{id}/confirm`, `/start-preparation`, `/ready-for-pickup`, `/cancel` | Live |
| Zones and policy | MERCHANT plus membership | Zone list/editor, versioned save, mode control | `GET/POST /merchant-branches/{branchId}/zones`, `PUT /zones/{zoneId}`, `GET/PUT /zone-policy` | Live |
| Destination preview | MERCHANT plus membership | Latitude/longitude preview and map point | `POST /merchant-branches/{branchId}/serviceability` | Live |
| Own driver status | DRIVER | On shift status/action rail | `GET /drivers/me` | Live |
| Durable duty transitions | DRIVER | Go online, available, pause, offline as valid | `POST /drivers/me/online`, `/available`, `/pause`, `/offline` | Live |
| Ephemeral liveness/fix | DRIVER | Explicit location sharing control and connection state | `POST /drivers/me/location`, `/heartbeat` | Live background writes |
| Own offers and responses | DRIVER | Deadline ordered offer inbox; accept/reject | `GET /drivers/me/offers`, `POST /drivers/me/offers/{offerId}/accept`, `/reject` | Live |
| Own deliveries and transitions | DRIVER | Delivery task/list; pickup, transit, complete, reasoned fail | `GET /drivers/me/deliveries`, `POST /drivers/me/deliveries/{deliveryId}/pickup`, `/start-transit`, `/complete`, `/fail` | Live |
| Match and evidence | DISPATCHER | Known-order dispatch workbench; run match and inspect latest 1–20 attempts/candidates | `POST /dispatch/orders/{orderId}/match`, `GET /attempts` | Live |
| Exclusive offer and history | DISPATCHER | Choose latest ranked candidate; inspect newest 1–20 assignments | `POST /dispatch/orders/{orderId}/offers`, `GET /assignments` | Live |
| Recovery and incident work | DISPATCHER | Reasoned retry/reassign/cancel/fail/resolve; incident table | `POST /dispatch/orders/{orderId}/retry`, `/reassign`, `/cancel`, `/incidents/fail`, `GET /incidents`, `POST /incidents/{incidentId}/resolve` | Live |
| Daily transitions | DISPATCHER | Small UTC date table/chart; 31-day maximum and derived label | `GET /dispatch/analytics/daily` | Live, derived |
| Actor/customer fixtures | ADMIN, local/test | Local lab staged creation and ID lookup | `POST/GET /actors`, `POST/GET /customers` (GET by `{id}`) | Lab only |
| Merchant/branch/membership fixtures | ADMIN, local/test | Local lab; create merchant → branch → staff membership; lookup by ID where available | `POST/GET /merchants`, `POST/GET /merchant-branches`, `POST /merchant-staff-memberships` | Lab only |
| Driver/vehicle fixtures | ADMIN, local/test | Local lab; create driver → vehicle; lookup by ID | `POST/GET /drivers`, `POST/GET /vehicles` | Lab only |
| Test order creation | CUSTOMER, local/test | Explicit lab order form with caller-price warning; branch ID input | `POST /orders` | Lab only |

Paths in this table omit the common `/api/v1` prefix. Fixture GETs are `/{id}` only: there is no collection list. All role checks and object checks remain on the server. The 13 ADMIN fixture operations are seven creates + six ID reads; the fourteenth local-only operation is CUSTOMER order creation.

**Intentionally outside direct business UI:** low-level Kafka/outbox tables, recovery scheduler state, migration history, raw audit tables, backup scripts, Prometheus scrape, and operational logs have no safe authorized business read/action APIs. Show their available aggregate meters through a restricted operations link or external Grafana; keep scripts in the operator runbook. Do not render a fake management console.

## 6. Information Architecture

Use four workspace identities behind one slim shell. The workspace switcher appears only for roles returned by the proposed actor-context endpoint (see §31). Within a workspace, keep at most four primary destinations:

| Workspace | Primary navigation | Contextual depth |
| --- | --- | --- |
| Customer | Orders, Activity | Order detail → tracking; lab-only New order. |
| Merchant | Branch orders, Zones | Branch ID context → order row action / zone editor / policy. |
| Driver | On shift, Offers, Deliveries, Activity | Offer detail → response; delivery detail → next valid transition. |
| Dispatcher | Open order, Daily transitions, Operations link | Known order ID → attempts / assignments / incidents / recovery. |
| Local ADMIN | Local lab | Fixture type → create/lookup; external Keycloak/admin instructions remain separate. |

No generic Overview route before a question-answering backend exists. A role's first screen *is* its operational overview. Global search is limited to navigation, recent locally opened IDs, and paste-ID resolution; it must not promise cross-role entity search.

## 7. Navigation Model

Desktop: 224 px quiet side rail with workspace name, 2–4 destinations, branch context when applicable, a compact connection indicator, and account control at the foot. Content gets a breadcrumb (`Workspace / known order ID / Attempts`) only beyond one level. Laptop rail may collapse to 72 px; tablet uses a labelled drawer; mobile uses a short bottom destination set and an explicit workspace menu. Never put a destructive action in global navigation.

`Ctrl/Cmd + K` opens a command palette for **local navigation only**, “Open known order ID” (dispatcher), and currently authorized create/actions with context. It does not query nonexistent global search. Escape returns focus; results announce count and group; no unverified action is executed from keyboard alone. Recent IDs stay local to that browser session and are never treated as authorization.

## 8. Route Map

`*` below means a frontend route **proposed**, not a registered backend handler. Guards use server actor context and endpoint responses; lab routes also require confirmed local/test environment. For all roles, `/` redirects to the first permitted workspace only after actor context loads.

```text
/
├─ /sign-in*                         → Keycloak redirect
├─ /auth/callback*                   → OIDC code processing
├─ /access*                          → authenticated but unprovisioned/no usable role
├─ /customer/orders*
│  ├─ /:orderId*                     → owned order + tracking
│  └─ /new*                          → local/test only
├─ /customer/activity*
├─ /merchant/branches/:branchId/orders*
├─ /merchant/branches/:branchId/zones*
│  └─ /:zoneId*                      → versioned edit drawer
├─ /driver/shift*
├─ /driver/offers*
├─ /driver/deliveries*
│  └─ /:deliveryId*
├─ /driver/activity*
├─ /dispatch/open*
│  └─ /:orderId*                     → attempts / assignments / incidents tabs
├─ /dispatch/daily*
├─ /lab/fixtures*                    → local/test ADMIN only
└─ /not-found*
```

| Route group | Permitted role | Major component / primary action | Backend dependency |
| --- | --- | --- | --- |
| Auth/access | signed actor; access state for no role | OIDC return, sign out, explain provisioning | Browser OIDC client and **new actor context API**. |
| Customer orders/detail/activity | CUSTOMER | Page table, saved detail, tracking/status, early cancel, notifications | Order, tracking, notification APIs. |
| Customer new | CUSTOMER + local/test | Lab order form, serviceability feedback | Local `POST /orders`; known branch ID. |
| Merchant branch orders/zones | MERCHANT + exact membership | Narrow order table/actions, zone list/editor/policy/preview | Branch orders and zone APIs; branch ID discovery prerequisite. |
| Driver shift/offers/deliveries/activity | DRIVER | Duty state, offer response, delivery progress, notifications | Self/offer/delivery/location APIs. |
| Dispatch open/order/daily | DISPATCHER | ID resolver, candidate evidence and recovery, UTC derived report | Per-order dispatch/recovery APIs and analytics. |
| Lab fixtures | ADMIN + local/test | Seven create flows and six ID lookups | Fixture APIs only. |

Dispatcher `/dispatch/open/:orderId` cannot show customer name/address/full order state because no dispatcher order-detail read exists. The workbench shows only fields in attempts, assignments and incidents and accepts a known ID. Merchant order rows cannot open a full detail page because only CUSTOMER may call `GET /orders/{id}`. These constraints stay visible in screen copy and are backend roadmap items, not frontend bugs.

## 9. Design Philosophy

**Simple at first glance, powerful when needed** means one primary question and action per surface. A customer sees order progress before metadata; a driver sees the next legal step before delivery history; a dispatcher sees current attempt evidence before algorithm configuration. Secondary fields remain one click away in a panel, not hidden behind ambiguous icons. A polished interface does not conceal a required reason, pending derived effect, or failed command.

Three recurring patterns give this product identity: (1) a narrow **state spine** showing the order or delivery's current stage with only server-supported history, (2) an **evidence lane** alongside dispatch decisions that shows rank, rejection reason and timestamp, and (3) a consistent **pending → committed → derived** distinction for commands that also generate async notifications/analytics. The state spine cannot display historical events until a scoped history API exists; initially it shows the current state and available timestamps only.

## 10. Visual Language

Work-tool precision: light mineral background, white editing surfaces, almost black typography, cool gray rules and one deep blue-green action color. The layout uses a calm left rail and open content plane. Important data are aligned on a baseline; timestamps, IDs, amounts and deadlines have tabular numerals. Maps appear only beside real zone geometry or an authorized fresh position. Keep illustrations out of dense operational pages. Icons are a single outline family (e.g. Lucide), 16–18 px, named with text for any high-risk action. Avoid shadows except a shallow popover/drawer elevation and focus treatment.

The memorable move is the state spine: a 2 px vertical rule with text labels and one active node, aligned with the action pane. It conveys progression without pretending the frontend owns history. On mobile it becomes a compact horizontal “current stage / next step” header; never compress its labels into unexplained colored dots.

## 11. Design Tokens

These are implementation starting values; test contrast in both themes and adjust tokens together. Color is semantic; do not reuse warning or action colors for decoration.

```css
:root {
  --canvas: #f7f8f8; --surface: #ffffff; --surface-raised: #ffffff;
  --ink: #172124; --ink-secondary: #405057; --ink-muted: #63737a;
  --rule: #d7e0e2; --rule-strong: #aebdc2;
  --accent: #116b72; --accent-hover: #0b555b; --on-accent: #ffffff;
  --positive: #1d6d45; --positive-soft: #eaf5ee;
  --caution: #805500; --caution-soft: #fff4d9;
  --critical: #a73335; --critical-soft: #fff0ef;
  --info: #205c83; --info-soft: #eaf3f8;
  --focus: #0a6e9b; --disabled: #9bacb1;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px;
  --space-5: 24px; --space-6: 32px; --space-7: 48px;
  --radius-control: 5px; --radius-panel: 8px; --radius-dialog: 10px;
  --motion-fast: 120ms; --motion-normal: 200ms; --motion-large: 300ms;
  --ease-enter: cubic-bezier(0, 0, .2, 1);
  --ease-exit: cubic-bezier(.4, 0, 1, 1);
  --ease-change: cubic-bezier(.4, 0, .2, 1);
}
```

Dark mode is an optional follow-on with explicit inverse tokens and screenshot/contrast verification; ship a complete light theme first. No theme toggle that merely changes background while leaving charts, maps or focus illegible. Primary content width: 1280 px for ordinary pages; dispatch evidence can use the full viewport up to 1600 px. Reading prose caps at about 70 characters. Table columns stretch to useful content widths, not equal cards.

## 12. Typography

Use one neutral sans family (Inter or an equally legible system face), with system fallback; no second display face. UI scale: 12 px metadata, 13 px dense table, 14 px controls/body, 16 px lead, 20 px section title, 26 px page title. Line heights 1.35 for controls/tables and 1.5 for prose. Weight 400 for body, 550/600 for emphasis; avoid all-caps section headings. Use `font-variant-numeric: tabular-nums` for IDs, counts, dates, deadlines, prices and scores; monospace only for copyable UUID/correlation ID or GeoJSON. Prices retain currency and decimal precision; UTC report labels stay explicit even when UI timestamps use the viewer's locale.

## 13. Color System

Canvas/surface/rule tokens carry structure. Accent marks interactive focus and the one primary action in a region. Status always includes text, and only exceptions demand a tinted background. Example: `FAILED` uses critical text plus an icon in the incident list; ordinary `PREPARING` is plain text, not a full-width badge. Distinguish `DISPATCH_FAILED` (order state), `DELIVERY_FAILED` (terminal state), expired offer, and open incident with words. A positive tone is reserved for confirmed completion, not “available” when GPS is stale. WCAG AA contrast is a release gate for normal text, compact metadata, charts, and focus.

## 14. Spacing & Layout

4 px base grid; 8–12 px within controls, 16 px row-to-row, 24–32 px between sections, 48 px for a major change in context. Data tables use 40 px default rows and an optional 32 px compact density on desktop, never below touch-target requirements on touch devices. Page headers contain a short title, one context line, and the primary action aligned to the right. Dividers separate functional groups; no border box around every section. Detail pages use a flexible main column (about 2/3) and a 280–340 px context/action column. Keep sticky action and table headers from obscuring focus or announcements.

## 15. Component System

| Component | Contract and interaction |
| --- | --- |
| Primary/secondary/text buttons | Verb labels; one primary per decision area; disabled explains why (vehicle required, wrong state, expired deadline); pending prevents duplicate submit. |
| Icon button | Tooltip and accessible name; no icon-only destructive commands. Copy ID gives inline “Copied” feedback. |
| Inputs/select/date | Persistent labels, examples only when needed, field help near source, inline errors from `fields`; date range is bounded before request. |
| Tabs | URL-backed for attempts/assignments/incidents; arrow-key behavior; preserve order ID/context. |
| Navigation/breadcrumb | Current workspace and branch/order context clear; direct links recover from refresh. |
| Table/list | Server-backed pagination/sort only where supported; click row only if a true detail exists; otherwise explicit row actions. |
| Status text | Exact backend state plus human label; never infer success from timeout or a pending request. |
| Popover/menu | Contextual low-risk actions, keyboard and escape support; high-risk action opens a dialog. |
| Drawer | Zone editing and candidate evidence; origin stays visible; focus returns to trigger. |
| Dialog | Names resource, consequence, required reason, and final verb. Distinguish irreversible completion/failure from reversible navigation. |
| Alert/toast | Inline for local result and conflicts; toast only for background result or cross-route navigation, capped/announced once. |
| Pagination | Customer/merchant pages: total and size from server. Bounded histories: “Newest 20” with no fake next page. |
| Timeline/activity | Order stage from live state; notifications are derived activity. Do not claim an audit timeline from non-exposed history tables. |
| Chart | Only UTC daily transition counts; axes, units, date range, lag note, and accessible table alternative. |
| Skeleton/empty/error | Match final geometry, keep existing data on refresh, distinguish zero records from inaccessible data. |

No bulk actions, editable columns, expandable history, full-text search, or autosave for consequential writes unless a backend contract is added. Column visibility and density are safe local preferences; they cannot widen fetched data. Tooltips clarify technical values but never carry essential instructions alone.

## 16. Motion System

Purpose: feedback, continuity, hierarchy. No motion may postpone a valid action or hide a state change. Animate opacity/transform where possible; large layout reflow is rare. Durations: micro 90–140 ms, normal 160–240 ms, large 240–320 ms; enter `--ease-enter`, exit `--ease-exit`, state changes `--ease-change`. A long async operation displays real progress/pending copy, not an ornamental spinner.

| Surface | Motion rule |
| --- | --- |
| Navigation/page | Replace content immediately; 120 ms fade on the newly available region only, preserve shell and scroll restoration. |
| Sidebar | 200 ms width/label transition on explicit collapse; no hover expansion that pushes content. |
| Drawer/dialog/palette | 220–280 ms translate 8–16 px plus opacity; anchor to trigger; background dims modestly, focus moves on open and returns on close. |
| Dropdown/tab/expand | 120–200 ms; selected underline travels only within tab strip; content becomes readable without waiting. |
| Table filter/sort/page | Keep prior rows visible with subdued refresh state; replace atomically, 120 ms highlight on changed row; announce count/order. No animated row shuffle for server sort. |
| Create/delete | Add/remove only after server acknowledgement; 160 ms local highlight/collapse. No undo promise for irreversible commands. |
| Offer deadline | Text countdown updates without pulsing; final 10 seconds gains a stable caution treatment; expiration snaps to server-confirmed state. |
| Success/failure | 120 ms state-text/color transition; failed command leaves prior durable state intact and focuses inline explanation. |
| Loading/chart | Static skeleton after a short delay; chart draws once in 240 ms or appears immediately under reduced motion. Never animate every metric tick. |
| Hover/press/notification | 90–120 ms color/opacity; pressed feedback <= 2 px; notification appears once, no bounce. |

Under `prefers-reduced-motion: reduce`, remove spatial movement, stagger and chart drawing; use instant state changes or very short opacity when needed. No animated GPS trail: a fresh point replaces the previous point and disappears when stale.

## 17. Micro-interactions

Copying a UUID changes the adjacent control to “Copied” for about 1.5 seconds without a toast. A submitted command first labels the button “Saving…” and then shows the **returned state/version** inline; a 409 shows “State changed elsewhere. Refreshing…” while fetching the canonical state. Offer accept/reject locks just that offer row while pending. Zone editor shows “Unsaved changes” locally and a version conflict recovery path; it never silently overwrites another edit. A failed geolocation permission request leaves a clear manual retry instruction and stops location writes. Countdown, SSE connection and derived notification state all use words; animation is supporting evidence only.

## 18. Dashboard Strategy

| Starting surface | Question answered | Source and limit |
| --- | --- | --- |
| Customer My orders | “What happened to my orders?” | Owned page and detail; newest row statuses, no invented delivery ETA. |
| Merchant Branch orders | “What needs preparation now?” | Branch page, current page only. Do not claim totals across all branches. |
| Driver On shift | “Am I ready, and what should I do next?” | Self status, own newest offers/deliveries; GPS readiness remains unknown unless a new read API is added. |
| Dispatcher Open order | “What decision is needed for this known order?” | Attempt/assignment/incident reads for pasted ID; no global backlog widget. |
| Dispatcher Daily transitions | “How have transitions changed over the selected UTC days?” | Derived daily transition counts; explicitly not current order inventory. |
| Operations external view | “Are dependencies and async processing healthy?” | Private Grafana's real 12 panels, or authorized Actuator metric queries; no public embedded dashboard. |

An in-app operations summary would need a purpose-built authorized backend projection before implementation. `GET /actuator/metrics` exposes raw meters to DISPATCHER but not a complete incident/queue browser. Avoid building another Grafana inside the product.

## 19. Data / Table UX

Customer orders: server sort (`createdAt` or `id`, asc/desc), branch/status/time filters, 0-based page, size 1–100, real total. The status filter currently accepts `CREATED`, `CONFIRMED`, `PREPARING`, `READY_FOR_PICKUP`, `CANCELLED`, `DISPATCHING`, `DISPATCH_FAILED`, and `DRIVER_ASSIGNED`; it does **not** accept `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`, or `DELIVERY_FAILED`, even though those states may appear in unfiltered results. Default newest first. Display order ID short + copy full, branch, status, amount/currency, created time; row opens owned detail. Preserve filters in URL. Merchant branch orders: server page/size only, fixed newest ordering; filters/search/sort beyond the current page are **not** represented as global. Row offers valid lifecycle commands, not a fake detail link. Driver offers/deliveries and dispatch histories are bounded newest 20 with no pagination, search, or “show all.” Zones list caps at 100 and returns newest first; local name/active filtering can say “within loaded zones” only. Daily report is a small table first, optional chart second.

Selection and bulk operations are absent because no bulk endpoint exists. The user may hide local columns; this does not change authorization. Sticky headers are desktop-only when the table scrolls inside a defined region. Small screens show a prioritized subset plus an explicit “View details” action where a real detail exists, otherwise a row disclosure containing only already fetched fields. Never horizontally trap keyboard users.

## 20. Forms

Forms are short and direct. Prevalidate required fields, UUID format, 1–500 character reasons, date ranges, latitude/longitude and 6-place precision, zone name/geometry size, and `expectedVersion` using the latest server value. Server validation is final. Do not autosave zone policy or action reasons. The lab order form shows the exact line-item arithmetic, a conspicuous “local test price supplied by you” label, and a review step before POST; it disappears outside local/test. Zone editing may have a map with coordinate input and raw GeoJSON fallback; validate geometry on the server before treating it as serviceable. `UNRESTRICTED` → `ENFORCED` asks for an explicit consequence review and displays the active-zone prerequisite.

## 21. Search

Search scope is deliberately narrow. Customer orders have backend filters but no text search. Dispatcher can open a known UUID and the palette can remember locally opened IDs; each lookup uses role-specific permitted endpoints and handles 404 without revealing existence to another role. Merchant branch ID is supplied by an authorized context contract or explicit lab configuration; there is no branch-name search. Do not display a global search box until server-side scoped search endpoints exist. Debounce only inputs that actually query an endpoint, not local navigation.

## 22. Notifications

The current API returns only the newest 1–20 actor-owned records (`kind`, `state`, `orderId`, `assignmentId`, `createdAt`). Render a chronological Activity list with stable labels and links where the current role can open the target. A stale offer notification opens the offer list and rechecks current state; it does not produce an Accept button itself. There is no unread flag, mark-read mutation, unread count, push/email/SMS channel, or pagination. The nav may show a neutral dot only for *new this session* client-observed records, clearly ephemeral; default to no badge. Distinguish these derived notifications from inline command confirmation, errors, open incidents, and operator alerts.

## 23. Major Screen Specifications

Each screen below includes purpose, role, information/actions, API, layout/interaction, states, responsive/motion, and accessibility. Shared loading/error treatments in §27 still apply. “Empty” always means an authorized successful response with no content; `403`/`404` never become an empty state.

### 23.1 Sign in and access state

**Purpose / role:** enter the authorized workspace; anyone may reach sign-in, authenticated actors without a usable role see Access. **Information / action:** product name, “Sign in with FleetFlow identity,” and a short access explanation; redirect to Keycloak. No registration/password fields in FleetFlow. **API:** new browser OIDC client and proposed `GET /api/v1/me` context. **Layout / interactions:** narrow centered panel with no marketing hero; return to original deep link after login if authorized. **Loading:** “Checking access…” in stable panel. **Empty/error:** unprovisioned actor explains that an operator must provision the application actor/role; identity service/network failure offers Retry without exposing tokens. **Responsive/motion/a11y:** same simple layout at all sizes; no entrance animation; focus heading on return and announce denial.

### 23.2 Customer My orders

**Purpose / role:** CUSTOMER scans owned orders. **Information / actions:** branch, status, total/currency, created time; open order, server filters and sort. Local-only New order is separate. **API:** `GET /orders`; query supports `branchId`, `status`, `from`, `to`, `page`, `size`, `sort`. **Layout / interaction:** open page header → filter row → table → true server pagination; row navigates. **Empty:** first-use “No orders are linked to this account”; filtered “No orders match these filters” with Clear filters. **Loading/error:** stable table skeleton, retain old rows on refresh; 403 access message, 503 Retry. **Responsive/motion/a11y:** mobile rows show status/branch/time/amount with detail link; announce sorted column and results count; no animated reshuffle.

### 23.3 Customer Order detail and tracking

**Purpose / role:** CUSTOMER understands one owned order. **Information / actions:** current exact status, saved pickup/delivery city and line, items, total, time, stage spine; cancel only in `CREATED`/`CONFIRMED`; optional fresh position and observed time. **API:** `GET /orders/{id}`, `/tracking`, `/tracking/stream`, early `/cancel`. **Layout / interaction:** main progress/items, narrower action/context pane; map appears only with fresh coordinates and a text alternative. Stream begins with snapshot; reconnect refetches snapshot; never replay connection-local SSE IDs. **Empty/error:** no item-empty state for valid order; 404 says unavailable without differentiating foreign/absent, stale position says “Live location unavailable” while status remains visible. **Loading:** render saved detail before tracking; stream reconnect state is small and inline. **Responsive/motion/a11y:** stack stage/action/items, do not require map use; aria-live status text, reduced-motion marker replacement.

### 23.4 Customer Activity

**Purpose / role:** CUSTOMER sees derived status messages. **Information / action:** newest notification records, timestamp and permitted order link; refresh. **API:** `GET /notifications/me?limit=20`. **Layout:** chronological text list with event labels, no fake unread badges. **Empty/loading/error:** “No updates yet”; rows skeleton; “Updates delayed” on 503 while order state can still be checked directly. **Responsive/motion/a11y:** single column; append only after refetch, announce newly loaded count, never auto-scroll.

### 23.5 Local test order creation

**Purpose / role:** CUSTOMER in loopback local/test exercises intake. **Information / actions:** known branch ID, delivery WGS84 address, currency, item quantities and *caller-supplied* amounts; review and Create test order. **API:** local/test `POST /orders`. **Layout:** simple sections for destination/items/review; calculated preview is advisory, returned total is final. **Empty/loading/error:** start with one editable item; submit pending locks form; 400 field focus, `ZONE_UNSERVICEABLE` gives destination/zone explanation, 409 refreshes context. **Responsive/motion/a11y:** single-column on mobile, persistent labels, decimal input mode; no production nav entry.

### 23.6 Merchant Branch orders

**Purpose / role:** MERCHANT member works one exact active branch. **Information / actions:** narrow summary ID/status/total/time; confirm, start preparation, ready, cancel with reason. **API:** `GET /merchant-branches/{branchId}/orders?page&size` plus four lifecycle POSTs. **Layout:** branch context, paged table, row action menu; no fake full-detail drawer. **Empty:** “This branch has no orders”; optional lab link only if a separate CUSTOMER role and lab route exist. **Loading/error:** preserve rows during page refresh; scoped 404 says branch unavailable, 409 refreshes row and explains state change. **Responsive/motion/a11y:** compact list with explicit actions; confirmation focus, action row highlight on returned status.

### 23.7 Merchant Zones and policy

**Purpose / role:** MERCHANT member defines branch coverage. **Information / actions:** active/inactive zones, polygon geometry, version, policy `UNRESTRICTED`/`ENFORCED`, coordinate serviceability preview, create/edit/activate/deactivate, versioned mode update. **API:** six zone/serviceability operations. **Layout:** zone list + actual geometry map where available, editor drawer with GeoJSON fallback, policy strip separated from per-zone edits. **Empty:** “No zones yet”; enforcing disabled until an active zone exists, per server. **Loading/error:** list skeleton and separate policy loading; `ZONE_VERSION_CONFLICT`/`ZONE_POLICY_VERSION_CONFLICT` offers Reload and compare, invalid geometry retains draft, 404 hides inaccessible branch. **Responsive/motion/a11y:** list-first with map below on mobile, keyboard geometry input, textual point result; drawer 240 ms, no animated polygon morph that changes meaning.

### 23.8 Driver On shift

**Purpose / role:** DRIVER sees durable duty state and the next permitted action. **Information / actions:** `operationalStatus`, active/suspended, version, Go online/Available/Pause/Offline; own current offers and deliveries preview. Location sharing is a separate explicit control, never inferred from AVAILABLE. **API:** `GET /drivers/me`, four lifecycle POSTs, offer/delivery reads, location/heartbeat writes only after sharing starts. **Layout:** large but restrained state line, one primary next step, small task list. **Empty:** “No active task”; if no active vehicle, explain why Available failed without claiming to list vehicles. **Loading/error:** self status skeleton; 409 refetch; suspended/inactive status stays readable and actions disable. **Responsive/motion/a11y:** driver-first mobile layout and 44 px targets; status change is text plus brief highlight, not color alone.

### 23.9 Driver Offers

**Purpose / role:** DRIVER responds to an exact unexpired offer. **Information / actions:** pickup/delivery lines/cities, deadline, current status; Accept or Reject. **API:** `GET /drivers/me/offers?limit=20`, `POST /{offerId}/accept|reject`. **Layout:** active offers before history, each row an actual decision surface; server expiry controls validity. **Empty:** “No offers right now”; past rejected/expired remain labelled history. **Loading/error:** limit to one row pending; `OFFER_EXPIRED` refreshes and explains loss, 409 refreshes all current offers. **Responsive/motion/a11y:** one-column decisive controls, deadline announced without noisy second-by-second live region; subtle state highlight only after response.

### 23.10 Driver Deliveries and detail

**Purpose / role:** DRIVER progresses assigned work. **Information / actions:** pickup/delivery address text, status and sequence; pickup from ASSIGNED, start transit from PICKED_UP, complete from IN_TRANSIT, fail from PICKED_UP/IN_TRANSIT with reason. **API:** `GET /drivers/me/deliveries?limit=20` and four POST commands. **Layout:** current task first; history below; detail shows stage spine and one next action. **Empty:** “No deliveries assigned”; not “You are unavailable.” **Loading/error:** cached delivery remains while refreshing; `DELIVERY_CONFLICT` refetches before next action. **Responsive/motion/a11y:** mobile-first task view with large action; provide addresses as selectable text, not only a map; terminal state uses brief textual confirmation.

### 23.11 Driver Activity

**Purpose / role:** DRIVER inspects derived offer notices. **Information / action:** newest records, timestamp, order/assignment reference when returned; open own offers. **API:** `GET /notifications/me?limit=20`. **Layout:** chronological list. **Empty/loading/error:** same pattern as Customer Activity; a delayed notice cannot hide a currently offered row. **Responsive/motion/a11y:** single column, current offer state checked independently, no stale Accept action attached to a notice.

### 23.12 Dispatcher Open order and workbench

**Purpose / role:** DISPATCHER acts on a *known* order ID. **Information / actions:** newest attempts with outcome/decision time/config, candidate rank/reason/score/age/distance, assignments and incidents; Match when the server accepts READY_FOR_PICKUP, Offer one latest eligible candidate, reasoned Retry/Reassign/Cancel/Fail/Resolve as applicable. **API:** match/attempts/offers/assignments/recovery/incidents; there is no dispatcher order-detail or queue GET. **Layout:** ID resolver → evidence lane and decisions → URL-backed Attempts/Assignments/Incidents; show order ID, not fabricated order summary. **Empty:** “No attempts for this order” or “No incidents recorded”; if all three reads 404, say “Order unavailable.” **Loading/error:** independent tab loading, keep successful tabs; 409 or `ASSIGNMENT_CONFLICT` refetches attempts and assignments; 503 holds prior evidence and disables writes. **Responsive/motion/a11y:** candidate rows become labelled blocks, each reason remains legible; action panel follows evidence on mobile, no auto-offer; drawer reveals full immutable attempt settings.

### 23.13 Dispatcher Daily transitions

**Purpose / role:** DISPATCHER sees derived UTC status transition counts. **Information / actions:** date range up to 31 inclusive days, status rows, optional small bars/line; switch to table. **API:** `GET /dispatch/analytics/daily?from&to`. **Layout:** date controls → concise total per returned status/day → chart only when it clarifies a trend. **Empty:** “No transitions recorded in this UTC range”; label possible projection delay. **Loading/error:** retain previous valid range while refreshing; 400 points to range constraint, 503 Retry. **Responsive/motion/a11y:** table scrolls only if needed; chart has equivalent data table, no meaningless animation.

### 23.14 Local lab fixture workbench

**Purpose / role:** ADMIN, local/test only, creates connected demo records. **Information / actions:** staged actor → customer/driver or merchant → branch → membership/vehicle creation, and six by-ID read forms; explicit note that roles and Keycloak users are provisioned separately. **API:** 13 fixture operations. **Layout:** step list with returned IDs and copy controls; no fake entity collection. **Empty:** blank form is expected. **Loading/error:** preserve completed IDs after a failed dependent step; 400 field errors, 409 duplicate/resource conflict, 404 lookup unavailable. **Responsive/motion/a11y:** one-column stages, reveal next only after server success; copy has live confirmation, no secrets in saved form/session.

### 23.15 Operations link and restricted metrics

**Purpose / role:** DISPATCHER/operator finds real system signals without inventing a management API. **Information / actions:** link to private Grafana/runbooks when configured, and optionally a small authorized Actuator metric read (`/actuator/metrics`), labelled with sample time. **API:** `GET /actuator/metrics[/name]` requires DISPATCHER; health liveness/readiness permits anonymous probes but hidden details. **Layout:** utilitarian list of dependencies/backlog/incident count, each with source and interpretation; external monitoring opens in its own secured context. **Empty/loading/error:** “Monitoring not configured for this environment”; independent metric failures show Unknown, never zero; 403 remains forbidden. **Responsive/motion/a11y:** text values and links survive mobile; no continuously animating dials. This screen is conditional on a safe deployment and is not a public Grafana embed.

## 24. Entity Detail Patterns

Customer order detail has saved identity/address/items/price and live tracking. Merchant order **row** has only summary fields and actions. Dispatcher known-order workbench has only attempt, assignment and incident records. Driver delivery detail has task addresses/status but no customer personal profile. Zone detail has name/geometry/active/version. This asymmetry is intentional: separate role views expose only each role's API fields. Each detail identifies its timestamp source and version when present. Show related entity UUID as a copyable link only if the current role has an actual permitted destination. Audit/history tabs must not appear merely because migration tables exist.

## 25. Admin / Operator Interfaces

The local lab is a fixture composition tool, not production administration. It cannot grant roles, deactivate actors, revoke memberships, list all users, edit vehicles, or modify Keycloak accounts. It can create actors/profiles/branches/memberships/vehicles only under the local/test profile and read six fixture types by ID. Production admin provisioning remains out of band; direct SQL and Keycloak Console are operator procedures, never hidden “admin UI” calls.

The operational experience is the existing private Grafana dashboard plus runbooks. It answers database/Redis availability, HTTP errors/latency, outbox backlog/failures/age, consumer effects/quarantine, overdue offers, due recovery, open incidents, and active tracking streams. Those meters are aggregates, not browsable outbox jobs. The UI may deep-link to Grafana only after its access and origin boundary are defined. No browser backup, Kafka retry, poison replay, audit-search, alert-rule edit, or service restart control exists. Phase 11 acceptance remains separate and `IN_PROGRESS`.

## 26. Authentication & Security UX

The current `fleetflow-cli` Keycloak client has direct grants enabled and **standard browser authorization flow disabled**. A separate public browser client with exact redirect/web origins, authorization code + S256 PKCE, and the `fleetflow-api` access-token audience is a prerequisite; do not reuse password grant in the browser. [Keycloak's browser adapter guidance](https://www.keycloak.org/securing-apps/javascript-adapter) recommends a public client with narrowly configured redirects, uses a bearer header for API requests, and keeps tokens in memory. Initialize OIDC before routing; refresh shortly before requests; clear in-memory token and role-scoped caches on logout, subject switch, or access failure. Never put tokens in URLs, localStorage, logs, telemetry, screenshots or error text.

The backend is a stateless bearer resource server. `401` means sign in/refresh may be needed; `403` means authenticated but no active actor/role; scoped `404` hides both missing and foreign resources. Do not say “this order belongs to another account.” Role labels from OIDC claims are display hints at most; actual permissions come from the proposed server context and each API response. Check active membership again on each merchant call; never cache an authorization verdict past the request. Sensitive cancellation, reassignment, delivery failure, incident resolution and enforcement mode changes have consequence dialogs and reasons. Rate-limit `429` honors `Retry-After` and avoids automatic retry storms.

Native `EventSource` cannot attach the required bearer header. Use an authenticated `fetch` stream parser with abort on route change/logout, or a separately designed same-origin BFF proxy; never use a token query parameter. Each reconnect must request a fresh tracking snapshot because stream IDs are connection-local. Limit one active stream per open order view and close it when hidden/terminal; handle stream cap and five-minute/token expiry without uncontrolled reconnects. Geolocation is opt-in per driver session, needs browser permission and a visible “Sharing location” state, and must stop on logout/role loss/offline. Heartbeat does not prove GPS freshness. Full permission, object-scope, and profile enforcement stays server-side.

## 27. Loading / Empty / Error States

| Situation | User-visible response and recovery |
| --- | --- |
| Initial auth/context | Stable app frame and “Checking access”; no flash of unauthorized nav. |
| Route/table load | Geometry-matching skeleton; existing rows remain during refetch with subtle refresh label. |
| Partial refresh | Preserve good sections and mark failed section only; do not replace known durable state with stale derived notice. |
| Empty authorized result | Explain whether first-use or filters caused it; clear filters or act only where a real create API exists. |
| `400 INVALID_REQUEST` / `INVALID_ZONE` | Focus first mapped field; quote safe backend message if useful; preserve draft. |
| `401 UNAUTHENTICATED` | Attempt one token refresh then return to sign-in; keep intended route, clear sensitive cached data. |
| `403 FORBIDDEN` | “This account cannot use this area”; offer workspace switch/sign-out, no resource clues. |
| `404 RESOURCE_NOT_FOUND` / `NOT_FOUND` | “This item is unavailable”; no owner/existence inference. |
| `409 LIFECYCLE_CONFLICT`, `ORDER_STATE_CONFLICT`, `ASSIGNMENT_CONFLICT`, `DELIVERY_CONFLICT`, `OFFER_EXPIRED` | Keep prior view, fetch canonical state, explain the action no longer applies; never auto-repeat a non-idempotent POST. |
| `ZONE_VERSION_CONFLICT` / `ZONE_POLICY_VERSION_CONFLICT` / `ZONE_EMPTY` / `ZONE_UNSERVICEABLE` | Preserve local draft, explain version/active-zone/destination issue; explicit reload or correction. |
| `429 RATE_LIMITED` / `TRACKING_LIMIT` | Show request-specific delay from `Retry-After` if present; back off and avoid parallel polling/stream loops. |
| `503 DATABASE_UNAVAILABLE`, tracking/network loss | Mark current information as last known with time; disable consequential writes; Retry when reachable. |
| Command success, async projection delayed | Show returned durable state immediately; Activity/analytics show “May update shortly” without implying command failure. |

Map `ApiProblem.code`, safe `message`, `fields`, `timestamp`, and `correlationId` in the API layer. Expose a copyable correlation ID in an expanded error detail for support, never show raw exception/SQL/token. A request that times out after submission has **unknown** outcome: refetch the affected resource before enabling another submit. A 204 location/heartbeat response needs a quiet live connection indicator, not repeated toasts.

## 28. Responsive Design

| Width / device | Navigation and content behavior |
| --- | --- |
| Large desktop (≥1440 px) | 224 px rail; main/detail split; dispatch evidence and decisions side by side; sticky table header in bounded scroll region. |
| Laptop (1024–1439 px) | Default target; rail can collapse to 72 px; action panel ~280 px; tables retain core columns and hide optional diagnostics behind row disclosure. |
| Tablet (768–1023 px) | Labelled nav drawer, one primary data column, context panel becomes a lower drawer; zones map/list are separate tabs with identical data. |
| Mobile (<768 px) | Driver actions and customer tracking first; bottom destinations for the current workspace; merchant/dispatch screens use focused rows and explicit action menus, not squeezed desktop grids. |

At all widths, never hide an action because a column disappeared. No mobile gesture is the sole way to accept an offer, save a zone, or confirm cancellation. An active dialog fits within the viewport and keeps its final action visible above the keyboard. Use 44×44 px touch targets for frequent controls. Test at 320 px as a minimum stress case and at 200% zoom; horizontal scrolling may be used for dense evidence tables only with clear cues and keyboard reachability.

## 29. Accessibility

Target WCAG 2.2 AA fundamentals. Use landmarks, one page `<h1>`, labelled headings, real `<table>` semantics where data are tabular, appropriate `aria-sort`, visible 2 px focus, keyboard-operable menus/drawers, focus trap and return in dialogs, and live-region announcements for submitted command outcomes. Do not over-announce the offer countdown, heartbeat or streaming GPS changes. Every chart has a textual/table equivalent, every map has position/coverage text and keyboard coordinate input, every status has a word, and color alone conveys nothing. Validate contrast for disabled/help text, not only primary body. Support reduced motion, screen magnification, high-contrast mode and touch input. Test actual tab order and screen-reader descriptions on the core customer, driver, merchant, dispatcher and zone workflows, in addition to automated checks.

## 30. Frontend Architecture

There is no existing frontend stack. Proposed implementation: a `web/` TypeScript + React application built with Vite, kept separate from the Spring modules. This fits a role-specific, interactive client over the current JSON/SSE API without introducing server rendering. [Vite documents a React/TypeScript starter](https://vite.dev/guide/). Use a typed route tree, route-level code splitting, feature-owned UI and queries, one small design-token layer, and accessible headless primitives where they save implementation risk. Pin and review actual package versions when implementation begins; the specification does not assert versions.

```text
web/src/
  app/             # boot, OIDC, route guards, shell, theme, error boundary
  api/             # fetch client, ApiProblem parser, auth header, typed contracts
  features/
    customer/      # orders, tracking, activity, lab-only intake
    merchant/      # branch orders, zones and policy
    driver/        # duty, offers, deliveries, location sharing, activity
    dispatch/      # ID resolver, attempts, offers, incidents, daily report
    lab/           # local/test fixture composition
    operations/    # permitted metrics and external monitoring links
  shared/          # tokens, table, dialog, form controls, icons, date/ID format
  test/            # contract fixtures, route/role and accessibility helpers
```

Feature folders own their API hooks, view models, screens and tests. Shared contains only cross-domain primitives. Keep server DTO fields distinct from presentation labels and do not synthesize missing values. An API contract snapshot generated from local/test `/v3/api-docs` can type-check the 57 current operations, but generation must preserve profile/role caveats; default/prod do not expose Swagger. Add a CI diff gate when frontend work begins so new backend routes cannot silently lack a UI decision.

For local development, a Vite proxy can send `/api` to the loopback Spring app while Keycloak uses its configured local origin. A real deployment needs an explicit same-origin serving/proxy and OIDC redirect-origin review; this spec does not authorize public exposure of the loopback Compose project. The browser sends bearer headers to Spring; Spring remains stateless. A BFF/cookie architecture is a future security decision, not a hidden assumption in the UI.

## 31. API Integration Strategy

The UI should have one request client with bearer renewal, cancellation, correlation ID propagation, safe problem parsing, `Retry-After` handling, and a no-retry default for POST/PUT. Use current backend field names and UTC ISO timestamps. Unknown status strings render literally in a safe “Unknown state” treatment rather than enabling an action. Round only for display; never alter money/score before sending. Every command refetches its affected durable reads before showing related derived data as settled.

Contract details for typed clients: all IDs are UUIDs. `OrderService.PageView` and merchant `PageView` have `content,page,size,totalElements,totalPages`; histories and notifications return `{content}` only. Customer `OrderView` includes immutable item and public address snapshots; merchant `Summary` has only `id,status,currency,totalAmount,createdAt`. Match returns `attemptId,outcome`, while the attempts read contains ranked/rejected candidate evidence and matching configuration. Offer writes require `{attemptId,driverId}` from the **latest eligible** attempt. Recovery/cancellation/failure/incident resolution bodies require `{reason}` (trimmed 1–500 characters). Zone create uses `{name,geometry,active}`, replace adds `expectedVersion`, policy change uses `{mode,expectedVersion}`, and preview uses `{latitude,longitude}`; GeoJSON must be WGS84 Polygon/MultiPolygon and the backend validates it. Driver location writes require `latitude,longitude,reportedAt`, with optional `heading,speed`; heartbeat has no body. Tracking snapshots have `orderId,orderStatus,orderVersion,deliveryStatus,observedAt,position?,terminal`. The API error envelope is `code,message,path,timestamp,correlationId,fields`.

| Endpoint family → service/hook | Screen | State and errors | Authorization |
| --- | --- | --- | --- |
| `/orders` → `ordersApi` / `useOwnedOrders`, `useOwnedOrder` | Customer list/detail | page skeleton, empty, 400 filters, 404 detail, 503 | CUSTOMER + owned SQL. |
| `/orders/{id}/tracking[/stream]` → `trackingApi` / `useTracking` | Customer detail | snapshot, connected, stale position, reconnect, terminal, 429 | CUSTOMER + owner; bearer stream. |
| `/merchant-branches/{id}/orders` → `branchOrdersApi` | Merchant table | true server page, 404 branch, 409 commands | MERCHANT + current exact membership. |
| `/merchant-branches/{id}/zones|zone-policy|serviceability` → `zonesApi` | Zones | list/policy independent, draft, version conflict, invalid geometry | MERCHANT + current exact membership. |
| `/drivers/me` and lifecycle/location → `driverApi` | On shift | durable state vs ephemeral sharing; 204 heartbeat/fix; 409 | DRIVER self. |
| `/drivers/me/offers|deliveries` → `driverTasksApi` | Offers/deliveries | newest 20, expiry/conflict, command status | DRIVER exact ownership. |
| `/dispatch/orders/{id}/*` → `dispatchApi` | Known-order workbench | parallel bounded reads, conflict/unknown outcome, independent tab errors | DISPATCHER. |
| `/notifications/me` → `activityApi` | Customer/driver Activity | newest 20, derived lag, no unread mutation | CUSTOMER or DRIVER, actor scope. |
| `/dispatch/analytics/daily` → `analyticsApi` | Daily transitions | 31-day inclusive UTC range, derived lag | DISPATCHER. |
| Fixture routes → `labApi` | Local lab | staged create/ID lookup, 400/409/404, never production | ADMIN + local/test; order create CUSTOMER. |
| `/actuator/metrics[/name]` → `opsApi` | Restricted Operations | sample/Unknown/error; no fake queue rows | DISPATCHER, deployment-specific exposure. |

### Required backend prerequisites and honest gaps

| ID / priority | Present limitation | Narrow backend contract needed before the dependent UI can ship |
| --- | --- | --- |
| B0 / block shell | No `GET /api/v1/me` actor-role/capability context. JWT role claims are not authoritative. | Signed read returning active actor ID, current DB roles, and authorized branch contexts (ID/name) or a separate scoped branch list. Never accept actor ID from client for scope. Test immediate revocation and multi-role actors. |
| B1 / block browser auth | `fleetflow-cli` is direct-grant-only. | Provision a separate Keycloak browser public client for code + S256 PKCE, exact redirect/web origins and `fleetflow-api` audience. Verify signed browser round trip and logout. |
| B2 / block normal merchant entry | No production branch membership list or branch profile read. | Scoped `GET /api/v1/merchant-branches/me` returning only active accessible branch IDs/names, or include these in B0. Until then, branch IDs are explicitly configured/entered for a local pilot. |
| B3 / block dispatcher overview | No dispatcher order list/queue/detail or global incident list. | Add bounded, paged dispatcher triage read with durable order status, assignment/incident summary, filtering and explicit authorization. Until then, `/dispatch/open` accepts a known ID; no queue dashboard. |
| B4 / enhance merchant detail | Branch orders expose narrow summaries only; customer detail is owner-only. | Scoped merchant order detail with permitted items/address/status/history as product needs, without reusing customer endpoint. Current merchant UI is row actions only. |
| B5 / enhance driver readiness | No read of GPS/heartbeat freshness or vehicles. | Signed self readiness projection; do not present AVAILABLE as proof of dispatch eligibility until then. |
| B6 / enhance history/activity | Status/zone history tables have no read endpoints; notifications have no unread/paging writes. | Scoped, bounded history/notification contracts only if the workflow proves value. Current UI shows state and newest records. |
| B7 / enhance operator UI | Outbox/quarantine/recovery rows, alerts and backups have no authorized management API. | Purpose-built read projections and audited commands if an in-app operator console is genuinely needed; private Grafana/runbooks cover current operations. |

Only B0/B1 are prerequisites for an authenticated shell; B2/B3 are prerequisites for *self-discovering* merchant/dispatcher workspaces. The current API can support known-ID pilots without pretending these reads exist. These are proposed backend additions, not implemented scope in this document. Production order intake additionally needs trusted catalog/pricing and onboarding design before a New order route can ship outside the local lab.

## 32. State Management

Use TanStack Query or an equivalent server-state cache for read deduplication, cancellation, targeted invalidation, and stale-while-revalidate behavior; its [official query-client documentation](https://tanstack.com/query/latest/docs/framework/react/reference/classes/QueryClient) describes targeted invalidation. Query keys include workspace, current actor, branch/order/delivery ID, filters, page and date range. Clear all actor-bound caches on logout, identity change, `403`, or role revocation. Keep modal state, unsaved form drafts, table density/visible columns, and the command palette in local client state. Persist only harmless view preferences; no tokens, GPS trails, addresses, or private notifications in persistent browser storage.

Mutations: disable duplicate submit, show pending locally, use returned durable state, then invalidate the narrow affected keys. Do not optimistically “accept” an offer, “deliver” an order, enforce a zone, or resolve an incident; concurrent workers or other actors can win. Safe optimistic behavior is limited to local presentation such as selected tab or unsaved row expansion. SSE updates the current tracking query only after schema/owner validation; REST snapshot is authoritative on reconnect. Notification/analytics cache has a distinct derived-lag label and never overwrites the durable order state.

## 33. Performance Strategy

Split routes by workspace; load map tooling only on tracking/zone views, chart tooling only on daily report. Request only currently visible bounded histories and cancel obsolete ID lookups. Keep customer/merchant pagination on the server; do not download all pages to simulate search. Virtualize only after measured row count/DOM cost justifies it (current bounded lists do not). Debounce ID validation and date input locally; do not poll faster than the backend rate classes permit. One active authenticated tracking stream per viewed order, exponential reconnect with a fresh snapshot and visibility pause. Driver location sampling must comply with service rate/age constraints, use geolocation permission and a battery-aware interval; no high-frequency React rerender for every fix. Keep animations on transform/opacity, avoid animated map layout, and measure p95 interaction/route readiness in a representative browser workload before setting hard budgets.

## 34. Testing Strategy

| Layer | Required proof |
| --- | --- |
| Contract | Compare typed clients with registered OpenAPI; verify 57-operation inventory and local/test vs default/prod route absence, including lab `POST /orders`. |
| Component | Buttons/dialogs, table server controls, zone version conflict, offer deadline, status spine, loading/empty/error states, keyboard/focus and reduced motion. |
| Integration | Mock known API bodies and `ApiProblem` codes; verify cache invalidation, no duplicate POST, no false success after timeout, SSE snapshot/reconnect/terminal/401/429 handling. |
| Auth/authorization | Browser OIDC round trip with signed Keycloak token; DB roles, revoked actor/membership, multi-role switch, wrong role, foreign/absent ID indistinguishable in customer/merchant views. |
| E2E | Customer read/cancel/track; merchant lifecycle and zone enforcement; driver duty/offer/delivery; dispatcher match/offer/recovery/incident; lab fixture flow only in local/test. Use real backend + PostgreSQL/Redis/Kafka/Keycloak in isolated test setup for critical flows. |
| Responsive/accessibility | 320 px through desktop, 200% zoom, keyboard-only, screen reader spot checks, reduced motion; automated [Playwright accessibility scans](https://playwright.dev/docs/accessibility-testing) plus manual checks because automation is incomplete. |
| Performance/operations | No polling storm under 429/Redis outage; bounded stream cleanup; no token/GPS/address in logs/browser storage; route/chunk and long-table profiling. |

Existing backend integration tests remain the truth for state races, PostGIS boundaries, outbox dedupe, and security. Frontend tests must assert the *user-visible consequence* of those contracts, not merely duplicate backend unit assertions. Browser E2E uses disposable identities and isolated databases; never mutate normal `fleetflow` volumes for a UI test.

## 35. Implementation Roadmap

Every phase ends with a usable, verified vertical slice. The phases add no claim of production readiness or public traffic. Complete backend prerequisites in small separate changes with signed tests before enabling a dependent route.

| Phase | Scope, screens and components | Backend exposed / dependency | Tests and acceptance |
| --- | --- | --- | --- |
| UI 0 — secure foundation | `web/`, tokens, shell, sign-in/access, route guards, customer My orders/detail and useful empty/error states | B0 + B1; existing owned order reads | Signed OIDC browser flow, revocation/foreign 404, responsive list/detail, keyboard and contract checks. A CUSTOMER can sign in, find and read own order without a placeholder page. |
| UI 1 — customer continuity | Tracking snapshot/stream, Activity, cancellation; lab-only New order | Tracking, notifications, customer cancel; local create | SSE reconnect/stale/terminal/stream-limit E2E, reasoned cancel, lab route absent in prod. Customer can follow and safely act on an order. |
| UI 2 — merchant work | Known-branch orders and lifecycle; branch discovery if B2 lands | Branch page and four commands; B2 for self-discovery | Exact membership/revocation tests, page controls and 409 conflict. Merchant can process a branch's work; known-ID pilot is labelled until B2. |
| UI 3 — driver work | On shift, location sharing, offers, deliveries, Activity | Self/lifecycle/location, offers, delivery, notifications; B5 optional | Real offered/deadline race, no stale GPS claim, geolocation permission, mobile keyboard/touch E2E. Driver can complete a delivery. |
| UI 4 — dispatch evidence | Known-order resolver/workbench, match, ranked candidates, offers, recovery and incidents | Per-order dispatch/recovery APIs; B3 for a real queue | Concurrent offer/conflict/timeout, no false retry, incident resolution E2E. Dispatcher can finish a known-ID case. |
| UI 5 — coverage and operations | Zone editor/policy/preview, UTC daily report, restricted monitoring link, local fixture workbench | Zone APIs, daily analytics, Actuator/private Grafana, 13 fixtures | PostGIS inside/boundary/hole tests through UI, version conflict, UTC limits, production fixture exclusion. Merchant controls coverage; dispatcher can read derived report; lab is isolated. |
| UI 6 — completion | Visual QA against reference lock, dark mode only if complete, responsive/a11y/performance/security polish, missing backend reads from B2–B7 only when delivered | Contract and deployment review | Cross-role browser matrix, WCAG manual/automated checks, stable reduced motion, load/stream profiling, no dead controls. All shipped routes have real API effects. |

UI 0 is the first releaseable slice; until B0/B1 are delivered, designers can prototype but must not claim a functioning authenticated product. B3 may be pursued in parallel with UI 2–3, but its queue screen remains absent until the backend exists. Keep phase status `IN_PROGRESS` when a mandatory gate is unverified, as with existing FleetFlow phase practice.

## 36. Definition of Done

The frontend is complete only when all 57 current operations have a deliberate surface or documented no-direct-UI reason, local/test operations cannot appear or succeed in default/prod, every role receives an honest usable starting point, and no screen claims a missing API. Primary customer/merchant/driver/dispatcher workflows must use signed identity and current DB authorization, handle loading/empty/error/conflict/429/unknown-outcome states, and show durable vs derived information distinctly. Responsive layouts, keyboard navigation, labels, contrast, reduced motion, safe token handling and stream cleanup must pass browser verification. Every visible button must have a real authorized effect. Critical real-backend E2E and frontend contract tests must pass on the delivered commit; visual QA must preserve the reference lock. Phase 11 production acceptance remains independent of UI completion.

### Final repository coverage audit

- Controllers checked: `ActorController`, `CustomerController`, `MerchantController`, `BranchController`, `MembershipController`, `MerchantBranchOrdersController`, `DriverController`, `VehicleController`, `DriverSelfController`, `DriverLifecycleController`, `DriverLocationController`, `OrderController`, `LocalOrderCreateController`, both order lifecycle controllers, `DispatchController`, `AssignmentController`, `DriverOfferController`, `RecoveryController`, both delivery controllers, `TrackingController`, `ZoneController`, and `EventReadController`. Their 57 `/api/v1` operations are accounted for in §5.
- Security checked: `SecurityConfiguration`, actor resolution, OIDC realm/client and JWT validation, exact object scope, profile-only fixture boundaries, API error shape and route limits. Frontend capability display cannot replace backend checks.
- Non-HTTP behavior checked: offer expiry and recovery sweeps, outbox/Kafka projection, Redis location, SSE lifetime and freshness, Grafana/Actuator metrics, backup/restore runbooks. Their UI treatment is status/explanation or restricted external tooling; no fictitious operator commands.
- Deliberate omissions recorded: production intake/pricing, user/role management, branch and dispatcher discovery, merchant full detail, global search, unread notifications, audit/history browser, full GPS history, payment, ETA and public observability. B0–B7 state the narrow read contracts needed where the future experience benefits.
- Final review on implementation must re-run this audit against the registered routes and the current default/prod profile, then update this document when backend contracts change.
