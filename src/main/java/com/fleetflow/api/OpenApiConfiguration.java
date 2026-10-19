package com.fleetflow.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
class OpenApiConfiguration {
    private static final String PROBLEM_SCHEMA = "#/components/schemas/ApiProblem";

    @Bean
    OpenAPI fleetFlowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("FleetFlow API").version("v1")
                        .description("Current business routes. Local/test includes loopback-only ADMIN fixtures "
                                + "and order creation with caller-submitted prices; default/prod register "
                                + "scoped reads, lifecycle, location, matching, assignment, tracking "
                                + "and merchant-scoped delivery zones. "
                                + "Use a Keycloak access token with audience fleetflow-api. "
                                + "A separately provisioned demo account may use X-FleetFlow-Session. "
                                + "FleetFlow roles are provisioned in PostgreSQL, not inferred from token claims."))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .addSecurityItem(new SecurityRequirement().addList("localSession"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("OIDC access token; issuer, audience and signature are validated. "
                                        + "The actor must be active and have the required local role."))
                        .addSecuritySchemes("localSession", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-FleetFlow-Session")
                                .description("Short-lived first-party demo session, held only in browser memory.")));
    }

    @Bean
    OpenApiCustomizer commonErrorResponses() {
        return api -> {
            api.getComponents().addSchemas("ApiProblem", problemSchema());
            api.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
                boolean publicAuth = path.startsWith("/api/v1/auth/");
                if (publicAuth) operation.setSecurity(java.util.List.of());
                var responses = operation.getResponses();
                responses.addApiResponse("401", problem("Missing or invalid bearer token or local session (UNAUTHENTICATED)."));
                responses.addApiResponse("403", problem("Actor missing, inactive, or lacking the required role (FORBIDDEN)."));
                responses.addApiResponse("503", problem("Required database or Redis service unavailable."));
                responses.addApiResponse("429", problem("Per-actor route budget, location/heartbeat window, or active stream cap exceeded; time-window limits include Retry-After."));
                responses.get("429").addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                        .description("Seconds until the next attempt may be admitted").schema(new Schema<>().type("integer")));
                if (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT)
                    responses.addApiResponse("413", problem("Request body exceeds the supported size."));
                if (operation.getParameters() != null) operation.getParameters().stream()
                        .filter(parameter -> "Idempotency-Key".equals(parameter.getName()))
                        .forEach(parameter -> parameter.setSchema(new StringSchema().minLength(1).maxLength(128)
                                .pattern("^[A-Za-z0-9._~-]{1,128}$")));
                if (method != PathItem.HttpMethod.GET || !path.endsWith("/me")) {
                    responses.addApiResponse("400", problem("Malformed input or unsupported filter (INVALID_REQUEST)."));
                }
                if (!publicAuth && (method == PathItem.HttpMethod.GET || method == PathItem.HttpMethod.PUT
                        || (method == PathItem.HttpMethod.POST && !path.equals("/api/v1/actors")
                        && !path.equals("/api/v1/merchants")))) {
                    responses.addApiResponse("404", problem("Requested or referenced resource is unavailable."));
                }
                if (!publicAuth && (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PUT)) {
                    responses.addApiResponse("409", problem(path.contains("/cancel")
                            || path.contains("/confirm") || path.contains("/start-preparation")
                            || path.contains("/ready-for-pickup") || path.contains("/drivers/me/")
                            ? "Invalid, duplicate or competing lifecycle action (LIFECYCLE_CONFLICT)."
                            : "Conflict with current or stored state (DATA_CONFLICT)."));
                }
            }));
        };
    }

    private ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content()
                .addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref(PROBLEM_SCHEMA))));
    }

    private Schema<?> problemSchema() {
        return new ObjectSchema().description("Safe API error with a request correlation ID.")
                .addProperty("code", new StringSchema().example("RESOURCE_NOT_FOUND"))
                .addProperty("message", new StringSchema().example("Resource not found"))
                .addProperty("path", new StringSchema().example("/api/v1/orders/00000000-0000-0000-0000-000000000000"))
                .addProperty("timestamp", new StringSchema().format("date-time"))
                .addProperty("correlationId", new StringSchema().example("request-123"))
                .addProperty("fields", new ArraySchema().items(new StringSchema()));
    }
}
