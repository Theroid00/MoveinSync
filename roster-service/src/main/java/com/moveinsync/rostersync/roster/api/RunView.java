package com.moveinsync.rostersync.roster.api;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.roster.repository.RunRow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Operator view of a run: status, counts, phase timings, assessment and flags. IDs and counts only. */
public record RunView(UUID runId, String sourceId, String status, long cutoffSeq, Instant exportedAt, Integer expectedRows,
                      int parsedRows, int validRows, int invalidRows, int unidentifiableRows, List<String> flags,
                      Integer baseline, Integer missing, Integer ratioBp, String assessmentHash, List<String> missingSample,
                      Integer bucketsMismatched, String approvedBy, String approvalReason, Instant approvedAt,
                      String rejectedBy, JsonNode counts, JsonNode phaseMs, String errorCode, Instant createdAt,
                      Instant updatedAt) {

    static RunView from(RunRow r, JsonMapper json) {
        return new RunView(r.runId(), r.sourceId(), r.status(), r.cutoffSeq(), r.exportedAt(), r.expectedRows(),
                r.parsedRows(), r.validRows(), r.invalidRows(), r.unidentifiableRows(), r.flags(), r.baseline(),
                r.missing(), r.ratioBp(), r.assessmentHash() == null ? null : HexFormat.of().formatHex(r.assessmentHash()),
                r.missingSample(), r.bucketsMismatched(), r.approvedBy(), r.approvalReason(), r.approvedAt(),
                r.rejectedBy(), json.readTree(r.counts()), json.readTree(r.phaseMs()), r.errorCode(), r.createdAt(),
                r.updatedAt());
    }
}
