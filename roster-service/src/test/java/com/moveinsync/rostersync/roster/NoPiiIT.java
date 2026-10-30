package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import com.moveinsync.rostersync.roster.Snapshot.Row;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.outbox.OutboxRelay;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Canary personal data pushed through every flow, valid and failing, must never appear in logs, error
 * bodies, outbox payloads or record_error rows; neither may anything shaped like an email or phone.
 */
@ExtendWith(OutputCaptureExtension.class)
class NoPiiIT extends AbstractReconIT {

    static final String NAME = "Zyxw Canary";
    static final String EMAIL = "canary.pii.7731@example.test";
    static final String PHONE = "+919999977731";
    static final String PICKUP = "Canary Lane 7731";
    static final List<String> CANARIES = List.of(NAME, EMAIL, PHONE, PICKUP, "Zyxw", "canary.pii", "99999777");
    static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s@\"]+@[^\\s@\"]+\\.[a-z]+");
    static final Pattern PHONE_SHAPE = Pattern.compile("\\+\\d{10,15}");

    static final StubBookingServer STUB = StubBookingServer.start();

    @DynamicPropertySource
    static void bookingUrl(DynamicPropertyRegistry registry) {
        registry.add("app.booking.base-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    OutboxRelay relay;

    static Map<String, Object> canaryHr(String office, String shift) {
        Map<String, Object> hr = new LinkedHashMap<>();
        hr.put("name", NAME);
        hr.put("email", EMAIL);
        hr.put("phone", PHONE);
        hr.put("managerId", null);
        hr.put("officeCode", office);
        hr.put("shiftCode", shift);
        return hr;
    }

    @Test
    void canariesNeverLeak(CapturedOutput output) {
        List<String> errorBodies = new ArrayList<>();

        // Valid ingest, then the failing variants.
        assertThat(api.postEvent(tenant, upsert("pii-1", 1, "EMP-C", canaryHr("BLR-A", "EVENING"))).text("outcome")).isEqualTo("APPLIED");
        errorBodies.add(api.postEvent(tenant, withHr(upsert("pii-2", 2, "EMP-C", canaryHr("NOPE-9", "EVENING")), "phone", PHONE + "x")).raw());
        errorBodies.add(api.postEvent(tenant, withHr(upsert("pii-3", 3, "EMP-C", canaryHr("BLR-A", "EVENING")), "pickupPoint", PICKUP)).raw());
        errorBodies.add(api.postEvent(tenant, upsert("pii-4", 1, "EMP-C", canaryHr("GGN-A", "NIGHT"))).raw());          // version conflict
        errorBodies.add(api.postEvent(tenant, upsert("pii-1", 1, "EMP-C", canaryHr("GGN-A", "NIGHT"))).raw());          // event id reused
        errorBodies.add(api.postBatch(tenant, "{\"eventId\":\"" + EMAIL + "\",\"hr\":{\"name\":\"" + NAME + "\"\n").raw());

        // Transport edits (valid canary pickup, then an HR field smuggled in).
        assertThat(api.patchTransport(tenant, "EMP-C", "1", Map.of("pickupPoint", PICKUP)).status()).isEqualTo(200);
        errorBodies.add(api.patchTransport(tenant, "EMP-C", "2", Map.of("pickupPoint", PICKUP, "email", EMAIL)).raw());

        // Reconciliation with a valid canary row and an invalid one.
        List<Row> rows = new ArrayList<>(Snapshot.rows(1, 5));
        rows.add(new Row("EMP-C", NAME, EMAIL, PHONE, "", "BLR-A", "EVENING", "50"));
        rows.add(new Row("EMP-D", NAME, EMAIL, PHONE + "9999", "", "NOPE-9", "EVENING", "51"));
        String runId = run(rows, 100).get("runId").asString();
        errorBodies.add(api.runErrors(tenant, runId).raw());

        // Relay everything to the stub consumer.
        db.sql("UPDATE outbox_event SET state = 'DELIVERED' WHERE tenant_id <> ? AND state <> 'DELIVERED'").param(tenant).update();
        while (relay.runOnce() > 0) {
            // drain
        }

        // Defence in depth: logging a whole HR object is dropped by the PII guard.
        LoggerFactory.getLogger(NoPiiIT.class).warn("accidental dto={}", new HrRecord(NAME, EMAIL, PHONE, null, "BLR-A", "EVENING"));

        List<String> payloads = db.sql("SELECT payload::text FROM outbox_event WHERE tenant_id = ?").param(tenant).query(String.class).list();
        String errorRows = db.sql("SELECT coalesce(string_agg(r::text, ' '), '') FROM record_error r WHERE tenant_id = ?")
                .param(tenant).query(String.class).single();
        String sent = String.join("\n", STUB.received().stream().map(StubBookingServer.Received::body).toList());

        assertThat(payloads).isNotEmpty();
        assertThat(errorRows).isNotBlank();
        assertClean("logs", output.getAll());
        assertClean("error bodies", String.join("\n", errorBodies));
        assertClean("outbox payloads", String.join("\n", payloads));
        assertClean("payloads sent downstream", sent);
        assertClean("record_error rows", errorRows);
        assertThat(output.getAll()).doesNotContain("accidental dto");
    }

    static void assertClean(String where, String text) {
        for (String canary : CANARIES) {
            assertThat(text).as("%s must not contain %s", where, canary).doesNotContain(canary);
        }
        assertThat(EMAIL_SHAPE.matcher(text).results().map(m -> m.group()).toList()).as("%s: email-shaped text", where).isEmpty();
        assertThat(PHONE_SHAPE.matcher(text).results().map(m -> m.group()).toList()).as("%s: phone-shaped text", where).isEmpty();
    }
}
