package com.moveinsync.rostersync.roster.observability;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * roster-service metrics (spec §13.5), named so the Prometheus exposition matches exactly
 * (e.g. counter "roster.events" → roster_events_total). Every label is a bounded enum value:
 * outcomes, validation codes, delivery results, phases and run statuses. Never ids or values.
 */
@Component
public class RosterMetrics {

    private final MeterRegistry registry;
    private final Timer applyTimer;
    private final Counter retries;
    private final Counter dead;
    private final Counter brakeTrips;
    private final Timer deliveryLag;
    private final Timer lockHeld;

    public RosterMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.applyTimer = Timer.builder("roster.event.apply").description("Single-event ingest latency")
                .publishPercentileHistogram().register(registry);
        this.retries = Counter.builder("outbox.retries").description("Deliveries scheduled for retry").register(registry);
        this.dead = Counter.builder("outbox.dead").description("Events moved to the dead-letter state").register(registry);
        this.brakeTrips = Counter.builder("roster.safety.brake.trips").description("Snapshots stopped by the safety brake")
                .register(registry);
        this.deliveryLag = Timer.builder("outbox.delivery.lag").description("Outbox row created → consumer acknowledged")
                .publishPercentileHistogram().maximumExpectedValue(Duration.ofHours(1)).register(registry);
        this.lockHeld = Timer.builder("roster.reconciliation.lock")
                .description("Time the exclusive tenant lock was held by a reconciliation apply").register(registry);
    }

    public void event(String outcome) {
        registry.counter("roster.events", "outcome", outcome).increment();
    }

    public void applyLatency(long nanos) {
        applyTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void rejection(String code) {
        registry.counter("roster.validation.rejections", "code", code).increment();
    }

    public void deliveryAttempt(String result) {
        registry.counter("outbox.delivery.attempts", "result", result).increment();
    }

    public void retry() {
        retries.increment();
    }

    public void deadLetter() {
        dead.increment();
    }

    public void deliveryLag(Duration lag) {
        deliveryLag.record(lag.isNegative() ? Duration.ZERO : lag);
    }

    public void reconciliationPhase(String phase, long millis) {
        Timer.builder("reconciliation.phase").tag("phase", phase).register(registry).record(millis, TimeUnit.MILLISECONDS);
    }

    public void reconciliationRun(String status) {
        registry.counter("reconciliation.runs", "status", status).increment();
    }

    public void brakeTrip() {
        brakeTrips.increment();
    }

    public void lockHeld(long nanos) {
        lockHeld.record(nanos, TimeUnit.NANOSECONDS);
    }
}
