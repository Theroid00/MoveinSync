package com.moveinsync.rostersync.roster.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relay-side outbox SQL (spec §10). outbox_event is a system table without RLS: the relay reads across
 * tenants; operator endpoints always filter by the caller's tenant. Every state change after a claim
 * is guarded by the lease token, so a worker whose lease expired cannot acknowledge.
 */
@Repository
public class OutboxRelayRepository {

    private final JdbcClient jdbc;

    public OutboxRelayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Claimed(long id, UUID eventId, String tenantId, String employeeId, long aggregateRevision,
                          String payload, int attempts, Instant createdAt) {
    }

    /**
     * Claims due rows (and expired leases) whose earlier revisions for the same employee are all
     * delivered. SKIP LOCKED lets several relays split the queue; attempts counts every send.
     */
    public List<Claimed> claim(UUID token, int batch, int leaseSecs) {
        return jdbc.sql("""
                WITH next AS (
                  SELECT o.id FROM outbox_event o
                  WHERE ((o.state IN ('PENDING','RETRY') AND o.next_attempt_at <= now())
                      OR (o.state = 'CLAIMED' AND o.lease_until < now()))
                    AND NOT EXISTS (SELECT 1 FROM outbox_event p
                                    WHERE p.tenant_id = o.tenant_id AND p.employee_id = o.employee_id
                                      AND p.aggregate_revision < o.aggregate_revision AND p.state <> 'DELIVERED')
                  ORDER BY o.id LIMIT :batch FOR UPDATE SKIP LOCKED)
                UPDATE outbox_event SET state = 'CLAIMED', lease_token = :token,
                       lease_until = now() + make_interval(secs => :leaseSecs), attempts = attempts + 1
                WHERE id IN (SELECT id FROM next)
                RETURNING id, event_id, tenant_id, employee_id, aggregate_revision, payload::text AS payload, attempts, created_at""")
                .param("batch", batch).param("token", token).param("leaseSecs", leaseSecs)
                .query((rs, n) -> new Claimed(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("employee_id"), rs.getLong("aggregate_revision"),
                        rs.getString("payload"), rs.getInt("attempts"), Sql.instant(rs, "created_at")))
                .list();
    }

    public boolean markDelivered(long id, UUID token) {
        return jdbc.sql("""
                UPDATE outbox_event SET state = 'DELIVERED', delivered_at = now(), lease_token = NULL
                WHERE id = :id AND lease_token = :token""")
                .param("id", id).param("token", token).update() == 1;
    }

    public boolean markRetry(long id, UUID token, String errorCode, double delaySeconds) {
        return jdbc.sql("""
                UPDATE outbox_event SET state = 'RETRY', lease_token = NULL, lease_until = NULL, last_error_code = :code,
                       next_attempt_at = now() + make_interval(secs => :delay)
                WHERE id = :id AND lease_token = :token""")
                .param("id", id).param("token", token).param("code", errorCode).param("delay", delaySeconds)
                .update() == 1;
    }

    public boolean markDead(long id, UUID token, String errorCode) {
        return jdbc.sql("""
                UPDATE outbox_event SET state = 'DEAD', lease_token = NULL, lease_until = NULL, last_error_code = :code
                WHERE id = :id AND lease_token = :token""")
                .param("id", id).param("token", token).param("code", errorCode).update() == 1;
    }

    /** DEAD → PENDING with attempts reset; the event keeps its event_id so the consumer can dedup. */
    public boolean replay(String tenantId, long id) {
        return jdbc.sql("""
                UPDATE outbox_event SET state = 'PENDING', attempts = 0, next_attempt_at = now(),
                       lease_token = NULL, lease_until = NULL
                WHERE id = :id AND tenant_id = :t AND state = 'DEAD'""")
                .param("id", id).param("t", tenantId).update() == 1;
    }

    public Optional<String> state(String tenantId, long id) {
        return jdbc.sql("SELECT state FROM outbox_event WHERE id = :id AND tenant_id = :t")
                .param("id", id).param("t", tenantId).query(String.class).optional();
    }

    public Map<String, Long> countsByState(String tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : List.of("PENDING", "CLAIMED", "RETRY", "DELIVERED", "DEAD")) {
            counts.put(state, 0L);
        }
        jdbc.sql("SELECT state, count(*) AS n FROM outbox_event WHERE tenant_id = :t GROUP BY state")
                .param("t", tenantId)
                .query((RowCallbackHandler) rs -> counts.put(rs.getString("state"), rs.getLong("n")));
        return counts;
    }

    /** Counts by state across all tenants (metrics only). */
    public Map<String, Long> countsByStateAllTenants() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("SELECT state, count(*) AS n FROM outbox_event GROUP BY state")
                .query((RowCallbackHandler) rs -> counts.put(rs.getString("state"), rs.getLong("n")));
        return counts;
    }

    /** Age in seconds of the oldest undelivered row (all tenants when tenantId is null); 0 when none. */
    public double oldestPendingAgeSeconds(String tenantId) {
        return jdbc.sql("""
                SELECT coalesce(EXTRACT(EPOCH FROM now() - min(created_at))::float8, 0) FROM outbox_event
                WHERE (CAST(:t AS text) IS NULL OR tenant_id = :t) AND state IN ('PENDING','CLAIMED','RETRY')""")
                .param("t", tenantId).query(Double.class).single();
    }

    public int purgeDelivered(int retentionDays) {
        return jdbc.sql("DELETE FROM outbox_event WHERE state = 'DELIVERED' AND delivered_at < now() - make_interval(days => :d)")
                .param("d", retentionDays).update();
    }
}
