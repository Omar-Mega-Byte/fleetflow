package com.fleetflow.security;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class OidcDecoderConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
                          @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwks,
                          @Value("${fleetflow.security.audience:}") String audience) {
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("OIDC issuer, JWKS URI and audience must be configured");
        }
        URI issuerUri = URI.create(issuer);
        URI jwksUri = URI.create(jwks);
        if (!isHttpUri(issuerUri) || !isHttpUri(jwksUri)) {
            throw new IllegalStateException("OIDC issuer and JWKS URI must be absolute HTTP(S) URLs");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(issuer),
                jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null
                        && !jwt.getSubject().isBlank() && jwt.getAudience().contains(audience)
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return decoder;
    }

    private boolean isHttpUri(URI uri) {
        return uri.isAbsolute() && uri.getHost() != null
                && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
    }
}
