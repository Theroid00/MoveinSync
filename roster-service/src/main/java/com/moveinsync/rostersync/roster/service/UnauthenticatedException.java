package com.moveinsync.rostersync.roster.service;

import com.moveinsync.rostersync.contract.ErrorCode;

public class UnauthenticatedException extends DomainException {

    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED);
    }
}
