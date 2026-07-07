package com.quantfinlib.execution;

import com.quantfinlib.execution.BenchmarkExecutor.Benchmark;
import com.quantfinlib.execution.BenchmarkExecutor.MarketState;
import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkExecutorTest {

    private static MarketState neutral(double frac) {
        return MarketState.neutral(100.0, frac);
    }

    /** Run a benchmark over N even time steps with neutral markets; return executed. */
    private static long runNeutral(Benchmark b, long parent, int steps) {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, parent, b, 0.1, 0, 1.0);
        for (int i = 1; i <= steps; i++) {
            long due = e.dueQuantity((double) i / steps, neutral((double) i / steps));
            e.onFill(due);
        }
        return e.executed();
    }

    // ------------------------------------------------------------------
    // Each benchmark's completion curve
    // ------------------------------------------------------------------

    @Test
    void twapIsLinearInTime() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 10_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        // Halfway through time, ~half should be due.
        assertEquals(5_000, e.dueQuantity(0.5, neutral(0.5)));
        e.onFill(5_000);
        assertEquals(0, e.dueQuantity(0.5, neutral(0.5)), "on schedule: nothing due");
        assertEquals(5_000, e.dueQuantity(1.0, neutral(1.0)));
    }

    @Test
    void allTimeBenchmarksFinishTheParent() {
        for (Benchmark b : new Benchmark[]{Benchmark.TWAP, Benchmark.ARRIVAL_PRICE,
                Benchmark.IMPLEMENTATION_SHORTFALL, Benchmark.CLOSING_PRICE,
                Benchmark.OPENING_PRICE}) {
            assertEquals(100_000, runNeutral(b, 100_000, 50), b + " must complete the parent");
        }
    }

    @Test
    void frontLoadedBenchmarksLeadBackLoadedOnesEarly() {
        // At 25% through time, compare how much each has scheduled.
        long open = frontProgress(Benchmark.OPENING_PRICE);
        long is = frontProgress(Benchmark.IMPLEMENTATION_SHORTFALL);
        long twap = frontProgress(Benchmark.TWAP);
        long close = frontProgress(Benchmark.CLOSING_PRICE);
        // Open (√f) > IS (1-(1-f)²) > TWAP (f) > Close (f²) at f=0.25.
        assertTrue(open > is, "open leads IS early");
        assertTrue(is > twap, "IS front-loads vs TWAP");
        assertTrue(twap > close, "TWAP leads the back-loaded close");
    }

    private static long frontProgress(Benchmark b) {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, b, 0.1, 0, 1.0);
        return e.dueQuantity(0.25, neutral(0.25));
    }

    @Test
    void closingPriceKeepsWeightNearTheClose() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000,
                Benchmark.CLOSING_PRICE, 0.1, 0, 1.0);
        // At f=0.5, target = 0.25 (f²), so ~25k due.
        assertEquals(25_000, e.dueQuantity(0.5, neutral(0.5)));
        e.onFill(25_000);
        // The last quarter of time carries the remaining 75%.
        assertTrue(e.dueQuantity(0.9, neutral(0.9)) > 25_000);
    }

    // ------------------------------------------------------------------
    // VWAP follows the volume curve, not the clock
    // ------------------------------------------------------------------

    @Test
    void vwapTracksTheVolumeCurveNotWallClock() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.VWAP,
                0.1, 0, 1.0);
        // 20% of time elapsed but 50% of expected volume already traded
        // (busy open): VWAP should be ~50% done, not 20%.
        MarketState busyOpen = new MarketState(100, 0, 0, Double.POSITIVE_INFINITY,
                0.5, 0, 0);
        assertEquals(50_000, e.dueQuantity(0.2, busyOpen));
    }

    // ------------------------------------------------------------------
    // POV is volume-driven
    // ------------------------------------------------------------------

    @Test
    void participationChasesRealizedVolume() {
        BenchmarkExecutor e = BenchmarkExecutor.pov(Side.BUY, 1_000_000, 0.10);
        assertEquals(0, e.dueQuantity(0.0, neutral(0.0)));
        e.onMarketVolume(500_000);                 // target = 50k
        assertEquals(50_000, e.dueQuantity(0.0, neutral(0.0)));
        e.onFill(50_000);
        e.onMarketVolume(300_000);                 // target 80k, done 50k
        long due = e.dueQuantity(0.0, neutral(0.0));
        assertEquals(30_000, due);
        e.onFill(due);                             // once filled, we're at target
        assertEquals(0.10, e.realizedParticipation(), 1e-9);
    }

    // ------------------------------------------------------------------
    // Dynamic layer: alpha, liquidity, drift
    // ------------------------------------------------------------------

    @Test
    void adverseAlphaSpeedsUpAndFavorableAlphaSlowsDown() {
        long base = dueWithAlpha(0.0);
        long adverse = dueWithAlpha(0.01);    // price rising, we're buying: hurry
        long favorable = dueWithAlpha(-0.01); // price falling: wait
        assertTrue(adverse > base, "adverse alpha must accelerate");
        assertTrue(favorable < base, "favorable alpha must decelerate");
    }

    private static long dueWithAlpha(double alpha) {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 10, 1.0);
        return e.dueQuantity(0.5, new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, 0.5, alpha, 0));
    }

    @Test
    void sellSideFlipsTheAlphaSign() {
        // For a seller, a FALLING price (negative alpha) is adverse -> hurry.
        BenchmarkExecutor buy = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 10, 1.0);
        BenchmarkExecutor sell = new BenchmarkExecutor(Side.SELL, 100_000, Benchmark.TWAP,
                0.1, 10, 1.0);
        MarketState priceFalling = new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, 0.5, -0.01, 0);
        assertTrue(sell.dueQuantity(0.5, priceFalling)
                > buy.dueQuantity(0.5, priceFalling));
    }

    @Test
    void liquidityCapLimitsTheChild() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 0.25);
        // Behind by ~50k, but only 40k displayed -> capped at 25% × 40k = 10k.
        MarketState thin = new MarketState(100, 0, 0, 40_000, 0.5, 0, 0);
        assertEquals(10_000, e.dueQuantity(0.5, thin));
    }

    @Test
    void wideSpreadDampsAggression() {
        BenchmarkExecutor tight = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        BenchmarkExecutor wide = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        long tightDue = tight.dueQuantity(0.5, new MarketState(100, 0.001, 0,
                Double.POSITIVE_INFINITY, 0.5, 0, 0));
        long wideDue = wide.dueQuantity(0.5, new MarketState(100, 1.0, 0,
                Double.POSITIVE_INFINITY, 0.5, 0, 0));
        assertTrue(wideDue < tightDue, "a wide spread must reduce the child");
    }

    @Test
    void estimatedImpactDampsThePaceLikeASpread() {
        BenchmarkExecutor free = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        BenchmarkExecutor costly = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        long freeDue = free.dueQuantity(0.5, new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, 0.5, 0, 0));
        // 100 bps of estimated impact is the same 1% trading cost as a 1.0
        // spread on a 100 mid: the pace halves.
        long costlyDue = costly.dueQuantity(0.5, new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, 0.5, 0, 100));
        assertEquals(freeDue / 2, costlyDue,
                "1% impact damps exactly like a 1% relative spread");
    }

    @Test
    void volatilityRaisesUrgencyForShortfallButLowersItForTwap() {
        MarketState volatile_ = new MarketState(100, 0, 0.5,
                Double.POSITIVE_INFINITY, 0.5, 0, 0);
        long isDue = new BenchmarkExecutor(Side.BUY, 100_000,
                Benchmark.IMPLEMENTATION_SHORTFALL, 0.1, 0, 1.0)
                .dueQuantity(0.5, volatile_);
        long isBase = new BenchmarkExecutor(Side.BUY, 100_000,
                Benchmark.IMPLEMENTATION_SHORTFALL, 0.1, 0, 1.0)
                .dueQuantity(0.5, neutral(0.5));
        long twapDue = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0).dueQuantity(0.5, volatile_);
        long twapBase = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0).dueQuantity(0.5, neutral(0.5));
        assertTrue(isDue > isBase, "vol raises IS urgency (timing risk)");
        assertTrue(twapDue < twapBase, "vol lowers passive TWAP aggression");
    }

    @Test
    void scheduleDriftReportsAheadBehind() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        e.onFill(60_000);
        assertTrue(e.scheduleDrift(0.5, neutral(0.5)) > 0, "60% done at 50% time = ahead");
        assertTrue(e.scheduleDrift(0.8, neutral(0.8)) < 0, "60% done at 80% time = behind");
    }

    @Test
    void catchesUpWhenBehindSchedule() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 0, 1.0);
        // Did nothing for the first 80% of time -> a big catch-up child.
        long due = e.dueQuantity(0.8, neutral(0.8));
        assertEquals(80_000, due, "behind-schedule pulls the full deficit");
    }

    // ------------------------------------------------------------------
    // Cross-asset + validation
    // ------------------------------------------------------------------

    @Test
    void worksOnFxSizedNotionalsAndRates() {
        // 50M EURUSD, VWAP, rates as the mid — no equity assumptions.
        BenchmarkExecutor e = new BenchmarkExecutor(Side.SELL, 50_000_000, Benchmark.VWAP,
                0.1, 5, 0.25);
        MarketState fx = new MarketState(1.08501, 0.00002, 0.0001,
                20_000_000, 0.4, 0, 0);
        long due = e.dueQuantity(0.3, fx);
        assertTrue(due > 0 && due <= 5_000_000, "capped at 25% of 20M depth");
    }

    @Test
    void nanMarketInputsAreNeutralNotASilentStall() {
        // A transient NaN in alpha/vol/spread must degrade to neutral, never
        // make (long)ceil(behind*NaN)=0 quietly stop sending children.
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.TWAP,
                0.1, 10, 1.0);
        MarketState poisoned = new MarketState(100, Double.NaN, Double.NaN,
                Double.POSITIVE_INFINITY, 0.5, Double.NaN, 0);
        assertEquals(50_000, e.dueQuantity(0.5, poisoned),
                "NaN inputs neutral: still ~half due at half time");
        // NaN volume fraction under VWAP degrades to the time fraction.
        BenchmarkExecutor v = new BenchmarkExecutor(Side.BUY, 100_000, Benchmark.VWAP,
                0.1, 0, 1.0);
        MarketState nanVol = new MarketState(100, 0, 0,
                Double.POSITIVE_INFINITY, Double.NaN, 0, 0);
        assertEquals(50_000, v.dueQuantity(0.5, nanVol), "NaN VWAP curve -> time fraction");
    }

    @Test
    void ofRejectsParticipationWhichNeedsAnExplicitRate() {
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkExecutor.of(Side.BUY, 1000, Benchmark.PARTICIPATION));
    }

    @Test
    void validatesInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkExecutor(Side.BUY, 0, Benchmark.TWAP, 0.1, 10, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> BenchmarkExecutor.pov(Side.BUY, 1000, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkExecutor(Side.BUY, 1000, Benchmark.TWAP, 0.1, -1, 0.25));
        assertThrows(IllegalArgumentException.class,
                () -> new BenchmarkExecutor(Side.BUY, 1000, Benchmark.TWAP, 0.1, 10, 0));
    }

    @Test
    void decisionsAreAllocationFree() {
        BenchmarkExecutor e = new BenchmarkExecutor(Side.BUY, Long.MAX_VALUE / 2,
                Benchmark.IMPLEMENTATION_SHORTFALL, 0.1, 10, 0.25);
        MarketState m = new MarketState(100, 0.01, 0.2, 50_000, 0.5, 0.001, 3);
        long blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += e.dueQuantity((i % 100) / 100.0, m);
            e.onMarketVolume(10);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += e.dueQuantity((i % 100) / 100.0, m);
            e.onMarketVolume(10);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "executor allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }
}
