package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.Test;

/** booking-service metrics of spec §11.1 are exposed after consuming a representative event mix. */
class MetricsIT extends AbstractBookingApiIT {

    @Test
    void consumerMetricsExposed() {
        activate("EMP-1");
        api.bookAsAdmin(tenant, "EMP-1", futureTrip(4, 2));
        EmployeeChangedEvent deactivation = BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 3, false, Instant.now());
        api.consume(deactivation);
        api.consume(deactivation);                                                                        // duplicate
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeUpdated, 2, true, Instant.now())); // older

        String scrape = api.send(get("/actuator/prometheus")).raw();
        assertThat(scrape).contains("booking_consumer_events_total{result=\"processed\"",
                "booking_consumer_events_total{result=\"duplicate\"", "booking_consumer_events_total{result=\"skipped_older\"",
                "booking_cancellations_total", "booking_seats_released_total");
        assertThat(scrape).doesNotContain(tenant, "EMP-1");
    }
}
