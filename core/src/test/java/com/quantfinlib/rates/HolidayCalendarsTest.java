package com.quantfinlib.rates;

import com.quantfinlib.rates.HolidayCalendars.Market;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holiday rules pinned against the published holiday lists of each centre
 * (Federal Reserve holiday schedule, gov.uk bank holidays, ECB TARGET
 * closing days, Japanese Cabinet Office national holidays) for whole years
 * written out by hand.
 */
class HolidayCalendarsTest {

    private static Set<LocalDate> dates(String... iso) {
        Set<LocalDate> s = new TreeSet<>();
        for (String d : iso) {
            s.add(LocalDate.parse(d));
        }
        return s;
    }

    @Test
    void easterSundayKnownYears() {
        assertEquals(LocalDate.of(2019, 4, 21), HolidayCalendars.easterSunday(2019));
        assertEquals(LocalDate.of(2024, 3, 31), HolidayCalendars.easterSunday(2024));
        assertEquals(LocalDate.of(2026, 4, 5), HolidayCalendars.easterSunday(2026));
        assertEquals(LocalDate.of(2038, 4, 25), HolidayCalendars.easterSunday(2038));
    }

    @Test
    void newYorkFed2026And2027() {
        assertEquals(dates("2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19",
                        "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-12-25"),
                HolidayCalendars.holidays(Market.NEW_YORK_FED, 2026));
        // 2027: Juneteenth and Christmas on Saturday are not moved; 4 Jul Sunday -> Monday.
        Set<LocalDate> h27 = HolidayCalendars.holidays(Market.NEW_YORK_FED, 2027);
        assertTrue(h27.contains(LocalDate.of(2027, 7, 5)));
        assertFalse(h27.contains(LocalDate.of(2027, 6, 18)));
        assertFalse(h27.contains(LocalDate.of(2027, 12, 24)));
        assertTrue(HolidayCalendars.holidays(Market.NEW_YORK_FED, 2023).contains(LocalDate.of(2023, 1, 2)));
        assertFalse(HolidayCalendars.holidays(Market.NEW_YORK_FED, 2021).contains(LocalDate.of(2021, 6, 18)));
    }

    @Test
    void london2026And2022() {
        assertEquals(dates("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25",
                        "2026-08-31", "2026-12-25", "2026-12-28"),
                HolidayCalendars.holidays(Market.LONDON, 2026));
        assertEquals(dates("2022-01-03", "2022-04-15", "2022-04-18", "2022-05-02", "2022-06-02",
                        "2022-06-03", "2022-08-29", "2022-09-19", "2022-12-26", "2022-12-27"),
                HolidayCalendars.holidays(Market.LONDON, 2022));
        assertTrue(HolidayCalendars.holidays(Market.LONDON, 2020).contains(LocalDate.of(2020, 5, 8)));
        assertFalse(HolidayCalendars.holidays(Market.LONDON, 2020).contains(LocalDate.of(2020, 5, 4)));
        // 2021: Christmas Sat -> Mon 27, Boxing Sun -> Tue 28.
        Set<LocalDate> h21 = HolidayCalendars.holidays(Market.LONDON, 2021);
        assertTrue(h21.contains(LocalDate.of(2021, 12, 27)) && h21.contains(LocalDate.of(2021, 12, 28)));
    }

    @Test
    void target2026() {
        assertEquals(dates("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-01", "2026-12-25"),
                HolidayCalendars.holidays(Market.TARGET, 2026));
    }

    @Test
    void tokyo2026WithSubstituteAndCitizensHoliday() {
        assertEquals(dates("2026-01-01", "2026-01-02", "2026-01-12", "2026-02-11", "2026-02-23",
                        "2026-03-20", "2026-04-29", "2026-05-04", "2026-05-05", "2026-05-06",
                        "2026-07-20", "2026-08-11", "2026-09-21", "2026-09-22", "2026-09-23",
                        "2026-10-12", "2026-11-03", "2026-11-23", "2026-12-31"),
                HolidayCalendars.holidays(Market.TOKYO, 2026));
        Set<LocalDate> h19 = HolidayCalendars.holidays(Market.TOKYO, 2019);
        for (String d : List.of("2019-04-29", "2019-04-30", "2019-05-01", "2019-05-02", "2019-05-03",
                "2019-05-06", "2019-10-22")) {
            assertTrue(h19.contains(LocalDate.parse(d)), d);
        }
        assertFalse(h19.contains(LocalDate.of(2019, 12, 23)));   // no emperor birthday in 2019
        Set<LocalDate> h20 = HolidayCalendars.holidays(Market.TOKYO, 2020);
        assertTrue(h20.contains(LocalDate.of(2020, 7, 24)));
        assertFalse(h20.contains(LocalDate.of(2020, 10, 12)));
        assertThrows(IllegalArgumentException.class, () -> HolidayCalendars.holidays(Market.TOKYO, 1999));
    }

    @Test
    void calendarAdjustsForHolidaysAndWeekends() {
        BusinessCalendar ny = HolidayCalendars.calendar(Market.NEW_YORK_FED, 2026, 2027);
        assertEquals(LocalDate.of(2026, 11, 30), ny.addBusinessDays(LocalDate.of(2026, 11, 24), 3));
        assertEquals(LocalDate.of(2026, 11, 27),
                BusinessCalendar.weekendsOnly().addBusinessDays(LocalDate.of(2026, 11, 24), 3));
        BusinessCalendar ldn = HolidayCalendars.calendar(Market.LONDON, 2026, 2026);
        assertEquals(LocalDate.of(2026, 12, 29),
                ldn.roll(LocalDate.of(2026, 12, 25), BusinessCalendar.Roll.FOLLOWING));
        assertEquals(LocalDate.of(2026, 12, 24),
                ldn.roll(LocalDate.of(2026, 12, 26), BusinessCalendar.Roll.PRECEDING));
        BusinessCalendar tky = HolidayCalendars.calendar(Market.TOKYO, 2026, 2026);
        assertEquals(LocalDate.of(2026, 3, 23),
                tky.roll(LocalDate.of(2026, 3, 20), BusinessCalendar.Roll.FOLLOWING));
        assertThrows(IllegalArgumentException.class,
                () -> HolidayCalendars.calendar(Market.TARGET, 2027, 2026));
    }
}
