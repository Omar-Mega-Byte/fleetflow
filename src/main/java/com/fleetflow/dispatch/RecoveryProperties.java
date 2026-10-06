package com.fleetflow.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetflow.recovery")
public record RecoveryProperties(int batchSize, int sweepIntervalMs) {
    public RecoveryProperties {
        if (batchSize < 1 || batchSize > 100 || sweepIntervalMs < 1000 || sweepIntervalMs > 60000) {
            throw new IllegalArgumentException("Invalid recovery configuration");
        }
    }
}
