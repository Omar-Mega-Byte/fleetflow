FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS security-libraries
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
