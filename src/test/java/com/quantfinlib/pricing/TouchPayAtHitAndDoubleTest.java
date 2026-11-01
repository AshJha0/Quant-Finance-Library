package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pay-at-hit one-touch and double no-touch, each pinned by an independent
 * reference: the first-passage Laplace transform integrated numerically from
 * the reflection-principle hit probability, the perpetual closed form, the
 * eigenfunction (Fourier sine) series for the double barrier, and Monte Carlo
 * with a Brownian-bridge crossing correction.
 */
class TouchPayAtHitAndDoubleTest {

    /** E[e^{-r tau} 1{tau<=T}] = e^{-rT} P(T) + r int_0^T e^{-rt} P(t) dt (by parts), Simpson. */
    private static double laplaceByParts(double s, double h, double r, double q, double vol, double t) {
        int n = 20_000;
        double dt = t / n;
        double sum = 0;
        for (int i = 0; i <= n; i++) {
            double ti = i * dt;
            double w = (i == 0 || i == n) ? 1 : ((i & 1) == 1 ? 4 : 2);
            double p = ti == 0 ? 0 : TouchOption.hitProbability(s, h, r, q, vol, ti);
            sum += w * Math.exp(-r * ti) * p;
        }
        return Math.exp(-r * t) * TouchOption.hitProbability(s, h, r, q, vol, t) + r * sum * dt / 3;
    }

    @Test
    void payAtHitMatchesLaplaceTransformOfHitProbability() {
        double[][] cases = {
            // spot, barrier, rate(dom), carry(for), vol, T
            {100, 110, 0.05, 0.02, 0.20, 1.0},
            {100, 90, 0.05, 0.02, 0.20, 1.0},
            {1.10, 1.20, 0.03, 0.045, 0.12, 0.5},
            {1.10, 1.02, 0.03, 0.045, 0.12, 2.0},
            {100, 130, 0.08, 0.00, 0.35, 3.0},
        };
        for (double[] c : cases) {
            double ref = laplaceByParts(c[0], c[1], c[2], c[3], c[4], c[5]);
            double px = TouchOption.oneTouchPayAtHit(c[0], c[1], c[2], c[3], c[4], c[5], 1);
            assertEquals(ref, px, 1e-8, () -> "case " + java.util.Arrays.toString(c));
            // Paying at hit is worth more than paying the same amount later at expiry.
            assertTrue(px > TouchOption.oneTouch(c[0], c[1], c[2], c[3], c[4], c[5], 1));
        }
    }

    @Test
    void zeroDomesticRateEqualsPayAtExpiry() {
        assertEquals(TouchOption.oneTouch(100, 115, 0, 0.03, 0.25, 1.5, 1e6),
                TouchOption.oneTouchPayAtHit(100, 115, 0, 0.03, 0.25, 1.5, 1e6), 1e-6);
        assertEquals(TouchOption.oneTouch(100, 85, 0, -0.01, 0.25, 1.5, 1),
                TouchOption.oneTouchPayAtHit(100, 85, 0, -0.01, 0.25, 1.5, 1), 1e-12);
    }

    @Test
    void longDatedLimitIsPerpetualClosedForm() {
        double r = 0.05, q = 0.01, vol = 0.3;
        double mu = (r - q - 0.5 * vol * vol) / (vol * vol);
        double lambda = Math.sqrt(mu * mu + 2 * r / (vol * vol));
        // Down: (H/S)^(mu+lambda); up: (H/S)^(mu-lambda).
        assertEquals(Math.pow(0.8, mu + lambda),
                TouchOption.oneTouchPayAtHit(100, 80, r, q, vol, 500, 1), 1e-10);
        assertEquals(Math.pow(1.25, mu - lambda),
                TouchOption.oneTouchPayAtHit(100, 125, r, q, vol, 500, 1), 1e-10);
    }

    @Test
    void payAtHitAgreesWithBrownianBridgeMonteCarlo() {
        double s0 = 100, h = 112, r = 0.06, q = 0.02, vol = 0.25, t = 1;
        int steps = 400, paths = 40_000;
        double dt = t / steps;
        double m = (r - q - 0.5 * vol * vol) * dt;
        double sd = vol * Math.sqrt(dt);
        SplittableRandom rnd = new SplittableRandom(7);
        double lh = Math.log(h);
        double sum = 0, sum2 = 0;
        for (int p = 0; p < paths; p++) {
            double x = Math.log(s0);
            double pay = 0;
            for (int i = 0; i < steps; i++) {
                double y = x + m + sd * rnd.nextGaussian();
                boolean hit = y >= lh;
                if (!hit) {
                    double cross = Math.exp(-2 * (lh - x) * (lh - y) / (sd * sd));
                    hit = rnd.nextDouble() < cross;
                }
                if (hit) {
                    pay = Math.exp(-r * (i + 0.5) * dt);   // bridge hit time ~ mid-step
                    break;
                }
                x = y;
            }
            sum += pay;
            sum2 += pay * pay;
        }
        double mean = sum / paths;
        double se = Math.sqrt((sum2 / paths - mean * mean) / paths);
        double cf = TouchOption.oneTouchPayAtHit(s0, h, r, q, vol, t, 1);
        assertEquals(cf, mean, 4 * se + 1e-3);
    }

    @Test
    void payAtHitEdgesAndValidation() {
        assertEquals(5, TouchOption.oneTouchPayAtHit(100, 100, 0.05, 0, 0.2, 1, 5));
        assertEquals(0, TouchOption.oneTouchPayAtHit(100, 110, 0.05, 0, 0.2, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> TouchOption.oneTouchPayAtHit(100, 110, -0.5, -0.5, 0.2, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> TouchOption.oneTouchPayAtHit(100, 110, Double.NaN, 0, 0.2, 1, 1));
    }

    /** Fourier sine (eigenfunction) series for the survival probability; converges fast at long T. */
    private static double eigenSeries(double s, double lo, double up, double r, double q, double vol, double t) {
        double m = r - q - 0.5 * vol * vol;
        double a = m / (vol * vol);
        double x = Math.log(s / lo), l = Math.log(up / lo);
        double sum = 0;
        for (int k = 1; k <= 4000; k++) {
            double w = k * Math.PI / l;
            double term = Math.sin(w * x) * w * (1 - Math.pow(-1, k) * Math.exp(a * l))
                    / (a * a + w * w) * Math.exp(-0.5 * vol * vol * w * w * t);
            sum += term;
        }
        return 2 / l * Math.exp(-a * x - m * m * t / (2 * vol * vol)) * sum;
    }

    @Test
    void doubleNoTouchImageSeriesMatchesEigenfunctionSeries() {
        double[][] cases = {
            {100, 90, 110, 0.05, 0.02, 0.20, 1.0},
            {100, 80, 120, 0.05, 0.02, 0.20, 0.25},
            {1.10, 1.05, 1.18, 0.03, 0.045, 0.10, 0.5},
            {100, 95, 140, 0.01, 0.06, 0.30, 2.0},
            {100, 99, 101, 0.0, 0.0, 0.15, 0.1},
        };
        for (double[] c : cases) {
            double ref = eigenSeries(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
            double p = TouchOption.doubleNoTouchProbability(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
            assertEquals(ref, p, 1e-10, () -> "case " + java.util.Arrays.toString(c));
            assertEquals(1, p + TouchOption.doubleOneTouch(c[0], c[1], c[2], c[3], c[4], c[5], c[6], 1)
                    / Math.exp(-c[3] * c[6]), 1e-12);
        }
    }

    @Test
    void doubleNoTouchWideBandCollapsesToSingleNoTouch() {
        assertEquals(TouchOption.noTouch(100, 85, 0.04, 0.01, 0.2, 1, 1),
                TouchOption.doubleNoTouch(100, 85, 1e5, 0.04, 0.01, 0.2, 1, 1), 1e-12);
        assertEquals(TouchOption.noTouch(100, 120, 0.04, 0.01, 0.2, 1, 1),
                TouchOption.doubleNoTouch(100, 1e-3, 120, 0.04, 0.01, 0.2, 1, 1), 1e-12);
    }

    @Test
    void doubleNoTouchAgreesWithBrownianBridgeMonteCarlo() {
        double s0 = 100, lo = 88, up = 115, r = 0.04, q = 0.01, vol = 0.18, t = 0.75;
        int steps = 200, paths = 40_000;
        double dt = t / steps, m = (r - q - 0.5 * vol * vol) * dt, sd = vol * Math.sqrt(dt);
        double ll = Math.log(lo), lu = Math.log(up);
        SplittableRandom rnd = new SplittableRandom(11);
        int survived = 0;
        for (int p = 0; p < paths; p++) {
            double x = Math.log(s0);
            boolean alive = true;
            for (int i = 0; i < steps && alive; i++) {
                double y = x + m + sd * rnd.nextGaussian();
                if (y <= ll || y >= lu) {
                    alive = false;
                } else {
                    double pl = Math.exp(-2 * (x - ll) * (y - ll) / (sd * sd));
                    double pu = Math.exp(-2 * (lu - x) * (lu - y) / (sd * sd));
                    alive = rnd.nextDouble() >= pl + pu;
                }
                x = y;
            }
            if (alive) {
                survived++;
            }
        }
        double mc = (double) survived / paths;
        double se = Math.sqrt(mc * (1 - mc) / paths);
        double cf = TouchOption.doubleNoTouchProbability(s0, lo, up, r, q, vol, t);
        assertEquals(cf, mc, 4 * se + 2e-3);
        assertEquals(Math.exp(-r * t) * cf, TouchOption.doubleNoTouch(s0, lo, up, r, q, vol, t, 1), 1e-15);
    }

    @Test
    void doubleNoTouchEdges() {
        assertEquals(0, TouchOption.doubleNoTouchProbability(80, 90, 110, 0.05, 0, 0.2, 1));
        assertEquals(0, TouchOption.doubleNoTouchProbability(110, 90, 110, 0.05, 0, 0.2, 1));
        assertEquals(1, TouchOption.doubleNoTouchProbability(100, 90, 110, 0.05, 0, 0.2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> TouchOption.doubleNoTouchProbability(100, 110, 90, 0.05, 0, 0.2, 1));
    }
}
