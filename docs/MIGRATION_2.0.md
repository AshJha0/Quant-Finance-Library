# Migrating from 1.x to 2.0

Version 2.0 fixes a number of results that were wrong in 1.x and changes some APIs to
do it. This page lists every source-incompatible change and every change in default
behaviour that alters numbers, grouped by package. Additions that do not affect
existing code are in [CHANGELOG.md](../CHANGELOG.md).

Two general points first:

- Java 25 is required (1.x needed Java 24).
- Seeded random results differ in several places (`Heston` Monte Carlo,
  `Autocallable`, the portfolio optimisers, which no longer use random search). Tests
  that pin seeded outputs from 1.x need new values.

## Build and packaging

- The library jar no longer contains `com.quantfinlib.examples`. Demos and the
  hand-written benchmarks ship as a separate jar with classifier `examples`. Add that
  jar if you called `QuickStartDemo` or the benchmarks from the library jar.
- Tests tagged `load` or `timing` are skipped by the default build; run them with
  `./mvnw -Pfull test`.
- `./mvnw verify` fails when line coverage is below 90 % overall or 80 % in a package.

## `backtest`, `backtest.portfolio`, `backtest.validation`, `backtest.tick`

**Fills move to the next bar's open.** `Backtester`, `PortfolioBacktester` and
`AlphaBacktester` used to fill an order at the close of the bar that produced the
signal, which uses a price the strategy could not have traded at. The default is now
`FillPolicy.NEXT_OPEN`. Every backtest result changes.

```java
// 1.x behaviour, now opt-in (for comparison only):
BacktestConfig sameBar = BacktestConfig.defaults().withFillPolicy(FillPolicy.CLOSE);
// 2.0 default:
BacktestConfig nextOpen = BacktestConfig.defaults();          // FillPolicy.NEXT_OPEN
BacktestConfig nextClose = BacktestConfig.defaults().withFillPolicy(FillPolicy.NEXT_CLOSE);
```

**Default costs changed.** `BacktestConfig.defaults()` charges 1 bp commission and
3 bp slippage (1.x: 10 bp commission, no slippage). For a zero-cost run use
`BacktestConfig.frictionless()`.

**`BacktestConfig` is a seven-component record.** The new last component is
`fillPolicy`. The six-argument constructor still exists and fills next open.

**Interfaces.** `TradingStrategy` gains a default `onBar(int index, double position)`
that calls `onBar(index)`; the bar engines call the new method. `PortfolioStrategy`
gains a default `init(signalData, tradeData)` that calls `init(signalData)`. Existing
implementations compile unchanged.

**Result records gained components.** The canonical constructors of the result records
of `PortfolioBacktester`, `WalkForwardAnalyzer` and `GridSearchOptimizer.Candidate`
changed. Code that reads them through accessors is unaffected; code that constructs
them must pass the new components.

**Changed numbers.**
- `WalkForwardAnalyzer` carries the open position across fold boundaries instead of
  closing and re-entering at each fold. Trade indices refer to the full series, and
  efficiency is computed on the stitched out-of-sample and in-sample curves.
- Stop-loss and take-profit fills pay slippage.
- `TradeAnalytics.kellyFraction` is the empirical growth-optimal fraction on per-trade
  returns. `MonteCarloTradeShuffle` compounds per-trade returns; its drawdowns are
  fractions, not currency.
- `TickBacktester` fills a resting order traded through by at most the size of the
  print that traded through it.
- `MarketImpactModel.estimate` (in `microstructure`) uses only bars before the trade
  bar.
- `ExecutionAlgoBacktester` gives POV runs `(1 - p)` of each bar's volume as other
  traders' volume (see `execution` below).

## `alpha`

- `AlphaBacktester` lets weights drift between rebalances and measures turnover
  against the drifted book. Its result record changed.
- Value and quality factors throw when asked to score historical bars from a static
  fundamentals snapshot. Attach a `FundamentalsHistory` with publication dates.
- `SignalEvaluator` hit rate uses cross-sectionally demeaned scores and returns.
- IC t-statistics (`SignalEvaluator`), Fama-MacBeth premia and calendar-anomaly
  t-statistics use Newey-West standard errors. The iid value is kept as
  `naiveTStat`.
- `AlphaValidation.monteCarloRobustness` uses a cross-sectional label permutation by
  default. The 1.x date permutation is `NullModel.DATE_PERMUTATION`.
- `parameterSensitivity.worstNeighborDrop` is the drop from the best parameter to its
  worse neighbour. The 1.x value is `largestAdjacentJump`.
- `PortfolioConstruction.zScoreWeights` stays dollar-neutral after clamping and
  capping. `trailingBetas` uses a leave-one-out market, so betas change.
- `AlphaContext.of` checks every bar timestamp and throws on misaligned series.
- Result records of `SignalEvaluator`, `AlphaValidation`, `FamaMacBeth`,
  `CalendarAnomalies` and `AlphaReport.Attribution` have new components.

## `microstructure`

- `AlphaEnsemble` and `OnlineAlphaLearner` give a signal weight only when its IC
  minus two EWMA standard errors is positive (1.x: IC > 0). Weights of weak signals
  drop to zero.
- `LeadLagEstimator.bestLag` reports no lead unless the best lag clears a
  multiple-testing band and beats the contemporaneous correlation.

## `pricing`

**`QuantoOption` takes a foreign rate.** In 1.x the foreign asset drifted at the
domestic rate. The forward is now `S exp((r_f - q - rho sigma_S sigma_X) T)`,
discounted at `r_d`.

```java
// 1.x
QuantoOption.price(type, spot, strike, domesticRate, assetYield, assetVol, fxVol, rho, t);
QuantoOption.quantoForward(spot, domesticRate, assetYield, assetVol, fxVol, rho, t);
// 2.0
QuantoOption.price(type, spot, strike, domesticRate, foreignRate, assetYield,
        assetVol, fxVol, rho, t);
QuantoOption.quantoForward(spot, foreignRate, assetYield, assetVol, fxVol, rho, t);
```

**Changed numbers and behaviour.**
- `MathUtils.normCdf` (in `util`) uses Cody's erfc algorithm instead of the
  Abramowitz-Stegun approximation. Every price differs in the last digits.
- `BlackScholes.impliedVol` and `Black76.impliedVol` use a Newton solver on the
  normalised Black function. A price equal to intrinsic returns 0 (1.x returned NaN).
- `BlackScholes` Greeks at zero volatility return their deterministic limits instead
  of NaN or infinity.
- `BinomialTree` throws on zero volatility instead of returning NaN.
- `BarrierOption` regular-barrier methods price reverse barriers instead of throwing.
- `SabrModel.calibrate` is Levenberg-Marquardt. Calibrated parameters change.
  `Params.rmse` is in decimal volatility (the 1.x documentation said vol points).
- `VolSurface` interpolates across expiries at fixed log-moneyness when forwards are
  supplied.
- `VannaVolga.impliedVol` inverts the out-of-the-money option and is flat or smoothly
  continued beyond the 10-delta strikes.
- `ForwardCurve.forward` on an empty curve and `impliedRateDifferential(0)` throw
  `IllegalArgumentException` (1.x: `NullPointerException`, NaN).
- `Heston` Monte Carlo and `Autocallable` use `SplittableRandom`.
- `Ndf` default fixing lags are documented as unverified; pass an explicit lag where
  it matters.

## `fx`

- `FxVolSurface` reads a quoted butterfly as the broker (market) strangle by default
  and solves for the smile butterfly that reprices it (Clark 2011). Smile
  wings change. For the 1.x reading pass `ButterflyQuote.SMILE_STRANGLE`.

## `rates`

**`BondPricer` uses the street convention.** Coupons are `c/f`, accrual is ACT/ACT
ICMA, and discounting uses fractional-period exponents. The calendar and roll
arguments are gone, because the convention discounts on unadjusted dates. An optional
end-of-month flag is added.

```java
// 1.x
BondPricer.dirtyPrice(face, coupon, freq, settle, maturity, yield, dayCount, calendar, roll);
// 2.0
BondPricer.dirtyPrice(face, coupon, freq, settle, maturity, yield, dayCount);
BondPricer.dirtyPrice(face, coupon, freq, settle, maturity, yield, dayCount, endOfMonth);
```

`cleanPrice` and `accruedInterest` changed the same way.

- `BusinessCalendar` schedules are computed from a fixed anchor, so dates after a
  February coupon no longer drift to the 28th.

## `util`

- `MathUtils.variance`, `stdDev`, `stdDevSample` and `stdDevP` return NaN for too few
  observations (1.x returned 0). Check for NaN where a short window is possible.

## `risk`

- **Expected shortfall** uses the Acerbi-Tasche estimator everywhere (`VarEngine`,
  `FrtbEs.es975`, `RiskMetrics.conditionalVar` and `expectedShortfall`,
  `SimulationResult`, `HedgingErrorDistribution`). The 1.x estimator also averaged
  the VaR observation and read low, by about 17 % at 250 observations. ES 97.5 % of
  the losses 1..1000 is 988.0 (1.x: 987.5).
- **`FrtbEs.stressCalibratedEs`** implements MAR33:
  `ES = ES_R,S * max(1, ES_F,C / ES_R,C)`. The argument order is
  `(esCurrentFull, esStressedReduced, esCurrentReduced)`. It throws when the reduced
  set explains less than 75 % of the full-set current ES. 1.x scaled the current
  full-set ES by a stressed/current ratio, which is not the rule; results differ.
- `GaussianCopula.sample` and `sampleT` take a `java.util.random.RandomGenerator`.
  Passing a `java.util.Random` still compiles.
- `VarEngine.monteCarloVar` and `GaussianCopula` use a PSD Cholesky that accepts
  rank-deficient matrices and throws on indefinite ones.
- `PortfolioRiskAnalyzer` copies its input, requires equal-length finite rows and takes
  a risk-free rate for the Sharpe ratio.

## `crb`

- `CentralRiskBook.report` uses delta-gamma (Cornish-Fisher) VaR and ES, so
  `CrbReport.var()` and `es()` change for books with options. A book without options
  gives the same numbers as 1.x.

## `hedging`

- `OptionsBook.PnlExplain` has `vannaPnl` and `volgaPnl` components; the record
  constructor changed.
- `DeltaHedger` credits carry (dividend yield or foreign rate) on the hedge, which
  removes a bias in mean replication P&L when the carry is non-zero.
- `CointegrationTest.engleGranger(a, b, lags)` uses ADF lags and MacKinnon (2010)
  critical values. The two-argument form is unchanged.

## `volatility`

- `Garch11`, `GjrGarch11` and `Egarch11` are fitted by maximum likelihood with omega
  estimated (1.x: grid search with variance targeting). Parameters change.
- `EwmaVolatility.variances` no longer seeds with the full-sample variance. Early
  values change; an explicit-seed overload is available.
- `HarRv` fits by Householder QR instead of the normal equations; coefficients can
  differ slightly on ill-conditioned data.

## `optimization`

- `PortfolioOptimizer` and `ConstrainedPortfolioOptimizer` solve the QP exactly by an
  active-set method. The `seed` constructor argument is accepted and ignored.
  Weights change (1.x random search was accurate to about 2 % in weights).
- `PortfolioOptimizer.maxSharpe` throws when no asset has an expected return above
  the risk-free rate.
- `RiskParityOptimizer` uses Spinu's convex formulation with damped Newton steps.

## `ml`, `simulation`, `regulatory`

- `RegimeDetector.RegimeModel` has a `filteredHighVolProbability` component. Use it
  for real-time decisions; the smoothed path looks ahead.
- `MonteCarloSimulator.simulatePortfolio` simulates buy-and-hold lognormal assets
  from log-return means and covariance and no longer clamps at zero. `simulate` draws
  the exact lognormal terminal value. `SimulationResult` CVaR is never below VaR.
- `BestExecutionAnalyzer.BestExecutionReport` has a quantity-weighted slippage
  component; the record constructor changed.

## `execution`

- POV targets in `PovTracker` and `BenchmarkExecutor` are a share of total volume:
  the target is `p / (1 - p)` times other traders' volume. A 10 % target in 1.x
  realised 9.1 %. `realizedParticipation()` reports executed / (executed + others),
  and the rate must be below 1.
- `BenchmarkExecutor`, `PovTracker` and `LiquiditySeekingAlgo` count sent but unfilled
  children against the schedule. Callers must report child orders with
  `onChildSent` and `onChildDone`, or the algorithm will see them as unsent.
- `BenchmarkExecutor.IMPLEMENTATION_SHORTFALL` follows the Almgren-Chriss trajectory
  `1 - sinh(kT(1 - f)) / sinh(kT)`, with urgency set by `withShortfallUrgency`
  (default 2).
- `BenchmarkExecutor` relaxes its depth cap over the last 10 % of the horizon.

## `trading`

- `OrderListener` has new default methods `onNewOrder`, `onCancel`, `onReplace` and
  `onCancelAll`. Existing adapters compile, but they ignore cancels until they
  override these methods. A venue adapter must implement them.
- `OrderStatus` has a new first constant, `PENDING_NEW`. Switch statements over
  `OrderStatus` and code that uses `ordinal()` need updating.
- `HftRiskGate` position limits count working orders as well as fills. Orders that
  passed in 1.x because earlier orders were still resting are now rejected with
  `REJECT_POSITION`. Non-finite, zero and negative prices are rejected with
  `REJECT_PRICE`.
- Engaging the kill switch (`HftRiskGate.kill(true)`) makes every attached
  `HftOrderGateway` send a cancel-all to its listeners and drop queued new orders.
- `GlobalRiskAggregator` is created with the static factory `start(...)`; the
  constructor no longer starts a thread.

  ```java
  // 1.x
  new GlobalRiskAggregator(gates, maxGross, resumeFraction, pollNanos);
  // 2.0
  GlobalRiskAggregator.start(gates, maxGross, resumeFraction, pollNanos);
  ```
- `GlobalRiskAggregator` trips its breaker when an exposed symbol has no reference
  price, and counts working orders in gross notional.
- `HftQuoter` keeps one working bid and offer per symbol and requotes by
  cancel/replace. It needs the BBO through `onQuote` and pulls quotes on a crossed or
  locked book.
- `AutoHedger` sends one IOC hedge at a time at the opposite touch.
- `PaperTradingGateway` fills at most the displayed size (use the `onQuote` overload
  with sizes), cancels the unfilled part of market orders, and calls execution
  listeners outside its lock.
- `ShardedTradingEngine.publish` before `start()` throws `IllegalStateException`.
- `TradingDashboard` binds to the loopback interface.

## `util`: `LatencyRecorder`

- Percentiles are upper bounds of the histogram bucket that holds the quantile (1.x
  reported bucket midpoints). Reported values are slightly higher for the same data.

## `fix`

- `FileSessionStore` is a CRC-32C journal (`messages.journal`, `session.seq`). A 1.x
  directory (`seqnums.dat`, `messages.dat`) is migrated on first open; keep a copy of
  the old directory until the first session after the upgrade has completed.
- The in-memory store keeps the last 100,000 application messages.
  `FileSessionStore(dir, maxRetained)` bounds the journal. A resend request below the
  retained window is reported through `Listener.onResendUnavailable`.
- `FixSessionStore` gained default methods `recordSent`, `sync`, `firstRetainedSeq`
  and `reset`. Custom stores compile but should implement them.
- `FixSession.Config` has a seventh component, `sendingTimeToleranceSeconds`
  (default 120; 0 disables the check). Existing constructors still work.
- `ExecutionReport` has a `possDup` component; the 12-argument constructor remains.
- `FixMessage.Builder.field(int, String)` rejects values containing SOH. Use
  `dataField` for length-prefixed data fields.
- Validation is stricter: CompIDs, SendingTime, PossDup OrigSendingTime and
  SequenceReset are checked; an application message before Logon disconnects; a
  malformed field gets a session Reject (35=3) instead of a disconnect.
- `FixParse.parseLong` and `priceMantissa` throw on non-digits and overflow.

## `sbe`

Schema version 2 changes the wire format. Version-1 frames can still be read, but
everything you write is version 2.

- Every message starts with the standard 8-byte SBE header (blockLength, templateId,
  schemaId, version), written by `MessageHeader.write`.
- `MESSAGE_TYPE` and `typeAt` are replaced by `TEMPLATE_ID` and
  `MessageHeader.templateId`. `BLOCK_LENGTH` is the body length; `ENCODED_LENGTH` is
  header plus body.
- Prices and sizes are int64 mantissas with an int8 exponent (`Decimal64`). The
  `encode` methods take the exponent.
- 64-bit fields are 8-byte aligned.

```java
// 1.x
trade.wrap(buf, 0).encode(symbolId, 101.25, 300, tsNanos);
// 2.0: price on a 1e-4 grid, size in whole units
trade.wrap(buf, 0).encode(symbolId, 1_012_500L, -4, 300L, 0, tsNanos);
// or pass doubles; encode throws if a value is not on its grid
trade.wrap(buf, 0).encode(symbolId, 101.25, -4, 300, 0, tsNanos);
```

- `BinaryOrderPublisher` writes through a non-blocking outbound buffer with a circuit
  breaker. Its constructor declares `IOException`, and prices go out on a per-symbol
  `10^exponent` grid (default 1e-8).

## `data`, `feed`, `marketdata`

- QFLT tick files are written as version 2 (clock domain, epoch anchor, CRC-32C
  blocks). Version-1 files are still read; 1.x readers cannot read version 2.
- `WebSocketFeed` and `BinaryMarketDataClient` drop and count ticks when the bus ring
  stays full after a bounded retry (`droppedTicks`), and raise a gap flag
  (`consumeGap`). In 1.x a tick that did not fit was lost without notice. Check the gap flag and rebuild state when it is set.
- `WebSocketFeed` drops and counts unknown or over-capacity symbols. Register symbols
  up front with `withSymbols`.
- `TickCapture` and `AsyncTickCapture` keep a writer failure and rethrow it from
  `close`; check `isHealthy()` during the session.
- `L3BookBuilder` removes the shares of an Order Executed With Price (C) message from
  the book. 1.x ignored the message and left phantom liquidity.
- `RingBuffer` uses acquire/release publication; behaviour is unchanged for correct
  single-producer use.
