package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.Test;

class BookingApiIT extends AbstractBookingApiIT {

    @Test
    void inactiveEmployeeCannotBook() {
        UUID tripId = futureTrip(4, 1);
        var unknown = api.bookAsAdmin(tenant, "EMP-NEVER", tripId);
        assertThat(unknown.status()).isEqualTo(409);
        assertThat(unknown.text("errorCode")).isEqualTo("EMPLOYEE_INACTIVE");

        activate("EMP-1");
        api.consume(BookingApi.event(tenant, "EMP-1", EventType.EmployeeDeactivated, 2, false, Instant.now()));
        var inactive = api.bookAsAdmin(tenant, "EMP-1", tripId);
        assertThat(inactive.status()).isEqualTo(409);
        assertThat(inactive.text("errorCode")).isEqualTo("EMPLOYEE_INACTIVE");
        assertThat(inactive.text("traceId")).isNotBlank();
    }

    @Test
    void tripFull409() {
        UUID tripId = futureTrip(2, 1);
        for (String e : new String[] {"EMP-1", "EMP-2", "EMP-3"}) {
            activate(e);
        }
        assertThat(api.bookAsAdmin(tenant, "EMP-1", tripId).status()).isEqualTo(201);
        assertThat(api.bookAsAdmin(tenant, "EMP-2", tripId).status()).isEqualTo(201);
        var full = api.bookAsAdmin(tenant, "EMP-3", tripId);
        assertThat(full.status()).isEqualTo(409);
        assertThat(full.text("errorCode")).isEqualTo("TRIP_FULL");
    }

    @Test
    void employeeCanOnlyBookSelf() {
        UUID tripId = futureTrip(4, 1);
        activate("EMP-1");
        activate("EMP-2");
        var self = api.book(tenant, "EMPLOYEE", "EMP-1", "EMP-1", tripId);
        assertThat(self.status()).isEqualTo(201);
        assertThat(self.body().get("seatNo").asInt()).isEqualTo(1);

        var other = api.book(tenant, "EMPLOYEE", "EMP-1", "EMP-2", tripId);
        assertThat(other.status()).isEqualTo(403);
        assertThat(other.text("errorCode")).isEqualTo("FORBIDDEN");
        assertThat(db.sql("SELECT count(*) FROM booking WHERE tenant_id = ? AND employee_id = 'EMP-2'")
                .param(tenant).query(Long.class).single()).isZero();
    }

    @Test
    void seatsDerivedFromReservations() {
        UUID tripId = futureTrip(4, 1);
        for (String e : new String[] {"EMP-1", "EMP-2", "EMP-3"}) {
            activate(e);
            api.bookAsAdmin(tenant, e, tripId);
        }
        var seats = api.seats(tenant, tripId);
        assertThat(seats.body().get("capacity").asInt()).isEqualTo(4);
        assertThat(seats.body().get("reserved").asInt()).isEqualTo(3);
        assertThat(seats.body().get("available").asInt()).isEqualTo(1);

        api.consume(BookingApi.event(tenant, "EMP-2", EventType.EmployeeDeactivated, 2, false, Instant.now()));
        seats = api.seats(tenant, tripId);
        assertThat(seats.body().get("reserved").asInt()).isEqualTo(2);
        assertThat(seats.body().get("available").asInt()).isEqualTo(2);
        // A freed seat is reused by the next booking.
        activate("EMP-4");
        assertThat(api.bookAsAdmin(tenant, "EMP-4", tripId).body().get("seatNo").asInt()).isEqualTo(2);
    }
}
