package com.moveinsync.rostersync.roster.core;

/** An UPSERT means "exists and is active with exactly this HR state"; deactivation only via DEACTIVATE. */
public enum HrEventKind {
    UPSERT,
    DEACTIVATE
}
