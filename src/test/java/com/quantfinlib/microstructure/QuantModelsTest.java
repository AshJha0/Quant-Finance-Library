package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quant model layer feeding BenchmarkExecutor.MarketState. */
class QuantModelsTest {

    private static final long SEC = 1_000_000_000L;

    // ------------------------------------------------------------------
    // VolumeCurve — intraday volume prediction
    // ------------------------------------------------------------------

    @Test
    void volumeCurveLearnsAUShapeAndGivesACumulativeFraction() {
        VolumeCurve vc = new VolumeCurve(4, 0.5);
        // A U-shaped day: heavy open/close, light middle.
        double[] shape = {40, 10, 10, 40};
        vc.seedProfile(shape);
        // Cumulative fraction: after bucket 0 = 40/100 = 0.4.
        assertEquals(0.4, vc.expectedFractionElapsed(1, 0.0), 1e-9);
        assertEquals(0.5, vc.expectedFractionElapsed(2, 0.0), 1e-9);
        assertEquals(1.0, vc.expectedFractionElapsed(3, 1.0), 1e-9);
        // Half through bucket 0: 0.2.
        assertEquals(0.2, vc.expectedFractionElapsed(0, 0.5), 1e-9);
    }

    @Test
    void volumeCurveRescalesTodayToRealizedVolume() {
        VolumeCurve vc = new VolumeCurve(4, 0.5);
        vc.seedProfile(new double[]{40, 10, 10, 40});   // learned total 100
        // Today the open trades DOUBLE the expected 40 -> project a bigger day.
        vc.onVolume(0, 80);
        double projected = vc.projectedDayVolume(1, 0.0);
        // Expected-so-far = 40; realized 80; ratio 2; weight = 40/100 = 0.4.
        // scale = 1 + 0.4*(2-1) = 1.4 -> 140.
        assertEquals(140, projected, 1e-6);
        assertTrue(vc.expectedVolumeRemaining(1, 0.0) > 0);
    }

    @Test
    void volumeCurveDegradesToLinearWithoutAProfile() {
        VolumeCurve vc = new VolumeCurve(10, 0.5);
        assertEquals(0.5, vc.expectedFractionElapsed(5, 0.0), 1e-9);   // linear time
    }

    @Test
    void volumeCurveLearnsAcrossDays() {
        VolumeCurve vc = new VolumeCurve(2, 1.0);
        vc.onVolume(0, 30);
        vc.onVolume(1, 70);
        vc.rollDay();
        assertEquals(1, vc.daysLearned());
        assertEquals(30, vc.profileVolume(0), 1e-9);
        assertEquals(0, vc.realizedToday(), 1e-9);       // reset
    }

    // ------------------------------------------------------------------
    // QueuePositionEstimator — L2 queue position
    // ------------------------------------------------------------------

    @Test
    void queueJoinsAtTheBackAndTradesReduceAhead() {
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(5_000, 1_000);                 // 5,000 ahead, our 1,000 behind
        assertEquals(5_000, q.sharesAhead(), 1e-9);
        q.onTrade(2_000);                      // front consumed
        assertEquals(3_000, q.sharesAhead(), 1e-9);
        q.onTrade(10_000);                     // clamp at 0
        assertEquals(0, q.sharesAhead(), 1e-9);
    }

    @Test
    void queueCancelsAreAttributedProRata() {
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(8_000, 2_000);                  // 8,000 ahead of 10,000 total-others...
        // others ahead = 8,000, our 2,000; level = 10,000.
        // A cancel shrinks the level from 10,000 to 6,000: 4,000 removed from
        // the 8,000 others, pro-rata fraction-ahead = 8,000/8,000 = 1.0 here,
        // so all 4,000 came from ahead -> 4,000 ahead.
        q.onLevelResize(6_000);
        assertEquals(4_000, q.sharesAhead(), 1e-6);
    }

    @Test
    void queueAddsBehindDoNotChangeAhead() {
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(3_000, 1_000);
        q.onLevelResize(9_000);                // someone joined behind us
        assertEquals(3_000, q.sharesAhead(), 1e-9);
    }

    @Test
    void queueFillProbabilityRisesAsWeAdvance() {
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(50_000, 1_000);
        double back = q.fillProbability(10_000);
        q.onTrade(49_000);                     // near the front
        double front = q.fillProbability(10_000);
        assertTrue(front > back);
    }

    // ------------------------------------------------------------------
    // HiddenLiquidityDetector — iceberg / hidden liquidity
    // ------------------------------------------------------------------

    @Test
    void detectsAnIcebergWhenASinglePrintExceedsTheDisplay() {
        // The sound signature: ONE print larger than the displayed tip —
        // displayed size can't fill more than it shows, so the excess hit
        // hidden liquidity.
        HiddenLiquidityDetector d = new HiddenLiquidityDetector(4, 0.5);
        d.onDisplayed(2, 1_000);              // tip shows 1,000
        d.onExecution(2, 3_000);              // one 3,000 print on a 1,000 tip
        assertTrue(d.isIceberg(2));
        assertEquals(3.0, d.hiddenMultiplier(2), 1e-9);
        assertEquals(3_000, d.estimatedTrueDepth(2), 1e-9);
    }

    @Test
    void ordinaryFragmentedFlowIsNotFalseFlaggedAsAnIceberg() {
        // A busy level legitimately trades many times its instantaneous
        // display via ordinary adds — no single print exceeds the tip, so
        // it must NOT flag (the cumulative-run formulation would).
        HiddenLiquidityDetector d = new HiddenLiquidityDetector(4, 0.5);
        for (int i = 0; i < 10; i++) {
            d.onDisplayed(1, 5_000);
            d.onExecution(1, 3_000);          // each print < display
        }
        assertFalse(d.isIceberg(1));
        assertEquals(1.0, d.hiddenMultiplier(1), 1e-9);
        assertEquals(5_000, d.estimatedTrueDepth(1), 1e-9);
    }

    @Test
    void learnedIcebergMemoryPersistsAcrossAClear() {
        HiddenLiquidityDetector d = new HiddenLiquidityDetector(2, 0.5);
        d.onDisplayed(0, 1_000);
        d.onExecution(0, 1_500);              // one 1,500 print on a 1,000 tip
        assertTrue(d.isIceberg(0));
        long obs = d.refillObservations(0);
        d.onLevelCleared(0);                  // best moved away
        d.onDisplayed(0, 2_000);
        d.onExecution(0, 500);                // 500 < 2,000: no new event
        assertEquals(obs, d.refillObservations(0), "no spurious event after clear");
        // The learned behavior persists (a level that icebergs repeats).
        assertTrue(d.hiddenMultiplier(0) > 1.0, "learned iceberg memory is kept");
    }

    // ------------------------------------------------------------------
    // SpreadForecaster — spread prediction
    // ------------------------------------------------------------------

    @Test
    void spreadForecastBlendsBaselineAndRevertingDeviation() {
        SpreadForecaster sf = new SpreadForecaster(3, 0.5, SEC);
        // Baseline: tight midday, wide at the open/close.
        sf.seedBaseline(new double[]{0.05, 0.01, 0.05});
        // A spike at bucket 1 above its 0.01 baseline.
        sf.onSpread(1, 0.03, SEC);
        // Immediately after: forecast > baseline (deviation not yet reverted).
        assertTrue(sf.forecast(1, SEC) > 0.01);
        // One half-life later: deviation ~halved.
        double later = sf.forecast(1, SEC + SEC);
        assertTrue(later < sf.forecast(1, SEC) && later > 0.01);
        // Far future: reverts to baseline.
        assertEquals(0.01, sf.forecast(1, SEC + 100 * SEC), 1e-3);
    }

    @Test
    void spreadForecastKnowsTheCloseIsWideBeforeItArrives() {
        SpreadForecaster sf = new SpreadForecaster(3, 0.5, SEC);
        sf.seedBaseline(new double[]{0.05, 0.01, 0.06});
        // Sitting midday (tight), the forecast for the close bucket is
        // already wide — the algo can pre-emptively damp.
        assertTrue(sf.forecast(2, SEC) > sf.forecast(1, SEC));
    }

    @Test
    void unseededDay0ForecastReturnsTheLiveSpreadNotZero() {
        // Before any rollDay/seed there is no baseline; the forecast must
        // degrade to the last observed spread, never a silent 0.
        SpreadForecaster sf = new SpreadForecaster(3, 0.1, SEC);
        assertTrue(Double.isNaN(sf.forecast(1, SEC)), "NaN before any observation");
        sf.onSpread(1, 0.02, SEC);
        assertEquals(0.02, sf.forecast(1, SEC), 1e-12, "day 0 = live spread, not 0");
    }

    @Test
    void baselineIsLearnedAcrossDaysViaRollDay() {
        SpreadForecaster sf = new SpreadForecaster(2, 0.5, SEC);
        // Day 0: bucket 0 averages 0.05, bucket 1 averages 0.01.
        sf.onSpread(0, 0.04, SEC);
        sf.onSpread(0, 0.06, SEC + 1);
        sf.onSpread(1, 0.01, SEC + 2);
        sf.rollDay();
        assertEquals(0.05, sf.baseline(0), 1e-9, "first session seeds the baseline");
        assertEquals(0.01, sf.baseline(1), 1e-9);
        // Day 1: bucket 0 now averages 0.09 -> EWMA folds at dayAlpha 0.5.
        sf.onSpread(0, 0.09, 2 * SEC);
        sf.rollDay();
        assertEquals(0.07, sf.baseline(0), 1e-9, "0.05 + 0.5*(0.09-0.05)");
        assertEquals(0.01, sf.baseline(1), 1e-9, "no obs -> baseline unchanged");
    }

    @Test
    void aBucketWithoutABaselineInjectsNoDeviation() {
        // Regression: bucket 1 was never observed on day 1, so its baseline
        // is 0. Day 2's first print there must SEED it at rollDay and must
        // NOT enter the shared deviation as a spread-sized "shock" that
        // would contaminate forecasts at every bucket.
        SpreadForecaster sf = new SpreadForecaster(2, 0.5, SEC);
        sf.onSpread(0, 0.02, SEC);
        sf.rollDay();                                       // bucket 1 unseen on day 1
        sf.onSpread(1, 0.03, 2 * SEC);
        assertEquals(0, sf.currentDeviation(2 * SEC), 1e-12,
                "no baseline for the bucket -> nothing to deviate from");
        assertEquals(0.02, sf.forecast(0, 2 * SEC), 1e-12,
                "bucket 0's forecast is untouched by the unseeded bucket's print");
        sf.rollDay();
        assertEquals(0.03, sf.baseline(1), 1e-9, "seeded from its own first day");
    }

    @Test
    void infiniteSpreadDoesNotPoisonABucketBaseline() {
        SpreadForecaster sf = new SpreadForecaster(2, 0.5, SEC);
        sf.seedBaseline(new double[]{0.02, 0.02});
        sf.onSpread(0, Double.POSITIVE_INFINITY, SEC);   // rejected
        sf.onSpread(0, Double.NaN, SEC + 1);             // rejected
        assertTrue(Double.isFinite(sf.forecast(0, SEC + 2)));
        assertEquals(0.02, sf.forecast(0, SEC + 2), 1e-3);
    }

    // ------------------------------------------------------------------
    // QueuePositionEstimator — progress against the join baseline
    // ------------------------------------------------------------------

    @Test
    void queueProgressIsZeroAtJoinAndOneAtTheFront() {
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(4_000, 1_000);
        assertEquals(0.0, q.queueProgress(), 1e-9, "just joined = no progress");
        q.onTrade(2_000);
        assertEquals(0.5, q.queueProgress(), 1e-9, "halfway drained");
        q.onTrade(2_000);
        assertEquals(1.0, q.queueProgress(), 1e-9, "front of queue");
    }

    // ------------------------------------------------------------------
    // Cross-asset: FX scales and sessions through the identical models
    // ------------------------------------------------------------------

    @Test
    void allFourModelsWorkAtFxScalesAndSessions() {
        // Volume curve on a 24h FX day (288 five-minute buckets) with the
        // classic London/NY double hump — fractions still sum sanely.
        VolumeCurve vc = new VolumeCurve(288, 0.1);
        double[] fxShape = new double[288];
        for (int b = 0; b < 288; b++) {
            fxShape[b] = 1 + (b >= 96 && b < 132 ? 4 : 0)      // London open
                    + (b >= 156 && b < 192 ? 5 : 0);           // NY overlap
        }
        vc.seedProfile(fxShape);
        double atLondonClose = vc.expectedFractionElapsed(192, 0.0);
        assertTrue(atLondonClose > 0.6, "most FX volume trades by end of NY overlap");
        assertEquals(1.0, vc.expectedFractionElapsed(287, 1.0), 1e-9);

        // Queue position on an FX ECN: 25M ahead of a 5M clip at a level.
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(25_000_000, 5_000_000);
        q.onTrade(10_000_000);
        assertEquals(15_000_000, q.sharesAhead(), 1e-6);

        // Iceberg on an ECN level: 2M tip trading 7M and re-quoting.
        HiddenLiquidityDetector d = new HiddenLiquidityDetector(8, 0.2);
        d.onDisplayed(3, 2_000_000);
        d.onExecution(3, 7_000_000);
        assertTrue(d.isIceberg(3));
        assertEquals(3.5, d.hiddenMultiplier(3), 1e-9);
        assertEquals(7_000_000, d.estimatedTrueDepth(3), 1e-6);

        // Spread forecast in pips: rollover hours (thin) wide, London tight.
        SpreadForecaster sf = new SpreadForecaster(288, 0.1, 5 * SEC);
        double[] pips = new double[288];
        java.util.Arrays.fill(pips, 0.00002);                  // 0.2 pip normal
        for (int b = 252; b < 264; b++) {
            pips[b] = 0.00015;                                 // rollover: 1.5 pips
        }
        sf.seedBaseline(pips);
        assertTrue(sf.forecast(255, SEC) > sf.forecast(120, SEC),
                "the rollover window forecasts wide before it arrives");
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Test
    void modelsValidateInputs() {
        assertThrows(IllegalArgumentException.class, () -> new VolumeCurve(0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new VolumeCurve(4, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new QueuePositionEstimator().join(-1, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new HiddenLiquidityDetector(0));
        assertThrows(IllegalArgumentException.class,
                () -> new SpreadForecaster(4, 0.5, 0));
    }

    // ------------------------------------------------------------------
    // Zero allocation on the streaming updates
    // ------------------------------------------------------------------

    @Test
    void streamingUpdatesAreAllocationFree() {
        VolumeCurve vc = new VolumeCurve(78, 0.1).seedProfile(uShape(78));
        HiddenLiquidityDetector d = new HiddenLiquidityDetector(64, 0.2);
        SpreadForecaster sf = new SpreadForecaster(78, 0.1, 5 * SEC);
        QueuePositionEstimator q = new QueuePositionEstimator();
        q.join(10_000, 500);
        double blackhole = 0;
        for (int i = 0; i < 200_000; i++) {           // warm-up
            blackhole += step(vc, d, sf, q, i);
        }
        var mx = (com.sun.management.ThreadMXBean)
                java.lang.management.ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 500_000; i++) {
            blackhole += step(vc, d, sf, q, i);
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(allocated < 100_000, "quant models allocated " + allocated + " bytes");
        assertTrue(blackhole != 0);
    }

    private static double step(VolumeCurve vc, HiddenLiquidityDetector d,
                               SpreadForecaster sf, QueuePositionEstimator q, int i) {
        int b = i % 78;
        long t = SEC + (long) i * 1_000_000;
        vc.onVolume(b, 100 + (i % 50));
        int lvl = i & 63;
        d.onDisplayed(lvl, 1_000);
        d.onExecution(lvl, 500 + (i % 900));
        sf.onSpread(b, 0.01 + (i % 5) * 0.001, t);
        if ((i & 3) == 0) {
            q.onTrade(10);
        }
        return vc.expectedFractionElapsed(b, 0.5) + d.hiddenMultiplier(lvl)
                + sf.forecast(b, t) + q.sharesAhead();
    }

    private static double[] uShape(int n) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) {
            double x = (double) i / (n - 1);
            a[i] = 1 + 4 * (x - 0.5) * (x - 0.5);      // U
        }
        return a;
    }
}
