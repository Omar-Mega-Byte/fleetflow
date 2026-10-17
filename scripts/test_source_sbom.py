import json
from pathlib import Path
import tempfile
import unittest

from verify_source_sbom import verify


class SourceSbomTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="fleetflow-source-bom-")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.bom, self.lock, self.tree = (self.root / name for name in ("bom.json", "lock.json", "tree.json"))
        self.components = [
            {"name": "direct", "version": "1", "type": "library", "purl": "pkg:maven/org.test/direct@1"},
            {"name": "transitive", "version": "2", "type": "library", "purl": "pkg:maven/org.test/transitive@2"},
            {"name": "dev-tool", "version": "3", "type": "library", "purl": "pkg:npm/%40scope/dev-tool@3"},
        ]
        self.lock.write_text(json.dumps({"packages": {"node_modules/@scope/dev-tool": {"version": "3", "dev": True}}}))
        self.tree.write_text(json.dumps({"children": [{"groupId": "org.test", "artifactId": "direct", "version": "1",
                             "children": [{"groupId": "org.test", "artifactId": "transitive", "version": "2"}]}]}))

    def check(self):
        self.bom.write_text(json.dumps({"bomFormat": "CycloneDX", "components": self.components}))
        return verify(self.bom, self.lock, self.tree, "a" * 40)

    def test_verifies_selected_transitives_and_scoped_dev_dependencies(self):
        result = self.check()
        self.assertEqual((result["selectedMavenComponents"], result["lockedNpmComponents"]), (2, 1))
        self.assertEqual(len(result["sbomSha256"]), 64)

    def test_missing_dev_dependency_fails(self):
        self.components.pop()
        with self.assertRaisesRegex(ValueError, "Missing npm inventory"):
            self.check()

    def test_wrong_maven_group_or_selected_version_fails(self):
        self.components[1]["purl"] = "pkg:maven/guessed/transitive@2"
        with self.assertRaisesRegex(ValueError, "Missing maven inventory"):
            self.check()

    def test_unknown_version_cannot_be_verified(self):
        self.components[0]["version"] = "UNKNOWN"
        with self.assertRaisesRegex(ValueError, "Unresolved component version"):
            self.check()


if __name__ == "__main__":
    unittest.main()
