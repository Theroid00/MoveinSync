package com.moveinsync.rostersync.contract;

/**
 * Error catalog shared by both services (spec §13.1). Titles are fixed strings: request values
 * are never interpolated into an error response.
 */
public enum ErrorCode {
    VALIDATION_FAILED(422, "Request validation failed"),
    UNKNOWN_FIELD(422, "Request contains a field that is not accepted"),
    MALFORMED_JSON(400, "Request body is not valid JSON"),
    NOT_FOUND(404, "Resource not found"),
    VERSION_CONFLICT(409, "Same source version with different content"),
    EVENT_ID_REUSED(409, "Event id was already used with a different payload"),
    REVISION_MISMATCH(409, "Resource revision does not match If-Match"),
    ALREADY_INACTIVE(409, "Employee is already inactive"),
    RUN_IN_PROGRESS(409, "Another reconciliation run is active for this source"),
    STALE_SNAPSHOT(409, "Snapshot cutoff is older than the accepted snapshot"),
    SNAPSHOT_CONFLICT(409, "A different snapshot was already completed for this cutoff"),
    RUN_NOT_APPROVABLE(409, "This run cannot be approved"),
    APPROVAL_STALE(409, "Assessment changed since it was reviewed"),
    INVALID_RUN_STATE(409, "Operation not allowed in the run's current state"),
    EMPLOYEE_INACTIVE(409, "Employee is not active"),
    TRIP_FULL(409, "Trip has no free seats"),
    UNAUTHENTICATED(401, "Authentication required"),
    FORBIDDEN(403, "Access denied"),
    PAYLOAD_TOO_LARGE(413, "Payload too large"),
    RECONCILIATION_IN_PROGRESS(503, "Reconciliation in progress; retry later"),
    DB_UNAVAILABLE(503, "Database temporarily unavailable; retry later"),
    INTERNAL_ERROR(500, "Internal error");

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }
}
