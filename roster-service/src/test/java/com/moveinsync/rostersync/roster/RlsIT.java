package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Row-Level Security is a real second wall: even a query with no tenant filter sees one tenant. */
@SpringBootTest
class RlsIT extends AbstractPostgresIT {

    static final List<String> RLS_TABLES =
            List.of("office", "employee", "employee_tombstone", "ingest_event", "employee_history", "record_error");

    @Autowired
    TenantTx tenantTx;

    @Autowired
    JdbcClient jdbc;

    String acme;
    String globex;

    @BeforeEach
    void twoTenantsWithData() {
        acme = "acme-" + UUID.randomUUID();
        globex = "globex-" + UUID.randomUUID();
        JdbcClient su = superuserJdbc();
        for (String tenant : List.of(acme, globex)) {
            su.sql("INSERT INTO office (tenant_id, office_code) VALUES (?, 'BLR-A')").param(tenant).update();
            su.sql("""
                    INSERT INTO employee (tenant_id, source_id, employee_id, name, email, phone, office_code, shift_code,
                                          active, source_seq, hr_hash, bucket, aggregate_revision)
                    VALUES (?, 'hr-main', 'EMP-001', 'Fixture', 'f@x.test', '+10000000000', 'BLR-A', 'MORNING',
                            true, 1, '\\x00'::bytea, 0, 1)""").param(tenant).update();
            su.sql("INSERT INTO record_error (tenant_id, code) VALUES (?, 'PHONE_E164')").param(tenant).update();
        }
    }

    @Test
    void unscopedQueryReturnsOnlyCurrentTenantRows() {
        for (String table : List.of("office", "employee", "record_error")) {
            List<String> seen = tenantTx.inTenant(acme,
                    s -> jdbc.sql("SELECT tenant_id FROM " + table).query(String.class).list());
            assertThat(seen).as(table).isNotEmpty().containsOnly(acme);
        }
        // No tenant set at all (fresh autocommit statement): nothing is visible.
        assertThat(jdbc.sql("SELECT count(*) FROM employee").query(Long.class).single()).isZero();
    }

    @Test
    void writeIntoAnotherTenantIsRejectedByPolicy() {
        assertThatThrownBy(() -> tenantTx.inTenant(acme, s -> jdbc
                .sql("INSERT INTO office (tenant_id, office_code) VALUES (:other, 'GGN-A')")
                .param("other", globex)
                .update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void appRoleIsNotOwnerAndHasNoBypass() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("roster_app");
        Map<String, Object> role = jdbc.sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query().singleRow();
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);

        for (String table : RLS_TABLES) {
            Map<String, Object> rel = jdbc.sql("""
                    SELECT pg_get_userbyid(c.relowner) AS owner, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = :t""").param("t", table).query().singleRow();
            assertThat(rel).as(table)
                    .containsEntry("owner", "roster_owner")
                    .containsEntry("relrowsecurity", true)
                    .containsEntry("relforcerowsecurity", true);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM pg_policies WHERE policyname = 'tenant_isolation'")
                .query(Long.class).single()).isEqualTo(RLS_TABLES.size());
    }
}
