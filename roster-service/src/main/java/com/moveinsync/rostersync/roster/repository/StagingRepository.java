package com.moveinsync.rostersync.roster.repository;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

/**
 * Staging writes on the bulk pool (spec §9.2). Rows arrive in chunks and go to Postgres with
 * COPY FROM STDIN (CSV), so the application never holds the whole file. Call inside a bulk
 * TenantTx: record_error is RLS-protected.
 */
@Repository
public class StagingRepository {

    static final String COPY_SQL = "COPY staging_employee (run_id,row_no,employee_id,name,email,phone,manager_id,office_code,"
            + "shift_code,last_change_seq,hr_hash,bucket,valid,error_codes) FROM STDIN (FORMAT csv)";

    /** One staged row; HR values are normalised for valid rows, as received for invalid ones. */
    public record StagedRow(int rowNo, String employeeId, String name, String email, String phone, String managerId,
                            String officeCode, String shiftCode, Long lastChangeSeq, byte[] hrHash, Integer bucket,
                            boolean valid, List<String> errorCodes) {
    }

    /** A per-row error: row number, employee id when identifiable, field name and code. Never the value. */
    public record RowError(int rowNo, String employeeId, String field, String code) {
    }

    private final JdbcClient jdbc;
    private final DataSource dataSource;

    public StagingRepository(@Qualifier("bulk") JdbcClient jdbc, @Qualifier("bulk") DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    public long copy(UUID runId, List<StagedRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        StringWriter csv = new StringWriter(rows.size() * 160);
        try (CSVPrinter printer = new CSVPrinter(csv, CSVFormat.POSTGRESQL_CSV)) {
            for (StagedRow r : rows) {
                printer.printRecord(runId, r.rowNo(), r.employeeId(), r.name(), r.email(), r.phone(), r.managerId(),
                        r.officeCode(), r.shiftCode(), r.lastChangeSeq(),
                        r.hrHash() == null ? null : "\\x" + HexFormat.of().formatHex(r.hrHash()), r.bucket(),
                        r.valid() ? "t" : "f",
                        r.errorCodes() == null || r.errorCodes().isEmpty() ? null : "{" + String.join(",", r.errorCodes()) + "}");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY_SQL, new StringReader(csv.toString()));
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("COPY into staging_employee failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /** One statement per chunk: parallel arrays unnested into record_error. */
    public void insertErrors(String tenantId, UUID runId, List<RowError> errors) {
        if (errors.isEmpty()) {
            return;
        }
        jdbc.sql("""
                INSERT INTO record_error (tenant_id, run_id, row_no, employee_id, field, code)
                SELECT :t, :run, e.row_no, e.employee_id, e.field, e.code
                FROM unnest(:rows, :emps, :fields, :codes) AS e(row_no, employee_id, field, code)""")
                .param("t", tenantId).param("run", runId)
                .param("rows", errors.stream().map(RowError::rowNo).toArray(Integer[]::new))
                .param("emps", errors.stream().map(RowError::employeeId).toArray(String[]::new))
                .param("fields", errors.stream().map(RowError::field).toArray(String[]::new))
                .param("codes", errors.stream().map(RowError::code).toArray(String[]::new))
                .update();
    }

    public void deleteErrors(String tenantId, UUID runId) {
        jdbc.sql("DELETE FROM record_error WHERE tenant_id = :t AND run_id = :run").param("t", tenantId).param("run", runId).update();
    }

    /** First occurrences of conflicting duplicate ids become invalid (the second occurrence was never staged). */
    public void markDuplicateConflicts(UUID runId, List<String> employeeIds) {
        if (employeeIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE staging_employee SET valid = false,
                       error_codes = array_append(coalesce(error_codes, '{}'), 'DUPLICATE_ROW_CONFLICT')
                WHERE run_id = :run AND employee_id = ANY(:ids)""")
                .param("run", runId).param("ids", employeeIds.toArray(String[]::new)).update();
    }

    public record StagedCounts(int valid, int invalid) {
    }

    public StagedCounts counts(UUID runId) {
        return jdbc.sql("SELECT count(*) FILTER (WHERE valid) AS v, count(*) FILTER (WHERE NOT valid) AS i FROM staging_employee WHERE run_id = :run")
                .param("run", runId).query((rs, n) -> new StagedCounts(rs.getInt("v"), rs.getInt("i"))).single();
    }

    public void analyze() {
        jdbc.sql("ANALYZE staging_employee").update();
    }

    public int delete(UUID runId) {
        return jdbc.sql("DELETE FROM staging_employee WHERE run_id = :run").param("run", runId).update();
    }

    public long count(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM staging_employee WHERE run_id = :run").param("run", runId).query(Long.class).single();
    }
}
