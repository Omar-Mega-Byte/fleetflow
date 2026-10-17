# Day 3 Prompt 3 — Release Acceptance

- **Initial closeout evidence:** 2026-10-16 (Africa/Cairo)
- **Supply-chain audit addendum:** 2026-10-17 (Africa/Cairo)
- **Live persona, recovery, and owner-alert addendum:** 2026-10-17 (Africa/Cairo)
- **Base commit:** `8fc2903403e6f01530e25bb6753de6f289cc5bfe`
- **Working branch:** `codex/day3-persona-closeout`
- **Feature commit with exact direct-push CI:** `bca17c48024b59cb26935e0d465f40d69344da96`
- **Decision:** **PARTIAL / NOT COMPLETE — local acceptance and exact-commit CI pass; 50 Keycloak findings and external release gates remain open.**

The 2026-10-17 entries below are the current scan and artifact status. The earlier persona, recovery, monitoring, backend, and frontend evidence was collected on 2026-10-16 and is retained with its original scope.

## Changes in this closeout

- Preserve the operator's synthetic seed/reset request body and idempotency key when a network outcome is ambiguous. Lock the form and dialog until a same-request retry confirms the outcome; do not report success on timeout, rate limiting, or service unavailability.
- Update the app runtime `libpng` package to `1.6.59-r0`. The final app image scan now reports no findings; the earlier medium finding for `libpng 1.6.58-r1` is absent from the fresh report.
- Add image-level `HEALTHCHECK` instructions to all six runtime Dockerfiles, using the same service endpoints or built-in readiness commands as the Compose probes. The final source scan no longer reports the six `DS-0026` Dockerfile notes.
- Add operator reset tests for timeout recovery and 429/503 responses.

## Verification ledger

| Gate | Result | Evidence and scope |
| --- | --- | --- |
| Backend clean verification | PASS — 2026-10-16 | `./mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --batch-mode --no-transfer-progress`; 145 tests, 34 suites, no failures/errors/skips. |
| Frontend checks | PASS — 2026-10-16 | `npm ci --prefix web` added 246 packages and audited 247 with 0 vulnerabilities; `npm run lint`, `npm run typecheck`, `npm run test` (54 tests across 14 files), `npm run build`, and `npm run test:e2e` (3/3 fixture journeys) passed. |
| Script checks | PASS — 2026-10-16 | `python -m unittest discover -s scripts -p 'test_*.py' -v`; 11 tests. Secret audit inspected 470 paths. |
| Local Compose config | PASS — 2026-10-17 | `docker compose --env-file .env.example -f compose.yaml config --quiet`; exit 0 after the Dockerfile changes. |
| Production Compose topology | PASS — 2026-10-17, rendered only | `docker compose --env-file .env.production.example --profile public --file ops/production/compose.production.yaml config --format json | python scripts/Validate-ProductionCompose.py`; structural and security-boundary checks passed using CI-only placeholder secret files. No services were started. |
| Fresh no-cache runtime image builds | PASS — 2026-10-17 | All six builds below exited 0 after the Dockerfile healthcheck edits. Immutable local image IDs are recorded in the table below. |
| Fresh runtime-image Trivy policy | PASS — policy threshold; open medium/low findings remain | Trivy `0.76.0`, scanners `vuln,secret`, `ignore-unfixed=false`. Six sanitized reports processed by `python scripts/check_trivy_sarif.py`. Totals: 50 findings (25 medium, 25 low), 0 critical/high, 0 secrets, 0 unclassified, 0 policy blockers. All 50 findings are in Keycloak; each other image has 0 findings. |
| Fresh source Trivy policy | PASS — 2026-10-17 | Trivy `0.76.0`, scanners `vuln,misconfig,secret`, `ignore-unfixed=false`; the sanitized final report has 0 findings, 0 secrets, 0 unclassified, and 0 blockers. The six pre-fix `DS-0026` low notes are absent after adding Dockerfile healthchecks. |
| CycloneDX SBOMs | PASS — 2026-10-17 | Syft `1.54.1` generated one source SBOM and six image SBOMs. File paths, CycloneDX metadata, component counts, and SHA-256 digests are listed in the audit-only `sbom-manifest.json` referenced below. |
| Immutable local build metadata | PASS — audit-only | `image-builds-audit.json` records the source commit, dirty-source manifest, tool versions, local image IDs/tags, scan/SBOM references, and `candidateApproved:false`. No registry publication or deployment occurred; no release candidate was created. |

### Fresh image build commands

The final image tags are local audit tags. The edge build bases came from the digest-pinned values in `.env.production.example`.

```powershell
docker build --no-cache --tag fleetflow-app:day3-current --file Dockerfile .
docker build --no-cache --tag fleetflow-keycloak-runtime:day3-current --file ops/production/keycloak.Dockerfile .
docker build --no-cache --tag fleetflow-edge:day3-current --file web/Dockerfile.release --build-arg "NODE_BUILD_IMAGE=node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402" --build-arg "NGINX_RUNTIME_IMAGE=nginx:stable-alpine@sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94" --build-arg VITE_OIDC_AUTHORITY=https://identity.fleetflow.localhost/realms/fleetflow web
docker build --no-cache --tag fleetflow-postgis:day3-current --file ops/production/postgis.Dockerfile .
docker build --no-cache --tag fleetflow-redis:day3-current --file ops/production/redis.Dockerfile .
docker build --no-cache --tag fleetflow-kafka:day3-current --file ops/production/kafka.Dockerfile .
```

| Component | Local tag | Immutable local image ID | Trivy result |
| --- | --- | --- | --- |
| App | `fleetflow-app:day3-current` | `sha256:34d1d2f8fc0c8ba11e4c9f8df1f12d313d50e714ec852e9b931118d0186e0b73` | 0 findings |
| Keycloak runtime | `fleetflow-keycloak-runtime:day3-current` | `sha256:e261c155b5993153f1586a5626d1f6fb4e981f9dd1a5a4cb5df3aaae4d21ba7f` | 50 open findings; details in the findings CSV |
| Edge | `fleetflow-edge:day3-current` | `sha256:82623c98f71e69dd40b3b6805aba07b7f6282009dd7151143da74b1ae2c2d561` | 0 findings |
| PostGIS | `fleetflow-postgis:day3-current` | `sha256:9ecfb6d4aec4a3264e2d1f6315c6eb36f6c2efea11e77699ca92268d50f0c780` | 0 findings |
| Redis | `fleetflow-redis:day3-current` | `sha256:0a300d774f532e2ab4c75678abf8cdd835d5b16c28654fa0d2e66c2fb528c1bc` | 0 findings |
| Kafka | `fleetflow-kafka:day3-current` | `sha256:af9ab28141cea680b8d013aa6413cfdf5321bed19a872c88152d1031905d04fc` | 0 findings |

### Trivy and SBOM commands

The source dependency metadata was warmed before scanning, matching the workflow's Maven preflight:

```powershell
.\mvnw.cmd --% -Dmaven.repo.local=C:\Users\Tolis\.m2\repository dependency:resolve --batch-mode --no-transfer-progress
```

The final source snapshot was scanned with Trivy `0.76.0`, `vuln,misconfig,secret`, `--timeout 20m`, `--ignore-unfixed=false`, `--exit-code 0`, and SARIF output using:

```powershell
$auditRoot = 'C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty'
$snapshotPath = Join-Path $auditRoot 'source-snapshot-final'
$cacheRoot = Join-Path $auditRoot 'trivy-cache'
docker run --rm --mount "type=bind,source=$snapshotPath,target=/workspace" --mount "type=bind,source=$auditRoot,target=/audit" --mount "type=bind,source=$cacheRoot,target=/root/.cache/trivy" --mount 'type=bind,source=C:\Users\Tolis\.m2\repository,target=/root/.m2/repository,readonly' aquasec/trivy:0.76.0 fs --scanners vuln,misconfig,secret --timeout 20m --ignore-unfixed=false --exit-code 0 --format sarif --output /audit/trivy-source-final.raw.sarif /workspace
python scripts/check_trivy_sarif.py --report "$auditRoot\trivy-source-final.raw.sarif=$auditRoot\trivy-source-final.sarif"
```

Each final image was saved from its local immutable image ID and scanned with Trivy `0.76.0`, `vuln,secret`, `--timeout 20m`, `--ignore-unfixed=false`, `--exit-code 0`, and SARIF output. Syft `1.54.1` generated CycloneDX JSON from the same saved image archive with its SBOM source name set to the image tag. The executed PowerShell loop was:

```powershell
$auditRoot = 'C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty'
$cacheRoot = Join-Path $auditRoot 'trivy-cache'
$images = @(
  @{ Name = 'app'; Tag = 'fleetflow-app:day3-current' },
  @{ Name = 'keycloak'; Tag = 'fleetflow-keycloak-runtime:day3-current' },
  @{ Name = 'edge'; Tag = 'fleetflow-edge:day3-current' },
  @{ Name = 'postgis'; Tag = 'fleetflow-postgis:day3-current' },
  @{ Name = 'redis'; Tag = 'fleetflow-redis:day3-current' },
  @{ Name = 'kafka'; Tag = 'fleetflow-kafka:day3-current' }
)
foreach ($image in $images) {
  $archiveName = 'final-fleetflow-' + $image.Name + '.tar'
  $archivePath = Join-Path $auditRoot $archiveName
  docker save --output $archivePath $image.Tag
  docker run --rm --mount "type=bind,source=$auditRoot,target=/audit" --mount "type=bind,source=$cacheRoot,target=/root/.cache/trivy" aquasec/trivy:0.76.0 image --input ("/audit/" + $archiveName) --scanners vuln,secret --timeout 20m --ignore-unfixed=false --exit-code 0 --format sarif --output ("/audit/trivy-" + $image.Name + "-final.raw.sarif")
  $sourceName = if ($image.Name -eq 'keycloak') { 'fleetflow-keycloak-runtime' } else { 'fleetflow-' + $image.Name }
  docker run --rm --mount "type=bind,source=$auditRoot,target=/audit" anchore/syft:v1.54.1 scan ("docker-archive:/audit/" + $archiveName) --source-name $sourceName --source-version day3-current --output ("cyclonedx-json=/audit/fleetflow-" + $image.Name + "-final-sbom.cdx.json")
}
```

The repository SARIF policy/redaction command was run against all six image reports:

```powershell
python scripts/check_trivy_sarif.py --report "$auditRoot\trivy-app-final.raw.sarif=$auditRoot\trivy-app-final.sarif" --report "$auditRoot\trivy-keycloak-final.raw.sarif=$auditRoot\trivy-keycloak-final.sarif" --report "$auditRoot\trivy-edge-final.raw.sarif=$auditRoot\trivy-edge-final.sarif" --report "$auditRoot\trivy-postgis-final.raw.sarif=$auditRoot\trivy-postgis-final.sarif" --report "$auditRoot\trivy-redis-final.raw.sarif=$auditRoot\trivy-redis-final.sarif" --report "$auditRoot\trivy-kafka-final.raw.sarif=$auditRoot\trivy-kafka-final.sarif"
```

The source CycloneDX SBOM was generated from that same source snapshot using the CI-pinned Syft version and repository config:

```powershell
docker run --rm --mount "type=bind,source=$snapshotPath,target=/workspace" --mount "type=bind,source=$auditRoot,target=/audit" anchore/syft:v1.54.1 scan dir:/workspace --config /audit/syft-source-config.yaml --output cyclonedx-json=/audit/fleetflow-source-final-sbom.cdx.json
```

The final source report and six image reports are under:
`C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty`.
Raw reports remain in that local audit folder; sanitized reports are the review copies. Trivy results by image are reported above.

Syft `1.54.1` generated CycloneDX JSON SBOMs from the final image archives and the final source snapshot. The source and image SBOM SHA-256 values are recorded in:
`C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty\sbom-manifest.json`.

The immutable build metadata and dirty-source inputs are recorded in:

- `C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty\image-builds-audit.json`
- `C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3SupplyChain-2026-10-17-8fc2903-dirty\source-manifest.json`

These are local `AUDIT_ONLY` / `BUILD_METADATA_ONLY` artifacts. The image IDs identify local builds; no published registry digest or exact-commit CI run is claimed.

## Remaining finding inventory and disposition

The final source report contains no findings. The final app, edge, PostGIS, Redis, and Kafka image reports contain no findings. The Keycloak report contains 50 findings: 25 medium and 25 low, all against packages in the pinned upstream Keycloak runtime image. Every row records the package, installed version, severity, CVE, empty fixed-version result, disposition, and scanner evidence in [DAY-3-SUPPLY-CHAIN-FINDINGS-2026-10-17.csv](DAY-3-SUPPLY-CHAIN-FINDINGS-2026-10-17.csv).

**Disposition for each Keycloak row:** **OPEN — no fixed version is reported by Trivy `0.76.0`; no supported package update or newer upstream base has been established for the pinned Keycloak `26.8.1` image.** No exception, ignore rule, or suppression was added. Keep the findings open and rescan when a vendor-supported fixed package or updated base becomes available.

The six earlier source findings were low `DS-0026` notices requesting a Dockerfile `HEALTHCHECK`. They affected `Dockerfile`, `ops/production/keycloak.Dockerfile`, `ops/production/kafka.Dockerfile`, `ops/production/postgis.Dockerfile`, `ops/production/redis.Dockerfile`, and `web/Dockerfile.release`. The supported fix was to add Dockerfile healthchecks using the same service endpoints or built-in readiness utilities as the existing Compose probes. The final source report confirms zero findings; no scanner finding was suppressed.

## Exact-commit CI and immutable artifacts — 2026-10-17

Feature commit `bca17c48024b59cb26935e0d465f40d69344da96` was tested by a **direct push** run. Its GitHub `headSha`, checkout SHA, and `image-builds.json` `sourceCommit` match. [Direct-push run 38987979087](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087) and [PR integration run 38987982954](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987982954) both completed successfully for this SHA. PR #40 is a draft and remains unmerged.

| Exact-commit gate | Result | Run evidence |
| --- | --- | --- |
| Backend, migrations, API contracts, safety preflights, Compose validation | PASS | [Backend job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781575) |
| Frontend lint, typecheck, tests, build, signed browser smoke | PASS | [Frontend job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781544) |
| Source Trivy scan and SARIF policy | PASS — 0 source findings | [Source security job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117264917382) |
| Source CycloneDX SBOM | PASS — CycloneDX 1.7, 39 components | [Source SBOM job and artifacts](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781267) |
| Runtime image build, production-topology smoke, signed vertical journey, six Trivy scans, six image SBOMs, metadata | PASS | [Runtime image job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781416) |
| CodeQL Java/Kotlin and JavaScript/TypeScript | PASS | [Java/Kotlin job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781391), [JavaScript/TypeScript job](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087/job/117263781484) |
| GitGuardian PR check | PASS | [PR #40 checks](https://github.com/Omar-Mega-Byte/fleetflow/pull/40) |

The current-run Trivy artifacts have **0 findings** for source, app, edge, PostGIS, Redis, and Kafka. Keycloak has **50 findings (25 medium, 25 low)**; its 27 distinct finding IDs match the checked-in inventory in `DAY-3-SUPPLY-CHAIN-FINDINGS-2026-10-17.csv`. No finding was suppressed. The scan policy passed because the inventory has no critical/high findings, secrets, or unclassified results; the 50 medium/low findings remain open.

Run 38987979087 contains `image-builds-bca17c48024b59cb26935e0d465f40d69344da96`, `trivy-images-bca17c48024b59cb26935e0d465f40d69344da96`, `trivy-source-bca17c48024b59cb26935e0d465f40d69344da96`, `live-browser-bca17c48024b59cb26935e0d465f40d69344da96`, `day2-real-browser-bca17c48024b59cb26935e0d465f40d69344da96`, and the source plus six image CycloneDX artifacts named with the same SHA. All seven SBOMs validate as CycloneDX 1.7. The source contains 39 components; app 1,443; Keycloak 4,605; edge 1,285; PostGIS 1,620; Redis 710; Kafka 1,593.

CI image IDs are immutable Docker build IDs from the run's `BUILD_METADATA_ONLY` artifact. `registryDigests` is empty for every image; these are not registry digests, and no image was published. The `image-builds.json` artifact SHA-256 is `7d78dfb83efde68d53a51f2ca45ba63aebaa6a6ea603bbcbbca617082c6819e5`.

| Component | CI image ID for commit `bca17c4` | CycloneDX artifact SHA-256 |
| --- | --- | --- |
| App | `sha256:a1273421ebdc17da1ee789de77fc1dfaeee18e11d4a9e8be3b0f7c465fcc6040` | `01fd49fcadc0597a386cfe86adbc8bf43293b3c01e261152b45b5c8e0b5f8b8f` |
| Keycloak runtime | `sha256:ff4f8f488a5d9d03464ada651de71e87b8ac36a747ec795af69ca1cd86dbaaa4` | `4d4b524314043f775b40491061d457a7da4e9b7f24b41c3d5ff77b831e6fc00b` |
| Edge | `sha256:000abd97b218d018933ab9e0e4d3b4307f666a5ddf4f2183f1c95956ae7ec447` | `5e393190fb3a8c36949c818d81fb9e86a5355e9a122208ba2a9335f296f47e1e` |
| PostGIS | `sha256:78aa51879935775457a8a9f084e2d3754c33cccc29af0be1eb71e1890b8a0708` | `ab71890c9bac51307a638fd08534696c3f5127634c206250aa648f56a37ca1a8` |
| Redis | `sha256:13a4c80e13805fed5d8b326dbbe6af6719323ad03a4d95f82ca4766df5a1a94d` | `7a2e04ba6bbd64e7510c86506a33ea2f825383c818fa4435d335b3b86db44d8b` |
| Kafka | `sha256:fdb8cc3b212e58495c964ad4223dfbd5db14e07c23bac8e21f12a6239f3bb224` | `bff2f750bd5b87981a045f159cdd4598a45916bfc64b5548638e67012619ced5` |
| Source SBOM | — | `f989790160399a546d6c44e8c0e39b3b100227fc73344fcf62d19060a5955eb2` |

The CI production-topology result records `publicDeployment:false`, loopback binding `127.0.0.1`, HTTP 413 for oversized input, issuer spoofing blocked, 56 edge pressure rejections, a Kafka-outage business commit, generation 2 retained after restart, outbox 0 with 4/4 processed markers, Redis availability 0→1, and optional-dependency readiness `UP`. Its `result.json` is **PARTIAL** solely for owner-alert delivery because CI did not receive `FLEETFLOW_DISCORD_WEBHOOK_FILE`; the missing input is the secure owner Discord webhook file/credential. The separate 2026-10-17 local run used the configured owner webhook and recorded accepted Discord firing and resolved sends, as detailed below. CI did not claim alert delivery.

The exact-run signed vertical journey artifact records reset generation 2, two tracking-stream requests, a persisted `DELIVERED|8` order, and logout. Its database postcondition and `vertical-result.json` SHA-256 values are `008008ecac39f07f16288eeb70cdc3d0321d1ede47f94e938a2ef3f9a0574012` and `bc31fd536440d102d1dd9ba35d55931b85106ebc0a51e66891eb78aa60b6ee8c` respectively.

## Previously collected local acceptance evidence

| Gate | Result | Evidence and scope |
| --- | --- | --- |
| Cross-persona vertical workflow | PASS — private disposable environment | Fresh Day 2 run covered PKCE, operator seed/reset, customer order, merchant, dispatcher, driver, SSE and persisted `DELIVERED`. Evidence: `%LOCALAPPDATA%\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\FleetFlowDay2Audit\2026-10-16\vertical-journey`. |
| Private signed browser and recovery smoke | PASS — private local only | The 2026-10-16 harness passed two signed Playwright journeys against disposable Keycloak and isolated PostGIS. It blocked issuer spoofing, returned HTTP 413 for an oversized body, rejected 48 edge-pressure requests, committed business work during broker outage, retained generation 2 after app restart, recovered all 4 processed markers with 0 outbox entries remaining, and kept optional-dependency liveness/readiness `UP`. The harness reported `publicDeployment:false`. Evidence: `%LOCALAPPDATA%\Temp\FleetFlowDay3Audit-2026-10-16-01a14629\day1-current-bundle\browser\result.json` and adjacent Playwright output. |
| Monitoring rule/config validation | PASS — 2026-10-16 rule checks | `promtool check config ops/monitoring/prometheus.yml` (9 rules), `promtool check rules ops/monitoring/alerts.yml` (9 rules), and `promtool test rules ops/monitoring/alerts.test.yml` passed. Those checks did not prove delivery; actual target delivery is verified in the 2026-10-17 section below. |
| Existing GitHub CI | Historical — base SHA only | [Run 38951366392](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38951366392) passed on base SHA `8fc2903403e6f01530e25bb6753de6f289cc5bfe`; it does not validate the feature branch. Exact feature-commit CI is recorded in the section below. |

## 2026-10-17 live persona, recovery, and notification closeout

All runs below used fresh, uniquely named disposable Compose projects. The Day 2 harness used the local Day 3 PostGIS, Redis, and Kafka builds with its pinned upstream Keycloak image and packaged application JAR; its development topology does not mount the production Keycloak database secret. The production-topology harness used all six local Day 3 image builds. It bound the edge only to `127.0.0.1:80/443`; Prometheus and Alertmanager had no host ports. Both harnesses removed their containers and project volumes. No public deployment occurred.

### Exact commands

The signed five-persona journey ran with the fresh Day 3 data-service images and pinned upstream Keycloak:

```powershell
$env:FLEETFLOW_TEST_IMAGE_POSTGRES='fleetflow-postgis:day3-current'
$env:FLEETFLOW_TEST_IMAGE_KEYCLOAK='quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244'
$env:FLEETFLOW_TEST_IMAGE_REDIS='fleetflow-redis:day3-current'
$env:FLEETFLOW_TEST_IMAGE_KAFKA='fleetflow-kafka:day3-current'
$env:FLEETFLOW_DAY2_AUDIT_DIR='C:\Users\Tolis\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\FleetFlowDay3Audit\2026-10-17-personas-fffc58bf'
python scripts/Run-Day2VerticalJourney.py
```

The production-topology smoke used all six current local images and the existing Git-ignored private Discord webhook file. The URL value is not recorded in the repository or evidence:

```powershell
$env:FLEETFLOW_TEST_IMAGE_APP='fleetflow-app:day3-current'
$env:FLEETFLOW_TEST_IMAGE_KEYCLOAK='fleetflow-keycloak-runtime:day3-current'
$env:FLEETFLOW_TEST_IMAGE_EDGE='fleetflow-edge:day3-current'
$env:FLEETFLOW_TEST_IMAGE_POSTGRES='fleetflow-postgis:day3-current'
$env:FLEETFLOW_TEST_IMAGE_REDIS='fleetflow-redis:day3-current'
$env:FLEETFLOW_TEST_IMAGE_KAFKA='fleetflow-kafka:day3-current'
$env:FLEETFLOW_DISCORD_WEBHOOK_FILE=(Resolve-Path .env.discord-webhook).Path
$env:FLEETFLOW_AUDIT_DIR='C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3Audit-2026-10-17-live-876f509e'
python scripts/Run-Day1BrowserSmoke.py
```

### Results

| Gate | Result | Evidence |
| --- | --- | --- |
| Five persona journeys and lifecycle | PASS | Day 2 Playwright **1/1** in 40.1 seconds. Operator seeded generation 1 and reset generation 2; customer created the order; merchant confirmed/prepared/readied it; dispatcher matched and sent the offer; driver accepted, picked up, started transit, and completed delivery; customer observed all status changes over SSE. PostgreSQL postcondition: `DELIVERED|8`, all nine lifecycle states. |
| Interrupted reset and same-request retry | PASS | The browser route let the first reset reach the API and commit generation 2, then dropped its response. The UI showed `Connection unavailable` and unknown-result guidance, disabled the reason and dialog close, and offered retry. The second POST had the same JSON body and `Idempotency-Key`; the API confirmed generation 2. |
| SSE reconnect and authorization denials | PASS | The first customer tracking stream request was deliberately aborted; exactly one reconnect followed, and tracking updates resumed through `DELIVERED`/`Tracking ended`. The Day 1 signed suite passed **2/2**: a driver reading another actor's order received 403, a customer's unknown order returned 404, an unauthenticated order read returned 401, and private Actuator/OpenAPI/Swagger/Keycloak admin routes returned 404 at the edge. A customer route to the operator workspace showed `Access unavailable`; stale dispatcher state showed `This information changed`. |
| Accessibility and mobile | PASS | The Day 2 journey ran **11 axe audits with zero violations**. Operator, customer, merchant, dispatcher, and driver each passed the 390px × 844px no-horizontal-overflow check; screenshots are in the Day 2 evidence directory. The Day 1 suite also rendered the PostGIS zone overlay and saved desktop/mobile captures. |
| Error and retry states | PASS | Live browser coverage exercised the ambiguous reset network outcome and dispatcher conflict. `npm --prefix web run test -- src/pages/OperationsPage.test.tsx` passed **5/5**, including retry with the same body/key and safe 429/503 messages without false success. |
| Private metrics and redaction | PASS | Day 1 scraped the management endpoint only from the app container, checked 13 required meters, rejected prohibited actor/order/token/address/location/reason labels, and confirmed the edge does not expose health/metrics/docs routes. The Playwright sanitizer ran; successful journeys produced no failure trace to scrub. A post-run scan of 27 structured evidence files found **zero** copies of the Discord webhook value. |
| Dependency and outbox recovery | PASS | A signed reset committed while Kafka was stopped, generation 2 survived app restart, then Kafka recovery drained the outbox to **0** with **4/4** expected consumer markers. A separate Redis stop/restart reported the private gauge **0 → 1**, liveness/readiness stayed `UP`, and the app restart count stayed 0. |
| Alert firing and owner delivery | PASS — actual Discord target | The real `FleetFlowOutboxBacklog` rule fired during the Kafka-only outage and cleared after the outbox drained. The configured `fleetflow-discord` receiver's `send_resolved: true` path sent through the existing private webhook file. Alertmanager's Discord accepted-notification count moved **0 → 1** for firing and **1 → 2** for recovery; the final evidence has `firingAlertObserved:true`, `resolvedAlertObserved:true`, `verified:true`, and `ownerAlertDeliveryMissingInput:null`. This was live Alertmanager delivery, not a promtool-only claim. |

The live harness queried the firing rule and the Discord integration counters from the running Prometheus/Alertmanager containers:

```powershell
$prometheus='fleetflow-day1-audit-3d34e31e2a-prometheus-1'
docker exec $prometheus promtool query instant http://127.0.0.1:9090 'sum(ALERTS{alertname="FleetFlowOutboxBacklog",alertstate="firing"})'
docker exec $prometheus promtool query instant http://127.0.0.1:9090 'sum(alertmanager_notifications_total{integration="discord"})'
docker exec $prometheus promtool query instant http://127.0.0.1:9090 'sum(alertmanager_notifications_failed_total{integration="discord"})'
```

The notification acceptance count is the Discord attempted count minus failed count. It was observed while the actual `discord_configs` receiver and private webhook file were active. The webhook URL is absent from the acceptance record, logs, and checked evidence.

Day 2 evidence is under `C:\Users\Tolis\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\FleetFlowDay3Audit\2026-10-17-personas-fffc58bf\` (`vertical-result.json`, `database-postcondition.json`, sanitized Playwright output, and five mobile screenshots). Day 1 evidence is under `C:\Users\Tolis\AppData\Local\Temp\FleetFlowDay3Audit-2026-10-17-live-876f509e\browser\` (`result.json`, `healthy.prom`, outage/recovery metrics, Playwright log, screenshots, and cleanup log). The Day 1 result records `publicDeployment:false`; the Compose project and its volumes were removed.

### Affected local checks

| Command | Result |
| --- | --- |
| `npm --prefix web run lint` | PASS |
| `npm --prefix web run typecheck` | PASS |
| `npm --prefix web run test` | PASS — 54 tests, 14 files |
| `python -m unittest discover -s scripts -p 'test_*.py' -v` | PASS — 11 tests |
| `python -m py_compile scripts/Run-Day1BrowserSmoke.py scripts/Run-Day2VerticalJourney.py` | PASS |
| `git diff --check` | PASS |

**Execution note:** an initial Day 2 launch used the production Keycloak runtime image in the development harness and exited before browser testing because that image requires a production database-secret mount. The private Compose project was removed; the successful run above used the harness's pinned upstream Keycloak image. The first instrumented browser run then exposed the stale one-stream assertion after the deliberate SSE drop; the assertion was updated to require the failed attempt and one recovery, and the rerun passed. An initial combined Kafka/Redis alert trial also produced two Discord notifications before its metric selector was corrected; no recovery pass was claimed from that trial. The final Kafka-only run above verified one firing and one resolved delivery. The temporary fixture and generated credentials from the interrupted trial were removed.

## Remaining release gates

- The 50 Keycloak medium/low findings remain open pending vendor-supported fixes; the policy gate reports zero blockers because no critical/high, secret, or unclassified findings were detected.
- Direct-push CI passed on feature commit `bca17c48024b59cb26935e0d465f40d69344da96` at run [38987979087](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987979087), and PR integration run [38987982954](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/38987982954) passed. The earlier base-SHA run remains historical only.
- The local owner-alert firing and recovery reached the configured Discord webhook, but public-host monitoring, credential rotation, and long-running alert behavior remain outside this disposable test.
- Public Azure, external-port, cost, DNS/certificate, and independent target-restore gates have not been run in this Day 3 closeout.

Phases 4, 5, 7, and 8 remain **IN_PROGRESS** for their remaining phase-specific acceptance gates: PostgreSQL/target observability qualification, target recovery and unsupported poison disposition, target-host persona acceptance, and supported Keycloak findings plus registry candidate provenance. Phase 11 remains **CLOSED_PARTIAL**. Day 3 remains **PARTIAL / NOT COMPLETE** until those open findings and mandatory external evidence are resolved.

No public deployment or release approval was performed.
