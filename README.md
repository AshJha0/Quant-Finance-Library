# Quant-Finance-Library (quantfinlib)

[![CI](https://github.com/AshJha0/Quant-Finance-Library/actions/workflows/ci.yml/badge.svg)](https://github.com/AshJha0/Quant-Finance-Library/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/Docs-GitHub%20Pages-blue.svg)](https://ashjha0.github.io/Quant-Finance-Library/)

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
mvn package && java -jar target/quant-finance-library-*.jar help   # runnable CLI jar
```

Tagged releases publish the library, sources and javadoc jars automatically
(GitHub Actions → Releases); see [CHANGELOG.md](CHANGELOG.md).

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
- **`AlmgrenChriss`** — optimal execution trajectories (closed-form): minimize
  expected cost + λ·variance; risk aversion front-loads the schedule, λ→0 recovers
  TWAP, and the efficient frontier maps the urgency trade-off.

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

### Execution-aware backtesting — `backtest`

The classic `Backtester` assumes instant fills at the close. `ExecutionAwareBacktester`
instead turns every strategy signal into a **parent order worked through an
`ExecutionModel`** — fills can span multiple bars, liquidity is finite, and execution
cost becomes a measured output instead of an assumption:

- **`InstantExecution`** — baseline (classic fill assumption, all-in pricing).
- **`SorExecution`** — a synthetic fragmented market per bar (spread + per-venue
  liquidity share of bar volume, fees, dark venues) routed by `SmartOrderRouter`;
  large parents take multiple bars to fill.
- **`IcebergExecution`** — wraps any model with the `IcebergOrder` display/reload
  state machine plus an optional participation cap, so entries and exits are worked
  patiently.

Every child fill is recorded per parent order, and TCA is one call:

```java
ExecutionAwareResult r = ExecutionAwareBacktester.run(
        new SmaCrossStrategy(20, 50), series, BacktestConfig.defaults(),
        new IcebergExecution(
                new SorExecution(List.of(
                        new SorExecution.VenueConfig("LIT_A", 1.0, 0.05, false),
                        new SorExecution.VenueConfig("LIT_B", 0.5, 0.05, false),
                        new SorExecution.VenueConfig("DARK_X", 0.2, 0.03, true)),
                        /*halfSpreadBps*/ 5, /*preferDark*/ true),
                /*displayQty*/ 1_000));

r.backtest().metrics();                    // performance net of realistic execution
ParentOrder entry = r.parentOrders().getFirst();
r.tca(entry).implementationShortfallBps(); // measured cost vs arrival mid
```

Stop-loss / take-profit exits are worked through the same model — a patient execution
style exits slowly, and that realism (plus the strategy-alpha-vs-execution-cost
trade-off) shows up directly in the equity curve.

## Research Validation & Portfolio Engine

- **Walk-forward analysis** (`backtest.validation`) — `ParameterGrid` +
  `GridSearchOptimizer` optimize a strategy on rolling train windows; each winner is
  evaluated on the unseen test window and out-of-sample equity is stitched into one
  curve (capital carries across folds). The **walk-forward efficiency ratio** (OOS/IS)
  exposes curve-fitting at a glance.
- **Deflated Sharpe** (`SharpeValidation`) — Bailey/López de Prado probabilistic Sharpe
  (track length, skew, kurtosis) and the deflated variant that haircuts for the number
  of parameter combinations tried.
- **VaR backtesting** (`risk.VarBacktest`) — Kupiec proportion-of-failures (two-sided),
  Christoffersen independence (exception clustering), and joint conditional coverage:
  the difference between producing VaR numbers and producing *validated* VaR numbers.
- **Portfolio backtesting** (`backtest.portfolio`) — multi-asset, long/short,
  weight-based `PortfolioBacktester` with rebalance cadence and commission on turnover;
  `PositionSizing` supplies Kelly / half-Kelly, fixed-fractional risk, inverse-vol
  weights, and vol-target leverage.
- **Paper trading** (`trading`) — `OrderGateway` abstraction + quote-driven
  `PaperTradingGateway`: market/limit orders, resting-order crosses, average-cost
  positions with realized P&L, commissions, and the `PreTradeLimitChecker` wired in as
  a pre-market risk gate — the research→production loop, closed.
- **Data I/O** (`data`) — `CsvBarLoader` (flexible headers/date formats, RFC-4180
  quoted fields, thousands separators, file-level epoch-seconds detection, round-trip
  save) and `HttpBarFetcher` (pure `java.net.http`) bring real historical data into
  every module; `SeriesAligner` (timestamp intersection or union + forward-fill)
  bridges ragged multi-asset files to the index-aligned `PortfolioBacktester`.
- **Tick capture & replay** (`data`) — `TickCapture` records every tick flowing through
  the `HftMarketDataBus` into the compact QFLT binary format (28 bytes/tick, inline
  symbol definitions); `TickFileReader` replays sessions deterministically — as fast as
  possible for backtesting, or paced at any speed multiple of recorded time for
  live-like feeds. Record a session once, run every experiment against identical real
  microstructure.
- **Live market data over WebSocket** (`feed`) — real ticks into the HFT bus with pure
  JDK (`java.net.http` WebSocket client): pluggable `FeedParser` per exchange
  (`BinanceTradeParser` ships as the reference — raw and combined streams), automatic
  reconnection with exponential backoff, optional subscribe message, and exchange
  event-time timestamps so recorded sessions replay with true market pacing. Tested
  end-to-end against an in-repo RFC 6455 loopback server (connect, trades → bus →
  capture, subscribe protocol, abrupt-disconnect reconnect) — CI never touches a real
  exchange.

```java
HftMarketDataBus bus = new HftMarketDataBus();
TickCapture capture = TickCapture.attach(bus, Path.of("session.qflt"));
bus.start();
try (WebSocketFeed feed = new WebSocketFeed(
        BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT"),
        new BinanceTradeParser(), bus)) {
    feed.start();   // live ticks now flow into strategies AND the recorder
}
```

- **FIX 4.4 connectivity** (`fix`) — a zero-dependency FIX engine: validated
  wire-format codec (BodyLength/CheckSum framing, fragmentation-safe stream decoder),
  full session layer (Logon handshake, Heartbeats with TestRequest probing and
  staleness disconnect, sequence-gap detection, Logout handshake), and the trading
  flow — `sendNewOrderSingle` out, typed `ExecutionReport`s in. Both **initiator and
  acceptor** roles, so the same class connects to a broker or *is* the venue
  simulator. **Full gap recovery**: inbound gaps trigger a ResendRequest (out-of-order
  messages are dropped and redelivered exactly once); inbound ResendRequests are
  serviced from the session's message store — application messages replayed with
  PossDupFlag/OrigSendingTime, admin runs coalesced into SequenceReset-GapFill;
  PossDup duplicates are suppressed and a too-low seqnum without PossDup disconnects.
  **Persistent sessions**: pass a `FileSessionStore` to `initiate`/`accept` for
  sequence-number continuation across restarts — ResendRequests are then serviced
  from messages sent before the reconnect, as production counterparties expect.
  Also: **order cancel/replace** (35=F/G with typed callbacks and
  canceled/replaced ExecutionReports), **session Reject** (35=3 — malformed
  application messages are auto-rejected instead of killing the session), and
  **Username/Password** (553/554) on Logon via `Config.withCredentials`.

```java
FixSession session = FixSession.initiate("broker.example.com", 9876,
        new FixSession.Config("MYFIRM", "BROKER", 30),
        new FixSession.Listener() {
            @Override public void onExecutionReport(FixSession s, ExecutionReport r) {
                if (r.isFill()) { /* update positions */ }
            }
        });
session.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000_000, 1.0851,
        NewOrderSingle.TIF_DAY);
```

- **Tick-level backtesting** (`backtest.tick`) — event-driven `TickBacktester` replays
  QFLT files through a `TickStrategy` with microstructure-aware fills: market orders
  pay half the spread; passive limit orders fill fully only when a print trades
  *through* the price, and fills at the level are earned print-by-print against a
  simulated queue ahead (`defaultQueueAhead`) — the level below bar backtesting, where
  queue position decides whether your order actually trades. Orders can never fill
  against the print that triggered them.

```java
// Capture a live session...
try (TickCapture capture = TickCapture.attach(bus, Path.of("session.qflt"))) {
    bus.start();
    /* ... trading day ... */
}
// ...then backtest strategies at tick level against the exact same tape:
var result = TickBacktester.run(myTickStrategy, Path.of("session.qflt"),
        TickBacktester.Config.defaults().withDefaultQueueAhead(500));
result.fills();            // every child fill with venue "TICK_SIM"
result.metrics();          // metrics on the sampled equity curve
```

```java
var wf = WalkForwardAnalyzer.analyze(series,
        new ParameterGrid().add("fast", 5, 10, 20).add("slow", 40, 60, 100),
        p -> new SmaCrossStrategy(p.get("fast").intValue(), p.get("slow").intValue()),
        BacktestConfig.defaults(), 252, 63, PerformanceMetrics::sharpeRatio);
wf.efficiency();               // OOS/IS objective ratio: ~1 robust, ~0 overfit
wf.outOfSampleMetrics();       // honest, stitched out-of-sample performance
```

## Quant Models — `rates`, `volatility`, `pricing`, `hedging`

- **Fixed income** (`rates`) — `YieldCurve` (zero curve, discount factors, implied
  forwards, classic bootstrap from annual par swap rates) and `BondPricer`
  (price/yield, Macaulay/modified duration, convexity, DV01, curve pricing).
- **Volatility models** (`volatility`) — `EwmaVolatility` (RiskMetrics λ=0.94) and
  `Garch11` (Gaussian MLE with variance targeting; k-step forecasts mean-revert to the
  unconditional variance).
- **American options** (`pricing.BinomialTree`) — CRR tree with early-exercise premium;
  converges to Black-Scholes for European payoffs.
- **SABR** (`pricing.SabrModel`) — Hagan 2002 implied vol + deterministic (α, ρ, ν)
  calibration: parametric smiles on top of `VolSurface` pillars.
- **Cointegration** (`hedging.CointegrationTest`) — Engle-Granger two-step with ADF
  t-statistic and critical values: the statistical gate before any `PairsHedger` trade.
- **Options book risk** (`hedging.OptionsBook`) — aggregate Greeks across positions and
  the underlying hedge, full-revaluation spot×vol scenario grids, and
  delta-gamma-vega-theta P&L explain with an unexplained residual.
- **Market conventions** (`rates`) — `DayCount` (ACT/360, ACT/365, 30/360, ACT/ACT
  ISDA), `BusinessCalendar` (holidays, FOLLOWING / MODIFIED_FOLLOWING / PRECEDING
  rolls, T+n settlement, coupon schedules), and date-based `BondPricer` methods
  (dirty/clean price, accrued interest) usable against real term sheets.
- **Portfolio construction** (`optimization`) — `RiskParityOptimizer` (equal risk
  contribution), `BlackLitterman` (equilibrium returns + view blending), and
  `ConstrainedPortfolioOptimizer` (position caps/floors, turnover penalty).
- **Regime detection** (`ml.RegimeDetector`) — 2-state Gaussian Markov-switching model
  (Baum-Welch EM): smoothed high-vol probabilities, transition persistence, current
  regime — feeds vol targeting and liquidity forecasting.
- **Corporate actions** (`data.CorporateActions`) — CRSP-style back-adjustment for
  splits (price and volume) and cash dividends (price only), composing across actions —
  the difference between toy and usable equity backtests.

## Hedging Algorithms — `hedging`, `pricing`

Quantitative hedging across asset classes, built on a dependency-free
Black-Scholes-Merton engine:

- **`BlackScholes`** (`pricing`) — pricing + full Greeks (delta, gamma, vega, theta,
  rho) with continuous carry, so the same formulas cover equity dividends and FX
  (Garman-Kohlhagen); implied vol by bisection. Verified against textbook values.
- **`DeltaHedger`** — dynamic delta hedging simulator: sell an option, replicate along
  a price path with a rebalance band and transaction costs, and measure the replication
  error. Quantifies the desk trade-off: tighter bands → smaller hedge error, more costs.
- **`GreekHedger`** — delta-gamma and delta-gamma-vega neutralization (exact linear
  solve), plus a general N-greek / N-instrument solver with residual verification.
- **`MinimumVarianceHedge`** — optimal hedge ratio (cov/var), hedge effectiveness (ρ²),
  realized variance reduction, and futures contract sizing for beta adjustment
  (`N = (β_target - β) · V / (F · multiplier)`).
- **`FxHedger`** — currency exposure netting across a book, variance-minimizing FX
  hedge ratio for foreign assets, forward-carry cost of the hedge in bps, and hedge
  notional sizing.
- **`PairsHedger`** — statistical hedging: OLS hedge ratio, spread construction,
  z-score for entry/exit, and mean-reversion half-life from an AR(1) fit (∞ when the
  spread doesn't revert).
- **`HedgingSimulator`** — Monte Carlo delta hedging: runs `DeltaHedger` across
  thousands of GBM paths in parallel (deterministic per seed) and returns the full
  `HedgingErrorDistribution` — replication error, hedging VaR/CVaR, probability of
  loss, cost and rebalance statistics. Hedge vol and realized vol are separate inputs,
  so both discretization risk and vol mispricing are directly measurable.
- **`VolSurface`** (`pricing`) — implied vol surface from pillar quotes or market
  prices (via `impliedVol`): linear smile interpolation in strike with flat wings,
  and total-variance (σ²T) interpolation across expiries for a calendar-consistent
  term structure; surface-consistent pricing and skew helpers.

```java
// Delta-hedge a short call daily with 1bp costs and measure replication error:
var report = DeltaHedger.simulateShortOption(OptionType.CALL, 100, 0.5,
        0.05, 0, 0.20, pricePath, 1.0 / 252, DeltaHedger.Config.every(1));
report.finalPnl();          // hedging error vs the option payoff

// Neutralize a book's delta/gamma/vega with spot + two options:
double[] qty = GreekHedger.deltaGammaVegaHedge(1_200, -80, 250, opt1, opt2);

// Hedge a $10M beta-1.2 portfolio with index futures:
double contracts = MinimumVarianceHedge.fullHedgeContracts(1.2, 10_000_000, 5_000, 50);

// Hedging-error distribution across 5,000 Monte Carlo paths (sold at 25 vol,
// realized 15): where does the vol P&L and the tail risk sit?
HedgingErrorDistribution dist = new HedgingSimulator(42).simulate(
        OptionType.CALL, 100, 100, 0.5, 0.02, 0,
        /*hedgeVol*/ 0.25, /*realizedVol*/ 0.15, 126, 5_000, DeltaHedger.Config.every(1));
dist.mean(); dist.valueAtRisk(0.95); dist.probabilityOfLoss();

// Vol surface from pillar quotes; interpolated smile + term structure:
VolSurface surface = VolSurface.builder()
        .add(0.25, 90, 0.25).add(0.25, 100, 0.20)
        .add(1.00, 90, 0.28).add(1.00, 100, 0.24).build();
surface.vol(0.5, 95);                                  // any (expiry, strike)
surface.price(OptionType.PUT, 100, 95, 0.02, 0, 0.5);  // surface-consistent price
```

## Tooling & Operations

- **CLI** (`cli.Main`) — run the library without writing Java:
  ```bash
  java -cp target/classes com.quantfinlib.cli.Main backtest \
      --csv bars.csv --symbol EURUSD --strategy sma --fast 10 --slow 30 --out report.html
  java -cp target/classes com.quantfinlib.cli.Main walkforward \
      --csv bars.csv --symbol EURUSD --train 252 --test 63 --fast 5,10,20 --slow 40,60
  java -cp target/classes com.quantfinlib.cli.Main report --csv bars.csv --symbol X --out x.html
  ```
- **Live dashboard** (`trading.TradingDashboard`) — zero-dep embedded HTTP server
  (JDK `httpserver`) showing the paper-trading account live in a browser: equity, cash,
  realized P&L, positions, rejections, and attached latency histograms
  (`/` self-refreshing page, `/api/status` JSON).
- **JMH microbenchmarks** (test scope only — the runtime stays zero-dependency):
  ```bash
  mvn test-compile exec:java -Dexec.mainClass=com.quantfinlib.bench.BenchRunner \
      -Dexec.classpathScope=test -Dexec.args=CoreBenchmarks
  ```
- **Docs site** — the Docs workflow builds and publishes a landing page + full javadoc
  site to GitHub Pages on every push to main:
  [ashjha0.github.io/Quant-Finance-Library](https://ashjha0.github.io/Quant-Finance-Library/)
  (javadoc under [`/api/`](https://ashjha0.github.io/Quant-Finance-Library/api/)).
- **Model-based fuzz tests** — the `OrderBook` is hammered with 100k random operations
  against an independent reference model (uncrossed book, depth conservation, cancel
  idempotence, queue-position consistency), and all three ring buffers run 2M-item
  concurrent SPSC stress with randomized batching.

## Alpha Research Pipeline

`com.quantfinlib.alpha` is the systematic factor-research workflow, end to end,
with each stage a separate composable step (scores flow as plain `double[]`
aligned to a frozen symbol panel; NaN = no data at every stage):

1. **Signal generation** (`Factors`) — nine standard factors: MA crossover, MACD,
   12-1 momentum (trend); contrarian RSI (Cutler's — named so, because it is NOT
   Wilder's `Indicators.rsi`), Bollinger reversion, mean reversion (reversal);
   value (earnings+book yield), quality (ROE − leverage), low volatility
   (defensive). Stateless, O(window), no-look-ahead by contract — and
   survivorship-aware: attach a `PointInTimeUniverse` via
   `AlphaContext.withUniverse` and every factor scores dead/non-member names
   NaN per bar, so ICs and weights only ever see the point-in-time
   cross-section.
2. **Signal evaluation** (`SignalEvaluator`) — rank IC (Spearman, monotone-invariant),
   Grinold-Kahn IR, t-stat on non-overlapping windows, hit rate, implied turnover,
   and cross-factor exposure (is your "new" factor just momentum in a hat).
3. **Validation** (`AlphaValidation`) — walk-forward selection with OOS efficiency,
   blocked k-fold consistency (no shuffled folds on time series), Monte Carlo
   permutation p-values (deliberately conservative for time-invariant signals),
   and parameter sensitivity (plateau vs lucky spike).
4. **Execution-aware backtest** (`AlphaBacktester`) — commission, bid-ask spread,
   slippage, and square-root market impact via `microstructure.MarketImpactModel`
   with per-symbol ADV/vol estimation: gross vs net curves and a per-component
   cost decomposition, so "which cost kills this signal" has a number.
5. **Portfolio construction** (`PortfolioConstruction`) — winsorized z-score sizing
   with caps, inverse-vol risk budgeting, exact sector and beta neutralization
   (Σwβ = 0 by projection), and an unconstrained mean-variance tilt (Σ⁻¹α).
6. **Reporting** (`AlphaReport`) — alpha decay profile with half-life, OLS factor
   attribution (residual alpha + R²), drawdown curves, rolling Sharpe, and the
   shared ratio set (Sharpe/Sortino/Calmar/CAGR/maxDD) from the backtest engine —
   definitions never fork between research and backtests.

```java
AlphaContext ctx = AlphaContext.of(alignedSeries, fundamentals);
AlphaFactor momo = Factors.momentum(252, 21);
SignalEvaluator.Report ic = SignalEvaluator.evaluate(ctx, momo, 260, 21);
AlphaValidation.RobustnessResult mc = AlphaValidation.monteCarloRobustness(ctx, momo, 21, 260, 500, 42);
AlphaBacktester.Result bt = AlphaBacktester.run(ctx, momo, AlphaBacktester.Config.defaults(260),
        (c, scores, t) -> PortfolioConstruction.betaNeutralize(
                PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05),
                PortfolioConstruction.trailingBetas(c, t, 60)));
```

## FX & Equities Instruments

Beyond spot, the library speaks the market's own conventions per asset class:

- **FX conventions** (`fx.CurrencyPair`) — pip sizes/precision (JPY quotes), T+1/T+2
  spot lags, settlement dates against *both* currencies' holiday calendars, forward
  tenor arithmetic (ON/TN/SN, weeks, months with modified-following and the end-end
  rule). Resolved to primitives at setup so the hot path never re-parses conventions.
- **FX forwards & swaps** (`fx.SwapPointsCurve`, `fx.FxSwap`) — quoted points per tenor
  → outrights for any broken date (linear in days, the interbank convention),
  covered-interest-parity implied carry, at-market swaps that value to zero by
  construction, points MTM with optional discounting, tom-next roll cost.
- **NDFs** (`fx.Ndf`) — fixing vs settlement dates per restricted currency (INR/KRW/TWD
  2-day lags, BRL PTAX 1-day), the USD-settled difference formula (divides by the
  fixing), MTM off the forward to the *fixing* date; `fx.FixingRisk` quantifies
  fix-window tracking error (σ²T/3 law) and participation.
- **FX options** (`fx.FxVolSurface`, `pricing.VannaVolga`) — the delta-quoted smile the
  market actually trades: ATM DNS + 25Δ/10Δ risk-reversal/butterfly → solved strikes
  (closed-form forward delta, bisection for premium-adjusted), then vanna-volga
  smile-consistent pricing/vols at any strike (exact at the pillars).
- **First-generation exotics** (`pricing`) — cash/asset-or-nothing digitals, one-touch/
  no-touch (reflection-principle hit probabilities), regular single-barrier knock-in/out
  (Reiner-Rubinstein closed form with in-out parity; reverse barriers are rejected, not
  silently mispriced) — all Monte Carlo cross-checked in tests.
- **Equity dividends & borrow** (`pricing.DividendSchedule`) — escrowed discrete
  dividends (PV-stripped spot), forwards with borrow cost, dividend-consistent
  European pricing; the forward-looking counterpart of `data.CorporateActions`.
- **Exchange mechanics** (`microstructure`) — `TickSizeSchedule` (MiFID II-style
  price-banded ticks with directional rounding, wired into the tick backtester via
  `Config.withTickSchedule`) and `Auction` (call-auction uncross: max volume →
  min surplus → reference proximity, market-on-auction orders, indicative feed).
- **Last look** (`backtest.LastLookExecution`) — FX-realistic execution model: the LP
  rejects fills when the intra-bar move runs beyond a threshold in the taker's favor,
  so backtests chase the market the way live FX flow actually does; reject-rate TCA
  included.
- **Survivorship-bias defense** (`data.PointInTimeUniverse`) — point-in-time universe
  membership (intervals, drop-and-re-add) with terminal events: delistings terminate
  positions at `lastClose × (1 + delistingReturn)` (Shumway −30% haircut constant for
  unknown involuntary proceeds), mergers convert to cash and/or acquirer shares at deal
  terms, index drops force liquidation. Wired into `PortfolioBacktester` (universe-aware
  overload, plus explicit **cash dividends on the ex-date** — shorts pay) and the
  screener (`StockScreener.membersAsOf`). Membership/event data loads from a documented
  CSV format (`data.UniverseCsvLoader`: MEMBER/DELIST/MERGER rows, ISO or epoch dates —
  free constituent lists like `datasets/s-and-p-500-companies` seed it, though only
  point-in-time histories remove the bias), and
  `backtest.portfolio.CrossSectionalMomentum` (12-1 Jegadeesh-Titman long/short) shows
  the pattern: every rebalance ranks only the members alive at that bar. The engine half
  is here and tested — the data half (dead-ticker histories, delisting returns;
  CRSP-style datasets) cannot be solved by code, and the docs say so.

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
| `HftRiskGate` (`trading`) | Zero-allocation pre-trade risk gate over dense int symbol ids: order size, notional, position, price collar, halt — int reason codes, ~3 ns/check with correct cross-thread visibility (VarHandle acquire/release: fills, halts and reference prices land from other threads and readers are guaranteed fresh, untorn values) |
| `HftOrderGateway` + `OrderRingBuffer` (`trading`) | The fast lane out: risk check → release-store publish into a preallocated primitive order ring → venue thread; zero allocation per order (proven by a per-thread allocation-counter test) |
| `sbe` package | SBE-style binary flyweight codecs (`TradeFlyweight`, `OrderFlyweight`, `QuoteFlyweight`: fixed-offset primitives, zero parse/copy/alloc — proven by test) with channel adapters replacing the text edges: `BinaryMarketDataClient` → bus, gateway → `BinaryOrderPublisher`; fragmentation-safe decode loops |
| `HftQuoter` (`trading`) | Streaming market maker on the fast lane: mid + inventory skew (read live from the risk gate) + tick-grid snap → two-sided quote through the gate and order ring, with conflation (min-move / min-interval) — zero allocation per tick |
| `AutoHedger` (`trading`) | Live position-band hedger: band breach on any tick fires a flattening order for the excess through the fast lane, with per-symbol cooldown while the hedge fill is in flight |
| `AggregatedBook` + `CrossRateEngine` (`fx`) | Multi-venue composite BBO with venue attribution (primitive arrays, zero alloc per quote, crossed composites reported not hidden) and streaming synthetic crosses (EURJPY from EURUSD×USDJPY) chained on the bus consumer thread |
| `IncrementalGreeks` (`pricing`) | Tick-fresh options risk without tick-frequency repricing: delta-gamma Taylor updates per tick (two multiplies, zero alloc), full Black-Scholes re-anchor off the hot path on drift |
| `HftOrderBook` (`orderbook`) | Venue-grade matching engine: dense integer-tick price ladder with occupancy bitmaps, pooled intrusive order nodes, primitive open-addressing id map (backward-shift deletion), zero allocation — ~204ns/op, 10M+ fills/sec; a model-based equivalence test pins it to the readable reference `OrderBook` |
| `HiccupMonitor` (`util`) | jHiccup-style platform stall attribution: all four benchmarks print a hiccup summary so tail outliers are correctly attributed to GC/safepoints/scheduler vs code (on the Windows dev box: benchmark max 541µs vs platform hiccups up to 1.6ms — the platform owns the tail) |

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
Market data (HftLatencyBenchmark):
  Throughput:                  9-12 million ticks/sec sustained (0 ticks lost)
  Publish-to-strategy latency: p50=204ns  p99=300-800ns  p99.9=~2.4us

Order entry (HftOrderBenchmark):
  Risk gate:                   ~3 ns per pre-trade check (cross-thread safe)
  Submit-to-venue latency:     p50=102ns  p99=296ns  p99.9=1.4us
  Tick-to-order END-TO-END:    p50=504ns  p99=1.0us  p99.9=4.0us
                               (tick -> bus -> 2xEMA strategy -> risk gate -> order ring -> venue)
  Throughput:                  21.2 million orders/sec sustained (v1.4.1 run; 15-21M across runs)

Market making (HftQuoterBenchmark):
  Tick-to-two-sided-quote:     p50=592ns  p99=912ns  p99.9=4.5us
                               (tick -> bus -> quoter: skew + grid snap -> risk gate x2 -> order ring -> venue, BOTH sides)

Matching engine (HftBookBenchmark, venue side):
  Per-operation latency:       p50=204ns  p99=504ns (70/20/10 add/cancel/aggress mix)
  Matching:                    10M+ fills/sec;  Passive churn: 7M+ add/cancel ops/sec
                               (also completes under Epsilon GC: 5.6M orders, GC never ran)
```

Reproduce with:

```bash
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes com.quantfinlib.examples.HftLatencyBenchmark
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes com.quantfinlib.examples.HftOrderBenchmark
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes com.quantfinlib.examples.HftQuoterBenchmark
java -Xms1g -Xmx1g -XX:+AlwaysPreTouch -cp target/classes com.quantfinlib.examples.HftBookBenchmark
```

Steady-state the hot path allocates nothing, so GC choice barely matters; for
production-grade tail latency also consider `-XX:+UseZGC`, core pinning via OS affinity
for the producer and consumer threads, and disabling CPU frequency scaling.

**Going further**: [docs/ULTRA_LOW_LATENCY.md](docs/ULTRA_LOW_LATENCY.md) is the full
latency-stack reference — what's implemented here, the JVM flags, the kernel/CPU tuning
([scripts/linux-tune.sh](scripts/linux-tune.sh), plus a manual `Benchmarks (Linux)`
workflow), and the kernel-bypass/off-heap/hardware frontier beyond a pure-JDK library.
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) maps every package (each also carries
`package-info.java` javadoc) to its classes and tests.

## Project Layout

```
com.quantfinlib
├── core          Bar, BarSeries (primitive-array OHLCV time series)
├── orderbook     OrderBook (research matching model), HftOrderBook (venue-
│                 grade: tick ladder + pooled nodes, zero-alloc, 10M+ fills/s),
│                 BookAnalytics, Side, LimitOrder
├── alpha         Factor research pipeline: Factors (9 signals), SignalEvaluator
│                 (IC/IR/turnover), AlphaValidation (walk-forward, CV, Monte
│                 Carlo, sensitivity), AlphaBacktester (cost-aware),
│                 PortfolioConstruction (sizing, budgets, neutrality),
│                 AlphaReport (decay, attribution, rolling metrics)
├── microstructure QueueModel, MarketImpactModel, TransactionCostAnalyzer,
│                 TickSizeSchedule (MiFID II price bands), Auction (call uncross)
├── fx            CurrencyPair conventions, SwapPointsCurve, FxSwap, Ndf,
│                 FxVolSurface (delta-quoted smile), FixingRisk,
│                 AggregatedBook (multi-venue BBO), CrossRateEngine (streaming)
├── pricing       FairValueEngine, TriangularArbitrage, ForwardCurve, BlackScholes,
│                 VolSurface, BinomialTree (American), SabrModel, VannaVolga,
│                 DigitalOption, TouchOption, BarrierOption, DividendSchedule,
│                 IncrementalGreeks (tick-path delta-gamma updates)
├── hedging       DeltaHedger, GreekHedger, MinimumVarianceHedge, FxHedger,
│                 PairsHedger, HedgingSimulator (Monte Carlo hedging error)
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
├── backtest      Backtester, config, trades, performance analytics,
│   │             ExecutionAwareBacktester + Instant/Sor/Iceberg/LastLook models
│   ├── strategies  SMA/EMA cross, RSI, MACD, Bollinger built-ins
│   ├── validation  ParameterGrid, GridSearchOptimizer, WalkForwardAnalyzer,
│   │               SharpeValidation (probabilistic + deflated Sharpe)
│   ├── portfolio   PortfolioBacktester (multi-asset long/short; survivorship-
│   │               aware overload: delistings/mergers/index drops/cash divs),
│   │               CrossSectionalMomentum (point-in-time 12-1), PositionSizing
│   └── tick        TickBacktester (event-driven, queue-aware fills), TickStrategy
├── data          CsvBarLoader, HttpBarFetcher, TickFileWriter/Reader (QFLT format),
│                 TickCapture (record the live bus for deterministic replay),
│                 CorporateActions (split/dividend back-adjustment),
│                 PointInTimeUniverse + UniverseCsvLoader (point-in-time
│                 membership, delisting/merger events, CSV interchange format)
├── feed          WebSocketFeed (live exchange data -> HFT bus), BinanceTradeParser
├── rates         YieldCurve (bootstrap, forwards), BondPricer (duration, DV01)
├── volatility    EwmaVolatility, Garch11 (MLE fit + forecasts)
├── trading       OrderGateway, PaperTradingGateway (risk-gated paper venue),
│                 fast lane: HftRiskGate, OrderRingBuffer, HftOrderGateway,
│                 HftQuoter (streaming market maker), AutoHedger (band hedging)
├── fix           FIX 4.4 engine: FixMessage codec, FixSession (initiator/acceptor,
│                 logon/heartbeat/logout), NewOrderSingle, ExecutionReport
├── dsl           Rule, Rules, StrategyBuilder
├── screener      Technical + fundamental filters, ranking, CSV export
├── simulation    MonteCarloSimulator, SimulationResult
├── marketdata    HFT path: TickRingBuffer, HftMarketDataBus, SymbolRegistry
│                 convenience path: RingBuffer, MarketDataProcessor, HistoricalDataStore
├── report        Report model + HTML/CSV/PDF/XLSX exporters, ReportGenerator,
│                 SvgCharts (inline equity/drawdown charts in HTML reports)
├── util          MathUtils, LatencyRecorder (nanosecond histogram)
└── examples      QuickStartDemo (all 11 capabilities), HftLatencyBenchmark
```

## License

Released under the [MIT License](LICENSE) — free to use, modify, and distribute
with attribution. See the [LICENSE](LICENSE) file for the full text.
