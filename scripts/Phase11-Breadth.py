"""Ten-driver, ten-owner SSE breadth drill for a disposable local Phase 11 project."""

import argparse
import base64
import concurrent.futures
import json
import re
import runpy
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
load = runpy.run_path(str(ROOT / "scripts" / "Phase11-Load.py"))
settings = load["settings"]
sql = load["sql"]
form = load["form"]
access_token = load["access_token"]
request = load["request"]
percentile = load["percentile"]
container_stats = load["container_stats"]
response_summary = runpy.run_path(str(ROOT / "scripts" / "Phase11-Results.py"))["response_summary"]
operational_samples = runpy.run_path(str(ROOT / "scripts" / "Phase11-Results.py"))["operational_samples"]


def create_identity(role, issuer, admin):
    base = issuer.removesuffix("/realms/fleetflow")
    username = f"phase11-breadth-{role.lower()}-{uuid.uuid4()}"
    password = secrets.token_urlsafe(32)
    payload = json.dumps({
        "username": username, "email": username + "@example.invalid", "emailVerified": True,
        "firstName": "Phase11", "lastName": role, "enabled": True, "requiredActions": [],
        "credentials": [{"type": "password", "value": password, "temporary": False}],
    }).encode()
    req = urllib.request.Request(base + "/admin/realms/fleetflow/users", payload,
                                 {"Authorization": "Bearer " + admin,
                                  "Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as response:
        if response.status != 201:
            raise RuntimeError("Synthetic identity creation failed")
    credentials = (username, password)
    token = access_token(issuer, credentials)
    segment = token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
    subject = str(uuid.UUID(claims["sub"]))
    return credentials, token, subject


def bootstrap(project, env_file, values, issuer, count):
    base = issuer.removesuffix("/realms/fleetflow")
    template = sql(project, env_file, values, "select id::text from orders order by created_at limit 1")
    if not template:
        raise RuntimeError("A synthetic template order is required")
    template_id = str(uuid.UUID(template[0]))
    customers, drivers = [], []
    for index in range(count * 2):
        role = "CUSTOMER" if index < count else "DRIVER"
        admin = form(base + "/realms/master/protocol/openid-connect/token",
                     {"grant_type": "password", "client_id": "admin-cli",
                      "username": values["KC_ADMIN_USER"],
                      "password": values["KC_ADMIN_PASSWORD"]})["access_token"]
        credentials, token, subject = create_identity(role, issuer, admin)
        actor_id = uuid.uuid4()
        statements = [
            f"insert into actors (id, issuer, subject, active, created_at, updated_at) "
            f"values ('{actor_id}', '{issuer}', '{subject}', true, now(), now())",
            f"insert into actor_roles (actor_id, role) values ('{actor_id}', '{role}')",
        ]
        if role == "CUSTOMER":
            customer_id, order_id = uuid.uuid4(), uuid.uuid4()
            statements += [
                f"insert into customers (id, actor_id, display_name, created_at, updated_at) "
                f"values ('{customer_id}', '{actor_id}', 'Phase11 breadth', now(), now())",
                f"insert into orders (id, customer_id, merchant_branch_id, status, "
                f"customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot, "
                f"pickup_line1, pickup_city, pickup_latitude, pickup_longitude, "
                f"delivery_line1, delivery_city, delivery_latitude, delivery_longitude, "
                f"currency, total_amount, created_at, updated_at) "
                f"select '{order_id}', '{customer_id}', merchant_branch_id, 'CREATED', "
                f"'Phase11 breadth', merchant_name_snapshot, branch_name_snapshot, "
                f"pickup_line1, pickup_city, pickup_latitude, pickup_longitude, "
                f"delivery_line1, delivery_city, delivery_latitude, delivery_longitude, "
                f"currency, total_amount, now(), now() from orders where id = '{template_id}'",
            ]
            customers.append({"credentials": credentials, "token": token, "order": str(order_id)})
        else:
            driver_id = uuid.uuid4()
            statements += [
                f"insert into drivers (id, actor_id, active, suspended, operational_status, "
                f"created_at, updated_at) values ('{driver_id}', '{actor_id}', true, false, "
                f"'OFFLINE', now(), now())",
                f"insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                f"values ('{uuid.uuid4()}', '{driver_id}', 'BIKE', 1, true, now(), now())",
            ]
            drivers.append({"credentials": credentials, "token": token})
        sql(project, env_file, values, "; ".join(statements) + ";")
    return customers, drivers


def prepare_business_transitions(project, env_file, values, issuer, count):
    """Create disposable orders and one real signed merchant for guarded commands."""
    base = issuer.removesuffix("/realms/fleetflow")
    admin = form(base + "/realms/master/protocol/openid-connect/token",
                 {"grant_type": "password", "client_id": "admin-cli",
                  "username": values["KC_ADMIN_USER"],
                  "password": values["KC_ADMIN_PASSWORD"]})["access_token"]
    credentials, token, subject = create_identity("MERCHANT", issuer, admin)
    actor_id, membership_id = uuid.uuid4(), uuid.uuid4()
    template = sql(project, env_file, values,
                   "select id::text || '|' || customer_id::text || '|' || merchant_branch_id::text "
                   "from orders order by created_at limit 1")
    if not template:
        raise RuntimeError("A template order is required for business load")
    template_id, _, branch_id = template[0].split("|")
    sql(project, env_file, values,
        f"insert into actors (id, issuer, subject, active, created_at, updated_at) "
        f"values ('{actor_id}', '{issuer}', '{subject}', true, now(), now()); "
        f"insert into actor_roles (actor_id, role) values ('{actor_id}', 'MERCHANT'); "
        f"insert into merchant_staff_memberships (id, actor_id, branch_id, active, created_at) "
        f"values ('{membership_id}', '{actor_id}', '{branch_id}', true, now())")
    marker = "Phase11-business-" + str(uuid.uuid4())
    orders = sql(project, env_file, values,
        "with inserted as (insert into orders (id, customer_id, merchant_branch_id, status, "
        "customer_name_snapshot, merchant_name_snapshot, branch_name_snapshot, "
        "pickup_line1, pickup_city, pickup_latitude, pickup_longitude, "
        "delivery_line1, delivery_city, delivery_latitude, delivery_longitude, "
        "currency, total_amount, created_at, updated_at) "
        f"select gen_random_uuid(), customer_id, merchant_branch_id, 'CREATED', '{marker}', "
        "merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city, "
        "pickup_latitude, pickup_longitude, delivery_line1, delivery_city, "
        "delivery_latitude, delivery_longitude, currency, total_amount, now(), now() "
        f"from orders cross join generate_series(1, {count}) where id = '{template_id}' "
        "returning id) select id from inserted")
    if len(orders) != count or any(not re.fullmatch(r"[0-9a-f-]{36}", item) for item in orders):
        raise RuntimeError("Disposable business order count did not match the target")
    return {"credentials": credentials, "token": token, "branch": branch_id,
            "orders": orders, "marker": marker}


def runtime_signals(project):
    app = subprocess.run(["docker", "ps", "-q", "--filter",
                          f"label=com.docker.compose.project={project}", "--filter",
                          "label=com.docker.compose.service=app"],
                         text=True, capture_output=True, check=True).stdout.strip()
    if not app:
        raise RuntimeError("App container is not running")
    metrics = subprocess.run(["docker", "exec", app, "wget", "-q", "-O", "-",
                              "http://127.0.0.1:9095/actuator/prometheus"],
                             text=True, capture_output=True, check=True).stdout
    return operational_samples(metrics)


def active_streams(project):
    active = runtime_signals(project)["sse_active"]
    if active is None:
        raise RuntimeError("Private active-stream gauge is missing")
    return int(active)


def stream_worker(customer, issuer, app, stop, ready, failures):
    while not stop.is_set():
        try:
            token = access_token(issuer, customer["credentials"])
            req = urllib.request.Request(
                app + f"/api/v1/orders/{customer['order']}/tracking/stream",
                headers={"Authorization": "Bearer " + token,
                         "Accept": "text/event-stream"})
            with urllib.request.urlopen(req, timeout=25) as response:
                if response.status != 200 or "text/event-stream" not in response.headers.get("Content-Type", ""):
                    raise RuntimeError("SSE connection did not return an event stream")
                first = response.readline()
                if not first:
                    raise RuntimeError("SSE stream closed before its initial event")
                ready.set()
                while not stop.is_set() and response.readline():
                    pass
        except Exception as error:
            if not stop.is_set():
                failures.append(type(error).__name__)
        if not stop.is_set():
            time.sleep(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=int, default=1800)
    parser.add_argument("--reviewer-sessions", type=int, choices=(2,), default=2,
                        help="Run simultaneous signed customer reads for two distinct review sessions")
    parser.add_argument("--business-transitions", action="store_true",
                        help="Add one signed merchant order confirmation per second")
    args = parser.parse_args()
    if not re.fullmatch(r"fleetflow-phase11-[a-z0-9-]+", args.project):
        raise RuntimeError("Only a disposable Phase 11 Compose project is allowed")
    if not 60 <= args.duration_seconds <= 3600:
        raise RuntimeError("The breadth drill requires 60 to 3600 seconds")
    env_file = args.env_file.resolve(strict=True)
    values = settings(env_file)
    issuer = values["OIDC_ISSUER"]
    if not re.fullmatch(r"http://127\.0\.0\.1:\d+/realms/fleetflow", issuer):
        raise RuntimeError("Only the loopback FleetFlow realm is allowed")
    app = "http://127.0.0.1:" + values.get("APP_PORT", "8080")
    if active_streams(args.project) != 0:
        raise RuntimeError("Wait for prior SSE sessions to expire or restart the disposable app")
    customers, drivers = bootstrap(args.project, env_file, values, issuer, 10)
    reviewers = customers[:args.reviewer_sessions]
    business = (prepare_business_transitions(args.project, env_file, values, issuer,
                                             args.duration_seconds)
                if args.business_transitions else None)
    for index, driver in enumerate(drivers):
        code, _, _ = request(app + "/api/v1/drivers/me/online", "POST", driver["token"])
        if code != 200:
            raise RuntimeError(f"Driver {index} activation returned HTTP {code}")
    stop = threading.Event()
    ready = [threading.Event() for _ in customers]
    stream_failures = []
    threads = [threading.Thread(target=stream_worker,
               args=(customer, issuer, app, stop, ready[index], stream_failures), daemon=True)
               for index, customer in enumerate(customers)]
    for thread in threads:
        thread.start()
    try:
        for event in ready:
            if not event.wait(30):
                raise RuntimeError("Ten signed SSE streams did not open")
        if active_streams(args.project) != 10:
            raise RuntimeError("Server did not report ten active customer streams")
        stats_before = container_stats(args.project)
        start_utc = datetime.now(timezone.utc).isoformat()
        start = time.perf_counter()
        results, gauges, resource_samples = [], [], []
        with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
            pending = []
            for second in range(args.duration_seconds):
                delay = start + second - time.perf_counter()
                if delay > 0:
                    time.sleep(delay)
                if second and second % 120 == 0:
                    for user in customers + drivers + ([business] if business else []):
                        user["token"] = access_token(issuer, user["credentials"])
                reviewer_pair = [reviewers[second % 2], reviewers[(second + 1) % 2]]
                driver = drivers[second % 10]
                paths = []
                for reviewer in reviewer_pair:
                    paths.extend([
                        ("review", app + "/api/v1/orders?page=0&size=10", reviewer["token"]),
                        ("review", app + f"/api/v1/orders/{reviewer['order']}", reviewer["token"]),
                        ("review", app + f"/api/v1/orders/{reviewer['order']}/tracking", reviewer["token"]),
                    ])
                paths.extend([
                    ("read", app + "/api/v1/drivers/me", driver["token"]),
                    ("read", app + "/api/v1/drivers/me/deliveries", driver["token"]),
                ])
                for kind, url, token in paths:
                    pending.append((kind, pool.submit(request, url, token=token)))
                pending.append(("command", pool.submit(
                    request, app + "/api/v1/drivers/me/heartbeat", "POST", driver["token"])))
                if business:
                    pending.append(("business", pool.submit(
                        request, app + f"/api/v1/merchant-branches/{business['branch']}"
                        f"/orders/{business['orders'][second]}/confirm", "POST", business["token"])))
                if second % 10 == 0:
                    for index, item in enumerate(drivers):
                        pending.append(("command", pool.submit(
                            request, app + "/api/v1/drivers/me/location", "POST", item["token"],
                            {"latitude": 30.001 + index * .001, "longitude": 31.001,
                             "reportedAt": datetime.now(timezone.utc).isoformat()})))
                if second % 15 == 0:
                    for item in drivers:
                        pending.append(("command", pool.submit(
                            request, app + "/api/v1/drivers/me/heartbeat", "POST", item["token"])))
                if second % 30 == 0:
                    signals = runtime_signals(args.project)
                    if signals["sse_active"] is None:
                        raise RuntimeError("Active-stream metric disappeared during the workload")
                    gauges.append(int(signals["sse_active"]))
                    resource_samples.append({"second": second, "utc": datetime.now(timezone.utc).isoformat(),
                                             "app": container_stats(args.project), "signals": signals})
            for kind, future in pending:
                try:
                    code, latency, _ = future.result(timeout=15)
                except Exception:
                    code, latency = 0, 8000.0
                results.append((kind, code, latency))
        elapsed = time.perf_counter() - start
        stats_after = container_stats(args.project)
        accounting = response_summary(results)
        unexpected = accounting["unexpected_responses"]
        by_kind = {kind: [ms for sample_kind, code, ms in results
                          if sample_kind == kind and code in (200, 204)]
                   for kind in ("review", "read", "command", "business")}
        report = {
            "started_utc": start_utc, "duration_seconds": round(elapsed, 2),
            "target_duration_seconds": args.duration_seconds,
            "drivers": len(drivers), "customer_streams": len(customers),
            "reviewer_sessions": len(reviewers),
            "stream_gauge_min": min(gauges), "stream_gauge_max": max(gauges),
            "stream_gauge_samples": len(gauges), "stream_reconnect_errors": len(stream_failures),
            "total_requests": len(results), "throughput_per_second": round(len(results) / elapsed, 2),
            **accounting,
            "environment": "disposable local Compose; not Azure qualification",
            "resource_samples": resource_samples,
            "read_ms": {key: percentile(by_kind["read"], fraction)
                        for key, fraction in (("p50", .5), ("p95", .95), ("p99", .99))},
            "review_ms": {key: percentile(by_kind["review"], fraction)
                          for key, fraction in (("p50", .5), ("p95", .95), ("p99", .99))},
            "command_ms": {key: percentile(by_kind["command"], fraction)
                           for key, fraction in (("p50", .5), ("p95", .95), ("p99", .99))},
            "business_transitions": len(by_kind["business"]),
            "business_ms": {key: percentile(by_kind["business"], fraction)
                            for key, fraction in (("p50", .5), ("p95", .95), ("p99", .99))},
            "app_stats_before": stats_before, "app_stats_after": stats_after,
        }
        if business:
            report["confirmed_rows"] = int(sql(args.project, env_file, values,
                "select count(*) from orders where customer_name_snapshot = "
                f"'{business['marker']}' and status = 'CONFIRMED'")[0])
            deadline = time.monotonic() + 120
            while True:
                counts = sql(args.project, env_file, values,
                    "select count(*)::text || '|' || "
                    "count(*) filter (where e.status = 'PUBLISHED')::text || '|' || "
                    "(select count(*) from processed_events p join outbox_events e2 "
                    "on e2.id = p.event_id join orders o2 on o2.id = e2.aggregate_id "
                    f"where o2.customer_name_snapshot = '{business['marker']}')::text "
                    "from outbox_events e join orders o on o.id = e.aggregate_id "
                    f"where o.customer_name_snapshot = '{business['marker']}'")
                total, published, projected = map(int, counts[0].split("|"))
                if total == published == args.duration_seconds and projected == 2 * total:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Business outbox did not publish and project within two minutes")
                time.sleep(2)
            report["business_outbox_total"] = total
            report["business_outbox_published"] = published
            report["business_processed_markers"] = projected
        print(json.dumps(report, indent=2))
        if unexpected / len(results) >= .01 or accounting["infrastructure_failures"]:
            raise RuntimeError("Unexpected error objective failed")
        if report["read_ms"]["p95"] >= 1000 or report["review_ms"]["p95"] >= 1000 or \
                report["command_ms"]["p95"] >= 1000:
            raise RuntimeError("Latency objective failed")
        if business and (report["business_transitions"] != args.duration_seconds
                         or report["confirmed_rows"] != args.duration_seconds
                         or report["business_ms"]["p95"] >= 1000):
            raise RuntimeError("Guarded business transition objective failed")
        if report["successful_expected_operations"] < 100:
            raise RuntimeError("At least 100 successful expected operations are required")
        if max(gauges) != 10 or sum(gauges) / len(gauges) < 9.5:
            raise RuntimeError("Ten-stream availability objective failed")
    finally:
        stop.set()
        for thread in threads:
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
