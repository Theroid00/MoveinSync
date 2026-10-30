package com.moveinsync.rostersync.booking.service;

import java.util.List;
import java.util.Map;

import com.moveinsync.rostersync.contract.ErrorCode;

/** Expected, client-visible failure: fixed title per code, no interpolated values, no stack trace. */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final List<Map<String, String>> errors;

    public DomainException(ErrorCode code) {
        this(code, List.of());
    }

    public DomainException(ErrorCode code, List<Map<String, String>> errors) {
        super(code.title(), null, false, false);
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public static DomainException field(ErrorCode code, String field, String fieldCode) {
        return new DomainException(code, List.of(Map.of("field", field, "code", fieldCode)));
    }

    public ErrorCode code() {
        return code;
    }

    public List<Map<String, String>> errors() {
        return errors;
    }
}
