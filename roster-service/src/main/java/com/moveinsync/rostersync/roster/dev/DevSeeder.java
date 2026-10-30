package com.moveinsync.rostersync.roster.dev;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

import com.moveinsync.rostersync.roster.core.HrEventKind;
import com.moveinsync.rostersync.roster.core.HrEventRequest;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.core.Sha256;
import com.moveinsync.rostersync.roster.repository.EmployeeRepository;
import com.moveinsync.rostersync.roster.repository.OfficeRepository;
import com.moveinsync.rostersync.roster.repository.TenantConfigRepository;
import com.moveinsync.rostersync.roster.repository.TenantSourceRepository;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import com.moveinsync.rostersync.roster.service.HrEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Demo data (spec §15, compose only): tenants acme and globex with source hr-main, default brake config,
 * offices BLR-A/BLR-B/GGN-A, and employees EMP-001..EMP-100 ingested through the real event path (so
 * the outbox and relay populate booking-service). Idempotent: re-runs change nothing.
 */
@Component
@ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
public class DevSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);
    static final List<String> TENANTS = List.of("acme", "globex");
    static final List<String> OFFICES = List.of("BLR-A", "BLR-B", "GGN-A");
    static final List<String> SHIFTS = List.of("MORNING", "EVENING", "NIGHT");
    static final int EMPLOYEES = 100;

    private final TenantSourceRepository sources;
    private final TenantConfigRepository config;
    private final OfficeRepository offices;
    private final EmployeeRepository employees;
    private final TenantTx tenantTx;
    private final HrEventService events;
    private final String sourceId;

    public DevSeeder(TenantSourceRepository sources, TenantConfigRepository config, OfficeRepository offices,
                     EmployeeRepository employees, TenantTx tenantTx, HrEventService events,
                     @Value("${app.tenant-source-default}") String sourceId) {
        this.sources = sources;
        this.config = config;
        this.offices = offices;
        this.employees = employees;
        this.tenantTx = tenantTx;
        this.events = events;
        this.sourceId = sourceId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        for (String tenant : TENANTS) {
            sources.ensure(tenant, sourceId, lockKey(tenant, sourceId));
            config.ensureDefault(tenant);
            tenantTx.inTenant(tenant, s -> {
                OFFICES.forEach(code -> offices.upsert(tenant, code, true));
                return null;
            });
            long existing = tenantTx.inTenant(tenant, s -> employees.count(tenant, sourceId));
            if (existing >= EMPLOYEES) {
                log.info("event=dev_seed_skipped tenant={} employees={}", tenant, existing);
                continue;
            }
            for (int i = 1; i <= EMPLOYEES; i++) {
                events.ingest(tenant, employee(tenant, i));
            }
            log.info("event=dev_seed_done tenant={} employees={}", tenant, EMPLOYEES);
        }
    }

    static HrEventRequest employee(String tenant, int i) {
        String id = "EMP-%03d".formatted(i);
        HrRecord hr = new HrRecord("Employee %03d".formatted(i), "emp%03d@%s.example".formatted(i, tenant),
                "+9190000%05d".formatted(i), i == 1 ? null : "EMP-001", OFFICES.get(i % OFFICES.size()),
                SHIFTS.get(i % SHIFTS.size()));
        return new HrEventRequest("seed-" + id, (long) i, HrEventKind.UPSERT, id, Instant.parse("2026-01-01T00:00:00Z"), hr);
    }

    /** Stable, positive advisory-lock key derived from tenant|source. */
    static long lockKey(String tenant, String source) {
        return ByteBuffer.wrap(Sha256.of(tenant + "|" + source)).getLong() & Long.MAX_VALUE;
    }
}
