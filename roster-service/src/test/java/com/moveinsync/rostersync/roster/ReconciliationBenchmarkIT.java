package com.moveinsync.rostersync.roster;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.moveinsync.rostersync.roster.bench.BenchmarkBaseline;
import com.moveinsync.rostersync.roster.bench.ExportGenerator;
import com.moveinsync.rostersync.roster.repository.ReconciliationRunRepository;
import com.moveinsync.rostersync.roster.repository.RunRow;
import com.moveinsync.rostersync.roster.repository.TenantTx;
import com.moveinsync.rostersync.roster.service.ReconciliationService;
import com.moveinsync.rostersync.roster.service.SnapshotApplier;
import com.moveinsync.rostersync.roster.service.SnapshotStager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One warm-up and three fresh-baseline measurements per scenario; profile enforces -Xmx256m. */
@Tag("benchmark")
@SpringBootTest
class ReconciliationBenchmarkIT extends AbstractPostgresIT {
    @Autowired SnapshotStager stager;
    @Autowired SnapshotApplier applier;
    @Autowired ReconciliationRunRepository runs;
    @Autowired @Qualifier("bulk") TenantTx bulkTx;
    @Autowired @Qualifier("bulk") JdbcClient bulk;
    @Autowired @Qualifier("bulk") DataSource dataSource;
    @Autowired JsonMapper json;

    @Test
    void benchmarkFreshBaselines() throws Exception {
        assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(256L * 1024 * 1024);
        Path exports = ExportGenerator.generate();
        Path results = ExportGenerator.root().resolve("bench/results");
        Files.createDirectories(results);
        String tenant = newTenant();
        String postgresVersion = superuserJdbc().sql("SELECT version()").query(String.class).single();
        Path baseline = exports.resolve("benchmark-baseline.csv");
        ExportGenerator.baseline(baseline, tenant);
        // Fixture permission only in this disposable test container; production app roles cannot TRUNCATE.
        superuserJdbc().sql("GRANT TRUNCATE ON employee, employee_history, outbox_event, staging_employee, "
                + "record_error, reconciliation_run, employee_tombstone, ingest_event TO roster_app").update();

        for (String scenario : ExportGenerator.SCENARIOS) {
            List<Map<String, Object>> measurements = new ArrayList<>();
            for (int iteration = 0; iteration < 4; iteration++) {
                bulkTx.inTenant(tenant, s -> {
                    bulk.sql("TRUNCATE employee, employee_history, outbox_event, staging_employee, record_error, "
                            + "reconciliation_run, employee_tombstone, ingest_event").update();
                    bulk.sql("UPDATE tenant_source SET accepted_snapshot_cutoff = 0, absence_watermark = 0 WHERE tenant_id = ?")
                            .param(tenant).update();
                    if (!scenario.equals("initial")) { BenchmarkBaseline.load(dataSource, baseline); }
                    bulk.sql("ANALYZE employee").update();
                    return null;
                });
                UUID run = UUID.randomUUID();
                Path file = exports.resolve(scenario + ".csv");
                var manifest = json.readValue(Files.readString(exports.resolve(scenario + ".manifest.json")), ReconciliationService.Manifest.class);
                runs.insertUploading(run, tenant, "hr-main", manifest.cutoffSeq(), Instant.parse(ExportGenerator.EXPORTED_AT),
                        file.toString(), manifest.expectedRows(), null);
                // Lineage is empty after reset; a fixed test hash avoids measuring the upload's disk copy.
                runs.setFileSha(run, new byte[32]);
                AtomicLong peak = new AtomicLong();
                long started = System.nanoTime();
                try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
                    sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max), 0, 100, TimeUnit.MILLISECONDS);
                    assertThat(stager.stage(runs.find(run).orElseThrow())).isEqualTo(SnapshotStager.Result.STAGED);
                    applier.assessAndApply(run);
                }
                long total = (System.nanoTime() - started) / 1_000_000;
                RunRow row = runs.find(run).orElseThrow();
                JsonNode counts = json.readTree(row.counts()), phases = json.readTree(row.phaseMs());
                long changed = counts.path("created").asLong() + counts.path("updated").asLong() + counts.path("deactivated").asLong();
                switch (scenario) {
                    case "initial" -> assertThat(counts.path("created").asInt()).isEqualTo(200_000);
                    case "typical" -> {
                        assertThat(counts.path("updated").asInt()).isEqualTo(6000);
                        assertThat(counts.path("created").asInt()).isEqualTo(2000);
                        assertThat(counts.path("deactivated").asInt()).isEqualTo(2000);
                    }
                    case "heavy" -> assertThat(counts.path("updated").asInt()).isEqualTo(100_000);
                    case "quiet" -> {
                        assertThat(counts.path("updated").asInt()).isEqualTo(100);
                        assertThat(row.bucketsMismatched()).isLessThan(656);
                    }
                    case "cut" -> {
                        assertThat(row.status()).isEqualTo("AWAITING_APPROVAL");
                        assertThat(row.missing()).isEqualTo(80_000);
                        assertThat(changed).isZero();
                    }
                    case "invalid" -> {
                        assertThat(row.status()).isEqualTo("PARTIAL_NO_DEACTIVATIONS");
                        assertThat(row.invalidRows()).isEqualTo(1000);
                        assertThat(counts.path("deactivated").asInt()).isZero();
                        assertThat(counts.path("updated").asInt()).isEqualTo(5000);
                        assertThat(counts.path("created").asInt()).isEqualTo(2000);
                    }
                }
                if (!scenario.equals("cut") && !scenario.equals("invalid")) { assertThat(row.status()).isEqualTo("COMPLETED"); }
                long outbox = bulk.sql("SELECT count(*) FROM outbox_event").query(Long.class).single();
                assertThat(outbox).isEqualTo(changed);
                long diff = phases.path("digest").asLong() + phases.path("assess").asLong() + phases.path("apply").asLong();
                if (scenario.equals("typical") || scenario.equals("heavy")) { assertThat(diff).isLessThan(60_000); }
                Map<String, Object> measurement = new LinkedHashMap<>();
                measurement.put("iteration", iteration); measurement.put("status", row.status());
                measurement.put("phase_ms", phases); measurement.put("total_ms", total);
                measurement.put("rows_per_second", row.parsedRows() * 1000.0 / Math.max(1, total));
                measurement.put("peak_heap_bytes", peak.get()); measurement.put("buckets_mismatched", row.bucketsMismatched());
                measurement.put("outbox_rows", outbox); measurement.put("counts", counts);
                System.out.println("benchmark " + scenario + " iteration=" + iteration + " " + json.writeValueAsString(measurement));
                if (iteration > 0) { measurements.add(measurement); }
                Files.writeString(results.resolve("recon-" + scenario + ".json"), json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Map.of("scenario", scenario, "seed", 42, "heap_limit_bytes", Runtime.getRuntime().maxMemory(),
                                "warmup_runs", 1, "measured_runs", measurements, "postgres_version", postgresVersion)));
            }
        }
    }
}
