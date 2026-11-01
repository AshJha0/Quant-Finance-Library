package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maximum-likelihood fits recover the parameters of series simulated from
 * known models, within three of their own standard errors, and the
 * reported optimum is a stationary point of the likelihood.
 */
class GarchMleTest {

    /** Unit-variance Student-t draw with nu degrees of freedom. */
    private static double studentT(SplittableRandom rnd, int nu) {
        double chi = 0;
        for (int k = 0; k < nu; k++) {
            double z = rnd.nextGaussian();
            chi += z * z;
        }
        return rnd.nextGaussian() / Math.sqrt(chi / nu) * Math.sqrt((nu - 2.0) / nu);
    }

    private static double[] garch(double omega, double alpha, double beta, int n, long seed,
                                  int nu) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double h = omega / (1 - alpha - beta);
        double[] r = new double[n];
        for (int t = 0; t < n; t++) {
            double z = nu == 0 ? rnd.nextGaussian() : studentT(rnd, nu);
            r[t] = Math.sqrt(h) * z;
            h = omega + alpha * r[t] * r[t] + beta * h;
        }
        return r;
    }

    private static void within3Se(double truth, double est, double se, String name) {
        assertTrue(Double.isFinite(se) && se > 0, name + " SE " + se);
        assertEquals(truth, est, 3 * se, name + " est " + est + " se " + se);
    }

    @Test
    void garchGaussianRecoversParametersWithinStandardErrors() {
        double[] r = garch(2e-6, 0.08, 0.90, 6000, 42, 0);
        Garch11.Fit f = Garch11.estimate(r, Innovation.GAUSSIAN);
        double[] se = f.standardErrors();
        within3Se(2e-6, f.params().omega(), se[0], "omega");
        within3Se(0.08, f.params().alpha(), se[1], "alpha");
        within3Se(0.90, f.params().beta(), se[2], "beta");
        // A 6000-day sample pins alpha to about +-0.01: the old grid accepted +-0.06.
        assertTrue(se[1] < 0.02, "alpha SE " + se[1]);
        // Robust SEs are close to the information SEs when the model is right.
        assertEquals(1, f.robustStandardErrors()[1] / se[1], 0.35);
        // The fit is the optimum: no small move in any parameter improves it.
        double ll = f.params().logLikelihood();
        double[] th = {f.params().omega(), f.params().alpha(), f.params().beta()};
        for (int i = 0; i < 3; i++) {
            for (double sign : new double[]{-1, 1}) {
                double[] p = th.clone();
                p[i] += sign * 0.05 * se[i];
                double llp = loglikGarch(r, p[0], p[1], p[2]);
                assertTrue(llp <= ll + 1e-6, "param " + i + " sign " + sign + ": " + llp + " > " + ll);
            }
        }
    }

    /** Independent Gaussian GARCH(1,1) log-likelihood with the sample-variance backcast. */
    private static double loglikGarch(double[] returns, double w, double a, double b) {
        double mean = 0;
        for (double x : returns) {
            mean += x;
        }
        mean /= returns.length;
        double var = 0;
        for (double x : returns) {
            var += (x - mean) * (x - mean);
        }
        var /= returns.length - 1;
        double h = var;
        double ll = 0;
        for (double x0 : returns) {
            double x = x0 - mean;
            ll += -0.5 * (Math.log(2 * Math.PI) + Math.log(h) + x * x / h);
            h = w + a * x * x + b * h;
        }
        return ll;
    }

    @Test
    void garchStudentTRecoversTheDegreesOfFreedom() {
        double[] r = garch(2e-6, 0.08, 0.90, 6000, 7, 6);
        Garch11.Fit f = Garch11.estimate(r, Innovation.STUDENT_T);
        double[] se = f.standardErrors();
        within3Se(6, f.nu(), se[3], "nu");
        within3Se(0.08, f.params().alpha(), se[1], "alpha");
        within3Se(0.90, f.params().beta(), se[2], "beta");
        Garch11.Fit g = Garch11.estimate(r, Innovation.GAUSSIAN);
        assertTrue(f.params().logLikelihood() > g.params().logLikelihood() + 20,
                "fat-tailed data prefer the t likelihood");
    }

    @Test
    void gjrRecoversTheLeverageTerm() {
        SplittableRandom rnd = new SplittableRandom(9);
        int n = 6000;
        double w = 2e-6, a = 0.03, g = 0.12, b = 0.85;
        double h = w / (1 - a - g / 2 - b);
        double[] r = new double[n];
        for (int t = 0; t < n; t++) {
            r[t] = Math.sqrt(h) * rnd.nextGaussian();
            h = w + (r[t] < 0 ? a + g : a) * r[t] * r[t] + b * h;
        }
        GjrGarch11.Fit f = GjrGarch11.estimate(r, Innovation.GAUSSIAN);
        double[] se = f.standardErrors();
        within3Se(a, f.params().alpha(), se[1], "alpha");
        within3Se(g, f.params().gamma(), se[2], "gamma");
        within3Se(b, f.params().beta(), se[3], "beta");
    }

    @Test
    void egarchRecoversParameters() {
        SplittableRandom rnd = new SplittableRandom(13);
        int n = 6000;
        double lnVar = Math.log(1e-4);
        double b = 0.97, a = 0.15, g = -0.08, w = (1 - b) * lnVar;
        double lnH = lnVar;
        double[] r = new double[n];
        double eAbs = Math.sqrt(2 / Math.PI);
        for (int t = 0; t < n; t++) {
            double z = rnd.nextGaussian();
            r[t] = Math.exp(0.5 * lnH) * z;
            lnH = w + b * lnH + a * (Math.abs(z) - eAbs) + g * z;
        }
        Egarch11.Fit f = Egarch11.estimate(r, Innovation.GAUSSIAN);
        double[] se = f.standardErrors();
        within3Se(a, f.params().alpha(), se[1], "alpha");
        within3Se(g, f.params().gamma(), se[2], "gamma");
        within3Se(b, f.params().beta(), se[3], "beta");
    }
}
