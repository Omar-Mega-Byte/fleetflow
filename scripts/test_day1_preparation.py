"""Unit checks for measurement and preflight guards; never touches a running stack."""
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parent.parent
summary = runpy.run_path(str(ROOT / "scripts/Phase11-Results.py"))["response_summary"]
operational = runpy.run_path(str(ROOT / "scripts/Phase11-Results.py"))["operational_samples"]
percentile = runpy.run_path(str(ROOT / "scripts/Phase11-Load.py"))["percentile"]
realm = runpy.run_path(str(ROOT / "ops/production/render-realm.py"))["realm_config"]


def unix_path(path):
    value = Path(path).resolve()
    return "/mnt/" + value.drive[0].lower() + value.as_posix()[2:] if os.name == "nt" else str(value)


class MeasurementTests(unittest.TestCase):
    def test_tail_percentiles_do_not_understate_small_samples(self):
        self.assertEqual(100, percentile([10, 100], .95))
        self.assertEqual(95, percentile(list(range(1, 101)), .95))
        self.assertIsNone(percentile([], .99))
        with self.assertRaises(ValueError):
            percentile([1], 0)

    def test_runtime_signal_units_and_unknown_values(self):
        samples = operational('jvm_gc_pause_seconds_sum{action="end"} 0.5\n'
            'jvm_gc_pause_seconds_sum{action="other"} 0.25\n'
            'jvm_memory_used_bytes{area="heap",id="one"} 1024\n'
            'jvm_memory_used_bytes{area="nonheap",id="other"} 9999\n'
            'fleetflow_tracking_active 10.0\n'
            'kafka_consumer_fetch_manager_records_lag_max{client_id="one"} 3\n'
            'kafka_consumer_fetch_manager_records_lag_max{client_id="two"} NaN\n')
        self.assertEqual(0.75, samples["gc_pause_seconds_total"])
        self.assertEqual(1024, samples["heap_used_bytes"])
        self.assertEqual(10, samples["sse_active"])
        self.assertEqual(3, samples["consumer_lag_max"])
        self.assertIsNone(samples["redis_available"])

    def test_expected_negative_probes_are_separate_from_positive_work(self):
        result = summary([("read", 200, 10), ("auth", 401, 2), ("scope", 403, 2),
                          ("race", 409, 2), ("pressure", 429, 2)],
                         {"auth": {401}, "scope": {403}, "race": {409}, "pressure": {429}})
        self.assertEqual(1.0, result["expected_operation_success_ratio"])
        self.assertEqual(0, result["infrastructure_failures"])
        self.assertEqual(0, result["unexpected_responses"])
        self.assertEqual({"401": 1, "403": 1, "409": 1, "429": 1}, result["expected_rejections"])

    def test_unspecified_4xx_is_unexpected_and_network_5xx_is_infrastructure(self):
        result = summary([("read", 200, 10), ("read", 429, 2), ("read", 503, 2), ("read", 0, 8000)])
        self.assertEqual(0.25, result["expected_operation_success_ratio"])
        self.assertEqual(2, result["infrastructure_failures"])
        self.assertEqual(1, result["unexpected_client_responses"])
        self.assertEqual({}, result["expected_rejections"])


class RealmTests(unittest.TestCase):
    def test_public_realm_has_only_pkce_browser_client(self):
        document = realm("demo.example.invalid", "identity.example.invalid")
        self.assertNotIn("users", document)
        self.assertEqual(1, len(document["clients"]))
        client = document["clients"][0]
        self.assertEqual("fleetflow-web", client["clientId"])
        self.assertTrue(client["publicClient"])
        self.assertFalse(client["directAccessGrantsEnabled"])
        self.assertNotIn("secret", client)
        self.assertEqual("S256", client["attributes"]["pkce.code.challenge.method"])
        self.assertEqual(["https://demo.example.invalid/auth/callback"], client["redirectUris"])
        self.assertEqual(["https://demo.example.invalid"], client["webOrigins"])
        self.assertEqual("https://demo.example.invalid/auth/signed-out", client["attributes"]["post.logout.redirect.uris"])
        self.assertTrue(document["bruteForceProtected"])

    def test_bad_hostnames_and_same_hosts_are_rejected(self):
        for host in ("https://bad.invalid", "*.example.invalid", "bad.invalid:443", "bad..invalid",
                     "bad.-label.invalid", "bad.label-.invalid", "127.0.0.1", "localhost"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                realm(host, "identity.example.invalid")
        with self.assertRaises(ValueError):
            realm("same.example.invalid", "same.example.invalid")


class BackupGuardTests(unittest.TestCase):
    def test_backup_age_success_stale_missing_malformed_future_and_invalid_bound(self):
        with tempfile.TemporaryDirectory(prefix="fleetflow-age-unit-") as temporary:
            marker = Path(temporary) / "marker"
            now = datetime.now(timezone.utc)
            cases = [(now - timedelta(hours=1), "24", 0), (now - timedelta(hours=25), "24", 1),
                     (now + timedelta(hours=1), "24", 1), ("not-a-date", "24", 2), (now, "25", 2), (None, "24", 1)]
            for value, limit, expected in cases:
                if value is None:
                    marker.unlink(missing_ok=True)
                else:
                    marker.write_text(value.strftime("%Y-%m-%dT%H:%M:%SZ") if isinstance(value, datetime) else value)
                result = subprocess.run(["bash", "-lc", f"FLEETFLOW_BACKUP_SUCCESS_MARKER='{unix_path(marker)}' "
                    f"FLEETFLOW_BACKUP_MAX_AGE_HOURS='{limit}' bash '{unix_path(ROOT / 'ops/production/check-backup-age.sh')}'"],
                    capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stderr)

    def test_bad_candidate_cannot_stop_services_or_create_backup(self):
        with tempfile.TemporaryDirectory(prefix="fleetflow-backup-guard-") as temporary:
            directory = Path(temporary); tools = directory / "bin"; tools.mkdir()
            calls = directory / "docker-calls"
            for command in ("age", "rclone", "docker"):
                script = tools / command
                script.write_text("#!/bin/sh\n" + (f"echo called >> '{unix_path(calls)}'\n" if command == "docker" else "") + "exit 99\n")
                script.chmod(0o700)
            metadata = directory / "metadata"; metadata.mkdir()
            (metadata / "source-sbom.json").write_text("{}")
            digest = "example.invalid/image@sha256:" + "1" * 64
            env = directory / "environment"
            env.write_text("\n".join(["FLEETFLOW_SOURCE_COMMIT=" + "a" * 40, "BACKUP_AGE_RECIPIENT=age1fakeunitrecipient",
                "BACKUP_REMOTE_DESTINATION=unit:recovery", "DEMO_HOST=demo.example.invalid", "IDENTITY_HOST=identity.example.invalid",
                "FLEETFLOW_RELEASE_METADATA_DIR=" + unix_path(metadata)] +
                [key + "=" + digest for key in ("FLEETFLOW_APP_IMAGE", "FLEETFLOW_KEYCLOAK_RUNTIME", "FLEETFLOW_EDGE_IMAGE",
                                               "FLEETFLOW_NODE_BUILD_IMAGE", "FLEETFLOW_NGINX_RUNTIME_IMAGE", "FLEETFLOW_POSTGIS_IMAGE",
                                               "FLEETFLOW_REDIS_IMAGE", "FLEETFLOW_KAFKA_IMAGE")]))
            for candidate in ({}, {"sourceCommit": "a" * 40, "images": {"app": digest, "edge": digest, "keycloakRuntime": digest}}):
                (metadata / "candidate.json").write_text(json.dumps(candidate))
                result = subprocess.run(["bash", "-lc", f"PATH='{unix_path(tools)}':/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "
                    f"FLEETFLOW_ENV_FILE='{unix_path(env)}' FLEETFLOW_BACKUP_DIR='{unix_path(directory / 'backups')}' "
                    f"bash '{unix_path(ROOT / 'ops/production/backup.sh')}'"], capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Candidate metadata", result.stderr)
                self.assertFalse(calls.exists(), "Preflight must fail before any Docker operation")
                self.assertFalse((directory / "backups").exists())


if __name__ == "__main__":
    unittest.main()
