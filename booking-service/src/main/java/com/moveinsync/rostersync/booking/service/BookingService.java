package com.moveinsync.rostersync.booking.service;

import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.booking.core.SeatAllocator;
import com.moveinsync.rostersync.booking.repository.BookingRepository;
import com.moveinsync.rostersync.booking.repository.BookingRepository.BookingRow;
import com.moveinsync.rostersync.booking.repository.BookingTenantTx;
import com.moveinsync.rostersync.booking.repository.ProjectionRepository;
import com.moveinsync.rostersync.booking.repository.TripRepository;
import com.moveinsync.rostersync.booking.repository.TripRepository.Trip;
import com.moveinsync.rostersync.booking.security.Caller;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Booking creation (spec §11.2). Locking the employee projection first closes the race with a
 * concurrent deactivation; locking the trip serialises seat allocation; the seat_active unique index
 * is the final guard.
 */
@Service
public class BookingService {

    public static final String ADMIN = "ADMIN";
    public static final String EMPLOYEE = "EMPLOYEE";

    private final BookingTenantTx tenantTx;
    private final ProjectionRepository projections;
    private final TripRepository trips;
    private final BookingRepository bookings;

    public BookingService(BookingTenantTx tenantTx, ProjectionRepository projections, TripRepository trips,
                          BookingRepository bookings) {
        this.tenantTx = tenantTx;
        this.projections = projections;
        this.trips = trips;
        this.bookings = bookings;
    }

    public BookingRow book(Caller caller, String employeeId, UUID tripId) {
        requireSelfOrAdmin(caller, employeeId);
        if (employeeId == null || tripId == null) {
            throw DomainException.field(ErrorCode.VALIDATION_FAILED, employeeId == null ? "employeeId" : "tripId", "REQUIRED");
        }
        try {
            return attempt(caller.tenantId(), employeeId, tripId);
        } catch (DuplicateKeyException raceOnSeat) {
            return attempt(caller.tenantId(), employeeId, tripId);   // one retry on seat_active violation
        }
    }

    private BookingRow attempt(String tenantId, String employeeId, UUID tripId) {
        return tenantTx.inTenant(tenantId, s -> {
            boolean active = projections.lock(tenantId, employeeId).map(p -> p.active()).orElse(false);
            if (!active) {
                throw new DomainException(ErrorCode.EMPLOYEE_INACTIVE);
            }
            Trip trip = trips.lock(tenantId, tripId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
            int seat = SeatAllocator.firstFree(trip.capacity(), trips.activeSeats(tenantId, tripId))
                    .orElseThrow(() -> new DomainException(ErrorCode.TRIP_FULL));
            UUID bookingId = UUID.randomUUID();
            bookings.insertReserved(tenantId, bookingId, employeeId, tripId, trip.startsAt(), seat);
            return bookings.find(tenantId, bookingId).orElseThrow();
        });
    }

    public BookingRow get(Caller caller, UUID bookingId) {
        BookingRow row = tenantTx.inTenant(caller.tenantId(), s -> bookings.find(caller.tenantId(), bookingId))
                .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND));
        if (!caller.has(ADMIN) && !row.employeeId().equals(caller.employeeId())) {
            throw new DomainException(ErrorCode.NOT_FOUND);   // someone else's booking: no existence leak
        }
        return row;
    }

    public List<BookingRow> forEmployee(Caller caller, String employeeId) {
        requireSelfOrAdmin(caller, employeeId);
        return tenantTx.inTenant(caller.tenantId(), s -> bookings.forEmployee(caller.tenantId(), employeeId));
    }

    private static void requireSelfOrAdmin(Caller caller, String employeeId) {
        if (caller.has(ADMIN)) {
            return;
        }
        if (caller.has(EMPLOYEE) && employeeId != null && employeeId.equals(caller.employeeId())) {
            return;
        }
        throw new DomainException(ErrorCode.FORBIDDEN);
    }
}
