package com.quantfinlib.credit;

import com.quantfinlib.credit.IsdaRateCurve.Swap;
import com.quantfinlib.rates.BusinessCalendar;
import com.quantfinlib.rates.HolidayCalendars;
import com.quantfinlib.rates.HolidayCalendars.Market;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsdaRateCurveTest {

    static final LocalDate TRADE = LocalDate.of(2021, 4, 26);

    @Test
    void sixMonthSwapMatchesClosedForm() {
        IsdaCurve cv = IsdaRateCurve.bootstrapSwaps(TRADE, List.of(new Swap("6M", 0.02)),
                BusinessCalendar.weekendsOnly());
        assertEquals(185 / 365.0, cv.time(0), 1e-15);
        double expected = Math.log(1 + 0.02 * 183 / 360.0) / (183 / 365.0);
        assertEquals(expected, cv.zeroRate(cv.time(0)), 1e-14);
        assertEquals(0.0201754, expected, 5e-8);
    }

    @Test
    void everyQuoteRepricesToPar() {
        List<Swap> swaps = List.of(new Swap("1M", 0.0005), new Swap("6M", 0.001), new Swap("1Y", 0.003),
                new Swap("2Y", 0.008), new Swap("5Y", 0.015), new Swap("10Y", 0.019), new Swap("30Y", 0.021));
        BusinessCalendar cal = HolidayCalendars.calendar(Market.NEW_YORK_FED, 2020, 2060);
        LocalDate trade = LocalDate.of(2022, 6, 21);
        IsdaCurve cv = IsdaRateCurve.bootstrapSwaps(trade, swaps, cal);
        assertEquals(swaps.size(), cv.timesLength());
        for (Swap s : swaps) {
            assertEquals(0.0, IsdaRateCurve.parSwapValue(cv, trade, s, cal), 1e-13, s.toString());
        }
    }

    @Test
    void modifiedFollowingAndHolidaysMoveKnots() {
        // spot Wed 2021-04-28; 1Y -> 2022-04-28 (Thu). Holiday on that date pushes to Fri.
        BusinessCalendar hol = BusinessCalendar.withHolidays(LocalDate.of(2022, 4, 28));
        IsdaCurve a = IsdaRateCurve.bootstrapSwaps(TRADE, List.of(new Swap("1Y", 0.01)),
                BusinessCalendar.weekendsOnly());
        IsdaCurve b = IsdaRateCurve.bootstrapSwaps(TRADE, List.of(new Swap("1Y", 0.01)), hol);
        assertEquals(367 / 365.0, a.time(0), 1e-15);
        assertEquals(368 / 365.0, b.time(0), 1e-15);
    }

    @Test
    void negativeRatesAreSupported() {
        IsdaCurve cv = IsdaRateCurve.bootstrapSwaps(TRADE, List.of(new Swap("1Y", -0.005), new Swap("3Y", -0.002)),
                BusinessCalendar.weekendsOnly());
        assertTrue(cv.zeroRate(cv.time(0)) < 0);
        assertTrue(cv.zeroRate(cv.time(1)) < 0);
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new Swap("5W", 0.01));
        assertThrows(IllegalArgumentException.class, () -> new Swap("0Y", 0.01));
        assertThrows(IllegalArgumentException.class, () -> new Swap("xY", 0.01));
        assertThrows(IllegalArgumentException.class, () -> new Swap("1Y", Double.NaN));
        BusinessCalendar cal = BusinessCalendar.weekendsOnly();
        assertThrows(IllegalArgumentException.class, () -> IsdaRateCurve.bootstrapSwaps(TRADE, List.of(), cal));
        assertThrows(IllegalArgumentException.class, () -> IsdaRateCurve.bootstrapSwaps(TRADE,
                List.of(new Swap("2Y", 0.01), new Swap("1Y", 0.01)), cal));
        assertThrows(IllegalArgumentException.class, () -> IsdaRateCurve.bootstrapSwaps(TRADE,
                List.of(new Swap("12M", 0.01), new Swap("1Y", 0.01)), cal));
    }
}
