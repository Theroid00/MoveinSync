package com.moveinsync.rostersync.roster.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RosterMetricsTest {
    @Test
    void failedRunsHaveABaselineBeforeTheFirstFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            RosterMetrics metrics = new RosterMetrics(registry);
            var failed = registry.find("reconciliation.runs").tag("status", "FAILED").counter();
            assertThat(failed).isNotNull();
            assertThat(failed.count()).isZero();
            metrics.reconciliationRun("FAILED");
            assertThat(failed.count()).isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
