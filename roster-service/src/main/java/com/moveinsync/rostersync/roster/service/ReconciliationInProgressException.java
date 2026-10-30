package com.moveinsync.rostersync.roster.service;

import com.moveinsync.rostersync.contract.ErrorCode;

/** The tenant's lock is held by a reconciliation apply longer than lock_timeout: retry later (503). */
public class ReconciliationInProgressException extends DomainException {

    public ReconciliationInProgressException() {
        super(ErrorCode.RECONCILIATION_IN_PROGRESS);
    }
}
