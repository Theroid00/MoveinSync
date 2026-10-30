package com.moveinsync.rostersync.booking.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.moveinsync.rostersync.booking.service.DomainException;
import com.moveinsync.rostersync.contract.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the caller. Until JWT authentication lands (P6) the values come from the
 * {@code X-Tenant-Id}, {@code X-Principal}, {@code X-Roles} and {@code X-Employee-Id} headers.
 */
public final class CallerContext {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String PRINCIPAL_HEADER = "X-Principal";
    public static final String ROLES_HEADER = "X-Roles";
    public static final String EMPLOYEE_HEADER = "X-Employee-Id";

    private CallerContext() {
    }

    public static Caller current() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String tenant = request.getHeader(TENANT_HEADER);
        if (tenant == null || tenant.isBlank()) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        String roles = request.getHeader(ROLES_HEADER);
        Set<String> roleSet = roles == null ? Set.of()
                : Arrays.stream(roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).collect(Collectors.toSet());
        return new Caller(tenant, request.getHeader(PRINCIPAL_HEADER), roleSet, request.getHeader(EMPLOYEE_HEADER));
    }
}
