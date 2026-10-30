package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Snapshot.id;
import static com.moveinsync.rostersync.roster.Snapshot.manifest;
import static com.moveinsync.rostersync.roster.Snapshot.row;
import static com.moveinsync.rostersync.roster.Snapshot.rows;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import com.moveinsync.rostersync.roster.RosterApi.Role;
import com.moveinsync.rostersync.roster.Snapshot.Row;
import com.moveinsync.rostersync.roster.core.Sha256;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.service.ReconciliationRecovery;
import com.moveinsync.rostersync.roster.service.SnapshotStager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Nightly reconciliation end to end (spec §9): diff, brake, approval, lineage, recovery. */
class ReconciliationIT extends AbstractReconIT {

    @Autowired
    SnapshotStager stager;

    @Autowired
    ReconciliationRecovery recovery;

    @Autowired
    ReconciliationRunRepository runs;

    static List<Row> without(List<Row> rows, int... numbers) {
        List<Row> out = new ArrayList<>(rows);
        for (int n : numbers) {
            out.removeIf(r -> r.id().equals(id(n)));
        }
        return out;
    }

    static List<Row> replace(List<Row> rows, Row replacement) {
        List<Row> out = new ArrayList<>(rows);
        out.replaceAll(r -> r.id().equals(replacement.id()) ? replacement : r);
        return out;
    }

    /** A cut file (first {@code kept} employees) after an initial load of {@code total}: trips the brake. */
    JsonNode blockedRun(int total, int kept, long cutoff) {
        initial(total, 100);
        JsonNode run = run(rows(1, kept), cutoff);
        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        return run;
    }

    @Test
    void initialLoadCreatesAll() {
        JsonNode run = settle(upload(rows(1, 50), manifest(100, 50, null)));
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "created")).isEqualTo(50);
        assertThat(run.get("parsedRows").asInt()).isEqualTo(50);
        assertThat(activeEmployees()).isEqualTo(50);
        assertThat(db.sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND event_type = 'EmployeeCreated'")
                .param(tenant).query(Long.class).single()).isEqualTo(50);
        assertThat(db.sql("SELECT count(*) FROM employee_history WHERE tenant_id = ? AND origin = 'RECONCILIATION'")
                .param(tenant).query(Long.class).single()).isEqualTo(50);
        assertThat(db.sql("SELECT DISTINCT payload->>'effectiveAt' FROM outbox_event WHERE tenant_id = ?").param(tenant)
                .query(String.class).single()).isEqualTo(Snapshot.EXPORTED_AT);
        assertThat(acceptedCutoff()).isEqualTo(100);
        assertThat(watermark()).isEqualTo(100);
        assertThat(stagingRows(run.get("runId").asString())).isZero();
        assertThat(run.get("phaseMs").has("stage")).isTrue();
        assertThat(run.get("phaseMs").has("apply")).isTrue();
    }

    @Test
    void typicalNightAppliesOnlyChanges() {
        initial(100, 100);
        List<Row> night = without(rows(1, 100), 99, 100);
        for (int i = 1; i <= 5; i++) {
            night = replace(night, row(i, 150 + i).shift("NIGHT"));
        }
        for (int i = 101; i <= 103; i++) {
            night.add(row(i, 160 + i - 100));
        }
        JsonNode run = run(night, 200);

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "updated")).isEqualTo(5);
        assertThat(count(run, "created")).isEqualTo(3);
        assertThat(count(run, "deactivated")).isEqualTo(2);
        assertThat(count(run, "advanced")).isZero();
        assertThat(count(run, "reactivated")).isZero();
        assertThat(active(id(99))).isFalse();
        assertThat(db.sql("SELECT source_seq || ':' || tombstone_reason FROM employee WHERE tenant_id = ? AND employee_id = ?")
                .params(tenant, id(100)).query(String.class).single()).isEqualTo("200:ABSENT_IN_SNAPSHOT");
        assertThat(db.sql("SELECT payload->'changedFields' ->> 0 FROM outbox_event WHERE tenant_id = ? AND employee_id = ? "
                + "AND event_type = 'EmployeeUpdated'").params(tenant, id(1)).query(String.class).single()).isEqualTo("shiftCode");
        assertThat(watermark()).isEqualTo(200);
    }

    @Test
    void quietNightTouchesFewBuckets() {
        initial(2000, 2000);
        List<Row> night = rows(1, 2000);
        for (int i : new int[] {7, 300, 901, 1500, 1999}) {
            night = replace(night, row(i, 3000 + i).office("GGN-A"));
        }
        JsonNode run = run(night, 6000);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "updated")).isEqualTo(5);
        assertThat(run.get("bucketsMismatched").asInt()).isLessThanOrEqualTo(10);
        assertThat(count(run, "unchangedBuckets")).isGreaterThan(1500);
    }

    @Test
    void sameHrHigherSeqAdvancesVersion() {
        initial(10, 100);
        JsonNode run = run(replace(rows(1, 10), row(3, 150)), 200);
        assertThat(count(run, "advanced")).isEqualTo(1);
        assertThat(count(run, "updated")).isZero();
        assertThat(db.sql("SELECT source_seq FROM employee WHERE tenant_id = ? AND employee_id = ?").params(tenant, id(3))
                .query(Long.class).single()).isEqualTo(150);
        assertThat(db.sql("SELECT event_type FROM outbox_event WHERE tenant_id = ? AND employee_id = ? ORDER BY aggregate_revision DESC LIMIT 1")
                .params(tenant, id(3)).query(String.class).single()).isEqualTo("EmployeeVersionAdvanced");
        // An API event between the old (3) and the new (150) version is now stale.
        assertThat(api.postEvent(tenant, Events.upsert("late", 120, id(3))).text("outcome")).isEqualTo("STALE");
    }

    @Test
    void newerApiChangeSurvivesSnapshot() {
        initial(10, 100);
        api.postEvent(tenant, Events.upsert("api-2", 150, id(2), Events.hr("GGN-A", "MORNING")));
        api.postEvent(tenant, Events.upsert("api-4", 160, id(4), Events.hr("BLR-A", "NIGHT")));
        // Export cut at 120: it still carries EMP-0002's old row and has no EMP-0004 at all.
        JsonNode run = run(without(rows(1, 10), 4), 120);

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "stale")).isEqualTo(1);
        assertThat(count(run, "deactivated")).isZero();
        assertThat(api.getEmployee(tenant, id(2)).text("officeCode")).isEqualTo("GGN-A");
        assertThat(active(id(4))).isTrue();
    }

    @Test
    void newJoinerAfterCutoffNotDeactivated() {
        initial(10, 100);
        api.postEvent(tenant, Events.upsert("joiner", 250, "EMP-NEW"));
        JsonNode run = run(rows(1, 10), 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "deactivated")).isZero();
        assertThat(active("EMP-NEW")).isTrue();
    }

    @Test
    void invalidRowsSuppressAllDeactivations() {
        initial(20, 100);
        List<Row> night = without(rows(1, 20), 19, 20);
        night = replace(night, row(3, 150).shift("EVENING"));
        night = replace(night, row(5, 151).phone("12345"));
        JsonNode run = run(night, 200);

        assertThat(run.get("status").asString()).isEqualTo("PARTIAL_NO_DEACTIVATIONS");
        assertThat(flags(run)).contains("INVALID_ROWS_PRESENT");
        assertThat(run.get("invalidRows").asInt()).isEqualTo(1);
        assertThat(count(run, "updated")).isEqualTo(1);
        assertThat(count(run, "deactivated")).isZero();
        assertThat(active(id(19))).isTrue();
        assertThat(active(id(20))).isTrue();
        assertThat(watermark()).isEqualTo(100);
        assertThat(acceptedCutoff()).isEqualTo(200);
    }

    @Test
    void unidentifiableRowSuppressesDeactivations() {
        initial(20, 100);
        List<Row> night = without(rows(1, 20), 20);
        night.add(row(21, 21).id(""));
        JsonNode run = run(night, 200);
        assertThat(run.get("status").asString()).isEqualTo("PARTIAL_NO_DEACTIVATIONS");
        assertThat(flags(run)).contains("UNIDENTIFIABLE_ROWS");
        assertThat(run.get("unidentifiableRows").asInt()).isEqualTo(1);
        assertThat(count(run, "deactivated")).isZero();
        assertThat(active(id(20))).isTrue();
    }

    @Test
    void rowCountMismatchNotApprovable() {
        initial(100, 100);
        JsonNode run = settle(upload(rows(1, 60), manifest(200, 100, null)));
        assertThat(run.get("status").asString()).isEqualTo("AWAITING_APPROVAL");
        assertThat(flags(run)).contains("ROW_COUNT_MISMATCH", "MISSING_RATIO_EXCEEDED");
        assertThat(run.get("missing").asInt()).isEqualTo(40);

        Response approve = api.approveRun(tenant, run.get("runId").asString(), run.get("assessmentHash").asString());
        assertThat(approve.status()).isEqualTo(409);
        assertThat(approve.text("errorCode")).isEqualTo("RUN_NOT_APPROVABLE");
        assertThat(activeEmployees()).isEqualTo(100);
    }

    @Test
    void checksumMismatchIncomplete() {
        initial(10, 100);
        JsonNode run = settle(upload(replace(rows(1, 10), row(2, 150).shift("NIGHT")), manifest(200, null, "00".repeat(32))));
        assertThat(run.get("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(run.get("errorCode").asString()).isEqualTo("CHECKSUM_MISMATCH");
        assertThat(api.getEmployee(tenant, id(2)).text("shiftCode")).isEqualTo("MORNING");
        assertThat(count("outbox_event", tenant)).isEqualTo(10);
        assertThat(stagingRows(run.get("runId").asString())).isZero();
    }

    @Test
    void staleSnapshotRejected() {
        initial(5, 200);
        Response r = api.uploadSnapshot(tenant, Role.OPERATOR, Snapshot.csv(rows(1, 5)), manifest(150));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("STALE_SNAPSHOT");
    }

    @Test
    void oneActiveRunPerTenant() {
        db.sql("""
                INSERT INTO reconciliation_run (run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path, status)
                VALUES (?, ?, 'hr-main', 50, now(), '/nowhere.csv', 'STAGING')""").params(UUID.randomUUID(), tenant).update();
        Response r = api.uploadSnapshot(tenant, Role.HR_INTEGRATION, Snapshot.csv(rows(1, 5)), manifest(100));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("RUN_IN_PROGRESS");
    }

    @Test
    void approvalWithStaleHashRejected() {
        JsonNode run = blockedRun(100, 50, 200);
        Response r = api.approveRun(tenant, run.get("runId").asString(), "ab".repeat(32));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("APPROVAL_STALE");
        assertThat(activeEmployees()).isEqualTo(100);
    }

    @Test
    void approvalReassessesAndApplies() {
        JsonNode blocked = blockedRun(100, 60, 200);
        String runId = blocked.get("runId").asString();
        String firstHash = blocked.get("assessmentHash").asString();
        assertThat(blocked.get("missing").asInt()).isEqualTo(40);

        // The data changes after the assessment: an admin deactivates one of the missing employees.
        assertThat(api.deactivate(tenant, id(99)).status()).isEqualTo(204);
        assertThat(api.approveRun(tenant, runId, firstHash).status()).isEqualTo(202);
        JsonNode reassessed = settle(runId);
        assertThat(reassessed.get("status").asString()).isEqualTo("AWAITING_APPROVAL");   // approval invalidated
        assertThat(reassessed.get("missing").asInt()).isEqualTo(39);
        assertThat(reassessed.get("approvedBy").isNull()).isTrue();
        String secondHash = reassessed.get("assessmentHash").asString();
        assertThat(secondHash).isNotEqualTo(firstHash);

        assertThat(api.approveRun(tenant, runId, secondHash).status()).isEqualTo(202);
        JsonNode applied = settle(runId);
        assertThat(applied.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(applied, "deactivated")).isEqualTo(39);
        assertThat(applied.get("approvedBy").asString()).isNotBlank();
        assertThat(activeEmployees()).isEqualTo(60);
    }

    @Test
    void rejectDeletesStaging() {
        JsonNode run = blockedRun(100, 50, 200);
        String runId = run.get("runId").asString();
        assertThat(stagingRows(runId)).isEqualTo(50);
        Response r = api.rejectRun(tenant, runId);
        assertThat(r.status()).isEqualTo(200);
        assertThat(api.getRun(tenant, runId).text("status")).isEqualTo("REJECTED");
        assertThat(api.getRun(tenant, runId).text("rejectedBy")).isNotBlank();
        assertThat(stagingRows(runId)).isZero();
        assertThat(activeEmployees()).isEqualTo(100);
        assertThat(api.rejectRun(tenant, runId).text("errorCode")).isEqualTo("INVALID_RUN_STATE");
    }

    @Test
    void startupRecoveryResumesStagedRun() throws Exception {
        initial(10, 100);
        // A run that was staged when the process died: staged, never applied.
        UUID staged = UUID.randomUUID();
        String csv = Snapshot.csv(replace(rows(1, 10), row(6, 150).shift("NIGHT")));
        Path file = TEMP_DIR.resolve("recon").resolve(staged + ".csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, csv);
        db.sql("""
                INSERT INTO reconciliation_run (run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path, file_sha256, status)
                VALUES (?, ?, 'hr-main', 200, '2026-10-30T02:00:00Z', ?, ?, 'UPLOADING')""")
                .params(staged, tenant, file.toString(), Sha256.of(csv.getBytes(StandardCharsets.UTF_8))).update();
        assertThat(stager.stage(runs.find(staged).orElseThrow())).isEqualTo(SnapshotStager.Result.STAGED);
        // And another tenant's run that died mid-staging.
        String other = newTenant();
        UUID interrupted = UUID.randomUUID();
        db.sql("""
                INSERT INTO reconciliation_run (run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path, status)
                VALUES (?, ?, 'hr-main', 100, now(), '/gone.csv', 'STAGING')""").params(interrupted, other).update();

        recovery.recover();

        JsonNode resumed = settle(staged.toString());
        assertThat(resumed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(resumed, "updated")).isEqualTo(1);
        assertThat(runs.find(interrupted).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(runs.find(interrupted).orElseThrow().errorCode()).isEqualTo("INTERRUPTED");
    }

    @Test
    void errorsEndpointHasNoValues() {
        initial(5, 100);
        List<Row> night = rows(1, 5);
        night = replace(night, row(2, 150).phone("+00leak7731"));
        night = replace(night, row(3, 151).email("leak7731@@example"));
        night = replace(night, row(4, 152).name(" "));
        night.add(row(9, 153).id("BAD ID leak7731"));
        String runId = settle(upload(night, manifest(200))).get("runId").asString();

        Response errors = api.runErrors(tenant, runId);
        assertThat(errors.status()).isEqualTo(200);
        assertThat(errors.body().get("total").asLong()).isEqualTo(4);
        assertThat(errors.body().get("items").findValuesAsString("code"))
                .containsExactlyInAnyOrder("PHONE_E164", "EMAIL_FORMAT", "NAME_REQUIRED", "EMPLOYEE_ID_FORMAT");
        assertThat(errors.body().get("items").get(0).propertyNames()).containsExactlyInAnyOrder("rowNo", "employeeId", "field", "code");
        assertThat(errors.raw()).doesNotContain("leak", "7731");
    }

    @Test
    void alertCreatedOnBrake() {
        JsonNode run = blockedRun(100, 50, 200);
        String details = db.sql("SELECT details::text FROM operator_alert WHERE tenant_id = ? AND kind = 'SAFETY_BRAKE'")
                .param(tenant).query(String.class).single();
        JsonNode alert = json.readTree(details);
        assertThat(alert.get("runId").asString()).isEqualTo(run.get("runId").asString());
        assertThat(alert.get("missing").asInt()).isEqualTo(50);
        assertThat(alert.get("baseline").asInt()).isEqualTo(100);
        assertThat(alert.get("ratioBp").asInt()).isEqualTo(5000);
        assertThat(details).doesNotContain("@", "Employee");

        Response pending = api.pendingApproval(tenant);
        assertThat(pending.body()).hasSize(1);
        assertThat(pending.body().get(0).get("missing").asInt()).isEqualTo(50);
        assertThat(pending.body().get(0).get("missingSample").size()).isEqualTo(20);
    }

    @Test
    void conflictsSuppressDeactivations() {
        initial(20, 100);
        List<Row> night = without(rows(1, 20), 20);
        night = replace(night, row(4, 4).shift("NIGHT"));            // same seq, different HR
        JsonNode run = run(night, 200);
        assertThat(run.get("status").asString()).isEqualTo("PARTIAL_NO_DEACTIVATIONS");
        assertThat(flags(run)).contains("CONFLICTS_PRESENT");
        assertThat(count(run, "conflict")).isEqualTo(1);
        assertThat(count(run, "deactivated")).isZero();
        assertThat(active(id(20))).isTrue();
        assertThat(watermark()).isEqualTo(100);
        assertThat(db.sql("SELECT count(*) FROM record_error WHERE tenant_id = ? AND code = 'VERSION_CONFLICT' AND employee_id = ?")
                .params(tenant, id(4)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void oldRunApprovedAfterNewerCompletesIsRejected() {
        JsonNode runA = blockedRun(100, 50, 150);
        JsonNode runB = run(replace(rows(1, 100), row(7, 170).shift("NIGHT")), 200);
        assertThat(runB.get("status").asString()).isEqualTo("COMPLETED");

        assertThat(api.approveRun(tenant, runA.get("runId").asString(), runA.get("assessmentHash").asString()).status())
                .isEqualTo(202);
        JsonNode settled = settle(runA.get("runId").asString());
        assertThat(settled.get("status").asString()).isEqualTo("REJECTED");
        assertThat(settled.get("errorCode").asString()).isEqualTo("STALE_SNAPSHOT");
        assertThat(activeEmployees()).isEqualTo(100);
    }

    @Test
    void sameCutoffSameFileIsNoop() {
        initial(10, 100);
        long outbox = count("outbox_event", tenant);
        Response again = api.uploadSnapshot(tenant, Role.OPERATOR, Snapshot.csv(rows(1, 10)), manifest(100));
        assertThat(again.status()).isEqualTo(202);
        assertThat(again.text("status")).isEqualTo("COMPLETED");
        JsonNode run = api.getRun(tenant, again.text("runId")).body();
        assertThat(run.get("counts").get("duplicateSnapshot").asBoolean()).isTrue();
        assertThat(count("outbox_event", tenant)).isEqualTo(outbox);
    }

    @Test
    void sameCutoffDifferentFileConflicts() {
        initial(10, 100);
        String other = Snapshot.csv(replace(rows(1, 10), row(3, 50).shift("NIGHT")));
        Response r = api.uploadSnapshot(tenant, Role.OPERATOR, other, manifest(100));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("SNAPSHOT_CONFLICT");
        // A rejected conflicting upload does not unlock a second attempt with the same file.
        assertThat(api.uploadSnapshot(tenant, Role.OPERATOR, other, manifest(100)).text("errorCode")).isEqualTo("SNAPSHOT_CONFLICT");
        assertThat(api.getEmployee(tenant, id(3)).text("shiftCode")).isEqualTo("MORNING");
    }

    @Test
    void correctedFileAfterPartialRunAllowed() {
        initial(20, 100);
        List<Row> broken = replace(without(rows(1, 20), 19, 20), row(5, 151).phone("bad"));
        assertThat(run(broken, 200).get("status").asString()).isEqualTo("PARTIAL_NO_DEACTIVATIONS");

        JsonNode corrected = run(replace(without(rows(1, 20), 19, 20), row(5, 151).shift("EVENING")), 200);
        assertThat(corrected.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(corrected, "deactivated")).isEqualTo(2);
        assertThat(count(corrected, "updated")).isEqualTo(1);
        assertThat(active(id(19))).isFalse();
        assertThat(watermark()).isEqualTo(200);
    }

    @Test
    void tombstonedIdInSnapshotIsStale() {
        initial(10, 100);
        assertThat(api.postEvent(tenant, Events.deactivate("gone", 150, "EMP-T")).text("outcome")).isEqualTo("TOMBSTONED");
        List<Row> night = rows(1, 10);
        night.add(row(77, 120).id("EMP-T"));
        JsonNode run = run(night, 200);
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(count(run, "stale")).isEqualTo(1);
        assertThat(count(run, "created")).isZero();
        assertThat(api.getEmployee(tenant, "EMP-T").status()).isEqualTo(404);
    }
}
