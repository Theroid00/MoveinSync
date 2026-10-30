package com.moveinsync.rostersync.roster.repository;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-record error report: ids, field names and codes only, never the rejected value. */
@Repository
public class RecordErrorRepository {

    private final JdbcClient jdbc;

    public RecordErrorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertForEvent(String tenantId, String eventId, UUID rejectionId, String employeeId, String field,
                               String code) {
        jdbc.sql("""
                INSERT INTO record_error (tenant_id, event_id, rejection_id, employee_id, field, code)
                VALUES (:t, :ev, :rej, :e, :field, :code)""")
                .param("t", tenantId).param("ev", eventId).param("rej", rejectionId).param("e", employeeId)
                .param("field", field).param("code", code)
                .update();
    }
}
