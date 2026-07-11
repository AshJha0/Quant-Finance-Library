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
long[] messages = {410, 395, 402, 388, 9_874, 401, 397};  // orders+cancels+replaces
long[] trades = {21, 19, 24, 18, 3, 22, 20};              // trades, same intervals
double[] mids = {100.02, 100.03, 100.01, 100.04, 99.38, 100.02, 100.03};

// Message rate a 3-sigma outlier AND order-to-trade ratio >= 50.
List<AnomalyDetector.Anomaly> stuffing =
        AnomalyDetector.detectQuoteStuffing(messages, trades, 3.0, 50.0);

// Interval return a 4-sigma outlier vs its own recent distribution.
List<AnomalyDetector.Anomaly> spikes =
        AnomalyDetector.detectPriceSpikes(mids, 4.0);

for (AnomalyDetector.Anomaly a : stuffing) {
    System.out.printf("interval %d: %s z=%.1f%n", a.intervalIndex(), a.type(), a.score());
}
spikes.forEach(a -> System.out.printf("interval %d: %s z=%.1f%n",
        a.intervalIndex(), a.type(), a.score()));
```

The quote-stuffing test deliberately requires both conditions at once: a message-rate spike alone is what a busy open looks like, and a high order-to-trade ratio alone is what a quiet market-maker looks like -- it is the combination (lots of quoting, almost no trading, far above baseline) that characterizes the pattern. Both detectors compute their baselines from the supplied window itself, so feed them rolling windows sized to a regime you consider comparable (a day, a session hour), not a mixed month; and treat the returned `score` as triage priority for a human, not as a verdict -- z-scores flag, people conclude.

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

---

reprice exactly.

---

Every number quoted here comes from a committed benchmark; every behavior
from a committed test. When a recipe and the javadoc disagree, the javadoc
wins — and please open an issue.
