package com.moveinsync.rostersync.roster.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Seeded random HR feeds for the property-style tests (replaces jqwik). Per seed: 1–20 employees,
 * 1–15 events each with increasing seq; HR values come from small pools so same-state version
 * advances occur. About a quarter of employees end with a DEACTIVATE that is delivered first,
 * i.e. before any UPSERT of a never-created employee.
 */
final class FeedGenerator {

    static final String[] NAMES = {"Asha Rao", "Ben Ode", "Chen Li"};
    static final String[] OFFICES = {"BLR-A", "BLR-B", "GGN-A"};
    static final String[] SHIFTS = {"MORNING", "EVENING", "NIGHT"};

    record Feed(List<HrEventRequest> inSeqOrder, List<HrEventRequest> delivered, int deactivateFirstCases) {
    }

    private FeedGenerator() {
    }

    static Feed generate(long seed) {
        Random rnd = new Random(seed);
        int employees = 1 + rnd.nextInt(20);
        List<HrEventRequest> all = new ArrayList<>();
        List<HrEventRequest> deliverFirst = new ArrayList<>();
        int eventNo = 0;
        for (int e = 0; e < employees; e++) {
            String id = "EMP-" + e;
            int count = 1 + rnd.nextInt(15);
            boolean deactivateFirst = rnd.nextInt(4) == 0;
            long seq = 0;
            for (int i = 0; i < count; i++) {
                seq += 1 + rnd.nextInt(3);
                String eventId = "s" + seed + "-e" + (eventNo++);
                boolean last = i == count - 1;
                boolean deactivate = deactivateFirst ? last : rnd.nextInt(10) < 3;
                HrEventRequest event = deactivate
                        ? Fixtures.deactivate(eventId, seq, id)
                        : Fixtures.upsert(eventId, seq, id, new HrRecord(
                                NAMES[rnd.nextInt(NAMES.length)], id.toLowerCase() + "@example.test",
                                "+9190000000" + rnd.nextInt(10), null,
                                OFFICES[rnd.nextInt(OFFICES.length)], SHIFTS[rnd.nextInt(SHIFTS.length)]));
                all.add(event);
                if (deactivateFirst && last) {
                    deliverFirst.add(event);
                }
            }
        }

        List<HrEventRequest> inSeqOrder = new ArrayList<>(all);
        inSeqOrder.sort(Comparator.comparing(HrEventRequest::employeeId).thenComparing(HrEventRequest::sourceSeq));

        List<HrEventRequest> delivered = new ArrayList<>(all);
        for (HrEventRequest event : all) {
            if (rnd.nextInt(5) == 0) {          // ~20% delivered twice
                delivered.add(event);
            }
        }
        Collections.shuffle(delivered, rnd);
        delivered.removeAll(deliverFirst);
        delivered.addAll(0, deliverFirst);
        return new Feed(List.copyOf(inSeqOrder), List.copyOf(delivered), deliverFirst.size());
    }

    /** Same events with fresh event ids: a re-sent feed that only state identity can recognise. */
    static List<HrEventRequest> withFreshEventIds(List<HrEventRequest> events, String prefix) {
        List<HrEventRequest> out = new ArrayList<>();
        for (HrEventRequest e : events) {
            out.add(new HrEventRequest(prefix + e.eventId(), e.sourceSeq(), e.eventType(), e.employeeId(),
                    e.effectiveAt(), e.hr()));
        }
        return out;
    }
}
