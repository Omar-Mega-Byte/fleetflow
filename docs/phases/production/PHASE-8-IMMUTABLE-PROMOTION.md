# Phase 8 immutable promotion handoff — 2026-10-18

**Technical review has no `OPEN` row, and the six images have been published privately.** The [publication record](PHASE-8-GHCR-PUBLICATION-2026-10-18.md) contains all six verified GHCR digest references, matching scanned config IDs and pull-back results. The [exact-image security reconciliation](KEYCLOAK-SECURITY-RECONCILIATION-2026-10-18.md) and [reachability audit](KEYCLOAK-REACHABILITY-AUDIT-2026-10-18.md) account for all 28 CVEs. Independent owner review, candidate approval and Azure deployment remain separate gates. The command sequence below is retained as a reproducible promotion procedure; do not push a second image set for this same checkpoint.

## Registry choice

Use **GitHub Container Registry (GHCR)** for this six-image synthetic demo. GitHub currently says [container image storage and bandwidth are free](https://docs.github.com/en/billing/concepts/product-billing/github-packages), and [GHCR supports private packages, access controls and OCI/Docker images](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry). This avoids an additional Azure registry resource under the owner's $10/month target. Recheck GitHub's policy and package visibility before publishing. ACR Basic is a valid technical registry but [has a paid pricing tier](https://learn.microsoft.com/en-us/azure/container-registry/container-registry-skus); its cost would need a separate account-specific budget decision. The Azure VM must have a private-package pull credential with `read:packages`, supplied through the approved secret channel, before Phase 9 starts. Keep packages private unless the owner explicitly approves public visibility.

The source of truth is trusted push [39131639694](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39131639694), source `ff9f4b284c807b6c5f4b88a826ab1513007f50b5`. Its `runtime-images-<SHA>` artifact is retained for 14 days. All 29 referenced evidence files, including the six-image archive, seven SBOMs, seven Trivy SARIF files, two CodeQL SARIF files, browser/database and owner alert evidence, were downloaded and SHA-256 checked in the ignored local `target/phase8-review-ff9f4b2/` staging directory on 2026-10-18. The six scan config digests are in `runtime-images.json`; the Keycloak config is `sha256:433db185614abcdd3813179338494ad9e53f63f45d5b6c4fe31ac6ac4297cbd3`. Do not substitute the Keycloak base-index digest for this ID.

## Trusted promotion host sequence

The 2026-10-18 publication used Docker Desktop on the authenticated release workstation, GitHub CLI with `write:packages`, and Docker Engine/Buildx. No credential was placed in the repository. The following Linux commands remain a recovery procedure if publication must be repeated from the same trusted artifacts. GitHub [documents package authentication](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry); keep credentials out of shell history and files.

```bash
set -euo pipefail
SHA=ff9f4b284c807b6c5f4b88a826ab1513007f50b5
RUN=39131639694
META="$PWD/target/phase8-review-ff9f4b2"
mkdir -p "$META"
gh run download "$RUN" --dir "$META"
python3 - "$META" "$SHA" <<'PY'
import hashlib, json, pathlib, sys, tarfile
root = pathlib.Path(sys.argv[1]).resolve()
sha = sys.argv[2]
evidence = json.loads((root / f'release-evidence-{sha}' / 'release-evidence.json').read_text())
assert evidence['sourceCommit'] == sha == evidence['headCommit']
assert evidence['ciRun'].endswith('/39131639694')
assert evidence['verification'] == 'PASS' and evidence['ownerAlertDelivery'] == 'VERIFIED'
assert evidence['candidateApproved'] is False
def refs(value):
    if isinstance(value, dict):
        if isinstance(value.get('path'), str) and isinstance(value.get('sha256'), str):
            yield value['path'], value['sha256']
        for child in value.values():
            yield from refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from refs(child)
items = dict(refs(evidence))
assert len(items) == 29
for name, expected in items.items():
    path = (root / name).resolve()
    assert path.is_relative_to(root) and path.is_file(), name
    with path.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected, name
manifest = json.loads((root / f'runtime-images-{sha}' / 'runtime-images.json').read_text())
assert manifest['sourceCommit'] == sha and manifest['ciRun'] == evidence['ciRun']
assert manifest['imageIds'] == {key: value['imageId'] for key, value in evidence['images'].items()}
wanted = {image_id.removeprefix('sha256:') for image_id in manifest['imageIds'].values()}
seen, referenced = set(), set()
with tarfile.open(root / f'runtime-images-{sha}' / 'fleetflow-runtime-images.tar.gz', 'r|gz') as archive:
    for member in archive:
        blob = member.name.rsplit('/', 1)[-1]
        if not member.name.startswith('blobs/sha256/') or (blob not in wanted and member.size > 3000):
            continue
        content = archive.extractfile(member).read()
        assert hashlib.sha256(content).hexdigest() == blob, member.name
        if blob in wanted:
            config = json.loads(content)
            assert config['os'] == 'linux' and config['architecture'] == 'amd64'
            seen.add(blob)
        else:
            try:
                obj = json.loads(content)
                if 'config' in obj and 'layers' in obj:
                    referenced.add(obj['config']['digest'].removeprefix('sha256:'))
            except (ValueError, KeyError):
                pass
assert seen == wanted and wanted <= referenced
print('PASS: 29 CI artifact hashes, six OCI config blobs and matching manifests')
PY
ARCHIVE="$META/runtime-images-$SHA/fleetflow-runtime-images.tar.gz"
docker load --input "$ARCHIVE"
read -rsp 'GHCR classic PAT with write:packages: ' GHCR_PAT; printf '\n'
printf '%s' "$GHCR_PAT" | docker login ghcr.io -u Omar-Mega-Byte --password-stdin
unset GHCR_PAT
for name in app edge keycloak-runtime postgis redis kafka; do
  docker tag "fleetflow-$name:ci" "ghcr.io/omar-mega-byte/fleetflow-$name:$SHA"
  docker push "ghcr.io/omar-mega-byte/fleetflow-$name:$SHA"
done
python3 - "$META" "$SHA" <<'PY'
import json, pathlib, subprocess, sys
root, sha = pathlib.Path(sys.argv[1]), sys.argv[2]
ids = json.loads((root / f'runtime-images-{sha}' / 'runtime-images.json').read_text())['imageIds']
refs = {}
for name in ('app', 'edge', 'keycloak-runtime', 'postgis', 'redis', 'kafka'):
    repo = f'ghcr.io/omar-mega-byte/fleetflow-{name}'
    tag = f'{repo}:{sha}'
    descriptor = json.loads(subprocess.check_output(['docker', 'buildx', 'imagetools', 'inspect', tag, '--format', '{{json .Manifest}}'], text=True))
    ref = f"{repo}@{descriptor['digest']}"
    manifest = json.loads(subprocess.check_output(['docker', 'buildx', 'imagetools', 'inspect', '--raw', ref], text=True))
    assert manifest['schemaVersion'] == 2 and 'manifests' not in manifest, name
    assert manifest['config']['digest'] == ids[name], name
    subprocess.run(['docker', 'pull', ref], check=True)
    refs[name] = ref
(root / 'published-refs.json').write_text(json.dumps(refs, indent=2) + '\n')
print(json.dumps(refs, indent=2))
PY
```

The first Python check verifies the archive SHA-256 because it is one of the evidence references. The archive's verified `runtime-images.json` binds those bytes to all six scanned config IDs. The final check obtains real `repository@sha256:...` references, rejects a registry index/multi-platform image, checks each registry manifest's config digest against CI and pulls all six back. Docker Desktop/containerd may report a manifest digest through local `.Id`, so the registry manifest comparison is decisive. [Docker Buildx documents the manifest descriptor and raw inspection commands](https://docs.docker.com/reference/cli/docker/buildx/imagetools/inspect/).

## Candidate assembly after publication

1. The **final technical** 28-row advisory CSV has been copied to private metadata as `advisory-review.csv` with zero `OPEN` rows. The release owner must still independently review its applicability conclusions. Retain the original exact SARIF/SBOM reports at their CI relative paths. Keep the metadata directory private and immutable.
2. Create `candidate.json` with the [contract's fields](CANDIDATE-METADATA.md): source commit and CI URL above, the six real references from `published-refs.json`, SHA-256 of the exact `release-evidence.json` and final `advisory-review.csv`, and a separate release-owner **candidate** approval record with its actual UTC timestamp. Do not copy the eight-CVE acceptance date into `approval.atUtc` as candidate approval. Do not alter CI's `candidateApproved: false`.
3. Populate the private [production environment template](../../../.env.production.example): `FLEETFLOW_SOURCE_COMMIT` is the scanned source commit, `FLEETFLOW_RELEASE_METADATA_DIR` is the absolute private metadata path, and all six `FLEETFLOW_*_IMAGE` values are the corresponding `published-refs.json` digest references. Follow the [production operator guide](../../../ops/production/README.md) for hostnames, five actual Keycloak subject UUIDs and six secret files. Do not commit this environment file or secrets.
4. On the trusted promotion host, run `python3 scripts/validate_release_candidate.py --env-file .env.production --check-registry-manifests --check-local-images`. Only a passing validator and the release owner's independent review make the immutable candidate approved. The Azure VM must repeat the digest checks before service start; Phase 9 then verifies public DNS/TLS, signed personas, recovery and budget separately.

If the image, configuration, scan set, source files or advisory conclusions change, stop and obtain a new trusted-push exact-image scan and owner review. Documentation-only commits do not change the archived six image config blobs, but they do not confer candidate approval or bypass the protected PR/main checks.
