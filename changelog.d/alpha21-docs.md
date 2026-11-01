# Docs notes for alpha21 (W3)

## FactorRiskModel (risk)
- LEARN: r_it = f_mkt + f_sec(i) + sum_k x_ik f_k + u_it; V = X F X' + D.
  Styles standardised to cap-weighted mean 0 and equal-weighted sd 1, clipped at +-3 and re-standardised (Menchero, Orr and Wang 2011, USE4 methodology notes, s3).
  Factor returns: WLS with sqrt(cap) weights. The sector dummies sum to the market column, so the constraint sum_s c_s f_s = 0 (c_s = sector cap share) is imposed (Menchero 2010, J. Performance Measurement 15(1)) and solved exactly by substitution.
  F: zero-mean EWMA, then Newey-West (1987) F = C0 + sum_l (1 - l/(L+1))(C_l + C_l'), then the volatility regime adjustment lambda_F^2 = EWMA of B_t^2 = mean_k (f_kt / sigma_k,t-1)^2 (USE4 s4.3).
  Specific: EWMA residual variance, Bayesian shrinkage sigma_SH = v mean_n + (1 - v) sigma with v = q|sigma - mean_n| / (Delta_n + q|sigma - mean_n|) inside size buckets (USE4 s5.3).
  Decomposition: marginal (Vw)_i / sigma; percent w_i (Vw)_i / sigma^2 (sums to 1); factor x_k (F x)_k / sigma^2 plus the specific share (sums to 1); Euler allocation (Litterman 1996).
- COOKBOOK: `FactorRiskModel m = FactorRiskModel.estimate(returns, styles, List.of("SIZE", "MOM"), sectors, List.of("TECH", "FIN"), caps, FactorRiskModel.Settings.defaults()); FactorRiskModel.Decomposition d = m.decompose(weights); d.totalRisk(); d.factorContribution(k);`
- Inputs: returns[T][N], styles[T or T+1][K][N], caps[T or T+1][N]; the optional extra row is the forecast date.

## AlphaRiskModels and AlphaBacktester.RiskOptions (alpha)
- `AlphaRiskModels.estimate(ctx, index, sectors, Spec.defaults())` builds the model from a panel (size = log market cap, or trailing dollar volume when there are no fundamentals).
- `AlphaBacktester.run(ctx, factor, config, RiskOptions.defaults().withSectors(labels))`: alpha_i = IC * sigma_i * sqrt(H) * z_i (Grinold 1994); maximise alpha'w - lambda w'(H V)w - kappa |w - w0|_1 with kappa the flat one-way cost; automatic %ADV trade limit advTradeFraction * ADV * price / (capital * equity). The older overloads are unchanged.
- `AlphaBacktester.runTraced(...)` returns per-bar held weights, name P&L, cost and financing; they add up to the net return exactly.

## PortfolioBacktester.Participation (backtest.portfolio)
- `PortfolioBacktester.run(strategy, data, data, config, null, Map.of(), new PortfolioBacktester.Participation(0.1, 20))`: each symbol trades at most 10% of its 20-bar ADV per bar; unfinished targets carry to the next fill point.

## AlphaCombiner (alpha)
- Per-date Gram-Schmidt on factor z-scores (order matters); horizon-matched IC_k(t) = (1/H) sum_{l<H} corr(z'_k(t), r_{t+l -> t+l+1}) (Grinold and Kahn 2000, ch. 13 on signal decay); exponential date weights; weights IC_k / sum|IC| shrunk toward 1/K.
- Example: `AlphaCombiner.Combination c = AlphaCombiner.fit(ctx, List.of(mom, value, lowVol), 60, 500, AlphaCombiner.Settings.of(21).withShrinkToEqual(0.3)); AlphaBacktester.run(ctx, c, config);`

## BacktestAttribution (alpha)
- factor_k = (sum_i h_i X_ik) f_k; specific = sum_i h_i u_i; trading = sum_i pnl_i - factor - specific; net = factor + specific + trading + cost + financing exactly. Long/short leg and sector splits.
- Example: `BacktestAttribution a = BacktestAttribution.attribute(ctx, AlphaBacktester.runTraced(ctx, f, cfg, builder), sectors, AlphaRiskModels.Spec.defaults()); a.format();`

## CapacityCurve (alpha)
- The same backtest at each capital; square-root impact drag grows as sqrt(capital), so net Sharpe falls; capacityAt(floor) interpolates in log capital (Ratcliffe, Miranda and Ang 2017).
- Example: `CapacityCurve.compute(ctx, f, cfg, builder, new double[]{1e7, 1e8, 1e9}, 0.1).capacityAt(1.0)`.

## StrategyRunner (backtest)
- One `TradingStrategy`, one tape, three engines (`BAR_BACKTESTER`, `TICK_BACKTESTER`, `PAPER_GATEWAY`). Parity contract: close fills, no costs, all-in long-only. Tick and paper trade whole shares, so they match the bar engine exactly when cash/price is whole, and match each other always.
- Example: `StrategyRunner.run(StrategyRunner.Engine.PAPER_GATEWAY, strategy, series, 1e6, tmpDir).fills()`.

## AlphaReport additions
- `icTable` / `formatIcTable` (mean IC per factor per horizon), `costBreakdown(Result)`, `exposureSummary(Traced)` = [gross, net, long, short, names held].

## Not done in W3.7
- Standard errors for VarianceRatio, OU and Kalman (microstructure package, outside this worker's scope) and the screener rank-transform default (screener package, outside scope).
