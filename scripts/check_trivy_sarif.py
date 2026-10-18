"""Apply the release severity policy and redact secret values from Trivy SARIF."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile

SEVERITIES = ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")
SEVERITY_PATTERN = re.compile(r"^Severity:\s*(CRITICAL|HIGH|MEDIUM|LOW|UNKNOWN)\s*$", re.MULTILINE | re.IGNORECASE)


def _tags(value) -> set[str]:
    if not isinstance(value, list):
        return set()
    return {str(item).strip().casefold() for item in value}


def _is_secret_rule(rule: dict) -> bool:
    properties = rule.get("properties") or {}
    name = str(rule.get("name") or "").strip().casefold()
    short = str((rule.get("shortDescription") or {}).get("text") or "").strip().casefold()
    return name == "secret" or short == "secret" or "secret" in _tags(properties.get("tags"))


def _severity(rule: dict, result: dict) -> str | None:
    properties = rule.get("properties") or {}
    tags = _tags(properties.get("tags"))
    for severity in SEVERITIES:
        if severity.casefold() in tags:
            return severity
    help_text = str((rule.get("help") or {}).get("text") or "")
    match = SEVERITY_PATTERN.search(help_text)
    if match:
        return match.group(1).upper()
    return None


def sanitize_sarif(source: Path, destination: Path) -> dict[str, int]:
    report = json.loads(source.read_text(encoding="utf-8"))
    if report.get("version") != "2.1.0" or not isinstance(report.get("runs"), list) or not report["runs"]:
        raise ValueError("expected a SARIF 2.1.0 report with at least one scanner run")

    stats = {"findings": 0, "critical": 0, "high": 0, "medium": 0, "low": 0,
             "unknown": 0, "secrets": 0, "unclassified": 0, "blockers": 0}
    for run in report["runs"]:
        driver = ((run.get("tool") or {}).get("driver") or {})
        rules = driver.get("rules") or []
        if not isinstance(rules, list):
            raise ValueError("SARIF driver rules must be a list")
        rules_by_id = {str(rule.get("id")): rule for rule in rules if isinstance(rule, dict) and rule.get("id")}
        secret_ids: set[str] = set()
        safe_results: list[dict] = []
        prior_summary = (run.get("properties") or {}).get("fleetflowSecretFindingSummary", [])
        if not isinstance(prior_summary, list) or any(not isinstance(item, dict) for item in prior_summary):
            raise ValueError("invalid retained secret finding summary")
        safe_secret_summary: list[dict[str, str | int | None]] = [
            {key: item.get(key) for key in ("ruleId", "artifactUri", "startLine")}
            for item in prior_summary
        ]
        # Reprocessing an already sanitized report must retain its blocking detections.
        stats["findings"] += len(prior_summary)
        stats["secrets"] += len(prior_summary)
        stats["blockers"] += len(prior_summary)
        results = run.get("results") or []
        if not isinstance(results, list):
            raise ValueError("SARIF run results must be a list")
        for result in results:
            if not isinstance(result, dict):
                raise ValueError("SARIF result must be an object")
            rule_id = str(result.get("ruleId") or "")
            rule = rules_by_id.get(rule_id)
            if rule and _is_secret_rule(rule):
                stats["findings"] += 1
                stats["secrets"] += 1
                stats["blockers"] += 1
                secret_ids.add(rule_id)
                uri = None
                line = None
                locations = result.get("locations") or []
                if locations:
                    physical = (((locations[0].get("physicalLocation") or {}).get("artifactLocation") or {}))
                    uri = physical.get("uri")
                    line = (((locations[0].get("physicalLocation") or {}).get("region") or {}).get("startLine"))
                safe_secret_summary.append({"ruleId": rule_id, "artifactUri": uri, "startLine": line})
                continue

            if not rule_id or rule is None:
                stats["unclassified"] += 1
                stats["blockers"] += 1
                safe_results.append({
                    "ruleId": rule_id or "UNCLASSIFIED",
                    "level": "error",
                    "message": {"text": "Unclassified Trivy result redacted; release review required."},
                })
                continue

            stats["findings"] += 1
            severity = _severity(rule, result)
            if severity is None:
                stats["unclassified"] += 1
                stats["blockers"] += 1
            else:
                stats[severity.casefold()] += 1
                if severity in {"HIGH", "CRITICAL", "UNKNOWN"}:
                    stats["blockers"] += 1
            safe_results.append(result)

        run["results"] = safe_results
        if secret_ids:
            driver["rules"] = [rule for rule in rules if str(rule.get("id") or "") not in secret_ids]
        properties = run.get("properties")
        if not isinstance(properties, dict):
            properties = {}
            run["properties"] = properties
        properties["fleetflowSecretFindingSummary"] = safe_secret_summary
        properties["fleetflowSecurityPolicy"] = "block-high-critical-unknown-unclassified-and-any-secret"

    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=destination.parent,
                                     prefix=".trivy-safe-", suffix=".sarif", delete=False) as temporary:
        temporary_path = Path(temporary.name)
        json.dump(report, temporary, ensure_ascii=False, indent=2)
        temporary.write("\n")
    os.replace(temporary_path, destination)
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", action="append", required=True, metavar="RAW=SAFE",
                        help="Trivy SARIF input and sanitized output path; repeat for multiple reports")
    args = parser.parse_args()
    totals = {"findings": 0, "critical": 0, "high": 0, "medium": 0, "low": 0,
              "unknown": 0, "secrets": 0, "unclassified": 0, "blockers": 0}
    try:
        for item in args.report:
            if "=" not in item:
                raise ValueError("each --report must be RAW=SAFE")
            raw, safe = item.split("=", 1)
            stats = sanitize_sarif(Path(raw), Path(safe))
            for key, count in stats.items():
                totals[key] += count
        print("Trivy inventory: " + ", ".join(
            f"{key}={totals[key]}" for key in ("findings", "critical", "high", "medium", "low", "unknown", "secrets", "unclassified")
        ) + f"; blockers={totals['blockers']}")
        if totals["blockers"]:
            print("Trivy release policy failed: high/critical/unknown findings, secret detections, or unclassified results require review.", file=sys.stderr)
            return 1
        return 0
    except (OSError, json.JSONDecodeError, ValueError, TypeError) as error:
        print(f"Trivy report processing failed ({type(error).__name__}); refusing to pass the security gate.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
