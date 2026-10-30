package com.moveinsync.rostersync.roster.bench;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Direct COPY fixture; never measured as part of reconciliation. Called in a bulk tenant transaction. */
public final class BenchmarkBaseline {
    public static final String COPY = "COPY benchmark_employee (tenant_id,source_id,employee_id,name,email,phone,manager_id,"
            + "office_code,shift_code,active,source_seq,hr_hash,bucket,aggregate_revision) FROM STDIN (FORMAT csv)";

    private BenchmarkBaseline() { }

    public static void load(DataSource dataSource, Path file) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Reader reader = Files.newBufferedReader(file); var statement = connection.createStatement()) {
            // PostgreSQL forbids COPY into RLS tables; INSERT from the temporary COPY table enforces RLS.
            statement.execute("CREATE TEMP TABLE benchmark_employee (LIKE employee INCLUDING DEFAULTS) ON COMMIT DROP");
            long copied = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(COPY, reader);
            if (copied != ExportGenerator.SIZE) { throw new IllegalStateException("incomplete benchmark baseline"); }
            if (statement.executeUpdate("INSERT INTO employee SELECT * FROM benchmark_employee") != ExportGenerator.SIZE) {
                throw new IllegalStateException("incomplete benchmark baseline insert");
            }
        } catch (Exception e) {
            throw new IllegalStateException("benchmark baseline COPY failed", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
