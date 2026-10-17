# Release candidate metadata contract

This describes the later Phase 8 candidate artifact. Verified CI build evidence exists, but registry publication, provenance, advisory and target gates remain open. No published or approved candidate is claimed.

Store one `candidate.json` with the successful CI run and all matching reports. Do not mint or upload it until the exact commit has green backend, frontend, Playwright, migration, Compose, security, CodeQL and SBOM jobs. The JSON must include:

```json
{
  "sourceCommit": "full 40-character Git commit",
  "ciRun": "GitHub Actions run URL and run id",
  "createdAtUtc": "ISO-8601 UTC",
  "tools": {
    "java": "exact runner version",
    "maven": "wrapper-reported version",
    "node": "exact runner version",
    "npm": "exact runner version",
    "trivy": "scanner version",
    "syft": "scanner version",
    "codeql": "scanner version"
  },
  "images": {
    "app": "repository@sha256:...",
    "edge": "repository@sha256:...",
    "keycloakRuntime": "repository@sha256:...",
    "postgres": "repository@sha256:...",
    "redis": "repository@sha256:...",
    "kafka": "repository@sha256:..."
  },
  "frontend": {
    "packageVersion": "web/package.json version",
    "lockfileSha256": "SHA-256 of web/package-lock.json",
    "buildJob": "successful CI job URL"
  },
  "sbom": "artifact name and SHA-256",
  "scanReports": ["artifact names, scanner versions, and SHA-256 values"],
  "exceptions": []
}
```

The Phase 8 policy is fail on unresolved critical/high findings; any exception must name an owner, applicability evidence, mitigation and expiry. A digest match count alone is not an exploitability conclusion. Do not publish moving tags or deploy an artifact rebuilt from a different commit than the one whose checks passed. CI builds all six runtime images, executes a private real-Keycloak browser/database smoke, scans source and runtime images, and emits source plus six CycloneDX SBOMs. Scanner versions are pinned. Current local audit artifacts are identified by base commit plus a dirty source manifest; they are AUDIT_ONLY and cannot be represented as a green remote CI run or a published release candidate. Registry publication, attestation, exact candidate digest verification and target acceptance remain later gates. Security refresh builds resolve signed OS packages at build time; deploy the reviewed output digest rather than rebuilding a candidate.

CI retains `image-builds.json` per commit/run with six immutable local image IDs and available registry digests. It is `BUILD_METADATA_ONLY`, with `candidateApproved: false`. Classic Docker may have no registry digests before publication; image config IDs cannot substitute for the approved published digests required above.

The final verify job now generates `release-evidence.json` for its exact source/head SHA and run. It hashes all seven SBOMs, Maven/npm source coverage, scanner reports, browser/SQL evidence and class-specific completed owner notifications. It records six immutable build image IDs, `candidateApproved: false`, `acceptanceDecision: PARTIAL_NOT_COMPLETE` and legacy Phase 11 `CLOSED_PARTIAL`. This exact-run acceptance/build record is retained as a CI artifact and reviewed locally as `target/day3-acceptance.json`; it cannot substitute for published registry digests or the approval contract above.
