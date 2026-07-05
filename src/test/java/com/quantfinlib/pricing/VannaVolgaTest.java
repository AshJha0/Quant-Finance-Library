package com.quantfinlib.pricing;

import com.quantfinlib.fx.FxVolSurface;
import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanna-volga: exact pillar reproduction (weights collapse to 1/0/0 at the
 * pillars), a sane smile between and beyond them, put-call consistency, and
 * end-to-end integration with the delta-quoted FxVolSurface pillars.
 */
class VannaVolgaTest {

    private static final double S = 1.0850;
    private static final double R = 0.045;
    private static final double Q = 0.030;
    private static final double T = 0.5;

    // A negative-RR (put wing over call wing) EURUSD-style smile.
    private static final double[] STRIKES = {1.04, 1.09, 1.14};
    private static final double[] VOLS = {0.098, 0.090, 0.094};

    private final VannaVolga vv = new VannaVolga(STRIKES, VOLS, R, Q, T);

    @Test
    void pillarStrikesRecoverTheirMarketVolsExactly() {
        for (int i = 0; i < 3; i++) {
            assertEquals(VOLS[i], vv.impliedVol(S, STRIKES[i]), 1e-6,
                    "pillar " + i + " must reproduce its market vol");
        }
    }

    @Test
    void pillarPricesMatchTheMarketPrices() {
        for (int i = 0; i < 3; i++) {
            double market = BlackScholes.price(OptionType.CALL, S, STRIKES[i], R, Q, VOLS[i], T);
            assertEquals(market, vv.price(OptionType.CALL, S, STRIKES[i]), 1e-12);
        }
    }

    @Test
    void smileInterpolatesSmoothlyBetweenPillars() {
        // Between ATM and the call wing the vol must sit between their levels
        // (the log-quadratic weights cannot overshoot on this smile shape).
        double mid = vv.impliedVol(S, 1.115);
        assertTrue(mid > Math.min(VOLS[1], VOLS[2]) - 1e-4
                && mid < Math.max(VOLS[1], VOLS[2]) + 1e-4, "mid=" + mid);
        // The smile is not flat: the adjustment is really doing something.
        assertTrue(Math.abs(mid - VOLS[1]) > 1e-4);
        // Wings continue outward without collapsing.
        assertTrue(vv.impliedVol(S, 1.00) > VOLS[1]);
    }

    @Test
    void putPricesAreParityConsistent() {
        double k = 1.115;
        double call = vv.price(OptionType.CALL, S, k);
        double put = vv.price(OptionType.PUT, S, k);
        // Same smile adjustment on both sides: put-call parity in the
        // adjusted prices (parity holds for the flat-vol legs and the
        // pillar-hedge adjustment is type-independent).
        double parity = S * Math.exp(-Q * T) - k * Math.exp(-R * T);
        assertEquals(parity, call - put, 1e-10);
    }

    @Test
    void integratesWithFxVolSurfacePillars() {
        // Delta-quoted surface → solved pillar strikes → vanna-volga.
        FxVolSurface surface = FxVolSurface.builder()
                .add(T, S * Math.exp((R - Q) * T), 0.090, -0.008, 0.0025)
                .build();
        FxVolSurface.SmilePillar p = surface.pillar(0);
        VannaVolga fromSurface = VannaVolga.ofPillars(p.strikes(), p.vols(), R, Q, T);
        for (int i = 0; i < 3; i++) {
            assertEquals(p.vols()[i], fromSurface.impliedVol(S, p.strikes()[i]), 1e-6);
        }
        // The five-pillar overload reduces to the 25Δ triple.
        double[] five = {1.02, 1.05, 1.09, 1.13, 1.16};
        double[] fiveVols = {0.101, 0.097, 0.090, 0.093, 0.099};
        VannaVolga reduced = VannaVolga.ofPillars(five, fiveVols, R, Q, T);
        assertEquals(fiveVols[2], reduced.impliedVol(S, five[2]), 1e-6);
    }

    @Test
    void validationRejectsBadPillars() {
        assertThrows(IllegalArgumentException.class,
                () -> new VannaVolga(new double[]{1, 2}, new double[]{0.1, 0.1}, R, Q, T));
        assertThrows(IllegalArgumentException.class,
                () -> new VannaVolga(new double[]{1.1, 1.0, 1.2}, VOLS, R, Q, T));
        assertThrows(IllegalArgumentException.class,
                () -> new VannaVolga(STRIKES, new double[]{0.1, -0.1, 0.1}, R, Q, T));
        assertThrows(IllegalArgumentException.class,
                () -> new VannaVolga(STRIKES, VOLS, R, Q, 0));
        assertThrows(IllegalArgumentException.class, () -> vv.price(OptionType.CALL, S, -1));
    }
}
