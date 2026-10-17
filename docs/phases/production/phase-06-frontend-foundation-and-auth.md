# Release Phase 6 — Frontend foundation and authentication

> Current Day 1 assessment: **COMPLETE for Day 1 slice**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P0.** React/Vite, PKCE, typed API/SSE and all role workspaces exist. The Days 1-3 audit passes three signed journeys across all five personas, zone and dispatch recovery, plus private production-edge PKCE, mobile/focus and synthetic movement checks. Exact updated-commit CI is required; public-issuer/target proof belongs to Phase 9.

## Objective

Create one small, maintainable web client with a coherent visual system, safe OIDC login and a typed, bounded API/SSE integration layer.

## Why this phase comes now

Backend invariants, authorization, error/rate contracts and identity claims are now stable. Phase 5 can proceed in parallel after Phase 4; the shell does not need to wait for operator replay endpoints.

## Existing state and disposition

- **KEEP:** existing API and SSE contracts as the primary interface; Swagger remains an operator/developer reference only.
- **ADD:** one web application, routing, accessible shell, design tokens/components, typed API client, OIDC Authorization Code with PKCE, and browser test foundation.
- **VERIFY:** exact auth/storage model against the OIDC security contract in Phase 2; use same-origin deployment where possible.
- `web/` now contains the Vite/React/TypeScript client, package lock, PKCE authentication, protected routing, API/SSE clients, shared states, responsive role workspaces and a customer orders/details/tracking flow. The 2026-10-15 Day 2 ledger records 47 unit/component tests, three fixture Playwright tests and a separate real-service signed vertical workflow. See the Day 1 frontend design decision and Day 2 acceptance evidence below.

## Existing features to upgrade

- Reuse the existing OpenAPI, OIDC role model, API problem shape and SSE endpoint; change backend contracts only for demonstrated workflow gaps.

## New features to add

- Add one typed React/TypeScript/Vite web client, auth shell, accessible component foundation and browser test setup.

### Day 1 implementation evidence — 2026-10-14

- Uses the frozen `fleetflow-web` public client, Authorization Code + PKCE S256, `openid profile`, the exact local callback/logout paths and no client secret. `oidc-client-ts` credentials use `InMemoryWebStorage`; sessionStorage holds only the temporary redirect/PKCE transaction. Expiry clears memory and returns to interactive sign-in; no silent renewal or refresh-token scope is requested.
- Effective roles come from authenticated `GET /api/v1/session`, backed by PostgreSQL actor roles. Customer list/detail/tracking use the real `/api/v1/orders` and `/api/v1/orders/{id}/tracking` contracts. SSE uses bearer-authenticated fetch, cleanup, bounded reconnect and snapshot refresh.
- Lockfile scripts are `lint`, `typecheck`, `test`, `build`, and `test:e2e`. Unit/component checks include storage privacy, problem mapping, route guards, reusable states, focus, customer rendering, cancellation and SSE cleanup. Playwright exercises signed ephemeral OIDC/PKCE, protected customer route and logout; its API is intercepted, so it is not a full backend or Keycloak acceptance test.
- On 2026-10-14, `npm ci`, `npm run lint`, `npm run typecheck`, `npm run test` (21 tests), `npm run build`, and `npm run test:e2e` all passed. The production output was 351.49 kB JavaScript (106.14 kB gzip) plus 12.16 kB CSS (3.64 kB gzip). The mock-issuer smoke proves browser PKCE and UI routing only.
- The customer order detail component renders lifecycle and tracking status from the owner-scoped API response shapes in a component test. A live browser-to-Keycloak-to-backend/database run remains blocked by the local Docker Engine endpoint failure.
- Visual decisions and the design-research limitation are recorded in [the Day 1 frontend design decision](day-1-frontend-design-decision.md).
- This phase remains `IN_PROGRESS`: provision the named demo subjects in the disposable IdP without frontend credentials, then prove a real browser token against backend authorization, seeded PostgreSQL rows and tracking behavior. Complete mobile/keyboard review and include browser checks in release CI before closing.

## Work included

### Frontend / UI

- **P0:** create one app under web/ using the Vite React TypeScript starter, React Router declarative routes, and a reproducible npm lockfile. React's from-scratch guide documents Vite as a build-tool option and Vite provides a React TypeScript template; verify the current supported Node LTS against Vite when executing this phase ([React guide](https://react.dev/learn/build-a-react-app-from-scratch), [Vite guide](https://vite.dev/guide/)).
- **P0:** build route guards and persona-aware navigation for customer, merchant, driver, dispatcher and operator.
- **P0:** implement a typed API client for the stable problem response; validate inputs; set request timeouts; cancel stale reads; retry only safe reads and only when policy allows.
- **P0:** integrate OIDC Authorization Code with PKCE using oidc-client-ts and the Phase 2 claims, redirect and logout contract. Configure the user store to InMemoryWebStorage; its default browser user store is sessionStorage, so tests must verify the configured behavior. Do not persist bearer or refresh tokens in localStorage/sessionStorage. Re-authenticate through the identity provider after reload/expiry ([oidc-client-ts configuration](https://github.com/authts/oidc-client-ts/blob/main/src/UserManagerSettings.ts)).
- **P1:** add semantic tokens/components, keyboard-visible focus, responsive layout, labels, status language, reduced-motion support and reusable loading/empty/error/permission states.
- **P1:** connect SSE with bounded reconnect/backoff, stream cleanup and scoped snapshot refresh; do not add WebSockets.
- **P1:** ground visual decisions in a small set of real delivery/fleet workflow references before implementation; record the selected reference patterns and design decisions.

### Security

- Same-origin API preferred; otherwise use an explicit exact-origin CORS allowlist. Define CSP and framing policy before deployment.
- Render untrusted strings as text; never inject server content as raw HTML. Avoid browser telemetry that captures synthetic coordinates or identity tokens.

### Tests

- Add Vitest and React Testing Library for component/unit tests with each shared component and auth/API boundary; add Playwright for login, route protection and logout ([Testing Library guidance](https://testing-library.com/docs/react-testing-library/intro/), [Playwright Test](https://playwright.dev/docs/intro)).

## Tests required

- Reproducible clean install, lint/typecheck, component/unit tests and browser smoke in a real browser.
- Auth redirect/state/PKCE, expiry/renewal, forbidden navigation and logout tests.
- API client mapping for 401/403/404/409/413/429/503, timeout and cancellation.
- Keyboard navigation, narrow mobile viewport, focus return and reduced-motion checks.

## Failure scenarios to verify

- Identity provider unavailable, expired session, token refresh/renewal failure, API timeout, malformed response, blocked CORS, SSE disconnect, unsafe server text and browser reload during an action.

## Verification

- Provide npm scripts named lint, typecheck, test, build and test:e2e; verify each from a clean checkout with npm ci, npm run lint, npm run typecheck, npm run test, npm run build and npm run test:e2e.
- Run browser checks against a disposable signed-OIDC stack; no production secret or public account in test fixtures.

## Exit criteria

- A production build runs locally behind the supported origin model and contains no credentials/tokens/secrets.
- Every persona has protected navigation; expired/unauthorized states are understandable and do not leak data.
- Shared API and SSE clients have bounded waits, cleanup and stable error mapping.
- Core shell works at mobile and desktop widths and keyboard-only with visible focus.
- Frontend lint, type, component and browser smoke checks are repeatable from the lockfile; tests prove access/refresh tokens are not present in browser localStorage or sessionStorage.

## Deliverables

- Web app, design tokens/components, OIDC/API/SSE clients, frontend test setup and design decision note.

## Features explicitly not added

- No global state manager, server cache, map vendor, paid routing API, analytics tracker, generic component library or duplicate API backend unless a measured workflow requires it.

## Risk

HIGH. Browser token/session mistakes can expose every synthetic persona; an unnecessary frontend stack can add operational burden.

## Depends On

- Phases 2–3. Phase 4 may proceed in parallel.

## Unlocks

- Complete persona journeys and browser E2E.


## Consolidation audit — 2026-10-14

Clean npm install, audit, lint, typecheck, 39 tests across nine files, production build and signed OIDC smoke pass. Real Keycloak-to-API/PostGIS/browser/lifecycle-SSE flow also passes, with memory-only token reload/re-auth/logout and foreign-role boundaries. Runtime decoders, callback/session-race guards, bounded API/SSE, explicit unavailable location, keyboard route focus, contrast and inspected 1440/390 px screenshots strengthen the foundation. Full persona workflows remain Phase 7.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
