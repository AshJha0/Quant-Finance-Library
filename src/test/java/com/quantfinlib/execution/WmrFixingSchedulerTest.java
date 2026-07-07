package com.quantfinlib.execution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WmrFixingSchedulerTest {

    @Test
    void slicesSpanTheWindowEvenlyAndSumExactly() {
        List<Slice> s = WmrFixingScheduler.schedule(10_000_000, 20);
        assertEquals(20, s.size());
        assertEquals(10_000_000, s.stream().mapToLong(Slice::quantity).sum());
        assertEquals(0, s.get(0).offsetMillis());
        assertEquals(WmrFixingScheduler.WINDOW_MILLIS * 19 / 20, s.get(19).offsetMillis());
        // Even slicing: all quantities within 1 of each other.
        long first = s.get(0).quantity();
        for (Slice slice : s) {
            assertTrue(Math.abs(slice.quantity() - first) <= 1);
        }
    }

    @Test
    void indivisibleQuantitiesStillSumExactly() {
        List<Slice> s = WmrFixingScheduler.schedule(1_000_003, 7);
        assertEquals(1_000_003, s.stream().mapToLong(Slice::quantity).sum());
    }

    @Test
    void customWindowsAreRespected() {
        List<Slice> s = WmrFixingScheduler.schedule(100, 60_000, 4);
        assertEquals(0, s.get(0).offsetMillis());
        assertEquals(15_000, s.get(1).offsetMillis());
        assertEquals(45_000, s.get(3).offsetMillis());
    }

    @Test
    void invalidInputsThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> WmrFixingScheduler.schedule(0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> WmrFixingScheduler.schedule(100, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> WmrFixingScheduler.schedule(100, 1000, 0));
    }

    @Test
    void zeroQuantityChildrenAndOverflowOffsetsAreRejectedAtScheduleTime() {
        // More slices than shares = zero-qty children = venue rejects
        // mid-window; and a huge window would overflow slice offsets
        // negative — children due BEFORE the window opens.
        assertThrows(IllegalArgumentException.class,
                () -> WmrFixingScheduler.schedule(3, 10));
        assertThrows(IllegalArgumentException.class,
                () -> WmrFixingScheduler.schedule(100, Long.MAX_VALUE / 2, 4));
    }

    @Test
    void wmrScheduleIsExactlyATwapSchedule() {
        // The class's whole claim is "TWAP-in-window IS neutral replication":
        // pin the delegation so the two can never drift.
        assertEquals(TwapScheduler.schedule(1_000_003, 300_000, 7),
                WmrFixingScheduler.schedule(1_000_003, 300_000, 7));
    }
}
