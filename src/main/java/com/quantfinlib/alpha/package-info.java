/**
 * The alpha research pipeline — signal to evaluated, validated, cost-aware,
 * constructed, reported strategy, with each stage a separate, composable
 * step:
 *
 * <ol>
 *   <li><b>Signal generation</b> — {@link com.quantfinlib.alpha.Factors}:
 *       nine standard factors (MA crossover, contrarian RSI, MACD,
 *       Bollinger reversion, mean reversion, 12-1 momentum, value, quality,
 *       low volatility) producing raw cross-sectional scores over an
 *       {@link com.quantfinlib.alpha.AlphaContext} panel;</li>
 *   <li><b>Evaluation</b> — {@link com.quantfinlib.alpha.SignalEvaluator}:
 *       rank IC, IR, t-stat, hit rate, turnover, cross-factor exposure —
 *       the cheap filter before any backtest;</li>
 *   <li><b>Validation</b> — {@link com.quantfinlib.alpha.AlphaValidation}:
 *       walk-forward selection with OOS efficiency, blocked k-fold
 *       consistency, Monte Carlo permutation p-values, parameter
 *       sensitivity — the overfitting defense;</li>
 *   <li><b>Execution-aware backtest</b> —
 *       {@link com.quantfinlib.alpha.AlphaBacktester}: commission, bid-ask
 *       spread, slippage and square-root market impact
 *       ({@code microstructure.MarketImpactModel}), with gross-vs-net cost
 *       decomposition;</li>
 *   <li><b>Portfolio construction</b> —
 *       {@link com.quantfinlib.alpha.PortfolioConstruction}: z-score
 *       sizing with caps, inverse-vol risk budgeting, sector and beta
 *       neutralization, mean-variance tilt;</li>
 *   <li><b>Reporting</b> — {@link com.quantfinlib.alpha.AlphaReport}:
 *       alpha decay with half-life, OLS factor attribution, drawdown
 *       curves, rolling Sharpe, and the shared ratio set from
 *       {@code backtest.PerformanceAnalytics}.</li>
 * </ol>
 *
 * <p>All scores/weights flow as {@code double[]} aligned with the frozen
 * {@code AlphaContext.symbols()} order; NaN marks "no data" at every
 * stage. Factors must never read past their evaluation index — the
 * no-look-ahead contract on {@link com.quantfinlib.alpha.AlphaFactor}.
 * Attach a {@code data.PointInTimeUniverse} via
 * {@link com.quantfinlib.alpha.AlphaContext#withUniverse} to make the whole
 * pipeline survivorship-honest: dead and non-member names score NaN per bar
 * and never enter ICs, validation, or constructed weights.</p>
 */
package com.quantfinlib.alpha;
