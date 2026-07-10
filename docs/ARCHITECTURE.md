# Architecture Reference

One page per question: where does a capability live, what are its key classes, and where
are its tests. Every package also carries a `package-info.java` with the same summary in
javadoc form (rendered on the docs site under `api/`). For rendered visual versions of
the flows below — the two-lane architecture, the measured hot path, the alpha pipeline,
per-bar survivorship ordering, matching-engine internals, and the FX instrument map —
see **[DIAGRAMS.md](DIAGRAMS.md)** (Mermaid, renders on GitHub).

## Data flow at a glance

```
                 CSV / HTTP / WebSocket / QFLT tick files / binary SBE
                                      │
                                   [data, feed, sbe]
                                      ▼
   research path                HftMarketDataBus ── TickCapture ──► session.qflt
   ┌──────────────┐                   │ (204ns p50)                     │ replay
   BarSeries ◄────┘                   ▼                                 ▼
   │ indicators / ml /          StreamingIndicators             TickBacktester
   │ screener / risk /                │                        (queue-aware fills)
   │ optimization                     ▼
   ▼                            strategy decision
   Backtester / walk-forward          │
   / portfolio backtests              ▼
   │                            HftRiskGate (~3ns) ─► HftOrderGateway ─► OrderListener
   ▼                                                    (504ns tick→order)     │
   reports (HTML/PDF/XLSX/CSV,                    ┌─────────────┬──────────────┤
   SVG charts), CLI, dashboard              FixSession    PaperTrading   sbe.BinaryOrder
                                            (broker)       Gateway        Publisher
```

## Package map

| Package | Purpose | Key classes | Tests |
|---|---|---|---|
| `core` | Primitive-array OHLCV time series | `Bar`, `BarSeries` | (covered via all consumers) |
| `util` | Numerics + measurement | `MathUtils`, `LatencyRecorder`, `HiccupMonitor` | `LatencyRecorderTest`, `HiccupMonitorTest` |
| `data` | Data in/out and preparation | `CsvBarLoader`, `HttpBarFetcher`, `TickFileWriter/Reader`, `TickCapture` + `AsyncTickCapture` (off-thread QFLT capture), `SeriesAligner`, `CorporateActions`, `PointInTimeUniverse` + `UniverseCsvLoader` (membership + delisting/merger events, CSV interchange format) | `CsvBarLoaderTest`, `HttpBarFetcherTest`, `TickFileTest`, `AsyncTickCaptureTest`, `SeriesAlignerTest`, `CorporateActionsTest`, `PointInTimeUniverseTest`, `UniverseCsvLoaderTest` |
| `feed` | Live WebSocket market data | `WebSocketFeed`, `BinanceTradeParser` | `WebSocketFeedTest` (loopback RFC6455 server) |
| `sbe` | Binary flyweight codecs + channel adapters | `TradeFlyweight`, `OrderFlyweight`, `QuoteFlyweight`, `BinaryMarketDataClient`, `BinaryOrderPublisher/Receiver` | `SbeCodecTest`, `QuoteFlyweightTest` (incl. zero-alloc proofs) |
| `fx` | FX conventions, curves, instruments, e-FX market structure | `CurrencyPair`, `SwapPointsCurve`, `FxSwap`, `Ndf`, `FxVolSurface`, `FixingRisk`, `AggregatedBook`, `CrossRateEngine`, `FxTierBook` (per-LP tier ladders: sweep + full-amount), `LpScorecard` (last-look analytics), `LpRouter` (expected-all-in routing), `SyntheticCross` (direct vs legs) | `CurrencyPairTest`, `SwapPointsCurveTest`, `FxVolSurfaceTest`, `FxSwapNdfTest`, `FixingRiskCrossRateTest`, `AggregatedBookTest`, `FxTierBookTest`, `LpScorecardAndRouterTest`, `SyntheticCrossTest` (all incl. zero-alloc proofs) |
| `marketdata` | Market data transport + equities L3 | HFT lane: `HftMarketDataBus`, `TickRingBuffer`, `SymbolRegistry`; equities: `ItchCodec` (ITCH 5.0-style flyweight), `L3BookBuilder` (full depth + own-order queue position), `Nbbo` (consolidated inside); convenience: `MarketDataProcessor`, `RingBuffer`, `HistoricalDataStore` | `HftPathTest`, `MarketDataTest`, `RingBufferStressTest`, `ItchCodecTest`, `L3BookBuilderTest`, `NbboTest` (incl. zero-alloc + differential proofs) |
| `indicators` | Technical analysis | `Indicators` (21 batch), `StreamingIndicators` (O(1) live) | `IndicatorsTest`, `StreamingIndicatorsTest` (batch/stream parity) |
| `orderbook` | Matching engines (research + venue-grade) + book analytics | `OrderBook` (readable reference), `HftOrderBook` (tick ladder, pooled nodes, zero-alloc, limit/market/IOC/FOK/post-only), `BookPrimitives` (shared bitmap scans + open-addressing map), `BookAnalytics`, `Side` | `OrderBookTest`, `OrderBookInvariantTest` (model-based fuzz), `HftOrderBookTest` (equivalence vs reference + zero-alloc proof), `HftOrderBookTifTest` (incl. FOK-atomicity property test) |
| `alpha` | Factor research pipeline (signal → IC → validation → cost-aware backtest → construction → report) | `AlphaContext`, `AlphaFactor`, `Factors`, `SignalEvaluator`, `AlphaValidation`, `AlphaBacktester`, `PortfolioConstruction`, `AlphaReport` | `FactorsTest`, `SignalEvaluatorTest`, `AlphaValidationTest`, `PortfolioConstructionTest`, `AlphaBacktesterReportTest` |
| `microstructure` | Impact, queues, TCA, optimal execution, exchange mechanics, streaming signals + quant models | `MarketImpactModel`, `QueueModel`, `TransactionCostAnalyzer`, `AlmgrenChriss`, `TickSizeSchedule`, `Auction`, `SignalEngine` (unified multi-symbol: imbalance/vol/liquidity/momentum/composite), `FlowSignals` (OFI + imbalances), `CircuitBreakers` (LULD + market-wide); execution-feeding models: `VolumeCurve` (dynamic intraday volume), `VolatilityCurve` (vol seasonality + regime), `QueuePositionEstimator` (L2 queue position), `HiddenLiquidityDetector` (iceberg inference), `SpreadForecaster` (spread prediction), `TradeClassifier` (Lee-Ready), `FillProbabilityModel` (touch × queue); adaptive layer: `OnlineAlphaLearner` (ridge-SGD alpha weights, prequential out-of-sample IC gate), `LeadLagEstimator` (streaming cross-asset lead-lag), `DayTypeProfiles` (per-day-type seasonality curves), `EwmaCovariance` (streaming basket risk matrix: marginal contribution + min-variance hedge ratios), `KylesLambda` (learned impact — the live `MarketState.impactBps` producer), `JumpRobustVolatility` (bipower jump/diffusion separation), `ClosingAuctionModel` (documented-contract auction reserve), `HawkesIntensity` (self-exciting event bursts, stability-enforced), `AlphaEnsemble` (IC-weighted, confidence-sized signal blend) | `MicrostructureTest`, `AlmgrenChrissTest`, `TickSizeAuctionTest`, `SignalEngineTest`, `FlowSignalsTest`, `CircuitBreakersTest`, `QuantModelsTest`, `QuantModels2Test`, `QuantModels3Test`, `QuantModels4Test`, `QuantModels5Test`, `QuantModels6Test`; the full learning loop end-to-end: `integration.OvernightLearningLoopTest` |
| `pricing` | Fair value & derivatives pricing | `BlackScholes`, `VolSurface`, `SabrModel`, `BinomialTree`, `FairValueEngine`, `TriangularArbitrage`, `ForwardCurve`, `VannaVolga`, `DigitalOption`, `TouchOption`, `BarrierOption`, `DividendSchedule`, `IncrementalGreeks`, `Autocallable` (memory coupons, knock-in; MC + antithetic, zero-vol cases exact), `Black76` (futures/forward options + implied vol), `Heston` (semi-analytic stochastic vol, stable little-trap form, MC cross-checked), `HigherOrderGreeks` (vanna/volga/exchange cross-gamma, pinned as finite differences) | `BlackScholesTest`, `VolSurfaceTest`, `AmericanAndSabrTest`, `PricingTest`, `ExoticOptionsTest` (MC cross-checked), `VannaVolgaTest`, `DividendScheduleTest`, `IncrementalGreeksTest`, `AutocallableRfqTest`, `MarketRiskPricingTest` |
| `rates` | Fixed income | `YieldCurve`, `BondPricer`, `DayCount`, `BusinessCalendar`, `ShortRateModels` (Vasicek/CIR/curve-fitted Hull-White: closed-form bonds + simulation steps), `KeyRateDurations` (per-node DV01 slices that sum to the parallel move) | `RatesTest`, `ConventionsTest`, `MarketRiskPricingTest` (in `pricing`) |
| `volatility` | Vol models | `EwmaVolatility`, `Garch11`, `GjrGarch11` (leverage-effect asymmetry, MLE; likelihood-beats the symmetric fit on asymmetric data — tested) | `VolatilityModelsTest`, `MarketRiskTest` (in `risk`) |
| `risk` | Portfolio/credit/limit/validation risk + the market-risk workflow ([MARKET_RISK.md](MARKET_RISK.md)) | `RiskMetrics`, `PortfolioRiskAnalyzer`, `VarBacktest`, `PreTradeLimitChecker`, `CounterpartyExposureTracker`, `SettlementRiskAnalyzer`, `ConcentrationRisk`; market risk: `Dependence` (Spearman/Kendall/τ→ρ bridge), `Pca` (Jacobi), `GaussianCopula` (+ Student-t tail dependence), `VarEngine` (delta-normal/MC/delta-gamma/historical, each with ES), `ExtremeValueTheory` (POT/GPD, refuses infinite-mean ES), `StressTester` (scenarios/ladders/closed-form reverse stress), `FrtbEs` (97.5% ES liquidity-horizon cascade + Basel traffic light — styled after MAR33, not certified), `PnlAttribution` (FRTB PLAT) | `RiskMetricsTest`, `VarBacktestTest`, `CreditAndLimitsTest`, `MarketRiskTest` |
| `hedging` | Hedging algorithms | `DeltaHedger`, `HedgingSimulator`, `GreekHedger`, `OptionsBook`, `MinimumVarianceHedge`, `FxHedger`, `PairsHedger`, `CointegrationTest` | `HedgingTest`, `HedgingSimulatorTest`, `OptionsBookTest`, `CointegrationTestTest` |
| `crb` | Central risk book ([CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md)) | `CentralRiskBook` (equities cash/options + FX spot/swap/NDF/options → one factor space; currency-level FX netting; diversification-benefit report; checkpointable), `FactorRegistry`, `SkewedQuoter` (inventory-shaded quotes), `InternalizationEngine` (improvement for risk-reducing flow, warehouse limit for risk-adding; checkpointable counters), `CrbHedgeUniverse` (loadings-matrix builder — FX forwards, index futures, outrights), `HedgeOptimizer` (min-variance + L1 cost, soft-threshold coordinate descent), `CrbAutoHedger` (band breach → hedge the excess; hard limit outranks cost), `CrbRouter` (internal cross → adverse-selection-priced dark → lit), `CrbPnlLedger` (spread captured vs hedge/router costs — the desk's realized economics) | `CrbBookTest`, `CrbExecutionTest`, `CrbPersistenceAndUniverseTest`, `CrbRealWorldScenarioTest` |
| `optimization` | Portfolio construction | `PortfolioOptimizer`, `RiskParityOptimizer`, `BlackLitterman`, `ConstrainedPortfolioOptimizer` | `PortfolioOptimizerTest`, `PortfolioConstructionTest` |
| `ml` | Statistical learning | `GradientBoostedRegressor`, `VolatilityForecaster`, `RegimeDetector`, `MarketImpactPredictor`, `IntradayLiquidityForecaster`, `AnomalyDetector` | `MlForecastingTest`, `MarketMlTest`, `RegimeDetectorTest` |
| `backtest` | Bar backtesting + execution/cost models | `Backtester`, `ExecutionAwareBacktester`, `Instant/Sor/Iceberg/LastLookExecution`, `TradeCostModel` (flat + institutional w/ sqrt impact, shared across engines), `ExecutionAlgoBacktester` (grade each benchmark algo TCA-style over a session's bars) | `BacktesterTest`, `ExecutionAwareBacktesterTest`, `LastLookExecutionTest`, `TradeCostModelTest`, `ExecutionAlgoBacktesterTest` |
| `backtest.strategies` | Built-in strategies | SMA/EMA cross, RSI, MACD, Bollinger | (covered by backtest/CLI tests) |
| `backtest.validation` | Overfitting defense | `WalkForwardAnalyzer`, `GridSearchOptimizer`, `SharpeValidation`, `ParameterGrid` | `ValidationTest` |
| `backtest.portfolio` | Multi-asset backtesting | `PortfolioBacktester` (survivorship-aware overload: delistings, mergers, index drops, ex-date cash dividends), `CrossSectionalMomentum` (point-in-time 12-1 long/short), `PositionSizing` | `PortfolioBacktestTest`, `SurvivorshipBacktestTest`, `CrossSectionalMomentumTest` |
| `backtest.tick` | Tick-level backtesting | `TickBacktester` (queue-aware fills, flat or banded tick grid) | `TickBacktesterTest` |
| `dsl` | Strategy builder | `StrategyBuilder`, `Rules`, `Rule` | `StrategyBuilderTest` |
| `screener` | Stock screening | `StockScreener`, `TechnicalFilters`, `FundamentalFilters`, `RankingEngine` | `ScreenerTest` |
| `simulation` | Monte Carlo | `MonteCarloSimulator`, `SimulationResult` | `MonteCarloTest` |
| `persist` | Multi-day persistence of learned state | `Checkpoint` (atomic named-section binary file; `writeState`/`readState` on `VolumeCurve`, `VolatilityCurve`, `SpreadForecaster`, `OnlineAlphaLearner`, `LeadLagEstimator`, `EwmaCovariance`, `KylesLambda`, `ClosingAuctionModel`, `AlphaEnsemble`, `VenueScorecard` (v2, reads v1), `LpScorecard` (v2, reads v1), `rfq.RfqDealerScorecard` — learned state survives the overnight, intraday state resets) | `CheckpointTest`, `integration.OvernightLearningLoopTest` (five days through the write/restore seam) |
| `execution` | Execution algos & venues, smart order routing | `SmartOrderRouter` (readable, fee-adjusted) + `HftSor` (zero-alloc tick path) + `AdaptiveSor` (full checklist: displayed + hidden liquidity, fees/rebates, latency, fill probability, reliability veto, dark probes) with `VenueScorecard` (streaming fill-rate/latency/hidden-liquidity), `BenchmarkExecutor` (dynamic VWAP/TWAP/Arrival/IS/Close/Open/POV over live market state), static schedulers `Twap/VwapScheduler`, `PovTracker`, `ImplementationShortfallScheduler` (Almgren-Chriss), `WmrFixingScheduler`, `IcebergOrder`, `DarkPoolSimulator`, `MidPegTracker`, `VenueBenchmark`, `PortfolioExecutor` (multi-symbol basket scheduling: leg-balance band + interval budget, risk-weighted by vol regime or — via `useRiskModel(EwmaCovariance)` — by marginal basket risk); `LiquiditySeekingAlgo` (opportunistic bursts on forecast-relative cheapness + completion floor); `VenueScorecard` also learns post-fill markout (adverse selection), priced into `AdaptiveSor`'s expected cost | `ExecutionTest`, `HftSorTest`, `AdaptiveSorTest`, `BenchmarkExecutorTest`, `PovAndIsSchedulerTest`, `WmrFixingSchedulerTest`, `PortfolioExecutorTest`, `LiquiditySeekingAlgoTest` |
| `rfq` | RFQ market structure for equity derivatives | `RfqAuction` (best/cover by client direction, spread to model fair value), `RfqDealerScorecard` (streaming panel quality: quote rate, response, spread-to-fair, win rate — persistable) | `AutocallableRfqTest` (in `pricing`) |
| `regulatory` | Surveillance & best-ex | `FixAnalyzer`, `BestExecutionAnalyzer`, `MarketQualityMetrics` | `RegulatoryTest` |
| `trading` | Order entry (paper + HFT lane) + scale-out | `OrderGateway`, `PaperTradingGateway`, `TradingDashboard`; fast lane: `HftRiskGate`, `OrderRingBuffer`, `HftOrderGateway`, `HftQuoter` + `AvellanedaStoikov` (closed-form reservation price / optimal spread; tested in `microstructure.QuantModels4Test`), `AutoHedger`, `OrderThrottle` (message-rate token bucket), `LastLookGate` (symmetric maker-side check); scale-out: `ShardedTradingEngine`, `GlobalRiskAggregator` | `PaperTradingTest`, `TradingDashboardTest`, `HftOrderPathTest`, `HftQuoterAutoHedgerTest`, `OrderThrottleTest`, `LastLookGateTest`, `ShardedTradingTest`, `LoadAndSoakTest` |
| `fix` | FIX 4.4 engine + garbage-free hot path | `FixSession` (+persistence: `FileSessionStore`), `FixMessage`, typed app messages; hot path: `FixOrderEncoder` (orders out), `FixExecReportView` (fills in), `FixMarketDataView` (35=W/X quotes in), shared `FixParse` | `FixMessageTest`, `FixSessionTest`, `FixPersistenceTest`, `FixProtocolExtrasTest`, `FixOrderEncoderTest`, `FixExecReportViewTest`, `FixMarketDataViewTest` |
| `report` | Reporting | `ReportGenerator`, HTML/CSV/PDF/XLSX exporters, `SvgCharts` | `ReportTest`, `SvgChartsTest` |
| `cli` | Command line | `Main` (backtest / walkforward / report) | `CliTest` |
| `examples` | Runnable demos & benchmarks (coverage-excluded) | `QuickStartDemo`, `LiveTradingDemo` (Binance → paper venue → dashboard), `HftLatencyBenchmark`, `HftOrderBenchmark`, `HftQuoterBenchmark`, `HftBookBenchmark`, `ScaleBenchmark`, `ShardScaleBenchmark` | run manually / bench workflow |

## Design invariants worth knowing

1. **Zero runtime dependencies** — main sources use only the JDK. JUnit/JMH are test scope.
2. **Hot paths are single-producer/single-consumer** — one bus per feed, one gateway per
   trading thread; correctness comes from acquire/release ordering, not locks.
3. **Batch and streaming indicators are bit-identical** — verified by parity tests, so
   backtested behavior transfers to live execution exactly.
4. **Performance claims are tests** — zero-allocation is asserted with the JVM's
   per-thread allocation counter; FIFO under stress is asserted across threads;
   latency numbers come from committed, re-runnable benchmarks.
5. **Recovery over reset** — FIX gaps are resent, tick sessions are replayable,
   walk-forward carries capital across folds: state is continuous, not restarted.
   The `persist` package is this invariant across the overnight: learned model
   state (curves, alpha weights + their IC evidence, venue/LP scorecards) survives
   via `Checkpoint`'s atomic named-section files, while intraday state deliberately
   resets on restore. New cross-session continuity belongs in that
   `writeState`/`readState` contract, not ad-hoc serialization.

## Further reading

- [LEARN.md](LEARN.md) — the from-zero tutorial: every concept above explained for
  beginners, with a guided reading path through these packages.
- [COOKBOOK.md](COOKBOOK.md) — fourteen runnable recipes across the capabilities.
- [MARKET_RISK.md](MARKET_RISK.md) — the 14-step market-risk workflow mapped to classes.
- [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md) — the central risk book: cross-product netting, internalization, cost-aware hedging, internal-first routing.
- `docs/ULTRA_LOW_LATENCY.md` — the latency stack: in-library techniques, JVM flags,
  kernel/CPU tuning (`scripts/linux-tune.sh`), and the kernel-bypass/hardware frontier.
- `CHANGELOG.md` — release history; `README.md` — capability tour with examples.
