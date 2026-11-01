package com.quantfinlib.fx;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * FX forward (swap-points) curve: the market's quoted forward points per
 * tenor, turned into outright forwards for any settlement date.
 *
 * <p>Forward FX does not trade as outright prices. It trades as <em>points</em>
 * (pips to add to spot) at standard tenors (ON, 1W, 1M, 3M, ...). This class
 * stores the pillar dates resolved through {@link CurrencyPair#tenorDate}
 * (so modified-following / end-end are already applied) and interpolates
 * points <b>linearly in actual days</b> between pillars, the interbank
 * convention for broken dates. Beyond the last pillar it extrapolates the
 * slope of the final segment; before the first pillar it interpolates from
 * zero points at spot. Interpolating points linearly in days ignores the
 * discount-factor structure of the forward; that is the interbank
 * convention for broken dates within a year and becomes biased for long
 * dates with large carry, where interpolating the implied carry is better.
 * Pre-spot dates (today, tomorrow) are read from explicit
 * {@link Builder#preSpot} quotes.</p>
 *
 * <p>Covered interest parity connects the curve to rates: with continuously
 * compounded rates, {@code F = S * exp((r_quote - r_base) * tau)}. The
 * {@link #impliedCarry} accessor inverts that, giving the rate differential
 * the points imply — the bridge to {@code rates.YieldCurve} bootstrapping
 * and the input NDF pricing needs for restricted currencies.</p>
 *
 * <p>The curve is immutable after {@link Builder#build}; lookups are
 * allocation-free (binary search over primitive arrays), so a pricing loop
 * can call {@link #outright} per tick if needed.</p>
 */
public final class SwapPointsCurve {

    private final CurrencyPair pair;
    private final LocalDate spotDate;
    private final double spotRate;
    /** Pillar offsets in actual days from spot, ascending. */
    private final long[] pillarDays;
    /** Forward points in price terms (pips already scaled by pip size). */
    private final double[] pillarPoints;
    private final String[] tenors;
    /** Pre-spot dates (days before spot, positive) and points from that date to spot. */
    private final java.util.Map<Long, Double> preSpot;

    private SwapPointsCurve(CurrencyPair pair, LocalDate spotDate, double spotRate,
                            long[] pillarDays, double[] pillarPoints, String[] tenors,
                            java.util.Map<Long, Double> preSpot) {
        this.pair = pair;
        this.spotDate = spotDate;
        this.spotRate = spotRate;
        this.pillarDays = pillarDays;
        this.pillarPoints = pillarPoints;
        this.tenors = tenors;
        this.preSpot = preSpot;
    }

    public static Builder builder(CurrencyPair pair, LocalDate tradeDate, double spotRate) {
        return new Builder(pair, tradeDate, spotRate);
    }

    /** Accumulates tenor/points quotes, then freezes them into a curve. */
    public static final class Builder {
        private final CurrencyPair pair;
        private final LocalDate tradeDate;
        private final double spotRate;
        private final List<String> tenors = new ArrayList<>();
        private final List<LocalDate> dates = new ArrayList<>();
        private final List<Double> points = new ArrayList<>();
        private final java.util.Map<Long, Double> preSpot = new java.util.HashMap<>();

        private Builder(CurrencyPair pair, LocalDate tradeDate, double spotRate) {
            if (spotRate <= 0) {
                throw new IllegalArgumentException("spotRate must be > 0: " + spotRate);
            }
            this.pair = pair;
            this.tradeDate = tradeDate;
            this.spotRate = spotRate;
        }

        /**
         * Adds a pillar quoted in <b>pips</b> (market form: "1M EURUSD +12.6"),
         * scaled by the pair's pip size internally. Negative points are normal
         * when the base currency yields more than the quote currency.
         */
        public Builder add(String tenor, double pips) {
            LocalDate date = pair.tenorDate(tradeDate, tenor);
            if (!date.isAfter(pair.spotDate(tradeDate))) {
                throw new IllegalArgumentException(
                        "tenor " + tenor + " does not settle after spot; pre-spot legs (ON/TN) "
                                + "belong to the roll, not the forward curve");
            }
            tenors.add(tenor);
            dates.add(date);
            points.add(pips * pair.pipSize());
            return this;
        }

        /**
         * Pre-spot value date (today or tomorrow) with the cumulative swap
         * points, in pips, from that date to spot: the outright for that date
         * is {@code spot − points}. For a T+2 pair, tomorrow takes the TN
         * points and today takes ON + TN. The caller states the cumulative
         * amount so no quoting-side convention is assumed here.
         */
        public Builder preSpot(LocalDate valueDate, double pipsToSpot) {
            LocalDate spot = pair.spotDate(tradeDate);
            long d = ChronoUnit.DAYS.between(valueDate, spot);
            if (d <= 0 || valueDate.isBefore(tradeDate)) {
                throw new IllegalArgumentException("pre-spot date must be in [trade date, spot)");
            }
            if (!Double.isFinite(pipsToSpot)) {
                throw new IllegalArgumentException("points must be finite");
            }
            preSpot.put(d, pipsToSpot * pair.pipSize());
            return this;
        }

        public SwapPointsCurve build() {
            if (dates.isEmpty()) {
                throw new IllegalStateException("at least one pillar required");
            }
            LocalDate spot = pair.spotDate(tradeDate);
            int n = dates.size();
            long[] days = new long[n];
            double[] pts = new double[n];
            String[] tns = new String[n];
            // Sort pillars by date (quotes may arrive in any order).
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> dates.get(a).compareTo(dates.get(b)));
            for (int i = 0; i < n; i++) {
                int src = order[i];
                days[i] = ChronoUnit.DAYS.between(spot, dates.get(src));
                pts[i] = points.get(src);
                tns[i] = tenors.get(src);
                if (i > 0 && days[i] == days[i - 1]) {
                    throw new IllegalArgumentException("duplicate pillar date at " + tns[i]);
                }
            }
            return new SwapPointsCurve(pair, spot, spotRate, days, pts, tns,
                    java.util.Map.copyOf(preSpot));
        }
    }

    // ------------------------------------------------------------------
    // Lookups (allocation-free)
    // ------------------------------------------------------------------

    /**
     * Interpolated forward points (price terms) for a settlement date:
     * linear in actual days, anchored at zero on the spot date.
     */
    public double forwardPoints(LocalDate valueDate) {
        long d = ChronoUnit.DAYS.between(spotDate, valueDate);
        if (d < 0) {
            Double pre = preSpot.get(-d);
            if (pre == null) {
                throw new IllegalArgumentException("valueDate " + valueDate + " is before spot "
                        + spotDate + " and no pre-spot points were given for it");
            }
            return -pre;
        }
        if (d == 0) {
            return 0;
        }
        int n = pillarDays.length;
        // Before the first pillar: interpolate from (0 days, 0 points).
        if (d <= pillarDays[0]) {
            return pillarPoints[0] * ((double) d / pillarDays[0]);
        }
        // Beyond the last pillar: extend the final segment's slope.
        if (d >= pillarDays[n - 1]) {
            if (n == 1) {
                return pillarPoints[0] * ((double) d / pillarDays[0]);
            }
            double slope = (pillarPoints[n - 1] - pillarPoints[n - 2])
                    / (pillarDays[n - 1] - pillarDays[n - 2]);
            return pillarPoints[n - 1] + slope * (d - pillarDays[n - 1]);
        }
        // Binary search for the bracketing pillars.
        int lo = 0;
        int hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (pillarDays[mid] <= d) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double w = (double) (d - pillarDays[lo]) / (pillarDays[hi] - pillarDays[lo]);
        return pillarPoints[lo] + w * (pillarPoints[hi] - pillarPoints[lo]);
    }

    /** Outright forward: spot plus interpolated points. */
    public double outright(LocalDate valueDate) {
        return spotRate + forwardPoints(valueDate);
    }

    /** Outright forward for a market tenor of this curve's pair. */
    public double outright(String tenor) {
        // Tenor dates are anchored at spot, so any trade date sharing this
        // curve's spot gives the same pillar; reuse the curve's own anchor.
        return outright(tenorDateFromSpot(tenor));
    }

    /**
     * Continuously compounded rate differential (quote minus base) implied by
     * covered interest parity at a date: {@code ln(F/S) / tau}, ACT/365.
     * Positive when the quote currency yields more (points ascending).
     */
    public double impliedCarry(LocalDate valueDate) {
        return impliedCarry(valueDate, com.quantfinlib.rates.DayCount.ACT_365);
    }

    /** {@link #impliedCarry(LocalDate)} with tau measured in the given day count. */
    public double impliedCarry(LocalDate valueDate, com.quantfinlib.rates.DayCount dayCount) {
        if (!valueDate.isAfter(spotDate)) {
            throw new IllegalArgumentException("valueDate must be after spot");
        }
        double tau = dayCount.yearFraction(spotDate, valueDate);
        return Math.log(outright(valueDate) / spotRate) / tau;
    }

    /** The spot settlement date all pillar offsets are measured from. */
    public LocalDate spotDate() {
        return spotDate;
    }

    public double spotRate() {
        return spotRate;
    }

    public CurrencyPair pair() {
        return pair;
    }

    /** Pillar tenors in date order (diagnostics/reporting). */
    public String[] pillarTenors() {
        return tenors.clone();
    }

    /** Resolves a tenor to a date using this curve's spot as the anchor. */
    private LocalDate tenorDateFromSpot(String tenor) {
        // CurrencyPair.tenorDate anchors at spotDate(tradeDate); the pair's
        // spot inversion keeps the anchors aligned.
        return pair.tenorDate(pair.tradeDateForSpot(spotDate), tenor);
    }
}
