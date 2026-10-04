package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import jakarta.servlet.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@ActiveProfiles("local")
class LocalProfileRoutesTests extends ProfileTestDatabase {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void loopbackLocalRegistersPhase1Routes() {
        assertThat(mappings.getHandlerMethods().keySet().stream().map(Object::toString))
                .anyMatch(path -> path.contains("/api/v1/orders"));
    }

    @Test
    void localOnboardingAcceptsFixtureOnLoopback() throws Exception {
        UUID admin = UUID.randomUUID();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, 'local-route-admin', true, now(), now())", admin, TestOidc.ISSUER);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'ADMIN')", admin);
        String body = "{\"issuer\":\"local-fixture\",\"subject\":\"%s\"}"
                .formatted(UUID.randomUUID());
        MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build()
                .perform(post("/api/v1/actors").header("Authorization", "Bearer " + TestOidc.token("local-route-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
