package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Spec §17.1 says "range 0..1023", which contradicts §7.3 and DEC-21 (65,536 buckets); the spec's
 * own data model wins (see docs/known-issues.md): buckets span 0..65535.
 */
class BucketerTest {

    @Test
    void rangeIs0To65535() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 1; i <= 200_000; i++) {
            int b = Bucketer.bucket("EMP-%06d".formatted(i));
            assertThat(b).isBetween(0, 65_535);
            seen.add(b);
        }
        assertThat(Bucketer.BUCKETS).isEqualTo(65_536);
        // 200k ids over 65,536 buckets: nearly every bucket is hit (expected ~95%).
        assertThat(seen.size()).isGreaterThan(60_000);
    }

    @Test
    void goldenValuesAreStable() {
        // Derived with a separate Python implementation: (sha256(id)[0] << 8) | sha256(id)[1].
        assertThat(Bucketer.bucket("EMP-001")).isEqualTo(1408);
        assertThat(Bucketer.bucket("EMP-042")).isEqualTo(32884);
        assertThat(Bucketer.bucket("EMP-000001")).isEqualTo(18170);
        assertThat(Bucketer.bucket("EMP-200000")).isEqualTo(60201);
    }
}
