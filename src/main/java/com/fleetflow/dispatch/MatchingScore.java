package com.fleetflow.dispatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pure scoring contract. Saved distance has six decimals; factors and score have six. HALF_UP throughout. */
public final class MatchingScore {
    private MatchingScore() { }

    public static Result score(BigDecimal distanceMeters, long gpsAgeMillis, int radiusMeters,
                               long maxGpsAgeMillis, MatchingProperties config) {
        if (distanceMeters == null || distanceMeters.signum() < 0 || gpsAgeMillis < 0
                || maxGpsAgeMillis <= 0 || radiusMeters <= 0) throw new IllegalArgumentException("Invalid score inputs");
        BigDecimal distanceFactor = factor(distanceMeters, BigDecimal.valueOf(radiusMeters));
        BigDecimal freshnessFactor = factor(BigDecimal.valueOf(gpsAgeMillis),
                BigDecimal.valueOf(maxGpsAgeMillis));
        BigDecimal value = distanceFactor.multiply(config.distanceWeight())
                .add(freshnessFactor.multiply(config.freshnessWeight()))
                .setScale(6, RoundingMode.HALF_UP);
        int estimate = distanceMeters.min(BigDecimal.valueOf(radiusMeters))
                .divide(config.metresPerSecond(), 0, RoundingMode.CEILING).intValueExact();
        return new Result(distanceFactor, freshnessFactor, value, estimate);
    }

    private static BigDecimal factor(BigDecimal used, BigDecimal maximum) {
        BigDecimal bounded = used.min(maximum).max(BigDecimal.ZERO);
        return BigDecimal.ONE.subtract(bounded.divide(maximum, 12, RoundingMode.HALF_UP))
                .setScale(6, RoundingMode.HALF_UP);
    }

    public static List<Rankable> ranked(List<Rankable> candidates) {
        return candidates.stream().sorted(Comparator.comparing((Rankable c) -> c.result().score()).reversed()
                .thenComparing(Rankable::distanceMeters).thenComparing(c -> c.driverId().toString())).toList();
    }

    public record Result(BigDecimal distanceFactor, BigDecimal freshnessFactor,
                         BigDecimal score, int straightLineSeconds) { }
    public record Rankable(UUID driverId, BigDecimal distanceMeters, long gpsAgeMillis, Result result) { }
}
