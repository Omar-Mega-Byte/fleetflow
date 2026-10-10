package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import jakarta.servlet.Filter;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Exercises the security chain for every registered business route, including new routes. */
@SpringBootTest
@ActiveProfiles("test")
class Phase11RouteBoundaryTests extends ProfileTestDatabase {
    private static final Pattern PARAMETER = Pattern.compile("\\{[^}]+}");
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void everyBusinessRouteRejectsAnonymousAndAValidActorWithTheWrongRole() throws Exception {
        String customerSubject = "route-customer-" + UUID.randomUUID();
        String adminSubject = "route-admin-" + UUID.randomUUID();
        String inactiveSubject = "route-inactive-" + UUID.randomUUID();
        createActor(customerSubject, "CUSTOMER");
        createActor(adminSubject, "ADMIN");
        UUID inactive = createActor(inactiveSubject, "CUSTOMER");
        jdbc.update("update actors set active = false where id = ?", inactive);
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var paths = entry.getKey().getPathPatternsCondition();
            if (paths == null) { continue; }
            for (var pattern : paths.getPatterns()) {
                String route = pattern.getPatternString();
                if (!route.startsWith("/api/v1/")) { continue; }
                assertThat(entry.getKey().getMethodsCondition().getMethods())
                        .as("explicit HTTP method for %s", route).isNotEmpty();
                String concrete = PARAMETER.matcher(route).replaceAll(UUID.randomUUID().toString());
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    HttpMethod httpMethod = HttpMethod.valueOf(method.name());
                    int anonymous = mvc.perform(request(httpMethod, concrete)).andReturn().getResponse().getStatus();
                    assertThat(anonymous).as("anonymous %s %s", method, route).isEqualTo(401);
                    String wrongSubject = wrongRoleSubject(route, adminSubject, customerSubject);
                    int wrongRole = mvc.perform(request(httpMethod, concrete)
                            .header("Authorization", "Bearer " + TestOidc.token(wrongSubject)))
                            .andReturn().getResponse().getStatus();
                    assertThat(wrongRole).as("wrong role %s %s", method, route).isEqualTo(403);
                    int disabled = mvc.perform(request(httpMethod, concrete)
                            .header("Authorization", "Bearer " + TestOidc.token(inactiveSubject)))
                            .andReturn().getResponse().getStatus();
                    assertThat(disabled).as("inactive actor %s %s", method, route).isEqualTo(403);
                    checked++;
                }
            }
        }
        System.out.println("Phase 11 signed route boundary checks: " + checked + " operations");
        assertThat(checked).as("registered business operations exercised").isGreaterThanOrEqualTo(40);
    }

    private static String wrongRoleSubject(String route, String admin, String customer) {
        if (route.startsWith("/api/v1/orders")
                || route.startsWith("/api/v1/notifications/")) {
            return admin;
        }
        return customer;
    }

    private UUID createActor(String subject, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", id, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", id, role);
        return id;
    }
}
