package com.moveinsync.rostersync.roster.service;

import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.roster.core.CanonicalHasher;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.Rules;
import com.moveinsync.rostersync.roster.repository.IngestEventRepository;
import com.moveinsync.rostersync.roster.repository.RecordErrorRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.springframework.stereotype.Component;

/**
 * Records a rejected record in its own transaction (spec §8 step 0): record_error rows under a new
 * rejection id, plus an ingest_event row when the event id is well-formed. Malformed ids and employee
 * ids are never stored.
 */
@Component
class RejectionRecorder {

    private final TenantTx tenantTx;
    private final IngestEventRepository ingestEvents;
    private final RecordErrorRepository recordErrors;

    RejectionRecorder(TenantTx tenantTx, IngestEventRepository ingestEvents, RecordErrorRepository recordErrors) {
        this.tenantTx = tenantTx;
        this.ingestEvents = ingestEvents;
        this.recordErrors = recordErrors;
    }

    IngestResult reject(String tenantId, String sourceId, HrEventRequest request, List<FieldError> errors) {
        UUID rejectionId = UUID.randomUUID();
        boolean eventIdOk = request != null && request.eventId() != null
                && errors.stream().noneMatch(e -> "EVENT_ID_REQUIRED".equals(e.code()));
        String eventId = eventIdOk ? request.eventId() : null;
        String employeeId = request != null && Rules.isValidEmployeeId(request.employeeId()) ? request.employeeId() : null;
        Long seq = request != null && request.sourceSeq() != null && request.sourceSeq() > 0 ? request.sourceSeq() : null;
        List<String> codes = errors.stream().map(FieldError::code).toList();

        tenantTx.inTenant(tenantId, status -> {
            if (eventId != null) {
                ingestEvents.insertIfAbsent(tenantId, sourceId, eventId, CanonicalHasher.payloadHash(request),
                        employeeId, seq, IngestOutcome.REJECTED.name(), codes);
            }
            for (FieldError error : errors) {
                recordErrors.insertForEvent(tenantId, eventId, rejectionId, employeeId, error.field(), error.code());
            }
            return null;
        });
        return new IngestResult(eventId, employeeId, IngestOutcome.REJECTED, null, rejectionId, errors);
    }
}
