package com.moveinsync.rostersync.booking.security;

import java.util.HashSet;
import java.util.List;

import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Resolves the caller from the validated JWT: tenant, subject, roles, employeeId. */
public final class CallerContext {

    private CallerContext() {
    }

    public static Caller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        Jwt jwt = token.getToken();
        String tenant = jwt.getClaimAsString("tenant");
        if (tenant == null || tenant.isBlank() || "*".equals(tenant)) {
            throw new DomainException(ErrorCode.FORBIDDEN);   // service tokens have no tenant API access
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        return new Caller(tenant, jwt.getSubject(), new HashSet<>(roles == null ? List.of() : roles),
                jwt.getClaimAsString("employeeId"));
    }
}
