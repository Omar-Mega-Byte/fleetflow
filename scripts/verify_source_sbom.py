"""Check source SBOM coverage against Maven's selected graph and the npm lockfile."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(sbom: Path, npm_lock: Path, maven_tree: Path, source: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise ValueError("A full source commit is required")
    document = json.loads(sbom.read_text(encoding="utf-8"))
    if document.get("bomFormat") != "CycloneDX" or not document.get("components"):
        raise ValueError("A populated CycloneDX SBOM is required")
    actual = {"npm": set(), "maven": set()}
    for component in document["components"]:
        if component.get("type") != "file" and component.get("version") in {None, "", "UNKNOWN"}:
            raise ValueError("Unresolved component version: " + component.get("name", "unnamed"))
        purl = component.get("purl", "").split("?", 1)[0].split("#", 1)[0]
        for ecosystem in actual:
            prefix = "pkg:" + ecosystem + "/"
            if purl.startswith(prefix):
                name = unquote(purl[len(prefix):].rsplit("@", 1)[0])
                actual[ecosystem].add((name, component.get("version")))
    lock = json.loads(npm_lock.read_text(encoding="utf-8"))
    expected_npm = {(value.get("name") or key.rsplit("node_modules/", 1)[-1], value["version"])
                    for key, value in lock["packages"].items() if key and "version" in value}
    tree = json.loads(maven_tree.read_text(encoding="utf-8"))
    expected_maven = set()

    def visit(node):
        expected_maven.add((node["groupId"] + "/" + node["artifactId"], node["version"]))
        for child in node.get("children", []):
            visit(child)

    # The project is described by BOM metadata; compare its selected dependencies.
    for child in tree.get("children", []):
        visit(child)
    for name, expected in (("npm", expected_npm), ("maven", expected_maven)):
        if not expected:
            raise ValueError("The resolved " + name + " inventory is empty")
        missing = expected - actual[name]
        if missing:
            raise ValueError(f"Missing {name} inventory: {len(missing)} selected components; {sorted(missing)[:5]}")
    return {"verification": "PASS", "sourceCommit": source, "sbomSha256": digest(sbom),
            "npmLockSha256": digest(npm_lock), "mavenTreeSha256": digest(maven_tree),
            "selectedMavenComponents": len(expected_maven), "lockedNpmComponents": len(expected_npm),
            "catalogedMavenComponents": len(actual["maven"]), "catalogedNpmComponents": len(actual["npm"]),
            "unknownVersions": 0,
            "scope": "Maven selected dependencies including test/provided scopes; all locked npm dependencies including dev/optional"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--npm-lock", type=Path, required=True)
    parser.add_argument("--maven-tree", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.sbom, args.npm_lock, args.maven_tree, args.source_commit)
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print("Source SBOM coverage failed: " + str(failure))
        return 1
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {result['selectedMavenComponents']} Maven and {result['lockedNpmComponents']} npm identities; no unknown versions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
