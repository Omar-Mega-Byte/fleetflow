# Phase 8 GHCR publication — 2026-10-18

**Six images published and identity verified; candidate approval pending.** At 2026-10-18 19:35 UTC, the six images from trusted push [39131639694](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39131639694), source `ff9f4b284c807b6c5f4b88a826ab1513007f50b5`, were loaded from its hashed archive and pushed to GHCR without a rebuild. GitHub's package API reported **private** visibility for all six packages. The later documentation/security-review head `36b60c75f004d128e21243dc63abeaafd947712a` passed [push CI 39158000013](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39158000013) and [PR CI 39158004005](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39158004005). It did not change the archived runtime images.

| Image | Immutable GHCR reference | Scanned CI config digest |
| --- | --- | --- |
| app | `ghcr.io/omar-mega-byte/fleetflow-app@sha256:ada711baaec5e95bb7ccf266379063539999fa82fa8ae87c146596d3b9526666` | `sha256:c850c695a80f52a42d7894397bec53589507ee8c1c163890e05ccdc4e4abf1b2` |
| edge | `ghcr.io/omar-mega-byte/fleetflow-edge@sha256:8e22e5b508ea3242690cf0e8d66d39124260d708022e8e5e6245c3c1f21dd02d` | `sha256:4ce6069d189be693c6c686d7090e2009cb0a03012ab735fbe66a6773239d1d25` |
| keycloak-runtime | `ghcr.io/omar-mega-byte/fleetflow-keycloak-runtime@sha256:92ea04d79b3f06f55a754823216926da8daa4d63fc98b800a4ed3703a3ab1c6e` | `sha256:433db185614abcdd3813179338494ad9e53f63f45d5b6c4fe31ac6ac4297cbd3` |
| postgis | `ghcr.io/omar-mega-byte/fleetflow-postgis@sha256:543db1adaf8e0c4db405922da15b9bef1a5aabc4a43361f33eb5d87d8de57052` | `sha256:b66d9ae3d3e7c3dd70f9c6e4033a71e2600b46bbd2b2a72137a0b1d5c807b46a` |
| redis | `ghcr.io/omar-mega-byte/fleetflow-redis@sha256:7405f59b2148385610918b70633e792fbb4651595b33b226bbdc272de488d421` | `sha256:03176c2d446736fe036215addf05db9480a7ef85412ed3e2ff058ff587d58357` |
| kafka | `ghcr.io/omar-mega-byte/fleetflow-kafka@sha256:535cace8e3f7d9a3397ccdc26f94391b54d128e084e21f83526b9c66f385e068` | `sha256:4f67052cb1d1f909484f38c27cda2bc8381e76f9e1dc7d0d84c2a68d0f29fc1d` |

## Verification performed

1. `release-evidence.json` references **29 files**; each file matched its recorded SHA-256. The six-image archive was **956,587,546 bytes**, SHA-256 `59a060bab128eb0fe0576635c2e6033a83d7935d79db7e3abf17fdd6e401deb4`.
2. The six OCI config blobs inside the archive matched `runtime-images.json` and each was `linux/amd64`; each was referenced by a manifest. `docker load` restored all six `fleetflow-*:ci` tags from that archive.
3. Docker tagged and pushed those loaded images at the scanned source SHA. `docker buildx imagetools inspect` returned the six digest references above. Each was a single-platform manifest whose `config.digest` equaled the corresponding scanned CI config ID. Each immutable reference was pulled back successfully.
4. GitHub's package API returned `visibility: private` for `fleetflow-app`, `fleetflow-edge`, `fleetflow-keycloak-runtime`, `fleetflow-postgis`, `fleetflow-redis` and `fleetflow-kafka`.

The private, ignored directory `target/phase8-review-ff9f4b2/` holds `published-refs.json`, the original artifacts, `release-evidence.json` (SHA-256 `e0bbb75db1dd5f8eb8eac7d7eb64b050d7f3fcccb9460e6ee3ce94a544227b06`), `advisory-review.csv` (SHA-256 `cc427b76b4ca73c23a882a58daacfc8e703cd3b82c6f33561649b4c07686c3cc`) and `candidate-draft.json`. The draft contains the real references and `approval: null`; it is **not** a `candidate.json` and cannot pass the approved-candidate validator.

## Remaining Phase 8 decision

The release owner must independently review the [28-row advisory reconciliation](DAY-4-SUPPLY-CHAIN-RECONCILIATION-2026-10-18.csv), especially the seven narrow reachability decisions in the [audit](KEYCLOAK-REACHABILITY-AUDIT-2026-10-18.md), and record an explicit candidate approval or rejection with a real UTC time and record location. The earlier approval of eight time-bound CVEs is not candidate approval. If approved, create the contract's private `candidate.json`, complete the private `.env.production`, and run `python scripts/validate_release_candidate.py --env-file .env.production --check-registry-manifests --check-local-images` against these six references. Keep Phase 8 `IN_PROGRESS` and Phase 9 closed until that validator passes. The Azure UAE North cost/quota and public-target acceptance gates remain separate.
