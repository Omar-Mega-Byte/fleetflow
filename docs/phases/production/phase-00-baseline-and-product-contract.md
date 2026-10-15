# Release Phase 0 — Baseline and product contract

**Status: COMPLETE. Priority: P0.** Complete for audit and scope lock. This is production-roadmap Phase 0, distinct from historical implementation Phase 0 in the parent folder. Completion does not approve public deployment.

## Objective

Set the known starting point and bound the product being finished: a synthetic FleetFlow showcase a recruiter can open during an on-demand demo window. It is not a commercial logistics service.

## Why this phase comes first

Every later security, UI, hosting, and recovery decision depends on the real product boundary and current implementation evidence. The audit and product boundary are already recorded, so execution starts with Phase 1.

## Existing state and disposition

- **KEEP:** the completed local synthetic demo and its backend workflows as the starting baseline.
- **VERIFY:** working-tree changes and local-only results against the exact branch/commit before claiming release evidence.
- **REMOVE from release scope:** paid payments, trusted commercial pricing, real public order intake, real customer/driver data, multi-tenant service, and commercial availability claims.
- The audit identifies a backend-only local demo, no product frontend, and no public-safe deployment. See the evidence and limits in [PRODUCTION-ROADMAP.md](../../PRODUCTION-ROADMAP.md).

## Existing features to upgrade

- No runtime feature changes in this phase. Keep the audit evidence current as later implementation phases produce new evidence.

## New features to add

- Add the authoritative release-phase index and execution briefs in this folder; do not alter legacy implementation-phase history.

## Work included

- **P0 Product boundary:** maintain synthetic identities, orders, locations, and operations only; do not collect recruiter or real-person data.
- **P0 Hosting boundary:** target one Azure Linux VM running one Compose project, manually started and deallocated by the owner. Expect the public URL to be unavailable while deallocated; this is an on-demand portfolio demo, not an uptime SLA.
- **P0 Cost boundary:** keep spend inside the owner's available Azure credit; check the live region/SKU, disks, IP and backup costs before provisioning. Do not promise a year-round running VM from the credit alone.
- **P1 Demo objectives:** qualify two simultaneous reviewer sessions, ten synthetic drivers and ten SSE streams for a 30-minute run with at least 100 scripted workflow operations. Target at least 99% successful expected operations and p95 non-stream API latency at or below one second; exclude intentionally invalid, unauthorized, limited and conflict responses from the success denominator and report them separately.
- **P1 Start objective:** after an owner-started cold VM boot, the demo's documented readiness check should pass within 15 minutes. There is no availability target while the VM is deallocated.
- **P1 Recovery objective:** target RPO no greater than 24 hours and RTO no greater than one hour, or prove the synthetic dataset can be reconstructed within one hour from a clean host.
- **P1 Claims:** distinguish local demo completion, public demo acceptance, and commercial production readiness in README and release notes.

## Tests required

- Review each claimed current behavior against implementation, tests, migration, configuration, or dated operational evidence.
- Confirm every release journey maps to existing API operations or a phase that will add a minimal, justified contract.
- No application test is required for this completed planning phase.

## Failure scenarios to verify

- A local-only fixture or admin route is incorrectly treated as public-safe; an outdated test count or local measurement is presented as current; a commercial capability is implied by the synthetic-demo scope.

## Verification

- Review registered routes and OpenAPI against the capability table and evidence index in the audit.
- Run `git diff --check` for roadmap/document changes.

## Exit criteria

- [x] Synthetic-only recruiter showcase and one-VM on-demand target are written.
- [x] Non-goals prevent the release plan expanding into commercial features or HA architecture.
- [x] Audit evidence and its freshness limits remain visible.
- [x] Later phases have explicit measurable exit criteria and a final release gate.

## Deliverables

- Audit and release sequence in [PRODUCTION-ROADMAP.md](../../PRODUCTION-ROADMAP.md).
- Existing audit baseline, README boundary, and this phase record.

## Features explicitly not added

- No payments, customer signup, commercial pricing, multi-tenancy, public real-order intake, Kubernetes, microservices, multi-region recovery, or high-availability claims.

## Risk

MEDIUM. A scope misunderstanding could expose local fixtures or imply a service level the single VM cannot provide.

## Depends On

- None.

## Unlocks

- All remaining release phases.
