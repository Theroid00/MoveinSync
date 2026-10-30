package com.moveinsync.rostersync.booking.dev;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.moveinsync.rostersync.booking.repository.BookingRepository;
import com.moveinsync.rostersync.booking.repository.BookingTenantTx;
import com.moveinsync.rostersync.booking.repository.ProjectionRepository;
import com.moveinsync.rostersync.booking.repository.TripRepository;
import com.moveinsync.rostersync.booking.repository.TripRepository.Trip;
import com.moveinsync.rostersync.booking.security.Caller;
import com.moveinsync.rostersync.booking.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Demo data (spec §15, compose only), idempotent: per tenant 20 future trips (capacity 10), then, once
 * roster's events have made EMP-001..EMP-050 active here, 3 future bookings each on 3 different trips,
 * plus one past booking for EMP-001..EMP-010 so the demo can show past trips are never cancelled.
 * Runs on a background thread so the service is healthy while it waits for the projection.
 */
@Component
@ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);
    static final List<String> TENANTS = List.of("acme", "globex");
    static final int TRIPS = 20;
    static final List<String> BOOKERS = IntStream.rangeClosed(1, 50).mapToObj(i -> "EMP-%03d".formatted(i)).toList();
    static final Duration WAIT = Duration.ofSeconds(120);

    private final BookingTenantTx tenantTx;
    private final TripRepository trips;
    private final ProjectionRepository projections;
    private final BookingRepository bookings;
    private final BookingService bookingService;

    public DevSeeder(BookingTenantTx tenantTx, TripRepository trips, ProjectionRepository projections,
                     BookingRepository bookings, BookingService bookingService) {
        this.tenantTx = tenantTx;
        this.trips = trips;
        this.projections = projections;
        this.bookings = bookings;
        this.bookingService = bookingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("dev-seeder").start(this::seed);
    }

    void seed() {
        try {
            TENANTS.forEach(this::seedTrips);
            for (String tenant : TENANTS) {
                if (!awaitProjection(tenant)) {
                    log.warn("event=dev_seed_projection_timeout tenant={}", tenant);
                    continue;
                }
                seedBookings(tenant);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("event=dev_seed_failed exception={}", e.getClass().getName(), e);
        }
    }

    private void seedTrips(String tenant) {
        tenantTx.inTenant(tenant, s -> {
            if (trips.count(tenant) == 0) {
                Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS);
                for (int day = 1; day <= TRIPS; day++) {
                    trips.insert(tenant, UUID.randomUUID(), 10, base.plus(day, ChronoUnit.DAYS));
                }
                log.info("event=dev_seed_trips tenant={} trips={}", tenant, TRIPS);
            }
            return null;
        });
    }

    /** Polls every 2 s (up to 120 s) until roster's relay has activated the employees to book for. */
    private boolean awaitProjection(String tenant) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            long active = tenantTx.inTenant(tenant, s -> projections.countActiveAmong(tenant, BOOKERS));
            if (active >= BOOKERS.size()) {
                return true;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    private void seedBookings(String tenant) {
        Caller seeder = new Caller(tenant, "dev-seeder", Set.of(BookingService.ADMIN), null);
        List<Trip> future = tenantTx.inTenant(tenant, s -> trips.futureTrips(tenant));
        int created = 0;
        for (int i = 0; i < BOOKERS.size(); i++) {
            String employee = BOOKERS.get(i);
            if (tenantTx.inTenant(tenant, s -> bookings.countFuture(tenant, employee)) > 0) {
                continue;
            }
            for (int k = 0; k < 3; k++) {
                bookingService.book(seeder, employee, future.get((3 * i + k) % future.size()).tripId());
                created++;
            }
        }
        Trip past = tenantTx.inTenant(tenant, s -> trips.anyPastTrip(tenant).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            Instant startsAt = Instant.now().minus(1, ChronoUnit.DAYS);
            trips.insert(tenant, id, 10, startsAt);
            return new Trip(id, 10, startsAt);
        }));
        for (int i = 0; i < 10; i++) {
            String employee = BOOKERS.get(i);
            int seat = i + 1;
            tenantTx.inTenant(tenant, s -> {
                if (bookings.countPast(tenant, employee) == 0) {
                    bookings.insertReserved(tenant, UUID.randomUUID(), employee, past.tripId(), past.startsAt(), seat);
                }
                return null;
            });
        }
        log.info("event=dev_seed_bookings tenant={} futureBookings={}", tenant, created);
    }
}
