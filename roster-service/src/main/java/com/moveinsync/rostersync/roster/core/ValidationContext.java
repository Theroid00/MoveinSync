package com.moveinsync.rostersync.roster.core;

import java.util.Set;

/** Reference data a validation rule may consult (the tenant's office catalog, the shift catalog). */
public interface ValidationContext {

    Set<String> SHIFTS = Set.of("MORNING", "EVENING", "NIGHT");

    boolean officeExists(String officeCode);

    default boolean shiftExists(String shiftCode) {
        return SHIFTS.contains(shiftCode);
    }
}
