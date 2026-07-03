# Quant-Finance-Library (quantfinlib)

**quantfinlib** is a production-ready quantitative finance platform for the JVM that unifies
risk management, portfolio optimization, machine learning, technical analysis, strategy
development, market screening, Monte Carlo simulation, reporting, and algorithmic research
in a single Java library — for multi-asset workflows (FX, equities, commodities, and more).

Built for quantitative researchers, algorithmic traders, developers, fintech teams, and
financial professionals: focus on building winning strategies while the platform handles
everything from backtesting to reporting.

- **Ultra low latency / HFT-grade hot path** — a zero-allocation, lock-free tick
  pipeline (Disruptor-style primitive ring buffer, dense int symbol ids, O(1) streaming
  indicators) measured at **~200 ns median publish-to-strategy latency** and
  **9-12M ticks/sec sustained** with a live strategy attached. See
  [Ultra-Low-Latency / HFT Path](#ultra-low-latency--hft-path).
- **Production-ready** — deterministic seeded algorithms, NaN-safe indicator warm-ups,
  gap-aware stop-loss/take-profit fills, and a full JUnit 5 test suite.
- **Modular architecture, clean APIs** — each capability lives in its own package with
  small, composable interfaces (`RiskMetric`, `TradingStrategy`, `Rule`, `ScreenFilter`,
  `ReportExporter`) for extension.

## Requirements

- JDK 24+
- Maven 3.9+

```bash
mvn test                                                    # build + full test suite
java -cp target/classes com.quantfinlib.examples.QuickStartDemo # end-to-end tour
```

## The 11 Capabilities

### 1. Advanced Risk Analysis — `com.quantfinlib.risk`
Portfolio and asset-level risk with a flexible analytics engine: volatility, exposure,
correlation, multi-asset support, and risk decomposition (fractional contribution of each
asset to portfolio variance).

```java
PortfolioRiskAnalyzer analyzer = new PortfolioRiskAnalyzer(symbols, assetReturns, weights);
PortfolioRiskAnalyzer.RiskReport risk = analyzer.analyze(0.95, 252);
risk.annualizedVolatility(); risk.valueAtRisk(); risk.riskContributions(); risk.correlationMatrix();
```

### 2. Real-Time Market Data Processing — `com.quantfinlib.marketdata`
Lock-free ring buffer ingestion, per-symbol subscriptions, a latest-price cache,
continuous portfolio mark-to-market, and an in-memory historical store.

```java
try (MarketDataProcessor mdp = new MarketDataProcessor()) {
    mdp.monitor(portfolio);                       // continuous portfolio monitoring
    mdp.subscribe("EURUSD", e -> onTick(e));
    mdp.start();
    mdp.publish(new MarketDataEvent("EURUSD", 1.0850, 1_000_000, System.nanoTime()));
}
```

### 3. Machine Learning Risk Forecasting — `com.quantfinlib.ml`
Gradient-boosted trees (XGBoost-style additive boosting, pure Java) over engineered
volatility/momentum features predict forward realized volatility and produce a 0–100
risk score.

```java
VolatilityForecaster f = VolatilityForecaster.weekly().fit(returns);
double nextWeekVol = f.forecast(returns);
double score = f.riskScore(returns);              // 0-100 intelligent risk score
```

### 4. Portfolio Optimization Engine — `com.quantfinlib.optimization`
Maximum Sharpe, minimum volatility, efficient frontier, and rebalancing deltas
(long-only, fully invested; deterministic stochastic search + refinement, no solver
dependency).

```java
PortfolioOptimizer opt = new PortfolioOptimizer(expectedReturns, covariance);
Allocation best = opt.maxSharpe(0.02);
Allocation safest = opt.minVolatility();
List<Allocation> frontier = opt.efficientFrontier(20);
```

### 5. Custom Risk Metrics Framework — `com.quantfinlib.risk`
Built-in VaR / CVaR / Expected Shortfall / volatility / downside deviation plus
user-defined metrics through a one-method interface.

```java
Map<String, Double> all = RiskMetricRegistry.withDefaults()
        .register("WorstDay", r -> -Arrays.stream(r).min().orElse(0))
        .calculateAll(portfolioReturns);
```

### 6. Advanced Strategy Backtesting Engine — `com.quantfinlib.backtest`
Built-in SMA / EMA / RSI / MACD / Bollinger / MA-cross strategies; commission, slippage,
intrabar stop-loss/take-profit with gap-aware fills; full analytics: CAGR, annual return,
Sharpe, Sortino, Calmar, profit factor, win rate, max drawdown, equity curve, trade history.

```java
BacktestResult r = Backtester.run(new SmaCrossStrategy(20, 50), series, BacktestConfig.defaults());
r.metrics().sharpeRatio(); r.equityCurve(); r.trades();
```

### 7. Professional Stock Screener — `com.quantfinlib.screener`
Technical filters (RSI, EMA/SMA, MACD, ADX, ATR, VWAP, SuperTrend, Bollinger, Ichimoku,
breakout, volume spike, gap, 52-week high/low) and fundamental filters (market cap, P/E,
P/B, EPS, ROE, dividend yield, debt/equity), plus a weighted ranking engine and CSV export.

```java
var ranked = new StockScreener(universe).screenAndRank(
        new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe())
                .addCriterion("P/E", -0.5, s -> s.fundamentals().peRatio()),
        FundamentalFilters.marketCapAbove(10e9),
        TechnicalFilters.rsiBelow(14, 70).and(TechnicalFilters.priceAboveSma(200)));
StockScreener.exportCsv(Path.of("screen.csv"), ranked);
```

### 8. Monte Carlo Portfolio Simulation — `com.quantfinlib.simulation`
10k–100k+ correlated multi-asset GBM scenarios in parallel: probability of profit/loss,
VaR, CVaR, confidence intervals, best/worst case, expected and median outcome.
Deterministic for a given seed.

```java
SimulationResult sim = new MonteCarloSimulator(42)
        .simulatePortfolio(1_000_000, weights, dailyMeans, dailyCov, 252, 100_000);
sim.probabilityOfProfit(); sim.valueAtRisk(0.95); sim.confidenceInterval(0.90);
```

### 9. Technical Indicator Engine — `com.quantfinlib.indicators`
RSI, SMA, EMA, WMA, VWAP, MACD, ATR, ADX, CCI, ROC, Momentum, OBV, CMF, SuperTrend,
Ichimoku Cloud, Stochastic RSI, Williams %R, Parabolic SAR, Bollinger Bands, Keltner
Channel, Donchian Channel. All return primitive arrays aligned to the input series
(NaN during warm-up) for seamless integration into quantitative workflows.

```java
double[] rsi = Indicators.rsi(series.closes(), 14);
Indicators.Macd macd = Indicators.macd(series.closes(), 12, 26, 9);
Indicators.SuperTrend st = Indicators.superTrend(series, 10, 3);
```

### 10. Professional Report Generator — `com.quantfinlib.report`
Portfolio summary, performance analytics, risk analysis, asset allocation, strategy
performance, trade history, Monte Carlo results, and technical summaries — exported to
**PDF**, **Excel (.xlsx)**, **HTML**, or **CSV**, all written natively with zero
dependencies. Implement `ReportExporter` for custom formats.

```java
new ReportGenerator("Q3 Portfolio Review")
        .addPortfolioSummary(portfolio)
        .addStrategyPerformance(backtest)
        .addRiskAnalysis(risk)
        .addMonteCarlo(sim)
        .addTradeHistory(backtest.trades())
        .toPdf(Path.of("review.pdf"));    // or .toExcel / .toHtml / .toCsv
```

### 11. Strategy Builder DSL — `com.quantfinlib.dsl`
Fluent API: define entry rules, exit conditions, stop loss, take profit — then backtest,
evaluate, and iterate quickly. Rules compose with `and` / `or` / `not`.

```java
BacktestResult r = StrategyBuilder.named("EMA momentum")
        .enterWhen(Rules.crossAbove(fastEma, slowEma).and(Rules.aboveValue(adx, 20)))
        .exitWhen(Rules.crossBelow(fastEma, slowEma))
        .withStopLoss(0.03)
        .withTakeProfit(0.08)
        .build()
        .backtest(series, 100_000);
```

## Trading & Execution Stack

Beyond the 11 research capabilities, the library ships a full trading-side stack across
six areas:

### 1. Market Microstructure & Liquidity Analytics — `orderbook`, `microstructure`
- **`OrderBook`** — price-time-priority matching engine: placement, cancels, partial
  fills, trade callbacks, queue position (`qtyAhead`), and message counters for
  order-to-trade ratio.
- **`BookAnalytics`** — quoted spread (bps), size-weighted microprice, depth imbalance,
  depth-within-bps, and non-destructive **sweep simulation** (VWAP-to-fill + impact of a
  large marketable order).
- **`QueueModel`** — fill probability from queue position, and the fill-probability edge
  bought by a latency advantage.
- **`MarketImpactModel`** — square-root law plus Almgren-Chriss-style temporary/permanent
  decomposition and expected schedule cost.
- **`TransactionCostAnalyzer`** — implementation shortfall vs arrival mid, slippage vs
  interval VWAP (or synthetic forward), effective spread per fill.

### 2. Pricing & Fair Value Construction — `pricing`
- **`FairValueEngine`** — microprice + mid-drift estimation → **latency-adjusted fair
  price** ("true mid" projected to when your order actually arrives).
- **`TriangularArbitrage`** — executable (bid/ask-based) round-trip edge across three FX
  pairs.
- **`ForwardCurve`** — implied forward curve construction with interpolation, implied
  rate differentials, and covered-interest-parity mispricing checks.

### 3. Surveillance / Credit / Limits Risk Models — `risk`
- **`CounterpartyExposureTracker`** — netted current exposure + tenor-bucketed potential
  future exposure (BIS CEM-style add-ons).
- **`PreTradeLimitChecker`** — synchronous order gate: size, notional, position, price
  collar, restricted symbols, counterparty credit headroom.
- **`SettlementRiskAnalyzer`** — Herstatt exposure per counterparty and peak intraday
  settlement exposure.
- **`ConcentrationRisk`** — HHI, effective positions, top-N share, single-name limit
  breaches.

### 4. Statistical & ML Applications — `ml`
- **`MarketImpactPredictor`** — gradient-boosted impact prediction + calibrated
  book-sweep probability.
- **`IntradayLiquidityForecaster`** — seasonal volume profiles, session peaks
  (Tokyo/London/NY), VWAP-ready weight curves.
- **`AnomalyDetector`** — quote-stuffing detection (message-rate z-score × order-to-trade
  ratio) and price-spike surveillance.

### 5. Execution Strategy Support — `execution`
- **`TwapScheduler` / `VwapScheduler`** — schedule design with anti-gaming randomization
  and exact largest-remainder quantity allocation.
- **`MidPegTracker`** — mid-rate pegging with offset, limit cap, and reprice-threshold
  logic.
- **`SmartOrderRouter`** — fee-adjusted multi-venue splitting with displayed-size limits
  and dark-first routing.
- **`IcebergOrder`** — display/reload state machine with randomized tranche sizes.
- **`DarkPoolSimulator`** — midpoint-cross venue with minimum-execution-quantity
  constraints.
- **`VenueBenchmark`** — fill rate, latency-to-fill, effective spread, and post-trade
  markout per venue, ranked.

### 6. Benchmark & Regulatory Metrics — `regulatory`
- **`FixAnalyzer`** — WM/Reuters-style fix calculation (median of window samples) and
  "banging the close" screening (participation × aligned run-up × reversion).
- **`BestExecutionAnalyzer`** — MiFID II-style report: slippage, latency-to-fill,
  price-improvement rate, per-venue breakdown.
- **`MarketQualityMetrics`** — quoted/effective/realized spread, price impact,
  order-to-trade ratio.

```java
// Route 900 across venues, benchmark the result, and TCA the fills:
var plan = SmartOrderRouter.route(Side.BUY, 900, venueQuotes, /*preferDark*/ true);
var tca  = TransactionCostAnalyzer.analyze(fills, arrivalMid, marketVwap, midsAtFill);
var sweep = BookAnalytics.sweep(orderBook, Side.BUY, 500_000);   // what would it cost?
```

## Ultra-Low-Latency / HFT Path

The library ships two market data paths. The convenience path
(`MarketDataProcessor`, String symbols, event objects) is for research and monitoring.
The **HFT path** is a zero-allocation, zero-lock, zero-map-lookup tick pipeline for
latency-critical trading:

| Component | Design |
|---|---|
| `TickRingBuffer` | Disruptor-style SPSC ring: preallocated primitive slots (`int/double/long` arrays — no event objects), cache-line-padded sequences (no false sharing), acquire/release publication (no CAS), producer/consumer sequence caching (minimal cross-core traffic) |
| `SymbolRegistry` | Symbols interned to dense int ids once at setup — the hot path never hashes a String |
| `HftMarketDataBus` | Array-indexed listener dispatch and last-price cache (no map lookups), optional busy-spin consumer (`Thread.onSpinWait()`) for minimum hand-off latency |
| `StreamingIndicators` | O(1)-per-tick SMA / EMA / RSI / MACD / VWAP, verified value-for-value identical to the batch engine — backtest results transfer to live execution exactly |
| `LatencyRecorder` | Zero-allocation log-linear nanosecond histogram (HdrHistogram-style) for measuring your own path |

```java
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 16, 16, /*busySpin*/ true)) {
    int eurusd = bus.registerSymbol("EURUSD");           // setup: intern once
    var fast = new StreamingIndicators.Ema(12);
    var slow = new StreamingIndicators.Ema(26);
    bus.subscribe(eurusd, (id, price, size, tsNanos) -> { // hot path: primitives only
        if (fast.update(price) > slow.update(price)) { /* fire order */ }
    });
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime()); // zero allocation
}
```

**Measured** (`HftLatencyBenchmark`, JDK 24, Windows 11, stock desktop hardware —
strategy workload of 2×EMA + RSI per tick plus latency recording included in every number):

```
Throughput:                  9-12 million ticks/sec sustained (0 ticks lost)
Publish-to-strategy latency: p50=204ns  p99=300-800ns  p99.9=~2.4us   (across runs)
```

Reproduce with:

```bash
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes com.quantfinlib.examples.HftLatencyBenchmark
```

Steady-state the hot path allocates nothing, so GC choice barely matters; for
production-grade tail latency also consider `-XX:+UseZGC`, core pinning via OS affinity
for the producer and consumer threads, and disabling CPU frequency scaling.

## Project Layout

```
com.quantfinlib
├── core          Bar, BarSeries (primitive-array OHLCV time series)
├── orderbook     OrderBook matching engine, BookAnalytics, Side, LimitOrder
├── microstructure QueueModel, MarketImpactModel, TransactionCostAnalyzer
├── pricing       FairValueEngine, TriangularArbitrage, ForwardCurve
├── execution     TWAP/VWAP schedulers, SmartOrderRouter, IcebergOrder,
│                 DarkPoolSimulator, MidPegTracker, VenueBenchmark
├── regulatory    FixAnalyzer, BestExecutionAnalyzer, MarketQualityMetrics
├── indicators    21-indicator batch engine + O(1) StreamingIndicators for live/HFT
├── risk          RiskMetrics, PortfolioRiskAnalyzer, Portfolio, metric registry,
│                 CounterpartyExposureTracker, PreTradeLimitChecker,
│                 SettlementRiskAnalyzer, ConcentrationRisk
├── ml            GradientBoostedRegressor, VolatilityForecaster,
│                 MarketImpactPredictor, IntradayLiquidityForecaster, AnomalyDetector
├── optimization  PortfolioOptimizer (max Sharpe / min vol / frontier / rebalance)
├── backtest      Backtester, config, trades, performance analytics
│   └── strategies  SMA/EMA cross, RSI, MACD, Bollinger built-ins
├── dsl           Rule, Rules, StrategyBuilder
├── screener      Technical + fundamental filters, ranking, CSV export
├── simulation    MonteCarloSimulator, SimulationResult
├── marketdata    HFT path: TickRingBuffer, HftMarketDataBus, SymbolRegistry
│                 convenience path: RingBuffer, MarketDataProcessor, HistoricalDataStore
├── report        Report model + HTML/CSV/PDF/XLSX exporters, ReportGenerator
├── util          MathUtils, LatencyRecorder (nanosecond histogram)
└── examples      QuickStartDemo (all 11 capabilities), HftLatencyBenchmark
```