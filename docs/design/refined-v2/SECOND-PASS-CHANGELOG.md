# FleetFlow second-pass changelog

Date: **2026-10-14**<br>
Target: [`refined.html`](refined.html)<br>
Source explorations: preserved unchanged

## Selection applied

- Uses Direction A’s operational SaaS composition as the base.
- Adopts Direction C’s deep evergreen shell and exception/recovery emphasis.
- Uses Direction B’s map/detail relationship only in Tracking and Zones.
- Keeps one visual system: dark navigation shell plus light operational workspace.

## Findings addressed

### Layout and overflow

- Replaced the clipped desktop table with a fluid five-column CSS grid.
- Added dedicated mobile order rows at 760 px instead of shrinking or concealing desktop columns.
- Added explicit `min-width: 0`, intrinsic wrapping and bounded content regions throughout.
- Filled previously sparse work areas with role-relevant exception, freshness and lifecycle context.

### Role consistency

- Added Customer, Merchant, Driver, Dispatcher and Admin preview roles.
- Each role receives a consistent dataset and an explicit allowed-surface map.
- Removed customer views that mixed merchant or dispatcher-only records.
- Added role-boundary states for unavailable surfaces instead of presenting omniscient access.

### Contract correction

- Removed pickup/destination map markers and connectors.
- Pickup and delivery use `line1` and `city` text only.
- Customer Tracking can show one optional authorized driver position.
- Tracking explicitly shows that the standalone basemap is unavailable and never implies a route or ETA.
- Merchant Zones render a synthetic authorized GeoJSON specimen with versioned validation and conflict feedback.

### Task copy and technical detail

- Replaced API/query explanations in ordinary flows with concise task labels: `Search this page`, `Last observed`, `Waiting for location`, `Partial coverage` and contextual recovery messages.
- Moved identity, contract and capability evidence into the Technical Details drawer.
- Kept all preview actions clearly synthetic.

### Navigation and readability

- Replaced C’s abbreviated rail with full labels and readable line icons.
- Set page titles to 28 px, body/controls to 14 px and meaningful metadata to at least 12 px.
- Added readable 58 px mobile navigation targets with full labels.
- Added crisp borders and a 6/8/10 px radius system without gradients, glow or decorative animation.

### Operational identity

- Added compact lifecycle bands, object-adjacent exception cards and freshness language.
- Added dispatcher matching evidence, candidate eligibility, excluded-candidate reason and offer expiry.
- Added merchant zone validation and optimistic version-conflict feedback.
- Added role-scoped notifications plus admin failed-outbox replay inspection and quarantine inspection-only behavior.

### Accessibility and interaction

- Added a skip link, native controls, visible focus rings and redundant status labels.
- Added drawer/dialog focus placement, Tab/Shift+Tab trapping, Escape close and trigger focus restoration.
- Added normal/loading/empty/error/partial/restricted/conflict states.
- Added reduced-motion handling and polite async status messages.

## Source preservation hashes

Recorded before the refined target was created:

| Source | SHA-256 |
|---|---|
| `direction-a.html` | `C29D971D0C619F8F71E99DB79F1131739FE7DE37A8FB0D49A10C850C5EA9175C` |
| `direction-b.html` | `48525C69EE3E46F809759135D6CBAF1E087658B0EA205CF0CED3F72BD5B999B0` |
| `direction-c.html` | `9625C97942D49411D4B4DF1785FAC5EAF3DED075E15D1F7DBF4BE432E7C3247A` |

## Deliberate prototype limits

- The refined target is not accepted implementation and does not select itself for production.
- No live backend, authorized basemap, SSE session, OIDC session or mutation API is connected.
- The rendered GeoJSON and driver position are synthetic specimens for interaction review.
- Parent review should inspect desktop and 390 px mobile output before commissioning implementation.
