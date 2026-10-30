package com.moveinsync.rostersync.booking.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.booking.repository.BookingRepository.BookingRow;
import com.moveinsync.rostersync.booking.security.CallerContext;
import com.moveinsync.rostersync.booking.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    public record CreateBookingRequest(String employeeId, UUID tripId) {
    }

    @PostMapping("/api/v1/bookings")
    public ResponseEntity<BookingRow> create(@RequestBody CreateBookingRequest request) {
        BookingRow row = bookings.book(CallerContext.current(), request.employeeId(), request.tripId());
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + row.bookingId())).body(row);
    }

    @GetMapping("/api/v1/bookings/{id}")
    public BookingRow get(@PathVariable UUID id) {
        return bookings.get(CallerContext.current(), id);
    }

    @GetMapping("/api/v1/employees/{id}/bookings")
    public List<BookingRow> forEmployee(@PathVariable String id) {
        return bookings.forEmployee(CallerContext.current(), id);
    }
}
