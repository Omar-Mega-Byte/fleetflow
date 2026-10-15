"""Identify the uncommitted executable source separately from the base Git commit."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent


def git(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT).decode().strip()


def manifest():
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    files = []
    for name in sorted(set(paths.decode().split("\0"))):
        if not name or name.startswith("docs/") or not (ROOT / name).is_file():
            continue
        files.append({"path": name, "sha256": hashlib.sha256((ROOT / name).read_bytes()).hexdigest()})
    source_hash = hashlib.sha256(json.dumps(files, separators=(",", ":")).encode()).hexdigest()
    return {"baseCommit": git("rev-parse", "HEAD"), "branch": git("branch", "--show-current"),
            "dirty": bool(git("status", "--porcelain")), "sourceSha256": source_hash,
            "createdAtUtc": datetime.now(timezone.utc).isoformat(), "kind": "AUDIT_ONLY",
            "scope": "nonignored repository files excluding docs; not a release candidate", "files": files}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest(), indent=2), encoding="utf-8")
    print("Recorded AUDIT_ONLY executable source identity; no candidate or commit created")
