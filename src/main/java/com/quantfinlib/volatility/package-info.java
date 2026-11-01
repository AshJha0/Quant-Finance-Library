/**
 * Volatility models: EWMA, GARCH-family, HAR-RV, a model-free volatility
 * index and a systematic/idiosyncratic decomposition.
 * {@link com.quantfinlib.volatility.EwmaVolatility}
 * (RiskMetrics exponentially-weighted variance, λ = 0.94),
 * {@link com.quantfinlib.volatility.Garch11} (Gaussian MLE with variance
 * targeting; conditional variances and mean-reverting k-step forecasts)
 * and {@link com.quantfinlib.volatility.GjrGarch11} (Glosten, Jagannathan
 * and Runkle, 1993: a down move raises next-period variance by α + γ, an
 * up move by α; a fitted γ near 0 indicates a symmetric series),
 * {@link com.quantfinlib.volatility.Egarch11} (Nelson, 1991: log-variance
 * dynamics, leverage as γ &lt; 0, no positivity constraints needed; one-step forecasts exact, multi-step
 * refused since the log recursion forecasts the median,
 * not the mean), and
 * {@link com.quantfinlib.volatility.HarRv} (Corsi, 2009: heterogeneous
 * autoregressive realized-volatility model, daily, weekly and monthly
 * horizons fitted by OLS; pair it with {@code microstructure.JumpRobustVolatility}'s bipower
 * variance to keep jumps out of the forecast). Also:
 * {@link com.quantfinlib.volatility.VolatilityIndex} (a VIX-style index
 * computed model-free from an option chain by variance-swap replication;
 * it includes the smile, so it usually sits above ATM implied vol) and
 * {@link com.quantfinlib.volatility.VolatilityDecomposition}
 * (systematic versus idiosyncratic: the OLS split
 * {@code Var(asset) = β²·Var(market) + Var(residual)}, separating what an
 * index hedge removes from what needs diversification or single-name
 * hedges). Historical volatility lives in
 * {@code risk.RiskMetrics.annualizedVolatility} and
 * {@code EwmaVolatility}; implied volatility in
 * {@code pricing.BlackScholes.impliedVol}, {@code pricing.Black76},
 * and the two vol surfaces. Feed the outputs to parametric VaR, vol
 * targeting, or option pricing; stochastic volatility lives in
 * {@code pricing.Heston}.
 */
package com.quantfinlib.volatility;
