package com.quantfinlib.fx;

import com.quantfinlib.rates.YieldCurve;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FX swap and NDF bookings: at-market swaps value to zero on their own
 * curve, MTM tracks points moves with the right sign, NDF fixing dates walk
 * back by the restricted currency's lag, and the USD settlement formula
 * divides by the fixing.
 */
class FxSwapNdfTest {

    private final CurrencyPair eurusd = CurrencyPair.of("EURUSD");
    private final LocalDate trade = LocalDate.of(2026, 1, 7); // Wed, spot Fri 01-09

    private SwapPointsCurve curve(double spot, double oneMonthPips, double threeMonthPips) {
        return SwapPointsCurve.builder(eurusd, trade, spot)
                .add("1M", oneMonthPips)
                .add("3M", threeMonthPips)
                .build();
    }

    @Test
    void atMarketSwapValuesToZeroOnItsOwnCurve() {
        SwapPointsCurve c = curve(1.0850, 12.6, 38.4);
        FxSwap swap = FxSwap.atMarket(c, "SPOT", "3M", 10_000_000);
        assertEquals(0, swap.markToMarket(c), 1e-9);
        // Traded differential is exactly the 3M points.
        assertEquals(38.4, swap.swapPointsPips(), 1e-9);
        assertEquals(c.spotDate(), swap.nearDate());
        assertTrue(swap.toString().contains("EURUSD"));
    }

    @Test
    void swapMtmTracksPointsMovesWithTheRightSign() {
        SwapPointsCurve struck = curve(1.0850, 12.6, 38.4);
        FxSwap swap = FxSwap.atMarket(struck, "SPOT", "3M", 10_000_000);
        // Points widen 38.4 → 45.0: the far leg (short base forward) loses
        // when forwards rise; near-spot leg gains from spot move only. Hold
        // spot constant so the move isolates the points.
        SwapPointsCurve wider = curve(1.0850, 12.6, 45.0);
        double mtm = swap.markToMarket(wider);
        // Far forward rose by 6.6 pips: short 10m base forward loses 10m × 0.00066.
        assertEquals(-10_000_000 * 6.6 * 0.0001, mtm, 1.0);
        // Discounting shrinks the magnitude but keeps the sign.
        YieldCurve usd = YieldCurve.ofZeroRates(new double[]{0.25, 1}, new double[]{0.05, 0.05});
        double discounted = swap.markToMarket(wider, usd);
        assertTrue(discounted < 0 && Math.abs(discounted) < Math.abs(mtm));
    }

    @Test
    void mismatchedSwapCarriesForwardExposure() {
        // 1M-vs-3M forward-forward: exposure only to the points between legs.
        SwapPointsCurve struck = curve(1.0850, 12.6, 38.4);
        FxSwap ff = FxSwap.atMarket(struck, "1M", "3M", 5_000_000);
        // Parallel spot move with unchanged points: both legs shift equally,
        // long near + short far cancels → MTM ≈ 0.
        SwapPointsCurve spotUp = curve(1.0950, 12.6, 38.4);
        assertEquals(0, ff.markToMarket(spotUp), 1e-9);
    }

    @Test
    void ndfBookingWalksFixingBackByTheCurrencyLag() {
        CurrencyPair usdinr = CurrencyPair.of("USDINR");
        Ndf ndf = Ndf.of(usdinr, trade, "1M", 84.50, 1_000_000);
        assertEquals(2, Ndf.fixingLagDays("INR"));
        assertEquals(1, Ndf.fixingLagDays("BRL"));
        assertEquals(2, Ndf.fixingLagDays("XXX")); // unlisted default
        // Settlement Mon 2026-02-09 (1M from spot Fri 01-09, rolled following);
        // fixing two business days earlier.
        assertEquals(LocalDate.of(2026, 2, 9), ndf.settlementDate());
        assertEquals(LocalDate.of(2026, 2, 5), ndf.fixingDate());
        assertTrue(ndf.fixingDate().isBefore(ndf.settlementDate()));
    }

    @Test
    void agedSwapMarksAgainstALaterCurveWithoutThrowing() {
        // Book a spot-vs-3M swap, then re-mark two weeks later: the near leg
        // has settled (realized cash) and must contribute zero, while the
        // far leg still marks against the new curve — routine daily P&L.
        SwapPointsCurve struck = curve(1.0850, 12.6, 38.4);
        FxSwap swap = FxSwap.atMarket(struck, "SPOT", "3M", 10_000_000);
        LocalDate laterTrade = trade.plusWeeks(2);
        SwapPointsCurve later = SwapPointsCurve.builder(eurusd, laterTrade, 1.0900)
                .add("1M", 11.0)
                .add("3M", 33.0)
                .build();
        // Near date (old spot) is before the new curve's spot: settled leg.
        assertTrue(swap.nearDate().isBefore(later.spotDate()));
        double mtm = swap.markToMarket(later);
        double farOnly = -10_000_000 * (later.outright(swap.farDate()) - swap.farRate());
        assertEquals(farOnly, mtm, 1e-9);
        // The discounted overload skips the settled leg the same way.
        com.quantfinlib.rates.YieldCurve usd = com.quantfinlib.rates.YieldCurve.ofZeroRates(
                new double[]{1}, new double[]{0.05});
        assertEquals(Math.signum(farOnly), Math.signum(swap.markToMarket(later, usd)));
    }

    @Test
    void ndfFixingCountsLocalNotJointBusinessDays() {
        // USDINR settling Wednesday with the preceding Monday a US-only
        // holiday: the RBI fixing counts INDIAN business days, so Monday
        // still counts and the fixing is Monday — a joint-calendar walk
        // would wrongly skip to Friday.
        com.quantfinlib.rates.BusinessCalendar usHoliday =
                com.quantfinlib.rates.BusinessCalendar.withHolidays(LocalDate.of(2026, 2, 9));
        CurrencyPair usdinr = CurrencyPair.of("USDINR")
                .withCalendars(usHoliday, com.quantfinlib.rates.BusinessCalendar.weekendsOnly());
        // Settlement lands Wed 2026-02-11 via explicit dates for precision.
        Ndf ndf = Ndf.of(usdinr, 1_000_000, 84.50,
                LocalDate.of(2026, 2, 9), LocalDate.of(2026, 2, 11));
        assertEquals(LocalDate.of(2026, 2, 9), ndf.fixingDate());
        // And the tenor-booking path: fixing walked back on the QUOTE (INR)
        // calendar ignores the US holiday.
        Ndf booked = Ndf.of(usdinr, LocalDate.of(2026, 1, 7), "1M", 84.50, 1_000_000);
        LocalDate expected = booked.settlementDate();
        for (int i = 0; i < 2; i++) { // walk back 2 INR business days
            do {
                expected = expected.minusDays(1);
            } while (!usdinr.quoteCalendar().isBusinessDay(expected));
        }
        assertEquals(expected, booked.fixingDate());
    }

    @Test
    void ndfSettlementDividesByTheFixing() {
        CurrencyPair usdinr = CurrencyPair.of("USDINR");
        Ndf ndf = Ndf.of(usdinr, trade, "1M", 84.50, 1_000_000);
        // Fixing above contract: base buyer (long USD) gains, settled in USD.
        assertEquals(1_000_000 * (85.00 - 84.50) / 85.00, ndf.settlementAmount(85.00), 1e-9);
        // Fixing below: buyer pays.
        assertTrue(ndf.settlementAmount(84.00) < 0);
        assertThrows(IllegalArgumentException.class, () -> ndf.settlementAmount(0));
    }

    @Test
    void ndfMarksAgainstTheForwardToTheFixingDate() {
        CurrencyPair usdinr = CurrencyPair.of("USDINR");
        SwapPointsCurve c = SwapPointsCurve.builder(usdinr, trade, 84.20)
                .add("1M", 3000)   // 30 paise of points (pip = 0.0001 → 0.30)
                .add("3M", 9000)
                .build();
        Ndf ndf = Ndf.of(usdinr, trade, "1M", 84.20, 1_000_000);
        double fwdAtFixing = c.outright(ndf.fixingDate());
        assertEquals(1_000_000 * (fwdAtFixing - 84.20) / fwdAtFixing, ndf.markToMarket(c), 1e-9);
        // Discounted MTM is smaller in magnitude, same sign.
        YieldCurve usd = YieldCurve.ofZeroRates(new double[]{1}, new double[]{0.05});
        assertTrue(ndf.markToMarket(c, usd) > 0
                && ndf.markToMarket(c, usd) < ndf.markToMarket(c));
    }

    @Test
    void rollCostAndValidation() {
        // Paying 0.15 pips tom-next on 10m base.
        assertEquals(10_000_000 * 0.15 * 0.0001, FxSwap.rollCost(eurusd, 10_000_000, 0.15), 1e-9);
        SwapPointsCurve c = curve(1.0850, 12.6, 38.4);
        assertThrows(IllegalArgumentException.class,
                () -> FxSwap.of(eurusd, 1_000_000, trade.plusDays(30), 1.09, trade.plusDays(10), 1.10));
        assertThrows(IllegalArgumentException.class,
                () -> Ndf.of(CurrencyPair.of("USDINR"), 1_000_000, 0,
                        trade.plusDays(28), trade.plusDays(30)));
    }
}
