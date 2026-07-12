package com.quantfinlib.rates;

/**
 * VANILLA interest-rate swap pricing off the {@link YieldCurve} — the
 * missing middle between the curve (which the bootstrap builds FROM par
 * swaps) and {@link RatesOptions} (which prices options ON forward
 * swaps): the PV, par rate and DV01 of an actual swap position.
 *
 * <p>Single-curve identities (annual fixed leg, matching the bootstrap's
 * convention):</p>
 *
 * <pre>
 *   annuity   = sum DF(t_i)                    i = 1..T (annual, tau = 1)
 *   parRate   = (1 - DF(T)) / annuity          spot-starting
 *   payerPv   = annuity * (parRate - K)        pay fixed K, receive float
 *   receiverPv = -payerPv
 * </pre>
 *
 * <p>The float leg needs no forecasting in a single-curve world: it is
 * worth par at inception, i.e. {@code 1 - DF(T)} per unit notional —
 * which is exactly why the par rate has that closed form. DV01 is the
 * bump-and-reprice sensitivity to a parallel 1bp shift of the zero
 * curve: for a fresh par swap it approximately equals
 * {@code annuity * 1bp * notional}, the number a rates desk quotes risk
 * in (and the tests pin that approximation). A swap struck at the par
 * rate must PV to zero — an identity, tested at 1e-12.</p>
 *
 * <p>Stated simplifications: single curve (no OIS/projection split),
 * annual fixed leg, spot start. Research lane, deterministic.</p>
 */
public final class SwapPricer {

    private SwapPricer() {
    }

    /** PV of the annual fixed-leg annuity, per unit notional. */
    public static double annuity(YieldCurve curve, int tenorYears) {
        requireTenor(tenorYears);
        double a = 0;
        for (int i = 1; i <= tenorYears; i++) {
            a += curve.discountFactor(i);
        }
        return a;
    }

    /** The spot-starting par swap rate for {@code tenorYears}. */
    public static double parRate(YieldCurve curve, int tenorYears) {
        return (1 - curve.discountFactor(tenorYears)) / annuity(curve, tenorYears);
    }

    /**
     * PV per unit notional of a PAYER swap (pay fixed {@code fixedRate},
     * receive float). Negate for the receiver.
     */
    public static double payerPv(YieldCurve curve, int tenorYears, double fixedRate) {
        if (!Double.isFinite(fixedRate)) {
            throw new IllegalArgumentException("fixedRate must be finite");
        }
        return annuity(curve, tenorYears) * (parRate(curve, tenorYears) - fixedRate);
    }

    /**
     * DV01 per unit notional: the payer swap's PV change for a +1bp
     * parallel shift of the zero curve (positive — rates up helps the
     * fixed payer).
     */
    public static double dv01(YieldCurve curve, int tenorYears, double fixedRate) {
        double base = payerPv(curve, tenorYears, fixedRate);
        double bumped = payerPv(parallelBump(curve, 1e-4), tenorYears, fixedRate);
        return bumped - base;
    }

    /** A copy of the curve with every pillar zero rate shifted by {@code bump}. */
    static YieldCurve parallelBump(YieldCurve curve, double bump) {
        var tenors = curve.tenors();
        double[] t = new double[tenors.size()];
        double[] z = new double[tenors.size()];
        int i = 0;
        for (double tenor : tenors) {
            t[i] = tenor;
            z[i] = curve.zeroRate(tenor) + bump;
            i++;
        }
        return YieldCurve.ofZeroRates(t, z);
    }

    private static void requireTenor(int tenorYears) {
        if (tenorYears < 1) {
            throw new IllegalArgumentException("tenorYears must be >= 1, got " + tenorYears);
        }
    }
}
