package com.moveinsync.rostersync.roster.core;

import java.time.Instant;

/** Small builders shared by the core unit tests. */
final class Fixtures {

    static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");
    static final ValidationContext CTX = code -> code.equals("BLR-A") || code.equals("BLR-B") || code.equals("GGN-A");

    private Fixtures() {
    }

    static HrRecord hr() {
        return new HrRecord("Synthetic Employee", "emp042@example.test", "+919000000042", "EMP-001", "BLR-A", "EVENING");
    }

    static HrEventRequest upsert(String eventId, long seq, String employeeId, HrRecord hr) {
        return new HrEventRequest(eventId, seq, HrEventKind.UPSERT, employeeId, T0, hr);
    }

    static HrEventRequest deactivate(String eventId, long seq, String employeeId) {
        return new HrEventRequest(eventId, seq, HrEventKind.DEACTIVATE, employeeId, T0, null);
    }
}
