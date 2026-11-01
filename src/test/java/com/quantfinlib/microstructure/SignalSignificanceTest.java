package com.quantfinlib.microstructure;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Significance gates on learned signals and Hawkes calibration. */
class SignalSignificanceTest {

    private static double gaussian(SplittableRandom rng) {
        double u = 1 - rng.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rng.nextDouble());
    }

    @Test
    void effectiveSampleSizeMatchesTheKishFormula() {
        // Long run: (2 - a)/a.
        assertEquals(199, IcSignificance.effectiveSamples(0.01, 1_000_000), 1e-6);
        // One observation: exactly 1.
        assertEquals(1, IcSignificance.effectiveSamples(0.01, 1), 1e-12);
        // Brute force over explicit weights.
        double a = 0.05;
        int k = 37;
        double sw = 0;
        double sw2 = 0;
        for (int j = 0; j < k; j++) {
            double w = a * Math.pow(1 - a, j);
            sw += w;
            sw2 += w * w;
        }
        assertEquals(sw * sw / sw2, IcSignificance.effectiveSamples(a, k), 1e-9);
    }

    @Test
    void noiseComponentsRarelyEarnWeight() {
        // 200 ensembles of one pure-noise component: the old IC > 0 gate gave
        // weight about half the time; the 2-SE gate about 2-3%.
        int trials = 200;
        int weighted = 0;
        int positiveIc = 0;
        for (int s = 0; s < trials; s++) {
            SplittableRandom rng = new SplittableRandom(s);
            AlphaEnsemble ens = new AlphaEnsemble(1, 0.01);
            double[] v = new double[1];
            for (int i = 0; i < 1_500; i++) {
                v[0] = rng.nextDouble() * 2 - 1;
                ens.onObservation(v, gaussian(rng));
            }
            v[0] = 1;
            if (ens.combined(v) != 0) {
                weighted++;
            }
            if (ens.componentIC(0) > 0) {
                positiveIc++;
            }
        }
        assertTrue(positiveIc > 0.35 * trials, "IC > 0 about half the time: " + positiveIc);
        assertTrue(weighted <= 0.08 * trials, "gated: " + weighted);
    }

    @Test
    void leadLagReportsNoLeadOnIndependentNoise() {
        int trials = 100;
        int leads = 0;
        for (int s = 0; s < trials; s++) {
            SplittableRandom rng = new SplittableRandom(100 + s);
            LeadLagEstimator est = new LeadLagEstimator(10, 0.01);
            for (int i = 0; i < 2_000; i++) {
                est.onSample(gaussian(rng), gaussian(rng));
            }
            if (est.bestLag() != 0) {
                leads++;
            } else {
                assertEquals(0, est.expectedFollowerReturn(), 0);
            }
        }
        // The old argmax reported a lead in every trial.
        assertTrue(leads <= 0.12 * trials, "spurious leads " + leads);
    }

    @Test
    void leadLagFindsARealLead() {
        SplittableRandom rng = new SplittableRandom(5);
        LeadLagEstimator est = new LeadLagEstimator(10, 0.01);
        double[] past = new double[4];
        for (int i = 0; i < 3_000; i++) {
            double lead = gaussian(rng);
            double follow = 0.5 * past[2] + gaussian(rng); // leader 3 intervals ago
            System.arraycopy(past, 0, past, 1, 3);
            past[0] = lead;
            est.onSample(lead, follow);
        }
        assertEquals(3, est.bestLag());
    }

    @Test
    void hawkesMaximumLikelihoodRecoversSimulatedParameters() {
        // Ogata (1981) thinning: mu = 1/s, alpha = 0.6/s, beta = 1.2/s
        // (branching 0.5), 20,000 s of events.
        double mu = 1.0;
        double alpha = 0.6;
        double beta = 1.2;
        double horizon = 20_000;
        SplittableRandom rng = new SplittableRandom(2024);
        long[] buf = new long[100_000];
        int n = 0;
        double t = 0;
        double excited = 0; // S(t) just after the last event
        double lastEvent = 0;
        while (true) {
            double sNow = excited * Math.exp(-beta * (t - lastEvent));
            double bound = mu + sNow;
            t += -Math.log(1 - rng.nextDouble()) / bound;
            if (t > horizon) {
                break;
            }
            double sAtT = excited * Math.exp(-beta * (t - lastEvent));
            if (rng.nextDouble() * bound <= mu + sAtT) {
                buf[n++] = Math.round(t * 1e9);
                excited = sAtT + alpha;
                lastEvent = t;
            } else {
                excited = sAtT;
                lastEvent = t;
            }
        }
        long[] events = java.util.Arrays.copyOf(buf, n);
        HawkesIntensity.Fit fit = HawkesIntensity.fit(events, 0, Math.round(horizon * 1e9));
        assertTrue(fit.converged());
        assertEquals(mu, fit.baselineRatePerSec(), 0.1 * mu);
        assertEquals(alpha, fit.excitation(), 0.1 * alpha);
        assertEquals(beta, fit.decayPerSec(), 0.15 * beta);
        assertEquals(0.5, fit.branchingRatio(), 0.05);
        // The fitted model is a valid streaming model.
        HawkesIntensity model = fit.toModel();
        model.onEvent(0);
        assertEquals(fit.baselineRatePerSec() + fit.excitation(), model.intensity(0), 1e-9);
        // The optimum beats the true parameters only by sampling noise, and
        // beats a Poisson fit (alpha -> 0) by a wide margin.
        double[] secs = new double[n];
        for (int i = 0; i < n; i++) {
            secs[i] = events[i] * 1e-9;
        }
        double llTrue = HawkesIntensity.logLikelihood(secs, horizon,
                new double[]{Math.log(mu), Math.log(alpha), Math.log(beta)});
        assertTrue(fit.logLikelihood() >= llTrue - 1e-6);
        double poisson = n * Math.log(n / horizon) - n;
        assertTrue(fit.logLikelihood() - poisson > 100);
    }
}
