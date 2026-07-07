package com.quantfinlib.microstructure;

import com.quantfinlib.microstructure.CircuitBreakers.Halt;
import com.quantfinlib.microstructure.CircuitBreakers.Luld;
import com.quantfinlib.microstructure.CircuitBreakers.LuldState;
import com.quantfinlib.microstructure.CircuitBreakers.MarketWide;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakersTest {

    private static final long S = 1_000_000_000L;   // one second in nanos

    // ------------------------------------------------------------------
    // LULD bands
    // ------------------------------------------------------------------

    @Test
    void bandPercentagesFollowTheTierAndPriceSchedule() {
        assertEquals(0.05, CircuitBreakers.luldBandPct(100.0, true, false), 1e-12);
        assertEquals(0.10, CircuitBreakers.luldBandPct(100.0, false, false), 1e-12);
        assertEquals(0.20, CircuitBreakers.luldBandPct(2.00, true, false), 1e-12);
        assertEquals(0.20, CircuitBreakers.luldBandPct(2.00, false, false), 1e-12);
        // Sub-$0.75: lesser of 75% or $0.15 as a fraction.
        assertEquals(0.15 / 0.50, CircuitBreakers.luldBandPct(0.50, true, false), 1e-12);
        assertEquals(0.75, CircuitBreakers.luldBandPct(0.10, true, false), 1e-12);
        // Widened windows double the band.
        assertEquals(0.10, CircuitBreakers.luldBandPct(100.0, true, true), 1e-12);
        assertEquals(95.0, CircuitBreakers.luldLowerBand(100.0, true, false), 1e-9);
        assertEquals(105.0, CircuitBreakers.luldUpperBand(100.0, true, false), 1e-9);
    }

    @Test
    void limitStateBecomesAPauseAfterFifteenSeconds() {
        Luld luld = new Luld(true);
        luld.reference(100.0, false);              // bands 95..105
        assertEquals(LuldState.NORMAL, luld.onNbbo(100.0, 100.02, 0));
        // NBB pins the upper band: limit up.
        assertEquals(LuldState.LIMIT_UP, luld.onNbbo(105.0, 105.05, 1 * S));
        // Still pinned 14 s later: not yet a pause.
        assertEquals(LuldState.LIMIT_UP, luld.onNbbo(105.0, 105.05, 15 * S));
        // 15 s in the limit state: pause.
        assertEquals(LuldState.PAUSED, luld.onNbbo(105.0, 105.05, 16 * S + 1));
        assertEquals(1, luld.pauseCount());
        // Pause holds for 5 minutes regardless of quotes...
        assertEquals(LuldState.PAUSED, luld.onNbbo(100.0, 100.02, 100 * S));
        // ...and lifts after it elapses.
        assertEquals(LuldState.NORMAL, luld.onNbbo(100.0, 100.02, 16 * S + 1 + 301 * S));
    }

    @Test
    void leavingTheBandResetsTheLimitClock() {
        Luld luld = new Luld(true);
        luld.reference(100.0, false);
        luld.onNbbo(105.0, 105.05, 0);              // limit up
        luld.onNbbo(104.0, 104.02, 10 * S);         // back inside: reset
        assertEquals(LuldState.NORMAL, luld.state());
        // Pinned again for 14s: the earlier 10s must not carry over.
        luld.onNbbo(105.0, 105.05, 11 * S);
        assertEquals(LuldState.LIMIT_UP, luld.onNbbo(105.0, 105.05, 24 * S));
        assertEquals(0, luld.pauseCount());
    }

    @Test
    void limitDownMirrorsOnTheOfferSide() {
        Luld luld = new Luld(false);                 // Tier 2: 10%
        luld.reference(50.0, false);                 // bands 45..55
        assertEquals(LuldState.LIMIT_DOWN, luld.onNbbo(44.90, 45.0, 0));
        assertEquals(LuldState.PAUSED, luld.onNbbo(44.90, 45.0, 16 * S));
    }

    // ------------------------------------------------------------------
    // Market-wide circuit breakers
    // ------------------------------------------------------------------

    @Test
    void levelsFireOncePerDayInOrder() {
        MarketWide mwcb = new MarketWide();
        int t1030 = 10 * 60 + 30;
        assertEquals(Halt.NONE, mwcb.onDecline(0.05, t1030));
        assertEquals(Halt.HALT_15_MIN, mwcb.onDecline(0.071, t1030));
        // Level 1 again: already used.
        assertEquals(Halt.NONE, mwcb.onDecline(0.09, t1030));
        assertEquals(Halt.HALT_15_MIN, mwcb.onDecline(0.131, t1030));
        assertEquals(Halt.NONE, mwcb.onDecline(0.15, t1030));
        assertEquals(Halt.HALT_REST_OF_DAY, mwcb.onDecline(0.21, t1030));
        assertTrue(mwcb.level3Used());
    }

    @Test
    void levels1And2DoNotFireAfter1525ButLevel3Does() {
        MarketWide mwcb = new MarketWide();
        int t1530 = 15 * 60 + 30;
        assertEquals(Halt.NONE, mwcb.onDecline(0.08, t1530));
        assertEquals(Halt.NONE, mwcb.onDecline(0.14, t1530));
        assertFalse(mwcb.level1Used());
        assertEquals(Halt.HALT_REST_OF_DAY, mwcb.onDecline(0.20, t1530));
    }

    @Test
    void aDeepGapCanSkipStraightToLevel2() {
        MarketWide mwcb = new MarketWide();
        assertEquals(Halt.HALT_15_MIN, mwcb.onDecline(0.135, 600));
        assertTrue(mwcb.level1Used(), "level 2 implies level 1 consumed");
        assertEquals(Halt.NONE, mwcb.onDecline(0.08, 610));
    }

    @Test
    void nothingFiresAfterALevel3Halt() {
        // Once the day is closed, a persisting 20%+ decline must not
        // downgrade into 15-minute halts on subsequent evaluations.
        MarketWide mwcb = new MarketWide();
        assertEquals(Halt.HALT_REST_OF_DAY, mwcb.onDecline(0.21, 600));
        assertEquals(Halt.NONE, mwcb.onDecline(0.21, 601));
        assertEquals(Halt.NONE, mwcb.onDecline(0.14, 602));
        assertEquals(Halt.NONE, mwcb.onDecline(0.25, 603));
        assertTrue(mwcb.level1Used() && mwcb.level2Used() && mwcb.level3Used());
    }

    @Test
    void wrongTimeUnitsFailLoudlyInsteadOfSuppressingHalts() {
        MarketWide mwcb = new MarketWide();
        // Seconds- or nanos-since-midnight would silently disable L1/L2.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mwcb.onDecline(0.08, 34_200));       // 9:30 in SECONDS
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> mwcb.onDecline(0.08, -5));
    }

    @Test
    void pauseExpiryIsPollableWithoutQuotes() {
        // A paused symbol is exactly the one that stops quoting: state(now)
        // must apply expiry without waiting for the next onNbbo.
        Luld luld = new Luld(true);
        luld.reference(100.0, false);
        luld.onNbbo(105.0, 105.05, 0);
        luld.onNbbo(105.0, 105.05, 16 * S);                // paused at 16s
        assertEquals(LuldState.PAUSED, luld.state(16 * S + 100 * S));
        assertEquals(LuldState.NORMAL, luld.state(16 * S + 301 * S),
                "expiry must not require a quote");
    }

    @Test
    void luldStateMachineIsAllocationFree() {
        Luld luld = new Luld(true);
        luld.reference(100.0, false);
        for (int i = 0; i < 200_000; i++) {                // warm-up
            luld.onNbbo(100 + (i % 6), 100.02 + (i % 6), i * 1_000L);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            luld.onNbbo(100 + (i % 6), 100.02 + (i % 6), i * 1_000L);
            luld.state(i * 1_000L);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "LULD allocated " + allocated + " bytes");
    }
}
