package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.moveinsync.rostersync.roster.service.IngestOutcome;
import org.junit.jupiter.api.Test;

/** v6 and v7 of the same employee race 500 times: v7 always wins, nothing is lost, ≤ 2 events. */
class RacingVersionsIT extends AbstractConcurrencyIT {

    @Test
    void higherVersionAlwaysWins() {
        try (Race race = new Race(2)) {
            for (int i = 0; i < 500; i++) {
                String employee = "EMP-R" + i;
                List<Race.Outcome<IngestOutcome>> outcomes = race.run(t -> t == 0
                        ? () -> ingest(upsert(employee + "-v6", 6, employee, "BLR-A", "NIGHT"))
                        : () -> ingest(upsert(employee + "-v7", 7, employee, "GGN-A", "EVENING")));
                assertThat(outcomes).noneMatch(Race.Outcome::failed);
                assertThat(outcomes.stream().map(Race.Outcome::value)).contains(IngestOutcome.APPLIED)
                        .allMatch(o -> o == IngestOutcome.APPLIED || o == IngestOutcome.STALE);

                var row = db.sql("SELECT source_seq, office_code, shift_code FROM employee WHERE tenant_id = ? AND employee_id = ?")
                        .params(tenant, employee).query().singleRow();
                assertThat(row).as("iteration %d", i).containsEntry("source_seq", 7L)
                        .containsEntry("office_code", "GGN-A").containsEntry("shift_code", "EVENING");
                assertThat(outboxRowsFor(employee)).isBetween(1L, 2L);
            }
        }
    }
}
