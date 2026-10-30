package com.moveinsync.rostersync.roster.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Canonical form and hashes of HR state (spec §7.3). The same bytes are produced for the API path,
 * the snapshot path and the in-memory model, so "same HR state" means the same hash everywhere.
 */
public final class CanonicalHasher {

    static final String SEP = "\u001F";
    static final String NULL = "\u0000";

    private CanonicalHasher() {
    }

    /** NFC + trim + collapsed whitespace for name; lower-cased email; trimmed codes; empty manager is null. */
    public static HrRecord normalize(HrRecord hr) {
        if (hr == null) {
            return null;
        }
        String name = hr.name() == null ? null
                : Normalizer.normalize(hr.name(), Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
        String email = hr.email() == null ? null : hr.email().trim().toLowerCase(Locale.ROOT);
        String manager = hr.managerId() == null ? null : hr.managerId().trim();
        return new HrRecord(name, email, trim(hr.phone()), manager == null || manager.isEmpty() ? null : manager,
                trim(hr.officeCode()), trim(hr.shiftCode()));
    }

    /** Fields joined by U+001F in a fixed order; null is U+0000. A null HR block is all-null. */
    public static String canonical(String employeeId, HrRecord hr) {
        HrRecord n = normalize(hr);
        StringJoiner j = new StringJoiner(SEP);
        j.add(orNull(employeeId));
        j.add(orNull(n == null ? null : n.name()));
        j.add(orNull(n == null ? null : n.email()));
        j.add(orNull(n == null ? null : n.phone()));
        j.add(orNull(n == null ? null : n.managerId()));
        j.add(orNull(n == null ? null : n.officeCode()));
        j.add(orNull(n == null ? null : n.shiftCode()));
        return j.toString();
    }

    public static byte[] hrHash(String employeeId, HrRecord hr) {
        return Sha256.of(canonical(employeeId, hr));
    }

    /** Event identity: the canonical HR state plus kind, version and effective time. */
    public static byte[] payloadHash(HrEventRequest r) {
        String s = canonical(r.employeeId(), r.hr())
                + SEP + (r.eventType() == null ? NULL : r.eventType().name())
                + SEP + (r.sourceSeq() == null ? NULL : r.sourceSeq().toString())
                + SEP + (r.effectiveAt() == null ? NULL : r.effectiveAt().toString());
        return Sha256.of(s);
    }

    /** Bucket digest element: employeeId UTF-8 || hrHash || int8(seq) big-endian (matches SQL int8send). */
    public static byte[] digestElement(String employeeId, byte[] hrHash, long seq) {
        byte[] id = employeeId.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(id.length + hrHash.length + Long.BYTES).put(id).put(hrHash).putLong(seq).array();
    }

    private static String trim(String v) {
        return v == null ? null : v.trim();
    }

    private static String orNull(String v) {
        return v == null ? NULL : v;
    }
}
