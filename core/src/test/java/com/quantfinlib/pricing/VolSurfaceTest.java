package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolSurfaceTest {

    private final VolSurface surface = VolSurface.builder()
            .add(0.25, 90, 0.25).add(0.25, 100, 0.20).add(0.25, 110, 0.22)
            .add(1.00, 90, 0.28).add(1.00, 100, 0.24).add(1.00, 110, 0.25)
            .build();

    @Test
    void recoversPillarQuotesExactly() {
        assertEquals(0.20, surface.vol(0.25, 100), 1e-12);
        assertEquals(0.28, surface.vol(1.00, 90), 1e-12);
        assertEquals(2, surface.expiries().size());
        assertEquals(3, surface.strikes(0.25).size());
    }

    @Test
    void interpolatesLinearlyAcrossStrikes() {
        // Midway between 90 (0.25) and 100 (0.20).
        assertEquals(0.225, surface.vol(0.25, 95), 1e-12);
        // Flat extrapolation beyond the wings.
        assertEquals(0.25, surface.vol(0.25, 80), 1e-12);
        assertEquals(0.22, surface.vol(0.25, 130), 1e-12);
    }

    @Test
    void interpolatesInTotalVarianceAcrossExpiries() {
        // K=100: w1 = 0.04*0.25 = 0.01, w2 = 0.0576*1.0 = 0.0576.
        // t=0.625 (halfway): w = 0.0338 -> vol = sqrt(0.0338/0.625).
        assertEquals(Math.sqrt(0.0338 / 0.625), surface.vol(0.625, 100), 1e-9);
        // Flat vol extrapolation outside the pillar range.
        assertEquals(0.20, surface.vol(0.10, 100), 1e-12);
        assertEquals(0.24, surface.vol(3.00, 100), 1e-12);
    }

    @Test
    void smileShapeIsPreserved() {
        // Put skew: downside strikes carry higher vol at every expiry.
        assertTrue(surface.vol(1.0, 90) > surface.vol(1.0, 100));
        assertTrue(surface.vol(0.625, 90) > surface.vol(0.625, 100));
        assertTrue(surface.skew(1.0, 90, 100) < 0);
    }

    @Test
    void buildsFromMarketPricesViaImpliedVol() {
        double spot = 100, rate = 0.03, carry = 0.01;
        double p1 = BlackScholes.price(OptionType.CALL, spot, 100, rate, carry, 0.30, 0.5);
        double p2 = BlackScholes.price(OptionType.PUT, spot, 90, rate, carry, 0.35, 0.5);

        VolSurface fromPrices = VolSurface.builder()
                .addFromPrice(OptionType.CALL, p1, spot, 100, rate, carry, 0.5)
                .addFromPrice(OptionType.PUT, p2, spot, 90, rate, carry, 0.5)
                .build();

        assertEquals(0.30, fromPrices.vol(0.5, 100), 1e-6);
        assertEquals(0.35, fromPrices.vol(0.5, 90), 1e-6);
    }

    @Test
    void pricesWithTheSurfaceVol() {
        double expected = BlackScholes.price(OptionType.CALL, 100, 95,
                0.02, 0, surface.vol(0.5, 95), 0.5);
        assertEquals(expected, surface.price(OptionType.CALL, 100, 95, 0.02, 0, 0.5), 1e-12);
    }

    @Test
    void validatesInputs() {
        assertThrows(IllegalStateException.class, () -> VolSurface.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> VolSurface.builder().add(-1, 100, 0.2));
        assertThrows(IllegalArgumentException.class,
                () -> surface.strikes(0.33));
    }

    @Test
    void timeInterpolationAtFixedLogMoneynessWhenForwardsAreGiven() {
        VolSurface s = VolSurface.builder()
                .add(0.25, 90, 0.25).add(0.25, 100, 0.20).add(0.25, 110, 0.22)
                .add(1.00, 100, 0.28).add(1.00, 110, 0.25).add(1.00, 121, 0.26)
                .forward(0.25, 100).forward(1.00, 110)
                .build();
        // At t = 0.625 the forward is 100*sqrt(1.1); at-the-forward strike uses
        // each pillar's own forward: K1 = 100 (0.20), K2 = 110 (0.25).
        // w = (0.01 + 0.0625)/2 = 0.03625.
        double f = 100 * Math.sqrt(1.1);
        assertEquals(f, s.forwardAt(0.625), 1e-12);
        assertEquals(Math.sqrt(0.03625 / 0.625), s.vol(0.625, f), 1e-12);
        assertTrue(s.calendarViolations(41).isEmpty());
    }

    @Test
    void calendarAndButterflyChecksFlagArbitrage() {
        // Total variance falls from 0.25y to 1y at the money: 0.5^2*0.25 = 0.0625
        // against 0.2^2*1 = 0.04.
        VolSurface bad = VolSurface.builder()
                .add(0.25, 100, 0.50).add(1.0, 100, 0.20).build();
        assertTrue(!bad.calendarViolations(5).isEmpty());
        assertTrue(surface.calendarViolations(41).isEmpty());
        // A hump at the money: vol rises 30 points across one strike and falls
        // back, so the call price is locally concave in strike.
        VolSurface v = VolSurface.builder()
                .add(0.5, 99, 0.10).add(0.5, 100, 0.40).add(0.5, 101, 0.10).build();
        assertTrue(v.minimumDensity(0.5, 100, 201) < 0);
        // A flat smile is clean.
        VolSurface flat = VolSurface.builder()
                .add(1.0, 90, 0.24).add(1.0, 100, 0.24).add(1.0, 110, 0.24).build();
        assertTrue(flat.minimumDensity(1.0, 100, 201) > 0);
        // The flat-wing extrapolation puts a concave kink at the outer pillar
        // of any skewed smile (here K = 90: slope 0 outside, -0.004 inside),
        // which the check reports (independent scan: about -15.5 at K = 90).
        assertTrue(surface.minimumDensity(1.0, 100, 201) < -10);
    }
}
