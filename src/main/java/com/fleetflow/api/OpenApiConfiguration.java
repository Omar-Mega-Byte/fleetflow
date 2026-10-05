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
                                + "and order creation with caller-submitted prices; default/prod also expose "
                                + "authenticated driver self actions. Use a Keycloak access token with audience fleetflow-api. "
                                + "FleetFlow roles are provisioned in PostgreSQL, not inferred from token claims."))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("OIDC access token; issuer, audience and signature are validated. "
                                        + "The actor must be active and have the required local role.")));
    }

    @Bean
    OpenApiCustomizer commonErrorResponses() {
        return api -> {
            api.getComponents().addSchemas("ApiProblem", problemSchema());
            api.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
                var responses = operation.getResponses();
                responses.addApiResponse("401", problem("Missing or invalid bearer token (UNAUTHENTICATED)."));
                responses.addApiResponse("403", problem("Actor missing, inactive, or lacking the required role (FORBIDDEN)."));
                responses.addApiResponse("503", problem("Required database or Redis service unavailable."));
                if (method != PathItem.HttpMethod.GET || !path.endsWith("/me")) {
                    responses.addApiResponse("400", problem("Malformed input or unsupported filter (INVALID_REQUEST)."));
                }
                if (method == PathItem.HttpMethod.GET
                        || (method == PathItem.HttpMethod.POST && !path.equals("/api/v1/actors")
                        && !path.equals("/api/v1/merchants"))) {
                    responses.addApiResponse("404", problem("Requested or referenced resource is unavailable."));
                }
                if (method == PathItem.HttpMethod.POST) {
                    responses.addApiResponse("409", problem("Conflict with current or stored state (DATA_CONFLICT)."));
                    if (path.equals("/api/v1/drivers/me/location")
                            || path.equals("/api/v1/drivers/me/heartbeat")) {
                        responses.addApiResponse("429", problem("Driver update rate exceeded."));
                    }
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
