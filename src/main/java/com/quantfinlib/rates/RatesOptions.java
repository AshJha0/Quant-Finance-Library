package com.quantfinlib.rates;

import com.quantfinlib.pricing.Black76;
import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * RATES VOLATILITY products priced off the curve — the bridge between
 * {@link YieldCurve} (where forwards and discount factors live) and
 * {@code pricing.Black76} (the market-standard lognormal quoter for
 * anything written on a forward rate).
 *
 * <ul>
 *   <li><b>Swaption</b> — an option on a forward-starting swap. The
 *       underlying is the forward swap rate
 *       {@code F = (DF(start) − DF(end)) / annuity}, the natural numeraire
 *       is the ANNUITY (the PV of the fixed leg's 1%-per-year), and the
 *       price is simply {@code annuity × Black76(F, K, vol, expiry)} —
 *       discounting lives entirely in the annuity, which is why the
 *       Black-76 leg is called with rate 0. A PAYER swaption (right to pay
 *       fixed) is a CALL on the swap rate: it pays when rates rise.</li>
 *   <li><b>Cap / floor</b> — a strip of independent options (caplets), one
 *       per accrual period, each a Black-76 call (put) on that period's
 *       SIMPLE forward rate {@code f_i = (DF(t_{i-1})/DF(t_i) − 1)/τ},
 *       fixing at the period START and paying at its end. The first
 *       period's rate is already fixed today, so its caplet is pure
 *       intrinsic — included, as the market convention includes it unless
 *       the trade says otherwise.</li>
 * </ul>
 *
 * <p>Two identities keep this honest (both pinned by tests): payer −
 * receiver = annuity·(F − K) (swaption put-call parity), and cap − floor =
 * the PV of the matching vanilla swap. Annual fixed legs and accrual
 * periods (τ = 1), matching {@link YieldCurve#bootstrapAnnualParSwaps};
 * one flat lognormal vol per product (no smile — {@code pricing.SabrModel}
 * is where the smile lives). Research lane, deterministic.</p>
 */
public final class RatesOptions {

    private RatesOptions() {
    }

    /** PV of 1 per year paid annually over (startYears, startYears+tenorYears]. */
    public static double annuity(YieldCurve curve, int startYears, int tenorYears) {
        validate(startYears, tenorYears);
        double a = 0;
        for (int i = 1; i <= tenorYears; i++) {
            a += curve.discountFactor(startYears + i);
        }
        return a;
    }

    /** Forward par swap rate for a swap starting at {@code startYears}. */
    public static double forwardSwapRate(YieldCurve curve, int startYears, int tenorYears) {
        validate(startYears, tenorYears);
        double dfStart = curve.discountFactor(startYears);
        double dfEnd = curve.discountFactor(startYears + tenorYears);
        return (dfStart - dfEnd) / annuity(curve, startYears, tenorYears);
    }

    /**
     * Black-76 swaption price per unit notional.
     *
     * @param payer true = payer swaption (call on the swap rate)
     * @param vol   flat lognormal vol of the forward swap rate
     */
    public static double swaption(YieldCurve curve, int startYears, int tenorYears,
                                  double strike, double vol, boolean payer) {
        requirePositiveVolAndStrike(strike, vol);
        double fsr = forwardSwapRate(curve, startYears, tenorYears);
        double a = annuity(curve, startYears, tenorYears);
        return a * Black76.price(payer ? OptionType.CALL : OptionType.PUT,
                fsr, strike, 0, vol, startYears);
    }

    /** Cap: strip of annual Black-76 caplets to {@code maturityYears}. */
    public static double cap(YieldCurve curve, int maturityYears, double strike, double vol) {
        return capletStrip(curve, maturityYears, strike, vol, OptionType.CALL);
    }

    /** Floor: the matching strip of floorlets. */
    public static double floor(YieldCurve curve, int maturityYears, double strike, double vol) {
        return capletStrip(curve, maturityYears, strike, vol, OptionType.PUT);
    }

    private static double capletStrip(YieldCurve curve, int maturityYears,
                                      double strike, double vol, OptionType type) {
        if (maturityYears < 1) {
            throw new IllegalArgumentException("maturityYears must be >= 1, got " + maturityYears);
        }
        requirePositiveVolAndStrike(strike, vol);
        double pv = 0;
        for (int i = 1; i <= maturityYears; i++) {
            double dfPay = curve.discountFactor(i);
            double simpleForward = curve.discountFactor(i - 1) / dfPay - 1;
            // Fixes at i-1 (the first period is already fixed: T=0 ->
            // Black76 returns discounted intrinsic, i.e. pure intrinsic
            // here since its own rate argument is 0), pays at i.
            pv += dfPay * Black76.price(type, simpleForward, strike, 0, vol, i - 1);
        }
        return pv;
    }

    private static void validate(int startYears, int tenorYears) {
        if (startYears < 0 || tenorYears < 1) {
            throw new IllegalArgumentException(
                    "need startYears >= 0 and tenorYears >= 1: " + startYears + "/" + tenorYears);
        }
    }

    private static void requirePositiveVolAndStrike(double strike, double vol) {
        if (!(strike > 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be positive and finite, got " + strike);
        }
        if (!(vol > 0) || vol == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("vol must be positive and finite, got " + vol);
        }
    }
}
