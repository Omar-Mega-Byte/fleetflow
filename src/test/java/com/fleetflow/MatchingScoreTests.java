package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fleetflow.dispatch.MatchingProperties;
import com.fleetflow.dispatch.MatchingScore;
import org.junit.jupiter.api.Test;

class MatchingScoreTests {
    private final MatchingProperties config = new MatchingProperties(5000, 50,
            new BigDecimal("0.80"), new BigDecimal("0.20"), new BigDecimal("8.33"));

    @Test
    void normalizedFactorsClampingPrecisionAndEstimate() {
        var best = MatchingScore.score(BigDecimal.ZERO, 0, 5000, 45_000, config);
        assertThat(best.distanceFactor()).isEqualByComparingTo("1.000000");
        assertThat(best.freshnessFactor()).isEqualByComparingTo("1.000000");
        assertThat(best.score()).isEqualByComparingTo("1.000000");
        assertThat(best.straightLineSeconds()).isZero();
        var edge = MatchingScore.score(new BigDecimal("5000.000000"), 45_000, 5000, 45_000, config);
        assertThat(edge.score()).isEqualByComparingTo("0.000000");
        assertThat(edge.straightLineSeconds()).isEqualTo(601);
        var over = MatchingScore.score(new BigDecimal("6000.000000"), 50_000, 5000, 45_000, config);
        assertThat(over.score()).isEqualByComparingTo("0.000000");
        assertThat(over.straightLineSeconds()).isEqualTo(601);
        var near = MatchingScore.score(new BigDecimal("8.330001"), 1, 5000, 45_000, config);
        assertThat(near.straightLineSeconds()).isEqualTo(2);
        assertThat(near.score().scale()).isEqualTo(6);
    }

    @Test
    void invalidConfigurationAndInputsFail() {
        assertThatThrownBy(() -> new MatchingProperties(0, 50, new BigDecimal("0.8"),
                new BigDecimal("0.2"), BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingProperties(5000, 50, new BigDecimal("0.9"),
                new BigDecimal("0.2"), BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingProperties(5000, 50, new BigDecimal("-0.1"),
                new BigDecimal("1.1"), BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingProperties(5000, 50, new BigDecimal("0.8"),
                new BigDecimal("0.2"), BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingProperties(5000, 50, new BigDecimal("0.80001"),
                new BigDecimal("0.19999"), BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingProperties(5000, 50, new BigDecimal("0.8"),
                new BigDecimal("0.2"), new BigDecimal("8.3333")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchingScore.score(BigDecimal.ONE, -1, 5000, 45_000, config))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversedInputCannotChangeScoreDistanceAndUuidTieBreak() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var score = MatchingScore.score(new BigDecimal("100.000000"), 1000, 5000, 45_000, config);
        var a = new MatchingScore.Rankable(high, new BigDecimal("100.000000"), 1000, score);
        var b = new MatchingScore.Rankable(low, new BigDecimal("100.000000"), 1000, score);
        assertThat(MatchingScore.ranked(List.of(a, b))).extracting(MatchingScore.Rankable::driverId)
                .containsExactly(low, high);
        assertThat(MatchingScore.ranked(List.of(b, a))).extracting(MatchingScore.Rankable::driverId)
                .containsExactly(low, high);
        UUID highBit = UUID.fromString("80000000-0000-0000-0000-000000000000");
        var c = new MatchingScore.Rankable(highBit, new BigDecimal("100.000000"), 1000, score);
        assertThat(MatchingScore.ranked(List.of(c, a, b))).extracting(MatchingScore.Rankable::driverId)
                .containsExactly(low, high, highBit);
        var closer = new MatchingScore.Rankable(high, new BigDecimal("90.000000"), 1000, score);
        assertThat(MatchingScore.ranked(List.of(b, closer))).extracting(MatchingScore.Rankable::driverId)
                .containsExactly(high, low);
    }
}
