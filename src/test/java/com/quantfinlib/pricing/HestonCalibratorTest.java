package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import static com.quantfinlib.pricing.BlackScholes.OptionType.CALL;
import static com.quantfinlib.pricing.BlackScholes.OptionType.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Levenberg-Marquardt Heston calibration: a surface generated from known
 * parameters (by the independent Simpson pricer) must be refitted to those
 * parameters from a distant start, and a noisy surface must still fit within
 * its noise.
 */
class HestonCalibratorTest {

    private static final double S = 100, R = 0.02, Q = 0.01;
    private static final double[] TS = {0.25, 0.5, 1, 2};
    private static final double[] KS = {80, 90, 100, 110, 120};

    private static double[][] surface(Heston.Params truth, double noise) {
        int n = TS.length * KS.length;
        double[] k = new double[n], t = new double[n], v = new double[n];
        int i = 0;
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(3);
        for (double tt : TS) {
            for (double kk : KS) {
                double fwd = S * Math.exp((R - Q) * tt);
                var type = kk < fwd ? PUT : CALL;
                double px = type == CALL ? Heston.call(S, kk, R, Q, tt, truth) : Heston.put(S, kk, R, Q, tt, truth);
                k[i] = kk;
                t[i] = tt;
                v[i] = BlackScholes.impliedVol(type, px, S, kk, R, Q, tt) + noise * (rnd.nextDouble() - 0.5);
                i++;
            }
        }
        return new double[][]{k, t, v};
    }

    @Test
    void recoversGeneratingParameters() {
        Heston.Params truth = new Heston.Params(2.5, 0.06, 0.5, -0.65, 0.035);
        double[][] q = surface(truth, 0);
        Heston.Params start = new Heston.Params(1.0, 0.03, 0.3, -0.2, 0.06);
        HestonCalibrator.Result res = HestonCalibrator.calibrate(S, R, Q, q[0], q[1], q[2], start);
        assertTrue(res.converged());
        assertTrue(res.impliedVolRmse() < 2e-6, "rmse " + res.impliedVolRmse());
        Heston.Params p = res.params();
        assertEquals(truth.kappa(), p.kappa(), 2e-3 * truth.kappa());
        assertEquals(truth.theta(), p.theta(), 1e-4);
        assertEquals(truth.sigmaV(), p.sigmaV(), 1e-3);
        assertEquals(truth.rho(), p.rho(), 1e-3);
        assertEquals(truth.v0(), p.v0(), 1e-5);
    }

    @Test
    void noisySurfaceFitsWithinNoise() {
        Heston.Params truth = new Heston.Params(1.5, 0.04, 0.4, -0.5, 0.05);
        double[][] q = surface(truth, 0.002);   // +/- 10 bp uniform vol noise
        HestonCalibrator.Result res = HestonCalibrator.calibrate(S, R, Q, q[0], q[1], q[2],
                new Heston.Params(1, 0.05, 0.3, -0.3, 0.04));
        // Uniform(-1e-3, 1e-3) has sd 5.8e-4; a 5-parameter fit cannot do much better or worse.
        assertTrue(res.impliedVolRmse() < 8e-4, "rmse " + res.impliedVolRmse());
        assertTrue(res.iterations() >= 1);
    }

    @Test
    void validation() {
        double[] k = {90, 100, 110, 120, 130};
        double[] t = {1, 1, 1, 1, 1};
        double[] v = {0.2, 0.2, 0.2, 0.2, 0.2};
        Heston.Params p = new Heston.Params(1, 0.04, 0.3, -0.5, 0.04);
        assertThrows(IllegalArgumentException.class,
                () -> HestonCalibrator.calibrate(S, R, Q, new double[]{1}, new double[]{1}, new double[]{1}, p));
        assertThrows(IllegalArgumentException.class,
                () -> HestonCalibrator.calibrate(S, R, Q, k, t, new double[]{0.2, 0.2, -1, 0.2, 0.2}, p));
        assertThrows(IllegalArgumentException.class,
                () -> HestonCalibrator.calibrate(S, R, Q, k, t, v, p, 0, 1e-10));
        assertThrows(IllegalArgumentException.class,
                () -> HestonCalibrator.calibrate(-1, R, Q, k, t, v, p));
    }
}
