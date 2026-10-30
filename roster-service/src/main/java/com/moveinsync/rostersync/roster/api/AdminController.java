package com.moveinsync.rostersync.roster.api;

import com.moveinsync.rostersync.roster.security.TenantContext;
import com.moveinsync.rostersync.roster.service.AdminService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant reference data (ADMIN): office catalog and brake threshold. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService admin;

    public AdminController(AdminService admin) {
        this.admin = admin;
    }

    public record OfficeRequest(Boolean active) {
    }

    public record OfficeResponse(String officeCode, boolean active) {
    }

    @PutMapping("/offices/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public OfficeResponse putOffice(@PathVariable String code, @RequestBody OfficeRequest request) {
        boolean active = request.active() == null || request.active();
        admin.putOffice(TenantContext.current(), code, active);
        return new OfficeResponse(code, active);
    }

    public record ConfigRequest(Integer brakeThresholdBp) {
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public ConfigRequest putConfig(@RequestBody ConfigRequest request) {
        admin.putBrakeThreshold(TenantContext.current(), request.brakeThresholdBp());
        return request;
    }
}
