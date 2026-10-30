package com.moveinsync.rostersync.booking.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bookings and seat reservations (RLS). */
@Repository
public class BookingRepository {

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record BookingRow(UUID bookingId, String employeeId, UUID tripId, Instant scheduledStart, String status,
                             String cancelledReason, Integer seatNo, String seatState) {
    }

    private static final String SELECT = """
            SELECT b.booking_id, b.employee_id, b.trip_id, b.scheduled_start, b.status, b.cancelled_reason,
                   s.seat_no, s.state AS seat_state
            FROM booking b LEFT JOIN seat_reservation s ON s.booking_id = b.booking_id""";

    public void insertReserved(String tenantId, UUID bookingId, String employeeId, UUID tripId, Instant scheduledStart,
                               int seatNo) {
        jdbc.sql("""
                INSERT INTO booking (booking_id, tenant_id, employee_id, trip_id, scheduled_start, status)
                VALUES (:b, :t, :e, :trip, :start, 'RESERVED')""")
                .param("b", bookingId).param("t", tenantId).param("e", employeeId).param("trip", tripId)
                .param("start", Sql.ts(scheduledStart))
                .update();
        jdbc.sql("""
                INSERT INTO seat_reservation (booking_id, tenant_id, trip_id, seat_no, state)
                VALUES (:b, :t, :trip, :seat, 'ACTIVE')""")
                .param("b", bookingId).param("t", tenantId).param("trip", tripId).param("seat", seatNo)
                .update();
    }

    /** Cancels RESERVED bookings that start after the deactivation took effect (DEC-13). */
    public List<UUID> cancelFutureReserved(String tenantId, String employeeId, Instant effectiveAt, UUID eventId) {
        return jdbc.sql("""
                UPDATE booking SET status = 'CANCELLED', cancelled_reason = 'EMPLOYEE_DEACTIVATED', cancelled_by_event = :ev
                WHERE tenant_id = :t AND employee_id = :e AND status = 'RESERVED' AND scheduled_start > :at
                RETURNING booking_id""")
                .param("ev", eventId).param("t", tenantId).param("e", employeeId).param("at", Sql.ts(effectiveAt))
                .query(UUID.class).list();
    }

    public int releaseSeats(String tenantId, List<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE seat_reservation SET state = 'RELEASED', released_at = now()
                WHERE tenant_id = :t AND booking_id = ANY(:ids) AND state = 'ACTIVE'""")
                .param("t", tenantId).param("ids", bookingIds.toArray(UUID[]::new))
                .update();
    }

    public Optional<BookingRow> find(String tenantId, UUID bookingId) {
        return jdbc.sql(SELECT + " WHERE b.tenant_id = :t AND b.booking_id = :b")
                .param("t", tenantId).param("b", bookingId).query(BookingRepository::row).optional();
    }

    public List<BookingRow> forEmployee(String tenantId, String employeeId) {
        return jdbc.sql(SELECT + " WHERE b.tenant_id = :t AND b.employee_id = :e ORDER BY b.scheduled_start")
                .param("t", tenantId).param("e", employeeId).query(BookingRepository::row).list();
    }

    public long countFuture(String tenantId, String employeeId) {
        return jdbc.sql("SELECT count(*) FROM booking WHERE tenant_id = :t AND employee_id = :e AND scheduled_start > now()")
                .param("t", tenantId).param("e", employeeId).query(Long.class).single();
    }

    public long countPast(String tenantId, String employeeId) {
        return jdbc.sql("SELECT count(*) FROM booking WHERE tenant_id = :t AND employee_id = :e AND scheduled_start <= now()")
                .param("t", tenantId).param("e", employeeId).query(Long.class).single();
    }

    public long countForEmployee(String tenantId, String employeeId) {
        return jdbc.sql("SELECT count(*) FROM booking WHERE tenant_id = :t AND employee_id = :e")
                .param("t", tenantId).param("e", employeeId).query(Long.class).single();
    }

    private static BookingRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new BookingRow(rs.getObject("booking_id", UUID.class), rs.getString("employee_id"),
                rs.getObject("trip_id", UUID.class), Sql.instant(rs, "scheduled_start"), rs.getString("status"),
                rs.getString("cancelled_reason"), rs.getObject("seat_no", Integer.class), rs.getString("seat_state"));
    }
}
