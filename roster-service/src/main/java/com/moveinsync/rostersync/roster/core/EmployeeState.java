package com.moveinsync.rostersync.roster.core;

/** The stored row as the version gate sees it. */
public record EmployeeState(boolean active, long sourceSeq, byte[] hrHash) {
}
