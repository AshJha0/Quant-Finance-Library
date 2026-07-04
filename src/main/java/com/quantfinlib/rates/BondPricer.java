package com.quantfinlib.rates;

/**
 * Fixed-coupon bond analytics: price/yield conversion, Macaulay and modified
 * duration, convexity, and DV01. Yields are per-annum with {@code frequency}
 * compounding (the bond's coupon frequency); coupons are assumed on a regular
 * schedule with the last payment at maturity.
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

    private static int periods(int frequency, double yearsToMaturity) {
        if (frequency < 1 || yearsToMaturity <= 0) {
            throw new IllegalArgumentException("need positive frequency and maturity");
        }
        return (int) Math.round(yearsToMaturity * frequency);
    }
}
