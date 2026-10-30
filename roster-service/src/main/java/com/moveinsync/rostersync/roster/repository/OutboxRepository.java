package com.moveinsync.rostersync.roster.repository;

import com.moveinsync.rostersync.contract.EmployeeChangedEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbox writes. Called inside the same transaction as the employee change, so the change and its
 * event commit or roll back together. The payload is the contract record serialised: IDs only.
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(EmployeeChangedEvent event) {
        jdbc.sql("""
                INSERT INTO outbox_event (event_id, tenant_id, employee_id, aggregate_revision, event_type, payload)
                VALUES (:id, :t, :e, :rev, :type, CAST(:payload AS jsonb))""")
                .param("id", event.eventId()).param("t", event.tenantId()).param("e", event.employeeId())
                .param("rev", event.aggregateRevision()).param("type", event.eventType().name())
                .param("payload", json.writeValueAsString(event))
                .update();
    }
}
