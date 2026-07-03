package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackScholesTest {

    // Textbook case: S=100, K=100, r=5%, q=0, sigma=20%, T=1.
    private static final double S = 100, K = 100, R = 0.05, Q = 0, VOL = 0.2, T = 1;

    @Test
    void matchesTextbookValues() {
        assertEquals(10.4506, BlackScholes.price(OptionType.CALL, S, K, R, Q, VOL, T), 1e-3);
        assertEquals(5.5735, BlackScholes.price(OptionType.PUT, S, K, R, Q, VOL, T), 1e-3);
        assertEquals(0.6368, BlackScholes.delta(OptionType.CALL, S, K, R, Q, VOL, T), 1e-3);
        assertEquals(0.0188, BlackScholes.gamma(S, K, R, Q, VOL, T), 1e-3);
        assertEquals(37.524, BlackScholes.vega(S, K, R, Q, VOL, T), 1e-2);
        assertEquals(-6.414, BlackScholes.theta(OptionType.CALL, S, K, R, Q, VOL, T), 1e-2);
        assertEquals(53.232, BlackScholes.rho(OptionType.CALL, S, K, R, Q, VOL, T), 1e-2);
    }

    @Test
    void putCallParityHoldsWithCarry() {
        // C - P = S*e^(-qT) - K*e^(-rT), q = foreign rate (Garman-Kohlhagen).
        double q = 0.03;
        double call = BlackScholes.price(OptionType.CALL, S, K, R, q, VOL, T);
        double put = BlackScholes.price(OptionType.PUT, S, K, R, q, VOL, T);
        assertEquals(S * Math.exp(-q * T) - K * Math.exp(-R * T), call - put, 1e-9);
    }

    @Test
    void greeksRecordConsistentWithIndividualFunctions() {
        BlackScholes.Greeks g = BlackScholes.greeks(OptionType.PUT, S, K, R, Q, VOL, T);
        assertEquals(BlackScholes.price(OptionType.PUT, S, K, R, Q, VOL, T), g.price(), 1e-12);
        assertEquals(BlackScholes.delta(OptionType.PUT, S, K, R, Q, VOL, T), g.delta(), 1e-12);
        assertTrue(g.delta() < 0 && g.gamma() > 0 && g.vega() > 0);
    }

    @Test
    void impliedVolRoundTrips() {
        double price = BlackScholes.price(OptionType.CALL, S, 110, R, Q, 0.27, 0.5);
        double iv = BlackScholes.impliedVol(OptionType.CALL, price, S, 110, R, Q, 0.5);
        assertEquals(0.27, iv, 1e-6);
    }

    @Test
    void expiryCollapsesToIntrinsic() {
        assertEquals(5, BlackScholes.price(OptionType.CALL, 105, 100, R, Q, VOL, 0), 1e-12);
        assertEquals(0, BlackScholes.price(OptionType.PUT, 105, 100, R, Q, VOL, 0), 1e-12);
        assertEquals(1, BlackScholes.delta(OptionType.CALL, 105, 100, R, Q, VOL, 0), 1e-12);
        assertEquals(0, BlackScholes.gamma(105, 100, R, Q, VOL, 0), 1e-12);
    }
}
