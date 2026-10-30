package com.moveinsync.rostersync.roster.core;

/**
 * Hashed bucket of an employee id: the first two bytes of SHA-256(id), 0..65535 (spec §7.3, DEC-21).
 * With ~10k changed ids, 65,536 buckets leave ~86% untouched; 1,024 buckets would all be touched.
 */
public final class Bucketer {

    public static final int BUCKETS = 65_536;

    private Bucketer() {
    }

    public static int bucket(String employeeId) {
        byte[] b = Sha256.of(employeeId);
        return (b[0] & 0xFF) << 8 | (b[1] & 0xFF);
    }
}
