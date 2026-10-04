package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
class DefaultProfileRoutesTests extends ProfileTestDatabase {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultRegistersOnlySecuredReadsAndNoOrderWrite() throws Exception {
        var paths = mappings.getHandlerMethods().keySet().stream().map(Object::toString).toList();
        assertThat(paths).anyMatch(path -> path.contains("/api/v1/orders"))
                .noneMatch(path -> path.contains("/api/v1/actors"))
                .noneMatch(path -> path.contains("POST") && path.contains("/api/v1/orders"));
        UUID actor = UUID.randomUUID();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, 'default-reader', true, now(), now())", actor, TestOidc.ISSUER);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", actor);
        MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build()
                .perform(post("/api/v1/orders").header("Authorization", "Bearer " + TestOidc.token("default-reader")))
                .andExpect(status().isForbidden());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'ADMIN')", actor);
        MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build()
                .perform(post("/api/v1/actors").header("Authorization", "Bearer " + TestOidc.token("default-reader")))
                .andExpect(status().isForbidden());
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + TestOidc.token("default-reader")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/swagger-ui/index.html")
                        .header("Authorization", "Bearer " + TestOidc.token("default-reader")))
                .andExpect(status().isForbidden());
    }
}
