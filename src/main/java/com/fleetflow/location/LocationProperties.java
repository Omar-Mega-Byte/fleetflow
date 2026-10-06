package com.fleetflow.location;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetflow.location")
public record LocationProperties(Duration heartbeatTtl, Duration locationTtl, Duration maxGpsAge,
                                 Duration maxIngestAge, Duration futureSkew, int maxRadiusMeters,
                                 int maxResults, int overfetchFactor, int overfetchCap,
                                 int updateLimit, Duration updateWindow, int heartbeatLimit,
                                 Duration heartbeatWindow) {
    public LocationProperties {
        if (heartbeatTtl == null || heartbeatTtl.isNegative() || heartbeatTtl.isZero()
                || locationTtl == null || locationTtl.isNegative() || locationTtl.isZero()
                || maxGpsAge == null || maxGpsAge.isNegative() || maxGpsAge.isZero()
                || maxIngestAge == null || maxIngestAge.isNegative() || maxIngestAge.isZero()
                || futureSkew == null || futureSkew.isNegative()
                || maxRadiusMeters < 1 || maxRadiusMeters > 100_000
                || maxResults < 1 || maxResults > 100
                || overfetchFactor < 1 || overfetchFactor > 10
                || overfetchCap < maxResults || overfetchCap > 1000
                || updateLimit < 1 || updateLimit > 1000
                || updateWindow == null || updateWindow.isNegative() || updateWindow.isZero()
                || heartbeatLimit < 1 || heartbeatLimit > 1000
                || heartbeatWindow == null || heartbeatWindow.isNegative() || heartbeatWindow.isZero()) {
            throw new IllegalArgumentException("Invalid location configuration");
        }
    }
}
