package com.moveinsync.rostersync.contract;

/** Consumer response body. {@code outcome} is one of PROCESSED, DUPLICATE, SKIPPED_OLDER. */
public record ConsumeResult(String outcome) {

    public static final String PROCESSED = "PROCESSED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String SKIPPED_OLDER = "SKIPPED_OLDER";
}
