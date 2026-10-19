# Phase 8 private candidate approval — 2026-10-18

**Owner-approved immutable candidate preflight: PASS.** The release owner, **Omar Elrfaay**, replied `APPROVE after review` to the separate 28-CVE advisory and six-image candidate decision in this Codex chat. The decision was recorded at **2026-10-18T19:56:53Z** in the private `owner-candidate-approval.md`. It is separate from the [eight-CVE time-bound acceptance](KEYCLOAK-TIME-BOUND-ACCEPTANCE-2026-10-18.md).

## Approved scope and identity

- Scanned source: `ff9f4b284c807b6c5f4b88a826ab1513007f50b5`.
- Trusted push build/scan and owner-alert evidence: [39131639694](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39131639694), `verification: PASS`, `ownerAlertDelivery: VERIFIED`.
- Exactly six private GHCR digest references in the [publication record](PHASE-8-GHCR-PUBLICATION-2026-10-18.md); each single-platform registry manifest has the scanned CI config digest, and all six references were pulled back.
- Advisory review: [28-row reconciliation](DAY-4-SUPPLY-CHAIN-RECONCILIATION-2026-10-18.csv), SHA-256 `cc427b76b4ca73c23a882a58daacfc8e703cd3b82c6f33561649b4c07686c3cc`; 20 `NOT_AFFECTED`, eight `ACCEPTED_TIME_BOUND`, zero `OPEN`. The eight acceptances expire **2026-11-18** and do not transfer to another image.
- Source-to-publication artifact binding: all 29 `release-evidence.json` references passed SHA-256 verification, including the 956,587,546-byte six-image archive (`59a060bab128eb0fe0576635c2e6033a83d7935d79db7e3abf17fdd6e401deb4`).

The durable **private**, untracked bundle is `C:\Users\Tolis\Desktop\Java\Fleetflow-Release-ff9f4b2`. It contains the CI artifact paths, `release-evidence.json`, `advisory-review.csv`, `published-refs.json`, `candidate.json`, `owner-candidate-approval.md`, `candidate-preflight.env` and `bundle-manifest.json`. Its `candidate.json` SHA-256 is `ae4c3783f840d7523e77c7827ac56cd0b1ce8c347619edf48d7a33eb990083fb`. The owner approval record SHA-256 is `0d0548ada775f247e97847e09a0ca467e59e47c248df10c1d48f2bd94a31faaf`. The bundle copy rechecked all 29 artifact hashes.

From the repository on the trusted Windows promotion host, the required candidate validator passed against that durable bundle:

```powershell
python scripts/validate_release_candidate.py --env-file 'C:\Users\Tolis\Desktop\Java\Fleetflow-Release-ff9f4b2\candidate-preflight.env' --check-registry-manifests --check-local-images
```

Result: `Approved candidate preflight passed for ff9f4b284c807b6c5f4b88a826ab1513007f50b5`. This command checked the genuine approval fields, exact trusted CI evidence and artifact hashes, completed advisory decisions, six immutable environment references, six pulled local identities and six registry manifest config digests. The `candidate-preflight.env` contains only release identity values; the full private `.env.production` with target hosts, real Keycloak subjects and secret-file locations is Phase 9 work.

The later documentation/review branch head `966a1f7b7a3e5b98f559879c96e129121e6b42d6` passed [push CI 39159615858](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39159615858) and [PR CI 39159620715](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39159620715), including exact-run evidence. Its Keycloak scan still had 51 results and the same 28 CVE IDs. CI rebuilt different image config IDs at that later commit; those images are **outside this approval** and have not been substituted for the six digest references above.

## Remaining boundaries

The **private `ff9f4b2` candidate approval gate is complete**. [PR #40](https://github.com/Omar-Mega-Byte/fleetflow/pull/40) merged as `530c9ea49aa8385d1afa81aef05ae663bff776fb` after its exact-head push and PR checks passed. The merge-triggered [main CI](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39162750016) rebuilds images. Those exact final-main images need their own scan, advisory applicability review, six-registry-digest verification and owner approval before deployment. This approval does not carry to a different Keycloak config digest. GitHub `main` had no configured branch protection at merge time.

No Azure Phase 9 resource was created and no public demo URL was approved. Before provisioning, resolve UAE North VM availability/quota and an all-in plan within **$10/month**, then provide target secret custody, read-only GHCR pull access, DNS/TLS and the five actual Keycloak subject UUIDs. Public boundary, backup/restore, load/failure and final acceptance remain separate later gates in the [remaining-work checklist](../../PROJECT-COMPLETION-REMAINING.md).
