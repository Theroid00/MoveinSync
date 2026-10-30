package com.moveinsync.rostersync.roster.core;

/**
 * Stops a snapshot that is missing too many active employees (spec §7.5, DEC-05). Exact integer
 * comparison in basis points; an empty baseline never authorises deactivation.
 */
public final class SafetyBrake {

    public static final int DEFAULT_THRESHOLD_BP = 3000;

    private SafetyBrake() {
    }

    public static boolean tripped(int baseline, int missing, int thresholdBp) {
        return baseline > 0 && (long) missing * 10_000 >= (long) thresholdBp * baseline;
    }

    /** missing / baseline in basis points (rounded down); 0 when the baseline is empty. */
    public static int ratioBp(int baseline, int missing) {
        return baseline == 0 ? 0 : (int) ((long) missing * 10_000 / baseline);
    }
}
