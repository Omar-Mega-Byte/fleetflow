"""Fail-closed structural checks for the draft single-VM production Compose topology."""

import json
import re
import sys
import uuid
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"Production Compose validation failed: {message}")


try:
    document = json.load(sys.stdin)
except (json.JSONDecodeError, OSError) as error:
    fail(f"Compose did not produce valid JSON ({type(error).__name__})")

services = document.get("services", {})
expected_services = {"postgres", "keycloak-db", "keycloak", "redis", "kafka", "app", "edge"}
if set(services) != expected_services:
    fail("service inventory changed; review the public/private boundary explicitly")

for name, service in services.items():
    image = service.get("image", "")
    if not re.search(r"@sha256:[0-9a-f]{64}$", image):
        fail(f"{name} does not use an immutable image digest")
    if name != "edge" and service.get("ports"):
        fail(f"{name} publishes a host port")
    if not service.get("mem_limit") or not service.get("cpus"):
        fail(f"{name} has no CPU/memory bound")
    if service.get("logging", {}).get("options", {}).get("max-size") != "10m":
        fail(f"{name} has no bounded log rotation")
    if service.get("privileged") or service.get("network_mode") == "host":
        fail(f"{name} bypasses the container isolation boundary")
    if service.get("restart") not in {"unless-stopped", "always"}:
        fail(f"{name} has no production restart policy")
    if not isinstance(service.get("healthcheck", {}).get("test"), (list, str)):
        fail(f"{name} has no health check")
    if service.get("healthcheck", {}).get("test") in (None, [], ""):
        fail(f"{name} has an empty health check")
    dependencies = service.get("depends_on", {})
    for dependency, settings in dependencies.items():
        if dependency not in services or settings.get("condition") != "service_healthy":
            fail(f"{name} dependency {dependency} is not ordered on health")
    for volume in service.get("volumes", []):
        if volume.get("type") == "bind":
            source_name = volume.get("source", "").replace("\\", "/").rsplit("/", 1)[-1]
            if not (name == "postgres" and source_name == "postgres-app-role.sh"
                    and volume.get("target") == "/docker-entrypoint-initdb.d/20_fleetflow-app-role.sh"
                    and volume.get("read_only") is True):
                fail(f"{name} has an unapproved host bind mount")
    if any("docker.sock" in str(item).lower() for item in service.get("volumes", [])):
        fail(f"{name} exposes a Docker socket")
    for key, value in service.get("environment", {}).items():
        normalized = key.upper()
        if re.search(r"PASSWORD|TOKEN|SECRET|PRIVATE_KEY", normalized) and not normalized.endswith("_FILE"):
            fail(f"{name} has a secret-like value in environment key {key}; use a mounted secret file")

edge_ports = services["edge"].get("ports", [])
published = {(str(port.get("published")), str(port.get("target")), port.get("protocol"))
             for port in edge_ports}
if published != {("80", "80", "tcp"), ("443", "443", "tcp")}:
    fail("only the reverse proxy may publish exactly TCP 80 and 443")

network_membership = {
    name: set(service.get("networks", {})) for name, service in services.items()
}
expected_networks = {
    "postgres": {"backend"},
    "keycloak-db": {"identity-data"},
    "keycloak": {"app_proxy", "identity-data"},
    "redis": {"backend"},
    "kafka": {"backend"},
    "app": {"app_proxy", "backend"},
    "edge": {"app_proxy", "public_front"},
}
if network_membership != expected_networks:
    fail("service network membership changed; review the private network boundary")

networks = document.get("networks", {})
for name in ("app_proxy", "backend", "identity-data"):
    if networks.get(name, {}).get("internal") is not True:
        fail(f"{name} must be an internal Docker network")

if services["keycloak"].get("environment", {}).get("KC_HOSTNAME_STRICT") not in ("true", True):
    fail("Keycloak strict hostname enforcement is required")
if services["keycloak"].get("environment", {}).get("KC_PROXY_HEADERS") != "xforwarded":
    fail("Keycloak forwarded-header mode is not explicit")
if not services["keycloak"].get("secrets") or not services["app"].get("secrets"):
    fail("application and identity passwords must be delivered as Compose secrets")
app_environment = services["app"].get("environment", {})
identity_environment = services["keycloak"].get("environment", {})
edge_environment = services["edge"].get("environment", {})
issuer = app_environment.get("OIDC_ISSUER", "")
identity_host = edge_environment.get("IDENTITY_HOST", "")
demo_host = edge_environment.get("DEMO_HOST", "")
if issuer != f"https://{identity_host}/realms/fleetflow" or not identity_host or not demo_host:
    fail("API issuer and production identity hostname do not match the public realm")
for host_name, host_value in (("DEMO_HOST", demo_host), ("IDENTITY_HOST", identity_host)):
    labels = host_value.split(".")
    if (len(host_value) > 253 or len(labels) < 2 or any(not re.fullmatch(
            r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label) for label in labels)):
        fail(f"{host_name} must be a DNS hostname")
if demo_host.lower() == identity_host.lower():
    fail("demo and identity hosts must be different")
if app_environment.get("DEMO_OIDC_ISSUER") != issuer:
    fail("demo identity issuer differs from the API issuer")
subjects = []
for role in ("CUSTOMER", "MERCHANT", "DRIVER", "DISPATCHER", "OPERATOR"):
    key = f"DEMO_{role}_SUBJECT"
    value = app_environment.get(key, "")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError, TypeError):
        fail(f"{key} must be the real Keycloak UUID subject")
    if parsed.int == 0 or str(parsed) != value.lower() or str(parsed) in {
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            "55555555-5555-4555-8555-555555555555"}:
        fail(f"{key} still uses an example or malformed subject")
    subjects.append(str(parsed))
if len(set(subjects)) != len(subjects):
    fail("demo role subjects must identify five distinct Keycloak users")
if app_environment.get("OIDC_AUDIENCE") != "fleetflow-api":
    fail("production API audience is not fleetflow-api")
if identity_environment.get("KC_HOSTNAME") != f"https://{identity_host}":
    fail("Keycloak public hostname does not match the API issuer host")
if app_environment.get("SPRING_PROFILES_ACTIVE") != "prod":
    fail("production application profile is not selected")
if app_environment.get("SERVER_FORWARD_HEADERS_STRATEGY") != "framework":
    fail("application forwarded-header handling is not explicitly enabled")

for name, service in services.items():
    if service.get("cap_add") or service.get("devices"):
        fail(f"{name} adds capabilities or devices outside the release boundary")
    if service.get("pid") == "host" or service.get("ipc") == "host" or service.get("uts") == "host":
        fail(f"{name} shares a host namespace")

named_volumes = set(document.get("volumes", {}))
used_named_volumes = {
    volume.get("source") for service in services.values() for volume in service.get("volumes", [])
    if volume.get("type") == "volume"
}
if named_volumes != {"postgres_data", "keycloak_db_data", "kafka_data"} or used_named_volumes != named_volumes:
    fail("persistent volume inventory changed; review restore and retention assumptions")

proxy = Path("web/nginx/fleetflow.conf.template").read_text(encoding="utf-8")
proxy += Path("web/nginx/proxy-headers.conf").read_text(encoding="utf-8")
required_proxy_rules = (
    "return 301 https://$host$request_uri",
    "proxy_set_header X-Forwarded-For $remote_addr",
    "proxy_buffering off",
    "proxy_read_timeout 360s",
    "Strict-Transport-Security",
    "location ^~ /actuator/ { return 404; }",
    "location ^~ /admin/ { return 404; }",
    "location ^~ /realms/master/ { return 404; }",
    "location /realms/fleetflow/",
    'proxy_set_header Forwarded ""',
    "proxy_set_header X-Forwarded-Port 443",
    "client_body_timeout 10s",
    "client_header_timeout 10s",
    "proxy_read_timeout 30s",
    "limit_req_zone $binary_remote_addr",
    "log_format fleetflow",
    'add_header Content-Security-Policy "default-src',
    'location = /runtime-config.js',
    'oidcAuthority: "https://${IDENTITY_HOST}/realms/fleetflow"',
    "connect-src 'self' https://${IDENTITY_HOST}",
    "proxy_set_header Host $host",
)
for rule in required_proxy_rules:
    if rule not in proxy:
        fail(f"reverse proxy draft is missing a required boundary: {rule}")
if "$request_uri" in proxy.split("log_format fleetflow", 1)[1].split(";", 1)[0]:
    fail("OIDC query strings must not appear in access logs")
if services["postgres"]["environment"].get("POSTGRES_USER") == services["app"]["environment"].get("DB_USER"):
    fail("application database credentials must not be the bootstrap superuser")

ports_text = ", ".join(f"edge:{protocol}/{port}" for port, _target, protocol in sorted(published))
print("Production Compose rehearsal passed: " + ports_text
      + "; databases, Redis, Kafka, app and Keycloak are private; health/restart/dependency,")
print("resource, secret-file, volume, proxy and issuer/hostname checks passed. Configuration was rendered, not started.")
