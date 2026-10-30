package com.moveinsync.rostersync.booking.repository;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Durable consumer dedup: one row per (consumer, eventId), written in the same transaction as the effects. */
@Repository
public class ProcessedMessageRepository {

    public static final String CONSUMER = "booking";

    private final JdbcClient jdbc;

    public ProcessedMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True if this is the first delivery of the event. */
    public boolean claim(UUID eventId) {
        return jdbc.sql("""
                INSERT INTO processed_message (consumer, event_id, outcome) VALUES (:c, :id, 'PROCESSING')
                ON CONFLICT DO NOTHING""")
                .param("c", CONSUMER).param("id", eventId)
                .update() == 1;
    }

    public void complete(UUID eventId, String outcome) {
        jdbc.sql("UPDATE processed_message SET outcome = :o WHERE consumer = :c AND event_id = :id")
                .param("o", outcome).param("c", CONSUMER).param("id", eventId)
                .update();
    }
}
