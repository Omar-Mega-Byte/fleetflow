package com.fleetflow.dispatch;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetflow.matching")
public record MatchingProperties(int radiusMeters, int candidateLimit, BigDecimal distanceWeight,
                                 BigDecimal freshnessWeight, BigDecimal metresPerSecond) {
    public MatchingProperties {
        if (radiusMeters < 1 || radiusMeters > 20_000 || candidateLimit < 1 || candidateLimit > 50
                || distanceWeight == null || freshnessWeight == null || metresPerSecond == null
                || distanceWeight.signum() < 0 || freshnessWeight.signum() < 0
                || distanceWeight.add(freshnessWeight).compareTo(BigDecimal.ONE) != 0
                || distanceWeight.stripTrailingZeros().scale() > 4
                || freshnessWeight.stripTrailingZeros().scale() > 4
                || metresPerSecond.stripTrailingZeros().scale() > 3
                || metresPerSecond.signum() <= 0 || metresPerSecond.compareTo(BigDecimal.valueOf(100)) > 0
                || BigDecimal.valueOf(radiusMeters).divide(metresPerSecond, 0,
                    java.math.RoundingMode.CEILING).compareTo(BigDecimal.valueOf(86_400)) > 0) {
            throw new IllegalArgumentException("Invalid matching configuration");
        }
    }
}
