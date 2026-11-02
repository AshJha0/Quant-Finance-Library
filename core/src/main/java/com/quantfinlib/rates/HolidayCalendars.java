package com.quantfinlib.rates;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rule-based holiday calendars for four settlement centres, generated as
 * {@link BusinessCalendar} objects for a range of years.
 *
 * <p>The plain idea: a holiday is either a fixed date (25 December), a
 * weekday rule (last Monday of May) or a date tied to Easter or to the
 * equinox, plus a few one-off days a government declared. Writing the
 * rules once means a calendar for 2040 is as correct as one for 2026,
 * with the one-off days listed explicitly up to the date of this code.</p>
 *
 * <p>Rules implemented (sources: the published holiday lists of each
 * centre; evidence class published reference, checked by own tests
 * against known dates):</p>
 * <ul>
 *   <li>{@link Market#NEW_YORK_FED}: US federal holidays as observed by
 *   the Federal Reserve, which is the calendar for SOFR publication and
 *   USD settlement. New Year, Martin Luther King (3rd Mon Jan),
 *   Presidents (3rd Mon Feb), Memorial (last Mon May), Juneteenth
 *   (19 Jun, from 2022), Independence (4 Jul), Labor (1st Mon Sep),
 *   Columbus (2nd Mon Oct), Veterans (11 Nov), Thanksgiving (4th Thu
 *   Nov), Christmas. A holiday on Sunday is observed on Monday; a
 *   holiday on Saturday is not moved (the Fed is open the Friday before).
 *   This is not the SIFMA bond-market calendar, which also closes on
 *   Good Friday.</li>
 *   <li>{@link Market#LONDON}: England and Wales bank holidays. New Year,
 *   Good Friday, Easter Monday, Early May (1st Mon May), Spring (last
 *   Mon May), Summer (last Mon Aug), Christmas, Boxing Day, with weekend
 *   substitution to the next weekday, plus the moved and one-off days of
 *   1999 to 2023 (millennium, royal weddings and jubilees, the 2020 VE
 *   Day move, the 2022 state funeral, the 2023 coronation).</li>
 *   <li>{@link Market#TARGET}: the TARGET2 closing days. New Year, Good
 *   Friday, Easter Monday, 1 May, 25 and 26 December. No substitution.</li>
 *   <li>{@link Market#TOKYO}: Japanese national holidays (supported from
 *   2000 to 2099) plus the bank closing days 2 and 3 January and 31
 *   December. Equinoxes by the standard approximation formula, Sunday
 *   substitute holidays (the next day that is not a holiday) and the
 *   "citizens' holiday" for a day sandwiched between two holidays; the
 *   2019 accession days and the Olympic moves of 2020 and 2021.</li>
 * </ul>
 *
 * <p>Worked example: {@code calendar(Market.NEW_YORK_FED, 2026, 2026)
 * .addBusinessDays(2026-11-24, 3)} skips Thanksgiving (Thu 26 Nov) and
 * the weekend and returns Mon 30 Nov 2026; a weekends-only calendar
 * returns Fri 27 Nov.</p>
 */
public final class HolidayCalendars {

    /** The settlement centres with rule-based calendars. */
    public enum Market {
        /** US federal holidays as observed by the Federal Reserve (USD, SOFR). */
        NEW_YORK_FED,
        /** England and Wales bank holidays (GBP, SONIA). */
        LONDON,
        /** TARGET2 closing days (EUR, ESTR). */
        TARGET,
        /** Japanese national holidays plus bank closing days (JPY, TONA). */
        TOKYO
    }

    private HolidayCalendars() {
    }

    /**
     * A business calendar for a market, holidays generated for every year
     * in {@code [fromYear, toYear]}. Dates outside that range count only
     * weekends as non-business days, so choose the range to cover the
     * trade's whole life plus a margin.
     *
     * @param market   the centre
     * @param fromYear first year, inclusive
     * @param toYear   last year, inclusive
     * @return the calendar
     */
    public static BusinessCalendar calendar(Market market, int fromYear, int toYear) {
        Objects.requireNonNull(market, "market");
        if (toYear < fromYear) {
            throw new IllegalArgumentException("toYear must be >= fromYear");
        }
        Set<LocalDate> all = new TreeSet<>();
        for (int y = fromYear; y <= toYear; y++) {
            all.addAll(holidays(market, y));
        }
        return BusinessCalendar.withHolidays(all);
    }

    /**
     * The weekday holidays of one year (holidays falling on a weekend and
     * not moved are left out, since the weekend already closes the day).
     *
     * @param market the centre
     * @param year   the year (Tokyo: 2000 to 2099)
     * @return sorted holiday dates
     */
    public static Set<LocalDate> holidays(Market market, int year) {
        Objects.requireNonNull(market, "market");
        Set<LocalDate> h = switch (market) {
            case NEW_YORK_FED -> newYorkFed(year);
            case LONDON -> london(year);
            case TARGET -> target(year);
            case TOKYO -> tokyo(year);
        };
        h.removeIf(HolidayCalendars::isWeekend);
        return java.util.Collections.unmodifiableSet(h);
    }

    /**
     * Gregorian Easter Sunday (the anonymous Gregorian algorithm, Meeus,
     * Astronomical Algorithms, ch. 8).
     *
     * @param year the year, 1583 or later
     * @return Easter Sunday
     */
    public static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static LocalDate nth(int year, Month m, int n, DayOfWeek dow) {
        return LocalDate.of(year, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    private static LocalDate last(int year, Month m, DayOfWeek dow) {
        return LocalDate.of(year, m, 1).with(TemporalAdjusters.lastInMonth(dow));
    }

    private static LocalDate fedObserved(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.plusDays(1) : d;
    }

    private static Set<LocalDate> newYorkFed(int y) {
        Set<LocalDate> h = new TreeSet<>();
        h.add(fedObserved(LocalDate.of(y, 1, 1)));
        h.add(nth(y, Month.JANUARY, 3, DayOfWeek.MONDAY));
        h.add(nth(y, Month.FEBRUARY, 3, DayOfWeek.MONDAY));
        h.add(last(y, Month.MAY, DayOfWeek.MONDAY));
        if (y >= 2022) {
            h.add(fedObserved(LocalDate.of(y, 6, 19)));
        }
        h.add(fedObserved(LocalDate.of(y, 7, 4)));
        h.add(nth(y, Month.SEPTEMBER, 1, DayOfWeek.MONDAY));
        h.add(nth(y, Month.OCTOBER, 2, DayOfWeek.MONDAY));
        h.add(fedObserved(LocalDate.of(y, 11, 11)));
        h.add(nth(y, Month.NOVEMBER, 4, DayOfWeek.THURSDAY));
        h.add(fedObserved(LocalDate.of(y, 12, 25)));
        return h;
    }

    private static Set<LocalDate> london(int y) {
        Set<LocalDate> h = new TreeSet<>();
        LocalDate ny = LocalDate.of(y, 1, 1);
        h.add(isWeekend(ny) ? ny.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) : ny);
        LocalDate easter = easterSunday(y);
        h.add(easter.minusDays(2));
        h.add(easter.plusDays(1));
        if (y == 1995 || y == 2020) {
            h.add(LocalDate.of(y, 5, 8));                 // VE Day anniversaries
        } else {
            h.add(nth(y, Month.MAY, 1, DayOfWeek.MONDAY));
        }
        switch (y) {
            case 2002 -> h.addAll(Set.of(LocalDate.of(2002, 6, 3), LocalDate.of(2002, 6, 4)));
            case 2012 -> h.addAll(Set.of(LocalDate.of(2012, 6, 4), LocalDate.of(2012, 6, 5)));
            case 2022 -> h.addAll(Set.of(LocalDate.of(2022, 6, 2), LocalDate.of(2022, 6, 3)));
            default -> h.add(last(y, Month.MAY, DayOfWeek.MONDAY));
        }
        h.add(last(y, Month.AUGUST, DayOfWeek.MONDAY));
        // Christmas and Boxing Day: each weekend day moves to the next free weekday.
        LocalDate xmas = LocalDate.of(y, 12, 25);
        LocalDate boxing = LocalDate.of(y, 12, 26);
        LocalDate x = xmas;
        while (isWeekend(x)) {
            x = x.plusDays(1);
        }
        h.add(x);
        LocalDate b = boxing.isAfter(x) ? boxing : x.plusDays(1);
        while (isWeekend(b) || b.equals(x)) {
            b = b.plusDays(1);
        }
        h.add(b);
        if (y == 1999) {
            h.add(LocalDate.of(1999, 12, 31));
        }
        if (y == 2011) {
            h.add(LocalDate.of(2011, 4, 29));
        }
        if (y == 2022) {
            h.add(LocalDate.of(2022, 9, 19));
        }
        if (y == 2023) {
            h.add(LocalDate.of(2023, 5, 8));
        }
        return h;
    }

    private static Set<LocalDate> target(int y) {
        Set<LocalDate> h = new TreeSet<>();
        LocalDate easter = easterSunday(y);
        h.add(LocalDate.of(y, 1, 1));
        h.add(easter.minusDays(2));
        h.add(easter.plusDays(1));
        h.add(LocalDate.of(y, 5, 1));
        h.add(LocalDate.of(y, 12, 25));
        h.add(LocalDate.of(y, 12, 26));
        return h;
    }

    /** Equinox day of month, valid 1980 to 2099 (Japanese National Astronomical Observatory approximation). */
    private static int equinoxDay(int y, double base) {
        return (int) Math.floor(base + 0.242194 * (y - 1980) - Math.floor((y - 1980) / 4.0));
    }

    private static Set<LocalDate> tokyo(int y) {
        if (y < 2000 || y > 2099) {
            throw new IllegalArgumentException("Tokyo calendar supports 2000 to 2099, got " + y);
        }
        Set<LocalDate> nat = new TreeSet<>();
        nat.add(LocalDate.of(y, 1, 1));
        nat.add(nth(y, Month.JANUARY, 2, DayOfWeek.MONDAY));            // Coming of Age
        nat.add(LocalDate.of(y, 2, 11));                                 // Foundation
        if (y >= 2020) {
            nat.add(LocalDate.of(y, 2, 23));                             // Emperor's birthday
        }
        nat.add(LocalDate.of(y, 3, equinoxDay(y, 20.8431)));
        nat.add(LocalDate.of(y, 4, 29));                                 // Showa
        nat.add(LocalDate.of(y, 5, 3));
        nat.add(LocalDate.of(y, 5, 4));
        nat.add(LocalDate.of(y, 5, 5));
        if (y == 2020) {
            nat.addAll(Set.of(LocalDate.of(2020, 7, 23), LocalDate.of(2020, 7, 24),
                    LocalDate.of(2020, 8, 10)));
        } else if (y == 2021) {
            nat.addAll(Set.of(LocalDate.of(2021, 7, 22), LocalDate.of(2021, 7, 23),
                    LocalDate.of(2021, 8, 8)));
        } else {
            nat.add(y >= 2003 ? nth(y, Month.JULY, 3, DayOfWeek.MONDAY) : LocalDate.of(y, 7, 20));
            if (y >= 2016) {
                nat.add(LocalDate.of(y, 8, 11));                         // Mountain Day
            }
            nat.add(nth(y, Month.OCTOBER, 2, DayOfWeek.MONDAY));         // Sports Day
        }
        nat.add(y >= 2003 ? nth(y, Month.SEPTEMBER, 3, DayOfWeek.MONDAY) : LocalDate.of(y, 9, 15));
        nat.add(LocalDate.of(y, 9, equinoxDay(y, 23.2488)));
        nat.add(LocalDate.of(y, 11, 3));
        nat.add(LocalDate.of(y, 11, 23));
        if (y <= 2018) {
            nat.add(LocalDate.of(y, 12, 23));
        }
        if (y == 2019) {
            nat.addAll(Set.of(LocalDate.of(2019, 4, 30), LocalDate.of(2019, 5, 1),
                    LocalDate.of(2019, 5, 2), LocalDate.of(2019, 10, 22)));
        }
        // Citizens' holiday: a non-Sunday day between two national holidays.
        Set<LocalDate> sandwiched = new TreeSet<>();
        for (LocalDate d : nat) {
            LocalDate mid = d.plusDays(1);
            if (nat.contains(d.plusDays(2)) && !nat.contains(mid)
                    && mid.getDayOfWeek() != DayOfWeek.SUNDAY) {
                sandwiched.add(mid);
            }
        }
        nat.addAll(sandwiched);
        // Substitute holiday: a holiday on Sunday moves to the next non-holiday day.
        Set<LocalDate> subs = new TreeSet<>();
        for (LocalDate d : nat) {
            if (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                LocalDate s = d.plusDays(1);
                while (nat.contains(s) || subs.contains(s)) {
                    s = s.plusDays(1);
                }
                subs.add(s);
            }
        }
        nat.addAll(subs);
        nat.add(LocalDate.of(y, 1, 2));                                   // bank closing days
        nat.add(LocalDate.of(y, 1, 3));
        nat.add(LocalDate.of(y, 12, 31));
        return nat;
    }
}
