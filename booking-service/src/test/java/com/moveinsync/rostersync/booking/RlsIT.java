package com.moveinsync.rostersync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moveinsync.rostersync.booking.repository.BookingTenantTx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Row-Level Security in the booking DB: unscoped queries see only the current tenant. */
@SpringBootTest
class RlsIT extends AbstractPostgresIT {

    static final List<String> RLS_TABLES = List.of("employee_projection", "trip", "booking", "seat_reservation");

    @Autowired
    BookingTenantTx tenantTx;

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
            su.sql("INSERT INTO trip (trip_id, tenant_id, capacity, starts_at) VALUES (?, ?, 4, now() + interval '1 day')")
                    .params(UUID.randomUUID(), tenant).update();
            su.sql("INSERT INTO employee_projection (tenant_id, employee_id, active, last_revision) VALUES (?, 'EMP-001', true, 1)")
                    .param(tenant).update();
        }
    }

    @Test
    void unscopedQueryReturnsOnlyCurrentTenantRows() {
        for (String table : List.of("trip", "employee_projection")) {
            List<String> seen = tenantTx.inTenant(acme,
                    s -> jdbc.sql("SELECT tenant_id FROM " + table).query(String.class).list());
            assertThat(seen).as(table).isNotEmpty().containsOnly(acme);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM trip").query(Long.class).single()).isZero();
    }

    @Test
    void writeIntoAnotherTenantIsRejectedByPolicy() {
        assertThatThrownBy(() -> tenantTx.inTenant(acme, s -> jdbc
                .sql("INSERT INTO trip (trip_id, tenant_id, capacity, starts_at) VALUES (:id, :other, 4, now())")
                .param("id", UUID.randomUUID())
                .param("other", globex)
                .update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void appRoleIsNotOwnerAndHasNoBypass() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("booking_app");
        Map<String, Object> role = jdbc.sql("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query().singleRow();
        assertThat(role).containsEntry("rolsuper", false).containsEntry("rolbypassrls", false);
        for (String table : RLS_TABLES) {
            Map<String, Object> rel = jdbc.sql("""
                    SELECT pg_get_userbyid(c.relowner) AS owner, c.relrowsecurity, c.relforcerowsecurity
                    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = :t""").param("t", table).query().singleRow();
            assertThat(rel).as(table)
                    .containsEntry("owner", "booking_owner")
                    .containsEntry("relrowsecurity", true)
                    .containsEntry("relforcerowsecurity", true);
        }
    }
}
