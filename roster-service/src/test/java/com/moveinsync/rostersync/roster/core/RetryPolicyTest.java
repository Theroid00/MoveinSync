package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.roster.core.RetryPolicy.Verdict;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void statusClassification() {
        assertThat(RetryPolicy.forStatus(200)).isEqualTo(Verdict.DELIVERED);
        assertThat(RetryPolicy.forStatus(204)).isEqualTo(Verdict.DELIVERED);
        assertThat(RetryPolicy.forStatus(400)).isEqualTo(Verdict.DEAD);
        assertThat(RetryPolicy.forStatus(404)).isEqualTo(Verdict.DEAD);
        assertThat(RetryPolicy.forStatus(422)).isEqualTo(Verdict.DEAD);
        assertThat(RetryPolicy.forStatus(408)).isEqualTo(Verdict.RETRY);
        assertThat(RetryPolicy.forStatus(429)).isEqualTo(Verdict.RETRY);
        assertThat(RetryPolicy.forStatus(500)).isEqualTo(Verdict.RETRY);
        assertThat(RetryPolicy.forStatus(503)).isEqualTo(Verdict.RETRY);
    }

    @Test
    void backoffIsExponentialWithTwentyPercentJitter() {
        assertThat(RetryPolicy.backoffSeconds(1, 0.0)).isEqualTo(1.6);
        assertThat(RetryPolicy.backoffSeconds(1, 0.999)).isLessThan(2.4);
        assertThat(RetryPolicy.backoffSeconds(3, 0.5)).isEqualTo(8.0);
    }

    @Test
    void backoffIsCappedAt300Seconds() {
        assertThat(RetryPolicy.backoffSeconds(9, 0.5)).isEqualTo(300.0);     // 2^9 = 512 → 300
        assertThat(RetryPolicy.backoffSeconds(60, 0.5)).isEqualTo(300.0);    // no overflow
    }

    @Test
    void exhaustedAtMaxAttempts() {
        assertThat(RetryPolicy.exhausted(9, 10)).isFalse();
        assertThat(RetryPolicy.exhausted(10, 10)).isTrue();
    }
}
