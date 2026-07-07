package com.quantfinlib.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxTierBookTest {

    /** Two LPs with two-tier ladders around 1.0850. */
    private static FxTierBook book() {
        FxTierBook b = new FxTierBook(3, 4);
        // LP0: tight small tier, wider big tier.
        b.tier(0, false, 0, 1.08502, 1_000_000);
        b.tier(0, false, 1, 1.08504, 5_000_000);
        b.tierCount(0, false, 2);
        b.tier(0, true, 0, 1.08500, 1_000_000);
        b.tier(0, true, 1, 1.08498, 5_000_000);
        b.tierCount(0, true, 2);
        // LP1: slightly worse top, deeper second tier.
        b.tier(1, false, 0, 1.08503, 2_000_000);
        b.tier(1, false, 1, 1.08505, 10_000_000);
        b.tierCount(1, false, 2);
        b.tier(1, true, 0, 1.08499, 2_000_000);
        b.tier(1, true, 1, 1.08497, 10_000_000);
        b.tierCount(1, true, 2);
        // LP2: silent.
        return b;
    }

    @Test
    void compositeTopOfBookScansTierZero() {
        FxTierBook b = book();
        assertEquals(1.08500, b.bestBid(), 1e-12);
        assertEquals(1.08502, b.bestAsk(), 1e-12);
    }

    @Test
    void sweepTakesCheapestTiersAcrossLps() {
        FxTierBook b = book();
        // Buy 4M: 1M @ .08502 (LP0 t0), 2M @ .08503 (LP1 t0), 1M @ .08504 (LP0 t1).
        double cost = b.sweepBuyCost(4_000_000);
        double expected = 1_000_000 * 1.08502 + 2_000_000 * 1.08503 + 1_000_000 * 1.08504;
        assertEquals(expected, cost, 1e-4);

        // Sell 4M: 1M @ .08500 (LP0), 2M @ .08499 (LP1), 1M @ .08498 (LP0 t1).
        double proceeds = b.sweepSellProceeds(4_000_000);
        double expSell = 1_000_000 * 1.08500 + 2_000_000 * 1.08499 + 1_000_000 * 1.08498;
        assertEquals(expSell, proceeds, 1e-4);
    }

    @Test
    void sweepPlanAttributesQuantityPerLp() {
        FxTierBook b = book();
        double[] plan = new double[3];
        double cost = b.sweepPlan(true, 4_000_000, plan);
        assertTrue(cost > 0);
        assertEquals(2_000_000, plan[0], 1e-9);   // LP0: 1M t0 + 1M t1
        assertEquals(2_000_000, plan[1], 1e-9);
        assertEquals(0, plan[2], 1e-9);
    }

    @Test
    void unfillableSizeIsNaNNotAPartialPrice() {
        FxTierBook b = book();
        assertTrue(Double.isNaN(b.sweepBuyCost(50_000_000)));
        assertTrue(Double.isNaN(b.sweepSellProceeds(50_000_000)));
        assertTrue(Double.isNaN(b.sweepBuyCost(0)));
    }

    @Test
    void fullAmountFindsTheSingleLpCoveringTheClip() {
        FxTierBook b = book();
        // 8M: only LP1's second tiers cover it.
        assertEquals(1.08505, b.bestFullAmountAsk(8_000_000), 1e-12);
        assertEquals(1, b.bestFullAmountAskLp(8_000_000));
        assertEquals(1.08497, b.bestFullAmountBid(8_000_000), 1e-12);
        // 4M: LP0 (.08504) vs LP1 (.08505) -> LP0 wins on the ask.
        assertEquals(1.08504, b.bestFullAmountAsk(4_000_000), 1e-12);
        assertEquals(0, b.bestFullAmountAskLp(4_000_000));
        // 1M: tier-0 prices compete; LP0 tightest ask.
        assertEquals(1.08502, b.bestFullAmountAsk(1_000_000), 1e-12);
        // 20M: nobody quotes it full-amount.
        assertTrue(Double.isNaN(b.bestFullAmountAsk(20_000_000)));
        assertEquals(-1, b.bestFullAmountAskLp(20_000_000));
    }

    @Test
    void clearingAnLpRemovesItEverywhere() {
        FxTierBook b = book();
        b.clear(0);
        assertEquals(1.08499, b.bestBid(), 1e-12);
        assertEquals(1.08503, b.bestAsk(), 1e-12);
        double cost = b.sweepBuyCost(4_000_000);
        double expected = 2_000_000 * 1.08503 + 2_000_000 * 1.08505;
        assertEquals(expected, cost, 1e-4);
        b.clear(1);
        assertTrue(Double.isNaN(b.bestBid()));
        assertTrue(Double.isNaN(b.sweepBuyCost(1)));
    }

    @Test
    void fullAmountRespectsTierOrderWithinAnLp() {
        FxTierBook b = new FxTierBook(1, 3);
        b.tier(0, false, 0, 1.0001, 1_000_000);
        b.tier(0, false, 1, 1.0003, 5_000_000);
        b.tier(0, false, 2, 1.0006, 20_000_000);
        b.tierCount(0, false, 3);
        assertEquals(1.0001, b.bestFullAmountAsk(500_000), 1e-12);
        assertEquals(1.0003, b.bestFullAmountAsk(2_000_000), 1e-12);
        assertEquals(1.0006, b.bestFullAmountAsk(19_000_000), 1e-12);
    }

    @Test
    void outOfRangeTierOrLpFailsLoudlyInsteadOfCorruptingANeighbor() {
        // The flat layout means an unchecked tier overflow would land in the
        // NEXT LP's ladder — the one failure a price store must never have.
        FxTierBook b = new FxTierBook(2, 2);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.tier(0, true, 2, 1.0, 1_000_000));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.tier(0, true, -1, 1.0, 1_000_000));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.tier(2, true, 0, 1.0, 1_000_000));
        // Neighbor untouched.
        assertTrue(Double.isNaN(b.bestBid()));
    }

    @Test
    void nanAndZeroTiersNeverWinASweep() {
        // AggregatedBook convention: "NaN never wins". A NaN-priced or
        // zero/NaN-size tier is skipped, not swept.
        FxTierBook b = new FxTierBook(2, 2);
        b.tier(0, false, 0, Double.NaN, 5_000_000);          // pulled via NaN price
        b.tierCount(0, false, 1);
        b.tier(1, false, 0, 1.08505, 5_000_000);
        b.tierCount(1, false, 1);
        assertEquals(5_000_000 * 1.08505, b.sweepBuyCost(5_000_000), 1e-4);
        double[] plan = new double[2];
        b.sweepPlan(true, 1_000_000, plan);
        assertEquals(0, plan[0], 1e-12, "the withdrawn LP must get no quantity");
        assertEquals(1_000_000, plan[1], 1e-12);
        // NaN size behaves as absent too.
        b.tier(0, false, 0, 1.08000, Double.NaN);
        b.tierCount(0, false, 1);
        assertEquals(5_000_000 * 1.08505, b.sweepBuyCost(5_000_000), 1e-4);
        // Zero SIZE is absent...
        b.tier(0, false, 0, 1.08000, 0);
        b.tierCount(0, false, 1);
        assertEquals(5_000_000 * 1.08505, b.sweepBuyCost(5_000_000), 1e-4);
        // ...and zero PRICE (the array default — e.g. a decoded empty price
        // field) must never win a buy sweep as an infinitely cheap ask.
        b.tier(0, false, 0, 0.0, 5_000_000);
        b.tierCount(0, false, 1);
        assertEquals(5_000_000 * 1.08505, b.sweepBuyCost(5_000_000), 1e-4);
    }

    @Test
    void topOfBookUsesTheFrontierNotBlindTierZero() {
        // A malformed tier 0 (NaN/zero) must not mask a live deeper quote:
        // bestBid/bestAsk follow the same frontier rule as sweeps, so the
        // composite mid can never go NaN while the book is actually priced.
        FxTierBook b = new FxTierBook(1, 2);
        b.tier(0, true, 0, Double.NaN, 1_000_000);
        b.tier(0, true, 1, 1.08498, 5_000_000);
        b.tierCount(0, true, 2);
        b.tier(0, false, 0, 0.0, 1_000_000);
        b.tier(0, false, 1, 1.08504, 5_000_000);
        b.tierCount(0, false, 2);
        assertEquals(1.08498, b.bestBid(), 1e-12);
        assertEquals(1.08504, b.bestAsk(), 1e-12);
    }

    @Test
    void zeroClipIsNotAQuoteRequest() {
        FxTierBook b = book();
        assertTrue(Double.isNaN(b.fullAmountPrice(0, true, 0)));
        assertTrue(Double.isNaN(b.fullAmountPrice(0, true, -5)));
        assertTrue(Double.isNaN(b.bestFullAmountAsk(0)));
    }

    @Test
    void perLpFullAmountPriceIsThePublicRoutingPrimitive() {
        FxTierBook b = book();
        assertEquals(1.08502, b.fullAmountPrice(0, true, 1_000_000), 1e-12);
        assertEquals(1.08504, b.fullAmountPrice(0, true, 4_000_000), 1e-12);
        assertTrue(Double.isNaN(b.fullAmountPrice(2, true, 1_000_000)));
        assertTrue(Double.isNaN(b.fullAmountPrice(0, true, 50_000_000)));
    }

    @Test
    void updatesAndSweepsAreAllocationFree() {
        FxTierBook b = book();
        double[] plan = new double[3];
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += step(b, plan, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(b, plan, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "tier book allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(FxTierBook b, double[] plan, int i) {
        int lp = i % 2;
        double px = 1.08500 + (i % 7) * 1e-5;
        b.tier(lp, false, 0, px + 2e-5, 1_000_000 + (i % 3) * 500_000);
        b.tier(lp, false, 1, px + 4e-5, 5_000_000);
        b.tierCount(lp, false, 2);
        b.tier(lp, true, 0, px, 1_000_000);
        b.tier(lp, true, 1, px - 2e-5, 5_000_000);
        b.tierCount(lp, true, 2);
        return b.sweepPlan(true, 3_000_000, plan) + b.bestFullAmountBid(2_000_000);
    }
}
