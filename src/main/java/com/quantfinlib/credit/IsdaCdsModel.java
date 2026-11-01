package com.quantfinlib.credit;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The ISDA CDS Standard Model: the pricing conventions every post-2009
 * standard CDS is quoted and settled with, beside the textbook quarterly
 * grid of {@link CdsPricer}. Sources: ISDA CDS Standard Model v1.8.2
 * (the open-source JPMorgan library, functions {@code JpmcdsCdsContingentLegPV}
 * and {@code JpmcdsFeeLegPV}); "ISDA CDS Standard Model proposed numerical
 * fix" (Markit, 2009); Markit, "The CDS Big Bang" (2009) for the contract
 * conventions; O'Kane, Modelling Single-name and Multi-name Credit
 * Derivatives (Wiley 2008), ch. 6.
 *
 * <p>Contract conventions (all implemented here):</p>
 * <ul>
 *   <li>Coupon dates are the IMM dates 20 Mar, 20 Jun, 20 Sep, 20 Dec.
 *   Maturity rolls semi-annually (ISDA, December 2015 rule): a trade
 *   dated on or after 20 Mar and before 20 Sep matures on 20 Jun of
 *   year + tenor; on or after 20 Sep, 20 Dec of year + tenor; before
 *   20 Mar, 20 Dec of year - 1 + tenor. Maturity is not adjusted.</li>
 *   <li>Step-in date T+1 calendar day; cash settlement T+3 business
 *   days; accrual starts on the last IMM date, business-day adjusted
 *   (following), on or before the step-in date.</li>
 *   <li>Quarterly periods. Accrual and payment dates are adjusted
 *   following; the last accrual period ends on maturity + 1 day (the
 *   maturity date itself is protected and accrues) and pays on the
 *   adjusted maturity. Accrual is ACT/360.</li>
 *   <li>Business days are Monday to Friday. Holidays are not applied:
 *   pass an adjusted date where a holiday matters.</li>
 * </ul>
 *
 * <p>Model (all times ACT/365F from the trade date, curves as
 * {@link IsdaCurve}, so hazard {@code lambda} and forward {@code f} are
 * constant on each segment between the merged node times of the
 * discount and survival curves). On a segment of length dt starting
 * with survival Q0 and discount P0, with {@code k = lambda + f}:</p>
 *
 * <pre>
 *   protection:  integral h Q P dt                  = lambda Q0 P0 (1 - e^{-k dt}) / k
 *   accrual on default, accrued c(t) = a (t - tS):
 *                integral a (t - tS) h Q P dt       = a lambda Q0 P0 [ (t0 - tS) (1 - e^{-k dt}) / k
 *                                                      + (1 - e^{-k dt} (1 + k dt)) / k^2 ]
 * </pre>
 *
 * <p>(the second is the integration by parts of the first; small k uses
 * the Taylor series). Protection runs from the trade date (step-in minus
 * one day) to maturity and pays {@code 1 - R} at default. Each premium
 * period pays {@code ACT/360(start, end)} at the payment date if the
 * name survives to {@code end - 1 day}, plus the accrued coupon at
 * default, the accrual rate being {@code a = ACT/360 days / ACT/365 span}
 * on the window [start - 1 day, end - 1 day] (the v1.8.2
 * observation-offset convention). Present values are divided by P at the
 * cash-settlement date, so they are amounts paid on that date.</p>
 *
 * <p>Quoting: {@code cleanUpfront = protection - coupon * (RPV01_dirty -
 * accrued)} is the points upfront the buyer pays per unit notional;
 * {@code dirtyUpfront = protection - coupon * RPV01_dirty} is the cash
 * that changes hands at settlement. The quoted (conventional) spread is
 * the par spread of the flat hazard curve that reprices the upfront,
 * with recovery 40% for standard contracts and fixed coupon 100bp or
 * 500bp.</p>
 *
 * <p>Known gaps against the C library: the "half-day" accrual-on-default
 * adjustment some vendors apply is not included, and the published
 * Markit test grid is not reproduced in the tests (it could not be
 * verified offline); the tests instead pin the closed-form segment
 * integrals against independent Gauss-Legendre quadrature to 1e-10
 * and the conventions against hand-built schedules. Research lane,
 * deterministic.</p>
 */
public final class IsdaCdsModel {

    /** Standard recovery rate for senior unsecured quoting. */
    public static final double STANDARD_RECOVERY = 0.40;
    /** Standard investment-grade coupon, 100bp. */
    public static final double COUPON_100BP = 0.01;
    /** Standard high-yield coupon, 500bp. */
    public static final double COUPON_500BP = 0.05;

    private static final double SMALL = 1e-6;

    private IsdaCdsModel() {
    }

    /**
     * One CDS contract's dates and terms.
     *
     * @param tradeDate        trade date T (the valuation date)
     * @param stepInDate       T + 1 calendar day
     * @param cashSettleDate   T + 3 business days
     * @param accrualStartDate first accrual date (adjusted IMM date)
     * @param maturityDate     protection end date (unadjusted)
     * @param coupon           running coupon, decimal per year (0.01 = 100bp)
     * @param recovery         recovery rate in [0, 1)
     */
    public record Contract(LocalDate tradeDate, LocalDate stepInDate, LocalDate cashSettleDate,
                           LocalDate accrualStartDate, LocalDate maturityDate,
                           double coupon, double recovery) {
        /** Validates the terms. */
        public Contract {
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(stepInDate, "stepInDate");
            Objects.requireNonNull(cashSettleDate, "cashSettleDate");
            Objects.requireNonNull(accrualStartDate, "accrualStartDate");
            Objects.requireNonNull(maturityDate, "maturityDate");
            if (stepInDate.isBefore(tradeDate) || cashSettleDate.isBefore(tradeDate)) {
                throw new IllegalArgumentException("step-in and cash settle must not precede trade");
            }
            if (accrualStartDate.isAfter(stepInDate)) {
                throw new IllegalArgumentException("accrual start must be on or before step-in");
            }
            if (!maturityDate.isAfter(stepInDate)) {
                throw new IllegalArgumentException("maturity must be after step-in");
            }
            if (!(coupon >= 0) || coupon == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("coupon must be >= 0 and finite, got " + coupon);
            }
            if (!(recovery >= 0) || !(recovery < 1)) {
                throw new IllegalArgumentException("recovery must be in [0, 1), got " + recovery);
            }
        }

        /** The same contract with another coupon. */
        public Contract withCoupon(double newCoupon) {
            return new Contract(tradeDate, stepInDate, cashSettleDate, accrualStartDate,
                    maturityDate, newCoupon, recovery);
        }
    }

    /**
     * A standard contract: rolled maturity, IMM accrual start, T+1 step-in
     * and T+3 business-day settlement.
     *
     * @param tradeDate  trade date
     * @param tenorYears tenor in years, 1 to 30
     * @param coupon     fixed coupon (usually {@link #COUPON_100BP} or {@link #COUPON_500BP})
     * @param recovery   recovery rate (usually {@link #STANDARD_RECOVERY})
     * @return the contract
     */
    public static Contract standardContract(LocalDate tradeDate, int tenorYears, double coupon,
                                            double recovery) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (tenorYears < 1 || tenorYears > 30) {
            throw new IllegalArgumentException("tenorYears must be in [1, 30], got " + tenorYears);
        }
        LocalDate stepIn = tradeDate.plusDays(1);
        return new Contract(tradeDate, stepIn, addBusinessDays(tradeDate, 3),
                accrualStartDate(stepIn), standardMaturity(tradeDate, tenorYears), coupon, recovery);
    }

    /**
     * Standard maturity under the semi-annual roll (20 Jun / 20 Dec).
     *
     * @param tradeDate  trade date
     * @param tenorYears tenor in years, &ge; 0
     * @return unadjusted maturity date
     */
    public static LocalDate standardMaturity(LocalDate tradeDate, int tenorYears) {
        if (tenorYears < 0) {
            throw new IllegalArgumentException("tenorYears must be >= 0");
        }
        int y = tradeDate.getYear();
        LocalDate mar20 = LocalDate.of(y, 3, 20);
        LocalDate sep20 = LocalDate.of(y, 9, 20);
        if (tradeDate.isBefore(mar20)) {
            return LocalDate.of(y - 1 + tenorYears, 12, 20);
        }
        if (tradeDate.isBefore(sep20)) {
            return LocalDate.of(y + tenorYears, 6, 20);
        }
        return LocalDate.of(y + tenorYears, 12, 20);
    }

    /**
     * The first IMM date (20 Mar/Jun/Sep/Dec) strictly after {@code date}.
     *
     * @param date any date
     * @return the next IMM date
     */
    public static LocalDate nextImmDate(LocalDate date) {
        int m = date.getMonthValue();
        int y = date.getYear();
        int immMonth = ((m - 1) / 3 + 1) * 3;   // 3, 6, 9, 12
        LocalDate candidate = LocalDate.of(y, immMonth, 20);
        if (candidate.isAfter(date)) {
            return candidate;
        }
        return immMonth == 12 ? LocalDate.of(y + 1, 3, 20) : LocalDate.of(y, immMonth + 3, 20);
    }

    /**
     * The last IMM date on or before {@code date} (unadjusted).
     *
     * @param date any date
     * @return the previous IMM date
     */
    public static LocalDate previousImmDate(LocalDate date) {
        LocalDate next = nextImmDate(date);
        return next.minusMonths(3).isAfter(date) ? next.minusMonths(6) : next.minusMonths(3);
    }

    /**
     * Accrual start for a step-in date: the last adjusted IMM date on or
     * before it.
     *
     * @param stepInDate step-in date
     * @return adjusted accrual start
     */
    public static LocalDate accrualStartDate(LocalDate stepInDate) {
        LocalDate imm = previousImmDate(stepInDate);
        LocalDate adj = adjustFollowing(imm);
        if (adj.isAfter(stepInDate)) {
            adj = adjustFollowing(previousImmDate(imm.minusDays(1)));
        }
        return adj;
    }

    /** Moves a weekend date to the following Monday. */
    public static LocalDate adjustFollowing(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Adds Monday-to-Friday business days. */
    public static LocalDate addBusinessDays(LocalDate date, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("days must be >= 0");
        }
        LocalDate d = date;
        int left = days;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    /**
     * One premium period of the coupon schedule.
     *
     * @param accrualStart accrual start (adjusted)
     * @param accrualEnd   accrual end (adjusted; maturity + 1 for the last)
     * @param paymentDate  payment date (adjusted)
     */
    public record Period(LocalDate accrualStart, LocalDate accrualEnd, LocalDate paymentDate) {
        /** ACT/360 accrual fraction. */
        public double yearFraction() {
            return ChronoUnit.DAYS.between(accrualStart, accrualEnd) / 360.0;
        }
    }

    /**
     * The coupon schedule from the accrual start to maturity.
     *
     * @param c the contract
     * @return the periods in order
     */
    public static List<Period> schedule(Contract c) {
        List<LocalDate> raw = new ArrayList<>();
        LocalDate d = c.maturityDate();
        // Roll back from maturity in 3-month steps to the accrual start.
        LocalDate start = c.accrualStartDate();
        while (d.isAfter(start)) {
            raw.add(0, d);
            d = d.minusMonths(3);
        }
        List<Period> periods = new ArrayList<>(raw.size());
        LocalDate prev = start;
        for (int i = 0; i < raw.size(); i++) {
            boolean last = i == raw.size() - 1;
            LocalDate end = last ? c.maturityDate().plusDays(1) : adjustFollowing(raw.get(i));
            LocalDate pay = adjustFollowing(raw.get(i));
            if (!end.isAfter(prev)) {
                continue;
            }
            periods.add(new Period(prev, end, pay));
            prev = end;
        }
        return periods;
    }

    private static double years(LocalDate base, LocalDate d) {
        return ChronoUnit.DAYS.between(base, d) / 365.0;
    }

    /**
     * Protection leg value per unit notional, paid at cash settlement:
     * {@code (1 - R) integral_0^tM h Q P dt / P(t_cs)}.
     *
     * @param c        the contract
     * @param discount discount curve, base date = trade date
     * @param survival survival curve, base date = trade date
     * @return the protection leg PV
     */
    public static double protectionLeg(Contract c, IsdaCurve discount, IsdaCurve survival) {
        LocalDate protStart = c.stepInDate().minusDays(1);
        if (protStart.isBefore(c.tradeDate())) {
            protStart = c.tradeDate();
        }
        double t0 = years(c.tradeDate(), protStart);
        double t1 = years(c.tradeDate(), c.maturityDate());
        double[] grid = mergedGrid(discount, survival, t0, t1);
        double pv = 0;
        for (int i = 1; i < grid.length; i++) {
            Seg s = segment(discount, survival, grid[i - 1], grid[i]);
            pv += s.lambda * s.q0p0 * e1(s.k, s.dt);
        }
        return (1 - c.recovery()) * pv / discount.value(years(c.tradeDate(), c.cashSettleDate()));
    }

    /**
     * Dirty risky annuity: the premium leg per unit coupon, including the
     * full current coupon and accrual on default, paid at cash settlement.
     *
     * @param c        the contract
     * @param discount discount curve
     * @param survival survival curve
     * @return dirty RPV01 in years
     */
    public static double riskyAnnuityDirty(Contract c, IsdaCurve discount, IsdaCurve survival) {
        LocalDate today = c.tradeDate();
        double sum = 0;
        for (Period p : schedule(c)) {
            if (!p.accrualEnd().isAfter(c.stepInDate())) {
                continue;
            }
            double yf = p.yearFraction();
            double tS = years(today, p.accrualStart().minusDays(1));
            double tE = years(today, p.accrualEnd().minusDays(1));
            sum += yf * survival.value(tE) * discount.value(years(today, p.paymentDate()));
            double a = yf / (tE - tS);
            double from = Math.max(tS, years(today, c.stepInDate().minusDays(1)));
            double[] grid = mergedGrid(discount, survival, from, tE);
            for (int i = 1; i < grid.length; i++) {
                Seg s = segment(discount, survival, grid[i - 1], grid[i]);
                sum += a * s.lambda * s.q0p0
                        * ((grid[i - 1] - tS) * e1(s.k, s.dt) + e2(s.k, s.dt));
            }
        }
        return sum / discount.value(years(today, c.cashSettleDate()));
    }

    /**
     * Accrued premium fraction at step-in: {@code ACT/360(accrual start, step-in)}.
     *
     * @param c the contract
     * @return accrued year fraction
     */
    public static double accruedYearFraction(Contract c) {
        return ChronoUnit.DAYS.between(c.accrualStartDate(), c.stepInDate()) / 360.0;
    }

    /** Clean risky annuity: dirty minus the accrued fraction. */
    public static double riskyAnnuityClean(Contract c, IsdaCurve discount, IsdaCurve survival) {
        return riskyAnnuityDirty(c, discount, survival) - accruedYearFraction(c);
    }

    /** Par spread: protection over clean risky annuity. */
    public static double parSpread(Contract c, IsdaCurve discount, IsdaCurve survival) {
        return protectionLeg(c, discount, survival) / riskyAnnuityClean(c, discount, survival);
    }

    /** Points upfront per unit notional paid by the protection buyer (clean). */
    public static double cleanUpfront(Contract c, IsdaCurve discount, IsdaCurve survival) {
        return protectionLeg(c, discount, survival)
                - c.coupon() * riskyAnnuityClean(c, discount, survival);
    }

    /** Cash settlement amount per unit notional paid by the buyer (dirty). */
    public static double dirtyUpfront(Contract c, IsdaCurve discount, IsdaCurve survival) {
        return protectionLeg(c, discount, survival)
                - c.coupon() * riskyAnnuityDirty(c, discount, survival);
    }

    /**
     * Converts a quoted (conventional) spread to clean points upfront at
     * the contract's coupon: flat hazard calibrated so the par spread is
     * the quote, using the contract's recovery.
     *
     * @param c            the contract (coupon and recovery)
     * @param quotedSpread conventional spread, decimal, &gt; 0
     * @param discount     discount curve
     * @return clean upfront per unit notional
     */
    public static double quotedSpreadToUpfront(Contract c, double quotedSpread, IsdaCurve discount) {
        double h = flatHazardForParSpread(c, quotedSpread, discount);
        return cleanUpfront(c, discount, IsdaCurve.flat(h));
    }

    /**
     * Converts clean points upfront to the quoted spread: flat hazard
     * that reprices the upfront, then its par spread.
     *
     * @param c            the contract (coupon and recovery)
     * @param cleanUpfront points upfront per unit notional
     * @param discount     discount curve
     * @return the quoted spread, decimal
     */
    public static double upfrontToQuotedSpread(Contract c, double cleanUpfront, IsdaCurve discount) {
        if (!Double.isFinite(cleanUpfront)) {
            throw new IllegalArgumentException("upfront must be finite, got " + cleanUpfront);
        }
        double h = solveFlatHazard(x -> cleanUpfront(c, discount, IsdaCurve.flat(x)) - cleanUpfront,
                "upfront " + cleanUpfront);
        return parSpread(c, discount, IsdaCurve.flat(h));
    }

    /**
     * The flat hazard whose par spread equals {@code parSpread}.
     *
     * @param c         the contract
     * @param parSpread target spread, decimal, &gt; 0
     * @param discount  discount curve
     * @return the hazard per year
     */
    public static double flatHazardForParSpread(Contract c, double parSpread, IsdaCurve discount) {
        if (!(parSpread > 0) || parSpread == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spread must be positive and finite, got " + parSpread);
        }
        Contract atPar = c.withCoupon(parSpread);
        return solveFlatHazard(x -> cleanUpfront(atPar, discount, IsdaCurve.flat(x)),
                "spread " + parSpread);
    }

    /**
     * Bootstraps a piecewise-constant hazard curve from par spreads of
     * standard contracts traded today, one node per maturity.
     *
     * @param tradeDate  trade date
     * @param tenorYears ascending tenors in years
     * @param parSpreads par spreads, decimal, &gt; 0
     * @param recovery   recovery rate
     * @param discount   discount curve
     * @return the survival curve
     */
    public static IsdaCurve bootstrap(LocalDate tradeDate, int[] tenorYears, double[] parSpreads,
                                      double recovery, IsdaCurve discount) {
        int n = tenorYears.length;
        if (n == 0 || parSpreads.length != n) {
            throw new IllegalArgumentException("need aligned, non-empty tenors and spreads");
        }
        double[] times = new double[n];
        double[] hazards = new double[n];
        Contract[] contracts = new Contract[n];
        for (int i = 0; i < n; i++) {
            if (i > 0 && tenorYears[i] <= tenorYears[i - 1]) {
                throw new IllegalArgumentException("tenors must be ascending");
            }
            if (!(parSpreads[i] > 0) || parSpreads[i] == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("spread must be positive: " + parSpreads[i]);
            }
            contracts[i] = standardContract(tradeDate, tenorYears[i], parSpreads[i], recovery);
            times[i] = years(tradeDate, contracts[i].maturityDate());
        }
        for (int k = 0; k < n; k++) {
            final int kk = k;
            hazards[k] = solveFlatHazard(x -> {
                double[] t = Arrays.copyOf(times, kk + 1);
                double[] h = Arrays.copyOf(hazards, kk + 1);
                h[kk] = x;
                return cleanUpfront(contracts[kk], discount, IsdaCurve.ofHazardRates(t, h));
            }, "tenor " + tenorYears[k] + "y spread " + parSpreads[k]);
        }
        return IsdaCurve.ofHazardRates(times, hazards);
    }

    /** Root of a function increasing in the hazard, on [0, 50], by bisection to machine precision. */
    private static double solveFlatHazard(java.util.function.DoubleUnaryOperator f, String what) {
        double lo = 0, hi = 50;
        double flo = f.applyAsDouble(lo);
        double fhi = f.applyAsDouble(hi);
        if (flo > 0 || fhi < 0) {
            throw new IllegalArgumentException(what + " has no hazard in [0, 50]");
        }
        for (int it = 0; it < 200 && hi - lo > 1e-15; it++) {
            double mid = 0.5 * (lo + hi);
            if (f.applyAsDouble(mid) > 0) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private record Seg(double lambda, double k, double dt, double q0p0) {
    }

    private static Seg segment(IsdaCurve discount, IsdaCurve survival, double a, double b) {
        double dt = b - a;
        double lambda = (survival.rateTimesTime(b) - survival.rateTimesTime(a)) / dt;
        double f = (discount.rateTimesTime(b) - discount.rateTimesTime(a)) / dt;
        return new Seg(lambda, lambda + f, dt, survival.value(a) * discount.value(a));
    }

    /** (1 - e^{-k dt}) / k, stable as k dt -&gt; 0. */
    static double e1(double k, double dt) {
        double x = k * dt;
        if (Math.abs(x) < SMALL) {
            return dt * (1 - x / 2 + x * x / 6 - x * x * x / 24);
        }
        return -Math.expm1(-x) / k;
    }

    /** (1 - e^{-k dt} (1 + k dt)) / k^2, stable as k dt -&gt; 0. */
    static double e2(double k, double dt) {
        double x = k * dt;
        if (Math.abs(x) < 0.1) {
            // sum_n (-1)^n (n+1) x^n / (n+2)!, truncated after n = 12 (error below 1e-17).
            double sum = 0;
            double term = 0.5;   // x^0 / 2!
            for (int n = 0; n <= 12; n++) {
                sum += (n + 1) * term;
                term *= -x / (n + 3);
            }
            return dt * dt * sum;
        }
        return (-Math.expm1(-x) - x * Math.exp(-x)) / (k * k);
    }

    private static double[] mergedGrid(IsdaCurve a, IsdaCurve b, double from, double to) {
        if (!(to > from)) {
            return new double[]{from};
        }
        double[] all = new double[a.timesLength() + b.timesLength() + 2];
        int n = 0;
        all[n++] = from;
        for (int i = 0; i < a.timesLength(); i++) {
            double t = a.time(i);
            if (t > from && t < to) {
                all[n++] = t;
            }
        }
        for (int i = 0; i < b.timesLength(); i++) {
            double t = b.time(i);
            if (t > from && t < to) {
                all[n++] = t;
            }
        }
        all[n++] = to;
        double[] g = Arrays.copyOf(all, n);
        Arrays.sort(g);
        int m = 1;
        for (int i = 1; i < g.length; i++) {
            if (g[i] > g[m - 1]) {
                g[m++] = g[i];
            }
        }
        return Arrays.copyOf(g, m);
    }
}
