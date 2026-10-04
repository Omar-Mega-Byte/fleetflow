package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest
@ActiveProfiles("test")
class KeycloakProviderTests {
    private static final String ISSUER = "http://localhost:8080/realms/fleetflow";
    private static final String ADMIN_USER = "bootstrap-" + UUID.randomUUID();
    private static final String ADMIN_PASSWORD = UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.11-alpine3.24");
    private static final GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse(
            "quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c"))
            .withExposedPorts(8080)
            .withCopyFileToContainer(MountableFile.forClasspathResource("oidc/fleetflow-realm.json"),
                    "/opt/keycloak/data/import/fleetflow-realm.json")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCommand("start-dev", "--import-realm", "--hostname=http://localhost:8080")
            .waitingFor(Wait.forHttp("/realms/fleetflow/.well-known/openid-configuration")
                    .forPort(8080).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        postgres.start();
        keycloak.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> ISSUER);
        registry.add("OIDC_JWKS_URI", () -> base() + "/realms/fleetflow/protocol/openid-connect/certs");
        registry.add("OIDC_AUDIENCE", () -> "fleetflow-api");
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keycloakIssuedAccessTokenPassesResourceServerOnlyAfterExplicitProvisioning() throws Exception {
        String adminToken = token("master", "admin-cli", ADMIN_USER, ADMIN_PASSWORD);
        String username = "customer-" + UUID.randomUUID();
        String password = UUID.randomUUID() + "-" + UUID.randomUUID();
        String userBody = """
                {"username":"%s","email":"%s@example.invalid","emailVerified":true,
                 "firstName":"Test","lastName":"Customer","enabled":true,"requiredActions":[],
                 "credentials":[{"type":"password","value":"%s","temporary":false}]}
                """.formatted(username, username, password);
        HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create(base() + "/admin/realms/fleetflow/users"))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .POST(HttpRequest.BodyPublishers.ofString(userBody)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);

        String userToken = token("fleetflow", "fleetflow-cli", username, password);
        var claims = SignedJWT.parse(userToken).getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getAudience()).contains("fleetflow-api");
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));

        UUID actor = UUID.randomUUID();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, ISSUER, claims.getSubject());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor);
        UUID customer = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Provider smoke', now(), now())", customer, actor);
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    private static String token(String realm, String client, String username, String password) throws Exception {
        String body = "grant_type=password&client_id=" + encode(client) + "&username=" + encode(username)
                + "&password=" + encode(password);
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        base() + "/realms/" + realm + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("Keycloak token endpoint: %s", response.body())
                .isEqualTo(200);
        Matcher matcher = Pattern.compile("\\\"access_token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String base() { return "http://127.0.0.1:" + keycloak.getMappedPort(8080); }
}
