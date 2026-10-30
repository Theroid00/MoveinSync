package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * UPSERT v1 races DEACTIVATE v2 for an employee nobody has seen, 200 times: whichever lands first, the
 * employee always ends inactive at seq 2 (inactive row or tombstone), never active.
 */
class CreateVsDeactivateUnknownIT extends AbstractConcurrencyIT {

    @Test
    void alwaysEndsInactiveAtSeq2() {
        try (Race race = new Race(2)) {
            for (int i = 0; i < 200; i++) {
                String employee = "EMP-U" + i;
                var outcomes = race.run(t -> t == 0
                        ? () -> ingest(upsert(employee + "-u1", 1, employee, "BLR-A", "MORNING"))
                        : () -> ingest(deactivate(employee + "-d2", 2, employee)));
                assertThat(outcomes).noneMatch(Race.Outcome::failed);

                var row = db.sql("SELECT active, source_seq FROM employee WHERE tenant_id = ? AND employee_id = ?")
                        .params(tenant, employee).query().listOfRows().stream().findFirst();
                Long tombstone = db.sql("SELECT source_seq FROM employee_tombstone WHERE tenant_id = ? AND employee_id = ?")
                        .params(tenant, employee).query(Long.class).optional().orElse(null);
                if (row.isPresent()) {
                    assertThat(row.get()).as("iteration %d", i).containsEntry("active", false).containsEntry("source_seq", 2L);
                } else {
                    assertThat(tombstone).as("iteration %d", i).isEqualTo(2L);
                }
            }
        }
        assertThat(db.sql("SELECT count(*) FROM employee WHERE tenant_id = ? AND active").param(tenant).query(Long.class).single())
                .isZero();
    }
}
