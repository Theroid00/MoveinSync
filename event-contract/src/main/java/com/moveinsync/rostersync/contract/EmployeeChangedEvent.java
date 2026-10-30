package com.moveinsync.rostersync.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The outbox payload, serialised as-is. Carries identifiers, the aggregate revision and field
 * <em>names</em> only: no personal data can travel downstream through this type.
 */
public record EmployeeChangedEvent(UUID eventId, EventType eventType, String tenantId, String employeeId,
                                   long aggregateRevision, boolean active, Instant effectiveAt,
                                   List<String> changedFields) {
}
