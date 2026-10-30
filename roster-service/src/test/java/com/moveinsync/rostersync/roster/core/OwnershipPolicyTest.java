package com.moveinsync.rostersync.roster.core;

import static com.moveinsync.rostersync.roster.core.Fixtures.hr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

class OwnershipPolicyTest {

    @Test
    void transportFieldsAreAllowedForTheTransportAdmin() {
        assertThatCode(() -> OwnershipPolicy.assertTransportOnly(Set.of("pickupPoint", "specialAssistance")))
                .doesNotThrowAnyException();
    }

    @Test
    void hrFieldsAreRejectedForTheTransportAdmin() {
        assertThatThrownBy(() -> OwnershipPolicy.assertTransportOnly(Set.of("pickupPoint", "officeCode", "name")))
                .isInstanceOf(OwnershipViolation.class)
                .satisfies(e -> assertThat(((OwnershipViolation) e).fields()).containsExactlyInAnyOrder("officeCode", "name"));
    }

    @Test
    void fieldListsAreDisjointAndComplete() {
        assertThat(OwnershipPolicy.HR_FIELDS).doesNotContainAnyElementsOf(OwnershipPolicy.TRANSPORT_FIELDS);
        assertThat(OwnershipPolicy.HR_FIELDS).contains("name", "email", "managerId", "officeCode");
        assertThat(OwnershipPolicy.TRANSPORT_FIELDS).containsExactly("pickupPoint", "specialAssistance");
    }

    @Test
    void changedFieldsComparesNormalisedValues() {
        HrRecord before = hr();
        HrRecord after = new HrRecord(" Synthetic  Employee ", "EMP042@example.test", before.phone(), before.managerId(),
                before.officeCode(), "NIGHT");
        assertThat(OwnershipPolicy.changedFields(before, after, false, false)).containsExactly("shiftCode");
    }

    @Test
    void createdEmployeeReportsAllHrFields() {
        assertThat(OwnershipPolicy.changedFields(null, hr(), true, false))
                .containsExactly("name", "email", "phone", "managerId", "officeCode", "shiftCode", "active");
    }

    @Test
    void deactivationReportsOnlyActive() {
        assertThat(OwnershipPolicy.changedFields(hr(), null, true, false)).containsExactly("active");
    }

    @Test
    void officeTransferWithPickupFlagsReview() {
        HrRecord before = hr();
        HrRecord moved = new HrRecord(before.name(), before.email(), before.phone(), before.managerId(), "BLR-B", before.shiftCode());
        assertThat(OwnershipPolicy.changedFields(before, moved, false, true))
                .containsExactly("officeCode", OwnershipPolicy.PICKUP_REVIEW_NEEDED);
        assertThat(OwnershipPolicy.changedFields(before, moved, false, false)).containsExactly("officeCode");
    }
}
