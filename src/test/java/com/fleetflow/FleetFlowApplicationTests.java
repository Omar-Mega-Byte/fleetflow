package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

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
    }

    @Autowired
    DataSource dataSource;

    @Autowired
    Flyway flyway;

    @Test
    void contextConnectsToPostgresWithoutInventingSchema() {
        assertThat(new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(flyway.info().applied()).isEmpty();
    }
}
