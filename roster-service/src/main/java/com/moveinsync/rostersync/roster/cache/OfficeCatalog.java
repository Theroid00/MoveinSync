package com.moveinsync.rostersync.roster.cache;

import java.util.Set;

import com.moveinsync.rostersync.roster.repository.OfficeRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/** The tenant's active office codes, used by OFFICE_UNKNOWN validation (cached 5 min, evicted on office writes). */
@Component
public class OfficeCatalog {

    private final TenantTx tenantTx;
    private final OfficeRepository offices;

    public OfficeCatalog(TenantTx tenantTx, OfficeRepository offices) {
        this.tenantTx = tenantTx;
        this.offices = offices;
    }

    @Cacheable(cacheNames = CacheConfig.OFFICE_CATALOG, key = "#tenantId")
    public Set<String> activeOffices(String tenantId) {
        return Set.copyOf(tenantTx.inTenant(tenantId, status -> offices.activeOfficeCodes(tenantId)));
    }
}
