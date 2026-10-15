import json
from pathlib import Path
import tempfile
import unittest

from check_trivy_sarif import sanitize_sarif


def sarif_with(findings):
    rules = []
    results = []
    for rule_id, name, severity, message in findings:
        rules.append({
            "id": rule_id,
            "name": name,
            "shortDescription": {"text": name},
            "help": {"text": f"Severity: {severity}\nMatch: {message}"},
            "properties": {"tags": [severity]},
            "defaultConfiguration": {"level": "error" if severity in {"HIGH", "CRITICAL"} else "note"},
        })
        results.append({
            "ruleId": rule_id,
            "level": "error" if severity in {"HIGH", "CRITICAL"} else "note",
            "message": {"text": message},
            "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/example.txt"},
                           "region": {"startLine": 7}}}],
        })
    return {"version": "2.1.0", "runs": [{"tool": {"driver": {"rules": rules}}, "results": results}]}


class TrivySarifGateTests(unittest.TestCase):
    def test_keeps_medium_low_inventory_and_passes_without_secrets_or_high_findings(self):
        with tempfile.TemporaryDirectory(prefix="fleetflow-trivy-") as directory:
            raw = Path(directory) / "raw.sarif"
            safe = Path(directory) / "safe.sarif"
            raw.write_text(json.dumps(sarif_with([
                ("CVE-2026-1234", "LanguageSpecificPackageVulnerability", "MEDIUM", "Package: libpng 1.6.58"),
                ("DS-0026", "Misconfiguration", "LOW", "Add a HEALTHCHECK"),
            ])), encoding="utf-8")

            stats = sanitize_sarif(raw, safe)

            report = json.loads(safe.read_text(encoding="utf-8"))
            self.assertEqual((stats["medium"], stats["low"], stats["blockers"]), (1, 1, 0))
            self.assertEqual(len(report["runs"][0]["results"]), 2)

    def test_blocks_high_critical_and_redacts_secret_values_before_output(self):
        marker = "synthetic-secret-must-not-be-archived"
        with tempfile.TemporaryDirectory(prefix="fleetflow-trivy-") as directory:
            raw = Path(directory) / "raw.sarif"
            safe = Path(directory) / "safe.sarif"
            raw.write_text(json.dumps(sarif_with([
                ("CVE-2026-5678", "OsPackageVulnerability", "HIGH", "Package: openssl 1.2.3"),
                ("aws-access-key-id", "Secret", "HIGH", marker),
            ])), encoding="utf-8")

            stats = sanitize_sarif(raw, safe)

            content = safe.read_text(encoding="utf-8")
            report = json.loads(content)
            run = report["runs"][0]
            self.assertEqual((stats["high"], stats["secrets"], stats["blockers"]), (1, 1, 2))
            self.assertNotIn(marker, content)
            self.assertEqual(len(run["results"]), 1)
            self.assertEqual(run["properties"]["fleetflowSecretFindingSummary"][0]["ruleId"], "aws-access-key-id")
            self.assertFalse(any(rule.get("id") == "aws-access-key-id" for rule in run["tool"]["driver"]["rules"]))


if __name__ == "__main__":
    unittest.main()
