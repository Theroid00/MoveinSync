package com.moveinsync.rostersync.roster.core;

import static com.moveinsync.rostersync.contract.EventType.EmployeeCreated;
import static com.moveinsync.rostersync.contract.EventType.EmployeeDeactivated;
import static com.moveinsync.rostersync.contract.EventType.EmployeeReactivated;
import static com.moveinsync.rostersync.contract.EventType.EmployeeUpdated;
import static com.moveinsync.rostersync.contract.EventType.EmployeeVersionAdvanced;
import static com.moveinsync.rostersync.roster.core.Decision.Outcome.APPLY;
import static com.moveinsync.rostersync.roster.core.Decision.Outcome.CONFLICT;
import static com.moveinsync.rostersync.roster.core.Decision.Outcome.DUPLICATE;
import static com.moveinsync.rostersync.roster.core.Decision.Outcome.STALE;
import static com.moveinsync.rostersync.roster.core.Decision.Outcome.TOMBSTONE;
import static com.moveinsync.rostersync.roster.core.HrEventKind.DEACTIVATE;
import static com.moveinsync.rostersync.roster.core.HrEventKind.UPSERT;
import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.rostersync.contract.EventType;
import com.moveinsync.rostersync.roster.core.Decision.Outcome;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** One test per row of the spec §7.4 decision tables. */
class VersionPolicyTest {

    static final byte[] H1 = {1};
    static final byte[] H2 = {2};

    static Decision decide(EmployeeState current, Long tomb, HrEventKind kind, long seq, byte[] hash, long watermark) {
        return VersionPolicy.decide(current, tomb, new Incoming(kind, seq, kind == UPSERT ? hash : null), watermark);
    }

    static void expect(Decision d, Outcome outcome, EventType type) {
        assertThat(d.outcome()).isEqualTo(outcome);
        assertThat(d.eventType()).isEqualTo(type);
    }

    @Nested
    class NoRowExists {

        @Test
        void upsertAtOrBelowFloorIsStale() {
            expect(decide(null, null, UPSERT, 5, H1, 5), STALE, null);           // watermark floor
            expect(decide(null, 7L, UPSERT, 7, H1, 0), STALE, null);             // tombstone floor (equal)
            expect(decide(null, 7L, UPSERT, 6, H1, 3), STALE, null);             // floor = max(3, 7)
        }

        @Test
        void upsertAboveFloorCreates() {
            expect(decide(null, null, UPSERT, 1, H1, 0), APPLY, EmployeeCreated);
            expect(decide(null, 7L, UPSERT, 8, H1, 5), APPLY, EmployeeCreated);
        }

        @Test
        void deactivateAboveTombstoneTombstones() {
            expect(decide(null, null, DEACTIVATE, 2, null, 0), TOMBSTONE, null);
            expect(decide(null, 4L, DEACTIVATE, 5, null, 0), TOMBSTONE, null);
            // The watermark does not block recording a newer tombstone.
            expect(decide(null, null, DEACTIVATE, 2, null, 10), TOMBSTONE, null);
        }

        @Test
        void deactivateAtOrBelowTombstoneIsStaleOrDuplicate() {
            expect(decide(null, 5L, DEACTIVATE, 4, null, 0), STALE, null);
            expect(decide(null, 5L, DEACTIVATE, 5, null, 0), DUPLICATE, null);
        }
    }

    @Nested
    class RowExists {

        final EmployeeState active5 = new EmployeeState(true, 5, H1);
        final EmployeeState inactive5 = new EmployeeState(false, 5, H1);

        @Test
        void lowerSeqIsStale() {
            expect(decide(active5, null, UPSERT, 4, H2, 0), STALE, null);
            expect(decide(active5, null, DEACTIVATE, 4, null, 0), STALE, null);
            expect(decide(inactive5, null, UPSERT, 4, H1, 0), STALE, null);
        }

        @Test
        void equalSeqSameHashSameIntentIsDuplicate() {
            expect(decide(active5, null, UPSERT, 5, H1, 0), DUPLICATE, null);
            expect(decide(inactive5, null, DEACTIVATE, 5, null, 0), DUPLICATE, null);
        }

        @Test
        void equalSeqOtherwiseIsConflict() {
            expect(decide(active5, null, UPSERT, 5, H2, 0), CONFLICT, null);       // different HR state
            expect(decide(active5, null, DEACTIVATE, 5, null, 0), CONFLICT, null); // different intent
            expect(decide(inactive5, null, UPSERT, 5, H1, 0), CONFLICT, null);     // different intent
        }

        @Test
        void activeUpsertHigherSeqDifferentHashUpdates() {
            expect(decide(active5, null, UPSERT, 6, H2, 0), APPLY, EmployeeUpdated);
        }

        @Test
        void activeUpsertHigherSeqSameHashAdvancesVersion() {
            expect(decide(active5, null, UPSERT, 6, H1, 0), APPLY, EmployeeVersionAdvanced);
        }

        @Test
        void inactiveUpsertHigherSeqReactivates() {
            expect(decide(inactive5, null, UPSERT, 6, H1, 0), APPLY, EmployeeReactivated);
            expect(decide(inactive5, null, UPSERT, 6, H2, 0), APPLY, EmployeeReactivated);
        }

        @Test
        void activeDeactivateHigherSeqDeactivates() {
            expect(decide(active5, null, DEACTIVATE, 6, null, 0), APPLY, EmployeeDeactivated);
        }

        @Test
        void inactiveDeactivateHigherSeqAdvancesVersion() {
            expect(decide(inactive5, null, DEACTIVATE, 6, null, 0), APPLY, EmployeeVersionAdvanced);
        }

        @Test
        void watermarkAndTombstoneDoNotApplyWhenRowExists() {
            expect(decide(active5, 9L, UPSERT, 6, H2, 9), APPLY, EmployeeUpdated);
        }
    }

    @Test
    void briefExampleV7TransferThenV6ShiftIsStale() {
        EmployeeState afterV7 = new EmployeeState(true, 7, H2);
        expect(decide(afterV7, null, UPSERT, 6, H1, 0), STALE, null);
    }
}
