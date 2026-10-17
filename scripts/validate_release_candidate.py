"""Validate an approved, digest-pinned FleetFlow release before starting services."""

import argparse
import csv
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath


IMAGE_ENV = {
    "app": "FLEETFLOW_APP_IMAGE",
    "edge": "FLEETFLOW_EDGE_IMAGE",
    "keycloak-runtime": "FLEETFLOW_KEYCLOAK_RUNTIME",
    "postgis": "FLEETFLOW_POSTGIS_IMAGE",
    "redis": "FLEETFLOW_REDIS_IMAGE",
    "kafka": "FLEETFLOW_KAFKA_IMAGE",
}
SBOMS = {"app", "edge", "keycloak", "postgis", "redis", "kafka", "source-sbom"}
TRIVY = {"app", "edge", "keycloak", "postgis", "redis", "kafka", "source"}
CODEQL = {"java-kotlin", "javascript-typescript"}
DIGEST = re.compile(r"^[A-Za-z0-9._/-]+(?::[A-Za-z0-9._-]+)?@sha256:([0-9a-f]{64})$")
SHA = re.compile(r"^[0-9a-f]{40}$")
HASH = re.compile(r"^[0-9a-f]{64}$")


def require(condition, message):
    if not condition:
        raise ValueError("Candidate metadata: " + message)


def env_values(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        require(key not in values, f"duplicate environment key {key}")
        values[key] = value.strip()
    return values


def checked_file(root, entry):
    require(isinstance(entry, dict), "artifact reference must contain path and SHA-256")
    name, expected = entry.get("path"), entry.get("sha256")
    require(isinstance(name, str) and isinstance(expected, str) and HASH.fullmatch(expected),
            "artifact reference has an invalid path or SHA-256")
    relative = PurePosixPath(name)
    require(not relative.is_absolute() and ".." not in relative.parts and "\\" not in name and name != "",
            "artifact path escapes the release directory")
    path = (root / name).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file(), f"artifact is missing: {name}")
    require(hashlib.sha256(path.read_bytes()).hexdigest() == expected, f"artifact SHA-256 differs: {name}")
    return path


def parse_utc(value):
    require(isinstance(value, str), "approval time is missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("Candidate metadata: approval time is invalid") from error
    require(parsed.tzinfo is not None and parsed.utcoffset() == timezone.utc.utcoffset(parsed),
            "approval time must be in UTC")


def validate(env_path, metadata_dir, check_local_images=False):
    values = env_values(env_path)
    if metadata_dir is None:
        location = values.get("FLEETFLOW_RELEASE_METADATA_DIR", "")
        require(location and Path(location).is_absolute(), "release metadata directory must be an absolute path")
        metadata_dir = Path(location)
    root = metadata_dir.resolve()
    require(root.is_dir(), "release metadata directory is missing")
    candidate_path = root / "candidate.json"
    require(candidate_path.is_file(), "candidate.json is missing")
    candidate = json.loads(candidate_path.read_text(encoding="utf-8"))
    require(isinstance(candidate, dict), "candidate.json must be an object")
    source = values.get("FLEETFLOW_SOURCE_COMMIT", "")
    require(SHA.fullmatch(source) and candidate.get("sourceCommit") == source, "source commit differs")
    ci_run = candidate.get("ciRun")
    require(isinstance(ci_run, str) and re.fullmatch(r"https://github\.com/Omar-Mega-Byte/fleetflow/actions/runs/[0-9]+", ci_run),
            "CI run URL is missing or invalid")
    approval = candidate.get("approval", {})
    require(isinstance(approval, dict) and approval.get("decision") == "APPROVED" and
            isinstance(approval.get("owner"), str) and approval["owner"].strip() and
            isinstance(approval.get("record"), str) and approval["record"].strip(),
            "explicit release-owner approval is missing")
    parse_utc(approval.get("atUtc"))

    image_refs = candidate.get("images")
    require(isinstance(image_refs, dict) and set(image_refs) == set(IMAGE_ENV), "six-image inventory differs")
    for name, env_key in IMAGE_ENV.items():
        ref = image_refs[name]
        require(isinstance(ref, str) and DIGEST.fullmatch(ref) and set(DIGEST.fullmatch(ref).group(1)) != {"0"},
                f"{name} is not an immutable published digest")
        require(ref == values.get(env_key), f"{name} differs from deployment environment")

    evidence = json.loads(checked_file(root, candidate.get("releaseEvidence")).read_text(encoding="utf-8"))
    require(evidence.get("kind") == "VERIFIED_BUILD_EVIDENCE" and evidence.get("verification") == "PASS" and
            evidence.get("sourceCommit") == source and evidence.get("headCommit") == source and
            evidence.get("ciRun") == ci_run, "exact CI evidence does not match the release")
    require(evidence.get("ownerAlertRequired") is True and evidence.get("ownerAlertDelivery") == "VERIFIED",
            "trusted-push owner alert evidence is missing")
    archive = evidence.get("runtimeImageArchive")
    require(isinstance(archive, dict) and isinstance(archive.get("archive"), dict) and
            HASH.fullmatch(archive["archive"].get("sha256", "")) and
            isinstance(archive["archive"].get("bytes"), int) and archive["archive"]["bytes"] > 0,
            "exact runtime image archive identity is missing")
    manifest = json.loads(checked_file(root, archive).read_text(encoding="utf-8"))
    require(manifest.get("sourceCommit") == source and manifest.get("ciRun") == ci_run and
            manifest.get("archiveSha256") == archive["archive"]["sha256"] and
            manifest.get("archiveBytes") == archive["archive"]["bytes"],
            "runtime image archive manifest differs from CI evidence")
    for field, expected in (("sboms", SBOMS), ("trivy", TRIVY), ("codeql", CODEQL)):
        reports = evidence.get(field)
        require(isinstance(reports, dict) and set(reports) == expected, f"{field} inventory differs")
        for report in reports.values():
            checked_file(root, report)
    scanned_images = evidence.get("images", {})
    require(isinstance(scanned_images, dict) and set(scanned_images) == set(IMAGE_ENV),
            "scanned image inventory differs")
    require(manifest.get("imageIds") == {name: image.get("imageId") for name, image in scanned_images.items()},
            "runtime image archive identities differ from scanned images")
    for name, report in evidence["trivy"].items():
        require(all(report.get(field) == 0 for field in ("critical", "high", "unknown", "secrets", "unclassified", "blockers")),
                f"{name} has unresolved scan blockers")
    require(all(report.get("findings") == 0 for report in evidence["codeql"].values()),
            "CodeQL findings require review")

    open_ids = set()
    for report in evidence["trivy"].values():
        if report.get("findings", 0) > 0:
            sarif = json.loads(checked_file(root, report).read_text(encoding="utf-8"))
            results = [result for run in sarif.get("runs", []) for result in run.get("results", [])]
            require(len(results) == report["findings"] and all(result.get("ruleId") for result in results),
                    "scanner finding count or IDs differ from CI evidence")
            open_ids.update(result["ruleId"] for result in results)
    if open_ids:
        review = candidate.get("advisoryReview")
        require(isinstance(review, dict), "advisory review is missing")
        with checked_file(root, review).open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
        decisions = {}
        for row in rows:
            finding_id = row.get("finding_id")
            require(finding_id and finding_id not in decisions, "advisory review has a missing or duplicate finding ID")
            decisions[finding_id] = row
        require(open_ids <= decisions.keys(), "open scanner findings lack a completed disposition")
        for finding_id in open_ids:
            row = decisions[finding_id]
            decision = row.get("disposition", "")
            require(decision in {"NOT_AFFECTED", "ACCEPTED_TIME_BOUND"} and
                    all(row.get(field, "").strip() for field in ("owner", "rationale", "evidence")),
                    f"{finding_id} lacks a completed disposition with owner and evidence")
            if decision == "ACCEPTED_TIME_BOUND":
                require(row.get("mitigation", "").strip(), f"{finding_id} lacks a mitigation")
                expires = row.get("expires_at_utc")
                parse_utc(expires)
                require(datetime.fromisoformat(expires.replace("Z", "+00:00")) > datetime.now(timezone.utc),
                        f"{finding_id} disposition has expired")

    if check_local_images:
        for name, ref in image_refs.items():
            inspected = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", ref], text=True).strip()
            require(inspected == scanned_images[name].get("imageId"),
                    f"published {name} image differs from the image scanned by CI")
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    parser.add_argument("--metadata-dir", type=Path)
    parser.add_argument("--check-local-images", action="store_true")
    args = parser.parse_args()
    try:
        source = validate(args.env_file, args.metadata_dir, args.check_local_images)
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release preflight failed: {error}\n")
    print(f"Approved candidate preflight passed for {source}")


if __name__ == "__main__":
    main()
