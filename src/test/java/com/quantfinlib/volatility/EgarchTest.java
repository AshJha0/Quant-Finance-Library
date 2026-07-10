package com.quantfinlib.volatility;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EGARCH(1,1): log-variance dynamics, leverage as a sign. */
class EgarchTest {

    @Test
    void egarchFindsPlantedLeverageAndItsAbsence() {
        // Simulate EGARCH with strong planted leverage (gamma = -0.12).
        Random rnd = new Random(42);
        int n = 4_000;
        double beta = 0.9;
        double alpha = 0.15;
        double gamma = -0.12;
        double omega = (1 - beta) * Math.log(1e-4);
        double[] r = new double[n];
        double lnH = Math.log(1e-4);
        for (int t = 0; t < n; t++) {
            double z = rnd.nextGaussian();
            r[t] = Math.exp(lnH / 2) * z;
            lnH = omega + beta * lnH
                    + alpha * (Math.abs(z) - Math.sqrt(2 / Math.PI)) + gamma * z;
        }
        Egarch11.Params fit = Egarch11.fit(r);
        assertTrue(fit.gamma() < -0.05,
                "the planted leverage is found AS A SIGN: " + fit.gamma());
        assertTrue(fit.beta() > 0.8 && fit.beta() < 0.98,
                "log-variance persistence: " + fit.beta());
        assertTrue(fit.alpha() > 0.03, "the magnitude effect: " + fit.alpha());
        assertEquals(fit.omega() / (1 - fit.beta()), fit.unconditionalLogVariance(),
                1e-12);

        // Symmetric GARCH data: the honest answer is gamma ~ 0.
        double[] sym = new double[n];
        double h = 2e-6 / (1 - 0.08 - 0.9);
        for (int t = 0; t < n; t++) {
            sym[t] = Math.sqrt(h) * rnd.nextGaussian();
            h = 2e-6 + 0.08 * sym[t] * sym[t] + 0.9 * h;
        }
        assertTrue(Math.abs(Egarch11.fit(sym).gamma()) < 0.08,
                "no asymmetry to find: " + Egarch11.fit(sym).gamma());
    }

    @Test
    void leverageMeansDownMovesRaiseTomorrowsVolMore() {
        Random rnd = new Random(7);
        double[] base = new double[200];
        for (int t = 0; t < base.length; t++) {
            base[t] = 0.01 * rnd.nextGaussian();
        }
        // Identical histories except the LAST return's sign.
        double[] down = base.clone();
        double[] up = base.clone();
        down[base.length - 1] = -0.03;
        up[base.length - 1] = 0.03;
        Egarch11.Params leveraged = new Egarch11.Params(
                0.1 * Math.log(1e-4), 0.15, -0.12, 0.9, 0);
        double afterDown = Egarch11.nextVariance(down, leveraged);
        double afterUp = Egarch11.nextVariance(up, leveraged);
        assertTrue(afterDown > afterUp,
                "gamma < 0: the down move frightens tomorrow more ("
                        + afterDown + " > " + afterUp + ")");

        // Every conditional variance is positive BY CONSTRUCTION — the
        // log form needs no parameter constraints to guarantee it.
        double[] variances = Egarch11.conditionalVariances(down, leveraged);
        for (double v : variances) {
            assertTrue(v > 0);
        }
        assertThrows(IllegalArgumentException.class, () -> Egarch11.fit(new double[50]));
        assertThrows(IllegalArgumentException.class, () -> Egarch11.fit(new double[200]),
                "zero variance is not a fittable series");
    }

    @Test
    void oneStepRecursionMatchesHandArithmeticExactly() {
        // Two returns {0.01, -0.02}: mean -0.005, sample var 4.5e-4.
        // z1 = 0.015/sqrt(4.5e-4) = 1/sqrt(2); the recursion is then
        // fully determined — computed by hand below and pinned.
        Egarch11.Params p = new Egarch11.Params(
                0.1 * Math.log(1e-4), 0.15, -0.12, 0.9, 0);
        double[] r = {0.01, -0.02};
        double lnH0 = Math.log(4.5e-4);
        double z1 = 0.015 / Math.sqrt(4.5e-4);
        double lnH1 = p.omega() + 0.9 * lnH0
                + 0.15 * (z1 - Math.sqrt(2 / Math.PI)) - 0.12 * z1;
        double[] cv = Egarch11.conditionalVariances(r, p);
        assertEquals(4.5e-4, cv[0], 1e-18, "seeded at the sample variance");
        assertEquals(Math.exp(lnH1), cv[1], 1e-15, "the exact one-step transition");

        double z2 = -0.015 / Math.sqrt(cv[1]);
        double lnH2 = p.omega() + 0.9 * Math.log(cv[1])
                + 0.15 * (Math.abs(z2) - Math.sqrt(2 / Math.PI)) - 0.12 * z2;
        assertEquals(Math.exp(lnH2), Egarch11.nextVariance(r, p), 1e-15,
                "nextVariance IS the recursion's next step, nothing else");

        // The house gates: a constant series must throw, never return
        // h = 0 (which would contradict 'positive by construction').
        assertThrows(IllegalArgumentException.class,
                () -> Egarch11.conditionalVariances(new double[]{0.01, 0.01, 0.01}, p));
        assertThrows(IllegalArgumentException.class,
                () -> Egarch11.nextVariance(new double[]{0.01, Double.NaN}, p));
        assertThrows(IllegalArgumentException.class,
                () -> Egarch11.conditionalVariances(new double[]{0.01}, p));
    }
}
