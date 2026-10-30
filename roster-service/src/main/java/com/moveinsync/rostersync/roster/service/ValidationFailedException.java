package com.moveinsync.rostersync.roster.service;

import java.util.List;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.core.FieldError;

/** Request-level validation failure (422) carrying field names and codes. */
public class ValidationFailedException extends DomainException {

    public ValidationFailedException(List<FieldError> errors) {
        super(ErrorCode.VALIDATION_FAILED, errors);
    }
}
