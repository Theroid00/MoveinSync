package com.moveinsync.rostersync.roster.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Buckets whose digests differ, or that exist on only one side (spec §7.6). */
public final class BucketDiff {

    private BucketDiff() {
    }

    public static Set<Integer> mismatched(Map<Integer, byte[]> staged, Map<Integer, byte[]> store) {
        Set<Integer> all = new HashSet<>(staged.keySet());
        all.addAll(store.keySet());
        Set<Integer> result = new TreeSet<>();
        for (Integer bucket : all) {
            if (!Arrays.equals(staged.get(bucket), store.get(bucket))) {
                result.add(bucket);
            }
        }
        return result;
    }
}
