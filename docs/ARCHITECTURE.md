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
   execution reports --> reporting.Rts22ReportBuilder --> TransactionReportSink
                                 (also reporting.cat, reporting.emir builders)
                                                     |
                                     reporting.registry.ReferenceDataChecker
                                     (GLEIF, FIRDS, ISO 10383) --> downstream sink

   venue bust / correction (ExecType H / G)
     --> HftOrderGateway.onTradeBust / onTradeCorrect
     --> FillListener.onBust / onCorrect --> PositionLedger (adjustment entry,
         rebuild from surviving fills) --> DropCopyReconciler (effective fills)
```

A listener that throws is counted in `fillListenerErrorCount` and the gateway
carries on. Without a listener nothing changes from 2.1.

## Package map

| Package | Purpose | Main classes |
|---|---|---|
| `core` | OHLCV series on primitive arrays | `Bar`, `BarSeries` |
| `stats` | Descriptive statistics, one definition each | `Stats` (mean, sample and population variance, covariance, Pearson, Hyndman-Fan type 7 and type 1 quantiles, median, midranks) |
| `util` | Numerics and measurement | `MathUtils` (normal CDF by Cody's erfc algorithm), `LatencyRecorder`, `HiccupMonitor` |
| `data` | Data in and out | `CsvBarLoader`, `HttpBarFetcher`, `SeriesAligner`, `CorporateActions`, `CorporateEvents`, `CorporateEventPositions`, `PointInTimeUniverse`, `UniverseCsvLoader`, `TickFileWriter`/`TickFileReader` (QFLT v2), `TickCapture`, `AsyncTickCapture` |
| `feed` | Live and multicast feeds | `WebSocketFeed`, `BinanceTradeParser`, `MoldUdp64Receiver` |
| `sbe` | SBE-style binary codecs (schema 2) | `MessageHeader`, `Decimal64`, `TradeFlyweight`, `OrderFlyweight`, `QuoteFlyweight`, `BinaryMarketDataClient`, `BinaryOrderPublisher`, `BinaryOrderReceiver` |
| `fix` | FIX session (4.4 default, 4.2, 5.0 SP2) and garbage-free codecs | `FixSession`, `FixSessionSchedule`, `FixSessionOptions`, `FixVersion`, `FixDictionary`, `FixGroupDef`, `FixGroup`, `FileSessionStore` (CRC-32C journal), `FixMessage`, `FixOrderEncoder`, `FixExecReportView`, `FixExecReportView.ExecIdDeduplicator`, `FixMarketDataView` |
| `marketdata` | Trading-lane transport and equities L3 | `HftMarketDataBus`, `TickRingBuffer`, `SymbolRegistry`, `ItchCodec`, `L3BookBuilder`, `Nbbo`; simpler `MarketDataProcessor`, `RingBuffer` |
| `orderbook` | Matching engines and book analytics | `OrderBook` (readable reference), `HftOrderBook` (price ladder, pooled nodes, self-trade prevention), `AuctionBook` (opening and closing call auctions), `BookAnalytics` |
| `trading` | Order entry and pre-trade risk | `HftRiskGate`, `HftOrderGateway`, `OrderStateMachine`, `OrderRingBuffer`, `OrderListener`, `HftQuoter`, `AutoHedger`, `OrderThrottle`, `LastLookGate`, `LoopbackVenue`, `PaperTradingGateway`, `TradingDashboard`, `ShardedTradingEngine`, `GlobalRiskAggregator`, `PositionLedger`, `FillListener`, `LedgerFillListener`, `DropCopyReconciler`, `LimitsConfig`, `LimitsManager` |
| `entitlements` | Who may trade what, where and when | `EntitlementService`, `Grant`, `Permission`, `Decision`, `EntitlementPreTradeCheck` |
| `reporting` | Regulatory transaction reports | `TransactionReport`, `TransactionReportSink`, `Rts22ReportBuilder`, `Rts22TransactionReport`, `ReportingIdentifiers` (`Rts22TransactionReport.toAuth016Xml`, `validateEsmaRules` from 2.3) |
| `reporting.registry` | Reference data for reports (2.3) | `LeiRegistry`, `GleifGoldenCopyLeiRegistry`, `GleifApiLeiRegistry`, `InstrumentRegistry`, `FirdsInstrumentRegistry`, `MicRegistry`, `Iso10383MicRegistry`, `ReferenceDataChecker` |
| `reporting.cat` | US CAT order events, indicative (2.3) | `CatEventBuilder`, `CatEvent`, `CatEventType`, `CatJsonLinesSink` |
| `reporting.emir` | EMIR Refit derivative reports, indicative (2.3) | `EmirRefitReportBuilder`, `EmirRefitReport`, `EmirActionType`, `EmirEventType` |
| `indicators` | Technical indicators | `Indicators` (batch), `StreamingIndicators` (one update per tick) |
| `pricing` | Option pricing and volatility surfaces | `BlackScholes`, `Black76`, `Bachelier`, `ImpliedVolatility`, `BinomialTree`, `Heston`, `SabrModel`, `VolSurface`, `BarrierOption`, `DigitalOption`, `TouchOption`, `AsianOption`, `QuantoOption`, `ExchangeOption`, `VarianceSwap`, `VannaVolga`, `HestonCos`, `HestonCalibrator`, `SviSlice`, `SsviSurface`, `SviSurface`, `TotalVarianceSurface`, `DupireLocalVol`, `LocalVolMonteCarlo`, `LocalVolPde`, `DividendTree`, `Autocallable`, `StructuredNotes`, `IncrementalGreeks`, `HigherOrderGreeks` |
| `rates` | Fixed income | `YieldCurve` (single curve), `DiscountCurve`, `MultiCurveBootstrap`, `MultiCurveRisk`, curve instruments (`Deposit`, `Fra`, `RateFuture`, `OisSwap`, `InterestRateSwap`), `BondPricer`, `DayCount`, `BusinessCalendar`, `HolidayCalendars`, `SwapPricer`, `RatesOptions`, `NelsonSiegel`, `Svensson`, `ShortRateModels`, `KeyRateDurations` |
| `credit` | Credit curves, CDS and counterparty exposure | `CreditCurve`, `CdsPricer`, `IsdaCdsModel`, `IsdaCurve`, `IsdaRateCurve`, `CreditSpreads`, `ExposureSimulator`, `MultiCurrencyExposureSimulator`, `ExposureProfile`, `CvaApproximator` |
| `fx` | FX conventions, instruments and e-FX structure | `CurrencyPair`, `SwapPointsCurve`, `FxSwap`, `Ndf`, `FxVolSurface`, `FixingRisk`, `VannaVolgaTouch`, `AggregatedBook`, `CrossRateEngine`, `FxTierBook`, `LpScorecard`, `LpRouter`, `SyntheticCross` |
| `commodities`, `markets` | Futures curves; index and private-market analytics | `CommodityCurve`, `IndexConstruction`, `PrivateMarketAnalytics` |
| `volatility` | Volatility models | `EwmaVolatility`, `Garch11`, `GjrGarch11`, `Egarch11` (maximum likelihood), `HarRv`, `RangeVolatility`, `VolatilityIndex`, `VolatilityDecomposition`, `InformationCriteria` |
| `risk` | Market, credit and limit risk | `TailRisk`, `VarEngine`, `RiskMetrics`, `FrtbEs`, `PnlAttribution`, `VarBacktest`, `ExtremeValueTheory`, `StressTester`, `ComponentVar`, `CovarianceShrinkage`, `PsdMatrices`, `GaussianCopula`, `Dependence`, `Pca`, `PreTradeLimitChecker`, `CounterpartyExposureTracker`, `SaCcr`, `SaCcrHaircuts`, `FrtbSa`, `FrtbDrc`, `FrtbRrao`, `FrtbNmrf`, `FactorRiskModel` |
| `crb` | Central risk book | `CentralRiskBook`, `InternalizationEngine`, `SkewedQuoter`, `HedgeOptimizer`, `CrbHedgeUniverse`, `CrbAutoHedger`, `CrbRouter`, `CrbPnlLedger` |
| `hedging` | Hedging | `DeltaHedger`, `HedgingSimulator`, `GreekHedger`, `OptionsBook`, `MinimumVarianceHedge`, `FxHedger`, `PairsHedger`, `CointegrationTest`, `WhalleyWilmott` |
| `optimization` | Portfolio construction | `QuadraticProgram` (active-set), `PortfolioOptimizer`, `ConstrainedPortfolioOptimizer`, `RiskParityOptimizer`, `BlackLitterman`, `NelderMead` |
| `alpha` | Factor research | `AlphaContext`, `Factors`, `SignalEvaluator`, `AlphaValidation`, `FamaMacBeth`, `NeweyWest`, `Ols`, `FundamentalsHistory`, `PortfolioConstruction`, `TurnoverAwareOptimizer`, `AlphaBacktester`, `AlphaRiskModels`, `AlphaCombiner`, `BacktestAttribution`, `CapacityCurve`, `AlphaReport` |
| `microstructure` | Impact, queues, signals, execution models | `AlmgrenChriss`, `MarketImpactModel`, `QueueModel`, `TransactionCostAnalyzer`, `SignalEngine`, `FlowSignals`, `VolumeCurve`, `VolatilityCurve`, `KylesLambda`, `HawkesIntensity`, `Vpin`, `OnlineAlphaLearner`, `AlphaEnsemble`, `EwmaCovariance`, `CircuitBreakers` |
| `execution` | Execution algorithms and routing | `BenchmarkExecutor`, `PovTracker`, `TwapScheduler`, `VwapScheduler`, `ImplementationShortfallScheduler`, `PortfolioExecutor`, `LiquiditySeekingAlgo`, `SmartOrderRouter`, `HftSor`, `AdaptiveSor`, `VenueScorecard` |
| `backtest` | Backtesting | `Backtester`, `BacktestConfig`, `FillPolicy`, `TradeCostModel`, `ExecutionAwareBacktester`, `ExecutionAlgoBacktester`, `TradeAnalytics`, `StrategyRunner`; `portfolio.PortfolioBacktester`; `tick.TickBacktester`; `validation.WalkForwardAnalyzer`, `GridSearchOptimizer`, `SharpeValidation`, `PurgedKFold`, `BlockBootstrap`, `OverfitProbability` |
| `ml` | Statistical learning | `GradientBoostedRegressor`, `RegimeDetector`, `VolatilityForecaster`, `MarketImpactPredictor`, `IntradayLiquidityForecaster`, `AnomalyDetector` |
| `simulation` | Monte Carlo | `MonteCarloSimulator`, `SimulationResult` |
| `screener`, `dsl` | Screening and rule-based strategies | `StockScreener`, `RankingEngine`, `StrategyBuilder`, `Rules` |
| `regulatory`, `rfq` | Best execution, fixings, RFQ | `BestExecutionAnalyzer`, `FixAnalyzer`, `MarketQualityMetrics`, `RfqAuction`, `RfqDealerScorecard` |
| `persist` | State across sessions | `Checkpoint` |
| `report`, `cli` | Output | `ReportGenerator`, HTML/PDF/XLSX/CSV exporters, `SvgCharts`, `cli.Main` |
| `examples` | Demos and benchmarks (separate jar) | `QuickStartDemo`, `LiveTradingDemo`, `HftLatencyBenchmark`, `HftOrderBenchmark`, `HftQuoterBenchmark`, `HftBookBenchmark`, `ScaleBenchmark`, `ShardScaleBenchmark` |

## Design rules

1. **No runtime dependencies.** Main sources use only the JDK. JUnit and JMH are
   test-scope.
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
4. **Allocation claims are tested.** Zero-allocation tests read the JVM's per-thread
   allocation counter and require exactly zero bytes after warm-up. Latency results
   come from the benchmarks described in [BENCHMARKS.md](BENCHMARKS.md).
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

## Module descriptor

The library jar is the named module `com.quantfinlib`
(`src/main/java/module-info.java`). It exports every package that was public in 2.0,
the reference packages (`fix`, `sbe`, `feed`, `marketdata`, `orderbook`, `trading`,
`util`), the `stats` package added in 2.1, and `entitlements` and
`reporting` added in 2.2, and `reporting.registry`, `reporting.cat` and
`reporting.emir` added in 2.3. Exporting a package does not make it
stable; the tiers are those of [STABILITY.md](STABILITY.md).

| Directive | Why |
|---|---|
| `requires transitive java.net.http` | `data.HttpBarFetcher`, `feed.WebSocketFeed`, and `reporting.registry.GleifApiLeiRegistry`, whose public constructor takes an `HttpClient` |
| `requires java.xml` | FIRDS and auth.016 XML in `reporting` and `reporting.registry` |
| `requires jdk.httpserver` | `trading.TradingDashboard` |
| `requires static org.jspecify` | `@NullMarked` and `@Nullable` annotations; compile time only, nothing at run time |

`com.quantfinlib.examples` is compiled with the library but is not in the library jar
and is not exported. It ships as the `examples` classifier jar, which has no
descriptor, so the demos run on the class path. A consumer either adds
`requires com.quantfinlib;` to its own `module-info.java` or keeps using the class
path. `ModuleDescriptorTest` fails if a package is added without an `exports` line.

## Statistics in one place

`stats.Stats` holds the library's descriptive statistics, each with its convention
written in the Javadoc: sample variance divides by n - 1 and population variance by
n; `quantile` and `quantileSorted` are Hyndman and Fan (1996) type 7;
`quantileType1Sorted` is type 1; `median` is type 7 at 0.5; `ranks` are 1-based
midranks with ties averaged. Empty input gives NaN, the sample variance of fewer than
two values is NaN, and a probability outside [0, 1] or NaN throws
`IllegalArgumentException`.

The older helpers delegate to it: `MathUtils.mean`, `variance`, `stdDev`, `stdDevP`,
`stdDevSample`, `percentile`, `percentileSorted` and `covariance`, and
`risk.Dependence.ranks`. Results on valid input are unchanged bit for bit.
`MathUtils.correlation` keeps its convention of 0 for zero dispersion, where
`Stats.pearson` returns NaN.

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
| `MalformedFileException` | `IOException` | A file with a bad magic number, version or structure (2.2): `data.TickFileReader`, `persist.Checkpoint`, `fix.FileSessionStore`, `execution.VenueScorecard`, `fx.LpScorecard` |
| `data.TickFileReader.TruncatedTickFileException` | `IOException` | A tick file cut short; predates the scheme and implements the marker |

Plain argument validation (a negative size, a null factory, a probability out of
range) stays `IllegalArgumentException`. That is a programming error at the call
site, not an input fault.

The `IOException` header and format checks in `data.TickFileReader`,
`persist.Checkpoint`, `fix.FileSessionStore` and the two scorecards, left as plain
`IOException`s in 2.1, throw `MalformedFileException` from 2.2. It is an
`IOException`, so existing catch blocks still catch it. The `UncheckedIOException` persistence
failures in `fix.FileSessionStore` (a failed write or force, not a bad format) are
unchanged.

## Further reading

- [GETTING_STARTED.md](GETTING_STARTED.md): build and first runs.
- [LEARN.md](LEARN.md): tutorial from first principles.
- [COOKBOOK.md](COOKBOOK.md): runnable recipes.
- [MARKET_RISK.md](MARKET_RISK.md): the market-risk workflow mapped to classes.
- [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md): netting, internalisation and hedging.
- [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md): the trading lane in detail.
- [CHANGELOG.md](../CHANGELOG.md): release history.
