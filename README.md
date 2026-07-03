# Quant-Finance-Library (quantfinlib)

**quantfinlib** is a production-ready quantitative finance platform for the JVM that unifies
risk management, portfolio optimization, machine learning, technical analysis, strategy
development, market screening, Monte Carlo simulation, reporting, and algorithmic research
in a single Java library — for multi-asset workflows (FX, equities, commodities, and more).

Built for quantitative researchers, algorithmic traders, developers, fintech teams, and
financial professionals: focus on building winning strategies while the platform handles
everything from backtesting to reporting.

- **Ultra low latency by design** — zero runtime dependencies, primitive-array
  (structure-of-arrays) time series, allocation-free hot paths, a lock-free SPSC ring
  buffer for market data, and parallel Monte Carlo across all cores.
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

## Project Layout

```
com.quantfinlib
├── core          Bar, BarSeries (primitive-array OHLCV time series)
├── indicators    21-indicator technical analysis engine
├── risk          RiskMetrics, PortfolioRiskAnalyzer, Portfolio, metric registry
├── ml            GradientBoostedRegressor, VolatilityForecaster
├── optimization  PortfolioOptimizer (max Sharpe / min vol / frontier / rebalance)
├── backtest      Backtester, config, trades, performance analytics
│   └── strategies  SMA/EMA cross, RSI, MACD, Bollinger built-ins
├── dsl           Rule, Rules, StrategyBuilder
├── screener      Technical + fundamental filters, ranking, CSV export
├── simulation    MonteCarloSimulator, SimulationResult
├── marketdata    RingBuffer, MarketDataProcessor, HistoricalDataStore
├── report        Report model + HTML/CSV/PDF/XLSX exporters, ReportGenerator
├── util          MathUtils (percentiles, Cholesky, inverse normal CDF, ...)
└── examples      QuickStartDemo (end-to-end tour of all 11 capabilities)
```

## License

MIT
