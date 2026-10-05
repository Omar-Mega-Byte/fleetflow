package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class DriverLifecycleTests extends ProfileTestDatabase {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void selfTransitionsAreGuardedDurableAndAudited() throws Exception {
        Fixture driver = fixture(true);
        String bearer = bearer(driver);
        mvc.perform(post("/api/v1/drivers/me/available").header("Authorization", bearer))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_STATE_CONFLICT"));
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/available").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/drivers/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/drivers/me/pause").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/offline").header("Authorization", bearer))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, driver.driverId))
                .isEqualTo("OFFLINE");
        assertThat(jdbc.queryForList("select from_status || '>' || to_status from driver_status_history "
                + "where driver_id = ? order by changed_at, id", String.class, driver.driverId))
                .hasSize(4);
    }

    @Test
    void vehicleAndProfileEligibilityAreRequired() throws Exception {
        Fixture driver = fixture(false);
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer(driver)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drivers/me/available").header("Authorization", bearer(driver)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from driver_status_history where driver_id = ?",
                Integer.class, driver.driverId)).isEqualTo(1);
        jdbc.update("update drivers set suspended = true where id = ?", driver.driverId);
        mvc.perform(post("/api/v1/drivers/me/offline").header("Authorization", bearer(driver)))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAuthenticatedSelfDriverCanChangeState() throws Exception {
        Fixture driver = fixture(true);
        Fixture other = fixture(true);
        mvc.perform(post("/api/v1/drivers/me/online"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", "Bearer broken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/drivers/me/online").header("Authorization", bearer(driver))
                .header("X-Dev-Actor-Id", other.actorId))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status from drivers where id = ?", String.class, other.driverId))
                .isEqualTo("OFFLINE");
    }

    private Fixture fixture(boolean vehicle) {
        UUID actor = UUID.randomUUID();
        UUID driver = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        jdbc.update("insert into actors (id, issuer, subject, active, created_at, updated_at) "
                + "values (?, ?, ?, true, now(), now())", actor, TestOidc.ISSUER, subject);
        jdbc.update("insert into actor_roles (actor_id, role) values (?, 'DRIVER')", actor);
        jdbc.update("insert into drivers (id, actor_id, active, suspended, created_at, updated_at) "
                + "values (?, ?, true, false, now(), now())", driver, actor);
        if (vehicle) {
            jdbc.update("insert into vehicles (id, driver_id, type, capacity, active, created_at, updated_at) "
                    + "values (?, ?, 'BIKE', 1, true, now(), now())", UUID.randomUUID(), driver);
        }
        return new Fixture(actor, driver, subject);
    }

    private String bearer(Fixture fixture) { return "Bearer " + TestOidc.token(fixture.subject); }

    private record Fixture(UUID actorId, UUID driverId, String subject) { }
}
