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
        // normInv and normCdf are now at double precision.
        assertEquals(0.25, forwardDelta(F, k25c, ATM, T, true), 1e-14);
        assertEquals(-0.25, forwardDelta(F, k25p, ATM, T, false), 1e-14);
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
        assertEquals(0, dc + dp, 1e-15); // straddle delta cancels
    }

    @Test
    void surfaceReproducesBrokerQuoteIdentities() {
        // Quotes taken as smile-strangle butterflies: the RR/BF identities hold
        // by definition. (The default, a broker strangle, is tested below.)
        FxVolSurface s = FxVolSurface.builder()
                .butterflyQuote(FxVolSurface.ButterflyQuote.SMILE_STRANGLE)
                .add(T, F, ATM, RR25, BF25).build();
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
        // The surface evaluates each bracketing smile at the TARGET expiry's
        // log-moneyness (here 0), i.e. at each pillar's own forward.
        double vLo = s.vol(T, s.forwardAt(T));
        double vHi = s.vol(1.0, s.forwardAt(1.0));
        double wExpected = vLo * vLo * T + (vHi * vHi * 1.0 - vLo * vLo * T) * (tMid - T) / (1.0 - T);
        assertEquals(Math.sqrt(wExpected / tMid), s.vol(tMid, s.forwardAt(tMid)), 1e-12);
        // Outside the quoted range: flat in the boundary smile.
        assertEquals(s.vol(T, s.forwardAt(tMid)), s.vol(0.05, s.forwardAt(tMid)), 1e-12);
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

    @Test
    void addRejectsRrBfCombinationThatProducesNonPositiveWingVol() {
        // atm=0.01, bf=0.0, rr=-0.02 -> 25-delta call wing = atm + bf + rr/2 = -0.01.
        assertThrows(IllegalArgumentException.class,
                () -> FxVolSurface.builder().add(T, F, 0.01, -0.02, 0.0));
    }

    @Test
    void strikeForDeltaAcceptsPaDeltaNearTheTrueContinuousPeak() {
        // The sampled 200-point grid used to under-estimate the true peak of
        // the premium-adjusted call delta; a delta just inside the true peak
        // (found here via a fine independent scan) must now be accepted
        // instead of being spuriously rejected as "unattainable".
        double sv = ATM * Math.sqrt(T);
        double lo = F * Math.exp(-8 * sv);
        double hi = F * Math.exp(8 * sv);
        double bestVal = -1;
        for (int i = 0; i <= 200_000; i++) {
            double k = lo * Math.pow(hi / lo, i / 200_000.0);
            double d2 = Math.log(F / k) / sv - 0.5 * sv;
            double v = (k / F) * MathUtils.normCdf(d2);
            if (v > bestVal) {
                bestVal = v;
            }
        }
        double target = bestVal - 1e-5; // just inside the true peak
        double k = FxVolSurface.strikeForDelta(F, ATM, T, target, true, true);
        double d2 = Math.log(F / k) / sv - 0.5 * sv;
        assertEquals(target, (k / F) * MathUtils.normCdf(d2), 1e-6);
    }

    // ---------------------------------------------------- broker strangle (F45)

    private static double black(double f, double k, double vol, double t, boolean call) {
        double sv = vol * Math.sqrt(t);
        double d1 = Math.log(f / k) / sv + 0.5 * sv;
        double d2 = d1 - sv;
        return call ? f * MathUtils.normCdf(d1) - k * MathUtils.normCdf(d2)
                : k * MathUtils.normCdf(-d2) - f * MathUtils.normCdf(-d1);
    }

    @Test
    void brokerStrangleIsRepricedOnTheSolvedSmile() {
        // USDJPY-like skew, 1Y: RR large relative to BF, the case where the
        // smile and broker butterflies differ most.
        double f = 145.0, t = 1.0, atm = 0.105, rr = -0.025, bfBroker = 0.004;
        FxVolSurface s = FxVolSurface.builder().add(t, f, atm, rr, bfBroker).build();
        FxVolSurface.SmilePillar p = s.pillar(0);
        // Smile RR identity holds on the solved pillars.
        assertEquals(rr, p.vols()[2] - p.vols()[0], 1e-12);
        double bfSmile = (p.vols()[2] + p.vols()[0]) / 2 - p.vols()[1];
        assertEquals(s.smileButterfly25(0), bfSmile, 1e-12);
        // The market strangle: strikes at one vol atm + bf, priced at that vol...
        double vMs = atm + bfBroker;
        double kc = FxVolSurface.strikeForDelta(f, vMs, t, 0.25, true, false);
        double kp = FxVolSurface.strikeForDelta(f, vMs, t, -0.25, false, false);
        double target = black(f, kc, vMs, t, true) + black(f, kp, vMs, t, false);
        // ...is worth the same priced on the smile.
        double onSmile = black(f, kc, s.vol(t, kc), t, true) + black(f, kp, s.vol(t, kp), t, false);
        assertEquals(target, onSmile, 1e-12 * target);
        // With skew the smile butterfly is above the broker one, by a margin that
        // matters against a typical bid/ask (several hundredths of a vol here).
        assertTrue(bfSmile - bfBroker > 2e-4, "bfSmile=" + bfSmile);
        // Treating the broker quote as a smile quote (the old behaviour) does
        // not reprice the market strangle.
        FxVolSurface naive = FxVolSurface.builder()
                .butterflyQuote(FxVolSurface.ButterflyQuote.SMILE_STRANGLE)
                .add(t, f, atm, rr, bfBroker).build();
        double naiveValue = black(f, kc, naive.vol(t, kc), t, true)
                + black(f, kp, naive.vol(t, kp), t, false);
        assertTrue(Math.abs(naiveValue - target) > 1e-4 * target);
    }

    @Test
    void withoutSkewBrokerAndSmileButterfliesCoincide() {
        FxVolSurface s = FxVolSurface.builder().add(T, F, ATM, 0.0, BF25).build();
        assertEquals(BF25, s.smileButterfly25(0), 1e-13);
    }

    @Test
    void tenAndTwentyFiveDeltaBrokerStranglesAreBothRepriced() {
        double t = 1.0, f = 1.0920, atm = 0.083;
        FxVolSurface s = FxVolSurface.builder()
                .add(t, f, atm, -0.012, 0.0030, -0.022, 0.0095).build();
        for (double[] lvl : new double[][]{{0.25, 0.0030}, {0.10, 0.0095}}) {
            double vMs = atm + lvl[1];
            double kc = FxVolSurface.strikeForDelta(f, vMs, t, lvl[0], true, false);
            double kp = FxVolSurface.strikeForDelta(f, vMs, t, -lvl[0], false, false);
            double target = black(f, kc, vMs, t, true) + black(f, kp, vMs, t, false);
            double onSmile = black(f, kc, s.vol(t, kc), t, true) + black(f, kp, s.vol(t, kp), t, false);
            assertEquals(target, onSmile, 1e-10 * target, "delta " + lvl[0]);
        }
    }

    @Test
    void spotDeltaAndAtmForwardConventions() {
        double dfF = Math.exp(-0.04 * T);
        double k = FxVolSurface.strikeForDelta(F, ATM, T, 0.25, true,
                FxVolSurface.DeltaConvention.SPOT, dfF);
        // Spot delta = e^{-r_f T} N(d1).
        assertEquals(0.25, dfF * forwardDelta(F, k, ATM, T, true), 1e-13);
        double kpa = FxVolSurface.strikeForDelta(F, ATM, T, -0.25, false,
                FxVolSurface.DeltaConvention.SPOT_PREMIUM_ADJUSTED, dfF);
        double sv = ATM * Math.sqrt(T);
        double d2 = Math.log(F / kpa) / sv - 0.5 * sv;
        assertEquals(-0.25, -dfF * (kpa / F) * MathUtils.normCdf(-d2), 1e-12);

        FxVolSurface s = FxVolSurface.builder()
                .atmConvention(FxVolSurface.AtmConvention.FORWARD)
                .deltaConvention(FxVolSurface.DeltaConvention.SPOT)
                .add(T, F, dfF, ATM, RR25, BF25, Double.NaN, Double.NaN).build();
        assertEquals(F, s.pillar(0).strikes()[1], 1e-15);
        assertEquals(FxVolSurface.DeltaConvention.SPOT, s.deltaConvention());
    }
}
