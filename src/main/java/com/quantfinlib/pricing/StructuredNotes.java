package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * STRUCTURED NOTES — the retail shelf, priced the only honest way: by
 * DECOMPOSITION into the vanilla pieces this library already prices.
 * Every structured product is a bond plus options in a costume; the
 * costume is what the issuer charges for. Pricing by replication makes
 * the margin visible, and the tests ARE the decompositions (each note
 * must equal its replicating portfolio to machine precision).
 *
 * <ul>
 *   <li><b>Reverse convertible</b> — par bond + fat coupon, but the
 *       investor has SOLD a put struck at K: if S_T &lt; K they receive
 *       shares worth {@code S_T/K} of par instead of par.
 *       {@code value = (par + coupon) * DF(T) - (par/K) * put(K)}. The
 *       "9% coupon" is put premium in disguise — the whole product in
 *       one sentence. (The knock-in variant needs a down-and-in put,
 *       which this library does not yet price closed-form — stated, not
 *       approximated.)</li>
 *   <li><b>Capital-protected note</b> — {@code protection * par} floor
 *       plus {@code participation} of the upside:
 *       {@code value = protection * par * DF(T) +
 *       participation * (par/S0) * call(S0)}. The issuer's real product
 *       decision is the PARTICIPATION RATE the budget affords:
 *       {@link #participationFor} inverts the pricing for it — low
 *       rates or high vol mean thin participation, which is why these
 *       notes flourish when rates are high and die when they are
 *       zero.</li>
 *   <li><b>Discount certificate</b> — buy the underlying at a discount,
 *       give away the upside beyond a cap: the covered call,
 *       {@code value = S * e^{-qT} - call(cap)}. The discount to spot
 *       is exactly the call premium received.</li>
 * </ul>
 *
 * <p>Deltas come from the same decompositions (sum of the pieces'
 * {@link BlackScholes#delta} terms), so hedging a note is hedging its
 * replication — which is literally what the issuer's desk does. Values
 * are per note of face {@code par} (certificate: per unit of
 * underlying). Issuer margin = issue price minus fair value; this class
 * computes fair value and leaves the margin arithmetic in plain sight.
 * Research lane, deterministic.</p>
 */
public final class StructuredNotes {

    private StructuredNotes() {
    }

    /**
     * Fair value of a vanilla reverse convertible of face {@code par}.
     *
     * @param couponRate total coupon rate for the LIFE of the note
     *                   (0.09 = 9% paid at maturity with the redemption)
     * @param strike     the conversion strike K (shares delivered are
     *                   worth {@code S_T/K * par} when {@code S_T < K})
     */
    public static double reverseConvertible(double par, double couponRate,
                                            double spot, double strike, double rate,
                                            double carry, double vol, double timeYears) {
        requireCommon(par, spot, rate, carry, vol, timeYears);
        if (!(strike > 0) || strike == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("strike must be positive and finite");
        }
        if (!(couponRate >= 0) || couponRate == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("couponRate must be >= 0 and finite");
        }
        double df = Math.exp(-rate * timeYears);
        double put = BlackScholes.price(OptionType.PUT, spot, strike, rate, carry, vol, timeYears);
        return par * (1 + couponRate) * df - (par / strike) * put;
    }

    /** Delta of the reverse convertible: short put makes the holder LONG the stock. */
    public static double reverseConvertibleDelta(double par, double spot, double strike,
                                                 double rate, double carry, double vol,
                                                 double timeYears) {
        requireCommon(par, spot, rate, carry, vol, timeYears);
        return -(par / strike)
                * BlackScholes.delta(OptionType.PUT, spot, strike, rate, carry, vol, timeYears);
    }

    /**
     * Fair value of a capital-protected note: {@code protection} of par
     * floored, plus {@code participation} of the underlying's upside from
     * {@code spot}.
     */
    public static double capitalProtectedNote(double par, double protection, double participation,
                                              double spot, double rate, double carry,
                                              double vol, double timeYears) {
        requireCommon(par, spot, rate, carry, vol, timeYears);
        if (!(protection > 0) || protection > 1) {
            throw new IllegalArgumentException("protection must be in (0, 1], got " + protection);
        }
        if (!(participation >= 0) || participation == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("participation must be >= 0 and finite");
        }
        double df = Math.exp(-rate * timeYears);
        double call = BlackScholes.price(OptionType.CALL, spot, spot, rate, carry, vol, timeYears);
        return protection * par * df + participation * (par / spot) * call;
    }

    /**
     * The participation rate a given ISSUE PRICE affords:
     * {@code (issuePrice - protection * par * DF) / ((par/S0) * call)}.
     * This is the issuer's product-design equation solved for its one
     * free variable — and the reason zero-rate eras produce notes with
     * embarrassing participation: the bond floor eats the whole budget.
     */
    public static double participationFor(double par, double protection, double issuePrice,
                                          double spot, double rate, double carry,
                                          double vol, double timeYears) {
        requireCommon(par, spot, rate, carry, vol, timeYears);
        if (!(issuePrice > 0) || issuePrice == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("issuePrice must be positive and finite");
        }
        if (!(protection > 0) || protection > 1) {
            throw new IllegalArgumentException("protection must be in (0, 1], got " + protection);
        }
        double df = Math.exp(-rate * timeYears);
        double floor = protection * par * df;
        if (issuePrice <= floor) {
            throw new IllegalArgumentException("issue price " + issuePrice
                    + " does not even cover the protected floor " + floor
                    + ": no participation is affordable");
        }
        double call = BlackScholes.price(OptionType.CALL, spot, spot, rate, carry, vol, timeYears);
        if (!(call > 0)) {
            throw new IllegalArgumentException(
                    "ATM call is worthless under these parameters: participation undefined");
        }
        return (issuePrice - floor) / ((par / spot) * call);
    }

    /**
     * Fair value per unit of underlying of a discount certificate capped
     * at {@code cap}: the covered call {@code S e^{-qT} - call(cap)}.
     */
    public static double discountCertificate(double spot, double cap, double rate,
                                             double carry, double vol, double timeYears) {
        requireCommon(1, spot, rate, carry, vol, timeYears);
        if (!(cap > 0) || cap == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("cap must be positive and finite");
        }
        return spot * Math.exp(-carry * timeYears)
                - BlackScholes.price(OptionType.CALL, spot, cap, rate, carry, vol, timeYears);
    }

    /** Delta of the discount certificate: long stock, short call. */
    public static double discountCertificateDelta(double spot, double cap, double rate,
                                                  double carry, double vol, double timeYears) {
        requireCommon(1, spot, rate, carry, vol, timeYears);
        return Math.exp(-carry * timeYears)
                - BlackScholes.delta(OptionType.CALL, spot, cap, rate, carry, vol, timeYears);
    }

    private static void requireCommon(double par, double spot, double rate, double carry,
                                      double vol, double timeYears) {
        if (!(par > 0) || par == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("par must be positive and finite");
        }
        if (!(spot > 0) || spot == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("spot must be positive and finite");
        }
        if (!Double.isFinite(rate) || !Double.isFinite(carry)) {
            throw new IllegalArgumentException("rate and carry must be finite");
        }
        if (!(vol >= 0) || vol == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("vol must be >= 0 and finite");
        }
        if (!(timeYears > 0) || timeYears == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("timeYears must be positive and finite");
        }
    }
}
