FROM maven:3.9-eclipse-temurin-21@sha256:613124833fa6718ded9d655a2ebfab6425818c178f899116b93560b6f1c9ffe9 AS security-libraries
RUN mvn --batch-mode --no-transfer-progress --strict-checksums org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy \
      -Dartifact=com.fasterxml.jackson.core:jackson-core:2.21.7 -DoutputDirectory=/patched \
    && mvn --batch-mode --no-transfer-progress --strict-checksums org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy \
      -Dartifact=com.fasterxml.jackson.core:jackson-databind:2.21.7 -DoutputDirectory=/patched \
    && mvn --batch-mode --no-transfer-progress --strict-checksums org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy \
      -Dartifact=at.yawk.lz4:lz4-java:1.11.4 -DoutputDirectory=/patched

FROM apache/kafka:4.2.2@sha256:1213eb3943d551e5ed1fca7a4e109001cee35770b66a02a0c37a8964efe09b69
USER root
# Maintenance-only library patches; broker version and protocol remain Apache Kafka 4.2.2.
RUN apk upgrade --no-cache \
    && rm /opt/kafka/libs/jackson-core-2.21.6.jar /opt/kafka/libs/jackson-databind-2.21.6.jar /opt/kafka/libs/lz4-java-1.11.2.jar
COPY --from=security-libraries /patched/ /opt/kafka/libs/
USER appuser
HEALTHCHECK --interval=20s --timeout=15s --retries=12 --start-period=20s CMD ["sh", "-ec", "KAFKA_HEAP_OPTS='-Xms16m -Xmx64m' /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092"]
