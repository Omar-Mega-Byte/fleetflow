import json
import hashlib
from pathlib import Path
import tempfile
import unittest

from record_release_evidence import IMAGES, record

SHA = "a" * 40
RUN = "https://github.com/example/fleetflow/actions/runs/123"


class ReleaseEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="fleetflow-release-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.images = {"keycloak-runtime" if name == "keycloak" else name:
                       {"imageId": "sha256:" + str(index) * 64, "registryDigests": []}
                       for index, name in enumerate(IMAGES)}
        self.write("image-builds.json", {"kind": "BUILD_METADATA_ONLY", "candidateApproved": False,
                   "application": "FleetFlow", "applicationVersion": "0.0.1-SNAPSHOT",
                   "sourceCommit": SHA, "ciRun": RUN, "images": self.images})
        for name in (*IMAGES, "source-sbom"):
            self.write(f"fleetflow-{name}-{SHA}.cdx.json", {"bomFormat": "CycloneDX", "specVersion": "1.7",
                       "metadata": {"component": {"name": "FleetFlow" if name == "source-sbom" else "fleetflow-" + ("keycloak-runtime" if name == "keycloak" else name),
                                                  "version": "0.0.1-SNAPSHOT" if name == "source-sbom" else "ci"}},
                       "components": [{"type": "library", "name": name}]})
        for name in (*IMAGES, "source", "java", "javascript"):
            filename = {"source": "trivy-results.sarif", "java": "java.sarif", "javascript": "javascript.sarif"}.get(name, f"trivy-{name}.sarif")
            self.write(filename, {"version": "2.1.0", "runs": [{"tool": {"driver": {"rules": []}}, "results": []}]})
        self.write("source-maven-tree.json", {"children": []})
        self.write("source-sbom-coverage.json", {"verification": "PASS", "sourceCommit": SHA,
                   "sbomSha256": hashlib.sha256((self.root / f"fleetflow-source-sbom-{SHA}.cdx.json").read_bytes()).hexdigest(),
                   "mavenTreeSha256": hashlib.sha256((self.root / "source-maven-tree.json").read_bytes()).hexdigest(),
                   "npmLockSha256": hashlib.sha256((Path(__file__).resolve().parent.parent / "web/package-lock.json").read_bytes()).hexdigest(),
                   "selectedMavenComponents": 1, "lockedNpmComponents": 1, "unknownVersions": 0})
        self.write("result.json", {"result": "PASS", "publicDeployment": False, "hostBinding": "127.0.0.1",
                   "issuerSpoofingBlocked": True, "brokerOutageBusinessCommit": True, "databaseRecoveredWithoutAppRestart": True,
                   "redisOutageGauge": 0, "redisRecoveryGauge": 1, "databaseOutageGauge": 0, "databaseRecoveryGauge": 1,
                   "databaseReadinessOutageStatus": 503, "outboxRemaining": 0, "processedMarkers": 4, "expectedProcessedMarkers": 4,
                   "restartRetainedGeneration": 2, "edgeBodyLimit": 413,
                   "databaseAlertFiringObserved": True, "databaseAlertResolvedObserved": True,
                   "databaseOwnerFiringDeliveryObserved": True, "databaseOwnerResolvedDeliveryObserved": True,
                   "databaseOwnerNotificationDelta": 2,
                   "images": {"postgres" if name == "postgis" else name: self.images["keycloak-runtime" if name == "keycloak" else name]["imageId"] for name in IMAGES},
                   "ownerAlertDelivery": {"verified": True, "firingAlertObserved": True, "firingDeliveryObserved": True,
                                          "resolvedAlertObserved": True, "firingNotificationDelta": 1, "resolvedNotificationDelta": 1,
                                          "firingGroupSuccessDelta": 1, "resolvedGroupSuccessDelta": 1},
                   "ownerAlertDeliveryMissingInput": None})
        self.write("vertical-result.json", {"orderId": "order-one", "finalCustomerStatus": "DELIVERED", "logoutConfirmed": True,
                   "interruptedOrderCreateConfirmed": True, "syntheticPositionsConfirmed": 2})
        self.write("owner-notification-successes.json", [{"msg": "Notify success", "receiver": "fleetflow-discord",
                   "integration": "discord[0]", "aggrGroup": '{}:{alertname="FleetFlowOutboxBacklog"}'}] * 2)
        self.write("database-owner-notification-successes.json", [{"msg": "Notify success", "receiver": "fleetflow-discord",
                   "integration": "discord[0]", "aggrGroup": '{}:{alertname="FleetFlowDatabaseReadinessLoss"}'}] * 2)
        self.write("zone-result.json", {"zoneId": "zone-one", **{field: True for field in
                   ("createSameBodyAndKey", "keyboardEditing", "mapDrawingInsideExistingBoundary", "incompleteBoundaryRejected",
                    "concurrentUpdateDenied", "enforcedInsideCovered", "enforcedOutsideRejected", "unrestrictedOutsideCovered", "foreignBranchDenied")}})
        self.write("recovery-result.json", {"orderId": "order-two", "finalStatus": "CANCELLED", **{field: True for field in
                   ("actualNoCandidates", "actualRetryRanked", "actualDriverRejection", "actualDispatcherCancellation")}})
        self.write("database-postcondition.json", {"orderId": "order-one", "order": "DELIVERED|8"})
        self.write("extended-database-postcondition.json", {"zoneId": "zone-one", "recoveryOrderId": "order-two", "postcondition": "CANCELLED|2:true|UNRESTRICTED|2"})

    def write(self, name, content):
        (self.root / name).write_text(json.dumps(content), encoding="utf-8")

    def load(self, name):
        return json.loads((self.root / name).read_text(encoding="utf-8"))

    def test_collects_all_artifact_hashes_without_approving_or_publishing_a_candidate(self):
        result = record(self.root, SHA, SHA, RUN, True)
        self.assertEqual(len(result["sboms"]), 7)
        self.assertEqual(len(result["images"]), 6)
        self.assertEqual(result["ownerAlertDelivery"], "VERIFIED")
        self.assertFalse(result["candidateApproved"])
        self.assertEqual(result["acceptanceDecision"], "PARTIAL_NOT_COMPLETE")
        self.assertEqual(result["legacyPhase11"], "CLOSED_PARTIAL")
        self.assertEqual(result["ownerNotificationEvidence"]["completedGroupNotifications"], 2)
        self.assertEqual(result["sourceCommit"], SHA)
        self.assertTrue(all(len(item["sha256"]) == 64 for item in result["sboms"].values()))

    def test_refuses_old_commit_metadata_and_missing_sbom(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            record(self.root, "b" * 40, SHA, RUN, True)
        (self.root / f"fleetflow-app-{SHA}.cdx.json").unlink()
        with self.assertRaisesRegex(ValueError, "exactly one"):
            record(self.root, SHA, SHA, RUN, True)

    def test_rejects_source_coverage_from_a_different_sbom(self):
        coverage = self.load("source-sbom-coverage.json")
        coverage["sbomSha256"] = "0" * 64
        self.write("source-sbom-coverage.json", coverage)
        with self.assertRaisesRegex(ValueError, "Source SBOM coverage"):
            record(self.root, SHA, SHA, RUN, True)

    def test_missing_owner_delivery_is_a_visible_pr_limit_and_a_trusted_push_failure(self):
        browser = self.load("result.json")
        browser.update({"result": "PARTIAL", "ownerAlertDelivery": None, "ownerAlertDeliveryMissingInput": "private credential missing"})
        self.write("result.json", browser)
        self.assertEqual(record(self.root, SHA, SHA, RUN, False)["ownerAlertDelivery"], "NOT_VERIFIED_MISSING_INPUT")
        with self.assertRaisesRegex(ValueError, "owner firing and recovery"):
            record(self.root, SHA, SHA, RUN, True)

    def test_database_rule_clear_without_owner_recovery_delivery_fails(self):
        browser = self.load("result.json")
        browser.update({"databaseOwnerResolvedDeliveryObserved": False, "databaseOwnerNotificationDelta": 1})
        self.write("result.json", browser)
        with self.assertRaisesRegex(ValueError, "owner firing and recovery"):
            record(self.root, SHA, SHA, RUN, True)

    def test_rejects_mismatched_runtime_images_and_retained_secret_findings(self):
        browser = self.load("result.json")
        browser["images"]["app"] = "sha256:" + "f" * 64
        self.write("result.json", browser)
        with self.assertRaisesRegex(ValueError, "recorded image"):
            record(self.root, SHA, SHA, RUN, True)
        report = self.load("trivy-app.sarif")
        report["runs"][0]["properties"] = {"fleetflowSecretFindingSummary": [{"ruleId": "redacted-secret"}]}
        self.write("trivy-app.sarif", report)
        with self.assertRaisesRegex(ValueError, "Blocking Trivy"):
            record(self.root, SHA, SHA, RUN, True)


if __name__ == "__main__":
    unittest.main()
