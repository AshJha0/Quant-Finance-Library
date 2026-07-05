package com.quantfinlib.fx;

import com.quantfinlib.rates.BusinessCalendar;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/**
 * Market conventions for an FX currency pair: quotation precision, pip size,
 * spot lag, and settlement-date arithmetic against <em>both</em> currencies'
 * holiday calendars.
 *
 * <p>Everything a strategy needs at runtime (pip size, precision, spot lag)
 * is resolved to primitives at construction, so hot-path code can hold a
 * {@code CurrencyPair} per dense symbol id and never re-parse conventions
 * per tick. The date arithmetic ({@link #spotDate}, {@link #tenorDate}) is
 * calendar work for the pricing/booking layer, not the tick path.</p>
 *
 * <h2>Conventions encoded</h2>
 * <ul>
 *   <li><b>Pip size / precision</b> — JPY-quoted pairs quote to 3 decimals
 *       (pip = 0.01), everything else to 5 (pip = 0.0001). Half-pip "points"
 *       shown by ECNs are the extra decimal.</li>
 *   <li><b>Spot lag</b> — T+2 for most pairs; T+1 for USDCAD, USDTRY, USDRUB
 *       and USDPHP (the market's short-dated exceptions).</li>
 *   <li><b>Joint calendar</b> — a settlement date must be a business day in
 *       both currencies' main centers. This class treats intermediate days
 *       the same way, a slight simplification of the full market rule (where
 *       a USD holiday only blocks the settlement day itself, not the
 *       intermediate days).</li>
 *   <li><b>Forward tenors</b> — months/years roll modified-following with the
 *       end-end rule: if spot is the last business day of its month, the
 *       forward date is the last business day of the target month.</li>
 * </ul>
 */
public final class CurrencyPair {

    private final String base;
    private final String quote;
    private final double pipSize;
    private final int pricePrecision;
    private final int spotLagDays;
    private final BusinessCalendar baseCalendar;
    private final BusinessCalendar quoteCalendar;

    private CurrencyPair(String base, String quote, double pipSize, int pricePrecision,
                         int spotLagDays, BusinessCalendar baseCalendar,
                         BusinessCalendar quoteCalendar) {
        this.base = base;
        this.quote = quote;
        this.pipSize = pipSize;
        this.pricePrecision = pricePrecision;
        this.spotLagDays = spotLagDays;
        this.baseCalendar = baseCalendar;
        this.quoteCalendar = quoteCalendar;
    }

    /**
     * Standard conventions for a 6-letter pair code ("EURUSD", "USDJPY", ...),
     * weekends-only calendars. Use {@link #withCalendars} to attach real
     * holiday calendars afterwards.
     */
    public static CurrencyPair of(String pair) {
        if (pair == null || pair.length() != 6) {
            throw new IllegalArgumentException("pair must be 6 letters, e.g. EURUSD: " + pair);
        }
        return of(pair.substring(0, 3), pair.substring(3, 6));
    }

    /** Standard conventions for an explicit base/quote, weekends-only calendars. */
    public static CurrencyPair of(String base, String quote) {
        String b = base.toUpperCase(Locale.ROOT);
        String q = quote.toUpperCase(Locale.ROOT);
        // JPY-quoted pairs trade to 3 decimals; everything else to 5.
        double pip = q.equals("JPY") ? 0.01 : 0.0001;
        int precision = q.equals("JPY") ? 3 : 5;
        int lag = spotLagFor(b, q);
        BusinessCalendar weekends = BusinessCalendar.weekendsOnly();
        return new CurrencyPair(b, q, pip, precision, lag, weekends, weekends);
    }

    /** Fully custom conventions (exotic pairs, onshore fixings, tests). */
    public static CurrencyPair custom(String base, String quote, double pipSize,
                                      int pricePrecision, int spotLagDays,
                                      BusinessCalendar baseCalendar,
                                      BusinessCalendar quoteCalendar) {
        if (pipSize <= 0 || spotLagDays < 0) {
            throw new IllegalArgumentException("pipSize must be > 0 and spotLagDays >= 0");
        }
        return new CurrencyPair(base.toUpperCase(Locale.ROOT), quote.toUpperCase(Locale.ROOT),
                pipSize, pricePrecision, spotLagDays, baseCalendar, quoteCalendar);
    }

    /** Same conventions with real holiday calendars for each currency's center. */
    public CurrencyPair withCalendars(BusinessCalendar baseCalendar, BusinessCalendar quoteCalendar) {
        return new CurrencyPair(base, quote, pipSize, pricePrecision, spotLagDays,
                baseCalendar, quoteCalendar);
    }

    /** T+1 market exceptions; every other pair settles T+2. */
    private static int spotLagFor(String base, String quote) {
        String pair = base + quote;
        return switch (pair) {
            case "USDCAD", "USDTRY", "USDRUB", "USDPHP" -> 1;
            default -> 2;
        };
    }

    // ------------------------------------------------------------------
    // Static conventions
    // ------------------------------------------------------------------

    public String base() {
        return base;
    }

    public String quote() {
        return quote;
    }

    /** "EURUSD" style symbol, the natural key for the tick bus. */
    public String symbol() {
        return base + quote;
    }

    /** One pip in price terms (0.0001, or 0.01 for JPY quotes). */
    public double pipSize() {
        return pipSize;
    }

    /** Quoted decimal places (5, or 3 for JPY quotes). */
    public int pricePrecision() {
        return pricePrecision;
    }

    /** Spot settlement lag in business days (T+2, or T+1 exceptions). */
    public int spotLagDays() {
        return spotLagDays;
    }

    /** Converts a price difference to pips (e.g. 0.00013 → 1.3 pips on EURUSD). */
    public double pips(double priceDifference) {
        return priceDifference / pipSize;
    }

    /** Converts pips to a price difference (the inverse of {@link #pips}). */
    public double priceFromPips(double pips) {
        return pips * pipSize;
    }

    /** Rounds a raw price to the pair's quoted precision (half-up). */
    public double round(double price) {
        double scale = Math.pow(10, pricePrecision);
        return Math.round(price * scale) / scale;
    }

    // ------------------------------------------------------------------
    // Settlement-date arithmetic (pricing/booking layer, not the tick path)
    // ------------------------------------------------------------------

    /** Business day in <em>both</em> currencies' calendars. */
    public boolean isJointBusinessDay(LocalDate date) {
        return baseCalendar.isBusinessDay(date) && quoteCalendar.isBusinessDay(date);
    }

    /**
     * Spot settlement date: {@code spotLagDays} joint business days after the
     * trade date (see the class doc for the intermediate-day simplification).
     */
    public LocalDate spotDate(LocalDate tradeDate) {
        return addJointBusinessDays(tradeDate, spotLagDays);
    }

    /**
     * Forward settlement date for a market tenor, measured from the pair's
     * spot date. Supported tenors:
     * <ul>
     *   <li>{@code ON} — trade date + 1 joint business day (pre-spot leg);</li>
     *   <li>{@code TN} — ON date + 1 joint business day (= spot for T+2 pairs);</li>
     *   <li>{@code SN} — spot + 1 joint business day;</li>
     *   <li>{@code <n>D / <n>W} — spot + days/weeks, rolled following;</li>
     *   <li>{@code <n>M / <n>Y} — spot + months/years, modified-following
     *       with the end-end rule.</li>
     * </ul>
     */
    public LocalDate tenorDate(LocalDate tradeDate, String tenor) {
        String t = tenor.toUpperCase(Locale.ROOT).trim();
        LocalDate spot = spotDate(tradeDate);
        switch (t) {
            case "ON":
                return addJointBusinessDays(tradeDate, 1);
            case "TN":
                return addJointBusinessDays(tradeDate, 2);
            case "SN":
                return addJointBusinessDays(spot, 1);
            default:
                // fall through to <n><unit> parsing below
        }
        int n = Integer.parseInt(t.substring(0, t.length() - 1));
        char unit = t.charAt(t.length() - 1);
        return switch (unit) {
            // Short dates roll forward only: crossing month-end is expected.
            case 'D' -> rollFollowing(spot.plusDays(n));
            case 'W' -> rollFollowing(spot.plusWeeks(n));
            case 'M' -> monthTenor(spot, n);
            case 'Y' -> monthTenor(spot, n * 12);
            default -> throw new IllegalArgumentException("unsupported tenor: " + tenor);
        };
    }

    /**
     * Month/year tenor: end-end rule first (spot on the last business day of
     * its month pins the forward to the last business day of the target
     * month), otherwise modified-following.
     */
    private LocalDate monthTenor(LocalDate spot, int months) {
        LocalDate target = spot.plusMonths(months);
        if (spot.equals(lastJointBusinessDayOf(YearMonth.from(spot)))) {
            return lastJointBusinessDayOf(YearMonth.from(target));
        }
        return rollModifiedFollowing(target);
    }

    /** Adds {@code n} joint business days (n >= 0). */
    public LocalDate addJointBusinessDays(LocalDate date, int n) {
        LocalDate d = date;
        for (int added = 0; added < n; ) {
            d = d.plusDays(1);
            if (isJointBusinessDay(d)) {
                added++;
            }
        }
        return d;
    }

    private LocalDate rollFollowing(LocalDate date) {
        LocalDate d = date;
        while (!isJointBusinessDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    private LocalDate rollModifiedFollowing(LocalDate date) {
        LocalDate rolled = rollFollowing(date);
        // Forward roll crossing month-end flips to backward (market standard).
        if (rolled.getMonth() != date.getMonth()) {
            LocalDate d = date;
            while (!isJointBusinessDay(d)) {
                d = d.minusDays(1);
            }
            return d;
        }
        return rolled;
    }

    private LocalDate lastJointBusinessDayOf(YearMonth month) {
        LocalDate d = month.atEndOfMonth();
        while (!isJointBusinessDay(d)) {
            d = d.minusDays(1);
        }
        return d;
    }

    @Override
    public String toString() {
        return symbol();
    }
}
