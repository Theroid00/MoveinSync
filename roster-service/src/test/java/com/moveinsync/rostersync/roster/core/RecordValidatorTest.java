package com.moveinsync.rostersync.roster.core;

import static com.moveinsync.rostersync.roster.core.Fixtures.CTX;
import static com.moveinsync.rostersync.roster.core.Fixtures.T0;
import static com.moveinsync.rostersync.roster.core.Fixtures.hr;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecordValidatorTest {

    final RecordValidator api = RecordValidator.forApi();

    List<String> codes(HrEventRequest r) {
        return api.validate(r, CTX).stream().map(FieldError::code).toList();
    }

    HrEventRequest withHr(HrRecord hr) {
        return new HrEventRequest("e-1", 5L, HrEventKind.UPSERT, "EMP-042", T0, hr);
    }

    @Test
    void validUpsertAndDeactivatePass() {
        assertThat(codes(withHr(hr()))).isEmpty();
        assertThat(codes(new HrEventRequest("e-2", 6L, HrEventKind.DEACTIVATE, "EMP-042", T0, null))).isEmpty();
    }

    @Test
    void eventIdRequiredAndAtMost100Chars() {
        assertThat(codes(new HrEventRequest(null, 5L, HrEventKind.UPSERT, "EMP-042", T0, hr()))).containsExactly("EVENT_ID_REQUIRED");
        assertThat(codes(new HrEventRequest("  ", 5L, HrEventKind.UPSERT, "EMP-042", T0, hr()))).containsExactly("EVENT_ID_REQUIRED");
        assertThat(codes(new HrEventRequest("x".repeat(100), 5L, HrEventKind.UPSERT, "EMP-042", T0, hr()))).isEmpty();
        assertThat(codes(new HrEventRequest("x".repeat(101), 5L, HrEventKind.UPSERT, "EMP-042", T0, hr()))).containsExactly("EVENT_ID_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "EMP 042", "EMP/42", "émp"})
    void employeeIdFormat(String id) {
        assertThat(codes(new HrEventRequest("e", 5L, HrEventKind.DEACTIVATE, id, T0, null))).containsExactly("EMPLOYEE_ID_FORMAT");
    }

    @Test
    void employeeIdLengthBoundary() {
        assertThat(codes(new HrEventRequest("e", 5L, HrEventKind.DEACTIVATE, "A".repeat(64), T0, null))).isEmpty();
        assertThat(codes(new HrEventRequest("e", 5L, HrEventKind.DEACTIVATE, "A".repeat(65), T0, null))).containsExactly("EMPLOYEE_ID_FORMAT");
        assertThat(codes(new HrEventRequest("e", 5L, HrEventKind.DEACTIVATE, null, T0, null))).containsExactly("EMPLOYEE_ID_FORMAT");
    }

    @Test
    void seqMustBePositive() {
        assertThat(codes(new HrEventRequest("e", 0L, HrEventKind.DEACTIVATE, "EMP-1", T0, null))).containsExactly("SEQ_NOT_POSITIVE");
        assertThat(codes(new HrEventRequest("e", null, HrEventKind.DEACTIVATE, "EMP-1", T0, null))).containsExactly("SEQ_NOT_POSITIVE");
        assertThat(codes(new HrEventRequest("e", 1L, HrEventKind.DEACTIVATE, "EMP-1", T0, null))).isEmpty();
    }

    @Test
    void effectiveAtAndEventTypeRequired() {
        assertThat(codes(new HrEventRequest("e", 1L, HrEventKind.DEACTIVATE, "EMP-1", null, null))).containsExactly("EFFECTIVE_AT_REQUIRED");
        assertThat(codes(new HrEventRequest("e", 1L, null, "EMP-1", T0, null))).containsExactly("EVENT_TYPE_REQUIRED");
    }

    @Test
    void hrBlockRequiredForUpsertAndForbiddenForDeactivate() {
        assertThat(codes(new HrEventRequest("e", 1L, HrEventKind.UPSERT, "EMP-1", T0, null))).containsExactly("HR_BLOCK_REQUIRED");
        assertThat(codes(new HrEventRequest("e", 1L, HrEventKind.DEACTIVATE, "EMP-1", T0, hr()))).containsExactly("HR_BLOCK_NOT_ALLOWED");
    }

    @Test
    void nameRequiredAndAtMost200AfterTrim() {
        HrRecord base = hr();
        assertThat(codes(withHr(new HrRecord("   ", base.email(), base.phone(), null, "BLR-A", "EVENING")))).containsExactly("NAME_REQUIRED");
        assertThat(codes(withHr(new HrRecord(null, base.email(), base.phone(), null, "BLR-A", "EVENING")))).containsExactly("NAME_REQUIRED");
        assertThat(codes(withHr(new HrRecord("  " + "n".repeat(200) + "  ", base.email(), base.phone(), null, "BLR-A", "EVENING")))).isEmpty();
        assertThat(codes(withHr(new HrRecord("n".repeat(201), base.email(), base.phone(), null, "BLR-A", "EVENING")))).containsExactly("NAME_TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plainaddress", "a@b", "a b@c.d", "@c.d", "a@@c.d"})
    void emailFormat(String email) {
        assertThat(codes(withHr(new HrRecord("N", email, "+919000000042", null, "BLR-A", "EVENING")))).containsExactly("EMAIL_FORMAT");
    }

    @Test
    void emailLengthBoundary() {
        String ok = "a".repeat(244) + "@example.t";      // 254 chars
        assertThat(ok).hasSize(254);
        assertThat(codes(withHr(new HrRecord("N", ok, "+919000000042", null, "BLR-A", "EVENING")))).isEmpty();
        assertThat(codes(withHr(new HrRecord("N", "a" + ok, "+919000000042", null, "BLR-A", "EVENING")))).containsExactly("EMAIL_FORMAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9000000042", "+09000000042", "+91 90000 00042", "+1234567", "+1234567890123456", "+91abc"})
    void phoneMustBeE164(String phone) {
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", phone, null, "BLR-A", "EVENING")))).containsExactly("PHONE_E164");
    }

    @Test
    void phoneLengthBoundaries() {
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+12345678", null, "BLR-A", "EVENING")))).isEmpty();          // 8 digits
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+123456789012345", null, "BLR-A", "EVENING")))).isEmpty();   // 15 digits
    }

    @Test
    void officeMustBeInCatalogAndShiftInSet() {
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", null, "XYZ-9", "EVENING")))).containsExactly("OFFICE_UNKNOWN");
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", null, "BLR-A", "AFTERNOON")))).containsExactly("SHIFT_UNKNOWN");
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", null, " BLR-A ", " NIGHT ")))).isEmpty();
    }

    @Test
    void managerOptionalFormattedAndNotSelf() {
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", "", "BLR-A", "EVENING")))).isEmpty();
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", "bad id", "BLR-A", "EVENING")))).containsExactly("MANAGER_ID_FORMAT");
        assertThat(codes(withHr(new HrRecord("N", "a@b.co", "+919000000042", "EMP-042", "BLR-A", "EVENING")))).containsExactly("MANAGER_SELF_REFERENCE");
    }

    @Test
    void reportsEveryFailureAndNeverTheValue() {
        HrRecord bad = new HrRecord("", "not-an-email", "12345", "EMP-042", "NOPE", "LATE");
        List<FieldError> errors = api.validate(withHr(bad), CTX);
        assertThat(errors).extracting(FieldError::code).containsExactly(
                "NAME_REQUIRED", "EMAIL_FORMAT", "PHONE_E164", "OFFICE_UNKNOWN", "SHIFT_UNKNOWN", "MANAGER_SELF_REFERENCE");
        assertThat(errors.toString()).doesNotContain("not-an-email", "12345", "NOPE", "LATE");
    }

    @Test
    void snapshotRulesAddSeqAfterCutoffAndSkipEventIdentity() {
        RecordValidator snapshot = RecordValidator.forSnapshot(100);
        HrEventRequest row = new HrEventRequest(null, 101L, HrEventKind.UPSERT, "EMP-042", null, hr());
        assertThat(snapshot.validate(row, CTX)).extracting(FieldError::code).containsExactly("SEQ_AFTER_CUTOFF");
        assertThat(snapshot.validate(new HrEventRequest(null, 100L, HrEventKind.UPSERT, "EMP-042", null, hr()), CTX)).isEmpty();
    }
}
