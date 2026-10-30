package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.Test;

/** The idempotent consumer: exactly-once effects under at-least-once delivery. */
class ConsumerIT extends AbstractBookingApiIT {

    UUID bookingOn(UUID tripId, String employeeId) {
        return UUID.fromString(api.bookAsAdmin(tenant, employeeId, tripId).text("bookingId"));
    }

    @Test
    void deactivationCancelsOnlyFutureReservedBookings() {
        activate("EMP-1");
        UUID a = bookingOn(futureTrip(4, 2), "EMP-1");
        UUID b = bookingOn(futureTrip(4, 3), "EMP-1");
        UUID past = pastBooking("EMP-1");
        activate("EMP-2");
        UUID other = bookingOn(futureTrip(4, 2), "EMP-2");

        var r = api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 2, false, Instant.now()));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("outcome")).isEqualTo("PROCESSED");

        assertThat(status(a)).isEqualTo("CANCELLED");
        assertThat(status(b)).isEqualTo("CANCELLED");
        assertThat(seatState(a)).isEqualTo("RELEASED");
        assertThat(seatState(b)).isEqualTo("RELEASED");
        assertThat(status(past)).isEqualTo("RESERVED");          // already started: never touched
        assertThat(seatState(past)).isEqualTo("ACTIVE");
        assertThat(status(other)).isEqualTo("RESERVED");         // other employee unaffected
        assertThat(db.sql("SELECT cancelled_reason FROM booking WHERE booking_id = ?").param(a).query(String.class).single())
                .isEqualTo("EMPLOYEE_DEACTIVATED");
    }

    @Test
    void seatsReleasedOnce() {
        activate("EMP-1");
        UUID tripId = futureTrip(4, 2);
        UUID a = bookingOn(tripId, "EMP-1");
        EmployeeChangedEvent deactivation = BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 2, false, Instant.now());
        api.consume(deactivation);
        Instant releasedAt = db.sql("SELECT released_at FROM seat_reservation WHERE booking_id = ?").param(a)
                .query(java.time.OffsetDateTime.class).single().toInstant();

        api.consume(deactivation);   // redelivery
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeVersionAdvanced, 3, false, Instant.now()));

        assertThat(db.sql("SELECT released_at FROM seat_reservation WHERE booking_id = ?").param(a)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(releasedAt);
        assertThat(api.seats(tenant, tripId).body().get("available").asInt()).isEqualTo(4);
        assertThat(db.sql("SELECT count(*) FROM booking WHERE tenant_id = ? AND cancelled_by_event = ?")
                .params(tenant, deactivation.eventId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void duplicateEventAbsorbed() {
        EmployeeChangedEvent created = BookingApi.event(tenant, "EMP-1", EventType.EmployeeCreated, 1, true, Instant.now());
        assertThat(api.consume(created).text("outcome")).isEqualTo("PROCESSED");
        assertThat(api.consume(created).text("outcome")).isEqualTo("DUPLICATE");
        assertThat(api.consume(created).text("outcome")).isEqualTo("DUPLICATE");
        assertThat(db.sql("SELECT count(*) FROM processed_message WHERE event_id = ?").param(created.eventId())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT outcome FROM processed_message WHERE event_id = ?").param(created.eventId())
                .query(String.class).single()).isEqualTo("PROCESSED");
    }

    @Test
    void olderRevisionSkipped() {
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeUpdated, 3, true, Instant.now()));
        var older = api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 2, false, Instant.now()));
        assertThat(older.text("outcome")).isEqualTo("SKIPPED_OLDER");
        assertThat(db.sql("SELECT active FROM employee_projection WHERE tenant_id = ? AND employee_id = 'EMP-1'")
                .param(tenant).query(Boolean.class).single()).isTrue();
        assertThat(db.sql("SELECT last_revision FROM employee_projection WHERE tenant_id = ? AND employee_id = 'EMP-1'")
                .param(tenant).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void reactivationDoesNotRestoreBookings() {
        activate("EMP-1");
        UUID a = bookingOn(futureTrip(4, 2), "EMP-1");
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 2, false, Instant.now()));
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeReactivated, 3, true, Instant.now()));

        assertThat(status(a)).isEqualTo("CANCELLED");
        assertThat(seatState(a)).isEqualTo("RELEASED");
        assertThat(db.sql("SELECT active FROM employee_projection WHERE tenant_id = ? AND employee_id = 'EMP-1'")
                .param(tenant).query(Boolean.class).single()).isTrue();
    }
}
