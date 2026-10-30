package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import com.moveinsync.rostersync.roster.RosterApi.Role;
import com.moveinsync.rostersync.roster.Snapshot.Row;
import tools.jackson.databind.JsonNode;

/** Reconciliation test support: upload a snapshot and wait (by polling, never sleeping) for the run to settle. */
public abstract class AbstractReconIT extends AbstractApiIT {

    static final Set<String> IN_FLIGHT = Set.of("UPLOADING", "STAGING", "STAGED", "APPLYING");

    protected String upload(List<Row> rows, Object manifest) {
        Response r = api.uploadSnapshot(tenant, Role.OPERATOR, Snapshot.csv(rows), manifest);
        assertThat(r.status()).as(r.raw()).isEqualTo(202);
        return r.text("runId");
    }

    /** Polls GET /reconciliations/{id} until the run leaves the in-flight states; returns the run view. */
    protected JsonNode settle(String runId) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(100))
                .until(() -> !IN_FLIGHT.contains(api.getRun(tenant, runId).text("status")));
        return api.getRun(tenant, runId).body();
    }

    protected JsonNode run(List<Row> rows, long cutoff) {
        return settle(upload(rows, Snapshot.manifest(cutoff)));
    }

    /** Initial load of employees 1..n (seq = number) at the given cutoff. */
    protected JsonNode initial(int n, long cutoff) {
        JsonNode run = run(Snapshot.rows(1, n), cutoff);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        return run;
    }

    protected long activeEmployees() {
        return db.sql("SELECT count(*) FROM employee WHERE tenant_id = ? AND active").param(tenant).query(Long.class).single();
    }

    protected boolean active(String employeeId) {
        return db.sql("SELECT active FROM employee WHERE tenant_id = ? AND employee_id = ?").params(tenant, employeeId)
                .query(Boolean.class).single();
    }

    protected long stagingRows(String runId) {
        return db.sql("SELECT count(*) FROM staging_employee WHERE run_id = ?::uuid").param(runId).query(Long.class).single();
    }

    protected long watermark() {
        return db.sql("SELECT absence_watermark FROM tenant_source WHERE tenant_id = ?").param(tenant).query(Long.class).single();
    }

    protected long acceptedCutoff() {
        return db.sql("SELECT accepted_snapshot_cutoff FROM tenant_source WHERE tenant_id = ?").param(tenant).query(Long.class).single();
    }

    protected static int count(JsonNode run, String field) {
        return run.get("counts").get(field).asInt();
    }

    protected static List<String> flags(JsonNode run) {
        return run.get("flags").valueStream().map(JsonNode::asString).toList();
    }
}
