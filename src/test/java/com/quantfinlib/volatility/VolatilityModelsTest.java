package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolatilityModelsTest {

    /** Simulates a GARCH(1,1) return series with known parameters. */
    private static double[] simulateGarch(double omega, double alpha, double beta,
                                          int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] r = new double[n];
        double h = omega / (1 - alpha - beta);
        for (int t = 0; t < n; t++) {
            r[t] = Math.sqrt(h) * rnd.nextGaussian();
            h = omega + alpha * r[t] * r[t] + beta * h;
        }
        return r;
    }

    @Test
    void ewmaReactsToVolatilityRegimeShift() {
        SplittableRandom rnd = new SplittableRandom(5);
        double[] r = new double[600];
        for (int i = 0; i < 600; i++) {
            double vol = i < 500 ? 0.005 : 0.03;   // regime shift at t=500
            r[i] = vol * rnd.nextGaussian();
        }
        EwmaVolatility ewma = EwmaVolatility.riskMetrics();
        double calm = ewma.latestVol(java.util.Arrays.copyOfRange(r, 0, 500));
        double stressed = ewma.latestVol(r);
        assertTrue(stressed > 2 * calm, "stressed " + stressed + " vs calm " + calm);
        // Variance series aligns with returns and stays positive.
        double[] h = ewma.variances(r);
        assertEquals(r.length, h.length);
        for (double v : h) {
            assertTrue(v > 0);
        }
        assertThrows(IllegalArgumentException.class, () -> new EwmaVolatility(1.5));
    }

    @Test
    void garchFitRecoversSimulatedParameters() {
        // True: alpha=0.08, beta=0.90, daily vol 1% (uncond var 1e-4).
        double uncond = 1e-4;
        double[] r = simulateGarch(uncond * 0.02, 0.08, 0.90, 4_000, 42);
        Garch11.Fit mle = Garch11.estimate(r, Innovation.GAUSSIAN);
        Garch11.Params fit = mle.params();

        assertTrue(fit.persistence() > 0.90 && fit.persistence() < 0.9995,
                "persistence=" + fit.persistence());
        // Within three standard errors of the truth (the grid search this
        // replaced needed +-0.06 on alpha and +-35% on the variance).
        assertEquals(0.08, fit.alpha(), 3 * mle.standardErrors()[1]);
        assertEquals(0.90, fit.beta(), 3 * mle.standardErrors()[2]);
        assertEquals(uncond, fit.unconditionalVariance(), uncond * 0.25);
        assertTrue(Double.isFinite(fit.logLikelihood()));
    }

    @Test
    void garchForecastMeanRevertsToUnconditional() {
        double[] r = simulateGarch(2e-6, 0.08, 0.90, 3_000, 7);
        Garch11.Params fit = Garch11.fit(r);
        double shortHorizon = Garch11.forecastVariance(r, fit, 1);
        double longHorizon = Garch11.forecastVariance(r, fit, 5_000);
        assertEquals(fit.unconditionalVariance(), longHorizon, fit.unconditionalVariance() * 0.01);
        assertTrue(shortHorizon > 0);
        // Conditional variances track squared-return clusters.
        double[] h = Garch11.conditionalVariances(r, fit);
        assertEquals(r.length, h.length);
    }
}
