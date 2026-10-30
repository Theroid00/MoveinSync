package com.moveinsync.rostersync.roster.security;

import java.util.List;

import com.moveinsync.rostersync.roster.service.UnauthenticatedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The caller's tenant, subject, roles and employee id, read from the validated JWT. */
public final class TenantContext {

    private TenantContext() {
    }

    public static String current() {
        String tenant = jwt().getToken().getClaimAsString("tenant");
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException();
        }
        return tenant;
    }

    public static String principal() {
        return jwt().getToken().getSubject();
    }

    public static String employeeId() {
        return jwt().getToken().getClaimAsString("employeeId");
    }

    public static List<String> roles() {
        List<String> roles = jwt().getToken().getClaimAsStringList("roles");
        return roles == null ? List.of() : roles;
    }

    private static JwtAuthenticationToken jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token;
        }
        throw new UnauthenticatedException();
    }
}
