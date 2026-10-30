package com.moveinsync.rostersync.roster.core;

/** An incoming change as the version gate sees it. {@code hrHash} is null for DEACTIVATE. */
public record Incoming(HrEventKind kind, long seq, byte[] hrHash) {
}
