package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class Phase10UpgradeRestoreTests {
    @Test
    void phase11DataBackedUpOnPlainPostgresRestoresAndUpgradesOnPostgis() throws Exception {
        try (PostgreSQLContainer old = new PostgreSQLContainer("postgres:17.11-alpine3.24");
             PostgreSQLContainer current = PostgisTestDatabase.create()) {
            old.start();
            var oldSource = new DriverManagerDataSource(old.getJdbcUrl(), old.getUsername(), old.getPassword());
            assertThat(Flyway.configure().dataSource(oldSource).locations("classpath:db/migration")
                    .target("11").load().migrate().migrationsExecuted).isEqualTo(11);
            JdbcTemplate before = new JdbcTemplate(oldSource);
            UUID actor = UUID.randomUUID(), customer = UUID.randomUUID(), merchant = UUID.randomUUID();
            UUID branch = UUID.randomUUID(), order = UUID.randomUUID();
            before.update("insert into actors (id, issuer, subject, created_at, updated_at) "
                    + "values (?, 'issuer', 'restore-customer', now(), now())", actor);
            before.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                    + "values (?, ?, 'Customer', now(), now())", customer, actor);
            before.update("insert into merchants (id, name, created_at, updated_at) "
                    + "values (?, 'Merchant', now(), now())", merchant);
            before.update("""
                    insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                      pickup_latitude, pickup_longitude, created_at, updated_at)
                    values (?, ?, 'Branch', 'Street', 'Cairo', 30, 31, now(), now())
                    """, branch, merchant);
            before.update("""
                    insert into orders (id, customer_id, merchant_branch_id, customer_name_snapshot,
                      merchant_name_snapshot, branch_name_snapshot, pickup_line1, pickup_city,
                      pickup_latitude, pickup_longitude, delivery_line1, delivery_city,
                      delivery_latitude, delivery_longitude, currency, total_amount, created_at, updated_at)
                    values (?, ?, ?, 'Customer', 'Merchant', 'Branch', 'Street', 'Cairo', 30, 31,
                      'Road', 'Cairo', 30.2, 31.2, 'EGP', 1.00, now(), now())
                    """, order, customer, branch);
            var dump = old.execInContainer("pg_dump", "--data-only", "--inserts",
                    "--exclude-table=public.flyway_schema_history", "-U", old.getUsername(), "-d", old.getDatabaseName());
            assertThat(dump.getExitCode()).isZero();
            assertThat(dump.getStdout()).contains(order.toString());

            current.start();
            var source = new DriverManagerDataSource(current.getJdbcUrl(),
                    current.getUsername(), current.getPassword());
            Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .target("11").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
            var dumpFile = Files.createTempFile("phase10-restore-", ".sql");
            try {
                Files.writeString(dumpFile, dump.getStdout(), StandardCharsets.UTF_8);
                current.copyFileToContainer(MountableFile.forHostPath(dumpFile),
                        "/tmp/phase10-restore.sql");
            } finally {
                Files.deleteIfExists(dumpFile);
            }
            var restore = current.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U",
                    current.getUsername(), "-d", current.getDatabaseName(), "-f", "/tmp/phase10-restore.sql");
            assertThat(restore.getExitCode()).withFailMessage(restore.getStderr()).isZero();
            assertThat(Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .load().migrate().migrationsExecuted).isEqualTo(1);

            JdbcTemplate after = new JdbcTemplate(source);
            assertThat(after.queryForObject("select merchant_branch_id from orders where id = ?",
                    UUID.class, order)).isEqualTo(branch);
            assertThat(after.queryForObject("select serviceability_mode from merchant_branches where id = ?",
                    String.class, branch)).isEqualTo("UNRESTRICTED");
            assertThat(after.queryForObject("select count(*) from delivery_zones", Integer.class)).isZero();
            assertThat(after.queryForObject("select postgis_full_version()", String.class))
                    .contains("POSTGIS");
        }
    }
}
