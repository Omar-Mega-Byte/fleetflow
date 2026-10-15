# Day 1 frontend design decision

**Date:** 2026-10-14<br>
**Scope:** Phase 6 shell and first customer order slice

## Research and reference lock

Live Refero research was attempted for a visual style, an order-tracking screen and a delivery tracking flow. Each query returned `NO_SUBSCRIPTION`, so no external reference was available to inspect or claim as an implementation source. The UI is grounded in the repository's real customer APIs and lifecycle/tracking contracts, the scope in [Phase 6](phase-06-frontend-foundation-and-auth.md), and the information hierarchy documented for the operator surface in [the monitoring runbook](../../operations/MONITORING.md). The operator dashboard's dark theme is not copied into the customer product.

## Selected direction

- Use a quiet, warm-light canvas with white order surfaces, dark forest-green text/action color, and neutral gray supporting labels. Amber marks stale or unavailable live updates; red is reserved for cancellation and errors.
- The signed-in customer lands on a short order list. Each row carries the real branch name, creation time, lifecycle status and backend total. The detail view pairs the order lifecycle with a clear live/stale/unavailable tracking state and a compact item/total summary.
- Show no decorative KPI cards, maps, charts or invented location activity. A location appears only when the tracking API provides a recent position; exact coordinates are not displayed. The seeded `CREATED` order truthfully says the driver has not accepted it yet.
- Use local system font stacks and CSS-only marks; the app fetches no third-party font, image, map or analytics asset.
- Keep navigation and copy direct. Backend-derived persona roles decide which navigation entries exist. Non-customer workspaces are signposted as future workflow surfaces without mock operational actions.

## Interaction and accessibility

The shell uses semantic navigation, headings, lists, buttons and links; each asynchronous state has a status or alert announcement. Keyboard focus is visible, a skip link is provided, and the sign-in action supports Tab/Enter. The layout stacks at narrow widths, and reduced-motion preferences disable animation. The browser smoke test uses a 360 px viewport and keyboard activation.

## Verification boundary

The Playwright smoke uses a transient RSA-signed OIDC provider with a real PKCE S256 verifier check, and intercepts the API responses to keep the login/route/logout test disposable. It verifies browser auth plumbing, not Keycloak interoperability or backend data access. Customer component tests use the documented response shapes; the running app itself calls the real customer order and tracking endpoints. A full signed browser-to-PostgreSQL run remains open until the disposable service stack is available and the named synthetic subjects are provisioned in its identity provider.


## Consolidation visual review — 2026-10-14

The audit retained the existing reference lock and consulted bundled Refero craft/accessibility guidance; live Refero again returned `NO_SUBSCRIPTION`. Real-Keycloak desktop (1440 px) and mobile (390 px) customer screenshots were inspected. The narrow shell wraps without horizontal overflow; lifecycle, item total and status remain readable. Removed the unused Manrope font claim, strengthened supporting/future-step contrast, preserved visible focus and 44 px sign-out, and added route-heading focus. Assigned orders with no fresh GPS explicitly say the current delivery location is unavailable; an open SSE connection is not presented as a fresh location. No invented map or movement was added. Current browser and build results are in the audit ledger; the older intercepted-smoke description above remains historical.
