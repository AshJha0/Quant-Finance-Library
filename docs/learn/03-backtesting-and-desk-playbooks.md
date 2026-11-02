# Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: risk, derivatives, rates and credit (sections 9 to 10u)](02-risk-derivatives-rates-credit.md) | [Next: Part II, The Technology (sections 12 to 20)](04-technology.md)

---

### 11. Backtesting without fooling yourself

A **backtest** replays history against your strategy. The ways it lies to
you are well-catalogued, and this library implements defenses for each:
costs and impact (unrealistically free fills), look-ahead (using data you
couldn't have had), survivorship (§8), overfitting (§8), execution fantasy
(assuming every limit order fills, when reality depends on queue position;
assuming FX orders always fill, when reality includes last-look rejects, which
cluster on exactly the trades that were about to be profitable). The
**deflated Sharpe ratio** even adjusts your performance stat for how many
strategies you tried before finding this one.

*In this library:* `backtest/` (bar-level, execution-aware, tick-level with
queue positions, last look), `backtest/validation/`, `marketdata/TickCapture` and
replay for deterministic tick-level history. Two defaults matter:
`BacktestConfig.defaults()` charges 1 bp commission and 3 bp slippage and
fills orders at the next bar's open (`BacktestConfig.frictionless()`
switches costs off for teaching), and walk-forward analysis carries an
open position across fold boundaries instead of closing it at each one.

**Was the *search* sound?** A single backtest can be clean and the
process that selected it still rotten. Four more defenses:

- **Purged K-fold with embargo.** Ordinary K-fold cross-validation
  leaks on financial data, and the leak is subtle: a sample's label is
  usually computed from bars that come *after* it (a "5-bar forward
  return"). Put bar 99 in training and bar 100 in test, and bar 99's
  label was computed from bars 100 to 104, so the model has already seen
  the test answer. `backtest/validation/PurgedKFold.splits` purges
  every training sample whose label window overlaps the test fold and
  adds an **embargo** buffer after it (serial correlation means
  features just past the fold still echo test-period information),
  and refuses any split that leaves a fold with no training data,
  because silently training on nothing is how "great" fold scores
  happen.
- **Probability of backtest overfitting.** You didn't run one
  backtest; you ran 400 parameter sets and reported the best. The
  right diagnostic is CSCV
  (`backtest/validation/OverfitProbability.java`): split time into
  blocks, form every symmetric in-sample/out-of-sample combination,
  and ask how often the in-sample winner lands *below the median* out
  of sample. That fraction is the **PBO**: below 0.1 the selection is
  finding something real; at 0.5 it's pure noise-mining and the "best"
  backtest is meaningless however good it looks.
- **The deflated Sharpe, wired in.** The deflated Sharpe ratio above
  isn't a separate ritual here. `GridSearchOptimizer.deflatedSharpeOfWinner`
  takes the ranked grid-search results and judges the winner's Sharpe
  against what the best of N zero-skill trials would have scored
  anyway, with N taken from `effectiveTrials` because neighbouring grid
  points are correlated. The search that created the multiple-testing problem hands
  you its own haircut.
- **Compared to what?** A standalone Sharpe answers "was this good?";
  an allocator asks "was this good *compared to just buying the
  index*?" `backtest/BenchmarkComparison.java` computes beta (how
  much of the strategy is the benchmark in disguise), Jensen alpha,
  tracking error, information ratio, and up/down capture. And
  "max drawdown 18%" hides the number that actually fires clients:
  how long the pain lasted. `backtest/DrawdownAnalytics.java` walks
  every peak-to-recovery episode (depth, duration, time under water)
  and surfaces a drawdown still open at series end instead of hiding
  it, because that is often the current state of the strategy.

### 11b. One strategy, three engines

A strategy usually runs on several engines over its life: a bar
backtester for research, a tick replay for execution detail, and a paper
gateway before going live. If the three disagree on the same data, you
need to know whether the strategy or an engine is at fault. The way to
find out is to fix one contract that every engine honours and compare the
fills.

The contract here is narrow: the strategy is asked once per
bar after the close, a buy while flat spends all cash and a sell while
long sells everything, both at the close, with no costs. That is the only
setting in which the three fill models coincide. From 2.2 every engine
buys `floor(cash/price)` whole shares by default, so all three agree
exactly. `StrategyRunner.Sizing.fractionalShares()` brings back the 2.1
bar engine, which buys `cash/price` shares, fractions included, and then
agrees with the others only when `cash/price` is whole at every entry. Costs, spreads, limit orders and queue models differ by
engine and are outside the contract.

*In this library:* `trading/StrategyRunner.java` (`run(Engine,
strategy, tape, capital, workDir)` with `Engine.BAR_BACKTESTER`,
`TICK_BACKTESTER` and `PAPER_GATEWAY`; `writeTape` writes the bars as a
tick file with one print per bar at the close). Recipe 324.

### The desk playbooks: pairs, toxicity, the central risk book, rolls

Four more things real desks do every day, each a small world of its own.
These build on everything above, so read §1–§11 first.

**Pairs trading, or: betting on a rubber band.** Some price
relationships are tethered: two share classes of one company, a stock
and its ADR, cash equities and index futures, on-the-run vs off-the-run
bonds. When the spread between them stretches, you sell the expensive
leg, buy the cheap one, and wait for the tether to pull them back.
Three questions decide whether this is a strategy or a slow-motion
accident:

1. *Is the spread actually tethered?* A *cointegration test* asks
   whether the spread is statistically stationary. Two random walks
   can look correlated for years and then never come back.
2. *How fast does it snap back?* Fit an **Ornstein-Uhlenbeck** process
   to the spread: the fitted **half-life** (how long the spread takes
   to close half its gap) is your expected holding period, and the
   **z-score** (how many stationary standard deviations you are from
   the mean) is your entry and exit signal. A 200-day half-life is not
   a trade. And a spread that
   shows no mean reversion in-sample must be refused, not fitted:
   fitting a rubber-band model to a random walk is how pairs desks die
   (this library's fit literally throws in that case).
3. *How do you get in without owning half a trade?* The moment you own
   one leg without the other, you're not a pairs trader; you're just
   long (or short) in disguise. **Legging risk** is the main execution
   risk of spread trading: work the illiquid leg patiently (it is the
   constraint), let the liquid leg chase each fill at the spread
   ratio, and set a hard cap on the imbalance. At the cap, stop adding
   risk entirely and cross the hedge leg to get flat, whatever it costs.

*Real-life case:* a desk sees two dual-listed shares 2.5 z-scores
apart with a fitted half-life of 8 days. Entry: sell the rich listing,
buy the cheap one, ratio-locked, legging cap at 5% of the position.
Exit: z back under 0.5, or the cointegration breaks (a takeover
announcement cuts the rubber band: exit everything, because the tether
is gone).

*In this library:* `hedging/CointegrationTest.java` (question 1;
`engleGranger(a, b, lags)` adds ADF lags and MacKinnon 2010
finite-sample critical values),
`microstructure/OrnsteinUhlenbeck.java` (question 2: κ/θ/σ, half-life,
z-score, and the refusal), `execution/SpreadExecutionAlgo.java`
(question 3: the legging cap), and cookbook recipe 15 runs the whole
chain. Desks add two refinements.
`microstructure/VarianceRatio.java` answers the question that comes
before question 1: is this series trending, mean-reverting, or a random
walk? Under a random walk variance grows linearly with horizon, so
the ratio is 1; mean reversion cancels, momentum compounds. And
`microstructure/KalmanBeta.java` replaces the static hedge ratio with
a time-varying one: relationships drift, a β fitted on last year is
stale by spring, and the filter tracks the current β while a
full-sample OLS averages the drift into a number that was never true
on any single day (the test demonstrates exactly that).

**Flow toxicity, or: knowing when to stop quoting.** §5 explained
adverse selection: the people most eager to trade with you know
something. **VPIN** turns that into a live dial: bucket trades by
volume (informed trading compresses clock time, but volume time it
cannot hide from), score each bucket's buy/sell imbalance, average the
last fifty buckets. Balanced two-way flow reads near 0; one-sided,
informed flow reads toward 1. It was elevated in the hour before
the 2010 flash crash (Easley, López de Prado and O'Hara 2012). A market maker watching VPIN climb doesn't argue
with it: widen the spread, cut the quote size, or stop quoting. Being
the last one quoting into informed flow is how you buy the top tick of
every move.

And when you have no quote data at all (a 20-year backtest, an
emerging market, history before your capture started) you can still
estimate liquidity from bars alone: Roll's estimator reads the spread
out of bid-ask bounce (trade prices ping-ponging leave a negative
autocorrelation fingerprint), Corwin-Schultz reads it from daily
high-low ranges, and Amihud's ratio (|return| per dollar traded) ranks
which names punish size. These are estimators, not measurements: rank
with them, don't mark books with them.

*In this library:* `microstructure/Vpin.java`,
`microstructure/TradeClassifier.java` (feeds it aggressor sides when
the venue doesn't say), `microstructure/LiquidityMeasures.java`,
cookbook recipe 17 wires VPIN into a quoting defense.

**The central risk book, or: netting before hedging.** Walk a large
firm's trading floor: the cash equities desk just bought 2M of an
index's risk from a client, and three desks away the ETF desk sold 2M
of nearly the same risk. If both desks hedge with the street, the firm
paid the spread twice for risk it never net had. A **central risk
book** fixes this by seeing every desk's position in one factor space:
equity deltas per symbol (an option's delta and cash shares land on
the same factor), FX exposure per currency (so EURUSD spot, a EURUSD
option's delta, and an NDF's forward all net at the currency level),
vega and gamma per underlying. What survives the netting is the only
risk worth paying to hedge, and even then the CRB hedges only the
excess beyond its warehouse band, because inventory that will net
against tomorrow's flow is an asset, not a problem.

The CRB is also a business, with three revenue mechanics you can now
read as code: it *internalizes* client flow that reduces its risk (and
gives the client back part of the saved spread, because being worth
trading with keeps the flow coming), it *skews* its quotes to attract the flow it wants
(long inventory, so shade both quotes down, so the ask becomes the
attractive side), and it routes its own hedges through itself first:
the firm's own offsetting inventory is a dark pool with zero cost and
zero information leakage. The close-of-day question is one number:
did the spread we captured pay for the hedging we did?

*Real-life case:* a pension unwinds 18M of index risk through the desk
in six tickets. The warehouse absorbs it (that's the service), the
equity band breaches, the cost-aware hedger notices the cheap ES-proxy
future can't satisfy a per-symbol band and escalates to the direct
program trade, the hedge routes to a clean midpoint pool while the
15bps-markout pool gets nothing (internal crossing is the router's
standing first priority, but that day there was nothing crossable),
and the day still nets positive after every cost. That
exact day runs in `crb/CrbRealWorldScenarioTest.java` (under
`src/test`).

*In this library:* the whole `crb/` package.
[CENTRAL_RISK_BOOK.md](../CENTRAL_RISK_BOOK.md) is the guided tour,
cookbook recipe 14 the runnable day.

**Rolls and anti-gaming, or: the trades everyone can see coming.**
Two execution problems are special because your intentions are
predictable. First, the futures roll: every futures position must
migrate from the expiring contract to the next one, everyone knows
roughly when, and liquidity migrates on a well-known S-curve. Roll
too early and you pay wide back-month spreads, too late and you join
the last-day scramble. The answer is to roll in step with the
migration, executing each day's slice as a calendar spread. Second,
the metronome problem: a TWAP that fires identical child orders on an
exact clock is detected by predators within minutes, and every
subsequent child gets leaned on. The fix is seeded randomness: jitter
child sizes and times enough to kill the pattern while preserving the
total exactly and keeping every child auditable (same seed, same
schedule: compliance can replay why each child fired when it did).

*In this library:* `execution/FuturesRollAlgo.java` (the migration
curve, with a curve that doesn't end at 100% rejected as the delivery
risk it is), `execution/AntiGamingJitter.java`,
`execution/OrderPlacementPolicy.java` (the smallest repeated decision,
post passively or cross, as expected-cost arithmetic instead of
habit).

**The volatility zoo: six kinds, one map.** "Volatility" is six
different things wearing one word, and confusing them is expensive:

1. **Historical volatility**: how much the price did move, measured
   from past returns: `risk/RiskMetrics.annualizedVolatility` (the
   plain standard deviation) and `volatility/EwmaVolatility` (recent
   moves weighted more, the RiskMetrics λ = 0.94 classic).
2. **Implied volatility**: the market's forecast, read backwards out
   of an option's price: `pricing/BlackScholes.impliedVol`,
   `pricing/Black76.impliedVol`, and the smile structures
   (`pricing/VolSurface`, `fx/FxVolSurface`, `pricing/VannaVolga`).
   Historical looks back; implied is what people are paying for.
3. **Realized volatility**: what actually happened over a window,
   built from intraday returns: `microstructure/JumpRobustVolatility`
   (and its key trick: bipower variation separates a headline jump
   from a genuine volatility regime), forecast by `volatility/HarRv`.
4. **Market volatility**: the whole market's expected turbulence,
   tracked by the VIX, the "fear index".
   `volatility/VolatilityIndex` computes it model-free from an option
   chain: every out-of-the-money option contributes its 1/K² slice
   to a portfolio whose payoff is variance, so the portfolio's price
   reveals the market's expectation with no pricing model assumed.
   The put skew is in the index, which is why VIX sits above ATM
   implied vol.
5. **Idiosyncratic volatility**: the company's own story (earnings,
   management, product launches). It diversifies away across names.
6. **Systematic volatility**: the economy's story (rates, inflation,
   macro shocks). It does not diversify away; only index hedges
   remove it. `volatility/VolatilityDecomposition` splits any stock's
   variance exactly into 5 and 6 via the single-factor regression
   (β²·market variance + residual). The R² is the systematic share:
   a utility near 0.7 is mostly a market proxy, a biotech near 0.05
   is mostly its own story, and the two need entirely different
   hedges.

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: risk, derivatives, rates and credit (sections 9 to 10u)](02-risk-derivatives-rates-credit.md) | [Next: Part II, The Technology (sections 12 to 20)](04-technology.md)
