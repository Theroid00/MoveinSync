package com.moveinsync.rostersync.roster.core;

import java.util.Arrays;

import com.moveinsync.rostersync.contract.EventType;
import com.moveinsync.rostersync.roster.core.Decision.Outcome;

/**
 * The version gate (spec §7.4). Higher {@code sourceSeq} wins; lower is STALE; equal seq with the same
 * HR state and active-intent is DUPLICATE; equal seq otherwise is CONFLICT (never silently resolved).
 */
public final class VersionPolicy {

    private VersionPolicy() {
    }

    /**
     * @param current          stored row, or null when none exists
     * @param tombstoneSeq     seq of a tombstone for a never-created employee, or null
     * @param incoming         the change being applied
     * @param absenceWatermark last snapshot cutoff that inferred absences (blocks resurrection)
     */
    public static Decision decide(EmployeeState current, Long tombstoneSeq, Incoming incoming, long absenceWatermark) {
        long seq = incoming.seq();
        if (current == null) {
            long tomb = tombstoneSeq == null ? 0 : tombstoneSeq;
            if (incoming.kind() == HrEventKind.UPSERT) {
                long floor = Math.max(absenceWatermark, tomb);
                return seq <= floor ? Decision.of(Outcome.STALE) : Decision.apply(EventType.EmployeeCreated);
            }
            if (seq > tomb) {
                return Decision.of(Outcome.TOMBSTONE);
            }
            return seq == tomb ? Decision.of(Outcome.DUPLICATE) : Decision.of(Outcome.STALE);
        }

        if (seq < current.sourceSeq()) {
            return Decision.of(Outcome.STALE);
        }
        if (seq == current.sourceSeq()) {
            boolean sameIntent = (incoming.kind() == HrEventKind.UPSERT) == current.active();
            // DEACTIVATE carries no HR state: "no HR change" for the comparison.
            boolean sameHash = incoming.kind() == HrEventKind.DEACTIVATE
                    || Arrays.equals(incoming.hrHash(), current.hrHash());
            return sameIntent && sameHash ? Decision.of(Outcome.DUPLICATE) : Decision.of(Outcome.CONFLICT);
        }

        if (incoming.kind() == HrEventKind.DEACTIVATE) {
            return Decision.apply(current.active() ? EventType.EmployeeDeactivated : EventType.EmployeeVersionAdvanced);
        }
        if (!current.active()) {
            return Decision.apply(EventType.EmployeeReactivated);
        }
        return Decision.apply(Arrays.equals(incoming.hrHash(), current.hrHash())
                ? EventType.EmployeeVersionAdvanced : EventType.EmployeeUpdated);
    }
}
