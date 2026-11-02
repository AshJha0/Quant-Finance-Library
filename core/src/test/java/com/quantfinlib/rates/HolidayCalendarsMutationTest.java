package com.quantfinlib.rates;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge years of the rule-based calendars, where a rule switches on or
 * off, plus an independent check of the Easter algorithm. The Easter
 * check runs a different published algorithm (Oudin 1940, as given in
 * Tondering's calendar FAQ) over every year from 1583 to 4099 and
 * compares, so a slip in either formula shows as a mismatch (independent
 * computation). The fixed dates are the extreme Easters (22 March 1818
 * and 2285, 25 April 1943 and 2038) and Meeus' worked examples (1954,
 * 2000), published reference.
 */
class HolidayCalendarsMutationTest {

    /** Oudin's algorithm for the Gregorian Easter. */
    private static LocalDate oudin(int y) {
        int g = y % 19;
        int c = y / 100;
        int h = (c - c / 4 - (8 * c + 13) / 25 + 19 * g + 15) % 30;
        int i = h - (h / 28) * (1 - (29 / (h + 1)) * ((21 - g) / 11));
        int j = (y + y / 4 + i + 2 - c + c / 4) % 7;
        int l = i - j;
        int month = 3 + (l + 40) / 44;
        int day = l + 28 - 31 * (month / 4);
        return LocalDate.of(y, month, day);
    }

    @Test
    void easterAgreesWithOudinForEveryYear() {
        for (int y = 1583; y <= 4099; y++) {
            assertEquals(oudin(y), HolidayCalendars.easterSunday(y), "year " + y);
        }
    }

    @Test
    void easterExtremesAndWorkedExamples() {
        assertEquals(LocalDate.of(1818, 3, 22), HolidayCalendars.easterSunday(1818));
        assertEquals(LocalDate.of(2285, 3, 22), HolidayCalendars.easterSunday(2285));
        assertEquals(LocalDate.of(1943, 4, 25), HolidayCalendars.easterSunday(1943));
        assertEquals(LocalDate.of(2038, 4, 25), HolidayCalendars.easterSunday(2038));
        assertEquals(LocalDate.of(1954, 4, 18), HolidayCalendars.easterSunday(1954));
        assertEquals(LocalDate.of(2000, 4, 23), HolidayCalendars.easterSunday(2000));
    }

    @Test
    void juneteenthStartsIn2022() {
        // 19 June 2022 was a Sunday, observed by the Fed on Monday 20 June.
        Set<LocalDate> h = HolidayCalendars.holidays(HolidayCalendars.Market.NEW_YORK_FED, 2022);
        assertTrue(h.contains(LocalDate.of(2022, 6, 20)));
        // 1 Jan 2022 was a Saturday and the Fed does not move Saturday
        // holidays, so ten weekday holidays remain.
        assertEquals(10, h.size());
    }

    @Test
    void tokyoYearRangeIsInclusive() {
        assertDoesNotThrow(() -> HolidayCalendars.holidays(HolidayCalendars.Market.TOKYO, 2000));
        assertDoesNotThrow(() -> HolidayCalendars.holidays(HolidayCalendars.Market.TOKYO, 2099));
        assertThrows(IllegalArgumentException.class,
                () -> HolidayCalendars.holidays(HolidayCalendars.Market.TOKYO, 1999));
        assertThrows(IllegalArgumentException.class,
                () -> HolidayCalendars.holidays(HolidayCalendars.Market.TOKYO, 2100));
    }

    @Test
    void tokyoRuleSwitchYears() {
        // Emperor's Birthday moved to 23 Feb from 2020; in 2020 it fell on a
        // Sunday, so Monday 24 Feb was the substitute holiday.
        assertTrue(tokyo(2020).contains(LocalDate.of(2020, 2, 24)));
        assertFalse(tokyo(2019).contains(LocalDate.of(2019, 2, 25)));
        // Mountain Day exists from 2016 (11 Aug 2016 was a Thursday).
        assertTrue(tokyo(2016).contains(LocalDate.of(2016, 8, 11)));
        assertFalse(tokyo(2015).contains(LocalDate.of(2015, 8, 11)));
        // The old Emperor's Birthday, 23 Dec, last held in 2018 (a Sunday,
        // so Monday 24 Dec 2018 was the substitute).
        assertTrue(tokyo(2018).contains(LocalDate.of(2018, 12, 24)));
        assertFalse(tokyo(2019).contains(LocalDate.of(2019, 12, 23)));
        // Marine Day: fixed 20 July before 2003, third Monday after.
        assertTrue(tokyo(2001).contains(LocalDate.of(2001, 7, 20)));
        assertTrue(tokyo(2010).contains(LocalDate.of(2010, 7, 19)));
        assertFalse(tokyo(2010).contains(LocalDate.of(2010, 7, 20)));
        // Respect for the Aged Day: fixed 15 Sep before 2003, third Monday after.
        assertTrue(tokyo(2000).contains(LocalDate.of(2000, 9, 15)));
        assertTrue(tokyo(2010).contains(LocalDate.of(2010, 9, 20)));
    }

    private static Set<LocalDate> tokyo(int y) {
        return HolidayCalendars.holidays(HolidayCalendars.Market.TOKYO, y);
    }
}
