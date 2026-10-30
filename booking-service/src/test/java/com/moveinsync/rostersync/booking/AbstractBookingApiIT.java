package com.moveinsync.rostersync.booking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.moveinsync.rostersync.contract.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** booking-service against postgres:18 with MockMvc and a fresh tenant per test. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractBookingApiIT extends AbstractPostgresIT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JsonMapper json;

    /** Replaces the JWKS-backed decoder with one trusting the test keypair (same validators). */
    @TestBean
    JwtDecoder jwtDecoder;

    static JwtDecoder jwtDecoder() {
        return TestTokens.decoder();
    }

    protected BookingApi api;
    protected String tenant;
    protected JdbcClient db;

    @BeforeEach
    void apiAndTenant() {
        api = new BookingApi(mvc, json);
        tenant = "t-" + UUID.randomUUID().toString().substring(0, 12);
        db = superuserJdbc();
    }

    protected void activate(String employeeId) {
        api.consume(BookingApi.event(tenant, employeeId, EventType.EmployeeCreated, 1, true, Instant.now()));
    }

    protected UUID trip(int capacity, Instant startsAt) {
        return UUID.fromString(api.createTrip(tenant, capacity, startsAt).text("tripId"));
    }

    protected UUID futureTrip(int capacity, int daysAhead) {
        return trip(capacity, Instant.now().plus(daysAhead, ChronoUnit.DAYS));
    }

    /** A booking on a trip in the past, inserted directly (as the dev seeder does). */
    protected UUID pastBooking(String employeeId) {
        UUID tripId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        db.sql("INSERT INTO trip (trip_id, tenant_id, capacity, starts_at) VALUES (?, ?, 10, now() - interval '1 day')")
                .params(tripId, tenant).update();
        db.sql("""
                INSERT INTO booking (booking_id, tenant_id, employee_id, trip_id, scheduled_start, status)
                VALUES (?, ?, ?, ?, now() - interval '1 day', 'RESERVED')""").params(bookingId, tenant, employeeId, tripId).update();
        db.sql("INSERT INTO seat_reservation (booking_id, tenant_id, trip_id, seat_no, state) VALUES (?, ?, ?, 1, 'ACTIVE')")
                .params(bookingId, tenant, tripId).update();
        return bookingId;
    }

    protected String status(UUID bookingId) {
        return db.sql("SELECT status FROM booking WHERE booking_id = ?").param(bookingId).query(String.class).single();
    }

    protected String seatState(UUID bookingId) {
        return db.sql("SELECT state FROM seat_reservation WHERE booking_id = ?").param(bookingId).query(String.class).single();
    }
}
