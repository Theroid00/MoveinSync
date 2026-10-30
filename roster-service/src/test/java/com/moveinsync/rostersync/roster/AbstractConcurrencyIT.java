package com.moveinsync.rostersync.roster;

import java.time.Instant;

import com.moveinsync.rostersync.roster.core.HrEventKind;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.service.EmployeeService;
import com.moveinsync.rostersync.roster.service.HrEventService;
import com.moveinsync.rostersync.roster.service.IngestOutcome;
import org.springframework.beans.factory.annotation.Autowired;

/** Concurrency tests call the services directly from many threads (the HTTP layer adds nothing to the race). */
public abstract class AbstractConcurrencyIT extends AbstractReconIT {

    static final Instant AT = Instant.parse("2026-10-30T08:00:00Z");

    @Autowired
    protected HrEventService events;

    @Autowired
    protected EmployeeService employees;

    protected static HrEventRequest upsert(String eventId, long seq, String employeeId, String office, String shift) {
        return new HrEventRequest(eventId, seq, HrEventKind.UPSERT, employeeId, AT,
                new HrRecord("Synthetic Employee", "synthetic@example.test", "+919000000042", null, office, shift));
    }

    protected static HrEventRequest deactivate(String eventId, long seq, String employeeId) {
        return new HrEventRequest(eventId, seq, HrEventKind.DEACTIVATE, employeeId, AT, null);
    }

    protected IngestOutcome ingest(HrEventRequest request) {
        return events.ingest(tenant, request).outcome();
    }

    protected long outboxRowsFor(String employeeId) {
        return outboxRows(employeeId);
    }
}
