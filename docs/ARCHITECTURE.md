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
| `data` | Data in/out and preparation | `CsvBarLoader`, `HttpBarFetcher`, `TickFileWriter/Reader`, `TickCapture`, `SeriesAligner`, `CorporateActions`, `PointInTimeUniverse` + `UniverseCsvLoader` (membership + delisting/merger events, CSV interchange format) | `CsvBarLoaderTest`, `HttpBarFetcherTest`, `TickFileTest`, `SeriesAlignerTest`, `CorporateActionsTest`, `PointInTimeUniverseTest`, `UniverseCsvLoaderTest` |
| `feed` | Live WebSocket market data | `WebSocketFeed`, `BinanceTradeParser` | `WebSocketFeedTest` (loopback RFC6455 server) |
| `sbe` | Binary flyweight codecs + channel adapters | `TradeFlyweight`, `OrderFlyweight`, `QuoteFlyweight`, `BinaryMarketDataClient`, `BinaryOrderPublisher/Receiver` | `SbeCodecTest`, `QuoteFlyweightTest` (incl. zero-alloc proofs) |
| `fx` | FX conventions, curves, instruments, e-FX | `CurrencyPair`, `SwapPointsCurve`, `FxSwap`, `Ndf`, `FxVolSurface`, `FixingRisk`, `AggregatedBook`, `CrossRateEngine` | `CurrencyPairTest`, `SwapPointsCurveTest`, `FxVolSurfaceTest`, `FxSwapNdfTest`, `FixingRiskCrossRateTest`, `AggregatedBookTest` (incl. zero-alloc proof) |
| `marketdata` | Market data transport | HFT lane: `HftMarketDataBus`, `TickRingBuffer`, `SymbolRegistry`; convenience: `MarketDataProcessor`, `RingBuffer`, `HistoricalDataStore` | `HftPathTest`, `MarketDataTest`, `RingBufferStressTest` |
| `indicators` | Technical analysis | `Indicators` (21 batch), `StreamingIndicators` (O(1) live) | `IndicatorsTest`, `StreamingIndicatorsTest` (batch/stream parity) |
| `orderbook` | Matching engines (research + venue-grade) + book analytics | `OrderBook` (readable reference), `HftOrderBook` (tick ladder, pooled nodes, zero-alloc), `BookAnalytics`, `Side` | `OrderBookTest`, `OrderBookInvariantTest` (model-based fuzz), `HftOrderBookTest` (equivalence vs reference + zero-alloc proof) |
| `alpha` | Factor research pipeline (signal → IC → validation → cost-aware backtest → construction → report) | `AlphaContext`, `AlphaFactor`, `Factors`, `SignalEvaluator`, `AlphaValidation`, `AlphaBacktester`, `PortfolioConstruction`, `AlphaReport` | `FactorsTest`, `SignalEvaluatorTest`, `AlphaValidationTest`, `PortfolioConstructionTest`, `AlphaBacktesterReportTest` |
| `microstructure` | Impact, queues, TCA, optimal execution, exchange mechanics | `MarketImpactModel`, `QueueModel`, `TransactionCostAnalyzer`, `AlmgrenChriss`, `TickSizeSchedule`, `Auction` | `MicrostructureTest`, `AlmgrenChrissTest`, `TickSizeAuctionTest` |
| `pricing` | Fair value & derivatives pricing | `BlackScholes`, `VolSurface`, `SabrModel`, `BinomialTree`, `FairValueEngine`, `TriangularArbitrage`, `ForwardCurve`, `VannaVolga`, `DigitalOption`, `TouchOption`, `BarrierOption`, `DividendSchedule`, `IncrementalGreeks` | `BlackScholesTest`, `VolSurfaceTest`, `AmericanAndSabrTest`, `PricingTest`, `ExoticOptionsTest` (MC cross-checked), `VannaVolgaTest`, `DividendScheduleTest`, `IncrementalGreeksTest` |
| `rates` | Fixed income | `YieldCurve`, `BondPricer`, `DayCount`, `BusinessCalendar` | `RatesTest`, `ConventionsTest` |
| `volatility` | Vol models | `EwmaVolatility`, `Garch11` | `VolatilityModelsTest` |
| `risk` | Portfolio/credit/limit/validation risk | `RiskMetrics`, `PortfolioRiskAnalyzer`, `VarBacktest`, `PreTradeLimitChecker`, `CounterpartyExposureTracker`, `SettlementRiskAnalyzer`, `ConcentrationRisk` | `RiskMetricsTest`, `VarBacktestTest`, `CreditAndLimitsTest` |
| `hedging` | Hedging algorithms | `DeltaHedger`, `HedgingSimulator`, `GreekHedger`, `OptionsBook`, `MinimumVarianceHedge`, `FxHedger`, `PairsHedger`, `CointegrationTest` | `HedgingTest`, `HedgingSimulatorTest`, `OptionsBookTest`, `CointegrationTestTest` |
| `optimization` | Portfolio construction | `PortfolioOptimizer`, `RiskParityOptimizer`, `BlackLitterman`, `ConstrainedPortfolioOptimizer` | `PortfolioOptimizerTest`, `PortfolioConstructionTest` |
| `ml` | Statistical learning | `GradientBoostedRegressor`, `VolatilityForecaster`, `RegimeDetector`, `MarketImpactPredictor`, `IntradayLiquidityForecaster`, `AnomalyDetector` | `MlForecastingTest`, `MarketMlTest`, `RegimeDetectorTest` |
| `backtest` | Bar backtesting + execution/cost models | `Backtester`, `ExecutionAwareBacktester`, `Instant/Sor/Iceberg/LastLookExecution`, `TradeCostModel` (flat + institutional w/ sqrt impact, shared across engines) | `BacktesterTest`, `ExecutionAwareBacktesterTest`, `LastLookExecutionTest`, `TradeCostModelTest` |
| `backtest.strategies` | Built-in strategies | SMA/EMA cross, RSI, MACD, Bollinger | (covered by backtest/CLI tests) |
| `backtest.validation` | Overfitting defense | `WalkForwardAnalyzer`, `GridSearchOptimizer`, `SharpeValidation`, `ParameterGrid` | `ValidationTest` |
| `backtest.portfolio` | Multi-asset backtesting | `PortfolioBacktester` (survivorship-aware overload: delistings, mergers, index drops, ex-date cash dividends), `CrossSectionalMomentum` (point-in-time 12-1 long/short), `PositionSizing` | `PortfolioBacktestTest`, `SurvivorshipBacktestTest`, `CrossSectionalMomentumTest` |
| `backtest.tick` | Tick-level backtesting | `TickBacktester` (queue-aware fills, flat or banded tick grid) | `TickBacktesterTest` |
| `dsl` | Strategy builder | `StrategyBuilder`, `Rules`, `Rule` | `StrategyBuilderTest` |
| `screener` | Stock screening | `StockScreener`, `TechnicalFilters`, `FundamentalFilters`, `RankingEngine` | `ScreenerTest` |
| `simulation` | Monte Carlo | `MonteCarloSimulator`, `SimulationResult` | `MonteCarloTest` |
| `execution` | Execution algos & venues | `SmartOrderRouter`, `Twap/VwapScheduler`, `IcebergOrder`, `DarkPoolSimulator`, `MidPegTracker`, `VenueBenchmark` | `ExecutionTest` |
| `regulatory` | Surveillance & best-ex | `FixAnalyzer`, `BestExecutionAnalyzer`, `MarketQualityMetrics` | `RegulatoryTest` |
| `trading` | Order entry (paper + HFT lane) | `OrderGateway`, `PaperTradingGateway`, `TradingDashboard`; fast lane: `HftRiskGate`, `OrderRingBuffer`, `HftOrderGateway`, `HftQuoter`, `AutoHedger` | `PaperTradingTest`, `TradingDashboardTest`, `HftOrderPathTest`, `HftQuoterAutoHedgerTest` |
| `fix` | FIX 4.4 engine | `FixSession` (+persistence: `FileSessionStore`), `FixMessage`, typed app messages | `FixMessageTest`, `FixSessionTest`, `FixPersistenceTest`, `FixProtocolExtrasTest` |
| `report` | Reporting | `ReportGenerator`, HTML/CSV/PDF/XLSX exporters, `SvgCharts` | `ReportTest`, `SvgChartsTest` |
| `cli` | Command line | `Main` (backtest / walkforward / report) | `CliTest` |
| `examples` | Runnable demos & benchmarks (coverage-excluded) | `QuickStartDemo`, `HftLatencyBenchmark`, `HftOrderBenchmark`, `HftQuoterBenchmark`, `HftBookBenchmark` | run manually / bench workflow |

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

## Further reading

- `docs/ULTRA_LOW_LATENCY.md` — the latency stack: in-library techniques, JVM flags,
  kernel/CPU tuning (`scripts/linux-tune.sh`), and the kernel-bypass/hardware frontier.
- `CHANGELOG.md` — release history; `README.md` — capability tour with examples.
