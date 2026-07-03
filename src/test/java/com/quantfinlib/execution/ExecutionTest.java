package com.quantfinlib.execution;

import com.quantfinlib.orderbook.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionTest {

    // ---- TWAP / VWAP ------------------------------------------------

    @Test
    void twapSplitsEvenlyAndPreservesTotal() {
        List<Slice> slices = TwapScheduler.schedule(1_000, 60_000, 6);
        assertEquals(6, slices.size());
        long total = 0;
        for (Slice s : slices) {
            total += s.quantity();
            assertTrue(Math.abs(s.quantity() - 1_000 / 6) <= 1);
        }
        assertEquals(1_000, total);
        assertEquals(0, slices.getFirst().offsetMillis());
        assertEquals(50_000, slices.getLast().offsetMillis());
    }

    @Test
    void randomizedTwapPreservesTotalAndVaries() {
        List<Slice> slices = TwapScheduler.scheduleRandomized(10_000, 60_000, 8, 0.3, 42);
        long total = 0;
        boolean uneven = false;
        for (Slice s : slices) {
            total += s.quantity();
            if (Math.abs(s.quantity() - 1_250) > 1) {
                uneven = true;
            }
        }
        assertEquals(10_000, total);
        assertTrue(uneven, "jitter should produce uneven slices");
    }

    @Test
    void vwapAllocatesProportionallyToProfile() {
        List<Slice> slices = VwapScheduler.schedule(1_000, new double[]{1, 3}, 20_000);
        assertEquals(250, slices.get(0).quantity());
        assertEquals(750, slices.get(1).quantity());
        assertEquals(10_000, slices.get(1).offsetMillis());
    }

    @Test
    void proportionalAllocationHandlesRounding() {
        long[] alloc = VwapScheduler.allocateProportionally(100, new double[]{1, 1, 1});
        assertEquals(100, alloc[0] + alloc[1] + alloc[2]);
        for (long a : alloc) {
            assertTrue(a >= 33 && a <= 34);
        }
    }

    // ---- Smart Order Router -----------------------------------------

    private static final List<VenueQuote> VENUES = List.of(
            new VenueQuote("LIT_A", 99.98, 400, 100.02, 400, 0.0, 500_000, false),
            new VenueQuote("LIT_B", 99.97, 600, 100.00, 300, 1.0, 800_000, false),
            new VenueQuote("DARK_X", 99.98, 500, 100.02, 500, 0.2, 1_000_000, true));

    @Test
    void sorPicksBestAllInPriceAcrossVenues() {
        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(Side.BUY, 900, VENUES, false);
        assertEquals(900, plan.routedQty());
        assertEquals(0, plan.unroutedQty());
        // Dark mid = 100.00 with 0.2bps fee (100.002 all-in) vs LIT_B ask 100.00 + 1bps (100.01)
        // vs LIT_A 100.02. Dark wins, then LIT_B, then LIT_A.
        assertEquals("DARK_X", plan.legs().get(0).venue());
        assertEquals(500, plan.legs().get(0).quantity());
        assertEquals("LIT_B", plan.legs().get(1).venue());
        assertEquals(300, plan.legs().get(1).quantity());
        assertEquals("LIT_A", plan.legs().get(2).venue());
        assertEquals(100, plan.legs().get(2).quantity());
        assertTrue(plan.expectedAvgAllInPrice() < 100.02);
    }

    @Test
    void sorReportsUnroutedWhenLiquidityInsufficient() {
        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(Side.BUY, 5_000, VENUES, false);
        assertEquals(1_200, plan.routedQty());   // 500 dark + 300 + 400 lit
        assertEquals(3_800, plan.unroutedQty());
    }

    @Test
    void sorSellSideUsesBids() {
        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(Side.SELL, 400, VENUES, false);
        // Sell: dark mid 100.00 beats best bid 99.98.
        assertEquals("DARK_X", plan.legs().getFirst().venue());
        assertEquals(400, plan.legs().getFirst().quantity());
    }

    @Test
    void preferDarkRoutesDarkFirstEvenWhenLitCompetitive() {
        List<VenueQuote> venues = List.of(
                new VenueQuote("LIT", 99.99, 1_000, 100.00, 1_000, 0.0, 1, false),
                new VenueQuote("DARK", 99.98, 300, 100.04, 300, 0.0, 1, true));  // dark mid 100.01 worse than lit ask
        SmartOrderRouter.RoutingPlan plan = SmartOrderRouter.route(Side.BUY, 500, venues, true);
        assertEquals("DARK", plan.legs().getFirst().venue());
        assertEquals(300, plan.legs().getFirst().quantity());
        assertEquals("LIT", plan.legs().get(1).venue());
    }

    // ---- Iceberg ------------------------------------------------------

    @Test
    void icebergReloadsTranches() {
        IcebergOrder ice = new IcebergOrder(1_000, 100);
        assertEquals(100, ice.visibleQty());
        assertEquals(900, ice.hiddenQty());

        assertTrue(ice.onFill(100));            // tranche exhausted -> reload
        assertEquals(100, ice.visibleQty());
        assertEquals(900, ice.remainingQty());

        assertFalse(ice.onFill(40));            // partial: no reload
        assertEquals(60, ice.visibleQty());

        long safety = 0;
        while (!ice.isComplete() && safety++ < 100) {
            ice.onFill(ice.visibleQty());
        }
        assertTrue(ice.isComplete());
        assertEquals(0, ice.remainingQty());
    }

    @Test
    void randomizedIcebergStaysWithinBoundsAndTotal() {
        IcebergOrder ice = new IcebergOrder(1_000, 100, 0.2, 7);
        long filled = 0;
        while (!ice.isComplete()) {
            long v = ice.visibleQty();
            assertTrue(v >= 1 && v <= Math.min(120, 1_000 - filled) + 1, "tranche " + v);
            ice.onFill(v);
            filled += v;
        }
        assertEquals(1_000, filled);
    }

    // ---- Mid peg ------------------------------------------------------

    @Test
    void midPegRepricesOnlyBeyondThreshold() {
        MidPegTracker peg = new MidPegTracker(Side.BUY, -0.01, Double.NaN, 0.02);
        assertEquals(99.99, peg.onQuote(99.98, 100.02), 1e-9);      // first quote always prices
        assertTrue(Double.isNaN(peg.onQuote(99.99, 100.02)));       // mid +0.005: below threshold
        assertEquals(100.04, peg.onQuote(100.03, 100.07), 1e-9);    // big move: reprice
        assertEquals(100.04, peg.currentPrice(), 1e-9);
    }

    @Test
    void midPegRespectsLimitCap() {
        MidPegTracker peg = new MidPegTracker(Side.BUY, 0, 100.00, 0.001);
        assertEquals(100.00, peg.onQuote(100.10, 100.20), 1e-9);    // mid 100.15 capped at limit
    }

    // ---- Dark pool ------------------------------------------------------

    @Test
    void darkPoolCrossesAtLitMid() {
        DarkPoolSimulator pool = new DarkPoolSimulator();
        pool.onQuote(99.99, 100.01);
        assertTrue(pool.submit(Side.SELL, 500, 0).isEmpty());       // rests hidden
        assertEquals(500, pool.restingQty(Side.SELL));

        List<DarkPoolSimulator.Fill> fills = pool.submit(Side.BUY, 300, 0);
        assertEquals(1, fills.size());
        assertEquals(100.00, fills.getFirst().price(), 1e-9);
        assertEquals(300, fills.getFirst().quantity());
        assertEquals(200, pool.restingQty(Side.SELL));
    }

    @Test
    void darkPoolHonorsMinimumExecutionQuantity() {
        DarkPoolSimulator pool = new DarkPoolSimulator();
        pool.onQuote(99.99, 100.01);
        pool.submit(Side.SELL, 200, 500);                            // resting min 500 > its qty: never fillable
        List<DarkPoolSimulator.Fill> fills = pool.submit(Side.BUY, 300, 0);
        assertTrue(fills.isEmpty());                                 // constraint blocks the cross
        assertEquals(300, pool.restingQty(Side.BUY));                // buyer rests instead
    }

    // ---- Venue benchmarking ---------------------------------------------

    @Test
    void venueBenchmarkRanksByExecutionQuality() {
        VenueBenchmark bench = new VenueBenchmark()
                // GOOD: tight effective spread, no adverse selection.
                .add(new VenueBenchmark.Sample("GOOD", Side.BUY, 100.01, 100, 100.00, 100.01, 1_000_000, true))
                .add(new VenueBenchmark.Sample("GOOD", Side.BUY, 100.01, 100, 100.00, 100.02, 1_200_000, true))
                // BAD: wide spread, price reverts (adverse selection), and a reject.
                .add(new VenueBenchmark.Sample("BAD", Side.BUY, 100.05, 100, 100.00, 99.98, 9_000_000, true))
                .add(new VenueBenchmark.Sample("BAD", Side.BUY, 0, 0, 0, 0, 0, false));

        List<VenueBenchmark.VenueStats> stats = bench.rank();
        assertEquals("GOOD", stats.getFirst().venue());
        assertEquals(1.0, stats.getFirst().fillRate(), 1e-12);
        assertEquals(0.5, stats.get(1).fillRate(), 1e-12);
        assertTrue(stats.get(1).avgEffectiveSpreadBps() > stats.getFirst().avgEffectiveSpreadBps());
        assertTrue(stats.get(1).avgMarkoutBps() < 0);   // adverse selection at BAD
    }
}
