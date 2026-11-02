package com.quantfinlib.credit;

import com.quantfinlib.rates.BusinessCalendar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds the discount curve of the ISDA CDS Standard Model from par swap
 * rates (the swap-only RFR curve used by the SOFR-era test grids).
 *
 * <p><b>Plain idea.</b> The quoted swaps are priced at par one at a time,
 * shortest first. For each new maturity we search for the one zero rate
 * that makes that swap worth zero, keeping the zero rates already found for
 * the shorter swaps. The result is an {@link IsdaCurve}, so it plugs
 * straight into {@link IsdaCdsModel}.</p>
 *
 * <p><b>Formal statement.</b> With trade date {@code T} (the curve base
 * date) and spot {@code S = T + 2} business days, a swap of tenor {@code m}
 * months matures at {@code M = MF(S + m months)} (modified following). Its
 * fixed leg pays annually on {@code MF(S + 12k months)}, the last payment on
 * {@code M}; a tenor below one year has a single payment. Accrual fractions
 * are ACT/360. The zero rate {@code r_i} at {@code t_i = (M - T)/365} solves
 * {@code P(M) + c * sum_k tau_k P(d_k) - P(S) = 0}, where {@code P} is the
 * {@link IsdaCurve} with knots {@code (t_1..t_i, r_1..r_i)}, linear in
 * {@code r t} between knots and flat outside them. The root is found by 200
 * bisection steps on {@code [-0.5, 1.0]}, far more than needed to reach
 * double precision.</p>
 *
 * <p><b>Worked example.</b> Trade Monday 2021-04-26 on a weekends-only
 * calendar, so spot is 2021-04-28. A 6M swap at 2% matures 2021-10-28 and
 * pays once, 183/360 of the coupon. Its knot sits at 185/365 years, and the
 * zero rate solves {@code P(185/365) (1 + 0.02 * 183/360) = P(2/365)}; with
 * the flat extrapolation of {@link IsdaCurve} both discount factors use the
 * same rate, giving {@code r = ln(1 + 0.02 * 183/360) / (183/365)}, about
 * 2.0175% (own test, {@code IsdaRateCurveTest}).</p>
 *
 * <p><b>Evidence.</b> Used with the official ISDA CDS Standard Model RFR
 * grids (cdsmodel.com, USD SOFR 2021-04-26 and 2022-06-21, 7164 rows each),
 * the curve reproduces cash settlement within 1e-6 USD on 10mm notional and
 * clean upfront within 1e-13 (published reference, checked by
 * {@code IsdaGridVerificationTest} when {@code QFL_ISDA_GRID_DIR} is set).
 * Money-market deposits are not supported: the RFR grids quote swaps only.</p>
 */
public final class IsdaRateCurve {

    private IsdaRateCurve() {
    }

    /**
     * One par swap quote.
     *
     * @param tenor tenor such as {@code "6M"} or {@code "10Y"}
     * @param rate  par fixed rate as a decimal (0.02 = 2%)
     */
    public record Swap(String tenor, double rate) {
        /** Validates the tenor format and that the rate is finite. */
        public Swap {
            Objects.requireNonNull(tenor, "tenor");
            tenorMonths(tenor);
            if (!Double.isFinite(rate)) {
                throw new IllegalArgumentException("rate must be finite: " + rate);
            }
        }
    }

    static int tenorMonths(String tenor) {
        if (tenor.length() < 2 || !(tenor.endsWith("M") || tenor.endsWith("Y"))) {
            throw new IllegalArgumentException("tenor must look like 6M or 5Y: " + tenor);
        }
        int num;
        try {
            num = Integer.parseInt(tenor.substring(0, tenor.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("tenor must look like 6M or 5Y: " + tenor, e);
        }
        if (num <= 0) {
            throw new IllegalArgumentException("tenor must be positive: " + tenor);
        }
        return tenor.endsWith("M") ? num : 12 * num;
    }

    private static double yrs(LocalDate base, LocalDate d) {
        return ChronoUnit.DAYS.between(base, d) / 365.0;
    }

    /** Fixed-leg payment dates and ACT/360 fractions of one swap. */
    private record Leg(LocalDate spot, LocalDate maturity, List<LocalDate> pays, double[] yfs) {
    }

    private static Leg leg(LocalDate tradeDate, String tenor, BusinessCalendar cal) {
        LocalDate spot = cal.addBusinessDays(tradeDate, 2);
        int months = tenorMonths(tenor);
        LocalDate mat = cal.roll(spot.plusMonths(months), BusinessCalendar.Roll.MODIFIED_FOLLOWING);
        int periods = Math.max(1, months / 12);
        List<LocalDate> pays = new ArrayList<>(periods);
        double[] yfs = new double[periods];
        LocalDate prev = spot;
        for (int k = 1; k <= periods; k++) {
            LocalDate d = k == periods ? mat
                    : cal.roll(spot.plusMonths(12L * k), BusinessCalendar.Roll.MODIFIED_FOLLOWING);
            pays.add(d);
            yfs[k - 1] = ChronoUnit.DAYS.between(prev, d) / 360.0;
            prev = d;
        }
        return new Leg(spot, mat, pays, yfs);
    }

    private static double value(IsdaCurve cv, LocalDate trade, Leg leg, double c) {
        double pv = cv.value(yrs(trade, leg.maturity()));
        for (int k = 0; k < leg.pays().size(); k++) {
            pv += c * leg.yfs()[k] * cv.value(yrs(trade, leg.pays().get(k)));
        }
        return pv - cv.value(yrs(trade, leg.spot()));
    }

    /**
     * Bootstraps the swap-only zero curve.
     *
     * @param tradeDate curve base date
     * @param swaps     par swaps in strictly increasing maturity order
     * @param calendar  business calendar for spot, rolls and payment dates
     * @return zero curve with one knot per swap maturity (ACT/365F from trade date)
     */
    public static IsdaCurve bootstrapSwaps(LocalDate tradeDate, List<Swap> swaps, BusinessCalendar calendar) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(calendar, "calendar");
        if (swaps == null || swaps.isEmpty()) {
            throw new IllegalArgumentException("at least one swap required");
        }
        int n = swaps.size();
        double[] t = new double[n];
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            Leg leg = leg(tradeDate, swaps.get(i).tenor(), calendar);
            t[i] = yrs(tradeDate, leg.maturity());
            if (i > 0 && !(t[i] > t[i - 1])) {
                throw new IllegalArgumentException("swap maturities must be strictly increasing at " + swaps.get(i));
            }
            double c = swaps.get(i).rate();
            double lo = -0.5;
            double hi = 1.0;
            for (int it = 0; it < 200; it++) {
                double mid = 0.5 * (lo + hi);
                r[i] = mid;
                IsdaCurve cv = IsdaCurve.ofZeroRates(Arrays.copyOf(t, i + 1), Arrays.copyOf(r, i + 1));
                if (value(cv, tradeDate, leg, c) > 0) {   // value decreases in r
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            r[i] = 0.5 * (lo + hi);
        }
        return IsdaCurve.ofZeroRates(t, r);
    }

    /**
     * Value per unit notional of a receive-fixed swap on {@code curve} under
     * the conventions above, including the notional exchange: zero for a
     * quote the curve was bootstrapped from.
     *
     * @param curve     discount curve with base date {@code tradeDate}
     * @param tradeDate curve base date
     * @param swap      the swap
     * @param calendar  business calendar
     * @return {@code P(M) + c * sum tau_k P(d_k) - P(S)}
     */
    public static double parSwapValue(IsdaCurve curve, LocalDate tradeDate, Swap swap, BusinessCalendar calendar) {
        Objects.requireNonNull(curve, "curve");
        return value(curve, tradeDate, leg(tradeDate, swap.tenor(), calendar), swap.rate());
    }
}
