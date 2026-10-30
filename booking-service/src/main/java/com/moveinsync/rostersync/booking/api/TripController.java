package com.moveinsync.rostersync.booking.api;

import org.springframework.security.access.prepost.PreAuthorize;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.moveinsync.rostersync.booking.repository.TripRepository.Trip;
import com.moveinsync.rostersync.booking.security.CallerContext;
import com.moveinsync.rostersync.booking.service.TripService;
import com.moveinsync.rostersync.booking.service.TripService.Seats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    public record CreateTripRequest(int capacity, Instant startsAt) {
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Trip> create(@RequestBody CreateTripRequest request) {
        Trip trip = trips.create(CallerContext.current().tenantId(), request.capacity(), request.startsAt());
        return ResponseEntity.created(URI.create("/api/v1/trips/" + trip.tripId())).body(trip);
    }

    @GetMapping("/{id}/seats")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'OPERATOR', 'ADMIN')")
    public Seats seats(@PathVariable UUID id) {
        return trips.seats(CallerContext.current().tenantId(), id);
    }
}
