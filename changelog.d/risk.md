### Fixed
- Expected shortfall now uses the Acerbi-Tasche estimator (mean of the worst (1-c)n observations with a fractional boundary weight) everywhere: `VarEngine` (historical, Monte Carlo, full revaluation), `FrtbEs.es975`, `RiskMetrics.conditionalVar`/`expectedShortfall`, `SimulationResult` and `HedgingErrorDistribution`. The old estimator also averaged the VaR observation and read about 4% low at n = 1000 and 17% low at 250 days. Test pin changed: ES 97.5% of losses 1..1000 is 988.0, not 987.5.
- `FrtbEs.stressCalibratedEs` follows BCBS MAR33: ES = ES_R,S x max(1, ES_F,C / ES_R,C), and rejects a reduced factor set that explains less than 75% of full-set current ES. The previous version scaled current full-set ES by a stressed/current ratio floored at 1, which is not the rule. Test pins changed from 150/100 to the MAR33 values.
- `DeltaHedger` credits the continuous carry (dividend yield or foreign rate) on the hedge position. With q != 0 the mean replication P&L was biased by roughly the dividends on the delta (about -0.9 on a 7.9 premium at q = 3%).
- `WhalleyWilmott.bandHalfWidth` has an overload with the e^{-r(T-t)} factor of the Whalley-Wilmott band, and the units of the risk aversion are documented.
- `CentralRiskBook.report` uses delta-gamma (Cornish-Fisher) VaR and ES: dollar-gamma rows are moved onto their underlying factors instead of being treated as linear exposures. A book without options gives the same numbers as before.
- `Garch11`, `GjrGarch11` and `Egarch11` fit by true maximum likelihood (Nelder-Mead on transformed parameters, several starts) instead of a coarse grid with variance targeting; omega is estimated.
- `PortfolioOptimizer` (min variance, frontier, max Sharpe) and `ConstrainedPortfolioOptimizer` solve the convex QP exactly by an active-set method; the random search was accurate only to about 2% in weights, and frontier points now hit their target return exactly. The seed constructor argument is ignored.
- `ConstrainedPortfolioOptimizer` projection onto the bounded simplex is the Euclidean projection.
- `MonteCarloSimulator.simulatePortfolio` simulates buy-and-hold lognormal assets and no longer clamps the portfolio at zero; its means and covariance are now those of daily log returns. `simulate` draws the exact lognormal terminal value.
- `SimulationResult` VaR and CVaR use the same tail definition, so CVaR is never below VaR.
- `EwmaVolatility.variances` no longer seeds with the full-sample variance (look-ahead); it uses causal bias-corrected weights, and an explicit-seed overload is added.
- `HarRv` fits by Householder QR on scaled regressors instead of the normal equations.
- `VarEngine.monteCarloVar`, `GaussianCopula` and `MonteCarloSimulator` share one PSD Cholesky (`PsdMatrices.choleskyPsd`) that accepts rank-deficient matrices and rejects indefinite ones.
- `RegimeDetector` validates that returns are finite, works with emission densities in log space (an extreme return can no longer underflow the filter), and runs five starts.
- `HedgeOptimizer` finishes on the exact support solution and adds a tiny ridge, so near-collinear hedge instruments converge instead of throwing.
- `RiskParityOptimizer` uses Spinu's convex formulation with damped Newton steps instead of the multiplicative update that could oscillate.
- `BlackLitterman` validates pick-matrix widths, tau and view variances, and computes the update in view space so Sigma need not be invertible.
- `PortfolioRiskAnalyzer` copies its input, requires equal-length finite return rows, and takes a risk-free rate for the Sharpe ratio.

### Changed
- `GaussianCopula.sample`/`sampleT` take a `java.util.random.RandomGenerator` (a `Random` still works).
- `OptionsBook.PnlExplain` has vanna and volga terms; the record constructor changed.
- `RegimeDetector.RegimeModel` has a new `filteredHighVolProbability` component; the class doc now says the smoothed path is for ex-post labelling only.
- `CentralRiskBook.CrbReport.var/es` are delta-gamma numbers.
- `FixAnalyzer` is described as WM/R-style rather than "per WM/R methodology".
- `BestExecutionAnalyzer.BestExecutionReport` has a quantity-weighted slippage component; the record constructor changed.
- `CointegrationTest.engleGranger(a, b, lags)` uses ADF lags and MacKinnon (2010) finite-sample critical values; the two-argument form is unchanged.
- `PortfolioOptimizer.maxSharpe` throws when no asset has an expected return above the risk-free rate.

### Added
- `risk.TailRisk`: shared empirical VaR and Acerbi-Tasche ES.
- `risk.PsdMatrices`: PSD Cholesky, Jacobi eigen-decomposition, nearest PSD matrix and Higham (2002) nearest correlation matrix.
- `CovarianceShrinkage.constantCorrelation`: Ledoit-Wolf shrinkage to the constant-correlation target; the population-vs-sample divisor is documented.
- `VarBacktest.acerbiSzekelyZ2` with the published 5% and 0.01% thresholds.
- `ExtremeValueTheory.fitPotMle` (GPD maximum likelihood with standard errors), `gpdLogLikelihood` and `meanExcess`.
- `VarEngine.filteredHistoricalVar` (filtered historical simulation with EWMA devolatilisation).
- `Garch11/GjrGarch11/Egarch11.estimate(returns, Innovation)` with Student-t innovations, observed-information and Bollerslev-Wooldridge robust standard errors.
- `HarRv.fitWithErrors` (Newey-West standard errors), `HarRv.fitLog` and `forecastLog` (log-HAR).
- `optimization.QuadraticProgram` (active-set convex QP) and `optimization.NelderMead`.
- `BlackLitterman.posterior` (posterior mean, covariance of the mean and Sigma + M) and `heLittermanOmega`.
- `RiskParityOptimizer.riskBudget` for unequal risk budgets.
- `MonteCarloSimulator.simulatePaths` and `price`: correlated multi-asset GBM paths with a payoff callback, antithetic variates and standard errors.
- `RegimeDetector.fit(returns, iterations, starts, seed)` and `RegimeDetector.viterbi`.
- `VolatilityForecaster.validate`: time-split out-of-sample comparison with EWMA and HAR-RV baselines.
- `MarketImpactPredictor.sweepProbability(qty, depth, slope)`.
- `FixAnalyzer.calculateFix(bids, offers)`: mid of separate bid and offer medians.
- `BestExecutionAnalyzer.implementationShortfall`: Perold delay/execution/opportunity decomposition.
- `CointegrationTest.adfTStatistic(series, lags)` and `criticalValue(level, T)`.
