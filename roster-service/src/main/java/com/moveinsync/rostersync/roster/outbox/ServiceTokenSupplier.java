package com.moveinsync.rostersync.roster.outbox;

/** Supplies the bearer token the relay presents to booking-service (role SERVICE). */
@FunctionalInterface
public interface ServiceTokenSupplier {

    String serviceToken();
}
