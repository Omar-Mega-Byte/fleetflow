"""Reject attempted or unrelated notifications as owner-delivery proof."""
import json
import os
from pathlib import Path
import runpy
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

FUNCTIONS = runpy.run_path(str(Path(__file__).with_name("Run-Day1BrowserSmoke.py")))
successes = FUNCTIONS["owner_notification_successes"]
delivery_count = FUNCTIONS["discord_delivery_successes"]


class OwnerAlertEvidenceTests(unittest.TestCase):
    def test_owner_credential_uses_a_private_runtime_subpath_and_readable_public_configuration(self):
        with tempfile.TemporaryDirectory(prefix="fleetflow-owner-config-") as directory:
            root = Path(directory)
            private = root / "private"
            private.mkdir(mode=0o700)
            target = root / "target"
            marker = "https://discord.com/api/webhooks/123/fixture-token"
            target.write_text(marker)
            target.chmod(0o600)
            document = {"services": {"app": {"networks": []}}, "volumes": {"runtime_secrets": {}}}
            previous_mask = os.umask(0o077)
            try:
                enabled, missing = FUNCTIONS["configure_alert_delivery"](document, private, "fixture", target)
            finally:
                os.umask(previous_mask)
            self.assertTrue(enabled)
            self.assertIsNone(missing)
            receiver = document["services"]["alertmanager"]
            self.assertNotIn("secrets", receiver)
            mount = next(item for item in receiver["volumes"] if item["target"] == "/run/secrets/discord_webhook_url")
            self.assertEqual(mount["source"], "runtime_secrets")
            self.assertEqual(mount["volume"]["subpath"], "discord_webhook_url")
            self.assertTrue(mount["read_only"])
            self.assertEqual((private / "discord_webhook_url").read_text(), marker)
            self.assertNotIn(marker, (private / "alertmanager.yml").read_text())
            if os.name != "nt":
                self.assertEqual((private / "alertmanager.yml").stat().st_mode & 0o777, 0o644)
                self.assertEqual((private / "discord_webhook_url").stat().st_mode & 0o777, 0o600)
                self.assertEqual(target.stat().st_mode & 0o777, 0o600)

    def test_diagnostics_redact_known_generated_credentials(self):
        with tempfile.TemporaryDirectory(prefix="fleetflow-owner-log-") as directory:
            path = Path(directory) / "diagnostics.log"
            result = SimpleNamespace(returncode=0, stdout="synthetic-private-password", stderr="")
            with patch("subprocess.run", return_value=result):
                FUNCTIONS["execute"](["fixture"], log=path, redact_values=(result.stdout,))
            self.assertEqual(path.read_text(), "[REDACTED_SECRET]")

    def test_counts_only_completed_requests_and_subtracts_failed_requests(self):
        expressions = []

        def query(container, expression):
            expressions.append(expression)
            return '{} => ' + ('2' if 'failed' in expression else '3') + ' @[123]'

        with patch.dict(delivery_count.__globals__, {"promtool_query": query}):
            self.assertEqual(delivery_count("private-prometheus"), 1)
        self.assertTrue(all("notification_requests_" in expression for expression in expressions))
        self.assertFalse(any("notifications_total" in expression for expression in expressions))

    def test_requires_success_for_the_actual_receiver_integration_and_backlog_group(self):
        expected = {"time": "2026-10-17T03:00:00Z", "msg": "Notify success",
                    "receiver": "fleetflow-discord", "integration": "discord[0]",
                    "aggrGroup": '{}:{alertname="FleetFlowOutboxBacklog"}',
                    "alerts": "[FleetFlowOutboxBacklog[synthetic][active]]", "numAlerts": 1}
        events = [expected, {**expected, "msg": "Notify attempt failed, will retry later"},
                  {**expected, "receiver": "someone-else"}, {**expected, "integration": "webhook[0]"},
                  {**expected, "aggrGroup": '{}:{alertname="FleetFlowReadinessUnobservable"}'}]
        result = SimpleNamespace(stdout="", stderr="\n".join(json.dumps(row) for row in events))
        with patch("subprocess.run", return_value=result):
            self.assertEqual(successes("private-alertmanager"), [expected])

    def test_does_not_archive_unrelated_log_fields_or_unstructured_lines(self):
        event = {"msg": "Notify success", "receiver": "fleetflow-discord", "integration": "discord[0]",
                 "aggrGroup": '{}:{alertname="FleetFlowOutboxBacklog"}', "unrelated": "must-not-be-retained"}
        with patch("subprocess.run", return_value=SimpleNamespace(stdout="not-json\n" + json.dumps(event), stderr="")):
            result = successes("private-alertmanager")
        self.assertEqual(len(result), 1)
        self.assertNotIn("must-not-be-retained", json.dumps(result))

    def test_database_delivery_cannot_be_satisfied_by_a_backlog_notification(self):
        base = {"msg": "Notify success", "receiver": "fleetflow-discord", "integration": "discord[0]"}
        events = [{**base, "aggrGroup": '{}:{alertname="FleetFlowOutboxBacklog"}'},
                  {**base, "aggrGroup": '{}:{alertname="FleetFlowDatabaseReadinessLoss"}'}]
        with patch("subprocess.run", return_value=SimpleNamespace(stdout="", stderr="\n".join(json.dumps(row) for row in events))):
            result = successes("private-alertmanager", "FleetFlowDatabaseReadinessLoss")
        self.assertEqual(len(result), 1)
        self.assertIn("FleetFlowDatabaseReadinessLoss", result[0]["aggrGroup"])


if __name__ == "__main__":
    unittest.main()
