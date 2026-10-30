package com.moveinsync.rostersync.roster.core;

import java.util.Optional;

/** Template for rules on the HR block: they only apply when the block is present. */
public abstract class HrFieldRule extends ValidationRule {

    @Override
    public final Optional<FieldError> check(HrEventRequest request, ValidationContext context) {
        return request.hr() == null ? pass() : checkHr(request.hr(), request, context);
    }

    protected abstract Optional<FieldError> checkHr(HrRecord hr, HrEventRequest request, ValidationContext context);
}
