package com.moveinsync.rostersync.roster.observability;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Queue depth by state and the oldest pending age, polled every 5 s across all tenants. */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);
    static final List<String> STATES = List.of("PENDING", "CLAIMED", "RETRY", "DEAD", "DELIVERED");

    private final OutboxRelayRepository outbox;
    private final Map<String, AtomicLong> depth = new ConcurrentHashMap<>();
    private final AtomicReference<Double> oldestPendingAge = new AtomicReference<>(0.0);

    public OutboxMetrics(OutboxRelayRepository outbox, MeterRegistry registry) {
        this.outbox = outbox;
        for (String state : STATES) {
            AtomicLong value = depth.computeIfAbsent(state, s -> new AtomicLong());
            Gauge.builder("outbox.queue.depth", value, AtomicLong::get).tag("state", state)
                    .description("Outbox rows by state").register(registry);
        }
        Gauge.builder("outbox.oldest.pending.age", oldestPendingAge, AtomicReference::get).baseUnit("seconds")
                .description("Age of the oldest undelivered outbox row").register(registry);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 1000)
    public void poll() {
        try {
            Map<String, Long> counts = outbox.countsByStateAllTenants();
            for (String state : STATES) {
                depth.get(state).set(counts.getOrDefault(state, 0L));
            }
            oldestPendingAge.set(outbox.oldestPendingAgeSeconds(null));
        } catch (RuntimeException e) {
            log.warn("event=outbox_metrics_poll_failed exception={}", e.getClass().getSimpleName());
        }
    }
}
