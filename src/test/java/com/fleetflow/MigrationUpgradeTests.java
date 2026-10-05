package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class MigrationUpgradeTests {
    @Test
    void existingPhase1ActorUpgradesWithoutImplicitRoles() {
        try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine3.24")) {
            db.start();
            Flyway phase1 = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").target("1").load();
            assertThat(phase1.migrate().migrationsExecuted).isEqualTo(1);
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),
                    db.getUsername(), db.getPassword()));
            UUID actor = UUID.randomUUID();
            jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                    + "values (?, 'https://issuer.example', 'existing-subject', true, now(), now())", actor);

            Flyway upgraded = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
                    .locations("classpath:db/migration").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select count(*) from actors where id = ?", Integer.class, actor))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from actor_roles", Integer.class)).isZero();
            jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor);
            assertThatThrownBy(() -> jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor))
                    .hasMessageContaining("pk_actor_roles");
        }
    }
}
