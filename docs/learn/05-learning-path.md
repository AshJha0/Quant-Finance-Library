# Part III, Your learning path

[Index: LEARN.md](../LEARN.md) | [Previous: Part II, The Technology (sections 12 to 20)](04-technology.md) | [Next: Part IV, Knowledge checks: Q1 to Q135](06-checks-q0001-q0135.md)

---

## Part III — Your learning path

**Run things (30 minutes).**

```bash
git clone https://github.com/AshJha0/Quant-Finance-Library && cd Quant-Finance-Library
./mvnw package                     # needs JDK 25; builds and runs the default test suite
java -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.QuickStartDemo
java -cp core/target/classes:io/target/classes:research/target/classes:lowlatency/target/classes com.quantfinlib.examples.LiveTradingDemo   # live Binance ticks, paper trading, browser dashboard
```

**Read code in this order** (each step uses the previous ones):

1. `core/Bar.java`, `indicators/StreamingIndicators.java`: the simplest lane.
2. `orderbook/OrderBook.java`: matching, readable (§2).
3. `marketdata/TickRingBuffer.java`: your first lock-free structure (§14).
4. `trading/HftRiskGate.java`: risk and the memory model (§9, §15).
5. `orderbook/HftOrderBook.java`: everything from §14 in one file.
6. `marketdata/L3BookBuilder.java` and `fx/FxTierBook.java`: the two market
   structures side by side (§3, §4).
7. `backtest/Backtester.java`, then the `alpha` package (§8, §11), then the
   search-validation tools: `backtest/validation/PurgedKFold.java`,
   `OverfitProbability.java`, `backtest/BenchmarkComparison.java` and
   `DrawdownAnalytics.java` (§11).
8. `optimization/PortfolioOptimizer.java`, `RiskParityOptimizer.java`,
   `BlackLitterman.java`, `ConstrainedPortfolioOptimizer.java` (§8b):
   forecasts into weights, and why the naive optimizer can't be trusted
   with your forecasts.
9. `microstructure/VolumeCurve.java`, `SignalEngine.java`,
   `OnlineAlphaLearner.java`, `execution/BenchmarkExecutor.java`,
   `PortfolioExecutor.java`, `persist/Checkpoint.java`: the execution
   stack from live inputs to basket schedule to overnight
   (§6, §7, §18).
10. The desk playbooks (end of Part I): `microstructure/OrnsteinUhlenbeck.java`
    and `execution/SpreadExecutionAlgo.java` (a pairs trade end to end),
    `microstructure/Vpin.java` (toxicity), then the whole `crb/` package
    with [CENTRAL_RISK_BOOK.md](../CENTRAL_RISK_BOOK.md) as the guide and
    `crb/CrbRealWorldScenarioTest.java` (under `src/test`) as a
    realistic trading week you can step through in a debugger.
11. The rates lane (§10b): `rates/YieldCurve.java`, `BondPricer.java`,
    `DayCount.java`, `KeyRateDurations.java`, `ShortRateModels.java`:
    curve to bond to hedge to simulation.
12. The risk stack: [MARKET_RISK.md](../MARKET_RISK.md) maps the steps
    from market data to Basel/FRTB; `risk/TailRisk.java`,
    `risk/VarEngine.java`, `risk/StressTester.java` and `risk/FrtbEs.java`
    are the reading spine,
    and `risk/MarketRiskTest.java` (under `src/test`) shows every
    formula pinned by hand. Then the compliance view (§9b):
    `regulatory/MarketQualityMetrics.java` and `regulatory/FixAnalyzer.java`
    are both readable in one sitting.
13. Connectivity (§17): `fix/FixSession.java` (with `fix/FixSessionTest.java`
    under `src/test` — watch a logon, a fill and a gap-fill happen) and
    `fix/FixOrderEncoder.java`, the same message written the readable way
    and the fast way.
14. `docs/COOKBOOK.md`: end-to-end recipes to modify and re-run.
15. Part IV below: once the above feels familiar, test yourself with the
    knowledge check. It is organised by topic, and every question has a
    worked answer tied back to a class you have now read.

**Exercises** (in rough order of difficulty):

1. Add a new streaming indicator (e.g. rate-of-change) to
   `StreamingIndicators` with a test proving it matches a batch
   computation.
2. Write an allocation-counter test for it (copy any existing one).
3. `OrderBook` already has `mid()`; `HftOrderBook` does not.
   Add it (from `bestBidTick`/`bestAskTick`), then extend the equivalence
   test so both books' mids are compared after every random operation.
4. Build a tiny strategy in the backtester: buy when queue imbalance > 0.5
   on replayed ticks. Measure how costs change the result.
5. Run `HftBookBenchmark` before and after replacing `HftOrderBook`'s
   bitmap scan with a linear loop, and *measure* what the bitmap buys.

**Glossary shortcut** — if you hit an unknown term anywhere in the docs:
bid/ask/spread/mid (§1), limit/market/IOC/FOK/post-only (§2), L1/L2/L3,
ITCH, book building, queue position (§3), NBBO, tape, dark venue, LULD,
OTC, LP, last look, tiers, WMR fix (§4), inventory, skew, adverse selection, markout (§5),
TWAP/VWAP/POV, implementation shortfall, arrival price, market impact, TCA,
slippage, volume curve, volatility regime, spread forecast, fill
probability, Lee-Ready, iceberg, transition, leg balance (§6), microprice,
imbalance, OFI, online learning, out-of-sample IC, nowcast, lead-lag,
day type (§7), factor, IC, walk-forward,
permutation test, survivorship bias, point-in-time (§8), mean-variance,
error-maximizer, risk parity, Black-Litterman, turnover penalty (§8b),
notional, collar, kill switch, VaR (§9), best execution, quoted/effective/
realized spread, price impact, order-to-trade ratio, banging the close (§9b),
forward, swap points, NDF, call/put, strike, greeks,
delta/gamma/vega/theta, implied vol, smile, risk reversal, butterfly,
vanna-volga (§10), yield curve, bootstrapping, discount factor, forward
rate, duration, convexity, DV01, key-rate duration, day count, roll
convention, short-rate model (§10b), deflated Sharpe, purged K-fold,
embargo, PBO, tracking error, information ratio, time under water (§11),
GC, Epsilon GC, autoboxing (§13),
ring buffer, SPSC, single-writer, open addressing, linear probing,
backward-shift deletion, bitmap, flyweight, cache locality (§14), memory
model, release/acquire, VarHandle (§15), percentile, p99, JIT, warm-up,
hiccup (§16), FIX, tag=value, scaled long, SBE, sequence recovery,
heartbeat, TestRequest, ClOrdID, ExecutionReport (§17),
sharding, shared-nothing, checkpoint, atomic rename (§18).

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part II, The Technology (sections 12 to 20)](04-technology.md) | [Next: Part IV, Knowledge checks: Q1 to Q135](06-checks-q0001-q0135.md)
