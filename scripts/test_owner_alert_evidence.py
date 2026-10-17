"""Reject attempted or unrelated notifications as owner-delivery proof."""
import json
from pathlib import Path
import runpy
from types import SimpleNamespace
import unittest
from unittest.mock import patch

FUNCTIONS = runpy.run_path(str(Path(__file__).with_name("Run-Day1BrowserSmoke.py")))
successes = FUNCTIONS["owner_notification_successes"]
delivery_count = FUNCTIONS["discord_delivery_successes"]


class OwnerAlertEvidenceTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
