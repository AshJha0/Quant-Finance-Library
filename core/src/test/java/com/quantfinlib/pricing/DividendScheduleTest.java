package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Escrowed discrete dividends: PV stripping, horizon filtering, the forward
 * with borrow, and the option-price direction (calls cheaper, puts richer
 * than the no-dividend case).
 */
class DividendScheduleTest {

    private static final double S = 100;
    private static final double R = 0.04;
    private static final double VOL = 0.25;
    private static final double T = 1.0;

    // Quarterly $1 dividends.
    private final DividendSchedule divs = DividendSchedule.of(
            new double[]{0.25, 0.50, 0.75, 1.00}, new double[]{1, 1, 1, 1});

    @Test
    void presentValueDiscountsAndFiltersByHorizon() {
        double expected = Math.exp(-R * 0.25) + Math.exp(-R * 0.50);
        // Only ex-dates on/before the horizon count.
        assertEquals(expected, divs.presentValue(R, 0.6), 1e-12);
        assertEquals(0, divs.presentValue(R, 0.1), 1e-12);
        assertEquals(4, divs.count());
        assertEquals(0, DividendSchedule.NONE.presentValue(R, 10), 1e-12);
    }

    @Test
    void forwardDropsByDividendsAndBorrow() {
        double noDivForward = S * Math.exp(R * T);
        double withDivs = divs.forward(S, R, 0, T);
        assertTrue(withDivs < noDivForward);
        assertEquals(divs.adjustedSpot(S, R, T) * Math.exp(R * T), withDivs, 1e-12);
        // Borrow fee acts like extra yield: forward drops further.
        assertTrue(divs.forward(S, R, 0.02, T) < withDivs);
        // No dividends, no borrow: the plain cost-of-carry forward.
        assertEquals(noDivForward, DividendSchedule.NONE.forward(S, R, 0, T), 1e-12);
    }

    @Test
    void optionPricesMoveInTheDividendDirection() {
        double plainCall = BlackScholes.price(OptionType.CALL, S, 100, R, 0, VOL, T);
        double plainPut = BlackScholes.price(OptionType.PUT, S, 100, R, 0, VOL, T);
        double divCall = divs.europeanPrice(OptionType.CALL, S, 100, R, 0, VOL, T);
        double divPut = divs.europeanPrice(OptionType.PUT, S, 100, R, 0, VOL, T);
        // Dividends leak value out of the diffusion: calls down, puts up.
        assertTrue(divCall < plainCall);
        assertTrue(divPut > plainPut);
        // Empty schedule reproduces Black-Scholes exactly.
        assertEquals(plainCall,
                DividendSchedule.NONE.europeanPrice(OptionType.CALL, S, 100, R, 0, VOL, T), 1e-12);
        // Put-call parity on the ADJUSTED spot (the escrowed model's parity).
        double adjusted = divs.adjustedSpot(S, R, T);
        assertEquals(adjusted - 100 * Math.exp(-R * T), divCall - divPut, 1e-10);
    }

    @Test
    void validationRejectsBadSchedules() {
        assertThrows(IllegalArgumentException.class,
                () -> DividendSchedule.of(new double[]{1}, new double[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> DividendSchedule.of(new double[]{0}, new double[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> DividendSchedule.of(new double[]{0.5, 0.25}, new double[]{1, 1}));
        assertThrows(IllegalArgumentException.class,
                () -> DividendSchedule.of(new double[]{0.5}, new double[]{-1}));
        // Dividend PV swamping spot must be reported, not priced.
        DividendSchedule huge = DividendSchedule.of(new double[]{0.5}, new double[]{200});
        assertThrows(IllegalArgumentException.class, () -> huge.adjustedSpot(S, R, T));
    }

    @Test
    void bosVandermarkIsCloserToTheJumpModelThanEscrow() {
        // Exact model: GBM between dividends, spot drops by the cash amount at
        // the ex-date. One late, large dividend makes the escrow error visible.
        double s0 = 100, k = 100, r = 0.05, vol = 0.30, t = 1.0, tDiv = 0.9, div = 7;
        DividendSchedule d = DividendSchedule.of(new double[]{tDiv}, new double[]{div});
        java.util.SplittableRandom rng = new java.util.SplittableRandom(7);
        int n = 400_000;
        double sum = 0, sum2 = 0;
        for (int i = 0; i < n; i++) {
            double s1 = s0 * Math.exp((r - 0.5 * vol * vol) * tDiv + vol * Math.sqrt(tDiv) * rng.nextGaussian());
            s1 = Math.max(s1 - div, 0);
            double sT = s1 * Math.exp((r - 0.5 * vol * vol) * (t - tDiv)
                    + vol * Math.sqrt(t - tDiv) * rng.nextGaussian());
            double v = Math.exp(-r * t) * Math.max(sT - k, 0);
            sum += v;
            sum2 += v * v;
        }
        double mc = sum / n;
        double se = Math.sqrt((sum2 / n - mc * mc) / n);
        double bv = d.bosVandermarkPrice(OptionType.CALL, s0, k, r, 0, vol, t);
        double escrow = d.europeanPrice(OptionType.CALL, s0, k, r, 0, vol, t);
        assertTrue(Math.abs(bv - mc) < Math.abs(escrow - mc),
                "bv " + bv + " escrow " + escrow + " mc " + mc + " +- " + se);
        assertTrue(Math.abs(bv - mc) < 0.1, "bv " + bv + " mc " + mc);
        // No dividends: both are plain Black-Scholes.
        assertEquals(BlackScholes.price(OptionType.PUT, s0, k, r, 0.01, vol, t),
                DividendSchedule.NONE.bosVandermarkPrice(OptionType.PUT, s0, k, r, 0.01, vol, t), 1e-12);
    }
}
