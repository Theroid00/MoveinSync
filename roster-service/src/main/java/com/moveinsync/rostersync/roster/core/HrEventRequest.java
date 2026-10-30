package com.moveinsync.rostersync.roster.core;

import java.time.Instant;

/**
 * One inbound HR event. {@code hr} is required for UPSERT and must be null for DEACTIVATE.
 * Unknown JSON properties are rejected at the boundary, so transport fields can never arrive here.
 */
public record HrEventRequest(String eventId, Long sourceSeq, HrEventKind eventType, String employeeId,
                             Instant effectiveAt, HrRecord hr) {
}
