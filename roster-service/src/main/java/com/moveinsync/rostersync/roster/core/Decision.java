package com.moveinsync.rostersync.roster.core;

import com.moveinsync.rostersync.contract.EventType;

/** Version-gate verdict. {@code eventType} is set only for APPLY. */
public record Decision(Outcome outcome, EventType eventType) {

    public enum Outcome { APPLY, TOMBSTONE, STALE, DUPLICATE, CONFLICT }

    static Decision apply(EventType type) {
        return new Decision(Outcome.APPLY, type);
    }

    static Decision of(Outcome outcome) {
        return new Decision(outcome, null);
    }
}
