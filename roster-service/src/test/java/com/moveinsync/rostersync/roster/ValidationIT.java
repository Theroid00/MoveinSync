package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ValidationIT extends AbstractApiIT {

    @Test
    void malformedPhoneRejectedWithCode() {
        Response r = api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "phone", "98450-12345"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.text("errorCode")).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().get("errors").get(0).get("field").asString()).isEqualTo("phone");
        assertThat(r.body().get("errors").get(0).get("code").asString()).isEqualTo("PHONE_E164");
        assertThat(r.raw()).doesNotContain("98450-12345");
        assertThat(count("employee", tenant)).isZero();
    }

    @Test
    void unknownOfficeRejectedWithCode() {
        Response r = api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "officeCode", "PUNE-Z"));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().get("errors").get(0).get("code").asString()).isEqualTo("OFFICE_UNKNOWN");
        assertThat(r.raw()).doesNotContain("PUNE-Z");
    }

    @Test
    void oneBadRecordDoesNotFailBatch() {
        Set<Integer> bad = Set.of(10, 50, 99);
        StringBuilder ndjson = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            Object event = bad.contains(i)
                    ? withHr(upsert("b-" + i, i, "EMP-" + i), "phone", "not-a-phone")
                    : upsert("b-" + i, i, "EMP-" + i);
            ndjson.append(json.writeValueAsString(event)).append('\n');
        }
        Response r = api.postBatch(tenant, ndjson.toString());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("summary").get("APPLIED").asInt()).isEqualTo(97);
        assertThat(r.body().get("summary").get("REJECTED").asInt()).isEqualTo(3);
        Set<Integer> rejectedLines = new HashSet<>();
        for (JsonNode line : r.body().get("results")) {
            if (line.get("outcome").asString().equals("REJECTED")) {
                rejectedLines.add(line.get("line").asInt());
                assertThat(line.get("errors").get(0).get("code").asString()).isEqualTo("PHONE_E164");
            }
        }
        assertThat(rejectedLines).isEqualTo(bad);
        assertThat(count("employee", tenant)).isEqualTo(97);
        assertThat(count("outbox_event", tenant)).isEqualTo(97);
    }

    @Test
    void errorBodiesNeverEchoValues() {
        String marker = "Leaky Value 7731";
        Response single = api.postEvent(tenant, withHr(withHr(upsert("e-1", 1, "EMP-1"),
                "name", marker.repeat(20)), "email", "leaky7731@@example"));
        assertThat(single.status()).isEqualTo(422);
        assertThat(single.raw()).doesNotContain("Leaky", "leaky7731");

        Response unknown = api.postEvent(tenant, withHr(upsert("e-2", 2, "EMP-2"), "shoeSize", marker));
        assertThat(unknown.status()).isEqualTo(422);
        assertThat(unknown.text("errorCode")).isEqualTo("UNKNOWN_FIELD");
        assertThat(unknown.raw()).doesNotContain("Leaky");

        Response batch = api.postBatch(tenant, "{\"eventId\": \"" + marker + "\", broken\n"
                + json.writeValueAsString(withHr(upsert("e-3", 3, "EMP-3"), "phone", marker)) + "\n");
        assertThat(batch.status()).isEqualTo(200);
        assertThat(batch.raw()).doesNotContain("Leaky");
        assertThat(batch.body().get("results").get(0).get("errors").get(0).get("code").asString()).isEqualTo("MALFORMED_JSON");

        assertThat(db.sql("SELECT string_agg(coalesce(event_id,'') || coalesce(field,'') || code, ',') FROM record_error WHERE tenant_id = ?")
                .param(tenant).query(String.class).single()).doesNotContain("Leaky", "leaky7731");
    }
}
