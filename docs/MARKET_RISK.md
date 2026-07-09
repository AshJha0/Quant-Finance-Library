# Market Risk Modeling — the 14-Step Roadmap

Market risk modeling is far more than a daily VaR number: it is the complete
workflow from market data through pricing models, Greeks, volatility and
correlation models, VaR and Expected Shortfall, stress testing, and
Basel/FRTB compliance, into production. This page maps every step of that
workflow to the classes in this library — what exists, what each is for, and
(step 14 and the honest-gaps section) what a JDK-only library deliberately
does not pretend to ship.

Every class named here follows the house rules: tested behavior, documented
limits, deterministic results. Regulatory items are **styled after the
regulation, not certified** — the formulas are the regulation's and the
tests pin their arithmetic, but capital approval involves programs no
library can contain.

---

## 1. Market Data Collection

| Need | Where |
|---|---|
| Equity/FX/commodity bars | `data.CsvBarLoader`, `data.HttpBarFetcher`, `core.BarSeries` |
| Live streams | `feed.WebSocketFeed`, `marketdata.HftMarketDataBus`, FIX 35=W/X views |
| Tick history | `data.TickFileWriter/Reader` (QFLT), `data.TickCapture` |
| Yield curves | `rates.YieldCurve` (zero rates, par-swap bootstrap) |
| Implied volatility / option data | `pricing.VolSurface` (pillar smiles), `fx.FxVolSurface` (delta-quoted) |
| FX forwards/swap points | `fx.SwapPointsCurve`, `pricing.ForwardCurve` |

*Gap (deliberate):* credit-spread market data has no dedicated loader —
represent spreads as a `BarSeries` or a `YieldCurve` spread over the
risk-free curve.

## 2. Data Cleaning & Processing

| Need | Where |
|---|---|
| Corporate action adjustment | `data.CorporateActions` (back-adjustment) |
| Alignment / missing values | `data.SeriesAligner` (intersect, union + forward-fill) |
| Return calculation | `core.BarSeries.returns()/logReturns()` |
| Volatility estimation | step 6 below |
| Curve construction | `rates.YieldCurve.bootstrapAnnualParSwaps` |
| Survivorship honesty | `data.PointInTimeUniverse` + delisting returns |

## 3. Risk Factor Identification

Factors in this library are **dense vectors with a documented order** —
equity returns, rate moves, FX returns, commodity returns, vol points —
consumed by the covariance/VaR/stress layers below. `risk.Pca` is the
factor-count honesty check: how many of your named factors are real
(a 12-tenor curve is ~3 factors: level, slope, curvature). Correlation and
basis risk live in the covariance matrix itself (`microstructure.EwmaCovariance`
streaming, or a sample covariance).

## 4. Pricing Models

| Model | Where |
|---|---|
| Black-Scholes (+ Garman-Kohlhagen) | `pricing.BlackScholes` |
| Black-76 (futures/forwards options) | `pricing.Black76` |
| Binomial trees (American) | `pricing.BinomialTree` |
| Monte Carlo | `simulation.MonteCarloSimulator`, `pricing.Autocallable`, `pricing.Heston.callMonteCarlo` |
| Heston stochastic vol | `pricing.Heston` (semi-analytic, little-trap form, MC cross-checked) |
| SABR | `pricing.SabrModel` (Hagan 2002 + calibration) |
| Vasicek / CIR / Hull-White | `rates.ShortRateModels` (closed-form bonds + simulation steps) |
| Exotics | `pricing.DigitalOption/TouchOption/BarrierOption/VannaVolga/Autocallable` |

## 5. Sensitivity Analysis (Greeks)

| Greek | Where |
|---|---|
| Delta, Gamma, Vega, Theta, Rho | `pricing.BlackScholes.greeks` |
| Vanna, Volga, cross-gamma | `pricing.HigherOrderGreeks` |
| Tick-frequency Greeks | `pricing.IncrementalGreeks` (delta-gamma Taylor per tick) |
| DV01 / duration / convexity | `rates.BondPricer` |
| Key-rate durations, PV01 by node | `rates.KeyRateDurations` (slices sum to the parallel DV01 — tested) |
| Book-level scenario grids | `hedging.OptionsBook` |

## 6. Volatility Modeling

| Model | Where |
|---|---|
| Historical / EWMA | `risk.RiskMetrics.volatility`, `volatility.EwmaVolatility` |
| GARCH(1,1) | `volatility.Garch11` (MLE, variance targeting) |
| GJR-GARCH (leverage effect) | `volatility.GjrGarch11` — γ ≈ 0 on a symmetric series is itself the finding |
| Jump-robust realized vol | `microstructure.JumpRobustVolatility` (bipower) |
| Intraday seasonality | `microstructure.VolatilityCurve` |
| Stochastic volatility | `pricing.Heston` |

*Gap (deliberate):* EGARCH is omitted — GJR captures the same asymmetry
with a better-behaved likelihood surface for a grid fitter; add EGARCH only
if log-variance dynamics are specifically required.

## 7. Correlation & Dependence Modeling

| Tool | Where |
|---|---|
| Pearson | `risk.RiskMetrics.correlation` |
| Spearman, Kendall's τ | `risk.Dependence` (+ the τ → Pearson elliptical bridge) |
| PCA | `risk.Pca` (Jacobi, eigenvalues descending, explained variance) |
| Gaussian / Student-t copulas | `risk.GaussianCopula` (t has the tail dependence Gaussian lacks — tested) |
| Streaming covariance | `microstructure.EwmaCovariance` (RiskMetrics-style, PSD-preserving) |

## 8. Value at Risk

| Method | Where |
|---|---|
| Single-series historical/parametric/CVaR | `risk.RiskMetrics` |
| Portfolio delta-normal (variance-covariance) | `risk.VarEngine.deltaNormalVar/Es` |
| Portfolio Monte Carlo | `risk.VarEngine.monteCarloVar` (agrees with delta-normal on linear books — tested) |
| Delta-gamma (Cornish-Fisher) | `risk.VarEngine.deltaGammaVar/Es` (short gamma worsens the tail — tested) |
| Historical simulation (portfolio) | `risk.VarEngine.historicalVar` |

Full-revaluation VaR = the Monte Carlo harness plus your pricer per
scenario; the linear engine is the template.

## 9. Tail Risk

| Tool | Where |
|---|---|
| Expected Shortfall | every `VarEngine` method returns it beside VaR; `RiskMetrics.expectedShortfall` |
| EVT / POT / GPD | `risk.ExtremeValueTheory` (PWM fit; refuses a finite ES when ξ ≥ 1 — the honest failure) |

## 10. Stress Testing & Scenario Analysis

| Tool | Where |
|---|---|
| Named/hypothetical scenarios | `risk.StressTester.scenarioPnl` (delta and delta-gamma) |
| Historical templates | `blackMonday1987/lehman2008/covidMarch2020` — stylized STARTING POINTS, edit them |
| Sensitivity ladders | `risk.StressTester.sensitivityLadder` |
| Reverse stress | `risk.StressTester.reverseStress` — closed form, reports the breaking move's implausibility in sigmas |

## 11. Backtesting

| Test | Where |
|---|---|
| Kupiec POF | `risk.VarBacktest` |
| Christoffersen independence + conditional coverage | `risk.VarBacktest` |
| Basel traffic light | `risk.FrtbEs.TrafficLight` (≤4 green, 5–9 amber, ≥10 red) |
| P&L attribution | `risk.PnlAttribution` (step 12) |

## 12. Regulatory Framework (Basel III / FRTB)

| Piece | Where | Status |
|---|---|---|
| ES 97.5% | `risk.FrtbEs.es975` | formulas per MAR33, **not certified** |
| Liquidity-horizon cascade | `risk.FrtbEs.liquidityHorizonEs` | tested arithmetic |
| Stressed calibration (IMCC ratio, floored at 1) | `risk.FrtbEs.stressCalibratedEs` | tested |
| P&L Attribution Test (Spearman + KS, green/amber/red) | `risk.PnlAttribution` | per MAR32 thresholds |
| Standardized Approach (SA) | — | **out of scope**: a large regulatory lookup-table exercise (risk weights, buckets, correlation scenarios) with no algorithmic content; implementing it partially would be worse than not at all |
| NMRF capital | — | **out of scope**: requires observability data governance, not code |

## 13. Model Validation

The validation toolkit is the library's existing honesty machinery applied
to risk models: benchmarking = the cross-checks every pricer ships with
(Heston vs its own MC, exotics vs MC, zero-vol exact cases); calibration
testing = `VarBacktest` + `PnlAttribution`; challenger models = fit both
`Garch11` and `GjrGarch11` and compare likelihoods (tested pattern);
sensitivity analysis = `StressTester.sensitivityLadder`. SR 11-7 governance
(documentation, independent review, effective challenge) is a PROCESS —
what a library can contribute is that every model documents its
assumptions and limits in javadoc, which this one does.

## 14. Production Deployment

| Piece | Where |
|---|---|
| Real-time risk gate | `trading.HftRiskGate` (~3 ns/check), `trading.GlobalRiskAggregator` |
| Live dashboards | `trading.TradingDashboard` |
| Automated reporting | `report.ReportGenerator` (HTML/PDF/XLSX/CSV) |
| Portfolio aggregation | `risk.PortfolioRiskAnalyzer`, `execution.PortfolioExecutor` |
| Model state across sessions | `persist.Checkpoint` |
| Model monitoring | `VarBacktest` + `PnlAttribution` run on a schedule ARE the monitor |

*Gap (deliberate):* cloud deployment, schedulers and data pipelines are
infrastructure, not library code. Everything here runs in a plain JVM.

---

## Reading order for the risk-curious

`RiskMetrics` → `VarEngine` → `VarBacktest` → `ExtremeValueTheory` →
`StressTester` → `FrtbEs`/`PnlAttribution`, with `Dependence`/`Pca`/
`GaussianCopula` as the correlation toolkit and step 4's pricers underneath.
The from-zero explanations live in [LEARN.md](LEARN.md) §9 (risk) and §10
(derivatives); every behavior claimed above is pinned in
`MarketRiskTest` and `MarketRiskPricingTest`.
