package com.moveinsync.rostersync.roster;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds nightly-export CSVs and manifests for the reconciliation tests (synthetic data only). */
public final class Snapshot {

    public static final String HEADER = "employee_id,name,email,phone,manager_id,office_code,shift_code,last_change_seq";
    public static final String EXPORTED_AT = "2026-10-30T02:00:00Z";

    private Snapshot() {
    }

    /** One CSV row; {@code seq} is a String so tests can also write malformed values. */
    public record Row(String id, String name, String email, String phone, String manager, String office, String shift,
                      String seq) {

        public Row seq(long s) {
            return new Row(id, name, email, phone, manager, office, shift, Long.toString(s));
        }

        public Row shift(String s) {
            return new Row(id, name, email, phone, manager, office, s, seq);
        }

        public Row office(String o) {
            return new Row(id, name, email, phone, manager, o, shift, seq);
        }

        public Row phone(String p) {
            return new Row(id, name, email, p, manager, office, shift, seq);
        }

        public Row email(String e) {
            return new Row(id, name, e, phone, manager, office, shift, seq);
        }

        public Row name(String n) {
            return new Row(id, n, email, phone, manager, office, shift, seq);
        }

        public Row id(String i) {
            return new Row(i, name, email, phone, manager, office, shift, seq);
        }
    }

    public static String id(int i) {
        return "EMP-%04d".formatted(i);
    }

    /** Employee i with stable synthetic HR fields and last_change_seq = seq. */
    public static Row row(int i, long seq) {
        return new Row(id(i), "Employee " + i, "emp" + i + "@example.test", "+91900%07d".formatted(i), "", "BLR-A",
                "MORNING", Long.toString(seq));
    }

    /** Employees from..to (inclusive), each with last_change_seq = its number. */
    public static List<Row> rows(int from, int to) {
        List<Row> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(row(i, i));
        }
        return rows;
    }

    public static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Row r : rows) {
            sb.append(String.join(",", quote(r.id()), quote(r.name()), quote(r.email()), quote(r.phone()), quote(r.manager()),
                    quote(r.office()), quote(r.shift()), quote(r.seq()))).append('\n');
        }
        return sb.toString();
    }

    public static Map<String, Object> manifest(long cutoff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceId", "hr-main");
        m.put("cutoffSeq", cutoff);
        m.put("exportedAt", EXPORTED_AT);
        return m;
    }

    public static Map<String, Object> manifest(long cutoff, Integer expectedRows, String sha256) {
        Map<String, Object> m = manifest(cutoff);
        if (expectedRows != null) {
            m.put("expectedRows", expectedRows);
        }
        if (sha256 != null) {
            m.put("sha256", sha256);
        }
        return m;
    }

    private static String quote(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
