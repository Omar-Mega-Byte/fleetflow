"""Render public-client realm configuration without users, passwords or the local direct-grant client."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def realm_config(demo_host: str, identity_host: str) -> dict:
    for host in (demo_host, identity_host):
        if len(host) > 253 or len(host.split(".")) < 2 or any(
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in host.split(".")
        ) or all(label.isdigit() for label in host.split(".")):
            raise ValueError("Use DNS hostnames without scheme, path, port or wildcard")
    if demo_host == identity_host:
        raise ValueError("Use separate demo and identity hostnames")
    source = json.loads((ROOT / "src/main/resources/oidc/fleetflow-realm.json").read_text(encoding="utf-8"))
    client = next(item for item in source["clients"] if item["clientId"] == "fleetflow-web")
    client["redirectUris"] = [f"https://{demo_host}/auth/callback"]
    client["webOrigins"] = [f"https://{demo_host}"]
    client["attributes"]["post.logout.redirect.uris"] = f"https://{demo_host}/auth/signed-out"
    return {"realm": "fleetflow", "enabled": True, "sslRequired": "external", "registrationAllowed": False,
            "resetPasswordAllowed": False, "rememberMe": False, "bruteForceProtected": True,
            "failureFactor": 5, "waitIncrementSeconds": 60, "maxFailureWaitSeconds": 900,
            "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 1800, "ssoSessionMaxLifespan": 3600,
            "attributes": {"frontendUrl": f"https://{identity_host}"}, "clients": [client]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--demo-host", required=True)
    parser.add_argument("--identity-host", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite an existing realm file")
    args.output.write_text(json.dumps(realm_config(args.demo_host, args.identity_host), indent=2), encoding="utf-8")
