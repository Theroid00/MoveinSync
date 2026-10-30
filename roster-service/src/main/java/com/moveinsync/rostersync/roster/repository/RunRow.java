package com.moveinsync.rostersync.roster.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One reconciliation run (system table; always read by tenant in SQL). Counts/phases are raw JSON. */
public record RunRow(UUID runId, String tenantId, String sourceId, long cutoffSeq, Instant exportedAt, String filePath,
                     byte[] fileSha256, Integer expectedRows, byte[] expectedSha256, int parsedRows, int validRows,
                     int invalidRows, int unidentifiableRows, String status, List<String> flags, Integer baseline,
                     Integer missing, Integer ratioBp, byte[] assessmentHash, List<String> missingSample,
                     Integer bucketsMismatched, String approvedBy, String approvalReason, Instant approvedAt,
                     String rejectedBy, String counts, String phaseMs, String errorCode, Instant createdAt,
                     Instant updatedAt) {
}
