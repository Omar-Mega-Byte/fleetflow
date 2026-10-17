# Release Phase 7 — Persona workflows and browser acceptance

**Status: IN_PROGRESS. Priority: P0.** The signed 2026-10-17 Day 3 live journey covers customer, merchant, dispatcher, driver and operator workflows, object/role denials, interrupted reset retry, SSE reconnect, error handling, and 390px accessibility checks. Local acceptance and exact-commit CI passed; target-host acceptance and the remaining phase exit criteria are recorded in the [Day 3 Prompt 3 evidence](DAY-3-PROMPT-3-RELEASE-ACCEPTANCE.md).

## Objective

Connect the stable backend to useful, understandable synthetic user journeys and a minimal operator experience. No critical demo task should require Swagger, SQL or an undocumented shell command.

## Why this phase comes now

Phase 6 establishes visual, auth and API foundations. Phase 5 establishes safe seed/reset and recovery contracts needed by operator screens. This is the first complete product experience milestone.

## Existing state and disposition

- **KEEP:** server-side authorization and existing API semantics; UI permissions are presentation only and never replace backend checks.
- **KEEP:** the implemented customer, merchant, driver, dispatcher and operator workspaces that map to supported operations.
- **COMPLETE:** integrate the local driver simulator behavior into a controlled synthetic experience; preserve server-side matching/assignment truth.
- **HARDEN:** feedback for conflict, duplicate submission, stale location, retry, outage and SSE reconnect.
- Do not expose operator internals, real infrastructure secrets, raw event payloads or another persona's location.

## Existing features to upgrade

- Turn existing backend endpoints, SSE tracking and synthetic simulator behavior into discoverable, role-scoped browser actions.

## New features to add

- Complete the remaining customer, merchant, driver, dispatcher and minimal operator journeys with state-specific UX and browser coverage.

## Work included

### Frontend / UI

- **P0 Customer:** view a seeded order, current lifecycle state, current/stale/unavailable tracking and completion; show a safe empty state before seed.
- **P0 Merchant:** view assigned synthetic work and perform only authorized lifecycle actions; show validation, conflict and resulting state.
- **P0 Driver:** view own offer, accept/reject, advance allowed delivery states and drive a clearly labeled synthetic location; show expiry and stale state.
- **P0 Dispatcher:** inspect queue, candidate explanation, assignment, recovery incident and serviceability; make only supported authorized actions.
- **P1 Operator:** seed/reset with deliberate consequence confirmation; inspect failed/quarantined work; replay/skip only actions exposed by Phase 5. Backup freshness remains an owner-only operations signal, not a public product feature.
- **P0:** make states clear: loading, empty, success, validation, unauthenticated, forbidden, conflict, limited, unavailable, partial and unexpected error.
- **P1:** provide reviewer entry guidance using safe, role-scoped synthetic personas; provision credentials at runtime and keep them out of source control and static bundles. The access method must be exercised with a first-time reviewer account before release.
- **P1:** verify accessible labels, focus, contrast, keyboard behavior, reduced motion and responsive layouts; avoid generic dashboard filler and meaningless charts.

### Testing

- Build a small critical Playwright suite against an isolated real backend and signed OIDC actors.
- Add component tests for stateful forms, destructive reset, conflict refresh, retry messaging and SSE cleanup.

## Tests required

- Browser journeys: login; seed; customer order/tracking; merchant transition; driver offer/completion; dispatcher recovery; operator inspection; logout.
- Negative journey: wrong persona tries another actor's resource and sees safe denial.
- Browser failure states: expired token, 409 conflict, 429, 503, empty data, failed SSE/reconnect and interrupted reset.
- Keyboard-only and mobile viewport acceptance for every critical route.

## Failure scenarios to verify

- Double-submit, race with another persona, stale tracking, no candidates, expired offer, lost connection during command, service outage, reset interruption and unauthorized operator action.

## Verification

- Run the full frontend suite, Playwright against disposable PostgreSQL/PostGIS/Redis/Kafka/Keycloak, and backend verification.
- Check each visible button maps to a registered operation and that the backend independently rejects wrong-role/object access.

## Exit criteria

- All five persona journeys complete without Swagger, direct SQL or undocumented operator commands.
- Every mutation shows pending/result/conflict accurately; duplicate submit cannot claim false success.
- No dead navigation, placeholder screens, raw JSON errors, cross-persona data, or destructive action without explicit feedback.
- Critical journeys pass in CI; accessibility and narrow-screen checklist passes with dated evidence.

## Deliverables

- Product pages, operator screens, component tests, critical Playwright journeys, reviewer guide and acceptance screenshots captured from synthetic data.

## Features explicitly not added

- No live maps/routing, payments, real account onboarding, admin CRUD suite, advanced analytics or user telemetry.

## Risk

HIGH. Inaccurate state or weak role presentation can mislead reviewers even when APIs are secure.

## Depends On

- Phases 5 and 6.

## Unlocks

- Integrated CI/release packaging and public demo review.

## Day 3 closeout — 2026-10-17

The disposable signed journey passed 1/1, 11 axe audits with zero violations, and five 390px mobile checks without horizontal overflow. The interrupted reset committed and same-body/idempotency-key retry confirmed generation 2; a deliberately dropped first SSE request reconnected and observed delivery. Authorization denials and reset 429/503 feedback passed. Direct-push and PR CI passed exact feature commit `bca17c48024b59cb26935e0d465f40d69344da96`; the remaining target-host acceptance gates keep this phase `IN_PROGRESS`. See the Day 3 acceptance ledger for run evidence.
