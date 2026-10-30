package com.moveinsync.rostersync.roster.cache;

import java.time.Duration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The three caches of spec §13.4 (Caffeine, W-TinyLFU, size-bounded, stats recorded for Micrometer):
 * <ul>
 *   <li>officeCatalog: tenant → active office codes, 5 min. Stale: an added office is rejected or a
 *       removed one accepted for at most 5 min; the office foreign key still rejects a removed office.</li>
 *   <li>tenantConfig: tenant → brake threshold, 60 s.</li>
 *   <li>tenantSource: tenant|source → advisory lock key, 10 min (immutable once created).</li>
 * </ul>
 * Never cached: employee state, versions or the absence watermark (read in-transaction).
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    public static final String OFFICE_CATALOG = "officeCatalog";
    public static final String TENANT_CONFIG = "tenantConfig";
    public static final String TENANT_SOURCE = "tenantSource";

    /** Replaceable in tests to prove the TTL bound without waiting. */
    @Bean
    Ticker cacheTicker() {
        return Ticker.systemTicker();
    }

    @Bean
    CacheManager cacheManager(Ticker cacheTicker) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(OFFICE_CATALOG, cache(cacheTicker, Duration.ofMinutes(5)));
        manager.registerCustomCache(TENANT_CONFIG, cache(cacheTicker, Duration.ofSeconds(60)));
        manager.registerCustomCache(TENANT_SOURCE, cache(cacheTicker, Duration.ofMinutes(10)));
        return manager;
    }

    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> cache(Ticker ticker, Duration ttl) {
        return Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(ttl).recordStats().ticker(ticker).build();
    }
}
