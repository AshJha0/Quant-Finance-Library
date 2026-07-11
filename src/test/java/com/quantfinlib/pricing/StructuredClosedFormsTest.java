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
    void zeroCorrelationQuantoIsThePlainVanilla() {
        double q = QuantoOption.price(OptionType.CALL, 100, 100, 0.03, 0.01, 0.2, 0.1, 0.0, 1);
        double bs = BlackScholes.price(OptionType.CALL, 100, 100, 0.03, 0.01, 0.2, 1);
        assertEquals(bs, q, 1e-12);
        assertEquals(100 * Math.exp(0.02), QuantoOption.quantoForward(
                100, 0.03, 0.01, 0.2, 0.1, 0.0, 1), 1e-12);
    }

    @Test
    void positiveCorrelationLowersTheQuantoForwardAndCall() {
        // Asset up when foreign ccy strengthens: the hedger's drag, priced.
        double f0 = QuantoOption.quantoForward(100, 0.03, 0.01, 0.2, 0.1, 0.0, 1);
        double fPos = QuantoOption.quantoForward(100, 0.03, 0.01, 0.2, 0.1, 0.6, 1);
        assertTrue(fPos < f0);
        // Exact drift: rho*sigmaS*sigmaFX = 0.012 off the carry.
        assertEquals(100 * Math.exp(0.02 - 0.012), fPos, 1e-12);
        double callPos = QuantoOption.price(OptionType.CALL, 100, 100, 0.03, 0.01, 0.2, 0.1, 0.6, 1);
        double call0 = QuantoOption.price(OptionType.CALL, 100, 100, 0.03, 0.01, 0.2, 0.1, 0.0, 1);
        assertTrue(callPos < call0, "a lower forward makes the call cheaper");
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
