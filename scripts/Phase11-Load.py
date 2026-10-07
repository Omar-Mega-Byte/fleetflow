"""Bounded, signed local Phase 11 workload. Uses only Python's standard library."""

import argparse
import concurrent.futures
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


def settings(path):
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value
    for key in ("DB_USER", "DB_NAME", "KC_ADMIN_USER", "KC_ADMIN_PASSWORD", "OIDC_ISSUER"):
        if not values.get(key):
            raise RuntimeError(f"Missing {key} in private environment file")
    if not values["OIDC_ISSUER"].startswith("http://127.0.0.1:"):
        raise RuntimeError("Only the loopback synthetic demo is allowed")
    return values


def sql(project, env_file, values, query):
    command = ["docker", "compose", "--env-file", str(env_file), "-p", project,
               "-f", str(ROOT / "compose.yaml"), "exec", "-T", "postgres", "psql",
               "-At", "-v", "ON_ERROR_STOP=1", "-U", values["DB_USER"],
               "-d", values["DB_NAME"], "-c", query]
    result = subprocess.run(command, capture_output=True, text=True, check=True)
    return [line for line in result.stdout.splitlines() if line]


def request(url, method="GET", token=None, data=None):
    payload = None if data is None else json.dumps(data).encode()
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, payload, headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=8) as response:
            body = response.read()
            return response.status, (time.perf_counter() - started) * 1000, body
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, (time.perf_counter() - started) * 1000, b""


def form(url, fields, token=None, method="POST"):
    headers = {"Content-Type": "application/x-www-form-urlencoded"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, urllib.parse.urlencode(fields).encode(), headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.load(response)


def identity_token(issuer, admin, subject):
    base = issuer.removesuffix("/realms/fleetflow")
    req = urllib.request.Request(base + "/admin/realms/fleetflow/users/" + subject,
                                 headers={"Authorization": "Bearer " + admin})
    with urllib.request.urlopen(req, timeout=10) as response:
        user = json.load(response)
    if not user.get("enabled") or not user.get("email", "").endswith("@example.invalid"):
        raise RuntimeError("Load identity is not an enabled disposable test user")
    password = secrets.token_urlsafe(32)
    payload = json.dumps({"type": "password", "value": password, "temporary": False}).encode()
    req = urllib.request.Request(base + "/admin/realms/fleetflow/users/" + subject + "/reset-password",
                                 payload, {"Authorization": "Bearer " + admin,
                                           "Content-Type": "application/json"}, method="PUT")
    with urllib.request.urlopen(req, timeout=10) as response:
        if response.status != 204:
            raise RuntimeError("Synthetic password reset failed")
    return user["username"], password


def access_token(issuer, credentials):
    return form(issuer + "/protocol/openid-connect/token",
                {"grant_type": "password", "client_id": "fleetflow-cli",
                 "username": credentials[0], "password": credentials[1]})["access_token"]


def percentile(samples, fraction):
    if not samples:
        return None
    ordered = sorted(samples)
    return round(ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * fraction)))], 1)


def container_stats(project):
    app = subprocess.run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
                          "--filter", "label=com.docker.compose.service=app"],
                         capture_output=True, text=True, check=True).stdout.strip()
    if not app:
        raise RuntimeError("App container is missing")
    result = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.CPUPerc}}|{{.MemUsage}}", app],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=int, default=600)
    args = parser.parse_args()
    if not args.project.startswith("fleetflow-phase11-") or args.duration_seconds < 10:
        raise RuntimeError("Use a disposable Phase 11 project and at least 10 seconds")
    env_file = args.env_file.resolve(strict=True)
    values = settings(env_file)
    issuer = values["OIDC_ISSUER"]
    base = issuer.removesuffix("/realms/fleetflow")
    app = "http://127.0.0.1:" + values.get("APP_PORT", "8080")
    admin = form(base + "/realms/master/protocol/openid-connect/token",
                 {"grant_type": "password", "client_id": "admin-cli",
                  "username": values["KC_ADMIN_USER"], "password": values["KC_ADMIN_PASSWORD"]})["access_token"]
    customer = sql(args.project, env_file, values,
                   "select a.subject::text || '|' || o.id::text from actors a join customers c on c.actor_id=a.id "
                   "join orders o on o.customer_id=c.id where a.active order by o.created_at desc limit 1")
    drivers = sql(args.project, env_file, values,
                  "select a.subject::text || '|' || d.operational_status from actors a join drivers d "
                  "on d.actor_id=a.id where a.active and d.active and not d.suspended order by d.id")
    if not customer or len(drivers) < 2:
        raise RuntimeError("At least one customer order and two synthetic drivers are required")
    customer_subject, order_id = customer[0].split("|")
    customer_credentials = identity_token(issuer, admin, customer_subject)
    driver_credentials = [identity_token(issuer, admin, row.split("|")[0]) for row in drivers[:2]]
    customer_token = access_token(issuer, customer_credentials)
    driver_tokens = [access_token(issuer, item) for item in driver_credentials]
    for index, row in enumerate(drivers[:2]):
        status = row.split("|")[1]
        if status in ("OFFLINE", "PAUSED"):
            action = "online" if status == "OFFLINE" else "available"
            code, _, _ = request(app + "/api/v1/drivers/me/" + action, "POST", driver_tokens[index])
            if code != 200:
                raise RuntimeError(f"Driver {index} activation returned HTTP {code}")
        code, _, _ = request(app + "/api/v1/drivers/me/heartbeat", "POST", driver_tokens[index])
        if code != 204:
            raise RuntimeError(f"Driver {index} heartbeat preflight returned HTTP {code}")
        code, _, _ = request(app + "/api/v1/drivers/me/location", "POST", driver_tokens[index],
                             {"latitude": 30.001 + index * .001, "longitude": 31.001,
                              "reportedAt": datetime.now(timezone.utc).isoformat()})
        if code != 204:
            raise RuntimeError(f"Driver {index} location preflight returned HTTP {code}")
    read_paths = ["/api/v1/orders?page=0&size=10", f"/api/v1/orders/{order_id}",
                  "/api/v1/notifications/me?limit=10", "/api/v1/drivers/me",
                  "/api/v1/drivers/me/deliveries"]
    for index, path in enumerate(read_paths):
        token = customer_token if index < 3 else driver_tokens[index - 3]
        code, _, _ = request(app + path, token=token)
        if code != 200:
            raise RuntimeError(f"Read preflight {index} returned HTTP {code}")
    stats_before = container_stats(args.project)
    started_utc = datetime.now(timezone.utc).isoformat()
    started = time.perf_counter()
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        pending = []
        for second in range(args.duration_seconds):
            deadline = started + second
            remaining = deadline - time.perf_counter()
            if remaining > 0:
                time.sleep(remaining)
            if second and second % 120 == 0:
                customer_token = access_token(issuer, customer_credentials)
                driver_tokens = [access_token(issuer, item) for item in driver_credentials]
            for index, path in enumerate(read_paths):
                token = customer_token if index < 3 else driver_tokens[index - 3]
                pending.append(("read", pool.submit(request, app + path, token=token)))
            driver_index = second % 2
            pending.append(("command", pool.submit(request, app + "/api/v1/drivers/me/heartbeat",
                                                    "POST", driver_tokens[driver_index])))
            if second % 10 == 0:
                for index in range(2):
                    pending.append(("command", pool.submit(
                        request, app + "/api/v1/drivers/me/location", "POST", driver_tokens[index],
                        {"latitude": 30.001 + index * .001, "longitude": 31.001,
                         "reportedAt": datetime.now(timezone.utc).isoformat()})))
        for kind, future in pending:
            try:
                code, latency, _ = future.result(timeout=15)
            except Exception:
                code, latency = 0, 8000.0
            results.append((kind, code, latency))
    elapsed = time.perf_counter() - started
    stats_after = container_stats(args.project)
    by_kind = {kind: [latency for item_kind, code, latency in results if item_kind == kind and code in (200, 204)]
               for kind in ("read", "command")}
    codes = Counter(str(code) for _, code, _ in results)
    unexpected = sum(count for code, count in codes.items() if code not in ("200", "204"))
    summary = {"started_utc": started_utc, "duration_seconds": round(elapsed, 2),
               "target_duration_seconds": args.duration_seconds, "arrival_rate_per_second": 6.2,
               "max_workers": 12, "total_requests": len(results), "throughput_per_second": round(len(results)/elapsed, 2),
               "status_counts": dict(sorted(codes.items())), "unexpected_error_percent": round(100*unexpected/len(results), 3),
               "read_ms": {"p50": percentile(by_kind["read"], .50), "p95": percentile(by_kind["read"], .95),
                           "p99": percentile(by_kind["read"], .99)},
               "command_ms": {"p50": percentile(by_kind["command"], .50),
                              "p95": percentile(by_kind["command"], .95),
                              "p99": percentile(by_kind["command"], .99)},
               "app_stats_before": stats_before, "app_stats_after": stats_after}
    print(json.dumps(summary, indent=2))
    if unexpected / len(results) >= .01 or any(code.startswith("5") for code in codes):
        raise RuntimeError("Unexpected error objective failed")
    if summary["read_ms"]["p95"] is None or summary["command_ms"]["p95"] is None or \
            summary["read_ms"]["p95"] >= 1000 or summary["command_ms"]["p95"] >= 2000:
        raise RuntimeError("Latency objective failed")


if __name__ == "__main__":
    main()
