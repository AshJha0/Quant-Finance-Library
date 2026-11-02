package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw SVI, SSVI and the SVI surface: closed-form limits, the
 * Gatheral-Jacquier (2014) arbitrage example, parameter recovery by
 * calibration, and dense-grid butterfly and calendar checks.
 */
class SviSsviTest {

    private static final double S = 100;
    private static final double R = 0.03;
    private static final double Q = 0.01;
    private static final double[] T = {0.25, 0.5, 1.0, 2.0};
    private static final double[] THETA = {0.010, 0.022, 0.045, 0.090};

    @Test
    void rawSviClosedFormLimits() {
        SviSlice s = new SviSlice(0.02, 0.3, -0.4, 0.1, 0.25);
        assertEquals(0.02 + 0.3 * 0.25, s.totalVariance(0.1), 1e-15);       // w(m) = a + b sigma
        assertEquals(0.0, s.slope(0.1) - 0.3 * -0.4, 1e-15);               // w'(m) = b rho
        assertEquals(0.3 / 0.25, s.curvature(0.1), 1e-12);                  // w''(m) = b / sigma
        assertEquals(0.3 * (1 - 0.4), s.slope(1e6), 1e-9);                  // right wing b(1+rho)
        assertEquals(-0.3 * (1 + 0.4), s.slope(-1e6), 1e-9);                // left wing -b(1-rho)
        assertEquals(Math.sqrt(s.totalVariance(0.3) / 0.5), s.impliedVol(0.3, 0.5), 1e-15);
        // Finite-difference check of the analytic derivatives.
        double h = 1e-5;
        assertEquals((s.totalVariance(0.4 + h) - s.totalVariance(0.4 - h)) / (2 * h), s.slope(0.4), 1e-9);
        assertEquals((s.slope(0.4 + h) - s.slope(0.4 - h)) / (2 * h), s.curvature(0.4), 1e-7);
    }

    @Test
    void sviRejectsInadmissibleParameters() {
        assertThrows(IllegalArgumentException.class, () -> new SviSlice(0.01, -0.1, 0, 0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new SviSlice(0.01, 0.1, 1, 0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new SviSlice(0.01, 0.1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SviSlice(-0.5, 0.1, 0, 0, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new SviSlice(0.01, 0.1, 0, 0, 0.1).impliedVol(0, 0));
    }

    /**
     * Gatheral and Jacquier (2014), example 3.1 (parameters due to Axel
     * Vogt): a = −0.0410, b = 0.1331, ρ = 0.3060, m = 0.3586, σ = 0.4153,
     * T = 1. The slice has admissible parameters yet g(k) &lt; 0 in the left
     * wing (their figure 2), so it admits butterfly arbitrage.
     */
    @Test
    void gatheralJacquierVogtExampleHasButterflyArbitrage() {
        SviSlice vogt = new SviSlice(-0.0410, 0.1331, 0.3060, 0.3586, 0.4153);
        double minG = vogt.minDensityFactor(-1.5, 1.5, 3001);
        assertTrue(minG < -0.02, "Vogt slice must show negative density, got " + minG);
        // Negative on part of the grid only.
        int negatives = 0;
        for (int i = 0; i <= 300; i++) {
            if (vogt.densityFactor(-1.5 + 0.01 * i) < 0) {
                negatives++;
            }
        }
        assertTrue(negatives > 0 && negatives < 300);

        // Refit with the butterfly penalty: close to the quotes, no arbitrage on a dense grid.
        double[] k = new double[31];
        double[] w = new double[31];
        for (int i = 0; i < k.length; i++) {
            k[i] = -1.5 + 0.1 * i;
            w[i] = vogt.totalVariance(k[i]);
        }
        SviSlice fixed = SviSlice.calibrate(k, w, null);
        assertTrue(fixed.minDensityFactor(-2.0, 2.0, 4001) > -1e-4,
                "penalised fit min g = " + fixed.minDensityFactor(-2.0, 2.0, 4001));
        assertTrue(fixed.rmse(k, w) < 5e-3, "rmse " + fixed.rmse(k, w));
        // Without the penalty the fit reproduces the arbitrageable slice.
        SviSlice raw = SviSlice.calibrate(k, w, null, false);
        assertTrue(raw.rmse(k, w) < 1e-6, "unpenalised rmse " + raw.rmse(k, w));
        assertTrue(raw.minDensityFactor(-1.5, 1.5, 3001) < -0.02);
    }

    @Test
    void sviCalibrationRecoversParameters() {
        SviSlice truth = new SviSlice(0.03, 0.25, -0.45, 0.05, 0.18);
        double[] k = new double[15];
        double[] w = new double[15];
        double[] wt = new double[15];
        for (int i = 0; i < k.length; i++) {
            k[i] = -0.7 + 0.1 * i;
            w[i] = truth.totalVariance(k[i]);
            wt[i] = 1 + i % 3;
        }
        SviSlice fit = SviSlice.calibrate(k, w, wt);
        assertEquals(truth.a(), fit.a(), 1e-5);
        assertEquals(truth.b(), fit.b(), 1e-5);
        assertEquals(truth.rho(), fit.rho(), 1e-5);
        assertEquals(truth.m(), fit.m(), 1e-5);
        assertEquals(truth.sigma(), fit.sigma(), 1e-5);
        assertThrows(IllegalArgumentException.class,
                () -> SviSlice.calibrate(new double[]{0, 1}, new double[]{1, 1}, null));
    }

    @Test
    void ssviSliceEqualsItsRawSviForm() {
        SsviSurface s = surface(-0.5, 1.2, 0.4);
        for (double t : new double[]{0.1, 0.25, 0.7, 2.0, 3.0}) {
            SviSlice raw = s.slice(t);
            for (double k = -1.0; k <= 1.0; k += 0.05) {
                assertEquals(s.totalVariance(t, k), raw.totalVariance(k), 1e-14);
            }
            assertEquals(s.thetaAt(t), s.totalVariance(t, 0), 1e-15);     // w(0) = theta
        }
        assertEquals(THETA[2], s.thetaAt(1.0), 1e-15);
        assertEquals(THETA[0] * 0.1 / 0.25, s.thetaAt(0.1), 1e-15);
        assertEquals(THETA[3] + (THETA[3] - THETA[2]) * 1.0, s.thetaAt(3.0), 1e-15);
        // Power-law phi, equation 4.5.
        assertEquals(1.2 / (Math.pow(0.05, 0.4) * Math.pow(1.05, 0.6)), s.phi(0.05), 1e-14);
    }

    @Test
    void ssviConstructorEnforcesTheorem41And42Bounds() {
        assertThrows(IllegalArgumentException.class, () -> surface(-0.5, 1.4, 0.4));  // eta(1+|rho|) > 2
        assertThrows(IllegalArgumentException.class, () -> surface(-0.5, 1.0, 0.6));  // gamma > 1/2
        assertThrows(IllegalArgumentException.class, () -> new SsviSurface(-0.5, 1, 0.4,
                new double[]{1, 2}, new double[]{0.05, 0.04}, S, R, Q));            // theta falls
        assertThrows(IllegalStateException.class, () -> surface(0, 0, 0.3).slice(1));
    }

    @Test
    void ssviHasNoStaticArbitrageOnDenseGrid() {
        SsviSurface s = surface(-0.7, 2 / 1.7, 0.5);     // at the remark 4.4 boundary
        double[] times = new double[60];
        for (int i = 0; i < times.length; i++) {
            times[i] = 0.02 + 0.05 * i;
        }
        assertEquals(0, s.calendarViolations(times, -2, 2, 201));
        for (double t : times) {
            assertTrue(s.slice(t).minDensityFactor(-3, 3, 1201) >= 0, "butterfly at t=" + t);
            assertTrue(s.minDensityFactor(t, -2, 2, 201) > -1e-6);
        }
    }

    @Test
    void ssviCalibrationRecoversParametersAndIsArbitrageFree() {
        SsviSurface truth = surface(-0.55, 1.1, 0.35);
        double[][] strikes = new double[T.length][9];
        double[][] vols = new double[T.length][9];
        for (int i = 0; i < T.length; i++) {
            double f = truth.forward(T[i]);
            for (int j = 0; j < 9; j++) {
                strikes[i][j] = f * Math.exp(-0.4 + 0.1 * j);
                vols[i][j] = truth.impliedVol(T[i], strikes[i][j]);
            }
        }
        SsviSurface fit = SsviSurface.calibrate(S, R, Q, T, strikes, vols, null);
        assertEquals(truth.rho(), fit.rho(), 1e-6);
        assertEquals(truth.eta(), fit.eta(), 1e-6);
        assertEquals(truth.gamma(), fit.gamma(), 1e-6);
        for (int i = 0; i < T.length; i++) {
            assertEquals(THETA[i], fit.theta(i), 1e-14);
            for (int j = 0; j < 9; j++) {
                assertEquals(vols[i][j], fit.impliedVol(T[i], strikes[i][j]), 1e-7);
            }
        }
        assertEquals(0, fit.calendarViolations(new double[]{0.1, 0.3, 0.6, 1.2, 2.5}, -1.5, 1.5, 301));
        assertEquals(fit.spot() * Math.exp((R - Q) * 1.5), fit.forward(1.5), 1e-12);
        assertEquals(BlackScholes.price(BlackScholes.OptionType.PUT, S, 95, R, Q,
                fit.impliedVol(1, 95), 1), fit.price(BlackScholes.OptionType.PUT, 95, 1), 1e-12);
    }

    @Test
    void sviSurfaceInterpolatesInTotalVariance() {
        SsviSurface truth = surface(-0.4, 1.0, 0.4);
        double[][] strikes = new double[T.length][11];
        double[][] vols = new double[T.length][11];
        for (int i = 0; i < T.length; i++) {
            double f = truth.forward(T[i]);
            for (int j = 0; j < 11; j++) {
                strikes[i][j] = f * Math.exp(-0.5 + 0.1 * j);
                vols[i][j] = truth.impliedVol(T[i], strikes[i][j]);
            }
        }
        SviSurface svi = SviSurface.calibrate(S, R, Q, T, strikes, vols, null);
        for (int i = 0; i < T.length; i++) {
            for (int j = 0; j < 11; j++) {
                assertEquals(vols[i][j], svi.impliedVol(T[i], strikes[i][j]), 2e-5);
            }
        }
        // Linear in total variance at fixed k between pillars, proportional outside.
        double k = 0.2;
        double w = svi.totalVariance(0.75, k);
        assertEquals(0.5 * (svi.slice(1).totalVariance(k) + svi.slice(2).totalVariance(k)), w, 1e-15);
        assertEquals(svi.slice(0).totalVariance(k) * 0.4, svi.totalVariance(0.1, k), 1e-15);
        assertEquals(svi.slice(3).totalVariance(k) * 1.5, svi.totalVariance(3.0, k), 1e-15);
        assertEquals(4, svi.expiryLength());
        assertEquals(2.0, svi.expiry(3));
        double[] times = {0.1, 0.25, 0.4, 0.5, 0.8, 1.0, 1.5, 2.0, 3.0};
        assertEquals(0, svi.calendarViolations(times, -0.5, 0.5, 101));
        for (double t : times) {
            assertTrue(svi.minDensityFactor(t, -0.5, 0.5, 101) > 0);
        }
        // Crossing slices are reported.
        SviSurface crossed = new SviSurface(new double[]{1, 2},
                new SviSlice[]{new SviSlice(0.04, 0.1, 0, 0, 0.1), new SviSlice(0.01, 0.1, 0, 0, 0.1)}, S, R, Q);
        assertTrue(crossed.calendarViolations(new double[]{1, 2}, -1, 1, 11) > 0);
    }

    private static SsviSurface surface(double rho, double eta, double gamma) {
        return new SsviSurface(rho, eta, gamma, T, THETA, S, R, Q);
    }
}
