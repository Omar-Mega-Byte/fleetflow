package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalApiBoundaryTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalApiBoundary.class);

    @Test
    void loopbackLocalProfileStarts() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("server.address=127.0.0.1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void publicLocalBindingFailsClosed() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("server.address=0.0.0.0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localPlusProdFailsClosed() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
                .withPropertyValues("server.address=127.0.0.1")
                .run(context -> assertThat(context).hasFailed());
    }
}
