# Keycloak time-bound risk decision — 2026-10-18

The release owner, **Omar Elrfaay**, approved a time-bound acceptance on 2026-10-18 for these eight scanned Keycloak findings only:

`CVE-2020-12413`, `CVE-2026-27171`, `CVE-2026-49919`, `CVE-2026-5958`, `CVE-2026-8674`, `CVE-2026-86805`, `CVE-2026-95512`, `CVE-2026-95818`.

**Decision:** `ACCEPTED_TIME_BOUND`. **Expires:** 2026-11-18 (use `2026-11-18T00:00:00Z` as the conservative validator cutoff). This records the owner's explicit instruction in the release-closeout request; it is **not** approval of an immutable candidate or of any other CVE. The owner must inspect the final advisory review and candidate separately.

**Scope:** trusted push [run 39131639694](https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/39131639694), source commit `ff9f4b284c807b6c5f4b88a826ab1513007f50b5`, scanned `fleetflow-keycloak-runtime:ci` config digest `sha256:433db185614abcdd3813179338494ad9e53f63f45d5b6c4fe31ac6ac4297cbd3`, built from `quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`. The base digest alone is insufficient to identify the scanned runtime image.

The acceptance is conditional on the actual production topology: Keycloak is reachable only through the edge's selected realm/resource routes, public TLS terminates at that edge, the Keycloak container runs as UID 1000, its database is PostgreSQL, and operators do not provide untrusted local shell, custom fonts/themes/providers, attacker-controlled resolver configuration, or privileged `sed -i --follow-symlinks` hooks. The [production Compose](../../../ops/production/compose.production.yaml), [Keycloak Dockerfile](../../../ops/production/keycloak.Dockerfile), [entrypoint](../../../ops/production/keycloak-entrypoint.sh), and [edge boundary](../../../ops/production/README.md) are the controls reviewed. The Compose service does **not** currently set `read_only`, `cap_drop`, or `no-new-privileges`; do not claim those extra controls. The target host's resolver, setuid/setgid inventory, mount permissions and public firewall still need target verification.

Re-review before expiry, after a runtime image or topology change, or when a relevant vendor fix is available. A different config digest, base digest, architecture, public route, provider set, or privilege boundary is outside this decision. Preserve the exact SARIF, SBOM and runtime archive with the private candidate evidence. The owner approval covers residual risk; it does not turn a present vulnerable package into `NOT_AFFECTED`.
