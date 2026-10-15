"""Private, disposable production-topology smoke. Never uses an existing project or data volume."""
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import tempfile
import urllib.parse
import uuid
import runpy
import concurrent.futures
import time
import re
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent
DEMO_HOST = "demo.fleetflow.localhost"
IDENTITY_HOST = "identity.fleetflow.localhost"


def execute(command, *, data=None, env=None, log=None):
    result = subprocess.run(command, input=data, text=True, capture_output=True, cwd=ROOT, env=env)
    if log:
        # A failed privacy assertion must not upload the very credentials it detected.
        retained = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED_JWT]", result.stdout + result.stderr)
        retained = re.sub(r"(\b(?:GET|POST|PUT|PATCH|DELETE)\s+[^\s?\"]+)\?[^\s\"]+", r"\1?[REDACTED_QUERY]", retained)
        log.write_text(retained, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed; inspect the private evidence log")
    return result.stdout


def probe(path, *, host=DEMO_HOST, method="GET", body=None, token=None, extra_headers=None):
    connection = http.client.HTTPSConnection("127.0.0.1", 443, context=ssl._create_unverified_context(), timeout=20)
    headers = {"Host": host, "Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    headers.update(extra_headers or {})
    if isinstance(body, dict):
        body = json.dumps(body)
        headers["Content-Type"] = "application/json"
    elif body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    connection.request(method, path, body, headers)
    response = connection.getresponse()
    payload = response.read()
    status = response.status
    response_headers = dict(response.getheaders())
    connection.close()
    return status, response_headers, payload


def https(path, **options):
    status, _, payload = probe(path, **options)
    if status >= 400:
        raise RuntimeError(f"Private fixture HTTP {status} for {path.split('?')[0]}")
    return json.loads(payload) if payload else None


def main():
    base = Path(os.environ.get("FLEETFLOW_AUDIT_DIR", str(Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir()))
                / "FleetFlowDay1Audit" / datetime.now(timezone.utc).strftime("%Y-%m-%d"))))
    evidence = base / "browser"
    evidence.mkdir(parents=True, exist_ok=True)
    project = "fleetflow-day1-audit-" + uuid.uuid4().hex[:10]
    private = Path(tempfile.mkdtemp(prefix=project + "-"))
    config_path = private / "compose.json"
    secret_volume = project + "_runtime_secrets"
    compose = ["docker", "compose", "--project-name", project, "--file", str(config_path)]
    created = False
    succeeded = False
    try:
        print("Preparing private runtime secrets and realm", flush=True)
        passwords = {name: secrets.token_urlsafe(32) for name in ("postgres_db_password", "app_db_password",
                    "keycloak_db_password", "keycloak_admin_password")}
        for name, value in passwords.items():
            (private / name).write_text(value, encoding="utf-8")
        unix_private = "/mnt/" + private.drive[0].lower() + private.as_posix()[2:] if os.name == "nt" else str(private)
        execute(["bash", "-lc", "openssl req -x509 -newkey rsa:2048 -nodes -days 1 "
                 f"-keyout '{unix_private}/tls_private_key.pem' -out '{unix_private}/tls_fullchain.pem' "
                 f"-subj '/CN={DEMO_HOST}' -addext 'subjectAltName=DNS:{DEMO_HOST},DNS:{IDENTITY_HOST}'"],
                log=evidence / "certificate.log")
        environment = os.environ.copy()
        environment.update({"FLEETFLOW_SECRET_DIR": private.as_posix(), "DEMO_HOST": DEMO_HOST, "IDENTITY_HOST": IDENTITY_HOST})
        images = {}
        for service, tag, variable in (("app", "fleetflow-app:day1-audit", "FLEETFLOW_APP_IMAGE"),
                ("keycloak", "fleetflow-keycloak-runtime:day1-audit", "FLEETFLOW_KEYCLOAK_RUNTIME"),
                ("edge", "fleetflow-edge:day1-audit", "FLEETFLOW_EDGE_IMAGE"),
                ("postgres", "fleetflow-postgis:day1-audit", "FLEETFLOW_POSTGIS_IMAGE"),
                ("redis", "fleetflow-redis:day1-audit", "FLEETFLOW_REDIS_IMAGE"),
                ("kafka", "fleetflow-kafka:day1-audit", "FLEETFLOW_KAFKA_IMAGE")):
            tag = os.environ.get("FLEETFLOW_TEST_IMAGE_" + service.upper(), tag)
            image = json.loads(execute(["docker", "image", "inspect", tag]))[0]
            # Classic Docker's local build store has no RepoDigests. Its immutable image ID
            # is sufficient only for this disposable fixture, never for published candidates.
            images[service] = image["RepoDigests"][0] if image.get("RepoDigests") else image["Id"]
            environment[variable] = images[service]
        document = json.loads(execute(["docker", "compose", "--env-file", ".env.production.example", "--profile", "public",
                    "--file", "ops/production/compose.production.yaml", "config", "--format", "json"], env=environment))
        document["name"] = project
        document.pop("secrets", None)
        for name, network in document["networks"].items():
            network["name"] = project + "_" + name
        for name, volume in document["volumes"].items():
            volume["name"] = project + "_" + name
        document["volumes"]["runtime_secrets"] = {"name": secret_volume}
        for name, service in document["services"].items():
            # Mount only each service's declared secret files; do not share the whole secret directory.
            required = service.pop("secrets", [])
            service["volumes"] = service.get("volumes", []) + [
                {"type": "volume", "source": "runtime_secrets", "target": secret["target"],
                 "read_only": True, "volume": {"subpath": secret["source"]}} for secret in required]
        document["services"]["edge"]["ports"] = [
            {"target": 80, "published": "80", "host_ip": "127.0.0.1", "protocol": "tcp"},
            {"target": 443, "published": "443", "host_ip": "127.0.0.1", "protocol": "tcp"}]
        document["services"]["edge"].pop("profiles", None)
        document["services"]["app"]["environment"]["SPRING_PROFILES_ACTIVE"] = "prod,monitoring"
        document["services"]["app"]["healthcheck"]["test"][-1] = "http://127.0.0.1:9095/actuator/health/readiness"
        realm = runpy.run_path(str(ROOT / "ops/production/render-realm.py"))["realm_config"](DEMO_HOST, IDENTITY_HOST)
        local = json.loads((ROOT / "src/main/resources/oidc/fleetflow-realm.json").read_text(encoding="utf-8"))
        bootstrap = next(client for client in local["clients"] if client["clientId"] == "fleetflow-cli")
        bootstrap["clientId"] = "fleetflow-test-bootstrap"
        bootstrap["name"] = "DISPOSABLE TEST ONLY"
        realm["clients"].append(bootstrap)
        realm["registrationAllowed"] = False
        accounts = {}
        for persona in ("customer", "merchant", "driver", "dispatcher", "operator", "bootstrap"):
            subject = "fleetflow-demo-" + persona
            accounts[persona] = {"id": subject, "username": subject, "password": secrets.token_urlsafe(24)}
        realm["users"] = [{"id": value["id"], "username": value["username"], "enabled": True,
            "emailVerified": True, "email": value["username"] + "@example.invalid", "firstName": "Synthetic",
            "lastName": "Demo", "requiredActions": [],
            "credentials": [{"type": "password", "value": value["password"], "temporary": False}]}
            for value in accounts.values()]
        (private / "realm.json").write_text(json.dumps(realm), encoding="utf-8")
        document["services"]["keycloak"]["command"] = ["start", "--optimized", "--import-realm"]
        document["services"]["keycloak"]["volumes"].append({"type": "bind", "source": str(private / "realm.json"),
            "target": "/opt/keycloak/data/import/fleetflow-realm.json", "read_only": True})
        config_path.write_text(json.dumps(document), encoding="utf-8")
        execute(["docker", "volume", "create", "--label", "com.docker.compose.project=" + project,
                 "--label", "com.docker.compose.volume=runtime_secrets", secret_volume])
        created = True
        execute(["docker", "run", "--rm", "--user", "0:0", "--entrypoint", "sh", "--mount", f"type=bind,source={private},target=/source,readonly",
                 "--mount", f"type=volume,source={secret_volume},target=/run/secrets", document["services"]["postgres"]["image"],
                 "-ec", "cp /source/*_password /run/secrets/; cp /source/tls_fullchain.pem /run/secrets/tls_fullchain; "
                 "cp /source/tls_private_key.pem /run/secrets/tls_private_key; chmod 0755 /run/secrets; "
                 "chmod 0400 /run/secrets/*; chmod 0444 /run/secrets/app_db_password; "
                 "chown 70:0 /run/secrets/postgres_db_password; chmod 0440 /run/secrets/keycloak_db_password; "
                 "chown 1000:0 /run/secrets/keycloak_db_password /run/secrets/keycloak_admin_password; "
                 "chown 101:101 /run/secrets/tls_fullchain /run/secrets/tls_private_key"], log=evidence / "secrets.log")
        print("Starting isolated production topology on loopback 80/443", flush=True)
        execute(compose + ["up", "--detach", "--wait", "--wait-timeout", "300"], log=evidence / "start.log")
        pg = execute(compose + ["ps", "--quiet", "postgres"]).strip()
        privilege = execute(["docker", "exec", pg, "sh", "-ec", 'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
            + "-c \"select rolsuper::text || '|' || rolcreaterole::text || '|' || rolcreatedb::text from pg_roles where rolname = 'fleetflow_app'\""]).strip()
        if privilege != "false|false|false":
            raise RuntimeError("Application database role unexpectedly has administrative privileges")
        operator = str(uuid.uuid4())
        bootstrap_subject = accounts["bootstrap"]["id"]
        sql = f"insert into actors(id, issuer, subject, active, created_at, updated_at) values ('{operator}', " \
              f"'https://{IDENTITY_HOST}/realms/fleetflow', '{bootstrap_subject}', true, now(), now()); " \
              f"insert into actor_roles(actor_id, role) values ('{operator}', 'ADMIN');"
        execute(["docker", "exec", "-i", pg, "sh", "-ec", 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], data=sql)
        token = https("/realms/fleetflow/protocol/openid-connect/token", host=IDENTITY_HOST, method="POST",
            body=urllib.parse.urlencode({"grant_type": "password", "client_id": "fleetflow-test-bootstrap",
                "username": accounts["bootstrap"]["username"], "password": accounts["bootstrap"]["password"]}))["access_token"]
        claims = json.loads(base64.urlsafe_b64decode(token.split('.')[1] + "=="))
        if claims["sub"] != bootstrap_subject:
            raise RuntimeError("Keycloak subject does not match the seeded identity contract")
        connection = http.client.HTTPSConnection("127.0.0.1", 443, context=ssl._create_unverified_context(), timeout=20)
        connection.request("POST", "/api/v1/demo/seed", json.dumps({"reason": "Disposable signed browser verification"}),
            {"Host": DEMO_HOST, "Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": "browser-seed"})
        response = connection.getresponse(); seed = json.loads(response.read()); connection.close()
        if response.status != 200 or len(seed.get("orderIds", [])) != 1:
            raise RuntimeError("Synthetic seed did not return the expected baseline")
        branch = str(uuid.UUID(bytes=hashlib.md5(b"fleetflow-public-demo-day1-v1:branch", usedforsecurity=False).digest(), version=3))
        print("Running real browser PKCE, customer order, SSE, reload, authorization and logout checks", flush=True)
        browser_env = os.environ.copy()
        browser_env.update({"FLEETFLOW_E2E_ACCOUNTS": json.dumps(accounts), "FLEETFLOW_E2E_ORDER": seed["orderIds"][0],
            "FLEETFLOW_E2E_BRANCH": branch, "FLEETFLOW_E2E_PROJECT": project, "FLEETFLOW_E2E_COMPOSE": str(config_path),
            "FLEETFLOW_E2E_EVIDENCE_DIR": str(evidence)})
        execute(["npm.cmd" if os.name == "nt" else "npm", "--prefix", "web", "run", "test:e2e:live"],
            env=browser_env, log=evidence / "playwright.log")
        state = execute(["docker", "exec", pg, "sh", "-ec", 'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                        + "-c \"select status from orders where id = '" + seed["orderIds"][0] + "'\""]).strip()
        if state != "CONFIRMED":
            raise RuntimeError("Browser scenario did not leave the verified database state")
        app = execute(compose + ["ps", "--quiet", "app"]).strip()
        metrics = execute(["docker", "exec", app, "wget", "-q", "-O", "-", "http://127.0.0.1:9095/actuator/prometheus"])
        for meter in ("http_server_requests_seconds_count", "http_server_requests_seconds_bucket",
                      "hikaricp_connections_active", "hikaricp_connections_pending",
                      "fleetflow_operations_database_available", "fleetflow_operations_redis_available",
                      "fleetflow_outbox_pending", "fleetflow_outbox_oldest_seconds", "fleetflow_outbox_failed",
                      "fleetflow_events_quarantined_rows", "fleetflow_incidents_oldest_seconds",
                      "fleetflow_tracking_active", "fleetflow_tracking_connections_total"):
            if not re.search(r"^" + meter + r"[ {]", metrics, re.MULTILINE):
                raise RuntimeError("Required private meter is absent: " + meter)
        if re.search(r"[{,](?:actor_id|order_id|token|address|latitude|longitude|polygon|reason)=", metrics):
            raise RuntimeError("Private scrape contains a prohibited identifier label")
        (evidence / "healthy.prom").write_text(metrics, encoding="utf-8")
        print("Exercising committed reset with Kafka down, app restart, and Redis loss", flush=True)
        execute(compose + ["stop", "kafka", "redis"], log=evidence / "optional-outage.log")
        status, _, payload = probe("/api/v1/demo/reset", method="POST", token=token,
            body={"confirmation": "RESET FLEETFLOW DEMO", "reason": "Disposable broker and restart verification"},
            extra_headers={"Idempotency-Key": "browser-recovery-reset"})
        if status != 200 or json.loads(payload).get("generation") != 2:
            raise RuntimeError("Business reset did not commit while optional dependencies were down")
        pending = execute(["docker", "exec", pg, "sh", "-ec", 'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
            + '-c "select count(*) from outbox_events where status in (\'PENDING\', \'PROCESSING\')"']).strip()
        if int(pending) < 1:
            raise RuntimeError("Committed reset did not leave durable outbox work")
        execute(compose + ["restart", "--no-deps", "app"], log=evidence / "app-restart.log")
        app = execute(compose + ["ps", "--quiet", "app"]).strip()
        deadline = time.monotonic() + 180
        while True:
            health = execute(["docker", "inspect", "--format", "{{.State.Health.Status}}", app]).strip()
            if health == "healthy":
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Application did not become ready with Redis/Kafka unavailable")
            time.sleep(2)
        for group in ("liveness", "readiness"):
            result = execute(["docker", "exec", app, "wget", "-q", "-O", "-",
                              "http://127.0.0.1:9095/actuator/health/" + group])
            if json.loads(result).get("status") != "UP":
                raise RuntimeError("Optional dependency outage changed " + group)
        metrics = execute(["docker", "exec", app, "wget", "-q", "-O", "-", "http://127.0.0.1:9095/actuator/prometheus"])
        (evidence / "optional-outage.prom").write_text(metrics, encoding="utf-8")
        if execute(["docker", "inspect", "--format", "{{.RestartCount}}", app]).strip() != "0":
            raise RuntimeError("Optional dependency loss caused a process restart loop")
        # Refresh the signed operator credential after the restart wait; credentials stay in this process.
        token = https("/realms/fleetflow/protocol/openid-connect/token", host=IDENTITY_HOST, method="POST",
            body=urllib.parse.urlencode({"grant_type": "password", "client_id": "fleetflow-test-bootstrap",
                "username": accounts["bootstrap"]["username"], "password": accounts["bootstrap"]["password"]}))["access_token"]
        if https("/api/v1/demo", token=token).get("generation") != 2:
            raise RuntimeError("Restart lost committed demo generation")
        execute(compose + ["start", "kafka", "redis"], log=evidence / "optional-recovery.log")
        deadline = time.monotonic() + 180
        while True:
            remaining = execute(["docker", "exec", pg, "sh", "-ec", 'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                + '-c "select count(*) from outbox_events where status <> \'PUBLISHED\'"']).strip()
            projections = execute(["docker", "exec", pg, "sh", "-ec", 'psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" '
                + '-c "select (select count(*) from processed_events)::text || \'|\' || (2 * count(*))::text from outbox_events"']).strip()
            actual, expected = map(int, projections.split("|"))
            if int(remaining) == 0 and actual == expected:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("Durable outbox did not drain through both deduplicating consumers")
            time.sleep(2)
        # Exercise public-edge controls after browser work so pressure cannot disrupt its login flow.
        discovery = https("/realms/fleetflow/.well-known/openid-configuration", host=IDENTITY_HOST,
            extra_headers={"Forwarded": "host=attacker.invalid;proto=http", "X-Forwarded-Host": "attacker.invalid",
                           "X-Forwarded-Proto": "http", "X-Forwarded-Port": "80"})
        if discovery["issuer"] != f"https://{IDENTITY_HOST}/realms/fleetflow":
            raise RuntimeError("Forwarded headers changed the public OIDC issuer")
        status, _, payload = probe("/api/v1/orders", method="POST", body="x" * (1024 * 1024 + 1))
        if status != 413 or json.loads(payload).get("code") != "PAYLOAD_TOO_LARGE":
            raise RuntimeError("Edge body limit did not return the safe 413 contract")
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            pressure = list(pool.map(lambda index: probe("/api/v1/orders",
                extra_headers={"X-Forwarded-For": f"192.0.2.{index % 250}"}), range(100)))
        rejected = [(headers, payload) for status, headers, payload in pressure if status == 429]
        if not rejected or any(not headers.get("Retry-After") or json.loads(payload).get("code") != "RATE_LIMITED"
                               for headers, payload in rejected):
            raise RuntimeError("Spoofed forwarding headers bypassed the edge budget or its 429 contract")
        (evidence / "result.json").write_text(json.dumps({"result": "PASS", "project": project, "images": images,
            "orderStatus": state, "hostBinding": "127.0.0.1", "identity": "real disposable Keycloak",
            "database": "isolated PostgreSQL/PostGIS", "appDatabaseRole": "non-superuser",
            "edgeBodyLimit": 413, "edgePressureRejections": len(rejected), "issuerSpoofingBlocked": True,
            "brokerOutageBusinessCommit": True, "restartRetainedGeneration": 2,
            "optionalDependencyLivenessReadiness": "UP", "outboxRemaining": int(remaining),
            "processedMarkers": actual, "expectedProcessedMarkers": expected,
            "publicDeployment": False}, indent=2), encoding="utf-8")
        succeeded = True
        print("PASS: real HTTPS browser-to-Keycloak-to-API-to-PostgreSQL/SSE slice", flush=True)
    finally:
        if created:
            # Only the exact newly generated project and its non-external volumes can be removed.
            if not project.startswith("fleetflow-day1-audit-"):
                raise RuntimeError("Unsafe fixture cleanup target")
            try:
                containers = execute(compose + ["ps", "--all", "--quiet"]).split()
                for index, container in enumerate(containers):
                    execute(["docker", "inspect", "--format", "{{json .State}}", container],
                            log=evidence / f"health-{index}.json")
                execute(compose + ["logs", "--no-color"], log=evidence / "containers.log")
            finally:
                execute(compose + ["down", "--volumes", "--remove-orphans"], log=evidence / "cleanup.log")
        # These are generated, short-lived test credentials; delete files only within the checked directory.
        private_real = private.resolve()
        if private_real.parent == Path(tempfile.gettempdir()).resolve() and private_real.name.startswith(project):
            import shutil
            shutil.rmtree(private_real)
        if not succeeded:
            print("FAIL: private browser fixture; evidence retained without a success claim", flush=True)


if __name__ == "__main__":
    main()
