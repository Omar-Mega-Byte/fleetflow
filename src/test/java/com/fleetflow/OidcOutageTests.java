package com.fleetflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"fleetflow.outbox.enabled=false", "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false"})
@ActiveProfiles("test")
class OidcOutageTests extends ProfileTestDatabase {
    private static final AtomicBoolean unavailable = new AtomicBoolean(true);
    private static final HttpServer jwks;
    static {
        try {
            byte[] keys;
            try (var stream = URI.create(TestOidc.jwksUri()).toURL().openStream()) { keys = stream.readAllBytes(); }
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/jwks", exchange -> {
                byte[] response = unavailable.get() ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8) : keys;
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(unavailable.get() ? 503 : 200, response.length);
                try (var body = exchange.getResponseBody()) { body.write(response); }
            });
            jwks.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @DynamicPropertySource
    static void jwksProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stop() { jwks.stop(0); }
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void uncachedKeysFailClosedCachedTokensRemainValidAndCurrentRolesStillApply() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
        UUID actor = UUID.randomUUID();
        String subject = "jwks-outage-" + actor;
        jdbc.update("insert into actors(id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles(actor_id, role) values (?, 'CUSTOMER'), (?, 'DRIVER')", actor, actor);
        String token = "Bearer " + TestOidc.token(subject);
        mvc.perform(get("/api/v1/session").header("Authorization", token)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));
        unavailable.set(false);
        mvc.perform(get("/api/v1/session").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER")).andExpect(jsonPath("$.roles[1]").value("DRIVER"));
        unavailable.set(true);
        mvc.perform(get("/api/v1/session").header("Authorization", token)).andExpect(status().isOk());
        String unknownKey = TestOidc.token(subject, TestOidc.ISSUER, TestOidc.AUDIENCE,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), TestOidc.trustedKey(), "unknown-key");
        mvc.perform(get("/api/v1/session").header("Authorization", "Bearer " + unknownKey)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));
        jdbc.update("delete from actor_roles where actor_id = ?", actor);
        mvc.perform(get("/api/v1/session").header("Authorization", token)).andExpect(status().isForbidden());
        jdbc.update("update actors set active = false where id = ?", actor);
        mvc.perform(get("/api/v1/session").header("Authorization", token)).andExpect(status().isForbidden());
    }
}
