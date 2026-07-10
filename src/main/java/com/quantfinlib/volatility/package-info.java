/**
 * Volatility models: {@link com.quantfinlib.volatility.EwmaVolatility}
 * (RiskMetrics exponentially-weighted variance, λ = 0.94),
 * {@link com.quantfinlib.volatility.Garch11} (Gaussian MLE with variance
 * targeting; conditional variances and mean-reverting k-step forecasts)
 * and {@link com.quantfinlib.volatility.GjrGarch11} (the leverage-effect
 * asymmetry equity indices demand — a down move raises tomorrow's
 * variance by α + γ, an up move by only α; fitting γ ≈ 0 is itself the
 * finding that the series is symmetric), and
 * {@link com.quantfinlib.volatility.HarRv} (Corsi's heterogeneous
 * autoregressive realized-vol model — daily/weekly/monthly horizons by
 * plain OLS, the forecasting benchmark GARCH papers have to beat; pair
 * it with {@code microstructure.JumpRobustVolatility}'s bipower
 * variance to keep jumps out of the forecast). Feed the outputs to
 * parametric VaR, vol targeting, or option pricing; stochastic
 * volatility lives in {@code pricing.Heston}.
 */
package com.quantfinlib.volatility;
