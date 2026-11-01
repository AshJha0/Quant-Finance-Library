package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Margrabe, Kirk and quanto pinned by their exact limits: each new
 * formula must collapse onto an already-tested pricer where the theory
 * says it must — the strongest cheap correctness check there is.
 */
class StructuredClosedFormsTest {

    // ------------------------------------------------------------------ Margrabe

    @Test
    void margrabeWithConstantSecondAssetIsABlackScholesCall() {
        // sigma2 = 0, q1 = 0: asset 2 is a bond worth S2 at expiry — the
        // exchange option IS a vanilla call struck at S2 with rate q2.
        double m = ExchangeOption.margrabe(100, 95, 0.0, 0.03, 0.25, 0.0, 0.5, 1.0);
        double bs = BlackScholes.price(OptionType.CALL, 100, 95, 0.03, 0.0, 0.25, 1.0);
        assertEquals(bs, m, 1e-12);
    }

    @Test
    void perfectlyCorrelatedEqualVolAssetsPayForwardIntrinsic() {
        // rho = 1, sigma1 = sigma2: the ratio cannot move — pure forward.
        double m = ExchangeOption.margrabe(100, 90, 0.01, 0.02, 0.30, 0.30, 1.0, 2.0);
        assertEquals(100 * Math.exp(-0.02) - 90 * Math.exp(-0.04), m, 1e-12);
        // And it is never negative even when the forward is under water.
        assertEquals(0, ExchangeOption.margrabe(90, 100, 0, 0, 0.30, 0.30, 1.0, 2.0), 0.0);
    }

    @Test
    void margrabeExpiryAndCorrelationBehavior() {
        assertEquals(10, ExchangeOption.margrabe(100, 90, 0, 0, 0.3, 0.2, 0.5, 0.0), 0.0);
        // Lower correlation -> higher ratio vol -> dearer option.
        double lowRho = ExchangeOption.margrabe(100, 100, 0, 0, 0.2, 0.2, -0.5, 1);
        double highRho = ExchangeOption.margrabe(100, 100, 0, 0, 0.2, 0.2, 0.8, 1);
        assertTrue(lowRho > highRho);
    }

    // ---------------------------------------------------------------------- Kirk

    @Test
    void kirkCollapsesToMargrabeAtZeroStrike() {
        double kirk = ExchangeOption.kirkSpreadCall(100, 90, 0, 0.05, 0.3, 0.2, 0.4, 1.5);
        double margrabeFwd = Math.exp(-0.05 * 1.5)
                * ExchangeOption.margrabe(100, 90, 0, 0, 0.3, 0.2, 0.4, 1.5);
        assertEquals(margrabeFwd, kirk, 1e-12);
    }

    @Test
    void kirkCollapsesToBlack76WithNoSecondLeg() {
        // f2 = 0: the "spread" is just an option on F1 struck at K, and
        // sigma2/rho must drop out entirely.
        double kirk = ExchangeOption.kirkSpreadCall(100, 0, 95, 0.03, 0.2, 0.7, -0.9, 1.0);
        double b76 = Black76.price(OptionType.CALL, 100, 95, 0.03, 0.2, 1.0);
        assertEquals(b76, kirk, 1e-12);
    }

    @Test
    void kirkGates() {
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeOption.kirkSpreadCall(100, -1, 95, 0, 0.2, 0.2, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeOption.kirkSpreadCall(100, 0, 0, 0, 0.2, 0.2, 0, 1)); // f2+K=0
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeOption.margrabe(100, 90, 0, 0, 0.2, 0.2, 1.5, 1));    // rho
    }

    // -------------------------------------------------------------------- quanto

    @Test
    void quantoForwardUsesTheForeignRateAndDiscountsDomestic() {
        // Nikkei-in-USD style: r_f = 0, r_d = 5%. F = S e^{(r_f - q - rho sS sX)T}.
        // Reference values computed independently (scipy, closed form of Hull's quanto).
        double fwd = QuantoOption.quantoForward(100, 0.0, 0.01, 0.2, 0.1, 0.3, 1);
        assertEquals(98.41273200552851, fwd, 1e-12);
        assertEquals(6.785980578296187,
                QuantoOption.price(OptionType.CALL, 100, 100, 0.05, 0.0, 0.01, 0.2, 0.1, 0.3, 1), 1e-12);
        assertEquals(8.295836599205705,
                QuantoOption.price(OptionType.PUT, 100, 100, 0.05, 0.0, 0.01, 0.2, 0.1, 0.3, 1), 1e-12);
        // The old code drifted at r_d: forward 100 e^{0.05-0.016} = 103.46, a 5% overstatement.
        assertTrue(Math.abs(fwd - 100 * Math.exp(0.034)) > 4);
    }

    @Test
    void zeroCorrelationQuantoIsTheForeignForwardDiscountedDomestic() {
        double q = QuantoOption.price(OptionType.CALL, 100, 100, 0.03, 0.01, 0.0, 0.2, 0.1, 0.0, 1);
        double f = 100 * Math.exp(0.01);
        double b76 = Math.exp(-0.03) * Black76.price(OptionType.CALL, f, 100, 0.0, 0.2, 1);
        assertEquals(b76, q, 1e-12);
    }

    @Test
    void quantoMatchesMonteCarloUnderTheForeignMeasure() {
        // Independent check: simulate S and X (domestic per foreign) under the
        // foreign risk-neutral measure and change numeraire with
        // dQd/dQf = X_0 e^{r_d T} / (X_T e^{r_f T}).
        double s0 = 100, k = 105, rd = 0.05, rf = 0.01, q = 0.02, sS = 0.25, sX = 0.12, rho = -0.4, t = 2;
        java.util.SplittableRandom rng = new java.util.SplittableRandom(20261101L);
        int n = 400_000;
        double sum = 0, sum2 = 0;
        double sqrtT = Math.sqrt(t);
        for (int i = 0; i < n; i++) {
            double z1 = rng.nextGaussian();
            double z2 = rho * z1 + Math.sqrt(1 - rho * rho) * rng.nextGaussian();
            double sT = s0 * Math.exp((rf - q - 0.5 * sS * sS) * t + sS * sqrtT * z1);
            // Under Q_f, X has drift r_d - r_f + sX^2.
            double xRatio = Math.exp((rd - rf + 0.5 * sX * sX) * t + sX * sqrtT * z2);
            double v = Math.max(sT - k, 0) * Math.exp(-rd * t) * Math.exp((rd - rf) * t) / xRatio;
            sum += v;
            sum2 += v * v;
        }
        double mean = sum / n;
        double se = Math.sqrt((sum2 / n - mean * mean) / n);
        double exact = QuantoOption.price(OptionType.CALL, s0, k, rd, rf, q, sS, sX, rho, t);
        assertEquals(exact, mean, 4 * se, "MC " + mean + " +- " + se);
    }

    // ------------------------------------------------------------ vol swap strike

    @Test
    void volSwapStrikeIsSqrtMinusConvexityCorrection() {
        // Zero vol-of-vol: exactly sqrt(K_var).
        assertEquals(0.2, VarianceSwap.volSwapStrike(0.04, 0.0), 0.0);
        // Brockhaus-Long at Var(V)=0.0008: 0.2 - 0.0008/(8*0.008) = 0.1875.
        assertEquals(0.1875, VarianceSwap.volSwapStrike(0.04, 0.0008), 1e-15);
        assertTrue(VarianceSwap.volSwapStrike(0.04, 0.0004) < 0.2,
                "Jensen: the vol strike sits below sqrt of the variance strike");
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.volSwapStrike(0, 0.0004));
        assertThrows(IllegalArgumentException.class,
                () -> VarianceSwap.volSwapStrike(0.04, -1e-9));
    }
}
