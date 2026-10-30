package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.deactivate;
import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.moveinsync.rostersync.roster.StubBookingServer.Status;
import com.moveinsync.rostersync.roster.observability.OutboxMetrics;
import com.moveinsync.rostersync.roster.outbox.OutboxRelay;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Every roster metric of spec §13.5 is exposed on /actuator/prometheus after a representative scenario. */
class MetricsIT extends AbstractReconIT {

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
    OutboxMetrics outboxMetrics;

    @Autowired
    TaskScheduler scheduler;

    @Test
    void queueMetricsCanPollWhileTheRelaySchedulerIsBusy() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1), polled = new CountDownLatch(1);
        var busy = scheduler.schedule(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        try {
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.schedule(() -> { outboxMetrics.poll(); polled.countDown(); }, Instant.now());
            assertThat(polled.await(5, TimeUnit.SECONDS)).as("queue poll runs while another scheduled task is blocked").isTrue();
        } finally {
            release.countDown();
            busy.get(5, TimeUnit.SECONDS);
        }
    }

    static final List<String> NAMES = List.of(
            "roster_events_total", "roster_event_apply_seconds_bucket", "roster_validation_rejections_total",
            "outbox_queue_depth", "outbox_oldest_pending_age_seconds", "outbox_delivery_attempts_total",
            "outbox_retries_total", "outbox_dead_total", "outbox_delivery_lag_seconds_bucket",
            "reconciliation_phase_seconds", "reconciliation_runs_total", "roster_safety_brake_trips_total",
            "roster_reconciliation_lock_seconds", "cache_gets_total");

    @Test
    void everyMetricIsExposedAfterAScenario() {
        // Ingest: every outcome.
        api.postEvent(tenant, upsert("m-1", 5, "EMP-1"));
        api.postEvent(tenant, upsert("m-1", 5, "EMP-1"));                                       // DUPLICATE
        api.postEvent(tenant, upsert("m-2", 4, "EMP-1"));                                       // STALE
        api.postEvent(tenant, upsert("m-3", 5, "EMP-1", hr("GGN-A", "NIGHT")));                 // CONFLICT
        api.postEvent(tenant, withHr(upsert("m-4", 1, "EMP-2"), "phone", "x"));                // REJECTED
        api.postEvent(tenant, deactivate("m-5", 9, "EMP-3"));                                   // TOMBSTONED
        api.postEvent(tenant, upsert("m-6", 1, "EMP-DEAD"));

        // Relay: a retry, a delivery with lag, a dead letter.
        db.sql("UPDATE outbox_event SET state = 'DELIVERED' WHERE tenant_id <> ? AND state <> 'DELIVERED'").param(tenant).update();
        STUB.respond(r -> new Status(r.body().contains("EMP-DEAD") ? 404 : r.call() == 1 ? 500 : 200));
        relay.runOnce();
        db.sql("UPDATE outbox_event SET next_attempt_at = now() WHERE tenant_id = ? AND state = 'RETRY'").param(tenant).update();
        relay.runOnce();

        // Reconciliation (fresh tenant, so the API employees above are not "missing"): one completed run
        // and one stopped by the brake.
        String apiTenant = tenant;
        tenant = newTenant();
        initial(10, 100);
        assertThat(run(Snapshot.rows(1, 4), 200).get("status").asString()).isEqualTo("AWAITING_APPROVAL");

        outboxMetrics.poll();
        RosterApi.Response scrape = api.send(get("/actuator/prometheus"));
        assertThat(scrape.status()).isEqualTo(200);
        for (String name : NAMES) {
            assertThat(scrape.raw()).as(name).contains(name);
        }
        assertThat(scrape.raw()).contains("roster_events_total{outcome=\"TOMBSTONED\"", "outcome=\"REJECTED\"",
                "roster_validation_rejections_total{code=\"PHONE_E164\"", "reconciliation_runs_total{status=\"AWAITING_APPROVAL\"",
                "outbox_delivery_attempts_total{result=\"DEAD\"", "reconciliation_phase_seconds_count{phase=\"digest\"",
                "outbox_queue_depth{state=\"PENDING\"", "cache=\"officeCatalog\"");
        // Labels are bounded enums: no tenant or employee identifiers.
        assertThat(scrape.raw()).doesNotContain(apiTenant, tenant, "EMP-1", "EMP-DEAD");
    }
}
