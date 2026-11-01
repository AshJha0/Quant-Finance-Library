package com.quantfinlib.ml;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegimeFilterAndValidationTest {

    private static double[] simulate(int n, int[] truth, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] r = new double[n];
        int state = 0;
        for (int t = 0; t < n; t++) {
            if (rnd.nextDouble() < 0.02) {
                state = 1 - state;
            }
            truth[t] = state;
            r[t] = (state == 0 ? 0.006 : 0.03) * rnd.nextGaussian();
        }
        return r;
    }

    private static double logPdf(double x, double m, double sd) {
        double d = (x - m) / sd;
        return -0.5 * d * d - Math.log(sd) - 0.5 * Math.log(2 * Math.PI);
    }

    /**
     * The filtered path equals an independent forward filter run with the
     * fitted parameters: it uses data up to t only. (The initial
     * distribution differs, so early points are skipped.)
     */
    @Test
    void filteredPathIsTheForwardFilter() {
        int[] truth = new int[2000];
        double[] r = simulate(2000, truth, 3);
        RegimeDetector.RegimeModel m = RegimeDetector.fit(r, 200);
        double[][] a = m.transitionCopy();
        double p1 = 0.5;
        for (int t = 0; t < r.length; t++) {
            double prior1 = t == 0 ? 0.5 : (1 - p1) * a[0][1] + p1 * a[1][1];
            double l0 = Math.log(1 - prior1) + logPdf(r[t], m.means(0), m.stdDevs(0));
            double l1 = Math.log(prior1) + logPdf(r[t], m.means(1), m.stdDevs(1));
            double mx = Math.max(l0, l1);
            p1 = Math.exp(l1 - mx) / (Math.exp(l0 - mx) + Math.exp(l1 - mx));
            if (t >= 50) {
                assertEquals(p1, m.filteredHighVolProbability(t), 1e-8, "t=" + t);
            }
        }
        assertEquals(m.currentProbabilities(1), m.filteredHighVolProbability(r.length - 1), 0);
        // The smoothed path looks ahead: it differs from the filtered one.
        double maxDiff = 0;
        for (int t = 0; t < r.length; t++) {
            maxDiff = Math.max(maxDiff,
                    Math.abs(m.smoothedHighVolProbability(t) - m.filteredHighVolProbability(t)));
        }
        assertTrue(maxDiff > 0.2);
    }

    @Test
    void restartsNeverLowerTheLikelihoodAndViterbiLabelsRegimes() {
        int[] truth = new int[3000];
        double[] r = simulate(3000, truth, 8);
        RegimeDetector.RegimeModel one = RegimeDetector.fit(r, 300, 1, 1);
        RegimeDetector.RegimeModel five = RegimeDetector.fit(r, 300, 5, 1);
        assertTrue(five.logLikelihood() >= one.logLikelihood() - 1e-9);
        int[] path = RegimeDetector.viterbi(r, five);
        int hits = 0;
        for (int t = 0; t < r.length; t++) {
            if (path[t] == truth[t]) {
                hits++;
            }
        }
        assertTrue(hits > 0.9 * r.length, "viterbi accuracy " + hits);
    }

    @Test
    void nonFiniteReturnsAreRejected() {
        double[] r = new double[200];
        java.util.Arrays.fill(r, 0.01);
        r[50] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> RegimeDetector.fit(r, 10));
    }

    @Test
    void extremeOutlierDoesNotUnderflowTheFilter() {
        int[] truth = new int[500];
        double[] r = simulate(500, truth, 5);
        r[250] = 5.0;                          // 160 sigma in the calm state
        RegimeDetector.RegimeModel m = RegimeDetector.fit(r, 50);
        assertTrue(Double.isFinite(m.logLikelihood()));
    }

    /** Validation reports out-of-sample errors on the same dates for all three forecasters. */
    @Test
    void validationComparesAgainstBaselines() {
        SplittableRandom rnd = new SplittableRandom(2);
        int n = 1500;
        double[] r = new double[n];
        double h = 1e-4;
        for (int t = 0; t < n; t++) {
            r[t] = Math.sqrt(h) * rnd.nextGaussian();
            h = 2e-6 + 0.08 * r[t] * r[t] + 0.9 * h;
        }
        VolatilityForecaster.Validation v = VolatilityForecaster.weekly().validate(r, 0.6);
        assertEquals(n - (int) Math.floor(0.6 * n) - 5 + 1, v.forecasts());
        assertTrue(v.rmseModel() > 0 && v.rmseEwma() > 0 && v.rmseHar() > 0);
        // On GARCH data the EWMA is close to the true conditional variance:
        // a fair comparison has the baselines within a factor of the model.
        assertTrue(v.rmseEwma() < 2 * v.rmseModel());
    }

    @Test
    void sweepSlopeIsExplicit() {
        assertEquals(0.5, MarketImpactPredictor.sweepProbability(100, 100, 7.0), 1e-15);
        assertEquals(1 / (1 + Math.exp(-2.0)), MarketImpactPredictor.sweepProbability(150, 100, 4.0),
                1e-15);
    }
}
