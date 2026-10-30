package com.moveinsync.rostersync.roster.service;

import java.util.List;

import com.moveinsync.rostersync.roster.config.ReconProperties;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.repository.RunRow;
import com.moveinsync.rostersync.roster.repository.StagingRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (spec §9.4): runs interrupted while uploading or staging fail (their staging rows are
 * discarded); STAGED runs resume at §9.3. APPLYING never survives a crash: it is set inside the apply
 * transaction, which rolls back.
 */
@Component
public class ReconciliationRecovery {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRecovery.class);

    private final ReconciliationRunRepository runs;
    private final StagingRepository staging;
    private final TenantTx bulkTx;
    private final ReconciliationService reconciliation;
    private final ReconProperties properties;

    public ReconciliationRecovery(ReconciliationRunRepository runs, StagingRepository staging,
                                  @Qualifier("bulk") TenantTx bulkTx, ReconciliationService reconciliation,
                                  ReconProperties properties) {
        this.runs = runs;
        this.staging = staging;
        this.bulkTx = bulkTx;
        this.reconciliation = reconciliation;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (properties.recover()) {
            recover();
        }
    }

    public void recover() {
        for (RunRow run : runs.inStatus(List.of("UPLOADING", "STAGING"))) {
            bulkTx.inTenant(run.tenantId(), s -> staging.delete(run.runId()));
            runs.finish(run.runId(), "FAILED", "INTERRUPTED");
            log.warn("event=recon_interrupted runId={}", run.runId());
        }
        for (RunRow run : runs.inStatus(List.of("STAGED"))) {
            log.info("event=recon_resumed runId={}", run.runId());
            reconciliation.submit(run.runId());
        }
    }
}
