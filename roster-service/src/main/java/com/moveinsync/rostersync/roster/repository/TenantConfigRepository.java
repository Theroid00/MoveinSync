package com.moveinsync.rostersync.roster.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-tenant brake threshold in basis points (default 3000 = 30%). */
@Repository
public class TenantConfigRepository {

    private final JdbcClient jdbc;

    public TenantConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Integer> brakeThresholdBp(String tenantId) {
        return jdbc.sql("SELECT brake_threshold_bp FROM tenant_config WHERE tenant_id = :t")
                .param("t", tenantId).query(Integer.class).optional();
    }

    public void ensureDefault(String tenantId) {
        jdbc.sql("INSERT INTO tenant_config (tenant_id) VALUES (:t) ON CONFLICT (tenant_id) DO NOTHING").param("t", tenantId).update();
    }

    public void upsert(String tenantId, int thresholdBp) {
        jdbc.sql("""
                INSERT INTO tenant_config (tenant_id, brake_threshold_bp) VALUES (:t, :bp)
                ON CONFLICT (tenant_id) DO UPDATE SET brake_threshold_bp = EXCLUDED.brake_threshold_bp""")
                .param("t", tenantId).param("bp", thresholdBp).update();
    }
}
