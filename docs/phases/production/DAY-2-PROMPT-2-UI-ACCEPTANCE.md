# Day 2 Prompt 2 — real-browser UI acceptance

**Run date:** 2026-10-15 (Africa/Cairo)<br>
**Status:** COMPLETE for the repository-controlled Day 2 vertical journey. This acceptance used the `local` application profile and fresh disposable services. It is not production Compose validation, a release approval, or a public deployment.

## Journey exercised

`python scripts/Run-Day2VerticalJourney.py` launched a uniquely named Compose project with fresh PostGIS, Redis, Kafka, and Keycloak services, then started the packaged FleetFlow application on a loopback port. Keycloak imported isolated synthetic users with per-run random passwords. The browser used the real FleetFlow UI, API, authorization, validation, and tracking stream.

The reviewer path completed as follows:

1. Authenticated an operator through Keycloak Authorization Code + PKCE. Playwright verified `response_type=code`, an S256 challenge, and no credentials in the URL or browser local/session storage.
2. Used the operator UI to seed generation 1 and deliberately reset to generation 2. Both actions waited for HTTP success and showed the returned generation/result.
3. Signed in as the driver, went online and available, shared the browser's granted test location, and refreshed the heartbeat.
4. Signed in as the customer, verified that `/workspace/admin` was unavailable, then created a local/test order through the customer form and real `POST /api/v1/orders` API.
5. Kept the customer's order detail open and observed state changes over one authenticated SSE stream.
6. Signed in as the merchant and confirmed the branch order, started preparation, and marked it ready for pickup.
7. Signed in as the dispatcher, started matching, saw the eligible driver, and sent the offer.
8. Returned to the driver session, saw the real offer and delivery addresses, accepted it, confirmed pickup, started transit, shared a position, and completed delivery.
9. Observed `DELIVERED` and `Tracking ended` in the customer UI; signed out and verified the signed-out page.

The browser-driven lifecycle was `CREATED → CONFIRMED → PREPARING → READY_FOR_PICKUP → DISPATCHING → DRIVER_ASSIGNED → PICKED_UP → IN_TRANSIT → DELIVERED`. An independent SQL postcondition, run after the browser completed, confirmed the created order persisted as `DELIVERED`, version 8, with all nine states in lifecycle history.

## UI/client changes

- Reused the locked refined-v2 shell, routes, role-filtered navigation, typed API client, `ApiProblem` error mapping, customer order views, merchant/dispatcher/driver workspaces, and bounded SSE implementation.
- Named the shared sidebar landmark `Workspace sidebar` after the real-browser Axe audit found the unnamed complementary landmark.
- Made operator seed/reset success visible only after the backend mutation succeeds, including the returned generation and synthetic order count. Failed/conflicting mutations retain an actionable error without announcing success.
- Added `web/e2e/day2-live/vertical-journey.spec.ts`, an isolated Playwright config and script, and focused operator mutation tests. The dedicated config retains failure traces for diagnosis; the harness scrubs OAuth values, credentials, headers and network bodies before CI may upload the trace ZIP. Automatic failure screenshots are disabled. A failed scrub blocks artifact upload.

## Verification

| Check | Result |
| --- | --- |
| `npm run lint` | PASS |
| `npm run build` (includes TypeScript typecheck) | PASS |
| `npm run test` | PASS — 47 tests across 13 files |
| `npm run test:e2e:day2` via the real-stack runner | PASS — 1 journey, 23.4 seconds |
| Axe browser checks | PASS — six audited workflow states, zero violations after the landmark fix |
| Responsive checks | PASS — customer and driver at 390 px, no horizontal document overflow |
| Browser runtime | PASS — no captured page errors across the signed sessions |
| SSE | PASS — one customer stream request for the active order; live state propagation and terminal state observed |
| Persisted completion | PASS — independent PostgreSQL check returned `DELIVERED|8` and the full lifecycle |

The browser also asserted that seed/reset used real successful responses, the driver offer was visible and actionable, the customer saw merchant/dispatch/driver transitions, and sign-out completed. Component/API tests cover conflicting seed responses and typed mappings for API failures; existing stream tests cover bounded reconnect, cancellation, terminal/error handling and cleanup after unmount.

Evidence was retained under `%LOCALAPPDATA%/FleetFlowDay2Audit/2026-10-15/vertical-journey/`: `vertical-result.json`, `database-postcondition.json`, redacted logs, and customer/driver desktop and mobile screenshots. The successful browser result records seed generation 1, reset generation 2, one tracking stream, terminal status, and logout.

The only direct database setup before the browser journey was insertion of the isolated bootstrap ADMIN actor/role mapping required to use the operator seed/reset UI. The order, merchant, dispatch, offer, driver, tracking, and completion operations were performed through the browser and real APIs. SQL was used again only after completion to verify the persisted postcondition.

## Boundaries and Prompt 3 handoff

- Customer order creation remains a verified `local`/`test` backend contract and was enabled only for this disposable local run. This does not show that production supports browser order intake.
- This is one connected lifecycle path through the personas involved. It does not claim full Day 3 acceptance of every persona workflow, zone editing, every conflict race, tile delivery/CSP, or production network behavior.
- The runner removed its uniquely named containers, network, and volumes and deleted the generated private realm/configuration/password files. It did not use or reset the existing developer Compose volumes.
- Continue with Prompt 3 only: CI/release integration, production Compose validation, security/supply-chain scans, backup/restore smoke, and evidence closure. No public deployment was performed.
