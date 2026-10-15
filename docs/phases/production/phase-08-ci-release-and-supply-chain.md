# Release Phase 8 — CI, release artifacts and supply chain

> Current Day 1 assessment: **COMPLETE for Day 1 foundation; exact-candidate acceptance remains open**. [Consolidation ledger](DAY-1-EVIDENCE-LEDGER.md) supersedes earlier execution snapshots. Full release-phase exit criteria below remain independent.

**Status: IN_PROGRESS. Priority: P0.** CI now contains backend/Testcontainers migration and route verification, frontend checks, fixture and signed live Playwright gates, Trivy source/container scans, CodeQL, source/image SBOMs and local/production Compose boundary validation. Day 1's 2026-10-14 local scans and seven SBOMs passed for their recorded base-commit plus source fingerprint; that evidence is historical for the Day 1 tree. The 2026-10-15 Day 2 source/image scan findings and SBOM artifacts must be reviewed from the exact pushed PR run before this phase's gate closes. No published candidate or attestation is claimed.

## Objective

Make every release candidate reproducible, scanned, traceable to source and tested as the same artifact that Phase 9 deploys.

## Why this phase comes now

One critical signed end-to-end browser journey now exists. Establish and review exact-commit release gates before provisioning/public deployment so infrastructure does not disguise unresolved code or supply-chain risk.

## Existing state and disposition

- **KEEP:** GitHub Actions backend verification, Compose config validation, tracked-secret preflight, digest-pinned service images and non-root app image.
- **HARDEN:** action references, source/dependency and container-image scans, frontend CI, browser E2E, migration checks and release evidence.
- **ADD:** immutable candidate metadata, verified SBOM/scan artifacts and digest attestation; deployment approval remains a manual release gate.
- **VERIFY:** image advisories from the earlier audit against current versions and affected ranges; match counts alone are not exploitability findings.
- **REMOVE:** no automatic production deploy on an unreviewed branch, mutable image tag or secret-in-logs shortcut.

## Existing features to upgrade

- Extend Maven/Compose/secret-preflight CI and digest-pinned images with frontend, browser, migration and current security gates.

## New features to add

- Add immutable release artifact metadata, SBOMs, source/image scan policy and digest-based deployment inputs.

## Work included

### CI/CD and security

- **P0:** run Java 21 clean verification, frontend lockfile install/lint/type/component tests, Compose validation, migration smoke and critical Playwright journeys in CI.
- **P0:** add secret scanning and dependency/source scanning; scan all deployed images. Pin third-party actions to immutable commit SHAs where practical and document update automation.
- **P0:** generate an SBOM for source/runtime images and record source commit, image digest, tool versions and scan report for each candidate.
- **P0:** define severity policy: critical/high findings must be fixed or have a named owner, rationale, mitigation and expiry before release; no silent suppression.
- **P1:** publish immutable release artifacts to a selected registry only after CI passes; deployment consumes the digest, not a moving tag.
- **P1:** verify migrations from a clean database and supported previous release; never run destructive production schema rollback.

### Testing

- Keep CI short enough to run on every PR; isolate external services with disposable Compose/Testcontainers. Do not add tests solely to increase test count.

## Tests required

- Backend, frontend, browser, migration and Compose jobs.
- Secret scanner positive fixture in a disposable test repository/configuration and negative fixture showing clean scan.
- Dependency/image scan policy behavior; pinned digest pull and artifact provenance check.
- CI failure tests for missing required job, scanner finding and artifact mismatch.

## Failure scenarios to verify

- Dependency/image vulnerability found, scanner unavailable, registry outage, mutable tag changed, frontend lockfile drift, migration failure, secret preflight failure and Playwright stack unavailable.

## Verification

- Inspect the exact successful CI run and its artifact digest/reports for the candidate commit.
- Re-pull the recorded image digest and compare it with the deployed candidate; verify no build-time secrets or local files entered the image.

## Exit criteria

- Required CI jobs cover backend, frontend, critical E2E, migration, Compose, secret and source/image security.
- Every runtime image has a recorded digest, SBOM and current vulnerability disposition.
- Candidate artifact is immutable and traceable to an exact source commit and green check set.
- No production deployment can consume an unreviewed or unscanned artifact.

## Deliverables

- Updated workflows, security policies, SBOM/scan artifacts, immutable image references and release checklist.

## Features explicitly not added

- No multi-environment enterprise CD, canary controller, Kubernetes, generalized artifact platform or automated deployment before approval.

## Risk

HIGH. An untraceable or vulnerable image invalidates all runtime evidence.

## Depends On

- Phases 1–7.

## Unlocks

- Repeatable Azure deployment and final release candidate.


## Day 1 consolidation snapshot — 2026-10-14

CI's prior working tree contained backend/migration/OpenAPI, frontend/browser, private production-topology browser, safety-unit, source/image scans, CodeQL and source/six-image SBOM jobs. Actionlint passed. Trivy 0.76.0 strict high/critical and secret gates passed for source plus all six runtime images for source fingerprint `a9e676bd9b200122176783324567c53701195ab65f96d9d2df505acc2615c3ba`; Syft 1.54.1 produced seven CycloneDX artifacts. All 157 initial finding entries in that dated audit inventory were remediated without an ignore policy. This is not a current scan of the Day 2 tree and does not replace exact-commit PR CI. No published candidate or attestation is claimed.

See [reconciliation and stable contracts](DAY-1-CONSOLIDATION-AUDIT.md), [execution ledger](DAY-1-EVIDENCE-LEDGER.md) and [redacted machine evidence](evidence/day1-consolidation-2026-10-14.json).
