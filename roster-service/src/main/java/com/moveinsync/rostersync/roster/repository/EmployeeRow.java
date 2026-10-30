package com.moveinsync.rostersync.roster.repository;

import java.time.Instant;

import com.moveinsync.rostersync.roster.core.HrRecord;

/** One employee row as stored (HR state normalised; transport fields owned by the admin). */
public record EmployeeRow(String employeeId, HrRecord hr, boolean active, long sourceSeq, byte[] hrHash,
                          String pickupPoint, String specialAssistance, long aggregateRevision,
                          String tombstoneReason, Instant updatedAt) {
}
