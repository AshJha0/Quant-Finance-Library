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
 */
package com.quantfinlib.backtest.validation;
