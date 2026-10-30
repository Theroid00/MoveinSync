package com.moveinsync.rostersync.roster.fault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FaultInjectorTest {

    final List<Integer> halts = new ArrayList<>();

    @Test
    void haltsWith137OnlyAtTheConfiguredPoint() {
        FaultInjector faults = new FaultInjector("AFTER_INGEST_COMMIT", halts::add);
        faults.hit("RELAY_AFTER_CLAIM");
        assertThat(halts).isEmpty();
        faults.hit("AFTER_INGEST_COMMIT");
        assertThat(halts).containsExactly(137);
    }

    @Test
    void emptyPointNeverHalts() {
        FaultInjector faults = new FaultInjector("", halts::add);
        for (String point : new String[] {"AFTER_INGEST_COMMIT", "DURING_RECON_APPLY", "RELAY_AFTER_CLAIM", "AFTER_CONSUMER_COMMIT", ""}) {
            faults.hit(point);
        }
        assertThat(halts).isEmpty();
    }

    @Test
    void pointIsTrimmedAndExact() {
        FaultInjector faults = new FaultInjector("  DURING_RECON_APPLY ", halts::add);
        faults.hit("DURING_RECON");
        assertThat(halts).isEmpty();
        faults.hit("DURING_RECON_APPLY");
        assertThat(halts).containsExactly(137);
    }
}
