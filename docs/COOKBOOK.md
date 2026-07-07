# Cookbook

Task-shaped recipes: each is complete, copy-pasteable, and under ~20 lines.
Reference docs live in [ARCHITECTURE.md](ARCHITECTURE.md); the visual map in
[DIAGRAMS.md](DIAGRAMS.md). New to the concepts themselves (order books,
market making, backtesting honestly, zero-allocation Java)? Start with the
beginner tutorial in [LEARN.md](LEARN.md). All recipes assume the library on
your classpath (`mvn package`, or the release jar).

---

## 1. Backtest your own CSV in ten lines

CSV header: `date,open,high,low,close[,volume]` (ISO dates or epoch numbers).

```java
BarSeries series = CsvBarLoader.load(Path.of("bars.csv"), "EURUSD");
BacktestResult result = Backtester.run(
        new SmaCrossStrategy(20, 50), series, BacktestConfig.defaults());
System.out.println(result.metrics());          // Sharpe, CAGR, maxDD, trades...
new ReportGenerator("My backtest")
        .addStrategyPerformance(result)
        .toHtml(Path.of("report.html"));        // or .toPdf / .toExcel / .toCsv
```

Or without writing code at all:

```bash
java -jar quant-finance-library-*.jar backtest --csv bars.csv --symbol EURUSD \
     --strategy sma --fast 20 --slow 50 --out report.html
```

## 2. Screen a stock universe

```java
List<StockSnapshot> universe = List.of(/* symbol + BarSeries + Fundamentals */);
var ranked = new StockScreener(universe).screenAndRank(
        new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe())
                .addCriterion("P/E", -0.5, s -> s.fundamentals().peRatio()),
        FundamentalFilters.marketCapAbove(10e9),
        TechnicalFilters.rsiBelow(14, 70).and(TechnicalFilters.priceAboveSma(200)));
StockScreener.exportCsv(Path.of("screen.csv"), ranked);
```

For historical screens, filter point-in-time first (see recipe 4):
`StockScreener.membersAsOf(universe, pointInTimeUniverse, asOfTimestamp)`.

## 3. Evaluate an alpha factor before backtesting it

```java
AlphaContext ctx = AlphaContext.of(alignedSeriesBySymbol);   // sorted, frozen panel
AlphaFactor momentum = Factors.momentum(252, 21);            // academic 12-1

SignalEvaluator.Report ic = SignalEvaluator.evaluate(ctx, momentum, 260, 21);
System.out.println(ic.format());   // IC, t-stat, IR, hit rate, turnover

// The overfitting defenses — run them BEFORE any capital-weighted number:
var mc = AlphaValidation.monteCarloRobustness(ctx, momentum, 21, 260, 500, 42);
var wf = AlphaValidation.walkForward(ctx,
        List.of(Factors.momentum(126, 21), momentum, Factors.momentum(504, 21)),
        21, 260, 504, 63);
System.out.printf("permutation p=%.3f, walk-forward efficiency=%.2f%n",
        mc.pValue(), wf.efficiency());
```

## 4. A survivorship-honest backtest

Universe file (`universe.csv` — see `UniverseCsvLoader` javadoc for the format):

```csv
symbol,event,date,end_date,value,acquirer_shares,acquirer
AAPL,MEMBER,2010-01-01,,,,
LEH,MEMBER,2000-01-03,2008-09-15,,,
LEH,DELIST,2008-09-15,,-1.0,,
```

```java
PointInTimeUniverse universe = UniverseCsvLoader.load(Path.of("universe.csv"));
PortfolioBacktester.Result r = PortfolioBacktester.run(
        new CrossSectionalMomentum(universe, CrossSectionalMomentum.Config.twelveMinusOne(10)),
        alignedSeriesBySymbol,
        PortfolioBacktester.Config.defaults()
                .withRebalanceEvery(21)
                .withCostModel(TradeCostModel.institutional(1, 2, 1, 20)),
        universe, Map.of());
// Delistings cost their delisting return, costs include sqrt market impact —
// survivorship-aware AND execution-aware in the same run.
```

## 5. Price FX derivatives from market quotes

```java
CurrencyPair eurusd = CurrencyPair.of("EURUSD");
SwapPointsCurve curve = SwapPointsCurve.builder(eurusd, LocalDate.now(), 1.0850)
        .add("1M", 12.6).add("3M", 38.4).add("1Y", 145.0).build();

FxVolSurface surface = FxVolSurface.builder()
        .add(0.25, curve.outright("3M"), 0.078, -0.010, 0.0022)  // ATM, RR25, BF25
        .build();
FxVolSurface.SmilePillar pillar = surface.pillar(0);

double touch = TouchOption.oneTouch(1.0850, 1.12, 0.045, 0.030, 0.078, 0.25, 1e6);
double barrier = BarrierOption.downAndOutCall(1.0850, 1.10, 1.05, 0.045, 0.030, 0.078, 0.25);
double smileVol = new VannaVolga(pillar.strikes(), pillar.vols(), 0.045, 0.030, 0.25)
        .impliedVol(1.0850, 1.0950);
```

## 6. Trade live data on paper money (the five-minute demo)

```bash
mvn package -DskipTests
java -cp target/classes com.quantfinlib.examples.LiveTradingDemo
# → open http://localhost:8080 for live positions, P&L, fills, latency
```

Real Binance trades → streaming EMA crossover → paper venue → dashboard.
Swap the strategy body in `LiveTradingDemo` for your own logic.

## 7. The measured hot path (quote a market, in nanoseconds)

```java
HftRiskGate gate = new HftRiskGate(16).maxOrderQuantity(1_000_000)
        .maxOrderNotional(1e9).priceCollarPct(0.05);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> {/* venue adapter */});
    HftQuoter quoter = new HftQuoter(gateway, 16,
            HftQuoter.Config.of(100_000, 0.0002).withMinMove(0.0001));
    int eurusd = bus.registerSymbol("EURUSD");
    bus.subscribe(eurusd, quoter);
    gateway.start();
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime()); // → two-sided quote
}
```

592 ns tick-to-two-sided-quote measured; scale out with
`ShardedTradingEngine` + `GlobalRiskAggregator` (see ULTRA_LOW_LATENCY.md).

## 8. Record a session, replay it deterministically

```java
// Live: capture off the trading bus without touching its latency.
AsyncTickCapture capture = AsyncTickCapture.attach(bus, Path.of("session.qflt"), 1 << 20);
// Later: replay tick-for-tick into a tick-level backtest with queue-aware fills.
TickBacktester.TickBacktestResult r = TickBacktester.run(
        myTickStrategy, Path.of("session.qflt"),
        TickBacktester.Config.defaults().withTickSize(0.0001));
```

## 9. Match orders like a venue

```java
HftOrderBook book = new HftOrderBook(90_000, 110_000, 1 << 20); // ticks, pool
book.tradeSink((maker, taker, tick, qty, ts) -> {/* fills */});
long bid = book.submitLimit(Side.BUY, 99_995, 1_000, now);
long ask = book.submitLimit(Side.SELL, 100_005, 1_000, now);
book.submitMarket(Side.BUY, 400, now);          // lifts the ask FIFO
book.cancel(bid);
// 204 ns/op, 10M+ fills/s, zero allocation — equivalence-tested against
// the readable OrderBook.
```

---

Every number quoted here comes from a committed benchmark; every behavior
from a committed test. When a recipe and the javadoc disagree, the javadoc
wins — and please open an issue.
