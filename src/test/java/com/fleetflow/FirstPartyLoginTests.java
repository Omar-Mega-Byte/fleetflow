package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
class FirstPartyLoginTests {
    private static final PostgreSQLContainer postgres = PostgisTestDatabase.create();
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.11-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499")
            .withExposedPorts(6379).withCommand("redis-server", "--save", "", "--appendonly", "no")
            .waitingFor(Wait.forListeningPort());
    static { postgres.start(); redis.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", postgres::getJdbcUrl);
        registry.add("DB_USER", postgres::getUsername);
        registry.add("DB_PASSWORD", postgres::getPassword);
        registry.add("OIDC_ISSUER", () -> TestOidc.ISSUER);
        registry.add("OIDC_JWKS_URI", TestOidc::jwksUri);
        registry.add("OIDC_AUDIENCE", () -> TestOidc.AUDIENCE);
        registry.add("fleetflow.recovery.enabled", () -> false);
        registry.add("REDIS_HOST", redis::getHost);
        registry.add("REDIS_PORT", () -> redis.getMappedPort(6379));
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void passwordLoginUsesServerSideSessionAndExistingActorRoles() throws Exception {
        UUID actor = UUID.randomUUID();
        String email = actor + "@fleetflow.demo";
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, actor.toString());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor);
        jdbc.update("insert into local_login_credentials (actor_id, email, password_hash) values (?, ?, ?)",
                actor, email, new BCryptPasswordEncoder(12).encode("correct-password"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", token)
                .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        jdbc.update("update actors set active = false where id = ?", actor);
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", token))
                .andExpect(status().isUnauthorized());
        jdbc.update("update actors set active = true where id = ?", actor);
        var next = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn();
        String nextToken = JsonPath.read(next.getResponse().getContentAsString(), "$.token");
        jdbc.update("update local_login_credentials set enabled = false where actor_id = ?", actor);
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", nextToken))
                .andExpect(status().isUnauthorized());
        jdbc.update("update local_login_credentials set enabled = true, password_hash = ? where actor_id = ?",
                new BCryptPasswordEncoder(12).encode("rotated-password"), actor);
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", nextToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").header("X-FleetFlow-Session", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicSignupCreatesOnlyCustomerIdentityAndCanSignIn() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String password = "a long customer passphrase";
        String body = "{\"displayName\":\"Alex Morgan\",\"email\":\"" + email.toUpperCase()
                + "\",\"password\":\"" + password + "\",\"role\":\"ADMIN\"}";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        UUID actor = jdbc.queryForObject("select actor_id from local_login_credentials where email = ?",
                UUID.class, email);
        assertThat(jdbc.queryForList("select role from actor_roles where actor_id = ?", String.class, actor))
                .containsExactly("CUSTOMER");
        assertThat(jdbc.queryForObject("select display_name from customers where actor_id = ?", String.class, actor))
                .isEqualTo("Alex Morgan");
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        mvc.perform(get("/api/v1/session").header("X-FleetFlow-Session", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        mvc.perform(get("/api/v1/demo").header("X-FleetFlow-Session", token))
                .andExpect(status().isForbidden());
    }

    @Test
    void signupRejectsShortOrCommonPasswordsWithoutCreatingAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alex Morgan\",\"email\":\"" + email
                        + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from local_login_credentials where email = ?",
                Integer.class, email)).isZero();
    }
}
