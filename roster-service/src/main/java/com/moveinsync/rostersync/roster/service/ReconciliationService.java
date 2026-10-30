package com.moveinsync.rostersync.roster.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.config.ReconProperties;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.core.Sha256;
import com.moveinsync.rostersync.roster.observability.Mdc;
import com.moveinsync.rostersync.roster.observability.RosterMetrics;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository.ErrorRow;
import com.moveinsync.rostersync.roster.repository.RunRow;
import com.moveinsync.rostersync.roster.repository.StagingRepository;
import com.moveinsync.rostersync.roster.repository.TenantSourceRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Nightly reconciliation lifecycle (spec §9): upload (stream to disk + SHA-256, 202), asynchronous
 * stage → assess/apply, operator approval / rejection / retry, and startup recovery.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final int MAX_ERRORS_PAGE = 500;

    /** Snapshot manifest (multipart part). */
    public record Manifest(String sourceId, Long cutoffSeq, Instant exportedAt, Integer expectedRows, String sha256) {
    }

    public record Accepted(UUID runId, String status) {
    }

    private final ReconciliationRunRepository runs;
    private final TenantSourceRepository tenantSources;
    private final SnapshotStager stager;
    private final SnapshotApplier applier;
    private final StagingRepository staging;
    private final TenantTx tenantTx;
    private final TenantTx bulkTx;
    private final ThreadPoolTaskExecutor executor;
    private final RosterMetrics metrics;
    private final Path dir;

    public ReconciliationService(ReconciliationRunRepository runs, TenantSourceRepository tenantSources,
                                 SnapshotStager stager, SnapshotApplier applier, StagingRepository staging,
                                 TenantTx tenantTx, @Qualifier("bulk") TenantTx bulkTx,
                                 @Qualifier("reconciliationExecutor") ThreadPoolTaskExecutor executor,
                                 RosterMetrics metrics, ReconProperties properties) {
        this.runs = runs;
        this.tenantSources = tenantSources;
        this.stager = stager;
        this.applier = applier;
        this.staging = staging;
        this.tenantTx = tenantTx;
        this.bulkTx = bulkTx;
        this.executor = executor;
        this.metrics = metrics;
        this.dir = Path.of(properties.dir());
    }

    public Accepted upload(String tenantId, InputStream file, Manifest manifest) {
        byte[] expectedSha = validate(manifest);
        long accepted = tenantSources.acceptedSnapshotCutoff(tenantId, manifest.sourceId())
                .orElseThrow(() -> new ValidationFailedException(List.of(new FieldError("sourceId", "SOURCE_UNKNOWN"))));
        if (manifest.cutoffSeq() < accepted) {
            throw new ConflictException(ErrorCode.STALE_SNAPSHOT);
        }

        UUID runId = UUID.randomUUID();
        Path path = dir.resolve(runId + ".csv");
        try {
            runs.insertUploading(runId, tenantId, manifest.sourceId(), manifest.cutoffSeq(), manifest.exportedAt(),
                    path.toString(), manifest.expectedRows(), expectedSha);
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.RUN_IN_PROGRESS);
        }

        byte[] sha;
        try {
            sha = store(file, path);
        } catch (UncheckedIOException e) {
            runs.finish(runId, "FAILED", "UPLOAD_FAILED");
            throw e;
        }
        runs.setFileSha(runId, sha);

        Lineage lineage = Lineage.of(runs.latestCompletedWithCutoff(tenantId, manifest.sourceId(), manifest.cutoffSeq(), runId)
                .map(RunRow::fileSha256).orElse(null), sha);
        switch (lineage) {
            case DUPLICATE -> {
                runs.completeAsDuplicate(runId);
                metrics.reconciliationRun("COMPLETED");
                deleteQuietly(path);
                log.info("event=recon_duplicate_snapshot runId={} cutoff={}", runId, manifest.cutoffSeq());
                return new Accepted(runId, "COMPLETED");
            }
            case CONFLICT -> {
                runs.finish(runId, "REJECTED", "SNAPSHOT_CONFLICT");
                metrics.reconciliationRun("REJECTED");
                deleteQuietly(path);
                throw new ConflictException(ErrorCode.SNAPSHOT_CONFLICT);
            }
            case ALLOW -> submit(runId);
        }
        log.info("event=recon_uploaded runId={} cutoff={}", runId, manifest.cutoffSeq());
        return new Accepted(runId, "UPLOADING");
    }

    public RunRow get(String tenantId, UUID runId) {
        return runs.find(tenantId, runId).orElseThrow(NotFoundException::new);
    }

    public record ErrorPage(int page, int size, long total, List<ErrorRow> items) {
    }

    public ErrorPage errors(String tenantId, UUID runId, int page, int size) {
        get(tenantId, runId);
        int boundedSize = Math.max(1, Math.min(size, MAX_ERRORS_PAGE));
        int boundedPage = Math.max(0, page);
        return tenantTx.inTenant(tenantId, s -> new ErrorPage(boundedPage, boundedSize, runs.errorCount(tenantId, runId),
                runs.errors(tenantId, runId, boundedPage, boundedSize)));
    }

    public List<RunRow> pendingApproval(String tenantId) {
        return runs.pendingApproval(tenantId);
    }

    /** Approval is bound to the assessment the admin saw; §9.3 re-assesses before applying anything. */
    public Accepted approve(String tenantId, UUID runId, String assessmentHashHex, String reason, String approver) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationFailedException(List.of(new FieldError("reason", "REASON_REQUIRED")));
        }
        RunRow run = get(tenantId, runId);
        if (!"AWAITING_APPROVAL".equals(run.status())) {
            throw new ConflictException(ErrorCode.INVALID_RUN_STATE);
        }
        if (run.flags().contains("ROW_COUNT_MISMATCH")) {
            throw new ConflictException(ErrorCode.RUN_NOT_APPROVABLE);     // a truncated file cannot be approved (DEC-09)
        }
        if (assessmentHashHex == null || run.assessmentHash() == null
                || !assessmentHashHex.equalsIgnoreCase(HexFormat.of().formatHex(run.assessmentHash()))) {
            throw new ConflictException(ErrorCode.APPROVAL_STALE);
        }
        try {
            if (!runs.approve(runId, approver, reason)) {
                throw new ConflictException(ErrorCode.INVALID_RUN_STATE);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.RUN_IN_PROGRESS);
        }
        log.info("event=recon_approved runId={} by={}", runId, approver);
        submit(runId);
        return new Accepted(runId, "STAGED");
    }

    public Accepted reject(String tenantId, UUID runId, String by) {
        get(tenantId, runId);
        if (!runs.reject(runId, by)) {
            throw new ConflictException(ErrorCode.INVALID_RUN_STATE);
        }
        bulkTx.inTenant(tenantId, s -> staging.delete(runId));
        metrics.reconciliationRun("REJECTED");
        log.info("event=recon_rejected runId={} by={}", runId, by);
        return new Accepted(runId, "REJECTED");
    }

    /** A FAILED run whose file is still on disk is staged again from scratch. */
    public Accepted retry(String tenantId, UUID runId) {
        RunRow run = get(tenantId, runId);
        if (!"FAILED".equals(run.status()) || !Files.exists(Path.of(run.filePath()))) {
            throw new ConflictException(ErrorCode.INVALID_RUN_STATE);
        }
        try {
            if (!runs.transition(runId, "FAILED", "UPLOADING")) {
                throw new ConflictException(ErrorCode.INVALID_RUN_STATE);
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException(ErrorCode.RUN_IN_PROGRESS);
        }
        submit(runId);
        return new Accepted(runId, "UPLOADING");
    }

    public void submit(UUID runId) {
        executor.execute(() -> processRun(runId));
    }

    /** UPLOADING → stage → STAGED → assess/apply. STAGED (approved or recovered) goes straight to §9.3. */
    public void processRun(UUID runId) {
        try (Mdc.Scope scope = Mdc.run(runId)) {
            processInScope(runId);
        }
    }

    private void processInScope(UUID runId) {
        try {
            RunRow run = runs.find(runId).orElseThrow();
            if ("UPLOADING".equals(run.status())) {
                if (stager.stage(run) != SnapshotStager.Result.STAGED) {
                    return;
                }
            } else if (!"STAGED".equals(run.status())) {
                return;
            }
            applier.assessAndApply(runId);
        } catch (RuntimeException e) {
            log.error("event=recon_failed runId={} exception={}", runId, e.getClass().getName(), e);
            runs.finish(runId, "FAILED", "PROCESSING_ERROR");
            metrics.reconciliationRun("FAILED");
        }
    }

    private static byte[] validate(Manifest m) {
        List<FieldError> errors = new ArrayList<>();
        if (m == null) {
            throw new ValidationFailedException(List.of(new FieldError("manifest", "MANIFEST_REQUIRED")));
        }
        if (m.sourceId() == null || m.sourceId().isBlank()) {
            errors.add(new FieldError("sourceId", "SOURCE_REQUIRED"));
        }
        if (m.cutoffSeq() == null || m.cutoffSeq() < 1) {
            errors.add(new FieldError("cutoffSeq", "SEQ_NOT_POSITIVE"));
        }
        if (m.exportedAt() == null) {
            errors.add(new FieldError("exportedAt", "EXPORTED_AT_REQUIRED"));
        }
        if (m.expectedRows() != null && m.expectedRows() < 0) {
            errors.add(new FieldError("expectedRows", "EXPECTED_ROWS_NEGATIVE"));
        }
        byte[] sha = null;
        if (m.sha256() != null) {
            try {
                sha = HexFormat.of().parseHex(m.sha256());
            } catch (IllegalArgumentException e) {
                sha = null;
            }
            if (sha == null || sha.length != 32) {
                errors.add(new FieldError("sha256", "SHA256_FORMAT"));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationFailedException(errors);
        }
        return sha;
    }

    /** Streams the upload to disk through a SHA-256 digest: the file is never held in memory. */
    private byte[] store(InputStream file, Path path) {
        MessageDigest digest = Sha256.digest();
        try {
            Files.createDirectories(dir);
            try (InputStream in = new DigestInputStream(file, digest); OutputStream out = Files.newOutputStream(path)) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return digest.digest();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("event=recon_file_delete_failed exception={}", e.getClass().getSimpleName());
        }
    }
}
