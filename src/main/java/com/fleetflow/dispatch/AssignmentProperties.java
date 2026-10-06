package com.fleetflow.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetflow.assignment")
public record AssignmentProperties(Duration offerTtl, Duration maxPreflightAge) {
    public AssignmentProperties {
        if (offerTtl == null || offerTtl.compareTo(Duration.ofSeconds(5)) < 0
                || offerTtl.compareTo(Duration.ofMinutes(5)) > 0
                || maxPreflightAge == null || maxPreflightAge.isZero()
                || maxPreflightAge.isNegative() || maxPreflightAge.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Invalid assignment configuration");
        }
    }
}
