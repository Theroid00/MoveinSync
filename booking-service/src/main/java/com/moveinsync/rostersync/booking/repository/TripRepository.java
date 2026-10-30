package com.moveinsync.rostersync.booking.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Trips and their derived seat usage (RLS). */
@Repository
public class TripRepository {

    private final JdbcClient jdbc;

    public TripRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Trip(UUID tripId, int capacity, Instant startsAt) {
    }

    public void insert(String tenantId, UUID tripId, int capacity, Instant startsAt) {
        jdbc.sql("INSERT INTO trip (trip_id, tenant_id, capacity, starts_at) VALUES (:id, :t, :c, :s)")
                .param("id", tripId).param("t", tenantId).param("c", capacity).param("s", Sql.ts(startsAt))
                .update();
    }

    public Optional<Trip> find(String tenantId, UUID tripId) {
        return jdbc.sql("SELECT trip_id, capacity, starts_at FROM trip WHERE tenant_id = :t AND trip_id = :id")
                .param("t", tenantId).param("id", tripId).query(TripRepository::trip).optional();
    }

    public Optional<Trip> lock(String tenantId, UUID tripId) {
        return jdbc.sql("SELECT trip_id, capacity, starts_at FROM trip WHERE tenant_id = :t AND trip_id = :id FOR UPDATE")
                .param("t", tenantId).param("id", tripId).query(TripRepository::trip).optional();
    }

    public List<Integer> activeSeats(String tenantId, UUID tripId) {
        return jdbc.sql("SELECT seat_no FROM seat_reservation WHERE tenant_id = :t AND trip_id = :id AND state = 'ACTIVE'")
                .param("t", tenantId).param("id", tripId).query(Integer.class).list();
    }

    public int activeReservations(String tenantId, UUID tripId) {
        return jdbc.sql("SELECT count(*) FROM seat_reservation WHERE tenant_id = :t AND trip_id = :id AND state = 'ACTIVE'")
                .param("t", tenantId).param("id", tripId).query(Integer.class).single();
    }

    public long count(String tenantId) {
        return jdbc.sql("SELECT count(*) FROM trip WHERE tenant_id = :t").param("t", tenantId).query(Long.class).single();
    }

    public java.util.Optional<Trip> anyPastTrip(String tenantId) {
        return jdbc.sql("SELECT trip_id, capacity, starts_at FROM trip WHERE tenant_id = :t AND starts_at <= now() ORDER BY starts_at LIMIT 1")
                .param("t", tenantId).query(TripRepository::trip).optional();
    }

    public List<Trip> futureTrips(String tenantId) {
        return jdbc.sql("SELECT trip_id, capacity, starts_at FROM trip WHERE tenant_id = :t AND starts_at > now() ORDER BY starts_at")
                .param("t", tenantId).query(TripRepository::trip).list();
    }

    private static Trip trip(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Trip(rs.getObject("trip_id", UUID.class), rs.getInt("capacity"), Sql.instant(rs, "starts_at"));
    }
}
