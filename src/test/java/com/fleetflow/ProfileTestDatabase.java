package com.fleetflow;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

abstract class ProfileTestDatabase {
    private static final PostgreSQLContainer postgres = PostgisTestDatabase.create();

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
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.idle-timeout", () -> 10000);
    }
}
