package com.moveinsync.rostersync.roster.security;

import com.moveinsync.rostersync.roster.service.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The caller's tenant and principal. Until JWT authentication lands (P6) they come from the
 * {@code X-Tenant-Id} / {@code X-Principal} headers; services only ever see the resolved values.
 */
public final class TenantContext {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String PRINCIPAL_HEADER = "X-Principal";

    private TenantContext() {
    }

    public static String current() {
        String tenant = request().getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            throw new UnauthenticatedException();
        }
        return tenant;
    }

    public static String principal() {
        String principal = request().getHeader(PRINCIPAL_HEADER);
        return principal == null || principal.isBlank() ? "unknown" : principal;
    }

    private static HttpServletRequest request() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
