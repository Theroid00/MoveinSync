package com.moveinsync.rostersync.booking.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SeatAllocatorTest {

    @Test
    void firstFreeSeatSkipsTakenOnes() {
        assertThat(SeatAllocator.firstFree(4, List.of())).hasValue(1);
        assertThat(SeatAllocator.firstFree(4, List.of(1, 2))).hasValue(3);
        assertThat(SeatAllocator.firstFree(4, List.of(2, 4))).hasValue(1);
    }

    @Test
    void fullTripHasNoSeat() {
        assertThat(SeatAllocator.firstFree(2, List.of(1, 2))).isEmpty();
    }

    @Test
    void availableIsCapacityMinusActive() {
        assertThat(SeatAllocator.available(10, 3)).isEqualTo(7);
        assertThat(SeatAllocator.available(2, 5)).isZero();
    }
}
