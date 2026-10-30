package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.deactivate;
import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import org.junit.jupiter.api.Test;

/** The brief's idempotency proof: replaying the same feed changes nothing. */
class ReplayIT extends AbstractApiIT {

    static final String[] OFFICES = {"BLR-A", "BLR-B", "GGN-A"};
    static final String[] SHIFTS = {"MORNING", "EVENING", "NIGHT"};

    @Test
    void replayFeedTwiceChangesNothing() {
        List<Map<String, Object>> feed = feed(500, new Random(42));
        String ndjson = toNdjson(feed);

        Response first = api.postBatch(tenant, ndjson);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("summary").get("APPLIED").asInt()).isPositive();
        String before = snapshot();

        Response second = api.postBatch(tenant, ndjson);
        assertThat(second.body().get("summary").get("DUPLICATE").asInt()).isEqualTo(500);
        assertThat(snapshot()).isEqualTo(before);

        // Re-sent with fresh event ids: state identity (seq + hash) still makes it a no-op.
        List<Map<String, Object>> resent = new ArrayList<>();
        for (Map<String, Object> e : feed) {
            Map<String, Object> copy = new LinkedHashMap<>(e);
            copy.put("eventId", "resent-" + e.get("eventId"));
            resent.add(copy);
        }
        Response third = api.postBatch(tenant, toNdjson(resent));
        assertThat(third.body().get("summary").get("APPLIED").asInt()).isZero();
        assertThat(third.body().get("summary").get("TOMBSTONED").asInt()).isZero();
        assertThat(third.body().get("summary").get("CONFLICT").asInt()).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    /** 100 employees × 5 events (some deactivations), shuffled so many arrive out of order. */
    static List<Map<String, Object>> feed(int size, Random rnd) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String employee = "EMP-" + (i % 100);
            long seq = i + 1;
            events.add(rnd.nextInt(10) == 0
                    ? deactivate("r-" + i, seq, employee)
                    : upsert("r-" + i, seq, employee, hr(OFFICES[rnd.nextInt(3)], SHIFTS[rnd.nextInt(3)])));
        }
        Collections.shuffle(events, rnd);
        return events;
    }

    String toNdjson(List<Map<String, Object>> events) {
        StringBuilder sb = new StringBuilder();
        events.forEach(e -> sb.append(json.writeValueAsString(e)).append('\n'));
        return sb.toString();
    }

    /** md5 of every employee row (incl. revisions and timestamps) + tombstones, plus outbox/history counts. */
    String snapshot() {
        return db.sql("""
                SELECT coalesce((SELECT md5(string_agg(e::text, ',' ORDER BY e.employee_id)) FROM employee e WHERE e.tenant_id = :t), '')
                    || coalesce((SELECT md5(string_agg(x::text, ',' ORDER BY x.employee_id)) FROM employee_tombstone x WHERE x.tenant_id = :t), '')
                    || '|' || (SELECT count(*) FROM outbox_event WHERE tenant_id = :t)
                    || '|' || (SELECT count(*) FROM employee_history WHERE tenant_id = :t)""")
                .param("t", tenant).query(String.class).single();
    }
}
