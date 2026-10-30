package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;

/** The two worked examples from the case-study brief. */
class BriefExampleIT extends AbstractReconIT {

    @Test
    void transferV7BeforeShiftV6() {
        api.postEvent(tenant, upsert("v5", 5, "EMP-42", hr("BLR-A", "MORNING")));
        // v7: office transfer (full HR state: new office, shift as of v7)
        assertThat(api.postEvent(tenant, upsert("v7", 7, "EMP-42", hr("GGN-A", "EVENING"))).text("outcome"))
                .isEqualTo("APPLIED");
        // v6: shift change, generated earlier but delivered late
        Response v6 = api.postEvent(tenant, upsert("v6", 6, "EMP-42", hr("BLR-A", "NIGHT")));
        assertThat(v6.status()).isEqualTo(200);
        assertThat(v6.text("outcome")).isEqualTo("STALE");

        Response emp = api.getEmployee(tenant, "EMP-42");
        assertThat(emp.text("officeCode")).isEqualTo("GGN-A");
        assertThat(emp.text("shiftCode")).isEqualTo("EVENING");
        assertThat(emp.body().get("sourceSeq").asLong()).isEqualTo(7);

        Response history = api.history(tenant, "EMP-42");
        assertThat(history.body().get("events").findValuesAsString("outcome")).containsExactly("APPLIED", "APPLIED", "STALE");
    }

    @Test
    void fileCutAt120kBlocksAndShowsMissingCount() {
        // Scaled 1:100: 2,000 active employees; tonight's file stops after 1,200 rows.
        initial(2000, 2000);
        tools.jackson.databind.JsonNode run = run(Snapshot.rows(1, 1200), 3000);

        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(run.get("baseline").asInt()).isEqualTo(2000);
        assertThat(run.get("missing").asInt()).isEqualTo(800);            // what the operator sees
        assertThat(run.get("ratioBp").asInt()).isEqualTo(4000);
        assertThat(run.get("flags").toString()).contains("MISSING_RATIO_EXCEEDED");
        assertThat(activeEmployees()).isEqualTo(2000);                     // nobody deactivated
        assertThat(count("outbox_event", tenant)).isEqualTo(2000);         // no events beyond the initial load
        assertThat(api.pendingApproval(tenant).body().get(0).get("missing").asInt()).isEqualTo(800);
    }
}
