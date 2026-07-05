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
 * multiple-testing haircut for grid-picked winners.
 */
package com.quantfinlib.backtest.validation;
