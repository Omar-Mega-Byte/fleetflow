# FleetFlow design exploration comparison

Status: **exploration only; no direction selected**<br>
Data: **synthetic preview content; no live backend connection**

## Shared product truth

- Production must preserve existing OIDC PKCE, API contracts, customer order/tracking ownership, merchant exact-branch scope, driver self scope, dispatcher order scope and audited admin recovery.
- The global Command Center is a composed, role-authorized view. Every direction shows source coverage and freshness instead of implying omniscient access.
- No global live-driver-map API, routed road geometry, delivery ETA, speed, route optimization service or universal Kafka event feed is represented.
- `distanceMeters` and `straightLineSeconds` appear only as dispatcher matching estimates. They are labeled as not being delivery ETA.
- Generic `POST /orders` is not presented as a production workflow. Empty-state recovery uses filters or known contexts; demo data is explicitly bounded and synthetic.

## Reference interpretation

The supplied references were reviewed directly:

- [Linear features](https://linear.app/features): informed compact navigation, list hierarchy and low-friction row scanning; no branded UI was copied.
- [Mapbox on-demand logistics](https://www.mapbox.com/on-demand-logistics): informed map/overlay relationships and the value of visible geographic context. FleetFlow intentionally omits the routing, optimization and ETA capabilities that were not proved.
- [Vercel web interface guidelines](https://vercel.com/design/guidelines): informed focus visibility, native controls, complete loading/empty/error states, explicit labels, stable skeletons, increased interaction contrast and reduced-motion handling.
- Refero was not used because the subscription is unavailable. The supplied craft guidance was applied instead.

## Comparison matrix

| Criterion | A — Operational SaaS | B — Cairo Map Control | C — Mission Control |
|---|---|---|---|
| Density | High, calm; compact rows and consistent table rhythm | Medium interface density over a high-information spatial canvas | High telemetry density separated by dark shell and light content |
| Operational usefulness | Best for order triage, sorting, detail comparison and frequent keyboard work | Best when zone coverage, point freshness and geographic relationships drive action | Best for lifecycle exceptions, recovery queues and infrastructure-aware operations |
| Role honesty | Coverage panel makes source limits visible beside the list | Selected-order sheet prevents the map from implying a fleet-wide driver view | Strongest separation of customer/driver notifications, dispatcher context and admin recovery |
| Distinctiveness | Premium and restrained; domain rigor differentiates a familiar SaaS frame | Most logistics-specific and visually distinctive | Most technical; communicates resilience and backend intelligence |
| Responsive behavior | Sidebar becomes bottom navigation; dense tables remain usable as horizontal work regions | Rail becomes horizontal cards; selected order becomes a compact bottom sheet | Event rail collapses; command navigation becomes a persistent bottom strip |
| Maintenance | Lowest complexity; conventional components and direct data binding | Highest complexity; map viewport, attribution and failure fallback need care | Medium complexity; primitives are simple but cross-source composition needs discipline |

## Direction A — premium operational SaaS

### Composition

- Fixed light sidebar, narrow utility bar and content-first workspace.
- Compact order table is the primary operational object.
- Coverage/freshness appears beside the list; drawers carry secondary detail.
- Best fit for broad daily usage across customer and merchant workflows.

### Component specification

- Navigation item: 34–38 px compact row with filled selected state.
- Data row: 42–46 px with tabular identifier, redundant status dot + label, freshness and owner scope.
- Drawer: 460 px desktop, full-width mobile; opened from a selected row or explicit action.
- Empty state: compact route glyph, no invented create-order CTA, direct filter/context recovery.

## Direction B — Cairo map control

### Composition

- Full geospatial surface with a compact contextual rail and anchored selected-delivery sheet.
- Cairo geography is schematic but intentionally aligned: Giza/Dokki west of the Nile, Zamalek in the river, Downtown east, Heliopolis northeast, Maadi south and New Cairo east.
- Branch zones use purposeful polygons rather than decorative filler.
- Map fallback is built in and labeled as schematic/non-navigation; no paid key or remote tile dependency exists.

### Component specification

- Delivery rail: 300 px desktop; horizontal selector on mobile.
- Selected sheet: anchored above the map, never detached from the selected point.
- Map states: normal, loading, empty, error and offline fallback.
- Point connector: explicitly labeled “not road route.”

## Direction C — mission-control infrastructure

### Composition

- Near-black green-tinted shell carries navigation and authorized activity.
- Light operational content preserves legibility for tables, lifecycles and recovery actions.
- Lifecycle bands, outbox/retry health and exception branches are first-class.
- Best fit for dispatch/admin recovery and engineering-adjacent operations.

### Component specification

- Command strip: five bounded health signals with explicit source context.
- Lifecycle band: filled progress segments plus separate exception state.
- Event rail: capability-aware records, not a universal event stream.
- Recovery cards: failed outbox supports audited replay; quarantine remains inspection only.

## Shared token specification

| Token role | Intent |
|---|---|
| `--bg` | Cool neutral canvas or green-tinted infrastructure shell |
| `--surface` | High-legibility working surface |
| `--fg` | Primary text with strong contrast |
| `--muted` | Metadata, scope and freshness supporting copy |
| `--border` | Crisp grouping without card-heavy elevation |
| `--accent` | Deep emerald brand and committed actions |
| `--cyan` | Fresh/live telemetry only |
| `--amber` | Waiting, expiring or attention-needed state |
| `--red` | Intervention, failure or lifecycle exception |

Typography uses Aptos Display / Segoe UI Variable Display for headings, Segoe UI Variable Text for body content and Cascadia Mono for identifiers. Titles stay at 26–30 px inside product screens; body/controls use 14 px; metadata uses 10–12 px.

## Motion rules

- Micro feedback: 140 ms for hover, selected and focus-adjacent response.
- Structural transitions: 220–240 ms for drawers, sheets and modal entry.
- Animate only `transform` and `opacity`; never use `transition: all`.
- No incessant pulses, autoplay telemetry or decorative motion.
- `prefers-reduced-motion` removes transforms and reduces transitions to effectively instantaneous feedback.

## Prototype-only interactions

- All matching, retry, replay, zone replacement, incident, filter and tracking interactions are local simulations.
- No prototype control calls a backend, opens an SSE connection, changes an order or mutates a zone.
- Cairo map geometry is an offline schematic for layout evaluation, not a routing or navigation product.
- Counts and timestamps are labeled synthetic and must not be quoted as production metrics.
- A production Command Center needs an explicit composition layer that enforces role scopes, exposes per-source freshness and handles partial failure.

## Second-pass critique prompts

1. Does the primary persona need to compare many orders, reason spatially, or recover lifecycle failures most often?
2. Which source combinations can the signed-in role legally see at the same time?
3. Does the mobile composition preserve the highest-value action without pretending dense desktop workflows fit unchanged?
4. Which direction makes missing/stale location, offer expiry, version conflict and recovery state easiest to understand under pressure?
5. Which primitive set is maintainable inside the eventual frontend architecture without weakening existing security and API contracts?
