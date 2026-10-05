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
    void phase2RowsReceiveHonestLifecycleBaselinesOnUpgrade() {
        try (PostgreSQLContainer db = new PostgreSQLContainer("postgres:17.11-alpine3.24")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            Flyway phase2 = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target("2").load();
            assertThat(phase2.migrate().migrationsExecuted).isEqualTo(2);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            UUID actor = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            UUID merchant = UUID.randomUUID();
            UUID branch = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            UUID driver = UUID.randomUUID();
            jdbc.update("insert into actors (id, issuer, subject, created_at, updated_at) "
                    + "values (?, 'issuer', 'legacy', now(), now())", actor);
            jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                    + "values (?, ?, 'Legacy', now(), now())", customer, actor);
            jdbc.update("insert into merchants (id, name, created_at, updated_at) "
                    + "values (?, 'Legacy', now(), now())", merchant);
            jdbc.update("""
                    insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                        pickup_latitude, pickup_longitude, created_at, updated_at)
                    values (?, ?, 'Legacy', 'Street', 'Cairo', 30, 31, now(), now())
                    """, branch, merchant);
            jdbc.update("insert into drivers (id, actor_id, created_at, updated_at) "
                    + "values (?, ?, now(), now())", driver, actor);
            jdbc.update("""
                    insert into orders (id, customer_id, merchant_branch_id, customer_name_snapshot,
                        merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city,
                        pickup_latitude, pickup_longitude, delivery_line1, delivery_city,
                        delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at)
                    values (?, ?, ?, 'Legacy', 'Legacy', 'Legacy', 'Street', 'Cairo', 30, 31,
                        'Road', 'Cairo', 30, 31, 'EGP', 1.00, now(), now())
                    """, order, customer, branch);

            Flyway upgraded = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, order)).isZero();
            assertThat(jdbc.queryForObject("select operational_status from drivers where id = ?", String.class, driver))
                    .isEqualTo("OFFLINE");
            assertThat(jdbc.queryForObject("select action from order_lifecycle_history where order_id = ?", String.class, order))
                    .isEqualTo("LEGACY_BACKFILL");
            assertThat(jdbc.queryForObject("select action from driver_lifecycle_history where driver_id = ?", String.class, driver))
                    .isEqualTo("LEGACY_BACKFILL");
            assertThat(jdbc.queryForObject("select actor_id from order_lifecycle_history where order_id = ?", UUID.class, order))
                    .isNull();
            assertThatThrownBy(() -> jdbc.update("update order_lifecycle_history set action = 'FAKE' where order_id = ?", order))
                    .hasMessageContaining("Lifecycle history is append-only");
            assertThatThrownBy(() -> jdbc.update("update orders set status = 'DELIVERED' where id = ?", order))
                    .hasMessageContaining("ck_orders_status");
            assertThat(jdbc.queryForObject("select count(*) from deliveries", Integer.class)).isZero();
        }
    }

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
