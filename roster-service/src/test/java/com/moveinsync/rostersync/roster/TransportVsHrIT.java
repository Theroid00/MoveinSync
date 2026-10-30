package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.service.ConflictException;
import org.junit.jupiter.api.Test;

/**
 * A transport-admin PATCH races an HR update 200 times. The loser of the revision check retries with
 * the fresh revision (as a client would after 409); both owners' fields always survive.
 */
class TransportVsHrIT extends AbstractConcurrencyIT {

    long patchWithRetry(String employee, String pickup) {
        for (int attempt = 0; attempt < 10; attempt++) {
            long revision = db.sql("SELECT aggregate_revision FROM employee WHERE tenant_id = ? AND employee_id = ?")
                    .params(tenant, employee).query(Long.class).single();
            try {
                return employees.updateTransport(tenant, employee, revision, pickup, "WHEELCHAIR");
            } catch (ConflictException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.REVISION_MISMATCH);
            }
        }
        throw new AssertionError("PATCH kept losing the revision race");
    }

    @Test
    void bothOwnersFieldsPreserved() {
        try (Race race = new Race(2)) {
            for (int i = 0; i < 200; i++) {
                String employee = "EMP-T" + i;
                ingest(upsert(employee + "-1", 1, employee, "BLR-A", "MORNING"));
                String pickup = "Gate " + i;
                var outcomes = race.run(t -> t == 0
                        ? () -> patchWithRetry(employee, pickup)
                        : () -> ingest(upsert(employee + "-2", 2, employee, "GGN-A", "NIGHT")));
                assertThat(outcomes).noneMatch(Race.Outcome::failed);

                var row = db.sql("""
                        SELECT office_code, shift_code, pickup_point, special_assistance, aggregate_revision
                        FROM employee WHERE tenant_id = ? AND employee_id = ?""").params(tenant, employee).query().singleRow();
                assertThat(row).as("iteration %d", i)
                        .containsEntry("office_code", "GGN-A").containsEntry("shift_code", "NIGHT")
                        .containsEntry("pickup_point", pickup).containsEntry("special_assistance", "WHEELCHAIR")
                        .containsEntry("aggregate_revision", 3L);
            }
        }
    }
}
