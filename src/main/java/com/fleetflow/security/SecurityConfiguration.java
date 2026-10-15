package com.fleetflow.security;

import com.fleetflow.api.SecurityProblemWriter;
import com.fleetflow.identity.ActorResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ActorResolver resolver,
                                            SecurityProblemWriter errors, Environment environment, MeterRegistry metrics) throws Exception {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        boolean localFixtures = profiles.equals(Set.of("local")) || profiles.equals(Set.of("test"));
        boolean monitoring = profiles.contains("monitoring");
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
                            .permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/*")
                            .hasRole("DISPATCHER");
                    if (monitoring) {
                        // Only the private management port exposes this endpoint in the monitoring profile.
                        auth.requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll();
                    }
                    if (localFixtures) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*")
                            .hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/session")
                            .hasAnyRole("CUSTOMER", "MERCHANT", "DRIVER", "SUPPORT", "DISPATCHER", "ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/operations/outbox/failed")
                            .hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/operations/events/quarantined")
                            .hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/operations/outbox/*/replay")
                            .hasRole("ADMIN");
                    auth.requestMatchers("/api/v1/demo", "/api/v1/demo/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/orders/*/tracking",
                            "/api/v1/orders/*/tracking/stream").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/merchant-branches/*/orders")
                            .hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.GET,
                            "/api/v1/merchant-branches/*/zones",
                            "/api/v1/merchant-branches/*/zone-policy").hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/merchant-branches/*/zones",
                            "/api/v1/merchant-branches/*/serviceability").hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.PUT,
                            "/api/v1/merchant-branches/*/zones/*",
                            "/api/v1/merchant-branches/*/zone-policy").hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/dispatch/orders/*/match")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/dispatch/orders/*/attempts")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/dispatch/orders/*/offers")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/dispatch/orders/*/assignments")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/dispatch/orders/*/retry",
                            "/api/v1/dispatch/orders/*/reassign",
                            "/api/v1/dispatch/orders/*/cancel",
                            "/api/v1/dispatch/orders/*/incidents/fail",
                            "/api/v1/dispatch/orders/*/incidents/*/resolve").hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/dispatch/orders/*/incidents")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/dispatch/analytics/daily")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/notifications/me")
                            .hasAnyRole("CUSTOMER", "DRIVER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/drivers/me/offers").hasRole("DRIVER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/drivers/me/deliveries").hasRole("DRIVER");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/drivers/me/offers/*/accept",
                            "/api/v1/drivers/me/offers/*/reject").hasRole("DRIVER");
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/drivers/me/deliveries/*/pickup",
                            "/api/v1/drivers/me/deliveries/*/start-transit",
                            "/api/v1/drivers/me/deliveries/*/complete",
                            "/api/v1/drivers/me/deliveries/*/fail").hasRole("DRIVER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/drivers/me").hasRole("DRIVER");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/orders/*/cancel")
                            .hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/merchant-branches/*/orders/*/confirm",
                            "/api/v1/merchant-branches/*/orders/*/start-preparation",
                            "/api/v1/merchant-branches/*/orders/*/ready-for-pickup",
                            "/api/v1/merchant-branches/*/orders/*/cancel")
                            .hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/drivers/me/online",
                            "/api/v1/drivers/me/available", "/api/v1/drivers/me/pause",
                            "/api/v1/drivers/me/offline", "/api/v1/drivers/me/location",
                            "/api/v1/drivers/me/heartbeat").hasRole("DRIVER");
                    if (localFixtures) {
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/orders").hasRole("CUSTOMER");
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/actors", "/api/v1/customers",
                                "/api/v1/merchants", "/api/v1/merchant-branches",
                                "/api/v1/merchant-staff-memberships", "/api/v1/drivers", "/api/v1/vehicles")
                                .hasRole("ADMIN");
                        auth.requestMatchers(HttpMethod.GET, "/api/v1/actors/*", "/api/v1/customers/*",
                                "/api/v1/merchants/*", "/api/v1/merchant-branches/*",
                                "/api/v1/drivers/*", "/api/v1/vehicles/*")
                                .hasRole("ADMIN");
                    }
                    auth.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) ->
                                errors.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication required")))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                errors.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication required"))
                        .accessDeniedHandler((request, response, failure) ->
                                errors.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "Access denied")))
                .addFilterBefore(new IdentityAvailabilityFilter(errors, metrics), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new ActorAuthenticationFilter(resolver, errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
