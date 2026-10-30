package com.moveinsync.rostersync.roster.core;

import java.util.Optional;

/** One boundary rule. Rules report a field name and a code, never the offending value. */
public abstract class ValidationRule {

    public abstract Optional<FieldError> check(HrEventRequest request, ValidationContext context);

    protected static Optional<FieldError> fail(String field, String code) {
        return Optional.of(new FieldError(field, code));
    }

    protected static Optional<FieldError> pass() {
        return Optional.empty();
    }

    protected static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
