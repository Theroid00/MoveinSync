package com.moveinsync.rostersync.roster.repository;

import java.util.List;
import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Event identity log: (tenant, source, eventId) is the idempotency key, payload_hash detects reuse. */
@Repository
public class IngestEventRepository {

    private final JdbcClient jdbc;

    public IngestEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the event once; empty when the event id was already seen. */
    public OptionalLong insertIfAbsent(String tenantId, String sourceId, String eventId, byte[] payloadHash,
                                       String employeeId, Long sourceSeq, String outcome, List<String> errorCodes) {
        return jdbc.sql("""
                INSERT INTO ingest_event (tenant_id, source_id, event_id, payload_hash, employee_id, source_seq,
                                          outcome, error_codes)
                VALUES (:t, :s, :id, :hash, :emp, :seq, :outcome, :codes)
                ON CONFLICT (tenant_id, source_id, event_id) DO NOTHING
                RETURNING id""")
                .param("t", tenantId).param("s", sourceId).param("id", eventId).param("hash", payloadHash)
                .param("emp", employeeId).param("seq", sourceSeq).param("outcome", outcome)
                .param("codes", errorCodes == null ? null : errorCodes.toArray(String[]::new))
                .query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    public byte[] payloadHash(String tenantId, String sourceId, String eventId) {
        return jdbc.sql("SELECT payload_hash FROM ingest_event WHERE tenant_id = :t AND source_id = :s AND event_id = :id")
                .param("t", tenantId).param("s", sourceId).param("id", eventId)
                .query(byte[].class).single();
    }

    public void setOutcome(long id, String outcome, List<String> errorCodes) {
        jdbc.sql("UPDATE ingest_event SET outcome = :outcome, error_codes = :codes WHERE id = :id")
                .param("outcome", outcome)
                .param("codes", errorCodes == null ? null : errorCodes.toArray(String[]::new))
                .param("id", id)
                .update();
    }

    public List<IngestEventView> forEmployee(String tenantId, String employeeId) {
        return jdbc.sql("""
                SELECT event_id, source_seq, outcome, created_at FROM ingest_event
                WHERE tenant_id = :t AND employee_id = :e ORDER BY id""")
                .param("t", tenantId).param("e", employeeId)
                .query((rs, n) -> new IngestEventView(rs.getString("event_id"), Sql.nullableLong(rs, "source_seq"),
                        rs.getString("outcome"), Sql.instant(rs, "created_at")))
                .list();
    }

    public record IngestEventView(String eventId, Long sourceSeq, String outcome, java.time.Instant createdAt) {
    }
}
