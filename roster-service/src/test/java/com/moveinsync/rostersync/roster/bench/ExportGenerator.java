package com.moveinsync.rostersync.roster.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

import com.moveinsync.rostersync.roster.core.Bucketer;
import com.moveinsync.rostersync.roster.core.CanonicalHasher;
import com.moveinsync.rostersync.roster.core.HrRecord;
import com.moveinsync.rostersync.roster.service.SnapshotStager;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/** Fixed seed 42; every scenario shares the same baseline and disjoint change/missing sets. */
public final class ExportGenerator {
    public static final int SIZE = 200_000;
    public static final String[] SCENARIOS = {"initial", "typical", "heavy", "quiet", "cut", "invalid"};
    public static final String EXPORTED_AT = "2026-01-01T00:00:00Z";

    private ExportGenerator() { }

    public static String id(int i) { return "EMP-" + String.format(java.util.Locale.ROOT, "%06d", i); }

    public static HrRecord hr(int i, boolean changed) {
        return new HrRecord("Employee " + i, "employee" + i + "@bench.example",
                "+91" + String.format(java.util.Locale.ROOT, "%010d", i), null,
                changed ? "GGN-A" : "BLR-A", changed ? "NIGHT" : "MORNING");
    }

    public static long cutoff(String scenario) { return scenario.equals("initial") ? SIZE : 600_000; }

    public static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve(".mvn"))) { p = p.getParent(); }
        if (p == null) { throw new IllegalStateException("project root not found"); }
        return p;
    }

    public static Path generate() throws IOException {
        Path dir = root().resolve("roster-service/target/bench");
        Files.createDirectories(dir);
        int[] rank = new int[SIZE + 1];
        int[] order = java.util.stream.IntStream.rangeClosed(1, SIZE).toArray();
        Random random = new Random(42);
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), old = order[i];
            order[i] = order[j]; order[j] = old;
        }
        for (int i = 0; i < SIZE; i++) { rank[order[i]] = i; }
        for (String scenario : SCENARIOS) {
            int written = 0;
            try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(dir.resolve(scenario + ".csv")), CSVFormat.DEFAULT)) {
                csv.printRecord(SnapshotStager.HEADER);
                for (int i = 1; i <= SIZE; i++) {
                    boolean typical = scenario.equals("typical") || scenario.equals("invalid");
                    if ((scenario.equals("cut") && i > 120_000) || (typical && rank[i] >= 6000 && rank[i] < 8000)) { continue; }
                    boolean changed = (typical && rank[i] < 6000) || (scenario.equals("heavy") && rank[i] < 100_000)
                            || (scenario.equals("quiet") && rank[i] < 100);
                    write(csv, i, changed, scenario.equals("invalid") && rank[i] < 1000);
                    written++;
                }
                if (scenario.equals("typical") || scenario.equals("invalid")) {
                    for (int i = SIZE + 1; i <= SIZE + 2000; i++) { write(csv, i, true, false); written++; }
                }
            }
            Files.writeString(dir.resolve(scenario + ".manifest.json"), """
                    {"sourceId":"hr-main","cutoffSeq":%d,"exportedAt":"%s","expectedRows":%d}
                    """.formatted(cutoff(scenario), EXPORTED_AT, written));
        }
        return dir;
    }

    private static void write(CSVPrinter csv, int i, boolean changed, boolean invalid) throws IOException {
        HrRecord h = hr(i, changed);
        csv.printRecord(id(i), h.name(), h.email(), invalid ? "bad-phone" : h.phone(), h.managerId(),
                h.officeCode(), h.shiftCode(), changed ? i + 300_000 : i);
    }

    /** COPY input used both by the benchmark fixture and the Compose contention run. */
    public static void baseline(Path file, String tenant) throws IOException {
        try (CSVPrinter csv = new CSVPrinter(Files.newBufferedWriter(file), CSVFormat.POSTGRESQL_CSV)) {
            for (int i = 1; i <= SIZE; i++) {
                HrRecord h = hr(i, false);
                String id = id(i);
                csv.printRecord(tenant, "hr-main", id, h.name(), h.email(), h.phone(), h.managerId(), h.officeCode(),
                        h.shiftCode(), true, i, "\\x" + HexFormat.of().formatHex(CanonicalHasher.hrHash(id, h)),
                        Bucketer.bucket(id), 1);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        Path dir = generate();
        baseline(dir.resolve("baseline.csv"), "acme");
        System.out.println("Exports and COPY baseline: " + dir);
    }
}
