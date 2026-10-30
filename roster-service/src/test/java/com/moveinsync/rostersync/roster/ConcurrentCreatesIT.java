package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 20 versions of a brand-new employee arrive at once: one row, at version 20. */
class ConcurrentCreatesIT extends AbstractConcurrencyIT {

    @Test
    void oneRowAtHighestVersion() {
        try (Race race = new Race(20)) {
            var outcomes = race.run(t -> () -> ingest(upsert("c-" + (t + 1), t + 1, "EMP-NEW", t % 2 == 0 ? "BLR-A" : "BLR-B",
                    "MORNING")));
            assertThat(outcomes).noneMatch(Race.Outcome::failed);
        }
        assertThat(db.sql("SELECT count(*) FROM employee WHERE tenant_id = ? AND employee_id = 'EMP-NEW'").param(tenant)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT source_seq FROM employee WHERE tenant_id = ? AND employee_id = 'EMP-NEW'").param(tenant)
                .query(Long.class).single()).isEqualTo(20);
        assertThat(db.sql("SELECT office_code FROM employee WHERE tenant_id = ? AND employee_id = 'EMP-NEW'").param(tenant)
                .query(String.class).single()).isEqualTo("BLR-B");
        assertThat(db.sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'EmployeeCreated'").param(tenant)
                .query(Long.class).single()).isEqualTo(1);
    }
}
