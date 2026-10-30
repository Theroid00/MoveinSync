package com.moveinsync.rostersync.roster.api;

import java.time.Instant;

import com.moveinsync.rostersync.roster.repository.EmployeeRow;

/** Employee as returned to authorised readers (ADMIN, OPERATOR, or the employee themself). */
public record EmployeeView(String employeeId, String name, String email, String phone, String managerId,
                           String officeCode, String shiftCode, boolean active, long sourceSeq, long aggregateRevision,
                           String pickupPoint, String specialAssistance, String tombstoneReason, Instant updatedAt) {

    static EmployeeView from(EmployeeRow r) {
        return new EmployeeView(r.employeeId(), r.hr().name(), r.hr().email(), r.hr().phone(), r.hr().managerId(),
                r.hr().officeCode(), r.hr().shiftCode(), r.active(), r.sourceSeq(), r.aggregateRevision(),
                r.pickupPoint(), r.specialAssistance(), r.tombstoneReason(), r.updatedAt());
    }
}
