package com.quantfinlib.risk;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VarBacktestTest {

    private static double[] gaussianReturns(int n, double vol, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = vol * rnd.nextGaussian();
        }
        return r;
    }

    @Test
    void wellCalibratedVarPassesAllTests() {
        double[] returns = gaussianReturns(2_000, 0.01, 42);
        // True 95% VaR of N(0, 1%) is 1.645%.
        VarBacktest.VarBacktestResult r = VarBacktest.test(returns, 0.01645, 0.95);

        assertEquals(2_000, r.observations());
        assertEquals(100, r.expectedExceptions(), 1e-9);
        assertTrue(Math.abs(r.exceptions() - 100) < 30, "exceptions=" + r.exceptions());
        assertTrue(r.calibrated(0.05), "kupiec p=" + r.kupiecPValue());
        assertTrue(r.independent(0.05), "independence p=" + r.independencePValue());
        assertTrue(r.passes(0.05), "cc p=" + r.conditionalCoveragePValue());
        // Exact chi-square(2) survival for the joint statistic.
        assertEquals(Math.exp(-r.conditionalCoverageStatistic() / 2),
                r.conditionalCoveragePValue(), 1e-12);
    }

    @Test
    void underestimatedVarIsRejected() {
        double[] returns = gaussianReturns(2_000, 0.01, 7);
        // Claiming 95% coverage with roughly a 68% quantile: far too many exceptions.
        VarBacktest.VarBacktestResult r = VarBacktest.test(returns, 0.010, 0.95);
        assertTrue(r.exceptions() > 200);
        assertFalse(r.calibrated(0.01), "kupiec p=" + r.kupiecPValue());
        assertFalse(r.passes(0.01));
    }

    @Test
    void overestimatedVarIsAlsoRejected() {
        // Kupiec is two-sided: a VaR so conservative it never breaks also fails.
        double[] returns = gaussianReturns(2_000, 0.01, 9);
        VarBacktest.VarBacktestResult r = VarBacktest.test(returns, 0.05, 0.95);
        assertEquals(0, r.exceptions());
        assertFalse(r.calibrated(0.01), "kupiec p=" + r.kupiecPValue());
    }

    @Test
    void clusteredExceptionsFailIndependence() {
        // Two series with identical exception COUNTS (25/500) but different timing.
        int n = 500;
        double[] clustered = new double[n];
        double[] scattered = new double[n];
        for (int i = 0; i < 25; i++) {
            clustered[100 + i] = -0.05;       // one 25-day crisis
            scattered[i * 20] = -0.05;        // evenly spread
        }
        VarBacktest.VarBacktestResult bad = VarBacktest.test(clustered, 0.02, 0.95);
        VarBacktest.VarBacktestResult good = VarBacktest.test(scattered, 0.02, 0.95);

        assertEquals(bad.exceptions(), good.exceptions());
        assertEquals(bad.kupiecStatistic(), good.kupiecStatistic(), 1e-9);   // same rate
        assertFalse(bad.independent(0.01),
                "clustered independence p=" + bad.independencePValue());
        assertTrue(good.independent(0.05),
                "scattered independence p=" + good.independencePValue());
        assertFalse(bad.passes(0.01));   // conditional coverage catches the clustering
    }

    @Test
    void perPeriodForecastsAreSupported() {
        // GARCH-style varying forecasts: scale VaR with a known vol path.
        SplittableRandom rnd = new SplittableRandom(11);
        int n = 1_000;
        double[] returns = new double[n];
        double[] var = new double[n];
        for (int i = 0; i < n; i++) {
            double vol = i < 500 ? 0.005 : 0.02;   // regime shift
            returns[i] = vol * rnd.nextGaussian();
            var[i] = 1.645 * vol;                  // correctly scaled per period
        }
        VarBacktest.VarBacktestResult adaptive = VarBacktest.test(returns, var, 0.95);
        assertTrue(adaptive.passes(0.05), "adaptive cc p=" + adaptive.conditionalCoveragePValue());

        // A constant VaR calibrated to the calm regime fails on the same data.
        VarBacktest.VarBacktestResult constant = VarBacktest.test(returns, 1.645 * 0.005, 0.95);
        assertFalse(constant.passes(0.01));
    }
}
