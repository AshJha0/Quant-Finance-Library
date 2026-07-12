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

## 23. Price and mark a variance swap

A variance swap pays realized variance minus a strike, and its fair strike
needs no volatility model at all: the 1/K^2 option portfolio replicates it
straight off the chain. That makes it the desk's cleanest pure-vol trade
to price, book, and mark.

```java
// One expiry's chain (generated at a flat 20 vol so the answer is known):
double forward = 100, rate = 0.02, tYears = 1.0;
int n = 29;
double[] strikes = new double[n], puts = new double[n], calls = new double[n];
for (int i = 0; i < n; i++) {
    strikes[i] = 30 + 5.0 * i;                       // 30..170: wide wings
    puts[i] = Black76.price(OptionType.PUT, forward, strikes[i], rate, 0.20, tYears);
    calls[i] = Black76.price(OptionType.CALL, forward, strikes[i], rate, 0.20, tYears);
}

// Model-free fair strike via the 1/K^2 replication: a VIX of 20 IS 0.04.
double kVar = VarianceSwap.fairVariance(strikes, puts, calls, forward, rate, tYears);

// Dealers quote VEGA, the swap settles VARIANCE -- the bridge is 2*K_vol:
double varNotional = VarianceSwap.varianceNotional(100_000, Math.sqrt(kVar));

// Six months in: 18 vol realized so far, the residual half reprices at 21.
double kRemaining = 0.21 * 0.21;   // = fairVariance on TODAY'S chain for [t, T]
double mtm = VarianceSwap.markToMarket(kVar, 0.18 * 0.18, kRemaining,
        0.5, tYears, rate);        // per unit of variance notional
System.out.printf("fair var %.4f, MTM $%,.0f%n", kVar, mtm * varNotional);

// The VOL swap is NOT model-free: its strike needs a vol-of-vol input.
double kVol = VarianceSwap.volSwapStrike(kVar, 0.004);   // Var(V) from your model
```

The mark works because variance is ADDITIVE in time: the elapsed leg is
locked realized variance, the remaining leg is just a fresh fair strike,
and the blend discounts against the original K -- no model, which is why
variance swaps mark cleanly. The vol swap strike always sits BELOW
sqrt(fair variance) (Jensen: square root is concave), and the gap grows
with vol-of-vol -- that convexity correction is exactly the model risk a
vol-swap desk charges for and a variance-swap desk avoids.

## 24. Price rates volatility off the curve

Swaptions and caps are Black-76 options on rates the curve already knows;
the two parity identities below are vol-free, which makes them the first
debugging tool when a rates-vol number looks wrong.

```java
// A flat 3% curve keeps every number legible.
double[] tenors = {1, 2, 3, 5, 7, 10};
double[] zeros = {0.03, 0.03, 0.03, 0.03, 0.03, 0.03};
YieldCurve curve = YieldCurve.ofZeroRates(tenors, zeros);

// The 1y-into-5y swaption's underlying: forward swap rate + annuity.
double f = RatesOptions.forwardSwapRate(curve, 1, 5);
double a = RatesOptions.annuity(curve, 1, 5);

double strike = 0.032, vol = 0.25;                    // flat Black-76 vol
double payer = RatesOptions.swaption(curve, 1, 5, strike, vol, true);
double receiver = RatesOptions.swaption(curve, 1, 5, strike, vol, false);

// Swaption put-call parity: payer - receiver = annuity * (F - K), ANY vol.
System.out.printf("parity gap %.2e%n", (payer - receiver) - a * (f - strike));

// A 5y cap, its floor, and the second identity: cap - floor = the swap.
double cap = RatesOptions.cap(curve, 5, strike, vol);
double floor = RatesOptions.floor(curve, 5, strike, vol);
double swapPv = RatesOptions.annuity(curve, 0, 5)
        * (RatesOptions.forwardSwapRate(curve, 0, 5) - strike);
System.out.printf("cap %.5f  floor %.5f  cap-floor-swap gap %.2e%n",
        cap, floor, (cap - floor) - swapPv);
```

Note where the discounting went: the swaption's natural numeraire is the
ANNUITY, so the Black-76 leg is called with rate 0 and the annuity does
all the discounting -- a payer swaption is a call on the swap rate paying
when rates rise. The cap's first caplet fixed today, so it contributes
pure intrinsic (the market convention); the vol cancels out of both
identities, so any gap bigger than floating-point noise is a bug in the
inputs, not in the smile.

## 25. Allocate risk like a risk committee

Portfolio VaR is a fact; "which desk owns how much of it?" is the
decision. The Euler split answers it exactly -- and its three numbers
answer three DIFFERENT questions.

```java
// The book: three desks, the third one short -- a deliberate hedge.
String[] desk = {"tech", "energy", "index-hedge"};
double[] w = {10_000_000, 8_000_000, -5_000_000};    // signed exposures ($)
double[][] cov = {{0.0400, 0.0100, 0.0200},
                  {0.0100, 0.0625, 0.0150},
                  {0.0200, 0.0150, 0.0225}};         // annual return covariance

var alloc = ComponentVar.allocate(w, cov, 0.99);
System.out.printf("portfolio VaR $%,.0f%n", alloc.portfolioVar());
for (int i = 0; i < w.length; i++) {
    System.out.printf("%-12s component $%,12.0f  marginal %.4f  closing moves VaR $%,.0f%n",
            desk[i], alloc.components()[i], alloc.marginals()[i],
            -ComponentVar.incremental(w, cov, 0.99, i));
}
// Components sum EXACTLY to portfolio VaR -- no diversification residual
// to argue over -- and the hedge's component is NEGATIVE: its incremental
// shows that closing it RAISES the book's VaR.

// Before an optimizer touches an ESTIMATED matrix, stabilize it: the
// sample covariance is the maximally overfit input an optimizer can get.
double[][] returns = /* the T x N daily return panel behind cov (rows = days) */;
var lw = CovarianceShrinkage.ledoitWolf(returns);    // intensity chosen FROM the data
double[] mu = {0.08, 0.07, 0.05};
var raw = new PortfolioOptimizer(mu, cov).maxSharpe(0.03);
var shrunk = new PortfolioOptimizer(mu, lw.matrix()).maxSharpe(0.03);
System.out.printf("delta=%.2f  raw %s  shrunk %s%n", lw.intensity(),
        Arrays.toString(raw.weights()), Arrays.toString(shrunk.weights()));
```

Component VaR is the risk-budget number (who owns today's risk), marginal
VaR prices the NEXT dollar (where growth is cheap), incremental VaR prices
closing the whole position -- and the hedge is the case where they visibly
disagree, which is why committees need all three. The Ledoit-Wolf
intensity is self-calibrating: short history and many names push delta
toward 1 (trust the boring target), long history pushes it toward 0 -- and
the shrunk matrix is always positive-definite, which the raw one from
short history is not guaranteed to be.

## 26. Price the two-asset zoo

Three closed forms cover most of the relative-value option book: Margrabe
for exchanges, Kirk for spreads with a strike, and the quanto adjustment
for payoffs settled in the wrong currency.

```java
// Margrabe: the right to swap asset 2 for asset 1 -- a BS call in disguise.
double marg = ExchangeOption.margrabe(105, 100, 0.01, 0.02, 0.30, 0.25, 0.5, 1.0);
// No rate in the argument list: a ratio has no financing cost. The vol is
// the RATIO's: var = vol1^2 + vol2^2 - 2 rho vol1 vol2 -- at rho = 1 with
// equal vols it hits zero and the option is pure forward intrinsic.

// Kirk: a spread call max(0, F1 - F2 - K) on two forwards.
double kirk = ExchangeOption.kirkSpreadCall(105, 100, 5, 0.03, 0.30, 0.25, 0.5, 1.0);
// A difference of lognormals is not lognormal, so this is an
// approximation -- exact in both limits, a few bps off for moderate K.

// The K = 0 limit IS Margrabe (zero yields make spots forwards; rate 0
// drops the discounting) -- pinned numerically:
double kirk0 = ExchangeOption.kirkSpreadCall(105, 100, 0, 0, 0.30, 0.25, 0.5, 1.0);
double marg0 = ExchangeOption.margrabe(105, 100, 0, 0, 0.30, 0.25, 0.5, 1.0);
System.out.printf("Kirk(K=0) %.6f == Margrabe %.6f%n", kirk0, marg0);

// Quanto: a Nikkei call settled in USD at a FIXED conversion rate.
double fQ = QuantoOption.quantoForward(30_000, 0.04, 0.01, 0.20, 0.10, 0.3, 1.0);
double call = QuantoOption.price(OptionType.CALL, 30_000, 30_000,
        0.04, 0.01, 0.20, 0.10, 0.3, 1.0);
// rho > 0: the hedger's FX losses cluster with asset rallies, a
// systematic drag they charge for -- the quanto forward fQ sits BELOW the
// vanilla forward 30_000 * e^(0.04 - 0.01). Only the drift moves; the
// payoff's vol stays the asset's own.
```

All three are one correlation input away from vanilla Black-Scholes, and
that input is the risk: a crack-spread book marked with Kirk lives and
dies on rho, not on either leg's vol. Kirk is known to degrade for very
large strikes with a high second-leg vol -- an approximation, stated, not
a theorem.

## 27. Fit and read a yield curve like a macro desk

Nelson-Siegel compresses the whole curve into four numbers a macro desk
can argue about -- level, slope, curvature, and where the hump sits -- and
the KRD ladder then says which of those a bond position is actually short.

```java
// Sample zeros: short end above the long end -- a LATE-CYCLE curve.
double[] tenors = {0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30};
double[] zeros = {0.0525, 0.0520, 0.0505, 0.0465, 0.0435,
                  0.0405, 0.0395, 0.0395, 0.0410, 0.0415};
NelsonSiegel.Fit fit = NelsonSiegel.fit(tenors, zeros);

// Four numbers carry the whole story:
System.out.printf("level b0=%.4f  slope b1=%+.4f  curvature b2=%+.4f  lambda=%.2fy  rmse=%.5f%n",
        fit.b0(), fit.b1(), fit.b2(), fit.lambda(), fit.rmse());
// b0 = z(inf), where the long end settles; z(0) = b0 + b1, so b1 > 0 IS
// inversion -- the recession-signal number. The inversion check:
System.out.printf("short %.4f vs long %.4f -> %s%n", fit.shortRate(), fit.longRate(),
        fit.shortRate() > fit.longRate() ? "INVERTED" : "upward");

// The follow-through: WHERE a 10y 4% bond's curve risk lives, per bucket.
YieldCurve curve = YieldCurve.ofZeroRates(tenors, zeros);
double[] krd = KeyRateDurations.keyRateDv01s(100, 0.04, 2, 10, curve);
double parallel = KeyRateDurations.parallelDv01(100, 0.04, 2, 10, curve);
double[] nodes = curve.tenors().stream().mapToDouble(Double::doubleValue).toArray();
double sum = 0;
for (int i = 0; i < krd.length; i++) {
    sum += krd[i];
    if (Math.abs(krd[i]) > 0.001) {
        System.out.printf("%5.2fy bucket  KRD %8.4f per 100 face%n", nodes[i], krd[i]);
    }
}
System.out.printf("sum(KRD) %.4f vs parallel DV01 %.4f%n", sum, parallel);
```

The fit is honest about shape: betas are unconstrained, so an inverted
curve comes back as b1 > 0 rather than an error -- and level/slope/
curvature are, to good approximation, the first three PCA factors of
yield curves, which is why central banks publish in this form. The KRD
printout is the hedge shopping list: each material bucket is offset with
the swap that drives that node (recipe 18 turns the ladder into pay-fixed
notionals), and sum(KRD) reconciling to the parallel DV01 is the
consistency check that says no bucket got lost. Use the parametric fit to
SMOOTH and COMPARE; use the bootstrap (recipe 18) when every input must
reprice exactly.

## 28. Load and clean a messy CSV of bars

Real vendor files arrive with semicolon delimiters, capitalized headers, quoted thousands separators, unordered rows and blank lines -- `CsvBarLoader` swallows all of it and hands back a sorted `BarSeries`.

```java
BarSeries series = CsvBarLoader.parse(List.of(
        "Date;Open;High;Low;Close;Volume",              // any delimiter, any case
        "2024-01-03;101.2;102.9;100.8;102.1;1200",      // rows out of order: sorted
        "",                                             // blank lines: skipped
        "2024-01-02;100.0;101.5;99.7;101.2;\"1,150\"",  // RFC-4180 quoted thousands
        "2024-01-04;102.1;103.4;101.9;103.0;1300"),
        "ACME");

// Files work the same way; header aliases (timestamp/time/datetime, adj close,
// vol) and epoch timestamps are handled without configuration.
BarSeries fromDisk = CsvBarLoader.load(Path.of("vendor_dump.csv"), "ACME");

// Write the normalized form back out: epoch-millis, canonical header.
CsvBarLoader.save(series, Path.of("clean.csv"));
System.out.println(series.size() + " bars, first close " + series.close(0));
```

Timestamps may be `yyyy-MM-dd`, ISO date-times, epoch millis or epoch seconds; the seconds-vs-millis question is resolved for the whole file at once (every numeric timestamp must sit in the plausible seconds range for the years 1973..5138), so one odd row cannot silently land in January 1970 -- but do not mix epoch numbers and ISO dates in the same file, since a file containing any ISO date treats all numeric timestamps as millis. `save` always writes epoch millis, which makes the round trip the cheapest way to canonicalize a pile of inconsistent vendor files.

## 29. Back-adjust a series through splits and dividends

A raw price series drops 75% on a 4-for-1 split date and bleeds a spurious negative return on every ex-dividend date; `CorporateActions.adjust` rewrites history with CRSP-style multiplicative factors so returns across ex-dates reflect economics.

```java
BarSeries raw = CsvBarLoader.load(Path.of("aapl_raw.csv"), "AAPL");

long splitEx = LocalDate.of(2020, 8, 31)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
long divEx = LocalDate.of(2020, 11, 6)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

BarSeries adjusted = CorporateActions.adjust(raw, List.of(
        new CorporateActions.CorporateAction(
                splitEx, CorporateActions.Type.SPLIT, 4),          // 4-for-1
        new CorporateActions.CorporateAction(
                divEx, CorporateActions.Type.CASH_DIVIDEND, 0.205))); // $ per share

// The input is untouched; the adjusted copy is what you backtest.
System.out.printf("raw close before split %.2f -> adjusted %.2f%n",
        raw.close(0), adjusted.close(0));
```

Bars before each ex-date get prices divided by the split ratio (volume multiplied by it), and dividends scale prior prices by `(prevClose - d) / prevClose` where `prevClose` is the last close before the ex-date -- so the adjusted series shows no mechanical jump at either event, only genuine market moves. The ex-timestamp is the first bar trading on the new basis, in the same epoch units as the series; a dividend at or above the prior close is rejected loudly rather than producing negative prices.

## 30. Align a multi-asset panel two ways

Different holidays, listing dates and data gaps mean multi-asset series never share a timeline; `SeriesAligner` builds the index-aligned panel that `PortfolioBacktester` requires, and the two policies fail in opposite directions.

```java
Map<String, BarSeries> raw = new LinkedHashMap<>();
raw.put("SPY", CsvBarLoader.load(Path.of("spy.csv"), "SPY"));
raw.put("EFA", CsvBarLoader.load(Path.of("efa.csv"), "EFA"));   // European holidays
raw.put("IPO", CsvBarLoader.load(Path.of("ipo.csv"), "IPO"));   // listed mid-sample

// Strict: only timestamps present in EVERY series survive.
Map<String, BarSeries> strict = SeriesAligner.intersect(raw);

// Permissive: union of timestamps from the latest first-bar onward;
// gaps carry the previous close forward as a flat zero-volume bar.
Map<String, BarSeries> filled = SeriesAligner.unionForwardFill(raw);

System.out.printf("intersect: %d bars, unionForwardFill: %d bars%n",
        strict.get("SPY").size(), filled.get("SPY").size());
```

Each policy lies in its own way. `intersect` deletes real bars: one asset's holiday removes that date for everyone, so a big SPY move on a European holiday simply vanishes from the panel -- fine for a handful of liquid names on the same calendar, increasingly destructive as the universe grows. `unionForwardFill` keeps every real bar but fabricates flat ones, which damp the gappy asset's measured volatility and correlations (a stale price looks like a zero return); the zero-volume marker at least lets downstream code detect the synthetic bars. Pick `intersect` for small same-calendar universes and `unionForwardFill` for broad ones, and treat any statistic computed off filled bars with suspicion.

## 31. Build a point-in-time universe from CSV

A universe built from today's index members has already deleted every bankruptcy and acquisition; `UniverseCsvLoader` reads membership intervals, delistings and mergers into a `PointInTimeUniverse` that answers "who was tradeable on this date" honestly.

```java
PointInTimeUniverse universe = UniverseCsvLoader.parse(List.of(
        "symbol,event,date,end_date,value,acquirer_shares,acquirer",
        "AAPL,MEMBER,2005-01-03,,,,",                  // still a member
        "LEH,MEMBER,2005-01-03,2008-09-15,,,",
        "LEH,DELIST,2008-09-15,,-1.0,,",               // shareholders wiped out
        "WCOM,MEMBER,2005-01-03,2006-01-20,,,",
        "WCOM,DELIST,2006-01-20,,,,",                  // empty value: Shumway -30%
        "TWX,MEMBER,2005-01-03,2018-06-15,,,",
        "TWX,MERGER,2018-06-15,,48.53,0.5471,T"));     // cash + acquirer stock

long asOf = LocalDate.of(2008, 6, 2)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
System.out.println(universe.membersAsOf(asOf));        // [AAPL, LEH, TWX]
System.out.println(universe.isMember("LEH", asOf));    // true -- not dead YET

PointInTimeUniverse.TerminalEvent leh = universe.terminalEvent("LEH");
System.out.printf("LEH delisting return: %.0f%%%n", leh.delistingReturn() * 100);
// UniverseCsvLoader.load(Path.of("universe.csv")) reads the same format from disk.
```

The engine half is only as honest as the data half: an involuntary delisting with an empty `value` defaults to `PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN` (-30%, the Shumway 1997 convention), and the universe-aware `PortfolioBacktester` overload charges that return when a held position dies instead of quietly exiting at the last print. Dates parse exactly like `CsvBarLoader` bar timestamps -- same whole-file epoch-seconds heuristic -- so universe dates and bar timestamps line up by construction, and every parse error carries its line number because these files are hand-curated.

## 32. Record a live session and replay it deterministically

Attach a `TickCapture` to the market data bus, trade the session as usual, and get a QFLT file that replays the exact tick sequence into any experiment, forever.

```java
Path file = Path.of("session.qflt");
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, false);
     TickCapture capture = TickCapture.attach(bus, file)) {   // subscribes to all
    int eurusd = bus.registerSymbol("EURUSD");
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    bus.publish(eurusd, 1.0851, 500_000, System.nanoTime());
    // ... the live session runs; every tick lands in the file (~28 bytes each)
    bus.stop();
    System.out.println(capture.ticksWritten() + " ticks captured");
}

// Later -- possibly years later: identical ticks, as fast as the disk reads.
long replayed = TickFileReader.replay(file,
        (symbolId, price, size, timestampNanos) -> {
            // feed your strategy, book builder, or latency experiment
        });
```

Replay is deterministic and allocation-free per tick, which is the point: run a strategy tweak, a queue-position model and a latency benchmark against the same recorded reality and any difference in results is caused by your change, not by the market being different that day. `TickFileReader.replayPaced(file, handler, 1.0)` reproduces the recorded inter-tick gaps for live-like feeds (2.0 = double speed), and the handler's optional `onSymbol` callback delivers the id-to-name mapping. One caveat from the class contract: `TickCapture` writes on the bus consumer thread, so on a bus that is actively trading use `AsyncTickCapture.attach(bus, file, 1 << 20)`, which moves file I/O behind a ring buffer to a dedicated writer thread.

## 33. Fetch remote bars over HTTP

Most free market data APIs offer a CSV export, and `HttpBarFetcher` turns any such endpoint into a `BarSeries` with the same tolerant parsing as local files -- pure JDK HTTP, no dependencies.

```java
HttpBarFetcher fetcher = new HttpBarFetcher(Duration.ofSeconds(10));

// Any endpoint that serves CSV bars works; stooq's export is a public example.
BarSeries spy = fetcher.fetchCsv(
        URI.create("https://stooq.com/q/d/l/?s=spy.us&i=d"), "SPY");

System.out.printf("%d bars, %s .. %s, last close %.2f%n",
        spy.size(),
        Instant.ofEpochMilli(spy.timestamp(0)),
        Instant.ofEpochMilli(spy.timestamp(spy.size() - 1)),
        spy.lastClose());

// Cache it locally so reruns don't hammer the API (and survive it moving).
CsvBarLoader.save(spy, Path.of("cache/spy.csv"));
```

The fetcher follows redirects, sends a proper `Accept: text/csv` header, and turns any non-200 status into an `IOException` naming the URL rather than letting an HTML error page reach the CSV parser. Because the body goes straight through `CsvBarLoader.parse`, every dialect that works from disk works over the wire -- epoch or ISO timestamps, header aliases, unordered rows. The save-then-load cache pattern shown is deliberately primitive and deliberately effective: research code should hit the network once per symbol per day, and a local canonical CSV makes every later run reproducible even after the vendor changes their format.

## 34. Compute an indicator panel in one pass and stream it live

The batch `Indicators` compute twenty-plus studies as whole arrays for research, and `StreamingIndicators` are their O(1)-per-tick twins with identical seeding, so what you backtested is what runs live.

```java
BarSeries s = CsvBarLoader.load(Path.of("bars.csv"), "SPY");
double[] close = s.closes();

// Batch: one call each -- sma/ema/wma, rsi, roc, momentum, macd, stochasticRsi,
// williamsR, cci, atr, adx, bollinger, keltner, donchian, obv, vwap, cmf,
// superTrend, ichimoku, parabolicSar... all NaN through their warm-up.
double[] ema20 = Indicators.ema(close, 20);
double[] rsi14 = Indicators.rsi(close, 14);
Indicators.Macd macd = Indicators.macd(close, 12, 26, 9);
Indicators.Bollinger bb = Indicators.bollinger(close, 20, 2.0);
double[] atr14 = Indicators.atr(s, 14);
double[] vwap = Indicators.vwap(s);

// Streaming: same math, updated one tick at a time, zero allocation.
var ema = new StreamingIndicators.Ema(20);
var rsi = new StreamingIndicators.Rsi(14);
var macdLive = new StreamingIndicators.Macd(12, 26, 9);
var vwapLive = new StreamingIndicators.Vwap();
for (int i = 0; i < s.size(); i++) {
    ema.update(close[i]);
    rsi.update(close[i]);
    macdLive.update(close[i]);
    vwapLive.update((s.high(i) + s.low(i) + s.close(i)) / 3.0, s.volume(i));
}
int last = s.size() - 1;
System.out.printf("EMA batch %.4f = stream %.4f, MACD batch %.4f = stream %.4f%n",
        ema20[last], ema.value(), macd.line()[last], macdLive.line());
```

The parity is exact, not approximate: the streaming EMA seeds with the SMA of its first `period` values, the streaming RSI uses the same Wilder smoothing, and the streaming MACD composes the same seeded EMAs -- so the loop above reproduces the batch arrays value for value, warm-up NaNs included. The one contract to respect is VWAP's input: the batch version feeds the typical price `(high + low + close) / 3`, so the streaming one must too. Use batch arrays for research and the `dsl` rules (recipe 35), and the streaming classes inside live strategies on the HFT bus where recomputing an array per tick is not an option.

## 35. Build a strategy without writing a class

`StrategyBuilder` composes indicator-array rules into a backtestable strategy with entries, exits, stops and targets -- a full strategy definition in one fluent expression.

```java
BarSeries series = CsvBarLoader.load(Path.of("bars.csv"), "SPY");
double[] close = series.closes();
double[] fast = Indicators.ema(close, 12);
double[] slow = Indicators.ema(close, 26);
double[] rsi = Indicators.rsi(close, 14);
double[] sma200 = Indicators.sma(close, 200);

BacktestResult result = StrategyBuilder.named("EMA momentum, regime-filtered")
        .enterWhen(Rules.crossAbove(fast, slow)         // signal...
                .and(Rules.above(close, sma200))        // ...only in an uptrend
                .and(Rules.belowValue(rsi, 70)))        // ...and not overbought
        .exitWhen(Rules.crossBelow(fast, slow)
                .or(Rules.crossAboveValue(rsi, 80)))
        .withStopLoss(0.03)                             // 3% of entry
        .withTakeProfit(0.08)                           // 8% of entry
        .build()
        .backtest(series, 100_000);

System.out.println(result.metrics());   // Sharpe, CAGR, maxDD, trades...
```

The design makes look-ahead bias structurally hard rather than merely discouraged: a `Rule` is a predicate over a bar index into arrays that causal indicator code computed once, so a rule can combine values at `i` and `i - 1` but cannot peek at `i + 1`. The cross combinators require the previous bar on the other side of the level, so a series that opens above a threshold does not fire a phantom breakout on bar 0, and NaN warm-up bars satisfy nothing -- properties that survive arbitrary `and`/`or`/`not` composition. The built `DslStrategy` is an ordinary `TradingStrategy`, so it drops into `Backtester.run` with a custom `BacktestConfig`, the walk-forward analyzer, or anything else that takes a strategy.

## 36. Screen a universe on technicals plus fundamentals, then rank it

`StockScreener` applies hard filters first, then `RankingEngine` scores the survivors on a weighted blend of normalized criteria -- the standard two-stage pipeline, ending in a CSV.

```java
List<StockSnapshot> universe = List.of(
        new StockSnapshot("AAPL", aaplSeries,
                new Fundamentals(3.4e12, 33, 45, 6.4, 0.45, 0.005, 1.8)),
        new StockSnapshot("XOM", xomSeries,
                new Fundamentals(4.5e11, 13, 1.9, 8.9, 0.15, 0.033, 0.2)),
        new StockSnapshot("NEWCO", newcoSeries, Fundamentals.unknown()));
        // field order: marketCap, peRatio, pbRatio, eps, roe, divYield, debtToEquity

var ranked = new StockScreener(universe).screenAndRank(
        new RankingEngine()
                .addCriterion("ROE", 1.0, s -> s.fundamentals().roe())
                .addCriterion("P/E", -0.5, s -> s.fundamentals().peRatio())
                .addCriterion("momentum", 0.8,
                        s -> s.lastClose() / s.series().close(0) - 1),
        FundamentalFilters.marketCapAbove(10e9),
        FundamentalFilters.debtToEquityBelow(2.0),
        TechnicalFilters.priceAboveSma(200).and(TechnicalFilters.rsiBelow(14, 70)));

StockScreener.exportCsv(Path.of("screen.csv"), ranked);
ranked.forEach(r -> System.out.printf("%-6s %.3f%n", r.stock().symbol(), r.score()));
```

The order of the two stages is not cosmetic: `RankingEngine` min-max-normalizes each criterion across the candidate set before blending, so a single absurd P/E in the pool compresses everyone else's spread -- filter the garbage out before it can distort the normalization. Negative weights invert a criterion (lower P/E ranks higher) without a separate flag, NaN fundamentals never match a filter and score a neutral 0.5 in ranking, and scores are relative to this run's candidates -- good for "pick today's best twenty", meaningless for tracking one name through time. For historical screens, first restrict the snapshots with `StockScreener.membersAsOf(snapshots, pointInTimeUniverse, asOfTimestamp)` (recipe 31) or the screen inherits survivorship bias before any filter runs.

## 37. Train and apply a gradient-boosted alpha

`GradientBoostedRegressor` is XGBoost-style additive boosting over decision stumps in pure Java -- enough model for small tabular alpha problems, with no dependency to audit.

```java
double[] r = CsvBarLoader.load(Path.of("bars.csv"), "SPY").returns();

// Features at t (yesterday's return, 5d and 21d momentum, 21d vol) -> return at t.
int lookback = 21, n = r.length - lookback;
double[][] x = new double[n][4];
double[] y = new double[n];
for (int t = lookback; t < r.length; t++) {
    double m5 = 0, m21 = 0, v = 0;
    for (int j = t - 5; j < t; j++) m5 += r[j] / 5;
    for (int j = t - 21; j < t; j++) m21 += r[j] / 21;
    for (int j = t - 21; j < t; j++) v += (r[j] - m21) * (r[j] - m21) / 20;
    x[t - lookback] = new double[]{r[t - 1], m5, m21, Math.sqrt(v)};
    y[t - lookback] = r[t];
}

int split = (int) (n * 0.8);   // train on the past, score on the future only
GradientBoostedRegressor model = GradientBoostedRegressor.withDefaults()
        .fit(Arrays.copyOfRange(x, 0, split), Arrays.copyOfRange(y, 0, split));

System.out.printf("out-of-sample RMSE %.5f%n",
        model.rmse(Arrays.copyOfRange(x, split, n), Arrays.copyOfRange(y, split, n)));
double signal = model.predict(x[n - 1]);   // today's alpha, in return units
```

`withDefaults()` is 200 rounds at learning rate 0.1; each round fits the SSE-optimal stump across all features, so the model captures thresholds and simple interactions but nothing a thousand-tree forest would -- which at daily-alpha signal-to-noise is a feature, not a limitation. The chronological split above is the minimum honesty bar, not the full one: before trading a prediction like `signal`, wrap it in an `AlphaFactor` and run it through the `alpha` package's IC evaluation and overfitting defenses (cookbook recipe 3), and if you cross-validate, use `PurgedKFold` -- ordinary K-fold leaks forward-return labels across fold boundaries.

## 38. Forecast volatility with ML and GARCH side by side

Fit `Garch11` and the gradient-boosted `VolatilityForecaster` on the same returns and compare their five-day-ahead vol forecasts -- disagreement between them is itself information.

```java
double[] returns = CsvBarLoader.load(Path.of("spx.csv"), "SPX").returns();

// Econometric: GARCH(1,1) by maximum likelihood.
Garch11.Params garch = Garch11.fit(returns);
double garchVol = Math.sqrt(Garch11.forecastVariance(returns, garch, 5));

// ML: boosted trees over multi-horizon realized vol, momentum and shock features.
VolatilityForecaster ml = VolatilityForecaster.weekly().fit(returns);  // horizon 5
double mlVol = ml.forecast(returns);

System.out.printf("GARCH: alpha %.3f beta %.3f persistence %.3f uncond vol %.2f%%%n",
        garch.alpha(), garch.beta(), garch.persistence(),
        Math.sqrt(garch.unconditionalVariance()) * 100);
System.out.printf("daily vol forecast: GARCH %.2f%% vs ML %.2f%%%n",
        garchVol * 100, mlVol * 100);
System.out.printf("ML risk score: %.0f/100 (percentile vs history)%n",
        ml.riskScore(returns));
```

Both numbers are per-period (daily) volatilities, but they answer subtly different questions: `forecastVariance(returns, params, 5)` is the model's variance for day T+5 specifically, mean-reverting from tomorrow's conditional variance toward the unconditional level at the persistence rate, while the ML forecaster predicts the realized standard deviation over the whole next five days. GARCH is interpretable and hard to overfit -- three parameters, decades of literature; the tree model can exploit asymmetries and shock features GARCH cannot see, at the price of needing `riskScore`'s percentile framing to make its raw output legible. When they diverge sharply, believe neither blindly: recent data contains something (a regime change, recipe 39) that at least one model is extrapolating badly.

## 39. Detect volatility regimes with an HMM

`RegimeDetector` fits a two-state Gaussian hidden Markov model by Baum-Welch EM and answers the questions a vol-targeting overlay actually asks: which regime is today, how sure, and how long do regimes last.

```java
double[] returns = CsvBarLoader.load(Path.of("spx.csv"), "SPX").returns();

RegimeDetector.RegimeModel model = RegimeDetector.fit(returns, 200);

// State 1 is always the high-volatility regime, by construction.
System.out.printf("calm vol %.1f%%, turbulent vol %.1f%% (annualized)%n",
        model.stdDevs()[0] * Math.sqrt(252) * 100,
        model.stdDevs()[1] * Math.sqrt(252) * 100);
System.out.printf("today: regime %d, P(turbulent) %.2f%n",
        model.currentRegime(), model.currentProbabilities()[1]);
System.out.printf("expected persistence: calm %.0f days, turbulent %.0f days%n",
        model.expectedDuration(0), model.expectedDuration(1));

// Full-sample smoothed P(high vol at t) -- for charts and historical analysis.
double[] pTurbulent = model.smoothedHighVolProbability();
```

Two arrays in the result serve different masters and must not be swapped: `currentProbabilities` is the filtered estimate using data up to today -- the only one a live decision may use -- while `smoothedHighVolProbability` conditions on the entire sample, including the future, and is therefore for hindcasting and plots only (a backtest that de-levers on the smoothed probability is quietly clairvoyant). `expectedDuration(state)` is `1 / (1 - pStay)` from the fitted transition matrix, the honest answer to "if I cut risk now, for roughly how long"; requiring at least 100 returns and 200 EM iterations is cheap insurance for a stable fit.

## 40. Flag anomalous market behavior for ops

`AnomalyDetector` screens interval-aggregated activity for the two classic surveillance patterns -- quote stuffing (message storms with no trading) and price spikes -- with plain z-score statistics an ops desk can defend.

```java
// Per-interval aggregates from your feed handler (e.g. one-second buckets).
long[] messages = {410, 395, 402, 388, 415, 391, 404,     // orders+cancels+replaces
                   399, 9_874, 401, 397, 407};
long[] trades = {21, 19, 24, 18, 22, 20, 23, 19, 3, 22, 20, 21};  // same intervals
double[] mids = {100.02, 100.03, 100.01, 100.04, 100.02, 100.05, 100.03,
                 100.02, 100.04, 99.38, 99.40, 99.39, 99.41};

// Message rate a 3-sigma outlier AND order-to-trade ratio >= 50.
List<AnomalyDetector.Anomaly> stuffing =
        AnomalyDetector.detectQuoteStuffing(messages, trades, 3.0, 50.0);

// Interval return a 3-sigma outlier vs its own recent distribution.
List<AnomalyDetector.Anomaly> spikes =
        AnomalyDetector.detectPriceSpikes(mids, 3.0);

for (AnomalyDetector.Anomaly a : stuffing) {
    System.out.printf("interval %d: %s z=%.1f%n", a.intervalIndex(), a.type(), a.score());
}
spikes.forEach(a -> System.out.printf("interval %d: %s z=%.1f%n",
        a.intervalIndex(), a.type(), a.score()));
```

The quote-stuffing test deliberately requires both conditions at once: a message-rate spike alone is what a busy open looks like, and a high order-to-trade ratio alone is what a quiet market-maker looks like -- it is the combination (lots of quoting, almost no trading, far above baseline) that characterizes the pattern. The scores are ROBUST z-scores -- (x - median) / (1.4826 * MAD) -- precisely so the anomaly cannot hide inside its own baseline: a mean/stdev z is capped at (n-1)/sqrt(n) because the outlier inflates the very stdev it is measured against (n=7 caps at 2.27 -- a 3-sigma threshold could never fire), while median/MAD ignores up to half the window being contaminated and lets a genuine storm score in the hundreds. Feed rolling windows sized to a regime you consider comparable (a session hour, a day). Treat the returned `score` as triage priority for a human, not as a verdict -- z-scores flag, people conclude.

## 41. Predict market impact before trading

`MarketImpactPredictor` learns realized impact from your own fills' order and book features, then prices the next order's expected damage -- plus the probability it sweeps the visible touch.

```java
// Training set from historical parent orders: features at arrival -> realized bps.
double[][] x = {
        MarketImpactPredictor.features(0.010, 2.1, 0.15, 0.008),
        MarketImpactPredictor.features(0.045, 3.8, 0.60, 0.021),
        MarketImpactPredictor.features(0.002, 1.5, -0.20, 0.006),
        MarketImpactPredictor.features(0.080, 5.2, 0.75, 0.030),
        // ... hundreds more rows: sizeVsAdv, spreadBps, bookImbalance, volatility
};
double[] realizedBps = {3.1, 11.4, 0.8, 22.7 /* ... */};

MarketImpactPredictor predictor = new MarketImpactPredictor().fit(x, realizedBps);

// The contemplated order: 2% of ADV, 3.5 bps spread, book leaning against us.
double expectedBps = predictor.predictImpactBps(
        MarketImpactPredictor.features(0.02, 3.5, 0.40, 0.012));

// Will 50k marketable shares blow through 12k displayed at the touch?
double pSweep = MarketImpactPredictor.sweepProbability(50_000, 12_000);
System.out.printf("expected impact %.1f bps, sweep probability %.2f%n",
        expectedBps, pSweep);
```

The four standard features are deliberately normalized quantities -- size as a fraction of ADV, spread in bps, signed book imbalance in [-1, 1], per-period volatility -- so a model trained on one symbol's fills transfers plausibly to comparable names. `sweepProbability` is a model-free companion: logistic in the size-to-depth ratio, exactly 0.5 when the order equals the visible contra depth, 1.0 when no depth is displayed. Compare the learned prediction against the square-root-law cost in `TradeCostModel` and against `KylesLambda`'s live tape estimate; when the ML number diverges from both, either your fills contain real edge the models lack, or your training set is stale -- retrain on a rolling window before believing it.

## 42. Run a 100k-path Monte Carlo portfolio simulation

`MonteCarloSimulator` runs a hundred thousand GBM scenarios in parallel across all cores -- deterministic for a given seed regardless of thread scheduling -- and `SimulationResult` turns the terminal values into the numbers a client meeting needs.

```java
MonteCarloSimulator sim = new MonteCarloSimulator(42);   // seed -> reproducible

// One-year horizon: $1M at 7% expected return, 16% annualized vol.
SimulationResult r = sim.simulate(1_000_000, 0.07, 0.16, 252, 100_000);

double[] ci90 = r.confidenceInterval(0.90);              // [p5, p95]
System.out.printf("median %,.0f  mean %,.0f  P(profit) %.1f%%%n",
        r.medianValue(), r.expectedValue(), r.probabilityOfProfit() * 100);
System.out.printf("90%% CI [%,.0f .. %,.0f]  VaR95 %.1f%%  CVaR95 %.1f%%%n",
        ci90[0], ci90[1],
        r.valueAtRisk(0.95) * 100, r.conditionalValueAtRisk(0.95) * 100);
System.out.printf("extremes: worst %,.0f, best %,.0f over %d paths%n",
        r.worstCase(), r.bestCase(), r.simulations());

// Correlated multi-asset version: daily means and covariance, e.g. 60/40.
SimulationResult multi = sim.simulatePortfolio(1_000_000,
        new double[]{0.6, 0.4},
        new double[]{0.00035, 0.00012},
        new double[][]{{1.0e-4, 1.2e-5}, {1.2e-5, 2.5e-5}},
        252, 100_000);
System.out.println(multi);   // one-line summary with VaR/CVaR baked in
```

Determinism under parallelism is the engineering point: each path derives its own `SplittableRandom` from the seed and path index, so path 71,342 produces identical draws whether it ran on core 0 or core 15, and yesterday's client numbers reproduce exactly today. On the finance side, remember what GBM assumes away -- fat tails, vol clustering, correlation spikes in crises -- so read `valueAtRisk` and `conditionalValueAtRisk` as lower bounds on how bad things get; the multi-asset variant at least lets you stress the covariance matrix toward crisis correlations and watch the tail respond. Feed the result to `ReportGenerator.addMonteCarlo(sim)` for the client-ready table (recipe 43).

## 43. Generate a client-ready report in four formats with charts

Build one format-agnostic `Report` of titled sections -- key-value blocks, tables, inline SVG charts -- and export the same model to HTML, PDF, XLSX and CSV.

```java
double[] equity = CsvBarLoader.load(Path.of("fund_nav.csv"), "FUND").closes();

Map<String, String> headline = new LinkedHashMap<>();    // insertion order kept
headline.put("AUM", "$412M");
headline.put("YTD return", "+8.4%");
headline.put("Sharpe (1y)", "1.31");

Report report = Report.builder("Q3 Client Review")
        .addKeyValueSection("Headline numbers", headline)
        .addTableSection("Top positions",
                List.of("Symbol", "Weight", "P&L"),
                List.of(List.of("AAPL", "6.2%", "+$3.1M"),
                        List.of("XOM", "4.8%", "-$0.4M")))
        .addHtmlSection("Equity curve", SvgCharts.equityChart(equity))
        .addHtmlSection("Drawdown", SvgCharts.drawdownChart(equity))
        .build();

new HtmlReportExporter().export(report, Path.of("review.html"));
new PdfReportExporter().export(report, Path.of("review.pdf"));
new XlsxReportExporter().export(report, Path.of("review.xlsx"));
new CsvReportExporter().export(report, Path.of("review.csv"));
```

The split between model and exporters is what keeps four formats honest with one another: every exporter renders the same ordered sections, so the PDF can never quietly disagree with the spreadsheet. HTML sections (the SVG charts) render in the HTML export only -- tabular exporters skip them by contract rather than mangling markup into cells -- and `SvgCharts.lineChart(values, width, height, strokeColor)` covers custom plots beyond the equity/drawdown pair. For the common backtest tear sheet, `ReportGenerator` is the convenience wrapper over this same machinery: `addStrategyPerformance(result)`, `addTradeHistory`, `addMonteCarlo`, then `toHtml`/`toPdf`/`toExcel`/`toCsv` (cookbook recipe 1).

## 44. Checkpoint a trading day and restore it tomorrow

Everything the microstructure models learn -- volume profiles, spread baselines, alpha weights -- is exactly what a desk does not want to relearn from zero every morning; `Checkpoint` persists it as one atomic binary file of named sections.

```java
// During the day the models learn from the live feed.
VolumeCurve volume = new VolumeCurve(78, 0.1);
SpreadForecaster spread = new SpreadForecaster();
// ... volume.onVolume(bucket, qty); spread.onSpread(bucket, s, tsNanos); ...

// End of day: fold today into the profiles, then commit atomically.
volume.rollDay();
spread.rollDay();
try (Checkpoint.Writer w = Checkpoint.writer(Path.of("state/eod.qflc"))) {
    w.section("volume.AAPL", volume::writeState)
     .section("spread.AAPL", spread::writeState);
}   // close() writes a temp file, then atomically renames over the old one

// Next session start: same configuration, warm state.
VolumeCurve volume2 = new VolumeCurve(78, 0.1);
SpreadForecaster spread2 = new SpreadForecaster();
Checkpoint.Reader r = Checkpoint.reader(Path.of("state/eod.qflc"));
boolean warm = r.section("volume.AAPL", volume2::readState)
             & r.section("spread.AAPL", spread2::readState);
System.out.println(warm ? volume2.daysLearned() + " days of profile restored"
                        : "cold start");
```

The failure modes are all designed to be loud. A crash mid-save leaves yesterday's file intact (temp file plus atomic rename, never a torn checkpoint); a section writer that throws poisons the whole writer so nothing half-written ever commits; restoring into a differently-configured instance (other bucket count) throws instead of silently misaligning arrays; and a model that does not consume its payload exactly is rejected as a writer/reader format drift. Absent sections just return `false` -- the caller decides whether that is a cold start or an error. Every model with a `writeState`/`readState` pair checkpoints this way: `VolumeCurve`, `VolatilityCurve`, `SpreadForecaster`, `OnlineAlphaLearner`, `KylesLambda`, `VenueScorecard`, `LpScorecard` and friends -- one section each, named `model.symbol`.

## 45. Measure your own latency honestly

`LatencyRecorder` is a zero-allocation nanosecond histogram safe to call on the hot path, and `HiccupMonitor` runs beside it to tell you whether a bad tail was your code or the platform.

```java
LatencyRecorder recorder = new LatencyRecorder();

try (HiccupMonitor hiccups = new HiccupMonitor().start()) {   // 1ms resolution
    for (int i = 0; i < 1_000_000; i++) {
        long t0 = System.nanoTime();
        // ... the operation under test: quote, book update, risk check ...
        recorder.record(System.nanoTime() - t0);
    }

    // p50/p90/p99/p99.9/max in one line, units auto-scaled.
    System.out.println("operation: " + recorder.summary());
    System.out.println(hiccups.summary());

    // Attribution: if the platform stalled longer than your worst sample,
    // the tail belongs to GC/safepoints/scheduler, not to your code.
    if (hiccups.maxHiccupNanos() > recorder.percentile(0.999)) {
        System.out.println("p99.9 is platform noise -- tune the JVM, not the code");
    }
}
```

`record` is a couple of array writes into HdrHistogram-style log-linear buckets (16 sub-buckets per power of two, ~6% worst-case quantile error) -- cheap enough to leave enabled in production hot paths; it is single-writer by design, so give each thread its own recorder. The hiccup monitor is the honesty half: a daemon thread repeatedly parks for a fixed interval and records how much longer than requested each park took, which surfaces exactly the GC pauses, safepoint stalls and scheduler preemptions that corrupt latency percentiles without appearing in application code. Publishing a p99.9 without a matching hiccup profile is publishing a number you cannot defend; `docs/ULTRA_LOW_LATENCY.md` covers the JVM and kernel tuning that shrinks what the monitor observes.

## 46. Drive everything from the CLI

The `cli.Main` entry point runs backtests, walk-forward validation and market reports on CSV bars with zero Java written -- the fastest path from a data file to a tear sheet.

```bash
# Backtest: strategies sma|ema|rsi|macd|bollinger, HTML tear sheet optional.
java -jar quant-finance-library-*.jar backtest \
     --csv bars.csv --symbol EURUSD \
     --strategy bollinger --period 20 --k 2.0 \
     --capital 250000 --out bt.html

# RSI variant has its own knobs:
java -jar quant-finance-library-*.jar backtest --csv bars.csv --symbol EURUSD \
     --strategy rsi --period 14 --oversold 30 --overbought 70

# Walk-forward: re-optimize an SMA grid per train window, score unseen bars only.
java -jar quant-finance-library-*.jar walkforward --csv bars.csv --symbol EURUSD \
     --train 252 --test 63 --fast 5,10,20 --slow 40,60 --out wf.html

# Market snapshot report: technicals, price chart, drawdown.
java -jar quant-finance-library-*.jar report --csv bars.csv --symbol EURUSD \
     --out market.html
```

The moving-average strategies take `--fast`/`--slow`, bollinger takes `--period`/`--k`, and every command accepts the tolerant CSV dialects of recipe 28 (`--csv` and `--symbol` are the only required options; `help` prints the full usage). The walk-forward command prints each fold's chosen parameters with in-sample versus out-of-sample objective, then the stitched out-of-sample metrics and the walk-forward efficiency -- the anti-overfitting number from recipe 20, computed without writing a line of code. For CI pipelines and tests, `Main.run(String[])` is the testable entry: it returns 0 for success, 1 for usage errors and 2 for execution failures instead of calling `System.exit`, so a nightly job can assert on the exit code and archive the HTML artifacts.

---

## 47. Decode an ITCH feed into a full-depth L3 book

`ItchCodec` speaks the Nasdaq TotalView-ITCH 5.0 layout (add / execute /
cancel / delete / replace / trade) as a zero-allocation flyweight;
`L3BookBuilder` consumes the stream and maintains the venue's book on your
side of the wire. Prices are 0.0001 ticks, so 1_500_000 = $150.00.

```java
int locate = 42;                                    // the feed's symbol id for the day
long apple = ItchCodec.packStock("AAPL");           // 8 ASCII bytes in a long
long ts = 34_200_000_000_000L;                      // 09:30 in nanos since midnight
byte[] wire = new byte[4096];                       // simulator side: encode a session
int w = 0;
w += ItchCodec.encodeAdd(wire, w, locate, ts, 1001L, ItchCodec.BUY, 500, apple, 1_499_900);
w += ItchCodec.encodeAdd(wire, w, locate, ts, 1002L, ItchCodec.SELL, 300, apple, 1_500_100);
w += ItchCodec.encodeExecuted(wire, w, locate, ts, 1002L, 100, 9001L);
w += ItchCodec.encodeReplace(wire, w, locate, ts, 1001L, 1003L, 500, 1_500_000);

// Participant side: one call per message, zero allocation per event.
L3BookBuilder book = new L3BookBuilder(locate, 1_400_000, 1_600_000, 1 << 16);
for (int p = 0; p < w; ) {
    int consumed = book.onMessage(wire, p);
    p += consumed > 0 ? consumed : ItchCodec.length(wire[p]);   // 0 = other symbol: skip
}
System.out.printf("bid %d x %d | ask %d x %d, resting=%d%n",
        book.bestBidTick(), book.bestBidSize(),
        book.bestAskTick(), book.bestAskSize(), book.restingOrders());
```

The builder is the same engineering as `HftOrderBook` (dense tick ladder,
occupancy bitmaps, pooled nodes) but driven by the feed instead of by
matching. Watch `unknownRefCount()` -- a nonzero rate is the classic feed-gap
symptom (resubscribe or reload a snapshot) -- and `outOfBandCount()`, which
means your price band is too narrow for the day's range.

## 48. Track your own order's exact queue position

Once your order's add appears on the L3 feed (you learn its reference from
your gateway's ack), `track` tells you exactly how many shares stand ahead of
you -- the number that decides whether a passive child fills.

```java
L3BookBuilder book = new L3BookBuilder(1, 990_000, 1_010_000, 1 << 12);
book.onAdd(501L, Side.BUY, 400, 1_000_000);      // ahead of us
book.onAdd(502L, Side.BUY, 250, 1_000_000);      // ahead of us
book.onAdd(777L, Side.BUY, 100, 1_000_000);      // OUR order (ref from the ack)
book.onAdd(503L, Side.BUY, 900, 1_000_000);      // behind us

book.track(777L);                                // one FIFO walk, then O(1) forever
System.out.println(book.sharesAhead(777L));      // 650

book.onExecute(501L, 400);                       // executions consume the head: 250
book.onCancel(503L, 900);                        // entered after us: still 250
book.onCancel(502L, 250);                        // entered before us: 0 -- we are next
System.out.println(book.sharesAhead(777L));      // 0
book.onExecute(777L, 100);                       // our fill: tracking ends itself
System.out.println(book.sharesAhead(777L));      // -1 (no longer tracked)
```

The O(1) maintenance rests on two facts of price-time priority: an execution
at your level always happened ahead of you, and a cancel was ahead of you iff
it entered the queue before you (insertion sequence). This is the exact
position; when your feed is only L2, use the probabilistic
`microstructure.QueuePositionEstimator` instead.

## 49. Consolidate an NBBO across venues and detect locked/crossed markets

`Nbbo` aggregates per-venue tops of book into the three things a smart order
router consumes: the national best bid/ask, the size there, and a bitmask of
which venues are quoting it.

```java
Nbbo nbbo = new Nbbo(3);                          // venues 0..2 (up to 64)
nbbo.listener((bid, bidSz, ask, askSz, ts) ->     // fires ONLY when the inside changes
        System.out.printf("NBBO %d (%d) / %d (%d)%n", bid, bidSz, ask, askSz));

long t = System.nanoTime();
nbbo.onVenueQuote(0, 999_900, 500, 1_000_100, 400, t);    // venue 0
nbbo.onVenueQuote(1, 1_000_000, 200, 1_000_200, 300, t);  // venue 1: better bid
nbbo.onVenueQuote(2, 1_000_000, 100, 1_000_100, 250, t);  // venue 2 joins both insides

System.out.println(nbbo.bidTick() + " x " + nbbo.bidSize());   // 1000000 x 300
System.out.println(Long.toBinaryString(nbbo.bidVenues()));     // 110 = venues 1 and 2
if (nbbo.locked() || nbbo.crossed()) {
    // stand down marketable routing: the tape is in a locked/crossed condition
}
nbbo.onVenueDown(1, t);                           // feed loss / halt: quotes removed
```

Most consolidated-tape updates are off-inside flicker; the fast path skips
recomputation for them and the listener naturally conflates downstream logic
to inside-quote changes -- `changeCount()` over `updateCount()` is your
conflation ratio.

## 50. Ship market data as 48-byte binary flyweights

The `sbe` package is the ITCH/SBE-family wire discipline in miniature: fixed
offsets over a `ByteBuffer`, so encode and decode are a handful of absolute
primitive reads/writes -- no parse step, no objects, no copying.

```java
ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
QuoteFlyweight quote = new QuoteFlyweight();      // one instance, reused forever
int symbolId = 7;                                 // dense id shared by both ends

// Encoder side (market maker out): a 48-byte two-sided quote.
quote.wrap(buf, 0).encode(symbolId, 1.0849, 2_000_000, 1.0851, 1_000_000,
        System.nanoTime());

// Decoder side: dispatch on the type header, then read primitives in place.
if (QuoteFlyweight.typeAt(buf, 0) == QuoteFlyweight.MESSAGE_TYPE) {
    quote.wrap(buf, 0);
    double mid = (quote.bidPrice() + quote.askPrice()) / 2;   // 1.0850
}

// Trades are the 32-byte sibling with the same discipline.
TradeFlyweight trade = new TradeFlyweight();
trade.wrap(buf, QuoteFlyweight.BLOCK_LENGTH)
        .encode(symbolId, 1.0850, 250_000, System.nanoTime());
```

A one-sided quote carries NaN on the pulled side (the convention
`fx.AggregatedBook` consumes), and the layout is little-endian with the type
discriminator at offset 0 -- a receive loop reads `typeAt`, wraps the matching
flyweight, and never allocates. `BinaryOrderPublisher`/`BinaryOrderReceiver`
run the same idea over a real transport.

## 51. Parse FIX market data snapshots and increments

Most bank FX streams and many ECNs deliver prices as FIX 35=W (snapshot) and
35=X (incremental). `FixMarketDataView` is a garbage-free flyweight over an
already-framed message: one pass records primitives, every getter is
primitive.

```java
FixMarketDataView view = new FixMarketDataView();     // retains up to 32 entries
byte[] msg = ("35=X|55=EURUSD|268=2"
        + "|279=0|269=0|270=1.0849|271=1000000"       // NEW bid  1.0849 x 1M
        + "|279=0|269=1|270=1.0852|271=500000|")      // NEW offer 1.0852 x 0.5M
        .replace('|', (char) 1)                       // SOH field separators
        .getBytes(StandardCharsets.US_ASCII);

if (view.wrap(msg, 0, msg.length)                      // true only for 35=W / 35=X
        && view.symbolEquals("EURUSD".getBytes(StandardCharsets.US_ASCII))) {
    for (int e = 0; e < view.entryCount(); e++) {
        byte action = view.action(e);   // ACTION_NEW / CHANGE / DELETE (W: always NEW)
        byte side = view.type(e);       // ENTRY_BID or ENTRY_OFFER
        double px = view.price(e);      // or pxMantissa(e)/pxDecimals(e) to stay integer
        long size = view.size(e);
    }
}
```

Entry order within the message is preserved -- for tiered LP streams,
position IS the tier. The scaled-long price (mantissa times 10^-decimals) is
the same representation `FixOrderEncoder` takes, so a feed-to-order loop
never has to touch a double; just never apply one entry's decimals to
another's mantissa.

## 52. Wire the zero-allocation market-data bus end to end

`HftMarketDataBus` is the single-producer fast lane: ticks travel a
preallocated primitive ring, symbols are dense int ids (interned once by the
built-in `SymbolRegistry`), and dispatch is plain array indexing.

```java
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true)) {
    // ring capacity, max symbols, busy-spin consumer (latency over cores)
    int eurusd = bus.registerSymbol("EURUSD");    // dense id: resolve once, cold path
    int usdjpy = bus.registerSymbol("USDJPY");
    bus.subscribe(eurusd, (id, price, size, ts) -> {
        // strategy: runs on the consumer thread, primitives only
    });
    bus.subscribeAll((id, price, size, ts) -> { /* tape recorder */ });
    bus.start();

    for (int i = 0; i < 1_000_000; i++) {          // producer hot path
        while (!bus.publish(eurusd, 1.0850 + i * 1e-7, 1_000_000, System.nanoTime())) {
            Thread.onSpinWait();                   // ring full: your backpressure policy
        }
    }
    while (bus.processedCount() < 1_000_000) {
        Thread.onSpinWait();                       // wait for the consumer to drain
    }
    System.out.println(bus.latestPrice("EURUSD"));
}
```

`publish` returning false is backpressure, not loss -- the caller chooses
spin, drop, or shed. One dispatch-cost rule worth respecting: with three or
more distinct listener classes on a symbol the call site goes megamorphic
(~10-20 ns per listener); keep it to one or two and fan out inside your own
listener.

## 53. Stream live trades from a WebSocket venue

`WebSocketFeed` is the last mile: pure-JDK WebSocket client, a pluggable
`FeedParser` per exchange, and automatic reconnection with exponential
backoff. Everything downstream (capture, paper trading, analytics) runs on
real ticks.

```java
HftMarketDataBus bus = new HftMarketDataBus();
bus.subscribeAll((id, price, size, ts) ->
        System.out.printf("%s %.2f x %.6f%n", bus.symbol(id), price, size));
bus.start();

try (WebSocketFeed feed = new WebSocketFeed(
        BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT"),
        new BinanceTradeParser(), bus)) {
    feed.withReconnect(10, 500);      // up to 10 attempts, 500 ms doubling to 30 s
    feed.start();                     // blocks until the first connection
    Thread.sleep(30_000);
    System.out.printf("msgs=%d trades=%d reconnects=%d%n",
            feed.messagesReceived(), feed.tradesPublished(), feed.reconnectCount());
}
bus.stop();
```

The feed registers symbols on the bus as they first appear and publishes the
exchange's event time (millis widened to nanos), so a `TickCapture` recording
of this session replays with true market pacing. Exchanges that need a
subscribe handshake after connecting get it via `withSubscribeMessage`; a
healthy connection resets the backoff ladder.

## 54. Run a venue-grade matching session

`HftOrderBook` is the zero-allocation matching engine (204 ns/op, dense tick
ladder, pooled nodes), with the full order-type menu a venue offers and
rejections as return codes -- a matching loop must never unwind over a bad
order.

```java
HftOrderBook book = new HftOrderBook(90_000, 110_000, 1 << 20);  // tick band, pool
book.tradeSink((maker, taker, tick, qty, ts) ->
        System.out.printf("fill %d @ %d (maker %d, taker %d)%n", qty, tick, maker, taker));

long now = System.nanoTime();
long bid = book.submitLimit(Side.BUY, 99_995, 1_000, now);       // rests
long ask = book.submitLimit(Side.SELL, 100_005, 1_000, now);     // rests

long ioc = book.submitIoc(Side.BUY, 100_005, 400, now);          // fills 400, rest expires
long fok = book.submitFok(Side.BUY, 100_005, 5_000, now);        // 0: can't fill fully,
                                                                 //    emits NO trades
long post = book.submitPostOnly(Side.BUY, 100_005, 200, now);    // would take liquidity
if (post == HftOrderBook.REJECT_WOULD_CROSS) {
    post = book.submitPostOnly(Side.BUY, 100_000, 200, now);     // reprice passively
}
book.cancel(bid);
System.out.printf("resting=%d trades=%d%n", book.restingOrders(), book.tradeCount());
```

The reject codes (`REJECT_POOL_FULL`, `REJECT_OUT_OF_BAND`, `REJECT_INVALID`,
`REJECT_WOULD_CROSS`) are all negative; accepted ids are positive. The
readable `OrderBook` is this engine's executable specification -- an
equivalence test drives both with identical randomized streams and demands
identical books.

## 55. Read book analytics a market maker watches

`BookAnalytics` answers the standing questions over an `OrderBook`: how wide,
which way is the book leaning, and what would a sweep cost -- without
mutating anything.

```java
OrderBook book = new OrderBook("AAPL");
long now = System.nanoTime();
book.submitLimit(Side.BUY, 149.98, 800, now);
book.submitLimit(Side.BUY, 149.95, 1_500, now);
book.submitLimit(Side.SELL, 150.02, 300, now);
book.submitLimit(Side.SELL, 150.05, 1_200, now);

double spreadBps = BookAnalytics.spreadBps(book);              // quoted width
double micro = BookAnalytics.microprice(book);                 // leans to the big side
double imb = BookAnalytics.imbalance(book, 2);                 // [-1,1], + = bid-heavy
long near = BookAnalytics.depthWithinBps(book, Side.SELL, 5);  // sellable near mid

BookAnalytics.SweepResult sweep = BookAnalytics.sweep(book, Side.BUY, 1_000);
System.out.printf("spread %.1f bps, micro %.4f, imbalance %+.2f%n", spreadBps, micro, imb);
System.out.printf("1000-lot sweep: filled %d @ %.4f, impact %.1f bps over %d levels%n",
        sweep.filledQty(), sweep.avgPrice(), sweep.impactBps(), sweep.levelsConsumed());
```

The microprice (size-weighted between bid and ask) is a better short-horizon
fair value than the mid when the book is imbalanced, and `sweep` prices a
contemplated marketable order against the actual resting ladder -- the honest
pre-trade impact number, not a model.

## 56. Uncross an opening auction

`Auction` implements the standard exchange rulebook: orders accumulate
without trading, then one clearing price executes the maximum matchable
volume -- the mechanism behind every open, close, and volatility-halt
reopening.

```java
Auction open = new Auction()
        .addBuy(100.10, 500).addBuy(100.00, 800).addMarketBuy(300)
        .addSell(99.90, 600).addSell(100.05, 700).addMarketSell(200);

// During the call phase: the indicative triple venues disseminate.
Auction.Result indicative = open.indicative(100.00);   // reference = last trade
System.out.printf("indicative %.2f x %d, imbalance %+d%n",
        indicative.price(), indicative.volume(), indicative.imbalance());
// -> 100.05 x 800, imbalance -700 (sell surplus)

Auction.Result print = open.uncross(100.00);           // the opening print
if (!print.hasBuySurplus()) {
    // leftover sells carry into continuous trading
}
```

Price discovery follows the hierarchy real rulebooks use: maximum executable
volume first, then minimum leftover surplus among volume ties, then proximity
to the reference price. Market-on-auction orders are eligible at any clearing
price, which is exactly why they are the imbalance you see published before
the bell.

## 57. Gate every order in ~3ns

`HftRiskGate` is the pre-trade check on the fast lane: all state in primitive
arrays by dense symbol id, `check` returns an int reason code, and the
cross-thread reads use VarHandle acquire/release so they cost nothing on x86.

```java
HftRiskGate gate = new HftRiskGate(64)             // symbol ids 0..63
        .maxOrderQuantity(1_000_000)
        .maxOrderNotional(25_000_000)
        .maxPositionQuantity(1_000)
        .priceCollarPct(0.02);                     // 2% fat-finger collar
gate.setReferencePrice(7, 150.00);                 // from the market-data thread

int code = gate.check(7, Side.BUY, 500, 150.01);   // OK (0)
gate.onFill(7, Side.BUY, 800);                     // venue-ack thread; atomic add
gate.onFill(7, Side.BUY, 800);                     // position now 1600: over the cap
gate.check(7, Side.BUY, 100, 150.00);              // REJECT_POSITION
gate.check(7, Side.SELL, 500, 150.00);             // OK -- risk-REDUCING orders pass
gate.check(7, Side.SELL, 3_100, 150.00);           // REJECT_POSITION: flips to -1500
gate.halt(7, true);                                // ops lever, callable anywhere
System.out.println(HftRiskGate.reasonName(
        gate.check(7, Side.BUY, 1, 150.00)));      // HALTED
gate.kill(true);                                   // gate-wide: everything rejects
```

The risk-reducing rule is the subtle one: from an over-cap +1600, selling 500
passes (smaller position, same sign) because rejecting the trade that
de-risks the book wedges it at its worst point -- but selling 3100 is a
brand-new over-cap short wearing a hedge's clothes, and is rejected.
`rejectionCount(code)` keeps per-reason tallies.

## 58. Throttle order flow with a token bucket

Every real venue enforces a message-rate limit; exceeding it earns
disconnects or fines, so the gateway must self-limit. `OrderThrottle` is a
nanosecond token bucket: sustained rate plus a bounded burst.

```java
OrderThrottle throttle = new OrderThrottle(100, 20);   // 100 msg/s sustained, burst 20

long now = System.nanoTime();
for (int i = 0; i < 25; i++) {
    if (throttle.tryAcquire(now)) {
        // send the order
    } else {
        long wait = throttle.nanosUntilAvailable(now); // pace: park instead of spin-fail
    }
}
// 20 granted (the opening burst), 5 throttled; a permit returns every 10 ms.
System.out.printf("sent=%d throttled=%d%n",
        throttle.acquiredCount(), throttle.throttledCount());
```

The caller passes `System.nanoTime()` rather than the throttle reading a
clock -- tests are deterministic and the hot path controls its own syscalls.
A persistently nonzero `throttledCount()` is a strategy design problem, not a
tuning problem: the strategy is generating more messages than the venue will
ever accept.

## 59. Quote two-sided markets with inventory skew

`HftQuoter` is the market-making loop on the fast lane: tick in, inventory
skew, tick-grid snap, two orders out through the risk gate -- zero allocation
per tick, and per-symbol configuration because a EURUSD half-spread is ~100x
too tight for USDJPY.

```java
HftRiskGate gate = new HftRiskGate(16).maxPositionQuantity(5_000_000);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> { /* venue adapter */ });

    HftQuoter quoter = new HftQuoter(gateway, 16,
            HftQuoter.Config.of(1_000_000, 0.00010)   // default: 1M per side, 1 pip half
                    .withSkewPerUnit(2e-11)           // long inventory shades quotes down
                    .withMinMove(0.00005));           // move-gated conflation
    int eurusd = bus.registerSymbol("EURUSD");
    int usdjpy = bus.registerSymbol("USDJPY");
    quoter.configureSymbol(usdjpy,                    // JPY pairs live on another scale
            HftQuoter.Config.of(1_000_000, 0.010).withMinMove(0.005));

    bus.subscribe(eurusd, quoter);
    bus.subscribe(usdjpy, quoter);
    gateway.start();
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    // -> BUY 1M @ 1.0849 and SELL 1M @ 1.0851 through the gate
}
```

The skew loop closes without extra state: fills update the gate's position
(`onFill`), the next tick reads it and shades both sides toward flattening.
Both quote sides still pass the gate's own checks -- a quoter cannot
out-trade its risk limits -- and `suppressedUpdates()` shows the conflation
saving you wire.

## 60. Auto-hedge a drifting inventory

`AutoHedger` is the streaming counterpart of the batch `hedging.DeltaHedger`:
it watches the risk gate's live position on every tick and fires a flattening
order the moment the band is breached -- back TO the band, not to flat.

```java
HftRiskGate gate = new HftRiskGate(16);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> { /* venue adapter */ });

    AutoHedger hedger = new AutoHedger(gateway, 16,
            2_000_000,          // tolerate |position| up to 2M without hedging
            50_000_000L);       // 50 ms cooldown while a hedge fill is in flight
    int eurusd = bus.registerSymbol("EURUSD");
    bus.subscribe(eurusd, hedger);   // AFTER the quoter/strategy listeners
    gateway.start();
    bus.start();

    gate.onFill(eurusd, Side.BUY, 3_500_000);   // one-sided flow builds inventory
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    // -> hedger submits SELL 1_500_000: the excess over the band, nothing more
    System.out.printf("hedges=%d rejected=%d%n",
            hedger.hedgesSubmitted(), hedger.hedgesRejected());
}
```

Hedging to the band edge removes the breach with the smallest order and
avoids ping-ponging around zero -- how dealer books actually run inventory.
The cooldown matters because positions only move when fills confirm via
`onFill`; without it the hedger would stack orders while the first hedge is
still in flight. Monitor `hedgesRejected()` -- a hedger the gate refuses is
an emergency.

## 61. Trade on paper with real risk checks

`PaperTradingGateway` closes the research-to-production loop: the same
strategy code and the same `PreTradeLimitChecker` as production, against
simulated fills with full account tracking.

```java
PreTradeLimitChecker limits = new PreTradeLimitChecker()
        .maxOrderQuantity(10_000).maxPositionQuantity(20_000).priceCollarPct(0.03);
PaperTradingGateway paper = new PaperTradingGateway(1_000_000, 0.0005, limits);
paper.addExecutionListener((id, symbol, side, price, qty, ts) ->
        System.out.printf("fill %s %s %d @ %.2f%n", symbol, side, qty, price));

paper.onQuote("AAPL", 149.99, 150.01);                     // top of book in
paper.submitMarket("AAPL", Side.BUY, 1_000);               // fills at the ask
paper.submitLimit("AAPL", Side.SELL, 1_000, 151.00);       // rests
paper.onQuote("AAPL", 151.05, 151.07);                     // market crosses: it fills
long fat = paper.submitLimit("AAPL", Side.BUY, 1_000, 200.00);
System.out.println(paper.status(fat));                     // REJECTED (price collar)
System.out.println(paper.rejectionLog());                  // the venue's reasons

PaperTradingGateway.AccountSnapshot account = paper.snapshot();
System.out.printf("cash %.2f equity %.2f realized %.2f rejections %d %s%n",
        account.cash(), account.equity(), account.realizedPnl(),
        account.rejectionCount(), account.positions());
```

Because it implements `OrderGateway`, swapping in a broker adapter later
changes zero strategy lines. `snapshot()` returns one internally consistent
view under a single lock acquisition -- safe for a dashboard thread to poll
while another thread trades (recipe 64 does exactly that).

## 62. Shard the engine across symbols and measure the scaling

`ShardedTradingEngine` is horizontal scaling as shipped machinery: N
independent bus -> gate -> gateway stacks behind one symbol-routing facade.
Shared-nothing, so aggregate capacity is per-shard throughput times shard
count.

```java
try (ShardedTradingEngine engine = new ShardedTradingEngine(
        4, 1 << 14, 1 << 13, 64, true,                 // 4 shards, busy-spin
        shard -> new HftRiskGate(64).maxOrderQuantity(5_000_000))) {

    int eurusd = engine.registerSymbol("EURUSD", 0);
    int usdjpy = engine.registerSymbol("USDJPY", 1);
    int eurjpy = engine.registerSymbol("EURJPY", 0, 1); // cross lives where BOTH legs tick

    engine.bus(0).subscribe(engine.localId(eurusd, 0),
            (id, px, size, ts) -> { /* shard-0 strategy, as on a standalone bus */ });
    engine.start();

    long t0 = System.nanoTime();
    for (int i = 0; i < 4_000_000; i++) {              // producer: routes by handle
        int handle = (i & 1) == 0 ? eurusd : usdjpy;
        while (!engine.publish(handle, 1.0850, 1_000_000, System.nanoTime())) {
            Thread.onSpinWait();
        }
    }
    while (engine.processedCount() < 4_000_000) {
        Thread.onSpinWait();
    }
    System.out.printf("%.1fM ticks/s across %d shards%n",
            4_000_000 / ((System.nanoTime() - t0) / 1e9) / 1e6, engine.shardCount());
}
```

Multi-shard registration is the cross co-location tool: duplicating a leg's
feed into a second shard costs one extra ring publish (~40 ns), which is the
whole point of shared-nothing. What sharding deliberately does NOT solve is
firm-wide risk -- each shard's gate sees only its own symbols. That is the
next recipe.

## 63. Pull the firm-wide kill switch on breach

`GlobalRiskAggregator` is the someone who can see every shard: a monitor
thread sweeps all gates' positions and reference prices (already published
with acquire/release semantics, so aggregation is free for the hot paths) and
pulls `HftRiskGate.kill` on every gate when gross notional breaches the cap.

```java
HftRiskGate shardA = new HftRiskGate(64);
HftRiskGate shardB = new HftRiskGate(64);
try (GlobalRiskAggregator firmRisk = new GlobalRiskAggregator(
        List.of(shardA, shardB),   // or engine.gates() from recipe 62
        100_000_000.0,             // firm-wide cap on sum |position| x reference
        0.9,                       // hysteresis: resume only below 90M
        1_000_000)) {              // 1 ms poll = detection latency

    shardA.setReferencePrice(0, 1.0850);          // unpriced symbols contribute zero
    shardA.onFill(0, Side.BUY, 120_000_000);      // 130.2M gross: breach

    Thread.sleep(10);                             // > one poll interval
    System.out.println(firmRisk.isTripped());     // true -- every gate killed
    System.out.println(shardA.check(0, Side.BUY, 1, 1.0850)
            == HftRiskGate.REJECT_KILLED);        // true: shard checks all reject

    shardA.onFill(0, Side.SELL, 60_000_000);      // de-risk to 65.1M, below resume
    Thread.sleep(10);
    System.out.println(firmRisk.isTripped());     // false -- trading resumed
}
```

The layering is deliberate: per-order checks stay per-shard and
nanosecond-cheap, while the firm-wide cap is a circuit breaker with
poll-interval detection -- how real risk stacks are built. The 0.9 resume
fraction stops the firm from flapping around the limit;
`tripCount()`/`lastGrossNotional()` feed your monitoring.

## 64. Watch it all live in a browser

`TradingDashboard` is a zero-dependency live view (JDK `com.sun.net.httpserver`):
a self-refreshing HTML page plus a `/api/status` JSON endpoint with the paper
account and any latency histograms you attach.

```java
PaperTradingGateway paper = new PaperTradingGateway(1_000_000);
LatencyRecorder tickToOrder = new LatencyRecorder();

try (TradingDashboard dashboard = new TradingDashboard(paper, 8080)) {
    dashboard.attachLatency("tick-to-order", tickToOrder);
    dashboard.start();
    System.out.println("open http://localhost:" + dashboard.port());

    // The live loop: quotes in, orders out, latency recorded on the hot path.
    paper.onQuote("BTCUSDT", 64_990, 65_010);
    long t0 = System.nanoTime();
    paper.submitMarket("BTCUSDT", Side.BUY, 2);
    tickToOrder.record(System.nanoTime() - t0);

    // Browser self-refreshes every second: equity, cash, realized P&L,
    // positions, rejections, and count/p50/p99/max per latency path.
    // Machine consumers poll GET /api/status for the same JSON payload.
}
```

Pass port 0 to bind an ephemeral port (read it back with `port()`), and
attach one `LatencyRecorder` per measured path -- `record` is a couple of
array writes, safe on the hot path. Every number on the page comes from
`PaperTradingGateway.snapshot()`, so the dashboard thread never sees a
half-updated account. This is the observable end of recipe 6's five-minute
demo.

---

## 65. Schedule a TWAP and a VWAP side by side

The two precomputed schedules, same parent, same window: TWAP spreads evenly
over time, VWAP follows the expected volume curve. Both slice with
largest-remainder allocation, so quantities always sum exactly to the parent.

```java
long parent = 100_000;
long window = 6 * 60 * 60 * 1_000L;                     // a 6.5h day, say 6h here

// TWAP: 26 equal slices, then a jittered variant that is harder to game.
List<Slice> twap = TwapScheduler.schedule(parent, window, 26);
List<Slice> twapJittered =
        TwapScheduler.scheduleRandomized(parent, window, 26, 0.3, 42L);

// VWAP: weight the same 26 buckets by expected volume (U-shape or a
// learned profile -- any positive scale works, only proportions matter).
double[] profile = new double[26];
for (int i = 0; i < 26; i++) {
    double x = i / 25.0;
    profile[i] = 1 + 3 * (x - 0.5) * (x - 0.5);         // heavier open/close
}
List<Slice> vwap = VwapScheduler.schedule(parent, profile, window);

for (int i = 0; i < 5; i++) {
    System.out.printf("slice %d: twap %d @+%dms, vwap %d @+%dms%n", i,
            twap.get(i).quantity(), twap.get(i).offsetMillis(),
            vwap.get(i).quantity(), vwap.get(i).offsetMillis());
}
```

Both emit the same `Slice(offsetMillis, quantity)` records, so the executor
downstream does not care which schedule produced them. The jittered TWAP is
deterministic for a given seed -- replayable in tests, unpredictable to
anyone watching the tape. If the day's volume shape is uncertain, prefer the
live curve of recipe 68 over a stale precomputed profile.

## 66. Track POV against others' volume without chasing yourself

Percentage-of-volume cannot be prescheduled -- it chases realized volume. The
one bug every homegrown POV ships with: counting your own fills as market
volume, which makes the algo chase itself (realized participation becomes
p/(1+p) instead of p). `PovTracker` keeps the two ledgers separate.

```java
// 10% of other people's volume, children between 500 and 5,000 shares.
PovTracker pov = new PovTracker(100_000, 0.10, 500, 5_000);

// On every tape print: our fills go to onExecuted, everyone else's to
// onMarketVolume -- never both for the same print.
pov.onMarketVolume(8_000);                  // someone else traded 8,000
long due = pov.dueQuantity();               // 800: 10% of 8,000, >= minSlice
if (due > 0) {
    send(due);                              // your router/venue call
    pov.onExecuted(due);                    // report the fill back
}

pov.onMarketVolume(60_000);                 // a burst of market activity
System.out.println(pov.dueQuantity());      // catches up toward 10% of 68,000
System.out.printf("participation so far: %.3f (target 0.10), remaining %d%n",
        pov.realizedParticipation(), pov.remaining());
```

`dueQuantity()` returns 0 while within `minSlice` of schedule, so quiet tape
does not dribble odd lots, and it never exceeds `maxSlice`, so a volume burst
cannot make you the print everyone notices. Poll it on every print; act when
positive. For POV with alpha/spread/impact shaping on top, use
`BenchmarkExecutor.pov` (recipe 68) -- same ledger discipline, more inputs.

## 67. Front-load an urgent parent with implementation shortfall

An implementation-shortfall schedule front-loads: trade more now to cut
exposure to price drift, but not so fast that temporary impact dominates.
The scheduler turns the Almgren-Chriss optimal trajectory into the same
`Slice` list TWAP and VWAP emit.

```java
// One share-time-unit convention throughout: here the unit is a day.
var params = new AlmgrenChriss.Params(
        100_000,    // parent shares
        1.0,        // horizon: 1 day
        26,         // intervals
        0.50,       // sigma: price vol per sqrt(day), in price units
        1e-6,       // eta: temporary impact per unit trade rate
        5e-8,       // gamma: permanent impact per share
        1e-6);      // lambda: risk aversion (0 = TWAP)

List<Slice> is = ImplementationShortfallScheduler.schedule(params,
        6 * 60 * 60 * 1_000L);
System.out.printf("first slice %d vs TWAP slice %d%n",
        is.get(0).quantity(), 100_000 / 26);

// Traders think in front-load, not lambda: find the risk aversion whose
// FIRST slice is 30% of the parent, then schedule with it.
double lambda30 = ImplementationShortfallScheduler
        .riskAversionForFrontLoad(params, 0.30);
List<Slice> urgent = ImplementationShortfallScheduler.schedule(
        params.withRiskAversion(lambda30), 6 * 60 * 60 * 1_000L);
```

`riskAversionForFrontLoad` bisects on lambda and throws if the requested
front-load is unreachable before the trajectory's sinh overflows -- you get a
loud error, never a silently wrong urgency. With `riskAversion = 0` the
schedule degrades to exactly TWAP, which is the correct smoke test: the math
says lambda -> 0 is the risk-neutral linear trajectory.

## 68. Drive all seven benchmarks through one executor

`BenchmarkExecutor` re-decides every interval from live market state instead
of walking a precomputed slice list. One class covers TWAP, VWAP, Arrival,
IS, Close, Open and POV -- only the target curve differs; the dynamic shaping
(alpha urgency, spread/impact damping, depth cap) is shared.

```java
// One executor per benchmark, same parent.
var execs = new ArrayList<BenchmarkExecutor>();
for (var b : BenchmarkExecutor.Benchmark.values()) {
    execs.add(b == BenchmarkExecutor.Benchmark.PARTICIPATION
            ? BenchmarkExecutor.pov(Side.BUY, 100_000, 0.10)
            : BenchmarkExecutor.of(Side.BUY, 100_000, b));
}

// Mid-morning snapshot: 40% of the horizon elapsed, adverse alpha (+0.5
// on a buy = the price is about to run away), normal spread and depth.
double f = 0.40;
var m = new BenchmarkExecutor.MarketState(
        100.00,     // mid
        0.02,       // spread (price units)
        0.3,        // normalized vol regime, ~0..1
        12_000,     // displayed take-now depth
        0.45,       // VWAP curve: 45% of the day's volume has traded
        +0.5,       // normalized alpha in [-1,1]
        1.5);       // estimated child impact, bps
for (var e : execs) {
    e.onMarketVolume(50_000);                       // POV/VWAP need the tape
    System.out.printf("%-25s due %d%n", e.benchmark(), e.dueQuantity(f, m));
}
```

The curves separate exactly as advertised: OPENING_PRICE is nearly done by
40%, ARRIVAL/IS are ahead of TWAP, CLOSING_PRICE has barely started, and
PARTICIPATION ignores `f` entirely. The +0.5 alpha on a buy raises urgency on
every curve, but POV's multiplier is clamped at 1 -- the participation rate is
a promise, and alpha is never allowed to break it. `MarketState.neutral(mid,
f)` disables every input if you want to add signals one at a time.

## 69. Hunt liquidity opportunistically with a completion floor

A schedule algo asks "am I behind the curve?"; a liquidity seeker asks "is
the market cheap right now?" and bursts when it is. The discipline that
makes it safe: a completion floor that ramps in over the final stretch, so
patience can never become a missed parent.

```java
// Defaults: 10% spread tolerance, vol regime < 0.5, impact < 5 bps,
// 25% of displayed depth per burst, floor ramps from 70% of the horizon.
var seek = new LiquiditySeekingAlgo(50_000, LiquiditySeekingAlgo.Config.defaults());

// A cheap moment mid-horizon: spread AT its time-of-day forecast, calm.
var cheap = new BenchmarkExecutor.MarketState(
        100.00, 0.015, 0.2, 20_000, Double.NaN, 0, 2.0);
long burst = seek.dueQuantity(0.40, cheap, 0.016);   // forecast spread 0.016
System.out.println(burst);                            // 5,000 = 25% of depth
seek.onFill(burst);

// An expensive moment: spread 2x forecast -- no burst, and before 70%
// elapsed the floor is 0, so nothing is sent at all.
var wide = new BenchmarkExecutor.MarketState(
        100.00, 0.032, 0.2, 20_000, Double.NaN, 0, 2.0);
System.out.println(seek.dueQuantity(0.50, wide, 0.016));   // 0: sit still

// Late and still expensive: the floor forces completion anyway.
System.out.println(seek.dueQuantity(0.90, wide, 0.016));   // ramped remainder
System.out.println(seek.dueQuantity(1.00, wide, 0.016));   // the full remainder
```

Cheapness is always relative to `SpreadForecaster`'s time-of-day forecast,
not an absolute number -- 2 pips is cheap at the London open and expensive at
midnight. NaN inputs degrade honestly: an unknowable forecast means no
opportunistic burst (the gate fails), but the floor still guarantees the
parent completes by the horizon.

## 70. Slice an iceberg with randomized display

An iceberg shows a small display tranche and reloads when it fills. A fixed
display size is a signature -- detectors like recipe 80's watch for exactly
that repeated print -- so the display can be jittered per reload.

```java
// 50,000 total, ~2,000 displayed, display jittered +/-20%, seeded.
IcebergOrder ice = new IcebergOrder(50_000, 2_000, 0.20, 7L);
System.out.printf("visible %d, hidden %d%n",
        ice.visibleQty(), ice.hiddenQty());

// Fills hit the visible tranche; onFill returns true when the tranche
// exhausted and a fresh one loaded -- that is your cue to re-submit the
// working order (it rejoins the back of the queue, as at a real venue).
long fill = Math.min(1_200, ice.visibleQty());
boolean reloaded = ice.onFill(fill);                 // false: tranche not done
reloaded = ice.onFill(ice.visibleQty());             // true: reload happened
if (reloaded) {
    resubmit(ice.visibleQty());                      // your venue call
}

while (!ice.isComplete()) {
    long take = ice.visibleQty();
    if (ice.onFill(take)) {
        resubmit(ice.visibleQty());
    }
}
```

The reload signal is the important part: every `true` from `onFill` means a
queue-priority reset, which is the real cost of icebergs -- you pay time
priority for size concealment. The jitter is deterministic per seed, so a
backtest replays exactly. To see the other side of this game, feed the
resulting prints to `HiddenLiquidityDetector` (recipe 80) and watch it infer
the reserve you just hid.

## 71. Execute a two-legged spread without legging risk

In a spread trade the risk is the moment you own one leg without the other.
The desk discipline: work the illiquid LEAD leg patiently, let the liquid
HEDGE leg chase its fills at the ratio, and cap the imbalance hard -- at the
cap, stop adding lead risk entirely and cross the hedge to get flat.

```java
// Buy 10,000 of the single name, hedge with 3 futures per 1,000 shares
// (ratio 0.003 hedge units per lead unit is awkward; scale to units you
// trade: here 1 lead unit = 100 shares, ratio = 0.3 contracts per unit).
var spread = new SpreadExecutionAlgo(
        100,     // lead parent: 100 units of 100 shares
        0.3,     // hedge per lead unit
        6,       // legging cap: 6 hedge units of imbalance, never more
        10);     // lead child max: patience knob

var c = spread.decide();
System.out.printf("lead %d, hedge %d%n", c.leadQty(), c.hedgeQty());
spread.onLeadFill(c.leadQty());              // lead filled, hedge did not

c = spread.decide();                          // hedge now chases the imbalance
System.out.printf("imbalance %d hedge units, hedge child %d, atCap %b%n",
        spread.imbalanceHedgeUnits(), c.hedgeQty(), c.atRiskCap());
spread.onHedgeFill(c.hedgeQty());

while (!spread.done()) {
    c = spread.decide();
    spread.onLeadFill(fillOf(c.leadQty()));   // route via your SOR
    spread.onHedgeFill(fillOf(c.hedgeQty()));
}
```

The lead child is sized so that even a full lead fill with NO hedge fill
stays inside the cap -- the cap protects against the hedge not filling, which
is why the lead stops entirely at the cap while the hedge child becomes the
whole imbalance. An imbalance cap that yields under pressure is not a cap.
`done()` requires both legs: lead complete AND hedge caught up.

## 72. Roll a futures position over an expiry window

Rolling everything on day one pays wide back-month spreads; waiting for
expiry pays the last-day scramble. The algo follows the liquidity migration
curve -- the cumulative fraction of open interest that has moved by each roll
day -- and executes each day's due as a calendar spread.

```java
// 500 contracts over a 5-day roll window, classic S-curve migration.
var roll = new FuturesRollAlgo(500, FuturesRollAlgo.defaultMigration(5));

for (int day = 0; day < roll.rollDays(); day++) {
    long due = roll.dueOnDay(day);
    if (due == 0) {
        continue;
    }
    // Each day's due IS a calendar spread: sell front / buy back for a
    // long -- SpreadExecutionAlgo with ratio 1 and the spread's own cap.
    var leg = new SpreadExecutionAlgo(due, 1.0, 25, 50);
    while (!leg.done()) {
        var c = leg.decide();
        leg.onLeadFill(fillOf(c.leadQty()));      // front-month child
        leg.onHedgeFill(fillOf(c.hedgeQty()));    // back-month child
    }
    roll.onRolled(due);
    System.out.printf("day %d: rolled %d, remaining %d%n",
            day, due, roll.remaining());
}
System.out.println(roll.done());                   // true: no delivery risk
```

Falling behind one day just makes later days' due larger -- the roll always
catches up to the curve, and the constructor rejects any migration curve
that does not end at exactly 1.0, because a roll that does not complete is a
delivery notice waiting to happen. Substitute your market's observed
migration (open-interest shift per day) for the default smoothstep when you
have it.

## 73. Execute at the WMR fix without banging it

Orders benchmarked to the WM/Refinitiv 4pm fix are executed by spreading the
parent evenly across the fixing's calculation window -- the fix is computed
from observations inside the window, so TWAP-in-window IS the neutral
replication. Then screen the result the way a surveillance desk would.

```java
// 50M base units across the standard 5-minute window in 20 slices.
// Align slice 0 with the window open: fix time minus half the window.
List<Slice> plan = WmrFixingScheduler.schedule(50_000_000, 20);

// After the window: the fix itself, and the banging-the-close screen.
double[] midsInWindow = capturedMids();            // sampled inside the window
double fix = FixAnalyzer.calculateFix(midsInWindow);   // median, per WM/R

var report = FixAnalyzer.analyze(
        midsInWindow,
        1.08420,       // mid just before the window opened
        1.08425,       // mid after the window closed
        50_000_000,    // our buys in the window
        0,             // our sells in the window
        160_000_000,   // total market volume in the window
        0.25);         // participation share that triggers scrutiny
System.out.printf("fix %.5f, run-up %.1f bps, reversion %.1f bps, "
                + "share %.2f, flagged %b%n",
        report.fixRate(), report.runUpBps(), report.reversionBps(),
        report.participationShare(), report.flagged());
```

The scheduler deliberately refuses the two conduct traps: executing ahead of
the window ("getting done early" is pre-hedging against the client's
benchmark) and skewing inside it (a bet, not replication). The analyzer
flags the classic signature only when all three hold -- large share, run-up
aligned with your net flow, and reversion afterwards: impact that decays is
the footprint of pressure, not information.

## 74. Let a bandit pick today's best algo

You shipped a new schedule variant. Always using the incumbent means never
learning if the challenger is better; rotating uniformly wastes flow on a
loser. UCB1 balances the two with a regret guarantee: mean reward plus an
optimism bonus that shrinks exactly as fast as the evidence accumulates.

```java
// Three arms: incumbent VWAP, the new IS variant, the liquidity seeker.
Ucb1Selector bandit = new Ucb1Selector(3);

for (int parent = 0; parent < 200; parent++) {
    int arm = bandit.select();                 // each arm once, then UCB
    double costBps = executeParentWith(arm);   // run the chosen algo, get TCA

    // Rewards must be in [0,1] -- the theory's scale. Map slippage onto
    // it: 0 bps of cost -> 1.0, 10+ bps -> 0.0.
    double reward = Math.max(0, Math.min(1, 1 - costBps / 10.0));
    bandit.record(arm, reward);
}

for (int a = 0; a < 3; a++) {
    System.out.printf("arm %d: %d pulls, mean reward %.3f%n",
            a, bandit.pulls(a), bandit.meanReward(a));
}
```

`record` rejects rewards outside [0,1] loudly, because a mis-scaled reward
silently breaks the exploration balance -- the one bug that makes a bandit
worse than a coin flip. UCB1 is the cold-start and A/B tool; once hundreds
of fills per venue exist, `VenueScorecard` (recipe 77) models fill rate,
latency and markout separately and is the better router input. Selection is
deterministic (ties break to the lowest index), so tests replay exactly.

## 75. Execute a basket with leg-balance and risk capacity

A two-sided transition is not N independent parents: the buy leg and sell
leg must stay in step or the basket carries net market exposure mid-flight.
`PortfolioExecutor` coordinates per-symbol `BenchmarkExecutor` children with
the two overlays that only exist at basket level.

```java
int n = 2;
var pe = new PortfolioExecutor(n, new PortfolioExecutor.Config(
        1_000_000,      // |filled buys - sells| stays inside $1M
        5_000_000));    // total basket demand per interval

int sellOld = pe.add(BenchmarkExecutor.of(Side.SELL, 80_000,
        BenchmarkExecutor.Benchmark.VWAP));
int buyNew = pe.add(BenchmarkExecutor.of(Side.BUY, 60_000,
        BenchmarkExecutor.Benchmark.VWAP));

// Upgrade capacity allocation from per-symbol vol to true basket risk:
// feed one return vector per interval; a natural hedge earns no urgency.
var cov = new EwmaCovariance(n, 0.97);
pe.useRiskModel(cov);

var states = new BenchmarkExecutor.MarketState[n];
long[] due = new long[n];
// Each interval:
cov.onReturns(intervalReturns);                    // length >= n
states[sellOld] = new BenchmarkExecutor.MarketState(
        52.00, 0.01, 0.3, 15_000, 0.45, 0, 1.0);
states[buyNew] = new BenchmarkExecutor.MarketState(
        101.00, 0.02, 0.5, 8_000, 0.45, 0, 2.0);
pe.decide(0.40, states, due);                      // zero-alloc
pe.onFill(buyNew, due[buyNew], 101.02);            // ALWAYS via pe.onFill
System.out.printf("net notional %.0f%n", pe.netNotional());
```

Both overlays only ever reduce a child's own due -- the ahead leg throttles,
the lagging leg is never pushed past its own benchmark -- so per-symbol
benchmark integrity holds by construction. The one wiring rule: report fills
through `pe.onFill(handle, qty, price)`, never `child(h).onFill(...)` -- the
child call advances the schedule but bypasses the notional ledger the
leg-balance band reads, leaving net exposure uncontrolled while every
per-child number looks healthy.

## 76. Route one order across lit and dark venues

`SmartOrderRouter` ranks venues purely on fee-adjusted displayed price;
`AdaptiveSor` prices the full checklist -- fill-rate reliability, measured
latency, adverse selection, learned dark liquidity -- from a `VenueScorecard`
it updates as you trade.

```java
var card = new VenueScorecard(3);                  // A=0, B=1, dark=2
var sor = new AdaptiveSor(card);
sor.register("EXCH_A", 0);
sor.register("EXCH_B", 1);
sor.register("DARK_X", 2);

var venues = List.of(
        new VenueQuote("EXCH_A", 99.99, 9_000, 100.01, 10_000, 0.3, 120_000, false),
        new VenueQuote("EXCH_B", 99.99, 7_000, 100.01, 8_000, 0.3, 80_000, false),
        new VenueQuote("DARK_X", 99.99, 0, 100.01, 0, 0.1, 200_000, true));

var plan = sor.route(Side.BUY, 12_000, venues);
for (var leg : plan.lit()) {
    System.out.printf("lit  %s %d @ %.2f%n", leg.venue(), leg.quantity(), leg.price());
}
for (var probe : plan.probes()) {
    System.out.printf("dark %s probe %d @ mid%n", probe.venue(), probe.quantity());
}
// -> B's 8,000 first (same price, lower latency), 4,000 to A, plus a
//    contingent midpoint probe at DARK_X. unrouted() is 0 here.

// Feed outcomes back so the next route is smarter:
card.onFill(1, 85_000);                            // B filled in 85us
card.onMiss(0, 130_000);                           // A faded
card.onDarkProbe(2, 3_200);                        // the probe found 3,200
```

Probe legs are additive and contingent: send them alongside the lit legs
and, on dark fills, cancel lit remainder -- the router plans, the executor
manages overfill. Venues below the config's fill-rate floor are vetoed
outright, and the scorecard's measured latency overrides the advertised
number once observed. Cross-asset: an FX "dark" venue is a mid-match
session, and fill rate there is one minus the last-look reject rate.

## 77. Probe a dark pool and measure what it cost you

Dark liquidity is only observable through fills, and a fill is not free just
because it printed at mid -- if the mid moves against you right after, you
traded against informed flow. Probe the pool, then let the scorecard's
post-fill markout put a number on the toxicity.

```java
var pool = new DarkPoolSimulator();                // stands in for the venue
pool.onQuote(99.99, 100.01);                       // crossing needs a lit mid
pool.submit(Side.SELL, 5_000, 1_000);              // contra interest rests

var card = new VenueScorecard(1, 0.05, 0.95, 100_000_000L);   // 100ms horizon
long t0 = System.nanoTime();

// Probe: a 2,000-share buy with a 500-share minimum execution quantity.
var fills = pool.submit(Side.BUY, 2_000, 500);
long got = fills.stream().mapToLong(DarkPoolSimulator.Fill::quantity).sum();
card.onDarkProbe(0, got);                          // teach the router the size
for (var f : fills) {                              // arm the markout per fill
    card.onFill(0, 50_000, true, f.price(), t0);
}

// Feed mids afterwards; markouts mature once the horizon elapses.
card.onMid(99.97, t0 + 150_000_000L);              // mid fell 3c after our buy
System.out.printf("hidden est %.0f, markout %.4f, matured %d%n",
        card.expectedHiddenShares(0),               // probe sizing next time
        card.postFillMarkout(0),                    // negative: fills fade
        card.maturedFillMarkouts());
```

A negative `postFillMarkout` is the adverse-selection cost `AdaptiveSor`
charges the venue per share on the next route -- two pools quoting the same
mid are not equal when one's fills systematically revert. Watch the wiring
canary: `maturedFillMarkouts()` stuck at zero while fills accrue means
`onMid` is not being fed and the router's adverse-selection term is silently
disabled. `onDarkProbe(venue, 0)` is a real observation too -- an empty pool
teaches as much as a full one.

## 78. Estimate impact before you trade, learn it after

Two impact numbers, two sources: `MarketImpactModel` parameterizes impact
from ADV and volatility (the square-root law, before you trade);
`KylesLambda` learns it from the tape (the market's own regression, after).
Run both and let disagreement tell you the assumed model is stale.

```java
// Before: parameterized. ADV 5M shares, 2% daily vol.
var model = new MarketImpactModel(5_000_000, 0.02);
System.out.printf("sqrt-law: %.1f bps for 250k%n",
        model.squareRootImpactBps(250_000));       // Y*sigma*sqrt(Q/ADV)
System.out.printf("schedule cost: %.1f bps at 10%% participation%n",
        model.expectedCostBps(250_000, 0.10));     // permanent/2 + temporary

// After: learned. One sample per decision interval -- the mid change over
// the window and the signed volume that traded in it (buy-aggressor
// positive; TradeClassifier supplies the sign when the feed does not).
var kyle = new KylesLambda(0.02);
kyle.onSample(+0.03, +40_000);                     // buys pushed mid up 3c
kyle.onSample(-0.02, -25_000);
kyle.onSample(+0.01, +10_000);

System.out.printf("lambda %.2e, learned impact %.2f bps for a 20k child%n",
        kyle.lambda(), kyle.impactBps(20_000, 100.00));
// Feed it live: MarketState.impactBps = kyle.impactBps(clipSize, mid).
```

`impactBps` is the live producer for `MarketState.impactBps` (recipe 68) --
impact learned from today's tape, not calibrated last quarter. It ignores
the sign of the quantity (impact is a cost in both directions) and clamps a
negative lambda estimate to zero: "the market pays you to trade" is an
estimation artifact, and feeding it to the executor would accelerate the
schedule on garbage. `lambda()` stays raw for diagnostics.

## 79. Model your fill probability at the touch

A passive order fills only if the price reaches the level AND the queue
clears to you. `FillProbabilityModel` composes the two; when you have no L3
feed to track your exact queue slot, `QueuePositionEstimator` maintains the
standard L2 estimate.

```java
// The L2 queue estimate: join the back of a 12,000-share level.
var q = new QueuePositionEstimator();
q.join(12_000, 1_000);

q.onTrade(4_000);                    // trades hit the front: ahead -= 4,000
q.onLevelResize(6_500);              // a cancel: pro-rata, could be either side
System.out.printf("ahead ~%.0f, progress %.2f%n",
        q.sharesAhead(), q.queueProgress());

// Queue-only fill probability, given expected traded volume at the level.
System.out.printf("queue clears: %.2f%n", q.fillProbability(15_000));

// The full placement score for a level 2 cents away: touch x queue-clear.
double p = FillProbabilityModel.passiveFillProbability(
        0.02,        // distance to the level, price units
        2e-4,        // vol per sqrt(second) (SignalEngine.volPerSqrtSecond)
        60,          // horizon: 60 seconds
        100.00,      // current price
        (long) q.sharesAhead(), 1_000,
        15_000);     // expected volume at the level over the horizon
System.out.printf("fill probability at the level: %.2f%n", p);
```

The feed-ordering contract matters: report each trade via `onTrade` BEFORE
the depth update that reflects it, and give `onLevelResize` sizes net of
trades already reported -- otherwise the same execution is counted once as a
trade and again as a cancel, and shares-ahead falls twice per fill. The
composed probability is a mild underestimate (touch and queue-clearing are
positively correlated), so treat it as a conservative placement score, not a
calibrated number.

## 80. Detect icebergs and toxic flow in real time

Two lit-tape inference tools a quoting desk runs side by side:
`HiddenLiquidityDetector` infers reserve size at a level (a single print
larger than the display can only have filled against hidden size), and
`Vpin` gauges flow toxicity (one-sided volume buckets = informed flow).

```java
// Iceberg inference, per price level (dense tick indices).
var hidden = new HiddenLiquidityDetector(100, 0.2);
int lvl = 42;
hidden.onDisplayed(lvl, 500);            // the tip shows 500
hidden.onExecution(lvl, 1_800);          // one print of 1,800: the tell
hidden.onDisplayed(lvl, 500);            // ...and it keeps quoting
System.out.printf("iceberg %b, multiplier %.1fx, true depth ~%.0f%n",
        hidden.isIceberg(lvl), hidden.hiddenMultiplier(lvl),
        hidden.estimatedTrueDepth(lvl));

// Flow toxicity: 5,000-share volume buckets, averaged over 50 buckets
// (classic: bucket ~ ADV/50, window 50). Feed TradeClassifier's aggressor
// side when the venue does not disclose it.
var vpin = new Vpin(5_000, 50);
for (int i = 0; i < 60; i++) {
    vpin.onTrade(2_500, true);           // relentless one-sided buying
    vpin.onTrade(2_500, i % 5 == 0);     // thin two-way relief
}
if (vpin.ready() && vpin.vpin() > 0.6) {
    widenQuotesOrPull();                 // quoting is no longer a business
}
System.out.printf("VPIN %.2f%n", vpin.vpin());
```

The detector's per-print signature is the sound one -- a cumulative
executed-vs-displayed comparison false-flags every busy level, since a level
legitimately trades many times its instantaneous display through ordinary
adds. `vpin()` returns NaN until the first bucket completes: an empty
average pretending to be calm would be exactly the wrong default for a risk
signal. Recipe 17 shows the full quote-defense loop these two feed.

## 81. Compute an Almgren-Chriss optimal trajectory

The closed-form optimal execution schedule: minimize expected cost plus
lambda times cost variance. Holdings decay as a sinh curve whose urgency
kappa grows with risk aversion; lambda -> 0 recovers TWAP. The frontier
across lambdas is how you choose the urgency.

```java
var p = new AlmgrenChriss.Params(
        1_000_000,   // X: shares to liquidate
        5.0,         // T: 5 days
        5,           // N: daily intervals
        0.95,        // sigma: price vol per sqrt(day)
        2.5e-6,      // eta: temporary impact
        2.5e-7,      // gamma: permanent impact
        2e-6);       // lambda: risk aversion

var t = AlmgrenChriss.optimalTrajectory(p);
System.out.printf("kappa %.3f, E[cost] %.0f, sd(cost) %.0f%n",
        t.kappa(), t.expectedCost(), Math.sqrt(t.costVariance()));
for (int j = 0; j < t.trades().length; j++) {
    System.out.printf("day %d: sell %.0f, hold %.0f%n",
            j + 1, t.trades()[j], t.holdings()[j + 1]);
}

// The efficient frontier: expected cost vs risk across urgencies.
for (var traj : AlmgrenChriss.efficientFrontier(p,
        new double[] {0, 5e-7, 2e-6, 1e-5})) {
    System.out.printf("E %.0f  sd %.0f%n",
            traj.expectedCost(), Math.sqrt(traj.costVariance()));
}
```

The frontier makes the trade-off concrete: each step up in lambda buys lower
cost variance (less exposure to drift) at higher expected cost (more
temporary impact) -- pick the point your risk mandate tolerates. All
quantities share one time unit (days here): sigma per sqrt(day), T in days.
To turn the chosen trajectory into executable slices, hand the same `Params`
to `ImplementationShortfallScheduler` (recipe 67); to re-decide live
instead, use the IS benchmark in recipe 68.

## 82. Grade an execution TCA-style after the fact

Post-trade truth: benchmark the fills against the arrival mid
(implementation shortfall), the interval VWAP, and the prevailing mid at
each fill (effective spread) -- then decompose the spread into what the
liquidity provider kept and what the price move took.

```java
var fills = List.of(
        new Execution("ACME", Side.BUY, 100.02, 4_000, t1, "EXCH_B"),
        new Execution("ACME", Side.BUY, 100.05, 3_000, t2, "EXCH_A"),
        new Execution("ACME", Side.BUY, 100.04, 3_000, t3, "DARK_X"));
double[] midAtFill = {100.00, 100.03, 100.04};

var tca = TransactionCostAnalyzer.analyze(fills,
        99.98,       // arrival mid: the parent's birth certificate
        100.06,      // market VWAP over the execution interval
        midAtFill);
System.out.printf("avg px %.4f, IS %.1f bps, vs VWAP %.1f bps, "
                + "eff spread %.1f bps%n",
        tca.avgExecutionPrice(), tca.implementationShortfallBps(),
        tca.slippageVsVwapBps(), tca.avgEffectiveSpreadBps());

// Decompose one fill: effective = realized + price impact.
double midAfter = 100.06;                          // mid 5 minutes later
System.out.printf("quoted %.1f  effective %.1f = realized %.1f + impact %.1f%n",
        MarketQualityMetrics.quotedSpreadBps(100.00, 100.04),
        MarketQualityMetrics.effectiveSpreadBps(Side.BUY, 100.02, 100.00),
        MarketQualityMetrics.realizedSpreadBps(Side.BUY, 100.02, midAfter),
        MarketQualityMetrics.priceImpactBps(Side.BUY, 100.00, midAfter));
```

All costs are signed so positive = cost to the trader, buys and sells alike
-- beating VWAP shows up negative. The decomposition identity (effective ~
realized + impact) separates the two reasons a fill was expensive: realized
spread is what the maker kept; price impact is the move your own trading (or
information) caused. Negative realized spread with large impact is the
adverse-selection signature recipe 77 prices per venue; for the full
regulatory best-execution report, see recipe 22.

---

## 83. Book an FX swap and mark it as points move

An FX swap is two offsetting exchanges -- buy the base currency on the near
date, sell it back on the far date -- and an at-market swap has zero value at
inception: only the points differential is economically exchanged. Value
appears as the points move.

```java
CurrencyPair eurusd = CurrencyPair.of("EURUSD");
LocalDate today = LocalDate.of(2026, 7, 10);
SwapPointsCurve curve = SwapPointsCurve.builder(eurusd, today, 1.0850)
        .add("1W", 2.8).add("1M", 12.6).add("3M", 38.4).add("1Y", 145.0)
        .build();

// Buy 10M EUR spot, sell it back in 3M -- the classic funding swap.
FxSwap swap = FxSwap.atMarket(curve, "SPOT", "3M", 10_000_000);
System.out.println(swap.swapPointsPips());     // the negotiated far-minus-near points
System.out.println(swap.markToMarket(curve));  // ~0: at-market by construction

// A week later the points (and spot) have moved; rebuild and re-mark:
SwapPointsCurve later = SwapPointsCurve.builder(eurusd, today.plusDays(7), 1.0790)
        .add("1M", 14.1).add("3M", 41.0).add("1Y", 150.0).build();
double mtm = swap.markToMarket(later);            // quote ccy, undiscounted
double disc = swap.markToMarket(later, usdCurve); // rates.YieldCurve discounting

// The daily position roll, as arithmetic:
double roll = FxSwap.rollCost(eurusd, 10_000_000, -0.45);   // tom-next in pips
```

Each leg marks as (current outright - traded rate) x signed notional, so the
swap is nearly spot-neutral: what moves it is the points, not the level. Once
the near leg's date falls before the marking curve's spot, that leg is
realized cash, not MTM -- a seasoned swap values only its remaining live leg,
which is exactly how the second mark above behaves.

## 84. Book and settle an NDF through its fixing

An NDF is the forward for restricted currencies (INR, KRW, BRL...): the local
currency never moves, and the trade cash-settles in USD against an official
fixing published before settlement. Two dates matter, and they differ.

```java
CurrencyPair usdinr = CurrencyPair.of("USDINR");
LocalDate trade = LocalDate.of(2026, 7, 10);
SwapPointsCurve curve = SwapPointsCurve.builder(usdinr, trade, 83.20)
        .add("1M", 2_800).add("3M", 8_600).build();   // pips: INR carry is real

// Buy 5M USD one month forward at the curve outright, cash-settled in USD.
double contractRate = curve.outright("1M");
Ndf ndf = Ndf.of(usdinr, trade, "1M", contractRate, 5_000_000);
System.out.println(ndf.fixingDate() + " fixes, " + ndf.settlementDate() + " pays");
// INR fixes 2 local business days before settlement: Ndf.fixingLagDays("INR")

// Daily mark while live: the settlement formula at the forward to the
// FIXING date -- the date the payoff actually references, not settlement.
double mtm = ndf.markToMarket(currentCurve);              // USD, undiscounted
double disc = ndf.markToMarket(currentCurve, usdCurve);   // discounted from settlement

// Fixing day: the official RBI reference rate prints and the curve retires.
double settle = ndf.settlementAmount(83.95);   // USD; positive pays the buyer
```

The fixing window is where marks get subtle: once the fixing date is at or
before the curve's spot there is no forward left to read, so `markToMarket`
degrades to the spot outright instead of throwing mid-lifecycle. After the
official rate prints, only `settlementAmount` with the actual fixing is the
right number -- a curve cannot know it. The division by the fixing in the
settlement formula converts the INR difference back into deliverable USD.

## 85. Build an FX vol smile from broker quotes

FX options are not quoted by strike. A broker screen shows, per expiry: ATM
vol, the 25-delta risk reversal (call vol minus put vol) and butterfly. This
recipe turns those three numbers into an absolute strike/vol smile that
strike-based pricers can consume.

```java
// Per expiry: expiryYears, forward, ATM, RR25, BF25 (and optional 10d wings).
FxVolSurface surface = FxVolSurface.builder()
        .add(0.25, 1.0885, 0.0780, -0.0100, 0.0022)
        .add(1.00, 1.0995, 0.0810, -0.0125, 0.0030, -0.0220, 0.0105)
        .build();
// USDJPY convention: .premiumAdjusted(true) before build()

// The solved pillar: [10dP, 25dP, ATM, 25dC, 10dC] strikes, low to high.
FxVolSurface.SmilePillar p = surface.pillar(1);
for (int i = 0; i < p.strikes().length; i++) {
    System.out.printf("K=%.4f vol=%.2f%%%n", p.strikes()[i], p.vols()[i] * 100);
}

// Lookups anywhere on the surface (allocation-free after build):
double v = surface.vol(0.50, 1.0200);   // total-variance interp across expiries
double atm = surface.atmVol(0.50);      // delta-neutral straddle vol

// The delta-strike arithmetic is exposed for hedgers and exotic pricers:
double k25p = FxVolSurface.strikeForDelta(1.0885, 0.0835, 0.25, -0.25, false, false);
```

The pillar vols come from the quote algebra (vol25c = atm + bf + rr/2, put
minus), and each pillar's strike is then solved from its own delta and its own
vol -- the market's definition of the smile points. A negative RR means puts
over calls: the EUR downside is bid. If your rr/bf signs are wrong the solved
strikes stop increasing and `build()` throws rather than shipping a crossed
smile; feed the pillar to `pricing.VannaVolga` for smile-consistent exotics.

## 86. Route an FX order across tiered LP liquidity

E-FX liquidity is not one anonymous book: each LP streams a private ladder of
size tiers, may hold your order under last look, and may reject it. Routing
by displayed price alone pays for that; this recipe routes by expected
all-in price.

```java
FxTierBook book = new FxTierBook(3, 4);        // 3 LPs, up to 4 tiers each
// LP 0's ask ladder arrives (tiers best-first; sizes are clip capacity):
book.tier(0, false, 0, 1.08502, 1_000_000);
book.tier(0, false, 1, 1.08504, 5_000_000);
book.tier(0, false, 2, 1.08508, 10_000_000);
book.tierCount(0, false, 3);                   // publish the update
// ... LP 1 and LP 2 stream likewise

LpScorecard card = new LpScorecard(3);          // 5% EWMA, 100ms markout horizon
LpRouter router = new LpRouter(book, card, 0.25);   // veto LPs above 25% rejects

int lp = router.route(true, 5_000_000);         // full-amount: ONE LP, one price
if (lp >= 0) {
    System.out.println(router.lastQuotedPrice() + " -> " + router.lastExpectedPrice());
    // send the clip; teach the scorecard whatever the LP does with it:
    card.onFill(lp, true, fillPx, midAtRequest, holdNanos);
    // or: card.onReject(lp, true, midAtRequest, tsNanos, holdNanos);
}
card.onMid(compositeMid, tsNanos);              // REQUIRED: matures reject markouts

// The deliberate multi-LP sweep, when you accept the signaling:
double[] plan = new double[3];
double cost = book.sweepPlan(true, 20_000_000, plan);   // qty taken per LP
```

The router's expected price is quoted price plus rejectRate x max(markout, 0):
a tight quote from an LP that rejects 20% of the time -- and whose rejects are
followed by adverse markout -- is more expensive than a wider firm quote. The
wiring requirement is `onMid`: without it markouts never mature and the
penalty is silently zero. Watch `card.maturedMarkouts()` in monitoring -- zero
while rejects accrue means the hook is missing. The card survives overnight
via `writeState`/`readState` (recipe 12's `Checkpoint`).

## 87. Price the exotics shelf: digital, touch, barrier

The first-generation FX exotics book in closed form -- with the in-out parity
check that catches sign errors before a client does.

```java
double s = 1.0850, r = 0.045, q = 0.030, vol = 0.078, t = 0.25;
// Conventions match BlackScholes: r = domestic rate, q = foreign rate (FX).

// European digital: fixed payout if spot finishes beyond the strike.
double digi = DigitalOption.cashOrNothing(
        OptionType.CALL, s, 1.10, r, q, vol, t, 1_000_000);

// One-touch / no-touch: desks quote these as ~discounted hit probabilities,
// and the hit probability itself is exposed.
double pHit = TouchOption.hitProbability(s, 1.12, r, q, vol, t);
double oneTouch = TouchOption.oneTouch(s, 1.12, r, q, vol, t, 1_000_000);
double noTouch = TouchOption.noTouch(s, 1.12, r, q, vol, t, 1_000_000);
// oneTouch + noTouch = discounted payout, always.

// Regular barriers (Reiner-Rubinstein reflection formulas):
double ki = BarrierOption.downAndInCall(s, 1.10, 1.05, r, q, vol, t);
double ko = BarrierOption.downAndOutCall(s, 1.10, 1.05, r, q, vol, t);

// In-out parity: holding both replicates the vanilla EXACTLY.
double vanilla = BlackScholes.price(OptionType.CALL, s, 1.10, r, q, vol, t);
System.out.println(Math.abs(ki + ko - vanilla));   // ~1e-16
```

The honest boundary: only regular barriers (barrier in the OTM region) price
in closed form here. A reverse barrier -- an up-and-out call with H above K --
knocks out exactly where the payoff is largest, and `BarrierOption` throws
rather than pricing it subtly wrong; price those with
`simulation.MonteCarloSimulator` path pricing. All prices use flat vol: for a
smile-consistent number, pull the vol at the relevant strike region from
`FxVolSurface` (recipe 85) or reprice with `VannaVolga`.

## 88. Calibrate SABR to a smile and read rho/nu

SABR is how rates and FX desks store a smile: three parameters with meanings,
instead of five vol points. With beta fixed by market convention, calibrate
(alpha, rho, nu) to the observed strike/vol quotes.

```java
double f = 100, t = 1.0, beta = 0.5;             // beta is chosen, not fitted
double[] strikes = {80, 90, 100, 110, 120};
double[] vols = {0.260, 0.235, 0.220, 0.215, 0.218};   // the observed smile

SabrModel.Params p = SabrModel.calibrate(f, t, beta, strikes, vols);
System.out.printf("alpha=%.4f rho=%.2f nu=%.2f (rmse %.4f vol pts)%n",
        p.alpha(), p.rho(), p.nu(), p.rmse());

// The fitted smile at ANY strike -- the interpolation pillars cannot give:
double v95 = SabrModel.impliedVol(f, 95, t, p.alpha(), p.beta(), p.rho(), p.nu());

// Sanity-check the fit visually before trusting it:
for (double k : strikes) {
    System.out.printf("K=%.0f market=%.3f sabr=%.3f%n", k,
            vols[(int) ((k - 80) / 10)],
            SabrModel.impliedVol(f, k, t, p.alpha(), p.beta(), p.rho(), p.nu()));
}
```

Read the parameters like a trader: alpha sets the ATM level, rho tilts the
smile (negative = downside vols over upside, the equity-style skew this
example's falling left wing produces), nu fattens both wings. The calibration
is a seeded random search plus shrinking coordinate refinement --
derivative-free and deterministic, so the same quotes always produce the same
parameters. Check `rmse()` before using the fit: a smile SABR cannot bend to
(e.g. a double kink) shows up there, not in an exception.

## 89. Cross-check Heston semi-analytic vs Monte Carlo

Heston's semi-analytic price is a numerical integral of a complex-valued
characteristic function -- the kind of formula that can be subtly wrong while
looking plausible. The Monte Carlo path pricer is the independent witness.

```java
// kappa, theta, sigmaV, rho, v0: variance mean-reverts to 4% vol^2,
// spot-vol correlation -0.7 produces the equity skew.
Heston.Params p = new Heston.Params(2.0, 0.04, 0.5, -0.7, 0.04);
System.out.println("Feller ratio " + p.feller());   // 0.64: violated -- common

double semi = Heston.call(100, 100, 0.03, 0.01, 1.0, p);
double mc = Heston.callMonteCarlo(100, 100, 0.03, 0.01, 1.0, p,
        252, 200_000, 42);                          // steps, paths, seed
System.out.printf("semi=%.4f mc=%.4f diff=%.4f%n", semi, mc, semi - mc);
// agreement to Monte Carlo noise: a few tenths of a percent of the price

// Puts come by parity; the smile the model PRODUCES is read by inversion:
double put = Heston.put(100, 90, 0.03, 0.01, 1.0, p);
double iv = BlackScholes.impliedVol(OptionType.CALL,
        Heston.call(100, 90, 0.03, 0.01, 1.0, p), 100, 90, 0.03, 0.01, 1.0);
```

The Feller ratio 2*kappa*theta/sigmaV^2 below 1 means the variance process
can touch zero -- markets calibrate there constantly, which is why the Monte
Carlo uses full-truncation Euler (negative variance clamped in the diffusion
only). The semi-analytic side uses the "little Heston trap" formulation with
a stretched Simpson window; the BS-limit test in `MarketRiskPricingTest`
caught a real first-integration-node bug before release -- which is the whole
argument for keeping both pricers and diffing them, exactly as above.

## 90. Price an autocallable and put it in competition

Structured products have no order book: you price the note with the model,
send an RFQ to a dealer panel, deal on the best quote -- and record what the
competition was worth, which is how tomorrow's panel gets chosen.

```java
// Quarterly observations, 2% memory coupons, 100% autocall, 60% knock-in.
Autocallable note = new Autocallable(1_000_000,
        new double[]{0.25, 0.50, 0.75, 1.00}, 1.00, 0.80, 0.60, 0.02, true);
double fair = note.price(100, 100, 0.24, 0.03, 0.02, 200_000, 42);
// vol 0.24: from the DOWNSIDE smile region -- the knock-in put lives there,
// and flat ATM vol underprices it.

RfqDealerScorecard panel = new RfqDealerScorecard(5);
RfqAuction rfq = new RfqAuction(true, fair, 5, nowNanos);   // client buys
rfq.onQuote(0, fair * 1.012, nowNanos + 40_000_000L);       // 40ms responder
rfq.onQuote(2, fair * 1.008, nowNanos + 90_000_000L);
rfq.onQuote(3, fair * 1.015, nowNanos + 150_000_000L);      // dealers 1, 4 declined

int winner = rfq.winner();                          // dealer 2: lowest offer
double paidBps = rfq.winnerSpreadToFairBps();       // ~80 bps over theory
double cover = rfq.coverPrice();                    // the second-best: dealer 0
panel.onAuction(rfq);                               // quote rate, wins, spread learn

System.out.printf("dealer 2 quote rate %.0f%%, avg spread-to-fair %.0f bps%n",
        panel.quoteRate(2) * 100, panel.avgSpreadToFairBps(2));
```

The cover is the industry's yardstick: the gap between cover and winning
price is what dealer 2's presence on the panel was worth this trade. A
winner AT the cover was replaceable. The scorecard accumulates quote rate,
response time, win rate and spread-to-fair per dealer, and persists overnight
via `Checkpoint` (recipe 12) -- panel selection is a learning loop, exactly
like venue and LP selection. Declines are recorded as no quote, never as a
tradable level.

## 91. Neutralize a book's greeks with a linear solve

Hedging delta, gamma and vega simultaneously is a 3x3 linear system: three
instruments whose greeks are linearly independent, solved for the quantities
that zero the book. Then verify -- and the next day, ask the greeks to explain
the P&L.

```java
OptionsBook book = new OptionsBook(100, 0.03, 0.0);   // spot, rate, carry
book.addOption("3M 105C", OptionType.CALL, 105, 0.25, -1_000, 0.22)  // short
    .addOption("6M 95P", OptionType.PUT, 95, 0.50, -800, 0.25);      // short
OptionsBook.BookGreeks g = book.greeks();   // net value/delta/gamma/vega/theta

// Hedge instruments: the underlying plus two liquid options (per-unit greeks).
var opt1 = new GreekHedger.Instrument("3M 100C", 0.55, 0.045, 0.20);
var opt2 = new GreekHedger.Instrument("6M 100C", 0.57, 0.031, 0.28);
double[] qty = GreekHedger.deltaGammaVegaHedge(
        g.delta(), g.gamma(), g.vega(), opt1, opt2);
// qty = [underlyingQty, opt1Qty, opt2Qty]

double[] residual = GreekHedger.residualGreeks(
        new double[]{g.delta(), g.gamma(), g.vega()},
        new GreekHedger.Instrument[]{
                GreekHedger.Instrument.underlying("SPOT"), opt1, opt2},
        qty);                                     // ~[0, 0, 0], to rounding

// Next day, spot 97, vols +2 points: did the greeks explain the move?
var explain = book.pnlExplain(97.0, 0.02, 1.0 / 252);
System.out.printf("actual=%.0f delta=%.0f gamma=%.0f vega=%.0f theta=%.0f "
        + "unexplained=%.0f%n", explain.actualPnl(), explain.deltaPnl(),
        explain.gammaPnl(), explain.vegaPnl(), explain.thetaPnl(),
        explain.unexplained());
```

The explain is the desk's daily honesty check: a large unexplained term means
either the move was too big for a second-order expansion or the greeks were
stale -- both are instructions to re-hedge, not curiosities. For the full
picture around the current point, `book.scenarioGrid(spotShifts, volShifts)`
produces the spot-by-vol P&L matrix every options risk report carries. The
general `GreekHedger.neutralize` handles any greek/instrument count, provided
the instrument greeks are linearly independent -- two near-identical options
make the system singular, and the solver says so.

## 92. Test cointegration before trading the pair

A high return correlation is not a pairs trade -- two random walks can look
correlated for years and never come back. The spread must be stationary, and
the hedge ratio drifts; test first, then track.

```java
// 1. Is there a tether at all? Engle-Granger: regress, then ADF the residual.
var eg = CointegrationTest.engleGranger(pricesA, pricesB);
System.out.printf("ADF t=%.2f (5%% critical %.2f)%n",
        eg.adfTStatistic(), CointegrationTest.CRITICAL_5PCT);
if (!eg.cointegrated5pct()) return;             // no tether, no trade

// 2. Characterize the spread: ratio, half-life, current stretch.
var pair = PairsHedger.analyze(pricesA, pricesB);
System.out.printf("beta=%.2f halfLife=%.1f bars z=%.2f%n",
        pair.hedgeRatio(), pair.halfLifeBars(), pair.lastZScore());
// halfLife = +INF means no in-sample mean reversion: also no trade.

// 3. The static OLS beta goes stale; track it live with a Kalman filter.
KalmanBeta kf = new KalmanBeta(pair.hedgeRatio(), 1.0, 1e-5, 1.0);
for (int i = 0; i < pricesA.length; i++) {
    double innovation = kf.onObservation(pricesB[i], pricesA[i]);
    // innovation = A minus the filter's prediction: itself a spread signal
}
double betaNow = kf.beta();
double trust = kf.betaVariance();   // wide = the filter distrusts its own ratio
```

The Kalman knobs mean something: processNoise is how fast you believe the
relationship drifts (0 collapses to recursive least squares -- beta converges
and freezes), observationNoise is how noisy each print is, and only their
ratio matters. A hedge sized off a beta the filter itself distrusts is a
position, not a hedge -- check `betaVariance()` before sizing. From here,
recipe 15 takes over: OU half-life on the spread and the legging-capped
`SpreadExecutionAlgo` for entry.

## 93. Simulate discrete delta-hedging error

Black-Scholes says continuous costless rebalancing replicates the option
exactly. You rebalance daily and pay costs; the difference is a
distribution, and this recipe produces it.

```java
// Rebalance when delta drifts more than 0.05; pay 2 bps per hedge trade.
var config = new DeltaHedger.Config(0.05, 2.0);
HedgingSimulator sim = new HedgingSimulator(7L);   // deterministic per seed

// Question 1 -- discretization risk: hedge vol EQUALS realized vol.
HedgingErrorDistribution fair = sim.simulate(OptionType.CALL, 100, 100, 0.5,
        0.03, 0.0, 0.20, 0.20, 126, 5_000, config);
System.out.println(fair);   // mean ~0; std IS the cost of discreteness

// Question 2 -- vol mispricing: sell at 22 implied, realize 20.
HedgingErrorDistribution rich = sim.simulate(OptionType.CALL, 100, 100, 0.5,
        0.03, 0.0, 0.22, 0.20, 126, 5_000, config);
System.out.printf("mean=%.3f pLoss=%.0f%% VaR95=%.3f CVaR95=%.3f "
        + "costs=%.3f rebalances=%.0f%n",
        rich.mean(), rich.probabilityOfLoss() * 100, rich.valueAtRisk(0.95),
        rich.conditionalValueAtRisk(0.95), rich.meanTradingCosts(),
        rich.meanRebalances());

// One path in detail, for intuition (or a REAL price path from your data):
DeltaHedger.HedgeReport one = DeltaHedger.simulateShortOption(
        OptionType.CALL, 100, 0.5, 0.03, 0.0, 0.20, path, 1.0 / 252, config);
```

The trade-off every option desk lives with is now a table: tighten the band
and the replication error shrinks while costs grow; widen it and the reverse.
Selling rich vol shows up as a positive mean P&L -- but the distribution keeps
its left tail, and `valueAtRisk`/`conditionalValueAtRisk` price it. The
simulator runs paths in parallel yet is deterministic for a given seed
regardless of thread scheduling, so the numbers in your report reproduce.

## 94. Run all four VaR flavors on one book

One input shape -- factor exposures against a covariance or a return history --
four methods that genuinely disagree, and the disagreement is the point. Each
comes with its expected shortfall: post-FRTB, ES leads and VaR is the
diagnostic.

```java
double[] exposures = {5_000_000, -2_000_000, 1_500_000};  // ccy P&L per unit move
double[][] cov = /* daily factor covariance: EwmaCovariance or sample */;

// 1. Delta-normal: instant, and exactly wrong for optionality.
double dnVar = VarEngine.deltaNormalVar(exposures, cov, 0.99);
double dnEs = VarEngine.deltaNormalEs(exposures, cov, 0.99);

// 2. Historical: replay actual factor-return rows; exactly as fat-tailed
//    as the window was, no correlation matrix assumed.
var hist = VarEngine.historicalVar(exposures, factorReturnRows, 0.99);

// 3. Monte Carlo: converges to delta-normal on a linear book (pinned by
//    tests) -- its value is the harness for full revaluation.
var mc = VarEngine.monteCarloVar(exposures, cov, 0.99, 100_000, 42);

// 4. Delta-gamma (Cornish-Fisher): the asymmetry delta-normal cannot see.
double[][] gamma = /* second-order sensitivities (options books) */;
double dgVar = VarEngine.deltaGammaVar(exposures, gamma, cov, 0.99);
double dgEs = VarEngine.deltaGammaEs(exposures, gamma, cov, 0.99);

System.out.printf("dn %.0f/%.0f  hist %.0f/%.0f  mc %.0f/%.0f  dg %.0f/%.0f%n",
        dnVar, dnEs, hist.var(), hist.expectedShortfall(),
        mc.var(), mc.expectedShortfall(), dgVar, dgEs);
```

Read the spread between methods, not any single number: hist far above dn
says the window had fatter tails than a Gaussian fitted to it; dgVar above
dnVar says the book is short gamma and the linear number flatters it. The
Cornish-Fisher expansion is honest about its boundary -- moderate gamma only;
when the quadratic term dominates, graduate to
`VarEngine.fullRevaluationVar(scenarios, moves -> yourPricer.pnl(moves), 0.99)`,
which reprices every scenario through YOUR pricer and is the only method that
sees a knocked-out barrier.

## 95. Fit the tail with EVT and know when to refuse

Historical VaR at 99.9% from 500 observations is reading the worst
half-observation. EVT fits a Generalized Pareto to the exceedances over a
high threshold and extrapolates along the fitted tail -- and refuses when the
tail has no finite mean.

```java
double[] losses = /* daily losses, positive numbers (feed -returns) */;

var tail = ExtremeValueTheory.fitPot(losses, 0.95);   // threshold quantile
System.out.printf("u=%.4f xi=%.2f beta=%.4f (%d exceedances of %d)%n",
        tail.threshold(), tail.shape(), tail.scale(),
        tail.exceedances(), tail.sampleSize());

double var999 = tail.var(0.999);          // beyond the sample, honestly
try {
    double es999 = tail.expectedShortfall(0.999);
} catch (IllegalStateException noMean) {
    // xi >= 1: the fitted tail's mean is INFINITE. The refusal is the
    // answer -- a finite number here would be the lie EVT exists to prevent.
}

// The classic threshold diagnostic: xi should be stable across thresholds.
for (double q : new double[]{0.90, 0.925, 0.95}) {
    System.out.printf("q=%.3f -> xi=%.2f%n", q,
            ExtremeValueTheory.fitPot(losses, q).shape());
}
```

The shape xi is the number to stare at: ~0 is an exponential (Gaussian-ish)
tail, positive is a power law -- equity returns typically fit 0.2 to 0.4. If
xi jumps around as you move the threshold, the tail is not GPD-stable yet:
lower the threshold or bring more data (`fitPot` already refuses under 10
exceedances). And `var(p)` throws for p below the fitted tail -- inside the
sample, plain historical VaR is the right tool, and the exception says so.

## 96. Reverse-stress the book: what move breaks us?

The regulator's inverted question: not "what does a 2-sigma move cost" but
"what move loses 2M, and how implausible is that move?" For a linear book the
answer is closed form -- the worst direction is along Sigma times delta.

```java
double[] exposures = book.netExposures();       // e.g. from CentralRiskBook
double[][] cov = /* factor covariance */;

var reverse = StressTester.reverseStress(exposures, cov, 2_000_000);
double[] breakingMove = reverse.shocks();       // most-probable move losing 2M
double sigmas = reverse.mahalanobisSigmas();    // its joint implausibility

System.out.printf("breaks at %.1f sigmas: %s%n", sigmas,
        java.util.Arrays.toString(breakingMove));

// Context: what would the named templates do to THIS book, today?
double covid = StressTester.scenarioPnl(exposures, StressTester.covidMarch2020());
double lehman = StressTester.scenarioPnl(exposures, StressTester.lehman2008());

// And the ladder for the report -- delta-gamma so the down rungs are honest:
double[] rungs = StressTester.sensitivityLadder(exposures, gamma, 0, 0.10, 10);
```

Read `mahalanobisSigmas` as the verdict: above 10, the netting bought you
genuine comfort -- only a joint move markets have never printed breaks the
book. Below 3, the book breaks on a Tuesday -- fix it today, not after the
committee meeting. The historical templates are stylized shock vectors for a
[equity, rates, FX-USD, commodity, vol] factor ordering -- starting points to
edit for your factor set, documented as approximations, not certified
replays. Verify the closed form by pushing `breakingMove` back through
`scenarioPnl`: it loses exactly the target.

## 97. Cascade FRTB liquidity-horizon ES and attribute PLAT

The Basel wrap on recipe 94's numbers: ES at 97.5% cascaded across liquidity
horizons, anchored to a stressed period, and the P&L attribution test that
decides whether the model keeps its internal-model approval.

```java
// 10-day ES per liquidity-horizon subset: index 0 = ALL factors at LH 10,
// then only the factors needing >= 20 days to exit, then >= 60.
double[] esByHorizon = {2_400_000, 1_100_000, 600_000};
int[] horizons = {10, 20, 60};
double es = FrtbEs.liquidityHorizonEs(esByHorizon, horizons);   // MAR33.5

// Anchor to the stressed period via the reduced factor set (ratio >= 1:
// a calmer-than-today stressed period must not DISCOUNT capital).
double imcc = FrtbEs.stressCalibratedEs(es, esStressedReduced, esCurrentReduced);

// The backtesting one-pager: 250-day 99% VaR exceptions -> zone.
var zone = FrtbEs.TrafficLight.of(6);    // AMBER: capital multiplier rises

// PLAT: does the RISK MODEL'S P&L track the desk's actual P&L?
var plat = PnlAttribution.test(hypotheticalPnl, riskTheoreticalPnl);
System.out.printf("%s: spearman=%.2f ks=%.3f%n",
        plat.zone(), plat.spearmanCorrelation(), plat.ksStatistic());

// The raw tail measure, if you have the loss sample directly:
double es975 = FrtbEs.es975(tenDayLosses);
```

PLAT asks two questions per MAR32: do HPL and RTPL rank days the same way
(Spearman above 0.80 for green), and do their distributions have the same
shape (KS below 0.09)? Failing usually means missing risk factors -- the model
prices with fewer factors than move the real book. Amber adds a capital
surcharge; red kicks the desk to the standardized approach. Everything here
is styled after BCBS MAR32/MAR33 and pinned by hand-computed tests, but
deliberately not certified: NMRF capital, desk approvals and the
standardized-approach floor are named out of scope in `docs/MARKET_RISK.md`.

## 98. Backtest your VaR like a regulator

A VaR model owes you two things: exceptions at the promised rate, and
exceptions that arrive independently. A model that is right on average but
wrong in crises passes the first test and fails the second -- which is the
failure that matters.

```java
double[] dailyReturns = /* realized returns */;
double[] varForecasts = /* each day's VaR as a POSITIVE loss fraction */;

var result = VarBacktest.test(dailyReturns, varForecasts, 0.99);
System.out.printf("%d exceptions in %d days (expected %.1f)%n",
        result.exceptions(), result.observations(), result.expectedExceptions());

// Kupiec POF: is the exception FREQUENCY right? (two-sided: too few also fails)
System.out.printf("Kupiec LR=%.2f p=%.3f -> calibrated=%b%n",
        result.kupiecStatistic(), result.kupiecPValue(), result.calibrated(0.05));

// Christoffersen: do exceptions CLUSTER? (the crisis-blindness test)
System.out.printf("independence LR=%.2f p=%.3f -> independent=%b%n",
        result.independenceStatistic(), result.independencePValue(),
        result.independent(0.05));

// The joint verdict, and the Basel one-pager on the same count:
boolean pass = result.passes(0.05);      // conditional coverage, chi-square(2)
var zone = FrtbEs.TrafficLight.of(result.exceptions());  // over a 250d window
```

Kupiec alone is gameable: a model that shows zero exceptions for 200 days and
then five in a row can look calibrated on frequency while being useless when
it matters -- the Christoffersen statistic catches exactly that clustering by
testing the transition probabilities between exception and non-exception
days. Reject on the joint p-value, and note the two-sided Kupiec: too FEW
exceptions also rejects, because an over-conservative VaR is misallocated
capital, not prudence.

## 99. Estimate factor premia honestly

The IC (recipe 3) says a factor ranks returns; Fama-MacBeth says what an
exposure is WORTH per period, in return space, with standard errors that
survive cross-sectional correlation. Calendar anomalies get the same
treatment: a profile is decoration until its t-stat says otherwise.

```java
// exposures[t][asset][factor]: loadings KNOWN at t (NaN = not in the
// cross-section); forwardReturns[t][asset]: realized AFTER t.
var fm = FamaMacBeth.fit(exposures, forwardReturns);
for (int k = 0; k < fm.premia().length; k++) {
    System.out.printf("factor %d: premium %.4f/period (t=%.2f)%n",
            k, fm.premia()[k], fm.tStats()[k]);
}
System.out.printf("intercept %.4f (t=%.2f) over %d cross-sections%n",
        fm.interceptMean(), fm.interceptTStat(), fm.periodsUsed());
// |t| > 2: priced. A SIGNIFICANT INTERCEPT is the louder finding:
// returns exist that your factors do not explain.

// Calendar seasonals, with the significance attached:
var dow = CalendarAnomalies.dayOfWeek(returns, epochMillis);   // Mon=0..Sun=6
var tom = CalendarAnomalies.turnOfMonth(returns, epochMillis, 3, 2);
System.out.printf("turn-of-month %.4f vs %.4f (Welch t=%.2f)%n",
        tom.insideMean(), tom.outsideMean(), tom.tStat());
```

Fama-MacBeth's entire point is the second pass: each period contributes one
premium estimate per factor, so the t-stat comes from the time series of
premia and cross-sectional correlation between assets -- the thing that wrecks
a pooled regression's standard errors -- is absorbed by construction. The
stated simplifications: plain t-stats (no Newey-West, so autocorrelated
premia inflate them) and calendar-day turn-of-month windows. Treat |t| < 2 as
decoration, and remember most published calendar anomalies died after
publication -- re-test out of sample (`AlphaValidation`) before believing
anything.

## 100. The graduation recipe: run the full pipeline end to end

Everything a systematic desk does fits on one line -- alpha discovery ->
signal -> validation -> sizing -> execution (LEARN.md section 8c) -- and every
arrow is a place where money is made or quietly lost. This is the whole
library in one compact, real pass.

```java
// 1. DISCOVERY: the candidate set. K = 3 trials -- every honesty statistic
//    downstream must know that number.
AlphaContext ctx = AlphaContext.of(alignedSeriesBySymbol).withUniverse(universe);
List<AlphaFactor> candidates = List.of(Factors.momentum(252, 21),
        Factors.meanReversion(21), Factors.lowVolatility(63));

// 2. CHEAP FILTER: IC before any capital-weighted number.
for (AlphaFactor f : candidates) {
    System.out.println(SignalEvaluator.evaluate(ctx, f, 260, 21).format());
}
AlphaFactor winner = candidates.get(0);          // momentum survives, say

// 3. VALIDATION: purged folds gate any parameter fitting; PBO grades the
//    SELECTION process itself (candidateReturns: one column per candidate).
var folds = PurgedKFold.splits(ctx.bars(), 5, 21, ctx.bars() / 100);
var pbo = OverfitProbability.cscvSharpe(candidateReturns, 8);
if (pbo.pbo() >= 0.5) return;   // the "best" candidate is noise-mining

// 4. SIZING: scores -> z-weights -> neutral -> risk-budgeted.
double[] scores = winner.scores(ctx, t);
double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05);
w = PortfolioConstruction.betaNeutralize(w,
        PortfolioConstruction.trailingBetas(ctx, t, 63));
w = PortfolioConstruction.inverseVolBudget(w,
        PortfolioConstruction.trailingVols(ctx, t, 63), 1.0);

// 5. EXECUTION: each target becomes a parent worked to a benchmark.
long qty = Math.round(Math.abs(w[i]) * equity / price);
BenchmarkExecutor exec = BenchmarkExecutor.of(w[i] > 0 ? Side.BUY : Side.SELL,
        qty, BenchmarkExecutor.Benchmark.VWAP);
while (!exec.done()) {
    long child = exec.dueQuantity(elapsed,
            BenchmarkExecutor.MarketState.neutral(mid, elapsed));
    // route the child (recipe 10 wires live alpha/vol/depth); report fills:
    exec.onFill(filled);
}
```

Each stage defends against a specific failure: the point-in-time universe
kills survivorship bias at discovery; non-overlapping IC windows keep the
t-stat honest; purging and the embargo stop the label leak ordinary K-fold
has on financial data; PBO asks whether picking the best of K was itself
noise-mining; beta-neutralization and inverse-vol budgeting stop the book
being a leveraged market bet in disguise; and the executor turns targets into
children the market can absorb. Skip a stage and the backtest improves -- that
is the tell, not a reward. LEARN.md 8c walks the same line with the math
attached; recipes 3, 4, 10 and 20 expand the individual stages.

## 101. Bootstrap a credit curve and price a CDS

The credit market's yield-curve bootstrap: walk the CDS quotes from short to
long, at each pillar solving for the one hazard rate that reprices that
maturity to zero upfront. Every input reprices exactly -- that is the
contract, and `CreditTest` pins it at 1e-10.

```java
YieldCurve discount = YieldCurve.ofZeroRates(
        new double[]{1, 2, 3, 5, 7, 10},
        new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});
int[] tenors = {1, 3, 5, 7};
double[] parSpreads = {0.008, 0.012, 0.015, 0.016};    // 80..160bp, upward
CreditCurve curve = CreditCurve.bootstrap(tenors, parSpreads, 0.40, discount);

// The bootstrap's contract: every quoted pillar reprices to its input.
System.out.println(CdsPricer.parSpread(curve, discount, 5));   // 0.015...

// Post-2009 contracts run a FIXED 100bp coupon; the risk difference is
// exchanged as upfront points (positive = protection buyer pays).
double upfront = CdsPricer.upfront(curve, discount, 0.01, 5);
double annuity = CdsPricer.riskyAnnuity(curve, discount, 5);   // risky DV01 base

// The credit triangle: par spread ~ hazard * (1 - recovery).
System.out.printf("h(5y)=%.4f  triangle h=%.4f  Q(5y)=%.4f%n",
        curve.hazard(5), 0.015 / (1 - 0.40), curve.survivalProbability(5));
```

The risky annuity is the desk's risky DV01 -- PnL per basis point of spread
move -- which is why `upfront == (parSpread - coupon) * annuity` to machine
precision. The triangle `spread ~ h * (1 - R)` is only exact on a flat curve,
but it is the mental arithmetic every credit desk carries: a 150bp name at
40% recovery defaults at about 2.5% a year. A quote no hazard in [1e-9, 10]
can explain throws instead of returning the bracket edge -- the house rule
for every solver here.

## 102. Measure a bond's credit spread and the CDS-bond basis

The Z-spread strips the entire risk-free curve out of a bond's price; what
remains is compensation for credit and liquidity. Triangulate it against the
same name's CDS and you have the classic relative-value number.

```java
YieldCurve govt = YieldCurve.ofZeroRates(
        new double[]{1, 2, 3, 5, 7, 10},
        new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});

// A 5y 5% semi-annual bond trading 5% below its on-curve PV:
double onCurve = CreditSpreads.priceWithZSpread(100, 0.05, 2, 5, govt, 0);
double dirty = onCurve * 0.95;
double z = CreditSpreads.zSpread(dirty, 100, 0.05, 2, 5, govt);

// Round trip: the solved z reprices the bond exactly.
System.out.println(dirty
        - CreditSpreads.priceWithZSpread(100, 0.05, 2, 5, govt, z)); // ~0

// The same name's CDS quotes 120bp at 5y: the basis is z minus par.
CreditCurve cds = CreditCurve.bootstrap(
        new int[]{5}, new double[]{0.012}, 0.40, govt);
double basis = z - CdsPricer.parSpread(cds, govt, 5);
System.out.printf("z=%.1fbp  basis=%.1fbp%n", z * 1e4, basis * 1e4);
```

A yield spread compares one bond's YTM to one government point and mixes
curve shape into the number; the Z-spread removes the whole curve first.
Negative basis (bond spread above CDS) is the famous trade: buy the bond, buy
protection, collect the difference -- in theory risk-free, in 2008 a funding
catastrophe, which is why the basis persists instead of being arbitraged
away. Both solvers are bracket-checked: a price no spread in [-50%, 500%]
can explain throws rather than returning an endpoint.

## 103. Read a commodity curve: roll yield and implied carry

A commodity position's PnL is mostly not about spot. A curve in contango
charges the long every roll; backwardation pays it. Over a decade the roll
term has dominated most commodity index returns -- the single most
misunderstood fact about the asset class.

```java
// WTI-style contango: spot 100, deferred contracts above.
CommodityCurve wti = CommodityCurve.of(100,
        new double[]{0.25, 0.5, 1.0}, new double[]{101, 102, 104});
System.out.println(wti.isContango());                    // true
double roll = wti.annualizedRollYield(0.25, 1.0);        // negative: long pays
double carry = wti.impliedCarry(1.0, 0.03);              // u - y (storage minus
                                                         // convenience), cc

// Heating-oil-style backwardation: the market pays to HOLD the physical.
CommodityCurve heat = CommodityCurve.of(100,
        new double[]{0.25, 0.5, 1.0}, new double[]{99, 97, 95});
System.out.printf("roll=%.2f%%  carry=%.2f%%  paid-to-roll=%.2f%%%n",
        roll * 100, carry * 100, heat.annualizedRollYield(0.25, 1.0) * 100);
```

`impliedCarry` backs storage-minus-convenience out of the storage-arbitrage
relation `F = S * exp((r + u - y) t)`: deeply negative means a convenience
yield -- think heating oil before a cold snap, or the USO fund's 2020 lesson
in the other direction (spot oil recovered; the contango roll ate the fund
anyway). Whole-curve shape tests are strict at every pillar pair, so seasonal
commodities (natural gas winters) read as neither contango nor backwardation
by design -- use pairwise roll yields there. When it is time to actually move
the position to the next contract, `execution.FuturesRollAlgo` (recipe 15's
sibling) executes the roll along the liquidity-migration S-curve as calendar
spreads.

## 104. Price and risk a vanilla swap

The missing middle between the curve (bootstrapped FROM par swaps) and
`RatesOptions` (options ON forward swaps): the PV, par rate and DV01 of an
actual position, in the single-curve world where the float leg is worth par
at inception.

```java
double[] t = {1, 2, 3, 4, 5, 7, 10};
double[] zeros = {0.05, 0.05, 0.05, 0.05, 0.05, 0.05, 0.05};
YieldCurve curve = YieldCurve.ofZeroRates(t, zeros);

double par = SwapPricer.parRate(curve, 5);      // (1 - DF(5)) / annuity
System.out.println(SwapPricer.payerPv(curve, 5, par));   // 0.0 -- an identity

// Pay 4% fixed on a 5% curve: positive PV to the payer, per unit notional.
double pv = SwapPricer.payerPv(curve, 5, 0.04);
double dv01 = SwapPricer.dv01(curve, 5, 0.04);  // +1bp parallel zero shift
double annuity = SwapPricer.annuity(curve, 5);
System.out.printf("pv=%.5f  dv01=%.6f  annuity*1bp=%.6f%n",
        pv, dv01, annuity * 1e-4);
```

A swap struck at the par rate must PV to zero -- pinned at 1e-12, not
approximately. The DV01 subtlety worth knowing: the bump hits the
continuously-compounded ZERO curve, and the simple par rate is `e^z - 1`, so
a 1bp zero shift moves the par rate by `e^z` bp -- the measured DV01 is
`annuity * e^z * 1bp` on a flat curve, about 5% above the naive
`annuity * 1bp` at 5% rates (`AssetClassRoundTest` pins exactly this).
Quoting risk without saying which curve you bumped is how two desks disagree
about the same trade.

## 105. Run private-markets analytics like an LP

No daily prices, manager-timed cash flows, appraisal NAVs: the usual
machinery fails on purpose in private markets. The LP toolkit: money-weighted
IRR, the multiples, a fair public-market benchmark, and desmoothed risk.

```java
// Investor-signed flows: contributions negative, distributions positive;
// the final period includes terminal NAV as a distribution.
double irr = PrivateMarketAnalytics.irr(
        new double[]{-100, -50, 30, 60, 130});             // per period

double tvpi = PrivateMarketAnalytics.tvpi(150, 90, 105);   // (90+105)/150 = 1.3
double dpi  = PrivateMarketAnalytics.dpi(150, 90, 105);    // cash back: 0.6
double rvpi = PrivateMarketAnalytics.rvpi(150, 90, 105);   // still appraisal: 0.7

// KS-PME: a fund that IS the index scores exactly 1 (the replication check).
double pme = PrivateMarketAnalytics.ksPme(
        new double[]{100, 0, 0}, new double[]{0, 0, 0}, 121,
        new double[]{100, 110, 121});                       // 1.0 exactly

// Appraisal NAVs understate volatility; desmooth before comparing risk.
double[] trueReturns = PrivateMarketAnalytics.geltnerDesmooth(navReturns, 0.4);
```

DPI is the honest multiple -- you cannot spend RVPI. PME > 1 is the only fair
"did they beat the market" answer for irregular cash flows: it grows every
flow forward at the index's return, so "our IRR beat the S&P's return" stops
being evidence. IRR itself is bracket-checked -- flows that never change sign
have no IRR and throw. And the Geltner inversion is exact (smoothing then
desmoothing round-trips to machine precision, pinned by test), so compare the
standard deviation of `trueReturns` against the observed series: the gap is
the volatility the appraisal process laundered away.


## 106. Back-adjust through a reverse split and a special dividend

A 1-for-10 reverse split multiplies the raw price tenfold on one date and a special dividend bleeds a spurious drop on its ex-date; both are corporate mechanics, not returns. `CorporateActions.adjust` rewrites the earlier history with CRSP-style multiplicative factors so nothing false survives into the backtest.

```java
BarSeries raw = CsvBarLoader.load(Path.of("ticker_raw.csv"), "TICK");

long splitEx = LocalDate.of(2023, 5, 15)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
long divEx = LocalDate.of(2023, 9, 12)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

BarSeries adjusted = CorporateActions.adjust(raw, List.of(
        // A reverse split IS a split with ratio < 1: 1-for-10 = 0.1.
        new CorporateActions.CorporateAction(
                splitEx, CorporateActions.Type.SPLIT, 0.1),
        new CorporateActions.CorporateAction(
                divEx, CorporateActions.Type.CASH_DIVIDEND, 1.50)));  // $ per share

System.out.printf("raw first close %.2f -> adjusted %.2f%n",
        raw.close(0), adjusted.close(0));
```

The split path is symmetric in the ratio: a forward split divides prior prices and multiplies prior volume, so a reverse split (ratio 0.1) multiplies prices by ten and divides volume by ten -- exactly reversing the ticker's mechanical jump. The dividend path scales prior prices by `(prevClose - d) / prevClose`, and the record's constructor rejects a non-positive value, so a fat-fingered negative dividend fails loudly at construction rather than producing negative prices deep in the adjusted history.

## 107. Align three vendors and reconcile their closes

Three data vendors rarely agree bar-for-bar: different holidays, different corporate-action timing, occasional bad prints. `SeriesAligner.intersect` puts them on one shared timeline so a reconciliation pass can compare like with like -- the disagreement is the data-quality signal.

```java
Map<String, BarSeries> vendors = new LinkedHashMap<>();
vendors.put("VENDOR_A", CsvBarLoader.load(Path.of("a.csv"), "SPY"));
vendors.put("VENDOR_B", CsvBarLoader.load(Path.of("b.csv"), "SPY"));
vendors.put("VENDOR_C", CsvBarLoader.load(Path.of("c.csv"), "SPY"));

// Only timestamps every vendor carries survive -- the reconcilable set.
Map<String, BarSeries> aligned = SeriesAligner.intersect(vendors);
BarSeries a = aligned.get("VENDOR_A");
BarSeries b = aligned.get("VENDOR_B");
BarSeries c = aligned.get("VENDOR_C");

double worst = 0;
int worstBar = -1;
for (int i = 0; i < a.size(); i++) {
    double hi = Math.max(a.close(i), Math.max(b.close(i), c.close(i)));
    double lo = Math.min(a.close(i), Math.min(b.close(i), c.close(i)));
    double spreadBps = (hi - lo) / lo * 1e4;
    if (spreadBps > worst) { worst = spreadBps; worstBar = i; }
}
System.out.printf("%d aligned bars, worst cross-vendor close spread %.1f bps at %s%n",
        a.size(), worst, Instant.ofEpochMilli(a.timestamp(worstBar)));
```

`intersect` is the right policy here precisely because it deletes rows: reconciliation needs the vendors index-aligned, and a bar one vendor is missing cannot be reconciled at all. A worst-spread of a fraction of a basis point is rounding; tens of basis points on a single date is usually one vendor applying a split a day early -- feed that date to recipe 106 rather than trusting any single feed.

## 108. Build a point-in-time universe in code, delisting returns and all

When the membership history lives in your own database rather than a CSV, build the `PointInTimeUniverse` directly: memberships, an involuntary delisting at the Shumway haircut, and a cash-plus-stock merger -- the same survivorship-honest object recipe 31 loads from disk.

```java
long day(int y, int m, int d) {
    return LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
}

PointInTimeUniverse universe = new PointInTimeUniverse()
        .addMembership("AAPL", day(2005, 1, 3))                       // still a member
        .addMembership("LEH", day(2005, 1, 3), day(2008, 9, 15))
        .addMembership("TWX", day(2005, 1, 3), day(2018, 6, 15));

// Lehman: proceeds unknown -> the -30% Shumway convention, not a silent exit.
universe.recordDelisting("LEH", day(2008, 9, 15),
        PointInTimeUniverse.DEFAULT_INVOLUNTARY_DELISTING_RETURN);
// Time Warner: $48.53 cash plus 0.5471 AT&T shares per share.
universe.recordMerger("TWX", day(2018, 6, 15), 48.53, 0.5471, "T");

long asOf = day(2008, 6, 2);
System.out.println(universe.membersAsOf(asOf));      // [AAPL, LEH, TWX]
System.out.println(universe.isMember("LEH", asOf));  // true -- not dead yet

PointInTimeUniverse.TerminalEvent leh = universe.terminalEvent("LEH");
System.out.printf("LEH ends %s with return %.0f%%%n",
        leh.type(), leh.delistingReturn() * 100);
```

The programmatic path enforces the same invariants as the loader: `recordDelisting` refuses a return below -100% (you cannot lose more than the position), `recordMerger` refuses a stock component with no acquirer symbol, and a second terminal event on the same name throws rather than overwriting the first. Hand the finished universe to the universe-aware `PortfolioBacktester` overload, which charges the -30% when a held name dies instead of quietly marking it out at the last print.

## 109. Detect and fix swapped high/low bars before they poison a series

A vendor that transposes the high and low columns on a handful of rows produces bars that violate `high >= low` -- and `Bar`'s constructor throws on exactly those. Repair the raw rows first, then let that same invariant be the backstop that proves the cleaned series is sound.

```java
// Raw vendor rows: {timestamp, open, high, low, close, volume}. Row 1 is swapped.
double[][] rows = {
        {1.0, 100.0, 101.5, 99.7, 101.2, 1200},
        {2.0, 101.2, 100.8, 102.9, 102.1, 1300},   // high 100.8 < low 102.9: swapped
        {3.0, 102.1, 103.4, 101.9, 103.0, 1400},
};

BarSeries.Builder clean = BarSeries.builder("ACME");
int repaired = 0;
for (double[] r : rows) {
    double high = r[2], low = r[3];
    if (high < low) { double t = high; high = low; low = t; repaired++; }  // swap back
    // Restore the OHLC envelope in case open/close now sit outside [low, high].
    high = Math.max(high, Math.max(r[1], r[4]));
    low = Math.min(low, Math.min(r[1], r[4]));
    clean.add((long) r[0], r[1], high, low, r[4], r[5]);
}
BarSeries series = clean.build();   // every Bar() ctor re-checks high >= low
System.out.printf("repaired %d swapped bars, %d clean bars%n", repaired, series.size());
```

The design leans on `Bar`'s own contract rather than a bolted-on validator: because the record throws whenever `high < low`, a repair pass that missed a row could never build the series -- the exception is the test. Widening the range to enclose `open` and `close` catches the softer corruption a plain swap leaves behind (a real high must be at least the open and the close), so the cleaned bar is internally consistent, not merely un-swapped.

## 110. Stream indicators live and prove batch parity to the last digit

The batch `Indicators` compute whole-array studies for research; `StreamingIndicators` are their O(1)-per-tick twins. Run both over the same closes and diff the last value: identical seeding means identical output, so what you backtested is exactly what trades live.

```java
BarSeries s = CsvBarLoader.load(Path.of("bars.csv"), "SPY");
double[] close = s.closes();

double[] sma20 = Indicators.sma(close, 20);
double[] ema20 = Indicators.ema(close, 20);
double[] rsi14 = Indicators.rsi(close, 14);
Indicators.Macd macd = Indicators.macd(close, 12, 26, 9);

var sma = new StreamingIndicators.Sma(20);
var ema = new StreamingIndicators.Ema(20);
var rsi = new StreamingIndicators.Rsi(14);
var macdLive = new StreamingIndicators.Macd(12, 26, 9);
var vwapLive = new StreamingIndicators.Vwap();
for (int i = 0; i < s.size(); i++) {
    sma.update(close[i]);
    ema.update(close[i]);
    rsi.update(close[i]);
    macdLive.update(close[i]);
    vwapLive.update(s.bar(i).typicalPrice(), s.volume(i));   // VWAP eats typical price
}
int last = s.size() - 1;
System.out.printf("SMA %.6f=%.6f  EMA %.6f=%.6f  RSI %.4f=%.4f  MACD %.6f=%.6f%n",
        sma20[last], sma.value(), ema20[last], ema.value(),
        rsi14[last], rsi.value(), macd.line()[last], macdLive.line());
```

Five streaming twins cover the studies a live strategy actually recomputes every tick -- SMA, EMA, RSI, MACD, VWAP -- and each seeds identically to its batch sibling (the EMA off an SMA of its first `period` values, the RSI with Wilder smoothing), so the printed pairs match value for value including the warm-up NaNs. The one input contract is VWAP's: the batch version feeds the typical price `(high + low + close) / 3`, so the streaming one must too -- `bar(i).typicalPrice()` is that number by definition. Keep the twenty-plus batch studies for research and the `dsl` rules of recipe 111; reach for the streaming five inside the HFT loop where an array-per-tick is not an option.

## 111. Build a golden-cross strategy in the DSL

`StrategyBuilder` composes indicator-array rules into a backtestable strategy without a class -- the canonical 50/200 golden cross, filtered so it only trades the signal the textbook actually describes.

```java
BarSeries series = CsvBarLoader.load(Path.of("bars.csv"), "SPY");
double[] close = series.closes();
double[] sma50 = Indicators.sma(close, 50);
double[] sma200 = Indicators.sma(close, 200);
double[] rsi14 = Indicators.rsi(close, 14);

BacktestResult result = StrategyBuilder.named("Golden cross, not overbought")
        .enterWhen(Rules.crossAbove(sma50, sma200)      // 50 crosses up through 200
                .and(Rules.belowValue(rsi14, 75)))      // ...but not into a blow-off
        .exitWhen(Rules.crossBelow(sma50, sma200))      // the death cross closes it
        .withStopLoss(0.05)                             // 5% of entry
        .withTakeProfit(0.20)                           // 20% of entry
        .build()
        .backtest(series, 100_000);

System.out.println(result.metrics());   // Sharpe, CAGR, maxDD, trades...
```

The cross combinators encode the golden cross honestly: `crossAbove` fires only when bar `i-1` had the fast average at or below the slow and bar `i` has it strictly above, so a series that opens with 50 already over 200 does not print a phantom cross on bar 0, and the 200-bar warm-up NaNs satisfy nothing. The built `DslStrategy` is an ordinary `TradingStrategy`, so the same object drops into `Backtester.run`, the walk-forward analyzer of recipe 46, or any custom `BacktestConfig`.

## 112. Screen for value, quality and momentum, then rank

The classic three-sleeve equity screen: hard filters remove the ineligible, then `RankingEngine` blends normalized value, quality and momentum into one score. Filter first, rank second -- the order is load-bearing.

```java
List<StockSnapshot> universe = List.of(
        new StockSnapshot("AAPL", aaplSeries,
                new Fundamentals(3.4e12, 33, 45, 6.4, 0.45, 0.005, 1.8)),
        new StockSnapshot("XOM", xomSeries,
                new Fundamentals(4.5e11, 13, 1.9, 8.9, 0.15, 0.033, 0.2)),
        new StockSnapshot("JPM", jpmSeries,
                new Fundamentals(5.5e11, 11, 1.6, 16.2, 0.17, 0.024, 1.2)));
        // fields: marketCap, peRatio, pbRatio, eps, roe, divYield, debtToEquity

var ranked = new StockScreener(universe).screenAndRank(
        new RankingEngine()
                .addCriterion("value(P/E)", -1.0, s -> s.fundamentals().peRatio())
                .addCriterion("quality(ROE)", 1.0, s -> s.fundamentals().roe())
                .addCriterion("momentum(12m)", 0.8,
                        s -> s.lastClose() / s.series().close(0) - 1),
        FundamentalFilters.marketCapAbove(50e9),
        FundamentalFilters.debtToEquityBelow(2.0),
        TechnicalFilters.priceAboveSma(200));

ranked.forEach(r -> System.out.printf("%-6s %.3f%n", r.stock().symbol(), r.score()));
StockScreener.exportCsv(Path.of("screen.csv"), ranked);
```

`RankingEngine` min-max-normalizes each criterion across the surviving candidates before the weighted blend, so a single distressed name with a 200 P/E left in the pool would compress everyone else's value spread toward zero -- the filters exist to evict that name before it distorts the normalization. A negative weight inverts a criterion (cheaper P/E ranks higher) with no separate flag, and `Fundamentals.unknown()` fields never satisfy a filter and score a neutral 0.5, so a name with missing data cannot rank first by accident. Scores are relative to this run's candidates -- ideal for "today's best names", meaningless for tracking one name through time.

## 113. Fit a gradient-boosted alpha behind an out-of-sample gate

`GradientBoostedRegressor` is XGBoost-style additive boosting over stumps in pure Java. Train on the past, score on the future, and let the out-of-sample RMSE -- not the training fit -- decide whether the signal is worth trading.

```java
double[] r = CsvBarLoader.load(Path.of("bars.csv"), "SPY").returns();

int lookback = 21, n = r.length - lookback;
double[][] x = new double[n][4];
double[] y = new double[n];
for (int t = lookback; t < r.length; t++) {
    double m5 = 0, m21 = 0, v = 0;
    for (int j = t - 5; j < t; j++)  m5  += r[j] / 5;
    for (int j = t - 21; j < t; j++) m21 += r[j] / 21;
    for (int j = t - 21; j < t; j++) v += (r[j] - m21) * (r[j] - m21) / 20;
    x[t - lookback] = new double[]{r[t - 1], m5, m21, Math.sqrt(v)};
    y[t - lookback] = r[t];
}

int split = (int) (n * 0.8);   // chronological: never train on the future
GradientBoostedRegressor model = GradientBoostedRegressor.withDefaults()
        .fit(Arrays.copyOfRange(x, 0, split), Arrays.copyOfRange(y, 0, split));

double oosRmse = model.rmse(
        Arrays.copyOfRange(x, split, n), Arrays.copyOfRange(y, split, n));
double baseline = Math.sqrt(Arrays.stream(Arrays.copyOfRange(y, split, n))
        .map(a -> a * a).average().orElse(0));   // predict-zero RMSE
System.out.printf("OOS RMSE %.5f vs zero-forecast %.5f -> %s%n",
        oosRmse, baseline, oosRmse < baseline ? "trade it" : "reject");
double signal = model.predict(x[n - 1]);   // today's alpha, in return units
```

`withDefaults()` is 200 rounds at learning rate 0.1; each round fits the SSE-optimal stump across all four features, so the model learns thresholds and simple interactions but nothing a thousand-tree forest would -- at daily-alpha signal-to-noise that ceiling is a feature. Beating the predict-zero baseline out of sample is the minimum bar, not the full one: before sizing on `signal`, wrap it in an `AlphaFactor` and run the `alpha` package's IC and PBO defenses, and cross-validate with `PurgedKFold` -- ordinary K-fold leaks forward-return labels across the fold seam.

## 114. Forecast volatility with ML and GARCH side by side

Fit `Garch11` by maximum likelihood and the boosted `VolatilityForecaster` on the same returns, then read their next-week vol forecasts together. When two honest models built on different assumptions disagree, the disagreement is itself the signal.

```java
double[] returns = CsvBarLoader.load(Path.of("spx.csv"), "SPX").returns();

Garch11.Params garch = Garch11.fit(returns);
double garchDaily = Math.sqrt(Garch11.forecastVariance(returns, garch, 5));

VolatilityForecaster ml = VolatilityForecaster.weekly().fit(returns);   // horizon 5
double mlDaily = ml.forecast(returns);

System.out.printf("GARCH: alpha %.3f beta %.3f persistence %.3f uncond %.2f%%%n",
        garch.alpha(), garch.beta(), garch.persistence(),
        Math.sqrt(garch.unconditionalVariance()) * 100);
System.out.printf("daily vol forecast: GARCH %.2f%% vs ML %.2f%%  (ML risk %.0f/100)%n",
        garchDaily * 100, mlDaily * 100, ml.riskScore(returns));
```

The two numbers answer subtly different questions: `forecastVariance(returns, params, 5)` is GARCH's variance for day T+5 specifically, mean-reverting from tomorrow's conditional variance toward the unconditional level at the persistence rate, while the weekly forecaster predicts realized standard deviation over the whole next five days. GARCH is three parameters and decades of theory -- hard to overfit, easy to interpret; the tree model exploits asymmetries and shock features GARCH cannot see, which is why it ships `riskScore`'s percentile framing to make its raw output legible. When they diverge sharply, believe neither blindly -- recent data holds a regime shift (recipe 115) at least one is extrapolating badly.

## 115. Detect volatility regimes with a two-state HMM

`RegimeDetector` fits a two-state Gaussian hidden Markov model by Baum-Welch EM and answers the three questions a vol-targeting overlay asks: which regime is today, how confident, and how long regimes persist.

```java
double[] returns = CsvBarLoader.load(Path.of("spx.csv"), "SPX").returns();

RegimeDetector.RegimeModel model = RegimeDetector.fit(returns, 200);

// State 1 is always the high-volatility regime, by construction.
System.out.printf("calm vol %.1f%%, turbulent vol %.1f%% (annualized)%n",
        model.stdDevs()[0] * Math.sqrt(252) * 100,
        model.stdDevs()[1] * Math.sqrt(252) * 100);
System.out.printf("today: regime %d, P(turbulent) %.2f%n",
        model.currentRegime(), model.currentProbabilities()[1]);
System.out.printf("persistence: calm %.0f days, turbulent %.0f days%n",
        model.expectedDuration(0), model.expectedDuration(1));

double[] pTurbulent = model.smoothedHighVolProbability();   // charts/hindcast only
```

Two arrays serve different masters and must never be swapped: `currentProbabilities` is the filtered estimate using data up to today -- the only one a live decision may read -- while `smoothedHighVolProbability` conditions on the entire sample including the future, so it belongs in plots and hindcasts (a backtest that de-levers on the smoothed series is quietly clairvoyant). `expectedDuration(state)` is `1 / (1 - pStay)` from the fitted transition matrix, the honest answer to "if I cut risk now, for roughly how long"; the 200-iteration cap is cheap insurance for a stable fit.

## 116. Flag quote stuffing with robust MAD scores

`AnomalyDetector` screens interval-aggregated activity for the two surveillance classics -- quote stuffing (message storms with almost no trading) and price spikes -- using median/MAD z-scores that an outlier cannot hide inside.

```java
long[] messages = {410, 395, 402, 388, 415, 391, 404,
                   399, 9_874, 401, 397, 407};      // orders + cancels + replaces
long[] trades   = {21, 19, 24, 18, 22, 20, 23, 19, 3, 22, 20, 21};
double[] mids   = {100.02, 100.03, 100.01, 100.04, 100.02, 100.05, 100.03,
                   100.02, 100.04, 99.38, 99.40, 99.39, 99.41};

// Message rate a 3-sigma outlier AND order-to-trade ratio >= 50.
List<AnomalyDetector.Anomaly> stuffing =
        AnomalyDetector.detectQuoteStuffing(messages, trades, 3.0, 50.0);
List<AnomalyDetector.Anomaly> spikes =
        AnomalyDetector.detectPriceSpikes(mids, 3.0);

for (AnomalyDetector.Anomaly a : stuffing) {
    System.out.printf("interval %d: %s z=%.1f%n", a.intervalIndex(), a.type(), a.score());
}
spikes.forEach(a -> System.out.printf("interval %d: %s z=%.1f%n",
        a.intervalIndex(), a.type(), a.score()));
```

The stuffing test deliberately requires both conditions: a message spike alone is a busy open, a high order-to-trade ratio alone is a quiet market maker -- only the combination (heavy quoting, almost no trading, far above baseline) is the pattern. The scores are robust z-scores, `(x - median) / (1.4826 * MAD)`, precisely so a storm cannot inflate the very statistic measuring it: a mean/stdev z on this twelve-point window would cap near `(n-1)/sqrt(n)` and a 3-sigma threshold could never fire, while median/MAD tolerates up to half the window being contaminated and lets a genuine storm score in the hundreds. Treat the `score` as triage priority for a human, not a verdict.

## 117. Monte-Carlo a portfolio over 100k paths

`MonteCarloSimulator` runs a hundred thousand GBM scenarios across all cores -- deterministic for a given seed regardless of thread scheduling -- and `SimulationResult` turns the terminal values into the numbers a risk meeting needs.

```java
MonteCarloSimulator sim = new MonteCarloSimulator(42);   // seed -> reproducible

// Single asset: $1M, one year, 7% drift, 16% vol, 252 steps, 100k paths.
SimulationResult r = sim.simulate(1_000_000, 0.07, 0.16, 252, 100_000);
double[] ci90 = r.confidenceInterval(0.90);
System.out.printf("median %,.0f  mean %,.0f  P(profit) %.1f%%%n",
        r.medianValue(), r.expectedValue(), r.probabilityOfProfit() * 100);
System.out.printf("90%% CI [%,.0f .. %,.0f]  VaR95 %.1f%%  CVaR95 %.1f%%%n",
        ci90[0], ci90[1], r.valueAtRisk(0.95) * 100, r.conditionalValueAtRisk(0.95) * 100);

// Correlated 60/40 book: weights, daily means, daily covariance.
SimulationResult multi = sim.simulatePortfolio(1_000_000,
        new double[]{0.6, 0.4},
        new double[]{0.00035, 0.00012},
        new double[][]{{1.0e-4, 1.2e-5}, {1.2e-5, 2.5e-5}},
        252, 100_000);
System.out.println(multi);   // one-line summary with VaR/CVaR baked in
```

Determinism under parallelism is the engineering point: each path draws from its own substream seeded by the run seed and the path index, so path 71,342 produces identical returns whether it lands on core 0 or core 15, and yesterday's numbers reproduce exactly. On the finance side, remember what GBM assumes away -- fat tails, vol clustering, crisis correlation spikes -- so read `valueAtRisk` and `conditionalValueAtRisk` as lower bounds on how bad things get, and stress the covariance toward crisis correlations in the multi-asset variant to watch the tail respond.

## 118. Turn a best-execution review into a compliance report in four formats

Build one format-agnostic `Report` from a `BestExecutionAnalyzer` summary, then export the identical model to HTML, PDF, XLSX and CSV -- the four artifacts a compliance file needs, guaranteed to agree because they render the same sections.

```java
var bx = new BestExecutionAnalyzer()
        .add(new BestExecutionAnalyzer.OrderOutcome("o1", "EXCH_A", Side.BUY, 10_000,
                100.00, 100.02, 85_000, true))
        .add(new BestExecutionAnalyzer.OrderOutcome("o2", "EXCH_B", Side.BUY, 8_000,
                100.05, 100.05, 120_000, true))
        .add(new BestExecutionAnalyzer.OrderOutcome("o3", "DARK_X", Side.SELL, 5_000,
                99.98, 0, 0, false));                       // unfilled: price ignored
BestExecutionAnalyzer.BestExecutionReport rep = bx.report();

Map<String, String> headline = new LinkedHashMap<>();
headline.put("Orders", String.valueOf(rep.totalOrders()));
headline.put("Fill rate", String.format("%.1f%%", rep.fillRate() * 100));
headline.put("Avg slippage", String.format("%.1f bps", rep.avgSlippageBps()));
headline.put("Median latency", String.format("%.1f ms", rep.medianLatencyToFillMillis()));

List<List<String>> venueRows = new ArrayList<>();
rep.avgSlippageBpsByVenue().forEach((v, bps) ->
        venueRows.add(List.of(v, String.format("%.1f", bps))));

Report report = Report.builder("Best-Execution Review Q3")
        .addKeyValueSection("Headline", headline)
        .addTableSection("Slippage by venue", List.of("Venue", "Avg bps"), venueRows)
        .build();

new HtmlReportExporter().export(report, Path.of("bestex.html"));
new PdfReportExporter().export(report, Path.of("bestex.pdf"));
new XlsxReportExporter().export(report, Path.of("bestex.xlsx"));
new CsvReportExporter().export(report, Path.of("bestex.csv"));
```

The split between the report model and its exporters is what keeps four formats honest with one another -- every exporter walks the same ordered sections, so the PDF a regulator reads cannot quietly disagree with the spreadsheet the desk keeps. Unfilled outcomes carry `filled=false` and their price and latency are ignored by construction, so a canceled order drags down the fill rate without corrupting the slippage average. For a chart, `addHtmlSection("...", SvgCharts.lineChart(values, 640, 200, "#2b8"))` renders in the HTML export only; the tabular exporters skip it rather than mangling markup into a cell.

## 119. Checkpoint a trading day and warm-start tomorrow

Everything the microstructure models learn intraday -- volume profiles, learned impact -- is exactly what a desk does not want to relearn from zero each morning. `Checkpoint` persists it as one atomic binary file of named sections.

```java
VolumeCurve volume = new VolumeCurve(78, 0.1);
KylesLambda impact = new KylesLambda(0.02);
// ... intraday: volume.onVolume(bucket, qty); impact.onSample(dMid, signedVol); ...

volume.rollDay();                                  // fold today into the profile
try (Checkpoint.Writer w = Checkpoint.writer(Path.of("state/eod.qflc"))) {
    w.section("volume.AAPL", volume::writeState)
     .section("impact.AAPL", impact::writeState);
}   // close() writes a temp file, then atomically renames over the old one

// Next session: same configuration, warm state.
VolumeCurve volume2 = new VolumeCurve(78, 0.1);
KylesLambda impact2 = new KylesLambda(0.02);
Checkpoint.Reader r = Checkpoint.reader(Path.of("state/eod.qflc"));
boolean warm = r.section("volume.AAPL", volume2::readState)
             & r.section("impact.AAPL", impact2::readState);
System.out.println(warm
        ? volume2.daysLearned() + " days profile, lambda " + impact2.lambda()
        : "cold start");
```

Every failure mode is designed to be loud. A crash mid-save leaves yesterday's file intact (temp file plus atomic rename, never a torn checkpoint); a section writer that throws poisons the whole writer so nothing half-written commits; restoring into a differently-configured instance (a different bucket count) throws rather than silently misaligning arrays; and a model that does not consume its exact payload is rejected as format drift. An absent section returns `false` -- the caller, not the library, decides whether that is a cold start or an error. Any model with a `writeState`/`readState` pair checkpoints this way, one section each, named `model.symbol`.

## 120. Measure latency percentiles honestly

`LatencyRecorder` is a zero-allocation nanosecond histogram safe to call on the hot path; `HiccupMonitor` runs beside it so you can tell whether a bad tail was your code or the platform underneath it.

```java
LatencyRecorder decode = new LatencyRecorder();
LatencyRecorder risk = new LatencyRecorder();

try (HiccupMonitor hiccups = new HiccupMonitor().start()) {   // 1ms park resolution
    for (int i = 0; i < 1_000_000; i++) {
        long a = System.nanoTime();
        // ... decode the market-data message ...
        long b = System.nanoTime();  decode.record(b - a);
        // ... run the pre-trade risk check ...
        risk.record(System.nanoTime() - b);
    }

    System.out.println("decode: " + decode.summary());   // p50/p90/p99/p99.9/max
    System.out.println("risk:   " + risk.summary());
    System.out.println(hiccups.summary());

    // Attribution: a platform stall longer than your worst sample owns the tail.
    if (hiccups.maxHiccupNanos() > risk.percentile(0.999)) {
        System.out.println("risk p99.9 is platform noise -- tune the JVM, not the code");
    }
}
```

`record` is a couple of array writes into HdrHistogram-style log-linear buckets (16 sub-buckets per power of two, roughly 6% worst-case quantile error) -- cheap enough to leave enabled in production, and single-writer by design, so give each measured path its own recorder as above. The hiccup monitor is the honesty half: a daemon thread repeatedly parks for a fixed interval and records how much longer than requested each park actually took, surfacing exactly the GC pauses, safepoint stalls and scheduler preemptions that corrupt percentiles without appearing in application code. Publishing a p99.9 without a matching hiccup profile is publishing a number you cannot defend.

## 121. Decode a Nasdaq ITCH feed into a full-depth L3 book

`ItchCodec` speaks the TotalView-ITCH 5.0 layout (add / execute / cancel / delete / replace / trade) as a zero-allocation flyweight; `L3BookBuilder` consumes the stream and rebuilds the venue's order-by-order book on your side of the wire. Prices are 0.0001 ticks, so 1_500_000 = $150.00.

```java
int locate = 42;                                    // the feed's symbol id for the day
long apple = ItchCodec.packStock("AAPL");
long ts = 34_200_000_000_000L;                      // 09:30 in nanos since midnight
byte[] wire = new byte[4096];                       // simulator side: encode a session
int w = 0;
w += ItchCodec.encodeAdd(wire, w, locate, ts, 1001L, ItchCodec.BUY, 500, apple, 1_499_900);
w += ItchCodec.encodeAdd(wire, w, locate, ts, 1002L, ItchCodec.SELL, 300, apple, 1_500_100);
w += ItchCodec.encodeExecuted(wire, w, locate, ts, 1002L, 100, 9001L);
w += ItchCodec.encodeReplace(wire, w, locate, ts, 1001L, 1003L, 500, 1_500_000);

L3BookBuilder book = new L3BookBuilder(locate, 1_400_000, 1_600_000, 1 << 16);
for (int p = 0; p < w; ) {
    int consumed = book.onMessage(wire, p);
    p += consumed > 0 ? consumed : ItchCodec.length(wire[p]);   // 0 = other symbol
}
System.out.printf("bid %d x %d | ask %d x %d, resting=%d, gaps=%d%n",
        book.bestBidTick(), book.bestBidSize(),
        book.bestAskTick(), book.bestAskSize(),
        book.restingOrders(), book.unknownRefCount());
```

The builder is the same engineering as `HftOrderBook` -- dense tick ladder, occupancy bitmaps, pooled nodes -- but driven by the feed rather than by matching, so a message loop is a single `onMessage` call per event with zero allocation. Watch two counters in production: a nonzero `unknownRefCount()` rate is the classic feed-gap symptom (a delete or execute arrived for a reference the book never saw -- resubscribe or reload a snapshot), and `outOfBandCount()` means the day's range escaped your price band and it needs widening.

## 122. Track your own order's exact queue position

Once your add appears on the L3 feed -- you learn its reference from the gateway ack -- `track` walks the level once and then maintains, in O(1) per event, exactly how many shares stand ahead of you. That number decides whether a passive child fills.

```java
L3BookBuilder book = new L3BookBuilder(1, 990_000, 1_010_000, 1 << 12);
book.onAdd(501L, Side.BUY, 400, 1_000_000);      // ahead of us
book.onAdd(502L, Side.BUY, 250, 1_000_000);      // ahead of us
book.onAdd(777L, Side.BUY, 100, 1_000_000);      // OUR order (ref from the ack)
book.onAdd(503L, Side.BUY, 900, 1_000_000);      // behind us

book.track(777L);                                // one FIFO walk, O(1) thereafter
System.out.println(book.sharesAhead(777L));      // 650

book.onExecute(501L, 400);                       // executions consume the head: 250
book.onCancel(503L, 900);                        // entered after us: still 250
book.onCancel(502L, 250);                        // entered before us: 0 -- we are next
System.out.println(book.sharesAhead(777L));      // 0
book.onExecute(777L, 100);                       // our fill: tracking ends itself
System.out.println(book.sharesAhead(777L));      // -1 (no longer tracked)
```

The O(1) maintenance rests on two facts of price-time priority: an execution at your level always happened at the front, ahead of you, and a cancel was ahead of you if and only if it entered the queue before you (its insertion sequence). This is the exact position, not an estimate; when your feed is only L2 and you cannot see individual orders, use the probabilistic `microstructure.QueuePositionEstimator` from recipe 153 instead.

## 123. Consolidate an NBBO and detect locked or crossed markets

`Nbbo` aggregates per-venue tops of book into the three things a smart order router consumes: the national best bid and offer, the size resting there, and a bitmask of which venues are quoting it -- and it fires the listener only when the inside actually changes.

```java
Nbbo nbbo = new Nbbo(3);                          // venues 0..2 (up to 64)
nbbo.listener((bid, bidSz, ask, askSz, ts) ->     // conflated to inside changes
        System.out.printf("NBBO %d (%d) / %d (%d)%n", bid, bidSz, ask, askSz));

long t = System.nanoTime();
nbbo.onVenueQuote(0, 999_900, 500, 1_000_100, 400, t);
nbbo.onVenueQuote(1, 1_000_000, 200, 1_000_200, 300, t);   // better bid
nbbo.onVenueQuote(2, 1_000_000, 100, 1_000_100, 250, t);   // joins both insides

System.out.println(nbbo.bidTick() + " x " + nbbo.bidSize());        // 1000000 x 300
System.out.println(Long.toBinaryString(nbbo.bidVenues()));          // 110 = venues 1,2
if (nbbo.locked() || nbbo.crossed()) {
    // stand down marketable routing: the tape is locked/crossed
}
System.out.printf("conflation ratio %.3f%n",
        nbbo.changeCount() / (double) nbbo.updateCount());
nbbo.onVenueDown(1, t);                            // feed loss/halt: venue removed
```

Most consolidated-tape traffic is off-inside flicker, and the fast path skips recomputation for those updates entirely -- so the listener naturally conflates downstream logic to inside-quote changes, and `changeCount()` over `updateCount()` is exactly that conflation ratio. A locked book (bid equals ask) or crossed book (bid above ask) is a transient artifact of asynchronous venue feeds, not a tradable state; the flags let the router pause marketable flow until the tape uncrosses rather than routing into a phantom price.

## 124. Encode and decode a 48-byte quote flyweight

The `sbe` package is the ITCH/SBE wire discipline in miniature: fixed offsets over a `ByteBuffer`, so encode and decode are a handful of absolute primitive reads and writes -- no parse step, no objects, no copying.

```java
ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
QuoteFlyweight quote = new QuoteFlyweight();      // one instance, reused forever
int symbolId = 7;                                 // dense id shared by both ends

// Encoder side (market maker out): a 48-byte two-sided quote.
quote.wrap(buf, 0).encode(symbolId, 1.0849, 2_000_000, 1.0851, 1_000_000,
        System.nanoTime());

// Decoder side: dispatch on the type header, read primitives in place.
if (QuoteFlyweight.typeAt(buf, 0) == QuoteFlyweight.MESSAGE_TYPE) {
    quote.wrap(buf, 0);
    double mid = (quote.bidPrice() + quote.askPrice()) / 2;   // 1.0850
    System.out.printf("mid %.4f, bid size %.0f%n", mid, quote.bidSize());
}

// Trades are the 32-byte sibling with the same discipline, packed right after.
TradeFlyweight trade = new TradeFlyweight();
trade.wrap(buf, QuoteFlyweight.BLOCK_LENGTH)
        .encode(symbolId, 1.0850, 250_000, System.nanoTime());
```

A one-sided quote carries NaN on the pulled side (the convention `fx.AggregatedBook` consumes), and the layout is little-endian with the type discriminator at offset 0 -- a receive loop reads `typeAt`, wraps the matching flyweight, and never allocates. Packing the trade at `QuoteFlyweight.BLOCK_LENGTH` shows the framing rule: each message occupies its own block length, so a stream is a walk of offsets, and `BinaryOrderPublisher`/`BinaryOrderReceiver` run the same idea over a real transport.

## 125. Parse FIX 35=W snapshots and 35=X increments

Bank FX streams and many ECNs deliver prices as FIX 35=W (full snapshot) and 35=X (incremental refresh). `FixMarketDataView` is a garbage-free flyweight over an already-framed message: one pass records primitives, and every getter after that is a primitive read.

```java
FixMarketDataView view = new FixMarketDataView();     // retains up to 32 entries
byte[] msg = ("35=X|55=EURUSD|268=2"
        + "|279=0|269=0|270=1.0849|271=1000000"        // NEW bid   1.0849 x 1M
        + "|279=0|269=1|270=1.0852|271=500000|")       // NEW offer 1.0852 x 0.5M
        .replace('|', (char) 1)                         // SOH field separators
        .getBytes(StandardCharsets.US_ASCII);

if (view.wrap(msg, 0, msg.length)                       // true only for 35=W / 35=X
        && view.symbolEquals("EURUSD".getBytes(StandardCharsets.US_ASCII))) {
    for (int e = 0; e < view.entryCount(); e++) {
        byte action = view.action(e);   // NEW / CHANGE / DELETE (W: always NEW)
        byte side = view.type(e);        // ENTRY_BID or ENTRY_OFFER
        double px = view.price(e);
        long size = view.size(e);
        System.out.printf("entry %d: action=%d side=%d %.4f x %d%n",
                e, action, side, px, size);
    }
}
```

Entry order within the message is preserved, which matters for tiered LP streams where the position of an entry IS its liquidity tier. The scaled-long price -- mantissa times ten to the minus decimals, reachable via `pxMantissa(e)`/`pxDecimals(e)` -- is the same integer representation `FixOrderEncoder` takes, so a feed-to-order loop never has to touch a double; the one rule is never to apply one entry's decimals to another entry's mantissa.

## 126. Run a FIX session with a sequence-number reset on logon

When two sides' sequence stores diverge beyond repair, the clean reconnect is ResetSeqNumFlag(141)=Y on logon: both counters restart at 1 and any unrecoverable history is abandoned. `FixSession.Config.withResetOnLogon()` drives exactly that handshake.

```java
var initiatorCfg = new FixSession.Config("MYDESK", "VENUE", 30).withResetOnLogon();
var acceptorCfg = new FixSession.Config("VENUE", "MYDESK", 30);

FixSession.Listener deskSide = new FixSession.Listener() {
    @Override public void onExecutionReport(FixSession s, ExecutionReport r) {
        if (r.isFill()) { /* position += lastQty at lastPrice */ }
    }
    @Override public void onDisconnect(String reason) { /* alert + reconnect */ }
};
FixSession.Listener venueSide = new FixSession.Listener() {
    @Override public void onNewOrderSingle(FixSession s, NewOrderSingle o) {
        s.sendExecutionReport(ExecutionReport.accepted(o, "v-1", "e-1"));
    }
};

try (ServerSocket server = new ServerSocket(0)) {
    var acceptorFut = CompletableFuture.supplyAsync(() -> {
        try { return FixSession.accept(server, acceptorCfg, venueSide); }
        catch (IOException e) { throw new CompletionException(e); }
    });
    try (FixSession desk = FixSession.initiate("localhost", server.getLocalPort(),
                 initiatorCfg, deskSide, new FileSessionStore(Path.of("fix-state")));
         FixSession venue = acceptorFut.join()) {
        System.out.println("established=" + desk.isEstablished()
                + " nextIncoming=" + desk.expectedIncomingSeqNum());   // both reset to 1
        desk.sendNewOrderSingle("ord-1", "EURUSD", Side.BUY, 1_000_000,
                1.0851, NewOrderSingle.TIF_DAY);
        desk.logout();
    }
}
```

The reset is committed before any thread runs: an initiator configured for reset-on-logon persists both counters at 1 through its `FixSessionStore` before sending `34=1, 141=Y`, so a crash mid-handshake cannot resurrect the abandoned numbers. The peer, per FIX 4.4, resets its own counters and confirms with 141=Y, which is why `expectedIncomingSeqNum()` reads a clean 1 on both ends above. The tradeoff is explicit: messages sent before the reset are no longer recoverable by ResendRequest, so use it only when the stores have genuinely diverged -- for an ordinary restart, the `FileSessionStore` resumes yesterday's numbers and can still service a peer's resend.

## 127. Stream live Binance trades over a WebSocket

`WebSocketFeed` is the last mile to real ticks: a pure-JDK WebSocket client, a pluggable `FeedParser` per exchange, and automatic reconnection with exponential backoff. Everything downstream -- capture, paper trading, analytics -- runs on the live tape.

```java
HftMarketDataBus bus = new HftMarketDataBus();
bus.subscribeAll((id, price, size, ts) ->
        System.out.printf("%s %.2f x %.6f%n", bus.symbol(id), price, size));
bus.start();

try (WebSocketFeed feed = new WebSocketFeed(
        BinanceTradeParser.streamUri("BTCUSDT", "ETHUSDT"),
        new BinanceTradeParser(), bus)) {
    feed.withReconnect(10, 500);      // up to 10 attempts, 500ms doubling toward 30s
    feed.start();                     // blocks until the first connection
    Thread.sleep(30_000);
    System.out.printf("msgs=%d trades=%d reconnects=%d%n",
            feed.messagesReceived(), feed.tradesPublished(), feed.reconnectCount());
}
bus.stop();
```

The feed registers symbols on the bus as they first appear and publishes the exchange's own event time (millis widened to nanos), so a `TickCapture` recording of this session (recipe 137) replays with true market pacing rather than your machine's. A healthy connection resets the backoff ladder, so a brief network blip does not permanently slow reconnection; exchanges that require a subscribe handshake after connecting get it via `withSubscribeMessage`.

## 128. Run a venue-grade matching session

`HftOrderBook` is the zero-allocation matching engine -- dense tick ladder, pooled nodes, around 204 ns per operation -- with the full order-type menu a venue offers and rejections returned as codes, because a matching loop must never unwind over a bad order.

```java
HftOrderBook book = new HftOrderBook(90_000, 110_000, 1 << 20);  // tick band, pool
book.tradeSink((maker, taker, tick, qty, ts) ->
        System.out.printf("fill %d @ %d (maker %d, taker %d)%n", qty, tick, maker, taker));

long now = System.nanoTime();
long bid = book.submitLimit(Side.BUY, 99_995, 1_000, now);        // rests
long ask = book.submitLimit(Side.SELL, 100_005, 1_000, now);      // rests

long ioc = book.submitIoc(Side.BUY, 100_005, 400, now);           // fills 400, rest cancels
long fok = book.submitFok(Side.BUY, 100_005, 5_000, now);         // 0: cannot fill fully
long post = book.submitPostOnly(Side.BUY, 100_005, 200, now);     // would take liquidity
if (post == HftOrderBook.REJECT_WOULD_CROSS) {
    post = book.submitPostOnly(Side.BUY, 100_000, 200, now);      // reprice passively
}
book.cancel(bid);
System.out.printf("resting=%d trades=%d%n", book.restingOrders(), book.tradeCount());
```

The reject codes -- `REJECT_POOL_FULL`, `REJECT_OUT_OF_BAND`, `REJECT_INVALID`, `REJECT_WOULD_CROSS` -- are all negative while accepted ids are positive, so a caller branches on the sign and never throws inside the match. The readable `OrderBook` is this engine's executable specification: an equivalence test drives both with identical randomized order streams and demands byte-identical books, which is how a zero-allocation rewrite stays trustworthy.

## 129. Uncross an opening auction

`Auction` implements the standard exchange rulebook: orders accumulate without trading through the call phase, then one clearing price executes the maximum matchable volume -- the mechanism behind every open, close and volatility-halt reopening.

```java
Auction open = new Auction()
        .addBuy(100.10, 500).addBuy(100.00, 800).addMarketBuy(300)
        .addSell(99.90, 600).addSell(100.05, 700).addMarketSell(200);

// Call phase: the indicative triple the exchange disseminates before the bell.
Auction.Result indicative = open.indicative(100.00);   // reference = last trade
System.out.printf("indicative %.2f x %d, imbalance %+d%n",
        indicative.price(), indicative.volume(), indicative.imbalance());

Auction.Result print = open.uncross(100.00);           // the opening print
System.out.printf("open %.2f x %d, buy surplus %b%n",
        print.price(), print.volume(), print.hasBuySurplus());
if (!print.hasBuySurplus()) {
    // leftover sells carry into continuous trading
}
```

Price discovery follows the hierarchy real rulebooks use: maximum executable volume first, then minimum leftover surplus among volume ties, then proximity to the reference price. Market-on-auction orders are eligible at any clearing price, which is precisely why they drive the imbalance the exchange publishes before the open -- unpriced demand that must trade shows up as pressure in one direction, and `imbalance()` is that signed leftover at the clearing price.

## 130. Gate every order in ~3ns with the reduce-not-flip rule

`HftRiskGate` is the pre-trade check on the fast lane: all state in primitive arrays by dense symbol id, `check` returns an int reason code, and cross-thread reads use VarHandle acquire/release so they cost nothing on x86.

```java
HftRiskGate gate = new HftRiskGate(64)             // symbol ids 0..63
        .maxOrderQuantity(1_000_000)
        .maxOrderNotional(25_000_000)
        .maxPositionQuantity(1_000)
        .priceCollarPct(0.02);                     // 2% fat-finger collar
gate.setReferencePrice(7, 150.00);                 // from the market-data thread

System.out.println(gate.check(7, Side.BUY, 500, 150.01));   // 0 = OK
gate.onFill(7, Side.BUY, 800);                     // venue-ack thread; atomic add
gate.onFill(7, Side.BUY, 800);                     // position now 1600: over the cap
System.out.println(HftRiskGate.reasonName(
        gate.check(7, Side.BUY, 100, 150.00)));    // MAX_POSITION
System.out.println(gate.check(7, Side.SELL, 500, 150.00) == 0);   // true: reduces risk
System.out.println(HftRiskGate.reasonName(
        gate.check(7, Side.SELL, 3_100, 150.00))); // MAX_POSITION: flips to -1500
gate.halt(7, true);                                // ops lever, callable anywhere
System.out.println(HftRiskGate.reasonName(gate.check(7, Side.BUY, 1, 150.00)));  // HALTED
```

The risk-reducing rule is the subtle one: from an over-cap +1600, selling 500 passes because it shrinks the position without changing its sign -- rejecting the trade that de-risks the book would wedge it at its worst point -- but selling 3100 is a brand-new over-cap short wearing a hedge's clothes, and is rejected. `rejectionCount(code)` keeps per-reason tallies for monitoring, and `kill(true)` is the gate-wide brother of `halt`, which recipe 136 pulls firm-wide.

## 131. Throttle order flow with a token bucket

Every real venue enforces a message-rate limit, and exceeding it earns disconnects or fines, so the gateway must self-limit. `OrderThrottle` is a nanosecond token bucket: a sustained rate plus a bounded burst, with the clock passed in so tests stay deterministic.

```java
OrderThrottle throttle = new OrderThrottle(100, 20);   // 100 msg/s sustained, burst 20

long now = System.nanoTime();
for (int i = 0; i < 25; i++) {
    if (throttle.tryAcquire(now)) {
        // send the order
    } else {
        long wait = throttle.nanosUntilAvailable(now);  // park this long, do not spin-fail
    }
}
System.out.printf("sent=%d throttled=%d%n",
        throttle.acquiredCount(), throttle.throttledCount());
// 20 granted (the opening burst), 5 throttled; a permit refills every 10 ms.
```

The caller passes `System.nanoTime()` rather than the throttle reading its own clock -- the hot path controls its syscalls, and a test can feed a synthetic timeline and assert exact grant counts. A persistently nonzero `throttledCount()` is a strategy-design problem, not a tuning one: the strategy is generating more messages than the venue will ever accept, and raising the limit just moves the disconnect from your gateway to theirs.

## 132. Quote two-sided markets with inventory skew

`HftQuoter` is the market-making loop on the fast lane: tick in, inventory skew applied, quotes snapped to the tick grid, two orders out through the risk gate -- zero allocation per tick, and per-symbol configuration because a EURUSD half-spread is roughly 100x too tight for USDJPY.

```java
HftRiskGate gate = new HftRiskGate(16).maxPositionQuantity(5_000_000);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> { /* venue adapter */ });

    HftQuoter quoter = new HftQuoter(gateway, 16,
            HftQuoter.Config.of(1_000_000, 0.00010)   // 1M per side, 1 pip half-spread
                    .withSkewPerUnit(2e-11)           // long inventory shades quotes down
                    .withMinMove(0.00005));           // move-gated conflation
    int eurusd = bus.registerSymbol("EURUSD");
    int usdjpy = bus.registerSymbol("USDJPY");
    quoter.configureSymbol(usdjpy,                    // JPY pairs live on another scale
            HftQuoter.Config.of(1_000_000, 0.010).withMinMove(0.005));

    bus.subscribe(eurusd, quoter);
    bus.subscribe(usdjpy, quoter);
    gateway.start();
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    // -> BUY 1M @ 1.0849 and SELL 1M @ 1.0851 through the gate
}
```

The skew loop closes with no extra state: fills update the gate's position via `onFill`, the next tick reads that position and shades both quote sides toward flattening. Both sides still pass the gate's own checks -- a quoter cannot out-trade its own risk limits -- and `suppressedUpdates()` counts the conflation the `withMinMove` gate saves you, which is real wire and real venue message budget under recipe 131's throttle.

## 133. Auto-hedge a drifting book back to the band

`AutoHedger` is the streaming counterpart of the batch `hedging.DeltaHedger`: it watches the risk gate's live position on every tick and fires a flattening order the moment the tolerance band is breached -- back to the band edge, not to flat.

```java
HftRiskGate gate = new HftRiskGate(16);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> { /* venue adapter */ });

    AutoHedger hedger = new AutoHedger(gateway, 16,
            2_000_000,          // tolerate |position| up to 2M unhedged
            50_000_000L);       // 50 ms cooldown while a hedge fill is in flight
    int eurusd = bus.registerSymbol("EURUSD");
    bus.subscribe(eurusd, hedger);   // subscribe AFTER the quoter/strategy listeners
    gateway.start();
    bus.start();

    gate.onFill(eurusd, Side.BUY, 3_500_000);   // one-sided flow builds inventory
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    // -> hedger submits SELL 1_500_000: the excess over the band, nothing more
    System.out.printf("hedges=%d rejected=%d%n",
            hedger.hedgesSubmitted(), hedger.hedgesRejected());
}
```

Hedging to the band edge removes the breach with the smallest possible order and avoids ping-ponging around zero -- how dealer books actually run inventory. The cooldown matters because a position only moves when fills confirm through `onFill`; without it the hedger would stack fresh orders on every tick while the first hedge is still in flight. A nonzero `hedgesRejected()` is an emergency: the gate is refusing the very order meant to reduce risk, which means the book is stuck at its worst point.

## 134. Trade on paper with production risk checks

`PaperTradingGateway` closes the research-to-production loop: the same strategy code and the same `PreTradeLimitChecker` as production, run against simulated fills with full account tracking.

```java
PreTradeLimitChecker limits = new PreTradeLimitChecker()
        .maxOrderQuantity(10_000).maxPositionQuantity(20_000).priceCollarPct(0.03);
PaperTradingGateway paper = new PaperTradingGateway(1_000_000, 0.0005, limits);
paper.addExecutionListener((id, symbol, side, price, qty, ts) ->
        System.out.printf("fill %s %s %d @ %.2f%n", symbol, side, qty, price));

paper.onQuote("AAPL", 149.99, 150.01);                     // top of book in
paper.submitMarket("AAPL", Side.BUY, 1_000);               // fills at the ask
paper.submitLimit("AAPL", Side.SELL, 1_000, 151.00);       // rests
paper.onQuote("AAPL", 151.05, 151.07);                     // market crosses it: fills
long fat = paper.submitLimit("AAPL", Side.BUY, 1_000, 200.00);
System.out.println(paper.status(fat));                     // REJECTED (price collar)

PaperTradingGateway.AccountSnapshot account = paper.snapshot();
System.out.printf("cash %.2f equity %.2f realized %.2f rejections %d %s%n",
        account.cash(), account.equity(), account.realizedPnl(),
        account.rejectionCount(), account.positions());
```

Because it implements `OrderGateway`, swapping in a real broker adapter later changes zero strategy lines -- the paper venue and production share the interface, so what passed the limits in simulation passes them live. `snapshot()` returns one internally consistent view under a single lock acquisition, safe for a dashboard thread to poll while another thread trades, and `rejectionLog()` carries the venue's stated reason for each refusal so a strategy author sees why an order died.

## 135. Shard the engine across symbols and measure the scaling

`ShardedTradingEngine` is horizontal scaling as shipped machinery: N independent bus -> gate -> gateway stacks behind one symbol-routing facade. Shared-nothing, so aggregate capacity is per-shard throughput times shard count.

```java
try (ShardedTradingEngine engine = new ShardedTradingEngine(
        4, 1 << 14, 1 << 13, 64, true,                 // 4 shards, busy-spin
        shard -> new HftRiskGate(64).maxOrderQuantity(5_000_000))) {

    int eurusd = engine.registerSymbol("EURUSD", 0);
    int usdjpy = engine.registerSymbol("USDJPY", 1);
    int eurjpy = engine.registerSymbol("EURJPY", 0, 1);  // cross lives where BOTH legs tick

    engine.bus(0).subscribe(engine.localId(eurusd, 0),
            (id, px, size, ts) -> { /* shard-0 strategy, as on a standalone bus */ });
    engine.start();

    long t0 = System.nanoTime();
    for (int i = 0; i < 4_000_000; i++) {
        int handle = (i & 1) == 0 ? eurusd : usdjpy;
        while (!engine.publish(handle, 1.0850, 1_000_000, System.nanoTime())) {
            Thread.onSpinWait();
        }
    }
    while (engine.processedCount() < 4_000_000) {
        Thread.onSpinWait();
    }
    System.out.printf("%.1fM ticks/s across %d shards%n",
            4_000_000 / ((System.nanoTime() - t0) / 1e9) / 1e6, engine.shardCount());
}
```

Multi-shard registration is the cross-instrument co-location tool: duplicating a leg's feed into a second shard costs one extra ring publish (around 40 ns), which is the whole point of shared-nothing scaling. What sharding deliberately does not solve is firm-wide risk -- each shard's gate sees only its own symbols, so a book split across four shards has four blind spots -- and recipe 136 is the layer that closes them.

## 136. Pull the firm-wide kill switch on breach

`GlobalRiskAggregator` is the one component that sees every shard: a monitor thread sweeps all gates' positions and reference prices (already published with acquire/release semantics, so aggregation is free for the hot paths) and pulls `HftRiskGate.kill` on every gate when gross notional breaches the firm cap.

```java
HftRiskGate shardA = new HftRiskGate(64);
HftRiskGate shardB = new HftRiskGate(64);
try (GlobalRiskAggregator firmRisk = new GlobalRiskAggregator(
        List.of(shardA, shardB),   // or engine.gates() from recipe 135
        100_000_000.0,             // firm-wide cap on sum |position| x reference
        0.9,                       // hysteresis: resume only below 90M
        1_000_000)) {              // 1 ms poll = detection latency

    shardA.setReferencePrice(0, 1.0850);          // unpriced symbols contribute zero
    shardA.onFill(0, Side.BUY, 120_000_000);      // ~130M gross: breach

    Thread.sleep(10);                             // > one poll interval
    System.out.println(firmRisk.isTripped());     // true -- every gate killed
    System.out.println(shardA.check(0, Side.BUY, 1, 1.0850)
            == HftRiskGate.REJECT_KILLED);        // true: shard checks all reject

    shardA.onFill(0, Side.SELL, 60_000_000);      // de-risk below the resume fraction
    Thread.sleep(10);
    System.out.println(firmRisk.isTripped());     // false -- trading resumed
    System.out.printf("trips=%d lastGross=%.0f%n",
            firmRisk.tripCount(), firmRisk.lastGrossNotional());
}
```

The layering is deliberate: per-order checks stay per-shard and nanosecond-cheap, while the firm-wide cap is a circuit breaker with poll-interval detection -- exactly how real risk stacks are built, because a synchronous firm-wide check on every order would serialize the shards it exists to parallelize. The 0.9 resume fraction stops the firm flapping around the limit, and `tripCount()`/`lastGrossNotional()` feed the monitoring that tells a risk manager how close to the edge the day ran.

## 137. Replay a captured session deterministically

Attach a `TickCapture` to the market-data bus, trade the session as usual, and get a QFLT file that replays the exact tick sequence into any experiment. `TickBacktester` runs a `TickStrategy` over that file with queue-aware fills -- the same recorded reality, forever.

```java
Path file = Path.of("session.qflt");
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, false);
     TickCapture capture = TickCapture.attach(bus, file)) {
    int eurusd = bus.registerSymbol("EURUSD");
    bus.start();
    bus.publish(eurusd, 1.0850, 1_000_000, System.nanoTime());
    bus.publish(eurusd, 1.0851, 500_000, System.nanoTime());
    bus.stop();
    System.out.println(capture.ticksWritten() + " ticks captured");
}

TickStrategy momentum = new TickStrategy() {
    private TickTradingContext ctx;
    public String name() { return "tick-momentum"; }
    public void init(TickTradingContext c) { this.ctx = c; }
    public void onTick(int sym, double price, double size, long ts) {
        if (ctx.position(sym) == 0) ctx.submitMarket(sym, Side.BUY, 1_000);
    }
};
TickBacktester.TickBacktestResult r = TickBacktester.run(momentum, file,
        TickBacktester.Config.defaults().withTickSize(0.0001));
System.out.printf("%s: %d ticks, final equity %.2f, %d fills%n",
        r.strategyName(), r.ticksProcessed(), r.finalEquity(), r.fills().size());
```

Replay is deterministic and allocation-free per tick, which is the entire point: run a strategy tweak, a queue-position model and a latency benchmark against the same recorded reality and any difference in results is caused by your change, not by the market being different that day. The `TickStrategy` resolves instruments lazily in `onTick` because symbol ids arrive with the ticks the file defines; on a bus that is actively trading, prefer `AsyncTickCapture.attach(bus, file, 1 << 20)`, which moves file I/O behind a ring buffer so capture never touches the trading path's latency.

## 138. Schedule a TWAP and jitter it against gaming

`TwapScheduler` spreads a parent evenly over time with largest-remainder allocation, so quantities sum exactly to the parent. A perfectly even schedule is also a perfectly predictable one -- `AntiGamingJitter` perturbs the sizes and times so the tape reader cannot front-run your clock.

```java
long parent = 100_000;
long window = 6 * 60 * 60 * 1_000L;                    // a 6-hour working window

List<Slice> even = TwapScheduler.schedule(parent, window, 26);   // 26 equal slices
long[] sizes = even.stream().mapToLong(Slice::quantity).toArray();
long[] times = even.stream().mapToLong(Slice::offsetMillis).toArray();

// Jitter sizes +/-15% and times within their slot; deterministic per seed.
AntiGamingJitter jitter = new AntiGamingJitter(42L, 0.15, 0.30);
long[] jSizes = jitter.jitterSizes(sizes);
long[] jTimes = jitter.jitterTimes(times, 0L);

long sum = Arrays.stream(jSizes).sum();
System.out.printf("slices %d, jittered sum %d (== parent %b)%n",
        jSizes.length, sum, sum == parent);
for (int i = 0; i < 4; i++) {
    System.out.printf("slot %d: %d -> %d @ +%dms%n",
            i, sizes[i], jSizes[i], jTimes[i]);
}
```

`jitterSizes` preserves the total exactly -- it moves quantity between slices, never creates or destroys it -- so the anti-gaming perturbation cannot leave the parent over- or under-filled. The jitter is seeded, so a backtest replays the identical randomized schedule and a tester can assert on it, while a live venue watcher sees a schedule that never repeats. When the day's volume shape is known, prefer the volume-weighted schedule of recipe 139 over even time-slicing; the same jitter applies to either.

## 139. Weight a VWAP schedule by a learned volume profile

`VwapScheduler` slices a parent in proportion to expected volume rather than evenly over time. Feed it a `VolumeCurve` the desk has learned from prior sessions instead of a stale U-shape guess, and the schedule tracks the day the market actually trades.

```java
long parent = 100_000;
long window = 6 * 60 * 60 * 1_000L;

// A learned intraday profile: read the per-bucket shape the model accumulated.
VolumeCurve learned = new VolumeCurve(26, 0.1);
// ... over prior days: learned.onVolume(bucket, qty); learned.rollDay(); ...
double[] profile = new double[26];
for (int b = 0; b < 26; b++) {
    profile[b] = learned.profileVolume(b);            // proportions are all that matter
}

List<Slice> vwap = VwapScheduler.schedule(parent, profile, window);
long check = vwap.stream().mapToLong(Slice::quantity).sum();
System.out.printf("VWAP slices %d, sum %d, first %d, last %d%n",
        vwap.size(), check, vwap.get(0).quantity(), vwap.get(25).quantity());
```

`schedule` normalizes the profile internally, so any positive scale works -- only the relative bucket weights steer the slice sizes, and largest-remainder allocation keeps the total exact. A learned curve beats a textbook U because it captures this instrument's real shape (an index-rebalance name front-loads, a thin small-cap is flat and lumpy), and because it survives overnight through the same `Checkpoint` machinery as recipe 119. When the intraday shape is uncertain enough to re-decide live, graduate from the precomputed slice list to the dynamic VWAP benchmark of recipe 142.

## 140. Run percentage-of-volume without chasing your own fills

Percentage-of-volume cannot be prescheduled -- it chases realized volume. The one bug every homegrown POV ships with is counting its own fills as market volume, which makes the algo chase itself (realized participation becomes p/(1+p) instead of p). `PovTracker` keeps the two ledgers strictly separate.

```java
// 10% of other people's volume; children between 500 and 5,000 shares.
PovTracker pov = new PovTracker(100_000, 0.10, 500, 5_000);

pov.onMarketVolume(8_000);                  // someone else traded 8,000
long due = pov.dueQuantity();               // 800: 10% of 8,000, >= minSlice
if (due > 0) {
    // send(due) via your router;
    pov.onExecuted(due);                    // OUR fill: never onMarketVolume
}

pov.onMarketVolume(60_000);                 // a burst of market activity
System.out.println(pov.dueQuantity());      // catches up toward 10% of 68,000
System.out.printf("participation %.3f (target 0.10), remaining %d, done %b%n",
        pov.realizedParticipation(), pov.remaining(), pov.done());
```

`dueQuantity()` returns 0 while within `minSlice` of schedule, so quiet tape does not dribble odd lots, and it never exceeds `maxSlice`, so a volume burst cannot make you the print everyone notices. The discipline is the two-method contract: every tape print is either your fill (`onExecuted`) or someone else's (`onMarketVolume`), never both -- routing your own fills into the market ledger is exactly the self-chasing bug the class is built to prevent. For POV with alpha and impact shaping on top, use `BenchmarkExecutor.pov` (recipe 142), which keeps the same ledger discipline with more inputs.

## 141. Front-load an urgent parent by implementation shortfall

An implementation-shortfall schedule front-loads: trade more now to cut exposure to price drift, but not so fast that temporary impact dominates. `ImplementationShortfallScheduler` turns the Almgren-Chriss optimal trajectory into the same `Slice` list TWAP and VWAP emit, and lets a trader dial urgency by front-load fraction instead of by an opaque risk-aversion number.

```java
var params = new AlmgrenChriss.Params(
        100_000,    // parent shares
        1.0,        // horizon: 1 day
        26,         // intervals
        0.50,       // sigma: price vol per sqrt(day)
        1e-6,       // eta: temporary impact per unit trade rate
        5e-8,       // gamma: permanent impact per share
        1e-6);      // lambda: risk aversion (0 = TWAP)

List<Slice> base = ImplementationShortfallScheduler.schedule(params,
        6 * 60 * 60 * 1_000L);
System.out.printf("first slice %d vs TWAP slice %d%n",
        base.get(0).quantity(), 100_000 / 26);

// Traders think in front-load, not lambda: solve for the risk aversion whose
// first slice is 30% of the parent, then schedule with it.
double lambda30 = ImplementationShortfallScheduler.riskAversionForFrontLoad(params, 0.30);
List<Slice> urgent = ImplementationShortfallScheduler.schedule(
        params.withRiskAversion(lambda30), 6 * 60 * 60 * 1_000L);
System.out.printf("urgent first slice %d%n", urgent.get(0).quantity());
```

`riskAversionForFrontLoad` bisects on lambda and throws if the requested front-load is unreachable before the trajectory's sinh overflows -- a loud error, never a silently wrong urgency. With `riskAversion = 0` the schedule degrades to exactly TWAP, which is the correct smoke test: the math says lambda approaching zero is the risk-neutral linear trajectory. Hand the same `Params` to `AlmgrenChriss.efficientFrontier` when you want to see the cost-versus-risk tradeoff across urgencies before committing to one.

## 142. Work any benchmark from live market state

`BenchmarkExecutor` re-decides every interval from live market state rather than walking a precomputed slice list. One class covers TWAP, VWAP, Arrival, Close, Open, Implementation Shortfall and POV -- only the target curve differs; the dynamic shaping (alpha urgency, spread and impact damping, depth cap) is shared. Here it works a closing-price benchmark.

```java
BenchmarkExecutor close = BenchmarkExecutor.of(Side.BUY, 100_000,
        BenchmarkExecutor.Benchmark.CLOSING_PRICE);

// Mid-morning: 40% of the horizon elapsed. A CLOSE benchmark has barely started.
double f = 0.40;
var m = new BenchmarkExecutor.MarketState(
        100.00,   // mid
        0.02,     // spread (price units)
        0.3,      // normalized vol regime ~0..1
        12_000,   // displayed take-now depth
        0.45,     // VWAP curve: 45% of the day's volume traded (unused by CLOSE)
        +0.4,     // normalized alpha in [-1,1]: price about to run away
        1.5);     // estimated child impact, bps
close.onMarketVolume(50_000);                         // keeps the tape ledger current
long due = close.dueQuantity(f, m);
System.out.printf("%s due %d, remaining %d%n", close.benchmark(), due, close.remaining());

// Route `due`, then report fills so the schedule self-corrects:
close.onFill(due);
System.out.println("done? " + close.done());
```

The closing-price curve is heavily back-loaded by design -- most of a CLOSE parent belongs in the last stretch, near the print it benchmarks against -- so at 40% elapsed `dueQuantity` releases very little even under positive alpha, because banging the close early defeats the benchmark. Adverse alpha raises urgency on every curve, but the shape still dominates the timing; `MarketState.neutral(mid, f)` disables every input so you can add signals one at a time and watch each move the schedule.

## 143. Slice an iceberg with randomized display

An iceberg shows a small display tranche and reloads when it fills. A fixed display size is a signature that detectors watch for, so `IcebergOrder` jitters the display per reload and hands back a clean reload signal -- the cue to resubmit and rejoin the queue.

```java
// 50,000 total, ~2,000 displayed, display jittered +/-20%, seeded.
IcebergOrder ice = new IcebergOrder(50_000, 2_000, 0.20, 7L);
System.out.printf("visible %d, hidden %d%n", ice.visibleQty(), ice.hiddenQty());

// Fills hit the visible tranche; onFill returns true when it exhausts and a
// fresh (re-jittered) tranche loads -- that is your cue to resubmit.
boolean reloaded = ice.onFill(1_200);           // false: tranche not done
reloaded = ice.onFill(ice.visibleQty());        // true: reload happened
if (reloaded) {
    // resubmit(ice.visibleQty()) -- rejoins the BACK of the queue, as at a venue
}
while (!ice.isComplete()) {
    if (ice.onFill(ice.visibleQty())) {
        // resubmit(ice.visibleQty());
    }
}
System.out.println("complete: " + ice.isComplete());
```

The reload signal is the important part: every `true` from `onFill` means a queue-priority reset, which is the real cost of icebergs -- you pay time priority for size concealment, joining the back of the line each reload. The jitter is deterministic per seed, so a backtest replays exactly, and the display never repeats to a watcher. To see the other side of this game, feed the resulting prints to the `HiddenLiquidityDetector` of recipe 151 and watch it infer the reserve you just hid.

## 144. Execute a two-legged spread without legging risk

In a spread trade the risk is the moment you own one leg without the other. `SpreadExecutionAlgo` encodes the desk discipline: work the illiquid lead leg patiently, let the liquid hedge chase its fills at the ratio, and cap the imbalance hard -- at the cap, stop adding lead risk entirely and cross the hedge to get flat.

```java
var spread = new SpreadExecutionAlgo(
        100,     // lead parent: 100 units
        0.3,     // hedge units per lead unit
        6,       // legging cap: 6 hedge units of imbalance, never more
        10);     // lead child max: the patience knob

var c = spread.decide();
System.out.printf("lead %d, hedge %d%n", c.leadQty(), c.hedgeQty());
spread.onLeadFill(c.leadQty());              // lead filled, hedge did not

c = spread.decide();                          // hedge now chases the imbalance
System.out.printf("imbalance %d units, hedge child %d, atCap %b%n",
        spread.imbalanceHedgeUnits(), c.hedgeQty(), c.atRiskCap());
spread.onHedgeFill(c.hedgeQty());

while (!spread.done()) {
    c = spread.decide();
    spread.onLeadFill(c.leadQty());          // route via your SOR, report actual fills
    spread.onHedgeFill(c.hedgeQty());
}
System.out.println("done: " + spread.done());
```

The lead child is sized so that even a full lead fill with no hedge fill stays inside the cap -- the cap protects against the hedge not filling, which is why the lead stops entirely at the cap while the hedge child becomes the whole imbalance. An imbalance cap that yields under pressure is not a cap. `done()` requires both legs complete: the lead fully worked and the hedge caught up to the ratio, so the trade never finishes one-sided.

## 145. Roll a futures position across an expiry window

Rolling everything on day one pays wide back-month spreads; waiting for expiry pays the last-day scramble. `FuturesRollAlgo` follows the liquidity-migration curve -- the cumulative fraction of open interest that has moved by each roll day -- and executes each day's due as a calendar spread.

```java
var roll = new FuturesRollAlgo(500, FuturesRollAlgo.defaultMigration(5));  // 500 lots, 5 days

for (int day = 0; day < roll.rollDays(); day++) {
    long due = roll.dueOnDay(day);
    if (due == 0) continue;
    // Each day's due IS a calendar spread: sell front / buy back at ratio 1.
    var leg = new SpreadExecutionAlgo(due, 1.0, 25, 50);
    while (!leg.done()) {
        var c = leg.decide();
        leg.onLeadFill(c.leadQty());          // front-month child
        leg.onHedgeFill(c.hedgeQty());        // back-month child
    }
    roll.onRolled(due);
    System.out.printf("day %d: rolled %d, remaining %d%n", day, due, roll.remaining());
}
System.out.println("done: " + roll.done());   // true: no delivery risk
```

Falling behind one day just makes later days' due larger -- the roll always catches up to the curve, and the constructor rejects any migration that does not end at exactly 1.0, because a roll that does not complete is a delivery notice waiting to happen. Each day's execution is recipe 144's spread algo with ratio 1, so the front and back legs never drift apart mid-roll; substitute your market's observed open-interest migration for the default smoothstep when you have it.

## 146. Execute at the WM/Refinitiv fix, then screen for banging it

Orders benchmarked to the 4pm WMR fix are replicated by spreading the parent evenly across the fixing's calculation window -- the fix is computed from observations inside the window, so TWAP-in-window IS the neutral execution. `WmrFixingScheduler` builds that plan and `FixAnalyzer` screens the result the way surveillance would.

```java
List<Slice> plan = WmrFixingScheduler.schedule(50_000_000, 20);   // 50M, 20 slices

// After the window: the fix, and the banging-the-close screen.
double[] midsInWindow = capturedMids();                // sampled inside the window
double fix = FixAnalyzer.calculateFix(midsInWindow);   // median, per WM/R

FixAnalyzer.FixImpactReport report = FixAnalyzer.analyze(
        midsInWindow,
        1.08420,       // mid just before the window opened
        1.08425,       // mid after the window closed
        50_000_000,    // our buys in the window
        0,             // our sells in the window
        160_000_000,   // total market volume in the window
        0.25);         // participation share that triggers scrutiny
System.out.printf("fix %.5f, run-up %.1f bps, reversion %.1f bps, share %.2f, flagged %b%n",
        report.fixRate(), report.runUpBps(), report.reversionBps(),
        report.participationShare(), report.flagged());
```

The scheduler refuses the two conduct traps: executing ahead of the window (getting done early is pre-hedging against the client's benchmark) and skewing inside it (a bet, not replication). The analyzer flags the classic signature only when all three conditions hold at once -- large participation share, a run-up aligned with your net flow, and reversion afterward -- because impact that decays is the footprint of pressure, not information, and any one of the three alone is ordinary market noise.

## 147. Let a UCB1 bandit pick today's execution algo

You shipped a new schedule variant. Always using the incumbent never learns whether the challenger is better; rotating uniformly wastes flow on a loser. `Ucb1Selector` balances the two with a regret guarantee -- mean reward plus an optimism bonus that shrinks exactly as fast as the evidence accumulates.

```java
Ucb1Selector bandit = new Ucb1Selector(3);   // incumbent VWAP, new IS, liquidity seeker

for (int parent = 0; parent < 200; parent++) {
    int arm = bandit.select();               // each arm once, then UCB
    double costBps = executeParentWith(arm); // run the chosen algo, get its TCA cost

    // Rewards must live in [0,1]: map slippage onto it (0 bps -> 1.0, 10+ -> 0.0).
    double reward = Math.max(0, Math.min(1, 1 - costBps / 10.0));
    bandit.record(arm, reward);
}

for (int a = 0; a < 3; a++) {
    System.out.printf("arm %d: %d pulls, mean reward %.3f%n",
            a, bandit.pulls(a), bandit.meanReward(a));
}
System.out.println("total parents routed: " + bandit.totalPulls());
```

`record` rejects rewards outside [0,1] loudly, because a mis-scaled reward silently breaks the exploration balance -- the one bug that makes a bandit worse than a coin flip. Selection is deterministic, with ties broken to the lowest index, so a test replays the exact arm sequence. UCB1 is the cold-start and A/B tool; once hundreds of fills per venue exist, the `VenueScorecard` of recipe 149 models fill rate, latency and markout separately and becomes the better router input.

## 148. Coordinate a basket transition as one schedule

A two-sided transition -- sell the old book, buy the new one -- is not N independent parents: the legs must stay in step or the basket carries unintended net market exposure mid-flight. `PortfolioExecutor` coordinates per-symbol `BenchmarkExecutor` children with two overlays that only exist at basket level: a leg-balance band and a per-interval notional budget.

```java
int n = 2;
var pe = new PortfolioExecutor(n, new PortfolioExecutor.Config(
        1_000_000,      // |filled buys - sells| stays inside $1M
        5_000_000));    // total basket demand per interval

int sellOld = pe.add(BenchmarkExecutor.of(Side.SELL, 80_000,
        BenchmarkExecutor.Benchmark.VWAP));
int buyNew = pe.add(BenchmarkExecutor.of(Side.BUY, 60_000,
        BenchmarkExecutor.Benchmark.VWAP));

// Upgrade capacity allocation from per-symbol vol to true basket risk.
var cov = new EwmaCovariance(n, 0.97);
pe.useRiskModel(cov);

var states = new BenchmarkExecutor.MarketState[n];
long[] due = new long[n];
// Each interval:
cov.onReturns(intervalReturns);                        // length >= n
states[sellOld] = new BenchmarkExecutor.MarketState(52.00, 0.01, 0.3, 15_000, 0.45, 0, 1.0);
states[buyNew]  = new BenchmarkExecutor.MarketState(101.00, 0.02, 0.5, 8_000, 0.45, 0, 2.0);
pe.decide(0.40, states, due);                          // zero-alloc
pe.onFill(buyNew, due[buyNew], 101.02);                // ALWAYS report via pe.onFill
System.out.printf("net notional %.0f%n", pe.netNotional());
```

Both overlays only ever reduce a child's own due -- the ahead leg throttles, the lagging leg is never pushed past its own benchmark -- so each symbol's benchmark integrity holds by construction. The one wiring rule is to report fills through `pe.onFill(handle, qty, price)`, never `child(h).onFill(...)`: the child call advances the schedule but bypasses the notional ledger the leg-balance band reads, leaving net exposure uncontrolled while every per-child number still looks healthy.

## 149. Route one order across lit and dark venues

`AdaptiveSor` prices the full routing checklist -- fill-rate reliability, measured latency, adverse selection, learned dark liquidity -- from a `VenueScorecard` it updates as you trade, then splits a parent across lit legs and contingent dark probes.

```java
var card = new VenueScorecard(3);                  // A=0, B=1, dark=2
var sor = new AdaptiveSor(card);
sor.register("EXCH_A", 0);
sor.register("EXCH_B", 1);
sor.register("DARK_X", 2);

var venues = List.of(
        new VenueQuote("EXCH_A", 99.99, 9_000, 100.01, 10_000, 0.3, 120_000, false),
        new VenueQuote("EXCH_B", 99.99, 7_000, 100.01, 8_000, 0.3, 80_000, false),
        new VenueQuote("DARK_X", 99.99, 0, 100.01, 0, 0.1, 200_000, true));

AdaptiveSor.RoutingDecision plan = sor.route(Side.BUY, 12_000, venues);
for (var leg : plan.lit()) {
    System.out.printf("lit  %s %d @ %.2f%n", leg.venue(), leg.quantity(), leg.price());
}
for (var probe : plan.probes()) {
    System.out.printf("dark %s probe %d @ mid%n", probe.venue(), probe.quantity());
}
System.out.println("unrouted: " + plan.unrouted());

// Feed outcomes back so the next route is smarter:
card.onFill(1, 85_000);                            // B filled in 85us
card.onMiss(0, 130_000);                           // A faded
card.onDarkProbe(2, 3_200);                        // the probe found 3,200
```

Probe legs are additive and contingent -- send them alongside the lit legs and, on a dark fill, cancel the lit remainder -- so the router plans while the executor manages overfill. Venues below the config's fill-rate floor are vetoed outright, and the scorecard's measured latency overrides the advertised number once observed, so a venue that quotes tight but fades gets priced out of the ranking by its own history rather than by a hand-tuned penalty.

## 150. Price impact before you trade, learn it after

Two impact numbers from two sources: `MarketImpactModel` parameterizes impact from ADV and volatility (the square-root law, before you trade), while `KylesLambda` learns it from the tape (the market's own regression, after). Run both and let disagreement tell you the assumed model has gone stale.

```java
// Before: parameterized. ADV 5M shares, 2% daily vol.
var model = new MarketImpactModel(5_000_000, 0.02);
System.out.printf("sqrt-law %.1f bps for 250k, schedule cost %.1f bps at 10%%%n",
        model.squareRootImpactBps(250_000),
        model.expectedCostBps(250_000, 0.10));     // permanent/2 + temporary

// After: learned. One sample per interval -- the mid change over the window and
// the signed volume that traded in it (buy-aggressor positive).
var kyle = new KylesLambda(0.02);
kyle.onSample(+0.03, +40_000);                     // buys pushed mid up 3c
kyle.onSample(-0.02, -25_000);
kyle.onSample(+0.01, +10_000);

System.out.printf("lambda %.2e, learned impact %.2f bps for a 20k child%n",
        kyle.lambda(), kyle.impactBps(20_000, 100.00));
// Feed it live: MarketState.impactBps = kyle.impactBps(clipSize, mid).
```

`impactBps` is the live producer for the `impactBps` field of recipe 142's `MarketState` -- impact learned from today's tape, not calibrated last quarter. It ignores the sign of the quantity (impact is a cost in both directions) and clamps a negative lambda estimate to zero, because "the market pays you to trade" is an estimation artifact that would accelerate the schedule on garbage; `lambda()` stays raw for diagnostics. When the parameterized and learned numbers diverge sharply, either your fills carry real edge the model lacks or the training window is stale -- retrain before believing the divergence.

## 151. Read flow toxicity and hidden liquidity off the lit tape

Two lit-tape inference tools a quoting desk runs side by side: `Vpin` gauges flow toxicity (one-sided volume buckets are informed flow) and `HiddenLiquidityDetector` infers reserve size at a level (a single print larger than the display can only have filled against hidden size).

```java
// Flow toxicity: 5,000-share buckets, averaged over 50. Feed TradeClassifier's
// aggressor side when the venue does not disclose it.
var vpin = new Vpin(5_000, 50);
for (int i = 0; i < 60; i++) {
    vpin.onTrade(2_500, true);            // relentless one-sided buying
    vpin.onTrade(2_500, i % 5 == 0);      // thin two-way relief
}
if (vpin.ready() && vpin.vpin() > 0.6) {
    // widen quotes or pull: quoting into this is no longer a business
}
System.out.printf("VPIN %.2f%n", vpin.vpin());

// Iceberg inference, per price level (dense tick indices).
var hidden = new HiddenLiquidityDetector(100, 0.2);
int lvl = 42;
hidden.onDisplayed(lvl, 500);             // the tip shows 500
hidden.onExecution(lvl, 1_800);           // one print of 1,800: the tell
hidden.onDisplayed(lvl, 500);             // ...and it keeps quoting
System.out.printf("iceberg %b, multiplier %.1fx, true depth ~%.0f%n",
        hidden.isIceberg(lvl), hidden.hiddenMultiplier(lvl),
        hidden.estimatedTrueDepth(lvl));
```

`vpin()` returns NaN until the first bucket completes -- an empty average pretending to be calm would be exactly the wrong default for a risk signal, so the detector refuses to answer until it has one. The iceberg detector's per-print signature is the sound one: a cumulative executed-versus-displayed comparison false-flags every busy level, since a level legitimately trades many times its instantaneous display through ordinary adds, whereas a single print larger than the visible size can only have hit a reserve. Recipe 143 is the other side of this game.

## 152. Measure order-flow imbalance in real time

`FlowSignals` maintains the three microstructure imbalances a short-horizon quoting model reads -- order-flow imbalance from quote changes, queue imbalance at the touch, and trade imbalance from the aggressor tape -- all as time-decayed running statistics on the hot path.

```java
FlowSignals flow = new FlowSignals();                 // default half-life

long t = System.nanoTime();
flow.onQuote(1.0850, 2_000_000, 1.0851, 1_000_000, t);
flow.onQuote(1.0850, 2_500_000, 1.0852, 900_000, t + 1_000_000);  // bid grows, ask lifts
flow.onTrade(true, 500_000, t + 1_500_000);           // buy-aggressor print
flow.onTrade(true, 400_000, t + 2_000_000);

long now = t + 3_000_000;
System.out.printf("OFI %.3f, queue imbalance %+.3f, trade imbalance %+.3f%n",
        flow.ofi(now), flow.queueImbalance(), flow.tradeImbalance());
System.out.printf("quotes %d, trades %d%n", flow.quoteCount(), flow.tradeCount());
```

The three imbalances answer different questions and decay on their own clock: order-flow imbalance reads the churn of bid and ask sizes (adds and pulls at the touch), queue imbalance is the instantaneous size lean, and trade imbalance is the signed pressure the tape actually printed. Passing `System.nanoTime()` into `ofi(now)` lets the caller read a decayed value at an arbitrary instant without a spurious event, so a quoter can poll the signal between ticks; feed the aggressor side from `TradeClassifier` when the venue does not disclose it.

## 153. Score a passive order's fill probability

A passive order fills only if the price reaches the level and the queue clears to you. `FillProbabilityModel` composes those two, and when you have no L3 feed to track your exact slot, `QueuePositionEstimator` maintains the standard L2 estimate the composed model needs.

```java
var q = new QueuePositionEstimator();
q.join(12_000, 1_000);               // join the back of a 12,000-share level, our 1,000

q.onTrade(4_000);                    // trades hit the front: ahead -= 4,000
q.onLevelResize(6_500);              // a cancel, net of trades already reported
System.out.printf("ahead ~%.0f, progress %.2f, queue clears %.2f%n",
        q.sharesAhead(), q.queueProgress(), q.fillProbability(15_000));

// The full placement score for a level 2 cents away: touch x queue-clear.
double p = FillProbabilityModel.passiveFillProbability(
        0.02,        // distance to the level, price units
        2e-4,        // vol per sqrt(second) (SignalEngine.volPerSqrtSecond)
        60,          // horizon: 60 seconds
        100.00,      // current price
        (long) q.sharesAhead(), 1_000,
        15_000);     // expected volume at the level over the horizon
System.out.printf("fill probability at the level: %.2f%n", p);
```

The feed-ordering contract matters: report each trade via `onTrade` before the depth update that reflects it, and give `onLevelResize` sizes net of trades already reported -- otherwise the same execution counts once as a trade and again as a cancel, and shares-ahead falls twice per fill. The composed probability is a mild underestimate because touch and queue-clearing are positively correlated, so treat it as a conservative placement score rather than a calibrated number, and the exact L3 alternative of recipe 122 when your feed carries individual orders.

## 154. Diagnose mean reversion and cross-asset lead-lag

Three complementary tests before trading a relationship: `VarianceRatio` decides whether a series even mean-reverts, `OrnsteinUhlenbeck` measures how fast and how stretched it is right now, and `LeadLagEstimator` finds which of two instruments moves first.

```java
// 1. Does the spread mean-revert, or is it a random walk?
double[] spreadReturns = /* first differences of the spread */;
VarianceRatio.Result vr = VarianceRatio.test(spreadReturns, 5);
System.out.printf("VR(5) ratio %.3f z=%.2f -> rejects random walk %b%n",
        vr.ratio(), vr.zStat(), vr.rejectsRandomWalk());

// 2. Characterize the reversion: speed, stationary width, current z-score.
double[] spreadLevel = /* the spread itself */;
OrnsteinUhlenbeck.Params ou = OrnsteinUhlenbeck.fit(spreadLevel, 1.0);   // dt = 1 bar
System.out.printf("kappa %.3f, half-life %.1f bars, stationary sd %.4f, z now %.2f%n",
        ou.kappa(), ou.halfLife(), ou.stationaryStdev(),
        OrnsteinUhlenbeck.lastZScore(spreadLevel, 1.0));

// 3. Which instrument leads? Feed aligned return pairs.
LeadLagEstimator ll = new LeadLagEstimator(10, 0.05);   // max lag 10, EWMA alpha
for (int i = 0; i < leaderRet.length; i++) {
    ll.onSample(leaderRet[i], followerRet[i]);
}
System.out.printf("best lag %d bars, correlation %.3f%n",
        ll.bestLag(), ll.bestCorrelation());
```

The three are a pipeline, not alternatives: a variance ratio below 1 with a rejecting z-stat says the spread is mean-reverting rather than trending, which is the precondition for the OU fit to mean anything -- a half-life on a random walk is noise. `lastZScore` is the tradable stretch given the fitted parameters, the entry trigger a pairs strategy watches. `LeadLagEstimator.expectedFollowerReturn()` is a raw return forecast, so it cannot go directly into a normalized alpha input without your own rescaling and validation -- a cross-asset signal earns no free pass on the IC gate.

## 155. Grade an execution three ways after the fact

Post-trade truth from three regulatory-grade tools: `TransactionCostAnalyzer` benchmarks the fills against arrival and VWAP, `MarketQualityMetrics` decomposes the spread into what the maker kept versus what the move took, and `BestExecutionAnalyzer` aggregates outcomes into the report a compliance file needs.

```java
var fills = List.of(
        new Execution("ACME", Side.BUY, 100.02, 4_000, t1, "EXCH_B"),
        new Execution("ACME", Side.BUY, 100.05, 3_000, t2, "EXCH_A"),
        new Execution("ACME", Side.BUY, 100.04, 3_000, t3, "DARK_X"));
double[] midAtFill = {100.00, 100.03, 100.04};

var tca = TransactionCostAnalyzer.analyze(fills, 99.98, 100.06, midAtFill);
System.out.printf("avg %.4f, IS %.1f bps, vs VWAP %.1f bps, eff spread %.1f bps%n",
        tca.avgExecutionPrice(), tca.implementationShortfallBps(),
        tca.slippageVsVwapBps(), tca.avgEffectiveSpreadBps());

// Spread decomposition on one fill: effective ~ realized + impact.
double midAfter = 100.06;
System.out.printf("quoted %.1f  effective %.1f = realized %.1f + impact %.1f%n",
        MarketQualityMetrics.quotedSpreadBps(100.00, 100.04),
        MarketQualityMetrics.effectiveSpreadBps(Side.BUY, 100.02, 100.00),
        MarketQualityMetrics.realizedSpreadBps(Side.BUY, 100.02, midAfter),
        MarketQualityMetrics.priceImpactBps(Side.BUY, 100.00, midAfter));

var bx = new BestExecutionAnalyzer()
        .add(new BestExecutionAnalyzer.OrderOutcome("ACME-1", "EXCH_B", Side.BUY,
                10_000, 99.98, 100.037, 90_000, true));
System.out.println(bx.report().avgSlippageBps());
```

All costs are signed so positive means cost to the trader, buys and sells alike -- beating VWAP shows up as a negative number. The decomposition identity (effective spread is approximately realized spread plus price impact) separates the two reasons a fill was expensive: realized spread is what the maker kept, price impact is the move your own trading or information caused, and a negative realized spread with large impact is the adverse-selection signature recipe 149 prices per venue. `BestExecutionAnalyzer` rolls many such outcomes into the fill rate, median latency and per-venue slippage the compliance report of recipe 118 renders.

## 156. Price the exotics shelf and check it with in-out parity

Digitals, touches, and barriers all sum back to something vanilla you can
price without them -- that identity is the first debugging tool when an
exotic number looks wrong, because the parity is vol-free but the exotic
is not.

```java
import com.quantfinlib.pricing.BlackScholes.OptionType;

double spot = 100, rate = 0.03, carry = 0.01, vol = 0.20, t = 1.0;

// DIGITAL cash-or-nothing: pays a fixed amount if in-the-money at expiry.
double digiCall = DigitalOption.cashOrNothing(OptionType.CALL, spot, 100, rate, carry, vol, t, 1.0);
double digiPut  = DigitalOption.cashOrNothing(OptionType.PUT,  spot, 100, rate, carry, vol, t, 1.0);
// Parity: a call + a put both paying 1.0 = a guaranteed 1.0 = the bond.
double bond = Math.exp(-rate * t);                 // digiCall + digiPut == bond

// TOUCH: pays if the barrier is EVER hit (one-touch) or NEVER (no-touch).
double oneTouch = TouchOption.oneTouch(spot, 120, rate, carry, vol, t, 1.0);
double noTouch  = TouchOption.noTouch(spot, 120, rate, carry, vol, t, 1.0);
double hitProb  = TouchOption.hitProbability(spot, 120, rate, carry, vol, t);
// Parity: hit-or-never is a certain payout. oneTouch + noTouch == 1.0 * bond.

// BARRIER: knock-in + knock-out = the vanilla, because you own exactly one.
double di   = BarrierOption.downAndInCall(spot, 100, 80, rate, carry, vol, t);
double dout = BarrierOption.downAndOutCall(spot, 100, 80, rate, carry, vol, t);
double call = BlackScholes.price(OptionType.CALL, spot, 100, rate, carry, vol, t);
System.out.printf("in-out gap %.2e%n", (di + dout) - call);   // machine noise
```

Desks quote a one-touch AS a discounted hit probability, which is exactly
what `hitProbability` exposes -- the model is the reflection principle, not
a smile. The three parities share one shape: two complementary exotics
rebuild a payout that has no optionality, so the vol cancels and any gap
larger than floating-point noise is a bug in the inputs. Touches here pay
at expiry (the vanna-volga convention); a pay-at-hit rebate discounts to
the hitting time instead, which the class states rather than approximates.

## 157. Calibrate SABR and read the smile off rho and nu

A pillar smile is a table; SABR turns it into three numbers that
inter/extrapolate arbitrage-aware -- and two of them, rho and nu, ARE the
smile's shape in plain language.

```java
double forward = 100, expiry = 1.0, beta = 0.5;   // beta fixed, market convention
double[] strikes    = {80, 90, 100, 110, 120};
double[] marketVols = {0.245, 0.220, 0.200, 0.205, 0.225};   // a smile with a skew

SabrModel.Params p = SabrModel.calibrate(forward, expiry, beta, strikes, marketVols);
System.out.printf("alpha=%.4f rho=%.3f nu=%.3f rmse=%.4f%n",
        p.alpha(), p.rho(), p.nu(), p.rmse());

// rho < 0 is the equity skew (down-strikes bid); nu is the smile's CURVATURE
// (vol-of-vol -- how fast the wings lift). Read a vol at any strike now:
double vol95 = SabrModel.impliedVol(forward, 95, expiry, p.alpha(), beta, p.rho(), p.nu());
double vol130 = SabrModel.impliedVol(forward, 130, expiry, p.alpha(), beta, p.rho(), p.nu());
```

The parameters map one-to-one to what a trader sees: alpha sets the ATM
level, rho tilts the smile (negative for equities where a sell-off lifts
vol, near zero for a symmetric FX smile), and nu bows it (a bigger nu
means fatter wings and more vol-of-vol premium). The fit is a seeded
derivative-free search, so it is deterministic -- the same quotes always
return the same params -- and `rmse` in vol points is the honesty check:
a wide RMSE means the five quotes do not live on one SABR smile, not that
the search failed.

## 158. Cross-check Heston against its own Monte Carlo

A closed-form price you cannot reproduce by simulation is a formula you do
not yet trust. Heston ships both the semi-analytic call and the Euler MC
that must converge to it -- running them together is how you certify the
characteristic-function integration before it prices real risk.

```java
Heston.Params p = new Heston.Params(2.0, 0.04, 0.30, -0.7, 0.04);
//                                  kappa theta sigmaV  rho  v0
System.out.printf("Feller 2*kappa*theta - sigmaV^2 = %.4f (>0 keeps var>0)%n", p.feller());

double spot = 100, strike = 100, rate = 0.02, divYield = 0.0, t = 1.0;

double analytic = Heston.call(spot, strike, rate, divYield, t, p);
double mc = Heston.callMonteCarlo(spot, strike, rate, divYield, t, p, 200, 200_000, 42);
System.out.printf("analytic %.4f  MC %.4f  gap %.3f%n", analytic, mc, mc - analytic);
```

The two prices agreeing to within MC standard error is the test that
actually matters: it pins the Fourier integration (the part that hides
numerical bugs) against a method that makes no such assumptions. The
negative rho bakes in the leverage effect -- spot down, vol up -- so the
Heston call carries a skew a flat Black-Scholes vol cannot; the Feller
condition `2*kappa*theta >= sigmaV^2` is the guardrail that keeps the
simulated variance from hitting zero, and reading it before you trust the
MC is cheaper than debugging a path that went negative.

## 159. Read an RFQ auction as post-trade TCA

The panel-learning loop lives in recipe 13; this is the other half -- what
the `RfqAuction` object tells you AFTER the deal, the numbers a
best-execution review reads to grade the dealers.

```java
// Price the note first (the downside-smile vol -- the knock-in put lives there):
Autocallable note = new Autocallable(1_000_000, new double[]{0.25, 0.5, 0.75, 1.0},
        1.00, 0.80, 0.60, 0.02, true);
double fair = note.price(spot, initial, vol, rate, divYield, 200_000, 42);

RfqAuction rfq = new RfqAuction(true, fair, 4, nowNanos);   // client buys, 4 dealers
rfq.onQuote(0, fair + 1_800, nowNanos + 40_000_000L);       // dealer, price, timestamp
rfq.onQuote(1, fair + 1_200, nowNanos + 55_000_000L);
rfq.onQuote(2, fair + 2_400, nowNanos + 30_000_000L);
// dealer 3 never responds

System.out.printf("won dealer %d at %.0f (cover %.0f)%n",
        rfq.winner(), rfq.bestPrice(), rfq.coverPrice());
System.out.printf("paid %.1f bps to fair; cover would have cost %.1f bps%n",
        rfq.winnerSpreadToFairBps(), rfq.spreadToFairBps(rfq.coverPrice()));
System.out.printf("winner responded in %.0f ms; %d of %d quoted%n",
        rfq.responseNanos(rfq.winner()) / 1e6, rfq.quoteCount(), rfq.dealerCount());
```

`coverPrice` is the second-best -- the price you would have paid without the
winner, so the gap `winnerSpreadToFairBps` minus the cover's spread is the
value that dealer's presence added on THIS trade. Response time and
quote-count are the other axes: a dealer who wins on price but answers in
55 ms on a fast market is a different risk than one who answers in 30, and
the non-responder (dealer 3) is a quote-rate ding the scorecard remembers.
Feed the finished auction to the panel (recipe 13) and these per-trade
numbers become tomorrow's dealer ranking.

## 160. Price a reverse convertible as a bond minus a put

A "9% coupon" note in a 3% world is not generosity -- it is put premium in
disguise. Price it by the decomposition and the margin becomes visible:
par bond plus fat coupon, minus the put the investor unknowingly sold.

```java
double par = 1000, coupon = 0.09, spot = 100, strike = 100;
double rate = 0.03, carry = 0.01, vol = 0.28, t = 1.0;

double fair = StructuredNotes.reverseConvertible(par, coupon, spot, strike, rate, carry, vol, t);
double delta = StructuredNotes.reverseConvertibleDelta(par, spot, strike, rate, carry, vol, t);
System.out.printf("fair value %.2f, delta %.2f (holder is LONG the stock)%n", fair, delta);

// The margin the issuer keeps = issue price (par) - fair value:
System.out.printf("issuer margin %.2f per note%n", par - fair);
```

The value is `par * (1 + coupon) * DF - (par/K) * put(K)`: the coupon is
real, but so is the short put struck at K, and if the stock finishes below
K the investor is delivered shares worth less than par. The delta is
negative-of-a-short-put, i.e. positive -- the holder is long the underlying
whether they meant to be or not, which is why these notes are sold as
"income" and behave as "equity". Pricing by replication puts the issuer
margin (issue price minus fair value) in plain sight; a knock-in variant
needs a down-and-in put, which this method states it does not yet price
closed-form rather than faking.

## 161. Solve the participation rate a protected note can afford

The capital-protected note's one free design variable is the participation
rate, and it is not chosen -- it is whatever the budget leaves after the
bond floor is paid for. Invert the pricing to read it.

```java
double par = 1000, protection = 1.00, issuePrice = 1000;   // 100% protected, sold at par
double spot = 100, rate = 0.03, carry = 0.0, vol = 0.20, t = 5.0;

double participation = StructuredNotes.participationFor(
        par, protection, issuePrice, spot, rate, carry, vol, t);
System.out.printf("affordable participation: %.0f%%%n", participation * 100);

// Price the note the desk actually issues at that rate and confirm it prices to par:
double value = StructuredNotes.capitalProtectedNote(
        par, protection, participation, spot, rate, carry, vol, t);
System.out.printf("value at that participation: %.2f (== issue price)%n", value);
```

The floor `protection * par * DF` is a zero-coupon bond, and the
participation is `(issuePrice - floor) / ((par/S0) * call)` -- everything
left over buys upside. That is why these notes flourish when rates are
high (a cheap bond floor leaves a fat option budget) and embarrass
themselves at zero rates (the floor eats the whole issue price and
participation collapses toward nothing). The method throws rather than
return a negative participation when the issue price cannot even cover the
floor -- an unaffordable note is a refusal, not a number.

## 162. Price a discount certificate as a covered call

Buy the underlying below spot, give away everything above a cap: that is a
covered call wearing a retail costume, and the discount you receive is
exactly the call premium you sold.

```java
double spot = 100, cap = 115, rate = 0.03, carry = 0.01, vol = 0.22, t = 1.0;

double value = StructuredNotes.discountCertificate(spot, cap, rate, carry, vol, t);
double delta = StructuredNotes.discountCertificateDelta(spot, cap, rate, carry, vol, t);
System.out.printf("fair value %.2f (discount to spot %.2f), delta %.2f%n",
        value, spot - value, delta);
```

The value is `S * e^{-qT} - call(cap)`: long the dividend-adjusted stock,
short the call struck at the cap. The discount to spot IS the premium
received for capping the upside -- a wider cap means a cheaper call, a
smaller discount, and more retained upside, the single trade-off the
product expresses. The delta, `e^{-qT} - delta(call)`, starts near 1 and
falls toward 0 as spot climbs through the cap: the certificate stops
tracking the stock exactly where the covered call goes fully short, which
is the payoff diagram the buyer is really holding.

## 163. Price a geometric and an arithmetic Asian

Averaging options kill the terminal-spike risk of a vanilla, and the
average's TYPE decides whether you get a closed form. The geometric mean
is lognormal (exact); the arithmetic mean is not (a moment-matched
approximation) -- and the gap between them is a pricing fact, not an error.

```java
import com.quantfinlib.pricing.BlackScholes.OptionType;

double spot = 100, strike = 100, rate = 0.05, carry = 0.02, vol = 0.30, t = 1.0;
int points = 52;                                   // weekly averaging

double geo = AsianOption.geometricPrice(OptionType.CALL, spot, strike, rate, carry, vol, t, points);
double ari = AsianOption.arithmeticPrice(OptionType.CALL, spot, strike, rate, carry, vol, t, points);
System.out.printf("geometric %.4f  arithmetic %.4f  (geo < ari)%n", geo, ari);
```

Both sit well below the vanilla because averaging shrinks the effective
volatility of the payoff -- an Asian never sees the single lucky (or
unlucky) close a European lives or dies on. The geometric price is exact
Black-Scholes on the geometric-mean distribution; the arithmetic price is
strictly higher (arithmetic mean >= geometric mean, always) and is a
moment-matched lognormal approximation, the industry standard for a payoff
with no closed form. Use the geometric number as the control variate when
you graduate to Monte Carlo for path-dependent variants -- the exact leg
cancels most of the simulation error.

## 164. Price a variance swap and see the vol-swap convexity

Recipe 23 walks the full variance-swap book; this is the one number it
turns on -- why a VOL swap always strikes below the square root of the
variance strike, and why that gap is a desk's model risk.

```java
double forward = 100, rate = 0.02, tYears = 1.0;
int n = 29;
double[] strikes = new double[n], puts = new double[n], calls = new double[n];
for (int i = 0; i < n; i++) {
    strikes[i] = 30 + 5.0 * i;                     // wide wings: the 1/K^2 weight needs them
    puts[i]  = Black76.price(OptionType.PUT,  forward, strikes[i], rate, 0.20, tYears);
    calls[i] = Black76.price(OptionType.CALL, forward, strikes[i], rate, 0.20, tYears);
}

double kVar = VarianceSwap.fairVariance(strikes, puts, calls, forward, rate, tYears);   // 0.04
double naiveVol = Math.sqrt(kVar);                 // 0.20 -- the WRONG vol-swap strike
double kVol = VarianceSwap.volSwapStrike(kVar, 0.004);   // Var(V) from your vol-of-vol model
System.out.printf("var strike %.4f  sqrt %.4f  vol-swap strike %.4f (below)%n",
        kVar, naiveVol, kVol);
```

The fair variance is model-free -- the 1/K^2 option portfolio replicates it
straight off the chain -- but the vol swap is not, because volatility is a
concave function of variance and Jensen's inequality drags its fair strike
BELOW `sqrt(kVar)`. The size of that convexity correction scales with
vol-of-vol: the more variance itself moves, the wider the gap, and that
gap is exactly the model-dependent charge a vol-swap desk books and a
variance-swap desk sidesteps. Quote the wrong one at `sqrt(kVar)` and you
are systematically overpaying every vol-swap seller.

## 165. Spread options two ways: Margrabe and Kirk

An option to exchange one asset for another has a clean closed form when
the strike is zero (Margrabe) and a very good approximation when it is not
(Kirk). Price both and watch the correlation do the work.

```java
// MARGRABE: the right to give up asset 2 and receive asset 1 (strike 0).
double s1 = 100, s2 = 95, q1 = 0.01, q2 = 0.02;    // spots and dividend yields
double vol1 = 0.25, vol2 = 0.30, rho = 0.4, t = 1.0;
double exchange = ExchangeOption.margrabe(s1, s2, q1, q2, vol1, vol2, rho, t);

// KIRK: a spread call on F1 - F2 with a real strike, the energy-desk workhorse.
double f1 = 100, f2 = 95, strike = 3, rate = 0.03;
double spread = ExchangeOption.kirkSpreadCall(f1, f2, strike, rate, vol1, vol2, rho, t);
System.out.printf("Margrabe %.4f  Kirk spread call %.4f%n", exchange, spread);
```

Both price off the SPREAD volatility `sigmaHat^2 = vol1^2 + vol2^2 -
2*rho*vol1*vol2`, which is the whole story: raise the correlation and the
two legs move together, the spread stops moving, and both options get
cheaper -- a spread option is a bet on DECORRELATION. Margrabe is exact
because a zero-strike exchange reduces to Black-Scholes on the ratio;
Kirk approximates the non-lognormal spread with a lognormal proxy around
the strike, accurate for the near-the-money spreads energy and crack
desks actually trade, and it is the natural partner to the
`HigherOrderGreeks.exchangeCrossGamma` in recipe 167.

## 166. Apply the quanto adjustment

A quanto pays a foreign asset's return in your currency at a fixed FX rate
-- so the asset's drift picks up a correction that depends entirely on the
asset-FX correlation. Get the sign of that correction right or the whole
trade is mispriced.

```java
double spot = 3000, domesticRate = 0.03, assetYield = 0.01;
double assetVol = 0.20, fxVol = 0.10, rho = 0.3, t = 1.0;

// The quanto forward carries the -rho*sigmaAsset*sigmaFx drift correction:
double qFwd = QuantoOption.quantoForward(spot, domesticRate, assetYield, assetVol, fxVol, rho, t);

double strike = 3000;
double price = QuantoOption.price(OptionType.CALL, spot, strike,
        domesticRate, assetYield, assetVol, fxVol, rho, t);
System.out.printf("quanto forward %.2f, quanto call %.2f%n", qFwd, price);
```

The adjustment is `-rho * assetVol * fxVol`: when the foreign asset and the
FX rate are positively correlated, the quanto investor is effectively
short that covariance and the asset's risk-neutral drift is reduced,
lowering the forward and the call. Flip the correlation sign and the
correction flips with it -- which is why a quanto on an index that tends to
rally when its currency strengthens prices differently from one where they
move opposite. Everything else is Black-Scholes in the domestic measure;
the correlation term is the only piece that makes it a quanto.

## 167. Take the second-order Greeks on a big position

A delta- and vega-hedged book is not a flat book. Vanna and volga are the
risks that leak through a first-order hedge exactly when the market makes
its characteristic move -- spot down, vol up -- so a real vol desk sizes
them in position dollars, not per-unit.

```java
double spot = 100, strike = 100, rate = 0.03, carry = 0.01, vol = 0.20, t = 0.5;
double contracts = 5_000;                          // the position, in options

double vanna = HigherOrderGreeks.vanna(spot, strike, rate, carry, vol, t);
double volga = HigherOrderGreeks.volga(spot, strike, rate, carry, vol, t);
System.out.printf("position vanna %,.0f per (1 spot x 1 vol)%n", vanna * contracts);
System.out.printf("position volga %,.0f per (1 vol)^2%n", volga * contracts);

// Two-asset book? The cross-gamma plain per-leg gammas miss:
double xgamma = HigherOrderGreeks.exchangeCrossGamma(100, 95, 0.25, 0.30, 0.4, t);
```

Vanna (`d2V/dS dSigma`) is how your delta drifts when vol moves -- the
skew-hedging Greek: a delta-hedged book with vanna is unhedged through the
spot-vol move equity markets actually make. Volga (`d2V/dSigma2`) is vega
convexity: a vega-hedged book with volga re-exposes itself the instant vol
moves, which is why these two are precisely what the vanna-volga method
charges the smile for. Both are identical for calls and puts (parity kills
the sign at second order), and the cross-gamma is the two-asset P&L term
per-asset gammas cannot see -- closed-form for the Margrabe case, and a cue
to differentiate YOUR pricer numerically for a general basket.

## 168. Keep Greeks tick-fresh without repricing every tick

Full Black-Scholes on every tick is how a risk display falls behind the
market. Anchor once, then ride the delta-gamma Taylor expansion on the hot
path -- two multiplies per tick, zero allocation -- and re-anchor only when
spot has actually drifted.

```java
IncrementalGreeks pos = new IncrementalGreeks();
pos.reprice(OptionType.CALL, 100.0, 100, 0.03, 0.01, 0.20, 0.25);   // the slow-path anchor

// The tick loop -- no transcendental math, no allocation:
double[] ticks = {100.05, 100.12, 100.03, 100.20, 100.31};
for (double s : ticks) {
    pos.onTick(s);
    if (pos.needsReprice(0.5)) {                   // spot drifted > 0.5 from the anchor
        // hand off to the pricing thread: pos.reprice(...) with fresh vol/rate
    }
}
System.out.printf("price ~%.4f  delta ~%.4f  gamma %.4f (anchor)%n",
        pos.estimatedPrice(), pos.estimatedDelta(), pos.gamma());
```

The expansion is `price0 + delta0*dS + 0.5*gamma0*dS^2` and `delta0 +
gamma0*dS`, whose error is O(dS^3) -- negligible for the sub-0.5% moves
between re-anchors and precisely why live options risk stays fresh this
way. Gamma, vega, and theta are the anchor's (they only change on
reprice, not per tick), and `needsReprice` is the clean split: the tick
thread reads a flag-style comparison and never re-anchors itself, while
the pricing thread owns the expensive `reprice` off the hot path. One
instance per position per risk thread, single-threaded by design.

## 169. Build an FX vol smile from broker quotes

FX vol does not arrive as strikes -- it arrives as ATM, risk reversal, and
butterfly per tenor, the three numbers a broker actually publishes. The
surface builder turns those into a strike-space smile you can read a vol
off at any delta.

```java
FxVolSurface surface = FxVolSurface.builder()
        .add(0.25, 1.0850, 0.085, -0.012, 0.0025)   // 3M: fwd, atm, rr25, bf25
        .add(0.50, 1.0870, 0.090, -0.015, 0.0030)   // 6M
        .add(1.00, 1.0900, 0.095, -0.018, 0.0035)   // 1Y
        .premiumAdjusted(true)
        .build();

// A vol at any expiry/strike (bilinear in the smile space):
double vol = surface.vol(0.50, 1.10);
double atm = surface.atmVol(0.50);

// Recover the strike for a given delta -- the broker quotes in delta, not strike:
double k25c = FxVolSurface.strikeForDelta(surface.forwardAt(0.50), atm, 0.50, 0.25, true, true);
System.out.printf("6M ATM %.4f, vol at 1.10 = %.4f, 25d call strike %.4f%n", atm, vol, k25c);
```

The risk reversal is the smile's SKEW (25-delta call vol minus put vol --
negative here means the market pays up for downside protection on the base
currency) and the butterfly is its CURVATURE (how far the wings sit above
ATM). The builder reconstructs the three market strikes from those quotes
using the delta-to-strike map, so the surface prices back the exact vols
the broker gave you at the pillars. Premium-adjustment matters for
currencies where the option premium is paid in the base currency (the
delta convention shifts) -- flag it once at build and every strike lookup
respects it.

## 170. Route an FX clip across tiered LPs by all-in cost

A tight quote from a liquidity provider that rejects one clip in five --
and picks you off on the reject -- is more expensive than a wider firm
price. The router prices last-look behavior INTO the decision, so the
cheapest displayed quote does not automatically win.

```java
FxTierBook book = new FxTierBook(2, 2);            // 2 LPs, up to 2 tiers each
book.tier(0, false, 0, 1.08502, 5_000_000); book.tierCount(0, false, 1);   // LP0 ask, tight
book.tier(1, false, 0, 1.08504, 5_000_000); book.tierCount(1, false, 1);   // LP1 ask, wider

LpScorecard card = new LpScorecard(2, 1.0, 100_000_000L);   // lps, alpha, markout horizon ns
LpRouter router = new LpRouter(book, card, 0.25);  // veto any LP above a 25% reject rate

// LP0 rejects and the market runs 30 pips against you afterward:
card.onReject(0, true, 1.08500, 0L, 1_000_000L);
card.onMid(1.08530, 100_000_000L);                 // matures the markout (feed mids on the clock!)

int lp = router.route(true, 1_000_000);            // buy 1mm
System.out.printf("routed to LP%d: displayed %.5f, expected all-in %.5f%n",
        lp, router.lastQuotedPrice(), router.lastExpectedPrice());
```

The expected cost is `quoted +/- rejectRate * max(postRejectMarkout, 0)`
-- worse for the taker on either side -- so LP0's 1.08502 display grosses up
to 1.08532 once its reject-and-run behavior is priced, losing to LP1's
firm 1.08504. The wiring requirement is load-bearing: `card.onMid` must be
fed composite mids on the same clock as the rejects, or markouts never
mature, the penalty is silently zero, and routing degrades to
displayed-price-plus-veto. Watch `card.maturedMarkouts()` in monitoring --
zero while rejects accrue means the hook is missing. Full-amount, one LP
per clip: the FX convention that avoids leaking intent into every
counterparty's last-look window.

## 171. Book and settle a non-deliverable forward

An NDF cash-settles in the deliverable currency against an official fixing
-- so two dates matter and they differ, and the settlement formula divides
by the fixing to convert the difference back into deliverable currency.
Book it, mark it, settle it.

```java
CurrencyPair usdinr = CurrencyPair.of("USDINR");
LocalDate trade = LocalDate.of(2026, 1, 7);
Ndf ndf = Ndf.of(usdinr, trade, "1M", 84.50, 1_000_000);   // long 1mm USD at 84.50
System.out.printf("fixing %s settles %s (lag %d local days)%n",
        ndf.fixingDate(), ndf.settlementDate(), Ndf.fixingLagDays("INR"));

// Mark it mid-life off the swap-points curve (undiscounted, then discounted):
SwapPointsCurve curve = SwapPointsCurve.builder(usdinr, trade, 84.20)
        .add("1M", 15.0).build();
double mtm = ndf.markToMarket(curve);
YieldCurve usd = YieldCurve.ofZeroRates(new double[]{0.25}, new double[]{0.05});
double discounted = ndf.markToMarket(curve, usd);

// At the fixing print, the curve cannot help -- only the official rate settles it:
double settlement = ndf.settlementAmount(85.10);   // RBI reference prints 85.10
System.out.printf("MTM %.0f (disc %.0f), settled %.0f%n", mtm, discounted, settlement);
```

Settlement to the base buyer is `baseNotional * (fixing - contractRate) /
fixing` -- the division converts the quote-currency difference back into
deliverable USD, which is why NDF desks still quote points like a
deliverable forward. The fixing date is walked back from settlement by the
restricted currency's lag counted in LOCAL business days: an RBI or KFTC
or PTAX fixing publishes on its own calendar regardless of USD holidays,
and passing real holiday calendars via `withCalendars` gets production
dates right. Inside the fixing window the mark degrades to the spot
outright rather than throwing -- but once the official rate prints, only
`settlementAmount` with the actual fixing is correct; a curve cannot know
it.

## 172. Book an FX swap and mark it daily

An FX swap is a near leg and a far leg in opposite directions -- so its risk
is not spot, it is the POINTS between the two dates. Book it at market,
then re-mark it as the curve moves and watch the exposure isolate to the
forward differential.

```java
CurrencyPair eurusd = CurrencyPair.of("EURUSD");
LocalDate trade = LocalDate.of(2026, 1, 7);
SwapPointsCurve struck = SwapPointsCurve.builder(eurusd, trade, 1.0850)
        .add("1M", 12.6).add("3M", 38.4).build();

FxSwap swap = FxSwap.atMarket(struck, "SPOT", "3M", 10_000_000);   // spot vs 3M, 10mm
System.out.printf("swap points %.1f pips, MTM on own curve %.4f%n",
        swap.swapPointsPips(), swap.markToMarket(struck));   // 38.4, ~0

// Points widen to 45.0 with spot unchanged: the short-far leg loses.
SwapPointsCurve wider = SwapPointsCurve.builder(eurusd, trade, 1.0850)
        .add("1M", 12.6).add("3M", 45.0).build();
double mtm = swap.markToMarket(wider);
YieldCurve usd = YieldCurve.ofZeroRates(new double[]{0.25, 1}, new double[]{0.05, 0.05});
System.out.printf("points +6.6 pips -> MTM %.0f (disc %.0f)%n", mtm, swap.markToMarket(wider, usd));
```

An at-market swap values to zero on its own curve, and its traded
differential IS the far-tenor points -- 38.4 here. Move the points and hold
spot: the far forward rises 6.6 pips, the short 10mm base forward loses
`10mm * 0.00066`, and the near-spot leg (which only sees spot) does not
move, so the MTM isolates exactly to the points change. Discounting shrinks
the magnitude but never the sign. A forward-forward (1M vs 3M) carries
exposure ONLY to the points between its legs -- a parallel spot move with
unchanged points leaves it flat -- and an aged swap whose near leg has
settled keeps marking on the far leg alone, which is routine daily P&L, not
an error.

## 173. Compute a cross rate and check it for triangular arbitrage

Three currencies, three quotes, one consistency condition: the cross must
equal the product of its legs, or a riskless loop exists. The one-shot math
lives in `TriangularArbitrage`; the streaming version rides the tick bus.

```java
// EUR/JPY implied from EUR/USD and USD/JPY (shared middle currency, multiply):
var eurusd = new TriangularArbitrage.Quote(1.0850, 1.0851);
var usdjpy = new TriangularArbitrage.Quote(150.00, 150.02);
var eurjpy = new TriangularArbitrage.Quote(162.70, 162.74);   // the quoted cross

double impliedMid = TriangularArbitrage.impliedCrossMid(eurusd, usdjpy);
double edgeBps = TriangularArbitrage.arbitrageBps(eurusd, usdjpy, eurjpy);
boolean live = TriangularArbitrage.exists(eurusd, usdjpy, eurjpy, 0.5);   // > 0.5bp threshold
System.out.printf("implied cross mid %.4f, edge %.2f bps, tradeable %b%n",
        impliedMid, edgeBps, live);

// Streaming: keep the synthetic cross live off leg ticks (consumer thread):
// CrossRateEngine engine = new CrossRateEngine(bus);
// engine.addCross("EURUSD", "USDJPY", "EURJPY", CrossRateEngine.Op.MULTIPLY, listener);
```

The implied cross is `EURUSD * USDJPY` for legs sharing the middle
currency, and `arbitrageBps` measures how far the quoted cross has drifted
from that -- the loop's edge after crossing all three spreads. `exists`
applies your threshold: below the round-trip transaction cost the "arb" is
a mirage, above it a real (and fleeting) loop. `CrossRateEngine` is the
streaming counterpart -- it maintains the synthetic cross from leg ticks
with zero per-tick allocation, delivering updates on the bus consumer
thread through a listener rather than re-publishing (the ring is
single-producer), so downstream indicators chain off the cross exactly as
off a native symbol.

## 174. Bootstrap a yield curve two ways and read it

Money-market deposits are already zero rates; par swap quotes are not --
they must be bootstrapped, each pillar solved so the swap reprices to par.
Build the curve either way, then read discount factors, zeros, and forwards
off one object.

```java
// Deposits / already-zero pillars: feed them straight in.
YieldCurve fromZeros = YieldCurve.ofZeroRates(
        new double[]{0.25, 0.5, 1, 2, 5, 10},
        new double[]{0.030, 0.031, 0.032, 0.034, 0.036, 0.038});

// Par swap quotes: bootstrap pillar by pillar.
int[] tenors = {1, 2, 3, 5, 7, 10, 30};
double[] parRates = {0.0320, 0.0335, 0.0345, 0.0360, 0.0370, 0.0380, 0.0400};
YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(tenors, parRates);

System.out.printf("DF(5y)=%.4f  zero(5y)=%.4f  fwd(4y,5y)=%.4f%n",
        curve.discountFactor(5), curve.zeroRate(5), curve.forwardRate(4, 5));
System.out.println("pillars: " + curve.tenors());
```

`ofZeroRates` takes continuously-compounded zeros directly -- the right
entry for deposits and any curve you already hold in zero space --
while `bootstrapAnnualParSwaps` walks the par quotes so each swap prices to
par at its pillar, the standard construction from a swap desk's screen. The
forward rate between two dates is the curve's view of the future short
rate, and an upward-sloping par curve produces forwards ABOVE the zeros
(the classic carry-and-roll picture). This one object is the input to
nearly everything downstream: swap pricing (recipe 177), key-rate hedging
(178), rates vol (recipe 24), credit discounting (180), and CVA (182).

## 175. Fit Nelson-Siegel and read level, slope, and curvature

A whole curve in four parameters, three of which a macro desk reads
directly: level, slope, and curvature. Fit it to the pillars and the fit
becomes a language for talking about the curve's SHAPE, not its points.

```java
double[] tenors = {0.25, 0.5, 1, 2, 3, 5, 7, 10, 20, 30};
double[] zeros  = {0.030, 0.031, 0.033, 0.035, 0.036, 0.038, 0.039, 0.040, 0.042, 0.043};

NelsonSiegel.Fit fit = NelsonSiegel.fit(tenors, zeros);
System.out.printf("level b0=%.4f  slope b1=%.4f  curvature b2=%.4f  lambda=%.4f%n",
        fit.b0(), fit.b1(), fit.b2(), fit.lambda());
System.out.printf("short end %.4f  long end %.4f  rmse %.5f%n",
        fit.shortRate(), fit.longRate(), fit.rmse());

double z4 = fit.zeroRate(4);                        // smooth interpolation at any tenor
```

`b0` is the long-run level (the rate the curve asymptotes to), `b1` is
minus the slope (so `b0 + b1` is the instantaneous short rate and a
negative `b1` is the normal upward curve), and `b2` is the curvature -- the
hump in the belly, positive when the 5-year sits above the line between the
2s and 10s. `lambda` sets WHERE that hump lives along the maturity axis.
The four parameters give you a parametric, smooth curve that extrapolates
sanely past the last pillar, and `rmse` in rate units is the fit quality:
a plain Nelson-Siegel fits most curves well but cannot capture two humps --
which is exactly what Svensson (recipe 176) adds.

## 176. Fit Svensson for a humped curve

When one curvature term is not enough -- a curve with a second hump at the
long end, the shape central banks publish -- Svensson adds a second decay
factor. Same read-outs as Nelson-Siegel, one more bump of flexibility.

```java
double[] tenors = {0.25, 0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30};
double[] zeros  = {0.028, 0.030, 0.033, 0.036, 0.038, 0.040, 0.041, 0.041, 0.040, 0.039, 0.038};

Svensson.Fit fit = Svensson.fit(tenors, zeros);
System.out.printf("b0=%.4f b1=%.4f b2=%.4f b3=%.4f lambda1=%.4f lambda2=%.4f%n",
        fit.b0(), fit.b1(), fit.b2(), fit.b3(), fit.lambda1(), fit.lambda2());
System.out.printf("short %.4f  long %.4f  rmse %.5f%n",
        fit.shortRate(), fit.longRate(), fit.rmse());
double z12 = fit.zeroRate(12);
```

The extra `b3`/`lambda2` pair is a second curvature term with its own decay
-- it captures the long-end hump this sample carries (rates peaking near 7-10
years, then rolling back down) that a single Nelson-Siegel curvature
cannot bend to. The two levels (`b0`) and slopes (`b1`) mean the same
things as before; you reach for Svensson only when the RMSE of the simpler
fit tells you one hump is being forced to do two humps' work. It is the
standard model for the smooth official curves (the ECB and the Fed publish
Svensson parameters) precisely because real sovereign curves are humped.

## 177. Price and risk a vanilla swap

A par swap is worth zero at inception by construction -- the whole point of
the par rate. Price one struck away from par, and its PV and DV01 fall
straight out of the curve's annuity.

```java
YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(
        new int[]{1, 2, 3, 5, 7, 10}, new double[]{0.032, 0.0335, 0.0345, 0.036, 0.037, 0.038});

int tenor = 5;
double par = SwapPricer.parRate(curve, tenor);      // the fair fixed rate: PV = 0 here
double annuity = SwapPricer.annuity(curve, tenor);  // sum of discount factors

// A swap struck 30bp through par: pay 3.30% fixed, receive floating.
double pv = SwapPricer.payerPv(curve, tenor, 0.0330);
double dv01 = SwapPricer.dv01(curve, tenor, 0.0330);
System.out.printf("par %.4f  annuity %.4f  payerPV %.2f  DV01 %.4f%n", par, annuity, pv, dv01);
```

The par rate is the fixed coupon that makes the swap's PV zero -- price a
swap AT it and `payerPv` returns machine-zero, the identity worth checking
first. Struck below par, a payer (pay-fixed) swap has positive PV because
you are paying less than the market rate for the floating stream, and the
DV01 is the annuity-weighted sensitivity: one basis point on the swap rate
moves the PV by roughly `annuity * notional * 0.0001`. That annuity is the
same numeraire the swaption in recipe 24 discounts against -- the swap and
its option share their plumbing, which is why a rates desk prices them off
one curve.

## 178. Key-rate-duration a bond to find where its risk lives

Parallel DV01 says how much a basis point costs; it does not say WHICH
basis point. Bump one curve node at a time and the key-rate ladder shows
where a bond's rate risk actually concentrates -- which is the hedge.

```java
YieldCurve curve = YieldCurve.bootstrapAnnualParSwaps(
        new int[]{1, 2, 3, 5, 7, 10, 30}, new double[]{0.032, 0.0335, 0.0345, 0.036, 0.037, 0.038, 0.040});

double face = 10_000_000, coupon = 0.04;
int freq = 2, maturity = 10;

double[] krd = KeyRateDurations.keyRateDv01s(100, coupon, freq, maturity, curve);
double parallel = KeyRateDurations.parallelDv01(100, coupon, freq, maturity, curve);

double[] nodes = curve.tenors().stream().mapToDouble(Double::doubleValue).toArray();
double sum = 0;
for (int i = 0; i < krd.length; i++) {
    if (Math.abs(krd[i]) < 1e-6) continue;
    System.out.printf("%4.0fy  KRD/100 %8.4f%n", nodes[i], krd[i]);
    sum += krd[i];
}
System.out.printf("sum(KRD) %.4f == parallel %.4f (consistency check)%n", sum, parallel);
```

The consistency law is `sum(krd) == parallelDv01` within interpolation
tolerance -- if it does not hold, the bumps are wrong, not the bond. A
10-year bullet concentrates its risk at the 10y node with a coupon strip
smeared thinly across the short nodes, so a hedge that cancels only the big
bucket leaves the strip exposed -- which is precisely the shape of a
curve-steepener P&L when the curve twists instead of shifting. Recipe 18
carries this the last mile into per-bucket swap hedge notionals; the ladder
here is the diagnosis that hedge treats.

## 179. Simulate a short-rate model and price off it

A short-rate model gives you two things that must agree: analytic bond
prices and a simulation that reproduces them. Step the SDE, price the bond
in closed form off the same parameters, and check the Feller guard on the
positive-rate variant.

```java
double a = 0.15, b = 0.04, sigma = 0.01, r = 0.03, dt = 1.0 / 252;

// Simulate a year of the Vasicek short rate (mean-reverts to b at speed a):
var rng = new java.util.Random(42);
double path = r;
for (int i = 0; i < 252; i++) path = ShortRateModels.vasicekStep(path, a, b, sigma, dt, rng.nextGaussian());

// Analytic 5y zero off the same params -- the number the simulation must average to:
double P = ShortRateModels.vasicekBond(r, a, b, sigma, 5);
double y = ShortRateModels.vasicekYield(r, a, b, sigma, 5);
System.out.printf("Vasicek P(5y)=%.4f  yield=%.4f  after 1y path r=%.4f%n", P, y, path);

// CIR keeps rates strictly positive WHEN Feller holds:
boolean feller = ShortRateModels.cirFeller(a, b, sigma);       // 2ab >= sigma^2
double cir = ShortRateModels.cirBond(r, a, b, sigma, 5);

// Hull-White fits TODAY'S curve exactly -- dynamics on top of the statics:
double hw = ShortRateModels.hullWhiteBond(curve, 1, 5, r, a, sigma);
```

Vasicek is the clean mean-reverting Gaussian model: `vasicekStep` advances
the rate one `dt` given a standard-normal draw, and averaging enough paths
of `e^{-integral r dt}` converges to `vasicekBond` -- the analytic-versus-MC
check that certifies the simulator, exactly as in the Heston recipe. Its
flaw is that a Gaussian rate can go negative; CIR fixes that with a
square-root diffusion, but only when the Feller condition `2ab >= sigma^2`
holds, which `cirFeller` reports so you check it before trusting a
positive-rate claim. Hull-White is the desk model because it takes today's
bootstrapped `YieldCurve` and reprices it exactly -- the statics from
recipe 174, with dynamics bolted on.

## 180. Bootstrap a credit curve and price a CDS by its legs

Recipe 101 covers the bootstrap's every-pillar-reprices contract; this one
opens the CDS up into its two legs, because that decomposition is what
lets you value an off-market contract and read its upfront.

```java
YieldCurve discount = YieldCurve.ofZeroRates(
        new double[]{1, 2, 3, 5, 7, 10}, new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});
CreditCurve credit = CreditCurve.bootstrap(
        new int[]{1, 3, 5, 7}, new double[]{0.008, 0.012, 0.015, 0.016}, 0.40, discount);

double m = 5;
double parSpread  = CdsPricer.parSpread(credit, discount, m);       // reprices the 5y input
double riskyAnn   = CdsPricer.riskyAnnuity(credit, discount, m);    // survival-weighted DV01
double protection = CdsPricer.protectionLegPv(credit, discount, m);
double premium    = CdsPricer.premiumLegPv(credit, discount, parSpread, m);
System.out.printf("par %.4f  riskyAnnuity %.4f  premiumPV %.4f protectionPV %.4f%n",
        parSpread, riskyAnn, premium, protection);

// Post-2009 contracts run a FIXED 100bp coupon; the difference settles UPFRONT:
double upfront = CdsPricer.upfront(credit, discount, 0.0100, m);
System.out.printf("upfront on the 100bp standard contract: %.4f%n", upfront);
```

At the par spread the premium and protection legs have equal PV, so the
contract is worth zero -- that is the definition, and `parSpread` repricing
the bootstrap input to 1e-10 is the same contract recipe 101 pins.
`riskyAnnuity` is the survival-probability-weighted sum of discount factors
-- the credit DV01 -- and the upfront on a standardized 100bp contract is
`(parSpread - 0.0100) * riskyAnnuity`: when the name trades wider than the
fixed coupon, the protection buyer pays that difference in cash up front.
The hazard curve behind all of this is what CVA reads in recipe 182.

## 181. Compute a Z-spread and the CDS-bond basis

A bond and a CDS on the same issuer price the same default risk two ways.
The Z-spread is the bond's constant add-on to the risk-free curve; the gap
between it and the CDS spread is the basis, and the basis is a trade.

```java
YieldCurve curve = YieldCurve.ofZeroRates(
        new double[]{1, 2, 3, 5, 7, 10}, new double[]{0.03, 0.03, 0.03, 0.03, 0.03, 0.03});

// A 5y 4% semi bond trading at 98.50 dirty: what flat spread reprices it?
double z = CreditSpreads.zSpread(98.50, 100, 0.04, 2, 5, curve);
double check = CreditSpreads.priceWithZSpread(100, 0.04, 2, 5, curve, z);   // == 98.50
System.out.printf("Z-spread %.4f (%.0f bp), reprices to %.2f%n", z, z * 1e4, check);

// The CDS par spread on the same name and tenor:
CreditCurve credit = CreditCurve.bootstrap(
        new int[]{1, 3, 5, 7}, new double[]{0.008, 0.012, 0.015, 0.016}, 0.40, curve);
double cds = CdsPricer.parSpread(credit, curve, 5);
System.out.printf("CDS %.0f bp,  basis (CDS - Z) %.0f bp%n", cds * 1e4, (cds - z) * 1e4);
```

The Z-spread is the single number added to every point of the risk-free
curve that makes the bond's discounted cash flows equal its dirty price --
`priceWithZSpread` round-trips it exactly, the invert-and-check the class
pins. The CDS-bond basis is `cds - zSpread`: negative basis (bond spread
wider than CDS) is the post-2008 norm and pays a basis-package buyer to own
the bond and its protection together; positive basis flags the CDS as the
richer hedge. Either way the two instruments should price the same credit,
and a basis far from zero is either a funding/liquidity signal or a
mispricing to lean on.

## 182. Compute CVA off the hazard curve

CVA is the price of the counterparty -- the expected loss from their default
before your trades finish paying out. It is three separate objects
multiplied together, and keeping them separate is the whole design.

```java
YieldCurve discount = YieldCurve.ofZeroRates(
        new double[]{1, 2, 3, 5}, new double[]{0.03, 0.03, 0.03, 0.03});
CreditCurve counterparty = CreditCurve.bootstrap(
        new int[]{1, 3, 5}, new double[]{0.010, 0.015, 0.020}, 0.40, discount);

// Expected exposure per bucket from YOUR pricing stack (a swap's EE hump here):
double[] ee = {120_000, 180_000, 210_000, 160_000, 90_000};
double[] bucketEnd = {1, 2, 3, 4, 5};

double cva = CvaApproximator.cva(ee, bucketEnd, counterparty, discount, 0.60);   // LGD 60%
System.out.printf("CVA charge %.0f (subtract from the risk-free PV)%n", cva);
```

The formula is `LGD * sum_i EE(t_i) * [Q(t_{i-1}) - Q(t_i)] * DF(t_i)`:
expected exposure, times the probability of defaulting IN that bucket read
off the survival curve, times the discount factor, times loss-given-default.
The three ingredients are deliberately distinct objects -- exposure comes
from your simulation stack, credit from the CDS-bootstrapped curve,
discounting from the yield curve -- because they are owned by different
desks and modeled independently. The stated approximations are honest:
exposure is taken at the bucket end (shrink the grid to shrink the bias),
and default and exposure are assumed INDEPENDENT, so this understates CVA
under wrong-way risk -- the EM-sovereign FX forward where exposure balloons
exactly as the counterparty weakens.

## 183. Read roll and carry off a commodity curve

A commodity futures curve is not a discounting curve -- it is a
storage-and-convenience story, and its slope tells you whether a passive
long earns or bleeds carry every time it rolls.

```java
CommodityCurve curve = CommodityCurve.of(60.0,                    // spot
        new double[]{0.0833, 0.25, 0.5, 1.0},                     // 1M, 3M, 6M, 1Y
        new double[]{59.4, 58.6, 57.9, 57.0});                    // downward = backwardation

double roll = curve.annualizedRollYield(0.25, 0.5);              // long rolling 3M -> 6M
double carry = curve.impliedCarry(1.0, 0.04);                    // storage minus convenience
System.out.printf("1Y price %.2f  roll yield %.2f%%  implied (u - y) %.2f%%%n",
        curve.price(1.0), roll * 100, carry * 100);
System.out.printf("contango %b  backwardation %b%n", curve.isContango(), curve.isBackwardation());
```

A backwardated curve (deferred below near, as here) pays a positive roll
yield: the long buys the cheaper far contract and it pulls UP toward the
richer spot as it ages -- the structural tailwind behind commodity momentum.
Contango flips it into a bleed, the drag that quietly wrecks a naive
long-oil ETF. `impliedCarry` inverts `F = S * exp((r + u - y) t)` to read
the market's net storage-minus-convenience yield: a deeply backwardated
curve implies a high convenience yield (the market pays up to hold the
physical NOW), the signature of a supply squeeze. The two boolean shape
checks are strict -- every adjacent pillar must agree -- so a mixed curve
returns false for both, which is itself information.

## 184. Construct an index three ways and adjust the divisor

Cap-weighted, price-weighted, equal-weighted: the same constituents give
three different indices, and the divisor is the thread that keeps any of
them continuous through a rebalance.

```java
double[] prices = {150, 40, 300, 90};
double[] shares = {2_000, 5_000, 800, 3_000};      // shares outstanding (millions)
double[] floatFactors = {0.90, 1.00, 0.55, 0.85};  // free-float adjustment

double[] cap = IndexConstruction.capWeights(prices, shares, floatFactors);
double[] price = IndexConstruction.priceWeights(prices);
double[] equal = IndexConstruction.equalWeights(4);

double divisor = 1_000_000;                          // set so level starts at a round number
double level = IndexConstruction.level(prices, shares, floatFactors, divisor);

// A constituent swap changes the aggregate -- re-solve the divisor to hold the level:
double newAggregate = 1_050_000_000, oldAggregate = 1_000_000_000;
double newDivisor = IndexConstruction.adjustDivisor(divisor, oldAggregate, newAggregate);
System.out.printf("level %.2f, turnover on rebalance %.2f%n",
        level, IndexConstruction.turnover(cap, equal));
```

Cap weighting scales by free-float-adjusted market value (the S&P
approach), price weighting by raw price alone (the Dow's quirk, where a
high-priced stock dominates regardless of company size), and equal
weighting ignores both. The divisor exists so the index level is
continuous: when a constituent is added, dropped, or splits, the raw
aggregate jumps, and `adjustDivisor` rescales so the published level does
not -- `newDivisor = oldDivisor * newAggregate / oldAggregate`. `turnover`
between two weight vectors is the one-way trading a rebalance implies, the
cost the index methodology is quietly imposing on every fund that tracks
it.

## 185. Compute private-markets IRR, PME, and desmoothed vol

Private-fund reporting has three traps: the IRR that flatters early
distributions, the absolute return that ignores what the public market did,
and the appraisal smoothing that launders volatility. Three tools, one
honest picture.

```java
// Cash flows, investor sign convention: contributions NEGATIVE, distributions POSITIVE,
// terminal NAV folded into the last flow.
double[] flows = {-100, -50, 20, 30, 40, 120};
double irr = PrivateMarketAnalytics.irr(flows);     // money-weighted, per period

// Multiples on total paid-in / distributed / residual NAV:
double tvpi = PrivateMarketAnalytics.tvpi(150, 90, 80);
double dpi  = PrivateMarketAnalytics.dpi(150, 90, 80);

// PME: did the fund beat the index ON ITS OWN CASH-FLOW DATES?
double[] contribs = {100, 50, 0, 0, 0, 0};
double[] distribs = {0, 0, 20, 30, 40, 0};
double[] index    = {100, 105, 112, 108, 120, 130};
double pme = PrivateMarketAnalytics.ksPme(contribs, distribs, 80, index);

// Geltner desmoothing: recover the TRUE return series from smoothed appraisals.
double[] observed = {0.02, 0.021, 0.019, 0.022, 0.020};
double[] trueRets = PrivateMarketAnalytics.geltnerDesmooth(observed, 0.4);
System.out.printf("IRR %.2f%%  TVPI %.2f  DPI %.2f  PME %.2f%n", irr * 100, tvpi, dpi, pme);
```

IRR is money-weighted and per-period -- it rewards getting cash back early,
which is exactly why a fund with a fast first distribution can post a great
IRR and a mediocre TVPI (the multiple that ignores timing). The
Kaplan-Schoar PME answers the question IRR cannot: it grows each
contribution and distribution by the public index's return to the end date,
so PME > 1 means the fund beat the index on its actual cash-flow schedule --
alpha net of what indexing would have done. Geltner desmoothing inverts the
AR(1) appraisal lag `r_obs = (1-phi)*r_true + phi*r_obs_prev`, recovering a
series whose volatility and public-market correlation can sit honestly next
to liquid assets; the inversion round-trips to machine precision, so it
adds no noise of its own.

## 186. Estimate volatility from the range four ways

Close-to-close volatility throws away the high and the low -- the two prices
that carry the most information about how far the asset actually traveled.
Four range estimators use them, each correcting a different bias.

```java
double[] open  = {100, 101, 100, 102, 103};
double[] high  = {102, 103, 103, 104, 105};
double[] low   = {99, 100, 99, 101, 102};
double[] close = {101, 100, 102, 103, 104};
double periodsPerYear = 252;

double park = RangeVolatility.parkinson(high, low, periodsPerYear);
double gk   = RangeVolatility.garmanKlass(open, high, low, close, periodsPerYear);
double rs   = RangeVolatility.rogersSatchell(open, high, low, close, periodsPerYear);
double yz   = RangeVolatility.yangZhang(open, high, low, close, periodsPerYear);
System.out.printf("Parkinson %.4f  Garman-Klass %.4f  Rogers-Satchell %.4f  Yang-Zhang %.4f%n",
        park, gk, rs, yz);
```

Parkinson uses only the high-low range -- roughly five times more efficient
than close-to-close, but it assumes no drift and no overnight gap.
Garman-Klass adds the open and close for more efficiency still, but shares
the zero-drift assumption. Rogers-Satchell is the one that stays unbiased
under a trend -- it handles a drifting asset the others overstate.
Yang-Zhang is the complete estimator: it combines an overnight-gap term, an
open-to-close term, and Rogers-Satchell, making it the only one of the four
that is both drift-independent and gap-aware, which is why it is the
default when bars have real overnight jumps. All four take `BarSeries`
overloads too, so they drop straight onto a loaded price series.

## 187. Pick a GARCH variant with AIC and BIC

More parameters always fit the in-sample returns better -- so "which GARCH"
cannot be answered by likelihood alone. AIC and BIC penalize the extra
parameters, and BIC penalizes them harder as the sample grows.

```java
double[] returns = /* daily log returns */;

Garch11.Params g   = Garch11.fit(returns);          // 3 params: omega, alpha, beta
GjrGarch11.Params j = GjrGarch11.fit(returns);      // 4 params: + leverage gamma
Egarch11.Params e   = Egarch11.fit(returns);        // 4 params: log-variance

int n = returns.length;
double aicG = InformationCriteria.aic(g.logLikelihood(), 3);
double aicJ = InformationCriteria.aic(j.logLikelihood(), 4);
double aicE = InformationCriteria.aic(e.logLikelihood(), 4);
double bicG = InformationCriteria.bic(g.logLikelihood(), 3, n);
double bicJ = InformationCriteria.bic(j.logLikelihood(), 4, n);
System.out.printf("AIC  garch %.1f  gjr %.1f  egarch %.1f%n", aicG, aicJ, aicE);
System.out.printf("BIC  garch %.1f  gjr %.1f%n", bicG, bicJ);   // lower wins
```

Lower is better for both, and the two criteria disagree on purpose: AIC
(`-2*logL + 2k`) targets predictive accuracy and tolerates the extra
parameter more readily, while BIC (`-2*logL + k*ln(n)`) targets the true
model and, because its penalty grows with `ln(n)`, punishes complexity
harder on a long sample. If the GJR's leverage term earns its keep -- equity
returns usually make it -- both criteria pick it over plain GARCH despite
the fourth parameter; if they split, trust BIC for a long series and AIC
when the sample is short and you care about the next forecast. The point is
that the log-likelihood alone would ALWAYS pick the richer model, and that
is the overfit these criteria exist to stop.

## 188. Read the leverage effect: GJR gamma and EGARCH log-variance

Equity volatility rises more after a drop than after an equivalent rally --
the leverage effect -- and plain GARCH is blind to it because it squares the
return and throws the sign away. Two models put the sign back, differently.

```java
double[] returns = /* daily log returns */;

GjrGarch11.Params gjr = GjrGarch11.fit(returns);
System.out.printf("GJR: omega=%.2e alpha=%.3f gamma=%.3f beta=%.3f persistence=%.3f%n",
        gjr.omega(), gjr.alpha(), gjr.gamma(), gjr.beta(), gjr.persistence());
// gamma > 0: negative shocks add gamma MORE variance than positive ones.
double gjrFcast = GjrGarch11.forecastVariance(returns, gjr, 5);

Egarch11.Params eg = Egarch11.fit(returns);
System.out.printf("EGARCH: unconditional log-var %.4f%n", eg.unconditionalLogVariance());
double egNext = Egarch11.nextVariance(returns, eg);   // no positivity constraint needed
System.out.printf("GJR 5-day var %.6f  EGARCH next-var %.6f%n", gjrFcast, egNext);
```

GJR adds a term that switches on only for negative returns, so a positive
`gamma` means a down-day contributes `alpha + gamma` to tomorrow's variance
versus `alpha` for an up-day of the same size -- the asymmetry read directly
off one coefficient. EGARCH gets there another way: it models the LOG of
variance, so variance is positive by construction (no awkward constraints
on the parameters) and the leverage effect enters through the sign of the
standardized shock. Both nest the symmetric case (`gamma = 0`), both
mean-revert as long as persistence stays below 1, and which you prefer is
usually the AIC/BIC call of recipe 187 -- but if you need to guarantee
positive variance while fitting freely, EGARCH's log formulation is the
cleaner tool.

## 189. Forecast realized variance with the HAR cascade

Realized volatility has long memory -- today's variance depends on
yesterday, last week, AND last month -- but you do not need a fractional
model to capture it. HAR-RV stacks three simple averages and forecasts
strikingly well.

```java
double[] realizedVariance = /* daily RV, e.g. sum of squared 5-min returns */;

HarRv.Params p = HarRv.fit(realizedVariance);
System.out.printf("intercept %.2e  daily %.3f  weekly %.3f  monthly %.3f%n",
        p.intercept(), p.betaDaily(), p.betaWeekly(), p.betaMonthly());

double next = HarRv.forecast(realizedVariance, p);
System.out.printf("next-day RV forecast %.6f (vol %.4f)%n", next, Math.sqrt(next * 252));
```

The regression is tomorrow's RV on three predictors -- yesterday's RV, the
trailing 5-day average, and the trailing 22-day average -- a "heterogeneous"
cascade meant to mirror the different horizons traders act on (day traders,
swing traders, position holders). The three betas typically all come out
positive and sum to near the series' persistence: a big daily beta means
recent shocks dominate, a big monthly beta means the series is slow and
sticky. It approximates true long memory with three OLS coefficients, which
is why HAR-RV became the realized-vol workhorse -- it beats far fancier
long-memory models out of sample while fitting in a single linear
regression.

## 190. Build a VIX-style index from an option chain

The market's own 30-day volatility forecast is not modeled -- it is READ,
model-free, out of the prices of out-of-the-money options. Same 1/K^2
replication as the variance swap, packaged as the fear gauge.

```java
double forward = 100, rate = 0.02, tYears = 30.0 / 365;
int n = 21;
double[] strikes = new double[n], puts = new double[n], calls = new double[n];
for (int i = 0; i < n; i++) {
    strikes[i] = 70 + 3.0 * i;                     // span several sigma*sqrt(T)
    puts[i]  = Black76.price(OptionType.PUT,  forward, strikes[i], rate, 0.22, tYears);
    calls[i] = Black76.price(OptionType.CALL, forward, strikes[i], rate, 0.22, tYears);
}

double vix = VolatilityIndex.index(strikes, puts, calls, forward, rate, tYears);
System.out.printf("index %.4f  (a VIX of %.0f)%n", vix, vix * 100);
```

The index weights each OTM option by `1/K^2` -- the exact slice needed to
build a constant-dollar-gamma portfolio whose P&L IS realized variance -- so
its price reveals the market's variance expectation whatever model anyone
used to quote the options. That weighting is why a vol SMILE pushes the
index ABOVE the ATM implied vol: the wings carry real premium and the
replication counts them. The honest caveats are structural: this is a
single expiry (the CBOE interpolates two to hit exactly 30 days), and the
strikes must span several standard deviations or the index reads LOW -- the
tails you cannot see are variance you are not counting.

## 191. Split volatility into systematic and idiosyncratic

Not all of an asset's volatility is its own. A single-factor regression on
the market splits total variance into the part the market drives (beta risk,
diversifiable in a portfolio) and the part that is the asset's alone.

```java
double[] assetReturns  = /* the stock's returns */;
double[] marketReturns = /* the index's returns, aligned */;

VolatilityDecomposition.Decomposition d =
        VolatilityDecomposition.decompose(assetReturns, marketReturns);
System.out.printf("beta %.2f  R^2 %.2f%n", d.beta(), d.rSquared());
System.out.printf("systematic vol %.2f%%  idiosyncratic vol %.2f%%  total %.2f%%%n",
        d.systematicVol(252) * 100, d.idiosyncraticVol(252) * 100, d.totalVol(252) * 100);
```

The identity is `totalVariance = beta^2 * marketVariance + residualVariance`
-- systematic plus idiosyncratic -- and `rSquared` is the fraction that is
systematic. A high-R^2 stock (say 0.7) is mostly a leveraged market bet: its
vol is beta risk you already own elsewhere, and hedging the index flattens
most of it. A low-R^2 name carries mostly idiosyncratic vol -- the part that
survives diversification and the part a stock-picker is actually paid for.
The two component vols add in QUADRATURE, not linearly (variances add,
vols do not), which is why a stock with meaningful risk on both axes has a
total vol well below their sum -- the same reason a diversified book's risk
is less than the sum of its positions'.

## 192. Simulate the delta-hedging error distribution

A delta hedge does not eliminate P&L -- it turns option risk into a
distribution of replication errors whose shape depends on how often you
rebalance and what it costs. Simulate that distribution before you trust
the hedge.

```java
HedgingSimulator sim = new HedgingSimulator(42);
DeltaHedger.Config cfg = DeltaHedger.Config.every(2.0);   // rebalance every step, 2bp cost

var dist = sim.simulate(OptionType.CALL, 100, 100, 1.0,   // type, spot, strike, expiry
        0.03, 0.0, 0.20, 0.25,                            // rate, carry, hedgeVol, realizedVol
        252, 10_000, cfg);                                // steps/path, paths, config
System.out.printf("mean P&L %.3f  stdev %.3f  VaR95 %.3f  CVaR95 %.3f%n",
        dist.mean(), dist.stdDev(), dist.valueAtRisk(0.95), dist.conditionalValueAtRisk(0.95));
System.out.printf("mean rebalances %.0f  mean costs %.3f  relative hedge error %.2f%%%n",
        dist.meanRebalances(), dist.meanTradingCosts(), dist.relativeHedgeError() * 100);
```

Here the option is sold at 20 vol but the underlying realizes 25 -- so the
short-option hedger loses on average, and the mean P&L quantifies exactly
how much that vol mismatch costs. The `stdDev` is the replication error the
finite rebalancing leaves behind: hedge continuously and it shrinks toward
zero, hedge less often and it fattens, and the transaction cost pulls the
mean down the more you trade -- the tension every hedger lives in. Read the
CVaR, not just the mean: the tail is where a discrete hedge on a gapping
underlier actually hurts, and `relativeHedgeError` normalizes it against the
premium so you can compare hedging schemes on one scale. The no-trade band
of recipe 193 is the disciplined answer to the rebalance-frequency tension
this distribution exposes.

## 193. Set a no-trade band with Whalley-Wilmott

Rebalancing on every tick pays infinite transaction costs to chase a delta
that has not really moved. The Whalley-Wilmott band is the cost-optimal
answer: hold inside a band around delta, trade only to its edge when you
leave it.

```java
double spot = 100, gamma = 0.05, costRate = 0.0005, riskAversion = 1.0;

double halfWidth = WhalleyWilmott.bandHalfWidth(spot, gamma, costRate, riskAversion);
System.out.printf("no-trade half-width: %.4f deltas%n", halfWidth);

double currentHedge = -0.42;      // short 0.42 of the underlying against the book
double delta = -0.50;             // the book's delta has drifted to -0.50
WhalleyWilmott.Action a = WhalleyWilmott.rebalance(currentHedge, delta, halfWidth);
if (a.trade()) System.out.printf("trade to the edge: target hedge %.4f%n", a.targetHedge());
else           System.out.println("inside the band: do nothing");
```

The half-width scales as the cube root of `1.5 * costRate * spot * gamma^2
/ riskAversion` -- higher costs or higher gamma widen the band (each trade
is dearer, or the delta moves faster so chasing it churns more), while more
risk aversion tightens it (you tolerate less unhedged delta). The policy is
hedge-to-the-nearest-EDGE, not to the center: when the delta drifts outside
the band you trade back only to the boundary, not all the way to `delta`,
which is what keeps the average trade small. It is the disciplined middle
between the two losing extremes recipe 192 shows -- rebalance-always (cost
death) and rebalance-never (variance death).

## 194. Greek-neutralize an options book

A book that is delta-flat can still bleed on a gamma or vega move. Read the
book's Greeks, then solve the exact instrument quantities that flatten
delta, gamma, AND vega together -- a square linear system, no iteration.

```java
OptionsBook book = new OptionsBook(100, 0.03, 0.01)
        .addOption("short-call", OptionType.CALL, 105, 0.5, -500, 0.22)
        .addOption("long-put",   OptionType.PUT,   95, 0.5,  300, 0.24)
        .addUnderlying(120);

OptionsBook.BookGreeks g = book.greeks();
double[] portfolio = {g.delta(), g.gamma(), g.vega()};
System.out.printf("book delta %.1f  gamma %.2f  vega %.1f%n", g.delta(), g.gamma(), g.vega());

// Two hedge options (per-unit greeks) plus the underlying = three instruments for three greeks:
var opt1 = new GreekHedger.Instrument("hedge-call", 0.55, 0.040, 12.0);
var opt2 = new GreekHedger.Instrument("hedge-put", -0.45, 0.035, 11.0);
var und  = GreekHedger.Instrument.underlying("spot");
double[] qty = GreekHedger.neutralize(portfolio, new GreekHedger.Instrument[]{opt1, opt2, und});

double[] residual = GreekHedger.residualGreeks(portfolio,
        new GreekHedger.Instrument[]{opt1, opt2, und}, qty);
System.out.printf("hedge qty %s -> residual %s%n", Arrays.toString(qty), Arrays.toString(residual));
```

`neutralize` builds the 3x3 system whose rows are the three Greeks and whose
columns are the instruments, then solves for the quantities that make the
summed hedge Greeks offset the book's exactly -- the residual comes back
machine-zero, which `residualGreeks` verifies. You need as many linearly
independent instruments as Greeks: the underlying alone can only kill delta
(gamma and vega zero), so flattening gamma and vega requires two options
with genuinely different second-order profiles. This is the same math
`deltaGammaVegaHedge` wraps for the common one-underlying-two-option case;
`neutralize` is the general form when your hedge menu is something else.

## 195. Minimum-variance hedge and the rho-squared it buys

The right hedge ratio is not 1.0 and it is not the beta from memory -- it is
the regression slope that minimizes the hedged variance, and how much
variance it actually removes is `rho^2`, no more.

```java
double[] assetReturns = /* the position you want to protect */;
double[] hedgeReturns = /* the instrument you will short against it */;

double ratio = MinimumVarianceHedge.hedgeRatio(assetReturns, hedgeReturns);
double effectiveness = MinimumVarianceHedge.hedgeEffectiveness(assetReturns, hedgeReturns);
double reduction = MinimumVarianceHedge.varianceReduction(assetReturns, hedgeReturns, ratio);
System.out.printf("hedge ratio %.3f  effectiveness (rho^2) %.2f  variance cut %.1f%%%n",
        ratio, effectiveness, reduction * 100);

// Beta-based futures hedge: contracts to move the book from its beta to a target.
double contracts = MinimumVarianceHedge.fullHedgeContracts(1.15, 10_000_000, 4500, 50);
double toTarget  = MinimumVarianceHedge.betaAdjustmentContracts(1.15, 0.5, 10_000_000, 4500, 50);
System.out.printf("full hedge %.1f contracts, to beta 0.5 %.1f contracts%n", contracts, toTarget);
```

The minimum-variance ratio is `cov(asset, hedge) / var(hedge)` -- the OLS
slope -- and `hedgeEffectiveness` is the regression's `rho^2`, which is
literally the ceiling on how much variance any linear hedge can remove.
That number is the honesty check: a hedge instrument correlated 0.7 with
your asset cuts at most 49% of the variance no matter how you size it, so a
low `rho^2` says "this is the wrong hedge," not "trade more of it." The
beta-adjustment helpers translate the same idea into index-futures
contracts -- full hedge to zero beta, or a partial move to a target beta --
the equity-desk version of the same variance-minimizing logic.

## 196. Test a pair for cointegration before trading the spread

Two stocks can trend together for years and never mean-revert -- correlation
is not cointegration. Engle-Granger tests whether the spread is actually
tethered; only then does the half-life tell you how to trade it.

```java
double[] pricesA = /* leg A prices */;
double[] pricesB = /* leg B prices */;

var eg = CointegrationTest.engleGranger(pricesA, pricesB);
System.out.printf("hedge ratio %.3f  ADF t=%.2f  cointegrated @5%% %b%n",
        eg.hedgeRatio(), eg.adfTStatistic(), eg.cointegrated5pct());
if (!eg.cointegrated5pct()) return;                 // no tether, no trade

PairsHedger.PairsAnalysis pa = PairsHedger.analyze(pricesA, pricesB);
System.out.printf("half-life %.1f bars  current z %.2f  correlation %.2f%n",
        pa.halfLifeBars(), pa.lastZScore(), pa.correlation());
if (Math.abs(pa.lastZScore()) > 2.0) { /* fade the spread toward its mean */ }
```

Engle-Granger regresses one leg on the other and runs an ADF test on the
residual spread: a t-statistic below the critical value (`-3.34` at 5%)
rejects the unit root, meaning the spread reverts rather than wanders. The
hedge ratio is that regression's slope -- units of B per unit of A -- and
`PairsHedger` adds the two numbers that turn a valid pair into a trade: the
mean-reversion half-life (a 200-bar half-life is an index fund with extra
steps; skip it) and the current z-score of the spread. A pair that fails
the cointegration test but shows a tempting z-score is the classic trap --
the spread is stretched because it is DRIFTING, not because it will snap
back. Recipe 15 carries this into the OU fit and the legging-cap execution.

## 197. Track a time-varying beta with a Kalman filter

A rolling-window beta is always stale -- it reacts to a regime change only
after the window fills with new data. A Kalman filter updates the beta on
every observation, weighting new evidence against the estimate's own
uncertainty.

```java
KalmanBeta kf = new KalmanBeta(1.0, 1.0, 1e-5, 1e-3);
//                             beta0 var0  process obs-noise

// Stream aligned (market, asset) return pairs:
double[][] obs = /* rows of {marketReturn, assetReturn} */;
for (double[] row : obs) {
    double beta = kf.onObservation(row[0], row[1]);   // returns the updated beta
}
System.out.printf("beta %.3f (+/- %.3f)  alpha %.4f  over %d obs%n",
        kf.beta(), Math.sqrt(kf.betaVariance()), kf.alpha(), kf.observations());
```

The filter treats beta as a hidden state that drifts (the process-noise
term sets how fast) and the asset return as a noisy measurement of it (the
observation-noise term sets how much you trust each print). Its gain -- how
hard it pulls the estimate toward the latest observation -- is set by the
ratio of those two noises: crank the process noise up and beta tracks
regime shifts fast but jitters; crank it down and beta is smooth but slow.
That is the same bias-variance dial a rolling window sets crudely with its
length, except the Kalman filter adapts it automatically and reports its own
uncertainty (`betaVariance`), which a fixed window never does. It is the
live, one-pass counterpart to the batch regression in recipe 191.

## 198. Optimize a cost-aware hedge basket

Hedging every factor with its own instrument minimizes risk and maximizes
cost. The CRB hedge optimizer trades one against the other: it leaves small,
cheap-to-carry exposures unhedged when the transaction cost outweighs the
risk they remove.

```java
double[] exposures = {5_000_000, -2_000_000, 1_500_000};      // net factor P&L per unit move
double[][] cov = /* factor covariance */;
double[][] loadings = /* [factor][instrument] spanning matrix */;
double[] costPerUnit = {0.8, 1.2, 0.5};                       // bps to trade each instrument

double[] h = HedgeOptimizer.hedge(exposures, cov, loadings, costPerUnit, 0.6);   // cost weight
double[] residual = HedgeOptimizer.residual(exposures, loadings, h);
System.out.printf("pre-hedge risk %.0f  post-hedge risk %.0f%n",
        HedgeOptimizer.risk(exposures, cov), HedgeOptimizer.risk(residual, cov));
System.out.println("hedge basket: " + Arrays.toString(h));
```

The objective minimizes `residual risk + costWeight * transaction cost`, so
the `costWeight` is the dial: at zero it drives the residual risk to its
minimum regardless of what trading costs (the risk-only hedge), and as you
raise it the optimizer starts leaving cheap-to-carry residual exposures
alone because hedging them costs more than the variance they contribute.
`residual` is the exposure the chosen basket leaves behind and `risk`
scores any exposure vector against the covariance, so the pre/post
comparison shows exactly what the basket bought -- and the gap between the
cost-aware residual and the risk-only minimum is the premium you declined to
pay. This is the engine under the central-risk-book auto-hedger of recipe
14.

## 199. Run four VaR flavors and read the disagreement

Recipe 94 lays out the four methods; the trading use is the SPREAD between
them. One book, four numbers, and each divergence names a specific feature
of the risk the linear headline hides.

```java
double[] exposures = {5_000_000, -2_000_000, 1_500_000};
double[][] cov = /* daily factor covariance */;
double[][] factorReturns = /* historical factor-return rows */;

double dn = VarEngine.deltaNormalVar(exposures, cov, 0.99);
double dnEs = VarEngine.deltaNormalEs(exposures, cov, 0.99);      // ES leads post-FRTB
var hist = VarEngine.historicalVar(exposures, factorReturns, 0.99);
var mc = VarEngine.monteCarloVar(exposures, cov, 0.99, 100_000, 42);
double[][] gamma = /* second-order sensitivities */;
double dg = VarEngine.deltaGammaVar(exposures, gamma, cov, 0.99);
System.out.printf("dn %.0f (ES %.0f)  hist %.0f  mc %.0f  dg %.0f%n",
        dn, dnEs, hist.var(), mc.var(), dg);
```

Delta-normal is instant and exactly wrong for optionality -- it is the
baseline the others are read against. Historical VaR far above delta-normal
means the return window had fatter tails than any Gaussian fitted to it (the
number to trust when the sample is rich). Monte Carlo converges to
delta-normal on a linear book by construction, so its value is as the
harness for full revaluation -- when the quadratic term dominates, graduate
to `fullRevaluationVar(scenarios, pricer, 0.99)`, the only method that sees
a knocked-out barrier. And delta-gamma above delta-normal is the tell that
the book is short gamma and the linear number is flattering it. Pair every
VaR with its ES: post-FRTB the expected shortfall is the headline and VaR is
the diagnostic.

## 200. Fit the tail with EVT and respect the refusal

Historical VaR at 99.9% from 500 days is reading the worst
half-observation -- noise dressed as a number. Extreme value theory fits the
exceedances over a high threshold and extrapolates along the fitted tail,
and refuses when the tail has no finite mean.

```java
double[] losses = /* daily losses, positive (feed -returns) */;

var tail = ExtremeValueTheory.fitPot(losses, 0.95);
System.out.printf("u=%.4f xi=%.2f beta=%.4f (%d exceedances of %d)%n",
        tail.threshold(), tail.shape(), tail.scale(), tail.exceedances(), tail.sampleSize());

double var999 = tail.var(0.999);                    // beyond the sample, honestly
try {
    double es999 = tail.expectedShortfall(0.999);
} catch (IllegalStateException noMean) {
    // xi >= 1: the fitted tail's mean is INFINITE. The refusal IS the answer.
}
```

The shape `xi` is the whole diagnosis: near zero is an exponential
(Gaussian-ish) tail, positive is a power law, and equity returns typically
fit 0.2 to 0.4. Stare at its stability across thresholds -- if `xi` jumps
around as you raise the cutoff, the tail is not yet GPD-stable and you need
a lower threshold or more data (`fitPot` already refuses under 10
exceedances). The `expectedShortfall` throwing at `xi >= 1` is not a bug to
catch and ignore -- it is EVT doing its one job: when the fitted tail is so
heavy its mean diverges, a finite ES would be the exact lie the method
exists to prevent. And `var(p)` throws below the fitted tail, because inside
the sample plain historical VaR is the right tool.

## 201. Reverse-stress the book: what move breaks it?

The regulator's inverted question is the useful one: not "what does a 2-sigma
move cost" but "what move loses 2 million, and how implausible is it?" For a
linear book the worst direction is closed-form -- along the covariance times
the exposures.

```java
double[] exposures = {5_000_000, -2_000_000, 1_500_000};
double[][] cov = /* factor covariance */;

var reverse = StressTester.reverseStress(exposures, cov, 2_000_000);
System.out.printf("breaks at %.1f sigmas: %s%n",
        reverse.mahalanobisSigmas(), Arrays.toString(reverse.shocks()));

// Verify the closed form: push the breaking move back through and it loses exactly the target.
double check = StressTester.scenarioPnl(exposures, reverse.shocks());
System.out.printf("re-priced loss %.0f (target 2,000,000)%n", -check);

// Named context: what would the historical templates do to THIS book?
double covid = StressTester.scenarioPnl(exposures, StressTester.covidMarch2020());
double lehman = StressTester.scenarioPnl(exposures, StressTester.lehman2008());
```

Read `mahalanobisSigmas` as the verdict: above 10, only a joint move markets
have never printed breaks the book -- the diversification bought real
comfort. Below 3, the book breaks on an ordinary Tuesday, and that is a
fix-it-today finding, not a committee item. The reverse-stress shock vector
is the most-probable move that hits the target loss, and pushing it back
through `scenarioPnl` must return exactly minus the target -- the
self-consistency check on the closed form. The named templates
(`covidMarch2020`, `lehman2008`, `blackMonday1987`) are stylized shock
vectors for an [equity, rates, FX, commodity, vol] ordering -- honest
starting points to edit for your factor set, documented as approximations,
not certified replays.

## 202. Cascade FRTB liquidity-horizon ES and attribute PLAT

The Basel wrap on the VaR numbers: expected shortfall at 97.5% cascaded
across liquidity horizons, anchored to a stressed period, plus the P&L
attribution test that decides whether the internal model keeps its
approval.

```java
double[] esByHorizon = {2_400_000, 1_100_000, 600_000};   // 10d ES per LH subset
int[] horizons = {10, 20, 60};
double es = FrtbEs.liquidityHorizonEs(esByHorizon, horizons);       // MAR33.5 cascade

// Anchor to the stressed period via the reduced factor set (ratio >= 1):
double imcc = FrtbEs.stressCalibratedEs(es, 3_000_000, 2_500_000);

// Backtesting one-pager: 250-day 99% VaR exceptions -> traffic-light zone.
var zone = FrtbEs.TrafficLight.of(6);               // AMBER: the multiplier rises

// PLAT: does the risk model's P&L track the desk's actual P&L?
double[] hypotheticalPnl = /* HPL */, riskTheoreticalPnl = /* RTPL */;
var plat = PnlAttribution.test(hypotheticalPnl, riskTheoreticalPnl);
System.out.printf("%s: spearman %.2f  ks %.3f%n",
        plat.zone(), plat.spearmanCorrelation(), plat.ksStatistic());
```

The liquidity-horizon ES cascades the base 10-day number across the
horizons different factors need to exit -- less-liquid risk is capitalized
as if it takes 20, 40, 60 days to shed -- and `stressCalibratedEs` scales it
to a stressed window, with the ratio floored at 1 so a calmer stressed
period cannot DISCOUNT capital. PLAT asks two questions per MAR32: do the
model's P&L and the desk's rank days the same way (Spearman above 0.80 for
green) and do their distributions share a shape (KS below 0.09)? Failing
usually means missing risk factors -- the model prices with fewer factors
than move the real book -- and the consequence escalates: amber adds a
surcharge, red kicks the desk to the standardized approach. Styled after
MAR32/33 and pinned by hand-computed tests, deliberately not certified.

## 203. Backtest a VaR model like a regulator

A VaR model owes two things: exceptions at the promised rate, and exceptions
that arrive independently. A model right on average but wrong in clusters
passes the first test and fails the second -- and the second is the failure
that matters.

```java
double[] returns = /* realized daily returns */;
double[] varForecasts = /* each day's VaR as a POSITIVE loss fraction */;

var r = VarBacktest.test(returns, varForecasts, 0.99);
System.out.printf("%d exceptions in %d days (expected %.1f)%n",
        r.exceptions(), r.observations(), r.expectedExceptions());
System.out.printf("Kupiec LR=%.2f p=%.3f calibrated=%b%n",
        r.kupiecStatistic(), r.kupiecPValue(), r.calibrated(0.05));
System.out.printf("independence LR=%.2f p=%.3f independent=%b%n",
        r.independenceStatistic(), r.independencePValue(), r.independent(0.05));
boolean pass = r.passes(0.05);                      // joint conditional coverage
```

Kupiec's POF test checks the exception FREQUENCY, and it is two-sided on
purpose: too few exceptions rejects too, because an over-conservative VaR is
misallocated capital, not prudence. But Kupiec alone is gameable -- a model
that shows zero exceptions for 200 days and then five in a row can look
calibrated on frequency while being useless exactly when it mattered.
Christoffersen's independence test catches that clustering by examining the
transition probabilities between exception and non-exception days, and
`passes` is the joint conditional-coverage verdict that demands both. Reject
on the joint p-value; a model can only be trusted if its misses are both the
right count AND scattered, not bunched into the crisis.

## 204. Split portfolio VaR into component contributions for the committee

Total VaR is a fact; "which position owns how much of it" is the decision --
and it is not the standalone VaRs, which ignore diversification. The Euler
allocation splits the portfolio number exactly, so the components sum back
to the whole.

```java
double[] weights = {0.40, 0.35, 0.25};              // portfolio weights
double[][] cov = /* asset return covariance */;

ComponentVar.Allocation a = ComponentVar.allocate(weights, cov, 0.99);
System.out.printf("portfolio VaR %.4f%n", a.portfolioVar());
for (int i = 0; i < weights.length; i++) {
    System.out.printf("asset %d: component %.4f  marginal %.4f%n",
            i, a.components()[i], a.marginals()[i]);
}
// components sum to portfolioVar -- the additive split a committee can defend.

// What would ADDING a position do? Incremental VaR, before the trade:
double incr = ComponentVar.incremental(weights, cov, 0.99, 1);
```

The marginal VaR is the sensitivity of portfolio VaR to a small increase in
one position; the component VaR is that marginal times the position's
weight, and by Euler's theorem for the homogeneous VaR function the
components sum EXACTLY to the total -- the additive decomposition a risk
committee needs to attribute the number without the pieces over- or
under-counting. A position can carry positive standalone risk yet a NEGATIVE
component if it hedges the rest of the book -- which is precisely the
diversification the standalone VaRs miss and the committee most wants to
see. Incremental VaR answers the forward-looking version: the change in
total VaR from putting a trade ON, which is the number to check before
adding risk, not after.

## 205. Shrink the covariance matrix before you optimize on it

Feed a raw sample covariance to an optimizer and it will lever up the
lowest-variance estimation error it can find -- garbage in, concentrated
leverage out. Ledoit-Wolf shrinkage pulls the noisy matrix toward a
structured target and reports how far it had to pull.

```java
double[][] returns = /* T x N: returns[t][j] = period-t return of asset j */;

CovarianceShrinkage.Result s = CovarianceShrinkage.ledoitWolf(returns);
System.out.printf("shrinkage intensity %.2f toward target %.4f%n", s.intensity(), s.target());
double[][] cov = s.matrix();

// Now optimize on the STABLE matrix, not the sample one:
double[] mu = /* expected returns */;
var opt = new PortfolioOptimizer(mu, cov).maxSharpe(0.03);
System.out.println("weights: " + Arrays.toString(opt.weights()));
```

The sample covariance is the maximally overfit estimate: with N assets and
T observations close together it is nearly singular, and its smallest
eigenvalues -- the "low-risk" directions an optimizer loves -- are almost
pure noise. Ledoit-Wolf blends it with a target (a scaled identity, average
sample variance on the diagonal) at an intensity `delta` chosen ANALYTICALLY
to minimize expected error, so you do not tune it. Read the intensity as a
data-quality gauge: near zero means the sample is trustworthy (long history,
few assets), near one means it is mostly noise and the optimizer would have
been dividing by it. The shrunk matrix produces portfolio weights that are
stabler out of sample and far less prone to the extreme long-short tilts
raw sample covariance invites -- the fix applied BEFORE recipe 19's three
optimizers, not after they misbehave.

## 206. Grade a factor before you ever backtest it (IC, IR, turnover)

The first honest number on any signal is not its P&L -- it is the rank
correlation between today's score and tomorrow's return, over and over.
`SignalEvaluator.evaluate` walks non-overlapping forward windows and hands
back the whole scorecard.

```java
AlphaContext ctx = AlphaContext.of(panel);          // Map<String, BarSeries>
AlphaFactor mom = Factors.momentum(126, 21);         // 6-1 momentum

// startIndex covers the factor's warm-up; horizon is BOTH the forward
// window and the step, so the windows never overlap (no IC autocorrelation).
SignalEvaluator.Report r = SignalEvaluator.evaluate(ctx, mom, 130, 21);
System.out.println(r.format());
// e.g. momentum: IC=0.031 (t=2.8, n=54) IR=0.38 hit=53.1% turnover=41.2%

// The three decisions the scorecard drives:
boolean real     = Math.abs(r.tStat()) > 2;          // is the IC even there?
boolean tradeable = r.ir() > 0.3;                    // is it worth risk budget?
boolean cheap    = r.meanTurnover() < 0.6;           // will costs eat it?
```

IC is the edge, IR (`meanIc/icStd`) is the edge per unit of its own
wobble, and turnover is what you pay to keep harvesting it. A gorgeous IC
with 200% turnover is a paper edge a real cost model deletes -- which is
exactly why `evaluate` reports all three off the same date grid, and why
recipe 231 charges the turnover before believing the Sharpe.

## 207. See the picture behind the IC: the quantile long-short spread

An IC of 0.03 is a summary; the quantile plot is the evidence. Bucket each
date's cross-section by score and average the forward returns per bucket --
a real factor is MONOTONE across buckets, a fake one hides its whole spread
in one tail.

```java
SignalEvaluator.QuantileReport q =
        SignalEvaluator.quantileReturns(ctx, mom, 130, 21, 5);   // quintiles

for (int i = 0; i < q.meanReturns().length; i++) {
    System.out.printf("Q%d  mean fwd %.4f  (n=%d)%n",
            i + 1, q.meanReturns()[i], q.counts()[i]);
}
// The top-minus-bottom return per period -- the tradeable long/short:
System.out.printf("long/short spread per 21d: %.4f%n", q.spread());
```

`spread()` is `meanReturns[last] - meanReturns[0]`: the return of being long
the top quintile and short the bottom, per rebalance. If Q1..Q5 climb
smoothly the factor ranks the whole cross-section; if only Q5 pays and
Q1..Q4 are flat, you own a tail bet wearing a factor costume -- the same IC,
a completely different risk. Desks plot both because neither number sees
what the other sees.

## 208. Estimate factor premia with honest t-stats (Fama-MacBeth)

Are the factors PAID? Fama-MacBeth runs one cross-sectional regression per
date, then treats the time series of slopes as the sample -- the standard
error comes from how much the premium wobbles date to date, not from a
single pooled fit that pretends every observation is independent.

```java
// exposures[t][asset][factor] known at t; forwardReturns[t][asset] after t.
double[][][] exposures = /* your point-in-time loadings, >= 12 periods */;
double[][]   fwd       = /* aligned forward returns; NaN = not in that date */;

FamaMacBeth.Result fm = FamaMacBeth.fit(exposures, fwd);
for (int k = 0; k < fm.premia().length; k++) {
    System.out.printf("factor %d: premium %.4f/period  t=%.2f%n",
            k, fm.premia()[k], fm.tStats()[k]);
}
// The intercept is the tell: a significant mean means the factors leave
// returns UNEXPLAINED (an alpha, or a missing factor).
System.out.printf("intercept %.4f (t=%.2f) over %d usable cross-sections%n",
        fm.interceptMean(), fm.interceptTStat(), fm.periodsUsed());
```

A NaN forward return is "not in this date's cross-section" and is skipped;
an infinite one is a data error and throws -- the same convention the alpha
package uses everywhere, so a broken adjustment upstream fails loudly rather
than poisoning a premium. A t-stat below ~2 says the premium is
indistinguishable from zero across the sample: the factor may be real, but
you were not paid for it here.

## 209. Test the calendar anomalies (day-of-week and turn-of-month)

Two of the oldest anomalies, with the t-stats that decide whether they
survived the papers that published them. `CalendarAnomalies` hands you the
profile AND the significance -- no eyeballing a bar chart.

```java
double[] returns = /* daily returns, >= 30, all finite */;
long[]   days    = /* aligned epoch-millis timestamps */;

CalendarAnomalies.DayOfWeekProfile dow = CalendarAnomalies.dayOfWeek(returns, days);
for (int d = 0; d < 5; d++) {                       // Mon=0 .. Fri=4
    System.out.printf("%s mean %.4f t=%.2f (n=%d)%n",
            java.time.DayOfWeek.of(d + 1), dow.meanReturn()[d], dow.tStat()[d],
            dow.count()[d]);
}

// The turn-of-month window: last 1 trading day + first 3 vs everything else.
CalendarAnomalies.TurnOfMonth tom = CalendarAnomalies.turnOfMonth(returns, days, 1, 3);
System.out.printf("turn-of-month %.4f vs rest %.4f  (Welch t=%.2f)%n",
        tom.insideMean(), tom.outsideMean(), tom.tStat());
```

The turn-of-month split uses a Welch t (unequal variances, unequal counts)
because the two windows are nothing alike in size. A day with fewer than two
observations reports NaN rather than a fake mean. These are the anomalies
most likely to be arbitraged flat by the time you read about them -- so run
them on YOUR sample, not the paper's, and let the t-stat, not the legend,
decide.

## 210. Neutralize a book on sector and beta at once

A stock-selection book should not be a leveraged sector bet or a leveraged
market bet. Demean the weights within each sector, then project out the beta
vector -- two linear operations that leave pure stock selection behind.

```java
double[] weights = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05);

// 1. Sector neutral: every sector's net weight becomes exactly 0. The
//    map overload resolves labels against the context's SORTED symbol
//    axis, so a hand-built array cannot silently misalign.
Map<String, String> sectorBySymbol = /* "AAPL" -> "TECH", ... */;
double[] sn = PortfolioConstruction.sectorNeutralize(ctx, weights, sectorBySymbol);

// 2. Beta neutral: project orthogonal to the trailing betas so sum(w*beta)=0.
double[] betas = PortfolioConstruction.trailingBetas(ctx, index, 126);
double[] neutral = PortfolioConstruction.betaNeutralize(sn, betas);

// Both operations move gross -- re-target it last if size matters.
PortfolioConstruction.normalizeGross(neutral, 1.0);
```

Order matters only a little here (both are linear), but re-targeting gross
comes last because each neutralization shrinks it. `trailingBetas` regresses
each name against the equal-weight universe when you have no index series --
the in-panel market proxy. After this the book's P&L stops explaining itself
with "the market was up" or "tech was hot" and starts answering for its
actual picks.

## 211. Budget a book by inverse volatility

Signal strength says WHAT to own; it does not say how much. Inverse-vol
budgeting rescales each signed position by 1/sigma so a quiet name and a
wild name contribute comparable risk -- the first-order risk parity, exact
when correlations are equal.

```java
double[] weights = PortfolioConstruction.zScoreWeights(scores, 1.0);
double[] vols    = PortfolioConstruction.trailingVols(ctx, index, 63);   // per-bar sigma

double[] budgeted = PortfolioConstruction.inverseVolBudget(weights, vols, 1.0);
```

Note the deliberate strictness: unlike `PositionSizing.inverseVolatilityWeights`
(which silently equal-weights on a degenerate vol because it builds weights
from scratch), `inverseVolBudget` THROWS on a zero or NaN vol -- a flat name
inside an already-signed book is a data problem to surface, not to paper
over. The common per-bar scale cancels in the renormalization, so you never
have to annualize the vols first.

## 212. Walk forward with warm folds (out-of-sample by construction)

The only backtest number worth defending is the one scored on bars the
optimizer never saw. `WalkForwardAnalyzer` re-optimizes the grid on each
train window, trades the next test window with the winner, and carries
capital across folds so the equity curve is continuous.

```java
ParameterGrid grid = new ParameterGrid().add("fast", 10, 20, 30)
                                        .add("slow", 50, 100, 200);
StrategyFactory factory = p ->
        new SmaCrossStrategy(p.get("fast").intValue(), p.get("slow").intValue());

WalkForwardAnalyzer.WalkForwardResult wf = WalkForwardAnalyzer.analyze(
        series, grid, factory, BacktestConfig.defaults(),
        504, 126, PerformanceMetrics::sharpeRatio);          // 2y train, 6m test

System.out.printf("walk-forward efficiency %.2f over %d folds%n",
        wf.efficiency(), wf.folds().size());
System.out.printf("stitched OOS Sharpe %.2f%n",
        wf.outOfSampleMetrics().sharpeRatio());
```

Each fold uses the train window as warm-up and starts trading exactly at the
test boundary -- no look-back leak. `efficiency()` is the ratio of summed
out-of-sample objective to summed in-sample objective: near 1 the edge
travels out of sample, near 0 the grid was fitting the train windows.
`outOfSampleTrades()` are the real fills to feed recipe 220 and 221.

## 213. Grid-search, then deflate the winner's Sharpe

A grid computes N Sharpes and quietly reports the max -- which is the
luckiest of N draws, not an edge. `GridSearchOptimizer` ranks the trials
AND hands you the multiple-testing haircut in one place.

```java
List<GridSearchOptimizer.Candidate> ranked = GridSearchOptimizer.search(
        grid, factory, series, BacktestConfig.defaults(),
        PerformanceMetrics::sharpeRatio);
GridSearchOptimizer.Candidate winner = ranked.getFirst();

// The winner's own per-period returns, from its equity curve:
double[] eq = Backtester.run(factory.create(winner.params()),
        series, BacktestConfig.defaults()).equityCurve();
double[] wr = new double[eq.length - 1];
for (int t = 1; t < eq.length; t++) wr[t - 1] = eq[t] / eq[t - 1] - 1;

double dsr = GridSearchOptimizer.deflatedSharpeOfWinner(ranked, wr, 252);
System.out.printf("winner %s  Sharpe %.2f  deflated %.3f%n",
        winner.params(), winner.metrics().sharpeRatio(), dsr);
```

The null is every trial's own Sharpe: the more configs you tried and the
more they scattered, the higher the bar the winner must clear.
`deflatedSharpeOfWinner` translates that into a probability -- below ~0.95
the "best" parameter set is statistically indistinguishable from picking the
luckiest of the pack, and the pretty backtest is selection bias with a
chart.

## 214. Purge and embargo K-fold for overlapping labels

A 5-bar forward-return label at bar t uses prices through t+5, so an ordinary
K-fold trains on samples whose labels overlap the test set -- leakage that
inflates every ML score. `PurgedKFold` removes the overlap and embargoes the
autocorrelated echo.

```java
int n = series.size();
int labelHorizon = 5;                                // 5-bar forward return
int embargo = (int) (0.01 * n);                      // ~1% of the sample

List<PurgedKFold.Split> splits = PurgedKFold.splits(n, 5, labelHorizon, embargo);
for (PurgedKFold.Split s : splits) {
    // train the model on s.trainIndices(), score on [s.testFrom(), s.testTo())
    System.out.printf("fold %d: test [%d,%d)  train n=%d%n",
            s.fold(), s.testFrom(), s.testTo(), s.trainIndices().length);
}
```

The purge drops training samples whose label window reaches into the test
fold; the embargo drops a further band after it, because returns near the
boundary are correlated even without direct overlap. What survives in
`trainIndices()` is genuinely disjoint in information from the test labels --
the precondition an out-of-sample ML score needs to mean anything.

## 215. Put a probability on backtest overfitting (CSCV / PBO)

Deflated Sharpe judges one winner; CSCV judges the SELECTION PROCESS. Across
every symmetric in-sample/out-of-sample split of the trials, how often is the
in-sample champion a below-median out-of-sample performer? That fraction is
the probability of backtest overfitting.

```java
// One per-period return series per grid variant (columns = variants).
double[][] variantReturns = new double[bars][ranked.size()];
for (int j = 0; j < ranked.size(); j++) {
    double[] e = Backtester.run(factory.create(ranked.get(j).params()),
            series, BacktestConfig.defaults()).equityCurve();
    for (int t = 1; t < e.length; t++)
        variantReturns[t - 1][j] = e[t] / e[t - 1] - 1;
}

OverfitProbability.Result pbo = OverfitProbability.cscvSharpe(variantReturns, 10);
System.out.printf("PBO %.2f over %d splits%n", pbo.pbo(), pbo.combinations());
```

`cscvSharpe` scores each sub-series by per-period Sharpe (a flat line scores
0 -- no risk-adjusted evidence either way). PBO >= 0.5 means the config you
would have picked is a coin flip to lose out of sample, no matter how good
its backtest looked -- the search itself is noise-mining. The block count is
capped at 16 because C(16,8) already enumerates 12,870 splits.

## 216. Bootstrap a confidence interval around your Sharpe

A point Sharpe of 1.4 with no interval is a number pretending to be
knowledge. The stationary block bootstrap resamples the return series in
geometric-length blocks -- preserving autocorrelation -- and hands back the
whole distribution of Sharpes the same data could have produced.

```java
double[] returns = /* strategy per-period returns, >= 50 finite */;

double[] samples = BlockBootstrap.sharpeSamples(
        returns, 20, 5_000, 252, 42L);   // mean block 20, 5000 paths, seed 42
// samples is sorted ascending -- read percentiles straight off it.
double lo = MathUtils.percentileSorted(samples, 0.05);
double hi = MathUtils.percentileSorted(samples, 0.95);
System.out.printf("Sharpe 90%% CI: [%.2f, %.2f]%n", lo, hi);
```

The block length is the whole point: an iid bootstrap (block = 1) shreds the
serial dependence real returns have and reports a falsely tight interval --
the class documents block = 1 only to show why you should not use it. If the
5th percentile sits below zero, a Sharpe of 1.4 is consistent with having no
edge at all; that is the number to put next to the headline, not instead of
it.

## 217. Validate a Sharpe three ways (PSR, DSR, min track record)

Three questions an allocator actually asks, in closed form: is this Sharpe
real given the sample and its fat tails, does it survive the search that
found it, and how long until the record itself is evidence?

```java
double sr = 0.12;            // PER-PERIOD Sharpe (not annualized)
int nObs  = 504;
double skew = -0.4, kurt = 6.0;

// PSR: probability the true Sharpe beats 0, adjusted for skew/kurtosis.
double psr = SharpeValidation.probabilisticSharpe(sr, 0.0, nObs, skew, kurt);

// DSR: PSR against the expected max of all the trials you ran.
double[] trialSharpes = /* per-period Sharpe of every config tried */;
double dsr = SharpeValidation.deflatedSharpe(sr, trialSharpes, nObs, skew, kurt);

// minTRL: how many periods of THIS performance until PSR clears 95%.
double minTrl = SharpeValidation.minTrackRecordLength(sr, 0.0, skew, kurt, 0.95);
System.out.printf("PSR %.3f  DSR %.3f  minTRL %.0f periods%n", psr, dsr, minTrl);
```

Every input is per-period, matching the moments -- annualized Sharpes here
give nonsense. Negative skew and high kurtosis (the option-seller signature)
INFLATE the variance of the Sharpe estimate, so PSR falls: fat left tails
make a given Sharpe less trustworthy, exactly as intuition says.
`minTrackRecordLength` returns +infinity when the Sharpe does not beat the
benchmark -- no length of record proves an edge the record does not show.

## 218. Measure alpha relative to a benchmark (IR and capture)

Absolute return flatters a bull market. Against a benchmark the honest
numbers are Jensen alpha, tracking error, information ratio, and the up/down
capture split that shows WHERE the active return came from.

```java
double[] strat = /* strategy per-period returns */;
double[] bench = /* benchmark per-period returns, must vary */;

BenchmarkComparison.Result b = BenchmarkComparison.compare(strat, bench, 252);
System.out.printf("alpha %.3f  beta %.2f  IR %.2f  TE %.3f%n",
        b.alpha(), b.beta(), b.informationRatio(), b.trackingError());
System.out.printf("up-capture %.2f  down-capture %.2f%n",
        b.upCapture(), b.downCapture());
```

The IR (annualized active return over tracking error) is the number a
manager is hired and fired on: it says how much active return you got per
unit of the risk you took AWAY from the benchmark. The capture split is the
character behind the IR -- up-capture 1.1 with down-capture 0.7 is a manager
who adds in rallies and defends in sell-offs; the reverse is a value trap. A
benchmark with no variance throws, because beta against a constant is
undefined.

## 219. Anatomize the drawdowns (depth AND duration)

Max drawdown is one number about the worst day; a book is killed as often by
the LENGTH of a drawdown as its depth -- the time under water is when
investors leave. `DrawdownAnalytics` returns every episode, both dimensions.

```java
double[] equity = result.equityCurve();
DrawdownAnalytics.Result dd = DrawdownAnalytics.analyze(equity);

System.out.printf("max depth %.1f%%  max duration %d bars  time under water %.0f%%%n",
        dd.maxDepth() * 100, dd.maxDuration(), dd.timeUnderWater() * 100);

for (DrawdownAnalytics.Drawdown d : dd.episodes()) {
    System.out.printf("peak@%d trough@%d recover@%d  depth %.1f%%  %d bars%n",
            d.peakIndex(), d.troughIndex(), d.recoveryIndex(), d.depth() * 100,
            d.duration(equity.length));
}
```

An open drawdown at series end reports `recoveryIndex() == -1`, and
`duration` then runs to the last bar -- the honest "still under water"
answer, not a silent zero. Two strategies with identical 20% max drawdowns
are not the same trade if one recovers in a month and the other takes two
years; `timeUnderWater` and `maxDuration` are what separate them.

## 220. Read the trade ledger (expectancy, payoff, streaks, Kelly)

The equity curve is the outcome; the trade distribution is the process. Win
rate alone lies -- 40% winners with a 3:1 payoff prints money, 60% winners
with a 1:3 payoff bleeds. `TradeAnalytics` reports the full shape.

```java
List<Trade> trades = result.trades();
TradeAnalytics.Result ta = TradeAnalytics.analyze(trades);

System.out.printf("n=%d  win%% %.0f  expectancy %.2f  payoff %.2f%n",
        ta.count(), ta.winRate() * 100, ta.expectancy(), ta.payoffRatio());
System.out.printf("max win streak %d  max loss streak %d  Kelly %.2f%n",
        ta.maxWinStreak(), ta.maxLossStreak(), ta.kellyFraction());
System.out.printf("avg hold: winners %.1f bars, losers %.1f bars%n",
        ta.avgBarsHeldWinners(), ta.avgBarsHeldLosers());
```

Expectancy (average P&L per trade) is the one number that must be positive;
payoff and win rate are the two ways to get there. The Kelly fraction is
`W - (1-W)/R` clamped to [0,1] -- and when it reads a suspicious 1.0 you have
a strategy with NO losing trades in-sample, which is the over-fit tell, not
the jackpot. Losers held longer than winners (the disposition effect) shows
up right in the two hold-time fields.

## 221. Stress the path: reshuffle the trades (Monte Carlo)

Your backtest realized ONE ordering of trades. Reshuffle that same set of
P&Ls thousands of times and the drawdown distribution shows what the strategy
could have done on a less kind path -- the number to size risk against, not
the single lucky sequence you happened to get.

```java
MonteCarloTradeShuffle.Result mc = MonteCarloTradeShuffle.analyze(trades, 10_000, 7L);

System.out.printf("median maxDD %.0f  P95 %.0f  P99 %.0f%n",
        mc.medianMaxDrawdown(), mc.p95MaxDrawdown(), mc.p99MaxDrawdown());
System.out.printf("median terminal PnL %.0f  P(loss) %.2f%n",
        mc.medianTerminalPnl(), mc.probLoss());
System.out.printf("actual maxDD %.0f sits at the %.0fth pct of shuffles%n",
        mc.actualMaxDrawdown(), mc.actualDrawdownPct() * 100);
```

Plan capital against P95, not the median -- the path you get is not the path
you averaged. The tell is `actualDrawdownPct`: if your realized drawdown sits
at the 95th percentile of shuffles, the real trade order was UNUSUALLY
painful, a hint that losses clustered (regime dependence the reshuffle breaks
by assumption). Reshuffling assumes trade independence -- which is precisely
why a large gap between actual and median is informative.

## 222. Backtest a strategy through a real cost model (execution-aware)

The gap between a strategy's gross idea and its net P&L is execution. Feed
the SAME `TradeCostModel` that prices live fills into the backtester and the
Sharpe you read already has commission, spread, slippage and square-root
impact taken out of it.

```java
ExecutionModel model = /* fill model: bar-close, VWAP slice, etc. */;
ExecutionAwareBacktester.ExecutionAwareResult r =
        ExecutionAwareBacktester.run(strategy, series, BacktestConfig.defaults(), model);

// The per-parent execution records show WHERE the slippage went.
System.out.println(r.result().metrics().sharpeRatio());
```

`ExecutionAwareBacktester` answers "how do realistic fills change a
STRATEGY's results?" -- the alpha survives or it does not once the fills are
honest. The cost model is pluggable so the same run can be both
execution-aware and (recipe 225) survivorship-aware: one definition of "what
a trade costs" shared across every engine, which is the only way two
backtests of the same idea can agree.

## 223. Backtest against the tape itself (tick-level, queue-aware)

Bar backtests assume you get the close. At the touch you get a QUEUE POSITION
-- and whether your passive order fills depends on how much size sits ahead of
you. `TickBacktester` replays the actual tick file and models the queue.

```java
TickBacktester.Config cfg = TickBacktester.Config.defaults()
        .withSpreadBps(2.0)
        .withCommissionBps(0.5)
        .withDefaultQueueAhead(5_000)                // size ahead at join
        .withEquitySampleEvery(1_000);

TickStrategy strat = /* your onTick logic implementing TickStrategy */;
TickBacktester.TickBacktestResult r =
        TickBacktester.run(strat, Path.of("ticks.bin"), cfg);

System.out.printf("%s  fills=%d%n", r.strategyName(), r.fills().size());
```

The queue model is the difference between a fantasy and a fill: a passive buy
at the bid only trades once the 5,000 shares ahead of it clear, so patient
strategies get realistic (worse) fill rates and aggressive ones pay the
spread they actually would. This is the lane for market-making and
microstructure alpha where bar granularity would hide the entire P&L.

## 224. Grade the benchmark algos TCA-style over a session

The execution desk's own question is not "does the alpha work?" but "which
benchmark algorithm, at what cost?" `ExecutionAlgoBacktester` works one
parent through a session's bars under each benchmark and grades it like a TCA
report.

```java
ExecutionAlgoBacktester bt = new ExecutionAlgoBacktester(
        ExecutionAlgoBacktester.Config.defaults());     // 5bp spread, 10% cap

for (Benchmark b : Benchmark.values()) {
    ExecutionAlgoBacktester.Result r = b == Benchmark.PARTICIPATION
            ? bt.run(session, Side.BUY, 100_000, b, 0.10)
            : bt.run(session, Side.BUY, 100_000, b);
    System.out.printf("%-16s shortfall %6.1f bps  vs VWAP %6.1f bps  done=%b%n",
            b, r.shortfallBps(), r.vwapSlippageBps(), r.completed());
}
```

Shortfall is signed so POSITIVE is always a cost on both sides -- a buy that
fills above arrival and a sell that fills below both read as positive bps,
so the sign never depends on direction. The class is explicit about its
honest simplifications (fills at close plus cost bps, an oracle VWAP curve,
one bar of POV look-ahead), so the VWAP result is an upper bound on what a
live volume forecast can hit -- read it as the ceiling, not the promise.

## 225. Backtest a book without survivorship bias

A universe of "today's members" quietly deletes every company that went to
zero -- the single most common way a backtest lies. `PortfolioBacktester`'s
survivorship-aware overload trades a point-in-time universe, pays dividends,
and forces delisting/merger sales on the actual date.

```java
PointInTimeUniverse universe = /* membership + terminal events by date */;
Map<String, List<CorporateActions.CorporateAction>> divs = /* cash dividends */;

PortfolioBacktester.Config cfg = PortfolioBacktester.Config.defaults()
        .withRebalanceEvery(21)
        .withCostModel(TradeCostModel.institutional(1, 2, 1, 20));   // shared seam

PortfolioBacktester.Result r = PortfolioBacktester.run(
        strategy, priceData, cfg, universe, divs);

System.out.printf("CAGR-equity final %.0f  costs %.0f  dividends %.0f  events %d%n",
        r.equityCurve()[r.equityCurve().length - 1], r.totalCosts(),
        r.dividendCashCredited(), r.lifecycleEventsApplied());
```

A name that delists on its ex-dividend date still pays its holder BEFORE the
position terminates -- the entitlement belongs to whoever held through the
prior close, and the engine processes dividends first for exactly that
reason. Because the cost model is the same `TradeCostModel` as recipe 222,
one run is simultaneously survivorship-aware and execution-aware: the two
biggest backtest lies, closed by the same call.

## 226. Cross-sectional momentum, point-in-time

The academic 12-1: rank the universe on trailing return skipping the last
month (to dodge short-term reversal), go long the top names and short the
bottom, dollar-neutral. `CrossSectionalMomentum` implements it as a
`PortfolioStrategy` that honors a point-in-time universe.

```java
CrossSectionalMomentum strat = new CrossSectionalMomentum(
        universe,                                      // null = always tradeable
        CrossSectionalMomentum.Config.twelveMinusOne(20));   // 252-21, 20/side

PortfolioBacktester.Result r = PortfolioBacktester.run(
        strat, priceData, PortfolioBacktester.Config.defaults().withRebalanceEvery(21),
        universe, Map.of());
System.out.printf("%s -> Sharpe %.2f%n", strat.name(), r.metrics().sharpeRatio());
```

The skip window is not cosmetic: the most recent month carries a
reversal that momentum must step over, which is why 12-1 (`252, 21`) beats
raw 12-0 out of sample. `perSide` shrinks automatically when the live member
count cannot fill both books without overlap, so the strategy never
double-counts a name long and short on a thin day -- the point-in-time
universe drives the cross-section, not a static ticker list.

## 227. Size positions by Kelly and by fixed-fractional risk

Two sizing philosophies from one class: Kelly maximizes long-run growth from
a return distribution; fixed-fractional caps the loss if the stop is hit.
Most desks trade half-Kelly and size each entry off its stop.

```java
double mean = 0.0008, var = 0.0004;                   // per-period return moments
double full = PositionSizing.kellyFraction(mean, var);     // mu / sigma^2
double half = PositionSizing.halfKelly(mean, var);         // the practitioner default

// Fixed-fractional: shares so that hitting the stop loses exactly 1% of equity.
double shares = PositionSizing.fixedFractionalQuantity(
        1_000_000, 0.01, 100.0, 97.0);               // equity, riskFrac, entry, stop

// Scale a sleeve to a target vol, and blend a multi-asset sleeve by inverse vol.
double lev = PositionSizing.volatilityTargetLeverage(0.09, 0.10);   // current, target
double[] w = PositionSizing.inverseVolatilityWeights(new double[]{0.10, 0.20, 0.15});
```

Full Kelly is a knife-edge: it is growth-optimal but its drawdowns are
brutal and it assumes you know `mu` and `sigma` exactly -- you do not, which
is why `halfKelly` (the growth-vs-drawdown trade) is the standard. Fixed
fractional inverts the question: instead of "how much do I believe?" it asks
"how much can I lose if I'm wrong?" and sizes the position so the stop, not
the conviction, sets the risk.

## 228. Compare a flat cost model with institutional square-root impact

Whether your book is capacity-constrained is a question flat costs cannot
answer. The institutional model adds a square-root impact term that GROWS
with size -- so the same strategy that thrives at $10m can bleed at $1bn, and
the model shows you where the line is.

```java
BarSeries s = series;
double notional = 5_000_000;

TradeCostModel flat = TradeCostModel.flat(3);         // 3 bps, size-independent
TradeCostModel inst = TradeCostModel.institutional(1, 2, 1, 20);   // comm+spread+slip+impact

System.out.printf("flat: %.2f bps   institutional: %.2f bps%n",
        flat.costBps(s, 500, notional),
        inst.costBps(s, 500, notional));

// Double the size: flat is unchanged, the impact term climbs.
System.out.printf("at 4x size, institutional: %.2f bps%n",
        inst.costBps(s, 500, 4 * notional));
```

The impact term reads trailing ADV and vol from the bars via
`MarketImpactModel.estimate`; before `impactWindow` bars (or on a series with
no volume) it degrades to the flat components rather than crashing --
documented, never silent. Feed `institutional` into recipes 222 and 225 and
your Sharpe is quoted at your ACTUAL size, which is the difference between a
research toy and a capacity plan.

## 229. Construct a portfolio three ways and see why they disagree

Max-Sharpe, risk parity and Black-Litterman price estimation error
differently -- feed them the same inputs and their disagreement IS the
lesson about how much to trust your return forecasts.

```java
double[] mu = {0.08, 0.10, 0.12};
double[][] cov = {{0.040, 0.018, 0.012}, {0.018, 0.090, 0.024}, {0.012, 0.024, 0.160}};

// Trusts mu completely -- and concentrates accordingly.
PortfolioOptimizer.Allocation ms = new PortfolioOptimizer(mu, cov).maxSharpe(0.03);

// Ignores mu -- equal risk contribution; each name is 1/3 of variance.
PortfolioOptimizer.Allocation rp = RiskParityOptimizer.equalRiskContribution(mu, cov);
double[] rc = RiskParityOptimizer.riskContributions(rp.weights(), cov);

// Starts from the market's implied views, tilts by one explicit opinion.
double[] pi = BlackLitterman.impliedEquilibriumReturns(2.5, cov, new double[]{0.5, 0.3, 0.2});
double[] post = BlackLitterman.posteriorReturns(0.025, cov, pi,
        new double[][]{{0, 1, -1}}, new double[]{0.02}, new double[]{0.001});
PortfolioOptimizer.Allocation bl = new PortfolioOptimizer(post, cov).maxSharpe(0.03);
```

Max-Sharpe piles into whatever `mu` flatters -- garbage in, leverage out.
Risk parity throws `mu` away and hands the low-vol asset the big weight.
Black-Litterman sits between: shrink the view uncertainty `omega` toward 0
and the BL weights walk to max-Sharpe; inflate it and they walk back to the
market. Same data, three answers, one spectrum of trust.

## 230. Optimize under real constraints (bounds and turnover)

The unconstrained optimizer's beautiful weights are often un-tradeable: a 60%
single-name bet, or a rebalance that churns the whole book. The constrained
long-only optimizer respects per-asset caps and charges turnover against
current holdings.

```java
double[] mu = {0.08, 0.10, 0.12, 0.07};
double[][] cov = /* 4x4 covariance */;
double[] current = {0.25, 0.25, 0.25, 0.25};          // today's book

PortfolioOptimizer.Allocation a = new ConstrainedPortfolioOptimizer(mu, cov)
        .withBounds(new double[]{0.05, 0.05, 0.05, 0.05},     // floors
                    new double[]{0.40, 0.40, 0.40, 0.40})     // caps
        .withTurnoverPenalty(current, 0.001)          // 10bps per unit turnover
        .maxSharpe(0.03);

System.out.println(java.util.Arrays.toString(a.weights()));   // sums to 1, within bounds
```

The turnover penalty makes the optimizer trade expected gain against the real
cost of getting there: a marginal alpha improvement that requires churning
30% of the book may not clear 10bps of round-trip cost, so the optimizer
leaves the position alone. The bounds are validated at configuration to admit
a fully-invested portfolio, so an infeasible box (caps summing below 1)
throws up front rather than returning a silently un-normalized answer.

## 231. Run the alpha pipeline end to end

Signal to net Sharpe in five moves: build the panel, pick the factor,
evaluate it, choose the construction, and backtest with real costs. Every
stage is a seam you can swap.

```java
AlphaContext ctx = AlphaContext.of(panel, fundamentals)          // Maps by symbol
        .withUniverse(pointInTimeUniverse);                      // no dead names

AlphaFactor factor = Factors.value();                            // or momentum, quality...

// 1) Is the signal even there? (recipe 206)
System.out.println(SignalEvaluator.evaluate(ctx, factor, 130, 21).format());

// 2) Backtest with the z-score construction and institutional costs.
AlphaBacktester.Config cfg = AlphaBacktester.Config.defaults(130);   // startIndex
AlphaBacktester.Result r = AlphaBacktester.run(ctx, factor, cfg,
        (c, scores, i) -> {                                      // custom construction
            double[] w = PortfolioConstruction.zScoreWeights(scores, 1.0, 0.05);
            double[] betas = PortfolioConstruction.trailingBetas(c, i, 126);
            return PortfolioConstruction.betaNeutralize(w, betas);
        });

System.out.printf("net Sharpe %.2f  gross Sharpe %.2f  cost drag %.4f  turnover %.0f%%%n",
        r.netMetrics().sharpeRatio(), r.grossMetrics().sharpeRatio(),
        r.totalCostDrag(), r.meanTurnover() * 100);
```

The gross-minus-net gap is the whole point: `totalCostDrag()` decomposes
into commission, spread, slippage and impact, so you see exactly which cost
kills the edge. If the book is too large for the universe's liquidity the
impact term throws rather than compounding negative equity into sign-inverted
garbage -- that loud failure IS the capacity answer for this factor at this
size.

## 232. Blend several alphas, IC-weighted and honest

A desk rarely trades one signal. The blending question is the same honesty
problem each learner solves: how much should each component be trusted, on
evidence it could not have memorized? `AlphaEnsemble` runs a prequential IC
per component and sizes by it.

```java
AlphaEnsemble ens = new AlphaEnsemble(3, 0.01);       // 3 signals, ~200-obs IC memory

for (int t = 0; t < intervals; t++) {
    double[] components = { queueEcho[t], learnerPrediction[t], leadLag[t] };  // ~[-1,1]
    ens.onObservation(components, realizedReturn[t]);  // scores PREVIOUS snapshot, honest
    double alpha = ens.combined(components);           // clamp(sum max(0,IC)*value)
    // feed alpha into BenchmarkExecutor.MarketState.alpha
}

for (int c = 0; c < ens.components(); c++)
    System.out.printf("component %d IC %.3f%n", c, ens.componentIC(c));
```

`onObservation` scores the values snapshotted at the PREVIOUS call against
the return just realized -- values now already contain the move, the same
nowcast trap the online learner closes. The IC weights are deliberately NOT
renormalized to sum to 1: a lone barely-trusted component with IC 0.01 must
emit a barely-sized signal, not a full-strength one. Until the record spans
one IC memory the ensemble emits 0 -- an unproven blend is silent.

## 233. Learn an alpha online, scored prequentially

The learner that grades itself as it goes: predict from the current weights,
score that prediction against the realized return, THEN update -- so the
information coefficient is out-of-sample by construction, a tripwire you can
gate on live.

```java
OnlineAlphaLearner learner = new OnlineAlphaLearner(0.01, 1e-4, 0.01);  // lr, ridge, IC

for (int t = 0; t < intervals; t++) {
    double pred = learner.predict(qImb[t], tImb[t], ofi[t], momZ[t]);   // four ingredients
    // ... act on pred if the gate below is open ...
    learner.train(qImb[t], tImb[t], ofi[t], momZ[t], realizedReturn[t]);
}

double ic = learner.outOfSampleIC();
double sized = learner.normalizedPrediction(qImb[now], tImb[now], ofi[now], momZ[now]);
System.out.printf("prequential IC %.3f  gated signal %.2f%n", ic, sized);
```

`normalizedPrediction` returns 0 while the out-of-sample IC is not positive
OR the track record is shorter than one IC memory (~1/icAlpha samples): a
lucky first hour is not evidence, so the learner stays silent until it has
demonstrated live predictive power. Ridge shrinkage keeps the four weights
from chasing one lucky streak. Same caveat as every learned signal here --
the live IC is a tripwire, not a validation; run the blend through the
walk-forward machinery of recipe 212 before you trade it.

## 234. Report a factor's decay and attribute its returns

Two questions after a factor works: how FAST does the edge decay (which sets
the rebalance frequency), and how much of the P&L is genuinely new versus a
repackaged known factor?

```java
// Decay: mean IC at each forward horizon, with the half-life.
AlphaReport.Decay decay = AlphaReport.decayProfile(ctx, factor, 130,
        new int[]{1, 5, 10, 21, 42, 63});
System.out.println(decay.format());
// e.g. h=1:0.05 h=5:0.04 ... | half-life=18.4 bars

// Attribution: regress the book's returns on known factor streams.
double[] port = AlphaReport.returnsOf(r.netEquity());   // portfolio per-bar returns
double[][] factorReturns = { marketRet, valueRet, sizeRet };
AlphaReport.Attribution attr = AlphaReport.attribute(port, factorReturns,
        List.of("MKT", "HML", "SMB"));
System.out.println(attr.format());
// attribution: alpha=0.000210/bar MKT=0.85 HML=0.31 SMB=-0.12 R2=0.640
```

The half-life sets the trade cadence: a signal that decays in 3 bars
rebalanced monthly harvests almost none of its edge, and one with a 60-bar
half-life churned daily just pays costs. Attribution's intercept is the
residual alpha -- what survives after MKT/HML/SMB take their share; a high
R-squared with a near-zero alpha means you rebuilt a known factor at
full turnover, which the fee committee will notice.

## 235. Find a mean-reversion half-life (Ornstein-Uhlenbeck)

Before trading a spread as mean-reverting, prove it reverts and measure how
FAST. `OrnsteinUhlenbeck.fit` estimates the OU parameters from the series and
refuses one with no in-sample reversion rather than reporting an infinite
half-life as a trade.

```java
double[] spread = /* e.g. pricesA - hedgeRatio * pricesB, >= 30 points */;
OrnsteinUhlenbeck.Params ou = OrnsteinUhlenbeck.fit(spread, 1.0 / 252);   // dt in years

System.out.printf("kappa %.2f  theta %.4f  sigma %.4f  half-life %.1f days%n",
        ou.kappa(), ou.theta(), ou.sigma(), ou.halfLife() * 252);

double z = ou.zScore(spread[spread.length - 1]);      // (x - theta) / stationary sd
if (Math.abs(z) > 2.0) { /* the spread is stretched -- fade it */ }
```

The half-life (`ln 2 / kappa`) is the sanity filter: an 8-day half-life is a
tradeable spring, a 200-day one is an index fund with extra steps -- skip it.
The z-score measures the current dislocation in stationary-standard-deviation
units, so a threshold of 2 means "two sigma stretched," comparable across
spreads with different absolute scales. `lastZScore` is the one-call shortcut
when you only need the latest reading.

## 236. Test trend versus reversion (variance ratio)

Is a return series trending, reverting, or a random walk? The variance ratio
compares the variance of q-period returns to q times the one-period variance:
1 is a random walk, above 1 trends, below 1 reverts -- with a z-stat that
says whether the deviation is even real.

```java
double[] returns = /* 1-period returns, >= 10*q finite */;

for (int q : new int[]{2, 5, 10, 20}) {
    VarianceRatio.Result vr = VarianceRatio.test(returns, q);
    System.out.printf("VR(%d) = %.3f  z=%.2f  %s%n", q, vr.ratio(), vr.zStat(),
            vr.rejectsRandomWalk() ? "NOT a random walk" : "consistent with random walk");
}
```

Run it across several horizons: a series can revert at short q (microstructure
bounce) and trend at long q (a slow drift), and the horizon where the ratio
crosses 1 is where the character flips -- exactly the horizon a strategy
should target. `rejectsRandomWalk()` fires at |z| >= 2; below that, whatever
pattern you think you see is inside the noise band, and a strategy built on it
is fitting the sample.

## 237. Stand up a central risk book across equities and FX

Two desks paying the street to shed opposite risks is money burned twice. The
CRB nets first: book every desk's flow into ONE factor-space inventory, and
the offsets cancel before anyone hedges. It spans asset classes -- equity and
FX risk live in the same net.

```java
CentralRiskBook book = new CentralRiskBook();

// Equity desk buys SPY; FX desk sells EURUSD -- both into the shared book.
book.bookCashEquity("cash-eq-desk", "SPY", 20_000, 500.0);       // qty, price
book.bookFxSpot("fx-desk", "EURUSD", 10_000_000, 1.085);         // base notional, rate

System.out.printf("net SPY-factor exposure  %,.0f%n", book.exposure("EQ:SPY"));
System.out.printf("net EUR-leg exposure     %,.0f%n", book.exposure("CCY:EUR"));
System.out.printf("netting efficiency %.1f%%  over %d flows%n",
        book.nettingEfficiency() * 100, book.flowsBooked());
```

`nettingEfficiency` is the headline: 1 minus net-over-gross, the fraction of
notional the book made disappear by pairing opposite flows internally. Every
flow lands in the `FactorRegistry` factor space, so an equity delta and an FX
delta are additive risks a single covariance can price -- which is what makes
the diversification report of recipe 244 and the cost-aware hedge of recipe
240 possible at all. The whole book serializes for overnight persistence
(`writeState`/`readState`).

## 238. Quote two-sided with inventory skew

A market maker long its limit does not want more; the price should say so.
`SkewedQuoter` shades the two-way quote toward the side that reduces
inventory -- passive risk management priced into every quote, not bolted on
after.

```java
double inventory = book.exposure("EQ:SPY");           // signed, from the CRB
SkewedQuoter.Quote q = SkewedQuoter.quote(
        500.0,          // mid
        2.0,            // unskewed half spread, bps
        inventory,      // current book inventory (signed)
        5_000_000,      // inventory limit
        0.5);           // a full-limit book shades half the half-spread

System.out.printf("bid %.4f  ask %.4f  skew %.1f bps%n", q.bid(), q.ask(), q.skewBps());
```

Long inventory drops both bid and ask, making the book cheap to hit and dear
to lift -- the market does the de-risking for you at a price you chose.
`skewFraction` caps how far a full-limit inventory can push the quote, and the
constructor rejects a spread so wide the worst-case shade would quote a
non-positive bid (100% half-spreads do not exist in the markets this serves).
This is recipe 17's toxicity defense with the inventory axis: widen for
toxicity, skew for inventory, and you have a survivable quote.

## 239. Decide internalize-or-hedge on each client flow

Not all client flow is a cost to lay off -- flow that REDUCES the book's risk
is worth paying to keep. `InternalizationEngine` warehouses risk-reducing
flow (and shares the saved spread with the client), and routes only what
would push inventory past the warehouse limit.

```java
InternalizationEngine engine = new InternalizationEngine(5_000_000, 0.4);  // limit, share

double bookNet = book.exposure("EQ:SPY");
InternalizationEngine.Decision d = engine.decide(bookNet, clientFlow, 2.0);  // half-spread bps

if (d.internalized() != 0)
    book.bookCashEquity("client-flow", "SPY", d.internalized() / px, px);
System.out.printf("internalized %,.0f  routed %,.0f  improvement %.2f bps%n",
        d.internalized(), d.routed(), d.improvementBps());
```

Risk-reducing flow is internalized in full and the client gets `improvementShare`
of the half-spread back -- the book pays for the privilege of shrinking its
own inventory. Risk-adding flow is internalized only up to the warehouse
limit; the excess routes out. The improvement paid and spread captured feed
straight into the ledger of recipe 243, where the day's close answers whether
internalizing beat routing everything.

## 240. Optimize the cheapest hedge for the net book

When the book must hedge, it should buy the CHEAPEST basket that flattens the
risk -- not one future per factor. `HedgeOptimizer` minimizes hedged variance
plus a cost penalty over a set of spanning instruments, trading residual risk
against transaction cost.

```java
double[]   exposures = book.netExposures();           // factor-space, registry order
double[][] cov       = /* factor covariance */;
double[][] loadings  = /* loadings[factor][instrument] */;
double[]   costs     = /* per-unit cost per instrument */;

double[] h = HedgeOptimizer.hedge(exposures, cov, loadings, costs, /*lambda*/ 1e-6);

double residualRisk = HedgeOptimizer.risk(
        HedgeOptimizer.residual(exposures, loadings, h), cov);
System.out.printf("hedged residual risk %.2f%n", residualRisk);
```

`lambda = 0` is pure minimum variance -- it will trade a near-worthless
instrument to shave a basis point of risk. Raising lambda buys less hedge and
leaves a little more residual, which is correct when the instrument is
expensive or illiquid. The solver runs Gauss-Seidel with a generous iteration
cap because near-collinear instruments (0.999-correlated futures) contract
slowly, and a silent max-iteration exit would return a plausible-looking,
grossly unconverged hedge -- so it refuses rather than lies.

## 241. Auto-hedge only the excess, with limit escalation

Hedging the whole book on every wobble pays away the CRB's edge -- inventory
inside the band is the franchise, not the problem. `CrbAutoHedger` fires only
when a factor breaches its band, hedges just the excess beyond the reset
level, and escalates to a cost-blind hedge if the cost-aware one still leaves
a hard-limit breach.

```java
CrbAutoHedger hedger = new CrbAutoHedger(limits, 0.6, 1);   // bands, reset frac, cooldown

CrbHedgeUniverse hedges = new CrbHedgeUniverse(book.factors())
        .addSingleFactor("ES-FUTURE", "EQ:SPX", 0.4)
        .addFxForward("EURUSD-1W", "EURUSD", 1.085, 0.2);

CrbAutoHedger.HedgeOrder[] orders = hedger.check(book.netExposures(), cov,
        hedges.loadings(), hedges.costs(), /*lambda*/ 1e-6, interval);
for (CrbAutoHedger.HedgeOrder o : orders)
    System.out.printf("hedge %s  %,.0f%n", hedges.name(o.instrument()), o.notional());
```

Only the exposure beyond `resetFraction * limit` on breached factors is
hedged -- flattening the whole book on a small breach would churn away
inventory that is inside its band on purpose. The cooldown suppresses
re-hedging for `cooldownIntervals` after a fire, so a jittery factor does not
generate a storm of tiny orders. And the escalation is the safety net: an
instrument set that cannot span a factor cannot fix it at any lambda, so the
best-effort cost-blind pass sends the orders anyway rather than leaving a hard
breach open.

## 242. Route the hedge internal, then dark, then lit

A hedge order should cross the book's own offsetting inventory for free
before it ever pays a venue, and prefer low-toxicity dark liquidity to the
lit spread. `CrbRouter` allocates the notional down that cost ladder in one
call.

```java
CrbRouter.DarkVenue[] venues = {
        new CrbRouter.DarkVenue("POOL-A", 2_000_000, 0.6, 0.5),   // liq, fillProb, adverse bps
        new CrbRouter.DarkVenue("POOL-B", 1_000_000, 0.4, 1.2),
};

CrbRouter.Allocation a = CrbRouter.route(
        Math.abs(order.notional()),   // amount to execute
        crossableInternal,            // offsetting book inventory available for free
        venues, /*halfSpreadBps*/ 1.0, /*impactBps*/ 2.0);

System.out.printf("internal %,.0f  dark %s  lit %,.0f  blended cost %.2f bps%n",
        a.internal(), java.util.Arrays.toString(a.dark()), a.lit(), a.expectedCostBps());
```

Internal crossing costs zero, so it fills first; dark venues are then used in
ASCENDING adverse-selection order but only while they undercut the lit cost
(`halfSpread + impact`) -- a pool whose expected adverse selection exceeds the
lit spread is worse than just crossing the spread, and the router skips it.
Whatever remains goes lit. The `impactBps` input is exactly where a
`KylesLambda` estimate slots in, so the routing decision uses YOUR size's
expected impact, not a constant.

## 243. Keep the CRB's P&L ledger and settle the day

Did the netting pay for its own risk management? Only a ledger that tracks
every leg -- spread captured, improvement paid to clients, hedge cost, router
cost -- can answer, and `CrbPnlLedger` nets them into one number at the close.

```java
CrbPnlLedger ledger = new CrbPnlLedger();

ledger.onDecision(d, 2.0);                       // spread captured, improvement paid
ledger.onHedge(order.notional(), costBps);       // cost of the hedge itself
ledger.onRoute(Math.abs(order.notional()), a);   // routing cost from the Allocation

System.out.printf("spread captured %,.0f  improvement paid %,.0f%n",
        ledger.spreadCaptured(), ledger.improvementPaid());
System.out.printf("hedge cost %,.0f  router cost %,.0f%n",
        ledger.hedgeCost(), ledger.routerCost());
System.out.printf("NET ECONOMICS %,.0f  (positive = the book earned its keep)%n",
        ledger.netEconomics());
```

`netEconomics` is spread captured minus improvement paid minus hedge and
router costs -- the one figure that says whether centralizing risk beat
letting each desk hedge itself. A positive number means the internalization
and netting more than covered the cost of hedging the residual; a persistently
negative one means the book is warehousing risk it should be laying off. The
ledger serializes alongside the book and engine so the running total survives
the overnight checkpoint of recipe 44.

## 244. Report the diversification benefit the book bought

The reason a central book beats siloed desks is that risks partly offset --
the whole is less risky than the sum of the parts. `CentralRiskBook.report`
prices that: portfolio VaR against the sum of standalone desk VaRs, the
difference being the diversification benefit.

```java
double[][] cov = /* factor covariance */;
CentralRiskBook.CrbReport rep = book.report(cov, 0.99);

System.out.printf("gross %,.0f  net %,.0f  netting %.1f%%%n",
        rep.grossExposure(), rep.netExposure(), rep.nettingEfficiency() * 100);
System.out.printf("portfolio VaR %,.0f  ES %,.0f%n", rep.var(), rep.es());
System.out.printf("sum of standalone desk VaRs %,.0f%n", rep.standaloneDeskVar());
System.out.printf("diversification benefit %,.0f%n", rep.diversificationBenefit());
```

`diversificationBenefit` is `standaloneDeskVar - var`: the risk capital the
firm does NOT have to hold because the desks' risks are not perfectly
correlated. It is the number that justifies the CRB's existence to the CFO --
run the desks separately and each must be capitalized for its own VaR; run
them in one book and the offsets free up capital. Pair it with recipe 16's
market-risk day: the same covariance feeds the VaR here and the stress tests
there.

## 245. Run the alpha pipeline on credit

The alpha machinery is asset-class agnostic: build the panel out of credit
total-return series and write a factor whose scores come from the credit
curve. A carry factor ranks names by spread earned per unit of default risk,
using the same `CdsPricer`/`CreditCurve` the desk marks with.

```java
YieldCurve discount = YieldCurve.ofZeroRates(new double[]{1, 3, 5, 7, 10},
        new double[]{0.03, 0.03, 0.03, 0.03, 0.03});

// A credit-carry factor: par spread minus the triangle's fair spread,
// so cheap-to-default names (high carry per risk) rank high.
AlphaFactor creditCarry = (ctx, i) -> {
    double[] s = new double[ctx.symbolCount()];
    for (int k = 0; k < s.length; k++) {
        CreditCurve cc = curveFor(ctx.symbols().get(k), i);        // your point-in-time curve
        double par = CdsPricer.parSpread(cc, discount, 5);
        double fair = cc.hazard(5) * (1 - cc.recovery());          // the credit triangle
        s[k] = par - fair;                                         // carry per unit risk
    }
    return s;
};

AlphaContext ctx = AlphaContext.of(creditTotalReturnPanel);
System.out.println(SignalEvaluator.evaluate(ctx, creditCarry, 130, 21).format());
```

Every stage downstream is unchanged: `zScoreWeights` sizes the book,
`AlphaBacktester` charges costs, `SignalEvaluator` scores the IC. The credit
triangle (`spread ~ hazard * (1 - recovery)`) is the fair spread the factor
measures dislocation against -- a name trading well above its triangle is
paid more than its default risk warrants, the credit analogue of a value tilt.
Point-in-time curves are the caller's discipline: a factor that peeks at
tomorrow's spread invalidates the whole evaluation.

## 246. Run the same pipeline on commodities

Swap the factor, keep the pipeline. A commodity's return is mostly roll, not
spot -- so the natural factor ranks contracts by carry read straight off the
`CommodityCurve`. Backwardated curves pay the long to roll; contango charges
it.

```java
AlphaFactor rollCarry = (ctx, i) -> {
    double[] s = new double[ctx.symbolCount()];
    for (int k = 0; k < s.length; k++) {
        CommodityCurve curve = curveFor(ctx.symbols().get(k), i);   // point-in-time
        // Annualized roll yield front-to-deferred: positive in backwardation.
        s[k] = curve.annualizedRollYield(0.25, 1.0);
    }
    return s;
};

AlphaContext ctx = AlphaContext.of(commodityExcessReturnPanel);
SignalEvaluator.Report r = SignalEvaluator.evaluate(ctx, rollCarry, 130, 21);
System.out.printf("commodity carry: IC %.3f  IR %.2f  turnover %.0f%%%n",
        r.meanIc(), r.ir(), r.meanTurnover() * 100);
```

Carry has been the single most durable commodity factor precisely because the
roll term has dominated index returns for decades -- long backwardation, short
contango is harvesting the storage-and-convenience structure, not forecasting
spot. `impliedCarry(t, rate)` gives the same signal in storage-minus-convenience
terms if you prefer to rank on the arbitrage relation directly. The panel is
excess (fully-collateralized) returns so the risk-free leg does not pollute
the cross-section.

## 247. Hedge a commodity position with an Asian option

A physical commodity book is exposed to the AVERAGE price over a delivery
window, not a single expiry -- so the right hedge is an Asian option, and its
lower vol (averaging dampens the terminal distribution) makes it cheaper than
the European that over-hedges it.

```java
CommodityCurve wti = CommodityCurve.of(80,
        new double[]{0.25, 0.5, 1.0}, new double[]{81, 82, 84});   // mild contango
double forward = wti.price(1.0);                                    // 1y futures

// Protect the average over ~monthly fixings across the delivery year.
double asianPut = AsianOption.arithmeticPrice(
        BlackScholes.OptionType.PUT, wti.spot(), 78, 0.03,
        0.03 - Math.log(forward / wti.spot()),   // carry that reprices the forward
        0.35, 1.0, 12);                          // vol, 1y, 12 fixings

double europeanPut = BlackScholes.price(BlackScholes.OptionType.PUT,
        wti.spot(), 78, 0.03, 0.0, 0.35, 1.0);
System.out.printf("Asian put %.2f  vs European %.2f  (averaging saves %.2f)%n",
        asianPut, europeanPut, europeanPut - asianPut);
```

The Asian always costs less than the otherwise-identical European because
averaging shrinks the variance of the payoff's underlying -- and it hedges the
real exposure of a book that prices off a monthly average, so you neither
over-pay nor over-hedge. `arithmeticPrice` uses Turnbull-Wakeman two-moment
matching (the arithmetic average is not lognormal); `geometricPrice` is the
exact Kemna-Vorst benchmark to sanity-check it against. Set the carry so the
model's forward matches the curve's -- otherwise you are hedging a different
forward than the one you own.

## 248. Mark a structured note as its replication P&L

A structured note is a bond plus an option position -- price it as its
replicating parts and its mark-to-market is just the sum of the pieces
moving. A reverse convertible is a deposit MINUS a put; the holder is short
volatility for the enhanced coupon.

```java
// Issue: 9% coupon reverse convertible, strike 100, on a 100-spot underlying.
double atIssue = StructuredNotes.reverseConvertible(
        100, 0.09, 100, 100, 0.03, 0.0, 0.25, 1.0);        // par, coupon, S, K, r, q, vol, T

// The stock drops 8% and vol spikes to 35 -- the short put hurts.
double later = StructuredNotes.reverseConvertible(
        100, 0.09, 92, 100, 0.03, 0.0, 0.35, 0.75);
double delta = StructuredNotes.reverseConvertibleDelta(100, 92, 100, 0.03, 0.0, 0.35, 0.75);

System.out.printf("note MTM: %.2f -> %.2f  (P&L %.2f)  delta %.3f%n",
        atIssue, later, later - atIssue, delta);
```

The mark falls on two fronts at once: the underlying dropped toward the strike
(the short put gains intrinsic value against the holder) AND vol rose (the
short put the holder is implicitly selling got more expensive) -- the classic
short-vol pain the enhanced coupon was paying for. The positive delta confirms
the holder is effectively LONG the stock through the short put. For a
capital-protected note use `capitalProtectedNote` (a discount bond plus a long
call) and `participationFor` to back out the participation an issue price
affords -- the design equation that explains why zero-rate eras produce
embarrassing participation.

## 249. Compute a swap's carry-and-roll

A received-fixed swap earns two things even if rates never move: the fixed
coupon minus the (lower, on an upward curve) floating it pays -- carry -- and
the pull as the position rolls down the curve to shorter, higher-priced
points -- roll. `SwapPricer` gives the PV, par rate and DV01 to build both.

```java
double[] t = {1, 2, 3, 5, 7, 10};
double[] zeros = {0.030, 0.032, 0.034, 0.036, 0.038, 0.040};   // upward curve
YieldCurve curve = YieldCurve.ofZeroRates(t, zeros);

double par5 = SwapPricer.parRate(curve, 5);
double pv   = SwapPricer.payerPv(curve, 5, par5 - 0.005);      // receive-fixed 50bp above float
double dv01 = SwapPricer.dv01(curve, 5, par5 - 0.005);

// Roll: the par rate 1y forward is the 4y par -- the position rolls toward it.
double roll1y = par5 - SwapPricer.parRate(curve, 4);
System.out.printf("PV %.5f  DV01 %.6f  1y roll-down %.1f bp%n", pv, dv01, roll1y * 1e4);
```

Carry-and-roll is the return to STANDING STILL -- the part of a rates trade's
P&L that shows up if the curve is unchanged tomorrow, and on a steep upward
curve it is most of the ex-ante expected return. The DV01 subtlety worth
knowing: the bump hits the continuously-compounded zero curve and the simple
par rate is `e^z - 1`, so a 1bp zero shift moves the par rate by `e^z` bp --
about 5% above the naive `annuity * 1bp` at these rates. A swap struck exactly
at par PVs to zero, pinned as an identity, so `payerPv(curve, 5, par5)` is your
smoke test.

## 250. Run an index-rebalance strategy

An index reconstitution is a known, dated flow: names enter and leave, and
funds tracking the index must trade the change -- a front-runnable event.
`IndexConstruction` builds the float-adjusted weights and, crucially, the
divisor adjustment that keeps the level continuous through the change.

```java
double[] prices = {180, 95, 410, 60};
double[] shares = {15.9e9, 7.4e9, 3.1e9, 5.0e9};
double[] floatF = {1.0, 0.85, 1.0, 0.70};

double[] wBefore = IndexConstruction.capWeights(prices, shares, floatF);
double aggBefore = 0;
for (int i = 0; i < prices.length; i++) aggBefore += prices[i] * shares[i] * floatF[i];

// A name's float doubles at reconstitution -- rescale the divisor so the
// published level does not jump on a purely mechanical change.
floatF[1] = 1.0;
double aggAfter = 0;
for (int i = 0; i < prices.length; i++) aggAfter += prices[i] * shares[i] * floatF[i];
double newDivisor = IndexConstruction.adjustDivisor(oldDivisor, aggBefore, aggAfter);

double[] wAfter = IndexConstruction.capWeights(prices, shares, floatF);
System.out.printf("rebalance turnover %.2f%%%n",
        IndexConstruction.turnover(wBefore, wAfter) * 100);
```

The divisor adjustment is the whole trick of index maintenance: a
float-factor change or a member swap alters the aggregate cap for
non-economic reasons, and without rescaling the divisor the level would gap on
reconstitution day. `turnover` (half the L1 distance between weight vectors)
sizes the flow every tracking fund must execute -- which is exactly the
predictable demand an index-rebalance strategy positions ahead of. The trade
is front-running a calendar, so its edge decays as more capital crowds the
same known dates.

## 251. Compare private markets against public honestly

No daily prices, manager-timed cash flows, appraisal NAVs that understate
volatility -- the public toolkit fails on purpose here. The LP answer to "did
they beat the market?" is PME, which grows every cash flow forward at the
index's return so an irregular schedule can be judged fairly.

```java
// Investor-signed flows: contributions negative, distributions positive;
// the final period folds terminal NAV in as a distribution.
double irr  = PrivateMarketAnalytics.irr(new double[]{-100, -50, 30, 60, 130});
double tvpi = PrivateMarketAnalytics.tvpi(150, 90, 105);      // (dist + nav) / contrib
double dpi  = PrivateMarketAnalytics.dpi(150, 90, 105);       // realized cash only

// KS-PME: a fund that IS the index scores exactly 1 -- the fair benchmark.
double pme = PrivateMarketAnalytics.ksPme(
        new double[]{100, 0, 0}, new double[]{0, 0, 0}, 121,
        new double[]{100, 110, 121});                          // 1.0 exactly

// Appraisal NAVs launder volatility -- desmooth before comparing risk.
double[] trueReturns = PrivateMarketAnalytics.geltnerDesmooth(navReturns, 0.4);
System.out.printf("IRR %.1f%%  TVPI %.2f  DPI %.2f  PME %.2f%n",
        irr * 100, tvpi, dpi, pme);
```

DPI is the honest multiple -- you cannot spend RVPI, and TVPI includes an
appraisal NAV the GP marked. PME > 1 is the only fair "beat the market"
statement for irregular flows: "our IRR beat the S&P's return" stops being
evidence once the cash-flow timing is accounted for. And compare the standard
deviation of `trueReturns` against the raw NAV series -- the gap is the
volatility the appraisal process smoothed away, which is why unadjusted
private-market Sharpes look impossibly high next to public ones.

## 252. Build a cross-asset momentum book

Momentum is not an equity phenomenon -- it shows up in bonds, commodities and
FX, and combining trends that are weakly correlated across asset classes is
the cheapest diversification a trend book can buy. Run one
`CrossSectionalMomentum` over a panel that mixes asset-class total-return
series.

```java
// One aligned panel: equity index futures, bond futures, commodity and FX
// excess-return series -- all as BarSeries, all index-aligned.
Map<String, BarSeries> crossAsset = /* "ES", "TY", "CL", "EURUSD", ... */;

CrossSectionalMomentum book = new CrossSectionalMomentum(
        null, new CrossSectionalMomentum.Config(126, 5, 4, 0.5));   // 6m lookback, 4/side

PortfolioBacktester.Result r = PortfolioBacktester.run(book, crossAsset,
        PortfolioBacktester.Config.defaults().withRebalanceEvery(21)
                .withCostModel(TradeCostModel.institutional(1, 2, 1, 20)));

System.out.printf("cross-asset momentum: Sharpe %.2f  maxDD via analytics next%n",
        r.metrics().sharpeRatio());
DrawdownAnalytics.Result dd = DrawdownAnalytics.analyze(r.equityCurve());
System.out.printf("time under water %.0f%%%n", dd.timeUnderWater() * 100);
```

The cross-asset panel is where trend earns its keep: an equity sell-off that
hurts the long-equity leg often coincides with a bond rally the long-bond leg
catches, so the book's drawdowns are shallower than any single-asset trend
sleeve. The catch trend books live with is the sharp reversal -- momentum's
left tail is a violent snap-back, which is exactly what `DrawdownAnalytics`
duration and `MonteCarloTradeShuffle` (recipe 221) are for. Excess-return
series keep the funding leg out of the cross-section.

## 253. Put on a credit carry trade

Selling protection earns the spread and pays out on default -- a positive-carry
trade whose risk is a jump, not a drift. Price the carry as the par spread
against the fair (triangle) spread, and know the risky DV01 so you can size the
mark-to-market against spread moves.

```java
YieldCurve discount = YieldCurve.ofZeroRates(new double[]{1, 3, 5, 7, 10},
        new double[]{0.03, 0.03, 0.03, 0.03, 0.03});
CreditCurve name = CreditCurve.bootstrap(
        new int[]{1, 3, 5, 7}, new double[]{0.008, 0.012, 0.015, 0.016}, 0.40, discount);

double par5   = CdsPricer.parSpread(name, discount, 5);        // the spread you earn
double dv01   = CdsPricer.riskyAnnuity(name, discount, 5);     // risky DV01 base
double fair   = name.hazard(5) * (1 - name.recovery());        // the triangle's fair spread

// Post-2009 standard contract runs a fixed 100bp coupon; the difference is
// exchanged upfront (positive = protection buyer pays).
double upfront = CdsPricer.upfront(name, discount, 0.01, 5);
System.out.printf("par %.1fbp  fair %.1fbp  carry %.1fbp  DV01 %.4f  upfront %.4f%n",
        par5 * 1e4, fair * 1e4, (par5 - fair) * 1e4, dv01, upfront);
```

The carry (par minus the triangle's fair spread) is the compensation ABOVE
pure default risk -- liquidity and risk premium -- and it is why selling
protection on a 150bp name that defaults at only ~2.5% a year is a positive
expected-value trade until it is not. The risky annuity IS the risky DV01: P&L
per basis point of spread move, and `upfront == (parSpread - coupon) * annuity`
to machine precision, which is the identity that ties the running-coupon quote
to the exchanged points. The jump risk is what `CvaApproximator` and the tail
tools price -- carry pays you to hold a short-volatility position on default.

## 254. Run an FX carry book off the forward points

FX carry is the oldest trade there is: long high-yield currencies funded by
low-yield ones, earning the interest differential that covered-interest parity
prices into the forward points. `SwapPointsCurve` gives the outright forwards
and the implied carry per pair.

```java
CurrencyPair audjpy = CurrencyPair.of("AUD/JPY");
LocalDate today = LocalDate.of(2026, 7, 13);

SwapPointsCurve curve = SwapPointsCurve.builder(audjpy, today, 98.50)
        .add("1W", 12).add("1M", 55).add("3M", 165).build();   // pips per tenor

double fwd3m   = curve.outright("3M");
double carry3m = curve.impliedCarry(curve.spotDate().plusMonths(3));
System.out.printf("%s spot %.2f  3M fwd %.2f  implied carry %.2f%%%n",
        audjpy.symbol(), curve.spotRate(), fwd3m, carry3m * 100);
```

A high-yielder trades at a forward DISCOUNT (points that pull the forward below
spot), and holding it to the fixing earns that discount back as the rate
differential -- `impliedCarry` is exactly the annualized version of that pull.
The trade's brutal reputation is earned: carry is short crash risk, funding
currencies rally violently in a risk-off event and the differential you
collected for months evaporates in a week. Size an AUD/JPY-style book against
that left tail -- the same `MonteCarloTradeShuffle` and drawdown-duration
tools from recipes 219 and 221 -- because the average carry is not the risk,
the reversal is.

## 255. The go-live checklist and the post-mortem, as code

Before a strategy touches real money it should pass every honesty gate in
this section, in order, and fail loudly on any one of them. After it trades,
the post-mortem walks the same arrows backward -- which stage's assumption
broke.

```java
// GO-LIVE: any single failure is a rejection -- these test different lies.
var wf  = WalkForwardAnalyzer.analyze(series, grid, factory, cfg, 504, 126,
        PerformanceMetrics::sharpeRatio);
double dsr = GridSearchOptimizer.deflatedSharpeOfWinner(ranked, winnerReturns, 252);
var pbo = OverfitProbability.cscvSharpe(variantReturns, 10);
var sh  = BlockBootstrap.sharpeSamples(winnerReturns, 20, 5_000, 252, 1L);

boolean cleared =
        wf.efficiency() > 0.5                                   // travels out of sample
     && dsr > 0.95                                              // survives its own search
     && pbo.pbo() < 0.5                                         // process is not noise-mining
     && MathUtils.percentileSorted(sh, 0.05) > 0;               // CI excludes zero
System.out.println(cleared ? "CLEARED for paper trading" : "REJECTED -- back to research");

// POST-MORTEM: walk the arrows -- gross intact but net gone => costs;
// backtest fine but live drifting => regime or execution.
var live = TradeAnalytics.analyze(liveTrades);
var dd   = DrawdownAnalytics.analyze(liveEquity);
var path = MonteCarloTradeShuffle.analyze(liveTrades, 10_000, 9L);
System.out.printf("live expectancy %.2f  maxDD %.1f%% at pct %.0f  (regime tell if high)%n",
        live.expectancy(), dd.maxDepth() * 100, path.actualDrawdownPct() * 100);
```

The go-live gates are AND-ed on purpose: walk-forward efficiency catches
curve-fitting, deflated Sharpe catches the multiple-testing win, PBO catches a
noise-mining SELECTION process, and the bootstrap CI catches a Sharpe that is
real but indistinguishable from zero -- four different ways to fool yourself,
so one pass is not four passes. The post-mortem reads the same diagram in
reverse: a gross P&L that survived but a net that vanished points at the cost
model (recipe 228); a live drawdown sitting at the 95th percentile of its own
shuffle points at a regime the backtest never saw, loss clustering the
reshuffle assumes away. When a recipe and the javadoc disagree, the javadoc
wins -- and please open an issue.

## 256. Wire research output into the trading lane (alpha -> gate -> gateway)

The whole point of the research lane is a number the fast lane can act on.
A learned alpha earns its place only after its own out-of-sample IC turns
positive; until then `normalizedPrediction` emits 0 and the gate never sees
a signal it should not.

```java
SignalEngine sig = new SignalEngine(16);                 // vol, alpha, imbalance
OnlineAlphaLearner learner = new OnlineAlphaLearner(0.01, 1e-4, 0.01);

HftRiskGate gate = new HftRiskGate(16).maxOrderQuantity(1_000_000)
        .maxOrderNotional(25_000_000).priceCollarPct(0.02);
try (HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {
    gateway.addOrderListener((id, sym, side, qty, px, ts) -> { /* venue */ });
    gateway.start();

    int aapl = 0;
    gate.setReferencePrice(aapl, sig.microprice(aapl));
    learner.trainFrom(sig, aapl, realizedReturn);        // predict-then-train
    double alpha = learner.normalizedPrediction(          // 0 until IC > 0
            sig.queueImbalance(aapl), sig.tradeImbalance(aapl),
            sig.normalizedOfi(aapl), sig.momentumZ(aapl));
    if (alpha > 0.2) {
        long id = gateway.submit(aapl, Side.BUY, 500, sig.microprice(aapl),
                System.nanoTime());                       // <0 = gate reason code
        if (id < 0) System.out.println(HftRiskGate.reasonName((int) -id));
    }
}
```

The gate is the airlock between the two lanes: research can be as clever as
it likes, but nothing reaches the venue without clearing the same
primitive-array checks recipe 57 runs in ~3ns. An unproven learner steering
size is not a possibility here -- it is structurally excluded.

## 257. The overnight learning loop (learn -> checkpoint -> restore)

A desk that relearns its volume profile from zero every morning throws away
a day of evidence. The loop is: models learn intraday, `rollDay` folds today
into the baseline, `Checkpoint` commits it atomically, and next session's
fresh instances read it back before the first tick.

```java
VolumeCurve volume = new VolumeCurve(78, 0.1);
OnlineAlphaLearner learner = new OnlineAlphaLearner(0.01, 1e-4, 0.01);
// ... all day: volume.onVolume(bucket, qty); learner.trainFrom(sig, sym, r); ...

volume.rollDay();                                     // end of day: fold in today
try (Checkpoint.Writer w = Checkpoint.writer(Path.of("state/eod.qflc"))) {
    w.section("volume.AAPL", volume::writeState)
     .section("alpha.AAPL", learner::writeState);
}                                                     // temp file + atomic rename

VolumeCurve volume2 = new VolumeCurve(78, 0.1);       // next morning: same config
OnlineAlphaLearner learner2 = new OnlineAlphaLearner(0.01, 1e-4, 0.01);
Checkpoint.Reader r = Checkpoint.reader(Path.of("state/eod.qflc"));
boolean warm = r.section("volume.AAPL", volume2::readState)
             & r.section("alpha.AAPL", learner2::readState);
System.out.println(warm ? "warm start" : "cold start");
```

Each model persists only its cross-day learned state; intraday counters
reset on read, so you restore at session start, never mid-stream. The
learner carries its own out-of-sample IC evidence across the boundary too --
a weighting that took a week to prove itself does not have to re-earn trust
on Monday. A differently-configured reader (other bucket count) throws
instead of silently misaligning arrays.

## 258. Reconcile the CRB ledger with the gateway

Two numbers must agree at the close: what the economics ledger says the desk
earned, and what actually settled through the execution gateway. When they
diverge, one of the two models has a bug -- and finding it before the desk
head does is the entire job.

```java
CrbPnlLedger ledger = new CrbPnlLedger();             // realized spread economics
PaperTradingGateway gateway = new PaperTradingGateway(10_000_000, 0.0005,
        new PreTradeLimitChecker().maxOrderQuantity(1_000_000));

// Internalized flow books spread on the ledger; hedges route through the gateway.
ledger.onDecision(decision, halfSpreadBps);           // spread captured
ledger.onHedge(hedgeNotional, hedgeCostBps);          // hedging cost
gateway.onQuote("ES", 5000.0, 5000.5);
gateway.submitMarket("ES", Side.SELL, hedgeContracts);// the actual hedge fill

// The close: two independent tallies of the same day.
double ledgerNet = ledger.netEconomics();             // spread - hedge - route
PaperTradingGateway.AccountSnapshot acct = gateway.snapshot();
double gatewayRealized = acct.realizedPnl();
System.out.printf("ledger net %.2f | gateway realized %.2f | drift %.2f%n",
        ledgerNet, gatewayRealized, ledgerNet - gatewayRealized);
```

The ledger and the gateway measure deliberately different things -- the
ledger is realized SPREAD economics with inventory marks excluded on
purpose, the gateway is settled cash P&L -- so they will not be equal, but
their drift must be EXPLAINABLE (it is the unrealized inventory mark, which
`CentralRiskBook.report` values separately). A drift you cannot attribute to
open inventory is a reconciliation break, and `snapshot()` gives you one
internally consistent gateway view under a single lock to compare against.

## 259. A firm-wide breaker drill (GlobalRiskAggregator)

Per-shard gates see only their own symbols; nobody is the someone who can
see the whole firm -- except the aggregator. Drill it the way ops drills a
fire alarm: force a breach, confirm every shard's gate kills, confirm the
hysteresis lets trading resume, and time the detection.

```java
HftRiskGate shardA = new HftRiskGate(64);
HftRiskGate shardB = new HftRiskGate(64);
try (GlobalRiskAggregator firmRisk = new GlobalRiskAggregator(
        List.of(shardA, shardB),   // or engine.gates() from the sharded engine
        100_000_000.0,             // firm cap: sum |position| x reference price
        0.9,                       // resume only below 90M (no flapping)
        1_000_000)) {              // 1ms poll = detection latency

    shardA.setReferencePrice(0, 1.0850);
    shardA.onFill(0, Side.BUY, 120_000_000);   // 130.2M gross: over the cap
    Thread.sleep(10);                          // > one poll interval
    assert firmRisk.isTripped();               // every gate now kills
    assert shardB.check(0, Side.BUY, 1, 1.0)   // a DIFFERENT shard also frozen
            == HftRiskGate.REJECT_KILLED;

    shardA.onFill(0, Side.SELL, 60_000_000);   // de-risk to 65.1M
    Thread.sleep(10);
    assert !firmRisk.isTripped();              // below resume: trading back
    System.out.printf("trips=%d last gross=%.0f%n",
            firmRisk.tripCount(), firmRisk.lastGrossNotional());
}
```

The drill proves the layering that recipe 63 describes: nanosecond per-order
checks stay local, the firm-wide cap is a poll-interval circuit breaker, and
a breach on ONE shard freezes ALL of them. Run this in CI, not just in the
war room -- an untested kill switch is decoration. `tripCount` and
`lastGrossNotional` are what your monitoring alerts on.

## 260. Capacity-plan from LatencyRecorder data

A latency histogram is not just a scorecard; it is a capacity plan. Service
time at a high percentile bounds sustainable throughput, and comparing p50
to p99.9 tells you whether adding load will fall off a cliff or degrade
gracefully.

```java
LatencyRecorder tickToOrder = new LatencyRecorder();
for (int i = 0; i < 5_000_000; i++) {
    long t0 = System.nanoTime();
    // ... the operation under test: gate check + gateway submit ...
    tickToOrder.record(System.nanoTime() - t0);
}

long p50 = tickToOrder.percentile(0.50);
long p999 = tickToOrder.percentile(0.999);
double serviceSeconds = p999 / 1e9;                       // plan to the tail
double maxSustainable = 1.0 / serviceSeconds;             // single-thread ceiling
System.out.printf("p50=%dns p99.9=%dns -> ceiling ~%.0f ops/s/thread%n",
        p50, p999, maxSustainable);
double tailBlowup = (double) p999 / p50;                  // >20 = fragile
System.out.println(tailBlowup > 20
        ? "tail-dominated: shard before you add load (recipe 277)"
        : "graceful: headroom to push the rate");
```

Sizing to the mean is how systems fall over at month-end volume: queues form
at the tail, not the average. If the p99.9-to-p50 ratio is large the path is
tail-dominated and the honest fix is horizontal (another shard), not a
faster loop. Pair the number with recipe 270's hiccup profile first -- if the
tail is platform noise, no amount of capacity planning removes it.

## 261. A full market-risk day (VaR -> ES -> stress -> FRTB)

The risk manager's afternoon on a netted factor book, end to end: the four
headline VaR flavors, the tail beyond the sample, the named and reverse
stress, and the Basel wrap. Every formula here is pinned by a hand-computed
test.

```java
double[] exposures = book.netExposures();         // e.g. from CentralRiskBook
double[][] cov = /* factor covariance, registry order */;

double var99 = VarEngine.deltaNormalVar(exposures, cov, 0.99);
double es99  = VarEngine.deltaNormalEs(exposures, cov, 0.99);   // post-FRTB, ES leads
double dgVar = VarEngine.deltaGammaVar(exposures, gamma, cov, 0.99); // options books
VarEngine.VarResult hist = VarEngine.historicalVar(exposures, factorRows, 0.99);

double covid = StressTester.scenarioPnl(templateExposures,
        StressTester.covidMarch2020());           // what would March 2020 do today
var reverse = StressTester.reverseStress(exposures, cov, 2_000_000);
// reverse.mahalanobisSigmas() < 3: the book breaks on a Tuesday -- fix TODAY.

var tail = ExtremeValueTheory.fitPot(dailyLosses, 0.95);
double var999 = tail.var(0.999);                  // beyond the sample, honestly

double frtbEs = FrtbEs.liquidityHorizonEs(esByHorizon, horizons); // LH cascade
double imcc = FrtbEs.stressCalibratedEs(esCurFull, esStressRed, esCurRed);
FrtbEs.TrafficLight zone = FrtbEs.TrafficLight.of(exceptionsLast250Days);
```

The methods disagree by design and the disagreement is the report: a
short-gamma book's delta-gamma VaR is WORSE than delta-normal admits,
historical VaR is exactly as fat-tailed as the window was, and the EVT tail
refuses to report an ES when the fitted tail has no finite mean rather than
printing a comfortable lie. The reverse-stress Mahalanobis distance is the
one number a board understands: how implausible is the move that breaks us.

## 262. A settlement-risk Herstatt check

Cross-currency settlement kills you in the window between paying one leg and
receiving the other -- the risk named for Bankhaus Herstatt, closed in 1974
after taking in DEM but before paying out USD. Screen the day's instructions
for that window and size the peak intraday exposure per counterparty.

```java
var legs = List.of(
    new SettlementRiskAnalyzer.SettlementLeg("BankX",
        "USD", 10_000_000, 9_00_000_000L,    // we pay USD at 09:00
        "EUR",  9_200_000, 15_00_000_000L),  // we receive EUR at 15:00 -> exposed
    new SettlementRiskAnalyzer.SettlementLeg("BankX",
        "EUR",  5_000_000, 10_00_000_000L,
        "GBP",  4_300_000, 10_00_000_000L)); // simultaneous: no Herstatt window

Map<String, Double> atRisk = SettlementRiskAnalyzer.herstattExposure(legs);
double peak = SettlementRiskAnalyzer.peakExposure(legs, "BankX"); // max outstanding
System.out.printf("BankX at-risk receive %.0f, peak intraday %.0f%n",
        atRisk.get("BankX"), peak);
```

`hasHerstattWindow` is simply pay-before-receive, and the peak calculation
applies payments before receipts at equal timestamps on purpose: for a
worst-case number a simultaneous pay/receive is read as your money leaving
first. This feeds directly into the counterparty limit of recipe 263 --
settlement exposure is a component of the total credit line, not a separate
universe.

## 263. Counterparty exposure with netting

Gross add-ons over-state exposure for exactly the books that hedge -- which
is what the netting agreement is FOR. The CEM net-to-gross adjustment gives
a well-hedged netting set up to 60% relief, and current-plus-potential is
the number the credit line is drawn against.

```java
var tracker = new CounterpartyExposureTracker()
    .addTrade(new CounterpartyExposureTracker.CounterpartyTrade(
        "HedgeFundA", "FX-FWD", 50_000_000,  +1_200_000, 0.5))  // in the money
    .addTrade(new CounterpartyExposureTracker.CounterpartyTrade(
        "HedgeFundA", "IRS",   80_000_000,   -800_000, 4.0))   // offsetting MTM
    .addTrade(new CounterpartyExposureTracker.CounterpartyTrade(
        "HedgeFundA", "XCCY",  30_000_000,   +300_000, 7.0));  // long tenor

double ce  = tracker.currentExposure("HedgeFundA");    // max(0, net MTM)
double pfe = tracker.potentialFutureExposure("HedgeFundA"); // NGR-adjusted add-ons
double total = tracker.totalExposure("HedgeFundA");
System.out.printf("CE %.0f + PFE %.0f = %.0f%n", ce, pfe, total);
tracker.allExposures().forEach((cp, ex) ->
        System.out.printf("  %s: %.0f%n", cp, ex));
```

Current exposure nets the marks and floors at zero; PFE scales the summed
notional add-ons by `(0.4 + 0.6*NGR)` where NGR is net current exposure over
gross positive MTM -- so the IRS whose mark offsets the forward earns the
book a discount the gross number cannot see. NGR falls back to 1 (no relief)
when there is no positive MTM to net against, which is the conservative
default.

## 264. Concentration risk on a book

Diversification is a claim you can measure. The Herfindahl index and its
reciprocal (effective number of positions) turn "how concentrated are we"
into two numbers, and the single-name limit turns policy into a list of
breaches.

```java
double[] exposures = { 40_000_000, 25_000_000, 15_000_000, 12_000_000, 8_000_000 };
double hhi = ConcentrationRisk.herfindahlIndex(exposures);   // 1/N .. 1
double effN = ConcentrationRisk.effectivePositions(exposures);// 1/HHI
double top3 = ConcentrationRisk.topNShare(exposures, 3);      // |share| of top 3

Map<String, Double> bySector = new LinkedHashMap<>();
bySector.put("Tech", 55_000_000.0);
bySector.put("Energy", 30_000_000.0);
bySector.put("Financials", 15_000_000.0);
Map<String, Double> shares = ConcentrationRisk.shares(bySector);
List<String> breaches = ConcentrationRisk.limitBreaches(bySector, 0.40); // >40%
System.out.printf("HHI %.3f, effective positions %.1f, top-3 %.1f%%%n",
        hhi, effN, top3 * 100);
System.out.println("over-limit sectors: " + breaches);   // [Tech]
```

Five equal positions have HHI 0.2 and effective-N 5; the same book with one
name at 40% has effective-N well below 5 even though the count is unchanged
-- that gap is the concentration the position count hides. The same functions
run over any grouping (name, sector, currency, counterparty), so the credit
line of recipe 263 and this book-level view share one arithmetic.

## 265. A best-execution report for compliance

MiFID II RTS 27/28 asks a factual question: did you get your clients a good
fill? Slippage versus arrival mid, fill rate, latency-to-fill, and the
per-venue breakdown are the evidence, assembled from order outcomes.

```java
var analyzer = new BestExecutionAnalyzer()
    .add(new BestExecutionAnalyzer.OrderOutcome("O1", "VENUE_A", Side.BUY,
            10_000, 150.00, 150.01, 250_000, true))   // 0.7bps slip, filled
    .add(new BestExecutionAnalyzer.OrderOutcome("O2", "VENUE_B", Side.BUY,
            5_000, 150.00, 149.99, 400_000, true))    // price improvement
    .add(new BestExecutionAnalyzer.OrderOutcome("O3", "VENUE_A", Side.SELL,
            8_000, 150.00, 0, 0, false));             // unfilled: px/latency ignored

BestExecutionAnalyzer.BestExecutionReport rpt = analyzer.report();
System.out.printf("orders %d, fill rate %.0f%%, avg slip %.2fbps%n",
        rpt.totalOrders(), rpt.fillRate() * 100, rpt.avgSlippageBps());
System.out.printf("median latency %.1fms, at-or-better than arrival %.0f%%%n",
        rpt.medianLatencyToFillMillis(), rpt.atOrBetterThanArrivalPct() * 100);
rpt.avgSlippageBpsByVenue().forEach((v, bps) ->
        System.out.printf("  %s: %.2fbps%n", v, bps));
```

Slippage is signed so that positive is always cost to you -- a buy filled
above arrival and a sell filled below both read as a debit -- which is why
the at-or-better fraction counts `slip <= 0`. Unfilled orders count against
the fill rate but carry no price, so a venue cannot flatter its slippage by
simply not filling. The per-venue table is the input to the venue selection
that recipe 283 hardens against gaming.

## 266. Banging-the-close surveillance

The WM/Reuters 4pm fix is set from the median mid inside a window, which
makes it a target: trade heavily one way into the window, push the print,
let it revert after. `FixAnalyzer` computes the fix the way the methodology
does and flags the classic signature.

```java
double[] window = { 1.0850, 1.0853, 1.0856, 1.0858, 1.0860 }; // mids in the window
FixAnalyzer.FixImpactReport rpt = FixAnalyzer.analyze(
        window,
        1.0848,        // pre-window mid
        1.0851,        // post-window mid (reverts down)
        40_000_000,    // participant buy qty in the window
        2_000_000,     // participant sell qty
        90_000_000,    // total market volume in the window
        0.25);         // participation share that triggers scrutiny

System.out.printf("fix %.4f, run-up %.1fbps, reversion %.1fbps%n",
        rpt.fixRate(), rpt.runUpBps(), rpt.reversionBps());
System.out.printf("share %.0f%%, net flow %d, FLAGGED=%b%n",
        rpt.participationShare() * 100, rpt.netFlow(), rpt.flagged());
```

The flag needs all three at once: a dominant share, a run-up ALIGNED with
the participant's net flow, and reversion AGAINST it afterwards. Impact that
decays is the footprint of pressure, not information -- a genuine informed
buyer's push does not hand the level back. One leg alone (big share, or a
run-up, or reversion) is not a case; the conjunction is what separates
manipulation from a large honest order.

## 267. Market-quality metrics

Execution-quality and venue-quality reports run on a small vocabulary of
spreads. `MarketQualityMetrics` is that vocabulary, signed so positive is
always cost to the liquidity taker, and it makes the adverse-selection
decomposition explicit: effective spread is what you paid, realized spread
is what the maker kept, and the difference is price impact.

```java
double quoted = MarketQualityMetrics.quotedSpreadBps(149.98, 150.02);   // width
double effective = MarketQualityMetrics.effectiveSpreadBps(
        Side.BUY, 150.01, 150.00);                    // what the taker paid
double realized = MarketQualityMetrics.realizedSpreadBps(
        Side.BUY, 150.01, 150.03);                    // vs mid 5min later
double impact = MarketQualityMetrics.priceImpactBps(
        Side.BUY, 150.00, 150.03);                    // how far mid moved after
double otr = MarketQualityMetrics.orderToTradeRatio(4_200, 300); // messages/fill
System.out.printf("quoted %.1f | effective %.1f = realized %.1f + impact %.1f%n",
        quoted, effective, realized, impact);
System.out.printf("order-to-trade %.1f%n", otr);
```

The identity `effective ~= realized + impact` is the whole story of who won
the trade: a large impact and a small realized spread means the taker was
informed and the maker got adversely selected; the reverse means the maker
earned the spread cleanly. The order-to-trade ratio is the exchange's
quote-stuffing tripwire and returns +Infinity on zero trades rather than
dividing by zero.

## 268. Prove zero allocation on the hot path

"Zero allocation" is a claim, and the JVM's per-thread allocation counter is
how you prove it instead of asserting it. Sample `getThreadAllocatedBytes`
around a steady-state loop; if the hot path allocates, the counter moves.

```java
var mx = (com.sun.management.ThreadMXBean)
        ManagementFactory.getThreadMXBean();
Assumptions.assumeTrue(mx.isThreadAllocatedMemorySupported());
mx.setThreadAllocatedMemoryEnabled(true);

HftRiskGate gate = new HftRiskGate(4).maxOrderQuantity(1_000_000).priceCollarPct(0.10);
try (HftOrderGateway gw = new HftOrderGateway(1 << 16, gate, true)) {
    gw.addOrderListener((id, s, side, q, p, t) -> { });
    gw.start();
    for (int i = 0; i < 50_000; i++) gw.submit(0, Side.BUY, 100, 100, i); // warm up JIT

    long tid = Thread.currentThread().threadId();
    long before = mx.getThreadAllocatedBytes(tid);
    for (int i = 0; i < 500_000; i++) {
        while (gw.submit(0, Side.BUY, 100, 100, i) <= 0) Thread.onSpinWait();
    }
    long allocated = mx.getThreadAllocatedBytes(tid) - before;
    assert allocated < 100_000 : "hot path allocated " + allocated + " bytes";
}
```

The warm-up loop matters: the first thousand submits allocate as the JIT
compiles and profiles, so measure only steady state. The threshold is a
small noise budget, not zero, because the counter itself and background
sampling contribute a few bytes -- but 500k submits inside 100KB means no
PER-ORDER allocation, which is the claim. This exact test guards
`HftOrderGateway`, `HftRiskGate`, the ring buffers and the SBE flyweights in
CI; a regression that starts allocating fails the build, not a benchmark.

## 269. Run under Epsilon GC

The strongest zero-allocation proof is a run that would DIE if the claim were
false. Epsilon is the no-op collector: it never reclaims, so any steady-state
allocation eventually exhausts the heap and the process aborts instead of
quietly pausing.

```bash
# Size the heap for startup/setup allocation only, then forbid collection.
java -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms8g -Xmx8g \
     -XX:+AlwaysPreTouch \
     -cp target/classes com.quantfinlib.examples.HftOrderBenchmark
```

```java
// The benchmark body: millions of hot-path operations, no allocation allowed.
try (HftOrderGateway gw = new HftOrderGateway(1 << 16, gate, true)) {
    gw.start();
    for (long i = 0; i < 50_000_000L; i++) {          // would OOM under Epsilon
        while (gw.submit(0, Side.BUY, 100, 100.0, i) <= 0) Thread.onSpinWait();
    }
}                                                     // survives = claim holds
```

Under Epsilon, GC pauses are zero BY CONSTRUCTION -- there is no collector to
pause -- so a clean 50M-order run is proof the tail is not hiding a
collection. `AlwaysPreTouch` faults every page in at startup so the first hot
tick does not pay for a page fault. This is the run described in
`docs/ULTRA_LOW_LATENCY.md`: the sized-up heap is the allowance for
setup-only allocation, and an unexpected allocation storm kills the process
instead of stalling it -- for a trading system, the honest failure mode.

## 270. Measure tail latency with coordinated-omission awareness

A latency benchmark that measures only the operation misses the pauses that
happen BETWEEN operations -- GC, safepoints, scheduler preemption -- and so
publishes a p99.9 it cannot defend. Run `HiccupMonitor` alongside and
attribute the tail honestly.

```java
LatencyRecorder op = new LatencyRecorder();
try (HiccupMonitor hiccups = new HiccupMonitor().start()) {   // 1ms parks, daemon
    for (int i = 0; i < 10_000_000; i++) {
        long t0 = System.nanoTime();
        // ... the operation under test ...
        op.record(System.nanoTime() - t0);
    }
    System.out.println("operation: " + op.summary());
    System.out.println(hiccups.summary());

    if (hiccups.maxHiccupNanos() > op.percentile(0.999)) {
        System.out.println("p99.9 is PLATFORM noise -- tune the JVM, not the code");
    } else {
        System.out.println("p99.9 is real service time -- optimize the path");
    }
}
```

The monitor is the honesty half: a daemon thread parks for a fixed interval
and records how much LONGER than requested each park took, which is exactly
the coordinated-omission blind spot -- a stall that freezes your measuring
loop and the system together. If the worst platform hiccup exceeds your worst
sample, the tail belongs to the platform and no code change removes it.
Publishing a p99.9 without a matching hiccup profile is publishing a number
you cannot stand behind.

## 271. Drive everything from the CLI

The fastest path from a data file to a tear sheet is no Java at all.
`cli.Main` runs backtests, walk-forward validation and market reports on CSV
bars, and its `run(String[])` entry returns an exit code instead of calling
`System.exit` -- so a nightly job can assert on it.

```bash
java -jar quant-finance-library-*.jar backtest --csv bars.csv --symbol EURUSD \
     --strategy bollinger --period 20 --k 2.0 --capital 250000 --out bt.html

java -jar quant-finance-library-*.jar walkforward --csv bars.csv --symbol EURUSD \
     --train 252 --test 63 --fast 5,10,20 --slow 40,60 --out wf.html
```

```java
// The testable entry: 0 = ok, 1 = usage error, 2 = execution failure.
int code = Main.run(new String[]{
        "backtest", "--csv", "bars.csv", "--symbol", "EURUSD",
        "--strategy", "sma", "--fast", "10", "--slow", "30", "--out", "bt.html"});
if (code != 0) throw new IllegalStateException("nightly backtest failed: " + code);
```

The walk-forward command prints each fold's chosen parameters with in-sample
versus out-of-sample objective and the stitched walk-forward efficiency --
the anti-overfitting number of recipe 20, computed without a line of Java. The
three-way exit code is the CI contract: a job asserts `run(...) == 0`,
archives the HTML artifact, and distinguishes a bad command line (1) from a
genuine execution failure (2) so an alert points at the right person.

## 272. Watch it live in a browser

`TradingDashboard` is the observable end of the paper-trading loop: a
zero-dependency JDK http server serving a self-refreshing page plus a JSON
endpoint, reading every number from one consistent account snapshot.

```java
PaperTradingGateway paper = new PaperTradingGateway(1_000_000);
LatencyRecorder tickToOrder = new LatencyRecorder();

try (TradingDashboard dashboard = new TradingDashboard(paper, 8080)) {
    dashboard.attachLatency("tick-to-order", tickToOrder);
    dashboard.start();
    System.out.println("open http://localhost:" + dashboard.port());

    paper.onQuote("BTCUSDT", 64_990, 65_010);
    long t0 = System.nanoTime();
    paper.submitMarket("BTCUSDT", Side.BUY, 2);
    tickToOrder.record(System.nanoTime() - t0);
    // browser self-refreshes: equity, cash, realized P&L, positions,
    // rejections, and count/p50/p99/max per attached latency path.
}
```

Pass port 0 to bind an ephemeral port and read it back with `port()`. Every
figure on the page comes from `PaperTradingGateway.snapshot()`, taken under a
single lock, so the dashboard thread never renders a half-updated account
while another thread trades. Machine consumers poll `GET /api/status` for the
identical JSON -- the same payload drives the human page and the monitoring
scrape.

## 273. A paper-to-prod cutover

The research-to-production loop closes only if the strategy code does not
change when the venue does. `PaperTradingGateway` implements `OrderGateway`
and runs the same `PreTradeLimitChecker` as production, so cutover is a
one-line swap of the gateway, not a rewrite.

```java
PreTradeLimitChecker limits = new PreTradeLimitChecker()
        .maxOrderQuantity(10_000).maxPositionQuantity(20_000).priceCollarPct(0.03);

// The strategy takes the interface, never the concrete class.
void runStrategy(OrderGateway gateway) {
    gateway.submitLimit("AAPL", Side.BUY, 1_000, 149.95);   // identical either side
}

OrderGateway venue = paperMode
        ? new PaperTradingGateway(1_000_000, 0.0005, limits)  // simulated fills
        : realBrokerAdapter;                                   // same interface
runStrategy(venue);
```

The invariant that makes cutover safe is that BOTH gateways enforce the same
pre-trade limits: a fat-finger the paper venue rejects, production rejects
too, so the risk behaviour you tested is the risk behaviour you ship. Because
the strategy is closed over the `OrderGateway` interface, the diff between
paper and prod is exactly one construction site -- everything the strategy
does, every limit it clears, is unchanged. Test the cutover by running the
same strategy against both and diffing the order streams.

## 274. A strategy kill decision

Retiring a live strategy is a decision under two kinds of evidence: is the
edge still statistically there (was it ever, once you account for the trials
that produced it), and is it currently bleeding into a risk limit. Combine
the deflated-Sharpe verdict with the live drawdown, not either alone.

```java
// 1. Was the backtest Sharpe real, or the best of many tries?
double deflated = SharpeValidation.deflatedSharpe(
        observedSharpe, trialSharpes, nObs, skew, kurtosis);   // haircut for selection
double psr = SharpeValidation.probabilisticSharpe(
        observedSharpe, 0.0, nObs, skew, kurtosis);            // P(true Sharpe > 0)

// 2. Is the LIVE record now breaking down?
OverfitProbability.Result pbo = OverfitProbability.cscvSharpe(foldReturns, 10);

boolean statisticallyDead = deflated < 0 || psr < 0.95 || pbo.pbo() > 0.5;
boolean riskBreach = liveDrawdown > maxDrawdownLimit;
if (statisticallyDead || riskBreach) {
    gate.halt(symbolId, true);       // stop new orders; flatten via AutoHedger
    System.out.printf("KILL: deflated=%.2f psr=%.2f pbo=%.2f dd=%.1f%%%n",
            deflated, psr, pbo.pbo(), liveDrawdown * 100);
}
```

The two signals answer different questions and you need both: the
deflated Sharpe and PBO ask whether the edge was ever more than the luckiest
of N backtests, while the drawdown asks whether today's book is the emergency.
A strategy can be statistically fine and still hit a stop (kill it now, argue
later), or statistically dead while flat (retire it calmly). The kill routes
through the same `HftRiskGate.halt` an ops lever uses -- one path to stop, not
two.

## 275. A data-fix ripple

A late split or dividend correction is not a one-file edit; it ripples
through every derived artifact. Back-adjust the series, and the backtest, the
indicator panel, and the universe screen all move -- so the fix is done only
when you have re-run the chain and confirmed the change is the expected one.

```java
BarSeries raw = CsvBarLoader.load(Path.of("AAPL.csv"), "AAPL");
List<CorporateActions.CorporateAction> fixes = List.of(
    new CorporateActions.CorporateAction(exTs, CorporateActions.Type.SPLIT, 4.0),
    new CorporateActions.CorporateAction(divTs, CorporateActions.Type.CASH_DIVIDEND, 0.24));
BarSeries adjusted = CorporateActions.adjust(raw, fixes);   // input untouched

// The ripple: everything downstream must be recomputed from the adjusted series.
BacktestResult before = Backtester.run(strategy, raw, BacktestConfig.defaults());
BacktestResult after  = Backtester.run(strategy, adjusted, BacktestConfig.defaults());
System.out.printf("Sharpe %.2f -> %.2f (a 4:1 split unadjusted = phantom -75%% gap)%n",
        before.metrics().sharpeRatio(), after.metrics().sharpeRatio());
```

`adjust` returns a NEW series and leaves the input untouched, which is what
lets you diff before against after and SEE the correction rather than trust
it. An unadjusted 4-for-1 split shows up as a fake -75% overnight return that
a momentum strategy trades on and a screener flags as a crash -- the ripple is
the point, and re-running the chain is how you confirm the fix landed
everywhere and nowhere else.

## 276. A checkpoint version migration

Model state formats evolve. The checkpoint contract makes migration safe by
refusing silence: a per-model version byte throws on mismatch, unknown
sections are skipped, and a partially-consumed section is rejected as format
drift. Migrate deliberately, one version bump at a time.

```java
// Old build wrote version 1; new build reads it and re-emits version 2.
public void readState(DataInput in) throws IOException {
    int v = in.readByte();
    if (v == 1) {
        legacyReadV1(in);              // parse the old layout in full
    } else if (v == 2) {
        readV2(in);
    } else {
        throw new IOException("state version " + v + " not supported");
    }
}

// A one-shot migration job: load with the new reader, re-checkpoint at v2.
Checkpoint.Reader r = Checkpoint.reader(Path.of("state/eod.qflc"));
r.section("volume.AAPL", model::readState);            // reads v1
try (Checkpoint.Writer w = Checkpoint.writer(Path.of("state/eod.qflc"))) {
    w.section("volume.AAPL", model::writeState);       // writes v2, atomically
}
```

`Checkpoint.requireVersion` is the shared first line of every `readState` and
it THROWS on an unexpected version rather than reading garbage into aligned
arrays -- a loud failure beats a silently corrupted volume profile steering
tomorrow's schedule. Because the file format skips unknown sections, a new
build can add a model without breaking an old reader, and the atomic rename
means a crash mid-migration leaves the v1 file intact to retry.

## 277. A shard rebalance

When one symbol's flow outgrows its shard, you move it -- but the sharded
engine is shared-nothing, so a rebalance is a re-registration, not a live
migration. Register the hot symbol on a second shard, drain, and let the
producer route by the new handle.

```java
try (ShardedTradingEngine engine = new ShardedTradingEngine(
        4, 1 << 14, 1 << 13, 64, true,
        shard -> new HftRiskGate(64).maxOrderQuantity(5_000_000))) {

    int eurusd = engine.registerSymbol("EURUSD", 0);      // starts on shard 0
    int eurjpy = engine.registerSymbol("EURJPY", 0, 1);   // cross: BOTH shards tick
    engine.start();

    // Shard 0 saturating? A leader/follower cross can live on shard 1 too:
    // one extra ring publish (~40ns) duplicates the leg's feed, which is the
    // whole point of shared-nothing co-location.
    for (int i = 0; i < 4_000_000; i++) {
        int handle = (i & 1) == 0 ? eurusd : eurjpy;
        while (!engine.publish(handle, 1.0850, 1_000_000, System.nanoTime()))
            Thread.onSpinWait();
    }
    System.out.printf("%d shards, processed %d%n",
            engine.shardCount(), engine.processedCount());
}
```

Rebalancing capacity is horizontal by construction: aggregate throughput is
per-shard rate times shard count, and multi-shard registration is the
co-location tool -- duplicating a leg into a second shard costs one ring
publish. What sharding deliberately does NOT rebalance is firm-wide risk:
each shard's gate sees only its own symbols, so a rebalance always pairs with
the `GlobalRiskAggregator` of recipe 259 watching `engine.gates()`.

## 278. The two-lane wiring for a new symbol

Onboarding a symbol touches both lanes at once: the fast lane needs a dense
id, a risk collar, and a reference price; the research lane needs a model
slot. Wire them together so the first tick is both tradeable and measured.

```java
HftRiskGate gate = new HftRiskGate(64);
SignalEngine sig = new SignalEngine(64);
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 64, true);
     HftOrderGateway gateway = new HftOrderGateway(1 << 13, gate, true)) {

    int nvda = bus.registerSymbol("NVDA");         // one dense id, resolved once
    gate.maxOrderQuantity(5_000).maxPositionQuantity(20_000).priceCollarPct(0.05);
    gate.setReferencePrice(nvda, 120.00);          // collar needs a reference

    bus.subscribe(nvda, (id, px, size, ts) -> {    // consumer thread, primitives
        // research lane observes; fast lane can act through the SAME gate
        gate.setReferencePrice(id, px);            // keep the collar current
    });
    gateway.addOrderListener((id, s, side, q, p, t) -> { /* venue */ });
    gateway.start();
    bus.start();
    bus.publish(nvda, 120.00, 1_000, System.nanoTime());  // first tradeable tick
}
```

The dense id is the shared key across both lanes -- the bus, the gate, and
the signal engine all index by the same `nvda` int, so there is no map lookup
on the hot path and no name-string comparison per tick. The one ordering
constraint is the reference price: the collar rejects everything until the
gate has one, so `setReferencePrice` comes before the first `submit`, and the
market-data listener keeps it fresh as the mid moves.

## 279. A corporate-action ripple through data + backtest + universe

Recipe 275 fixed one series; a real corporate action ripples through the
point-in-time universe too. A ticker change or a constituent event moves both
the price history AND the membership set, and a survivorship-honest backtest
must see both as of each date.

```java
// 1. Adjust the price history for the action.
BarSeries adjusted = CorporateActions.adjust(raw, List.of(
    new CorporateActions.CorporateAction(exTs, CorporateActions.Type.SPLIT, 2.0)));

// 2. Screen the universe as of a POINT IN TIME, not today's members.
List<StockSnapshot> universe = /* symbol + adjusted BarSeries + Fundamentals */;
List<StockSnapshot> membersAsOf = StockScreener.membersAsOf(
        universe, pointInTimeUniverse, asOfTimestamp);   // no lookahead members

// 3. Backtest only what was investable then, on adjusted prices.
var ranked = new StockScreener(membersAsOf).screenAndRank(
        new RankingEngine().addCriterion("mom", 1.0, s -> momentum(s)),
        FundamentalFilters.marketCapAbove(1e9));
```

The two corrections defend against two different lies: price adjustment stops
a split reading as a crash (recipe 275), and point-in-time membership stops
you from backtesting today's winners on yesterday's dates. Skip either and
the backtest looks better than reality -- the split inflates a momentum
signal, and the survivorship gives you a universe that could not have been
traded. Honesty here is the difference between a strategy and a hindsight
narrative.

## 280. A covariance feeding risk + optimization + hedging

One covariance estimate should feed everything that consumes correlation --
the VaR number, the portfolio weights, and the hedge ratio -- so they cannot
silently disagree about the world. Estimate it once with EWMA and hand the
same matrix to all three.

```java
EwmaCovariance cov = new EwmaCovariance(nAssets, 0.97);
cov.onReturns(intervalReturns);                 // per interval, on your clock

double[][] sigma = new double[nAssets][nAssets]; // materialize once
for (int i = 0; i < nAssets; i++)
    for (int j = 0; j < nAssets; j++)
        sigma[i][j] = cov.covariance(i, j);

// 1. Risk: portfolio VaR from the same matrix.
double var99 = VarEngine.deltaNormalVar(exposures, sigma, 0.99);
// 2. Optimization: max-Sharpe weights from the same matrix.
var alloc = new PortfolioOptimizer(expectedReturns, sigma).maxSharpe(0.02);
// 3. Hedging: min-variance hedge ratio from the same return histories.
double h = MinimumVarianceHedge.hedgeRatio(assetReturns, hedgeReturns);
System.out.printf("VaR %.0f | opt Sharpe %.2f | hedge ratio %.3f%n",
        var99, alloc.sharpe(), h);
```

The reason to share one estimate is consistency, not just tidiness: if the
risk system thinks two assets are correlated and the optimizer thinks they
are not, the book the optimizer builds carries risk the VaR under-reports --
the two models are then arguing, and the desk loses the argument. EWMA's
decay (0.97 here) is the single knob that sets how fast all three react to a
regime change together.

## 281. A cross-asset stress

A named historical stress is only meaningful if every desk's exposure is
expressed in the SAME factor space -- otherwise you are shocking equities and
rates in incompatible units. Run the stress on the central risk book's netted
factor vector, where an FX-option delta and a cash-equity position already
share a language.

```java
// The netted book already speaks one factor language (recipe 292).
double[] exposures = book.netExposures();
double[][] cov = /* factor covariance in registry order */;

// Named single-day shock, applied through delta-gamma P&L across all factors.
double covid = StressTester.scenarioPnl(templateExposures, gamma,
        StressTester.covidMarch2020());       // [equity, rates, FX, commodity, vol]
double lehman = StressTester.scenarioPnl(templateExposures,
        StressTester.lehman2008());

// A sensitivity ladder on the equity factor, curvature included.
double[] ladder = StressTester.sensitivityLadder(exposures, gamma, 0, 0.20, 8);

// Reverse: what cross-asset move loses 5M, and how implausible is it?
var reverse = StressTester.reverseStress(exposures, cov, 5_000_000);
System.out.printf("COVID %.0f, Lehman %.0f, break-move %.1f sigma%n",
        covid, lehman, reverse.mahalanobisSigmas());
```

The templates are STARTING POINTS with a documented factor ordering
`[equity, rates, FX-USD, commodity, vol]`, not certified replays -- edit the
magnitudes to your book's real factor set. The delta-gamma overload is what
makes a cross-asset stress honest for an options book: the down rungs of the
equity ladder are worse than the linear ladder admits, and a stress that
ignored curvature would under-report exactly the scenario you ran it for.

## 282. A signal-to-fill lineage trace

When a fill surprises you, the question is which link in the chain produced
it. Stamp a correlation id through the pipeline -- signal, decision, gate,
fill -- so a post-trace can replay the exact inputs that led to the order,
not a reconstruction.

```java
long corrId = System.nanoTime();                       // the lineage key
double alpha = learner.normalizedPrediction(qi, ti, ofi, mz);
tape.record(corrId, "alpha", alpha);                   // your audit sink

BenchmarkExecutor.MarketState m = new BenchmarkExecutor.MarketState(
        sig.microprice(sym), spread.forecast(bucket, now),
        volCurve.regime(bucket, sig.volPerSqrtSecond(sym)), depth,
        vol.expectedFractionElapsed(bucket, fracIn), alpha,
        kyle.impactBps(clip, sig.microprice(sym)));
long child = exec.dueQuantity(scheduleFraction, m);
tape.record(corrId, "due", child);

long id = gateway.submit(sym, Side.BUY, child, m.mid(), corrId); // reuse as ts
tape.record(corrId, id < 0 ? "reject:" + HftRiskGate.reasonName((int) -id)
                           : "accepted:" + id, id);
```

The lineage trace is what turns "why did we buy there" into an answer: every
stage records under one key, so replaying `corrId` shows the alpha that fired,
the `MarketState` it fed, the due quantity the executor computed, and whether
the gate accepted or rejected -- the whole causal chain, deterministic because
the research lane is deterministic. The gateway's timestamp slot is a natural
place to carry the id when your audit tape keys on it; a reject reason is part
of the lineage too, not a dead end.

## 283. A benchmark-that-cant-be-gamed setup

A venue scorecard that a venue can game is worse than none -- it launders bad
routing into a good-looking number. Harden it by scoring on out-of-sample,
selection-adjusted evidence: deflate the Sharpe for the number of venues
tried, and validate the ranking the way you validate an alpha.

```java
// N venues tried is N trials; the best of N looks good by luck alone.
double[] venueSharpes = perVenueRealizedSharpe;         // one per venue
double deflated = SharpeValidation.deflatedSharpe(
        bestVenueSharpe, venueSharpes, nObs, skew, kurtosis);
double expectedBest = SharpeValidation.expectedMaxSharpe(
        venueSharpes.length, MathUtils.variance(venueSharpes));

// The best venue only "wins" if it beats what luck alone would produce.
boolean genuinelyBetter = bestVenueSharpe > expectedBest && deflated > 0;
System.out.printf("best %.2f vs luck %.2f -> %s%n",
        bestVenueSharpe, expectedBest,
        genuinelyBetter ? "route more" : "no real edge; keep it honest");
```

The ungameable move is to make the benchmark pay the multiple-testing tax
that the venue does not want it to pay: `expectedMaxSharpe` is what the best
of N random venues would score, so a venue must clear THAT bar, not zero, to
earn flow. This is the same defense the `alpha` package applies to signals --
walk-forward and permutation before capital -- pointed at execution. A
scorecard that skips it rewards the noisiest venue, which is precisely the one
gaming you.

## 284. A documentation compile-and-run harness

Every code block in these recipes is a liability if it does not compile. The
harness that keeps them honest extracts each block, compiles it against the
real library, and runs the ones that assert -- so a renamed method breaks the
docs build, not a reader's afternoon.

```java
// The harness pattern (JShell or a generated test source per block).
for (CodeBlock block : extractJavaBlocks(Path.of("docs/COOKBOOK.md"))) {
    var result = compileAgainstClasspath(block, libraryJar);   // real signatures
    assertTrue(result.diagnostics().isEmpty(),
            "recipe " + block.recipe() + " will not compile: " + result.diagnostics());
    if (block.isRunnable()) {
        int exit = run(block);                                 // e.g. Main.run(...)
        assertEquals(0, exit, "recipe " + block.recipe() + " failed at runtime");
    }
}
```

The reason this matters more than it looks: documentation drifts silently --
a method gets a parameter, a class moves package, and the prose still reads
plausibly while the code is dead. Compiling against the SHIPPED jar (not the
source tree) catches exactly the drift a reader would hit. Runnable blocks --
the CLI ones especially, via `Main.run` returning an exit code -- go one
further and prove the example produces the claimed artifact. A doc you can
compile is a doc you can trust.

## 285. A formula-pin regression

The strongest test of a pricing or risk formula is a value computed by hand,
independently, and pinned. When a refactor changes the number, the pinned
test fails and forces the question: is the new number right, or is the
refactor wrong?

```java
@Test
void deltaNormalVarMatchesHandComputation() {
    // Two factors, known sigma and correlation: work sigma_P by hand.
    double[] delta = { 1_000_000, -500_000 };
    double[][] cov = {
        { 0.0004,  0.0001 },     // var 0.02^2, cov from rho=0.25
        { 0.0001,  0.0004 } };
    // sigma_P^2 = d'Cov d ; z(0.99)=2.3263 ; VaR = z * sigma_P.
    double expected = 2.3263 * Math.sqrt(
        1e6*1e6*0.0004 + 2*1e6*(-5e5)*0.0001 + 5e5*5e5*0.0004);
    assertEquals(expected, VarEngine.deltaNormalVar(delta, cov, 0.99), 1.0);
}
```

A pinned formula test is a tripwire, not a rederivation: it does not re-prove
the math each run, it asserts the code still produces the number a human
verified once. This is how `MarketRiskPricingTest` caught a real numerical bug
before release -- a Heston pricer that quietly stopped reducing to its
Black-Scholes limit -- because the limit value was pinned and the refactor
moved it. The
delta-gamma ES reducing EXACTLY to delta-normal ES when gamma is zero is
another such pin: an identity the code must honor, checked in one line.

## 286. A fuzz / equivalence book test

Two implementations of the same spec are a test oracle for each other. The
readable `OrderBook` is the executable specification of the fast
`HftOrderBook`; drive both with the same randomized stream and demand
identical books, and any divergence is a bug in the fast one.

```java
Random rnd = new Random(seed);                         // seed = reproducible fuzz
OrderBook readable = new OrderBook("AAPL");
HftOrderBook fast = new HftOrderBook(90_000, 110_000, 1 << 20);

for (int i = 0; i < 1_000_000; i++) {
    Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
    int tick = 95_000 + rnd.nextInt(10_000);
    long qty = 1 + rnd.nextInt(1_000);
    // apply the SAME event to both engines
    readable.submitLimit(side, tick / 1000.0, qty, i);
    fast.submitLimit(side, tick, qty, i);
    // then assert identical top of book every step
    assertEquals(readable.bestBid(), fast.bestBidTick() / 1000.0, 1e-9);
    assertEquals(readable.bestAsk(), fast.bestAskTick() / 1000.0, 1e-9);
}
```

Equivalence testing beats hand-written cases because the fuzz explores states
you would never think to write down -- a cancel that empties a level exactly
as a market order arrives, a post-only that would cross by one tick. The
readable engine is slow but obviously correct, so it can be the judge; the
fast engine is subtle but must agree. A seed that diverges is a reproducible
bug report, and shrinking it to the first differing event is the whole
debugging session.

## 287. A load-soak drop-budget run

Production dies from sustained pressure, not a single spike. A soak run holds
a high publish rate for hours against a bounded ring and measures the DROP
BUDGET -- how many ticks the strategy shed under backpressure -- because
`publish` returning false is a policy decision, not a loss you get to ignore.

```java
long published = 0, dropped = 0;
try (HftMarketDataBus bus = new HftMarketDataBus(1 << 14, 16, true)) {
    bus.subscribe(0, (id, px, size, ts) -> { /* strategy */ });
    bus.start();
    long deadline = System.nanoTime() + Duration.ofHours(2).toNanos();
    while (System.nanoTime() < deadline) {
        if (bus.publish(0, 1.0850, 1_000_000, System.nanoTime())) published++;
        else { dropped++; /* your shed policy: drop, or spin, or alert */ }
    }
    double dropRate = (double) dropped / (published + dropped);
    System.out.printf("published %d, dropped %d (%.4f%%), budget %s%n",
            published, dropped, dropRate * 100,
            dropRate < 1e-4 ? "OK" : "BREACH: consumer too slow");
}
```

`publish` returning false is backpressure, and the caller owns the response:
spin (latency for completeness), drop (completeness for latency), or alert.
The soak proves the choice holds under HOURS of load, where a slow consumer
leak or a GC creep that a 30-second benchmark misses will push the drop rate
past budget. Run it under Epsilon GC (recipe 269) and the soak also proves no
steady-state allocation -- two honesty tests in one long run.

## 288. A CI-aware assertion

A latency or throughput assertion that passes on a quiet workstation and
fails on a loaded CI box is worse than no assertion -- it trains the team to
ignore red. Make performance assertions ADAPTIVE: assert correctness always,
assert absolute latency only when the environment can support it.

```java
boolean ci = System.getenv("CI") != null;              // loaded, shared runner
LatencyRecorder rec = new LatencyRecorder();
// ... run the measured loop, recording into rec ...

// Correctness: always. A wrong answer is wrong on any hardware.
assertEquals(expectedFills, book.tradeCount());
assertTrue(mx.getThreadAllocatedBytes(tid) - before < 100_000); // allocation is env-free

// Absolute latency: only where the platform is quiet enough to mean it.
if (!ci) {
    assertTrue(rec.percentile(0.50) < 300,
            "p50 " + rec.percentile(0.50) + "ns regressed");
} else {
    System.out.println("CI p50=" + rec.percentile(0.50) + "ns (recorded, not gated)");
}
```

The split is deliberate: allocation counts and fill correctness are
properties of the CODE and hold on any hardware, so they gate the build
everywhere; wall-clock percentiles are properties of the PLATFORM and only
mean something on a quiet box. Gating p50 on a noisy shared runner produces
flaky failures that get muted, and a muted assertion protects nothing. Record
the number in CI for trend-watching, enforce it where enforcement is honest.

## 289. The release train

A release is a train, not a teleport: the same sequence runs every time, each
car depends on the one before, and the train does not leave if any car fails.
The library's train is compile, test, doc-harness, benchmark, then tag.

```bash
mvn clean verify                       # compile + unit/equivalence/allocation tests
# doc harness (recipe 284): every COOKBOOK block compiles against the built jar
mvn -Pdocs test
# performance smoke (recipe 288): correctness gated, latency recorded
mvn -Pbench test
# only if all green: the release plugin cuts the tag and the jar
mvn -B release:prepare release:perform
```

```java
// The tag is a claim; the CHANGELOG is the evidence. Assert they agree.
assertEquals(pomVersion(), changelogTopVersion(),
        "CHANGELOG top entry must match the pom version being tagged");
```

The ordering encodes the dependencies: there is no point benchmarking code
that fails its correctness tests, and no point tagging a version whose
documentation will not compile. Each stage is a gate, so a red car stops the
train at the station instead of shipping a broken release and rolling back.
The version/CHANGELOG assertion is the small honesty check that stops the
most common release mistake -- a tag that claims a version the notes do not
describe.

## 290. A mojibake-safe doc edit

The docs are UTF-8 and full of non-ASCII (sigma, arrows, em-dashes). Editing
them through a tool that assumes a different encoding silently corrupts every
such character into mojibake -- and the corruption is invisible in a diff
viewer that also guesses wrong. The safe edit reads and writes UTF-8
explicitly and verifies round-trip.

```java
Path doc = Path.of("docs/COOKBOOK.md");
String text = Files.readString(doc, StandardCharsets.UTF_8);   // explicit charset
String edited = text.replace("## 299.", "## 299.\n\nNew content here.");
Files.writeString(doc, edited, StandardCharsets.UTF_8);        // explicit again

// Verify: no replacement char crept in, byte count moved by the expected delta.
String back = Files.readString(doc, StandardCharsets.UTF_8);
if (back.codePoints().anyMatch(c -> c == 0xFFFD))    // U+FFFD = mojibake
    throw new IllegalStateException("mojibake: a non-UTF-8 read corrupted the doc");
```

The failure mode is specific and nasty: a Windows PowerShell 5.1
`Get-Content`/`Set-Content` round-trip defaults to a different code page and
turns every sigma and arrow into garbage, which is why the safe path is an
explicit-UTF-8 read-modify-write and never a code-page-guessing shell filter.
The replacement-character scan is the tripwire -- `U+FFFD` only appears when
something decoded bytes as the wrong charset, so its presence after an edit is
proof the edit corrupted the file. Verify before you commit, not after a
reader complains.

## 291. A curve + credit + commodity multi-asset pipeline

A cross-asset desk prices off three curves that share one discounting
backbone: the rates curve discounts everything, the credit curve is
bootstrapped through that same discounting, and the commodity curve's carry
is read against the same rate. Build them in dependency order.

```java
// 1. The rates backbone: everything downstream discounts on this.
YieldCurve disc = YieldCurve.bootstrapAnnualParSwaps(
        new int[]{1, 2, 3, 5, 10}, new double[]{0.045, 0.043, 0.042, 0.041, 0.040});

// 2. Credit: hazard rates bootstrapped THROUGH the same discounting.
CreditCurve credit = CreditCurve.bootstrap(
        new int[]{1, 3, 5}, new double[]{0.010, 0.015, 0.020}, 0.40, disc);
double parSpread = CdsPricer.parSpread(credit, disc, 5.0);      // 5y par CDS
double upfront = CdsPricer.upfront(credit, disc, 0.01, 5.0);    // vs 100bp coupon

// 3. Commodity: carry read against the same short rate.
CommodityCurve wti = CommodityCurve.of(78.0,
        new double[]{0.25, 0.5, 1.0}, new double[]{78.5, 79.2, 80.1});
double roll = wti.annualizedRollYield(0.25, 1.0);
System.out.printf("5y CDS par %.4f, upfront %.4f, WTI %s, roll %.2f%%%n",
        parSpread, upfront, wti.isContango() ? "contango" : "backwardation",
        roll * 100);
```

The shared discounting curve is what makes the three prices consistent: a CDS
upfront and a commodity carry computed against DIFFERENT rates would embed a
hidden rates arbitrage no desk intends. The credit bootstrap walks the CDS
quotes short-to-long, solving one hazard rate per pillar to reprice that
maturity to zero upfront -- and it throws on a quote no hazard can explain
rather than returning a curve bound, the same refusal-over-guess stance the
rates and commodity curves take.

## 292. An FX + equities cross-desk CRB day

The central risk book's reason to exist is that an FX desk and an equity desk
carry offsetting risk they would otherwise both pay the street to shed.
Booking both into ONE factor space nets them, and the diversification benefit
is the number that justifies running risk centrally.

```java
CentralRiskBook book = new CentralRiskBook();
// Equity desk: cash and a listed option.
book.bookCashEquity("EQ-DESK", "SPY", 100_000, 500.0);
book.bookEquityOption("EQ-DESK", "SPY", OptionType.PUT,
        -200, 100, 500.0, 495.0, 0.04, 0.015, 0.18, 0.25);
// FX desk: spot and an option, decomposed to currency legs that net.
book.bookFxSpot("FX-DESK", "EURUSD", 10_000_000, 1.0850);
book.bookFxOption("FX-DESK", "EURUSD", OptionType.CALL, 5_000_000,
        1.0850, 1.10, 0.04, 0.03, 0.09, 0.5);

double[] cov = /* factor covariance in registry order */;
CentralRiskBook.CrbReport rpt = book.report(cov2d, 0.99);
System.out.printf("gross %.0f -> net %.0f (%.0f%% netted), book VaR %.0f%n",
        rpt.grossExposure(), rpt.netExposure(),
        rpt.nettingEfficiency() * 100, rpt.var());
System.out.printf("standalone desk VaR %.0f, diversification benefit %.0f%n",
        rpt.standaloneDeskVar(), rpt.diversificationBenefit());
```

Every instrument decomposes into a COMMON factor space at booking time -- an
equity-option delta nets against cash shares, an FX-option delta nets to the
currency level against spot -- so two desks' opposite flows cancel before
anyone pays a spread. The diversification benefit (sum of standalone desk VaRs
minus the netted book's VaR) is the CRB's whole commercial argument in one
figure, and the covariance is in registry order `factors().name(i)`, the same
contract `VarEngine` takes.

## 293. Honesty as a feature (three demonstrations)

Across the library, a wrong-but-comfortable number is treated as a bug. Three
mechanisms make honesty structural rather than aspirational: an unproven
signal emits nothing, an unpriceable scenario throws, and an undefined tail
refuses.

```java
// 1. An unproven learner emits ZERO, not a lucky guess.
double a = learner.normalizedPrediction(qi, ti, ofi, mz);
assert a == 0 || learner.outOfSampleIC() > 0;   // no signal without live evidence

// 2. A scenario the pricer cannot price THROWS, never a silent quantile.
try {
    VarEngine.fullRevaluationVar(scenarios, moves -> Double.NaN, 0.99);
    throw new AssertionError("should have refused a non-finite P&L");
} catch (IllegalArgumentException expected) { /* a modelling problem, loudly */ }

// 3. A fitted tail with no finite mean REFUSES to report an ES.
var tail = ExtremeValueTheory.fitPot(losses, 0.95);
try { double es = tail.expectedShortfall(0.999); }
catch (ArithmeticException | IllegalStateException refused) { /* shape >= 1 */ }
```

Each refusal is the library declining to launder ignorance into a number: the
learner will not steer size on a lucky first hour, the VaR engine will not
report a quantile over a scenario your pricer cannot value, and the EVT tail
will not print an expected shortfall when the fitted shape implies an infinite
mean. The comfortable alternative in each case -- emit the raw prediction,
skip the bad scenario, return the sample mean -- is exactly the failure that
looks fine until it costs money. A loud refusal is the feature.

## 294. The single most load-bearing invariant

If one property in this library had to be true above all others, it is this:
the fast matching engine and the readable one produce identical books.
Everything downstream -- fills, P&L, risk, reports -- inherits its correctness
from that single equivalence, which is why it is tested hardest.

```java
// One randomized stream, two engines, identical books demanded EVERY step.
long seed = ThreadLocalRandom.current().nextLong();
OrderBook spec = new OrderBook("AAPL");            // slow, obviously correct
HftOrderBook fast = new HftOrderBook(90_000, 110_000, 1 << 20); // fast, subtle

replayIdenticalStream(seed, spec, fast);           // limits, markets, IOC, cancels
assertEquals(spec.bestBid(), fast.bestBidTick() / 1000.0, 1e-9);
assertEquals(spec.bestAsk(), fast.bestAskTick() / 1000.0, 1e-9);
assertEquals(spec.tradeCount(), fast.tradeCount());
// A single differing seed is a reproducible bug against the SPECIFICATION.
```

The `OrderBook` is the executable specification and the `HftOrderBook` is the
optimization; the equivalence test makes the specification the judge, so the
204ns engine cannot drift from correct without failing the build. This is the
load-bearing invariant because it is UPSTREAM of everything: a matching bug
would corrupt every fill, and a corrupted fill poisons the P&L, the risk book,
and the compliance report in turn. Guard the foundation hardest, and the
tower it holds up inherits the guarantee.

## 295. A warm-start A/B: does persisted state actually help

Persisting learned state (recipe 257) is only worth the complexity if it
measurably improves the first hour. Prove it with an A/B: run one session
cold, one warm from yesterday's checkpoint, on the same replayed ticks, and
diff the early-session performance.

```java
TickBacktester.Config cfg = TickBacktester.Config.defaults().withTickSize(0.0001);

// A: cold start -- models begin at zero.
VolumeCurve cold = new VolumeCurve(78, 0.1);
TickBacktester.TickBacktestResult a = TickBacktester.run(
        strategyUsing(cold), Path.of("session.qflt"), cfg);

// B: warm start -- same models, restored from yesterday.
VolumeCurve warm = new VolumeCurve(78, 0.1);
Checkpoint.reader(Path.of("state/eod.qflc")).section("volume.AAPL", warm::readState);
TickBacktester.TickBacktestResult b = TickBacktester.run(
        strategyUsing(warm), Path.of("session.qflt"), cfg);

System.out.printf("cold slippage %.2fbps vs warm %.2fbps%n",
        a.avgSlippageBps(), b.avgSlippageBps());   // warm should win the open
```

The A/B is the honest test of a feature that is easy to assume and hard to
verify: a warm volume curve should schedule the open better than a cold one
that thinks every bucket is average, and if it does not, the persistence is
cost without benefit. Replaying the SAME captured ticks into both runs isolates
the variable to the starting state -- deterministic replay is what makes the
comparison clean rather than a tale of two different mornings.

## 296. A latency budget across the whole pipeline

A tick-to-order number is a sum of stages, and you cannot optimize a sum you
have not decomposed. Put a `LatencyRecorder` on each stage -- signal, decision,
gate, publish -- and the budget shows you which link owns the tail.

```java
LatencyRecorder signal = new LatencyRecorder(), decide = new LatencyRecorder(),
                gateT  = new LatencyRecorder(), total  = new LatencyRecorder();

long t0 = System.nanoTime();
double alpha = sig.alpha(sym);                     long t1 = System.nanoTime();
long due = exec.dueQuantity(frac, marketState);    long t2 = System.nanoTime();
int code = gate.check(sym, Side.BUY, due, px);     long t3 = System.nanoTime();
if (code == 0) gateway.submit(sym, Side.BUY, due, px, t3);
long t4 = System.nanoTime();

signal.record(t1 - t0); decide.record(t2 - t1);
gateT.record(t3 - t2);  total.record(t4 - t0);
System.out.printf("signal p99=%dns decide p99=%dns gate p99=%dns | total p99=%dns%n",
        signal.percentile(0.99), decide.percentile(0.99),
        gateT.percentile(0.99), total.percentile(0.99));
```

The stage recorders answer the only question that matters when a total budget
is blown: which stage regressed. A per-stage p99 that suddenly dominates is the
suspect, and the sum of stage p99s exceeding the total p99 is the normal
signature of the stages NOT stalling together -- if they did stall together
(one GC pause hitting all four), recipe 270's hiccup monitor would show it.
Each recorder is single-writer and a couple of array writes, cheap enough to
leave on in production.

## 297. An end-of-day firm reconciliation

The close is a reconciliation, not a shutdown: the gateway's settled cash, the
economics ledger's realized spread, the risk book's marks, and the checkpoint
about to be written must tell one coherent story. Run the four together and
assert they agree before anyone goes home.

```java
PaperTradingGateway.AccountSnapshot acct = gateway.snapshot();  // settled cash
double ledgerNet = ledger.netEconomics();                       // realized spread
CentralRiskBook.CrbReport risk = book.report(cov, 0.99);        // marks + VaR

// The three views of the same day must reconcile (recipe 258).
double explained = acct.realizedPnl() - ledgerNet;              // = inventory marks?
System.out.printf("cash P&L %.0f = spread %.0f + inventory mark %.0f?%n",
        acct.realizedPnl(), ledgerNet, explained);

// Only after reconciliation: persist what tomorrow needs.
book.factors();                                                 // registry frozen
try (Checkpoint.Writer w = Checkpoint.writer(Path.of("state/eod.qflc"))) {
    w.section("crb.book", book::writeState)
     .section("crb.ledger", ledger::writeState);
}   // atomic commit: a torn checkpoint after a clean reconciliation is unthinkable
```

The discipline is reconcile-THEN-persist: checkpointing a book whose numbers do
not tie out just carries the break into tomorrow with a clean-looking file. The
three ledgers measure different slices on purpose -- settled cash, realized
spread, unrealized marks -- so the test is not equality but ATTRIBUTION: the gap
between cash P&L and ledger spread must be the inventory mark the risk report
values, and a gap you cannot attribute is a break to fix before the atomic
commit locks the day in.

## 298. A risk-limit calibration from history feeding the live gate

The fast-lane gate's collars and caps are policy numbers, and policy should be
calibrated to the book's own history, not guessed. Compute the limits offline
from realized distributions, then push them into the live `HftRiskGate` at
session start -- research informs the fast lane without ever touching its hot
path.

```java
// Offline: size the collar to the realized move distribution, the position
// cap to a VaR-consistent level.
double[] intradayReturns = /* from captured sessions */;
double collarPct = 5 * MathUtils.stdDev(intradayReturns);  // 5-sigma fat-finger band
double var99 = VarEngine.deltaNormalVar(exposures, cov, 0.99);
long positionCap = (long) (riskAppetite / var99 * currentPosition);

// Session start (cold path): push calibrated policy into the live gate.
HftRiskGate gate = new HftRiskGate(64)
        .maxPositionQuantity(positionCap)
        .priceCollarPct(collarPct);
gate.setReferencePrice(sym, openMid);
// ... from here the gate checks every order in ~3ns against calibrated limits.
```

The separation of concerns is the whole design: the calibration is research-lane
work -- statistics over history, allowed to allocate and take its time -- and its
OUTPUT is a handful of primitive limits the gate enforces at nanosecond cost.
A collar set by gut is either so wide it never catches a fat-finger or so tight
it rejects normal flow; calibrating it to five sigma of the book's own realized
moves makes it a measured tripwire. Recalibrate end-of-day and push the new
limits at the next session start, the same warm-start cadence as recipe 257.

## 299. The honest-number preflight

Before any number leaves the desk -- a VaR to the board, a Sharpe to an
allocator, a fill-quality stat to compliance -- it should clear a preflight of
the library's own honesty checks. Bundle them into one gate so a number that
cannot defend itself never ships.

```java
List<String> failures = new ArrayList<>();

// Backtest number: did it survive selection and the out-of-sample test?
if (SharpeValidation.deflatedSharpe(sh, trials, n, sk, ku) <= 0)
    failures.add("Sharpe does not survive the multiple-testing haircut");
if (OverfitProbability.cscvSharpe(folds, 10).pbo() > 0.5)
    failures.add("PBO > 0.5: backtest is probably overfit");

// Risk number: is the tail even defined, and does the model backtest clean?
var tail = ExtremeValueTheory.fitPot(losses, 0.95);
if (FrtbEs.TrafficLight.of(exceptions250d) == FrtbEs.TrafficLight.RED)
    failures.add("VaR model in the RED zone: presumed wrong");

if (!failures.isEmpty())
    throw new IllegalStateException("number failed preflight: " + failures);
```

The preflight makes honesty a GATE rather than a good intention: each check is
one the library already ships -- deflated Sharpe for selection bias, PBO for
overfitting, the FRTB traffic light for model validity, the EVT refusal for an
undefined tail -- and bundling them means a number is either clean on all counts
or it does not go out. The failures list is the reviewer's checklist made
executable; a number that trips it is not censored, it is sent back for the
caveat it needs. This is recipe 293's stance turned into a shipping gate.

## 300. The whole library in one program

The grand finale: one compact program that touches every layer in order --
data, signal, risk, execution, report -- using the real classes, so the last
recipe is also the smallest complete tour of the library.

```java
// 1. DATA: load and clean bars, adjust for a split.
long exTs = LocalDate.of(2020, 8, 31)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();   // split ex-date
BarSeries bars = CorporateActions.adjust(
        CsvBarLoader.load(Path.of("AAPL.csv"), "AAPL"),
        List.of(new CorporateActions.CorporateAction(exTs,
                CorporateActions.Type.SPLIT, 4.0)));

// 2. SIGNAL: backtest a strategy, honestly (out-of-sample metrics).
BacktestResult bt = Backtester.run(new SmaCrossStrategy(20, 50), bars,
        BacktestConfig.defaults());

// 3. RISK: size the position to a VaR budget on a factor book.
double[] exposures = { bt.metrics().sharpeRatio() * 1_000_000 };
double var99 = VarEngine.deltaNormalVar(exposures, new double[][]{{0.0004}}, 0.99);

// 4. EXECUTION: work the sized order on paper with real pre-trade limits.
PaperTradingGateway paper = new PaperTradingGateway(1_000_000, 0.0005,
        new PreTradeLimitChecker().maxOrderQuantity(10_000).priceCollarPct(0.03));
paper.onQuote("AAPL", 149.99, 150.01);
paper.submitMarket("AAPL", Side.BUY, 1_000);

// 5. REPORT: one client-ready artifact tying it together.
PaperTradingGateway.AccountSnapshot acct = paper.snapshot();
new ReportGenerator("End to end")
        .addStrategyPerformance(bt)
        .toHtml(Path.of("report.html"));
System.out.printf("Sharpe %.2f, VaR %.0f, equity %.2f -> report.html%n",
        bt.metrics().sharpeRatio(), var99, acct.equity());
```

Five layers, one thread of control: cleaned data feeds an honest backtest, the
backtest's edge sizes a risk budget, the budget sizes an order the paper venue
fills through the same limits production would use, and the whole day lands in
one report. Every class here is real and every step is testable -- swap
`PaperTradingGateway` for a broker adapter (recipe 273) and the same five lines
run live. That is the library's argument in one program: the path from a CSV to
a defensible fill is short, honest at every stage, and the same code in research
and in production.

---


Every number quoted here comes from a committed benchmark; every behavior
from a committed test. When a recipe and the javadoc disagree, the javadoc
wins — and please open an issue.
