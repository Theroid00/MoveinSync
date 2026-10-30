package com.moveinsync.rostersync.roster.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A reference model of event ingestion for one tenant source, driven by the production
 * {@link VersionPolicy}. Mirrors the database path: event-id dedup, then the version gate on the
 * employee row or its tombstone. Used to prove order independence and replay idempotence.
 */
public final class InMemoryApplier {

    /** Ingest outcomes as reported to the caller. */
    public enum Result { APPLIED, TOMBSTONED, STALE, DUPLICATE, CONFLICT }

    private record Row(boolean active, long seq, byte[] hrHash) {
    }

    private final Map<String, Row> rows = new HashMap<>();
    private final Map<String, Long> tombstones = new HashMap<>();
    private final Map<String, byte[]> seenEvents = new HashMap<>();
    private final long absenceWatermark;

    public InMemoryApplier(long absenceWatermark) {
        this.absenceWatermark = absenceWatermark;
    }

    public Result apply(HrEventRequest event) {
        byte[] payloadHash = CanonicalHasher.payloadHash(event);
        byte[] previous = seenEvents.putIfAbsent(event.eventId(), payloadHash);
        if (previous != null) {
            return Arrays.equals(previous, payloadHash) ? Result.DUPLICATE : Result.CONFLICT;
        }

        String id = event.employeeId();
        Row row = rows.get(id);
        byte[] hash = event.eventType() == HrEventKind.UPSERT ? CanonicalHasher.hrHash(id, event.hr()) : null;
        Decision decision = VersionPolicy.decide(
                row == null ? null : new EmployeeState(row.active(), row.seq(), row.hrHash()),
                tombstones.get(id),
                new Incoming(event.eventType(), event.sourceSeq(), hash),
                absenceWatermark);

        return switch (decision.outcome()) {
            case APPLY -> {
                if (event.eventType() == HrEventKind.UPSERT) {
                    rows.put(id, new Row(true, event.sourceSeq(), hash));
                    tombstones.remove(id);
                } else {
                    rows.put(id, new Row(false, event.sourceSeq(), row.hrHash()));
                }
                yield Result.APPLIED;
            }
            case TOMBSTONE -> {
                tombstones.merge(id, event.sourceSeq(), Math::max);
                yield Result.TOMBSTONED;
            }
            case STALE -> Result.STALE;
            case DUPLICATE -> Result.DUPLICATE;
            case CONFLICT -> Result.CONFLICT;
        };
    }

    /**
     * Observable state per employee (spec §7.4): an active row is (active, seq, hash); an inactive row
     * and a tombstone both mean "inactive at seq" and project to the same value.
     */
    public Map<String, ObservableState> observable() {
        Map<String, ObservableState> out = new TreeMap<>();
        tombstones.forEach((id, seq) -> out.put(id, ObservableState.inactive(seq)));
        rows.forEach((id, row) -> out.put(id, row.active()
                ? ObservableState.active(row.seq(), Sha256.hex(row.hrHash()))
                : ObservableState.inactive(row.seq())));
        return out;
    }

    public record ObservableState(boolean active, long seq, String hrHashHex) {

        static ObservableState active(long seq, String hrHashHex) {
            return new ObservableState(true, seq, hrHashHex);
        }

        static ObservableState inactive(long seq) {
            return new ObservableState(false, seq, null);
        }
    }
}
