package com.quantfinlib.fx;

import com.quantfinlib.util.MathUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delta-quoted FX smile: strike solving round-trips the target delta, the
 * surface reproduces the broker quote identities (RR/BF), the delta-neutral
 * straddle really is delta-neutral, and premium-adjusted solving differs
 * from unadjusted in the documented direction.
 */
class FxVolSurfaceTest {

    private static final double F = 1.0850;   // EURUSD 3M forward
    private static final double T = 0.25;
    private static final double ATM = 0.078;  // 7.8 vols
    private static final double RR25 = -0.010; // EURUSD-style: puts over calls
    private static final double BF25 = 0.0022;

    private FxVolSurface surface() {
        return FxVolSurface.builder()
                .add(T, F, ATM, RR25, BF25)
                .add(1.0, 1.0920, 0.083, -0.012, 0.0030, -0.020, 0.0095)
                .build();
    }

    /** Forward delta of a call/put at a strike under Black (forward measure). */
    private static double forwardDelta(double f, double k, double vol, double t, boolean call) {
        double sv = vol * Math.sqrt(t);
        double d1 = Math.log(f / k) / sv + 0.5 * sv;
        return call ? MathUtils.normCdf(d1) : -MathUtils.normCdf(-d1);
    }

    @Test
    void strikeForDeltaRoundTripsTheTargetDelta() {
        double k25c = FxVolSurface.strikeForDelta(F, ATM, T, 0.25, true, false);
        double k25p = FxVolSurface.strikeForDelta(F, ATM, T, -0.25, false, false);
        // Tolerance bounded by MathUtils.normInv accuracy (~1e-9 in p-space).
        assertEquals(0.25, forwardDelta(F, k25c, ATM, T, true), 1e-6);
        assertEquals(-0.25, forwardDelta(F, k25p, ATM, T, false), 1e-6);
        // OTM strikes sit on the expected sides of the forward.
        assertTrue(k25c > F);
        assertTrue(k25p < F);
    }

    @Test
    void dnsStrikeIsDeltaNeutral() {
        double k = FxVolSurface.dnsStrike(F, ATM, T, false);
        assertEquals(F * Math.exp(0.5 * ATM * ATM * T), k, 1e-12);
        double dc = forwardDelta(F, k, ATM, T, true);
        double dp = forwardDelta(F, k, ATM, T, false);
        assertEquals(0, dc + dp, 1e-7); // straddle delta cancels (normCdf precision)
    }

    @Test
    void surfaceReproducesBrokerQuoteIdentities() {
        FxVolSurface s = surface();
        FxVolSurface.SmilePillar p = s.pillar(0);
        // Three pillars at 3M (no 10Δ): 25P, ATM, 25C, strikes ascending.
        assertEquals(3, p.strikes().length);
        double v25p = p.vols()[0];
        double vAtm = p.vols()[1];
        double v25c = p.vols()[2];
        assertEquals(RR25, v25c - v25p, 1e-12);              // risk reversal
        assertEquals(BF25, (v25c + v25p) / 2 - vAtm, 1e-12); // butterfly
        assertEquals(ATM, vAtm, 1e-12);
        // Surface lookup at a pillar strike returns the pillar vol.
        assertEquals(v25c, s.vol(T, p.strikes()[2]), 1e-10);
        // Negative RR: put wing above call wing (EURUSD skew).
        assertTrue(v25p > v25c);
    }

    @Test
    void tenDeltaWingsExtendTheSmile() {
        FxVolSurface s = surface();
        FxVolSurface.SmilePillar p = s.pillar(1);
        assertEquals(5, p.strikes().length);
        // Wing vols beyond the wings are flat (no explosive extrapolation).
        assertEquals(p.vols()[0], s.vol(1.0, p.strikes()[0] * 0.90), 1e-10);
        assertEquals(p.vols()[4], s.vol(1.0, p.strikes()[4] * 1.10), 1e-10);
    }

    @Test
    void timeInterpolationIsLinearInTotalVariance() {
        FxVolSurface s = surface();
        double tMid = 0.5;
        double f = s.forwardAt(tMid);
        double k = f; // zero log-moneyness at tMid
        // The surface evaluates each bracketing smile at the TARGET expiry's
        // log-moneyness (here 0), i.e. at each pillar's own forward.
        double vLo = s.vol(T, s.forwardAt(T));
        double vHi = s.vol(1.0, s.forwardAt(1.0));
        double wExpected = vLo * vLo * T + (vHi * vHi * 1.0 - vLo * vLo * T) * (tMid - T) / (1.0 - T);
        assertEquals(Math.sqrt(wExpected / tMid), s.vol(tMid, k), 1e-12);
        // Outside the quoted range: flat in the boundary smile.
        assertEquals(s.vol(T, k), s.vol(0.05, k), 1e-12);
    }

    @Test
    void premiumAdjustedSolvingHitsThePaDelta() {
        double sv = ATM * Math.sqrt(T);
        double k = FxVolSurface.strikeForDelta(F, ATM, T, 0.25, true, true);
        double d2 = Math.log(F / k) / sv - 0.5 * sv;
        assertEquals(0.25, (k / F) * MathUtils.normCdf(d2), 1e-9);
        // Premium adjustment lowers the call strike for the same delta.
        double kUnadj = FxVolSurface.strikeForDelta(F, ATM, T, 0.25, true, false);
        assertTrue(k < kUnadj);
        // PA DNS strike is below forward (F·e^{−σ²τ/2}).
        assertTrue(FxVolSurface.dnsStrike(F, ATM, T, true) < F);
        // Put side round-trips too.
        double kp = FxVolSurface.strikeForDelta(F, ATM, T, -0.25, false, true);
        double d2p = Math.log(F / kp) / sv - 0.5 * sv;
        assertEquals(-0.25, -(kp / F) * MathUtils.normCdf(-d2p), 1e-9);
    }

    @Test
    void validationRejectsBadInput() {
        assertThrows(IllegalStateException.class, () -> FxVolSurface.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> FxVolSurface.builder().add(0, F, ATM, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FxVolSurface.strikeForDelta(F, ATM, T, 1.5, true, false));
        assertThrows(IllegalArgumentException.class, () -> surface().vol(T, -1));
        // An unattainable premium-adjusted delta is reported, not silently clamped.
        assertThrows(IllegalArgumentException.class,
                () -> FxVolSurface.strikeForDelta(F, ATM, T, 0.999, true, true));
    }
}
