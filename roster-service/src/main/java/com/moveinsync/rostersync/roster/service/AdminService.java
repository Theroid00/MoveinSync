package com.moveinsync.rostersync.roster.service;

import java.util.List;
import java.util.regex.Pattern;

import com.moveinsync.rostersync.roster.cache.CacheConfig;
import com.moveinsync.rostersync.roster.core.FieldError;
import com.moveinsync.rostersync.roster.repository.OfficeRepository;
import com.moveinsync.rostersync.roster.repository.TenantConfigRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

/** Reference-data writes; each evicts the tenant's cache entry so the change is visible immediately. */
@Service
public class AdminService {

    private static final Pattern OFFICE_CODE = Pattern.compile("^[A-Z0-9-]{1,32}$");

    private final TenantTx tenantTx;
    private final OfficeRepository offices;
    private final TenantConfigRepository config;

    public AdminService(TenantTx tenantTx, OfficeRepository offices, TenantConfigRepository config) {
        this.tenantTx = tenantTx;
        this.offices = offices;
        this.config = config;
    }

    @CacheEvict(cacheNames = CacheConfig.OFFICE_CATALOG, key = "#tenantId")
    public void putOffice(String tenantId, String officeCode, boolean active) {
        if (officeCode == null || !OFFICE_CODE.matcher(officeCode).matches()) {
            throw new ValidationFailedException(List.of(new FieldError("officeCode", "OFFICE_CODE_FORMAT")));
        }
        tenantTx.inTenant(tenantId, s -> {
            offices.upsert(tenantId, officeCode, active);
            return null;
        });
    }

    @CacheEvict(cacheNames = CacheConfig.TENANT_CONFIG, key = "#tenantId")
    public void putBrakeThreshold(String tenantId, Integer thresholdBp) {
        if (thresholdBp == null || thresholdBp < 1 || thresholdBp > 10_000) {
            throw new ValidationFailedException(List.of(new FieldError("brakeThresholdBp", "RANGE_1_10000")));
        }
        config.upsert(tenantId, thresholdBp);
    }
}
