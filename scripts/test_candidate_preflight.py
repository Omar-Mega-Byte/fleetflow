"""Release preflight must reject unapproved, mismatched or altered artifacts."""

import hashlib
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from validate_release_candidate import CODEQL, IMAGE_ENV, SBOMS, TRIVY, validate  # noqa: E402


class CandidatePreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fleetflow-candidate-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = "a" * 40
        self.expiry = (datetime.now(timezone.utc) + timedelta(days=365)).isoformat()
        self.ci_run = "https://github.com/Omar-Mega-Byte/fleetflow/actions/runs/12345"
        self.refs = {name: "ghcr.io/example/" + name + "@sha256:" + str(index + 1) * 64
                     for index, name in enumerate(IMAGE_ENV)}
        self.ids = {name: "sha256:" + "abcdef"[index] * 64 for index, name in enumerate(IMAGE_ENV)}
        self.env = self.root / "environment"
        self.env.write_text("\n".join(["FLEETFLOW_SOURCE_COMMIT=" + self.source,
            "FLEETFLOW_RELEASE_METADATA_DIR=" + str(self.root)] +
            [key + "=" + self.refs[name] for name, key in IMAGE_ENV.items()]), encoding="utf-8")
        self.evidence = {"kind": "VERIFIED_BUILD_EVIDENCE", "verification": "PASS",
            "sourceCommit": self.source, "headCommit": self.source, "ciRun": self.ci_run,
            "ownerAlertRequired": True, "ownerAlertDelivery": "VERIFIED",
            "images": {name: {"imageId": image_id} for name, image_id in self.ids.items()},
            "sboms": {}, "trivy": {}, "codeql": {}}
        archive = self.artifact("fleetflow-runtime-images.tar.gz", "small archive fixture")
        manifest = {"sourceCommit": self.source, "ciRun": self.ci_run, "imageIds": self.ids,
                    "archiveSha256": archive["sha256"], "archiveBytes": (self.root / archive["path"]).stat().st_size}
        self.evidence["runtimeImageArchive"] = {**self.artifact("runtime-images.json", json.dumps(manifest)),
            "archive": {"path": archive["path"], "sha256": archive["sha256"], "bytes": manifest["archiveBytes"]}}
        for name in SBOMS:
            self.evidence["sboms"][name] = self.artifact("sbom-" + name + ".json", "{}")
        for name in TRIVY:
            self.evidence["trivy"][name] = {**self.artifact("trivy-" + name + ".sarif", '{"runs":[]}'),
                **{field: 0 for field in ("findings", "critical", "high", "unknown", "secrets", "unclassified", "blockers")}}
        for name in CODEQL:
            self.evidence["codeql"][name] = {**self.artifact("codeql-" + name + ".sarif", "{}"), "findings": 0}
        self.candidate = {"sourceCommit": self.source, "ciRun": self.ci_run, "images": self.refs,
            "approval": {"decision": "APPROVED", "owner": "release-owner", "atUtc": "2026-10-17T12:00:00Z",
                         "record": "reviewed release decision"}}
        self.save()

    def artifact(self, name, contents):
        path = self.root / name
        path.write_text(contents, encoding="utf-8")
        return {"path": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def save(self):
        self.candidate["releaseEvidence"] = self.artifact("release-evidence.json", json.dumps(self.evidence))
        (self.root / "candidate.json").write_text(json.dumps(self.candidate), encoding="utf-8")

    def test_approved_exact_candidate_passes(self):
        self.assertEqual(self.source, validate(self.env, self.root))

    def test_missing_approval_and_tampered_sbom_fail(self):
        self.candidate["approval"]["decision"] = "PENDING"
        self.save()
        with self.assertRaisesRegex(ValueError, "approval is missing"):
            validate(self.env, self.root)
        self.candidate["approval"]["decision"] = "APPROVED"
        self.save()
        (self.root / "sbom-app.json").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "artifact SHA-256 differs"):
            validate(self.env, self.root)

    def test_untrusted_or_mismatched_image_archive_fails(self):
        self.evidence["ownerAlertDelivery"] = "NOT_VERIFIED_MISSING_INPUT"
        self.save()
        with self.assertRaisesRegex(ValueError, "owner alert evidence"):
            validate(self.env, self.root)
        self.evidence["ownerAlertDelivery"] = "VERIFIED"
        self.evidence["runtimeImageArchive"]["archive"]["sha256"] = "f" * 64
        self.save()
        with self.assertRaisesRegex(ValueError, "archive manifest differs"):
            validate(self.env, self.root)

    def test_approval_timestamp_must_be_utc(self):
        self.candidate["approval"]["atUtc"] = "2026-10-17T15:00:00+03:00"
        self.save()
        with self.assertRaisesRegex(ValueError, "approval time must be in UTC"):
            validate(self.env, self.root)

    def test_published_image_must_match_scanned_image_id(self):
        with patch("validate_release_candidate.subprocess.check_output", return_value=self.ids["app"]):
            with self.assertRaisesRegex(ValueError, "scanned config or approved manifest"):
                validate(self.env, self.root, check_local_images=True)

    def test_classic_and_containerd_image_store_ids_both_match(self):
        classic = [self.ids[name] for name in IMAGE_ENV]
        containerd = ["sha256:" + self.refs[name].rsplit("@sha256:", 1)[1] for name in IMAGE_ENV]
        for ids in (classic, containerd):
            with self.subTest(ids=ids[0]):
                with patch("validate_release_candidate.subprocess.check_output", side_effect=ids):
                    self.assertEqual(self.source, validate(self.env, self.root, check_local_images=True))

    def test_registry_manifest_must_reference_scanned_config_and_one_platform(self):
        manifests = [json.dumps({"schemaVersion": 2, "config": {"digest": self.ids[name]}})
                     for name in IMAGE_ENV]
        with patch("validate_release_candidate.subprocess.check_output", side_effect=manifests):
            self.assertEqual(self.source, validate(self.env, self.root, check_registry_manifests=True))
        wrong = manifests.copy()
        wrong[0] = json.dumps({"schemaVersion": 2, "config": {"digest": "sha256:" + "f" * 64}})
        with patch("validate_release_candidate.subprocess.check_output", side_effect=wrong):
            with self.assertRaisesRegex(ValueError, "manifest config differs"):
                validate(self.env, self.root, check_registry_manifests=True)
        index = manifests.copy()
        index[0] = json.dumps({"schemaVersion": 2, "manifests": []})
        with patch("validate_release_candidate.subprocess.check_output", side_effect=index):
            with self.assertRaisesRegex(ValueError, "single-platform image manifest"):
                validate(self.env, self.root, check_registry_manifests=True)

    def test_open_finding_needs_completed_disposition(self):
        self.evidence["trivy"]["keycloak"] = {**self.artifact("trivy-keycloak.sarif",
            json.dumps({"runs": [{"results": [{"ruleId": "CVE-EXAMPLE"}]}]})),
            **{field: 0 for field in ("critical", "high", "unknown", "secrets", "unclassified", "blockers")},
            "findings": 1}
        self.candidate["advisoryReview"] = self.artifact("advisory-review.csv",
            "finding_id,disposition,owner,rationale,evidence,mitigation,expires_at_utc\n"
            f"CVE-EXAMPLE,OPEN,owner,pending,review,pending,{self.expiry}\n")
        self.save()
        with self.assertRaisesRegex(ValueError, "completed disposition"):
            validate(self.env, self.root)
        self.candidate["advisoryReview"] = self.artifact("advisory-review.csv",
            "finding_id,disposition,owner,rationale,evidence,mitigation,expires_at_utc\n"
            f"CVE-EXAMPLE,ACCEPTED_TIME_BOUND,owner,reviewed synthetic use,vendor-record,private edge,{self.expiry}\n")
        self.save()
        self.assertEqual(self.source, validate(self.env, self.root))


if __name__ == "__main__":
    unittest.main()
