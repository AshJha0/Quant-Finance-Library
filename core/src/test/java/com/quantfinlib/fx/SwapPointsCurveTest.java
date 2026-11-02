package com.quantfinlib.fx;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swap-points curve: pillar exactness, linear-in-days broken dates, slope
 * extrapolation, covered-interest-parity carry, and negative-points curves.
 */
class SwapPointsCurveTest {

    private final CurrencyPair eurusd = CurrencyPair.of("EURUSD");
    private final LocalDate trade = LocalDate.of(2026, 1, 7); // Wednesday, spot Fri 01-09

    private SwapPointsCurve curve() {
        return SwapPointsCurve.builder(eurusd, trade, 1.0850)
                .add("1M", 12.6)     // quoted in pips, out of order on purpose
                .add("1W", 3.1)
                .add("3M", 38.4)
                .build();
    }

    @Test
    void pillarsReproduceExactly() {
        SwapPointsCurve c = curve();
        LocalDate oneMonth = eurusd.tenorDate(trade, "1M");
        assertEquals(1.0850 + 12.6 * 0.0001, c.outright(oneMonth), 1e-12);
        assertEquals(1.0850 + 12.6 * 0.0001, c.outright("1M"), 1e-12);
        // Builder sorted the out-of-order quotes by date.
        assertArrayEquals(new String[]{"1W", "1M", "3M"}, c.pillarTenors());
        assertEquals(LocalDate.of(2026, 1, 9), c.spotDate());
        assertEquals(1.0850, c.spotRate());
        assertEquals(eurusd, c.pair());
    }

    @Test
    void brokenDatesInterpolateLinearlyInDays() {
        SwapPointsCurve c = curve();
        LocalDate d1 = eurusd.tenorDate(trade, "1M");
        LocalDate d3 = eurusd.tenorDate(trade, "3M");
        LocalDate mid = d1.plusDays(ChronoUnit.DAYS.between(d1, d3) / 2);
        double p1 = c.forwardPoints(d1);
        double p3 = c.forwardPoints(d3);
        double expected = p1 + (p3 - p1)
                * ((double) ChronoUnit.DAYS.between(d1, mid) / ChronoUnit.DAYS.between(d1, d3));
        assertEquals(expected, c.forwardPoints(mid), 1e-12);
        // Before the first pillar: anchored at zero points on spot.
        assertEquals(0, c.forwardPoints(c.spotDate()), 1e-12);
        LocalDate early = c.spotDate().plusDays(3);
        assertTrue(c.forwardPoints(early) > 0 && c.forwardPoints(early) < p1);
    }

    @Test
    void beyondLastPillarExtendsTheFinalSlope() {
        SwapPointsCurve c = curve();
        LocalDate d1 = eurusd.tenorDate(trade, "1M");
        LocalDate d3 = eurusd.tenorDate(trade, "3M");
        double slope = (c.forwardPoints(d3) - c.forwardPoints(d1))
                / ChronoUnit.DAYS.between(d1, d3);
        LocalDate beyond = d3.plusDays(30);
        assertEquals(c.forwardPoints(d3) + slope * 30, c.forwardPoints(beyond), 1e-12);
    }

    @Test
    void impliedCarryMatchesCoveredInterestParity() {
        SwapPointsCurve c = curve();
        LocalDate d3 = eurusd.tenorDate(trade, "3M");
        double tau = ChronoUnit.DAYS.between(c.spotDate(), d3) / 365.0;
        // F = S·e^{(r_q − r_b)τ} → carry = ln(F/S)/τ; ascending points ⇒ positive.
        double carry = c.impliedCarry(d3);
        assertEquals(Math.log(c.outright(d3) / 1.0850) / tau, carry, 1e-12);
        assertTrue(carry > 0);
    }

    @Test
    void negativePointsCurveIsSupported() {
        // Base yields more than quote (e.g. USD over JPY historically):
        // forwards below spot, negative carry.
        CurrencyPair usdjpy = CurrencyPair.of("USDJPY");
        SwapPointsCurve c = SwapPointsCurve.builder(usdjpy, trade, 155.00)
                .add("1M", -22.0)
                .add("6M", -130.0)
                .build();
        LocalDate d6 = usdjpy.tenorDate(trade, "6M");
        assertEquals(155.00 - 130.0 * 0.01, c.outright(d6), 1e-9);
        assertTrue(c.impliedCarry(d6) < 0);
    }

    @Test
    void validationRejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> SwapPointsCurve.builder(eurusd, trade, 0).add("1M", 1).build());
        assertThrows(IllegalStateException.class,
                () -> SwapPointsCurve.builder(eurusd, trade, 1.08).build());
        // Pre-spot legs belong to the roll, not the forward curve.
        assertThrows(IllegalArgumentException.class,
                () -> SwapPointsCurve.builder(eurusd, trade, 1.08).add("ON", 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> curve().forwardPoints(trade)); // before spot
        assertThrows(IllegalArgumentException.class,
                () -> SwapPointsCurve.builder(eurusd, trade, 1.08)
                        .add("1M", 1).add("1M", 2).build()); // duplicate pillar
    }

    @Test
    void preSpotDatesAndDayCountForCarry() {
        CurrencyPair eurusd = CurrencyPair.of("EUR", "USD");
        LocalDate trade = LocalDate.of(2026, 3, 2); // Monday, spot Wednesday
        SwapPointsCurve c = SwapPointsCurve.builder(eurusd, trade, 1.0850)
                .add("1M", 12.0)
                .preSpot(trade.plusDays(1), 0.4)       // TN
                .preSpot(trade, 0.4 + 0.45)           // ON + TN
                .build();
        assertEquals(1.0850 - 0.00004, c.outright(trade.plusDays(1)), 1e-15);
        assertEquals(1.0850 - 0.000085, c.outright(trade), 1e-15);
        LocalDate oneMonth = c.spotDate().plusMonths(1);
        double act365 = c.impliedCarry(oneMonth);
        double act360 = c.impliedCarry(oneMonth, com.quantfinlib.rates.DayCount.ACT_360);
        assertEquals(act365 * 360 / 365, act360, 1e-15);
    }
}
