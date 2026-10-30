package com.moveinsync.rostersync.roster.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.moveinsync.rostersync.roster.core.Rules;
import org.slf4j.MDC;

/**
 * MDC keys added around units of work (spec §13.3): eventId, employeeId, runId. Values are only put
 * when they have the shape of an identifier, so an odd client value can never smuggle data into logs.
 */
public final class Mdc {

    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");

    private Mdc() {
    }

    /** Removes what it added when closed. */
    public static final class Scope implements AutoCloseable {
        private final List<String> keys = new ArrayList<>();

        Scope put(String key, String value) {
            if (value != null) {
                MDC.put(key, value);
                keys.add(key);
            }
            return this;
        }

        @Override
        public void close() {
            keys.forEach(MDC::remove);
        }
    }

    public static Scope event(String eventId, String employeeId) {
        return new Scope()
                .put("eventId", eventId != null && EVENT_ID.matcher(eventId).matches() ? eventId : null)
                .put("employeeId", Rules.isValidEmployeeId(employeeId) ? employeeId : null);
    }

    public static Scope run(UUID runId) {
        return new Scope().put("runId", runId == null ? null : runId.toString());
    }

    public static Scope outbox(UUID eventId, String employeeId) {
        return new Scope().put("eventId", eventId == null ? null : eventId.toString())
                .put("employeeId", Rules.isValidEmployeeId(employeeId) ? employeeId : null);
    }
}
