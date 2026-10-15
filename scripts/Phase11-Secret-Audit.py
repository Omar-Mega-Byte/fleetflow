"""Fail CI on repository private artifacts and high-confidence credential patterns.

This complements GitGuardian; it is intentionally not a complete secret scanner.
Only file paths, line numbers, and rule names are printed.
"""

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RULES = {
    "private-key-header": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "github-token": re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})"),
    "aws-access-key": re.compile(r"(?:AKIA|ASIA)[A-Z0-9]{16}"),
    "stripe-secret-key": re.compile(r"sk_(?:live|test)_[A-Za-z0-9]{20,}"),
}
PRIVATE_SUFFIXES = (".ffbackup", ".dump", ".pem", ".key", ".p12", ".jks")


def main():
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    ).split(b"\0")
    findings = []
    for raw_name in names:
        if not raw_name:
            continue
        name = raw_name.decode("utf-8")
        path = ROOT / name
        leaf = path.name.lower()
        if (leaf.startswith(".env") and leaf not in {".env.example", ".env.production.example"}) \
                or leaf.endswith(PRIVATE_SUFFIXES):
            findings.append((name, 0, "private-artifact"))
            continue
        if not path.is_file() or path.stat().st_size > 2_000_000:
            continue
        contents = path.read_bytes()
        if b"\0" in contents:
            continue
        for number, line in enumerate(contents.decode("utf-8", errors="replace").splitlines(), 1):
            for rule, pattern in RULES.items():
                if pattern.search(line):
                    findings.append((name, number, rule))
    for name, number, rule in findings:
        print(f"{name}:{number}: {rule}")
    if findings:
        raise SystemExit(1)
    print(f"Repository credential preflight passed ({len(names) - 1} paths inspected).")


if __name__ == "__main__":
    main()
