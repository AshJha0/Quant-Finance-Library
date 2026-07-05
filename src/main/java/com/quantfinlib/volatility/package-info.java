/**
 * Volatility models: {@link com.quantfinlib.volatility.EwmaVolatility}
 * (RiskMetrics exponentially-weighted variance, λ = 0.94) and
 * {@link com.quantfinlib.volatility.Garch11} (Gaussian MLE with variance
 * targeting; conditional variances and mean-reverting k-step forecasts).
 * Feed the outputs to parametric VaR, vol targeting, or option pricing.
 */
package com.quantfinlib.volatility;
