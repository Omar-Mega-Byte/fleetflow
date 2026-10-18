"""Run FleetFlow's signed Day 2 journey on a fresh loopback-only test stack."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
REALM_SOURCE = ROOT / "src/main/resources/oidc/fleetflow-realm.json"
PROJECT_PREFIX = "fleetflow-day2-vertical-"
JWT_PATTERN = re.compile(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+")


def execute(command: list[str], *, env: dict[str, str] | None = None,
            data: str | None = None, log: Path | None = None,
            secrets_to_redact: tuple[str, ...] = ()) -> str:
    result = subprocess.run(
        command, input=data, text=True, capture_output=True, cwd=ROOT, env=env,
    )
    output = result.stdout + ("\n" + result.stderr if result.stderr else "")
    output = redact(output, secrets_to_redact)
    if log is not None:
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_text(output, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} exited {result.returncode}; inspect the redacted evidence log")
    return result.stdout


def redact(text: str, values: tuple[str, ...] = ()) -> str:
    result = JWT_PATTERN.sub("[REDACTED_JWT]", text)
    for value in values:
        if value:
            result = result.replace(value, "[REDACTED_SECRET]")
    return result


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def wait_http(url: str, *, timeout_seconds: int = 240) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_status = "not available"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                body = response.read()
                if response.status == 200:
                    return json.loads(body)
                last_status = f"HTTP {response.status}"
        except (OSError, urllib.error.URLError, TimeoutError) as error:
            last_status = type(error).__name__
        time.sleep(2)
    raise RuntimeError(f"Service did not become ready at {url}; last result: {last_status}")


def stable_branch_id() -> str:
    raw = bytearray(hashlib.md5(
        b"fleetflow-public-demo-day1-v1:branch", usedforsecurity=False,
    ).digest())
    raw[6] = (raw[6] & 0x0F) | 0x30
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(raw)))


def write_private_realm(path: Path) -> tuple[dict[str, dict[str, str]], tuple[str, ...]]:
    names = {
        "customer": "fleetflow-demo-customer",
        "merchant": "fleetflow-demo-merchant",
        "driver": "fleetflow-demo-driver",
        "dispatcher": "fleetflow-demo-dispatcher",
        "operator": "fleetflow-demo-operator",
        "bootstrap": "fleetflow-day2-bootstrap",
    }
    accounts: dict[str, dict[str, str]] = {}
    users = []
    passwords: list[str] = []
    for key, subject in names.items():
        password = secrets.token_hex(32)
        passwords.append(password)
        accounts[key] = {"id": subject, "username": subject, "password": password}
        users.append({
            "id": subject,
            "username": subject,
            "enabled": True,
            "emailVerified": True,
            "email": subject + "@example.invalid",
            "firstName": "Synthetic",
            "lastName": "Day Two",
            "requiredActions": [],
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        })
    realm = json.loads(REALM_SOURCE.read_text(encoding="utf-8"))
    realm["users"] = users
    path.write_text(json.dumps(realm), encoding="utf-8")
    return accounts, tuple(passwords)


def scrub_file(path: Path, secrets_to_redact: tuple[str, ...]) -> None:
    if path.exists():
        path.write_text(redact(path.read_text(encoding="utf-8", errors="replace"), secrets_to_redact),
                        encoding="utf-8")


def main() -> int:
    local_app_data = Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir()))
    day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    evidence = Path(os.environ.get(
        "FLEETFLOW_DAY2_AUDIT_DIR",
        str(local_app_data / "FleetFlowDay2Audit" / day / "vertical-journey"),
    )).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    private = Path(tempfile.mkdtemp(prefix=PROJECT_PREFIX)).resolve()
    if private.parent != Path(tempfile.gettempdir()).resolve() or not private.name.startswith(PROJECT_PREFIX):
        raise RuntimeError("Refusing to use an unexpected private harness directory")

    project = PROJECT_PREFIX + uuid.uuid4().hex[:10]
    config_path = private / "compose.json"
    env_path = private / ".env"
    realm_path = private / "fleetflow-realm.json"
    app_log = private / "app-raw.log"
    app_process: subprocess.Popen | None = None
    app_stdout = None
    compose: list[str] = []
    compose_started = False
    secrets_to_redact: tuple[str, ...] = ()

    try:
        ports = {
            "DB_PORT": free_port(),
            "OIDC_PORT": free_port(),
            "REDIS_PORT": free_port(),
            "KAFKA_PORT": free_port(),
            "APP_PORT": free_port(),
        }
        db_password = secrets.token_hex(32)
        admin_password = secrets.token_hex(32)
        env_values = {
            "DB_NAME": "fleetflow",
            "DB_USER": "fleetflow",
            "DB_PASSWORD": db_password,
            "DB_URL": f"jdbc:postgresql://127.0.0.1:{ports['DB_PORT']}/fleetflow",
            "OIDC_ISSUER": f"http://127.0.0.1:{ports['OIDC_PORT']}/realms/fleetflow",
            "OIDC_JWKS_URI": f"http://127.0.0.1:{ports['OIDC_PORT']}/realms/fleetflow/protocol/openid-connect/certs",
            "OIDC_AUDIENCE": "fleetflow-api",
            "DEMO_ENABLED": "true",
            "DEMO_OIDC_ISSUER": f"http://127.0.0.1:{ports['OIDC_PORT']}/realms/fleetflow",
            "DEMO_CUSTOMER_SUBJECT": "fleetflow-demo-customer",
            "DEMO_MERCHANT_SUBJECT": "fleetflow-demo-merchant",
            "DEMO_DRIVER_SUBJECT": "fleetflow-demo-driver",
            "DEMO_DISPATCHER_SUBJECT": "fleetflow-demo-dispatcher",
            "DEMO_OPERATOR_SUBJECT": "fleetflow-demo-operator",
            "KC_ADMIN_USER": "fleetflow-day2-operator",
            "KC_ADMIN_PASSWORD": admin_password,
            "FLEETFLOW_POSTGIS_IMAGE": os.environ.get(
                "FLEETFLOW_TEST_IMAGE_POSTGRES", "fleetflow-postgis:day1-audit"),
            "FLEETFLOW_KEYCLOAK_IMAGE": os.environ.get(
                "FLEETFLOW_TEST_IMAGE_KEYCLOAK",
                "quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244"),
            "FLEETFLOW_REDIS_IMAGE": os.environ.get(
                "FLEETFLOW_TEST_IMAGE_REDIS",
                "redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499"),
            "FLEETFLOW_KAFKA_IMAGE": os.environ.get(
                "FLEETFLOW_TEST_IMAGE_KAFKA",
                "apache/kafka:3.9.2@sha256:05b4616e0702ef2729327705d54ad6b50ea70b271c4b730fabd2320789fb7b02"),
            **{name: str(value) for name, value in ports.items()},
        }
        env_path.write_text("\n".join(f"{key}={value}" for key, value in env_values.items()) + "\n",
                            encoding="utf-8")
        accounts, account_passwords = write_private_realm(realm_path)
        secrets_to_redact = (db_password, admin_password, *account_passwords)

        rendered = execute([
            "docker", "compose", "--project-name", project,
            "--env-file", str(env_path), "--file", str(ROOT / "compose.yaml"),
            "config", "--format", "json",
        ])
        document = json.loads(rendered)
        document["name"] = project
        document["services"].pop("app", None)
        kafka_health = document["services"]["kafka"].get("healthcheck")
        if kafka_health:
            # The Kafka CLI health probe launches a JVM; first-start Keycloak import can
            # saturate this host long enough to exceed the local Compose 5s probe timeout.
            kafka_health["timeout"] = "30s"
            kafka_health["retries"] = 24
            kafka_health["start_period"] = "120s"
        for volume in document["services"]["keycloak"].get("volumes", []):
            if isinstance(volume, dict) and volume.get("target") == "/opt/keycloak/data/import/fleetflow-realm.json":
                volume["source"] = str(realm_path)
        for name, network in document.get("networks", {}).items():
            if not network.get("external"):
                network["name"] = project + "_" + name
        for name, volume in document.get("volumes", {}).items():
            if not volume.get("external"):
                volume["name"] = project + "_" + name
        config_path.write_text(json.dumps(document), encoding="utf-8")
        compose = ["docker", "compose", "--project-name", project, "--file", str(config_path)]

        print("Starting fresh PostGIS, Redis, Kafka and Keycloak services under a unique Compose project.", flush=True)
        compose_started = True
        execute(compose + ["up", "--detach", "--wait", "--wait-timeout", "300",
                           "postgres", "redis", "kafka", "keycloak"],
                log=evidence / "compose-start.log", secrets_to_redact=secrets_to_redact)

        issuer = env_values["OIDC_ISSUER"]
        wait_http(issuer + "/.well-known/openid-configuration", timeout_seconds=240)
        app_env = os.environ.copy()
        app_env.update({
            "SPRING_PROFILES_ACTIVE": "local",
            "SERVER_PORT": str(ports["APP_PORT"]),
            "DB_URL": env_values["DB_URL"],
            "DB_USER": "fleetflow",
            "DB_PASSWORD": db_password,
            "OIDC_ISSUER": issuer,
            "OIDC_JWKS_URI": env_values["OIDC_JWKS_URI"],
            "OIDC_AUDIENCE": "fleetflow-api",
            "REDIS_HOST": "127.0.0.1",
            "REDIS_PORT": str(ports["REDIS_PORT"]),
            "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{ports['KAFKA_PORT']}",
            "OUTBOX_ENABLED": "true",
            "DEMO_ENABLED": "true",
            "DEMO_OIDC_ISSUER": issuer,
            "DEMO_CUSTOMER_SUBJECT": "fleetflow-demo-customer",
            "DEMO_MERCHANT_SUBJECT": "fleetflow-demo-merchant",
            "DEMO_DRIVER_SUBJECT": "fleetflow-demo-driver",
            "DEMO_DISPATCHER_SUBJECT": "fleetflow-demo-dispatcher",
            "DEMO_OPERATOR_SUBJECT": "fleetflow-demo-operator",
        })
        jars = [candidate for candidate in (ROOT / "target").glob("fleetflow-*.jar")
                if not candidate.name.endswith("-sources.jar") and not candidate.name.endswith("-javadoc.jar")]
        if len(jars) != 1:
            raise RuntimeError("Expected one packaged FleetFlow application JAR; run Maven package first")
        app_stdout = app_log.open("w", encoding="utf-8")
        app_process = subprocess.Popen(["java", "-jar", str(jars[0])], cwd=ROOT,
                                       env=app_env, stdout=app_stdout, stderr=subprocess.STDOUT)
        wait_http(f"http://127.0.0.1:{ports['APP_PORT']}/actuator/health/readiness", timeout_seconds=240)

        postgres_id = execute(compose + ["ps", "--quiet", "postgres"]).strip()
        bootstrap_actor_id = str(uuid.uuid4())
        bootstrap_subject = accounts["bootstrap"]["id"]
        bootstrap_sql = (
            "insert into actors(id, issuer, subject, active, created_at, updated_at) "
            f"values ('{bootstrap_actor_id}', '{issuer}', '{bootstrap_subject}', true, now(), now());\n"
            f"insert into actor_roles(actor_id, role) values ('{bootstrap_actor_id}', 'ADMIN');\n"
            "insert into merchants(id, name, active, created_at, updated_at) values "
            "('22222222-2222-4222-8222-222222222222', 'Foreign synthetic merchant', true, now(), now());\n"
            "insert into merchant_branches(id, merchant_id, name, pickup_line1, pickup_city, "
            "pickup_latitude, pickup_longitude, active, created_at, updated_at) values "
            "('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222', "
            "'Foreign synthetic branch', 'Fixture address', 'Cairo', 30.0444, 31.2357, true, now(), now());\n"
        )
        execute(["docker", "exec", "-i", postgres_id, "sh", "-ec",
                 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                data=bootstrap_sql, log=evidence / "bootstrap-actor.log",
                secrets_to_redact=secrets_to_redact)

        # Verify the signed application boundary before opening the browser. A
        # health probe alone does not exercise cold issuer/JWKS initialization.
        preflight_started = time.monotonic()
        token_request = urllib.request.Request(
            issuer + "/protocol/openid-connect/token",
            data=urllib.parse.urlencode({
                "grant_type": "password", "client_id": "fleetflow-cli",
                "username": accounts["bootstrap"]["username"],
                "password": accounts["bootstrap"]["password"],
            }).encode(),
        )
        with urllib.request.urlopen(token_request, timeout=30) as response:
            bootstrap_token = json.load(response)["access_token"]
        session_request = urllib.request.Request(
            f"http://127.0.0.1:{ports['APP_PORT']}/api/v1/session",
            headers={"Authorization": "Bearer " + bootstrap_token},
        )
        with urllib.request.urlopen(session_request, timeout=30) as response:
            session = json.load(response)
        if "ADMIN" not in session.get("roles", []):
            raise RuntimeError("Signed application preflight did not resolve the bootstrap administrator")
        (evidence / "identity-preflight.json").write_text(json.dumps({
            "status": 200, "roles": session["roles"],
            "elapsedMs": round((time.monotonic() - preflight_started) * 1000),
        }, indent=2), encoding="utf-8")
        del bootstrap_token

        browser_env = os.environ.copy()
        browser_env.update({
            "FLEETFLOW_E2E_ACCOUNTS": json.dumps(accounts),
            "FLEETFLOW_E2E_BRANCH": stable_branch_id(),
            "FLEETFLOW_E2E_EVIDENCE_DIR": str(evidence),
            "FLEETFLOW_E2E_PLAYWRIGHT_OUTPUT_DIR": str(evidence / "playwright-output"),
            "FLEETFLOW_E2E_BACKEND_URL": f"http://127.0.0.1:{ports['APP_PORT']}",
            "FLEETFLOW_E2E_OIDC_ISSUER": issuer,
            "VITE_LOCAL_ORDER_CREATION": "true",
        })
        print("Running the signed customer-to-delivery browser journey against real APIs and SSE.", flush=True)
        browser_exit = 0
        try:
            execute(["npm.cmd" if os.name == "nt" else "npm", "--prefix", "web", "run", "test:e2e:day2"],
                env=browser_env, log=evidence / "playwright.log",
                secrets_to_redact=secrets_to_redact)
        except RuntimeError:
            browser_exit = 1
        execute([sys.executable, str(ROOT / "scripts" / "scrub_playwright_trace.py"),
                 "--root", browser_env["FLEETFLOW_E2E_PLAYWRIGHT_OUTPUT_DIR"]],
                data=json.dumps(secrets_to_redact), log=evidence / "trace-scrub.log")
        if github_output := os.environ.get("GITHUB_OUTPUT"):
            with Path(github_output).open("a", encoding="utf-8") as output:
                output.write("trace_scrubbed=true\n")
        if browser_exit:
            raise RuntimeError("Day 2 Playwright journey failed; inspect redacted browser evidence")

        result_file = evidence / "vertical-result.json"
        if not result_file.is_file():
            raise RuntimeError("The browser journey did not produce its completion evidence")
        result = json.loads(result_file.read_text(encoding="utf-8"))
        order_id = result.get("orderId", "")
        if not isinstance(order_id, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
                order_id, re.IGNORECASE):
            raise RuntimeError("Browser evidence did not contain a valid created order identifier")
        lifecycle_sql = (
            "select string_agg(new_state, '>' order by version) from order_lifecycle_history "
            f"where order_id = '{order_id}'"
        )
        postcondition_sql = (
            "select status || '|' || version from orders "
            f"where id = '{order_id}'"
        )
        status_row = execute(["docker", "exec", postgres_id, "sh", "-ec",
                              'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                              "sh", postcondition_sql]).strip()
        lifecycle = execute(["docker", "exec", postgres_id, "sh", "-ec",
                             'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"',
                             "sh", lifecycle_sql]).strip()
        expected_states = ["CREATED", "CONFIRMED", "PREPARING", "READY_FOR_PICKUP",
                           "DRIVER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "DELIVERED"]
        observed_states = lifecycle.split(">") if lifecycle else []
        if not status_row.startswith("DELIVERED|") or not all(state in observed_states for state in expected_states):
            raise RuntimeError("Post-journey database evidence did not confirm the complete delivery lifecycle")
        (evidence / "database-postcondition.json").write_text(json.dumps({
            "orderId": order_id,
            "order": status_row,
            "lifecycle": observed_states,
            "source": "isolated ephemeral PostgreSQL after browser-driven API mutations",
        }, indent=2) + "\n", encoding="utf-8")
        recovery = json.loads((evidence / "recovery-result.json").read_text(encoding="utf-8"))
        zone = json.loads((evidence / "zone-result.json").read_text(encoding="utf-8"))
        identifiers = [recovery.get("orderId", ""), zone.get("zoneId", "")]
        if any(not isinstance(value, str) or not re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", value, re.IGNORECASE)
                for value in identifiers):
            raise RuntimeError("Extended browser evidence has an invalid resource identifier")
        recovery_order, zone_id = identifiers
        extra_sql = (
            f"select (select status from orders where id = '{recovery_order}') || '|' || "
            f"(select version::text || ':' || ST_IsValid(boundary)::text from delivery_zones where id = '{zone_id}') || '|' || "
            f"(select serviceability_mode from merchant_branches where id = '{stable_branch_id()}') || '|' || "
            f"(select count(*)::text from delivery_zones where branch_id = '{stable_branch_id()}')"
        )
        extended = execute(["docker", "exec", postgres_id, "sh", "-ec",
                            'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', "sh", extra_sql]).strip()
        if extended != "CANCELLED|2:true|UNRESTRICTED|2":
            raise RuntimeError("The database did not confirm cancellation, valid zone revision and a single created boundary")
        (evidence / "extended-database-postcondition.json").write_text(json.dumps({
            "recoveryOrderId": recovery_order, "zoneId": zone_id, "postcondition": extended,
            "source": "isolated PostgreSQL after signed browser recovery and zone edits",
        }, indent=2) + "\n", encoding="utf-8")
        print("PASS: real PKCE, operator seed/reset, customer order creation, merchant, dispatcher, driver, SSE and persisted DELIVERED state.", flush=True)
        print(f"Evidence: {evidence}", flush=True)
        return 0
    except Exception as error:
        scrub_file(app_log, secrets_to_redact)
        if compose:
            try:
                compose_logs = execute(compose + ["logs", "--no-color"], secrets_to_redact=secrets_to_redact)
                (evidence / "compose-failure.log").write_text(compose_logs, encoding="utf-8")
            except Exception:
                pass
        print(f"BLOCKED: {error}", file=sys.stderr, flush=True)
        print(f"Redacted evidence: {evidence}", file=sys.stderr, flush=True)
        return 1
    finally:
        if app_process is not None:
            app_process.terminate()
            try:
                app_process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                app_process.kill()
                app_process.wait(timeout=15)
        if app_stdout is not None:
            app_stdout.close()
        if app_log.exists():
            scrub_file(app_log, secrets_to_redact)
            shutil.move(str(app_log), str(evidence / "app.log"))
        if compose_started:
            try:
                execute(compose + ["down", "--volumes", "--remove-orphans"],
                        log=evidence / "compose-cleanup.log", secrets_to_redact=secrets_to_redact)
            except Exception as cleanup_error:
                print(f"CLEANUP WARNING: isolated Compose project cleanup failed: {cleanup_error}", file=sys.stderr)
        try:
            for child in list(private.iterdir()):
                if child.is_file() or child.is_symlink():
                    child.unlink(missing_ok=True)
            private.rmdir()
        except OSError as cleanup_error:
            print(f"PRIVATE FILE CLEANUP WARNING: {cleanup_error}", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
