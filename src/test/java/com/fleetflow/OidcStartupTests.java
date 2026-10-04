package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetflow.security.OidcDecoderConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OidcStartupTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OidcDecoderConfiguration.class);

    @Test
    void everyProfileFailsClosedWhenIssuerAudienceOrJwksIsMissing() {
        for (String profile : new String[] {"local", "test", "default", "prod"}) {
            var configured = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
            configured.withPropertyValues("spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks",
                            "fleetflow.security.audience=fleetflow-api")
                    .run(context -> assertThat(context).hasFailed());
            configured.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/realms/fleetflow",
                            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks")
                    .run(context -> assertThat(context).hasFailed());
            configured.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/realms/fleetflow",
                            "fleetflow.security.audience=fleetflow-api")
                    .run(context -> assertThat(context).hasFailed());
            configured.withPropertyValues("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost/realms/fleetflow",
                            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/jwks",
                            "fleetflow.security.audience=fleetflow-api")
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }
}
