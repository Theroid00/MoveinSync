package com.moveinsync.rostersync.roster.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalLong;

import com.moveinsync.rostersync.roster.core.HrRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Employee and tombstone writes. HR statements never name the transport columns and the transport
 * statement never names HR columns: field ownership is enforced by the SQL itself (DEC-06).
 */
@Repository
public class EmployeeRepository {

    private static final String COLUMNS = """
            employee_id, name, email, phone, manager_id, office_code, shift_code, active, source_seq, hr_hash,
            pickup_point, special_assistance, aggregate_revision, tombstone_reason, updated_at""";
    private static final String PK = "tenant_id = :t AND source_id = :s AND employee_id = :e";

    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeRow> findForUpdate(String tenantId, String sourceId, String employeeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM employee WHERE " + PK + " FOR UPDATE")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .query(EmployeeRepository::row).optional();
    }

    public Optional<EmployeeRow> find(String tenantId, String sourceId, String employeeId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM employee WHERE " + PK)
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .query(EmployeeRepository::row).optional();
    }

    public boolean exists(String tenantId, String sourceId, String employeeId) {
        return jdbc.sql("SELECT count(*) FROM employee WHERE " + PK)
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .query(Long.class).single() > 0;
    }

    public Optional<Long> tombstoneSeq(String tenantId, String sourceId, String employeeId) {
        return jdbc.sql("SELECT source_seq FROM employee_tombstone WHERE " + PK)
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .query(Long.class).optional();
    }

    /** New employee at revision 1 (HR columns only). */
    public void insert(String tenantId, String sourceId, String employeeId, HrRecord hr, long seq, byte[] hrHash, int bucket) {
        jdbc.sql("""
                INSERT INTO employee (tenant_id, source_id, employee_id, name, email, phone, manager_id, office_code,
                                      shift_code, active, source_seq, hr_hash, bucket, aggregate_revision)
                VALUES (:t, :s, :e, :name, :email, :phone, :manager, :office, :shift, true, :seq, :hash, :bucket, 1)""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .param("name", hr.name()).param("email", hr.email()).param("phone", hr.phone())
                .param("manager", hr.managerId()).param("office", hr.officeCode()).param("shift", hr.shiftCode())
                .param("seq", seq).param("hash", hrHash).param("bucket", bucket)
                .update();
    }

    /** Guarded HR upsert of an existing row: applies only if the stored seq is older. */
    public OptionalLong applyHrUpsert(String tenantId, String sourceId, String employeeId, HrRecord hr, long seq,
                                      byte[] hrHash, int bucket) {
        return revision(jdbc.sql("""
                UPDATE employee SET name = :name, email = :email, phone = :phone, manager_id = :manager,
                       office_code = :office, shift_code = :shift, active = true, source_seq = :seq, hr_hash = :hash,
                       bucket = :bucket, aggregate_revision = aggregate_revision + 1, tombstone_reason = NULL,
                       updated_at = now()
                WHERE tenant_id = :t AND source_id = :s AND employee_id = :e AND source_seq < :seq
                RETURNING aggregate_revision""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .param("name", hr.name()).param("email", hr.email()).param("phone", hr.phone())
                .param("manager", hr.managerId()).param("office", hr.officeCode()).param("shift", hr.shiftCode())
                .param("seq", seq).param("hash", hrHash).param("bucket", bucket));
    }

    /** Guarded HR deactivation: stamps the HR seq so older events cannot resurrect the employee. */
    public OptionalLong applyHrDeactivate(String tenantId, String sourceId, String employeeId, long seq) {
        return revision(jdbc.sql("""
                UPDATE employee SET active = false, source_seq = :seq, tombstone_reason = 'HR_DEACTIVATED',
                       aggregate_revision = aggregate_revision + 1, updated_at = now()
                WHERE tenant_id = :t AND source_id = :s AND employee_id = :e AND source_seq < :seq
                RETURNING aggregate_revision""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId).param("seq", seq));
    }

    /** Transport-admin edit under optimistic concurrency (If-Match = aggregate_revision). */
    public OptionalLong applyTransport(String tenantId, String sourceId, String employeeId, String pickupPoint,
                                       String specialAssistance, long expectedRevision) {
        return revision(jdbc.sql("""
                UPDATE employee SET pickup_point = :pickup, special_assistance = :assist,
                       aggregate_revision = aggregate_revision + 1, updated_at = now()
                WHERE tenant_id = :t AND source_id = :s AND employee_id = :e AND aggregate_revision = :rev
                RETURNING aggregate_revision""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .param("pickup", pickupPoint).param("assist", specialAssistance).param("rev", expectedRevision));
    }

    /** Admin deactivation (DEC-17): HR owns source_seq, so it is left unchanged. */
    public OptionalLong applyAdminDeactivate(String tenantId, String sourceId, String employeeId) {
        return revision(jdbc.sql("""
                UPDATE employee SET active = false, tombstone_reason = 'ADMIN_DEACTIVATED',
                       aggregate_revision = aggregate_revision + 1, updated_at = now()
                WHERE tenant_id = :t AND source_id = :s AND employee_id = :e AND active
                RETURNING aggregate_revision""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId));
    }

    public void upsertTombstone(String tenantId, String sourceId, String employeeId, long seq) {
        jdbc.sql("""
                INSERT INTO employee_tombstone (tenant_id, source_id, employee_id, source_seq) VALUES (:t, :s, :e, :seq)
                ON CONFLICT (tenant_id, source_id, employee_id)
                DO UPDATE SET source_seq = GREATEST(employee_tombstone.source_seq, EXCLUDED.source_seq)""")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId).param("seq", seq)
                .update();
    }

    public void deleteTombstone(String tenantId, String sourceId, String employeeId) {
        jdbc.sql("DELETE FROM employee_tombstone WHERE " + PK)
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .update();
    }

    private static OptionalLong revision(JdbcClient.StatementSpec statement) {
        return statement.query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    private static EmployeeRow row(ResultSet rs, int n) throws SQLException {
        return new EmployeeRow(rs.getString("employee_id"),
                new HrRecord(rs.getString("name"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("manager_id"), rs.getString("office_code"), rs.getString("shift_code")),
                rs.getBoolean("active"), rs.getLong("source_seq"), rs.getBytes("hr_hash"),
                rs.getString("pickup_point"), rs.getString("special_assistance"), rs.getLong("aggregate_revision"),
                rs.getString("tombstone_reason"), Sql.instant(rs, "updated_at"));
    }
}
