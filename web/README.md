# FleetFlow browser workspace

React, TypeScript, Vite and React Router. Node >=22.12; dependencies use `package-lock.json`.

```powershell
cd web
npm ci
npm run lint
npm run typecheck
npm run test
npm run build
npx playwright install chromium
npm run test:e2e
```

`test:e2e` uses a disposable signed OIDC test server and intercepted API responses. For the real HTTPS Keycloak/API/PostGIS/SSE slice, build the six runtime images documented in CI, then run `python scripts/Run-Day1BrowserSmoke.py` from the repository root. It uses only a newly generated Compose project, loopback 80/443 and new volumes. Self-signed TLS and the fixture-only direct-grant setup client are confined to that harness. It removes only its own volumes and generated secrets. Images, logs and screenshots are retained outside the repository; no public deployment occurs.

## Boundaries

- `auth/`: Authorization Code + PKCE S256 through oidc-client-ts. Access, refresh and ID tokens use `InMemoryWebStorage`. Session storage holds only the temporary PKCE transaction. Reload/expiry requires interactive re-authentication; IdP SSO may satisfy it. No silent renewal or browser client secret.
- `/api/v1/session` supplies effective roles from the backend's current PostgreSQL actor mapping. JWT role claims and hidden navigation never grant access. Guards preserve safe return paths and focus the new route heading.
- `api/`: one same-origin client, runtime response decoders, safe problem messages, cancellation and bounded timeouts. At most one retry for an eligible safe read; mutations never retry automatically. Retry-After is respected.
- `src/sse/tracking.ts`: bearer header fetch stream, bounded frames, connect/idle watchdogs, four reconnect attempts, snapshot refresh and cleanup. 401 expires the session. Navigation closes the stream. No bearer token in a URL.
- `src/pages/`: role-aware Orders/detail/Tracking, Dispatch, Merchant, Driver, Zones, Activity and Operations. Known branch/order context is required where discovery is unavailable. Empty, stale, unavailable and ended states are explicit. Maps use authorized positions and GeoJSON; no fabricated routes or ETA.
- Public `VITE_*` configuration may contain only the OIDC authority/client ID and API path. Use exact HTTPS production issuer; API requests stay under `/api/v1` on the same origin (client base `/api`). Never put credentials in frontend environment variables.

Shared states and styles provide semantic controls, visible keyboard focus, route focus, reduced motion and narrow-width layouts. Current redesign evidence, limitations and exact resume step are recorded in [UI.md](../UI.md). `npm run test:performance` profiles the production build with fixtures; it is not live-backend evidence. Local-only order creation requires `VITE_LOCAL_ORDER_CREATION=true`; leave it disabled for public environments.
