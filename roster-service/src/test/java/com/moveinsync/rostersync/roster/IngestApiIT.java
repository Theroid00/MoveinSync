package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.deactivate;
import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;

class IngestApiIT extends AbstractApiIT {

    @Test
    void appliesCreate() {
        Response r = api.postEvent(tenant, upsert("e-1", 5, "EMP-1"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("outcome")).isEqualTo("APPLIED");
        assertThat(r.body().get("aggregateRevision").asLong()).isEqualTo(1);

        Response emp = api.getEmployee(tenant, "EMP-1");
        assertThat(emp.status()).isEqualTo(200);
        assertThat(emp.text("officeCode")).isEqualTo("BLR-A");
        assertThat(emp.body().get("active").asBoolean()).isTrue();
        assertThat(emp.body().get("sourceSeq").asLong()).isEqualTo(5);
        assertThat(historyRows("EMP-1")).isEqualTo(1);
        assertThat(outboxRows("EMP-1")).isEqualTo(1);
        assertThat(db.sql("SELECT event_type FROM outbox_event WHERE tenant_id = ?").param(tenant)
                .query(String.class).single()).isEqualTo("EmployeeCreated");
    }

    @Test
    void duplicateEventIsNoop() {
        Map<String, Object> event = upsert("e-1", 5, "EMP-1");
        assertThat(api.postEvent(tenant, event).text("outcome")).isEqualTo("APPLIED");
        Response again = api.postEvent(tenant, event);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.text("outcome")).isEqualTo("DUPLICATE");
        assertThat(historyRows("EMP-1")).isEqualTo(1);
        assertThat(outboxRows("EMP-1")).isEqualTo(1);
    }

    @Test
    void eventIdReusedWithDifferentPayloadIsConflict() {
        api.postEvent(tenant, upsert("e-1", 5, "EMP-1"));
        Response r = api.postEvent(tenant, withHr(upsert("e-1", 5, "EMP-1"), "shiftCode", "NIGHT"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("EVENT_ID_REUSED");
        assertThat(r.text("outcome")).isEqualTo("CONFLICT");
        assertThat(api.getEmployee(tenant, "EMP-1").text("shiftCode")).isEqualTo("MORNING");
        assertThat(outboxRows("EMP-1")).isEqualTo(1);
    }

    @Test
    void staleVersionIgnored() {
        api.postEvent(tenant, upsert("e-5", 5, "EMP-1", hr("BLR-A", "MORNING")));
        Response r = api.postEvent(tenant, upsert("e-4", 4, "EMP-1", hr("BLR-B", "NIGHT")));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("outcome")).isEqualTo("STALE");
        Response emp = api.getEmployee(tenant, "EMP-1");
        assertThat(emp.text("officeCode")).isEqualTo("BLR-A");
        assertThat(emp.body().get("sourceSeq").asLong()).isEqualTo(5);
        assertThat(outboxRows("EMP-1")).isEqualTo(1);
    }

    @Test
    void equalSeqDifferentHashIsConflict() {
        api.postEvent(tenant, upsert("e-a", 5, "EMP-1", hr("BLR-A", "MORNING")));
        Response r = api.postEvent(tenant, upsert("e-b", 5, "EMP-1", hr("BLR-A", "NIGHT")));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("VERSION_CONFLICT");
        assertThat(api.getEmployee(tenant, "EMP-1").text("shiftCode")).isEqualTo("MORNING");
        assertThat(db.sql("SELECT count(*) FROM record_error WHERE tenant_id = ? AND code = 'VERSION_CONFLICT'")
                .param(tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void deactivateUnknownCreatesTombstone() {
        Response r = api.postEvent(tenant, deactivate("d-1", 7, "EMP-9"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("outcome")).isEqualTo("TOMBSTONED");
        assertThat(outboxRows("EMP-9")).isZero();
        assertThat(db.sql("SELECT source_seq FROM employee_tombstone WHERE tenant_id = ? AND employee_id = 'EMP-9'")
                .param(tenant).query(Long.class).single()).isEqualTo(7);
    }

    @Test
    void deactivateBeforeCreateEndsInactive() {
        assertThat(api.postEvent(tenant, deactivate("d-2", 2, "EMP-2")).text("outcome")).isEqualTo("TOMBSTONED");
        Response late = api.postEvent(tenant, upsert("u-1", 1, "EMP-2"));
        assertThat(late.text("outcome")).isEqualTo("STALE");
        assertThat(api.getEmployee(tenant, "EMP-2").status()).isEqualTo(404);
        assertThat(db.sql("SELECT count(*) FROM employee WHERE tenant_id = ? AND active").param(tenant)
                .query(Long.class).single()).isZero();
        assertThat(outboxRows("EMP-2")).isZero();
    }

    @Test
    void upsertAboveTombstoneCreatesAndClearsTombstone() {
        api.postEvent(tenant, deactivate("d-2", 2, "EMP-3"));
        Response r = api.postEvent(tenant, upsert("u-3", 3, "EMP-3"));
        assertThat(r.text("outcome")).isEqualTo("APPLIED");
        assertThat(api.getEmployee(tenant, "EMP-3").body().get("active").asBoolean()).isTrue();
        assertThat(count("employee_tombstone", tenant)).isZero();
        assertThat(db.sql("SELECT event_type FROM outbox_event WHERE tenant_id = ?").param(tenant)
                .query(String.class).single()).isEqualTo("EmployeeCreated");
    }

    @Test
    void rejectedRecordWithoutEventIdHasRejectionId() {
        Map<String, Object> event = withHr(upsert(null, 5, "EMP-4"), "phone", "12345");
        Response r = api.postEvent(tenant, event);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("outcome")).isEqualTo("REJECTED");
        String rejectionId = r.text("rejectionId");
        assertThat(rejectionId).isNotBlank();

        assertThat(count("ingest_event", tenant)).isZero();
        List<Map<String, Object>> errors = db.sql("SELECT rejection_id, event_id, code FROM record_error WHERE tenant_id = ?")
                .param(tenant).query().listOfRows();
        assertThat(errors).extracting(row -> row.get("code")).containsExactlyInAnyOrder("EVENT_ID_REQUIRED", "PHONE_E164");
        assertThat(errors).allSatisfy(row -> {
            assertThat(row.get("rejection_id")).isEqualTo(UUID.fromString(rejectionId));
            assertThat(row.get("event_id")).isNull();
        });
    }

    @Test
    void reactivationRequiresHigherSeq() {
        api.postEvent(tenant, upsert("u-1", 1, "EMP-5"));
        api.postEvent(tenant, deactivate("d-2", 2, "EMP-5"));
        assertThat(api.postEvent(tenant, upsert("u-1b", 1, "EMP-5", hr("GGN-A", "NIGHT"))).text("outcome")).isEqualTo("STALE");
        assertThat(api.postEvent(tenant, upsert("u-2", 2, "EMP-5")).status()).isEqualTo(409);   // same seq, other intent
        assertThat(api.getEmployee(tenant, "EMP-5").body().get("active").asBoolean()).isFalse();

        Response r = api.postEvent(tenant, upsert("u-3", 3, "EMP-5"));
        assertThat(r.text("outcome")).isEqualTo("APPLIED");
        assertThat(api.getEmployee(tenant, "EMP-5").body().get("active").asBoolean()).isTrue();
        assertThat(db.sql("SELECT event_type FROM outbox_event WHERE tenant_id = ? ORDER BY aggregate_revision")
                .param(tenant).query(String.class).list())
                .containsExactly("EmployeeCreated", "EmployeeDeactivated", "EmployeeReactivated");
    }

    @Test
    void oldEventCannotResurrectAfterSnapshotAbsence() {
        api.postEvent(tenant, upsert("u-10", 10, "EMP-6"));
        // A snapshot with cutoff 50 proved EMP-6 (and never-seen EMP-7) absent: stamped at seq 50.
        db.sql("UPDATE employee SET active = false, source_seq = 50, tombstone_reason = 'ABSENT_IN_SNAPSHOT' "
                + "WHERE tenant_id = ? AND employee_id = 'EMP-6'").param(tenant).update();
        db.sql("UPDATE tenant_source SET absence_watermark = 50, accepted_snapshot_cutoff = 50 WHERE tenant_id = ?")
                .param(tenant).update();

        assertThat(api.postEvent(tenant, upsert("u-20", 20, "EMP-6")).text("outcome")).isEqualTo("STALE");
        assertThat(api.postEvent(tenant, upsert("u-30", 30, "EMP-7")).text("outcome")).isEqualTo("STALE");
        assertThat(api.getEmployee(tenant, "EMP-6").body().get("active").asBoolean()).isFalse();
        assertThat(api.getEmployee(tenant, "EMP-7").status()).isEqualTo(404);

        assertThat(api.postEvent(tenant, upsert("u-60", 60, "EMP-6")).text("outcome")).isEqualTo("APPLIED");
        assertThat(api.postEvent(tenant, upsert("u-61", 61, "EMP-7")).text("outcome")).isEqualTo("APPLIED");
    }
}
