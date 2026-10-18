"""Reconcile one Keycloak CVE decision per exact trusted-run SARIF rule ID."""

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def value(message, label):
    match = re.search(rf"(?m)^{re.escape(label)}: ([^\n]+)$", message)
    require(match is not None, f"SARIF result lacks {label}")
    return match.group(1)


def check(sarif_path: Path, review_path: Path, evidence_path: Path, require_complete=False):
    sarif_bytes = sarif_path.read_bytes()
    evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
    report = evidence["trivy"]["keycloak"]
    require(hashlib.sha256(sarif_bytes).hexdigest() == report["sha256"], "SARIF hash differs from trusted evidence")
    sarif = json.loads(sarif_bytes)
    results = [item for run in sarif["runs"] for item in run["results"]]
    require(len(results) == report["findings"], "SARIF result count differs from trusted evidence")
    grouped = defaultdict(list)
    for result in results:
        finding = result.get("ruleId")
        require(isinstance(finding, str) and finding.startswith("CVE-"), "SARIF result lacks a CVE ID")
        message = result["message"]["text"]
        grouped[finding].append((value(message, "Package"), value(message, "Installed Version"),
                                 value(message, "Severity")))
    with review_path.open(encoding="utf-8-sig", newline="") as stream:
        rows = list(csv.DictReader(stream))
    require(len(rows) == len(grouped), "review row count differs from distinct SARIF CVE count")
    by_id = {}
    image_id = evidence["images"]["keycloak-runtime"]["imageId"]
    decisions = Counter()
    for row in rows:
        finding = row.get("finding_id", "")
        require(finding in grouped and finding not in by_id, f"unknown or duplicate review ID: {finding}")
        by_id[finding] = row
        require(row.get("scanned_image_config_digest") == image_id,
                f"{finding} review references a different scanned image")
        require(int(row.get("scanner_count", "-1")) == len(grouped[finding]),
                f"{finding} scanner count differs")
        packages = row.get("scanner_packages", "").split(";")
        versions = row.get("installed_versions", "").split(";")
        require(len(packages) == len(versions) == len(grouped[finding]) and
                Counter(zip(packages, versions)) == Counter((p, v) for p, v, _ in grouped[finding]),
                f"{finding} package/version inventory differs")
        require({severity for _, _, severity in grouped[finding]} == {row.get("scanner_severity")},
                f"{finding} severity differs")
        disposition = row.get("disposition", "")
        require(disposition in {"OPEN", "NOT_AFFECTED", "ACCEPTED_TIME_BOUND"},
                f"{finding} has an unsupported disposition")
        require(row.get("official_record", "").startswith("https://") and
                row.get("rationale", "").strip() and row.get("evidence", "").strip(),
                f"{finding} lacks vendor, rationale or technical evidence")
        if disposition == "ACCEPTED_TIME_BOUND":
            require(row.get("owner", "").strip() and row.get("decision_date", "").strip() and
                    row.get("mitigation", "").strip(), f"{finding} acceptance lacks owner/date/mitigation")
            expiry = datetime.fromisoformat(row.get("expires_at_utc", "").replace("Z", "+00:00"))
            require(expiry.tzinfo is not None and expiry > datetime.now(timezone.utc),
                    f"{finding} acceptance lacks a future UTC expiry")
        decisions[disposition] += 1
    require(set(by_id) == set(grouped), "review IDs differ from SARIF")
    if require_complete:
        require(decisions["OPEN"] == 0, f"{decisions['OPEN']} review decisions remain OPEN")
    return len(results), len(grouped), decisions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sarif", required=True, type=Path)
    parser.add_argument("--review", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    try:
        matches, ids, decisions = check(args.sarif, args.review, args.evidence, args.require_complete)
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        parser.exit(1, f"Keycloak review failed: {error}\n")
    print(f"Keycloak review reconciled: {matches} SARIF results, {ids} CVEs, "
          f"{decisions['NOT_AFFECTED']} NOT_AFFECTED, {decisions['ACCEPTED_TIME_BOUND']} "
          f"ACCEPTED_TIME_BOUND, {decisions['OPEN']} OPEN")


if __name__ == "__main__":
    main()
