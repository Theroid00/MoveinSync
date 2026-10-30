package com.moveinsync.rostersync.booking.service;

import java.time.Instant;
import java.util.UUID;

import com.moveinsync.rostersync.booking.core.SeatAllocator;
import com.moveinsync.rostersync.booking.repository.BookingTenantTx;
import com.moveinsync.rostersync.booking.repository.TripRepository;
import com.moveinsync.rostersync.booking.repository.TripRepository.Trip;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class TripService {

    private final BookingTenantTx tenantTx;
    private final TripRepository trips;

    public TripService(BookingTenantTx tenantTx, TripRepository trips) {
        this.tenantTx = tenantTx;
        this.trips = trips;
    }

    public record Seats(UUID tripId, int capacity, int reserved, int available) {
    }

    public Trip create(String tenantId, int capacity, Instant startsAt) {
        if (capacity < 1 || capacity > 100) {
            throw DomainException.field(ErrorCode.VALIDATION_FAILED, "capacity", "CAPACITY_RANGE");
        }
        if (startsAt == null) {
            throw DomainException.field(ErrorCode.VALIDATION_FAILED, "startsAt", "STARTS_AT_REQUIRED");
        }
        UUID tripId = UUID.randomUUID();
        tenantTx.inTenant(tenantId, s -> {
            trips.insert(tenantId, tripId, capacity, startsAt);
            return null;
        });
        return new Trip(tripId, capacity, startsAt);
    }

    /** available = capacity − ACTIVE reservations (derived; no counter to drift). */
    public Seats seats(String tenantId, UUID tripId) {
        return tenantTx.inTenant(tenantId, s -> {
            Trip trip = trips.find(tenantId, tripId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
            int reserved = trips.activeReservations(tenantId, tripId);
            return new Seats(tripId, trip.capacity(), reserved, SeatAllocator.available(trip.capacity(), reserved));
        });
    }
}
