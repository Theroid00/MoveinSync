package com.moveinsync.rostersync.roster.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Transaction-scoped advisory locks for the API path. The tenant lock is taken shared (it only waits
 * for a reconciliation apply holding it exclusively); the per-employee lock serialises every event for
 * one employee, including the "no row yet" create/deactivate/tombstone cases.
 */
@Repository
public class LockRepository {

    private final JdbcClient jdbc;

    public LockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockTimeout3s() {
        jdbc.sql("SET LOCAL lock_timeout = '3s'").update();
    }

    public void tenantShared(long lockKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(:k)").param("k", lockKey).query().listOfRows();
    }

    public void employee(String tenantId, String sourceId, String employeeId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:t || '|' || :s || '|' || :e, 0))")
                .param("t", tenantId).param("s", sourceId).param("e", employeeId)
                .query().listOfRows();
    }
}
