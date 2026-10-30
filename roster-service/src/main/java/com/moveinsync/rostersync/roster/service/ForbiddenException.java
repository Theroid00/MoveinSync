package com.moveinsync.rostersync.roster.service;

import com.moveinsync.rostersync.contract.ErrorCode;

public class ForbiddenException extends DomainException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }
}
