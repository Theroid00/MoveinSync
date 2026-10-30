package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moveinsync.rostersync.roster.fault.FaultInjector;
import com.moveinsync.rostersync.roster.observability.RosterMetrics;
import com.moveinsync.rostersync.roster.outbox.EventPublisher;
import com.moveinsync.rostersync.roster.outbox.OutboxRelay;
import com.moveinsync.rostersync.roster.outbox.RelayProperties;
import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Three relay instances compete for 5,000 events (500 employees × 10 revisions). SKIP LOCKED splits the
 * queue; every event is delivered at least once and each employee's revisions arrive in order.
 */
class RelayCompetitionIT extends AbstractApiIT {

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
    OutboxRelayRepository repository;

    @Autowired
    EventPublisher publisher;

    @Autowired
    RelayProperties properties;

    @Autowired
    RosterMetrics metrics;

    @Autowired
    FaultInjector faults;

    long undelivered() {
        return db.sql("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND state <> 'DELIVERED'").param(tenant)
                .query(Long.class).single();
    }

    @Test
    void threeRelaysDeliverEverythingInOrder() {
        db.sql("UPDATE outbox_event SET state = 'DELIVERED' WHERE tenant_id <> ? AND state <> 'DELIVERED'").param(tenant).update();
        db.sql("""
                INSERT INTO outbox_event (event_id, tenant_id, employee_id, aggregate_revision, event_type, payload)
                SELECT gen_random_uuid(), :t, 'EMP-' || e, r, 'EmployeeUpdated',
                       jsonb_build_object('employeeId', 'EMP-' || e, 'aggregateRevision', r)
                FROM generate_series(1, 10) r, generate_series(1, 500) e
                ORDER BY r, e""").param("t", tenant).update();
        assertThat(undelivered()).isEqualTo(5000);

        List<OutboxRelay> relays = List.of(new OutboxRelay(repository, publisher, properties, metrics, faults),
                new OutboxRelay(repository, publisher, properties, metrics, faults),
                new OutboxRelay(repository, publisher, properties, metrics, faults));
        try (Race race = new Race(3)) {
            var outcomes = race.run(t -> () -> {
                for (int round = 0; round < 10_000 && undelivered() > 0; round++) {
                    relays.get(t).runOnce();
                }
                return null;
            });
            assertThat(outcomes).noneMatch(Race.Outcome::failed);
        } finally {
            relays.forEach(OutboxRelay::shutdown);
        }

        assertThat(undelivered()).isZero();
        Set<String> eventIds = new HashSet<>(db.sql("SELECT event_id::text FROM outbox_event WHERE tenant_id = ?")
                .param(tenant).query(String.class).list());
        Map<String, List<Long>> firstReceipts = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> received = new HashMap<>();
        for (StubBookingServer.Received r : STUB.received()) {
            received.merge(r.idempotencyKey(), 1, Integer::sum);
            if (seen.add(r.idempotencyKey())) {
                JsonNode body = json.readTree(r.body());
                firstReceipts.computeIfAbsent(body.get("employeeId").asString(), k -> new java.util.ArrayList<>())
                        .add(body.get("aggregateRevision").asLong());
            }
        }
        assertThat(received.keySet()).containsAll(eventIds);                     // every event received ≥ 1
        assertThat(firstReceipts).hasSize(500);
        firstReceipts.forEach((employee, revisions) ->
                assertThat(revisions).as(employee).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
        assertThat(eventIds).allMatch(id -> UUID.fromString(id) != null);
    }
}
