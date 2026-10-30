package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.service.IngestOutcome;
import org.junit.jupiter.api.Test;

/** The same event delivered by 50 threads at once: exactly one APPLIED and one outbox row. */
class ConcurrentDuplicatesIT extends AbstractConcurrencyIT {

    @Test
    void oneAppliedRestDuplicate() {
        HrEventRequest event = upsert("dup-1", 3, "EMP-DUP", "BLR-A", "MORNING");
        try (Race race = new Race(50)) {
            List<Race.Outcome<IngestOutcome>> outcomes = race.run(t -> () -> ingest(event));
            assertThat(outcomes).noneMatch(Race.Outcome::failed);
            assertThat(outcomes.stream().filter(o -> o.value() == IngestOutcome.APPLIED)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o.value() == IngestOutcome.DUPLICATE)).hasSize(49);
        }
        assertThat(outboxRowsFor("EMP-DUP")).isEqualTo(1);
        assertThat(historyRows("EMP-DUP")).isEqualTo(1);
        assertThat(count("ingest_event", tenant)).isEqualTo(1);
    }
}
