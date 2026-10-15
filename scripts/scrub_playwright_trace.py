"""Remove credentials and request bodies from Playwright traces before archival."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
import sys
import tempfile
import zipfile
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")
SENSITIVE_HEADERS = {"authorization", "proxy-authorization", "cookie", "set-cookie"}
SENSITIVE_FIELDS = {
    "access_token", "refresh_token", "id_token", "client_secret", "password",
    "postdata", "post_data", "postdataentries", "body", "bodybase64",
    "requestbody", "responsebody",
}
SENSITIVE_QUERY = {
    "access_token", "refresh_token", "id_token", "code", "code_verifier",
    "client_secret", "password", "session_code", "session_state", "state",
}


def scrub_string(value: str, secrets: tuple[str, ...]) -> str:
    value = JWT_PATTERN.sub("[REDACTED_JWT]", value)
    for secret in secrets:
        if secret:
            value = value.replace(secret, "[REDACTED_SECRET]")
    return value


def scrub_url(value: str, secrets: tuple[str, ...]) -> str:
    try:
        parts = urlsplit(value)
    except ValueError:
        return scrub_string(value, secrets)
    query = [(key, item) for key, item in parse_qsl(parts.query, keep_blank_values=True)
             if key.casefold() not in SENSITIVE_QUERY]
    return scrub_string(urlunsplit((parts.scheme, parts.netloc, parts.path,
                                    urlencode(query), parts.fragment)), secrets)


def scrub_json(value, secrets: tuple[str, ...], parent: str = ""):
    if isinstance(value, dict):
        # Playwright represents headers as name/value objects in some trace versions.
        header_name = str(value.get("name", "")).casefold()
        result = {}
        for key, item in value.items():
            folded = str(key).casefold().replace("-", "_")
            if folded in SENSITIVE_FIELDS:
                continue
            if folded == "value" and header_name in SENSITIVE_HEADERS:
                result[key] = "[REDACTED_HEADER]"
            elif folded == "url" and isinstance(item, str):
                result[key] = scrub_url(item, secrets)
            elif folded in SENSITIVE_HEADERS:
                result[key] = "[REDACTED_HEADER]"
            else:
                result[key] = scrub_json(item, secrets, folded)
        return result
    if isinstance(value, list):
        return [scrub_json(item, secrets, parent) for item in value]
    if isinstance(value, str):
        if parent in {"url", "requesturl", "responseurl"}:
            return scrub_url(value, secrets)
        return scrub_string(value, secrets)
    return value


def scrub_trace(path: Path, secrets: tuple[str, ...]) -> None:
    with zipfile.ZipFile(path, "r") as source:
        retained: list[tuple[str, bytes]] = []
        for info in source.infolist():
            name = info.filename
            if name.endswith("/") or name.startswith("resources/"):
                continue
            raw = source.read(info)
            if name.endswith((".trace", ".network", ".jsonl")):
                lines = []
                for line in raw.decode("utf-8", errors="strict").splitlines():
                    if not line.strip():
                        continue
                    event = json.loads(line)
                    lines.append(json.dumps(scrub_json(event, secrets), separators=(",", ":")))
                raw = ("\n".join(lines) + ("\n" if lines else "")).encode("utf-8")
            elif name.endswith((".txt", ".json", ".html")):
                decoded = raw.decode("utf-8", errors="strict")
                # Trace source files can contain token-like literals even when a test fails.
                raw = scrub_string(decoded, secrets).encode("utf-8")
            retained.append((name, raw))

    with tempfile.NamedTemporaryFile(prefix="fleetflow-trace-", suffix=".zip",
                                     dir=path.parent, delete=False) as temporary:
        replacement = Path(temporary.name)
    try:
        with zipfile.ZipFile(replacement, "w", compression=zipfile.ZIP_DEFLATED) as target:
            for name, data in retained:
                target.writestr(name, data)
        # Verify the rebuilt archive can be opened before replacing the original.
        with zipfile.ZipFile(replacement, "r") as check:
            if check.testzip() is not None:
                raise ValueError("scrubbed trace archive failed its ZIP integrity check")
        replacement.replace(path)
    finally:
        replacement.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    args = parser.parse_args()
    try:
        raw_secrets = json.load(sys.stdin)
        if not isinstance(raw_secrets, list) or not all(isinstance(item, str) for item in raw_secrets):
            raise ValueError("stdin must contain a JSON array of secret strings")
        root = args.root.resolve()
        traces = sorted(root.rglob("*.zip")) if root.exists() else []
        for trace in traces:
            scrub_trace(trace, tuple(raw_secrets))
        print(json.dumps({"tracesScrubbed": len(traces), "networkBodiesRemoved": True,
                          "authorizationCookiesAndTokensRemoved": True}))
        return 0
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"Trace scrubbing failed ({type(error).__name__}); refusing artifact upload", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
