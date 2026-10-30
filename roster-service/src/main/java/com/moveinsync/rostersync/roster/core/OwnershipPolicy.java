package com.moveinsync.rostersync.roster.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Field ownership (spec §7.7, DEC-06/07): HR owns identity, contact, manager, office, shift and the
 * active flag; the transport admin owns pickup point and special assistance. HR writes never name the
 * transport columns; an office transfer keeps the pickup point but flags it for review.
 */
public final class OwnershipPolicy {

    public static final List<String> HR_FIELDS =
            List.of("name", "email", "phone", "managerId", "officeCode", "shiftCode", "active");
    public static final List<String> TRANSPORT_FIELDS = List.of("pickupPoint", "specialAssistance");
    public static final String PICKUP_REVIEW_NEEDED = "PICKUP_REVIEW_NEEDED";

    private OwnershipPolicy() {
    }

    public static void assertTransportOnly(Set<String> fields) {
        Set<String> foreign = new TreeSet<>(fields);
        foreign.removeAll(TRANSPORT_FIELDS);
        if (!foreign.isEmpty()) {
            throw new OwnershipViolation(foreign);
        }
    }

    /**
     * Names of HR fields whose normalised values differ, plus {@code active} when it flips, plus
     * PICKUP_REVIEW_NEEDED when the office changes for an employee with a pickup point.
     * A null {@code before} means a new employee: every HR field counts as changed.
     */
    public static List<String> changedFields(HrRecord before, HrRecord after, boolean activeFlips, boolean hasPickupPoint) {
        HrRecord a = CanonicalHasher.normalize(after);
        HrRecord b = CanonicalHasher.normalize(before);
        List<String> changed = new ArrayList<>();
        if (a != null) {
            boolean created = b == null;
            addIf(changed, "name", created || !Objects.equals(b.name(), a.name()));
            addIf(changed, "email", created || !Objects.equals(b.email(), a.email()));
            addIf(changed, "phone", created || !Objects.equals(b.phone(), a.phone()));
            addIf(changed, "managerId", created || !Objects.equals(b.managerId(), a.managerId()));
            addIf(changed, "officeCode", created || !Objects.equals(b.officeCode(), a.officeCode()));
            addIf(changed, "shiftCode", created || !Objects.equals(b.shiftCode(), a.shiftCode()));
        }
        addIf(changed, "active", activeFlips);
        if (changed.contains("officeCode") && before != null && hasPickupPoint) {
            changed.add(PICKUP_REVIEW_NEEDED);
        }
        return List.copyOf(changed);
    }

    private static void addIf(List<String> list, String name, boolean condition) {
        if (condition) {
            list.add(name);
        }
    }
}
