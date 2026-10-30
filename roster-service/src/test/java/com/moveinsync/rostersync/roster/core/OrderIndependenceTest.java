package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Any delivery order (shuffled, ~20% duplicated, deactivations racing ahead of creations) ends in the
 * same observable state as applying the feed in version order (spec §7.4 projection, DEC-20).
 */
class OrderIndependenceTest {

    @Test
    void shuffledDuplicatedDeliveryEqualsSeqOrder() {
        int deactivateFirstCases = 0;
        for (long seed = 1; seed <= 1000; seed++) {
            FeedGenerator.Feed feed = FeedGenerator.generate(seed);
            deactivateFirstCases += feed.deactivateFirstCases();

            Map<String, InMemoryApplier.ObservableState> sorted = applyAll(feed.inSeqOrder());
            Map<String, InMemoryApplier.ObservableState> delivered = applyAll(feed.delivered());

            assertThat(delivered).as("seed %d", seed).isEqualTo(sorted);
        }
        assertThat(deactivateFirstCases).as("generator covered deactivate-before-create").isGreaterThan(500);
    }

    @Test
    void deactivateV2ThenUpsertV1EndsInactiveAt2() {
        InMemoryApplier applier = new InMemoryApplier(0);
        assertThat(applier.apply(Fixtures.deactivate("d2", 2, "EMP-1"))).isEqualTo(InMemoryApplier.Result.TOMBSTONED);
        assertThat(applier.apply(Fixtures.upsert("u1", 1, "EMP-1", Fixtures.hr()))).isEqualTo(InMemoryApplier.Result.STALE);

        InMemoryApplier sorted = new InMemoryApplier(0);
        sorted.apply(Fixtures.upsert("u1", 1, "EMP-1", Fixtures.hr()));
        sorted.apply(Fixtures.deactivate("d2", 2, "EMP-1"));

        assertThat(applier.observable()).isEqualTo(sorted.observable())
                .containsEntry("EMP-1", new InMemoryApplier.ObservableState(false, 2, null));
    }

    static Map<String, InMemoryApplier.ObservableState> applyAll(List<HrEventRequest> events) {
        InMemoryApplier applier = new InMemoryApplier(0);
        events.forEach(applier::apply);
        return applier.observable();
    }
}
