package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalEngineTest {

    private static final long MS = 1_000_000L;
    private static final long SEC = 1_000_000_000L;

    /** Config with 1s/4s momentum, 2s vol+liquidity, 200ms flow, equal weights. */
    private static SignalEngine.Config cfg() {
        return new SignalEngine.Config(200 * MS, SEC, 4 * SEC, 2 * SEC, 2 * SEC, 1, 1, 1, 1);
    }

    // ------------------------------------------------------------------
    // Imbalance family (delegation + cross-asset doubles)
    // ------------------------------------------------------------------

    @Test
    void imbalancesWorkOnRawFxRates() {
        SignalEngine e = new SignalEngine(2, cfg());
        e.onQuote(0, 1.08500, 500, 1.08502, 400, SEC);
        assertEquals(100.0 / 900, e.queueImbalance(0), 1e-12);
        // Bid size grows at same price: OFI +200 (the double-price path).
        e.onQuote(0, 1.08500, 700, 1.08502, 400, SEC + MS);
        assertEquals(200, e.ofi(0), 1e-6);
        e.onTrade(0, true, 300, SEC + 2 * MS);
        assertEquals(1.0, e.tradeImbalance(0), 1e-12);
        // Symbol 1 untouched: independence.
        assertEquals(0, e.ofi(1), 1e-12);
        assertEquals(0, e.queueImbalance(1), 1e-12);
    }

    @Test
    void tickAndDoubleFlowSignalsAgreeExactly() {
        // Equity ticks through the int API and the same values as doubles
        // must produce identical OFI — ticks are exact in a double.
        FlowSignals ticks = new FlowSignals(200 * MS);
        FlowSignals doubles = new FlowSignals(200 * MS);
        int[][] quotes = {{10_000, 500, 10_002, 400}, {10_001, 300, 10_002, 400},
                {10_001, 300, 10_001, 250}, {10_000, 100, 10_001, 250}};
        for (int i = 0; i < quotes.length; i++) {
            int[] q = quotes[i];
            ticks.onQuote(q[0], q[1], q[2], q[3], SEC + i);
            doubles.onQuote((double) q[0], q[1], (double) q[2], q[3], SEC + i);
            assertEquals(ticks.ofi(), doubles.ofi(), 0.0, "step " + i);
        }
    }

    // ------------------------------------------------------------------
    // Volatility
    // ------------------------------------------------------------------

    @Test
    void volatilityConvergesToTheAnalyticRateOnSyntheticReturns() {
        SignalEngine e = new SignalEngine(1, cfg());
        // Alternating ±1bp returns every 100ms: r^2/dt is constant at
        // (1e-4)^2 / 0.1s = 1e-7 per second -> vol = sqrt(1e-7) per sqrt-sec.
        double mid = 1.0000;
        long t = SEC;
        e.onQuote(0, mid - 0.00005, 100, mid + 0.00005, 100, t);
        for (int i = 0; i < 400; i++) {
            mid = mid * (1 + ((i & 1) == 0 ? 1e-4 : -1e-4 / (1 + 1e-4)));
            t += 100 * MS;
            e.onQuote(0, mid - 0.00005, 100, mid + 0.00005, 100, t);
        }
        double expected = Math.sqrt(1e-8 / 0.1);
        assertEquals(expected, e.volPerSqrtSecond(0), expected * 0.05,
                "EWMA should converge to the constant variance rate");
    }

    @Test
    void volatilityIsZeroForAFrozenMid() {
        SignalEngine e = new SignalEngine(1, cfg());
        for (int i = 0; i <= 50; i++) {
            e.onQuote(0, 99.99, 100, 100.01, 100, SEC + i * 100 * MS);
        }
        assertEquals(0, e.volPerSqrtSecond(0), 1e-12);
    }

    // ------------------------------------------------------------------
    // Liquidity
    // ------------------------------------------------------------------

    @Test
    void liquidityTracksSpreadDepthAndIntensity() {
        SignalEngine e = new SignalEngine(1, cfg());
        // Constant 2-pip spread, 900 total depth, one quote every 50ms.
        for (int i = 0; i <= 200; i++) {
            e.onQuote(0, 1.08500, 500, 1.08502, 400, SEC + i * 50L * MS);
        }
        assertEquals(0.00002, e.spread(0), 1e-9);
        assertEquals(0.00002 / 1.08501 * 1e4, e.spreadBps(0), 1e-3);
        assertEquals(900, e.topDepth(0), 1e-6);
        assertEquals(20, e.quoteIntensityPerSecond(0), 20 * 0.05,
                "50ms gaps = 20 quotes/sec");
    }

    // ------------------------------------------------------------------
    // Momentum
    // ------------------------------------------------------------------

    @Test
    void momentumSignsFollowTheDrift() {
        SignalEngine up = new SignalEngine(1, cfg());
        SignalEngine down = new SignalEngine(1, cfg());
        double midUp = 100;
        double midDown = 100;
        for (int i = 0; i <= 100; i++) {
            long t = SEC + i * 100L * MS;
            up.onQuote(0, midUp - 0.01, 100, midUp + 0.01, 100, t);
            down.onQuote(0, midDown - 0.01, 100, midDown + 0.01, 100, t);
            midUp += 0.05;                 // steady uptrend
            midDown -= 0.05;
        }
        assertTrue(up.momentum(0) > 0, "uptrend must read positive: " + up.momentum(0));
        assertTrue(down.momentum(0) < 0, "downtrend must read negative");
        // Faster EMA sits nearer the recent price than the slow one.
        assertTrue(Math.abs(up.momentum(0)) > 1e-5);
    }

    @Test
    void momentumIsZeroWithoutDrift() {
        SignalEngine e = new SignalEngine(1, cfg());
        for (int i = 0; i <= 100; i++) {
            e.onQuote(0, 99.99, 100, 100.01, 100, SEC + i * 100L * MS);
        }
        assertEquals(0, e.momentum(0), 1e-12);
    }

    // ------------------------------------------------------------------
    // Composite alpha
    // ------------------------------------------------------------------

    @Test
    void alphaBlendsDimensionlessIngredientsByWeight() {
        // Only queue-imbalance weighted: alpha == queueImbalance exactly.
        SignalEngine.Config qOnly = new SignalEngine.Config(200 * MS, SEC, 4 * SEC,
                2 * SEC, 2 * SEC, 1, 0, 0, 0);
        SignalEngine e = new SignalEngine(1, qOnly);
        e.onQuote(0, 100.00, 300, 100.02, 100, SEC);
        assertEquals(0.5, e.alpha(0), 1e-12);

        // Zero weights -> defined 0, not NaN.
        SignalEngine.Config none = new SignalEngine.Config(200 * MS, SEC, 4 * SEC,
                2 * SEC, 2 * SEC, 0, 0, 0, 0);
        SignalEngine z = new SignalEngine(1, none);
        z.onQuote(0, 100.00, 300, 100.02, 100, SEC);
        assertEquals(0, z.alpha(0), 1e-12);
    }

    @Test
    void normalizedIngredientsStayClamped() {
        SignalEngine e = new SignalEngine(1, cfg());
        e.onQuote(0, 100.00, 10, 100.02, 10, SEC);
        // Massive OFI relative to tiny depth: clamped at 1, never beyond.
        for (int i = 1; i <= 50; i++) {
            e.onQuote(0, 100.00 + i * 0.01, 1_000_000, 100.02 + i * 0.01, 10, SEC + i * MS);
        }
        assertTrue(e.normalizedOfi(0) <= 1.0);
        assertTrue(e.alpha(0) <= 1.0 && e.alpha(0) >= -1.0);
    }

    // ------------------------------------------------------------------
    // Gap discipline + validation
    // ------------------------------------------------------------------

    @Test
    void oneSidedQuotesAreGapsForEveryFamily() {
        SignalEngine e = new SignalEngine(1, cfg());
        e.onQuote(0, 100.00, 100, 100.02, 100, SEC);
        e.onQuote(0, 100.50, 100, 100.52, 100, 2 * SEC);
        double volBefore = e.volPerSqrtSecond(0);
        double spreadBefore = e.spread(0);
        assertTrue(volBefore > 0);
        // One-sided quote: nothing updates during the gap itself.
        e.onQuote(0, 100.60, 100, Double.NaN, 0, 3 * SEC);
        assertEquals(volBefore, e.volPerSqrtSecond(0), 1e-15);
        assertEquals(spreadBefore, e.spread(0), 1e-15);
        assertEquals(0, e.queueImbalance(0), 1e-12);
        // The next two-sided quote re-seeds every estimator: the huge apparent
        // jump across the gap must NOT count as a return — vol rebuilds from 0
        // rather than spiking or resuming the stale pre-gap value.
        e.onQuote(0, 200.00, 100, 200.02, 100, 4 * SEC);
        assertEquals(0, e.volPerSqrtSecond(0), 1e-15,
                "the across-gap jump is not a return; vol rebuilds");
    }

    @Test
    void zeroOrInfinitePricesAreGapsAndNeverPoisonVolatility() {
        SignalEngine e = new SignalEngine(1, cfg());
        e.onQuote(0, 99.99, 100, 100.01, 100, SEC);
        e.onQuote(0, 100.49, 100, 100.51, 100, 2 * SEC);
        double vol = e.volPerSqrtSecond(0);
        assertTrue(vol > 0);
        // A zero-price placeholder quote must be a gap, not seed prevMid=0.
        e.onQuote(0, 0, 100, 0, 100, 3 * SEC);
        assertEquals(vol, e.volPerSqrtSecond(0), 1e-15);
        // And the NEXT real quote must not compute r=(mid-0)/0 = Inf/NaN.
        e.onQuote(0, 100.99, 100, 101.01, 100, 4 * SEC);
        assertTrue(Double.isFinite(e.volPerSqrtSecond(0)), "no NaN poison");
        assertTrue(Double.isFinite(e.alpha(0)));
        // Infinite price is a gap too.
        e.onQuote(0, Double.POSITIVE_INFINITY, 100, 101, 100, 5 * SEC);
        e.onQuote(0, 101.5, 100, 101.7, 100, 6 * SEC);
        assertTrue(Double.isFinite(e.volPerSqrtSecond(0)));
    }

    @Test
    void gapReseedsEveryEstimatorSymmetrically() {
        // After a gap, vol and quote-intensity must rebuild from scratch —
        // not resume their pre-gap levels — just like momentum/spread/depth.
        SignalEngine e = new SignalEngine(1, cfg());
        for (int i = 0; i <= 30; i++) {
            e.onQuote(0, 100 + i * 0.1, 100, 100.02 + i * 0.1, 100, SEC + i * 10 * MS);
        }
        assertTrue(e.volPerSqrtSecond(0) > 0);
        assertTrue(e.quoteIntensityPerSecond(0) > 0);
        // One-sided gap.
        e.onQuote(0, 103.0, 100, Double.NaN, 0, SEC + 400 * MS);
        // First quote after the gap seeds; vol/intensity have no 2-point
        // basis yet, so they read 0 (rebuilding), not the stale pre-gap value.
        e.onQuote(0, 103.0, 100, 103.02, 100, SEC + 500 * MS);
        assertEquals(0, e.volPerSqrtSecond(0), 1e-12, "vol rebuilds, not resumes");
        assertEquals(0, e.quoteIntensityPerSecond(0), 1e-12);
    }

    @Test
    void configValidatesItself() {
        assertThrows(IllegalArgumentException.class, () -> new SignalEngine.Config(
                0, SEC, 4 * SEC, SEC, SEC, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SignalEngine.Config(
                MS, 4 * SEC, SEC, SEC, SEC, 1, 1, 1, 1));   // fast >= slow
        assertThrows(IllegalArgumentException.class, () -> new SignalEngine.Config(
                MS, SEC, 4 * SEC, SEC, SEC, -1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SignalEngine(0));
    }

    // ------------------------------------------------------------------
    // Zero allocation
    // ------------------------------------------------------------------

    @Test
    void steadyStateSignalsAreAllocationFree() {
        SignalEngine e = new SignalEngine(8, cfg());
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += step(e, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(e, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "signal engine allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(SignalEngine e, int i) {
        int s = i & 7;
        long t = SEC + (long) i * 200_000;
        double mid = 100 + (i % 60) * 0.001;
        e.onQuote(s, mid - 0.01, 100 + (i % 5) * 40, mid + 0.01, 100 + (i % 3) * 50, t);
        if ((i & 3) == 0) {
            e.onTrade(s, (i & 4) == 0, 10 + (i % 90), t);
        }
        return e.alpha(s) + e.volPerSqrtSecond(s) + e.momentum(s)
                + e.quoteIntensityPerSecond(s) + e.spreadBps(s);
    }
}
