ARG KEYCLOAK_BASE_IMAGE=quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
FROM ${KEYCLOAK_BASE_IMAGE} AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_BASE_IMAGE}
ENV KC_DB=postgres KC_HEALTH_ENABLED=true KC_METRICS_ENABLED=true
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 --chmod=0550 ops/production/keycloak-entrypoint.sh /opt/keycloak/bin/fleetflow-entrypoint.sh
USER 1000:0
HEALTHCHECK --interval=10s --timeout=5s --retries=18 --start-period=30s CMD ["bash", "-ec", "exec 3<>/dev/tcp/127.0.0.1/9000; printf 'GET /health/ready HTTP/1.0\\r\\n\\r\\n' >&3; IFS= read -r status <&3; [[ $status == *' 200 '* ]]"]
ENTRYPOINT ["/opt/keycloak/bin/fleetflow-entrypoint.sh"]
CMD ["start", "--optimized"]
