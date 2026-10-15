package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.fleetflow.api.SecurityProblemWriter;
import com.fleetflow.identity.ActorResolver;
import com.fleetflow.security.ActorAuthenticationFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class Phase2SecurityTests extends ProfileTestDatabase {
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
    void csrfRemainsEnabledOutsideBearerOnlyApiRoutes() throws Exception {
        mvc.perform(post("/error"))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtValidationRejectsUntrustedOrOutOfTimeTokens() throws Exception {
        Actor customer = actor("CUSTOMER");
        customer(customer);
        mvc.perform(get("/api/v1/orders").header("X-Correlation-ID", "jwt-negative"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Correlation-ID", "jwt-negative"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value("jwt-negative"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders"));
        jwtDenied("not-a-jwt");
        jwtDenied(TestOidc.token(customer.subject, "http://wrong-issuer", TestOidc.AUDIENCE,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), TestOidc.trustedKey()));
        jwtDenied(TestOidc.token(customer.subject, TestOidc.ISSUER, "wrong-audience",
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), TestOidc.trustedKey()));
        jwtDenied(TestOidc.token(customer.subject, TestOidc.ISSUER, TestOidc.AUDIENCE,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(1), TestOidc.trustedKey()));
        jwtDenied(TestOidc.token(customer.subject, TestOidc.ISSUER, TestOidc.AUDIENCE,
                Instant.now().plusSeconds(120), Instant.now().plusSeconds(600), TestOidc.trustedKey()));
        jwtDenied(TestOidc.token(customer.subject, TestOidc.ISSUER, TestOidc.AUDIENCE,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), TestOidc.untrustedKey()));
        jwtDenied(TestOidc.token(customer.subject, TestOidc.ISSUER, TestOidc.AUDIENCE,
                Instant.now().minusSeconds(10), Instant.now().plusSeconds(600), TestOidc.untrustedKey(), "unknown-kid"));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(customer)))
                .andExpect(status().isOk());
    }

    @Test
    void actorLookupAndRolesAreExplicitAndRevocable() throws Exception {
        int before = jdbc.queryForObject("select count(*) from actors", Integer.class);
        mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + TestOidc.token("absent-actor")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("select count(*) from actors", Integer.class)).isEqualTo(before);

        Actor unprovisioned = actor();
        customer(unprovisioned);
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(unprovisioned)))
                .andExpect(status().isForbidden());
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'CUSTOMER')", unprovisioned.id);
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(unprovisioned)))
                .andExpect(status().isOk());
        jdbc.update("delete from actor_roles where actor_id = ?", unprovisioned.id);
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(unprovisioned)))
                .andExpect(status().isForbidden());
        jdbc.update("update actors set active = false where id = ?", unprovisioned.id);
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(unprovisioned)))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", UUID.randomUUID(), TestOidc.ISSUER,
                unprovisioned.subject)).hasMessageContaining("uk_actors_issuer_subject");
        assertThatThrownBy(() -> jdbc.update("insert into actor_roles (actor_id, role) values (?, 'OWNER')",
                unprovisioned.id)).hasMessageContaining("ck_actor_roles_role");
    }

    @Test
    void customerOrdersAreScopedInSqlAndSpoofedHeadersCannotSelectOwner() throws Exception {
        Actor a = actor("CUSTOMER");
        Actor b = actor("CUSTOMER");
        customer(a);
        customer(b);
        UUID branch = branch();
        String aOrder = createOrder(a, branch);
        String bOrder = createOrder(b, branch);
        mvc.perform(get("/api/v1/orders/{id}", aOrder).header("Authorization", bearer(a)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(aOrder));
        mvc.perform(get("/api/v1/orders/{id}", bOrder).header("Authorization", bearer(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bOrder));
        mvc.perform(get("/api/v1/orders/{id}", bOrder).header("Authorization", bearer(a))
                        .header("X-Dev-Actor-Id", b.id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders/{id}", aOrder).header("Authorization", bearer(b)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()).header("Authorization", bearer(a)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(a)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(aOrder));
        mvc.perform(get("/api/v1/orders").header("Authorization", bearer(a)).param("customerId", b.id.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void merchantRequiresCurrentExactBranchMembership() throws Exception {
        Actor customer = actor("CUSTOMER");
        customer(customer);
        Actor merchantA = actor("MERCHANT");
        Actor merchantB = actor("MERCHANT");
        UUID branchA = branch();
        UUID branchB = branch();
        String aOrder = createOrder(customer, branchA);
        String bOrder = createOrder(customer, branchB);
        UUID membership = UUID.randomUUID();
        jdbc.update("insert into merchant_staff_memberships (id, actor_id, branch_id, active, created_at) "
                + "values (?, ?, ?, true, now())", membership, merchantA.id, branchA);
        mvc.perform(get("/api/v1/merchant-branches/{id}/orders", branchA)
                        .header("Authorization", bearer(merchantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(aOrder))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/merchant-branches/{id}/orders", branchB)
                        .header("Authorization", bearer(merchantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/merchant-branches/{id}/orders", branchA)
                        .header("Authorization", bearer(merchantB)))
                .andExpect(status().isNotFound());
        jdbc.update("update merchant_staff_memberships set active = false where id = ?", membership);
        mvc.perform(get("/api/v1/merchant-branches/{id}/orders", branchA)
                        .header("Authorization", bearer(merchantA)))
                .andExpect(status().isNotFound());
        jdbc.update("delete from merchant_staff_memberships where id = ?", membership);
        mvc.perform(get("/api/v1/merchant-branches/{id}/orders", branchA)
                        .header("Authorization", bearer(merchantA)))
                .andExpect(status().isNotFound());
        assertThat(bOrder).isNotEqualTo(aOrder);
    }

    @Test
    void driverSelfOnlyAndAdminFixtureRoutes() throws Exception {
        Actor driverA = actor("DRIVER");
        Actor driverB = actor("DRIVER");
        UUID aId = driver(driverA);
        UUID bId = driver(driverB);
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(driverA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(aId.toString()));
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(driverB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(bId.toString()));
        mvc.perform(get("/api/v1/drivers/{id}", bId).header("Authorization", bearer(driverA)))
                .andExpect(status().isForbidden());
        jdbc.update("update drivers set suspended = true, active = false where id = ?", aId);
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer(driverA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.suspended").value(true))
                .andExpect(jsonPath("$.active").value(false));

        Actor admin = actor("ADMIN");
        String body = "{\"issuer\":\"" + TestOidc.ISSUER + "\",\"subject\":\"fixture-child-" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/api/v1/actors").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/actors").header("Authorization", bearer(driverA))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        Actor customer = actor("CUSTOMER");
        mvc.perform(post("/api/v1/actors")
                        .header("Authorization", "Bearer " + TestOidc.claimedAdminToken(customer.subject))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousHealthAndInfoExposeNoBusinessData() throws Exception {
        MvcResult health = mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk()).andReturn();
        assertThat(health.getResponse().getContentAsString()).contains("UP")
                .doesNotContain("db", "password", "issuer");
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/drivers/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void actorDatabaseOutageProducesSafeCorrelated503() throws Exception {
        ActorResolver unavailable = mock(ActorResolver.class);
        when(unavailable.resolve(TestOidc.ISSUER, "outage-subject"))
                .thenThrow(new DataAccessResourceFailureException("sensitive database detail"));
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .claim("iss", TestOidc.ISSUER).claim("sub", "outage-subject").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/orders");
            request.setServletPath("/api/v1/orders");
            request.setAttribute("phase1CorrelationId", "outage-cid");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ActorAuthenticationFilter(unavailable,
                    new SecurityProblemWriter(context.getBean(ObjectMapper.class)))
                    .doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("DATABASE_UNAVAILABLE", "outage-cid")
                    .doesNotContain("sensitive database detail", "test-token", "outage-subject");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void jwtDenied(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(token, "JWT", "signature", "subject", "stackTrace");
    }

    private Actor actor(String... roles) {
        Actor actor = new Actor(UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor.id, TestOidc.ISSUER, actor.subject);
        for (String role : roles) {
            jdbc.update("insert into actor_roles (actor_id, role) values (?, ?)", actor.id, role);
        }
        return actor;
    }

    private UUID customer(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into customers (id, actor_id, display_name, created_at, updated_at) "
                + "values (?, ?, 'Customer', now(), now())", id, actor.id);
        return id;
    }

    private UUID branch() {
        UUID merchant = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        jdbc.update("insert into merchants (id, name, active, created_at, updated_at) "
                + "values (?, 'Merchant', true, now(), now())", merchant);
        jdbc.update("""
                insert into merchant_branches (id, merchant_id, name, pickup_line1, pickup_city,
                    pickup_latitude, pickup_longitude, active, created_at, updated_at)
                values (?, ?, 'Branch', 'Street', 'Cairo', 30.000000, 31.000000, true, now(), now())
                """, branch, merchant);
        return branch;
    }

    private UUID driver(Actor actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into drivers (id, actor_id, active, suspended, created_at, updated_at) "
                + "values (?, ?, true, false, now(), now())", id, actor.id);
        return id;
    }

    private String createOrder(Actor customer, UUID branch) throws Exception {
        String body = """
                {"branchId":"%s","delivery":{"line1":"Road","city":"Cairo",
                "latitude":30.000000,"longitude":31.000000},"currency":"EGP",
                "items":[{"description":"Item","quantity":1,"unitAmount":1.00}]}
                """.formatted(branch);
        MvcResult result = mvc.perform(orderPost(customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String bearer(Actor actor) { return "Bearer " + TestOidc.token(actor.subject); }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder orderPost(Actor actor) {
        return orderPost(actor, UUID.randomUUID().toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder orderPost(Actor actor,
                                                                                                  String key) {
        return post("/api/v1/orders").header("Authorization", bearer(actor))
                .header("Idempotency-Key", key);
    }

    private record Actor(UUID id, String subject) { }
}
