package com.moveinsync.rostersync.roster.core;

import java.util.Optional;
import java.util.regex.Pattern;

/** The concrete boundary rules (spec §7.2). Each is a small {@link ValidationRule} subclass. */
public final class Rules {

    static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private Rules() {
    }

    public static boolean isValidEmployeeId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static final class EventIdRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return r.eventId() == null || r.eventId().isBlank() || r.eventId().length() > 100
                    ? fail("eventId", "EVENT_ID_REQUIRED") : pass();
        }
    }

    public static final class EmployeeIdRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return isValidEmployeeId(r.employeeId()) ? pass() : fail("employeeId", "EMPLOYEE_ID_FORMAT");
        }
    }

    public static final class SourceSeqRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return r.sourceSeq() == null || r.sourceSeq() < 1 ? fail("sourceSeq", "SEQ_NOT_POSITIVE") : pass();
        }
    }

    /** Snapshot rows only: a row may not claim a version newer than the export's cutoff. */
    public static final class SeqAfterCutoffRule extends ValidationRule {
        private final long cutoffSeq;

        public SeqAfterCutoffRule(long cutoffSeq) {
            this.cutoffSeq = cutoffSeq;
        }

        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return r.sourceSeq() != null && r.sourceSeq() > cutoffSeq ? fail("sourceSeq", "SEQ_AFTER_CUTOFF") : pass();
        }
    }

    public static final class EffectiveAtRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return r.effectiveAt() == null ? fail("effectiveAt", "EFFECTIVE_AT_REQUIRED") : pass();
        }
    }

    public static final class EventTypeRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            return r.eventType() == null ? fail("eventType", "EVENT_TYPE_REQUIRED") : pass();
        }
    }

    public static final class HrBlockRule extends ValidationRule {
        @Override
        public Optional<FieldError> check(HrEventRequest r, ValidationContext ctx) {
            if (r.eventType() == HrEventKind.UPSERT && r.hr() == null) {
                return fail("hr", "HR_BLOCK_REQUIRED");
            }
            if (r.eventType() == HrEventKind.DEACTIVATE && r.hr() != null) {
                return fail("hr", "HR_BLOCK_NOT_ALLOWED");
            }
            return pass();
        }
    }

    public static final class NameRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String name = trimmed(hr.name());
            if (name == null || name.isEmpty()) {
                return fail("name", "NAME_REQUIRED");
            }
            return name.length() > 200 ? fail("name", "NAME_TOO_LONG") : pass();
        }
    }

    public static final class EmailRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String email = trimmed(hr.email());
            return email == null || email.length() > 254 || !EMAIL.matcher(email).matches()
                    ? fail("email", "EMAIL_FORMAT") : pass();
        }
    }

    public static final class PhoneRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String phone = trimmed(hr.phone());
            return phone == null || !PHONE.matcher(phone).matches() ? fail("phone", "PHONE_E164") : pass();
        }
    }

    public static final class OfficeRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String office = trimmed(hr.officeCode());
            return office == null || office.isEmpty() || !ctx.officeExists(office)
                    ? fail("officeCode", "OFFICE_UNKNOWN") : pass();
        }
    }

    public static final class ShiftRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String shift = trimmed(hr.shiftCode());
            return shift == null || !ctx.shiftExists(shift) ? fail("shiftCode", "SHIFT_UNKNOWN") : pass();
        }
    }

    public static final class ManagerIdRule extends HrFieldRule {
        @Override
        protected Optional<FieldError> checkHr(HrRecord hr, HrEventRequest r, ValidationContext ctx) {
            String manager = trimmed(hr.managerId());
            if (manager == null || manager.isEmpty()) {
                return pass();
            }
            if (!isValidEmployeeId(manager)) {
                return fail("managerId", "MANAGER_ID_FORMAT");
            }
            return manager.equals(r.employeeId()) ? fail("managerId", "MANAGER_SELF_REFERENCE") : pass();
        }
    }
}
