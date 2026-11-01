package com.quantfinlib.pricing;

/**
 * An implied volatility surface expressed as total implied variance
 * w(T, k) = σ_BS(T, K)²·T against log-moneyness k = ln(K/F(T)), on a market
 * with flat continuously compounded rate {@code r} and carry (dividend or
 * foreign) yield {@code q}, so F(T) = S·e^{(r−q)T}.
 *
 * <p>Implemented by {@link SsviSurface} and {@link SviSurface}; consumed by
 * {@link DupireLocalVol}.</p>
 */
public interface TotalVarianceSurface {

    /** Total implied variance at expiry {@code t} (years, &gt; 0) and log-moneyness {@code k}. */
    double totalVariance(double t, double k);

    /** Spot price. */
    double spot();

    /** Continuously compounded risk-free rate. */
    double rate();

    /** Continuous carry yield (dividend yield, or foreign rate for FX). */
    double carry();

    /** Forward F(t) = S·e^{(r−q)t}. */
    default double forward(double t) {
        return spot() * Math.exp((rate() - carry()) * t);
    }

    /** Black-Scholes implied volatility at expiry {@code t} and strike {@code strike}. */
    default double impliedVol(double t, double strike) {
        if (!(t > 0) || !(strike > 0)) {
            throw new IllegalArgumentException("t and strike must be > 0");
        }
        return Math.sqrt(totalVariance(t, Math.log(strike / forward(t))) / t);
    }

    /** Black-Scholes price of a European option off the surface. */
    default double price(BlackScholes.OptionType type, double strike, double t) {
        return BlackScholes.price(type, spot(), strike, rate(), carry(), impliedVol(t, strike), t);
    }

    /**
     * Calendar-arbitrage scan: number of (t, k) grid points where total
     * variance falls between consecutive times of {@code times} (ascending)
     * at fixed log-moneyness, over {@code nk} points of [kMin, kMax].
     */
    default int calendarViolations(double[] times, double kMin, double kMax, int nk) {
        if (times.length < 2 || nk < 2 || !(kMax > kMin)) {
            throw new IllegalArgumentException("need >= 2 times, nk >= 2, kMax > kMin");
        }
        int count = 0;
        for (int j = 0; j < nk; j++) {
            double k = kMin + (kMax - kMin) * j / (nk - 1);
            double prev = totalVariance(times[0], k);
            for (int i = 1; i < times.length; i++) {
                if (!(times[i] > times[i - 1])) {
                    throw new IllegalArgumentException("times must be strictly ascending");
                }
                double w = totalVariance(times[i], k);
                if (w < prev - 1e-14) {
                    count++;
                }
                prev = w;
            }
        }
        return count;
    }

    /**
     * Minimum Gatheral-Jacquier density factor g(k) at expiry {@code t} over
     * {@code nk} points of [kMin, kMax], with w', w'' by central differences.
     * Negative means butterfly arbitrage.
     */
    default double minDensityFactor(double t, double kMin, double kMax, int nk) {
        if (nk < 2 || !(kMax > kMin)) {
            throw new IllegalArgumentException("need nk >= 2 and kMax > kMin");
        }
        double h = 1e-4;
        double min = Double.POSITIVE_INFINITY;
        for (int j = 0; j < nk; j++) {
            double k = kMin + (kMax - kMin) * j / (nk - 1);
            double w = totalVariance(t, k);
            double wu = totalVariance(t, k + h);
            double wd = totalVariance(t, k - h);
            min = Math.min(min, SviSlice.densityFactor(k, w, (wu - wd) / (2 * h), (wu - 2 * w + wd) / (h * h)));
        }
        return min;
    }
}
