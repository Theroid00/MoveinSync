package com.moveinsync.rostersync.roster.core;

import static com.moveinsync.rostersync.roster.core.Fixtures.T0;
import static com.moveinsync.rostersync.roster.core.Fixtures.hr;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class CanonicalHasherTest {

    static String h(String employeeId, HrRecord hr) {
        return Sha256.hex(CanonicalHasher.hrHash(employeeId, hr));
    }

    @Test
    void goldenValuesMatchIndependentImplementation() {
        // Derived with a separate Python implementation of spec §7.3 (hashlib + unicodedata).
        assertThat(h("EMP-042", hr())).isEqualTo("f5e0e2f8e009af857e3ca519f679f95df22b59b8a49def64b2639d29ac59785b");
        assertThat(h("EMP-042", new HrRecord("Synthetic Employee", "emp042@example.test", "+919000000042", null, "BLR-A", "EVENING")))
                .isEqualTo("fe188d05b49af126651e742575d4318c9ad9fbb98745a7db54b922b29450a760");
    }

    @Test
    void canonicalStringFieldOrderIsFixed() {
        assertThat(CanonicalHasher.canonical("E", new HrRecord("n", "e@x.co", "+1", "M", "O", "S")))
                .isEqualTo("E\u001Fn\u001Fe@x.co\u001F+1\u001FM\u001FO\u001FS");
        // Swapping two field values changes the hash.
        assertThat(h("E", new HrRecord("n", "e@x.co", "+1", "M", "O", "S")))
                .isNotEqualTo(h("E", new HrRecord("n", "e@x.co", "+1", "O", "M", "S")));
    }

    @Test
    void whitespaceIsTrimmedAndCollapsedInName() {
        HrRecord base = hr();
        HrRecord spaced = new HrRecord("  Synthetic \t  Employee ", " emp042@example.test ", " +919000000042 ",
                " EMP-001 ", " BLR-A ", " EVENING ");
        assertThat(h("EMP-042", spaced)).isEqualTo(h("EMP-042", base));
    }

    @Test
    void emailIsCaseInsensitiveButNameIsNot() {
        HrRecord base = hr();
        assertThat(h("EMP-042", new HrRecord(base.name(), "EMP042@Example.TEST", base.phone(), base.managerId(), base.officeCode(), base.shiftCode())))
                .isEqualTo(h("EMP-042", base));
        assertThat(h("EMP-042", new HrRecord("synthetic employee", base.email(), base.phone(), base.managerId(), base.officeCode(), base.shiftCode())))
                .isNotEqualTo(h("EMP-042", base));
    }

    @Test
    void unicodeNfcAndNfdAreEqual() {
        String nfc = "José";
        String nfd = "José";
        assertThat(nfc).isNotEqualTo(nfd);
        assertThat(h("E", new HrRecord(nfc, "a@b.co", "+12345678", null, "O", "S")))
                .isEqualTo(h("E", new HrRecord(nfd, "a@b.co", "+12345678", null, "O", "S")));
    }

    @Test
    void emptyManagerIsNullButOtherNullsDifferFromEmpty() {
        assertThat(h("E", new HrRecord("n", "a@b.co", "+12345678", "", "O", "S")))
                .isEqualTo(h("E", new HrRecord("n", "a@b.co", "+12345678", null, "O", "S")));
        assertThat(h("E", new HrRecord("n", "", "+12345678", null, "O", "S")))
                .isNotEqualTo(h("E", new HrRecord("n", null, "+12345678", null, "O", "S")));
    }

    @Test
    void hashIs32BytesAndStable() {
        byte[] a = CanonicalHasher.hrHash("EMP-042", hr());
        assertThat(a).hasSize(32).isEqualTo(CanonicalHasher.hrHash("EMP-042", hr()));
    }

    @Test
    void payloadHashCoversKindSeqAndEffectiveAt() {
        HrEventRequest base = Fixtures.upsert("e", 7, "EMP-042", hr());
        byte[] p = CanonicalHasher.payloadHash(base);
        assertThat(CanonicalHasher.payloadHash(Fixtures.upsert("other-id", 7, "EMP-042", hr()))).isEqualTo(p);
        assertThat(CanonicalHasher.payloadHash(Fixtures.upsert("e", 8, "EMP-042", hr()))).isNotEqualTo(p);
        assertThat(CanonicalHasher.payloadHash(new HrEventRequest("e", 7L, HrEventKind.UPSERT, "EMP-042",
                Instant.parse("2026-10-30T08:00:01Z"), hr()))).isNotEqualTo(p);
        assertThat(CanonicalHasher.payloadHash(new HrEventRequest("e", 7L, HrEventKind.DEACTIVATE, "EMP-042", T0, null)))
                .isNotEqualTo(p);
    }

    @Test
    void digestElementIsIdThenHashThenBigEndianSeq() {
        byte[] hash = new byte[32];
        byte[] el = CanonicalHasher.digestElement("AB", hash, 258);
        assertThat(el).hasSize(2 + 32 + 8);
        assertThat(el[0]).isEqualTo((byte) 'A');
        assertThat(el[el.length - 2]).isEqualTo((byte) 1);
        assertThat(el[el.length - 1]).isEqualTo((byte) 2);
    }
}
