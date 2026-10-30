package com.moveinsync.rostersync.roster.api;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.service.IngestOutcome;
import com.moveinsync.rostersync.roster.service.IngestResult;

/** 200 body for a single event: identifiers, outcome and revision. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record IngestResponse(String eventId, String employeeId, IngestOutcome outcome, Long aggregateRevision,
                             UUID rejectionId, List<FieldError> errors) {

    static IngestResponse from(IngestResult r) {
        return new IngestResponse(r.eventId(), r.employeeId(), r.outcome(), r.aggregateRevision(), r.rejectionId(),
                r.errors());
    }
}
