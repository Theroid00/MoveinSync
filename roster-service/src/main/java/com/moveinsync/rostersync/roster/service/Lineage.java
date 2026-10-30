package com.moveinsync.rostersync.roster.service;

import java.util.Arrays;

/**
 * Same-cutoff lineage (spec §9.1, DEC-23): once a cutoff has a COMPLETED run, the identical file is a
 * no-op replay and a different file is a second "complete truth" for the same cutoff, i.e. a conflict.
 * Without a completed run (none yet, or only partial/failed/rejected ones) a corrected re-send is allowed.
 */
enum Lineage {
    ALLOW, DUPLICATE, CONFLICT;

    static Lineage of(byte[] completedFileSha, byte[] fileSha) {
        if (completedFileSha == null) {
            return ALLOW;
        }
        return Arrays.equals(completedFileSha, fileSha) ? DUPLICATE : CONFLICT;
    }
}
