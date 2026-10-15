import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from scrub_playwright_trace import scrub_trace


class PlaywrightTraceSanitizerTests(unittest.TestCase):
    def test_removes_credentials_network_bodies_and_oauth_query_values(self):
        jwt = "eyJhbGciOiJub25lIn0." + "eyJzdWIiOiJzeW50aGV0aWMifQ." + "signature"
        token_password = "per-run-password-123"
        trace_events = [
            {"type": "before", "url": "http://identity.invalid/callback?code=one-time&state=private"},
            {"type": "resource-snapshot", "request": {
                "url": "http://api.invalid/orders?access_token=leak&keep=visible",
                "headers": [{"name": "Authorization", "value": f"Bearer {jwt}"}],
                "postData": f"password={token_password}",
            }, "response": {"body": f'{{"access_token":"{jwt}"}}'}},
        ]
        with tempfile.TemporaryDirectory(prefix="fleetflow-trace-test-") as directory:
            archive = Path(directory) / "trace.zip"
            with zipfile.ZipFile(archive, "w") as trace:
                trace.writestr("trace.trace", "\n".join(json.dumps(item) for item in trace_events))
                trace.writestr("resources/oidc-response.json", json.dumps({"access_token": jwt}))
                trace.writestr("test-failed-1.png", b"synthetic screenshot")

            scrub_trace(archive, (token_password,))

            with zipfile.ZipFile(archive) as trace:
                names = trace.namelist()
                content = trace.read("trace.trace").decode("utf-8")
                self.assertIn('"type":"before"', content)
                self.assertIn("keep=visible", content)
                self.assertIn("[REDACTED_HEADER]", content)
                self.assertNotIn(jwt, content)
                self.assertNotIn(token_password, content)
                self.assertNotIn("one-time", content)
                self.assertNotIn("access_token=", content)
                self.assertFalse(any(name.startswith("resources/") for name in names))
                self.assertIn("test-failed-1.png", names)


if __name__ == "__main__":
    unittest.main()
