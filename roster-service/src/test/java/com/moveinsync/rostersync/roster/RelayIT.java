package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.deactivate;
import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.roster.StubBookingServer.Status;
import com.moveinsync.rostersync.roster.outbox.OutboxRelay;
import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** The outbox relay against a stub consumer: delivery, retries, dead letters, leases and ordering. */
class RelayIT extends AbstractApiIT {

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

    @Autowired
    OutboxRelayRepository repository;

    record Row(long id, UUID eventId, String employeeId, long revision, String state, int attempts, String lastError) {
    }

    @BeforeEach
    void cleanQueue() {
        STUB.reset();
        // Rows left by earlier test classes (relay disabled there) must not occupy this test's batches.
        db.sql("UPDATE outbox_event SET state = 'DELIVERED' WHERE tenant_id <> ? AND state <> 'DELIVERED'").param(tenant).update();
    }

    List<Row> rows() {
        return db.sql("""
                SELECT id, event_id, employee_id, aggregate_revision, state, attempts, last_error_code
                FROM outbox_event WHERE tenant_id = ? ORDER BY id""").param(tenant)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getInt(6), rs.getString(7)))
                .list();
    }

    Row only() {
        List<Row> rows = rows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    void makeDue() {
        db.sql("UPDATE outbox_event SET next_attempt_at = now() WHERE tenant_id = ? AND state = 'RETRY'").param(tenant).update();
    }

    void drain() {
        for (int i = 0; i < 50 && relay.runOnce() > 0; i++) {
            // until nothing is claimable
        }
    }

    JsonNode body(StubBookingServer.Received r) {
        return json.readTree(r.body());
    }

    double secondsUntilNextAttempt() {
        return db.sql("SELECT EXTRACT(EPOCH FROM next_attempt_at - now())::float8 FROM outbox_event WHERE tenant_id = ?")
                .param(tenant).query(Double.class).single();
    }

    @Test
    void deliversAndMarksDelivered() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        assertThat(relay.runOnce()).isEqualTo(1);

        Row row = only();
        assertThat(row.state()).isEqualTo("DELIVERED");
        assertThat(row.attempts()).isEqualTo(1);
        assertThat(STUB.received()).hasSize(1);
        StubBookingServer.Received sent = STUB.received().getFirst();
        assertThat(sent.idempotencyKey()).isEqualTo(row.eventId().toString());
        assertThat(body(sent).get("eventId").asString()).isEqualTo(row.eventId().toString());
        assertThat(body(sent).get("employeeId").asString()).isEqualTo("EMP-1");
        assertThat(db.sql("SELECT delivered_at IS NOT NULL AND lease_token IS NULL FROM outbox_event WHERE tenant_id = ?")
                .param(tenant).query(Boolean.class).single()).isTrue();
    }

    @Test
    void retriesWithBackoffOn500() {
        STUB.respondAlways(500);
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        relay.runOnce();
        Row first = only();
        assertThat(first.state()).isEqualTo("RETRY");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.lastError()).isEqualTo("HTTP_500");
        assertThat(secondsUntilNextAttempt()).isBetween(1.3, 2.4);          // 2^1 s ± 20%

        assertThat(relay.runOnce()).isZero();                                  // not due yet
        makeDue();
        relay.runOnce();
        assertThat(only().attempts()).isEqualTo(2);
        assertThat(secondsUntilNextAttempt()).isBetween(3.0, 4.8);          // 2^2 s ± 20%
        assertThat(STUB.received()).hasSize(2);
    }

    @Test
    void deadLettersAfterMaxAttempts() {
        STUB.respondAlways(503);
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        for (int i = 0; i < 10; i++) {
            assertThat(relay.runOnce()).isEqualTo(1);
            makeDue();
        }
        Row row = only();
        assertThat(row.state()).isEqualTo("DEAD");
        assertThat(row.attempts()).isEqualTo(10);
        assertThat(row.lastError()).isEqualTo("HTTP_503");
        assertThat(relay.runOnce()).isZero();
        assertThat(STUB.received()).hasSize(10);

        var status = api.outboxStatus(tenant);
        assertThat(status.body().get("counts").get("DEAD").asLong()).isEqualTo(1);
    }

    @Test
    void fourxxGoesDeadImmediately() {
        STUB.respond(r -> new Status(body(r).get("employeeId").asString().equals("EMP-429") ? 429 : 422));
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        api.postEvent(tenant, upsert("e-2", 1, "EMP-429"));
        relay.runOnce();
        Map<String, Row> byEmployee = rows().stream().collect(java.util.stream.Collectors.toMap(Row::employeeId, r -> r));
        assertThat(byEmployee.get("EMP-1").state()).isEqualTo("DEAD");
        assertThat(byEmployee.get("EMP-1").attempts()).isEqualTo(1);
        assertThat(byEmployee.get("EMP-1").lastError()).isEqualTo("HTTP_422");
        assertThat(byEmployee.get("EMP-429").state()).isEqualTo("RETRY");      // 429 is transient
    }

    @Test
    void expiredLeaseIsReclaimed() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        // A relay claims the row and dies before sending.
        assertThat(repository.claim(UUID.randomUUID(), 100, 30)).hasSize(1);
        assertThat(relay.runOnce()).isZero();                                  // lease still valid
        db.sql("UPDATE outbox_event SET lease_until = now() - interval '1 second' WHERE tenant_id = ?").param(tenant).update();

        assertThat(relay.runOnce()).isEqualTo(1);
        Row row = only();
        assertThat(row.state()).isEqualTo("DELIVERED");
        assertThat(row.attempts()).isEqualTo(2);                               // the lost claim counts: it may have sent
    }

    @Test
    void staleLeaseCannotAck() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        UUID first = UUID.randomUUID();
        long id = repository.claim(first, 100, 30).getFirst().id();
        db.sql("UPDATE outbox_event SET lease_until = now() - interval '1 second' WHERE id = ?").param(id).update();
        UUID second = UUID.randomUUID();
        assertThat(repository.claim(second, 100, 30)).hasSize(1);

        assertThat(repository.markDelivered(id, first)).isFalse();
        assertThat(repository.markDead(id, first, "HTTP_500")).isFalse();
        assertThat(only().state()).isEqualTo("CLAIMED");
        assertThat(repository.markDelivered(id, second)).isTrue();
        assertThat(only().state()).isEqualTo("DELIVERED");
    }

    @Test
    void perEmployeeOrderPreserved() {
        api.postEvent(tenant, upsert("a1", 1, "EMP-A"));
        api.postEvent(tenant, upsert("b1", 2, "EMP-B"));
        api.postEvent(tenant, upsert("a2", 3, "EMP-A", hr("BLR-B", "MORNING")));
        api.postEvent(tenant, upsert("a3", 4, "EMP-A", hr("BLR-B", "NIGHT")));
        api.postEvent(tenant, upsert("b2", 5, "EMP-B", hr("GGN-A", "NIGHT")));
        api.postEvent(tenant, deactivate("a4", 6, "EMP-A"));
        api.postEvent(tenant, upsert("a5", 7, "EMP-A"));
        // Every other delivery fails once, so retries interleave with first attempts.
        STUB.respond(r -> new Status(r.call() % 2 == 0 ? 500 : 200));

        for (int i = 0; i < 100 && rows().stream().anyMatch(r -> !r.state().equals("DELIVERED")); i++) {
            relay.runOnce();
            makeDue();
        }
        assertThat(rows()).allMatch(r -> r.state().equals("DELIVERED"));
        for (String employee : List.of("EMP-A", "EMP-B")) {
            List<Long> delivered = STUB.received().stream().map(this::body)
                    .filter(b -> b.get("employeeId").asString().equals(employee))
                    .map(b -> b.get("aggregateRevision").asLong()).distinct().toList();
            assertThat(delivered).as(employee).isSorted();
        }
    }

    @Test
    void deadPredecessorBlocksSuccessorOnly() {
        STUB.respond(r -> {
            JsonNode b = body(r);
            return new Status(b.get("employeeId").asString().equals("EMP-A") && b.get("aggregateRevision").asLong() == 1 ? 404 : 200);
        });
        api.postEvent(tenant, upsert("a1", 1, "EMP-A"));
        api.postEvent(tenant, upsert("a2", 2, "EMP-A", hr("GGN-A", "NIGHT")));
        api.postEvent(tenant, upsert("b1", 3, "EMP-B"));
        drain();

        Map<String, String> states = rows().stream().collect(java.util.stream.Collectors.toMap(
                r -> r.employeeId() + "#" + r.revision(), Row::state));
        assertThat(states).containsEntry("EMP-A#1", "DEAD").containsEntry("EMP-A#2", "PENDING")
                .containsEntry("EMP-B#1", "DELIVERED");
        assertThat(STUB.received().stream().map(this::body)
                .filter(b -> b.get("employeeId").asString().equals("EMP-A") && b.get("aggregateRevision").asLong() == 2))
                .isEmpty();
    }

    @Test
    void replayDeadEventSameEventId() {
        STUB.respondAlways(404);
        api.postEvent(tenant, upsert("a1", 1, "EMP-A"));
        api.postEvent(tenant, upsert("a2", 2, "EMP-A", hr("GGN-A", "NIGHT")));
        drain();
        Row dead = rows().getFirst();
        assertThat(dead.state()).isEqualTo("DEAD");

        assertThat(api.replayOutbox(tenant, dead.id()).status()).isEqualTo(202);
        assertThat(rows().getFirst().state()).isEqualTo("PENDING");
        assertThat(rows().getFirst().attempts()).isZero();
        assertThat(api.replayOutbox(tenant, dead.id()).status()).isEqualTo(409);              // not DEAD any more
        assertThat(api.replayOutbox(newTenant(), dead.id()).status()).isEqualTo(404);          // another tenant

        STUB.respondAlways(200);
        drain();
        assertThat(rows()).allMatch(r -> r.state().equals("DELIVERED"));
        assertThat(STUB.received().stream().filter(r -> dead.eventId().toString().equals(r.idempotencyKey())))
                .hasSize(2);                                                                    // same eventId both times
    }

    @Test
    void attemptsCountEverySend() {
        STUB.respond(r -> new Status(r.call() == 1 ? 500 : 200));
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        relay.runOnce();
        assertThat(only().state()).isEqualTo("RETRY");
        makeDue();
        relay.runOnce();
        Row row = only();
        assertThat(row.state()).isEqualTo("DELIVERED");
        assertThat(row.attempts()).isEqualTo(2);
    }
}
