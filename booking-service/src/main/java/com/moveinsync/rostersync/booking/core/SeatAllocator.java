package com.moveinsync.rostersync.booking.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/** Seats are derived, never counted: the first seat in 1..capacity with no ACTIVE reservation. */
public final class SeatAllocator {

    private SeatAllocator() {
    }

    public static OptionalInt firstFree(int capacity, Collection<Integer> takenSeats) {
        Set<Integer> taken = new HashSet<>(takenSeats);
        for (int seat = 1; seat <= capacity; seat++) {
            if (!taken.contains(seat)) {
                return OptionalInt.of(seat);
            }
        }
        return OptionalInt.empty();
    }

    public static int available(int capacity, int activeReservations) {
        return Math.max(0, capacity - activeReservations);
    }
}
