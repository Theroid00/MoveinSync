package com.moveinsync.rostersync.roster.core;

/**
 * Delivery verdicts and backoff for the outbox relay (spec §10). 2xx delivers; 4xx is permanent
 * except 408/429; 5xx, 408, 429 and transport errors retry with jittered exponential backoff,
 * capped at 300 s, until the attempt budget is spent.
 */
public final class RetryPolicy {

    public enum Verdict { DELIVERED, RETRY, DEAD }

    public static final int MAX_BACKOFF_SECONDS = 300;

    private RetryPolicy() {
    }

    public static Verdict forStatus(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return Verdict.DELIVERED;
        }
        if (httpStatus >= 400 && httpStatus < 500 && httpStatus != 408 && httpStatus != 429) {
            return Verdict.DEAD;
        }
        return Verdict.RETRY;
    }

    /** min(2^attempts, 300) seconds × (0.8 + 0.4 × random), random in [0, 1). */
    public static double backoffSeconds(int attempts, double random01) {
        long base = Math.min(1L << Math.min(Math.max(attempts, 0), 30), MAX_BACKOFF_SECONDS);
        return base * (0.8 + 0.4 * random01);
    }

    /** attempts counts every send, including the one that just failed. */
    public static boolean exhausted(int attempts, int maxAttempts) {
        return attempts >= maxAttempts;
    }
}
