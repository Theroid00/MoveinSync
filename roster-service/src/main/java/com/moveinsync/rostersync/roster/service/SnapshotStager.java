package com.moveinsync.rostersync.roster.service;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moveinsync.rostersync.roster.cache.OfficeCatalog;
import com.moveinsync.rostersync.roster.core.Bucketer;
import com.moveinsync.rostersync.roster.core.CanonicalHasher;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.core.HrEventKind;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.core.RecordValidator;
import com.moveinsync.rostersync.roster.core.Rules;
import com.moveinsync.rostersync.roster.core.Sha256;
import com.moveinsync.rostersync.roster.observability.RosterMetrics;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.repository.RunRow;
import com.moveinsync.rostersync.roster.repository.StagingRepository;
import com.moveinsync.rostersync.roster.repository.StagingRepository.RowError;
import com.moveinsync.rostersync.roster.repository.StagingRepository.StagedRow;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Stage phase (spec §9.2): stream the stored file record by record, validate, normalise and hash each
 * row, and COPY chunks of 5,000 into staging_employee. Memory is bounded by the chunk plus the set of
 * seen employee ids (≈20 MB for 200k ids, DEC-18).
 */
@Component
public class SnapshotStager {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStager.class);

    public static final List<String> HEADER = List.of("employee_id", "name", "email", "phone", "manager_id",
            "office_code", "shift_code", "last_change_seq");
    static final int CHUNK = 5000;
    static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();

    public enum Result { STAGED, INCOMPLETE }

    /** First occurrence of an employee id in the file. */
    private static final class Seen {
        final int rowNo;
        final long contentHash;
        boolean conflicted;

        Seen(int rowNo, long contentHash) {
            this.rowNo = rowNo;
            this.contentHash = contentHash;
        }
    }

    private static final class MalformedCsvException extends RuntimeException {
        MalformedCsvException() {
            super("malformed CSV structure", null, false, false);
        }
    }

    private final TenantTx bulkTx;
    private final StagingRepository staging;
    private final ReconciliationRunRepository runs;
    private final OfficeCatalog offices;
    private final RosterMetrics metrics;

    public SnapshotStager(@Qualifier("bulk") TenantTx bulkTx, StagingRepository staging, ReconciliationRunRepository runs,
                          OfficeCatalog offices, RosterMetrics metrics) {
        this.bulkTx = bulkTx;
        this.staging = staging;
        this.runs = runs;
        this.offices = offices;
        this.metrics = metrics;
    }

    public Result stage(RunRow run) {
        long started = System.nanoTime();
        String tenant = run.tenantId();
        runs.setStatus(run.runId(), "STAGING");
        bulkTx.inTenant(tenant, s -> {
            staging.delete(run.runId());
            staging.deleteErrors(tenant, run.runId());
            return null;
        });

        RecordValidator validator = RecordValidator.forSnapshot(run.cutoffSeq());
        Set<String> officeCodes = offices.activeOffices(tenant);
        Map<String, Seen> seen = new HashMap<>();
        List<String> conflictIds = new ArrayList<>();
        List<StagedRow> rows = new ArrayList<>(CHUNK);
        List<RowError> errors = new ArrayList<>();
        int parsed = 0;
        int unidentifiable = 0;
        int skippedConflicts = 0;

        try (Reader reader = Files.newBufferedReader(Path.of(run.filePath()), StandardCharsets.UTF_8);
             CSVParser parser = FORMAT.parse(reader)) {
            if (!parser.getHeaderNames().containsAll(HEADER)) {
                throw new MalformedCsvException();
            }
            for (CSVRecord record : parser) {
                int rowNo = ++parsed;
                if (!record.isConsistent()) {
                    throw new MalformedCsvException();
                }
                String employeeId = trim(record.get("employee_id"));
                if (!Rules.isValidEmployeeId(employeeId)) {
                    unidentifiable++;
                    errors.add(new RowError(rowNo, null, "employeeId", "EMPLOYEE_ID_FORMAT"));
                    continue;
                }
                long contentHash = contentHash(record);
                Seen first = seen.get(employeeId);
                if (first != null) {
                    if (first.contentHash == contentHash) {
                        continue;                                    // exact duplicate row: skip
                    }
                    if (!first.conflicted) {
                        first.conflicted = true;
                        conflictIds.add(employeeId);
                        errors.add(new RowError(first.rowNo, employeeId, "employeeId", "DUPLICATE_ROW_CONFLICT"));
                    }
                    errors.add(new RowError(rowNo, employeeId, "employeeId", "DUPLICATE_ROW_CONFLICT"));
                    skippedConflicts++;
                    continue;
                }
                seen.put(employeeId, new Seen(rowNo, contentHash));
                rows.add(toStagedRow(record, rowNo, employeeId, validator, officeCodes, errors));
                if (rows.size() >= CHUNK) {
                    flush(run, rows, errors);
                }
            }
            flush(run, rows, errors);
        } catch (UncheckedIOException | MalformedCsvException | IllegalArgumentException e) {
            return incomplete(run, "CSV_MALFORMED");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        StagingRepository.StagedCounts counts = bulkTx.inTenant(tenant, s -> {
            staging.markDuplicateConflicts(run.runId(), conflictIds);
            return staging.counts(run.runId());
        });
        List<String> flags = new ArrayList<>();
        if (run.expectedRows() != null && parsed != run.expectedRows()) {
            flags.add("ROW_COUNT_MISMATCH");
        }
        runs.recordStaging(run.runId(), parsed, counts.valid(), counts.invalid() + skippedConflicts, unidentifiable, flags);

        if (run.expectedSha256() != null && !Arrays.equals(run.expectedSha256(), run.fileSha256())) {
            return incomplete(run, "CHECKSUM_MISMATCH");
        }
        bulkTx.inTenant(tenant, s -> {
            staging.analyze();
            return null;
        });
        runs.setStatus(run.runId(), "STAGED");
        long millis = (System.nanoTime() - started) / 1_000_000;
        runs.phase(run.runId(), "stage", millis);
        metrics.reconciliationPhase("stage", millis);
        log.info("event=recon_staged runId={} parsed={} valid={} unidentifiable={} ms={}", run.runId(), parsed,
                counts.valid(), unidentifiable, millis);
        return Result.STAGED;
    }

    private static StagedRow toStagedRow(CSVRecord record, int rowNo, String employeeId, RecordValidator validator,
                                         Set<String> officeCodes, List<RowError> errors) {
        Long seq = parseSeq(record.get("last_change_seq"));
        HrRecord raw = new HrRecord(record.get("name"), record.get("email"), record.get("phone"),
                record.get("manager_id"), record.get("office_code"), record.get("shift_code"));
        List<FieldError> problems = validator.validate(
                new HrEventRequest(null, seq, HrEventKind.UPSERT, employeeId, null, raw), officeCodes::contains);
        int bucket = Bucketer.bucket(employeeId);
        if (problems.isEmpty()) {
            HrRecord n = CanonicalHasher.normalize(raw);
            return new StagedRow(rowNo, employeeId, n.name(), n.email(), n.phone(), n.managerId(), n.officeCode(),
                    n.shiftCode(), seq, CanonicalHasher.hrHash(employeeId, n), bucket, true, null);
        }
        for (FieldError problem : problems) {
            errors.add(new RowError(rowNo, employeeId, problem.field(), problem.code()));
        }
        return new StagedRow(rowNo, employeeId, raw.name(), raw.email(), raw.phone(), raw.managerId(), raw.officeCode(),
                raw.shiftCode(), seq, null, bucket, false, problems.stream().map(FieldError::code).distinct().toList());
    }

    private void flush(RunRow run, List<StagedRow> rows, List<RowError> errors) {
        if (rows.isEmpty() && errors.isEmpty()) {
            return;
        }
        bulkTx.inTenant(run.tenantId(), s -> {
            staging.copy(run.runId(), rows);
            staging.insertErrors(run.tenantId(), run.runId(), errors);
            return null;
        });
        rows.clear();
        errors.clear();
    }

    private Result incomplete(RunRow run, String errorCode) {
        bulkTx.inTenant(run.tenantId(), s -> staging.delete(run.runId()));
        runs.finish(run.runId(), "INCOMPLETE", errorCode);
        metrics.reconciliationRun("INCOMPLETE");
        log.warn("event=recon_incomplete runId={} code={}", run.runId(), errorCode);
        return Result.INCOMPLETE;
    }

    /** 64 bits of SHA-256 over the raw row: distinguishes exact duplicates from conflicting ones. */
    private static long contentHash(CSVRecord record) {
        StringBuilder sb = new StringBuilder();
        for (String column : HEADER) {
            sb.append(record.get(column)).append('\u001F');
        }
        return ByteBuffer.wrap(Sha256.of(sb.toString())).getLong();
    }

    private static Long parseSeq(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
