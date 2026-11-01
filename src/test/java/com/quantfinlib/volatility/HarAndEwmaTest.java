package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarAndEwmaTest {

    private static double[] simulateHar(int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] rv = new double[n];
        java.util.Arrays.fill(rv, 0, 22, 1e-4);
        for (int t = 21; t < n - 1; t++) {
            double w = com.quantfinlib.util.MathUtils.mean(rv, t - 4, t + 1);
            double m = com.quantfinlib.util.MathUtils.mean(rv, t - 21, t + 1);
            rv[t + 1] = Math.max(1e-8, 1e-5 + 0.4 * rv[t] + 0.3 * w + 0.2 * m
                    + 2e-5 * rnd.nextGaussian());
        }
        return rv;
    }

    /** QR coefficients agree with a 50-digit BigDecimal solve of the normal equations. */
    @Test
    void harOlsMatchesAHighPrecisionReference() {
        double[] rv = simulateHar(1500, 3);
        HarRv.Params p = HarRv.fit(rv);
        int n = rv.length;
        MathContext mc = new MathContext(50);
        BigDecimal[][] a = new BigDecimal[4][5];
        for (BigDecimal[] row : a) {
            java.util.Arrays.fill(row, BigDecimal.ZERO);
        }
        for (int t = 21; t < n - 1; t++) {
            double[] x = {1, rv[t], com.quantfinlib.util.MathUtils.mean(rv, t - 4, t + 1),
                    com.quantfinlib.util.MathUtils.mean(rv, t - 21, t + 1)};
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    a[i][j] = a[i][j].add(new BigDecimal(x[i]).multiply(new BigDecimal(x[j]), mc), mc);
                }
                a[i][4] = a[i][4].add(new BigDecimal(x[i]).multiply(new BigDecimal(rv[t + 1]), mc), mc);
            }
        }
        for (int c = 0; c < 4; c++) {
            for (int r = c + 1; r < 4; r++) {
                BigDecimal f = a[r][c].divide(a[c][c], mc);
                for (int k = c; k < 5; k++) {
                    a[r][k] = a[r][k].subtract(f.multiply(a[c][k], mc), mc);
                }
            }
        }
        double[] beta = new double[4];
        BigDecimal[] b = new BigDecimal[4];
        for (int r = 3; r >= 0; r--) {
            BigDecimal s = a[r][4];
            for (int k = r + 1; k < 4; k++) {
                s = s.subtract(a[r][k].multiply(b[k], mc), mc);
            }
            b[r] = s.divide(a[r][r], mc);
            beta[r] = b[r].doubleValue();
        }
        assertEquals(beta[0], p.intercept(), 1e-9 * Math.abs(beta[0]));
        assertEquals(beta[1], p.betaDaily(), 1e-9);
        assertEquals(beta[2], p.betaWeekly(), 1e-9);
        assertEquals(beta[3], p.betaMonthly(), 1e-9);
    }

    /** Intercept-only regression: Newey-West SE has a closed form. */
    @Test
    void neweyWestMatchesTheHandFormulaForAMean() {
        SplittableRandom rnd = new SplittableRandom(4);
        int n = 300;
        double[][] x = new double[n][1];
        double[] y = new double[n];
        double prev = 0;
        for (int i = 0; i < n; i++) {
            x[i][0] = 1;
            prev = 0.5 * prev + rnd.nextGaussian();
            y[i] = 2 + prev;
        }
        LeastSquares.Result r = LeastSquares.fit(x, y, 3);
        double mean = 0;
        for (double v : y) {
            mean += v;
        }
        mean /= n;
        assertEquals(mean, r.beta()[0], 1e-12);
        double s = 0;
        for (int l = 0; l <= 3; l++) {
            double w = l == 0 ? 1 : 2 * (1 - l / 4.0);
            for (int t = l; t < n; t++) {
                s += w * (y[t] - mean) * (y[t - l] - mean);
            }
        }
        assertEquals(Math.sqrt(s) / n, r.hacStdErrors()[0], 1e-12);
    }

    @Test
    void logHarRecoversItsCoefficients() {
        SplittableRandom rnd = new SplittableRandom(8);
        int n = 4000;
        double[] lrv = new double[n];
        double[] rv = new double[n];
        java.util.Arrays.fill(lrv, 0, 22, Math.log(1e-4));
        for (int i = 0; i < 22; i++) {
            rv[i] = 1e-4;
        }
        for (int t = 21; t < n - 1; t++) {
            double w = Math.log(com.quantfinlib.util.MathUtils.mean(rv, t - 4, t + 1));
            double m = Math.log(com.quantfinlib.util.MathUtils.mean(rv, t - 21, t + 1));
            lrv[t + 1] = -0.9 + 0.35 * lrv[t] + 0.35 * w + 0.2 * m + 0.4 * rnd.nextGaussian();
            rv[t + 1] = Math.exp(lrv[t + 1]);
        }
        HarRv.Fit f = HarRv.fitLog(rv);
        double[] se = f.hacStandardErrors();
        assertEquals(0.35, f.params().betaDaily(), 3 * se[1]);
        assertEquals(0.35, f.params().betaWeekly(), 3 * se[2]);
        assertEquals(0.2, f.params().betaMonthly(), 3 * se[3]);
        assertEquals(0.16, f.residualVariance(), 0.02);
        assertTrue(HarRv.forecastLog(rv, f) > 0);
    }

    /** EWMA is causal: early variances do not change when later data change. */
    @Test
    void ewmaDoesNotLookAhead() {
        SplittableRandom rnd = new SplittableRandom(1);
        double[] a = new double[300];
        for (int i = 0; i < a.length; i++) {
            a[i] = 0.01 * rnd.nextGaussian();
        }
        double[] b = a.clone();
        for (int i = 150; i < b.length; i++) {
            b[i] *= 10;                  // a crisis in the second half
        }
        EwmaVolatility e = EwmaVolatility.riskMetrics();
        double[] ha = e.variances(a);
        double[] hb = e.variances(b);
        for (int t = 0; t <= 150; t++) {
            assertEquals(ha[t], hb[t], 0, "t=" + t);
        }
    }
}
