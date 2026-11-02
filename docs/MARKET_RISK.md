# Market risk workflow

A market-risk process runs from market data through pricing models, sensitivities,
volatility and correlation models, VaR and expected shortfall, stress testing,
backtesting and regulatory measures. This page maps each of fourteen steps to the
classes in this library and lists what is not implemented.

Regulatory pieces follow the published text (Basel Committee MAR31-33) and their
arithmetic is tested, but they are not certified for capital use. An internal-model
approval also depends on data, governance and validation processes that a library
cannot supply.

Convention: unless a class says otherwise, VaR and ES are reported as positive
numbers for losses.

---

## 1. Market data

| Need | Where |
|---|---|
| Equity, FX and commodity bars | `data.CsvBarLoader`, `data.HttpBarFetcher`, `core.BarSeries` |
| Live streams | `feed.WebSocketFeed`, `marketdata.HftMarketDataBus`, `fix.FixMarketDataView` (35=W/X) |
| Tick history | `data.TickFileWriter`/`TickFileReader` (QFLT), `data.TickCapture` |
| Yield curves | `rates.YieldCurve` (zero rates; `bootstrapAnnualParSwaps` from annual par swaps, single curve); `rates.MultiCurveBootstrap` (OIS discount and projection curves) with `rates.MultiCurveRisk` for bucketed, key-rate and parallel DV01 |
| Implied volatility | `pricing.VolSurface`, `fx.FxVolSurface` (delta-quoted) |
| FX forwards and swap points | `fx.SwapPointsCurve`, `pricing.ForwardCurve` |

There is no dedicated loader for credit-spread data; hold spreads in a `BarSeries`,
or bootstrap a `credit.CreditCurve` from CDS par spreads.

## 2. Cleaning and preparation

| Need | Where |
|---|---|
| Splits and cash dividends | `data.CorporateActions` (back-adjustment) |
| Alignment and missing values | `data.SeriesAligner` (intersection, or union with forward fill; filled bars are flagged as synthetic and skipped by volatility and impact estimators) |
| Returns | `core.BarSeries.returns()`, `logReturns()` |
| Liquidity from bars | `microstructure.LiquidityMeasures` (Roll, Corwin-Schultz, Amihud) |
| Survivorship | `data.PointInTimeUniverse` with delisting and merger events |

## 3. Risk factors

Risk factors are dense vectors with a documented order (equity returns, rate moves,
FX returns, volatility points) that the covariance, VaR and stress layers consume.
`risk.Pca` shows how many independent factors the data supports; a 12-tenor curve is
usually about three (level, slope, curvature). `crb.CentralRiskBook` is a worked
decomposition of equity and FX products onto named factors
([CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md)).

## 4. Pricing models

| Model | Where |
|---|---|
| Black-Scholes-Merton, Garman-Kohlhagen | `pricing.BlackScholes` |
| Black-76 | `pricing.Black76` |
| Bachelier (normal) | `pricing.Bachelier` |
| Binomial trees (American) | `pricing.BinomialTree` |
| Monte Carlo | `simulation.MonteCarloSimulator` (`simulatePaths`, `price`), `pricing.Autocallable`, `pricing.Heston` |
| Heston | `pricing.Heston` (semi-analytic, little-trap form of Albrecher et al. 2007) |
| SABR | `pricing.SabrModel` (Hagan et al. 2002, Levenberg-Marquardt calibration) |
| Short-rate models | `rates.ShortRateModels` (Vasicek, CIR, Hull-White) |
| Swaptions, caps and floors | `rates.RatesOptions` (lognormal, shifted lognormal, normal) |
| Exotics | `pricing.BarrierOption`, `DigitalOption`, `TouchOption`, `AsianOption`, `VannaVolga`, `Autocallable` |

## 5. Sensitivities

| Measure | Where |
|---|---|
| Delta, gamma, vega, theta, rho | `pricing.BlackScholes.greeks` |
| Vanna, volga, cross-gamma | `pricing.HigherOrderGreeks` |
| Per-tick Greeks | `pricing.IncrementalGreeks` (delta-gamma Taylor update with theta between re-anchors) |
| Duration, convexity, DV01 | `rates.BondPricer`, `rates.SwapPricer` |
| Key-rate durations | `rates.KeyRateDurations` (slices sum to the parallel DV01) |
| Book scenario grids and P&L explain | `hedging.OptionsBook` (delta, gamma, vega, vanna, volga, theta) |

## 6. Volatility

| Model | Where |
|---|---|
| Historical, EWMA | `risk.RiskMetrics.volatility`, `volatility.EwmaVolatility` (causal, bias-corrected start) |
| GARCH(1,1), GJR-GARCH, EGARCH | `volatility.Garch11`, `GjrGarch11`, `Egarch11`. Maximum likelihood (Nelder-Mead on transformed parameters, several starts), omega estimated. `estimate(returns, Innovation)` adds Student-t innovations and Bollerslev-Wooldridge (1992) robust standard errors. EGARCH forecasts one step only, because the log recursion forecasts the median rather than the mean beyond that. |
| HAR-RV | `volatility.HarRv` (Corsi 2009), with Newey-West errors and a log variant |
| Jump-robust realised volatility | `microstructure.JumpRobustVolatility` (bipower variation) |
| Intraday seasonality | `microstructure.VolatilityCurve` |
| Model-free volatility index | `volatility.VolatilityIndex` (CBOE-style variance replication, single expiry) |
| Systematic and idiosyncratic split | `volatility.VolatilityDecomposition` |
| Out-of-sample comparison | `ml.VolatilityForecaster.validate` (against EWMA and HAR-RV baselines) |

## 7. Correlation and dependence

| Tool | Where |
|---|---|
| Pearson | `risk.RiskMetrics.correlation` |
| Spearman, Kendall's tau | `risk.Dependence` |
| PCA | `risk.Pca` (Jacobi) |
| Gaussian and Student-t copulas | `risk.GaussianCopula` |
| Shrinkage | `risk.CovarianceShrinkage` (Ledoit-Wolf 2004 to the scaled identity, and to constant correlation) |
| PSD repair | `risk.PsdMatrices` (PSD Cholesky, nearest PSD matrix, Higham 2002 nearest correlation matrix) |
| Streaming covariance | `microstructure.EwmaCovariance` |

## 8. Value at risk

| Method | Where |
|---|---|
| Single series: historical, parametric, CVaR | `risk.RiskMetrics` |
| Delta-normal | `risk.VarEngine.deltaNormalVar`, `deltaNormalEs` |
| Delta-gamma (Cornish-Fisher) | `risk.VarEngine.deltaGammaVar`, `deltaGammaEs` |
| Monte Carlo | `risk.VarEngine.monteCarloVar` |
| Historical simulation | `risk.VarEngine.historicalVar` |
| Filtered historical simulation | `risk.VarEngine.filteredHistoricalVar` (EWMA devolatilisation; Hull and White 1998, Barone-Adesi et al. 1999) |
| Full revaluation | `risk.VarEngine.fullRevaluationVar` (each scenario through a pricer callback you supply) |
| Allocation | `risk.ComponentVar` (Euler components sum to portfolio VaR) |

Full revaluation takes scenario rows from the caller: historical rows for historical
simulation, or simulated rows for Monte Carlo.

## 9. Tail risk

**Expected shortfall.** Every empirical VaR/ES pair in the library comes from
`risk.TailRisk`. VaR is the lower empirical quantile `L(ceil(c n))` of the sorted
losses. ES is the Acerbi-Tasche (2002) estimator: the mean of the worst
`w = (1 - c) n` losses, with a fractional weight on the boundary observation:

```
ES = ( L(n) + ... + L(n-k+1) + (w - k) L(n-k) ) / w,    k = floor(w)
```

Example: losses 1, 2, ..., 1000 at c = 97.5 % give w = 25, VaR = 975 and
ES = mean(976..1000) = 988.0. An estimator that also averages the VaR observation
gives 987.5; at a 250-day window that bias is about 17 % of the tail spread.

**Extreme value theory.** `risk.ExtremeValueTheory` fits a generalised Pareto
distribution to threshold exceedances by probability-weighted moments (`fitPot`) or
maximum likelihood with standard errors (`fitPotMle`), with `meanExcess` for threshold
choice. ES is refused when the shape parameter is 1 or more, because the tail mean is
then infinite.

## 10. Stress testing

| Tool | Where |
|---|---|
| Scenario P&L (delta and delta-gamma) | `risk.StressTester.scenarioPnl` |
| Historical templates | `StressTester.blackMonday1987`, `lehman2008`, `covidMarch2020` (stylised shocks; edit them for your factors) |
| Sensitivity ladders | `StressTester.sensitivityLadder` |
| Reverse stress | `StressTester.reverseStress` (closed form for a linear book; reports the move's size in standard deviations) |

## 11. Backtesting

| Test | Where |
|---|---|
| Kupiec proportion of failures | `risk.VarBacktest` |
| Christoffersen independence and conditional coverage | `risk.VarBacktest` |
| Acerbi-Szekely Z2 for ES | `risk.VarBacktest.acerbiSzekelyZ2` (thresholds -0.70 at 5 % and -1.80 at 0.01 %, from Acerbi and Szekely 2014) |
| Basel traffic light | `risk.FrtbEs.TrafficLight` (0-4 green, 5-9 amber, 10 or more red, over 250 days at 99 %) |
| P&L attribution test | `risk.PnlAttribution` (step 12) |

## 12. Regulatory measures (FRTB internal models)

| Piece | Where | Notes |
|---|---|---|
| ES at 97.5 % | `risk.FrtbEs.es975` | Acerbi-Tasche estimator (step 9) |
| Liquidity-horizon cascade | `risk.FrtbEs.liquidityHorizonEs` | MAR33 cascade over the liquidity horizons |
| Stressed calibration | `risk.FrtbEs.stressCalibratedEs` | See below |
| P&L attribution test | `risk.PnlAttribution` | Spearman correlation and Kolmogorov-Smirnov statistic with the MAR32 zone thresholds |

Stressed calibration follows MAR33:

```
ES = ES_R,S * max(1, ES_F,C / ES_R,C)
```

`ES_R,S` is the ES of the reduced factor set over the stressed period, `ES_F,C` the
ES of the full factor set over the current 12 months, and `ES_R,C` the ES of the
reduced set over the current period. The ratio is floored at 1. The method throws when
the reduced set explains less than 75 % of the full-set current ES
(`ES_R,C < 0.75 ES_F,C`). Arguments are in the order
`stressCalibratedEs(esCurrentFull, esStressedReduced, esCurrentReduced)`.

Example: `ES_F,C = 100`, `ES_R,C = 80`, `ES_R,S = 150` give `150 * 1.25 = 187.5`.

## 13. Model validation

The validation tools are the ones above applied to the models themselves: pricers are
tested against independent methods (Heston against its own Monte Carlo, exotics
against simulation, closed-form limits at zero volatility); calibration is tested with
`VarBacktest` and `PnlAttribution`; challenger models are compared by likelihood
(`Garch11` against `GjrGarch11`, with `InformationCriteria`); sensitivity is explored
with `StressTester.sensitivityLadder`. Governance in the sense of SR 11-7
(documentation, independent review, effective challenge) is an organisational process.
Each class documents its assumptions and limits in its javadoc.

## 14. Running it

| Piece | Where |
|---|---|
| Pre-trade risk gate | `trading.HftRiskGate`, firm-wide `trading.GlobalRiskAggregator` |
| Dashboards | `trading.TradingDashboard` |
| Reports | `report.ReportGenerator` (HTML, PDF, XLSX, CSV) |
| Portfolio aggregation | `risk.PortfolioRiskAnalyzer`, `crb.CentralRiskBook` |
| State across sessions | `persist.Checkpoint` |
| Monitoring | `VarBacktest` and `PnlAttribution` run on a schedule |

Schedulers, data pipelines and deployment are infrastructure and are not part of the
library.

---

## Not implemented

- Certification of the FRTB standardised approach (`risk.FrtbSa`, `FrtbDrc`,
  `FrtbRrao`, following BCBS d457 with MAR paragraph citations), and the default
  risk charge for securitisations. Since 2.3 the supervisory correlations for CSR
  buckets, GIRR inflation and cross-currency basis, equity repo, CTP and
  securitisation non-CTP are built in.
- The risk-factor eligibility test. NMRF stress capital (`risk.FrtbNmrf`) aggregates
  scenario charges the caller computes (MAR33.17).
- ISDA SIMM: not included; ISDA licenses SIMM for use.
- CVA capital. SA-CCR (`risk.SaCcr`) haircuts follow the Basel II table (BCBS 128
  para 151, currency add-on para 152) by default, or CRE22.49 with
  `SaCcrHaircuts.Table.CRE22`; the CRE22.50 table and securitisation collateral are
  not implemented.
- xVA beyond CVA, DVA (unilateral and first-to-default bilateral) and wrong-way CVA,
  unilateral or bilateral, in `credit.CvaApproximator`.
  `credit.MultiCurrencyExposureSimulator` has multi-curve swaps, foreign-currency
  swaps, several FX pairs with stochastic foreign rates and an optional stochastic
  basis for domestic swaps; foreign swaps do not see the basis yet.
- Smile risk on the SVI, SSVI and local-volatility surfaces is not wired into the VaR
  engines; scenario revaluation on them is left to the caller.
- A scenario or market-data store; scenarios are arrays supplied by the caller.

## Reading order

`TailRisk` and `RiskMetrics`, then `VarEngine`, `VarBacktest`,
`ExtremeValueTheory`, `StressTester`, and `FrtbEs` with `PnlAttribution`.
`Dependence`, `Pca`, `GaussianCopula` and `PsdMatrices` are the correlation tools,
and the pricers of step 4 sit underneath. [LEARN.md](LEARN.md) explains the ideas from
first principles.
