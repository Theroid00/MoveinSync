package com.moveinsync.rostersync.roster.core;

import java.util.ArrayList;
import java.util.List;

/** Runs a fixed list of {@link ValidationRule}s and collects every failure (not just the first). */
public final class RecordValidator {

    private final List<ValidationRule> rules;

    public RecordValidator(List<ValidationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** Rules for API events (spec §7.2). */
    public static RecordValidator forApi() {
        return new RecordValidator(List.of(
                new Rules.EventIdRule(), new Rules.EmployeeIdRule(), new Rules.SourceSeqRule(),
                new Rules.EffectiveAtRule(), new Rules.EventTypeRule(), new Rules.HrBlockRule(),
                new Rules.NameRule(), new Rules.EmailRule(), new Rules.PhoneRule(), new Rules.OfficeRule(),
                new Rules.ShiftRule(), new Rules.ManagerIdRule()));
    }

    /**
     * Rules for snapshot rows (spec §9.2): the API rules minus event identity and timestamp, plus
     * SEQ_AFTER_CUTOFF. Rows are presented as UPSERT requests with {@code sourceSeq = last_change_seq}.
     */
    public static RecordValidator forSnapshot(long cutoffSeq) {
        return new RecordValidator(List.of(
                new Rules.EmployeeIdRule(), new Rules.SourceSeqRule(), new Rules.SeqAfterCutoffRule(cutoffSeq),
                new Rules.NameRule(), new Rules.EmailRule(), new Rules.PhoneRule(), new Rules.OfficeRule(),
                new Rules.ShiftRule(), new Rules.ManagerIdRule()));
    }

    public List<FieldError> validate(HrEventRequest request, ValidationContext context) {
        List<FieldError> errors = new ArrayList<>();
        for (ValidationRule rule : rules) {
            rule.check(request, context).ifPresent(errors::add);
        }
        return List.copyOf(errors);
    }
}
