package com.moveinsync.rostersync.roster.service;

import java.util.List;
import java.util.UUID;

import com.moveinsync.rostersync.roster.core.FieldError;

/** Result of one event: identifiers, outcome and codes. Never HR values. */
public record IngestResult(String eventId, String employeeId, IngestOutcome outcome, Long aggregateRevision,
                           UUID rejectionId, List<FieldError> errors) {

    static IngestResult of(String eventId, String employeeId, IngestOutcome outcome, Long revision) {
        return new IngestResult(eventId, employeeId, outcome, revision, null, List.of());
    }

    static IngestResult conflict(String eventId, String employeeId, String field, String code) {
        return new IngestResult(eventId, employeeId, IngestOutcome.CONFLICT, null, null,
                List.of(new FieldError(field, code)));
    }
}
