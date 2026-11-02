/**
 * Cross-sectional alpha research: signal generation, evaluation,
 * validation, execution-aware backtesting, portfolio construction and
 * reporting, each a separate step.
 *
 * <ol>
 *   <li><b>Signal generation</b>: {@link com.quantfinlib.alpha.Factors}
 *       (MA crossover, contrarian RSI, MACD, Bollinger reversion, mean
 *       reversion, 12-1 momentum, value, quality, low volatility) over an
 *       {@link com.quantfinlib.alpha.AlphaContext} panel, with per-date
 *       winsorise, z-score and sector-neutralise decorators. Value and
 *       quality need point-in-time fundamentals
 *       ({@link com.quantfinlib.alpha.FundamentalsHistory}); a static
 *       snapshot is refused for historical bars.</li>
 *   <li><b>Evaluation</b>: {@link com.quantfinlib.alpha.SignalEvaluator}:
 *       rank IC at every bar with Newey-West t-stats and IC
 *       autocorrelation, demeaned hit rate, turnover, quantile spreads,
 *       cross-factor exposure.</li>
 *   <li><b>Validation</b>: {@link com.quantfinlib.alpha.AlphaValidation}:
 *       walk-forward selection with observation-weighted folds and a
 *       best-of-N haircut, blocked k-fold consistency, permutation nulls
 *       (cross-sectional label permutation by default; circular shift and
 *       date permutation as diagnostics), parameter sensitivity.</li>
 *   <li><b>Execution-aware backtest</b>:
 *       {@link com.quantfinlib.alpha.AlphaBacktester}: next-open fills by
 *       default, weights that drift between trades, commission, spread,
 *       slippage and square-root impact, participation caps with carry-over,
 *       financing, and terminal events for held names.</li>
 *   <li><b>Portfolio construction</b>:
 *       {@link com.quantfinlib.alpha.PortfolioConstruction} (dollar-neutral
 *       z-score books with water-filling caps, inverse-vol budgeting, joint
 *       sector/beta/dollar neutralisation, mean-variance tilt) and
 *       {@link com.quantfinlib.alpha.TurnoverAwareOptimizer} (alpha, risk,
 *       L1 turnover penalty, boxes, trade limits and neutrality equalities,
 *       solved by ADMM).</li>
 *   <li><b>Reporting</b>: {@link com.quantfinlib.alpha.AlphaReport}: alpha
 *       decay on lagged one-bar returns with an exponential fit,
 *       holding-period IC, OLS attribution with Newey-West errors on
 *       {@link com.quantfinlib.alpha.FactorReturns} streams, drawdown curves,
 *       rolling Sharpe.</li>
 * </ol>
 *
 * <p>All scores and weights flow as {@code double[]} aligned with the
 * sorted {@code AlphaContext.symbols()}; NaN marks "no data" at every
 * stage. Factors must never read past their evaluation index. Attach a
 * {@code data.PointInTimeUniverse} via
 * {@link com.quantfinlib.alpha.AlphaContext#withUniverse}: non-members score
 * NaN, and forward returns and held positions earn delisting and merger
 * proceeds instead of forward-filled ghost bars.</p>
 *
 * <p>Pricing and seasonality: {@link com.quantfinlib.alpha.FamaMacBeth}
 * (cross-sectional premia with Newey-West errors on the premium series,
 * optional per-period standardisation, QR solves) and
 * {@link com.quantfinlib.alpha.CalendarAnomalies} (day-of-week and
 * turn-of-month profiles with HAC t-stats and Holm-corrected flags).
 * Shared statistics: {@link com.quantfinlib.alpha.NeweyWest},
 * {@link com.quantfinlib.alpha.Ols}.</p>
 */
@NullMarked
package com.quantfinlib.alpha;

import org.jspecify.annotations.NullMarked;
