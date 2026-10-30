package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** HR owns identity/office/shift; the transport admin owns pickup point and special assistance (DEC-06/07). */
class OwnershipIT extends AbstractApiIT {

    @Test
    void hrUpdateKeepsPickupAndAssistance() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        Response patch = api.patchTransport(tenant, "EMP-1", "1",
                Map.of("pickupPoint", "Gate 4", "specialAssistance", "WHEELCHAIR"));
        assertThat(patch.status()).isEqualTo(200);
        assertThat(patch.header("ETag")).isEqualTo("\"2\"");

        assertThat(api.postEvent(tenant, upsert("e-2", 2, "EMP-1", hr("BLR-A", "NIGHT"))).text("outcome")).isEqualTo("APPLIED");
        Response emp = api.getEmployee(tenant, "EMP-1");
        assertThat(emp.text("shiftCode")).isEqualTo("NIGHT");
        assertThat(emp.text("pickupPoint")).isEqualTo("Gate 4");
        assertThat(emp.text("specialAssistance")).isEqualTo("WHEELCHAIR");
        assertThat(emp.body().get("aggregateRevision").asLong()).isEqualTo(3);
    }

    @Test
    void hrPayloadWithPickupRejected422() {
        Response nested = api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "pickupPoint", "Gate 9"));
        assertThat(nested.status()).isEqualTo(422);
        assertThat(nested.text("errorCode")).isEqualTo("UNKNOWN_FIELD");
        assertThat(nested.body().get("errors").get(0).get("field").asString()).isEqualTo("hr.pickupPoint");

        Map<String, Object> topLevel = new HashMap<>(upsert("e-2", 1, "EMP-1"));
        topLevel.put("specialAssistance", "none");
        assertThat(api.postEvent(tenant, topLevel).status()).isEqualTo(422);
        assertThat(count("employee", tenant)).isZero();
    }

    @Test
    void transportPatchRejectsHrField() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        Response r = api.patchTransport(tenant, "EMP-1", "1", Map.of("pickupPoint", "Gate 4", "officeCode", "GGN-A"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("errorCode")).isEqualTo("UNKNOWN_FIELD");
        Response emp = api.getEmployee(tenant, "EMP-1");
        assertThat(emp.text("officeCode")).isEqualTo("BLR-A");
        assertThat(emp.body().get("pickupPoint").isNull()).isTrue();
    }

    @Test
    void transportPatchRevisionMismatch409() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        Response r = api.patchTransport(tenant, "EMP-1", "\"99\"", Map.of("pickupPoint", "Gate 4"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("errorCode")).isEqualTo("REVISION_MISMATCH");
        assertThat(api.patchTransport(tenant, "EMP-404", "1", Map.of("pickupPoint", "Gate 4")).status()).isEqualTo(404);
        assertThat(api.patchTransport(tenant, "EMP-1", null, Map.of("pickupPoint", "Gate 4")).status()).isEqualTo(422);
    }

    @Test
    void officeTransferFlagsPickupReview() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1", hr("BLR-A", "MORNING")));
        api.patchTransport(tenant, "EMP-1", "1", Map.of("pickupPoint", "Gate 4"));
        api.postEvent(tenant, upsert("e-2", 2, "EMP-1", hr("GGN-A", "MORNING")));

        String payload = db.sql("SELECT payload::text FROM outbox_event WHERE tenant_id = ? ORDER BY aggregate_revision DESC LIMIT 1")
                .param(tenant).query(String.class).single();
        JsonNode event = json.readTree(payload);
        assertThat(event.get("eventType").asString()).isEqualTo("EmployeeUpdated");
        assertThat(event.get("changedFields").valueStream().map(JsonNode::asString))
                .containsExactly("officeCode", "PICKUP_REVIEW_NEEDED");
        assertThat(api.getEmployee(tenant, "EMP-1").text("pickupPoint")).isEqualTo("Gate 4");
    }
}
