package com.fleetflow;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

final class PostgisTestDatabase {
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "postgis/postgis:17-3.5-alpine@sha256:894f570c0cf0664ed5576a8fd5d5bfb8fb1b19d592885b686c3a88c8bd90c41f")
            .asCompatibleSubstituteFor("postgres");

    private PostgisTestDatabase() { }

    static PostgreSQLContainer create() {
        return new PostgreSQLContainer(IMAGE);
    }
}
