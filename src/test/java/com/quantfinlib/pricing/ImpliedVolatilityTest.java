package com.quantfinlib.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

class ImpliedVolatilityTest {

    /**
     * impliedVol(price(σ)) = σ across moneyness from 1e-6-delta wings to deep
     * ITM, total vols from 1e-4 to 4, and expiries from one day to 30 years.
     * The old bisection on a 7.5e-8 CDF could not get below about 1e-6 in the
     * wings and returned NaN for many of these points.
     */
    @Test
    void roundTripSweepAcrossWingsAndExpiries() {
        double[] expiries = {1.0 / 365, 7.0 / 365, 0.25, 1, 5, 30};
        double[] vols = {0.01, 0.05, 0.2, 0.6, 1.5};
        double[] moneyness = {-6, -4, -2.5, -1, -0.3, -0.05, 0, 0.05, 0.3, 1, 2.5, 4, 6};
        int checked = 0;
        for (double t : expiries) {
            for (double vol : vols) {
                double sd = vol * Math.sqrt(t);
                for (double m : moneyness) {
                    double f = 100;
                    double k = f * Math.exp(m * sd); // m standard deviations away
                    for (OptionType type : OptionType.values()) {
                        double p = Black76.price(type, f, k, 0.0, vol, t);
                        double intrinsic = Math.max(type.sign() * (f - k), 0);
                        double vega = Black76.vega(f, k, 0.0, vol, t);
                        if (!(p - intrinsic > 1e-300) || !(vega > 0)) {
                            continue;
                        }
                        double iv = Black76.impliedVol(type, p, f, k, 0.0, t);
                        // Error budget: 1e-11 relative, plus what the pricer's own rounding
                        // (a few ulps of max(F, K), visible for deep ITM and ATM low vol) moves sigma by.
                        double tol = 1e-11 * vol + 50 * Math.ulp(Math.max(f, k)) / vega;
                        assertEquals(vol, iv, tol, type + " t=" + t + " vol=" + vol + " m=" + m);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 600, "checked " + checked);
    }

    @Test
    void deepOtmWingsRecoverToNearMachinePrecision() {
        // Out-of-the-money options carry their whole value as time value, so
        // the inversion should be essentially exact even at 1e-200 prices.
        for (double vol : new double[] {0.05, 0.3, 1.0}) {
            for (double k : new double[] {1e-3, 0.1, 10, 1e3, 1e5}) {
                OptionType type = k > 1 ? OptionType.CALL : OptionType.PUT;
                double p = Black76.price(type, 1.0, k, 0, vol, 2.0);
                if (!(p > 1e-290)) {
                    continue;
                }
                double iv = Black76.impliedVol(type, p, 1.0, k, 0, 2.0);
                assertEquals(vol, iv, vol * 1e-12, "k=" + k + " vol=" + vol + " p=" + p);
            }
        }
    }

    @Test
    void lowTotalVolAtTheMoney() {
        for (double s : new double[] {1e-8, 1e-6, 1e-4, 1e-2}) {
            double p = Black76.price(OptionType.CALL, 1.0, 1.0, 0, s, 1);
            assertEquals(s, Black76.impliedVol(OptionType.CALL, p, 1.0, 1.0, 0, 1), s * 1e-9 + 4 * Math.ulp(1.0) / 0.3989);
        }
    }

    @Test
    void attainabilityConventions() {
        assertTrue(Double.isNaN(ImpliedVolatility.black(OptionType.CALL, 101, 100, 100, 1)));
        assertTrue(Double.isNaN(ImpliedVolatility.black(OptionType.CALL, 9, 110, 100, 1)));
        assertEquals(0.0, ImpliedVolatility.black(OptionType.CALL, 10, 110, 100, 1));
        assertTrue(Double.isNaN(ImpliedVolatility.black(OptionType.PUT, 1, 100, 100, 0)));
    }

    @Test
    void spotFormRoundTripsWithRatesAndCarry() {
        double p = BlackScholes.price(OptionType.PUT, 100, 70, 0.05, 0.02, 0.45, 3);
        assertEquals(0.45, BlackScholes.impliedVol(OptionType.PUT, p, 100, 70, 0.05, 0.02, 3), 1e-13);
        p = BlackScholes.price(OptionType.CALL, 100, 60, 0.05, 0.02, 0.25, 0.5); // deep ITM call
        assertEquals(0.25, BlackScholes.impliedVol(OptionType.CALL, p, 100, 60, 0.05, 0.02, 0.5), 1e-9);
    }

    // ------------------------------------------------------------- zero vol

    @Test
    void zeroVolGreeksAreTheDeterministicLimitsNotNaN() {
        double s = 100, k = 95, r = 0.05, q = 0.01, t = 1;
        double cf = Math.exp(-q * t), df = Math.exp(-r * t);
        BlackScholes.Greeks c = BlackScholes.greeks(OptionType.CALL, s, k, r, q, 0.0, t);
        assertEquals(s * cf - k * df, c.price(), 1e-12);
        assertEquals(cf, c.delta(), 1e-15);
        assertEquals(0.0, c.gamma());
        assertEquals(0.0, c.vega());
        assertEquals(q * s * cf - r * k * df, c.theta(), 1e-12);
        assertEquals(k * t * df, c.rho(), 1e-12);
        BlackScholes.Greeks p = BlackScholes.greeks(OptionType.PUT, s, k, r, q, 0.0, t);
        assertEquals(0.0, p.price(), 0.0);
        assertEquals(0.0, p.delta(), 0.0);
        assertEquals(0.0, p.theta(), 0.0);
        assertEquals(0.0, p.rho(), 0.0);
        // The limits agree with a tiny positive vol.
        assertEquals(c.delta(), BlackScholes.delta(OptionType.CALL, s, k, r, q, 1e-6, t), 1e-12);
        assertEquals(c.theta(), BlackScholes.theta(OptionType.CALL, s, k, r, q, 1e-6, t), 1e-9);
        // At the forward the vega limit is S e^{-qT} phi(0) sqrt(T), and delta is half.
        double fwdStrike = s * Math.exp((r - q) * t);
        assertEquals(s * cf * 0.3989422804014327,
                BlackScholes.vega(s, fwdStrike, r, q, 0.0, t), 1e-12);
        assertEquals(0.5 * cf, BlackScholes.delta(OptionType.CALL, s, fwdStrike, r, q, 0.0, t), 1e-15);
    }

    @Test
    void binomialTreeRejectsZeroVolInsteadOfReturningNaN() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> BinomialTree.price(OptionType.CALL, BinomialTree.ExerciseStyle.AMERICAN,
                        100, 100, 0.05, 0.05, 0.0, 1, 100));
    }
}
