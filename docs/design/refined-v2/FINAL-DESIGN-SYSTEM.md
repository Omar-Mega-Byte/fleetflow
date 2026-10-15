# FleetFlow refined interface system

Status: **second-pass design target for review**<br>
Artifact: [`refined.html`](refined.html)<br>
Data: **synthetic preview only; no backend connection**

## Product posture

FleetFlow uses one coherent product shell:

- Deep evergreen navigation establishes operational identity and role context.
- White and cool-neutral work surfaces keep dense task content readable.
- Emerald marks committed actions; cyan marks a fresh position; amber marks waiting/expiry; red marks intervention.
- Technical contract evidence lives in a dedicated drawer rather than interrupting ordinary tasks.
- Tracking and Zones use a map/detail relationship only where the selected role owns the data.

## Exact CSS tokens

```css
:root {
  --shell: oklch(0.205 0.035 166);
  --shell-raised: oklch(0.245 0.038 166);
  --shell-border: oklch(0.34 0.04 166);
  --shell-fg: oklch(0.95 0.008 165);
  --shell-muted: oklch(0.74 0.025 165);

  --bg: oklch(0.972 0.006 185);
  --surface: oklch(0.995 0.002 185);
  --surface-subtle: oklch(0.952 0.009 185);
  --fg: oklch(0.205 0.022 175);
  --muted: oklch(0.505 0.022 180);
  --border: oklch(0.865 0.014 185);

  --emerald: oklch(0.505 0.135 162);
  --emerald-hover: oklch(0.445 0.135 162);
  --emerald-soft: oklch(0.93 0.045 162);
  --cyan: oklch(0.68 0.135 210);
  --cyan-soft: oklch(0.94 0.035 210);
  --amber: oklch(0.72 0.145 78);
  --amber-soft: oklch(0.955 0.042 78);
  --red: oklch(0.595 0.19 28);
  --red-soft: oklch(0.95 0.045 28);

  --font: "Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif;
  --mono: "Cascadia Mono", "SFMono-Regular", monospace;
  --r-sm: 6px;
  --r-md: 8px;
  --r-lg: 10px;
  --micro: 140ms;
  --standard: 220ms;
}
```

## Typography

One UI family is used throughout: Segoe UI Variable Text with system fallbacks.

| Role | Size | Weight | Notes |
|---|---:|---:|---|
| Page title | 28 px | 720 | Compact, -0.035 em tracking |
| Section title | 14 px | 650–700 | Used in panel headers |
| Body and controls | 14 px | 400–680 | Default task text |
| Meaningful metadata | 12 px minimum | 400–640 | Freshness, labels, timestamps |
| Identifier | 12 px | regular mono | Tabular UUID/order/version content |

## Layout

- Desktop shell: 236 px navigation + fluid work canvas.
- Desktop work canvas: maximum 1450 px, 14–40 px responsive gutters.
- Main two-column operations grid: fluid primary region plus 290 px minimum context region.
- Breakpoint at 1060 px: primary/context grids stack; sidebar contracts to 210 px.
- Breakpoint at 760 px: sidebar becomes a compact role header and bottom navigation appears.
- Breakpoint at 420 px: metrics become single-column and modal actions stack.
- The order grid has five fluid columns with explicit minimums. Mobile replaces it with dedicated stacked order rows; it does not hide or clip desktop columns.

## Navigation and role model

The role selector changes the complete workspace context rather than mixing objects from multiple roles.

| Role | Available surfaces |
|---|---|
| Customer | Command Center, Orders, Detail, Tracking, Activity |
| Merchant | Command Center, Orders, Detail, Zones, Activity |
| Driver | Command Center, Detail, Tracking, Activity |
| Dispatcher | Command Center, Detail, Dispatch, Activity |
| Admin | Command Center, Activity, Operations |

Unavailable surfaces remain visible for evaluation but resolve to an explicit role-boundary state with a one-click preview-role switch.

## Component variants

### Buttons

- Primary: emerald fill, white label, darker emerald hover.
- Secondary: white surface, neutral border, cool-neutral hover.
- Intervention: white surface, red text and tinted border.
- Icon button: 38 × 36 px desktop; descriptive accessible name required.
- Every control has a visible cyan focus ring and paired foreground/background hover state.

### Navigation

- Desktop item: 42 px minimum height with 17 px line icon and full label.
- Selected item: raised evergreen fill plus border; never a color-only cue.
- Restricted item: lower emphasis with a visible `role` suffix on desktop.
- Mobile item: 88 × 58 px, horizontally scrollable, full icon and readable label.

### Status

- Fresh: cyan dot + text.
- Committed/available: emerald dot + text.
- Waiting/expiring: amber dot + text.
- Intervention/failure: red dot + text.
- Color is always paired with a label.

### Data rows

- Desktop: five-column CSS grid, 42–46 px typical height, no fixed-width table overflow.
- Mobile: dedicated card row with order/status header, movement line and freshness/version footer.
- Row selection opens the selected-object drawer and restores focus when closed.

### Panels and context

- Working panels use one border and 8 px radius; elevation is reserved for overlays.
- Lifecycle exceptions stay adjacent to the active object.
- Context drawers hold technical evidence and selected-object detail.
- Dialogs are reserved for incidents, versioned zone replacement and audited replay inspection.

### Map/detail modules

- Tracking plots at most one optional authorized driver position.
- Pickup and destination appear as address text only; no markers or connectors are derived from `PublicAddress`.
- The standalone prototype shows a clearly labeled unavailable basemap state.
- Zones render the active synthetic authorized GeoJSON polygon and expose its exact specimen payload.

## State model

Every main surface supports:

- Normal: synthetic role-appropriate content.
- Loading: stable layout with reduced motion-safe progress indicator.
- Empty: useful next step without a fake production order-creation action.
- Error: recoverable copy and return to last visible state.
- Partial: task content remains visible with a freshness/coverage banner.
- Restricted: explicit role boundary and role-switch action.
- Conflict: zone replacement asks the user to reload the active version.

## Motion and focus

- Micro feedback: 140 ms for hover, selection and button response.
- Structural feedback: 220 ms for drawers and dialogs.
- Animated properties: transform and opacity only.
- `prefers-reduced-motion` removes animation and reduces transitions to near-instant feedback.
- Drawers and dialogs move focus inside on open, trap Tab/Shift+Tab, close on Escape and restore focus to the trigger.
- Async preview outcomes use a polite `aria-live` toast.

## Prototype boundary

- All data, counts, timestamps, positions, GeoJSON and outcomes are synthetic.
- No control calls FleetFlow APIs, starts SSE, changes an order, mutates a zone, replays an outbox record or creates an incident.
- Production implementation must preserve current OIDC PKCE, ownership, branch scope, driver self scope, dispatcher order scope, admin audit requirements and API contracts.
