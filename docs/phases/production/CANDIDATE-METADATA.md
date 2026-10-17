# Release candidate metadata contract

**Current state:** no published or approved candidate exists. The exact-commit CI artifact is build evidence. It records `candidateApproved: false`, because publication, advisory disposition and release-owner approval are separate decisions.

Package one private release metadata directory with `candidate.json`, the exact CI `release-evidence.json`, `runtime-images.json`, and every source/image SBOM, Trivy SARIF and CodeQL SARIF at the relative paths named by the evidence file. Keep the original artifact paths when downloading them. The evidence file lists all seven SBOMs, seven Trivy reports, two CodeQL reports, six build image IDs and the source/CI identity.

For trusted pushes, CI also retains `runtime-images-<SHA>` for 14 days. Its `runtime-images.json` binds the compressed Docker archive hash and six image IDs to that exact CI run. Promote only after the full run is green: verify the archive hash, load the images on the trusted release host, compare loaded IDs with the manifest, then tag/push without rebuilding. This artifact is build evidence; its existence alone does not approve a candidate.

After the registry images have been published and reviewed, the release owner may create `candidate.json` in this form:

```json
{
  "sourceCommit": "full 40-character Git commit",
  "ciRun": "https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/123456",
  "images": {
    "app": "ghcr.io/owner/fleetflow-app@sha256:<64 hex>",
    "edge": "ghcr.io/owner/fleetflow-edge@sha256:<64 hex>",
    "keycloak-runtime": "ghcr.io/owner/fleetflow-keycloak-runtime@sha256:<64 hex>",
    "postgis": "ghcr.io/owner/fleetflow-postgis@sha256:<64 hex>",
    "redis": "ghcr.io/owner/fleetflow-redis@sha256:<64 hex>",
    "kafka": "ghcr.io/owner/fleetflow-kafka@sha256:<64 hex>"
  },
  "releaseEvidence": {
    "path": "release-evidence.json",
    "sha256": "SHA-256 of the exact CI evidence file"
  },
  "advisoryReview": {
    "path": "advisory-review.csv",
    "sha256": "SHA-256 of the release-owner-reviewed finding dispositions"
  },
  "approval": {
    "decision": "APPROVED",
    "owner": "named release owner",
    "atUtc": "2026-10-17T12:00:00Z",
    "record": "location of the human approval decision"
  }
}
```

Use real values; the sample is not a candidate. `advisoryReview` is required when any Trivy result has findings. Its CSV must contain `finding_id,disposition,owner,rationale,evidence,mitigation,expires_at_utc` and one row per distinct SARIF rule ID. Allowed decisions for a still-scanned finding are `NOT_AFFECTED` with applicability evidence, or `ACCEPTED_TIME_BOUND` with a mitigation and future expiry. The current Day 4 CSV has `OPEN` decisions and cannot be used as release approval. Critical/high, unknown, secret, unclassified or scanner blockers fail the preflight; policy exceptions for those severities require a separate policy change and renewed review. The release owner must independently verify each decision and its evidence.

Run `python3 scripts/validate_release_candidate.py --env-file .env.production` after packaging the artifacts. The validator checks the source SHA, CI run, trusted-push owner alert result, exact image archive manifest, explicit approval fields, six environment digests, exact evidence hash, all SBOM/scan hashes, policy blockers and finding dispositions. The large image archive is needed on the trusted promotion host, not on the deployed VM; retain its manifest in the metadata directory. Install the Docker Buildx plugin on the promotion host and VM. On the promotion host, run `python3 scripts/validate_release_candidate.py --env-file .env.production --check-registry-manifests --check-local-images` after pulling all six published references. The registry check requires a single-platform manifest whose config digest equals the image ID scanned in CI; the local check accepts the scanned config digest from a classic Docker store or the approved manifest digest from a containerd store. Docker Engine 29 can use the containerd image store, where `docker image inspect .Id` is the manifest digest. `ops/production/fleetflow.sh up` performs the metadata check before pulling, then repeats both registry and local checks after pulling and before starting services. `ops/production/backup.sh` repeats the metadata check before stopping services for a backup. No moving tag, multi-platform index or locally rebuilt image passes the promotion boundary.

The release owner must also independently inspect the exact GitHub run and the approval record. The metadata validator verifies file integrity and identities, but it cannot establish who authored an approval record or whether the CI service remains trustworthy. Keep the metadata directory private and immutable for the deployed release.

The edge image obtains its HTTPS OIDC issuer from the validated production `IDENTITY_HOST` at runtime through `/runtime-config.js`. It does not need a host-specific rebuild. Verify the served config and signed browser flow on the public target; a source change after the last green CI run requires a new exact-commit build and scan.
