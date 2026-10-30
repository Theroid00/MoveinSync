package com.moveinsync.rostersync.roster.outbox;

import com.moveinsync.rostersync.roster.repository.OutboxRelayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the relay on a fixed delay and purges old delivered rows; off when app.relay.enabled=false. */
@Component
@ConditionalOnProperty(name = "app.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay relay;
    private final OutboxRelayRepository repository;
    private final RelayProperties properties;

    public OutboxRelayScheduler(OutboxRelay relay, OutboxRelayRepository repository, RelayProperties properties) {
        this.relay = relay;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.relay.poll-ms:200}")
    void poll() {
        try {
            while (relay.runOnce() == properties.batch()) {
                // a full batch: keep draining without waiting for the next tick
            }
        } catch (RuntimeException e) {
            log.warn("event=relay_poll_failed exception={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purge() {
        int purged = repository.purgeDelivered(properties.retentionDays());
        log.info("event=outbox_purged rows={} retentionDays={}", purged, properties.retentionDays());
    }
}
