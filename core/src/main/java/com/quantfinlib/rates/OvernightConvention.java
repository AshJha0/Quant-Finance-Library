package com.quantfinlib.rates;

/**
 * How a compounded-in-arrears overnight coupon (SOFR, ESTR, SONIA) reads its
 * daily fixings: lookback, observation shift and lockout, as described in
 * the ISDA 2021 Interest Rate Derivatives Definitions (section 6.9,
 * overnight rate compounding) and the ARRC SOFR conventions.
 *
 * <p>The plain idea: the coupon for an accrual period is the
 * daily-compounded overnight rate, but the payer needs to know the amount a
 * few days before paying. Each convention buys that time differently.</p>
 * <ul>
 *   <li><b>Lookback</b> of L business days, no shift: accrual day i uses the
 *       fixing published L business days earlier, weighted by the accrual
 *       day's own calendar-day count {@code n_i}.</li>
 *   <li><b>Observation shift</b> of L days: the whole observation window
 *       moves back L business days, and both rates and weights {@code n_j}
 *       come from the shifted window; the compounded rate is annualised over
 *       the shifted window's days.</li>
 *   <li><b>Lockout</b> of k business days: the rates for the last k
 *       business days of the (observation) period are replaced by the rate of
 *       the day before them.</li>
 * </ul>
 * <p>Formal statement, with {@code d} the day-count denominator of the leg
 * (360 for ACT/360, 365 for ACT/365F):</p>
 * <pre>
 *   R      = ( prod_i (1 + r_i n_i / d) - 1 ) * d / N,   N = sum_i n_i
 *   coupon = (R + spread) * tau(accrualStart, accrualEnd)
 * </pre>
 * <p>Worked example: with no lookback, shift or lockout, no spread and
 * forecast fixings from a single curve {@code P}, the coupon times tau
 * telescopes to {@code P(s)/P(e) - 1} (the identity the tests pin).</p>
 *
 * @param lookbackDays     business days of lookback (or shift), {@code >= 0}
 * @param observationShift whether the lookback shifts the observation window (weights from it)
 * @param lockoutDays      business days of lockout at the end of the period, {@code >= 0}
 */
public record OvernightConvention(int lookbackDays, boolean observationShift, int lockoutDays) {

    /** Plain compounding in arrears: no lookback, no shift, no lockout. */
    public static final OvernightConvention IN_ARREARS = new OvernightConvention(0, false, 0);

    /** Validates the fields. */
    public OvernightConvention {
        if (lookbackDays < 0 || lockoutDays < 0) {
            throw new IllegalArgumentException("lookbackDays and lockoutDays must be >= 0");
        }
    }
}
