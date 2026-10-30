package com.moveinsync.rostersync.roster.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import com.moveinsync.rostersync.roster.core.RetryPolicy;
import com.moveinsync.rostersync.roster.fault.FaultInjector;
import com.moveinsync.rostersync.roster.observability.Mdc;
import com.moveinsync.rostersync.roster.observability.RosterMetrics;
import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository.Claimed;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * At-least-once outbox relay (spec §10): claim a batch under a lease, send each event outside any
 * transaction on a small pool of virtual threads, then record the result guarded by the lease token.
 * Per-employee order holds because a row is only claimable once every earlier revision is delivered.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRelayRepository repository;
    private final EventPublisher publisher;
    private final RelayProperties properties;
    private final RosterMetrics metrics;
    private final FaultInjector faults;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofVirtual().name("relay-", 0).factory());

    public OutboxRelay(OutboxRelayRepository repository, EventPublisher publisher, RelayProperties properties,
                       RosterMetrics metrics, FaultInjector faults) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
        this.faults = faults;
    }

    /** One poll: returns the number of events claimed. */
    public int runOnce() {
        UUID token = UUID.randomUUID();
        List<Claimed> claimed = repository.claim(token, properties.batch(), properties.leaseSecs());
        if (claimed.isEmpty()) {
            return 0;
        }
        faults.hit("RELAY_AFTER_CLAIM");          // claims committed, nothing sent: leases must expire and be reclaimed
        List<Callable<Void>> sends = new ArrayList<>(claimed.size());
        for (Claimed event : claimed) {
            sends.add(() -> {
                deliver(event, token);
                return null;
            });
        }
        try {
            workers.invokeAll(sends);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return claimed.size();
    }

    private void deliver(Claimed event, UUID token) {
        try (Mdc.Scope scope = Mdc.outbox(event.eventId(), event.employeeId())) {
            deliverInScope(event, token);
        }
    }

    private void deliverInScope(Claimed event, UUID token) {
        PublishResult result = publisher.publish(event.eventId(), event.payload());
        metrics.deliveryAttempt(result.verdict().name());
        boolean acknowledged = switch (result.verdict()) {
            case DELIVERED -> {
                metrics.deliveryLag(Duration.between(event.createdAt(), Instant.now()));
                yield repository.markDelivered(event.id(), token);
            }
            case DEAD -> deadLetter(event, token, result.errorCode());
            case RETRY -> {
                if (RetryPolicy.exhausted(event.attempts(), properties.maxAttempts())) {
                    yield deadLetter(event, token, result.errorCode());
                }
                metrics.retry();
                yield repository.markRetry(event.id(), token, result.errorCode(),
                        RetryPolicy.backoffSeconds(event.attempts(), ThreadLocalRandom.current().nextDouble()));
            }
        };
        if (!acknowledged) {
            log.warn("event=relay_stale_lease outboxId={} eventId={}", event.id(), event.eventId());
        }
    }

    private boolean deadLetter(Claimed event, UUID token, String errorCode) {
        metrics.deadLetter();
        log.warn("event=relay_dead_letter outboxId={} eventId={} employeeId={} attempts={} code={}",
                event.id(), event.eventId(), event.employeeId(), event.attempts(), errorCode);
        return repository.markDead(event.id(), token, errorCode);
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
