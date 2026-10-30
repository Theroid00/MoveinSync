package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.deactivate;
import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Every applied change has exactly one outbox row, written in the same transaction. */
class OutboxIT extends AbstractApiIT {

    @Test
    void oneOutboxRowPerAppliedChange() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        api.postEvent(tenant, upsert("e-2", 2, "EMP-1", hr("BLR-B", "MORNING")));
        api.postEvent(tenant, upsert("e-3", 3, "EMP-1", hr("BLR-B", "MORNING")));   // same HR, higher seq
        api.postEvent(tenant, deactivate("e-4", 4, "EMP-1"));
        api.postEvent(tenant, upsert("e-5", 5, "EMP-1"));
        api.patchTransport(tenant, "EMP-1", "5", Map.of("pickupPoint", "Gate 4"));
        assertThat(api.deactivate(tenant, "EMP-1").status()).isEqualTo(204);

        assertThat(db.sql("SELECT event_type FROM outbox_event WHERE tenant_id = ? ORDER BY aggregate_revision")
                .param(tenant).query(String.class).list())
                .containsExactly("EmployeeCreated", "EmployeeUpdated", "EmployeeVersionAdvanced", "EmployeeDeactivated",
                        "EmployeeReactivated", "EmployeeTransportUpdated", "EmployeeDeactivated");
        assertThat(db.sql("SELECT aggregate_revision FROM outbox_event WHERE tenant_id = ? ORDER BY id")
                .param(tenant).query(Long.class).list()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(historyRows("EMP-1")).isEqualTo(7);
    }

    @Test
    void noOutboxRowForStaleDuplicateConflict() {
        api.postEvent(tenant, upsert("e-5", 5, "EMP-1"));
        assertThat(api.postEvent(tenant, upsert("e-5", 5, "EMP-1")).text("outcome")).isEqualTo("DUPLICATE");      // same event
        assertThat(api.postEvent(tenant, upsert("e-5b", 5, "EMP-1")).text("outcome")).isEqualTo("DUPLICATE");     // same state
        assertThat(api.postEvent(tenant, upsert("e-4", 4, "EMP-1")).text("outcome")).isEqualTo("STALE");
        assertThat(api.postEvent(tenant, upsert("e-5c", 5, "EMP-1", hr("GGN-A", "NIGHT"))).status()).isEqualTo(409);
        assertThat(api.postEvent(tenant, deactivate("d-0", 9, "EMP-NEW")).text("outcome")).isEqualTo("TOMBSTONED");
        assertThat(count("outbox_event", tenant)).isEqualTo(1);
        assertThat(count("employee_history", tenant)).isEqualTo(1);
    }

    @Test
    void employeeAndOutboxRollBackTogether() {
        // Force a failure after the employee write: a pre-existing outbox row makes the outbox insert
        // for (tenant, EMP-RB, revision 1) violate its unique key inside the same transaction.
        db.sql("""
                INSERT INTO outbox_event (event_id, tenant_id, employee_id, aggregate_revision, event_type, payload)
                VALUES (?, ?, 'EMP-RB', 1, 'EmployeeCreated', '{}'::jsonb)""").params(UUID.randomUUID(), tenant).update();

        Response r = api.postEvent(tenant, upsert("e-rb", 1, "EMP-RB"));
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.text("errorCode")).isEqualTo("INTERNAL_ERROR");
        assertThat(count("employee", tenant)).isZero();
        assertThat(count("employee_history", tenant)).isZero();
        assertThat(count("ingest_event", tenant)).isZero();

        db.sql("DELETE FROM outbox_event WHERE tenant_id = ?").param(tenant).update();
        assertThat(api.postEvent(tenant, upsert("e-rb", 1, "EMP-RB")).text("outcome")).isEqualTo("APPLIED");
        assertThat(outboxRows("EMP-RB")).isEqualTo(1);
    }

    @Test
    void payloadContainsNoPii() {
        Map<String, Object> pii = Map.of("name", "Zyxw Canary", "email", "canary.pii.7731@example.test",
                "phone", "+919999977731", "officeCode", "BLR-A", "shiftCode", "EVENING");
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1", pii));
        api.patchTransport(tenant, "EMP-1", "1", Map.of("pickupPoint", "Canary Lane 7731"));

        for (String payload : db.sql("SELECT payload::text FROM outbox_event WHERE tenant_id = ?").param(tenant)
                .query(String.class).list()) {
            assertThat(payload).doesNotContain("Zyxw", "Canary", "canary", "7731", "@");
            JsonNode node = json.readTree(payload);
            assertThat(node.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "tenantId", "employeeId",
                    "aggregateRevision", "active", "effectiveAt", "changedFields");
        }
    }
}
