package com.moveinsync.rostersync.roster.service;

/** Per-event outcome reported to the HR system. */
public enum IngestOutcome { APPLIED, TOMBSTONED, DUPLICATE, STALE, CONFLICT, REJECTED }
