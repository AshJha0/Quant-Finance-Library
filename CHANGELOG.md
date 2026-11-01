# Changelog

All notable changes to this project are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/) from 2.0.0 onward.

Entries for 1.x releases are archived in [docs/history/CHANGELOG-1.x.md](docs/history/CHANGELOG-1.x.md).

## [Unreleased]

## [2.1.0] - 2026-11-01

Minor release. Everything is additive: code written for 2.0 compiles and behaves the same, apart from the notes in [docs/MIGRATION_2.1.md](docs/MIGRATION_2.1.md). An API-compatibility check against 2.0.0 now runs in CI.

### Rates

#### Added

- `rates.DiscountCurve`: dated discount curve on ACT/365F time with log-linear discount or Hagan-West (2006) monotone-convex interpolation.
- `rates.MultiCurveBootstrap`: OIS discount curve and projection curves (discounted on OIS), pillar-by-pillar Newton then a global Newton solve; every input reprices to 1e-12.
- Curve instruments `Deposit`, `Fra`, `RateFuture` (Hull-White convexity adjustment), `OisSwap` and `InterestRateSwap` behind the sealed `CurveInstrument` interface, using `DayCount` and `BusinessCalendar` schedules.
- `rates.MultiCurveRisk`: bucketed, Ho (1992) key-rate and parallel DV01 by bumping quotes and rebuilding the curve pair; `CurvePair` record.
- `SwapPricer` overloads (annuity, par rate, payer PV, DV01) and `RatesOptions` overloads (swaption, cap/floor) taking a discount curve and a projection curve.

### Credit and counterparty risk

#### Added

- `credit.IsdaCdsModel` and `credit.IsdaCurve`: ISDA CDS Standard Model with IMM dates, semi-annual maturity roll, T+1 step-in and T+3 cash settlement, ACT/360 accrual, accrual on default, closed-form segment integrals, clean and dirty upfront, par spread, quoted spread to upfront conversion (40% recovery, 100bp/500bp coupons) and a hazard bootstrap.
- `credit.ExposureSimulator` and `credit.ExposureProfile`: Monte Carlo netting-set exposure for interest-rate swaps (Hull-White one-factor, exact simulation, closed-form bonds) and FX forwards (GBM correlated with rates), with EE, ENE, PFE and EPE, and a two-way CSA with threshold, minimum transfer amount and margin period of risk.
- `CvaApproximator.cva(ExposureProfile, ...)`, `dva(...)` and `cvaWrongWay(...)`: CVA and DVA from a simulated profile, and wrong-way CVA through a Gaussian copula between default and a market factor.
- `risk.SaCcr` and `risk.SaCcrTrade`: SA-CCR (BCBS 279) replacement cost (margined and unmargined), add-ons for interest rates, FX, credit, equity and commodities, the multiplier and EAD; reproduces the Annex 4a examples.

#### Fixed

- `CvaApproximator.cvaWrongWay` now ties each bucket's default driver to the factor move that drives that bucket's exposure (level at the bucket date, or the increment over the margin period of risk under a CSA; Hull-White 2012), so wrong-way CVA on a collateralised payer swap with positive rho is no longer below the independent CVA.

### Volatility surfaces

#### Added

- `pricing.SviSlice`: raw SVI (Gatheral 2004) with the Gatheral-Jacquier density factor g(k) and Levenberg-Marquardt calibration with parameter bounds, caller weights and a butterfly penalty.
- `pricing.SsviSurface`: SSVI with power-law phi; constructor enforces the Gatheral-Jacquier theorem 4.1/4.2 bounds, calibration of rho, eta, gamma to vol quotes.
- `pricing.SviSurface`: per-expiry SVI slices interpolated linearly in total variance at fixed log-moneyness.
- `pricing.TotalVarianceSurface`: common surface interface with implied vol, price, calendar and butterfly grid checks.
- `pricing.DupireLocalVol`: Dupire local vol in Gatheral's total-variance form from any total-variance surface.
- `pricing.LocalVolMonteCarlo` (log-Euler, seeded, antithetic) and `pricing.LocalVolPde` (Crank-Nicolson with Rannacher start) European pricers under local vol.
- `pricing.VannaVolga`: exact 3x3 vega/vanna/volga weights (`exactWeights`, `weightsFor`, `priceExact`), `pillarSmileCost` and pillar accessors.
- `fx.VannaVolgaTouch`: vanna-volga adjusted one-touch with survival-probability weighting.

### Exotics

#### Added

- `TouchOption.oneTouchPayAtHit`: one-touch paying at the hitting time (Reiner-Rubinstein 1991 rebate formula), up or down, domestic and foreign rates.
- `TouchOption.doubleNoTouch`, `doubleOneTouch` and `doubleNoTouchProbability`: double-barrier touches by the Ikeda-Kunitomo (1992) image series, truncated at |n| <= 50.
- `HestonCos`: Heston European pricer by the Fang-Oosterlee (2008) COS method with analytic delta, gamma, dV/dv0 and full parameter gradient.
- `HestonCalibrator`: Levenberg-Marquardt calibration of Heston to an implied-vol grid using the analytic COS gradient.
- `AsianOption.turnbullWakemanSeasoned`, `curranPrice` and `levyPrice`: arithmetic Asians with arbitrary and partly fixed schedules (Turnbull-Wakeman 1991, Curran 1994, Levy 1992).
- `DividendTree`: European and American options with discrete cash dividends on a recombining CRR tree (Vellekoop-Nieuwenhuis 2006).
- `DividendSchedule.exTime(i)` and `amount(i)` accessors.

### Alpha, portfolio and research

#### Added

- `risk.FactorRiskModel`: market, sector and style factor risk model; constrained WLS factor returns (sqrt-cap weights, cap-weighted sector constraint), EWMA factor covariance with Newey-West and volatility regime adjustment, EWMA specific risk with Bayesian size-bucket shrinkage, and `Decomposition` (exposures, factor/specific/total risk, marginal and percent contributions).
- `alpha.AlphaRiskModels`: builds a `FactorRiskModel` from an `AlphaContext` (SIZE, MOMENTUM, LOW_VOL and, with fundamentals, VALUE styles) without look-ahead.
- `AlphaBacktester.run(ctx, factor, config, RiskOptions)`: the turnover-aware ADMM optimiser on the rebuilt risk model as the construction, with an automatic %ADV trade limit; the existing overloads keep the z-score construction.
- `AlphaBacktester.runTraced` and `AlphaBacktester.Traced`: per-bar book, name P&L, cost and financing that sum to the net return exactly.
- `PortfolioBacktester.Participation` and a `run` overload with a per-bar participation cap that works unfinished targets over later bars.
- `alpha.AlphaCombiner`: horizon-matched, decay-aware IC weights with per-date Gram-Schmidt orthogonalisation and shrinkage toward equal weights.
- `alpha.BacktestAttribution`: per-bar factor, specific, trading, cost and financing P&L with long/short leg and sector splits.
- `alpha.CapacityCurve`: net Sharpe against capital with square-root impact and the participation cap, and capacity at a Sharpe floor.
- `backtest.StrategyRunner`: one driver for a bar strategy on `Backtester`, `TickBacktester` and `PaperTradingGateway`, with a replay-equality test.
- `AlphaReport.icTable`, `formatIcTable`, `costBreakdown` and `exposureSummary`.

### Trading lane

#### Added

- `fix.FixSessionSchedule`: daily, overnight and weekly FIX session windows in a time zone, DST-aware.
- `fix.FixSessionOptions` and new `FixSession.overStream`/`initiate`/`accept` overloads: Logon refused outside the session window, Logout at the end of the window, and an end-of-period sequence reset (store roll) that also survives a restart.
- `FixSessionStore.sessionStartMillis`/`saveSessionStartMillis` (default no-op); `FileSessionStore` persists the period in `session.period`.
- NextExpectedMsgSeqNum(789) on Logon: sent when enabled, answered when the peer sends it, gaps recovered by replay instead of a ResendRequest exchange; `FixMessage.NEXT_EXPECTED_MSG_SEQ_NUM`.
- `trading.PositionLedger`: allocation-free fill ingestion over dense account/strategy/symbol ids, average-cost and FIFO realised P&L, mark-to-market unrealised P&L, contract multipliers, fees and aggregates.
- `trading.DropCopyReconciler`: compares booked fills with a drop copy and reports missing, extra, mismatched and duplicate executions plus the per-symbol position difference.
- `trading.LimitsConfig` (validated immutable limits with a plain-text format) and `trading.LimitsManager` (versioned changes to running `HftRiskGate`s with an audit trail and rollback).
- `HftRiskGate.limits(LimitsConfig)` and getters for every limit.
- `orderbook.AuctionBook`: opening and closing call auctions with indicative price, volume and imbalance, uncross by maximum volume, then minimum imbalance, then nearest reference price, price-time allocation and transfer of the residual into `HftOrderBook`.
- `AdaptiveSor.maxLatencyPenaltyBps`: optional cap on the latency term.

#### Changed

- `HftRiskGate` keeps its limits in one immutable object read once per check, so a check never sees a half-applied change; the existing setters keep their behaviour.
- `OrderBook.cancel` unlinks in O(1) instead of scanning the price level.

#### Fixed

- `AdaptiveSor`: every cost term is now in price units (the adverse-selection markout was converted to a fraction and scaled by the fee-adjusted price), and dark probes across all dark venues share one `maxDarkFraction` budget instead of each taking it.
- `AntiGamingJitter.jitterTimes`: bounds come from the original schedule only, so two neighbours can no longer end up 1 ns apart; schedules for a given seed differ from 2.0.0.

### Library structure

#### Added

- `stats.Stats`: one home for mean, sample and population variance and standard deviation, sample covariance, Pearson correlation, Hyndman-Fan type 7 and type 1 quantiles, median and average-tie midranks, each with its convention and edge cases stated.
- `module-info.java`: the library jar is now the named module `com.quantfinlib`, exporting every package that was public in 2.0 (`requires java.net.http`, `jdk.httpserver`, `static org.jspecify`); `examples` is not exported and stays out of the library jar.
- Library exception types in `core`: `QuantFinException` (marker interface), `WireProtocolException` (an `IllegalStateException`), `MalformedDataException` and `ConfigurationException` (both `IllegalArgumentException`s). `fix.FixParseException` and `data.TickFileReader.TruncatedTickFileException` implement the marker.

#### Changed

- `MathUtils.mean`, `variance`, `stdDev`, `stdDevP`, `stdDevSample`, `percentile`, `percentileSorted` and `covariance`, and `risk.Dependence.ranks`, delegate to `Stats`; results are unchanged bit for bit on valid input.
- `MathUtils.percentile` and `percentileSorted` now reject a probability outside [0, 1] or NaN with `IllegalArgumentException`; before, such a value threw `ArrayIndexOutOfBoundsException` or returned the minimum.
- `MathUtils.mean(v, from, to)` and the range standard deviations now throw `IndexOutOfBoundsException` for a range outside the array or with `from > to`.
- FIX BodyLength and SBE header framing faults throw `WireProtocolException`; CSV bar and universe file format faults throw `MalformedDataException`; `trading.LimitsConfig` faults throw `ConfigurationException`. Each is a subtype of the type thrown before, so existing catch blocks still work.
- Classes in exported packages that relied on the implicit default constructor now declare it explicitly (same signature).

#### Fixed

- `crb.InternalizationEngine`: the constructor Javadoc sat on a field and did not appear in the API docs.

### Build and tooling

#### Added

- API compatibility gate: the `api-compat` Maven profile (japicmp) compares the build against the released 2.0.0 jar and fails on binary-incompatible changes in the stable packages; reference packages are reported only. Runs in CI on Ubuntu.
- `scripts/fetch-api-baseline.sh` downloads the released jar used as the compatibility baseline.
- JSpecify `@NullMarked` on the stable packages: parameters and return values there are non-null unless marked `@Nullable`. JSpecify is a compile-time (provided, optional) dependency; the library still has no runtime dependencies.
- Mutation testing: the `mutation` Maven profile runs PIT on `pricing`, `risk`, `rates` and `volatility`; the nightly workflow publishes the per-package score and the HTML report.
- `scripts/run-benchmarks.sh` runs the four latency benchmarks three times with the documented JVM flags and prints the min / median / max table used in docs/BENCHMARKS.md.

### Documentation

#### Changed

- Docs: 43 passages in LEARN.md Part IV reworded where an earlier dash removal had left stacked colons.

#### Fixed

- Docs: FRTB citations now point at the exact paragraphs (MAR33.4 for the liquidity-horizon cascade, MAR33.5 and MAR33.6 for stressed calibration, the 75% test and the floor at 1).
- Docs: current exposure method cited to BCBS 128 Annex 4 and the April 1995 amendment; barrier citations consistently Haug (2007) section 4.17.1 with values from table 4-13.
- Docs: the corporate-actions sequence diagram in DIAGRAMS.md failed to render because of semicolons in message text; all 100 Mermaid diagrams now render with Mermaid 11.4.1.


## [2.0.0] - 2026-11-01

Major release. It corrects several pricing, risk and backtesting results, makes the trading lane fail safe, and moves the build to Java 25. Many results change value; see [docs/MIGRATION_2.0.md](docs/MIGRATION_2.0.md) before upgrading.

### Build, CI and tests

#### Changed
- The library jar no longer contains `com.quantfinlib.examples`; demos and hand-rolled benchmarks ship as a separate jar with classifier `examples`.
- Load, soak and wall-clock tests are tagged `load` or `timing` and skipped by the default build; `./mvnw -Pfull test` runs them, and so does the nightly workflow.
- Test classes now run in parallel, and every test has a 120-second timeout.
- `./mvnw verify` fails if line coverage falls below 90 % overall or 80 % in any package.
- CI runs `./mvnw verify` on Ubuntu and Windows with JDK 25, with read-only token permissions, a concurrency group and job timeouts.
- The release workflow refuses to publish when the tag does not match the pom version.

#### Added
- `-Xlint:all` compiler warnings, and an `analysis` profile running Error Prone and SpotBugs (warnings only for now).
- Nightly workflow for the tagged tests and static analysis.
- Dependabot for Maven and GitHub Actions, SECURITY.md, CONTRIBUTING.md, issue and pull request templates.

### Pricing, FX and rates

#### Fixed
- `QuantoOption` drifted the foreign asset at the domestic rate. It now takes a `foreignRate`: forward `S·exp((r_f − q − ρσ_Sσ_X)T)`, discounted at `r_d`. The test that pinned the old `r_f = r_d` identity was replaced by a closed-form pin with `r_f ≠ r_d` and a Monte Carlo check under the foreign measure.
- `MathUtils.normCdf` was the Abramowitz-Stegun approximation (7.5e-8 absolute error). It is now Cody's rational erfc algorithm, accurate to about 1e-15 relative down to x = −37.5. `normInv` gains a Halley refinement step and is accurate to double precision.
- `BlackScholes.impliedVol` and `Black76.impliedVol` used 100-200 step bisection. They now use a safeguarded Newton solver on the normalised Black function (`ImpliedVolatility`) that inverts the out-of-the-money option, and recover σ to near machine precision in deep wings and at very short and long expiries. A price equal to intrinsic now gives 0, not NaN.
- `BlackScholes` delta, gamma, vega, theta and rho returned NaN or ±Inf at zero volatility. They now return the deterministic limits.
- `BinomialTree` returned NaN silently when volatility was zero; it now throws.
- `FxVolSurface` treated the broker (market strangle) butterfly as the smile butterfly. By default it now solves for the smile butterfly that reprices the market strangle (Clark 2011), for 25-delta and 10-delta jointly. Use `ButterflyQuote.SMILE_STRANGLE` for the old reading.
- `BusinessCalendar` schedules drifted to the 28th after a February coupon because each date was stepped from the previous one. Dates are now computed from a fixed anchor.
- `BondPricer` date-based pricing paid day-count-weighted coupons (2.975/3.025 on a 6% ACT/ACT bond) and discounted with year fractions. It now uses the street convention: round coupons `c/f`, ACT/ACT ICMA accrual and fractional-period exponents. It reproduces Microsoft's published PRICE and YIELD examples.
- `VannaVolga.impliedVol` inverted a call at every strike, losing accuracy on the put wing. It now inverts the out-of-the-money option, and beyond the 10-delta strikes it holds the vol flat or continues it smoothly instead of using the unbounded quadratic weights.
- `SabrModel.Params.rmse` was documented in vol points but returned in decimal vol; the documentation now says decimal vol.
- `ForwardCurve.forward` threw a NullPointerException on a curve with no points, and `impliedRateDifferential(0)` returned NaN. Both now throw clear exceptions.
- `MathUtils.variance`, `stdDev`, `stdDevSample` and `stdDevP` returned 0 for too few observations. They now return NaN.

#### Changed
- `QuantoOption.price` and `quantoForward` signatures now include `foreignRate` (breaking).
- `BondPricer.dirtyPrice`, `cleanPrice` and `accruedInterest` drop the calendar and roll arguments, since the street convention discounts on unadjusted dates. They gain an optional end-of-month flag (breaking).
- `BarrierOption` regular-barrier methods now price reverse barriers instead of throwing. Breached barriers still throw.
- `SabrModel.calibrate` is now Levenberg-Marquardt, replacing random search plus coordinate descent. It recovers parameters to about 1e-8 instead of 1e-2.
- `VolSurface` interpolates across expiries at fixed log-moneyness when forwards are supplied.
- `AsianOption.arithmeticPrice` computes the Turnbull-Wakeman second moment in O(n) instead of O(n²).
- `Heston` Monte Carlo and `Autocallable` use `SplittableRandom` instead of `java.util.Random`, so seeded results differ from v1.
- The `Ndf` default fixing-lag table (BRL and CLP in particular) is documented as unverified against the EMTA templates. A new overload takes an explicit lag.

#### Added
- `ImpliedVolatility`, a Black implied-vol solver and normalised Black function.
- `Bachelier`, the normal model, with price, delta, vega and implied normal vol.
- `RatesOptions.VolModel` (lognormal, shifted lognormal, normal). Swaptions and caps/floors now support any accrual frequency and negative rates, and `swaptionImpliedVol` returns implied vol for each model.
- `BarrierOption.price` covers all eight barrier types with cash rebates (Reiner-Rubinstein, pinned to Haug table 4-13).
- `FxVolSurface` gains `DeltaConvention` (forward or spot, with or without premium adjustment), `AtmConvention` (DNS or ATM forward), `ButterflyQuote`, spot-delta strike solving and `smileButterfly25/10`.
- `BusinessCalendar.StubType` (short or long, front or back) and an end-of-month rule. New day counts: `DayCount.THIRTY_E_360`, `THIRTY_E_360_ISDA` (with termination-date form), `ACT_ACT_ICMA`, `actActIcma`, and `bus252`.
- `BondPricer.yieldFromCleanPrice`, a street-convention yield.
- `SabrModel.calibrate` with weights, `calibrateToAtm` (alpha from West's ATM cubic), `alphaFromAtmVol` and the `minimumDensity` butterfly check.
- `VolSurface.Builder.forward`, `forwardAt`, `calendarViolations` and `minimumDensity`.
- `DigitalOption.cashOrNothingSkewAdjusted`, a smile-consistent digital (call-spread limit).
- `IncrementalGreeks.onTick(spot, yearsSinceAnchor)` includes theta decay between anchors.
- `DividendSchedule.bosVandermarkPrice`, a dividend split between spot and strike.
- `SwapPointsCurve.Builder.preSpot` for today and tomorrow outrights, and `impliedCarry` with a day count.
- `MathUtils.logNormPdf`.

### Risk, hedging, volatility and optimisation

#### Fixed
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

#### Changed
- `GaussianCopula.sample`/`sampleT` take a `java.util.random.RandomGenerator` (a `Random` still works).
- `OptionsBook.PnlExplain` has vanna and volga terms; the record constructor changed.
- `RegimeDetector.RegimeModel` has a new `filteredHighVolProbability` component; the class doc now says the smoothed path is for ex-post labelling only.
- `CentralRiskBook.CrbReport.var/es` are delta-gamma numbers.
- `FixAnalyzer` is described as WM/R-style rather than "per WM/R methodology".
- `BestExecutionAnalyzer.BestExecutionReport` has a quantity-weighted slippage component; the record constructor changed.
- `CointegrationTest.engleGranger(a, b, lags)` uses ADF lags and MacKinnon (2010) finite-sample critical values; the two-argument form is unchanged.
- `PortfolioOptimizer.maxSharpe` throws when no asset has an expected return above the risk-free rate.

#### Added
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

### Alpha research and backtesting

#### Fixed
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

#### Changed
- `BacktestConfig.defaults()` charges 1 bp commission and 3 bp slippage (was 10 bp commission, no slippage) and fills next open; `BacktestConfig.frictionless()` gives a zero-cost config.
- `AlphaReport.decayProfile` is documented as the holding-period IC; its format string says so.
- `PortfolioStrategy` gains `init(signalData, tradeData)`; `PortfolioBacktester` has a price-pair overload so signals run on adjusted prices while cash and dividends use unadjusted prices.
- `TradingStrategy` gains a position-aware `onBar(index, position)` default method, used by the bar engines.
- Result records of `AlphaBacktester`, `PortfolioBacktester`, `SignalEvaluator`, `AlphaValidation`, `FamaMacBeth`, `CalendarAnomalies`, `AlphaReport.Attribution`, `WalkForwardAnalyzer` and `GridSearchOptimizer.Candidate` carry new fields.
- `Indicators.Ichimoku.chikou` is documented as display-only (it holds future prices relative to its index).
- `KylesLambda.impactBps` documents that λ is per unit of net flow over one aggregation window.

#### Added
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

### Trading lane, execution and benchmarks

#### Fixed
- `HftRiskGate` rejects NaN, zero, negative and infinite prices (`REJECT_PRICE`); before, a NaN order passed every check.
- The position limit in `HftRiskGate` now counts working orders: the gateway reserves each order's quantity and releases it on fill, cancel or reject, so resting orders can no longer exceed the cap.
- Engaging the kill switch now cancels: every `HftOrderGateway` on the gate sends a cancel-all to its venue listeners and drops new orders still queued in the ring.
- A throwing venue listener or bus listener no longer kills its thread silently: the exception is contained and counted, the gate is killed, and `isHealthy()` turns false; a venue thread that dies anyway makes `submit` return `-REJECT_GATEWAY_DOWN`.
- `HftQuoter` keeps one working bid and one working offer per symbol and requotes by cancel/replace, waiting for the venue's answer; before, it stacked a new pair of orders on every tick.
- `AutoHedger` sends one IOC hedge at a time, priced at the opposite touch, and waits until it is finished before sending another; before, it re-sent the full excess after a time cooldown while the first hedge could still be working.
- POV targets in `PovTracker` and `BenchmarkExecutor` are now a share of total volume, `p / (1 - p)` times others' volume; before, a 10 % target realised 9.1 %. Tests that pinned the old targets were changed (for example 500 to 555 shares on 5,000 of volume); `realizedParticipation()` now reports executed / (executed + others) and the rate must be below 1.
- `BenchmarkExecutor`, `PovTracker` and `LiquiditySeekingAlgo` count sent-but-unfilled children against the schedule (`onChildSent`, `onChildDone`), so a resting child is not sent again.
- `BenchmarkExecutor` finishes time benchmarks at the horizon: the depth cap relaxes over the last 10 % of the horizon to all of the displayed depth, so the remainder is worked as far as the book allows.
- `ExecutionAlgoBacktester` feeds POV runs `(1 - p)` of each bar's volume as other traders' volume, so a 10 % POV still trades 10 % of the bar under the share-of-total definition.
- `GlobalRiskAggregator` fails closed: an exposed symbol without a reference price trips the breaker, and working orders count in the worst-case gross notional.
- `HftOrderBook` delivers trades only after the book is consistent, so a throwing trade sink can no longer leave a half-updated book; id-map probes are bounded.
- `LatencyRecorder` percentiles are upper bounds on the true quantile, never bucket midpoints; resolution raised to 128 sub-buckets per power of two (exact below 128 ns).
- `HftLatencyBenchmark` reports the measured tick loss instead of a hard-coded zero.
- `ShardedTradingEngine.publish` before `start()` throws `IllegalStateException` instead of a `NullPointerException`.
- `TradingDashboard` binds to the loopback interface by default.
- Observability counters in the bus, gateway, quoter and hedger are published in opaque mode, so monitoring threads see current values.

#### Changed
- `OrderListener` gains default methods `onNewOrder` (with IOC flag), `onCancel`, `onReplace` and `onCancelAll`; existing adapters still compile but must override the cancel methods to honour cancels.
- `OrderRingBuffer` slots carry a message type (new, cancel, replace, cancel-all).
- `OrderStatus` gains `PENDING_NEW`; `PaperTradingGateway` follows the shared `OrderStateMachine`, fills at most the displayed size (new `onQuote` overload with sizes), cancels the unfilled part of market orders and calls execution listeners outside its lock.
- `HftRiskGate` limits can be changed from any thread at any time; new controls: daily order count, daily notional, daily loss (`updateDailyPnl`), duplicate-order window, optional fail-closed collar without a reference price.
- `HftQuoter` takes a BBO through `onQuote`, quotes off the mid, ignores trade prints once a book exists, pulls quotes on a crossed or locked book and never sends a side that would cross the BBO.
- `GlobalRiskAggregator` is created with `GlobalRiskAggregator.start(...)`; the constructor no longer starts a thread.
- `BenchmarkExecutor.IMPLEMENTATION_SHORTFALL` follows the Almgren-Chriss trajectory `1 - sinh(kT(1-f))/sinh(kT)` (urgency `withShortfallUrgency`, default 2) instead of the fixed curve still used by `ARRIVAL_PRICE`.
- `TickRingBuffer`, `OrderRingBuffer` and `RingBuffer` keep their sequences in a strided array (one cache line pair each); `RingBuffer` now uses acquire/release publication and sequence caching.
- `HiccupMonitor.spinning(...)` samples with a busy `nanoTime` loop; the default park mode is documented as timer-bound on Windows.
- Benchmarks print the clock step, run open-loop fixed-rate passes with intended-start timestamps next to the closed-loop ones, measure the risk gate over 1,024 random symbols with an independent collar reference, and time order-book operations in 128-op batches. JMH `CoreBenchmarks` run 3 forks, 5 warm-up and 10 measurement iterations and add `riskGateCheckRandomSymbols`.
- Zero-allocation tests in the trading, market-data, order-book and execution packages assert exactly zero bytes after warm-up, with a bounded retry.
- SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` are no longer excluded globally (277 findings before). Result types copy arrays and collections on construction and hand out elements (`x(i)`), lengths and copies (`xCopy()`); the 51 records with array components (47 public, 4 internal) are final classes with content equality (Error Prone `ArrayRecordComponent`: 0). The only remaining matches are per method in `spotbugs-exclude.xml`, each with a reason: flyweight `wrap` methods, injected collaborators and recorded failures.
- `BarSeries` column arrays no longer escape: `closesCopy()` and the other column copies, `copyClosesInto`; the class is `@Immutable`.
- `HftOrderGateway` execution-report and venue-thread counters moved from adjacent `AtomicLong`s to padded `StridedSequences` slots (single-writer opaque increments on the venue thread, atomic adds for reports), removing false sharing and lock-prefixed increments on the venue thread.
- `HftOrderGateway`, `HftMarketDataBus`, `ShardedTradingEngine`, `AsyncTickCapture`, `BinaryMarketDataClient` and `WebSocketFeed` wait through a pluggable `IdleStrategy` instead of hard-coded `onSpinWait`/`parkNanos`; every polling thread is created by a replaceable `ThreadFactory`.
- `BinaryMarketDataClient` and `BinaryOrderReceiver` count decoded, dropped and skipped messages with single-writer opaque increments on padded slots instead of `AtomicLong.incrementAndGet` per message.
- `GlobalRiskAggregator` sweeps without allocating (reused scratch, indexed loop instead of an iterator) and accepts a `ThreadFactory`.
- `CentralRiskBook` keys desks, equity symbols and FX pairs by dense ids resolved once; booking no longer builds factor-name strings, boxes NDF notionals or looks up a `TreeMap` per flow, and the String-keyed methods are allocation-free after the first booking of a name. Factor registration order, serialized state and reports are unchanged.
- `CrbAutoHedger.check` returns a shared empty array when no hedge is due; `Vpin` advances its ring without a modulo.
- `LatencyRecorder` bucket arithmetic uses shifts and masks.
- Benchmarks run with the recommended JDK 25 flags (`-XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem`, fixed heap) in the workflow and the JMH forks; `docs/ULTRA_LOW_LATENCY.md` section 8 lists them.


#### Added
- `HftOrderGateway.cancel`, `replace`, `cancelAll`, execution-report methods (`onAck`, `onReject`, `onFill`, `onCanceled`, `onReplaced`, `onCancelReject`), order queries, ack-timeout scan and an optional `OrderThrottle`.
- `OrderStateMachine`, `OrderStateTable`, `LoopbackVenue` (in-process venue for tests and benchmarks), `marketdata.QuoteListener`, `marketdata.StridedSequences`.
- `HftOrderBook` owner ids with self-trade prevention (cancel resting, cancel incoming, cancel both, decrement), `modify` (size-down keeps priority), market-order protection band, trade sequence numbers and `SequencedTradeSink`.
- `Nbbo` per-venue update times and `expireStale` with a maximum quote age.
- `ShardedTradingEngine.publishMissMask` reporting which shards missed a tick; each shard's bus kills its gate on a listener failure.
- `HftMarketDataBus.onListenerFailure`, `isHealthy`, `failure`, `listenerErrorCount`.
- `util.IdleStrategy` (busy spin, yield, fixed park, spin-yield-park back-off), `util.ThreadPlacement` (named daemon factories with an on-start placement hook; `taskset`/`isolcpus` notes), `util.NanoClock`, `util.ValueArrays`, `util.Immutable`.
- `HftOrderGateway.threadFactory`, `clock`; `HftMarketDataBus.threadFactory` and an `IdleStrategy` constructor; `ShardedTradingEngine` constructor with a per-thread `Supplier<IdleStrategy>`; `GlobalRiskAggregator.start(..., ThreadFactory)`.
- `CentralRiskBook.deskId`, `equityHandle`, `fxPairHandle` and int-keyed booking overloads; `InternalizationEngine.internalize` with `lastRouted()` and `lastImprovementBps()` (no result object).
- `HotPathAllocationTest` (exactly zero bytes for idle strategies, FIX encoder read-out, MoldUDP64 header encoding, tick-file writing across block flushes, CRB booking, VPIN, auto-hedger idle check, internalization, aggregator reads, streaming indicators, latency recording) and `ValueClassCopyContractTest` (copy-in, copy-out and content equality for every former array record).


### FIX, feeds, SBE and persistence

#### Fixed
- FIX: an application message received before Logon now disconnects the session instead of reaching the listener; acceptors can refuse a Logon (credentials, permissions) through `Listener.onLogonRequest`, answered with a Logout carrying the reason.
- FIX: the acceptor's Logon reply is now sent before its ResendRequest when the initiator's sequence number is ahead, so conformant peers no longer treat the reconnect as a failed logon.
- FIX: `FileSessionStore` rewritten as a CRC-32C journal forced to disk before the message reaches the socket; after a crash a ResendRequest replays the real orders instead of covering them with a GapFill.
- FIX: a torn record at the end of the journal (crash mid-write) is truncated on open instead of making the store unopenable; `truncatedBytes()` reports what was dropped.
- FIX: session stores are bounded (`FileSessionStore(dir, maxRetained)` compacts the journal; the in-memory store keeps the last 100 000 messages) and roll on ResetSeqNumFlag; resend requests below the retention window are reported through `Listener.onResendUnavailable`.
- FIX: incoming SenderCompID/TargetCompID and SendingTime (default tolerance 120 s) are validated; the acceptor adopts the initiator's HeartBtInt; a heartbeat clears the TestRequest only when it echoes its TestReqID; PossDup messages need OrigSendingTime no later than SendingTime; SequenceReset-GapFill must move forward and SequenceReset-Reset rejects a NewSeqNo below the expected one; a too-low MsgSeqNum is answered with Logout before disconnecting.
- FIX: a well-framed message with a bad field gets a session Reject (35=3, SessionRejectReason 373) instead of a disconnect; a garbled frame (CheckSum, BodyLength) is ignored without consuming its sequence number.
- FIX: the parser reads length-prefixed data fields (RawData, XmlData, EncodedText and others) by their length, so embedded SOH no longer splits them, and keeps every occurrence of repeated tags (`getAll`).
- FIX: `FixParse.parseLong` and `priceMantissa` reject non-digits and overflow instead of producing garbage (e.g. "1e3"); `FixDecoder.poll` no longer reads bytes past the filled length.
- FIX: `FixSession.send` writes the store and the socket under one sync per call; `sendBatch` shares one disk force across several messages, and inbound sequence numbers are no longer written synchronously per message.
- WebSocketFeed: close and error callbacks of one connection schedule at most one reconnect, the previous socket is aborted first, and callbacks from a superseded socket are ignored, so two sockets can no longer publish into the single-producer bus.
- WebSocketFeed and BinaryMarketDataClient: a publish into a full bus ring is retried for a bounded time, then the tick is dropped, counted (`droppedTicks`) and a gap flag is raised (`consumeGap`); a reconnect also raises it.
- WebSocketFeed: symbols can be registered up front (`withSymbols`); unknown or over-capacity symbols are dropped and counted instead of throwing inside the WebSocket callback and cycling the connection.
- WebSocketFeed: `close()` waits up to two seconds for the close handshake before aborting.
- ITCH: `L3BookBuilder.onMessage` checks that the whole message is inside the buffer before decoding; a short datagram is skipped and counted (`truncatedCount`) instead of throwing on the feed thread.
- ITCH: Order Executed With Price (C) now removes the executed shares from the book (previously ignored, leaving phantom liquidity) and records the print.
- SBE: 64-bit fields of the order, trade and quote layouts are now 8-byte aligned (the order layout had them at offsets 4 mod 8 while documenting them as aligned).
- BinaryOrderPublisher no longer blocks the venue thread or throws from `onOrder` on a slow or failed peer.
- Checkpoint: the temporary file is forced to the device before the rename and the directory is forced after it, so a power loss cannot leave an empty or partial checkpoint.
- AsyncTickCapture and TickCapture: a writer failure is kept and surfaced (`isHealthy`, `failure`, rethrown by `close`) instead of killing the writer thread silently or throwing into the bus consumer.
- QFLT replay: a truncated or corrupt file now reports how many ticks were delivered (`TruncatedTickFileException.ticksDelivered`).

#### Changed
- FIX: `FixSessionStore` gained `recordSent`, `sync`, `firstRetainedSeq` and `reset` (default methods); administrative messages are journalled too, and the next outgoing sequence number is recovered from the journal. The on-disk format changed (`messages.journal`, `session.seq`); a version-1 directory (`seqnums.dat`, `messages.dat`) is migrated on first open.
- FIX: `FixSession.Config` has a seventh component, `sendingTimeToleranceSeconds` (existing constructors keep working; `withSendingTimeTolerance`, 0 disables).
- FIX: `ExecutionReport` has a `possDup` component (true for PossDupFlag or PossResend); the 12-argument constructor remains.
- FIX: `FixMessage.Builder.field(int, String)` refuses a value containing SOH; use `dataField`.
- FIX: the in-memory session store default keeps the last 100 000 application messages instead of all of them.
- SBE schema version 2: every message starts with the standard 8-byte SBE header (blockLength, templateId, schemaId, version), prices and sizes are int64 mantissas with an int8 exponent (`Decimal64`), and the codecs' `encode` methods take the exponent. `MESSAGE_TYPE`/`typeAt` are replaced by `TEMPLATE_ID`/`MessageHeader`; `BLOCK_LENGTH` is now the body length and `ENCODED_LENGTH` the full message. Readers skip unknown templates by block length, stop on a foreign schema id, and still decode version-1 frames.
- BinaryOrderPublisher: non-blocking writes through an outbound buffer with a circuit breaker (buffer full, stall timeout, write failure, off-grid price); the constructor now declares `IOException`; prices go out on a per-symbol `10^exponent` grid (default 1e-8).
- QFLT tick files are written as version 2: header with clock domain (`TickFileWriter.ClockDomain`) and an epoch/monotonic anchor, records grouped in CRC-32C blocks, file forced on close. Version-1 files are still read.
- Tests that measured allocation now require exactly zero bytes (smallest of five runs) instead of under 100 KB.
- FIX session tests that slept or depended on the wall clock were replaced by deterministic scenarios on a manual clock.
- `FixOrderEncoder` no longer exposes its buffer: `copyInto`, `writeTo(ByteBuffer)`, `byteAt`, `toByteArray` replace `buffer()`/`offset()`; `FixExecReportView.byteAt` replaces `buffer()`.
- `TickFileWriter` reuses its frame header, trailer and CRC32C and writes from direct buffers, so a steady tick stream allocates nothing per 64 KiB block.
- `MoldUdp64Receiver.encodeHeader`/`encodeRequest` write the space-padded session in place instead of through `String.format`; sessions over 10 characters are rejected.


#### Added
- `FixClock` and `FixSession.overStream(...)`: a thread-free session over any transport, driven by `onBytes` and `onTimer`, for custom transports and deterministic tests.
- `FixSession.Listener` callbacks: `onLogonRequest`, `onOrderCancelReject` (35=9), `onBusinessMessageReject` (35=j), `onGarbledMessage`, `onResendUnavailable`.
- `FixExecReportView`: ExecID, OrderID, PossDupFlag, PossResend, OrdRejReason, Text and AvgPx, plus `ExecIdDeduplicator` to drop fills replayed after a resend.
- `FixOrderEncoder.encodeCancel` (35=F) and `encodeReplace` (35=G), garbage-free.
- ITCH: System Event (S), Stock Trading Action (H, `L3BookBuilder.tradingState`/`isTrading`), Cross Trade (Q) and Broken Trade (B) decoding and encoding; wire lengths for every ITCH 5.0 message; `ItchCodec.complete`; `L3BookBuilder.clear()` for snapshot rebuilds.
- `feed.MoldUdp64Receiver`: MoldUDP64 framing with sequence gap detection, retransmit requests and a snapshot fallback.
- `sbe.MessageHeader`, `sbe.Decimal64`.
- `TickFileReader.info` (version, clock domain, anchor) and `FileInfo.toEpochNanos`.

[Unreleased]: https://github.com/AshJha0/Quant-Finance-Library/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/AshJha0/Quant-Finance-Library/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/AshJha0/Quant-Finance-Library/compare/v1.17.0...v2.0.0
