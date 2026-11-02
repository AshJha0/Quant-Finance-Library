# Architecture

Where each capability lives, how data moves through the library, and the rules the
code follows. Every package also has a `package-info.java` with the same summary in
javadoc form, published under `api/` on the documentation site. Tests mirror the main
source tree: the tests for `com.quantfinlib.risk` are in
`src/test/java/com/quantfinlib/risk`. Rendered diagrams of the flows below are in
[DIAGRAMS.md](DIAGRAMS.md). Which packages are stable APIs and which are reference
implementations is set out in [STABILITY.md](STABILITY.md).

## Two lanes

The library has a research lane and a trading lane.

The research lane works on whole series held in memory: load bars, compute
indicators and factors, price instruments, measure risk, optimise, backtest and
report. It favours clear code over speed, and allocation is acceptable.

The trading lane handles one event at a time: a tick arrives, a strategy decides, a
risk gate checks the order, and an order gateway hands it to a venue adapter. Its
steady-state paths do not allocate, and its threads communicate through
single-producer, single-consumer ring buffers.

```
              CSV / HTTP / WebSocket / QFLT tick files / SBE / ITCH
                                    |
                          [data, feed, sbe, marketdata]
                                    |
   research lane                    v                 trading lane
   -------------              HftMarketDataBus ---- TickCapture --> QFLT file
   BarSeries                        |                                  | replay
     | indicators, alpha,           v                                  v
     | pricing, risk,         strategy / HftQuoter               TickBacktester
     | optimization                 |
     v                              v
   Backtester, walk-forward    HftRiskGate --> HftOrderGateway --> OrderListener
   portfolio and alpha                          (order state,          |
   backtests                                    cancel/replace)        |
     |                                               +-----------+-----+--------+
     v                                               |           |              |
   reports (HTML/PDF/XLSX/CSV),                 FixSession  PaperTrading   BinaryOrder
   CLI, dashboard                             (FIX 4.2/4.4/  Gateway        Publisher (SBE)
                                                5.0 SP2)
```

Execution reports come back the other way: the venue adapter calls the gateway's
`onAck`, `onFill`, `onCanceled`, `onReplaced`, `onReject` and `onCancelReject`, which
drive the order state machine and release the risk gate's reservations.

From 2.2 the gateway also publishes each accepted fill to its registered
`FillListener`s, with the venue's execution id:

```
   EntitlementPreTradeCheck --> HftRiskGate --> HftOrderGateway --> venue
                                                     |  onFill(orderId, execId, ...)
                                                     v
                                               FillListener(s) --> LedgerFillListener
                                                                        |
                                                                        v
                                                PositionLedger --> DropCopyReconciler
   execution reports --> regreporting.Rts22ReportBuilder --> TransactionReportSink
                                 (also regreporting.cat, regreporting.emir builders)
                                                     |
                                     regreporting.registry.ReferenceDataChecker
                                     (GLEIF, FIRDS, ISO 10383) --> downstream sink

   venue bust / correction (ExecType H / G)
     --> HftOrderGateway.onTradeBust / onTradeCorrect
     --> FillListener.onBust / onCorrect --> PositionLedger (adjustment entry,
         rebuild from surviving fills) --> DropCopyReconciler (effective fills)
```

A listener that throws is counted in `fillListenerErrorCount` and the gateway
carries on. Without a listener nothing changes from 2.1.

## Modules

Since 3.0 the build has two Maven modules, one per stability tier
([STABILITY.md](STABILITY.md)). The arrow is the only allowed dependency
direction: the low-latency module reads the stable one, never the reverse.

```
 artifact quant-finance-library-lowlatency         artifact quant-finance-library
 module com.quantfinlib.lowlatency                 module com.quantfinlib
 (lowlatency/, reference tier)                     (core/, stable tier)
+-------------------------------------+           +----------------------------------------+
| trading     marketdata  orderbook   |  requires | core  stats  data  marketdata.tick     |
| fix         sbe         feed        | transitive| pricing rates credit fx commodities    |
| entitlements regreporting(.cat,     |---------->| markets risk crb volatility hedging    |
|   .emir, .registry)     util        |           | optimization alpha backtest(.*)        |
|                                     |           | indicators simulation ml screener dsl  |
| examples (not exported; jar         |  reads    | microstructure execution regulatory    |
|   classifier "examples")            |<----------| rfq report cli persist                 |
| requires java.xml, jdk.httpserver   |  internal | internal (exported to lowlatency only) |
+-------------------------------------+           | requires java.net.http                 |
                                                  +----------------------------------------+
```

The lower arrow is the qualified export `exports com.quantfinlib.internal to
com.quantfinlib.lowlatency`: the lane uses the `Immutable` marker and
`ValueArrays` without making them API. A type that both tiers need lives in the
stable module (`core.Side`, `fx.CrossOp`, the tick-file format in
`marketdata.tick`); a type that needs the lane lives in the lane module even when
its 2.x home was a stable package (`trading.StrategyRunner`,
`marketdata.CrossRateEngine`, `marketdata.TickCapture`). MIGRATION_3.0.md section
8 lists each move.

## Package map

Packages of the low-latency module are marked (LL); everything else is in the
stable module.

| Package | Purpose | Main classes |
|---|---|---|
| `core` | OHLCV series on primitive arrays; the order side | `Bar`, `BarSeries`, `Side` (3.0; was `orderbook.Side`) |
| `stats` | Descriptive statistics and numerical routines, one definition each | `Stats` (mean, sample and population variance, covariance, Pearson, skewness, kurtosis, Hyndman-Fan type 7 and type 1 quantiles, median, midranks), `SpecialFunctions` (normal CDF by Cody's erfc algorithm, inverse normal, log-gamma, incomplete beta, Student-t CDF), `LinearAlgebra` (Cholesky, linear solve, inverse), `Numerics` (clamp, NaN arrays, decay factor, pair sort) |
| `util` (LL) | Measurement and threading | `LatencyRecorder`, `HiccupMonitor`, `IdleStrategy`, `NanoClock`, `ThreadPlacement` |
| `internal` | Shared helpers, not API (exported to the LL module only) | `ValueArrays`, `Immutable` |
| `data` | Data in and out | `CsvBarLoader`, `HttpBarFetcher`, `SeriesAligner`, `CorporateActions`, `CorporateEvents`, `CorporateEventPositions`, `PointInTimeUniverse`, `UniverseCsvLoader` |
| `marketdata.tick` | QFLT tick files (3.0; were in `data`) | `TickFileWriter`, `TickFileReader` (QFLT v2) |
| `feed` (LL) | Live and multicast feeds | `WebSocketFeed`, `BinanceTradeParser`, `MoldUdp64Receiver` |
| `sbe` (LL) | SBE-style binary codecs (schema 2) | `MessageHeader`, `Decimal64`, `TradeFlyweight`, `OrderFlyweight`, `QuoteFlyweight`, `BinaryMarketDataClient`, `BinaryOrderPublisher`, `BinaryOrderReceiver` |
| `fix` (LL) | FIX session (4.4 default, 4.2, 5.0 SP2) and garbage-free codecs | `FixSession`, `FixSessionSchedule`, `FixSessionOptions`, `FixVersion`, `FixDictionary`, `FixGroupDef`, `FixGroup`, `FileSessionStore` (CRC-32C journal), `FixMessage`, `FixOrderEncoder`, `FixExecReportView`, `FixExecReportView.ExecIdDeduplicator`, `FixMarketDataView` |
| `marketdata` (LL) | Trading-lane transport and equities L3 | `HftMarketDataBus`, `TickRingBuffer`, `SymbolRegistry`, `ItchCodec`, `L3BookBuilder`, `Nbbo`; simpler `MarketDataProcessor`, `RingBuffer`; bus recorders `TickCapture`, `AsyncTickCapture` and the streaming `CrossRateEngine` (3.0; were in `data` and `fx`) |
| `orderbook` (LL) | Matching engines and book analytics | `OrderBook` (readable reference), `HftOrderBook` (price ladder, pooled nodes, self-trade prevention), `AuctionBook` (opening and closing call auctions), `BookAnalytics` |
| `trading` (LL) | Order entry and pre-trade risk | `StrategyRunner` (3.0; was in `backtest`), `HftRiskGate`, `HftOrderGateway`, `OrderStateMachine`, `OrderRingBuffer`, `OrderListener`, `HftQuoter`, `AutoHedger`, `OrderThrottle`, `LastLookGate`, `LoopbackVenue`, `PaperTradingGateway`, `TradingDashboard`, `ShardedTradingEngine`, `GlobalRiskAggregator`, `PositionLedger`, `FillListener`, `LedgerFillListener`, `DropCopyReconciler`, `LimitsConfig`, `LimitsManager`; `PaperTradingGateway.TradeListener` (busts and corrections, 2.4) |
| `entitlements` (LL) | Who may trade what, where and when | `EntitlementService`, `Grant`, `Permission`, `Decision`, `EntitlementPreTradeCheck` |
| `regreporting` (LL; `reporting` up to 2.4) | Regulatory transaction reports | `TransactionReport`, `TransactionReportSink`, `Rts22ReportBuilder`, `Rts22TransactionReport`, `ReportingIdentifiers` (`Rts22TransactionReport.toAuth016Xml`, `validateEsmaRules` from 2.3; `validateWithReferenceData`, `toSubmissionXml` from 2.4) |
| `regreporting.registry` (LL) | Reference data for reports (2.3) | `LeiRegistry`, `GleifGoldenCopyLeiRegistry`, `GleifApiLeiRegistry`, `InstrumentRegistry`, `FirdsInstrumentRegistry`, `MicRegistry`, `Iso10383MicRegistry`, `ReferenceDataChecker`; `ReferenceData`, `LeiValidity`, `CodeRegistry` (2.4); `LeiRecord.entityCategory` (GLEIF category, for the EMIR branch rules) and a FIRDS parse that reports bad bytes only through `IOException` (3.1) |
| `regreporting.cat` (LL) | US CAT order events, indicative (2.3); option and quote events, option lifecycle, supplement and multi-leg events, linkage within a batch and across files, JSON schema check (3.1); the other 47 option and multi-leg events of Tables 60 and 61, record checks only, and a nightly comparison with the official schema (3.2) | `CatEventBuilder` (`start` from 3.2), `CatEvent`, `CatEventType` (MENQ, MONO, MOOR, MONQ, MENOS, MONOS, MOOA, MOOM, MOOC, MOOT, MOOF, MLNO, MLOR, MLOC, MLOS from 3.1; 47 more and `family()` from 3.2), `CatEventFamily` (3.2), `CatJsonLinesSink`, `CatLinkageChecker` (3.1; `checkFiles`, `Batch`, `FileIssue`), `CatSchema` (3.1) |
| `regreporting.emir` (LL) | EMIR Refit derivative reports, indicative (2.3); opt-in stateful rules (3.1): history, receipt time, GLEIF status and category, venue MIC (1003-01), caller trade key (2001-03), "reported for that UTI" dates; fields 2.2-2.4 and 2001-04, column AD codes (3.2) | `EmirRefitReportBuilder`, `EmirRefitReport`, `EmirActionType`, `EmirEventType`; `EmirStatefulValidator`, `UtiHistory`, `InMemoryUtiHistory`, `CurrencyRegistry` (3.1) |
| `indicators` | Technical indicators | `Indicators` (batch), `StreamingIndicators` (one update per tick) |
| `pricing` | Option pricing and volatility surfaces | `BlackScholes`, `Black76`, `Bachelier`, `ImpliedVolatility`, `BinomialTree`, `Heston`, `SabrModel`, `VolSurface`, `BarrierOption`, `DigitalOption`, `TouchOption`, `AsianOption`, `QuantoOption`, `ExchangeOption`, `VarianceSwap`, `VannaVolga`, `HestonCos`, `HestonCalibrator`, `SviSlice`, `SsviSurface`, `SviSurface`, `TotalVarianceSurface`, `DupireLocalVol`, `LocalVolMonteCarlo`, `LocalVolPde`, `DividendTree`, `Autocallable`, `StructuredNotes`, `IncrementalGreeks`, `HigherOrderGreeks` |
| `rates` | Fixed income | `YieldCurve` (single curve), `DiscountCurve`, `MultiCurveBootstrap`, `MultiCurveRisk`, curve instruments (`Deposit`, `Fra`, `RateFuture`, `OisSwap`, `InterestRateSwap`), `BondPricer` (with the 3.1 `...Exact` non-integral-period functions and `convexityAnalytic`), `BootstrapJacobian` (analytic per-quote DV01, 3.1), the 3.1 leg model (`LegConvention`, `FixedLeg`, `FloatLeg` = `OvernightLeg` / `TermRateLeg`, `OvernightConvention`, `FixingHistory`, `VanillaSwap`), `DayCount`, `BusinessCalendar`, `HolidayCalendars`, `SwapPricer` (deprecated since 3.1, see `MIGRATION_3.1.md`), `RatesOptions`, `NelsonSiegel`, `Svensson`, `ShortRateModels`, `KeyRateDurations` |
| `credit` | Credit curves, CDS and counterparty exposure | `CreditCurve`, `CdsPricer`, `IsdaCdsModel`, `IsdaCurve`, `IsdaRateCurve`, `CreditSpreads`, `MultiCurrencyExposureSimulator` (single- and multi-currency; absorbed `ExposureSimulator` in 3.0), `ExposureProfile`, `CvaApproximator` |
| `fx` | FX conventions, instruments and e-FX structure | `CurrencyPair`, `SwapPointsCurve`, `FxSwap`, `Ndf`, `FxVolSurface`, `FixingRisk`, `VannaVolgaTouch`, `AggregatedBook`, `CrossOp`, `FxTierBook`, `LpScorecard`, `LpRouter`, `SyntheticCross` |
| `commodities`, `markets` | Futures curves; index and private-market analytics | `CommodityCurve`, `IndexConstruction`, `PrivateMarketAnalytics` |
| `volatility` | Volatility models | `EwmaVolatility`, `Garch11`, `GjrGarch11`, `Egarch11` (maximum likelihood), `HarRv`, `RangeVolatility`, `VolatilityIndex`, `VolatilityDecomposition`, `InformationCriteria` |
| `risk` | Market, credit and limit risk | `TailRisk`, `VarEngine`, `RiskMetrics`, `FrtbEs`, `PnlAttribution`, `VarBacktest`, `ExtremeValueTheory`, `StressTester`, `ComponentVar`, `CovarianceShrinkage`, `PsdMatrices`, `GaussianCopula`, `Dependence`, `Pca`, `PreTradeLimitChecker`, `CounterpartyExposureTracker`, `SaCcr`, `SaCcrHaircuts`, `FrtbSa`, `FrtbDrc`, `FrtbDrcSecuritisation`, `FrtbRrao`, `FrtbNmrf`, `FactorRiskModel` |
| `crb` | Central risk book | `CentralRiskBook`, `InternalizationEngine`, `SkewedQuoter`, `HedgeOptimizer`, `CrbHedgeUniverse`, `CrbAutoHedger`, `CrbRouter`, `CrbPnlLedger` |
| `hedging` | Hedging | `DeltaHedger`, `HedgingSimulator`, `GreekHedger`, `OptionsBook`, `MinimumVarianceHedge`, `FxHedger`, `PairsHedger`, `CointegrationTest`, `WhalleyWilmott` |
| `optimization` | Portfolio construction | `QuadraticProgram` (active-set), `PortfolioOptimizer`, `ConstrainedPortfolioOptimizer`, `RiskParityOptimizer`, `BlackLitterman`, `NelderMead` |
| `alpha` | Factor research | `AlphaContext`, `Factors`, `SignalEvaluator`, `AlphaValidation`, `FamaMacBeth`, `NeweyWest`, `Ols`, `FundamentalsHistory`, `PortfolioConstruction`, `TurnoverAwareOptimizer`, `AlphaBacktester`, `AlphaRiskModels`, `AlphaCombiner`, `BacktestAttribution`, `CapacityCurve`, `AlphaReport` |
| `microstructure` | Impact, queues, signals, execution models | `AlmgrenChriss`, `MarketImpactModel`, `QueueModel`, `TransactionCostAnalyzer`, `SignalEngine`, `FlowSignals`, `VolumeCurve`, `VolatilityCurve`, `KylesLambda`, `HawkesIntensity`, `Vpin`, `OnlineAlphaLearner`, `AlphaEnsemble`, `EwmaCovariance`, `CircuitBreakers` |
| `execution` | Execution algorithms and routing | `BenchmarkExecutor`, `PovTracker`, `TwapScheduler`, `VwapScheduler`, `ImplementationShortfallScheduler`, `PortfolioExecutor`, `LiquiditySeekingAlgo`, `SmartOrderRouter`, `HftSor`, `AdaptiveSor`, `VenueScorecard` |
| `backtest` | Backtesting | `Backtester`, `BacktestConfig`, `FillPolicy`, `TradeCostModel`, `ExecutionAwareBacktester`, `ExecutionAlgoBacktester`, `TradeAnalytics`; `strategies.SmaCrossStrategy`; `portfolio.PortfolioBacktester`; `tick.TickBacktester`; `validation.WalkForwardAnalyzer`, `GridSearchOptimizer`, `SharpeValidation`, `PurgedKFold`, `BlockBootstrap`, `OverfitProbability` |
| `ml` | Statistical learning | `GradientBoostedRegressor`, `RegimeDetector`, `VolatilityForecaster`, `MarketImpactPredictor`, `IntradayLiquidityForecaster`, `AnomalyDetector` |
| `simulation` | Monte Carlo | `MonteCarloSimulator`, `SimulationResult` |
| `screener`, `dsl` | Screening and rule-based strategies | `StockScreener`, `RankingEngine`, `StrategyBuilder`, `Rules` |
| `regulatory`, `rfq` | Best execution, fixings, RFQ | `BestExecutionAnalyzer`, `FixAnalyzer`, `MarketQualityMetrics`, `RfqAuction`, `RfqDealerScorecard` |
| `persist` | State across sessions | `Checkpoint` |
| `report`, `cli` | Output | `ReportGenerator`, HTML/PDF/XLSX/CSV exporters, `SvgCharts`, `cli.Main` |
| `examples` (LL, not exported) | Demos, benchmarks and sample strategies (separate jar) | `strategies.EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy`, `BollingerBandsStrategy`, `QuickStartDemo`, `LiveTradingDemo`, `HftLatencyBenchmark`, `HftOrderBenchmark`, `HftQuoterBenchmark`, `HftBookBenchmark`, `ScaleBenchmark`, `ShardScaleBenchmark` |

## Design rules

1. **No runtime dependencies.** Main sources use only the JDK. JUnit and JMH are
   test-scope. Where a format needs parsing, the library carries a small reader of
   its own: the CAT schema check (3.1) uses a package-private strict JSON reader in
   `regreporting.cat` rather than a JSON or JSON-Schema library.
2. **Single writer per hot structure.** Each market-data bus has one producer and one
   consumer thread, and each order gateway has one submitting thread and one venue
   thread. The ring buffers and the risk gate's per-symbol state publish with
   acquire/release (`VarHandle`) ordering, so the per-event paths take no locks.
   Locks are used elsewhere: lifecycle and registration methods (`start`, `stop`,
   `subscribe`, `SymbolRegistry.register`, `addOrderListener`) are `synchronized`;
   `FixSession` serialises the journal write and the socket write under one lock;
   `PaperTradingGateway` and `GlobalRiskAggregator.sweep` are synchronized.
3. **Batch and streaming indicators agree.** `Indicators` and `StreamingIndicators`
   are tested for equal outputs on the same input, so a rule backtested on bars
   behaves the same when fed ticks.
4. **Allocation claims are tested.** Every "allocation-free" claim in the javadoc has a
   test in `AllocationClaimsTest` (core) or `LaneAllocationClaimsTest` (lowlatency),
   tagged `allocation`. Each reads the JVM's per-thread allocation counter after
   50,000 warm-up calls and allows at most 256 bytes over a batch of 10,000 calls (one
   object per call would be 160,000 bytes). Older per-class tests require exactly zero
   bytes. A claim that fails its test is fixed or reworded, as `BinanceTradeParser`'s
   and `ShortRateModels`' were in 3.1. Latency results come from the benchmarks described in
   [BENCHMARKS.md](BENCHMARKS.md).
5. **No look-ahead by default.** Backtests fill at the next bar's open, EWMA
   volatility is seeded causally, fundamentals are point-in-time, and the regime
   detector exposes a filtered probability for live use.
6. **Fail closed in the trading lane.** The risk gate rejects non-finite prices, counts
   working orders against position limits, and its kill switch cancels working orders.
   A listener that throws kills the gate rather than failing silently.
7. **Recover state instead of resetting it.** The FIX journal replays real messages
   after a crash, tick sessions can be replayed, walk-forward analysis carries the
   position across folds, and `persist.Checkpoint` writes learned model state
   atomically (forced to disk before the rename) so it survives a restart.
8. **Large public classes are facades.** `Indicators`, `MultiCurrencyExposureSimulator`
   and `FixSession` keep their 3.0 signatures, but since 3.1 the work lives in
   package-private classes beside them (`MovingAverages`, `Oscillators`, ...;
   `MultiCurrencyExposureEngine`; `FixSessionMessages`, `FixSessionParsing`). The plan
   for moving packages between modules in 4.0 is in [DESIGN_4.0.md](DESIGN_4.0.md).

## Module descriptors

The stable jar is the named module `com.quantfinlib`
(`core/src/main/java/module-info.java`) and exports every package of the stable
tier; `internal` is exported only to `com.quantfinlib.lowlatency`. The lane jar is
`com.quantfinlib.lowlatency` (`lowlatency/src/main/java/module-info.java`), which
`requires transitive com.quantfinlib` and exports `trading`, `marketdata`,
`orderbook`, `fix`, `sbe`, `feed`, `entitlements`, `regreporting` (with `cat`,
`emir`, `registry`) and `util`. Up to 2.4 one module exported everything and the
tiers lived only in STABILITY.md.

| Module | Directive | Why |
|---|---|---|
| `com.quantfinlib` | `requires transitive java.net.http` | `data.HttpBarFetcher` |
| `com.quantfinlib` | `requires static org.jspecify` | `@NullMarked` and `@Nullable`; compile time only |
| `com.quantfinlib.lowlatency` | `requires transitive com.quantfinlib` | lane signatures use stable types (`core.Side`, `core.BarSeries`, `backtest.TradingStrategy`) |
| `com.quantfinlib.lowlatency` | `requires java.xml` | the FIRDS parser in `regreporting.registry` |
| `com.quantfinlib.lowlatency` | `requires jdk.httpserver` | `trading.TradingDashboard` |

`feed.WebSocketFeed` and `regreporting.registry.GleifApiLeiRegistry` use
`java.net.http` through the stable module's transitive requirement.
`ModuleDescriptorTest` (stable) and `LowLatencyModuleDescriptorTest` fail if a
package is added without an `exports` line; the stable one also runs `jdeps` on
`core/target/classes` and fails if `jdk.httpserver` appears.

`com.quantfinlib.examples` is compiled with the low-latency module but is not in
its jar and is not exported. It ships as that artifact's `examples` classifier
jar, which has no descriptor, so the demos run on the class path.

## Statistics in one place

`stats.Stats` holds the library's descriptive statistics, each with its convention
written in the Javadoc: sample variance divides by n - 1 and population variance by
n; `quantile` and `quantileSorted` are Hyndman and Fan (1996) type 7;
`quantileType1Sorted` is type 1; `median` is type 7 at 0.5; `ranks` are 1-based
midranks with ties averaged. Empty input gives NaN, the sample variance of fewer than
two values is NaN, and a probability outside [0, 1] or NaN throws
`IllegalArgumentException`.

`risk.Dependence.ranks` delegates to it. `Stats.pearsonOrZero` returns 0 for zero
dispersion, where `Stats.pearson` returns NaN. `util.MathUtils` was removed in 3.0;
its routines moved to `SpecialFunctions`, `LinearAlgebra` and `Numerics` in 2.4.0
unchanged bit for bit (`MovedFromMathUtilsTest` pins the 2.3.0 values).
[MIGRATION_3.0.md](MIGRATION_3.0.md) section 4 maps each method.

Four similar computations stay separate because their conventions differ:
`alpha.AlphaCombiner.pearson` (pairwise-complete, NaN below three pairs),
`risk.TailRisk.varSorted` (type 1 with confidence snapping for the Basel
convention), `util.LatencyRecorder.percentile` (histogram buckets) and the lagged,
mean-supplied autocovariances in `alpha.NeweyWest`.

## Exception policy

A library exception marks a fault in external input: a byte stream, a file or a
configuration. Each one extends the JDK type the same site threw in 2.0, so a catch
block written for 2.0 still catches it. A Java class has one superclass, so no common
base class could extend both `IllegalArgumentException` and
`IllegalStateException`; the common type is the marker interface
`core.QuantFinException`. Test `e instanceof QuantFinException` to tell a library
input fault from any other failure.

| Type (in `core` unless named) | Extends | Thrown by |
|---|---|---|
| `WireProtocolException` | `IllegalStateException` | A stream that breaks its framing: FIX BodyLength in `fix.FixDecoder`, the SBE schema and template header in `sbe.SbeFrames`. Drop the connection and recover by sequence number. |
| `MalformedDataException` | `IllegalArgumentException` | A file or record that does not follow its format: `data.CsvBarLoader.parse`, `data.UniverseCsvLoader.parse` |
| `ConfigurationException` | `IllegalArgumentException` | An invalid configuration or limit: `trading.LimitsConfig` |
| `fix.FixParseException` | `IllegalArgumentException` | A malformed FIX message; predates the scheme and implements the marker |
| `MalformedFileException` | `IOException` | A file with a bad magic number, version or structure (2.2): `marketdata.tick.TickFileReader`, `persist.Checkpoint`, `fix.FileSessionStore`, `execution.VenueScorecard`, `fx.LpScorecard` |
| `marketdata.tick.TickFileReader.TruncatedTickFileException` | `IOException` | A tick file cut short; predates the scheme and implements the marker |

Plain argument validation (a negative size, a null factory, a probability out of
range) stays `IllegalArgumentException`. That is a programming error at the call
site, not an input fault.

The `IOException` header and format checks in `marketdata.tick.TickFileReader`,
`persist.Checkpoint`, `fix.FileSessionStore` and the two scorecards, left as plain
`IOException`s in 2.1, throw `MalformedFileException` from 2.2. It is an
`IOException`, so existing catch blocks still catch it. The `UncheckedIOException` persistence
failures in `fix.FileSessionStore` (a failed write or force, not a bad format) are
unchanged.

## CI and release gates

Every push and pull request runs `ci.yml`: tests on JDK 25 (Ubuntu, Windows,
macOS), the
japicmp API check, and a reproducible-build job (two clean builds, same jar
hashes; `scripts/check-reproducible.sh`). Release builds add a CycloneDX SBOM
(`-Psbom`). Tests that need files we do not commit skip locally; PR CI runs the
committed stand-ins `IsdaGoldenSubsetTest` (48 invented CDS contracts) and
`EsmaXsdGoldenTest` (SHA-256 of the validated documents).

`nightly.yml` runs six jobs: `full-tests` (load and timing tags), `analysis`
(SpotBugs, Error Prone), `reference-files` (downloads the ISDA grids and ESMA
XSDs, checks the SHA-256 pins in `scripts/reference-files.sha256`, and fails on
any skipped reference test), `fuzz` (`-Pfuzz` on the parser fuzz tests at
200,000 cases each, then `PricingInvariantPropertiesTest` at 20,000),
`mutation` (PIT on core) and `mutation-lowlatency` (PIT on regreporting and the
FIX codecs). The two PIT jobs fail when a package falls below its floor in
`scripts/pit-thresholds.txt`.

The API check compares each module with its own released 3.0.0 jar. Core has
no allow-list: 3.x makes no breaking changes ([STABILITY.md](STABILITY.md)).

`release.yml` runs on a `v*` tag: `tag-guard` (refuses a forced push, an
existing release, or a tag name already released from another commit), then
`ci` (the reusable `ci.yml`) and `docs` (javadoc build), then `release`, which
attaches the jars and dispatches `docs.yml` on `main`. The checklist is in
[PUBLISHING.md](PUBLISHING.md); diagram 112 in [DIAGRAMS.md](DIAGRAMS.md).

Loaders bound what they read (zip entries, inflated bytes, line lengths); the
per-loader limits are in [INPUT_LIMITS.md](INPUT_LIMITS.md).

## Further reading

- [GETTING_STARTED.md](GETTING_STARTED.md): build and first runs.
- [LEARN.md](LEARN.md): tutorial from first principles.
- [COOKBOOK.md](COOKBOOK.md): runnable recipes.
- [MARKET_RISK.md](MARKET_RISK.md): the market-risk workflow mapped to classes.
- [INPUT_LIMITS.md](INPUT_LIMITS.md): the size limits of every file loader.
- [PUBLISHING.md](PUBLISHING.md): the release checklist.
- [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md): netting, internalisation and hedging.
- [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md): the trading lane in detail.
- [CHANGELOG.md](../CHANGELOG.md): release history.
