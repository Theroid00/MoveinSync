package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.upsert;
import static com.moveinsync.rostersync.roster.Events.withHr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import com.moveinsync.rostersync.roster.RosterApi.Response;
import com.moveinsync.rostersync.roster.RosterApi.Role;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every error path returns a ProblemDetail with errorCode and traceId, a fixed title, and nothing that
 * looks like a stack trace or an echoed value (spec §13.1). Later phases add their codes here.
 */
class ErrorContractIT extends AbstractApiIT {

    @TestConfiguration
    static class FailingEndpoints {
        @Bean
        FailingController failingController(JdbcClient jdbc) {
            return new FailingController(jdbc);
        }
    }

    @RestController
    static class FailingController {
        private final JdbcClient jdbc;

        FailingController(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        /** Exceeds the app role's 5 s statement_timeout on the primary pool. */
        @GetMapping("/test/errors/db-timeout")
        String dbTimeout() {
            return jdbc.sql("SELECT pg_sleep(6)::text").query(String.class).single();
        }

        @GetMapping("/test/errors/internal")
        String internal() {
            throw new IllegalStateException("internal detail Zyxw 7731");
        }
    }

    static void assertProblem(Response r, int status, String code) {
        assertThat(r.status()).as(code).isEqualTo(status);
        assertThat(r.servlet().getContentType()).as(code).startsWith("application/problem+json");
        assertThat(r.text("errorCode")).isEqualTo(code);
        assertThat(r.text("traceId")).as(code).isNotBlank();
        assertThat(r.text("title")).as(code).isNotBlank();
        assertThat(r.body().get("status").asInt()).isEqualTo(status);
        // The random traceId is excluded: a UUID can contain any 4 hex digits, including a marker.
        String withoutTraceId = r.raw().replace(r.text("traceId"), "");
        assertThat(withoutTraceId).as(code).doesNotContain("Exception", "\tat ", ".java:", "stackTrace", "SELECT", "7731");
    }

    @Test
    void validationFailed() {
        assertProblem(api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "phone", "x")), 422, "VALIDATION_FAILED");
    }

    @Test
    void unknownField() {
        assertProblem(api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "pickupPoint", "Gate")), 422, "UNKNOWN_FIELD");
    }

    @Test
    void malformedJson() {
        assertProblem(api.postEvent(tenant, "{\"eventId\": \"e-1\", "), 400, "MALFORMED_JSON");
        assertProblem(api.postEvent(tenant, "{\"eventId\": \"e-1\", \"sourceSeq\": \"seven\"}"), 400, "MALFORMED_JSON");
    }

    @Test
    void notFound() {
        assertProblem(api.getEmployee(tenant, "EMP-404"), 404, "NOT_FOUND");
        assertProblem(api.perform(get("/api/v1/no-such-endpoint"), tenant, Role.ADMIN), 404, "NOT_FOUND");
    }

    @Test
    void versionConflictAndEventIdReused() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        assertProblem(api.postEvent(tenant, withHr(upsert("e-2", 1, "EMP-1"), "shiftCode", "NIGHT")), 409, "VERSION_CONFLICT");
        assertProblem(api.postEvent(tenant, withHr(upsert("e-1", 1, "EMP-1"), "shiftCode", "NIGHT")), 409, "EVENT_ID_REUSED");
    }

    @Test
    void revisionMismatchAndAlreadyInactive() {
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        assertProblem(api.patchTransport(tenant, "EMP-1", "7", Map.of("pickupPoint", "Gate")), 409, "REVISION_MISMATCH");
        api.deactivate(tenant, "EMP-1");
        assertProblem(api.deactivate(tenant, "EMP-1"), 409, "ALREADY_INACTIVE");
    }

    @Test
    void payloadTooLarge() {
        String line = json.writeValueAsString(upsert("e", 1, "EMP-1")) + "\n";
        assertProblem(api.postBatch(tenant, line.repeat(1001)), 413, "PAYLOAD_TOO_LARGE");
        assertThat(count("employee", tenant)).isZero();
    }

    @Test
    void unauthenticated() {
        assertProblem(api.perform(post("/api/v1/hr/events").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(upsert("e-1", 1, "EMP-1"))), null, Role.HR_INTEGRATION), 401, "UNAUTHENTICATED");
    }

    @Test
    void forbidden() {
        assertProblem(api.perform(get("/api/v1/employees/EMP-1"), tenant, Role.HR_INTEGRATION), 403, "FORBIDDEN");
    }

    @Test
    void reconciliationInProgressWhileTenantLockHeldExclusively() throws Exception {
        long lockKey = db.sql("SELECT lock_key FROM tenant_source WHERE tenant_id = ?").param(tenant).query(Long.class).single();
        try (Connection holder = DriverManager.getConnection(jdbcUrl(""), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
            }
            Response r = api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
            assertProblem(r, 503, "RECONCILIATION_IN_PROGRESS");
            assertThat(r.header("Retry-After")).isEqualTo("5");
            holder.rollback();
        }
        assertThat(api.postEvent(tenant, upsert("e-1", 1, "EMP-1")).text("outcome")).isEqualTo("APPLIED");
    }

    @Test
    void dbUnavailable() {
        Response r = api.perform(get("/test/errors/db-timeout"), tenant, Role.ADMIN);
        assertProblem(r, 503, "DB_UNAVAILABLE");
        assertThat(r.header("Retry-After")).isEqualTo("5");
    }

    @Test
    void internalError() {
        Response r = api.perform(get("/test/errors/internal"), tenant, Role.ADMIN);
        assertProblem(r, 500, "INTERNAL_ERROR");
        assertThat(r.raw()).doesNotContain("internal detail", "Zyxw");
    }

    /** A reconciliation run row inserted directly, so no asynchronous processing is involved. */
    java.util.UUID fixtureRun(String status, long cutoff, String[] flags, byte[] assessmentHash, byte[] fileSha) {
        java.util.UUID id = java.util.UUID.randomUUID();
        db.sql("""
                INSERT INTO reconciliation_run (run_id, tenant_id, source_id, cutoff_seq, exported_at, file_path, status,
                                                flags, assessment_hash, file_sha256)
                VALUES (?, ?, 'hr-main', ?, now(), '/none.csv', ?, ?, ?, ?)""")
                .params(id, tenant, cutoff, status, flags, assessmentHash, fileSha).update();
        return id;
    }

    Response upload(long cutoff) {
        return api.uploadSnapshot(tenant, Role.OPERATOR, Snapshot.csv(Snapshot.rows(1, 3)), Snapshot.manifest(cutoff));
    }

    @Test
    void reconciliationRunErrors() {
        fixtureRun("COMPLETED", 300, new String[0], null, new byte[32]);
        assertProblem(upload(300), 409, "SNAPSHOT_CONFLICT");

        db.sql("UPDATE tenant_source SET accepted_snapshot_cutoff = 500 WHERE tenant_id = ?").param(tenant).update();
        assertProblem(upload(100), 409, "STALE_SNAPSHOT");

        java.util.UUID active = fixtureRun("STAGING", 600, new String[0], null, null);
        assertProblem(upload(600), 409, "RUN_IN_PROGRESS");
        db.sql("UPDATE reconciliation_run SET status = 'FAILED' WHERE run_id = ?").param(active).update();

        java.util.UUID truncated = fixtureRun("AWAITING_APPROVAL", 700, new String[] {"ROW_COUNT_MISMATCH"}, new byte[32], null);
        assertProblem(api.approveRun(tenant, truncated.toString(), "00".repeat(32)), 409, "RUN_NOT_APPROVABLE");

        java.util.UUID blocked = fixtureRun("AWAITING_APPROVAL", 800, new String[] {"MISSING_RATIO_EXCEEDED"}, new byte[32], null);
        assertProblem(api.approveRun(tenant, blocked.toString(), "ff".repeat(32)), 409, "APPROVAL_STALE");

        java.util.UUID completed = fixtureRun("COMPLETED", 900, new String[0], null, null);
        assertProblem(api.rejectRun(tenant, completed.toString()), 409, "INVALID_RUN_STATE");
    }

    @Test
    void traceIdEchoesRequestId() {
        Response r = api.perform(get("/api/v1/employees/EMP-404").header("X-Request-Id", "req-abc-123"), tenant, Role.ADMIN);
        assertThat(r.text("traceId")).isEqualTo("req-abc-123");
        assertThat(r.header("X-Request-Id")).isEqualTo("req-abc-123");
    }
}
