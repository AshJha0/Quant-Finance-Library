/**
 * The defense against overfit backtests:
 * {@link com.quantfinlib.backtest.validation.ParameterGrid} +
 * {@link com.quantfinlib.backtest.validation.GridSearchOptimizer} enumerate
 * and rank parameter combinations;
 * {@link com.quantfinlib.backtest.validation.WalkForwardAnalyzer} optimizes
 * on rolling train windows and evaluates on unseen test windows, stitching
 * out-of-sample equity (capital carries across folds) and reporting the
 * walk-forward efficiency ratio;
 * {@link com.quantfinlib.backtest.validation.SharpeValidation} applies the
 * Bailey/López de Prado probabilistic and deflated Sharpe — the
 * multiple-testing haircut for grid-picked winners — plus the minimum
 * track record length (how many periods before the record MEANS
 * something, in closed form);
 * {@link com.quantfinlib.backtest.validation.BlockBootstrap} hands the
 * backtest Sharpe its sampling DISTRIBUTION (stationary Politis-Romano
 * blocks — an iid resample destroys the autocorrelation and understates
 * the uncertainty, the classic route to false confidence): the honest
 * question is whether the 5th percentile is still positive, not whether
 * 1.2 is a good number.
 *
 * <p>Two more layers of defense:
 * {@link com.quantfinlib.backtest.validation.PurgedKFold} generates
 * cross-validation splits that PURGE training samples whose label windows
 * overlap the test fold and EMBARGO the serially-correlated echo after it
 * — ordinary K-fold on financial data leaks the test answers into
 * training through forward-looking labels; and
 * {@link com.quantfinlib.backtest.validation.OverfitProbability} asks the
 * question that comes before any single track record: is the SELECTION
 * process itself noise-mining? (CSCV: how often does the in-sample winner
 * rank below the out-of-sample median across every symmetric
 * train/test split.)</p>
 */
package com.quantfinlib.backtest.validation;
