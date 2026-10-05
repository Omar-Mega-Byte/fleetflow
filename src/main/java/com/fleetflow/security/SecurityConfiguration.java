package com.fleetflow.security;

import com.fleetflow.api.SecurityProblemWriter;
import com.fleetflow.identity.ActorResolver;
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
                                            SecurityProblemWriter errors, Environment environment) throws Exception {
        Set<String> profiles = Set.of(environment.getActiveProfiles());
        boolean localFixtures = profiles.equals(Set.of("local")) || profiles.equals(Set.of("test"));
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error")
                            .permitAll();
                    if (localFixtures) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/orders", "/api/v1/orders/*")
                            .hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/merchant-branches/*/orders")
                            .hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/merchant/orders/*/confirm",
                            "/api/v1/merchant/orders/*/prepare", "/api/v1/merchant/orders/*/ready")
                            .hasRole("MERCHANT");
                    auth.requestMatchers(HttpMethod.POST, "/api/v1/dispatch/orders/*/match")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/dispatch/orders/*/attempts")
                            .hasRole("DISPATCHER");
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/drivers/me").hasRole("DRIVER");
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
                .addFilterAfter(new ActorAuthenticationFilter(resolver, errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
