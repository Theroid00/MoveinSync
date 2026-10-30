package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moveinsync.rostersync.booking.repository.BookingRepository.BookingRow;
import com.moveinsync.rostersync.booking.security.Caller;
import com.moveinsync.rostersync.booking.service.BookingService;
import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 20 employees book a 4-seat trip at the same instant: exactly 4 succeed, on 4 different seats. */
class SeatRaceIT extends AbstractBookingApiIT {

    @Autowired
    BookingService bookings;

    @Test
    void exactlyCapacitySucceeds() {
        UUID trip = futureTrip(4, 1);
        for (int i = 0; i < 20; i++) {
            activate("EMP-" + i);
        }
        Caller admin = new Caller(tenant, "admin", Set.of("ADMIN"), null);
        List<Race.Outcome<BookingRow>> outcomes;
        try (Race race = new Race(20)) {
            outcomes = race.run(t -> () -> bookings.book(admin, "EMP-" + t, trip));
        }
        List<BookingRow> booked = outcomes.stream().filter(o -> !o.failed()).map(Race.Outcome::value).toList();
        assertThat(booked).hasSize(4);
        assertThat(booked).extracting(BookingRow::seatNo).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(outcomes.stream().filter(Race.Outcome::failed))
                .hasSize(16)
                .allMatch(o -> o.error() instanceof DomainException d && d.code() == ErrorCode.TRIP_FULL);
        assertThat(api.seats(tenant, trip).body().get("available").asInt()).isZero();
    }
}
