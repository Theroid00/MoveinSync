package com.moveinsync.rostersync.roster.service;

import com.moveinsync.rostersync.contract.ErrorCode;

/** Missing resource, or another tenant's resource (never distinguishable from the outside). */
public class NotFoundException extends DomainException {

    public NotFoundException() {
        super(ErrorCode.NOT_FOUND);
    }
}
