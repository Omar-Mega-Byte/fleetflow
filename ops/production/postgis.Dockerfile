ARG POSTGIS_BASE_IMAGE=postgis/postgis:17-3.5-alpine@sha256:894f570c0cf0664ed5576a8fd5d5bfb8fb1b19d592885b686c3a88c8bd90c41f
FROM ${POSTGIS_BASE_IMAGE}
# Refresh signed Alpine security packages without changing PostgreSQL/PostGIS versions.
# su-exec replaces the obsolete Go-based privilege-drop helper used only at startup.
RUN apk upgrade --no-cache \
    && apk add --no-cache su-exec \
    && sed -i 's/exec gosu postgres/exec su-exec postgres/' /usr/local/bin/docker-entrypoint.sh \
    && rm /usr/local/bin/gosu
USER postgres:root
