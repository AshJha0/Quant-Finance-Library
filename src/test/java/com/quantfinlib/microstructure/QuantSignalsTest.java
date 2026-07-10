package com.quantfinlib.microstructure;

import com.quantfinlib.volatility.HarRv;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** VPIN toxicity, OU mean reversion, HAR-RV forecasting, bar-only liquidity. */
class QuantSignalsTest {

    // ------------------------------------------------------------------
    // VPIN
    // ------------------------------------------------------------------

    @Test
    void vpinReadsOneSidedFlowAsToxicAndBalancedFlowAsCalm() {
        Vpin toxic = new Vpin(1_000, 5);
        assertTrue(Double.isNaN(toxic.vpin()), "no buckets yet: NaN, not fake calm");
        for (int i = 0; i < 5; i++) {
            toxic.onTrade(1_000, true);            // relentless one-way buying
        }
        assertTrue(toxic.ready());
        assertEquals(1.0, toxic.vpin(), 1e-12, "pure one-sided flow: maximum toxicity");

        Vpin calm = new Vpin(1_000, 5);
        for (int i = 0; i < 10; i++) {
            calm.onTrade(500, i % 2 == 0);         // perfectly two-way
        }
        assertEquals(0.0, calm.vpin(), 1e-12, "balanced flow: zero toxicity");

        // A block trade SPLITS across buckets — volume time, not clock time.
        Vpin split = new Vpin(1_000, 5);
        split.onTrade(2_500, true);                // 2 full buy buckets + 500
        split.onTrade(500, false);                 // completes bucket 3 balanced
        assertEquals(3, split.bucketsCompleted());
        assertEquals((1.0 + 1.0 + 0.0) / 3, split.vpin(), 1e-12);

        // Regime shift: toxicity RISES as informed flow displaces noise.
        Vpin regime = new Vpin(1_000, 4);
        for (int i = 0; i < 8; i++) {
            regime.onTrade(500, i % 2 == 0);
        }
        double before = regime.vpin();
        for (int i = 0; i < 4; i++) {
            regime.onTrade(1_000, true);
        }
        assertTrue(regime.vpin() > before + 0.9,
                "the window now holds only informed flow: " + regime.vpin());

        assertThrows(IllegalArgumentException.class, () -> new Vpin(0, 5));
        assertThrows(IllegalArgumentException.class, () -> calm.onTrade(0, true));
    }

    @Test
    void vpinEvictsExactlyTheOldestBucketAndSurvivesBlockTrades() {
        // Window 3 holding {1, 0, 0}: rolling in one more balanced bucket
        // must evict exactly the toxic 1.0 — not average a stale slot.
        Vpin vpin = new Vpin(100, 3);
        vpin.onTrade(100, true);                       // bucket 1: imbalance 1
        for (int i = 0; i < 2; i++) {
            vpin.onTrade(50, true);                    // buckets 2, 3: balanced
            vpin.onTrade(50, false);
        }
        assertEquals(1.0 / 3, vpin.vpin(), 1e-12, "window {1, 0, 0}");
        vpin.onTrade(50, true);
        vpin.onTrade(50, false);
        assertEquals(0.0, vpin.vpin(), 1e-12,
                "the oldest (toxic) bucket was the one evicted");

        // A block trade of ANY size is O(window), never O(size/bucket):
        // this returns instantly and reads maximum toxicity.
        Vpin block = new Vpin(1_000, 4);
        block.onTrade(Long.MAX_VALUE - 1, true);
        assertTrue(block.ready());
        assertEquals(1.0, block.vpin(), 1e-12, "one-sided by definition");
    }

    // ------------------------------------------------------------------
    // Ornstein-Uhlenbeck
    // ------------------------------------------------------------------

    @Test
    void ouRecoversPlantedDynamicsAndRefusesARandomWalk() {
        // Exact discretization of OU(kappa=5, theta=2, sigma=0.5) daily.
        double kappa = 5;
        double theta = 2;
        double sigma = 0.5;
        double dt = 1.0 / 252;
        double b = Math.exp(-kappa * dt);
        double sdE = Math.sqrt(sigma * sigma * (1 - b * b) / (2 * kappa));
        Random rnd = new Random(42);
        double[] x = new double[5_000];
        x[0] = theta;
        for (int t = 1; t < x.length; t++) {
            x[t] = theta + (x[t - 1] - theta) * b + sdE * rnd.nextGaussian();
        }
        OrnsteinUhlenbeck.Params p = OrnsteinUhlenbeck.fit(x, dt);
        assertEquals(kappa, p.kappa(), 2.0, "mean-reversion speed: " + p.kappa());
        assertEquals(theta, p.theta(), 0.05, "long-run mean: " + p.theta());
        assertEquals(sigma, p.sigma(), 0.05, "diffusion vol: " + p.sigma());
        assertEquals(Math.log(2) / p.kappa(), p.halfLife(), 1e-12,
                "half-life IS ln2/kappa, by definition");

        // The z-score in stationary units, exactly.
        assertEquals(0, p.zScore(p.theta()), 1e-12);
        assertEquals(1, p.zScore(p.theta() + p.stationaryStdev()), 1e-12);
        assertTrue(Double.isFinite(OrnsteinUhlenbeck.lastZScore(x, dt)));

        // A trending series has NO mean reversion: refuse, don't report
        // an infinite half-life as if it were a holding period.
        double[] trend = new double[100];
        for (int t = 0; t < trend.length; t++) {
            trend[t] = t;
        }
        assertThrows(IllegalArgumentException.class,
                () -> OrnsteinUhlenbeck.fit(trend, dt),
                "fitting OU to a random walk is how pairs desks die");
        assertThrows(IllegalArgumentException.class,
                () -> OrnsteinUhlenbeck.fit(new double[]{1, 2}, dt));
    }

    // ------------------------------------------------------------------
    // HAR-RV
    // ------------------------------------------------------------------

    @Test
    void harRecoverssPlantedCoefficientsAndForecastsSanely() {
        // Plant the HAR process itself: c=0.02, bd=0.4, bw=0.3, bm=0.2
        // (persistence 0.9, stationary mean 0.2), small uniform noise.
        Random rnd = new Random(9);
        int n = 1_500;
        double[] rv = new double[n];
        java.util.Arrays.fill(rv, 0, 22, 0.2);
        for (int t = 21; t < n - 1; t++) {
            double w = com.quantfinlib.util.MathUtils.mean(rv, t - 4, t + 1);
            double m = com.quantfinlib.util.MathUtils.mean(rv, t - 21, t + 1);
            rv[t + 1] = Math.max(1e-6, 0.02 + 0.4 * rv[t] + 0.3 * w + 0.2 * m
                    + 0.005 * (2 * rnd.nextDouble() - 1));
        }
        HarRv.Params p = HarRv.fit(rv);
        assertEquals(0.4, p.betaDaily(), 0.08, "daily horizon: " + p.betaDaily());
        assertEquals(0.3, p.betaWeekly(), 0.20, "weekly horizon: " + p.betaWeekly());
        assertEquals(0.2, p.betaMonthly(), 0.20, "monthly horizon: " + p.betaMonthly());
        assertEquals(0.02, p.intercept(), 0.03);

        double forecast = HarRv.forecast(rv, p);
        assertTrue(forecast > 0.1 && forecast < 0.3,
                "the forecast sits near the stationary level: " + forecast);

        // The floor: a pathological parameter set cannot forecast a
        // negative variance.
        assertEquals(0, HarRv.forecast(rv, new HarRv.Params(-10, 0, 0, 0)), 0.0);

        // Window alignment pinned EXACTLY where the horizons disagree:
        // 21 flat days then a spiked last day. d = 0.5, w = (4·0.1+0.5)/5
        // = 0.18, m = (21·0.1+0.5)/22; a d/w swap or off-by-one window
        // moves this a lot.
        double[] spiked = new double[22];
        java.util.Arrays.fill(spiked, 0.1);
        spiked[21] = 0.5;
        HarRv.Params hand = new HarRv.Params(0.01, 0.5, 0.3, 0.1);
        double expected = 0.01 + 0.5 * 0.5 + 0.3 * 0.18 + 0.1 * (21 * 0.1 + 0.5) / 22;
        assertEquals(expected, HarRv.forecast(spiked, hand), 1e-12,
                "c + bd·d + bw·w + bm·m, by hand");

        assertThrows(IllegalArgumentException.class, () -> HarRv.fit(new double[59]));
        double[] bad = rv.clone();
        bad[100] = -0.1;
        assertThrows(IllegalArgumentException.class, () -> HarRv.fit(bad));
    }

    // ------------------------------------------------------------------
    // Bar-only liquidity estimators
    // ------------------------------------------------------------------

    @Test
    void rollSpreadRecoversThePlantedBounceAndRefusesTrends() {
        // Roll's model exactly: mid 100, iid +/- spread/2 bounce.
        double spread = 0.10;
        Random rnd = new Random(11);
        double[] prices = new double[4_000];
        for (int i = 0; i < prices.length; i++) {
            prices[i] = 100 + (rnd.nextBoolean() ? 1 : -1) * spread / 2;
        }
        assertEquals(spread, LiquidityMeasures.rollSpread(prices), 0.02,
                "the bounce autocovariance gives the spread back");

        // A trending series has no bounce signature: NaN, not "zero spread".
        double[] trend = new double[100];
        for (int i = 0; i < trend.length; i++) {
            trend[i] = 100 + i;
        }
        assertTrue(Double.isNaN(LiquidityMeasures.rollSpread(trend)),
                "an undefined estimator says so");
        assertThrows(IllegalArgumentException.class,
                () -> LiquidityMeasures.rollSpread(new double[]{100, 101}));
    }

    @Test
    void corwinSchultzAndAmihudMatchHandArithmetic() {
        // Two identical 1%-range days: the estimate lands ~ the range
        // (hand-computed: alpha = 0.0099504, spread = 0.0099502).
        assertEquals(0.00995, LiquidityMeasures.corwinSchultzSpread(101, 100, 101, 100),
                2e-4, "two clean 1%-range days imply ~1% spread");
        // Zero-range days: zero spread, exactly.
        assertEquals(0, LiquidityMeasures.corwinSchultzSpread(100, 100, 100, 100), 0.0);
        // A gapping market (disjoint ranges) drives the estimator
        // negative: clamps to 0, as the literature does.
        assertEquals(0, LiquidityMeasures.corwinSchultzSpread(101, 100, 106, 105), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidityMeasures.corwinSchultzSpread(100, 101, 100, 99));

        // Amihud: |r|/volume, averaged — exact.
        assertEquals(1e-8, LiquidityMeasures.amihudIlliquidity(
                new double[]{0.01, -0.02, 0.03},
                new double[]{1e6, 2e6, 3e6}), 1e-20);
        assertThrows(IllegalArgumentException.class,
                () -> LiquidityMeasures.amihudIlliquidity(
                        new double[]{0.01}, new double[]{0}),
                "zero volume is a data gap, not infinite illiquidity");
        // The less liquid name ranks as such.
        double liquid = LiquidityMeasures.amihudIlliquidity(
                new double[]{0.01, 0.01}, new double[]{5e7, 5e7});
        double illiquid = LiquidityMeasures.amihudIlliquidity(
                new double[]{0.03, 0.03}, new double[]{2e6, 2e6});
        assertTrue(illiquid > 10 * liquid, "the ranking survives");
    }
}
