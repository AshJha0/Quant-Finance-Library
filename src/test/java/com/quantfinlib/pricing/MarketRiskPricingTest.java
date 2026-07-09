package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.rates.KeyRateDurations;
import com.quantfinlib.rates.ShortRateModels;
import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Market-risk pricing models: Black-76, higher-order Greeks, Heston, short rates, KRDs. */
class MarketRiskPricingTest {

    // ------------------------------------------------------------------
    // Black-76
    // ------------------------------------------------------------------

    @Test
    void black76IsBlackScholesWithZeroCarryOnTheForward() {
        double f = 102;
        double k = 100;
        double r = 0.03;
        double vol = 0.25;
        double t = 0.75;
        // The identity in the house convention: BlackScholes' carry is the
        // YIELD q, so a driftless forward means q = r.
        assertEquals(BlackScholes.price(OptionType.CALL, f, k, r, r, vol, t),
                Black76.price(OptionType.CALL, f, k, r, vol, t), 1e-10,
                "Black-76 = Black-Scholes on the forward with q = r");
        // Put-call parity on the forward: C − P = df·(F − K).
        double c = Black76.price(OptionType.CALL, f, k, r, vol, t);
        double p = Black76.price(OptionType.PUT, f, k, r, vol, t);
        assertEquals(Math.exp(-r * t) * (f - k), c - p, 1e-10, "parity");
        // Implied vol round-trips.
        assertEquals(vol, Black76.impliedVol(OptionType.CALL, c, f, k, r, t), 1e-6);
        // Delta bounds and vega positivity.
        assertTrue(Black76.delta(OptionType.CALL, f, k, r, vol, t) > 0.5, "ITM-ish call");
        assertTrue(Black76.vega(f, k, r, vol, t) > 0);
        assertEquals(Math.exp(-r * t) * 2, Black76.price(OptionType.CALL, f, k, r, 0, t),
                1e-12, "zero vol = discounted intrinsic");
    }

    // ------------------------------------------------------------------
    // Higher-order Greeks vs finite differences of the first-order ones
    // ------------------------------------------------------------------

    @Test
    void vannaAndVolgaMatchFiniteDifferencesOfDeltaAndVega() {
        // Deliberately r != 2*carry etc. — parameters chosen so no
        // convention coincidence can fake agreement.
        double s = 100;
        double k = 110;
        double r = 0.02;
        double carry = 0.035;
        double vol = 0.3;
        double t = 0.5;
        double h = 1e-4;

        double vannaFd = (BlackScholes.delta(OptionType.CALL, s, k, r, carry, vol + h, t)
                - BlackScholes.delta(OptionType.CALL, s, k, r, carry, vol - h, t)) / (2 * h);
        assertEquals(vannaFd, HigherOrderGreeks.vanna(s, k, r, carry, vol, t), 1e-4,
                "vanna = d(delta)/d(vol), independently differenced");

        double volgaFd = (BlackScholes.vega(s, k, r, carry, vol + h, t)
                - BlackScholes.vega(s, k, r, carry, vol - h, t)) / (2 * h);
        assertEquals(volgaFd, HigherOrderGreeks.volga(s, k, r, carry, vol, t), 1e-3,
                "volga = d(vega)/d(vol)");

        // An OTM call's volga is positive (long wings love vol of vol).
        assertTrue(HigherOrderGreeks.volga(s, k, r, carry, vol, t) > 0);
        // Exchange-option cross-gamma is negative and shrinks as correlation rises.
        double lowCorr = HigherOrderGreeks.exchangeCrossGamma(100, 100, 0.3, 0.3, 0.2, 1);
        double highCorr = HigherOrderGreeks.exchangeCrossGamma(100, 100, 0.3, 0.3, 0.8, 1);
        assertTrue(lowCorr < 0 && highCorr < 0);
        assertTrue(Math.abs(highCorr) > Math.abs(lowCorr),
                "closer legs = sharper exchange-option kink");
    }

    // ------------------------------------------------------------------
    // Heston
    // ------------------------------------------------------------------

    @Test
    void hestonCollapsesToBlackScholesWhenVolOfVolVanishes() {
        // Small (not degenerate) vol-of-vol with v0 = theta: variance is
        // pinned near v0, so the price sits within O(sigmaV^2) of
        // BS(sqrt(v0)) — sigmaV = 0.01 keeps the integrand well-conditioned.
        // This tolerance is deliberately tight: it caught a genuine bug —
        // the naive complex sqrt lost the imaginary part near u = 0 and
        // biased every price ~0.5%; the stable form agrees to 4+ decimals.
        Heston.Params p = new Heston.Params(5, 0.04, 0.01, 0.0, 0.04);
        double heston = Heston.call(100, 100, 0.02, 0.0, 1.0, p);
        double bs = BlackScholes.price(OptionType.CALL, 100, 100, 0.02, 0.0, 0.2, 1.0);
        assertEquals(bs, heston, 5e-4, "the BS limit: " + heston + " vs " + bs);
    }

    @Test
    void hestonSurvivesShortDatedLowVolWhereAFixedWindowTruncates() {
        // 1 week at 4% vol: the integrand's decay scale ~1/(sigma*sqrt(T))
        // is ~180 here, so a FIXED u-window of 200 truncates real mass and
        // silently biases the price — the window must stretch with the
        // parameters (this pins that it does).
        Heston.Params p = new Heston.Params(5, 0.0016, 0.005, 0.0, 0.0016);
        double t = 1.0 / 52;
        double heston = Heston.call(100, 100, 0.02, 0.0, t, p);
        double bs = BlackScholes.price(OptionType.CALL, 100, 100, 0.02, 0.0, 0.04, t);
        assertEquals(bs, heston, 1e-4, "short-dated BS limit: " + heston + " vs " + bs);
    }

    @Test
    void hestonSemiAnalyticAgreesWithItsOwnMonteCarlo() {
        // Realistic equity-skew parameters (Feller violated, as markets do).
        Heston.Params p = new Heston.Params(2.0, 0.04, 0.5, -0.7, 0.04);
        assertTrue(p.feller() < 1, "deliberately in the violated regime");
        double analytic = Heston.call(100, 100, 0.02, 0.0, 1.0, p);
        double mc = Heston.callMonteCarlo(100, 100, 0.02, 0.0, 1.0, p, 200, 40_000, 42);
        assertEquals(analytic, mc, 0.35,
                "two independent routes to one price: " + analytic + " vs " + mc);
        // Put-call parity ties the put to the same integral.
        double put = Heston.put(100, 100, 0.02, 0.0, 1.0, p);
        double forward = 100 * Math.exp(0.02);
        assertEquals(analytic - Math.exp(-0.02) * (forward - 100), put, 1e-9);
        // The skew: with rho < 0, low strikes carry MORE implied vol than high.
        double lowK = Heston.call(100, 80, 0.02, 0.0, 1.0, p);
        double highK = Heston.call(100, 120, 0.02, 0.0, 1.0, p);
        double ivLow = BlackScholes.impliedVol(OptionType.CALL, lowK, 100, 80, 0.02, 0.0, 1.0);
        double ivHigh = BlackScholes.impliedVol(OptionType.CALL, highK, 100, 120, 0.02, 0.0, 1.0);
        assertTrue(ivLow > ivHigh, "rho<0 produces the equity skew: "
                + ivLow + " > " + ivHigh);
        assertThrows(IllegalArgumentException.class,
                () -> new Heston.Params(0, 0.04, 0.5, -0.7, 0.04));
        assertThrows(IllegalArgumentException.class,
                () -> Heston.call(Double.NaN, 100, 0.02, 0, 1, p));
        // The MC cross-check gates its market inputs exactly like call().
        assertThrows(IllegalArgumentException.class,
                () -> Heston.callMonteCarlo(Double.NaN, 100, 0.02, 0, 1, p, 10, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Heston.callMonteCarlo(100, 100, 0.02, 0, -1, p, 10, 100, 1));
    }

    // ------------------------------------------------------------------
    // Short-rate models
    // ------------------------------------------------------------------

    @Test
    void vasicekAndCirBondsBehaveLikeBonds() {
        // sigma = 0, r = b: the rate never moves -> P = e^{-bT} exactly.
        assertEquals(Math.exp(-0.03 * 5),
                ShortRateModels.vasicekBond(0.03, 0.5, 0.03, 0, 5), 1e-12,
                "the deterministic limit is exact");
        // Convexity: with vol, the Gaussian rate makes the bond WORTH MORE
        // (Jensen on e^{-∫r}).
        assertTrue(ShortRateModels.vasicekBond(0.03, 0.5, 0.03, 0.02, 5)
                > Math.exp(-0.03 * 5));
        // Both decrease in the short rate; both price below par for r > 0.
        assertTrue(ShortRateModels.vasicekBond(0.05, 0.5, 0.03, 0.01, 5)
                < ShortRateModels.vasicekBond(0.02, 0.5, 0.03, 0.01, 5));
        assertTrue(ShortRateModels.cirBond(0.05, 0.5, 0.03, 0.1, 5)
                < ShortRateModels.cirBond(0.02, 0.5, 0.03, 0.1, 5));
        assertTrue(ShortRateModels.cirBond(0.03, 0.5, 0.03, 0.1, 5) < 1);
        // Yields recover the log-price.
        assertEquals(0.03, ShortRateModels.vasicekYield(0.03, 0.5, 0.03, 0, 5), 1e-12);
        // Feller: 2ab vs sigma^2.
        assertTrue(ShortRateModels.cirFeller(0.5, 0.03, 0.1) > 1);
        assertTrue(ShortRateModels.cirFeller(0.1, 0.02, 0.2) < 1);
        // Exact Vasicek step is mean-reverting in expectation (z = 0).
        assertEquals(0.03 + (0.05 - 0.03) * Math.exp(-0.5),
                ShortRateModels.vasicekStep(0.05, 0.5, 0.03, 0.01, 1.0, 0), 1e-12);
        // CIR full truncation never sources vol from a negative rate.
        double stepped = ShortRateModels.cirStep(-0.01, 0.5, 0.03, 0.1, 1.0 / 252, 3.0);
        assertTrue(Double.isFinite(stepped), "no sqrt(negative)");
        assertThrows(IllegalArgumentException.class,
                () -> ShortRateModels.vasicekBond(0.03, 0, 0.03, 0.01, 5));
    }

    @Test
    void hullWhiteRepricesTodaysCurveByConstruction() {
        double[] tenors = {1, 2, 5, 10};
        double[] rates = {0.030, 0.030, 0.030, 0.030};    // flat: f(0,t) = 3% exactly
        YieldCurve curve = YieldCurve.ofZeroRates(tenors, rates);
        // At t = 0 with r = f(0,0), P(0,T) must come back exactly.
        double f0 = ShortRateModels.instantaneousForward(curve, 0);
        assertEquals(0.03, f0, 1e-6, "flat curve: instantaneous forward = the rate");
        assertEquals(curve.discountFactor(5),
                ShortRateModels.hullWhiteBond(curve, 0, 5, f0, 0.1, 0.01), 1e-6,
                "the fitted model disagrees with its own curve by nothing");
        // Away from the curve rate, higher r -> cheaper bond.
        assertTrue(ShortRateModels.hullWhiteBond(curve, 1, 5, 0.05, 0.1, 0.01)
                < ShortRateModels.hullWhiteBond(curve, 1, 5, 0.02, 0.1, 0.01));

        // A steep short end: z(t) = 0.02 + 0.01t exactly (two pillars,
        // linear interpolation), so f(0,t) = 0.02 + 0.02t and g = -ln P
        // is quadratic — the short-end stencil is exact there, where a
        // clamped central difference would report f near (t+h)/2 instead.
        YieldCurve steep = YieldCurve.ofZeroRates(
                new double[]{1e-4, 2}, new double[]{0.020001, 0.04});
        assertEquals(0.02002, ShortRateModels.instantaneousForward(steep, 0.001), 1e-7,
                "the forward AT t, not somewhere in a one-sided window");
        // Negative valuation time is rejected, not silently clamped to 0.
        assertThrows(IllegalArgumentException.class,
                () -> ShortRateModels.instantaneousForward(steep, -5));
    }

    // ------------------------------------------------------------------
    // Key-rate durations
    // ------------------------------------------------------------------

    @Test
    void keyRateSlicesAddBackUpToTheParallelDv01() {
        double[] tenors = {1, 2, 5, 10};
        double[] rates = {0.02, 0.025, 0.03, 0.032};
        YieldCurve curve = YieldCurve.ofZeroRates(tenors, rates);
        double[] krd = KeyRateDurations.keyRateDv01s(100, 0.04, 2, 5, curve);
        double parallel = KeyRateDurations.parallelDv01(100, 0.04, 2, 5, curve);

        double sum = 0;
        int maxAt = 0;
        for (int i = 0; i < krd.length; i++) {
            sum += krd[i];
            if (krd[i] > krd[maxAt]) {
                maxAt = i;
            }
        }
        assertEquals(parallel, sum, 0.02 * parallel,
                "the slices reassemble the parallel move (interpolation tolerance)");
        assertEquals(2, maxAt, "a 5y bond's rate risk lives at the 5y node");
        assertTrue(parallel > 0, "rates up, bond down — DV01 sign convention");
        assertTrue(krd[3] < krd[2], "little risk beyond the bond's maturity");
    }
}
