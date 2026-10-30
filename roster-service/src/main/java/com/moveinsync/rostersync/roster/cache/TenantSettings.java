package com.moveinsync.rostersync.roster.cache;

import com.moveinsync.rostersync.roster.core.SafetyBrake;
import com.moveinsync.rostersync.roster.repository.TenantConfigRepository;
import com.moveinsync.rostersync.roster.repository.TenantSourceRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/** Cached per-tenant settings. Only the immutable lock key is cached for a source, never its watermark. */
@Component
public class TenantSettings {

    private final TenantConfigRepository config;
    private final TenantSourceRepository sources;

    public TenantSettings(TenantConfigRepository config, TenantSourceRepository sources) {
        this.config = config;
        this.sources = sources;
    }

    @Cacheable(cacheNames = CacheConfig.TENANT_CONFIG, key = "#tenantId")
    public int brakeThresholdBp(String tenantId) {
        return config.brakeThresholdBp(tenantId).orElse(SafetyBrake.DEFAULT_THRESHOLD_BP);
    }

    /** Null when the tenant has no such source (not cached, so a newly provisioned source is seen at once). */
    @Cacheable(cacheNames = CacheConfig.TENANT_SOURCE, key = "#tenantId + '|' + #sourceId", unless = "#result == null")
    public Long lockKey(String tenantId, String sourceId) {
        return sources.lockKey(tenantId, sourceId).orElse(null);
    }
}
