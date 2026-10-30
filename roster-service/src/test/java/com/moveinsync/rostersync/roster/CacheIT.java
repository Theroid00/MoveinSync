package com.moveinsync.rostersync.roster;

import static com.moveinsync.rostersync.roster.Events.hr;
import static com.moveinsync.rostersync.roster.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.moveinsync.rostersync.roster.RosterApi.Role;
import com.moveinsync.rostersync.roster.cache.TenantSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;

/** Hit/miss behaviour, eviction on write, and the TTL bound on staleness (fake ticker, no waiting). */
class CacheIT extends AbstractApiIT {

    static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    static final FakeTicker TICKER = new FakeTicker();

    @TestBean
    Ticker cacheTicker;

    static Ticker cacheTicker() {
        return TICKER;
    }

    @Autowired
    CacheManager caches;

    @Autowired
    TenantSettings settings;

    CacheStats officeStats() {
        return ((CaffeineCache) caches.getCache("officeCatalog")).getNativeCache().stats();
    }

    @Test
    void officeCatalogHitsAfterFirstLoad() {
        CacheStats before = officeStats();
        api.postEvent(tenant, upsert("e-1", 1, "EMP-1"));
        CacheStats afterFirst = officeStats();
        assertThat(afterFirst.missCount() - before.missCount()).isEqualTo(1);

        api.postEvent(tenant, upsert("e-2", 1, "EMP-2"));
        api.postEvent(tenant, upsert("e-3", 1, "EMP-3"));
        CacheStats afterMore = officeStats();
        assertThat(afterMore.missCount()).isEqualTo(afterFirst.missCount());
        assertThat(afterMore.hitCount() - afterFirst.hitCount()).isEqualTo(2);
    }

    @Test
    void officeAddVisibleAfterEvict() {
        assertThat(api.postEvent(tenant, upsert("e-1", 1, "EMP-1", hr("PUN-1", "MORNING"))).status()).isEqualTo(422);
        var put = api.perform(put("/api/v1/admin/offices/PUN-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"), tenant, Role.ADMIN);
        assertThat(put.status()).isEqualTo(200);
        // No waiting: the write evicted the tenant's catalog entry.
        assertThat(api.postEvent(tenant, upsert("e-2", 1, "EMP-1", hr("PUN-1", "MORNING"))).text("outcome")).isEqualTo("APPLIED");
    }

    @Test
    void staleWithinTtlBoundOnly() {
        api.postEvent(tenant, upsert("e-0", 1, "EMP-0"));                     // loads the catalog
        // An office added behind the cache's back (no eviction) ...
        db.sql("INSERT INTO office (tenant_id, office_code) VALUES (?, 'HYD-1')").param(tenant).update();
        assertThat(api.postEvent(tenant, upsert("e-1", 1, "EMP-1", hr("HYD-1", "MORNING"))).status()).isEqualTo(422);
        TICKER.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(api.postEvent(tenant, upsert("e-2", 1, "EMP-1", hr("HYD-1", "MORNING"))).status()).isEqualTo(422);
        // ... is visible once the 5-minute bound passes, never later.
        TICKER.advance(Duration.ofSeconds(2));
        assertThat(api.postEvent(tenant, upsert("e-3", 1, "EMP-1", hr("HYD-1", "MORNING"))).text("outcome")).isEqualTo("APPLIED");

        // tenantConfig: 60 s bound.
        assertThat(settings.brakeThresholdBp(tenant)).isEqualTo(3000);
        db.sql("UPDATE tenant_config SET brake_threshold_bp = 2500 WHERE tenant_id = ?").param(tenant).update();
        TICKER.advance(Duration.ofSeconds(59));
        assertThat(settings.brakeThresholdBp(tenant)).isEqualTo(3000);
        TICKER.advance(Duration.ofSeconds(2));
        assertThat(settings.brakeThresholdBp(tenant)).isEqualTo(2500);
    }
}
