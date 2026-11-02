package com.quantfinlib.rates;

/**
 * Fixed-coupon bond analytics: price/yield conversion, Macaulay and modified
 * duration, convexity, and DV01. Yields are per-annum with {@code frequency}
 * compounding (the bond's coupon frequency); coupons are assumed on a regular
 * schedule with the last payment at maturity.
 *
 * <p>The risk numbers and what each is for: <b>Macaulay duration</b> is the
 * cash-flow-weighted average time to payment (years; an intuition number);
 * <b>modified duration</b> = Macaulay / (1 + y/f) is the first-order price
 * sensitivity, {@code dP/P ≈ -modDur · dy}; <b>DV01</b> is the same
 * derivative in money terms per 1bp, the number the desk actually hedges,
 * because DV01s add across positions while durations must be
 * value-weighted. <b>Convexity</b> is the second-order term that makes the
 * duration hedge wrong for large moves. It is always in the long
 * bondholder's favor (price rises more than duration predicts when yields
 * fall, drops less when they rise), which is why long-convexity positions
 * cost carry.</p>
 *
 * <p>The {@code yearsToMaturity} functions work in whole coupon periods
 * (no accrued interest, no settlement inside a period): the textbook bond.
 * The date-based functions ({@code dirtyPrice}, {@code cleanPrice},
 * {@code accruedInterest}, {@code yieldFromCleanPrice}) use the street
 * convention with real day counts, including ACT/ACT ICMA. Pricing off a full curve (each cash flow
 * at its own zero rate) is {@link YieldCurve}'s job; hedging a position
 * against non-parallel curve moves is {@link KeyRateDurations}'.</p>
 */
public final class BondPricer {

    private BondPricer() {
    }

    /** Dirty price per {@code face} from a yield (regular schedule, whole periods). */
    public static double priceFromYield(double face, double couponRate, int frequency,
                                        double yearsToMaturity, double yield) {
        int n = periods(frequency, yearsToMaturity);
        double coupon = face * couponRate / frequency;
        double y = yield / frequency;
        double price = 0;
        for (int i = 1; i <= n; i++) {
            price += coupon / Math.pow(1 + y, i);
        }
        return price + face / Math.pow(1 + y, n);
    }

    /** Price by discounting each cash flow on a zero curve. */
    public static double priceFromCurve(double face, double couponRate, int frequency,
                                        double yearsToMaturity, YieldCurve curve) {
        int n = periods(frequency, yearsToMaturity);
        double coupon = face * couponRate / frequency;
        double price = 0;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / frequency;
            price += coupon * curve.discountFactor(t);
        }
        return price + face * curve.discountFactor((double) n / frequency);
    }

    /** Yield to maturity by bisection (price must be positive). */
    public static double yieldToMaturity(double price, double face, double couponRate,
                                         int frequency, double yearsToMaturity) {
        double lo = -0.9, hi = 10;
        // Bracket check: a price outside [PV(10), PV(-0.9)] has no yield in
        // the search range, and bisection would silently converge to an
        // endpoint and hand back -89.99% or 1000% as if it were a market
        // yield. Refuse loudly instead.
        double maxPrice = priceFromYield(face, couponRate, frequency, yearsToMaturity, lo);
        double minPrice = priceFromYield(face, couponRate, frequency, yearsToMaturity, hi);
        if (!(price >= minPrice) || !(price <= maxPrice)) {
            throw new IllegalArgumentException("price " + price + " has no yield in [-90%, 1000%]"
                    + " (attainable price range [" + minPrice + ", " + maxPrice + "])");
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (priceFromYield(face, couponRate, frequency, yearsToMaturity, mid) > price) {
                lo = mid;   // price too high -> yield higher
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Macaulay duration in years: PV-weighted average time to cash flow. */
    public static double macaulayDuration(double face, double couponRate, int frequency,
                                          double yearsToMaturity, double yield) {
        int n = periods(frequency, yearsToMaturity);
        double coupon = face * couponRate / frequency;
        double y = yield / frequency;
        double weighted = 0, price = 0;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / frequency;
            double cf = coupon + (i == n ? face : 0);
            double pv = cf / Math.pow(1 + y, i);
            weighted += t * pv;
            price += pv;
        }
        return weighted / price;
    }

    /** Modified duration: price sensitivity per unit yield change. */
    public static double modifiedDuration(double face, double couponRate, int frequency,
                                          double yearsToMaturity, double yield) {
        return macaulayDuration(face, couponRate, frequency, yearsToMaturity, yield)
                / (1 + yield / frequency);
    }

    /** Convexity (numeric second derivative of price w.r.t. yield, normalized by price). */
    public static double convexity(double face, double couponRate, int frequency,
                                   double yearsToMaturity, double yield) {
        double h = 1e-4;
        double p0 = priceFromYield(face, couponRate, frequency, yearsToMaturity, yield);
        double up = priceFromYield(face, couponRate, frequency, yearsToMaturity, yield + h);
        double dn = priceFromYield(face, couponRate, frequency, yearsToMaturity, yield - h);
        return (up + dn - 2 * p0) / (p0 * h * h);
    }

    /** Price change for a one-basis-point yield move (positive number). */
    public static double dv01(double face, double couponRate, int frequency,
                              double yearsToMaturity, double yield) {
        double price = priceFromYield(face, couponRate, frequency, yearsToMaturity, yield);
        return modifiedDuration(face, couponRate, frequency, yearsToMaturity, yield) * price * 1e-4;
    }

    /**
     * Analytic convexity on the same whole-period schedule as
     * {@link #convexity}: {@code (1/P) d^2P/dy^2} with
     *
     * <pre>  d^2P/dy^2 = sum_i CF_i i (i + 1) / (f^2 (1 + y/f)^(i+2))</pre>
     *
     * <p>The existing {@link #convexity} is a central difference with step
     * 1e-4 and keeps its results; this is the closed form it approximates
     * (they agree to about 1e-7 relative, pinned in the tests).</p>
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity (rounded to whole periods, as in {@link #priceFromYield})
     * @param yield           yield, compounded {@code frequency} times a year
     * @return convexity in years squared
     */
    public static double convexityAnalytic(double face, double couponRate, int frequency,
                                           double yearsToMaturity, double yield) {
        return exactCashFlowMoments(face, couponRate, frequency,
                periods(frequency, yearsToMaturity), 1.0, yield)[2];
    }

    /**
     * Whether {@code yearsToMaturity * frequency} is a whole number of coupon
     * periods (to 1e-9). When it is not, the whole-period functions
     * ({@link #priceFromYield}, {@link #macaulayDuration}, {@link #convexity},
     * {@link #dv01}, {@link #yieldToMaturity}, {@link #priceFromCurve}) round
     * to the nearest period: a 2.3-year semi-annual bond is priced as 2.5
     * years. That rounding is kept for compatibility (release policy: no
     * behaviour changes in 3.x) but its use with non-integral maturities is
     * deprecated since 3.1.0; use the {@code ...Exact} functions instead.
     *
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @return true when the maturity is a whole number of periods
     */
    public static boolean isWholePeriods(int frequency, double yearsToMaturity) {
        requirePositive(frequency, yearsToMaturity);
        double x = yearsToMaturity * frequency;
        return Math.abs(x - Math.rint(x)) <= 1e-9;
    }

    /**
     * Dirty price from a yield for any (also non-integral) time to maturity,
     * by the street convention: coupons sit on the regular quasi-coupon grid
     * counted back from maturity, the first one {@code w} periods away, and a
     * full coupon is paid on it.
     *
     * <p>With {@code x = yearsToMaturity * f}, {@code n = ceil(x)} coupons
     * remain and {@code w = x - (n - 1)} in (0, 1]:</p>
     * <pre>  P = sum_{k=1..n} C / (1 + y/f)^(k - 1 + w) + face / (1 + y/f)^(n - 1 + w),   C = face c / f</pre>
     * <p>When {@code x} is whole, {@code w = 1} and this is exactly
     * {@link #priceFromYield}. Worked example: 2.3 years semi-annual gives
     * {@code x = 4.6}, five coupons, the first 0.6 periods (0.3 years) away;
     * the clean price is this minus {@link #accruedInterestExact}.</p>
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity, any positive value
     * @param yield           yield, compounded {@code frequency} times a year
     * @return dirty price
     */
    public static double priceFromYieldExact(double face, double couponRate, int frequency,
                                             double yearsToMaturity, double yield) {
        double[] g = grid(frequency, yearsToMaturity);
        return exactCashFlowMoments(face, couponRate, frequency, (int) g[0], g[1], yield)[0];
    }

    /**
     * Accrued interest on the quasi-coupon grid of {@link #priceFromYieldExact}:
     * {@code C (1 - w)}; zero when the maturity is a whole number of periods.
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @return accrued interest
     */
    public static double accruedInterestExact(double face, double couponRate, int frequency,
                                              double yearsToMaturity) {
        double w = grid(frequency, yearsToMaturity)[1];
        return face * couponRate / frequency * (1 - w);
    }

    /**
     * Macaulay duration in years on the exact grid of {@link #priceFromYieldExact}.
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @param yield           yield, compounded {@code frequency} times a year
     * @return Macaulay duration
     */
    public static double macaulayDurationExact(double face, double couponRate, int frequency,
                                               double yearsToMaturity, double yield) {
        double[] g = grid(frequency, yearsToMaturity);
        return exactCashFlowMoments(face, couponRate, frequency, (int) g[0], g[1], yield)[1];
    }

    /**
     * Modified duration on the exact grid: Macaulay / (1 + y/f).
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @param yield           yield, compounded {@code frequency} times a year
     * @return modified duration
     */
    public static double modifiedDurationExact(double face, double couponRate, int frequency,
                                               double yearsToMaturity, double yield) {
        return macaulayDurationExact(face, couponRate, frequency, yearsToMaturity, yield)
                / (1 + yield / frequency);
    }

    /**
     * Analytic convexity on the exact grid,
     * {@code (1/P) sum_k CF_k e_k (e_k + 1) / (f^2 (1 + y/f)^(e_k + 2))},
     * {@code e_k = k - 1 + w}.
     *
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @param yield           yield, compounded {@code frequency} times a year
     * @return convexity in years squared
     */
    public static double convexityExact(double face, double couponRate, int frequency,
                                        double yearsToMaturity, double yield) {
        double[] g = grid(frequency, yearsToMaturity);
        return exactCashFlowMoments(face, couponRate, frequency, (int) g[0], g[1], yield)[2];
    }

    /**
     * Yield from a dirty price on the exact grid, by bisection on [-90%, 1000%].
     *
     * @param dirtyPrice      dirty price
     * @param face            face value
     * @param couponRate      annual coupon rate
     * @param frequency       coupons per year
     * @param yearsToMaturity years to maturity
     * @return the yield
     */
    public static double yieldToMaturityExact(double dirtyPrice, double face, double couponRate,
                                              int frequency, double yearsToMaturity) {
        double lo = -0.9;
        double hi = 10;
        double maxPrice = priceFromYieldExact(face, couponRate, frequency, yearsToMaturity, lo);
        double minPrice = priceFromYieldExact(face, couponRate, frequency, yearsToMaturity, hi);
        if (!(dirtyPrice >= minPrice) || !(dirtyPrice <= maxPrice)) {
            throw new IllegalArgumentException("price " + dirtyPrice + " has no yield in [-90%, 1000%]");
        }
        for (int i = 0; i < 200 && hi - lo > 1e-16; i++) {
            double mid = 0.5 * (lo + hi);
            if (priceFromYieldExact(face, couponRate, frequency, yearsToMaturity, mid) > dirtyPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** {n, w} of the quasi-coupon grid. */
    private static double[] grid(int frequency, double yearsToMaturity) {
        requirePositive(frequency, yearsToMaturity);
        double x = yearsToMaturity * frequency;
        if (Math.abs(x - Math.rint(x)) <= 1e-9) {
            return new double[] {Math.rint(x), 1.0};
        }
        double n = Math.ceil(x);
        return new double[] {n, x - (n - 1)};
    }

    /** {price, Macaulay duration, convexity} for n flows at period exponents k - 1 + w. */
    private static double[] exactCashFlowMoments(double face, double couponRate, int frequency, int n,
                                                 double w, double yield) {
        double coupon = face * couponRate / frequency;
        double v = 1 / (1 + yield / frequency);
        double price = 0;
        double weighted = 0;
        double second = 0;
        for (int k = 1; k <= n; k++) {
            double e = k - 1 + w;
            double cf = coupon + (k == n ? face : 0);
            double pv = cf * Math.pow(v, e);
            price += pv;
            weighted += e / frequency * pv;
            second += pv * e * (e + 1) * v * v / ((double) frequency * frequency);
        }
        return new double[] {price, weighted / price, second / price};
    }

    private static void requirePositive(int frequency, double yearsToMaturity) {
        if (frequency < 1 || !(yearsToMaturity > 0) || !Double.isFinite(yearsToMaturity)) {
            throw new IllegalArgumentException("need positive frequency and maturity");
        }
    }

    private static int periods(int frequency, double yearsToMaturity) {
        if (frequency < 1 || yearsToMaturity <= 0) {
            throw new IllegalArgumentException("need positive frequency and maturity");
        }
        return (int) Math.round(yearsToMaturity * frequency);
    }

    // ------------------------------------------------------------------
    // Date-based pricing: street convention
    // ------------------------------------------------------------------

    /**
     * Where settlement sits in the coupon schedule: previous and next
     * (quasi-)coupon dates, the number of coupons still to be paid, and the
     * elapsed fraction of the current period under the bond's day count.
     */
    private record Position(java.time.LocalDate prev, java.time.LocalDate next, int remaining,
                            int months, boolean endOfMonth) {
    }

    private static Position locate(java.time.LocalDate settlement, java.time.LocalDate maturity,
                                   int frequency, boolean endOfMonth) {
        if (frequency < 1 || 12 % frequency != 0) {
            throw new IllegalArgumentException("frequency must divide 12");
        }
        if (!maturity.isAfter(settlement)) {
            throw new IllegalArgumentException("maturity must be after settlement");
        }
        int months = 12 / frequency;
        int k = 0;
        java.time.LocalDate next = maturity;
        while (true) {
            java.time.LocalDate before =
                    BusinessCalendar.shiftMonths(maturity, -(long) months * (k + 1), endOfMonth);
            if (!before.isAfter(settlement)) {
                return new Position(before, next, k + 1, months, endOfMonth);
            }
            next = before;
            k++;
        }
    }

    /**
     * Coupon paid for the regular period [start, end). Bond conventions
     * (30/360 family, ACT/ACT ICMA) pay exactly {@code face·c/f}; the
     * money-market bases (ACT/360, ACT/365, ACT/ACT ISDA) pay
     * {@code face·c·yearFraction}.
     */
    private static double periodCoupon(double face, double couponRate, int frequency,
                                       java.time.LocalDate start, java.time.LocalDate end,
                                       DayCount dayCount) {
        return switch (dayCount) {
            case THIRTY_360, THIRTY_E_360, THIRTY_E_360_ISDA, ACT_ACT_ICMA ->
                    face * couponRate / frequency;
            default -> face * couponRate * dayCount.yearFraction(start, end);
        };
    }

    /** Elapsed fraction of the period [prev, next) at settlement. */
    private static double elapsed(Position p, java.time.LocalDate settlement, int frequency,
                                  DayCount dayCount, java.time.LocalDate maturity) {
        return switch (dayCount) {
            case ACT_ACT_ICMA -> DayCount.actActIcma(p.prev(), settlement, p.prev(), p.next(),
                    frequency) * frequency;
            case THIRTY_360, THIRTY_E_360, THIRTY_E_360_ISDA ->
                    dayCount.yearFraction(p.prev(), settlement, maturity) * frequency;
            default -> dayCount.yearFraction(p.prev(), settlement)
                    / dayCount.yearFraction(p.prev(), p.next());
        };
    }

    /**
     * Dirty price per {@code face} from a yield, street convention (the
     * formula behind the SIFMA/Bloomberg "street" yield and Excel's price):
     *
     * <pre>  P = Σ_{k=0}^{N−1} C_k / (1 + y/f)^{k + w} + face / (1 + y/f)^{N − 1 + w}</pre>
     *
     * <p>with w = 1 − (elapsed fraction of the current coupon period) and N
     * the number of coupons left. Coupons are {@code face·c/f} for the bond
     * conventions THIRTY_360, THIRTY_E_360, THIRTY_E_360_ISDA and ACT_ACT_ICMA
     * (US Treasuries and most government bonds use ACT/ACT ICMA, where the
     * elapsed fraction is actual days over actual days in the period).
     * Discounting uses the unadjusted coupon dates, as the street
     * convention does: a coupon delayed by a weekend is not discounted for
     * the extra days.</p>
     *
     * <p>The schedule is regular, generated backward from maturity (with the
     * end-of-month rule when {@code endOfMonth}); odd first coupons are not
     * modelled, so settlement must be after the first coupon's regular
     * period start.</p>
     */
    public static double dirtyPrice(double face, double couponRate, int frequency,
                                    java.time.LocalDate settlement, java.time.LocalDate maturity,
                                    double yield, DayCount dayCount, boolean endOfMonth) {
        Position p = locate(settlement, maturity, frequency, endOfMonth);
        double w = 1 - elapsed(p, settlement, frequency, dayCount, maturity);
        double v = 1 / (1 + yield / frequency);
        double price = 0;
        java.time.LocalDate start = p.prev();
        for (int k = 0; k < p.remaining(); k++) {
            java.time.LocalDate end = BusinessCalendar.shiftMonths(maturity,
                    -(long) p.months() * (p.remaining() - 1 - k), endOfMonth);
            double cf = periodCoupon(face, couponRate, frequency, start, end, dayCount);
            if (k == p.remaining() - 1) {
                cf += face;
            }
            price += cf * Math.pow(v, k + w);
            start = end;
        }
        return price;
    }

    /** {@link #dirtyPrice(double, double, int, java.time.LocalDate, java.time.LocalDate, double, DayCount, boolean)} without the EOM rule. */
    public static double dirtyPrice(double face, double couponRate, int frequency,
                                    java.time.LocalDate settlement, java.time.LocalDate maturity,
                                    double yield, DayCount dayCount) {
        return dirtyPrice(face, couponRate, frequency, settlement, maturity, yield, dayCount, false);
    }

    /**
     * Accrued interest at settlement: the current period's coupon times the
     * elapsed fraction (ACT/ACT ICMA: actual days over actual days in the
     * period; 30/360 family: 30/360 days over 360/f).
     */
    public static double accruedInterest(double face, double couponRate, int frequency,
                                         java.time.LocalDate settlement, java.time.LocalDate maturity,
                                         DayCount dayCount, boolean endOfMonth) {
        Position p = locate(settlement, maturity, frequency, endOfMonth);
        return periodCoupon(face, couponRate, frequency, p.prev(), p.next(), dayCount)
                * elapsed(p, settlement, frequency, dayCount, maturity);
    }

    public static double accruedInterest(double face, double couponRate, int frequency,
                                         java.time.LocalDate settlement, java.time.LocalDate maturity,
                                         DayCount dayCount) {
        return accruedInterest(face, couponRate, frequency, settlement, maturity, dayCount, false);
    }

    /** Clean price = dirty price minus accrued interest. */
    public static double cleanPrice(double face, double couponRate, int frequency,
                                    java.time.LocalDate settlement, java.time.LocalDate maturity,
                                    double yield, DayCount dayCount, boolean endOfMonth) {
        return dirtyPrice(face, couponRate, frequency, settlement, maturity, yield, dayCount,
                endOfMonth)
                - accruedInterest(face, couponRate, frequency, settlement, maturity, dayCount,
                endOfMonth);
    }

    public static double cleanPrice(double face, double couponRate, int frequency,
                                    java.time.LocalDate settlement, java.time.LocalDate maturity,
                                    double yield, DayCount dayCount) {
        return cleanPrice(face, couponRate, frequency, settlement, maturity, yield, dayCount, false);
    }

    /**
     * Street-convention yield from a clean price (inverse of
     * {@link #cleanPrice(double, double, int, java.time.LocalDate, java.time.LocalDate, double, DayCount, boolean)}),
     * by bisection on [−90%, 1000%] to full double precision. Throws when the
     * price has no yield in that range.
     */
    public static double yieldFromCleanPrice(double cleanPrice, double face, double couponRate,
                                             int frequency, java.time.LocalDate settlement,
                                             java.time.LocalDate maturity, DayCount dayCount,
                                             boolean endOfMonth) {
        double lo = -0.9, hi = 10;
        double pLo = cleanPrice(face, couponRate, frequency, settlement, maturity, lo, dayCount, endOfMonth);
        double pHi = cleanPrice(face, couponRate, frequency, settlement, maturity, hi, dayCount, endOfMonth);
        if (!(cleanPrice <= pLo) || !(cleanPrice >= pHi)) {
            throw new IllegalArgumentException("price " + cleanPrice + " has no yield in [-90%, 1000%]");
        }
        for (int i = 0; i < 200 && hi - lo > 1e-16; i++) {
            double mid = 0.5 * (lo + hi);
            if (cleanPrice(face, couponRate, frequency, settlement, maturity, mid, dayCount, endOfMonth)
                    > cleanPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Settlement date from a trade date and settlement lag (e.g. T+2). */
    public static java.time.LocalDate settlementDate(java.time.LocalDate tradeDate, int lagDays,
                                                     BusinessCalendar calendar) {
        return calendar.addBusinessDays(tradeDate, lagDays);
    }
}
