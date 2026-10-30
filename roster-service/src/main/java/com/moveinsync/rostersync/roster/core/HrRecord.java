package com.moveinsync.rostersync.roster.core;

/** HR-owned fields of an employee. Transport-owned fields are deliberately absent from this type. */
public record HrRecord(String name, String email, String phone, String managerId,
                       String officeCode, String shiftCode) {
}
