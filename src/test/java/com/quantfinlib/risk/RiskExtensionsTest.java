package com.quantfinlib.risk;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskExtensionsTest {

    // ---- Constant-correlation Ledoit-Wolf --------------------------------

    private static double[][] sampleOneFactor(int t, int n, double rho, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[][] r = new double[t][n];
        for (int i = 0; i < t; i++) {
            double m = rnd.nextGaussian();
            for (int j = 0; j < n; j++) {
                double vol = 0.01 * (1 + j % 3);
                r[i][j] = vol * (Math.sqrt(rho) * m + Math.sqrt(1 - rho) * rnd.nextGaussian());
            }
        }
        return r;
    }

    @Test
    void constantCorrelationTargetHasTheAverageCorrelationAndBeatsTheSampleMatrix() {
        int n = 20;
        double rho = 0.3;
        double lossShrunk = 0;
        double lossSample = 0;
        for (int rep = 0; rep < 20; rep++) {
            double[][] r = sampleOneFactor(40, n, rho, 100 + rep);
            CovarianceShrinkage.Result res = CovarianceShrinkage.constantCorrelation(r);
            assertTrue(res.intensity() >= 0 && res.intensity() <= 1);
            CovarianceShrinkage.Result zero = CovarianceShrinkage.ledoitWolf(r);
            // delta = 0 would reproduce S: rebuild S from the identity result's
            // inputs instead: S = (Sigma* - delta*mu*I)/(1 - delta).
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    double truth = 0.01 * (1 + a % 3) * 0.01 * (1 + b % 3) * (a == b ? 1 : rho);
                    double s = (zero.matrix()[a][b] - (a == b ? zero.intensity() * zero.target() : 0))
                            / (1 - zero.intensity());
                    lossShrunk += Math.pow(res.matrix()[a][b] - truth, 2);
                    lossSample += Math.pow(s - truth, 2);
                }
            }
        }
        assertTrue(lossShrunk < 0.8 * lossSample, lossShrunk + " vs " + lossSample);
    }

    @Test
    void constantCorrelationIntensityVanishesWhenTheTargetIsWrong() {
        // Two blocks: correlation 0.6 inside the first block, 0 elsewhere.
        // The constant-correlation target is then biased, so with long
        // samples the optimal intensity goes to zero (it is O(1/T)).
        SplittableRandom rnd = new SplittableRandom(9);
        int t = 20_000;
        double[][] r = new double[t][6];
        for (int i = 0; i < t; i++) {
            double m = rnd.nextGaussian();
            for (int j = 0; j < 6; j++) {
                double e = rnd.nextGaussian();
                r[i][j] = 0.01 * (j < 3 ? Math.sqrt(0.6) * m + Math.sqrt(0.4) * e : e);
            }
        }
        CovarianceShrinkage.Result res = CovarianceShrinkage.constantCorrelation(r);
        assertTrue(res.intensity() < 0.01, "intensity " + res.intensity());
        // Average correlation: 3 of the 15 pairs at 0.6 -> 0.12.
        assertEquals(0.12, res.target(), 0.02);
    }

    // ---- Acerbi-Szekely Z2 ------------------------------------------------

    @Test
    void z2HandExampleAndBehaviourUnderTheTruthAndUnderUnderstatement() {
        // T = 4, c = 0.75 (alpha = 0.25): one exceedance of 0.05 against ES 0.04:
        // Z2 = 1 - (0.05/0.04)/(4*0.25) = -0.25.
        double z = VarBacktest.acerbiSzekelyZ2(new double[]{0.01, -0.05, 0.0, -0.01},
                new double[]{0.03, 0.03, 0.03, 0.03}, new double[]{0.04, 0.04, 0.04, 0.04}, 0.75);
        assertEquals(-0.25, z, 1e-15);

        double zq = MathUtils.normInv(0.975);
        double esUnit = MathUtils.normPdf(zq) / 0.025;
        SplittableRandom rnd = new SplittableRandom(4);
        double sumTrue = 0;
        double sumUnder = 0;
        int reps = 400;
        for (int k = 0; k < reps; k++) {
            double[] r = new double[250];
            for (int t = 0; t < 250; t++) {
                r[t] = 0.01 * rnd.nextGaussian();
            }
            double[] var = new double[250];
            double[] es = new double[250];
            double[] varU = new double[250];
            double[] esU = new double[250];
            java.util.Arrays.fill(var, 0.01 * zq);
            java.util.Arrays.fill(es, 0.01 * esUnit);
            java.util.Arrays.fill(varU, 0.007 * zq);
            java.util.Arrays.fill(esU, 0.007 * esUnit);
            sumTrue += VarBacktest.acerbiSzekelyZ2(r, var, es, 0.975);
            sumUnder += VarBacktest.acerbiSzekelyZ2(r, varU, esU, 0.975);
        }
        assertEquals(0, sumTrue / reps, 0.06, "E[Z2] = 0 under the true model");
        assertTrue(sumUnder / reps < VarBacktest.Z2_THRESHOLD_5PCT,
                "understated vol fails: " + sumUnder / reps);
    }

    // ---- GPD MLE ----------------------------------------------------------

    @Test
    void gpdMleRecoversAFatTailWithinStandardErrors() {
        SplittableRandom rnd = new SplittableRandom(21);
        double xi = 0.6;
        double beta = 1.0;
        int n = 20_000;
        double[] losses = new double[n];
        for (int i = 0; i < n; i++) {
            double u = rnd.nextDouble();
            losses[i] = beta / xi * (Math.pow(1 - u, -xi) - 1);    // GPD(ξ, β) from 0
        }
        // Threshold at the median: exceedances over u of a GPD are GPD(ξ, β + ξu).
        ExtremeValueTheory.GpdMle mle = ExtremeValueTheory.fitPotMle(losses, 0.5);
        double u = mle.fit().threshold();
        assertEquals(xi, mle.fit().shape(), 3 * mle.shapeStdError());
        assertEquals(beta + xi * u, mle.fit().scale(), 3 * mle.scaleStdError());
        // The MLE is at least as likely as the PWM fit.
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        int m = mle.fit().exceedances();
        double[] y = new double[m];
        for (int i = 0; i < m; i++) {
            y[i] = sorted[n - m + i] - u;
        }
        ExtremeValueTheory.GpdFit pwm = ExtremeValueTheory.fitPot(losses, 0.5);
        double llPwm = ExtremeValueTheory.gpdLogLikelihood(y, pwm.shape(), pwm.scale());
        assertTrue(mle.logLikelihood() >= llPwm - 1e-9);
        assertEquals(mle.logLikelihood(),
                ExtremeValueTheory.gpdLogLikelihood(y, mle.fit().shape(), mle.fit().scale()), 1e-6);
        // Mean excess of a GPD is linear: e(u) = (β + ξu)/(1 − ξ).
        double[] me = ExtremeValueTheory.meanExcess(losses, new double[]{0.0});
        assertEquals(beta / (1 - xi), me[0], 0.15);
    }

    // ---- Filtered historical simulation ----------------------------------

    @Test
    void fhsScalesHistoryToTodaysVolatility() {
        // Volatility drifts from 1% to 3% over the history: plain HS mixes
        // the levels, FHS reports today's 3% risk.
        SplittableRandom rnd = new SplittableRandom(6);
        int t = 3000;
        double[][] rows = new double[t][1];
        for (int i = 0; i < t; i++) {
            double vol = 0.01 + 0.02 * i / (t - 1.0);
            rows[i][0] = vol * rnd.nextGaussian();
        }
        double[] exposure = {1_000_000};
        VarEngine.VarResult hs = VarEngine.historicalVar(exposure, rows, 0.99);
        VarEngine.VarResult fhs = VarEngine.filteredHistoricalVar(exposure, rows, 0.94, 50, 0.99);
        double truth = MathUtils.normInv(0.99) * 0.03 * 1_000_000;
        // Tolerance 20%: σ_{T+1} from an EWMA with an effective sample of ~30
        // days is itself ~13% noisy, and the standardised shocks are a scale
        // mixture with heavier tails than the normal.
        assertEquals(truth, fhs.var(), 0.20 * truth);
        assertTrue(fhs.var() > 1.2 * hs.var());
        assertTrue(hs.var() < 0.8 * truth, "plain HS understates: " + hs.var());
    }
}
