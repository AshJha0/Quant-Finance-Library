# Cookbook

Task-shaped recipes: each is complete, copy-pasteable, and roughly ~20 lines.
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

## 10. Work a benchmark order with live signals (models → executor → router)

The full execution pipeline: quant models feed a normalized `MarketState`,
the dynamic `BenchmarkExecutor` decides how much to send this interval, and
the router places it. Works identically for equities and FX (prices are
doubles). One instance each, per parent order.

```java
// Models (learn/update from the live feed on your feed thread).
SignalEngine sig   = new SignalEngine(nSymbols);         // vol, alpha, imbalance
VolumeCurve  vol   = new VolumeCurve(78, 0.1);           // live VWAP curve
SpreadForecaster spread = new SpreadForecaster();        // time-of-day spread
VolatilityCurve volCurve = new VolatilityCurve(78, 0.1); // vol seasonality
KylesLambda kyle = new KylesLambda();                    // impact from the tape
long clipSize = 2_000;         // the contemplated child size to price impact
                               // for (a typical slice, e.g. parent/intervals)

BenchmarkExecutor exec = BenchmarkExecutor.of(Side.BUY, 100_000, Benchmark.VWAP);

// Each interval: assemble the normalized MarketState from the models.
var m = new BenchmarkExecutor.MarketState(
        sig.microprice(sym),                             // mid
        spread.forecast(bucket, now),                    // spread (cost)
        volCurve.regime(bucket, sig.volPerSqrtSecond(sym)), // vol regime ~0..1
        book.bestAskSize(),                              // displayed depth (take-now)
        vol.expectedFractionElapsed(bucket, fracIn),     // VWAP curve
        sig.alpha(sym),                                  // alpha, already in [-1,1]
        kyle.impactBps(clipSize, sig.microprice(sym)));  // impact, learned live
long child = exec.dueQuantity(scheduleFraction, m);      // how much, right now
if (child > 0) {
    var plan = sor.route(Side.BUY, child, venueQuotes);  // AdaptiveSor: where
    // ...send plan.lit() legs and plan.probes(), then feed fills back:
    exec.onFill(filled);                                 // schedule self-corrects
}
```

The units contract is the one thing to get right: `volatility` and `alpha`
are **normalized** — `SignalEngine.alpha()` is already `[-1,1]`, and
`VolatilityCurve.regime()` produces exactly the 0..1 vol signal (elevated
*for this time of day*, so the always-wild open doesn't read as urgency) —
and `displayedDepth` is take-now size; see `MarketState`'s javadoc.
`MarketState.neutral(mid, f)` disables every input (VWAP degrades to TWAP)
if you want to add signals one at a time. For passive child placement,
`FillProbabilityModel.passiveFillProbability` scores a level (touch × queue)
and `TradeClassifier` infers the aggressor side your feed doesn't carry.

Three adaptive upgrades slot in without changing the pipeline (recipe 12
persists what they learn):
`OnlineAlphaLearner` replaces the fixed-weight `sig.alpha(sym)` with learned
weights — feed `trainFrom(sig, sym, realizedReturn)` each interval and pass
`normalizedPrediction(...)` as the alpha input; it emits 0 until its rolling
*out-of-sample* IC is positive, so an unproven learner can't steer the
schedule. On days with a different shape (expiry, half day, FX fixing day),
pick the curves from a `DayTypeProfiles<VolumeCurve>` (and vol/spread
siblings) at session start instead of the single averaged curve. And when
the traded instrument follows a leader (EURJPY behind EURUSD, cash behind
futures), `LeadLagEstimator` measures the lead across instruments — note
its `expectedFollowerReturn()` is a RAW return forecast, so it cannot go
into `MarketState.alpha` (a normalized [-1,1] input) or into the learner
(closed over the four SignalEngine ingredients by design) without your own
rescaling and, more importantly, your own validation through the `alpha`
package — a cross-asset signal gets no free pass on the IC gate.

---

## 11. Execute a basket as one schedule (portfolio-level execution)

A two-sided transition (sell the old book, buy the new one) is not N
independent parent orders: the legs must stay in step or the basket carries
unintended net market exposure mid-flight. `PortfolioExecutor` coordinates
per-symbol `BenchmarkExecutor` children with the two overlays that only
exist at basket level — a leg-balance band and a per-interval notional
budget allocated to the riskiest names first.

```java
var pe = new PortfolioExecutor(basketSize, new PortfolioExecutor.Config(
        1_000_000,     // |buys − sells| filled notional stays inside $1M
        5_000_000));   // total basket demand per interval

int aapl = pe.add(BenchmarkExecutor.of(Side.SELL, 80_000, Benchmark.VWAP));
int msft = pe.add(BenchmarkExecutor.of(Side.BUY, 60_000, Benchmark.VWAP));
// ... one child per symbol, buys and sells mixed freely.

// Each interval: per-symbol MarketStates (recipe 10), one portfolio decision.
long[] due = new long[basketSize];
pe.decide(scheduleFraction, states, due);      // zero-alloc
// route due[h] per symbol; report fills:
pe.onFill(msft, filledQty, fillPrice);         // maintains the net ledger
```

The overlays only ever *reduce* a child's own due — the ahead leg throttles,
the lagging leg is never pushed past its own benchmark — so each symbol's
benchmark integrity holds by construction, and anything deferred reappears
through that child's behind-schedule catch-up. When the budget binds,
capacity goes to weight ∝ (1 + vol regime) × due notional by default — the
diagonal approximation of multi-asset Almgren-Chriss. To make it the real
thing, feed a streaming covariance matrix one return vector per interval
and plug it in:

```java
var cov = new EwmaCovariance(basketSize, 0.97);
cov.onReturns(intervalReturns);            // per interval, on your clock
pe.useRiskModel(cov);                      // budget now flows by marginal
                                           // contribution to BASKET risk
```

Two correlated legs then read as one concentrated risk, and a natural
hedge earns no extra urgency; while the matrix is unlearned the executor
falls back to the vol-regime weights.

---

## 12. Survive the overnight (persist learned state)

Everything the models learn — volume/vol/spread baselines, alpha weights
and their out-of-sample IC evidence, venue and LP scorecards — should not
be relearned from zero every morning. One checkpoint file, written at end
of day, restored at session start:

```java
// End of day: one atomic file of named sections.
try (var w = Checkpoint.writer(Path.of("eod.qflc"))) {
    w.section("volume.AAPL", volumeCurve::writeState)
     .section("vol.AAPL", volCurve::writeState)
     .section("alpha.AAPL", learner::writeState)
     .section("venues", scorecard::writeState);
}

// Next session start: restore. A missing SECTION is a cold start for that
// model; a missing FILE (the very first morning) throws from reader() —
// guard it.
if (Files.exists(Path.of("eod.qflc"))) {
    var r = Checkpoint.reader(Path.of("eod.qflc"));
    r.section("volume.AAPL", volumeCurve::readState);
    r.section("alpha.AAPL", learner::readState); // weights + IC evidence travel together
}
```

The instance must be constructed with the same configuration (bucket/venue
counts) — a mismatch throws rather than misaligning arrays. Intraday state
deliberately resets on restore: you restore at session start, not
mid-stream. The commit is temp-file + atomic rename, so a crash mid-save
leaves yesterday's checkpoint intact. For `DayTypeProfiles`, write one
section per day type (`"volume.AAPL.day0"`, `"volume.AAPL.day1"`, …).

---

## 13. Price an autocallable and RFQ it to the panel

Structured products trade by request-for-quote: price the note with the
model, ask the dealer panel, deal on the best — and record the cover and
each dealer's spread-to-fair, which is how tomorrow's panel gets chosen.

```java
// The note: quarterly observations, 2% memory coupons, 60% knock-in.
Autocallable note = new Autocallable(1_000_000,
        new double[]{0.25, 0.5, 0.75, 1.0}, 1.00, 0.80, 0.60, 0.02, true);
double fair = note.price(spot, initial, vol, rate, divYield, 200_000, 42);
// vol: pick it from the DOWNSIDE smile region (the knock-in put lives
// there), e.g. via VolSurface — flat ATM vol underprices the risk.

RfqAuction rfq = new RfqAuction(true, fair, dealerCount, nowNanos);
// ...dealer responses arrive:
rfq.onQuote(dealer, price, tsNanos);
// Deal on rfq.winner(); rfq.coverPrice() is what it would have cost
// without them; rfq.winnerSpreadToFairBps() is what you paid vs theory.
panel.onAuction(rfq);                    // RfqDealerScorecard learns
```

The scorecard's quote rate, response time, spread-to-fair and win rate
per dealer persist overnight via `Checkpoint` (recipe 12) — panel
selection is a learning loop, exactly like venue and LP selection.

## 14. Run a central risk book day (net → internalize → hedge → route)

Two desks paying the street to shed opposite risks is money burned
twice. The CRB nets first, keeps risk-reducing client flow (and pays
for it), hedges only the excess, and routes the hedge through its own
inventory before anyone else's dark pool.

```java
CentralRiskBook book = new CentralRiskBook();
InternalizationEngine engine = new InternalizationEngine(5_000_000, 0.4);
CrbPnlLedger ledger = new CrbPnlLedger();

// Client flow arrives all day (book what the BOOK absorbs):
var d = engine.decide(book.exposure("EQ:SPY"), clientNotional, 2.0);
if (d.internalized() != 0) {
    book.bookCashEquity("client-flow", "SPY", d.internalized() / px, px);
}
ledger.onDecision(d, 2.0);               // spread captured, improvement paid

// Quote the next client with the inventory shading in the price:
var q = SkewedQuoter.quote(mid, 2.0, book.exposure("EQ:SPY"), 5_000_000, 0.5);

// Band breach? Hedge the EXCESS with the cheapest spanning basket:
CrbHedgeUniverse hedges = new CrbHedgeUniverse(book.factors())
        .addSingleFactor("ES-FUTURE", "EQ:SPX", 0.4)
        .addFxForward("EURUSD-1W", "EURUSD", 1.10, 0.2);
var orders = hedger.check(book.netExposures(), cov,
        hedges.loadings(), hedges.costs(), lambda, interval);
for (var o : orders) {
    var alloc = CrbRouter.route(Math.abs(o.notional()), crossable,
            darkVenues, halfSpreadBps, impactBps);   // internal → dark → lit
    ledger.onHedge(o.notional(), costBps);
    ledger.onRoute(Math.abs(o.notional()), alloc);
}

// The close: did the netting pay for its own risk management?
double answer = ledger.netEconomics();   // positive = yes
var report = book.report(cov, 0.99);     // and the diversification benefit
```

Everything survives the night via `Checkpoint` sections (book, engine
counters, ledger — recipe 12), and `CrbRealWorldScenarioTest` runs the
whole week: quiet two-way day, one-way institutional day (the hedge
escalation), a COVID-style stress replay to the dollar, and an NDF
fixing day. Details in `docs/CENTRAL_RISK_BOOK.md`.

---

Every number quoted here comes from a committed benchmark; every behavior
from a committed test. When a recipe and the javadoc disagree, the javadoc
wins — and please open an issue.
