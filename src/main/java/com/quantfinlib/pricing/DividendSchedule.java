package com.quantfinlib.pricing;

import com.quantfinlib.pricing.BlackScholes.OptionType;

/**
 * Discrete (cash) dividends for equity derivatives — the forward-looking
 * counterpart to {@code data.CorporateActions}' historical back-adjustment.
 *
 * <p>A continuous dividend yield misprices single stocks: dividends arrive
 * as dated cash amounts, and an option spanning an ex-date is worth
 * measurably less (calls) or more (puts) than the yield approximation says.
 * This class implements the <b>escrowed dividend</b> model: the PV of all
 * dividends with ex-dates before expiry is stripped from spot, and the
 * remainder diffuses lognormally,</p>
 *
 * <pre>
 *   S* = S − Σ dᵢ·e^{−r·tᵢ}   (tᵢ ≤ T),   F = S*·e^{(r−borrow)T}
 * </pre>
 *
 * <p>Borrow cost enters exactly like a continuous yield ({@code carry} in
 * {@link BlackScholes}) — hard-to-borrow names price calls down and puts up
 * the same way a dividend yield does.</p>
 *
 * <p>The escrowed model applies the volatility to S* for the whole life of
 * the option, which understates the variance of a spot that only drops at
 * the ex-date; the error grows with the dividend size, the time from the
 * ex-date to expiry and the option's maturity (Haug, Haug and Lewis 2003,
 * "Back to basics: a new approach to the discrete dividend problem",
 * Wilmott). {@link #bosVandermarkPrice} splits each dividend between spot
 * and strike by its timing (Bos and Vandermark 2002, "Finessing fixed
 * dividends", Risk) and is markedly closer to the exact jump model for
 * European options. Neither adjustment is right for American exercise just
 * before an ex-date; that needs a tree or grid with the cash drop applied at
 * the ex-date, which this library does not provide.</p>
 *
 * <p>A dividend with ex-time equal to the horizon is included (the holder at
 * expiry does not receive it in the convention used here).</p>
 *
 * <p>Instances are immutable; all queries are allocation-free.</p>
 */
public final class DividendSchedule {

    /** Empty schedule (no dividends): forwards collapse to the yield-free case. */
    public static final DividendSchedule NONE = new DividendSchedule(new double[0], new double[0]);

    private final double[] exTimes;  // years from valuation, ascending
    private final double[] amounts;  // cash per share

    private DividendSchedule(double[] exTimes, double[] amounts) {
        this.exTimes = exTimes;
        this.amounts = amounts;
    }

    /**
     * @param exTimesYears ex-dividend times in years from valuation, ascending
     * @param amounts      cash amounts per share, aligned with the times
     */
    public static DividendSchedule of(double[] exTimesYears, double[] amounts) {
        if (exTimesYears.length != amounts.length) {
            throw new IllegalArgumentException("times and amounts must align");
        }
        for (int i = 0; i < exTimesYears.length; i++) {
            if (exTimesYears[i] <= 0 || amounts[i] < 0) {
                throw new IllegalArgumentException("ex-times must be > 0 and amounts >= 0");
            }
            if (i > 0 && exTimesYears[i] <= exTimesYears[i - 1]) {
                throw new IllegalArgumentException("ex-times must be strictly ascending");
            }
        }
        return new DividendSchedule(exTimesYears.clone(), amounts.clone());
    }

    /** Present value of all dividends with ex-dates on or before {@code horizonYears}. */
    public double presentValue(double rate, double horizonYears) {
        double pv = 0;
        for (int i = 0; i < exTimes.length && exTimes[i] <= horizonYears; i++) {
            pv += amounts[i] * Math.exp(-rate * exTimes[i]);
        }
        return pv;
    }

    /** Escrowed spot: what actually diffuses once dividend PV is stripped. */
    public double adjustedSpot(double spot, double rate, double horizonYears) {
        double adjusted = spot - presentValue(rate, horizonYears);
        if (adjusted <= 0) {
            throw new IllegalArgumentException(
                    "dividend PV exceeds spot — check amounts/horizon");
        }
        return adjusted;
    }

    /** Equity forward with discrete dividends and a continuous borrow fee. */
    public double forward(double spot, double rate, double borrow, double horizonYears) {
        return adjustedSpot(spot, rate, horizonYears)
                * Math.exp((rate - borrow) * horizonYears);
    }

    /**
     * European price under the escrowed model: Black-Scholes on the adjusted
     * spot, with borrow as the carry. With no dividends and no borrow this
     * is exactly the plain Black-Scholes price.
     */
    public double europeanPrice(OptionType type, double spot, double strike, double rate,
                                double borrow, double vol, double timeYears) {
        return BlackScholes.price(type, adjustedSpot(spot, rate, timeYears), strike,
                rate, borrow, vol, timeYears);
    }

    /**
     * European price with the Bos-Vandermark (2002) split: for dividends
     * dᵢ at tᵢ ≤ T,
     *
     * <pre>  S' = S − Σ ((T − tᵢ)/T)·dᵢ·e^{−r tᵢ},   K' = K + Σ (tᵢ/T)·dᵢ·e^{r(T − tᵢ)}</pre>
     *
     * <p>and Black-Scholes on (S', K') with borrow as the carry. Early
     * dividends come mostly off spot, late ones mostly onto the strike.</p>
     */
    public double bosVandermarkPrice(OptionType type, double spot, double strike, double rate,
                                     double borrow, double vol, double timeYears) {
        if (!(timeYears > 0)) {
            return BlackScholes.intrinsic(type, spot - presentValue(rate, 0), strike);
        }
        double near = 0, far = 0;
        for (int i = 0; i < exTimes.length && exTimes[i] <= timeYears; i++) {
            double t = exTimes[i];
            near += (timeYears - t) / timeYears * amounts[i] * Math.exp(-rate * t);
            far += t / timeYears * amounts[i] * Math.exp(rate * (timeYears - t));
        }
        double s = spot - near;
        if (!(s > 0)) {
            throw new IllegalArgumentException("dividend PV exceeds spot - check amounts/horizon");
        }
        return BlackScholes.price(type, s, strike + far, rate, borrow, vol, timeYears);
    }

    public int count() {
        return exTimes.length;
    }
}
