package com.fleetflow;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class ProfileTestDatabase {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine3.24");

    static { postgres.start(); }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
        registry.add("fleetflow.recovery.enabled", () -> false);
    }
}
