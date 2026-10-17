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
| `python -m unittest discover -s scripts -p 'test_*.py' -v` | PASS: 21 tests, including empty scanner execution, owner-delivery and release-evidence failure behavior. |
| `python -m py_compile scripts/Run-Day2VerticalJourney.py scripts/Run-Day1BrowserSmoke.py scripts/record_release_evidence.py` | PASS. |
| `python scripts/Phase11-Secret-Audit.py`; `git diff --check` | PASS; credential preflight inspected 481 paths before the documentation additions. |
| `docker compose --env-file .env.example -f compose.yaml config --quiet`; `pwsh -NoProfile -File scripts/Validate-ProductionCompose.ps1`; `actionlint.exe -no-color` | PASS: local/production boundary render and workflow syntax. Production render exposes only the edge on 80/443. |
| `python scripts/Run-Day2VerticalJourney.py` with fresh `FLEETFLOW_DAY2_AUDIT_DIR=...\personas-acceptance` | PASS: 3/3 signed live journeys, real Keycloak/API/PostGIS/Redis/Kafka/SSE, five personas, keyboard command activation, visible focus, 390px no-overflow and zero axe violations. |
| Independent SQL after the signed browser | PASS: delivery `DELIVERED|8`; dispatch recovery/zone/policy/reset `CANCELLED|2:true|UNRESTRICTED|2`. Resource IDs match their browser JSON. |

The live suite covers committed reset and customer/zone-create response interruption; same-body/key recovery; first SSE request failure and reconnect; actual zone concurrent-update conflict; foreign existing branch's privacy-preserving 404; wrong-persona denials; injected client 401/429/503; real reauthentication; actual no-candidate failure, retry to ranked candidates, driver rejection and dispatcher cancellation. Injected client errors are labeled as such; they are not represented as dependency outages.

Screenshots `zone-editor-mobile.png` and `driver-mobile.png` were inspected after success. The editor uses labeled fields and a scrollable dialog; the driver shows fixture progress and no GPS action. The guide is [Private reviewer journey](../../operations/PRIVATE-REVIEWER-JOURNEY.md).

## Failed attempts retained

- First updated backend run: OpenAPI operation count needed 64→65; the test still compares the complete registered-route set. Docker restart changed the randomly assigned PostgreSQL host port; the test now binds a stable loopback endpoint. Focused rerun passed.
- Unit tests run concurrently with image builds and live browsers timed out across unrelated files. Four workers and an isolated rerun passed all 66 tests. No timeout increase or skipped assertion was used to mask this.
- First edge rebuild retry failed fetching an unchanged npm package. Retry with the named existing Docker builder succeeded; the lockfile was preserved.
- Live harness initially attempted the production Keycloak entrypoint without mounted production secrets and correctly failed closed. The local fixture harness uses the pinned base; the separate private production rehearsal uses the custom runtime and secret files.
- Cold signed session initialization took 9.284 seconds during contention. A signed application preflight now checks actual role resolution after readiness before opening the browser; it does not change browser authentication or mock APIs.
- Keyboard `.press` on a pending disabled action did nothing; the browser suite now waits for enabled controls before Enter. The foreign branch denial assertion now follows the server's deliberate 404 privacy contract, with an independently existing foreign fixture.

- The stronger owner-delivery rehearsal was interrupted while Docker was later stopped. Its exact backlog-group firing/recovery success records survived, but it did not write a final result; no complete pass is claimed for that run. A fresh rehearsal is required.
- On resume, the exact orphaned project `fleetflow-day1-audit-861dfb82a8` and its volumes were removed. Automatic approval review rejected recursive removal of `C:\Users\Tolis\AppData\Local\Temp\fleetflow-day1-audit-861dfb82a8-e3hf9ntz` with "blocked by policy". Its generated fixture credentials and TLS files remain in that temporary folder; the corresponding disposable databases and containers are gone. The configured owner webhook itself was mounted from the ignored private file and is not copied into this record.

## Final build and CI evidence

CI_PENDING — populate only from the newly pushed exact source and downloaded artifacts. Previous successful runs on `bca17c4` and `002836a` validate their own source only.

## Remaining gates and next action

- Keep every current image advisory visible with component, severity, disposition and exact scan evidence. Apply only a supported vendor fix; do not infer a backport from a failed advisory lookup. The Red Hat public CVE API returned HTTP 403 during this audit; no applicability conclusion was drawn from it.
- Registry publication, immutable registry digests and candidate provenance/approval remain open. Local image config IDs and successful CI are build evidence; no published candidate or deployment is claimed.
- Public Azure subscription/region/SKU/budget, hostname/DNS/TLS, runtime secret custody and target-host recovery/load/cost evidence remain external. Public deployment is explicitly outside the authorized task. Local disposable restore is not replacement-host or sustained RPO evidence.
- Keep Day 3 **PARTIAL / NOT COMPLETE** and legacy Phase 11 **CLOSED_PARTIAL** while these gates remain unmet. The next release action is a vendor-supported advisory resolution and a separately approved immutable candidate/target qualification.
