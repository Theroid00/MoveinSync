package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class BucketDiffTest {

    @Test
    void identicalMapsHaveNoMismatch() {
        assertThat(BucketDiff.mismatched(Map.of(1, new byte[] {9}), Map.of(1, new byte[] {9}))).isEmpty();
        assertThat(BucketDiff.mismatched(Map.of(), Map.of())).isEmpty();
    }

    @Test
    void differentDigestIsMismatched() {
        assertThat(BucketDiff.mismatched(Map.of(1, new byte[] {9}, 2, new byte[] {7}),
                Map.of(1, new byte[] {8}, 2, new byte[] {7}))).containsExactly(1);
    }

    @Test
    void bucketOnOneSideOnlyIsMismatched() {
        assertThat(BucketDiff.mismatched(Map.of(3, new byte[] {1}), Map.of())).containsExactly(3);   // new joiner
        assertThat(BucketDiff.mismatched(Map.of(), Map.of(4, new byte[] {1}))).containsExactly(4);   // absent employee
    }

    @Test
    void comparesContentNotIdentity() {
        assertThat(BucketDiff.mismatched(Map.of(5, new byte[] {1, 2}), Map.of(5, new byte[] {1, 2}))).isEmpty();
    }
}
