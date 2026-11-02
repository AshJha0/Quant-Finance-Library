package com.quantfinlib.fx;

import com.quantfinlib.rates.BusinessCalendar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FX pair conventions: pip/precision tables, T+1/T+2 spot lags across dual
 * holiday calendars, and forward tenor rolls (following, modified-following,
 * end-end).
 */
class CurrencyPairTest {

    @Test
    void conventionTableCoversMajorsAndJpyQuotes() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        assertEquals(0.0001, eurusd.pipSize());
        assertEquals(5, eurusd.pricePrecision());
        assertEquals(2, eurusd.spotLagDays());

        CurrencyPair usdjpy = CurrencyPair.of("USDJPY");
        assertEquals(0.01, usdjpy.pipSize());
        assertEquals(3, usdjpy.pricePrecision());

        // The market's T+1 exceptions.
        assertEquals(1, CurrencyPair.of("USDCAD").spotLagDays());
        assertEquals(1, CurrencyPair.of("USDTRY").spotLagDays());
    }

    @Test
    void pipConversionsRoundTrip() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        assertEquals(1.3, eurusd.pips(0.00013), 1e-9);
        assertEquals(0.00013, eurusd.priceFromPips(1.3), 1e-12);
        assertEquals(1.08535, eurusd.round(1.0853499999), 1e-9);

        CurrencyPair usdjpy = CurrencyPair.of("USDJPY");
        assertEquals(2.5, usdjpy.pips(0.025), 1e-9);
    }

    @Test
    void spotDateSkipsWeekends() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        // Friday 2026-01-02 + 2 business days = Tuesday 2026-01-06.
        assertEquals(LocalDate.of(2026, 1, 6), eurusd.spotDate(LocalDate.of(2026, 1, 2)));
        // T+1 pair: Friday → Monday.
        assertEquals(LocalDate.of(2026, 1, 5), CurrencyPair.of("USDCAD").spotDate(LocalDate.of(2026, 1, 2)));
    }

    @Test
    void spotDateHonoursBothCurrenciesHolidays() {
        // Monday 2026-01-05 is a base-side holiday: Fri trade must skip it.
        BusinessCalendar eurHols = BusinessCalendar.withHolidays(LocalDate.of(2026, 1, 5));
        CurrencyPair pair = CurrencyPair.of("EURUSD")
                .withCalendars(eurHols, BusinessCalendar.weekendsOnly());
        // Fri 01-02 + 2 joint business days: Tue 01-06, Wed 01-07.
        assertEquals(LocalDate.of(2026, 1, 7), pair.spotDate(LocalDate.of(2026, 1, 2)));

        // The same holiday on the quote side must block equally.
        CurrencyPair mirrored = CurrencyPair.of("EURUSD")
                .withCalendars(BusinessCalendar.weekendsOnly(), eurHols);
        assertEquals(LocalDate.of(2026, 1, 7), mirrored.spotDate(LocalDate.of(2026, 1, 2)));
    }

    @Test
    void weekTenorsRollForward() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        // Trade Wed 2026-01-07 → spot Fri 01-09 → 1W = Fri 01-16.
        assertEquals(LocalDate.of(2026, 1, 16), eurusd.tenorDate(LocalDate.of(2026, 1, 7), "1W"));
        // Spot Mon 2026-01-12 (trade Thu 01-08): +2D lands Wed.
        assertEquals(LocalDate.of(2026, 1, 14), eurusd.tenorDate(LocalDate.of(2026, 1, 8), "2D"));
    }

    @Test
    void monthTenorUsesModifiedFollowing() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        // Trade Mon 2026-04-27 → spot Wed 04-29 → 1M = Fri 05-29 (05-29 is a
        // business day; unadjusted 05-29 needs no roll).
        assertEquals(LocalDate.of(2026, 5, 29), eurusd.tenorDate(LocalDate.of(2026, 4, 27), "1M"));
        // Trade Wed 2026-01-28 → spot Fri 01-30 → 1M unadjusted Sat 02-28 →
        // following would cross into March, so modified-following rolls BACK
        // to Fri 02-27.
        assertEquals(LocalDate.of(2026, 2, 27), eurusd.tenorDate(LocalDate.of(2026, 1, 28), "1M"));
    }

    @Test
    void endEndRulePinsMonthEnds() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        // Trade Thu 2026-02-24? — pick a trade whose spot is the last business
        // day of February: spot Fri 2026-02-27 (trade Wed 02-25). 1M forward
        // must pin to the last business day of March (Tue 03-31), not 03-27.
        LocalDate trade = LocalDate.of(2026, 2, 25);
        assertEquals(LocalDate.of(2026, 2, 27), eurusd.spotDate(trade));
        assertEquals(LocalDate.of(2026, 3, 31), eurusd.tenorDate(trade, "1M"));
    }

    @Test
    void preSpotTenorsAndValidation() {
        CurrencyPair eurusd = CurrencyPair.of("EURUSD");
        LocalDate trade = LocalDate.of(2026, 1, 7); // Wednesday
        assertEquals(LocalDate.of(2026, 1, 8), eurusd.tenorDate(trade, "ON"));
        assertEquals(LocalDate.of(2026, 1, 9), eurusd.tenorDate(trade, "TN"));   // = spot for T+2
        assertEquals(eurusd.spotDate(trade), eurusd.tenorDate(trade, "TN"));
        assertEquals(LocalDate.of(2026, 1, 12), eurusd.tenorDate(trade, "SN"));  // spot Fri + 1 = Mon

        assertThrows(IllegalArgumentException.class, () -> CurrencyPair.of("EUR"));
        assertThrows(IllegalArgumentException.class, () -> eurusd.tenorDate(trade, "1Q"));
        assertTrue(eurusd.toString().equals("EURUSD"));
    }
}
