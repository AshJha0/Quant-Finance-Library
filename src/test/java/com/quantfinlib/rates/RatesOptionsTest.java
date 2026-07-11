package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swaptions and caps priced off the curve, pinned by the two parities
 * that any correct implementation must satisfy EXACTLY, whatever the vol:
 * payer − receiver = annuity·(F − K), and cap − floor = the swap PV.
 */
class RatesOptionsTest {

    private static YieldCurve flat5() {
        double[] tenors = new double[10];
        double[] zeros = new double[10];
        for (int i = 0; i < 10; i++) {
            tenors[i] = i + 1;
            zeros[i] = 0.05;
        }
        return YieldCurve.ofZeroRates(tenors, zeros);
    }

    @Test
    void flatCurveForwardSwapRateIsTheSimpleForward() {
        // Flat cc curve: every annual simple forward is e^0.05 - 1, and a
        // par swap rate over identical forwards equals that forward.
        YieldCurve c = flat5();
        double expected = Math.exp(0.05) - 1;
        assertEquals(expected, RatesOptions.forwardSwapRate(c, 1, 5), 1e-12);
        assertEquals(expected, RatesOptions.forwardSwapRate(c, 0, 10), 1e-12);
    }

    @Test
    void swaptionPutCallParityIsExact() {
        YieldCurve c = flat5();
        double strike = 0.04;
        double payer = RatesOptions.swaption(c, 1, 5, strike, 0.25, true);
        double receiver = RatesOptions.swaption(c, 1, 5, strike, 0.25, false);
        double annuity = RatesOptions.annuity(c, 1, 5);
        double fsr = RatesOptions.forwardSwapRate(c, 1, 5);
        assertEquals(annuity * (fsr - strike), payer - receiver, 1e-12,
                "payer - receiver must equal the forward swap PV, any vol");
        assertTrue(payer > 0 && receiver > 0);

        // At-the-money forward: payer and receiver are worth the same.
        double atmPayer = RatesOptions.swaption(c, 1, 5, fsr, 0.25, true);
        double atmReceiver = RatesOptions.swaption(c, 1, 5, fsr, 0.25, false);
        assertEquals(atmPayer, atmReceiver, 1e-12);
    }

    @Test
    void capMinusFloorIsTheSwapPv() {
        YieldCurve c = flat5();
        double strike = 0.04;
        double cap = RatesOptions.cap(c, 5, strike, 0.30);
        double floor = RatesOptions.floor(c, 5, strike, 0.30);
        double swapPv = 0;
        for (int i = 1; i <= 5; i++) {
            double dfPay = c.discountFactor(i);
            double fwd = c.discountFactor(i - 1) / dfPay - 1;
            swapPv += dfPay * (fwd - strike);
        }
        assertEquals(swapPv, cap - floor, 1e-12, "cap - floor = swap, any vol");
        // Longer caps contain more caplets: strictly more valuable.
        assertTrue(RatesOptions.cap(c, 7, strike, 0.30) > cap);
    }

    @Test
    void ratesOptionsGates() {
        YieldCurve c = flat5();
        assertThrows(IllegalArgumentException.class, () -> RatesOptions.annuity(c, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> RatesOptions.annuity(c, -1, 5));
        assertThrows(IllegalArgumentException.class,
                () -> RatesOptions.swaption(c, 1, 5, 0, 0.25, true));
        assertThrows(IllegalArgumentException.class,
                () -> RatesOptions.swaption(c, 1, 5, 0.04, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> RatesOptions.cap(c, 0, 0.04, 0.30));
    }
}
