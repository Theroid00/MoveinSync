package com.moveinsync.rostersync.booking.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** employee_projection: booking's revision-gated view of each employee's active flag (RLS). */
@Repository
public class ProjectionRepository {

    private final JdbcClient jdbc;

    public ProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Projection(boolean active, long lastRevision) {
    }

    /** Creates the row at revision 0 if absent, then locks it. */
    public Projection ensureAndLock(String tenantId, String employeeId) {
        jdbc.sql("""
                INSERT INTO employee_projection (tenant_id, employee_id, active, last_revision) VALUES (:t, :e, false, 0)
                ON CONFLICT (tenant_id, employee_id) DO NOTHING""")
                .param("t", tenantId).param("e", employeeId).update();
        return lock(tenantId, employeeId).orElseThrow();
    }

    public Optional<Projection> lock(String tenantId, String employeeId) {
        return jdbc.sql("""
                SELECT active, last_revision FROM employee_projection
                WHERE tenant_id = :t AND employee_id = :e FOR UPDATE""")
                .param("t", tenantId).param("e", employeeId)
                .query((rs, n) -> new Projection(rs.getBoolean("active"), rs.getLong("last_revision")))
                .optional();
    }

    public void update(String tenantId, String employeeId, boolean active, long revision, Instant effectiveAt) {
        jdbc.sql("""
                UPDATE employee_projection SET active = :a, last_revision = :r, effective_at = :at
                WHERE tenant_id = :t AND employee_id = :e""")
                .param("a", active).param("r", revision).param("at", Sql.ts(effectiveAt))
                .param("t", tenantId).param("e", employeeId)
                .update();
    }

    public long countActive(String tenantId) {
        return jdbc.sql("SELECT count(*) FROM employee_projection WHERE tenant_id = :t AND active")
                .param("t", tenantId).query(Long.class).single();
    }
}
