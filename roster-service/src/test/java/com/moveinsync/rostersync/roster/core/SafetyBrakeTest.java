package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafetyBrakeTest {

    @Test
    void emptyBaselineNeverTrips() {
        assertThat(SafetyBrake.tripped(0, 0, 3000)).isFalse();
        assertThat(SafetyBrake.tripped(0, 50, 3000)).isFalse();
    }

    @Test
    void defaultThresholdBoundary() {
        assertThat(SafetyBrake.tripped(1000, 299, SafetyBrake.DEFAULT_THRESHOLD_BP)).isFalse();
        assertThat(SafetyBrake.tripped(1000, 300, SafetyBrake.DEFAULT_THRESHOLD_BP)).isTrue();
        assertThat(SafetyBrake.tripped(1000, 400, SafetyBrake.DEFAULT_THRESHOLD_BP)).isTrue();
    }

    @Test
    void thresholdIsConfigurable() {
        assertThat(SafetyBrake.tripped(1000, 249, 2500)).isFalse();
        assertThat(SafetyBrake.tripped(1000, 250, 2500)).isTrue();
        assertThat(SafetyBrake.tripped(1000, 299, 2500)).isTrue();
    }

    @Test
    void briefExampleCutAt120kOf200k() {
        assertThat(SafetyBrake.tripped(200_000, 80_000, 3000)).isTrue();
        assertThat(SafetyBrake.ratioBp(200_000, 80_000)).isEqualTo(4000);
    }

    @Test
    void noOverflowAtLargeCounts() {
        assertThat(SafetyBrake.tripped(Integer.MAX_VALUE, Integer.MAX_VALUE / 2, 3000)).isTrue();
        assertThat(SafetyBrake.ratioBp(0, 10)).isZero();
    }
}
