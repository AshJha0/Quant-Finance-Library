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
CrbAutoHedger hedger = new CrbAutoHedger(limits, 0.6, 1);   // bands, reset, cooldown
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

## 15. Trade a pairs spread (cointegration → half-life → legging control)

The three questions of pairs trading, in order: is the spread tethered,
how fast does it snap back, and how do you get in without owning half a
trade?

```java
// 1. Is the spread stationary at all? Two random walks can look
//    correlated for years and never come back.
var coint = CointegrationTest.engleGranger(pricesA, pricesB);
if (!coint.cointegrated5pct()) return;          // no tether, no trade

// 2. How fast does it revert? Fit OU to the spread series.
double[] spread = /* pricesA - hedgeRatio * pricesB */;
OrnsteinUhlenbeck.Params ou = OrnsteinUhlenbeck.fit(spread, 1.0 / 252);
// ou.halfLife() in years: 8 trading days ≈ 0.032. A 200-day half-life
// is an index fund with extra steps — skip it.
double z = ou.zScore(spread[spread.length - 1]);
if (Math.abs(z) < 2.0) return;                  // not stretched enough

// 3. Execute with the legging cap: work the ILLIQUID leg patiently,
//    let the liquid hedge leg chase, stop everything at the cap.
SpreadExecutionAlgo algo = new SpreadExecutionAlgo(
        leadQty, hedgeRatio, leggingCapHedgeUnits, leadChildMax);
while (!algo.done()) {
    var children = algo.decide();
    // route children.leadQty() passively (OrderPlacementPolicy says
    // post or cross), children.hedgeQty() aggressively; report fills:
    algo.onLeadFill(leadFilled);
    algo.onHedgeFill(hedgeFilled);
    if (children.atRiskCap()) { /* you are ALL hedge child now */ }
}
```

Exit at |z| < 0.5 — or immediately if the cointegration breaks (a
takeover announcement doesn't stretch the rubber band, it CUTS it).
`OrnsteinUhlenbeck.fit` refuses a series with no in-sample mean
reversion rather than reporting an infinite half-life as a holding
period.

## 16. Run a market-risk day (VaR → ES → stress → reverse stress → report)

The risk manager's actual afternoon, on a netted factor book — the full
14-step map is `docs/MARKET_RISK.md`.

```java
double[] exposures = book.netExposures();       // e.g. from CentralRiskBook
double[][] cov = /* factor covariance: EwmaCovariance or sample */;

// The four headline numbers (five flavors available):
double var99 = VarEngine.deltaNormalVar(exposures, cov, 0.99);
double es99 = VarEngine.deltaNormalEs(exposures, cov, 0.99);       // post-FRTB, ES leads
double dgVar = VarEngine.deltaGammaVar(exposures, gamma, cov, 0.99); // options books
var fullReval = VarEngine.fullRevaluationVar(scenarios,
        moves -> yourPricer.pnl(moves), 0.99);   // sees the knocked-out barrier

// Named stress: what would March 2020 do to THIS book, today?
double covid = StressTester.scenarioPnl(templateExposures,
        StressTester.covidMarch2020());

// The regulator's inverted question: what move loses 2M, and how
// implausible is that move?
var reverse = StressTester.reverseStress(exposures, cov, 2_000_000);
// reverse.mahalanobisSigmas() > 10: the netting bought you comfort.
// < 3: the book breaks on a Tuesday — fix it TODAY.

// Beyond-sample tail: EVT extrapolation with honest refusals.
var tail = ExtremeValueTheory.fitPot(dailyLosses, 0.95);
double var999 = tail.var(0.999);                 // beyond the sample, honestly
// tail.expectedShortfall throws if the fitted tail has no finite mean.

// The Basel wrap: ES cascade, backtest zone, PLAT.
double frtbEs = FrtbEs.liquidityHorizonEs(esByHorizon, horizons);
var zone = FrtbEs.TrafficLight.of(exceptionsLast250Days);
var plat = PnlAttribution.test(hypotheticalPnl, riskTheoreticalPnl);
```

Every formula here is pinned by a hand-computed test in
`MarketRiskTest`/`MarketRiskPricingTest` — including the Heston
BS-limit test that caught a real numerical bug before release.

## 17. Defend a quote against toxic flow (VPIN → skew → post-or-cross)

The market maker's survival loop: measure toxicity, shade the quote,
and stop paying to be run over.

```java
Vpin vpin = new Vpin(avgDailyVolume / 50, 50);   // the classic buckets
// per classified trade (TradeClassifier supplies the aggressor side):
vpin.onTrade(qty, buyAggressor);

double toxicity = vpin.ready() ? vpin.vpin() : 0.3;   // prudent default
double half = baseHalfSpreadBps * (1 + 2 * toxicity); // widen as it climbs

// Inventory-aware two-way price (recipe 14's CRB skew, solo edition):
var quote = SkewedQuoter.quote(mid, half, inventory, inventoryLimit, 0.5);

// And the passive-vs-aggressive hedging decision as arithmetic, not
// habit: fill probability, adverse selection (markouts), drift, rebate.
var region = OrderPlacementPolicy.postRegion(halfSpread,
        adverseSelectionFromMarkouts, expectedDrift, makerRebate);
boolean post = region.contains(fillProbability);
// In the toxic regime the region is EMPTY: cross and be done — a
// passive fill there means the market just came THROUGH you.
```

VPIN reads near 0 on balanced flow and toward 1 on one-sided informed
flow (famously elevated before the 2010 flash crash). Past your
threshold, the honest moves are: widen, shrink, or stop quoting.
Being the last quote standing in toxic flow is how you buy every top
tick.

## 18. Build a yield curve and hedge a bond with key-rate durations

Where does a bond's rate risk actually live? Bootstrap the curve, price
the position off it, then bump one node at a time — the KRD ladder IS the
hedging recipe. (Money-market deposits are already zeros — feed those
pillars through `YieldCurve.ofZeroRates`; par swap quotes bootstrap.)

```java
int[] tenors = {1, 2, 3, 5, 7, 10, 30};
double[] parRates = {0.0320, 0.0335, 0.0345, 0.0360, 0.0370, 0.0380, 0.0400};
YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(tenors, parRates);
System.out.printf("DF(5y)=%.4f  fwd(4y,5y)=%.4f%n",
        curve.discountFactor(5), curve.forwardRate(4, 5));

// The position: 10M face of a 4% semi-annual 10-year bond.
double face = 10_000_000;
double price = BondPricer.priceFromCurve(100, 0.04, 2, 10, curve);
double ytm = BondPricer.yieldToMaturity(price, 100, 0.04, 2, 10);
double dv01 = BondPricer.dv01(100, 0.04, 2, 10, ytm) * face / 100;  // $/bp, parallel

// WHERE the risk lives: bump ONE node 1bp, reprice, repeat per node.
double[] krd = KeyRateDurations.keyRateDv01s(100, 0.04, 2, 10, curve);
double parallel = KeyRateDurations.parallelDv01(100, 0.04, 2, 10, curve);
// sum(krd) == parallel within interpolation tolerance — the consistency check.

// The hedge: per bucket, the pay-fixed swap notional that cancels that node.
double[] nodes = curve.tenors().stream().mapToDouble(Double::doubleValue).toArray();
for (int i = 0; i < krd.length; i++) {
    if (Math.abs(krd[i]) < 0.001) continue;              // only material buckets
    double y = curve.zeroRate(nodes[i]);
    double hedgeDv01 = BondPricer.dv01(100, y, 1, nodes[i], y);  // par swap proxy, per 100
    System.out.printf("%4.0fy  KRD %8.4f  hedge notional %,14.0f%n",
            nodes[i], krd[i], face * krd[i] / hedgeDv01);
}
```

The parallel DV01 says *how much* a basis point costs; the ladder says
*which* basis point. A 10-year bullet concentrates at the 10y node with a
coupon strip smeared across the short nodes — hedging only the big bucket
leaves the strip unhedged, which is exactly what a curve steepener bill
looks like. `ShortRateModels.hullWhiteBond` prices off the same curve when
you need the dynamics, not just the statics.

## 19. Construct a portfolio three ways (and see why they disagree)

Same expected returns, same covariance — three philosophies about how much
to trust the return estimates.

```java
double[] mu = {0.08, 0.10, 0.12};                 // annualized
double[][] cov = {{0.0400, 0.0180, 0.0120},
                  {0.0180, 0.0900, 0.0240},
                  {0.0120, 0.0240, 0.1600}};

// 1. Max Sharpe: trusts mu COMPLETELY — and concentrates accordingly.
var maxSharpe = new PortfolioOptimizer(mu, cov).maxSharpe(0.03);

// 2. Risk parity: trusts mu NOT AT ALL (used only for reporting);
//    every asset contributes the same share of portfolio variance.
var riskParity = RiskParityOptimizer.equalRiskContribution(mu, cov);
double[] rc = RiskParityOptimizer.riskContributions(riskParity.weights(), cov);
// each rc[i] == 1/3 — that IS the definition.

// 3. Black-Litterman: start from what the MARKET must believe (reverse-
//    optimize the market-cap weights), then tilt by one explicit view.
double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, cov,
        new double[]{0.5, 0.3, 0.2});             // market portfolio
double[] posterior = BlackLitterman.posteriorReturns(0.025, cov, pi,
        new double[][]{{0, 1, -1}},               // view: asset 2 beats asset 3
        new double[]{0.02},                       // ...by 2% a year
        new double[]{0.001});                     // ...held with this much doubt
var bl = new PortfolioOptimizer(posterior, cov).maxSharpe(0.03);

System.out.println("max Sharpe : " + Arrays.toString(maxSharpe.weights()));
System.out.println("risk parity: " + Arrays.toString(riskParity.weights()));
System.out.println("BL + view  : " + Arrays.toString(bl.weights()));
```

They differ because they price estimation error differently. Max-Sharpe
piles into whatever `mu` flatters — garbage-in, leverage-out. Risk parity
throws `mu` away and allocates by risk alone, so the low-vol asset gets
the big weight. Black-Litterman sits between: the equilibrium prior keeps
weights near the market, and each view moves them only as far as its
stated confidence (shrink `omega` toward 0 and the BL weights walk toward
the max-Sharpe answer; inflate it and they walk back to the market).

## 20. Prove your backtest isn't overfit

A grid search computes N Sharpes and quietly reports the maximum. Before
believing the winner, make it survive all four defenses — each catches a
different way of lying to yourself.

```java
ParameterGrid grid = new ParameterGrid().add("fast", 10, 20, 30)
                                        .add("slow", 50, 100, 200);
StrategyFactory factory = p ->
        new SmaCrossStrategy(p.get("fast").intValue(), p.get("slow").intValue());
BacktestConfig config = BacktestConfig.defaults();

// The temptation: nine trials, keep the best in-sample Sharpe.
var ranked = GridSearchOptimizer.search(grid, factory, series, config,
        PerformanceMetrics::sharpeRatio);

// (a) Walk-forward: re-optimize on each train window, score only unseen bars.
var wf = WalkForwardAnalyzer.analyze(series, grid, factory, config,
        504, 126, PerformanceMetrics::sharpeRatio);

// (b) Deflated Sharpe: winner's Sharpe vs the best of 9 zero-skill trials.
double[] eq = Backtester.run(factory.create(ranked.getFirst().params()),
        series, config).equityCurve();
double[] winnerReturns = new double[eq.length - 1];
for (int t = 1; t < eq.length; t++) winnerReturns[t - 1] = eq[t] / eq[t - 1] - 1;
double dsr = GridSearchOptimizer.deflatedSharpeOfWinner(ranked, winnerReturns, 252);

// (c) CSCV: across every symmetric IS/OOS split, how often is the
//     in-sample winner a BELOW-MEDIAN out-of-sample performer?
double[][] variantReturns = new double[eq.length - 1][ranked.size()];
for (int j = 0; j < ranked.size(); j++) {
    double[] e = Backtester.run(factory.create(ranked.get(j).params()),
            series, config).equityCurve();
    for (int t = 1; t < e.length; t++) variantReturns[t - 1][j] = e[t] / e[t - 1] - 1;
}
var pbo = OverfitProbability.cscvSharpe(variantReturns, 8);

// (d) Training an ML model on the same bars? A 5-bar forward-return label
//     leaks into ordinary K-fold; purge the horizon and embargo the echo.
var splits = PurgedKFold.splits(series.size(), 5, 5, (int) (0.01 * series.size()));

System.out.printf("WF efficiency %.2f · deflated Sharpe %.2f · PBO %.2f%n",
        wf.efficiency(), dsr, pbo.pbo());
```

The decision rule: walk-forward efficiency near 1 is robust, near 0 is
curve-fitting; deflated Sharpe below ~0.95 means the winner is
indistinguishable from the luckiest of nine random trials; and **PBO ≥ 0.5
means the selection process is pure noise-mining** — the config you would
have picked is a coin-flip to be an out-of-sample loser, regardless of how
good its backtest looks. Any single failure is a rejection; they test
different lies.

## 21. Speak FIX to a venue (logon → orders → resend → logout)

The whole session protocol — Logon handshake, heartbeats with TestRequest
probing, sequence-gap ResendRequest recovery, Logout — runs on the
session's own threads; your code sends orders and receives reports.

```java
FixSession.Listener desk = new FixSession.Listener() {
    @Override public void onExecutionReport(FixSession s, ExecutionReport r) {
        if (r.isFill()) { /* position += r.lastQty() at r.lastPrice() */ }
    }
    @Override public void onSequenceGap(long expected, long received) {
        // Session already sent ResendRequest(2); the missed messages are
        // redelivered with PossDupFlag, admin runs arrive as GapFill.
    }
    @Override public void onDisconnect(String reason) { /* alert + reconnect */ }
};

try (FixSession session = FixSession.initiate("fix.venue.example", 9880,
        new FixSession.Config("MYDESK", "VENUE", 30).withCredentials("user", "***"),
        desk, new FileSessionStore(Path.of("fix-state")))) {   // seqnums survive restarts
    // initiate() returns AFTER the Logon handshake; heartbeats now run themselves.
    session.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000_000,
            1.0851, NewOrderSingle.TIF_DAY);   // limitPrice = NaN → market order
    session.sendOrderCancelReplace("ord-2", "ord-1", "EURUSD", Side.BUY,
            1_000_000, 1.0849, NewOrderSingle.TIF_DAY);
    // ... trade the day; ExecutionReports arrive on the listener ...
    session.logout();                          // Logout handshake, then close
}
```

The venue side is the same class: `FixSession.accept(serverSocket, config,
listener)` receives your `onNewOrderSingle` and answers with
`sendExecutionReport(ExecutionReport.accepted(...))` /
`ExecutionReport.filled(...)` — `FixSessionTest` drives both ends of a
real TCP socket through every path above, including a forced sequence gap
and the PossDup-flagged replay that heals it. With the `FileSessionStore`,
a restart resumes yesterday's sequence numbers and can still service the
peer's ResendRequest for messages sent before the crash.

## 22. Run a best-execution report

The compliance afternoon: slippage versus arrival on every parent order,
the message-discipline ratio, the spread decomposition — and one screen
for the 4pm fix.

```java
BestExecutionAnalyzer bestEx = new BestExecutionAnalyzer();
// One outcome per parent order (unfilled: filled=false, price/latency ignored).
bestEx.add(new BestExecutionAnalyzer.OrderOutcome("ord-1", "XLON", Side.BUY,
        10_000, 100.00, 100.02, 45_000_000L, true));
// ... every order of the day ...
var rep = bestEx.report();
System.out.printf("fill %.0f%% · slip %.2f bps · median latency %.1f ms · "
                + "at-or-better %.0f%% · by venue %s%n",
        rep.fillRate() * 100, rep.avgSlippageBps(), rep.medianLatencyToFillMillis(),
        rep.atOrBetterThanArrivalPct() * 100, rep.avgSlippageBpsByVenue());

// Message discipline — the number regulators read first:
double otr = MarketQualityMetrics.orderToTradeRatio(messagesToday, tradesToday);

// Spread decomposition per fill: effective ≈ realized + impact.
double effective = MarketQualityMetrics.effectiveSpreadBps(Side.BUY, fillPx, midAtExec);
double realized  = MarketQualityMetrics.realizedSpreadBps(Side.BUY, fillPx, midAfter5m);
double impact    = MarketQualityMetrics.priceImpactBps(Side.BUY, midAtExec, midAfter5m);

// Banging-the-close screen: big share of the fixing window + run-up
// aligned with the desk's net flow + reversion after = the signature.
var screen = FixAnalyzer.analyze(midSamplesInWindow, preWindowMid, postWindowMid,
        deskBuyQty, deskSellQty, windowMarketVolume, 0.25);
if (screen.flagged()) { /* escalate BEFORE the regulator asks */ }
```

Impact that persists is information; impact that decays was pressure —
that's why the screen demands reversion, not just a price move. A high
order-to-trade ratio with clean slippage numbers still gets you a venue
letter; `BestExecutionAnalyzer` and the message counts come from the same
day's FIX logs, so run them together.

---

Every number quoted here comes from a committed benchmark; every behavior
from a committed test. When a recipe and the javadoc disagree, the javadoc
wins — and please open an issue.
