# Release Phase 2 — Identity and object-level authorization

> Current Day 1 assessment: **COMPLETE**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: COMPLETE. Priority: P0.** Day 1 contract review and current-tree verification passed; public target acceptance remains a separate Phase 9 gate.

## Objective

Prove each persona can act only on its own permitted resources, and establish the identity contract the UI and public deployment will use.

## Why this phase comes now

Public exposure and UI integration are unsafe until actor identity, roles, object ownership and error semantics are explicit. Phase 1 first stabilizes the state-changing operations these checks protect.

## Existing state and disposition

- **KEEP:** signed OIDC validation, issuer/audience/expiry checks, local actor mapping, explicit route allowlist ending in deny, and authorization in service/query layers.
- **HARDEN:** foreign-object, branch membership, role-combination, deactivation and revocation evidence across critical resource types.
- **VERIFY:** the existing 57-route anonymous/wrong-role/inactive actor sweep and each profile boundary.
- **REMOVE:** no fake production user header, local fixture bypass, permissive fallback issuer or broad admin permission.

## Existing features to upgrade

- Extend OIDC and service-level scope evidence to foreign objects, revocation, role combinations and state-changing routes.

## New features to add

- Add a route/persona/object matrix and browser OIDC contract; add policy code only where the matrix exposes a real gap.

## Work included

### Security and backend

- **P0:** generate a method/path/profile/required-role/object-scope matrix from registered routes and OpenAPI.
- **P0:** cover customer order ownership; merchant branch membership; driver self, offer, delivery and tracking ownership; dispatcher incident/zone scope; inactive actors; multi-role combinations; changed/revoked membership; reassignment and terminal states.
- **P0:** document when a foreign object returns 404 to avoid enumeration and when a role failure returns 403. Keep token/identity errors generic.
- **P1:** validate signed tokens against the chosen issuer, audience, signature, expiry and clock policy using realistic signed test tokens.
- **P1:** specify the browser authorization code with PKCE client, redirect/logout origins, role claims and session renewal behavior for Phase 6/9.
- Keep dispatcher permissions explicitly global for this single synthetic deployment; do not claim tenant isolation.

### Testing

- Add object-scope tests for each critical route/resource pair not already covered; prefer a generated matrix over duplicating controller tests.
- Verify disabled actors and membership revocation take effect on the next protected request.

## Tests required

- Signed OIDC integration tests for missing, malformed, expired, wrong issuer and wrong audience tokens.
- Role matrix tests for anonymous, wrong-role, inactive and multi-role actors.
- Foreign-object tests for customer, branch, driver, offer, delivery, incident, zone and SSE snapshot/stream access.
- Mutation assertions proving forbidden requests do not write state or publish events.

## Failure scenarios to verify

- Keycloak/JWKS unavailable, unknown subject, expired token, stale role, concurrent branch-membership revocation, wrong driver accepting an offer, foreign order/stream ID and an inactive actor with a still-valid token.

## Verification

- Run the security integration suites and full Maven verification against signed tokens.
- Compare all registered routes and documented authorization rows; fail if a business route is unmapped.

## Exit criteria

- Every business route has an explicit persona/role and object-scope rule.
- Every listed critical resource has a signed foreign-object denial test and no forbidden side effect.
- Revoked/inactive actors cannot perform protected actions; authentication fails closed when issuer verification is unavailable.
- The browser OIDC redirect, logout, claim and renewal contract is written before frontend auth implementation.

## Deliverables

- Route/persona/object matrix, security tests, any minimal policy fixes, and browser identity contract.

## Features explicitly not added

- No customer self-registration, real merchant onboarding, multi-company tenancy or custom application token issuer.

## Risk

CRITICAL. An authorization bypass leaks addresses/locations or permits unauthorized state changes.

## Depends On

- Phase 1.

## Unlocks

- API abuse policy, browser authentication and public hosting.

## Historical Day 1 prompt evidence — 2026-10-14

- See [Day 1 backend contract evidence](day-1-backend-contract-evidence.md) for the JWT/actor contract, route sweep and browser OIDC contract.
- Current actor roles come from PostgreSQL on each request; issuer and subject are the identity key. The live route test covers every registered API operation for anonymous, signed wrong-role and inactive actors, and snapshots durable database row counts.
- The phase remains `IN_PROGRESS`: complete the critical foreign-object matrix, verify observed behavior during JWKS outage, then implement and prove the browser client against the eventual public issuer.


## Consolidation audit — 2026-10-14

JWT issuer/audience/expiry and current PostgreSQL role/membership boundaries pass. The live handler denial sweep and OpenAPI cover 64 test-profile operations with demo enabled. Real Keycloak PKCE/browser/customer API authorization is verified on disposable HTTPS. Uncached JWKS outage returns safe 503 IDENTITY_UNAVAILABLE; cached valid-key tokens continue but revoked roles/inactive actors return 403. Public target issuer acceptance remains Phase 9 work.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
