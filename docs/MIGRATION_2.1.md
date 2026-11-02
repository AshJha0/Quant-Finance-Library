# Moving to 2.1

Version 2.1 adds classes, methods and overloads. It removes nothing and changes no
public signature, so code written against 2.0 compiles unchanged, and the API
compatibility gate (below) checks this for the stable packages. Some results change
because a fix landed or a behaviour was tightened, and invalid input to some
`MathUtils` methods now fails with a different exception; all are listed under
"Behaviour notes". [CHANGELOG.md](../CHANGELOG.md) has the full list.

## New APIs

| Area | Types (under `com.quantfinlib`) |
|---|---|
| Multi-curve rates | `rates.DiscountCurve`, `MultiCurveBootstrap`, `MultiCurveRisk`, `CurvePair`; instruments `Deposit`, `Fra`, `RateFuture`, `OisSwap`, `InterestRateSwap` behind `CurveInstrument`; two-curve overloads of `SwapPricer` and `RatesOptions` |
| Credit | `credit.IsdaCdsModel`, `IsdaCurve` (ISDA CDS Standard Model); `ExposureSimulator`, `ExposureProfile`; `CvaApproximator.cva(ExposureProfile, ...)`, `dva`, `cvaWrongWay` |
| Counterparty capital | `risk.SaCcr`, `SaCcrTrade` (BCBS 279) |
| Smile and exotics | `pricing.SviSlice`, `SsviSurface`, `SviSurface`, `TotalVarianceSurface`, `DupireLocalVol`, `LocalVolMonteCarlo`, `LocalVolPde`, `HestonCos`, `HestonCalibrator`, `DividendTree`; `VannaVolga.exactWeights`, `weightsFor`, `priceExact`; `TouchOption.oneTouchPayAtHit`, `doubleNoTouch`, `doubleOneTouch`; `AsianOption.turnbullWakemanSeasoned`, `curranPrice`, `levyPrice`; `fx.VannaVolgaTouch` |
| Factor risk and research | `risk.FactorRiskModel`; `alpha.AlphaRiskModels`, `AlphaCombiner`, `BacktestAttribution`, `CapacityCurve`; `AlphaBacktester.RiskOptions` and `runTraced`; `PortfolioBacktester.Participation`; `backtest.StrategyRunner` |
| Statistics | `stats.Stats` (moments, covariance, Pearson, Hyndman-Fan type 7 and type 1 quantiles, median, midranks) |
| Exceptions | `core.QuantFinException` (marker interface), `WireProtocolException`, `MalformedDataException`, `ConfigurationException` |
| Trading lane | `fix.FixSessionSchedule`, `FixSessionOptions` and `FixSession` overloads that take them; `trading.PositionLedger`, `DropCopyReconciler`, `LimitsConfig`, `LimitsManager`, `HftRiskGate.limits(LimitsConfig)`; `orderbook.AuctionBook`; `AdaptiveSor.maxLatencyPenaltyBps` |

## Behaviour notes

- `AntiGamingJitter.jitterTimes` now takes its bounds from the original schedule
  only, so two neighbouring child orders keep a minimum gap. For a given seed the
  jittered schedule differs from 2.0. Tests that pinned 2.0 times need new values.
- `AdaptiveSor` puts every cost term in price units, and dark probes across all dark
  venues share one `maxDarkFraction` budget. Routing decisions can differ from 2.0.
- `HftRiskGate` keeps its limits in one immutable object that each check reads once,
  so a check never sees half of a limit change. The existing setters still work and
  each replaces that object. `limits(LimitsConfig)` changes all limits at once;
  `LimitsManager` versions such changes, keeps an audit trail and can roll back.
- `OrderBook.cancel` is O(1) in the price level. Results are unchanged.
- `AlphaBacktester.run` without `RiskOptions` keeps the z-score construction and
  gives the same results as 2.0.
- `MathUtils.mean`, `variance`, `stdDev`, `stdDevP`, `stdDevSample`, `percentile`,
  `percentileSorted` and `covariance`, and `risk.Dependence.ranks`, now delegate to
  `stats.Stats`. Results on valid input are unchanged bit for bit.
- `MathUtils.percentile` and `percentileSorted` throw `IllegalArgumentException` for a
  probability outside [0, 1] or NaN. In 2.0 such a value threw
  `ArrayIndexOutOfBoundsException` or returned the minimum.
- `MathUtils.mean(v, from, to)` and the range standard deviations throw
  `IndexOutOfBoundsException` for a range outside the array or with `from > to`.
- FIX BodyLength and SBE header framing faults throw `WireProtocolException`, CSV bar
  and universe format faults throw `MalformedDataException`, and `LimitsConfig`
  faults throw `ConfigurationException`. Each extends the type thrown in 2.0, so
  existing catch blocks still work. All implement `core.QuantFinException`, as do
  `fix.FixParseException` and `data.TickFileReader.TruncatedTickFileException`.

## Build and test tooling

- The library jar is the named module `com.quantfinlib`. On the module path, add
  `requires com.quantfinlib;` to your `module-info.java`; on the class path nothing
  changes. The `examples` jar has no descriptor and runs on the class path.
  `java -p quant-finance-library-2.1.0.jar -m com.quantfinlib` starts the command
  line, as `java -jar` does.
- `scripts/fetch-api-baseline.sh && ./mvnw -Papi-compat -DskipTests verify` compares
  the build with the released 2.0.0 jar. See [STABILITY.md](STABILITY.md).
- The stable packages are annotated `@NullMarked` (JSpecify). JSpecify is a
  compile-time, optional dependency; nothing is added at run time.
- The nightly workflow runs PIT mutation testing (`-Pmutation`) on `pricing`, `risk`,
  `rates` and `volatility`.
- `scripts/run-benchmarks.sh` repeats the latency benchmarks of
  [BENCHMARKS.md](BENCHMARKS.md).

## Adopting the main additions

**Multi-curve.** Build the OIS curve and a projection curve together, then price on
both. The single-curve `YieldCurve` methods are unchanged.

```java
CurvePair c = MultiCurveRisk.buildCurves(valuationDate, oisInstruments, threeMonthInstruments,
        CurveInterpolation.MONOTONE_CONVEX);
double pv = SwapPricer.payerPv(c.discount(), c.projection(), swap);
```

[COOKBOOK.md](COOKBOOK.md) has a full example with the instrument lists.

**ISDA CDS.** Standard contracts trade on a fixed coupon plus an upfront.

```java
IsdaCdsModel.Contract cds = IsdaCdsModel.standardContract(LocalDate.of(2026, 11, 2), 5,
        IsdaCdsModel.COUPON_100BP, IsdaCdsModel.STANDARD_RECOVERY);
double upfront = IsdaCdsModel.quotedSpreadToUpfront(cds, 0.03, IsdaCurve.flat(0.035));
```

The model has not been checked against the Markit test grid, and holidays are
weekends only.

**Factor risk model.** Estimate from a panel of returns, styles, sectors and caps, or
from an `AlphaContext`, then decompose a portfolio.

```java
FactorRiskModel m = AlphaRiskModels.estimate(ctx, index, sectors, AlphaRiskModels.Spec.defaults());
FactorRiskModel.Decomposition d = m.decompose(weights);
double total = d.totalRisk();
```

To build the alpha backtest's book with the risk model, pass `RiskOptions`:
`AlphaBacktester.run(ctx, factor, config, AlphaBacktester.RiskOptions.defaults().withSectors(labels))`.

**StrategyRunner.** Run one `TradingStrategy` on the bar backtester, the tick
backtester or the paper-trading gateway with the same tape.

```java
StrategyRunner.Run run = StrategyRunner.run(StrategyRunner.Engine.PAPER_GATEWAY,
        strategy, bars, 1_000_000, workDir);
```

The bar engine trades fractional shares, so it matches the other two exactly only
when cash over price is a whole number.

## Deferred to 3.0

A smaller public API is planned for 3.0. 2.x keeps every public type of 2.0.
