/**
 * Volatility models: {@link com.quantfinlib.volatility.EwmaVolatility}
 * (RiskMetrics exponentially-weighted variance, λ = 0.94),
 * {@link com.quantfinlib.volatility.Garch11} (Gaussian MLE with variance
 * targeting; conditional variances and mean-reverting k-step forecasts)
 * and {@link com.quantfinlib.volatility.GjrGarch11} (the leverage-effect
 * asymmetry equity indices demand — a down move raises tomorrow's
 * variance by α + γ, an up move by only α; fitting γ ≈ 0 is itself the
 * finding that the series is symmetric),
 * {@link com.quantfinlib.volatility.Egarch11} (Nelson's log-variance
 * dynamics: leverage as a SIGN — γ &lt; 0 — with no positivity
 * constraints by construction; one-step forecasts exact, multi-step
 * deliberately refused since the log recursion forecasts the median,
 * not the mean), and
 * {@link com.quantfinlib.volatility.HarRv} (Corsi's heterogeneous
 * autoregressive realized-vol model — daily/weekly/monthly horizons by
 * plain OLS, the forecasting benchmark GARCH papers have to beat; pair
 * it with {@code microstructure.JumpRobustVolatility}'s bipower
 * variance to keep jumps out of the forecast). Two more members of the
 * volatility zoo: {@link com.quantfinlib.volatility.VolatilityIndex}
 * (the VIX-style "fear gauge" — MARKET volatility read model-free out
 * of an option chain via the variance-swap replication; the smile is
 * IN the index, which is why it sits above ATM implied vol) and
 * {@link com.quantfinlib.volatility.VolatilityDecomposition}
 * (SYSTEMATIC vs IDIOSYNCRATIC: the exact OLS split
 * {@code Var(asset) = β²·Var(market) + Var(residual)} — what index
 * hedges can remove vs what only diversification or single-name
 * hedges address). Historical volatility lives in
 * {@code risk.RiskMetrics.annualizedVolatility} and
 * {@code EwmaVolatility}; IMPLIED volatility in
 * {@code pricing.BlackScholes.impliedVol}, {@code pricing.Black76},
 * and the two vol surfaces. Feed the outputs to parametric VaR, vol
 * targeting, or option pricing; stochastic volatility lives in
 * {@code pricing.Heston}.
 */
package com.quantfinlib.volatility;
