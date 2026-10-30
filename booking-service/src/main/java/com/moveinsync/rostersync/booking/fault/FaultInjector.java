package com.moveinsync.rostersync.booking.fault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Process-level failure injection (spec §13.2). When {@code app.faults.point} names the point being
 * hit, the JVM halts with exit code 137 (as if SIGKILLed): no shutdown hooks, no flush, no response.
 * Empty by default, so production never halts.
 */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    /** How to stop the process; tests substitute one that records the call. */
    @FunctionalInterface
    public interface Halter {
        void halt(int status);
    }

    private final String point;
    private final Halter halter;

    @Autowired
    public FaultInjector(@Value("${app.faults.point:}") String point) {
        this(point, status -> Runtime.getRuntime().halt(status));
    }

    public FaultInjector(String point, Halter halter) {
        this.point = point == null ? "" : point.trim();
        this.halter = halter;
    }

    public void hit(String faultPoint) {
        if (!point.isEmpty() && point.equals(faultPoint)) {
            log.error("event=fault_halt point={}", faultPoint);
            halter.halt(137);
        }
    }
}
