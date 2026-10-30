package com.moveinsync.rostersync.roster.service;

import java.util.List;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.core.FieldError;

/** A 409: the request is well-formed but conflicts with current state. */
public class ConflictException extends DomainException {

    public ConflictException(ErrorCode code) {
        super(code);
    }

    public ConflictException(ErrorCode code, List<FieldError> errors) {
        super(code, errors);
    }
}
