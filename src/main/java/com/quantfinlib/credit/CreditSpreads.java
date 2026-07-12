package com.quantfinlib.credit;

import com.quantfinlib.rates.YieldCurve;

/**
 * BOND credit-spread measures — the translation layer between a bond's
 * PRICE and how much of it is credit.
 *
 * <p>The <b>Z-SPREAD</b> is the single constant shift z added to every
 * point of the risk-free zero curve that makes the bond's discounted
 * cash flows equal its dirty price:</p>
 *
 * <pre>  price = sum cf_i * exp(-(z(t_i) + z) * t_i)</pre>
 *
 * <p>It is the honest successor to "yield spread over the 10y": a yield
 * spread compares one bond's YTM to one government point and mixes curve
 * shape into the number; the Z-spread strips the entire risk-free curve
 * out first, so what remains is compensation for credit and liquidity.
 * A desk triangulates it against the same name's CDS: {@code zSpread -
 * cdsParSpread} is the CDS-BOND BASIS, the classic relative-value trade
 * (negative basis: buy the bond, buy CDS protection, collect the
 * difference — a trade that famously blew through funding constraints
 * in 2008, which is why the basis is not free money).</p>
 *
 * <p>Solving is bisection on z in [-50%, +500%] with an explicit bracket
 * check: a price outside what that range can explain throws rather than
 * returning an endpoint (the house rule for every solver since the YTM
 * incident). Annual-fraction period grid {@code i/frequency}, cash flows
 * of a standard fixed-coupon bond. Research lane, deterministic.</p>
 */
public final class CreditSpreads {

    private CreditSpreads() {
    }

    /**
     * The Z-spread (continuously compounded, decimal) of a fixed-coupon
     * bond over {@code curve}.
     *
     * @param dirtyPrice      market dirty price per {@code face}
     * @param face            face value, &gt; 0
     * @param couponRate      annual coupon rate (decimal)
     * @param frequency       coupons per year, &ge; 1
     * @param yearsToMaturity whole periods assumed, &gt; 0
     */
    public static double zSpread(double dirtyPrice, double face, double couponRate,
                                 int frequency, double yearsToMaturity, YieldCurve curve) {
        if (!(dirtyPrice > 0) || dirtyPrice == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("dirtyPrice must be positive and finite");
        }
        if (!(face > 0) || face == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("face must be positive and finite");
        }
        if (!(couponRate >= 0) || couponRate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("couponRate must be >= 0 and finite");
        }
        if (frequency < 1) {
            throw new IllegalArgumentException("frequency must be >= 1, got " + frequency);
        }
        if (!(yearsToMaturity > 0) || yearsToMaturity == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("yearsToMaturity must be positive and finite");
        }
        double lo = -0.5, hi = 5.0;
        double pvLo = pv(face, couponRate, frequency, yearsToMaturity, curve, lo);
        double pvHi = pv(face, couponRate, frequency, yearsToMaturity, curve, hi);
        // PV is decreasing in z: pv(lo) is the maximum, pv(hi) the minimum.
        if (!(dirtyPrice <= pvLo) || !(dirtyPrice >= pvHi)) {
            throw new IllegalArgumentException("price " + dirtyPrice
                    + " has no Z-spread in [-50%, 500%] (attainable ["
                    + pvHi + ", " + pvLo + "])");
        }
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (pv(face, couponRate, frequency, yearsToMaturity, curve, mid) > dirtyPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** Bond PV under the curve shifted by a constant z (cc). */
    public static double priceWithZSpread(double face, double couponRate, int frequency,
                                          double yearsToMaturity, YieldCurve curve, double z) {
        if (!Double.isFinite(z)) {
            throw new IllegalArgumentException("z must be finite");
        }
        return pv(face, couponRate, frequency, yearsToMaturity, curve, z);
    }

    private static double pv(double face, double couponRate, int frequency,
                             double yearsToMaturity, YieldCurve curve, double z) {
        int n = (int) Math.round(yearsToMaturity * frequency);
        double coupon = face * couponRate / frequency;
        double pv = 0;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / frequency;
            double cf = coupon + (i == n ? face : 0);
            pv += cf * Math.exp(-(curve.zeroRate(t) + z) * t);
        }
        return pv;
    }
}
