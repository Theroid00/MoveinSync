package com.moveinsync.rostersync.roster;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builders for HR event payloads used by the integration tests (synthetic data only). */
public final class Events {

    public static final String EFFECTIVE_AT = "2026-10-30T08:00:00Z";

    private Events() {
    }

    public static Map<String, Object> hr(String office, String shift) {
        Map<String, Object> hr = new LinkedHashMap<>();
        hr.put("name", "Synthetic Employee");
        hr.put("email", "synthetic@example.test");
        hr.put("phone", "+919000000042");
        hr.put("managerId", null);
        hr.put("officeCode", office);
        hr.put("shiftCode", shift);
        return hr;
    }

    public static Map<String, Object> upsert(String eventId, long seq, String employeeId, Map<String, Object> hr) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", eventId);
        e.put("sourceSeq", seq);
        e.put("eventType", "UPSERT");
        e.put("employeeId", employeeId);
        e.put("effectiveAt", EFFECTIVE_AT);
        e.put("hr", hr);
        return e;
    }

    public static Map<String, Object> upsert(String eventId, long seq, String employeeId) {
        return upsert(eventId, seq, employeeId, hr("BLR-A", "MORNING"));
    }

    public static Map<String, Object> deactivate(String eventId, long seq, String employeeId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", eventId);
        e.put("sourceSeq", seq);
        e.put("eventType", "DEACTIVATE");
        e.put("employeeId", employeeId);
        e.put("effectiveAt", EFFECTIVE_AT);
        return e;
    }

    /** Copy with one HR field changed. */
    public static Map<String, Object> withHr(Map<String, Object> event, String field, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(event);
        @SuppressWarnings("unchecked")
        Map<String, Object> hr = new LinkedHashMap<>((Map<String, Object>) event.get("hr"));
        hr.put(field, value);
        copy.put("hr", hr);
        return copy;
    }
}
