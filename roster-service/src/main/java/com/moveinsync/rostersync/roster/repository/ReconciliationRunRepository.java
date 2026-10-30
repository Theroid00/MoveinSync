package com.moveinsync.rostersync.roster.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * reconciliation_run lifecycle outside the apply transaction. The unique index one_active_run allows at
 * most one UPLOADING/STAGING/STAGED/APPLYING run per tenant source.
 */
@Repository
public class ReconciliationRunRepository {

    public static final List<String> FINISHED =
            List.of("COMPLETED", "PARTIAL_NO_DEACTIVATIONS", "REJECTED", "FAILED", "INCOMPLETE");

    static final String COLUMNS = """
            run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path, file_sha256, expected_rows, expected_sha256,
            parsed_rows, valid_rows, invalid_rows, unidentifiable_rows, status, flags, baseline, missing, ratio_bp,
            assessment_hash, missing_sample, buckets_mismatched, approved_by, approval_reason, approved_at, rejected_by,
            counts::text AS counts, phase_ms::text AS phase_ms, error_code, created_at, updated_at""";

    private final JdbcClient jdbc;

    public ReconciliationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Throws DuplicateKeyException when another run is active for the source. */
    public void insertUploading(UUID runId, String tenantId, String sourceId, long cutoffSeq, Instant exportedAt,
                                String filePath, Integer expectedRows, byte[] expectedSha256) {
        jdbc.sql("""
                INSERT INTO reconciliation_run (run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path,
                                                expected_rows, expected_sha256, status)
                VALUES (:id, :t, :s, :cutoff, :exported, :path, :expected, :sha, 'UPLOADING')""")
                .param("id", runId).param("t", tenantId).param("s", sourceId).param("cutoff", cutoffSeq)
                .param("exported", Sql.ts(exportedAt)).param("path", filePath).param("expected", expectedRows)
                .param("sha", expectedSha256)
                .update();
    }

    public Optional<RunRow> find(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reconciliation_run WHERE run_id = :id")
                .param("id", runId).query(ReconciliationRunRepository::row).optional();
    }

    public Optional<RunRow> find(String tenantId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reconciliation_run WHERE run_id = :id AND tenant_id = :t")
                .param("id", runId).param("t", tenantId).query(ReconciliationRunRepository::row).optional();
    }

    public List<RunRow> pendingApproval(String tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reconciliation_run WHERE tenant_id = :t AND status = 'AWAITING_APPROVAL' ORDER BY created_at")
                .param("t", tenantId).query(ReconciliationRunRepository::row).list();
    }

    public List<RunRow> inStatus(List<String> statuses) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reconciliation_run WHERE status = ANY(:s) ORDER BY created_at")
                .param("s", statuses.toArray(String[]::new)).query(ReconciliationRunRepository::row).list();
    }

    /** Latest COMPLETED run of the source with the same cutoff (same-cutoff lineage rule, DEC-23). */
    public Optional<RunRow> latestCompletedWithCutoff(String tenantId, String sourceId, long cutoffSeq, UUID excluding) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM reconciliation_run
                WHERE tenant_id = :t AND source_id = :s AND cutoff_seq = :c AND run_id <> :id AND status = 'COMPLETED'
                ORDER BY created_at DESC LIMIT 1""")
                .param("t", tenantId).param("s", sourceId).param("c", cutoffSeq).param("id", excluding)
                .query(ReconciliationRunRepository::row).optional();
    }

    public void setFileSha(UUID runId, byte[] sha) {
        jdbc.sql("UPDATE reconciliation_run SET file_sha256 = :sha, updated_at = now() WHERE run_id = :id")
                .param("sha", sha).param("id", runId).update();
    }

    public void setStatus(UUID runId, String status) {
        jdbc.sql("UPDATE reconciliation_run SET status = :st, updated_at = now() WHERE run_id = :id")
                .param("st", status).param("id", runId).update();
    }

    /** Moves the run only if it is still in the expected state; false when it was not. */
    public boolean transition(UUID runId, String from, String to) {
        return jdbc.sql("UPDATE reconciliation_run SET status = :to, updated_at = now() WHERE run_id = :id AND status = :from")
                .param("to", to).param("id", runId).param("from", from).update() == 1;
    }

    public void finish(UUID runId, String status, String errorCode) {
        jdbc.sql("UPDATE reconciliation_run SET status = :st, error_code = :code, updated_at = now() WHERE run_id = :id")
                .param("st", status).param("code", errorCode).param("id", runId).update();
    }

    public void completeAsDuplicate(UUID runId) {
        jdbc.sql("""
                UPDATE reconciliation_run SET status = 'COMPLETED', counts = '{"duplicateSnapshot": true}'::jsonb,
                       updated_at = now() WHERE run_id = :id""").param("id", runId).update();
    }

    public void recordStaging(UUID runId, int parsed, int valid, int invalid, int unidentifiable, List<String> flags) {
        jdbc.sql("""
                UPDATE reconciliation_run SET parsed_rows = :p, valid_rows = :v, invalid_rows = :i, unidentifiable_rows = :u,
                       flags = :flags, updated_at = now() WHERE run_id = :id""")
                .param("p", parsed).param("v", valid).param("i", invalid).param("u", unidentifiable)
                .param("flags", flags.toArray(String[]::new)).param("id", runId).update();
    }

    public void phase(UUID runId, String phase, long millis) {
        jdbc.sql("UPDATE reconciliation_run SET phase_ms = phase_ms || jsonb_build_object(CAST(:k AS text), :v) WHERE run_id = :id")
                .param("k", phase).param("v", millis).param("id", runId).update();
    }

    /** AWAITING_APPROVAL → STAGED with the approver recorded (DuplicateKeyException if another run is active). */
    public boolean approve(UUID runId, String approver, String reason) {
        return jdbc.sql("""
                UPDATE reconciliation_run SET status = 'STAGED', approved_by = :by, approval_reason = :reason,
                       approved_at = now(), updated_at = now()
                WHERE run_id = :id AND status = 'AWAITING_APPROVAL'""")
                .param("by", approver).param("reason", reason).param("id", runId).update() == 1;
    }

    public boolean reject(UUID runId, String by) {
        return jdbc.sql("""
                UPDATE reconciliation_run SET status = 'REJECTED', rejected_by = :by, updated_at = now()
                WHERE run_id = :id AND status = 'AWAITING_APPROVAL'""")
                .param("by", by).param("id", runId).update() == 1;
    }

    public record ErrorRow(Integer rowNo, String employeeId, String field, String code) {
    }

    public List<ErrorRow> errors(String tenantId, UUID runId, int page, int size) {
        return jdbc.sql("""
                SELECT row_no, employee_id, field, code FROM record_error
                WHERE tenant_id = :t AND run_id = :id ORDER BY row_no NULLS LAST, id LIMIT :size OFFSET :offset""")
                .param("t", tenantId).param("id", runId).param("size", size).param("offset", page * size)
                .query((rs, n) -> new ErrorRow((Integer) rs.getObject("row_no"), rs.getString("employee_id"),
                        rs.getString("field"), rs.getString("code")))
                .list();
    }

    public long errorCount(String tenantId, UUID runId) {
        return jdbc.sql("SELECT count(*) FROM record_error WHERE tenant_id = :t AND run_id = :id")
                .param("t", tenantId).param("id", runId).query(Long.class).single();
    }

    static RunRow row(ResultSet rs, int n) throws SQLException {
        return new RunRow(rs.getObject("run_id", UUID.class), rs.getString("tenant_id"), rs.getString("source_id"),
                rs.getLong("cutoff_seq"), Sql.instant(rs, "exported_at"), rs.getString("file_path"),
                rs.getBytes("file_sha256"), (Integer) rs.getObject("expected_rows"), rs.getBytes("expected_sha256"),
                rs.getInt("parsed_rows"), rs.getInt("valid_rows"), rs.getInt("invalid_rows"),
                rs.getInt("unidentifiable_rows"), rs.getString("status"), strings(rs.getArray("flags")),
                (Integer) rs.getObject("baseline"), (Integer) rs.getObject("missing"), (Integer) rs.getObject("ratio_bp"),
                rs.getBytes("assessment_hash"), strings(rs.getArray("missing_sample")),
                (Integer) rs.getObject("buckets_mismatched"), rs.getString("approved_by"), rs.getString("approval_reason"),
                Sql.instant(rs, "approved_at"), rs.getString("rejected_by"), rs.getString("counts"), rs.getString("phase_ms"),
                rs.getString("error_code"), Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
