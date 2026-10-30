package com.moveinsync.rostersync.roster.repository;

import java.util.HashSet;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Office catalog (RLS table): call inside {@link TenantTx#inTenant}. */
@Repository
public class OfficeRepository {

    private final JdbcClient jdbc;

    public OfficeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> activeOfficeCodes(String tenantId) {
        return new HashSet<>(jdbc.sql("SELECT office_code FROM office WHERE tenant_id = :t AND active")
                .param("t", tenantId).query(String.class).list());
    }

    public void upsert(String tenantId, String officeCode, boolean active) {
        jdbc.sql("""
                INSERT INTO office (tenant_id, office_code, active) VALUES (:t, :c, :a)
                ON CONFLICT (tenant_id, office_code) DO UPDATE SET active = EXCLUDED.active""")
                .param("t", tenantId).param("c", officeCode).param("a", active)
                .update();
    }
}
