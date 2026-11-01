### Fixed
- `Backtester`, `PortfolioBacktester` and `AlphaBacktester` no longer fill an order at the close of the bar that generated it; orders fill at the next bar's open by default (`FillPolicy` NEXT_OPEN, with NEXT_CLOSE and the old CLOSE behaviour as explicit options).
- `AlphaBacktester` lets weights drift with prices between rebalances and measures turnover against the drifted book; it previously rebalanced back to target every bar for free.
- Forward returns, IC, quantile spreads and held positions in the alpha package now earn delisting and merger proceeds when a point-in-time universe is attached, instead of reading the flat forward-filled bars after a delisting as a zero return.
- Value and quality factors refuse to score historical bars from a static fundamentals snapshot (look-ahead); attach a `FundamentalsHistory` with publication dates instead.
- `SignalEvaluator` hit rate is computed on cross-sectionally demeaned scores and returns, so one-signed scores (low volatility, value) no longer report the market's up-move fraction.
- IC t-stats (`SignalEvaluator`), Fama-MacBeth premia and calendar anomaly t-stats use Newey-West HAC standard errors; the iid versions are kept as `naiveTStat`. Tests that pinned the iid values were updated.
- `AlphaValidation.monteCarloRobustness` defaults to a cross-sectional label permutation null; the old date permutation, which gives p near 1 for every persistent factor, is kept as `NullModel.DATE_PERMUTATION`. A rounding bug that made exact-tie permutations count as less extreme than the observed IC is fixed.
- `PortfolioConstruction.zScoreWeights` stays dollar-neutral after the ±3σ clamp and the name cap, and the cap is applied by iterative water-filling; `zScoreBook` reports achieved gross and flags a shortfall.
- `PortfolioConstruction.trailingBetas` uses a leave-one-out market (or an external market series); the test that pinned the self-inclusive betas 1.6/0.4 now expects 4.0/0.25.
- `parameterSensitivity.worstNeighborDrop` measures the drop from the best parameter to its worse neighbour; the old largest-adjacent-jump value is `largestAdjacentJump`.
- Walk-forward analysis (`WalkForwardAnalyzer`) carries the open position across fold boundaries instead of force-closing and re-entering each fold, reports trade indices into the full series, and computes efficiency on the stitched OOS and IS curves; alpha walk-forward weights folds by observation count.
- DSL strategies evaluate the exit rule first while long; a level-type entry rule no longer masks every exit.
- `MarketImpactModel.estimate` uses only the bars before the trade bar and skips forward-filled bars.
- `AlphaContext.of` checks every bar timestamp, not just series length.
- Stop-loss and take-profit fills pay slippage; the stop-before-target precedence on a bar that touches both is documented.
- `TradeAnalytics.kellyFraction` is the empirical growth-optimal fraction on per-trade returns; `MonteCarloTradeShuffle` compounds per-trade returns (drawdowns are now fractions). Tests that pinned the currency-P&L values were updated.
- `TickBacktester` fills a resting order traded through by at most the through print's size.
- Streaming and batch SMA re-sum the window every `period` updates so rounding cannot drift over long runs.
- `AlphaEnsemble` and `OnlineAlphaLearner` gate on IC significance (IC minus two EWMA standard errors) rather than on IC > 0; the test that expected the ungated weight now expects 0 for a non-significant IC.
- `LeadLagEstimator.bestLag` reports no lead unless the best lag clears a multiple-testing corrected band and beats the contemporaneous correlation.
- `TechnicalFilters.priceAboveVwap` uses a rolling 20-bar VWAP instead of one anchored at the first bar.

### Changed
- `BacktestConfig.defaults()` charges 1 bp commission and 3 bp slippage (was 10 bp commission, no slippage) and fills next open; `BacktestConfig.frictionless()` gives a zero-cost config.
- `AlphaReport.decayProfile` is documented as the holding-period IC; its format string says so.
- `PortfolioStrategy` gains `init(signalData, tradeData)`; `PortfolioBacktester` has a price-pair overload so signals run on adjusted prices while cash and dividends use unadjusted prices.
- `TradingStrategy` gains a position-aware `onBar(index, position)` default method, used by the bar engines.
- Result records of `AlphaBacktester`, `PortfolioBacktester`, `SignalEvaluator`, `AlphaValidation`, `FamaMacBeth`, `CalendarAnomalies`, `AlphaReport.Attribution`, `WalkForwardAnalyzer` and `GridSearchOptimizer.Candidate` carry new fields.
- `Indicators.Ichimoku.chikou` is documented as display-only (it holds future prices relative to its index).
- `KylesLambda.impactBps` documents that λ is per unit of net flow over one aggregation window.

### Added
- `FillPolicy` enum shared by the bar engines.
- `AlphaBacktester.Config` options for fill policy, financing (borrow fee, cash rate, margin rate) and a per-bar participation cap with carry-over of unexecuted trades.
- `PortfolioBacktester.Financing` (cash, margin and per-symbol borrow rates) and fill policy.
- `FundamentalsHistory` point-in-time fundamentals with reporting lags.
- `NeweyWest` (HAC variance, Andrews automatic lag) and `Ols` (Householder QR with classical and HAC errors).
- `AlphaReport.lagIcProfile`: IC against lagged one-bar returns with an exponential decay fit and signal autocorrelation.
- `FactorReturns.longShort` buy-and-hold quantile factor return streams; OLS attribution now reports standard errors and a HAC t on alpha.
- `TurnoverAwareOptimizer`: ADMM solver for alpha, risk, L1 turnover penalty, position and trade boxes and neutrality equalities.
- `PortfolioConstruction.neutralize` (joint projection onto several constraints) and `sectorRows`.
- `Factors.winsorized`, `standardized`, `sectorNeutralized` and `prepared` per-date decorators.
- Synthetic-bar flag on `BarSeries`, set by `SeriesAligner.unionForwardFill` and skipped by volatility and impact estimators.
- `HawkesIntensity.fit`: maximum-likelihood calibration of the exponential-kernel Hawkes process.
- `IcSignificance` (EWMA effective sample size and IC standard error).
- `GridSearchOptimizer.effectiveTrials`; the deflated Sharpe of the grid winner uses it.
- `TickBacktester.Config.impactCoefficientBps` square-root size term for market orders.
- Noisy-panel statistical tests (null IC t-stats across seeds, shuffled signals, zero-return panels), an exact two-asset backtest case, a look-ahead canary, and indicator values pinned to the published StockCharts RSI example and to Wilder's ATR/ADX definitions.
