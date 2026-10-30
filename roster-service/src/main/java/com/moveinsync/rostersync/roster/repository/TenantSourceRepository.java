package com.moveinsync.rostersync.roster.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** tenant_source: a system table (no RLS) read by tenant and source in every query. */
@Repository
public class TenantSourceRepository {

    private final JdbcClient jdbc;

    public TenantSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> lockKey(String tenantId, String sourceId) {
        return jdbc.sql("SELECT lock_key FROM tenant_source WHERE tenant_id = :t AND source_id = :s")
                .param("t", tenantId).param("s", sourceId)
                .query(Long.class).optional();
    }

    /** Read inside the ingest transaction, never from a cache: it guards against resurrection. */
    public long absenceWatermark(String tenantId, String sourceId) {
        return jdbc.sql("SELECT absence_watermark FROM tenant_source WHERE tenant_id = :t AND source_id = :s")
                .param("t", tenantId).param("s", sourceId)
                .query(Long.class).single();
    }

    public Optional<Long> acceptedSnapshotCutoff(String tenantId, String sourceId) {
        return jdbc.sql("SELECT accepted_snapshot_cutoff FROM tenant_source WHERE tenant_id = :t AND source_id = :s")
                .param("t", tenantId).param("s", sourceId)
                .query(Long.class).optional();
    }

    public void ensure(String tenantId, String sourceId, long lockKey) {
        jdbc.sql("""
                INSERT INTO tenant_source (tenant_id, source_id, lock_key) VALUES (:t, :s, :k)
                ON CONFLICT (tenant_id, source_id) DO NOTHING""")
                .param("t", tenantId).param("s", sourceId).param("k", lockKey)
                .update();
    }
}
