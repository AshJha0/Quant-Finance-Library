package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 2 of the quant model layer: vol seasonality, trade classification, fill probability. */
class QuantModels2Test {

    // ------------------------------------------------------------------
    // VolatilityCurve — intraday vol seasonality + the regime signal
    // ------------------------------------------------------------------

    @Test
    void volCurveLearnsTheUShapeAcrossDays() {
        VolatilityCurve vc = new VolatilityCurve(3, 0.5);
        // Day 0: wild open (2e-4), calm middle (5e-5), busy close (1.5e-4).
        vc.onVol(0, 2e-4);
        vc.onVol(1, 5e-5);
        vc.onVol(2, 1.5e-4);
        vc.rollDay();
        assertEquals(2e-4, vc.baseline(0), 1e-12, "first session seeds");
        // Day 1: open runs 4e-4 -> EWMA folds at 0.5.
        vc.onVol(0, 4e-4);
        vc.rollDay();
        assertEquals(3e-4, vc.baseline(0), 1e-12, "2e-4 + 0.5*(4e-4 - 2e-4)");
        assertEquals(5e-5, vc.baseline(1), 1e-12, "no obs -> unchanged");
    }

    @Test
    void regimeIsTimeOfDayAwareNotAbsolute() {
        VolatilityCurve vc = new VolatilityCurve(2, 0.5);
        vc.seedBaseline(new double[]{2e-4, 5e-5});     // wild open, calm lunch
        // The SAME absolute vol reading is calm at the open, extreme at lunch.
        assertEquals(0.0, vc.regime(0, 2e-4), 1e-12, "normal for the open");
        assertEquals(1.0, vc.regime(1, 2e-4), 1e-12, "4x lunch baseline: clamped extreme");
        // Halfway elevated: 7.5e-5 on a 5e-5 baseline = +50% -> 0.5.
        assertEquals(0.5, vc.regime(1, 7.5e-5), 1e-12);
        // Neutral on garbage or before learning.
        assertEquals(0, vc.regime(0, Double.NaN), 1e-12);
        assertEquals(0, new VolatilityCurve(2, 0.5).regime(0, 1e-4), 1e-12);
    }

    @Test
    void aBucketFirstObservedLaterSeedsInsteadOfRampingFromZero() {
        // Regression: the feed starts mid-session on day 1, so bucket 1 is
        // never observed. Seeding is per bucket — day 2's first observation
        // must SEED bucket 1, not EWMA-fold against a baseline of 0 (which
        // would leave regime() falsely reading "extreme" there for weeks).
        VolatilityCurve vc = new VolatilityCurve(2, 0.1);
        vc.onVol(0, 2e-4);
        vc.rollDay();                                       // bucket 1 unseen on day 1
        vc.onVol(1, 5e-5);
        vc.rollDay();
        assertEquals(5e-5, vc.baseline(1), 1e-15, "seeded from its own first day");
        assertEquals(0.0, vc.regime(1, 5e-5), 1e-12, "normal vol reads as calm, not extreme");
    }

    @Test
    void volCurveIgnoresNonFiniteReadings() {
        VolatilityCurve vc = new VolatilityCurve(1, 0.5);
        vc.onVol(0, 1e-4);
        vc.onVol(0, Double.NaN);
        vc.onVol(0, Double.POSITIVE_INFINITY);
        vc.onVol(0, -1);
        vc.rollDay();
        assertEquals(1e-4, vc.baseline(0), 1e-12, "only the clean reading counts");
    }

    // ------------------------------------------------------------------
    // TradeClassifier — Lee-Ready
    // ------------------------------------------------------------------

    @Test
    void quoteRuleClassifiesAtAndThroughTheTouch() {
        TradeClassifier tc = new TradeClassifier();
        tc.onQuote(100.00, 100.02);
        assertEquals(TradeClassifier.BUY, tc.classify(100.02), "at the ask = lifted");
        assertEquals(TradeClassifier.BUY, tc.classify(100.05), "through the ask");
        assertEquals(TradeClassifier.SELL, tc.classify(100.00), "at the bid = hit");
        assertEquals(TradeClassifier.SELL, tc.classify(99.95), "through the bid");
        // Inside the spread: mid leans.
        assertEquals(TradeClassifier.BUY, tc.classify(100.015), "above mid");
        assertEquals(TradeClassifier.SELL, tc.classify(100.005), "below mid");
    }

    @Test
    void tickTestResolvesMidpointTrades() {
        TradeClassifier tc = new TradeClassifier();
        tc.onQuote(100.00, 100.02);
        assertEquals(TradeClassifier.SELL, tc.classify(100.005));   // below mid
        // Exactly at the mid: uptick from 100.005 -> BUY.
        assertEquals(TradeClassifier.BUY, tc.classify(100.01));
        // At the mid again, same price (zero-tick): repeats the last side.
        assertEquals(TradeClassifier.BUY, tc.classify(100.01));
    }

    @Test
    void unknownWithoutQuotesOrHistoryAndOnGarbage() {
        TradeClassifier tc = new TradeClassifier();
        assertEquals(TradeClassifier.UNKNOWN, tc.classify(100.0), "no quote, no history");
        assertEquals(TradeClassifier.UNKNOWN, tc.classify(Double.NaN));
        assertEquals(TradeClassifier.UNKNOWN, tc.classify(-5));
        // History accumulates even quote-less: tick test kicks in.
        assertEquals(TradeClassifier.BUY, tc.classify(100.5), "uptick from 100.0");
    }

    @Test
    void isBuyAggressorFallsBackToTheLastKnownSide() {
        TradeClassifier tc = new TradeClassifier();
        tc.onQuote(1.08500, 1.08502);
        assertTrue(tc.isBuyAggressor(1.08502));
        // A zero-tick mid print maps to the remembered side.
        tc.onQuote(Double.NaN, Double.NaN);
        assertTrue(tc.isBuyAggressor(1.08502), "unknown -> last known (buy)");
    }

    // ------------------------------------------------------------------
    // FillProbabilityModel — touch × queue
    // ------------------------------------------------------------------

    @Test
    void touchProbabilityBehavesLikeABarrierHit() {
        double vol = 1e-4;                 // per √second
        double price = 100;
        // At/through the level: certain.
        assertEquals(1.0, FillProbabilityModel.touchProbability(0, vol, 60, price), 1e-12);
        assertEquals(1.0, FillProbabilityModel.touchProbability(-1, vol, 60, price), 1e-12);
        // Farther = less likely; longer horizon = more likely.
        double near = FillProbabilityModel.touchProbability(0.01, vol, 60, price);
        double far = FillProbabilityModel.touchProbability(0.05, vol, 60, price);
        double farLonger = FillProbabilityModel.touchProbability(0.05, vol, 600, price);
        assertTrue(near > far, "distance decreases touch probability");
        assertTrue(farLonger > far, "time increases touch probability");
        assertTrue(near > 0 && near < 1);
        // One sigma of travel: 2*Phi(-1) ~ 0.3173.
        double oneSigma = price * vol * Math.sqrt(60);
        assertEquals(0.3173, FillProbabilityModel.touchProbability(
                oneSigma, vol, 60, price), 1e-3);
        // Dead market never arrives.
        assertEquals(0, FillProbabilityModel.touchProbability(0.01, 0, 60, price), 1e-12);
        assertEquals(0, FillProbabilityModel.touchProbability(0.01, vol, 0, price), 1e-12);
        assertEquals(0, FillProbabilityModel.touchProbability(0.01, Double.NaN, 60, price), 1e-12);
    }

    @Test
    void passiveFillComposesTouchAndQueue() {
        double vol = 1e-4;
        double price = 100;
        // At the touch with an empty queue and huge expected volume: ~1.
        double best = FillProbabilityModel.passiveFillProbability(
                0, vol, 60, price, 0, 100, 1_000_000);
        assertTrue(best > 0.99);
        // Same level, deep queue: the queue term dominates.
        double queued = FillProbabilityModel.passiveFillProbability(
                0, vol, 60, price, 50_000, 100, 10_000);
        assertTrue(queued < 0.01);
        // Far level (0.50 = ~6.5 sigma of 60s travel), empty queue: the
        // touch term dominates and kills the probability.
        double far = FillProbabilityModel.passiveFillProbability(
                0.50, vol, 60, price, 0, 100, 1_000_000);
        assertTrue(far < 0.01, "6.5 sigma away is a non-fill: " + far);
        // Composition never exceeds either factor.
        double both = FillProbabilityModel.passiveFillProbability(
                0.01, vol, 60, price, 5_000, 100, 10_000);
        assertTrue(both <= FillProbabilityModel.touchProbability(0.01, vol, 60, price));
        assertTrue(both <= QueueModel.fillProbability(5_000, 100, 10_000));
    }

    // ------------------------------------------------------------------
    // Validation + allocation
    // ------------------------------------------------------------------

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new VolatilityCurve(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new VolatilityCurve(4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VolatilityCurve(2, 0.5).seedBaseline(new double[3]));
    }

    @Test
    void streamingUpdatesAreAllocationFree() {
        VolatilityCurve vc = new VolatilityCurve(78, 0.1)
                .seedBaseline(java.util.stream.IntStream.range(0, 78)
                        .mapToDouble(i -> 1e-4).toArray());
        TradeClassifier tc = new TradeClassifier();
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {            // warm-up
            blackhole += step(vc, tc, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(vc, tc, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "round-2 models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(VolatilityCurve vc, TradeClassifier tc, int i) {
        int b = i % 78;
        vc.onVol(b, 1e-4 + (i % 7) * 1e-5);
        double px = 100 + (i % 9) * 0.005;
        tc.onQuote(px - 0.01, px + 0.01);
        int c = tc.classify(px + ((i & 1) == 0 ? 0.01 : -0.01));
        return vc.regime(b, 1.5e-4) + c
                + FillProbabilityModel.passiveFillProbability(
                        0.02, 1e-4, 60, px, 1_000 + (i % 500), 100, 5_000);
    }
}
