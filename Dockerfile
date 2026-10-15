FROM maven:3.9-eclipse-temurin-21@sha256:613124833fa6718ded9d655a2ebfab6425818c178f899116b93560b6f1c9ffe9 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
WORKDIR /app
RUN apk add --no-cache --upgrade zlib=1.3.2-r1
RUN addgroup -S -g 10001 fleetflow && adduser -S -u 10001 -G fleetflow fleetflow
COPY --from=build --chown=fleetflow:fleetflow /workspace/target/fleetflow-0.0.1-SNAPSHOT.jar app.jar
COPY --chown=fleetflow:fleetflow ops/production/app-entrypoint.sh /app/entrypoint.sh
RUN chmod 0555 /app/entrypoint.sh
USER fleetflow
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
