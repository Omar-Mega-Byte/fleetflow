package com.fleetflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetflow.location.Wgs84Distance;
import org.junit.jupiter.api.Test;

class Wgs84DistanceTests {
    @Test
    void equatorialDegreeAndSymmetryUseEllipsoid() {
        assertThat(Wgs84Distance.metres(0, 0, 0, 1)).isCloseTo(111_319.490793,
                org.assertj.core.data.Offset.offset(0.001));
        assertThat(Wgs84Distance.metres(0, 1, 0, 0)).isCloseTo(111_319.490793,
                org.assertj.core.data.Offset.offset(0.001));
        assertThat(Wgs84Distance.metres(30, 31, 30, 31)).isZero();
    }
}
