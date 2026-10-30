package com.moveinsync.rostersync.booking.security;

import java.util.Set;

/** The authenticated caller as the services see it. */
public record Caller(String tenantId, String principal, Set<String> roles, String employeeId) {

    public boolean has(String role) {
        return roles.contains(role);
    }
}
