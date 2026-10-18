# Days 1–3 full audit — 2026-10-17

**Branch:** `codex/day3-persona-closeout`. **Starting HEAD:** `002836ae31c47c263fab3e5d4875cb6d5480ee4f`, initially clean. Existing history and the ordinary developer data were preserved. This audit covers the first three execution days and their repository-controlled acceptance, with disposable local services. Public deployment, merge, tags and changes to `main` are outside the authorized work.

**Decision: PARTIAL / NOT COMPLETE.** Day 1 remains conditionally complete with external prerequisites. Day 2's local vertical workflow is verified. Day 3 has expanded local acceptance; current-commit CI and remaining release gates are recorded below. Legacy Phase 11 remains **CLOSED_PARTIAL**.

## Repairs and completed features

| Finding | Change and evidence |
|---|---|
| A lost customer-create response allowed an edited draft to be retried under the original key | Immutable request snapshot, synchronous duplicate lock, locked fields/dialog and explicit same-body/key Retry. Component regressions and a real committed-response-drop browser exercise verify one created order. Definite validation rejection permits correction with a fresh key. |
| A replacement boundary could save stale coordinates after drawing was cleared or undone | Extracted zone editor clears geometry below three vertices, preserves complex geometry on a name-only edit, supports labeled keyboard vertices, closes rings and caps vertex editing. Component and real PostGIS browser checks cover these cases. |
| Existing map polygons swallowed drawing clicks; tile errors could remain after recovery | Drawing receives polygon clicks; tile failures are tracked by tile lifecycle; Retry redraws tiles while retaining authorized overlays. The live zone exercise verifies failure and fixture recovery. Separate private edge evidence records actual external tile delivery. |
| Branch coverage could only be checked outside the UI | Merchant destination check uses the existing signed serviceability API, reports actual coverage and policy version, and invalidates displayed results after policy changes. Live enforced-inside/outside and unrestricted checks pass. |
| Synthetic driver workflow required device GPS | Added a read-only, demo-enabled driver fixture endpoint restricted to the active seeded actor and owned dataset. Five six-decimal fixture points come from the actual seeded order. Manual UI submissions advance only after normal signed location confirmation. The seeded reviewer never receives a GPS button or requests device geolocation. Other accounts retain explicit device sharing only after a definite non-demo lookup. |
| Retry-After countdown could carry the previous response's deadline | Each problem records its own deadline; regression covers network→429 and repeated equal-delay 429. |
| A placeholder workspace route remained reachable | Workspace index now redirects to the implemented Command Center. |
| PostgreSQL outage evidence stopped at failure | Backend test and private topology exercise stop/start PostgreSQL, verify 503 readiness with UP liveness, restore readiness and signed reads, retain durable data and prove the application was not restarted. The disposable test endpoint stays fixed across Docker stop/start. |
| Sanitized SARIF could lose a prior secret blocker when processed again | Retained secret summaries remain blocking; UNKNOWN severity also blocks. Regressions verify both. No ignore rule, exception or finding suppression was added. |
| CI build artifacts were individually retained without a complete identity check | New final evidence job validates exact source/run, six runtime identities, seven SBOMs, all Trivy/CodeQL reports, signed journey/zone/recovery evidence and independent SQL postconditions; it hashes the inputs and refuses mismatches or missing artifacts. It explicitly leaves candidate approval false. |
| SBOM action used a deprecated Node runtime | Updated the immutable action pin to the official v0.24.3 commit `66cbf4bc1f1c0d2edc94016e65bc221b6bb0ad6c`, which uses Node 24 and retains the existing inputs. Syft stays pinned to 1.54.1. Final-head CI must verify the updated pin. |
| CI owner delivery was optional even on the authorized branch push | Existing private owner webhook is supplied as an encrypted repository secret, written to a temporary private file only on trusted pushes. Required firing and resolved delivery use per-phase successful-notification deltas. PR runs receive no webhook and explicitly report that limitation. |

The synthetic route is not a road-routing feature and does not advance delivery state. Order creation remains local/test only. Unsupported arbitrary malformed-poison replay/skip remains an optional SHOULD item under the Day 3 schedule; it is not silently treated as an implemented action or a mandatory local gate.

## Verification commands and results

Full local logs are outside the repository at `C:\Users\Tolis\AppData\Local\Packages\OpenAI.Codex_2p2nqsd0c76g0\LocalCache\Local\FleetFlowDays1-3Audit\20261017-042018`. The local working tree was dirty during these rehearsals; they do not substitute for CI on the committed source.

| Exact command / gate | Result |
|---|---|
| `./mvnw.cmd -Dmaven.repo.local=C:\Users\Tolis\.m2\repository clean verify --batch-mode --no-transfer-progress` on starting HEAD | PASS: 145 tests, zero failures/errors/skips; `baseline-backend.log`. |
| `npm ci --prefix web`; `npm audit --prefix web --audit-level=high` | PASS: reproducible install and zero npm advisories; baseline logs. |
| `npm --prefix web run lint`; `typecheck`; `test`; `build` | PASS on updated client: 66 tests / 17 files, zero failures; `isolated-frontend-*.log`. Four unit workers bound resource use. |
| `npm --prefix web run test:e2e`; `npm --prefix web run test:performance` | PASS: three fixture journeys and one performance/reduced-motion check. Fixture evidence remains separate from signed live acceptance. |
| `./mvnw.cmd -Dmaven.repo.local=C:\Users\Tolis\.m2\repository -Dtest=DatabaseOutageTests,DemoResetSafetyTests,Phase11RouteBoundaryTests,OpenApiDocsTests,ProdProfileRoutesTests package --batch-mode --no-transfer-progress` | PASS: 11 focused tests after the endpoint and restart corrections; `focused-backend.log`. |
| `python -m unittest discover -s scripts -p 'test_*.py' -v` | PASS: 30 tests, including empty scanner execution, owner-delivery, source coverage and release-evidence failure behavior. |
| `python -m py_compile scripts/Run-Day2VerticalJourney.py scripts/Run-Day1BrowserSmoke.py scripts/record_release_evidence.py` | PASS. |
| `python scripts/Phase11-Secret-Audit.py`; `git diff --check` | PASS; credential preflight inspected 481 paths before the documentation additions. |
| `docker compose --env-file .env.example -f compose.yaml config --quiet`; `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1`; `actionlint.exe -no-color` | PASS: local/production boundary render and workflow syntax. Production render exposes only the edge on 80/443. |
| `python scripts/Run-Day2VerticalJourney.py` with fresh `FLEETFLOW_DAY2_AUDIT_DIR=...\personas-acceptance` | PASS: 3/3 signed live journeys, real Keycloak/API/PostGIS/Redis/Kafka/SSE, five personas, keyboard command activation, visible focus, 390px no-overflow and zero axe violations. |
| Updated local clean Maven verification | PASS: 146 tests, zero failures/errors/skips, 5:18; `backend-final-continued.log`. The direct-push CI backend on `9f5d9e9` also passed all 146. |
| `python scripts/Run-Day1BrowserSmoke.py` with six audit images and required owner input | PASS: resumed private production topology, 2/2 signed smoke tests, real external tiles, private metrics/redaction, outbox/Redis/database recovery and completed backlog-group firing/recovery Discord requests. Evidence: `private-resumed/browser/`. |
| `pwsh -NoProfile -File scripts/Run-Day2BackupRestoreSmoke.ps1 -EvidenceDirectory C:\Users\Tolis\AppData\Local\FleetFlowDays1-3Audit\20261017-042018\backup-restore` | PASS: encrypted archive integrity, fresh independent disposable target, restored identity/PostGIS, signed read/cancel and SQL `CANCELLED`; local functional RTO 72.8 seconds. |
| Independent SQL after the signed browser | PASS: delivery `DELIVERED|8`; dispatch recovery / valid zone revision / policy / own-branch zone count `CANCELLED|2:true|UNRESTRICTED|2`. Resource IDs match their browser JSON. |

The live suite covers committed reset and customer/zone-create response interruption; same-body/key recovery; first SSE request failure and reconnect; actual zone concurrent-update conflict; foreign existing branch's privacy-preserving 404; wrong-persona denials; injected client 401/429/503; real reauthentication; actual no-candidate failure, retry to ranked candidates, driver rejection and dispatcher cancellation. Injected client errors are labeled as such; they are not represented as dependency outages.

Screenshots `zone-editor-mobile.png` and `driver-mobile.png` were inspected after success. The editor uses labeled fields and a scrollable dialog; the driver shows fixture progress and no GPS action. The guide is [Private reviewer journey](../../operations/PRIVATE-REVIEWER-JOURNEY.md).

## Failed attempts retained

- First updated backend run: OpenAPI operation count needed 64→65; the test still compares the complete registered-route set. Docker restart changed the randomly assigned PostgreSQL host port; the test now binds a stable loopback endpoint. Focused rerun passed.
- Unit tests run concurrently with image builds and live browsers timed out across unrelated files. Four workers and an isolated rerun passed all 66 tests. No timeout increase or skipped assertion was used to mask this.
- First edge rebuild retry failed fetching an unchanged npm package. Retry with the named existing Docker builder succeeded; the lockfile was preserved.
- Live harness initially attempted the production Keycloak entrypoint without mounted production secrets and correctly failed closed. The local fixture harness uses the pinned base; the separate private production rehearsal uses the custom runtime and secret files.
- Cold signed session initialization took 9.284 seconds during contention. A signed application preflight now checks actual role resolution after readiness before opening the browser; it does not change browser authentication or mock APIs.
- Keyboard `.press` on a pending disabled action did nothing; the browser suite now waits for enabled controls before Enter. The foreign branch denial assertion now follows the server's deliberate 404 privacy contract, with an independently existing foreign fixture.

- The stronger owner-delivery rehearsal was interrupted while Docker was later stopped. Its exact backlog-group firing/recovery success records survived, but it did not write a final result; no complete pass is claimed for that run. A fresh rehearsal was required and subsequently passed as `private-final`; the final database-specific receiver recovery gate is checked in the final-head CI artifact.
- On resume, the exact orphaned project `fleetflow-day1-audit-861dfb82a8` and its volumes were removed. Automatic approval review rejected recursive removal of `C:\Users\Tolis\AppData\Local\Temp\fleetflow-day1-audit-861dfb82a8-e3hf9ntz` with "blocked by policy". A narrower cleanup then removed each of the eleven verified files by literal path, confirmed the directory was empty and removed it without recursion. The folder, generated credentials/TLS files, disposable databases and containers are now gone. The configured owner webhook itself was mounted from the ignored private file and is not copied into this record.

## Final build and CI evidence

The first implementation commit is `9f5d9e9697b6e3561821d7144bb68d2737f1e1f6`. Its [PR run 39021099031](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39021099031) passed all eight jobs. Its [direct-push run 39021095840](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39021095840) failed during private topology startup; the required owner credential's restrictive host permissions and inherited 0077 umask exposed a Linux fixture-mount gap. That original run did not retain startup diagnostics, so no more specific runtime error is claimed. The follow-up copies secret/realm files into scoped runtime-volume subpaths owned by the consumer UID, makes only public monitoring configuration readable inside the private temporary directory, and retains sanitized startup/health/cleanup diagnostics. A narrow Linux-container proof verifies old 0600/UID1001 unreadability and corrected UID65534/0400 readability; unit checks exercise the restrictive umask.

The original raw source SBOM had 39 components, unresolved Maven versions and omitted npm development entries. The replacement uses pinned CycloneDX Maven plugin 2.9.3 for authoritative selected coordinates and Syft's SBOM cataloger plus all locked npm entries. Generic Java coordinate guessing is replaced only for SBOM generation; Trivy scans and finding policy are unchanged. Local coverage verifies every one of 193 Maven-selected dependencies and 270 npm lockfile identities, with no unknown versions. The coverage JSON hashes the source SBOM, Maven graph and lockfile; the final CI evidence job checks the same source/hash identity. The independent failure tests reject missing dev/transitive coordinates, wrong Maven groups, unknown versions and mismatched coverage hashes.

### Completed implementation checkpoint

Commit `da526e6f6e94e15d0a24643121aac4f58f165900` passed **8/8 jobs** in both [trusted-push run 39022493957](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39022493957) and [PR integration run 39022498045](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39022498045). The trusted push verified actual backlog firing/recovery delivery; the PR deliberately receives no owner webhook. Twenty-six downloaded artifact hashes were independently checked. [Frozen machine evidence](evidence/days1-3-ci-da526e6.json) preserves that exact checkpoint. It does not validate subsequent source changes.

Source and five runtime Trivy reports have zero findings. Keycloak has **50 OPEN findings: 25 MEDIUM, 25 LOW**, no HIGH/CRITICAL, secrets, unknown/unclassified results or policy blockers. Every finding has its package/version, component, severity, open disposition, source/image identity and scan evidence in [the refreshed inventory](DAYS-1-3-SUPPLY-CHAIN-FINDINGS-2026-10-17.csv). No fixed vendor package version is reported. The official [latest Keycloak release](https://github.com/keycloak/keycloak/releases/tag/26.8.1) was rechecked on 2026-10-17: 26.8.1 remains latest. No exception or suppression was introduced.

The following are immutable Docker **build image IDs** for that checkpoint. Registry digests are empty; these are not published candidate digests.

| Image | Build image ID |
|---|---|
| app | `sha256:69581912d3d7d65a9c0e49189320c861fcfe5e88df387c1276c680ed155b26b5` |
| keycloak-runtime | `sha256:8908c0c683b105937cad7e76247e1b5e7c1504262b4e369d8d48593b526401fa` |
| edge | `sha256:8c37e73a47fa1fff3facbe10b97b1da247f627a14d28356855ace74aef32d6cf` |
| postgis | `sha256:1453a86e0c40d2ec712943527913ed9ae2073dd23cc089a914f1fcac1d1d880b` |
| redis | `sha256:364223ee730e3be29523ee290c3f396bb7f9c6212de08c75063ebd0ddea874a4` |
| kafka | `sha256:4b69b374484f0dec27f6964ece6a7b326326b7d65924622ceb1c09085dc9971d` |

Seven CycloneDX artifacts were verified, including all 193 selected Maven dependencies and 270 npm lockfile identities with no unknown versions:

| SBOM | Components | SHA-256 |
|---|---:|---|
| app | 1443 | `fac95951bbed8b03e930797db4070bf53a86a0fd5cc01390d8fc9f023a8cc7d5` |
| keycloak | 4605 | `72e263938b5cfa2c984c3569d2fb48857639c4548fde45c9290bded0d59e1abb` |
| edge | 1285 | `163cdebadb44728ea4ce05af392d73caa6b67bf04a57b4d609b6789aeccfd310` |
| postgis | 1620 | `9aadec5a03c7adddb0098e363730940cdeca7f911f8ba5ef401db043ce0b1f1f` |
| redis | 710 | `ff6d3aa6112c230b5251f4dea5849ff827865cf6a600a7c8d303c18682dedc09` |
| kafka | 1593 | `1251941bdceba8d3be88cb94f3d72e599c3c006988ffa1a991cafa0092a272b6` |
| source-sbom | 476 | `1fa222261507e701f6e74dea9ea909cd68ab257ed3befe30f679c99274a7584c` |

### Final exact-head acceptance record

The final follow-up also requires completed firing **and recovery** notifications for `FleetFlowDatabaseReadinessLoss`. The previous local rehearsal recorded database rule clearance but only one database-group notification before cleanup. The corrected harness waits for the second completed request and the evidence gate checks both class-specific notification files. Rule clearance alone cannot pass this gate.

For the final head, `scripts/record_release_evidence.py` generates the authoritative **exact-run acceptance record** as CI artifact `release-evidence-<full source SHA>`. It contains its own full source/head commit, CI URL, all six runtime identities, seven SBOM hashes, complete source-coverage identity, scanner results, matched browser/database resource IDs, and both owner notification evidence hashes. It explicitly records `acceptanceDecision: PARTIAL_NOT_COMPLETE`, `candidateApproved: false` and `legacyPhase11: CLOSED_PARTIAL`. No earlier checkpoint substitutes for that final trusted-push gate.

The reviewed local copy of this final-head record is `target/day3-acceptance.json` (Git-ignored; obtained from the successful exact-head CI artifact). Use the matching SHA/run under [feature-branch CI](https://github.com/Omar-Mega-Byte/fleetflow/actions/workflows/verify.yml?query=branch%3Acodex%2Fday3-persona-closeout). This avoids embedding a self-referencing commit into its own source document. The task closes only after the final required push/PR jobs and both owner alert classes pass; public/release acceptance remains partial.

### Day and phase decisions

| Scope | Status | Evidence / limit |
|---|---|---|
| Day 1 repository-controlled foundation | CONDITIONALLY COMPLETE — EXTERNAL DEPENDENCY | Current correctness/auth/error, migration, scanner and private-boundary checks pass; cloud/target inputs remain separate. |
| Day 2 local vertical acceptance | VERIFIED | Signed delivery persists `DELIVERED|8`; fresh independent encrypted restore reaches signed read/cancel and `CANCELLED`, RTO 72.8 seconds. |
| Day 3 | PARTIAL / NOT COMPLETE | Local workflow and build evidence pass; open vendor findings and published/approved candidate controls remain. |
| Phase 4 | IN_PROGRESS; Day 3 private slice verified | Private real metrics/redaction, dependency recovery and class-specific owner-delivery gates; target backup/certificate/monitoring integration remains later work. |
| Phase 5 | IN_PROGRESS; Day 3 MUST slice verified | Bounded effects, durable drain/dedupe and safe deterministic seed/reset; arbitrary malformed-poison replay/skip is an unsupported optional SHOULD item. |
| Phase 6 | COMPLETE for declared client foundation | PKCE/storage, bounded API/SSE, 66 client tests, fixture/live browser and responsive/focus checks. Public issuer/host acceptance remains Phase 9. |
| Phase 7 | COMPLETE for declared private synthetic workflows | Five signed personas, delivery/zone/recovery journeys, denials/conflicts/retry, interrupted commands, mobile/accessibility and controlled synthetic movement. |
| Phase 8 | IN_PROGRESS | Current scans, resolved SBOMs and immutable build evidence operate; 50 Keycloak findings, registry publication and candidate provenance/approval remain open. |
| Legacy Phase 11 | CLOSED_PARTIAL | No sustained RPO, replacement-host, public target or production claim is inferred. |
 Previous successful runs on `bca17c4` and `002836a` validate their own source only.

## Remaining gates and next action

- The final image inventory remains open for all 50 Keycloak package findings; the refreshed CSV and exact-head artifact are the evidence. Keep every current image advisory visible with component, severity, disposition and exact scan evidence. Apply only a supported vendor fix; do not infer a backport from a failed advisory lookup. The Red Hat public CVE API returned HTTP 403 during this audit; no applicability conclusion was drawn from it.
- Registry publication, immutable registry digests and candidate provenance/approval remain open. Local image config IDs and successful CI are build evidence; no published candidate or deployment is claimed.
- Public Azure subscription/region/SKU/budget, hostname/DNS/TLS, runtime secret custody and target-host recovery/load/cost evidence remain external. Public deployment is explicitly outside the authorized task. Local disposable restore is not replacement-host or sustained RPO evidence.
- Keep Day 3 **PARTIAL / NOT COMPLETE** and legacy Phase 11 **CLOSED_PARTIAL** while these gates remain unmet. The next release action is a vendor-supported advisory resolution and a separately approved immutable candidate/target qualification.
