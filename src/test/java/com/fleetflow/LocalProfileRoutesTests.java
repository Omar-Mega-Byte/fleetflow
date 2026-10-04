package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

    @Test
    void loopbackLocalRegistersPhase1Routes() {
        assertThat(mappings.getHandlerMethods().keySet().stream().map(Object::toString))
                .anyMatch(path -> path.contains("/api/v1/orders"));
    }

    @Test
    void localOnboardingAcceptsFixtureOnLoopback() throws Exception {
        String body = "{\"issuer\":\"local-fixture\",\"subject\":\"%s\"}"
                .formatted(UUID.randomUUID());
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/actors").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
