package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@ActiveProfiles("prod")
class ProdProfileRoutesTests extends ProfileTestDatabase {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired WebApplicationContext context;

    @Test
    void prodRegistersNoAnonymousBusinessRoutes() throws Exception {
        assertThat(mappings.getHandlerMethods().keySet().stream().map(Object::toString))
                .noneMatch(path -> path.contains("/api/v1/"));
        MockMvcBuilders.webAppContextSetup(context).build().perform(post("/api/v1/orders"))
                .andExpect(status().isNotFound());
    }
}
