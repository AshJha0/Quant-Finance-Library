package com.quantfinlib.pricing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Equity derivatives: the autocallable pricer (the RFQ half moved to lowlatency {@code rfq.RfqMarketStructureTest} in 4.0). */
class AutocallableRfqTest {

    private static final double[] QUARTERS = {0.25, 0.5, 0.75, 1.0};

    // ------------------------------------------------------------------
    // Autocallable — exact zero-vol cases (the MC collapses to arithmetic)
    // ------------------------------------------------------------------

    @Test
    void zeroVolFlatMarketAutocallsAtTheFirstObservation() {
        // vol=0, r=q=0: the path sits at spot forever; barrier 1.0 triggers
        // immediately -> notional + one coupon, undiscounted. Exact.
        Autocallable note = new Autocallable(1_000_000, QUARTERS, 1.0, 0.8, 0.6,
                0.02, true);
        double price = note.price(100, 100, 0, 0, 0, 10, 42);
        assertEquals(1_020_000, price, 1e-6, "notional + one 2% coupon at obs 1");
    }

    @Test
    void zeroVolBelowAutocallCollectsCouponsAndProtection() {
        // Barrier 1.2 never triggers; flat at 100 pays every coupon (>= 0.8
        // barrier) and redeems protected at maturity (>= 0.6 KI). Exact.
        Autocallable note = new Autocallable(1_000_000, QUARTERS, 1.2, 0.8, 0.6,
                0.02, false);
        double price = note.price(100, 100, 0, 0, 0, 10, 42);
        assertEquals(1_080_000, price, 1e-6, "four coupons + protected notional");
    }

    @Test
    void zeroVolKnockedInTakesTheEquityLoss() {
        // A heavy dividend drift sinks the path deterministically:
        // S_T = 100*e^{-0.6} = 54.88 < KI 60 -> redeem S_T/S0, no coupons.
        Autocallable note = new Autocallable(1_000_000, new double[]{1.0}, 1.2, 0.8,
                0.6, 0.02, false);
        double price = note.price(100, 100, 0, 0, 0.6, 10, 42);
        assertEquals(1_000_000 * Math.exp(-0.6), price, 1e-3,
                "the protection is gone: the holder owns the drop");
    }

    @Test
    void memoryCouponsCatchUpAtTheNextPayingObservation() {
        // Drift puts the path below the coupon barrier for obs 1-2, then a
        // rising drift... simpler: two observations, drift down then flat is
        // not expressible under GBM zero-vol; instead compare memory ON vs
        // off on a stochastic run — memory can only add value.
        Autocallable memory = new Autocallable(1_000_000, QUARTERS, 1.05, 0.95, 0.6,
                0.02, true);
        Autocallable plain = new Autocallable(1_000_000, QUARTERS, 1.05, 0.95, 0.6,
                0.02, false);
        double withMemory = memory.price(100, 100, 0.25, 0.02, 0, 40_000, 42);
        double without = plain.price(100, 100, 0.25, 0.02, 0, 40_000, 42);
        assertTrue(withMemory > without,
                "missed coupons that can be caught up are worth something: "
                        + withMemory + " vs " + without);
    }

    @Test
    void monteCarloMonotonicitiesAndReproducibility() {
        Autocallable base = new Autocallable(1_000_000, QUARTERS, 1.0, 0.8, 0.6,
                0.02, true);
        double p = base.price(100, 100, 0.20, 0.02, 0.01, 40_000, 42);
        assertEquals(p, base.price(100, 100, 0.20, 0.02, 0.01, 40_000, 42), 0.0,
                "fixed seed = bit-identical price");
        // Fatter coupon -> worth more.
        Autocallable richer = new Autocallable(1_000_000, QUARTERS, 1.0, 0.8, 0.6,
                0.03, true);
        assertTrue(richer.price(100, 100, 0.20, 0.02, 0.01, 40_000, 42) > p);
        // Higher knock-in barrier -> protection dies earlier -> worth less.
        Autocallable fragile = new Autocallable(1_000_000, QUARTERS, 1.0, 0.8, 0.8,
                0.02, true);
        assertTrue(fragile.price(100, 100, 0.20, 0.02, 0.01, 40_000, 42) < p);
        // More volatility -> the sold knock-in put dominates -> worth less.
        assertTrue(base.price(100, 100, 0.40, 0.02, 0.01, 40_000, 42) < p);
        // Hard bound: never worth more than every cashflow undiscounted.
        assertTrue(p < 1_000_000 * (1 + 4 * 0.02));
    }

    @Test
    void autocallableValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Autocallable(
                1_000_000, QUARTERS, 1.0, 1.1, 0.6, 0.02, true));   // coupon > autocall
        assertThrows(IllegalArgumentException.class, () -> new Autocallable(
                1_000_000, QUARTERS, 1.0, 0.8, 1.2, 0.02, true));   // KI above autocall
        assertThrows(IllegalArgumentException.class, () -> new Autocallable(
                Double.NaN, QUARTERS, 1.0, 0.8, 0.6, 0.02, true));  // NaN fails HERE
        assertThrows(IllegalArgumentException.class, () -> new Autocallable(
                1_000_000, QUARTERS, 1.0, 0.8, 0.6, Double.NaN, true));
        assertThrows(IllegalArgumentException.class, () -> new Autocallable(
                1_000_000, new double[]{0.5, 0.5}, 1.0, 0.8, 0.6, 0.02, true));
        Autocallable note = new Autocallable(1_000_000, QUARTERS, 1.0, 0.8, 0.6,
                0.02, true);
        assertThrows(IllegalArgumentException.class,
                () -> note.price(Double.NaN, 100, 0.2, 0, 0, 100, 1));
        assertThrows(IllegalArgumentException.class,
                () -> note.price(100, 100, Double.NaN, 0, 0, 100, 1));
    }
}
