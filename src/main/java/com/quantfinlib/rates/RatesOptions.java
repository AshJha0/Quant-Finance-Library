package com.quantfinlib.rates;

import com.quantfinlib.pricing.Bachelier;
import com.quantfinlib.pricing.Black76;
import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Rates volatility products priced off the curve: the bridge between
 * {@link YieldCurve} (where forwards and discount factors live) and the
 * option models on a forward rate, {@code pricing.Black76} (lognormal and
 * shifted lognormal) and {@code pricing.Bachelier} (normal).
 *
 * <ul>
 *   <li><b>Swaption</b> — an option on a forward-starting swap. The
 *       underlying is the forward swap rate
 *       {@code F = (DF(start) − DF(end)) / annuity}, the natural numeraire
 *       is the annuity (the PV of the fixed leg's 1%-per-year), and the
 *       price is simply {@code annuity × Black76(F, K, vol, expiry)} —
 *       discounting lives entirely in the annuity, which is why the
 *       Black-76 leg is called with rate 0. A payer swaption (right to pay
 *       fixed) is a call on the swap rate: it pays when rates rise.</li>
 *   <li><b>Cap / floor</b> — a strip of independent options (caplets), one
 *       per accrual period, each a Black-76 call (put) on that period's
 *       simple forward rate {@code f_i = (DF(t_{i-1})/DF(t_i) − 1)/τ},
 *       fixing at the period start and paying at its end. The first
 *       period's rate is already fixed today, so its caplet is pure
 *       intrinsic — included, as the market convention includes it unless
 *       the trade says otherwise.</li>
 * </ul>
 *
 * <p>Two identities hold for every vol model (both pinned by tests): payer −
 * receiver = annuity·(F − K) (swaption put-call parity), and cap − floor =
 * the PV of the matching vanilla swap.</p>
 *
 * <p>Vol models ({@link VolModel}). Since rates went negative in 2014-2015
 * the market quotes swaption and cap vols as normal (Bachelier) vols, in
 * absolute rate units, or as shifted-lognormal vols (Black-76 on F + s and
 * K + s, EUR shift typically 1-3%). Plain lognormal Black-76 needs F, K &gt; 0
 * and is kept for positive-rate markets and older data. One flat vol per
 * product; {@code pricing.SabrModel} is where the smile lives.</p>
 *
 * <p>Schedules: the integer-year functions use annual periods (τ = 1);
 * the overloads with {@code paymentsPerYear} use τ = 1/f on a curve in
 * year fractions, which is exact for the curve's own time axis. Day-count
 * accrual on real dates is not modelled here.</p>
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

    /**
     * Which vol a quote is: lognormal (Black-76), shifted lognormal
     * (Black-76 on forward + shift and strike + shift) or normal (Bachelier).
     */
    public enum VolType {
        LOGNORMAL, SHIFTED_LOGNORMAL, NORMAL
    }

    /** A vol type together with its displacement (0 unless shifted lognormal). */
    public record VolModel(VolType type, double shift) {
        public VolModel {
            java.util.Objects.requireNonNull(type, "type");
            if (!Double.isFinite(shift) || (type != VolType.SHIFTED_LOGNORMAL && shift != 0)) {
                throw new IllegalArgumentException("shift is only for SHIFTED_LOGNORMAL, got " + shift);
            }
        }

        public static VolModel lognormal() {
            return new VolModel(VolType.LOGNORMAL, 0);
        }

        public static VolModel shiftedLognormal(double shift) {
            return new VolModel(VolType.SHIFTED_LOGNORMAL, shift);
        }

        public static VolModel normal() {
            return new VolModel(VolType.NORMAL, 0);
        }

        /** Undiscounted option value on a forward under this model. */
        public double forwardValue(OptionType type, double forward, double strike, double vol,
                                   double expiry) {
            if (!(vol >= 0) || vol == Double.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("vol must be >= 0 and finite, got " + vol);
            }
            return switch (this.type) {
                case NORMAL -> Bachelier.price(type, forward, strike, 0, vol, expiry);
                case LOGNORMAL, SHIFTED_LOGNORMAL -> {
                    double f = forward + shift;
                    double k = strike + shift;
                    if (!(f > 0) || !(k > 0)) {
                        throw new IllegalArgumentException("lognormal model needs forward + shift > 0"
                                + " and strike + shift > 0, got F=" + forward + " K=" + strike
                                + " shift=" + shift);
                    }
                    yield Black76.price(type, f, k, 0, vol, expiry);
                }
            };
        }

        /** Implied vol of this model from an undiscounted value (NaN if unattainable). */
        public double impliedVol(OptionType type, double forwardValue, double forward,
                                 double strike, double expiry) {
            return switch (this.type) {
                case NORMAL -> Bachelier.impliedVol(type, forwardValue, forward, strike, 0, expiry);
                case LOGNORMAL, SHIFTED_LOGNORMAL ->
                        Black76.impliedVol(type, forwardValue, forward + shift, strike + shift, 0, expiry);
            };
        }
    }

    /**
     * PV of 1 per year paid {@code paymentsPerYear} times a year (accrual
     * τ = 1/f) over (start, start + tenor].
     */
    public static double annuity(YieldCurve curve, double startYears, double tenorYears,
                                 int paymentsPerYear) {
        int n = periods(startYears, tenorYears, paymentsPerYear);
        double tau = 1.0 / paymentsPerYear;
        double a = 0;
        for (int i = 1; i <= n; i++) {
            a += tau * curve.discountFactor(startYears + i * tau);
        }
        return a;
    }

    /** Forward par swap rate with fixed-leg frequency {@code paymentsPerYear}. */
    public static double forwardSwapRate(YieldCurve curve, double startYears, double tenorYears,
                                         int paymentsPerYear) {
        double a = annuity(curve, startYears, tenorYears, paymentsPerYear);
        return (curve.discountFactor(startYears) - curve.discountFactor(startYears + tenorYears)) / a;
    }

    /**
     * Swaption price per unit notional under any {@link VolModel}:
     * annuity × model value on the forward swap rate, expiry = start. Normal
     * and shifted models accept zero and negative strikes and forwards.
     */
    public static double swaption(YieldCurve curve, double startYears, double tenorYears,
                                  int paymentsPerYear, double strike, double vol, boolean payer,
                                  VolModel model) {
        if (!Double.isFinite(strike)) {
            throw new IllegalArgumentException("strike must be finite, got " + strike);
        }
        double fsr = forwardSwapRate(curve, startYears, tenorYears, paymentsPerYear);
        double a = annuity(curve, startYears, tenorYears, paymentsPerYear);
        return a * model.forwardValue(payer ? OptionType.CALL : OptionType.PUT, fsr, strike, vol,
                startYears);
    }

    /**
     * Implied vol (of the given model) of a swaption price per unit notional.
     */
    public static double swaptionImpliedVol(YieldCurve curve, double startYears, double tenorYears,
                                            int paymentsPerYear, double strike, double price,
                                            boolean payer, VolModel model) {
        double fsr = forwardSwapRate(curve, startYears, tenorYears, paymentsPerYear);
        double a = annuity(curve, startYears, tenorYears, paymentsPerYear);
        return model.impliedVol(payer ? OptionType.CALL : OptionType.PUT, price / a, fsr, strike,
                startYears);
    }

    /**
     * Cap (or floor, {@code cap = false}) with accrual τ = 1/f: caplet i
     * fixes at (i−1)τ, pays τ·max(L_i − K, 0) at iτ, L_i the simple forward.
     * The first caplet is already fixed and contributes intrinsic value.
     */
    public static double capFloor(YieldCurve curve, double maturityYears, int paymentsPerYear,
                                  double strike, double vol, boolean cap, VolModel model) {
        int n = periods(0, maturityYears, paymentsPerYear);
        if (!Double.isFinite(strike)) {
            throw new IllegalArgumentException("strike must be finite, got " + strike);
        }
        double tau = 1.0 / paymentsPerYear;
        double pv = 0;
        for (int i = 1; i <= n; i++) {
            double dfPay = curve.discountFactor(i * tau);
            double fwd = (curve.discountFactor((i - 1) * tau) / dfPay - 1) / tau;
            pv += tau * dfPay * model.forwardValue(cap ? OptionType.CALL : OptionType.PUT,
                    fwd, strike, vol, (i - 1) * tau);
        }
        return pv;
    }

    private static int periods(double startYears, double tenorYears, int paymentsPerYear) {
        if (paymentsPerYear < 1 || 12 % paymentsPerYear != 0) {
            throw new IllegalArgumentException("paymentsPerYear must divide 12");
        }
        if (!(startYears >= 0) || !(tenorYears > 0) || !Double.isFinite(startYears + tenorYears)) {
            throw new IllegalArgumentException("need start >= 0 and tenor > 0");
        }
        double n = tenorYears * paymentsPerYear;
        long rounded = Math.round(n);
        if (Math.abs(n - rounded) > 1e-9 || rounded < 1) {
            throw new IllegalArgumentException("tenor must be a whole number of periods");
        }
        return (int) rounded;
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
