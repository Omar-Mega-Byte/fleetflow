package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.time.Duration;
import java.net.ServerSocket;
import java.io.IOException;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseOutageTests {
    private static final PostgreSQLContainer postgres = restartableDatabase();

    private static PostgreSQLContainer restartableDatabase() {
        final int port;
        try (var reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        catch (IOException failure) { throw new IllegalStateException("No local test port is available", failure); }
        // Keep the disposable database endpoint stable across Docker stop/start.
        return PostgisTestDatabase.create().withCreateContainerCmdModifier(command ->
                command.getHostConfig().withPortBindings(new PortBinding(
                        Ports.Binding.bindIpAndPort("127.0.0.1", port), ExposedPort.tcp(5432))));
    }

    static { postgres.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void postgresLossIsCorrelatedAndReadinessRecoversWithoutAnApplicationRestart() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor);
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Outage customer', now(), now())", customer, actor);
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        String token = "Bearer " + TestOidc.token(subject);
        mvc.perform(get("/api/v1/orders").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        var docker = DockerClientFactory.instance().client();
        docker.stopContainerCmd(postgres.getContainerId()).withTimeout(5).exec();
        try {
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
            String body = mvc.perform(get("/api/v1/orders").header("Authorization", token)
                            .header("X-Correlation-ID", "postgres-outage"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.correlationId").value("postgres-outage"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(token, subject, "PSQLException", "stackTrace");
        } finally {
            docker.startContainerCmd(postgres.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
            mvc.perform(get("/api/v1/orders").header("Authorization", token)).andExpect(status().isOk());
        });
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        assertThat(jdbc.queryForObject("select count(*) from customers where id = ?", Integer.class, customer)).isEqualTo(1);
    }
}
