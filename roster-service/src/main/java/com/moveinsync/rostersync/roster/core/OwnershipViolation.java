package com.moveinsync.rostersync.roster.core;

import java.util.Set;

/** A caller tried to write a field it does not own. Carries field names only. */
public class OwnershipViolation extends RuntimeException {

    private final Set<String> fields;

    public OwnershipViolation(Set<String> fields) {
        super("fields not owned by caller: " + fields);
        this.fields = Set.copyOf(fields);
    }

    public Set<String> fields() {
        return fields;
    }
}
