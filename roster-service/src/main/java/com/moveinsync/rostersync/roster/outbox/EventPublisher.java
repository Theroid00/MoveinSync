package com.moveinsync.rostersync.roster.outbox;

import java.util.UUID;

/**
 * Delivers one outbox payload downstream. HTTP today; a broker publisher would implement the same
 * interface (the swap point named in the design). Implementations never throw: failures are results.
 */
public interface EventPublisher {

    PublishResult publish(UUID eventId, String payloadJson);
}
