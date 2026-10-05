package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("local")
class OpenApiDocsTests extends ProfileTestDatabase {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired ObjectMapper mapper;

    @Test
    void localSwaggerDocumentsEveryRegisteredBusinessOperation() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode document = mapper.readTree(body);

        Set<String> actual = new HashSet<>();
        for (String path : document.path("paths").propertyNames()) {
            for (String method : document.path("paths").path(path).propertyNames()) {
                if (method.equals("get") || method.equals("post")) {
                    actual.add(method.toUpperCase() + " " + path);
                    JsonNode operation = document.path("paths").path(path).path(method);
                    assertThat(operation.path("summary").asText()).isNotBlank();
                    assertThat(operation.path("tags").isEmpty()).isFalse();
                    boolean noContent = method.equals("post") && (path.startsWith("/api/v1/drivers/me/")
                            || path.startsWith("/api/v1/merchant/orders/"));
                    String success = method.equals("get") ? "200" : noContent ? "204" : "201";
                    assertThat(operation.path("responses").has(success))
                            .withFailMessage("Missing success response for %s %s", method, path).isTrue();
                    if (!noContent) {
                        assertThat(operation.path("responses").path(success).path("content")
                                .path("application/json").path("schema").path("$ref").asText())
                                .withFailMessage("Missing success schema for %s %s", method, path).isNotBlank();
                    }
                    if (method.equals("post") && !noContent
                            && !path.equals("/api/v1/dispatch/orders/{orderId}/match")) {
                        assertThat(operation.path("requestBody").path("content")
                                .path("application/json").path("schema").path("$ref").asText()).isNotBlank();
                    }
                    if (path.equals("/api/v1/drivers/me/location")) {
                        assertThat(operation.path("requestBody").path("content")
                                .path("application/json").path("schema").path("$ref").asText()).isNotBlank();
                    }
                    for (String status : Set.of("401", "403", "503")) {
                        assertThat(operation.path("responses").path(status).path("content")
                                .path("application/json").path("schema").path("$ref").asText())
                                .isEqualTo("#/components/schemas/ApiProblem");
                    }
                }
            }
        }

        Set<String> registered = new HashSet<>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> {
            if (mapping.getPathPatternsCondition() != null) {
                for (String path : mapping.getPathPatternsCondition().getPatternValues()) {
                    if (path.startsWith("/api/v1/")) {
                        for (RequestMethod method : mapping.getMethodsCondition().getMethods()) {
                            registered.add(method.name() + " " + path);
                        }
                    }
                }
            }
        });
        assertThat(actual).hasSize(29).containsExactlyInAnyOrderElementsOf(registered);
        assertThat(document.path("paths").has("/actuator/health")).isFalse();
        assertThat(document.path("components").path("securitySchemes").path("bearerAuth")
                .path("scheme").asText()).isEqualTo("bearer");
        assertThat(document.path("security").toString()).contains("bearerAuth");
        assertThat(document.path("paths").path("/api/v1/orders").path("post")
                .path("responses").has("201")).isTrue();
        Set<String> orderFilters = new HashSet<>();
        for (JsonNode parameter : document.path("paths").path("/api/v1/orders").path("get").path("parameters")) {
            orderFilters.add(parameter.path("name").asText());
        }
        assertThat(orderFilters).containsExactlyInAnyOrder("branchId", "status", "from", "to", "page", "size", "sort");
        assertThat(document.path("components").path("schemas").has("ApiProblem"))
                .withFailMessage("Schemas: %s", document.path("components").path("schemas").propertyNames())
                .isTrue();
        assertThat(document.path("components").path("schemas").propertyNames())
                .contains("CustomerOrderPage", "CustomerOrderSummary",
                        "MerchantBranchOrderPage", "MerchantBranchOrderSummary");

        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isOk());
    }
}
