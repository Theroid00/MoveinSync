package com.moveinsync.rostersync.roster.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** employee_history: one row per applied change (origin API, RECONCILIATION or ADMIN). */
@Repository
public class HistoryRepository {

    private final JdbcClient jdbc;

    public HistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tenantId, String employeeId, Long sourceSeq, long aggregateRevision, String change,
                       String origin, UUID runId) {
        jdbc.sql("""
                INSERT INTO employee_history (tenant_id, employee_id, source_seq, aggregate_revision, change, origin, run_id)
                VALUES (:t, :e, :seq, :rev, :change, :origin, :run)""")
                .param("t", tenantId).param("e", employeeId).param("seq", sourceSeq).param("rev", aggregateRevision)
                .param("change", change).param("origin", origin).param("run", runId)
                .update();
    }

    public List<HistoryEntry> forEmployee(String tenantId, String employeeId) {
        return jdbc.sql("""
                SELECT source_seq, aggregate_revision, change, origin, run_id, created_at FROM employee_history
                WHERE tenant_id = :t AND employee_id = :e ORDER BY id""")
                .param("t", tenantId).param("e", employeeId)
                .query((rs, n) -> new HistoryEntry(Sql.nullableLong(rs, "source_seq"), rs.getLong("aggregate_revision"),
                        rs.getString("change"), rs.getString("origin"), rs.getObject("run_id", UUID.class),
                        Sql.instant(rs, "created_at")))
                .list();
    }

    public record HistoryEntry(Long sourceSeq, long aggregateRevision, String change, String origin, UUID runId,
                               Instant createdAt) {
    }
}
