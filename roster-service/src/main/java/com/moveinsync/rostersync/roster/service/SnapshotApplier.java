package com.moveinsync.rostersync.roster.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moveinsync.rostersync.roster.cache.TenantSettings;
import com.moveinsync.rostersync.roster.core.BucketDiff;
import com.moveinsync.rostersync.roster.core.SafetyBrake;
import com.moveinsync.rostersync.roster.core.Sha256;
import com.moveinsync.rostersync.roster.fault.FaultInjector;
import com.moveinsync.rostersync.roster.observability.RosterMetrics;
import com.moveinsync.rostersync.roster.repository.ReconciliationApplyRepository;
import com.moveinsync.rostersync.roster.repository.ReconciliationApplyRepository.Assessment;
import com.moveinsync.rostersync.roster.repository.ReconciliationApplyRepository.TenantSourceState;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.repository.RunRow;
import com.moveinsync.rostersync.roster.repository.StagingRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assess + apply (spec §9.3) as ONE transaction on the bulk pool, under the tenant source's exclusive
 * advisory lock: re-check freshness and lineage, diff bucket digests, plan, assess absences, stop at the
 * safety brake with zero live changes, otherwise apply creates/updates/advances/reactivations (and, only
 * on clean evidence, absences) together with their history and outbox rows. A crash anywhere rolls the
 * whole run back (DEC-10).
 */
@Component
public class SnapshotApplier {

    private static final Logger log = LoggerFactory.getLogger(SnapshotApplier.class);

    public enum Result { COMPLETED, PARTIAL_NO_DEACTIVATIONS, AWAITING_APPROVAL, REJECTED, DUPLICATE }

    private final TenantTx bulkTx;
    private final ReconciliationApplyRepository apply;
    private final ReconciliationRunRepository runs;
    private final StagingRepository staging;
    private final TenantSettings tenantSettings;
    private final RosterMetrics metrics;
    private final FaultInjector faults;
    private final JsonMapper json;

    public SnapshotApplier(@Qualifier("bulk") TenantTx bulkTx, ReconciliationApplyRepository apply,
                           ReconciliationRunRepository runs, StagingRepository staging,
                           TenantSettings tenantSettings, RosterMetrics metrics, FaultInjector faults, JsonMapper json) {
        this.bulkTx = bulkTx;
        this.apply = apply;
        this.runs = runs;
        this.staging = staging;
        this.tenantSettings = tenantSettings;
        this.metrics = metrics;
        this.faults = faults;
        this.json = json;
    }

    public Result assessAndApply(UUID runId) {
        RunRow run = runs.find(runId).orElseThrow();
        Long lockKey = tenantSettings.lockKey(run.tenantId(), run.sourceId());
        if (lockKey == null) {
            throw new NotFoundException();
        }
        int thresholdBp = tenantSettings.brakeThresholdBp(run.tenantId());

        Result result = bulkTx.inTenant(run.tenantId(), status -> {
            apply.statementTimeout300s();
            apply.lockExclusive(lockKey);
            long locked = System.nanoTime();
            try {
                return assessAndApplyLocked(run, thresholdBp);
            } finally {
                metrics.lockHeld(System.nanoTime() - locked);
            }
        });
        metrics.reconciliationRun(result == Result.DUPLICATE ? "COMPLETED" : result.name());
        if (result != Result.AWAITING_APPROVAL) {
            bulkTx.inTenant(run.tenantId(), status -> staging.delete(runId));
        }
        return result;
    }

    private Result assessAndApplyLocked(RunRow run, int thresholdBp) {
        UUID runId = run.runId();
        String tenant = run.tenantId();
        String source = run.sourceId();
        long cutoff = run.cutoffSeq();

        TenantSourceState state = apply.tenantSource(tenant, source);
        if (cutoff < state.acceptedCutoff()) {
            apply.setStatus(runId, "REJECTED", "STALE_SNAPSHOT");      // e.g. an old run approved after a newer one completed
            return Result.REJECTED;
        }
        switch (Lineage.of(apply.latestCompletedWithCutoff(tenant, source, cutoff, runId)
                .map(ReconciliationApplyRepository.FinishedRun::fileSha256).orElse(null), run.fileSha256())) {
            case DUPLICATE -> {
                apply.completeAsDuplicate(runId);
                return Result.DUPLICATE;
            }
            case CONFLICT -> {
                apply.setStatus(runId, "REJECTED", "SNAPSHOT_CONFLICT");
                return Result.REJECTED;
            }
            case ALLOW -> apply.setStatus(runId, "APPLYING", null);
        }

        long t0 = System.nanoTime();
        Map<Integer, byte[]> stagedDigests = apply.stagedDigests(runId);
        Map<Integer, byte[]> storeDigests = apply.storeDigests(tenant, source);
        Set<Integer> mismatchedSet = BucketDiff.mismatched(stagedDigests, storeDigests);
        Integer[] mismatched = mismatchedSet.toArray(Integer[]::new);
        Set<Integer> allBuckets = new HashSet<>(stagedDigests.keySet());
        allBuckets.addAll(storeDigests.keySet());
        phase(runId, "digest", t0);

        long t1 = System.nanoTime();
        apply.createPlan(tenant, source, runId, mismatched);
        Map<String, Long> plan = apply.planCounts();
        List<String> flags = new ArrayList<>();
        if (run.flags().contains("ROW_COUNT_MISMATCH")) {
            flags.add("ROW_COUNT_MISMATCH");
        }
        if (run.invalidRows() > 0) {
            flags.add("INVALID_ROWS_PRESENT");
        }
        if (run.unidentifiableRows() > 0) {
            flags.add("UNIDENTIFIABLE_ROWS");
        }
        if (plan.getOrDefault("CONFLICT", 0L) > 0) {
            flags.add("CONFLICTS_PRESENT");
        }
        boolean inferAbsences = flags.isEmpty();            // conflicting evidence never deactivates anyone (DEC-08)

        Assessment assessment = apply.assess(tenant, source, runId, cutoff, mismatched);
        byte[] assessmentHash = assessmentHash(cutoff, assessment);
        int ratioBp = SafetyBrake.ratioBp(assessment.baseline(), assessment.missing());
        boolean brake = SafetyBrake.tripped(assessment.baseline(), assessment.missing(), thresholdBp);
        boolean approved = run.approvedBy() != null && java.util.Arrays.equals(run.assessmentHash(), assessmentHash);
        if (brake) {
            flags.add("MISSING_RATIO_EXCEEDED");
        }
        if (brake && !approved) {
            apply.awaitApproval(runId, flags, assessment, ratioBp, assessmentHash, mismatched.length);
            apply.insertBrakeAlert(tenant, runId, json.writeValueAsString(Map.of("runId", runId.toString(),
                    "missing", assessment.missing(), "baseline", assessment.baseline(), "ratioBp", ratioBp)));
            phase(runId, "assess", t1);
            metrics.brakeTrip();
            log.warn("event=safety_brake runId={} missing={} baseline={} ratioBp={}", runId, assessment.missing(),
                    assessment.baseline(), ratioBp);
            return Result.AWAITING_APPROVAL;                  // committed with zero employee changes
        }
        phase(runId, "assess", t1);

        long t2 = System.nanoTime();
        String exportedAt = run.exportedAt().toString();
        Map<String, Integer> created = apply.applyCreates(tenant, source, runId, exportedAt);
        apply.deleteCreatedTombstones(tenant, source);
        Map<String, Integer> updated = apply.applyUpdates(tenant, source, runId, exportedAt);
        int conflicts = apply.recordConflicts(tenant, runId);
        int deactivated = inferAbsences ? apply.applyDeactivations(tenant, source, runId, cutoff, mismatched, exportedAt) : 0;
        faults.hit("DURING_RECON_APPLY");             // mid-transaction: a halt here must leave no partial run
        apply.advanceTenantSource(tenant, source, cutoff, inferAbsences);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("created", created.getOrDefault("EmployeeCreated", 0));
        counts.put("updated", updated.getOrDefault("EmployeeUpdated", 0));
        counts.put("advanced", updated.getOrDefault("EmployeeVersionAdvanced", 0));
        counts.put("reactivated", updated.getOrDefault("EmployeeReactivated", 0));
        counts.put("deactivated", deactivated);
        counts.put("stale", plan.getOrDefault("STALE", 0L));
        counts.put("duplicate", plan.getOrDefault("DUPLICATE", 0L));
        counts.put("conflict", conflicts);
        counts.put("unchangedBuckets", allBuckets.size() - mismatched.length);
        Result result = inferAbsences ? Result.COMPLETED : Result.PARTIAL_NO_DEACTIVATIONS;
        apply.finishRun(runId, result.name(), flags, assessment, ratioBp, assessmentHash, mismatched.length,
                json.writeValueAsString(counts));
        phase(runId, "apply", t2);
        log.info("event=recon_applied runId={} status={} bucketsMismatched={} counts={}", runId, result, mismatched.length, counts);
        return result;
    }

    /**
     * SHA-256 over "S|baseline|missing|hex(sha256(missing ids joined by ',' in id order))", the last
     * part computed in SQL. Binds an approval to exactly what the operator saw.
     */
    static byte[] assessmentHash(long cutoff, Assessment a) {
        return Sha256.of(cutoff + "|" + a.baseline() + "|" + a.missing() + "|" + a.missingIdsHashHex());
    }

    private void phase(UUID runId, String phase, long startedNanos) {
        long millis = millisSince(startedNanos);
        apply.phase(runId, phase, millis);
        metrics.reconciliationPhase(phase, millis);
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
