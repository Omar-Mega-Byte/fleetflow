"""Verify and hash exact-run artifacts without approving or publishing a candidate."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile

from check_trivy_sarif import sanitize_sarif

IMAGES = ("app", "keycloak", "edge", "postgis", "redis", "kafka")


def one(root: Path, pattern: str) -> Path:
    matches = [path for path in root.rglob(pattern) if path.is_file()]
    if len(matches) != 1:
        raise ValueError("Expected exactly one artifact: " + pattern)
    return matches[0]


def read(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Artifact must contain a JSON object: " + path.name)
    return value


def identity(root: Path, path: Path) -> dict:
    return {"path": path.relative_to(root).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def record(root: Path, source: str, head: str, ci_run: str, owner_required: bool) -> dict:
    if any(not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in (source, head)):
        raise ValueError("Full source and head commit SHAs are required")
    build_path = one(root, "image-builds.json")
    build = read(build_path)
    if build.get("sourceCommit") != source or build.get("ciRun") != ci_run:
        raise ValueError("Image metadata does not match this checkout and CI run")
    if build.get("kind") != "BUILD_METADATA_ONLY" or build.get("candidateApproved") is not False:
        raise ValueError("Build metadata must not imply candidate approval")
    if build.get("application") != "FleetFlow" or not build.get("applicationVersion"):
        raise ValueError("Application build identity is missing")
    expected_images = {"keycloak-runtime" if name == "keycloak" else name for name in IMAGES}
    if set(build.get("images", {})) != expected_images:
        raise ValueError("All six runtime image identities are required")
    for image in build["images"].values():
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image.get("imageId", "")):
            raise ValueError("Invalid immutable Docker image ID")
    sboms = {}
    for name in (*IMAGES, "source-sbom"):
        path = one(root, f"fleetflow-{name}-{source}.cdx.json")
        document = read(path)
        if document.get("bomFormat") != "CycloneDX" or document.get("specVersion") not in {"1.6", "1.7"}:
            raise ValueError("Unsupported or invalid CycloneDX artifact: " + name)
        if not isinstance(document.get("components"), list) or not document["components"]:
            raise ValueError("SBOM has no component inventory: " + name)
        component = (document.get("metadata") or {}).get("component") or {}
        expected_name = "FleetFlow" if name == "source-sbom" else "fleetflow-" + ("keycloak-runtime" if name == "keycloak" else name)
        expected_version = build["applicationVersion"] if name == "source-sbom" else "ci"
        if component.get("name") != expected_name or component.get("version") != expected_version:
            raise ValueError("SBOM source identity does not match its build: " + name)
        sboms[name] = {**identity(root, path), "specVersion": document["specVersion"], "components": len(document["components"])}
    scans = {}
    coverage_path = one(root, "source-sbom-coverage.json")
    coverage = read(coverage_path)
    tree_path = one(root, "source-maven-tree.json")
    checkout_lock = Path(__file__).resolve().parent.parent / "web/package-lock.json"
    if (coverage.get("verification") != "PASS" or coverage.get("sourceCommit") != source
            or coverage.get("sbomSha256") != sboms["source-sbom"]["sha256"]
            or coverage.get("mavenTreeSha256") != identity(root, tree_path)["sha256"]
            or coverage.get("npmLockSha256") != hashlib.sha256(checkout_lock.read_bytes()).hexdigest()
            or coverage.get("unknownVersions") != 0
            or coverage.get("selectedMavenComponents", 0) < 1 or coverage.get("lockedNpmComponents", 0) < 1):
        raise ValueError("Source SBOM coverage does not match this source and inventory")
    with tempfile.TemporaryDirectory(prefix="fleetflow-release-sarif-") as scratch:
        for name in (*IMAGES, "source"):
            path = one(root, "trivy-results.sarif" if name == "source" else f"trivy-{name}.sarif")
            stats = sanitize_sarif(path, Path(scratch) / (name + ".sarif"))
            scans[name] = {**identity(root, path), **stats}
            if stats["blockers"]:
                raise ValueError("Blocking Trivy findings remain in " + name)
    codeql = {}
    for name, filename in (("java-kotlin", "java.sarif"), ("javascript-typescript", "javascript.sarif")):
        path = one(root, filename)
        document = read(path)
        if document.get("version") != "2.1.0" or not document.get("runs"):
            raise ValueError("CodeQL report is invalid: " + name)
        count = sum(len(run.get("results", [])) for run in document["runs"])
        codeql[name] = {**identity(root, path), "findings": count}
        if count:
            raise ValueError("CodeQL findings require disposition before candidate qualification: " + name)
    browser_path = one(root, "result.json")
    browser = read(browser_path)
    if browser.get("result") not in {"PASS", "PARTIAL"} or browser.get("publicDeployment") is not False or browser.get("hostBinding") != "127.0.0.1":
        raise ValueError("Browser evidence is not from the private rehearsal")
    for field in ("issuerSpoofingBlocked", "brokerOutageBusinessCommit", "databaseRecoveredWithoutAppRestart"):
        if browser.get(field) is not True:
            raise ValueError("Missing real dependency/browser evidence: " + field)
    for field, expected in {"redisOutageGauge": 0, "redisRecoveryGauge": 1, "databaseOutageGauge": 0,
                            "databaseRecoveryGauge": 1, "databaseReadinessOutageStatus": 503,
                            "restartRetainedGeneration": 2, "edgeBodyLimit": 413,
                            "outboxRemaining": 0, "processedMarkers": 4, "expectedProcessedMarkers": 4}.items():
        if browser.get(field) != expected:
            raise ValueError("Unexpected dependency recovery result: " + field)
    for name in IMAGES:
        metadata_name = "keycloak-runtime" if name == "keycloak" else name
        browser_name = "postgres" if name == "postgis" else name
        image = build["images"][metadata_name]
        if browser.get("images", {}).get(browser_name) not in [image["imageId"], *image.get("registryDigests", [])]:
            raise ValueError("The browser did not exercise the recorded image: " + name)
    delivery = browser.get("ownerAlertDelivery") or {}
    owner_verified = all(delivery.get(field) is True for field in
                         ("verified", "firingAlertObserved", "firingDeliveryObserved", "resolvedAlertObserved")) and all(
                             delivery.get(field, 0) >= 1 for field in ("firingNotificationDelta", "resolvedNotificationDelta",
                                                                     "firingGroupSuccessDelta", "resolvedGroupSuccessDelta"))
    if owner_required and (browser.get("result") != "PASS" or not owner_verified or browser.get("ownerAlertDeliveryMissingInput")
                           or browser.get("databaseAlertFiringObserved") is not True or browser.get("databaseAlertResolvedObserved") is not True):
        raise ValueError("Required owner firing and recovery delivery was not verified")
    owner_evidence = None
    if owner_verified:
        owner_path = one(root, "owner-notification-successes.json")
        owner_events = json.loads(owner_path.read_text(encoding="utf-8"))
        if not isinstance(owner_events, list) or len(owner_events) < 2 or any(
                not isinstance(event, dict) or event.get("msg") != "Notify success"
                or event.get("receiver") != "fleetflow-discord" or event.get("integration") != "discord[0]"
                or 'alertname="FleetFlowOutboxBacklog"' not in str(event.get("aggrGroup", ""))
                for event in owner_events):
            raise ValueError("Completed notifications for the actual owner alert group are missing")
        owner_evidence = {**identity(root, owner_path), "completedGroupNotifications": len(owner_events)}
    journey_path = one(root, "vertical-result.json")
    journey = read(journey_path)
    if journey.get("finalCustomerStatus") != "DELIVERED" or journey.get("logoutConfirmed") is not True:
        raise ValueError("The signed delivery journey is incomplete")
    if journey.get("interruptedOrderCreateConfirmed") is not True or journey.get("syntheticPositionsConfirmed", 0) < 2:
        raise ValueError("Order retry or controlled synthetic movement was not verified")
    zone_path = one(root, "zone-result.json")
    zone = read(zone_path)
    for field in ("createSameBodyAndKey", "keyboardEditing", "mapDrawingInsideExistingBoundary", "incompleteBoundaryRejected",
                  "concurrentUpdateDenied", "enforcedInsideCovered", "enforcedOutsideRejected", "unrestrictedOutsideCovered", "foreignBranchDenied"):
        if zone.get(field) is not True:
            raise ValueError("Missing signed zone acceptance: " + field)
    recovery_path = one(root, "recovery-result.json")
    recovery = read(recovery_path)
    if recovery.get("finalStatus") != "CANCELLED" or any(recovery.get(field) is not True for field in
            ("actualNoCandidates", "actualRetryRanked", "actualDriverRejection", "actualDispatcherCancellation")):
        raise ValueError("Signed dispatch recovery is incomplete")
    database_path = one(root, "database-postcondition.json")
    database = read(database_path)
    extra_path = one(root, "extended-database-postcondition.json")
    extra = read(extra_path)
    if database.get("order") != "DELIVERED|8" or extra.get("postcondition") != "CANCELLED|2:true|UNRESTRICTED|2":
        raise ValueError("Database postconditions do not confirm the browser results")
    if database.get("orderId") != journey.get("orderId") or extra.get("zoneId") != zone.get("zoneId") or extra.get("recoveryOrderId") != recovery.get("orderId"):
        raise ValueError("Evidence resource identities do not match their database postconditions")
    return {"kind": "VERIFIED_BUILD_EVIDENCE", "candidateApproved": False, "verification": "PASS",
            "sourceCommit": source, "headCommit": head, "ciRun": ci_run, "imageMetadata": identity(root, build_path),
            "images": build["images"], "sboms": sboms, "trivy": scans, "codeql": codeql,
            "sourceCoverage": {**identity(root, coverage_path), "mavenTree": identity(root, tree_path),
                               "selectedMavenComponents": coverage["selectedMavenComponents"],
                               "lockedNpmComponents": coverage["lockedNpmComponents"], "unknownVersions": 0},
            "ownerAlertDelivery": "VERIFIED" if owner_verified else "NOT_VERIFIED_MISSING_INPUT",
            "ownerAlertRequired": owner_required,
            "ownerNotificationEvidence": owner_evidence,
            "browserEvidence": [identity(root, path) for path in (browser_path, journey_path, zone_path, recovery_path, database_path, extra_path)],
            "remainingReleaseGates": (["vendor-supported disposition of open image findings"] if any(item["findings"] for item in scans.values()) else [])
                + ["published registry digests and candidate provenance", "explicit candidate approval", "external target acceptance"],
            "notes": "Successful CI build evidence is not a published or approved release candidate. All medium/low scan findings remain visible."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--head-commit", required=True)
    parser.add_argument("--ci-run", required=True)
    parser.add_argument("--owner-alert-required", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = record(args.root.resolve(), args.source_commit, args.head_commit, args.ci_run, args.owner_alert_required)
    except (OSError, ValueError, TypeError, KeyError) as failure:
        print("Release evidence verification failed: " + str(failure))
        return 1
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Verified six build identities, seven SBOMs, Trivy/CodeQL, signed browser and database evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
