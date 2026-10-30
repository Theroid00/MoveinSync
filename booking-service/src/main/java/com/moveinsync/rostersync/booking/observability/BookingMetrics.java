package com.moveinsync.rostersync.booking.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** booking-service metrics (spec §11.1); labels are bounded consumer results only. */
@Component
public class BookingMetrics {

    private final MeterRegistry registry;
    private final Counter cancellations;
    private final Counter seatsReleased;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cancellations = Counter.builder("booking.cancellations").description("Bookings cancelled by deactivations")
                .register(registry);
        this.seatsReleased = Counter.builder("booking.seats.released").description("Seat reservations released")
                .register(registry);
        for (String result : new String[] {"processed", "duplicate", "skipped_older"}) {
            registry.counter("booking.consumer.events", "result", result);
        }
    }

    public void consumed(String outcome, int cancelled, int released) {
        registry.counter("booking.consumer.events", "result", outcome.toLowerCase()).increment();
        cancellations.increment(cancelled);
        seatsReleased.increment(released);
    }
}
