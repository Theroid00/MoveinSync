package com.moveinsync.rostersync.roster.outbox;

import com.moveinsync.rostersync.roster.core.RetryPolicy.Verdict;

/** Outcome of one send: verdict plus a bounded error code (HTTP_5xx, IO_ERROR, TIMEOUT). */
public record PublishResult(Verdict verdict, String errorCode) {
}
