package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.moveinsync.rostersync.booking.consumer.EmployeeEventConsumer;
import com.moveinsync.rostersync.booking.security.Caller;
import com.moveinsync.rostersync.booking.service.BookingService;
import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Creating a booking races the employee's deactivation event 100 times. Both lock the projection row,
 * so either the booking is refused or it is created and then cancelled: never an ACTIVE seat for an
 * inactive employee.
 */
class BookingRaceIT extends AbstractBookingApiIT {

    @Autowired
    BookingService bookings;

    @Autowired
    EmployeeEventConsumer consumer;

    @Test
    void noActiveReservationForAnInactiveEmployee() {
        Caller admin = new Caller(tenant, "admin", Set.of("ADMIN"), null);
        UUID trip = futureTrip(100, 2);
        int refused = 0;
        try (Race race = new Race(2)) {
            for (int i = 0; i < 100; i++) {
                String employee = "EMP-" + i;
                activate(employee);
                var outcomes = race.run(t -> t == 0
                        ? () -> bookings.book(admin, employee, trip)
                        : () -> consumer.consume(BookingApi.event(tenant, employee, EventType.EmployeeDeactivated, 2, false, Instant.now())));
                Race.Outcome<?> booking = outcomes.get(0);
                if (booking.failed()) {
                    assertThat(booking.error()).isInstanceOf(DomainException.class);
                    assertThat(((DomainException) booking.error()).code()).isEqualTo(ErrorCode.EMPLOYEE_INACTIVE);
                    refused++;
                }
                assertThat(outcomes.get(1).failed()).isFalse();
                assertThat(db.sql("""
                        SELECT count(*) FROM seat_reservation s JOIN booking b ON b.booking_id = s.booking_id
                        WHERE b.tenant_id = ? AND b.employee_id = ? AND s.state = 'ACTIVE'""").params(tenant, employee)
                        .query(Long.class).single()).as("iteration %d", i).isZero();
                assertThat(db.sql("SELECT active FROM employee_projection WHERE tenant_id = ? AND employee_id = ?")
                        .params(tenant, employee).query(Boolean.class).single()).isFalse();
            }
        }
        assertThat(refused).isBetween(0, 100);
    }
}
