# Release Phase 12 — Final public-demo acceptance

**Status: NOT_STARTED. Priority: P0.** This is a verification and release decision phase. Do not add new features here.

## Objective

Approve or reject the exact public, on-demand synthetic demo release using dated evidence for product, security, recovery, cost and recruiter access.

## Why this phase comes last

Only the exact artifact and Azure target that passed every prior phase can be assessed. A green local build, roadmap, or earlier branch is not deployment acceptance.

## Existing state and disposition

- **KEEP:** earlier local demo completion as a separate historical scope.
- **VERIFY:** every P0 release gate against the exact commit, image digest, public URL, identity config and deployment resources.
- **REMOVE:** no acceptance by documentation-only claims, screenshots of unverified behavior, or stale CI evidence.
- No feature development belongs here; return defects to the owning phase and rerun all affected gates.

## Existing features to upgrade

- Update release evidence and recruiter-facing claims to match the exact candidate; do not change working features in this gate.

## New features to add

- Add only the final acceptance record, deployed URL/reviewer guide if approved, or an evidence-backed rejection list.

## Work included

### Final checks

- **P0:** freeze commit and release image digests; ensure exact CI and current security reports pass.
- **P0:** verify TLS/DNS, stable OIDC, external port policy, synthetic personas, object-level security, public rate limits and browser error/accessibility states.
- **P0:** complete a first-time reviewer journey from provided instructions through seed/order, merchant action, driver offer/completion, tracking and dispatcher/operator review.
- **P0:** complete backup/fresh-host restore or approved synthetic reconstruction, alert delivery, restart/rollback and VM deallocate/start checks.
- **P0:** review Azure spend, credits, disk/IP continuing charges, budget alerts, manual online/offline behavior and resource cleanup plan.
- **P0:** inspect README/reviewer guide and all claims against observed behavior; disclose single-VM downtime while deallocated and lack of commercial SLA.
- **P1:** capture redacted dated evidence: CI, scans, external HTTPS/port checks, workflow/browser run, recovery, load and budget estimate.
- No new functionality or optional future feature is accepted in this phase.

## Tests required

- Full release CI and critical Playwright/browser acceptance.
- Signed denial case, API conflict/rate limit, rollback/restore, and cost-safe start/deallocate cycle.
- Repeat only the relevant suites for a defect; then rerun every gate affected by changed code/config/image.

## Failure scenarios to verify

- One dependency outage and recovery, a failed-backup/alert path, rollback or restore, a wrong-persona access attempt and start/deallocate while checking persistent data and cost boundaries.

## Verification

- Use the release checklist and gates in [PRODUCTION-ROADMAP.md](../../PRODUCTION-ROADMAP.md).
- Tie every result to one commit, one immutable image set, one Azure resource inventory and one date.
- Mark approved only after every P0 checkbox has a result and evidence location; otherwise mark rejected/blocked with the exact owning phase.

## Exit criteria

- Every P0 public-demo gate passes on the exact candidate; no unresolved critical finding or unexplained external port exists.
- Reviewer can sign in through the documented safe access mechanism and complete the critical synthetic workflows without an operator improvising.
- Recovery, alert, budget, deployment stop/start, and availability limits are demonstrably understood by the owner.
- README and recruiter instructions truthfully describe the deployed version and on-demand single-host boundary.
- Final status is exactly one of **APPROVED FOR ON-DEMAND PUBLIC DEMO** or **REJECTED — NOT READY**, with evidence and residual P1/P2 items.

## Deliverables

- Signed/date-stamped acceptance record, public demo URL and reviewer guide if approved, or blocker list mapped to phases if rejected.

## Features explicitly not added

- No feature work, scope expansion, commercial-service approval or production-SLA language.

## Risk

CRITICAL. A mistaken approval exposes the public host or makes an unsupported availability/security claim.

## Depends On

- Phases 0–11.

## Unlocks

- Advertise the approved synthetic demo. Any commercial service requires a separate architecture and risk decision.
