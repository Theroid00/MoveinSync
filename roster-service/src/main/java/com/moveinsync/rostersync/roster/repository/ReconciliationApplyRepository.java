package com.moveinsync.rostersync.roster.repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The assess-and-apply transaction of spec §9.3, on the bulk pool. Every method runs inside one bulk
 * TenantTx that holds the tenant source's exclusive advisory lock; every write is restricted to
 * mismatched buckets and guarded by source_seq, and feeds history and outbox through one CTE.
 */
@Repository
public class ReconciliationApplyRepository {

    /** Common tail: history row + outbox row per changed employee; returns counts by event type. */
    private static final String HISTORY_AND_OUTBOX = """
            ), ev AS (SELECT gen_random_uuid() AS eid, c.* FROM changed c),
            hist AS (
              INSERT INTO employee_history (tenant_id, employee_id, source_seq, aggregate_revision, change, origin, run_id)
              SELECT CAST(:t AS text), employee_id, source_seq, aggregate_revision, event_type, 'RECONCILIATION', :run FROM ev
            ), ob AS (
              INSERT INTO outbox_event (event_id, tenant_id, employee_id, aggregate_revision, event_type, payload)
              SELECT eid, CAST(:t AS text), employee_id, aggregate_revision, event_type,
                     jsonb_build_object('eventId', eid::text, 'eventType', event_type, 'tenantId', CAST(:t AS text),
                                        'employeeId', employee_id, 'aggregateRevision', aggregate_revision,
                                        'active', active, 'effectiveAt', CAST(:exportedAt AS text),
                                        'changedFields', changed_fields)
              FROM ev RETURNING event_type
            )
            SELECT event_type, count(*) AS n FROM ob GROUP BY event_type""";

    private final JdbcClient jdbc;

    public ReconciliationApplyRepository(@Qualifier("bulk") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record TenantSourceState(long acceptedCutoff, long absenceWatermark) {
    }

    public record Assessment(int baseline, int missing, String missingIdsHashHex, List<String> sample) {
    }

    public void statementTimeout300s() {
        jdbc.sql("SET LOCAL statement_timeout = '300s'").update();
    }

    public void lockExclusive(long lockKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", lockKey).query().listOfRows();
    }

    public TenantSourceState tenantSource(String tenantId, String sourceId) {
        return jdbc.sql("SELECT accepted_snapshot_cutoff, absence_watermark FROM tenant_source WHERE tenant_id = :t AND source_id = :s")
                .param("t", tenantId).param("s", sourceId)
                .query((rs, n) -> new TenantSourceState(rs.getLong(1), rs.getLong(2))).single();
    }

    public record FinishedRun(String status, byte[] fileSha256) {
    }

    public Optional<FinishedRun> latestCompletedWithCutoff(String tenantId, String sourceId, long cutoffSeq, UUID excluding) {
        return jdbc.sql("""
                SELECT status, file_sha256 FROM reconciliation_run
                WHERE tenant_id = :t AND source_id = :s AND cutoff_seq = :c AND run_id <> :id AND status = 'COMPLETED'
                ORDER BY created_at DESC LIMIT 1""")
                .param("t", tenantId).param("s", sourceId).param("c", cutoffSeq).param("id", excluding)
                                .query((rs, n) -> new FinishedRun(rs.getString(1), rs.getBytes(2))).optional();
    }

    public void setStatus(UUID runId, String status, String errorCode) {
        jdbc.sql("UPDATE reconciliation_run SET status = :st, error_code = :code, updated_at = now() WHERE run_id = :id")
                .param("st", status).param("code", errorCode).param("id", runId).update();
    }

    public void completeAsDuplicate(UUID runId) {
        jdbc.sql("""
                UPDATE reconciliation_run SET status = 'COMPLETED', counts = '{"duplicateSnapshot": true}'::jsonb,
                       updated_at = now() WHERE run_id = :id""").param("id", runId).update();
    }

    public void phase(UUID runId, String phase, long millis) {
        jdbc.sql("UPDATE reconciliation_run SET phase_ms = phase_ms || jsonb_build_object(CAST(:k AS text), :v) WHERE run_id = :id")
                .param("k", phase).param("v", millis).param("id", runId).update();
    }

    /** sha256 over (employeeId || hrHash || int8(seq)) ordered by id, per bucket, for the staged valid rows. */
    public Map<Integer, byte[]> stagedDigests(UUID runId) {
        return digests(jdbc.sql("""
                SELECT bucket, sha256(string_agg(convert_to(employee_id, 'UTF8') || hr_hash || int8send(last_change_seq),
                                                 ''::bytea ORDER BY employee_id))
                FROM staging_employee WHERE run_id = :run AND valid GROUP BY bucket""").param("run", runId));
    }

    /** The same digest over the store's active employees of the source. */
    public Map<Integer, byte[]> storeDigests(String tenantId, String sourceId) {
        return digests(jdbc.sql("""
                SELECT bucket, sha256(string_agg(convert_to(employee_id, 'UTF8') || hr_hash || int8send(source_seq),
                                                 ''::bytea ORDER BY employee_id))
                FROM employee WHERE tenant_id = :t AND source_id = :s AND active GROUP BY bucket""")
                .param("t", tenantId).param("s", sourceId));
    }

    private static Map<Integer, byte[]> digests(JdbcClient.StatementSpec statement) {
        Map<Integer, byte[]> digests = new HashMap<>();
        statement.query((RowCallbackHandler) rs -> digests.put(rs.getInt(1), rs.getBytes(2)));
        return digests;
    }

    /** Row-level classification, only for mismatched buckets (spec §9.3 step 6 SQL). */
    public void createPlan(String tenantId, String sourceId, UUID runId, Integer[] mismatched) {
        jdbc.sql("""
                CREATE TEMP TABLE recon_plan ON COMMIT DROP AS
                SELECT s.employee_id, s.last_change_seq AS new_seq, s.hr_hash AS new_hash, s.bucket,
                       e.active AS old_active, e.source_seq AS old_seq, e.hr_hash AS old_hash,
                       CASE WHEN e.employee_id IS NULL AND t.source_seq >= s.last_change_seq THEN 'STALE'
                            WHEN e.employee_id IS NULL THEN 'CREATE'
                            WHEN s.last_change_seq < e.source_seq THEN 'STALE'
                            WHEN s.last_change_seq = e.source_seq AND s.hr_hash = e.hr_hash THEN 'DUPLICATE'
                            WHEN s.last_change_seq = e.source_seq THEN 'CONFLICT'
                            WHEN NOT e.active THEN 'REACTIVATE'
                            WHEN s.hr_hash = e.hr_hash THEN 'ADVANCE'
                            ELSE 'UPDATE' END AS action
                FROM staging_employee s
                LEFT JOIN employee e ON e.tenant_id = :t AND e.source_id = :src AND e.employee_id = s.employee_id
                LEFT JOIN employee_tombstone t ON t.tenant_id = :t AND t.source_id = :src AND t.employee_id = s.employee_id
                WHERE s.run_id = :run AND s.valid AND s.bucket = ANY(:mismatched)""")
                .param("t", tenantId).param("src", sourceId).param("run", runId).param("mismatched", mismatched)
                .update();
        // Temporary tables are not auto-analyzed; apply joins need the actual action distribution.
        jdbc.sql("ANALYZE recon_plan").update();
    }

    public Map<String, Long> planCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT action, count(*) FROM recon_plan GROUP BY action")
                .query((RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    /**
     * Baseline = active employees with source_seq ≤ S; missing = those (in mismatched buckets: an absent
     * employee always mismatches its bucket) with no staged row at all, valid or not.
     */
    public Assessment assess(String tenantId, String sourceId, UUID runId, long cutoff, Integer[] mismatched) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM employee WHERE tenant_id = :t AND source_id = :src AND active AND source_seq <= :cutoff) AS baseline,
                       m.missing, m.ids_hash, m.sample
                FROM (SELECT count(*) AS missing,
                             encode(sha256(convert_to(coalesce(string_agg(e.employee_id, ',' ORDER BY e.employee_id), ''), 'UTF8')), 'hex') AS ids_hash,
                             (array_agg(e.employee_id ORDER BY e.employee_id))[1 : 20] AS sample
                      FROM employee e
                      WHERE e.tenant_id = :t AND e.source_id = :src AND e.active AND e.source_seq <= :cutoff
                        AND e.bucket = ANY(:mismatched)
                        AND NOT EXISTS (SELECT 1 FROM staging_employee s WHERE s.run_id = :run AND s.employee_id = e.employee_id)) m""")
                .param("t", tenantId).param("src", sourceId).param("cutoff", cutoff).param("mismatched", mismatched)
                .param("run", runId)
                .query((rs, n) -> {
                    Array sample = rs.getArray("sample");
                    return new Assessment(rs.getInt("baseline"), rs.getInt("missing"), rs.getString("ids_hash"),
                            sample == null ? List.of() : Arrays.asList((String[]) sample.getArray()));
                })
                .single();
    }

    public void awaitApproval(UUID runId, List<String> flags, Assessment a, int ratioBp, byte[] assessmentHash, int bucketsMismatched) {
        jdbc.sql("""
                UPDATE reconciliation_run SET status = 'AWAITING_APPROVAL', flags = :flags, baseline = :b, missing = :m,
                       ratio_bp = :r, assessment_hash = :h, missing_sample = :sample, buckets_mismatched = :bm,
                       approved_by = NULL, approval_reason = NULL, approved_at = NULL, updated_at = now()
                WHERE run_id = :id""")
                .param("flags", flags.toArray(String[]::new)).param("b", a.baseline()).param("m", a.missing())
                .param("r", ratioBp).param("h", assessmentHash).param("sample", a.sample().toArray(String[]::new))
                .param("bm", bucketsMismatched).param("id", runId)
                .update();
    }

    public void insertBrakeAlert(String tenantId, UUID runId, String detailsJson) {
        jdbc.sql("INSERT INTO operator_alert (tenant_id, kind, run_id, details) VALUES (:t, 'SAFETY_BRAKE', :run, CAST(:d AS jsonb))")
                .param("t", tenantId).param("run", runId).param("d", detailsJson).update();
    }

    public Map<String, Integer> applyCreates(String tenantId, String sourceId, UUID runId, String exportedAt) {
        return eventCounts(jdbc.sql("""
                WITH changed AS (
                  INSERT INTO employee (tenant_id, source_id, employee_id, name, email, phone, manager_id, office_code,
                                        shift_code, active, source_seq, hr_hash, bucket, aggregate_revision)
                  SELECT CAST(:t AS text), CAST(:src AS text), s.employee_id, s.name, s.email, s.phone, s.manager_id,
                         s.office_code, s.shift_code, true, s.last_change_seq, s.hr_hash, s.bucket, 1
                  FROM staging_employee s JOIN recon_plan p USING (employee_id)
                  WHERE p.action = 'CREATE' AND s.run_id = :run
                  RETURNING employee_id, aggregate_revision, source_seq, 'EmployeeCreated'::text AS event_type, active,
                            '["name","email","phone","managerId","officeCode","shiftCode","active"]'::jsonb AS changed_fields
                """ + HISTORY_AND_OUTBOX), tenantId, sourceId, runId, exportedAt);
    }

    public int deleteCreatedTombstones(String tenantId, String sourceId) {
        return jdbc.sql("""
                DELETE FROM employee_tombstone t USING recon_plan p
                WHERE t.tenant_id = :t AND t.source_id = :src AND t.employee_id = p.employee_id AND p.action = 'CREATE'""")
                .param("t", tenantId).param("src", sourceId).update();
    }

    /** UPDATE / ADVANCE / REACTIVATE; changedFields from PostgreSQL 18 RETURNING old/new values. */
    public Map<String, Integer> applyUpdates(String tenantId, String sourceId, UUID runId, String exportedAt) {
        return eventCounts(jdbc.sql("""
                WITH changed AS (
                  UPDATE employee e SET name = s.name, email = s.email, phone = s.phone, manager_id = s.manager_id,
                         office_code = s.office_code, shift_code = s.shift_code, active = true, source_seq = p.new_seq,
                         hr_hash = p.new_hash, aggregate_revision = e.aggregate_revision + 1, tombstone_reason = NULL,
                         updated_at = now()
                  FROM recon_plan p JOIN staging_employee s ON s.run_id = :run AND s.employee_id = p.employee_id
                  WHERE p.action IN ('UPDATE', 'ADVANCE', 'REACTIVATE') AND e.tenant_id = :t AND e.source_id = :src
                    AND e.employee_id = p.employee_id AND e.source_seq < p.new_seq
                  RETURNING new.employee_id, new.aggregate_revision, new.source_seq,
                            CASE p.action WHEN 'UPDATE' THEN 'EmployeeUpdated' WHEN 'ADVANCE' THEN 'EmployeeVersionAdvanced'
                                          ELSE 'EmployeeReactivated' END AS event_type,
                            new.active,
                            to_jsonb(array_remove(ARRAY[
                              CASE WHEN old.name IS DISTINCT FROM new.name THEN 'name' END,
                              CASE WHEN old.email IS DISTINCT FROM new.email THEN 'email' END,
                              CASE WHEN old.phone IS DISTINCT FROM new.phone THEN 'phone' END,
                              CASE WHEN old.manager_id IS DISTINCT FROM new.manager_id THEN 'managerId' END,
                              CASE WHEN old.office_code IS DISTINCT FROM new.office_code THEN 'officeCode' END,
                              CASE WHEN old.shift_code IS DISTINCT FROM new.shift_code THEN 'shiftCode' END,
                              CASE WHEN old.active IS DISTINCT FROM new.active THEN 'active' END,
                              CASE WHEN old.office_code IS DISTINCT FROM new.office_code AND new.pickup_point IS NOT NULL
                                   THEN 'PICKUP_REVIEW_NEEDED' END], NULL)) AS changed_fields
                """ + HISTORY_AND_OUTBOX), tenantId, sourceId, runId, exportedAt);
    }

    public int recordConflicts(String tenantId, UUID runId) {
        return jdbc.sql("""
                INSERT INTO record_error (tenant_id, run_id, row_no, employee_id, field, code)
                SELECT :t, :run, s.row_no, p.employee_id, 'sourceSeq', 'VERSION_CONFLICT'
                FROM recon_plan p JOIN staging_employee s ON s.run_id = :run AND s.employee_id = p.employee_id
                WHERE p.action = 'CONFLICT'""")
                .param("t", tenantId).param("run", runId).update();
    }

    /** Inferred absences, stamped at seq S so no older event can resurrect them (DEC-04). */
    public int applyDeactivations(String tenantId, String sourceId, UUID runId, long cutoff, Integer[] mismatched,
                                  String exportedAt) {
        return eventCounts(jdbc.sql("""
                WITH changed AS (
                  UPDATE employee e SET active = false, source_seq = :cutoff, tombstone_reason = 'ABSENT_IN_SNAPSHOT',
                         aggregate_revision = e.aggregate_revision + 1, updated_at = now()
                  WHERE e.tenant_id = :t AND e.source_id = :src AND e.active AND e.source_seq <= :cutoff
                    AND e.bucket = ANY(:mismatched)
                    AND NOT EXISTS (SELECT 1 FROM staging_employee s WHERE s.run_id = :run AND s.employee_id = e.employee_id)
                  RETURNING e.employee_id, e.aggregate_revision, e.source_seq, 'EmployeeDeactivated'::text AS event_type,
                            e.active, '["active"]'::jsonb AS changed_fields
                """ + HISTORY_AND_OUTBOX).param("cutoff", cutoff).param("mismatched", mismatched),
                tenantId, sourceId, runId, exportedAt).getOrDefault("EmployeeDeactivated", 0);
    }

    public void advanceTenantSource(String tenantId, String sourceId, long cutoff, boolean inferAbsences) {
        jdbc.sql("""
                UPDATE tenant_source SET accepted_snapshot_cutoff = GREATEST(accepted_snapshot_cutoff, :cutoff),
                       absence_watermark = CASE WHEN :infer THEN GREATEST(absence_watermark, :cutoff) ELSE absence_watermark END
                WHERE tenant_id = :t AND source_id = :src""")
                .param("cutoff", cutoff).param("infer", inferAbsences).param("t", tenantId).param("src", sourceId)
                .update();
    }

    public void finishRun(UUID runId, String status, List<String> flags, Assessment a, int ratioBp, byte[] assessmentHash,
                          int bucketsMismatched, String countsJson) {
        jdbc.sql("""
                UPDATE reconciliation_run SET status = :st, flags = :flags, baseline = :b, missing = :m, ratio_bp = :r,
                       assessment_hash = :h, missing_sample = :sample, buckets_mismatched = :bm,
                       counts = CAST(:counts AS jsonb), updated_at = now()
                WHERE run_id = :id""")
                .param("st", status).param("flags", flags.toArray(String[]::new)).param("b", a.baseline())
                .param("m", a.missing()).param("r", ratioBp).param("h", assessmentHash)
                .param("sample", a.sample().toArray(String[]::new)).param("bm", bucketsMismatched)
                .param("counts", countsJson).param("id", runId)
                .update();
    }

    private static Map<String, Integer> eventCounts(JdbcClient.StatementSpec statement, String tenantId, String sourceId,
                                                    UUID runId, String exportedAt) {
        Map<String, Integer> counts = new HashMap<>();
        statement.param("t", tenantId).param("src", sourceId).param("run", runId).param("exportedAt", exportedAt)
                .query((RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getInt(2)));
        return counts;
    }
}
