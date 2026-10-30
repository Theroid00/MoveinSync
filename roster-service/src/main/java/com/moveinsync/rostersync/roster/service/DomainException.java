package com.moveinsync.rostersync.roster.service;

import java.util.List;

import com.moveinsync.rostersync.contract.ErrorCode;
import com.moveinsync.rostersync.roster.core.FieldError;

/**
 * Base of every expected, client-visible failure. The message is the code's fixed title: request
 * values are never interpolated, so nothing personal can leak through an exception.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldError> errors;

    protected DomainException(ErrorCode code) {
        this(code, List.of());
    }

    protected DomainException(ErrorCode code, List<FieldError> errors) {
        super(code.title(), null, false, false);
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
