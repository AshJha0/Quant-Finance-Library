package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Digital, touch and barrier pricing: exact parities against the vanilla
 * pricer, limiting behavior, and Monte Carlo cross-checks of the
 * reflection-principle formulas (with the discrete-monitoring bias of the
 * simulation accounted for in the assertion direction).
 */
class ExoticOptionsTest {

    private static final double S = 1.0850;   // FX-style levels (EURUSD)
    private static final double R = 0.045;    // domestic (USD) rate
    private static final double Q = 0.030;    // foreign (EUR) rate = carry
    private static final double VOL = 0.09;
    private static final double T = 0.5;

    // ------------------------------------------------------------------
    // Digitals
    // ------------------------------------------------------------------

    @Test
    void digitalParitiesHoldExactly() {
        double k = 1.10;
        double cashCall = DigitalOption.cashOrNothing(OptionType.CALL, S, k, R, Q, VOL, T, 1);
        double cashPut = DigitalOption.cashOrNothing(OptionType.PUT, S, k, R, Q, VOL, T, 1);
        // Call + put digitals pay 1 in every state: worth the discount factor.
        assertEquals(Math.exp(-R * T), cashCall + cashPut, 1e-12);

        double assetCall = DigitalOption.assetOrNothing(OptionType.CALL, S, k, R, Q, VOL, T);
        double assetPut = DigitalOption.assetOrNothing(OptionType.PUT, S, k, R, Q, VOL, T);
        assertEquals(S * Math.exp(-Q * T), assetCall + assetPut, 1e-12);

        // A vanilla is asset-or-nothing minus K cash-or-nothings.
        double vanilla = BlackScholes.price(OptionType.CALL, S, k, R, Q, VOL, T);
        assertEquals(vanilla, assetCall - k * cashCall, 1e-12);
    }

    @Test
    void digitalExpiryAndValidation() {
        assertEquals(5, DigitalOption.cashOrNothing(OptionType.CALL, 1.10, 1.05, R, Q, VOL, 0, 5));
        assertEquals(0, DigitalOption.cashOrNothing(OptionType.CALL, 1.00, 1.05, R, Q, VOL, 0, 5));
        assertEquals(1.10, DigitalOption.assetOrNothing(OptionType.CALL, 1.10, 1.05, R, Q, VOL, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DigitalOption.cashOrNothing(OptionType.CALL, -1, 1, R, Q, VOL, T, 1));
    }

    // ------------------------------------------------------------------
    // Touches
    // ------------------------------------------------------------------

    @Test
    void touchPropertiesAndComplement() {
        double upper = 1.12;
        double p = TouchOption.hitProbability(S, upper, R, Q, VOL, T);
        assertTrue(p > 0 && p < 1);
        // Barrier at spot: certain touch. Barrier far away: nearly none.
        assertEquals(1, TouchOption.hitProbability(S, S, R, Q, VOL, T));
        assertTrue(TouchOption.hitProbability(S, 2.5, R, Q, VOL, T) < 1e-9);
        // One-touch + no-touch = discounted payout (complement identity).
        double ot = TouchOption.oneTouch(S, upper, R, Q, VOL, T, 100);
        double nt = TouchOption.noTouch(S, upper, R, Q, VOL, T, 100);
        assertEquals(100 * Math.exp(-R * T), ot + nt, 1e-9);
        // Longer expiry can only raise the hit probability.
        assertTrue(TouchOption.hitProbability(S, upper, R, Q, VOL, 1.0) > p);
        // Lower barrier branch.
        double pLow = TouchOption.hitProbability(S, 1.05, R, Q, VOL, T);
        assertTrue(pLow > 0 && pLow < 1);
    }

    @Test
    void touchProbabilityMatchesMonteCarlo() {
        double upper = 1.11;
        double closed = TouchOption.hitProbability(S, upper, R, Q, VOL, T);
        double mc = mcHitProbability(S, upper, true);
        // Discrete monitoring misses intra-step touches: MC is biased LOW.
        assertTrue(mc <= closed + 0.005, "mc=" + mc + " closed=" + closed);
        assertEquals(closed, mc, 0.03);

        double lower = 1.06;
        double closedLow = TouchOption.hitProbability(S, lower, R, Q, VOL, T);
        double mcLow = mcHitProbability(S, lower, false);
        assertEquals(closedLow, mcLow, 0.03);
    }

    // ------------------------------------------------------------------
    // Barriers
    // ------------------------------------------------------------------

    @Test
    void inOutParityReconstructsTheVanilla() {
        double k = 1.10;
        double h = 1.05;
        double vanilla = BlackScholes.price(OptionType.CALL, S, k, R, Q, VOL, T);
        double ki = BarrierOption.downAndInCall(S, k, h, R, Q, VOL, T);
        double ko = BarrierOption.downAndOutCall(S, k, h, R, Q, VOL, T);
        assertEquals(vanilla, ki + ko, 1e-12);
        assertTrue(ki > 0 && ko > 0);

        double hp = 1.13;
        double kp = 1.08;
        double vanillaPut = BlackScholes.price(OptionType.PUT, S, kp, R, Q, VOL, T);
        assertEquals(vanillaPut,
                BarrierOption.upAndInPut(S, kp, hp, R, Q, VOL, T)
                        + BarrierOption.upAndOutPut(S, kp, hp, R, Q, VOL, T), 1e-12);
    }

    @Test
    void limitingBarriersRecoverVanillaAndZero() {
        double k = 1.10;
        double vanilla = BlackScholes.price(OptionType.CALL, S, k, R, Q, VOL, T);
        // A barrier miles below spot never knocks: KO ≈ vanilla, KI ≈ 0.
        assertEquals(vanilla, BarrierOption.downAndOutCall(S, k, 0.40, R, Q, VOL, T), 1e-9);
        assertTrue(BarrierOption.downAndInCall(S, k, 0.40, R, Q, VOL, T) < 1e-9);
        // A barrier just below spot almost surely knocks: KO ≈ 0.
        assertTrue(BarrierOption.downAndOutCall(S, k, S - 1e-4, R, Q, VOL, T) < 0.002);
    }

    @Test
    void barrierPriceMatchesMonteCarlo() {
        double k = 1.10;
        double h = 1.05;
        double closed = BarrierOption.downAndOutCall(S, k, h, R, Q, VOL, T);
        double mc = mcDownAndOutCall(S, k, h);
        // Discrete monitoring misses knocks: MC knock-OUT is biased HIGH.
        assertTrue(mc >= closed - 0.0005, "mc=" + mc + " closed=" + closed);
        assertEquals(closed, mc, 0.002);
    }

    @Test
    void reverseAndBreachedBarriersAreRejected() {
        assertThrows(IllegalArgumentException.class, // breached down barrier
                () -> BarrierOption.downAndOutCall(S, 1.10, 1.09, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, // reverse: H > K on a call
                () -> BarrierOption.downAndOutCall(1.20, 1.02, 1.05, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, // breached up barrier
                () -> BarrierOption.upAndOutPut(S, 1.08, 1.05, R, Q, VOL, T));
        assertThrows(IllegalArgumentException.class, // reverse: H < K on a put
                () -> BarrierOption.upAndOutPut(1.00, 1.15, 1.10, R, Q, VOL, T));
    }

    // ------------------------------------------------------------------
    // Deterministic GBM Monte Carlo (log-Euler is exact for GBM per step)
    // ------------------------------------------------------------------

    private static final int PATHS = 60_000;
    private static final int STEPS = 400;

    private static double mcHitProbability(double s0, double barrier, boolean upper) {
        Random rng = new Random(42);
        double dt = T / STEPS;
        double drift = (R - Q - 0.5 * VOL * VOL) * dt;
        double diff = VOL * Math.sqrt(dt);
        int hits = 0;
        for (int p = 0; p < PATHS; p++) {
            double s = s0;
            for (int i = 0; i < STEPS; i++) {
                s *= Math.exp(drift + diff * rng.nextGaussian());
                if (upper ? s >= barrier : s <= barrier) {
                    hits++;
                    break;
                }
            }
        }
        return (double) hits / PATHS;
    }

    private static double mcDownAndOutCall(double s0, double strike, double barrier) {
        Random rng = new Random(4242);
        double dt = T / STEPS;
        double drift = (R - Q - 0.5 * VOL * VOL) * dt;
        double diff = VOL * Math.sqrt(dt);
        double sum = 0;
        for (int p = 0; p < PATHS; p++) {
            double s = s0;
            boolean knocked = false;
            for (int i = 0; i < STEPS; i++) {
                s *= Math.exp(drift + diff * rng.nextGaussian());
                if (s <= barrier) {
                    knocked = true;
                    break;
                }
            }
            if (!knocked) {
                sum += Math.max(0, s - strike);
            }
        }
        return Math.exp(-R * T) * sum / PATHS;
    }
}
