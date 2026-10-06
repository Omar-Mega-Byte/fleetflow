package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.TimeZone;
import javax.sql.DataSource;

import com.fleetflow.identity.IdentityService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
class FleetFlowApplicationTests {

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17.11-alpine3.24");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Autowired
    IdentityService identity;

    @Test
    void contextConnectsToPostgresAndValidatesFirstMigration() {
        assertThat(new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(flyway.info().applied()).hasSize(11);
        assertThat(flyway.info().applied()[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().applied()[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().applied()[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void databaseEnforcesIdentityReferencesAndChecks() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID actor = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        jdbc.update("insert into actors (id, issuer, subject, created_at, updated_at) values (?, 'fixture', ?, now(), now())",
                actor, subject);

        assertThatThrownBy(() -> jdbc.update(
                "insert into actors (id, issuer, subject, created_at, updated_at) values (?, 'fixture', ?, now(), now())",
                UUID.randomUUID(), subject)).hasMessageContaining("uk_actors_issuer_subject");
        assertThatThrownBy(() -> jdbc.update(
                "insert into customers (id, actor_id, display_name, created_at, updated_at) values (?, ?, 'Missing', now(), now())",
                UUID.randomUUID(), UUID.randomUUID())).hasMessageContaining("fk_customers_actor");
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) values (?, ?, 'First', now(), now())",
                UUID.randomUUID(), actor);
        assertThatThrownBy(() -> jdbc.update(
                "insert into customers (id, actor_id, display_name, created_at, updated_at) values (?, ?, 'Second', now(), now())",
                UUID.randomUUID(), actor)).hasMessageContaining("uk_customers_actor");

        UUID merchant = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name, created_at, updated_at) values (?, 'Fixture merchant', now(), now())",
                merchant);
        assertThatThrownBy(() -> jdbc.update("""
                insert into merchant_branches
                    (id, merchant_id, name, pickup_line1, pickup_city, pickup_latitude, pickup_longitude, created_at, updated_at)
                values (?, ?, 'Invalid', 'Street', 'Cairo', 91, 31, now(), now())
                """, UUID.randomUUID(), merchant)).hasMessageContaining("ck_merchant_branches_latitude");
        UUID driver = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, created_at, updated_at) values (?, ?, now(), now())",
                driver, actor);
        assertThatThrownBy(() -> jdbc.update("""
                insert into vehicles (id, driver_id, type, capacity, created_at, updated_at)
                values (?, ?, 'BIKE', 0, now(), now())
                """, UUID.randomUUID(), driver)).hasMessageContaining("ck_vehicles_capacity");
        jdbc.update("insert into vehicles (id, driver_id, type, capacity, created_at, updated_at) values (?, ?, 'BIKE', 1, now(), now())",
                UUID.randomUUID(), driver);
        assertThatThrownBy(() -> jdbc.update("""
                insert into vehicles (id, driver_id, type, capacity, created_at, updated_at)
                values (?, ?, 'CAR', 2, now(), now())
                """, UUID.randomUUID(), driver)).hasMessageContaining("uk_vehicles_driver");
    }

    @Test
    void listIndexExists() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer indexes = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'orders'
                  and indexname = 'idx_orders_customer_created_id'
                """, Integer.class);
        assertThat(indexes).isEqualTo(1);
    }

    @Test
    void instantRoundTripsWithNonUtcJvmZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Instant before = Instant.now();
            UUID actorId = identity.create("fixture-time", UUID.randomUUID().toString()).id();
            Instant after = Instant.now();
            OffsetDateTime stored = new JdbcTemplate(dataSource).queryForObject(
                    "select created_at from actors where id = ?", OffsetDateTime.class, actorId);
            // PostgreSQL timestamptz stores microseconds; the lower nanoseconds can round down.
            assertThat(stored.toInstant()).isBetween(before.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                    after.plus(1, java.time.temporal.ChronoUnit.MICROS));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
