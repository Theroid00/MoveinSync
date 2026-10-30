package com.moveinsync.rostersync.roster.service;

import com.moveinsync.rostersync.contract.ErrorCode;

public class PayloadTooLargeException extends DomainException {

    public PayloadTooLargeException() {
        super(ErrorCode.PAYLOAD_TOO_LARGE);
    }
}
