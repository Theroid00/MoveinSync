package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import com.moveinsync.rostersync.roster.service.IngestOutcome;
import com.moveinsync.rostersync.roster.service.ReconciliationInProgressException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A snapshot apply (cutoff S, 200 employees absent) runs while 200 API events with seq > S arrive for
 * exactly those employees. Whatever the interleaving (before the lock: excluded by the seq ≤ S guard;
 * after the apply: the newer seq reactivates), no API change is lost and nobody ends deactivated.
 */
class SnapshotVsApiIT extends AbstractConcurrencyIT {

    static final long CUTOFF = 2000;

    IngestOutcome ingestRetrying(int i) {
        String employee = Snapshot.id(i);
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return ingest(upsert("api-" + i, CUTOFF + 1000 + i, employee, "GGN-A", "NIGHT"));
            } catch (ReconciliationInProgressException e) {
                // 503 during the apply's exclusive lock: a client retries the same event
            }
        }
        throw new AssertionError("still locked after 20 attempts");
    }

    @Test
    void apiChangesNewerThanTheCutoffSurvive() {
        initial(1000, 1000);
        // Tonight's export (cutoff 2000) lacks employees 801..1000 (20%, below the brake).
        String runId = upload(Snapshot.rows(1, 800), Snapshot.manifest(CUTOFF));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(5))
                .until(() -> !"UPLOADING".equals(api.getRun(tenant, runId).text("status")));

        try (Race race = new Race(8)) {
            List<Race.Outcome<IngestOutcome>> outcomes = race.run(200, t -> () -> ingestRetrying(801 + t));
            assertThat(outcomes).noneMatch(Race.Outcome::failed);
            assertThat(outcomes).allMatch(o -> o.value() == IngestOutcome.APPLIED);
        }
        JsonNode run = settle(runId);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");

        List<Long> seqs = db.sql("""
                SELECT source_seq FROM employee WHERE tenant_id = ? AND employee_id BETWEEN 'EMP-0801' AND 'EMP-1000' AND active
                ORDER BY employee_id""").param(tenant).query(Long.class).list();
        assertThat(seqs).hasSize(200).allMatch(seq -> seq > CUTOFF);
        assertThat(db.sql("SELECT count(*) FROM employee WHERE tenant_id = ? AND NOT active").param(tenant)
                .query(Long.class).single()).isZero();
    }
}
