package com.moveinsync.rostersync.roster.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Applying any feed twice equals applying it once: by event identity and by state identity. */
class ReplayIdempotenceTest {

    @Test
    void replayingAFeedChangesNothing() {
        for (long seed = 1; seed <= 1000; seed++) {
            FeedGenerator.Feed feed = FeedGenerator.generate(seed);
            InMemoryApplier applier = new InMemoryApplier(0);
            feed.delivered().forEach(applier::apply);
            Map<String, InMemoryApplier.ObservableState> once = applier.observable();

            // Same event ids: event identity makes every replayed event a DUPLICATE.
            List<InMemoryApplier.Result> sameIds = feed.delivered().stream().map(applier::apply).toList();
            assertThat(sameIds).as("seed %d same ids", seed).containsOnly(InMemoryApplier.Result.DUPLICATE);
            assertThat(applier.observable()).as("seed %d same ids", seed).isEqualTo(once);

            // Fresh event ids: state identity (seq + hash + intent) still makes it a no-op.
            List<InMemoryApplier.Result> freshIds = FeedGenerator.withFreshEventIds(feed.delivered(), "replay-")
                    .stream().map(applier::apply).toList();
            assertThat(freshIds).as("seed %d fresh ids", seed)
                    .doesNotContain(InMemoryApplier.Result.APPLIED, InMemoryApplier.Result.TOMBSTONED,
                            InMemoryApplier.Result.CONFLICT);
            assertThat(applier.observable()).as("seed %d fresh ids", seed).isEqualTo(once);
        }
    }
}
