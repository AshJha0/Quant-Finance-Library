package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;
import com.quantfinlib.util.MathUtils;

/**
 * Single-barrier vanilla options — continuously monitored knock-in /
 * knock-out — for the <em>regular</em> barrier configurations, priced in
 * closed form by the reflection principle (Reiner–Rubinstein, as in Hull):
 *
 * <ul>
 *   <li><b>Down</b> barriers on <b>calls</b> with {@code H ≤ K} (barrier in
 *       the OTM region): down-and-in from the reflection formula,
 *       down-and-out from in–out parity {@code KO = vanilla − KI};</li>
 *   <li><b>Up</b> barriers on <b>puts</b> with {@code H ≥ K}, the mirror
 *       case.</li>
 * </ul>
 *
 * <p><b>Reverse</b> barriers (a barrier in the ITM region, e.g. an
 * up-and-out call) knock out exactly where the payoff is largest, need the
 * full eight-case decomposition, and their risk is dominated by the barrier
 * gamma — this class rejects them explicitly rather than pricing them
 * subtly wrong. Price those with {@code simulation.MonteCarloSimulator}
 * path pricing or a barrier-aware tree.</p>
 *
 * <p>No rebates. Conventions match {@link BlackScholes}: {@code carry} is
 * the continuous yield (foreign rate for FX, dividend yield for equities).
 * With {@code λ = (r − q + σ²/2)/σ²} and
 * {@code y = ln(H²/(S·K))/(σ√T) + λσ√T}, the down-and-in call is</p>
 *
 * <pre>  c_di = S·e^{−qT}(H/S)^{2λ}·N(y) − K·e^{−rT}(H/S)^{2λ−2}·N(y − σ√T)</pre>
 */
public final class BarrierOption {

    private BarrierOption() {
    }

    /** Down-and-in call, {@code H ≤ min(S, K)}: alive only after the barrier trades. */
    public static double downAndInCall(double spot, double strike, double barrier,
                                       double rate, double carry, double vol, double timeYears) {
        validateDownCall(spot, strike, barrier);
        if (timeYears <= 0) {
            return 0; // never touched, expires worthless as a knock-in
        }
        return reflectionIn(OptionType.CALL, spot, strike, barrier, rate, carry, vol, timeYears);
    }

    /** Down-and-out call, {@code H ≤ min(S, K)}: dies if the barrier trades. */
    public static double downAndOutCall(double spot, double strike, double barrier,
                                        double rate, double carry, double vol, double timeYears) {
        validateDownCall(spot, strike, barrier);
        // In–out parity: holding both KI and KO replicates the vanilla.
        return BlackScholes.price(OptionType.CALL, spot, strike, rate, carry, vol, timeYears)
                - downAndInCall(spot, strike, barrier, rate, carry, vol, timeYears);
    }

    /** Up-and-in put, {@code H ≥ max(S, K)}: the mirror of the down-and-in call. */
    public static double upAndInPut(double spot, double strike, double barrier,
                                    double rate, double carry, double vol, double timeYears) {
        validateUpPut(spot, strike, barrier);
        if (timeYears <= 0) {
            return 0;
        }
        return reflectionIn(OptionType.PUT, spot, strike, barrier, rate, carry, vol, timeYears);
    }

    /** Up-and-out put, {@code H ≥ max(S, K)}. */
    public static double upAndOutPut(double spot, double strike, double barrier,
                                     double rate, double carry, double vol, double timeYears) {
        validateUpPut(spot, strike, barrier);
        return BlackScholes.price(OptionType.PUT, spot, strike, rate, carry, vol, timeYears)
                - upAndInPut(spot, strike, barrier, rate, carry, vol, timeYears);
    }

    /**
     * The Reiner–Rubinstein knock-in value: the vanilla priced on the
     * barrier-reflected path measure. Sign symmetry handles the put mirror.
     */
    private static double reflectionIn(OptionType type, double spot, double strike,
                                       double barrier, double rate, double carry,
                                       double vol, double t) {
        int s = type.sign(); // +1 call (down barrier), −1 put (up barrier)
        double lambda = (rate - carry + 0.5 * vol * vol) / (vol * vol);
        double sq = vol * Math.sqrt(t);
        double y = Math.log(barrier * barrier / (spot * strike)) / sq + lambda * sq;
        double hs = barrier / spot;
        return s * (spot * Math.exp(-carry * t) * Math.pow(hs, 2 * lambda)
                        * MathUtils.normCdf(s * y)
                - strike * Math.exp(-rate * t) * Math.pow(hs, 2 * lambda - 2)
                        * MathUtils.normCdf(s * (y - sq)));
    }

    private static void validateDownCall(double spot, double strike, double barrier) {
        validateCommon(spot, strike, barrier);
        if (barrier >= spot) {
            throw new IllegalArgumentException(
                    "down barrier " + barrier + " already breached at spot " + spot);
        }
        if (barrier > strike) {
            throw new IllegalArgumentException(
                    "reverse barrier (H > K on a call) is not supported in closed form here — "
                            + "price it with Monte Carlo or a barrier-aware tree");
        }
    }

    private static void validateUpPut(double spot, double strike, double barrier) {
        validateCommon(spot, strike, barrier);
        if (barrier <= spot) {
            throw new IllegalArgumentException(
                    "up barrier " + barrier + " already breached at spot " + spot);
        }
        if (barrier < strike) {
            throw new IllegalArgumentException(
                    "reverse barrier (H < K on a put) is not supported in closed form here — "
                            + "price it with Monte Carlo or a barrier-aware tree");
        }
    }

    private static void validateCommon(double spot, double strike, double barrier) {
        if (spot <= 0 || strike <= 0 || barrier <= 0) {
            throw new IllegalArgumentException("spot, strike, barrier must be > 0");
        }
    }
}
