# Learn: A Beginner's Guide to This Library

This guide assumes **no background in finance and no background in
low-latency engineering**. It explains, in plain language, every major idea
this library implements — first the finance, then the technology — and ties
each one to the actual classes so you can read real code right after
learning the concept. If you're a student or a junior engineer, read it top
to bottom; each section is a few minutes.

A useful mindset before you start: nothing in trading is magic. Markets are
queues, prices are auctions, "high-frequency" is mostly careful engineering,
and most of the math is high-school algebra applied very carefully. The hard
part is honesty — measuring what your system actually does instead of what
you hope it does. That theme runs through this whole codebase.

---

## Part I — The Finance

### 1. Why every price is actually two prices

When people say "EURUSD is 1.0850," they're simplifying. At any moment there
are two prices: the **bid** (the highest price someone is willing to *buy*
at) and the **ask** or **offer** (the lowest price someone is willing to
*sell* at). The gap between them is the **spread**.

If the bid is 1.0850 and the ask is 1.0852, and you want to buy *right now*,
you pay 1.0852. If you immediately changed your mind and sold, you'd receive
1.0850. You just lost 2 **pips** (a pip = 0.0001 for most FX pairs) without
the market moving at all. That round-trip cost is the spread, and it is the
basic fee the market charges for immediacy. The **mid** (average of bid and
ask) is a convenient "fair-ish" reference price, but nobody actually trades
at the mid.

*In this library:* every book class exposes the bid and ask (most also a
mid) — `fx/AggregatedBook.java` is a good first read: ~190 lines, plain
arrays, and its javadoc explains each decision.

### 2. The order book: the market is a queue

An **order book** is two sorted lists. Buyers post **limit orders** ("I'll
buy 100 shares at $50.00 or less") which rest on the bid side; sellers post
theirs on the ask side. The book is sorted by price, and within one price
level, by arrival time — **price-time priority**. First come, first served,
like any queue.

A **market order** ("buy 100 shares at whatever price") doesn't rest — it
*matches* against the best resting orders on the opposite side, walking to
worse prices if the best level doesn't have enough size. That's why big
market orders "move the market": they eat through the book.

Some useful order-type vocabulary you'll meet in the code:

- **IOC** (immediate-or-cancel): match what you can right now, cancel the
  rest — never rest in the book.
- **FOK** (fill-or-kill): fill the *entire* quantity immediately or do
  nothing at all.
- **Post-only**: rest in the book or be rejected — never take liquidity.
  Used by market makers who want to *earn* the spread, not pay it.
- **Iceberg**: show only a small part of your true size, reload as it fills.

*In this library:* `orderbook/OrderBook.java` is the readable matching
engine — start there; every rule is spelled out. `orderbook/HftOrderBook.java`
is the same logic rebuilt for speed (Part II explains how), with
`submitIoc`/`submitFok`/`submitPostOnly`. A test drives both with identical
random orders and demands identical books — the readable one is the
executable specification of the fast one.

### 3. Market data: L1, L2, L3

Exchanges broadcast what's happening in the book. The levels of detail have
names:

- **L1** — just the best bid/ask and last trade ("top of book").
- **L2** — the book aggregated by price level: "there are 3,400 shares bid
  at $49.99 total" (but not whose they are).
- **L3** — every individual order: each add, cancel, execution, with an ID.
  From an L3 feed you can rebuild the *entire* book and even know where a
  specific order sits in each queue.

Nasdaq's L3 feed protocol is called **ITCH**: a stream of tiny binary
messages ("order 812 added: buy 300 AAPL at 175.50", "order 812 executed
for 100"). Millions per second at peak. The exercise of consuming this
stream and maintaining a correct book is called **book building**.

Why would you care where your order sits in a queue? Because at a busy
price level, hundreds of orders wait ahead of you, and only the front of
the queue gets filled when trades happen. Your **queue position** largely
determines your probability of getting filled — a first-class input to any
market-making decision.

*In this library:* `marketdata/ItchCodec.java` decodes ITCH-style messages;
`marketdata/L3BookBuilder.java` rebuilds the full book *and* tracks your own
order's exact queue position (`sharesAhead`); `microstructure/QueueModel.java`
turns queue position into a fill probability.

### 4. Market structure, or: equities and FX are different planets

**US equities** are exchange-traded: a share of AAPL trades on ~16 exchanges
plus **dark venues** (trading places that don't display their orders —
institutions use them to trade size without showing their hand), but the lit
exchanges all publish quotes, and regulation stitches them into a national
view — the consolidated **tape** (the public record of every quote and
trade). The best bid and best offer across *all*
exchanges is the **NBBO** (National Best Bid and Offer). There are also
mandated safety brakes: **LULD** (limit up–limit down) bands pause a single
stock that moves too fast, and **market-wide circuit breakers** halt
everything if the S&P 500 drops 7%/13%/20%.

**FX (currencies)** has no exchange and no tape. It is **OTC**
(over-the-counter): banks and non-bank **liquidity providers (LPs)** stream
you *their* prices privately. Three consequences that shape everything:

- **Quotes, not orders.** An LP's price is an invitation, not a commitment.
  When you try to trade on it, the LP may briefly hold your request and
  reject it if the market moved — a practice called **last look**. The
  industry's FX Global Code requires last look to be applied
  *symmetrically* (reject moves in both directions, not only the ones that
  hurt the LP).
- **Tiers.** LPs quote different prices for different sizes: 1M at one
  spread, 5M a bit wider, 20M wider still. There's no single "the price" —
  it depends on your size.
- **Benchmarks.** Many institutional FX orders are executed "at the fix" —
  the WM/Refinitiv 4pm London benchmark, computed over a 5-minute window.
  Executing evenly across that window replicates the benchmark.

*In this library:* equities side — `marketdata/Nbbo.java`,
`microstructure/CircuitBreakers.java`. FX side — `fx/FxTierBook.java`
(tiers), `fx/LpScorecard.java` + `fx/LpRouter.java` (measuring and routing
around last look), `trading/LastLookGate.java` (the maker's side, done
symmetrically), `execution/WmrFixingScheduler.java` (the fix).

### 5. Market making: selling immediacy for a living

A **market maker** quotes both sides at once — bid 1.0850, ask 1.0852 —
hoping to buy at the bid, sell at the ask, and pocket the spread over and
over. Two things ruin this simple plan:

- **Inventory risk.** If you keep getting filled on one side (everyone's
  selling to you), you accumulate a position, and now you're just *long* in
  a falling market. Makers respond by **skewing**: shading both quotes away
  from their inventory to attract offsetting flow, and **hedging** when the
  position breaches a band.
- **Adverse selection.** The counterparties most eager to trade with you
  are the ones who know something you don't. The trades you "win" easily
  are, on average, the ones you should have lost. Every serious maker
  measures **markout**: where the price goes right *after* each fill.

*In this library:* `trading/HftQuoter.java` (streaming two-sided quotes with
inventory skew), `trading/AutoHedger.java` (band hedging),
`pricing/FairValueEngine.java` (microprice — see §7).

### 6. Execution algorithms: how big orders hide

Suppose a pension fund must buy 5 million shares. Sent as one order it
would obliterate the book and telegraph its intent to everyone. Execution
algorithms slice the parent order into small children over time:

- **TWAP** (time-weighted): equal slices at equal intervals. Simple,
  predictable — sometimes *too* predictable, hence randomized jitter.
- **VWAP** (volume-weighted): slice in proportion to the typical volume
  curve (markets trade heavily at the open and close, quietly at lunch).
- **POV** (percentage of volume): react to *actual* volume — "be 10% of
  whatever trades." One subtlety: measure your participation against
  *other people's* volume, or the algo chases its own fills.
- **Implementation Shortfall / Arrival Price**: the thinking person's algo.
  Trading fast costs **market impact** (you push the price); trading slow
  costs **risk** (the price drifts away from where it was when you
  decided — the "arrival price"). Almgren & Chriss (2000) solved for the
  optimal trade-off: a front-loaded schedule whose urgency grows with your
  risk aversion. Set risk aversion to zero and the math collapses to TWAP,
  which is a satisfying sanity check.

After the fact, **TCA** (transaction cost analysis) grades the execution:
slippage vs arrival, vs VWAP, fill rates, venue quality, markouts.

*In this library:* the static schedulers `execution/TwapScheduler.java`,
`VwapScheduler.java`, `PovTracker.java`,
`ImplementationShortfallScheduler.java` (with
`microstructure/AlmgrenChriss.java` for the math) emit a fixed slice list;
`execution/BenchmarkExecutor.java` is the **dynamic** version — one executor
covering all of VWAP/TWAP/Arrival/IS/Close/Open/POV that re-decides each
interval from live spread, depth, volatility, the volume curve, estimated
impact and an alpha signal. TCA is
`microstructure/TransactionCostAnalyzer.java`. Smart order
routing — which venue gets each child — is `execution/SmartOrderRouter.java`
(readable), `execution/HftSor.java` (fast), and
`execution/AdaptiveSor.java` (full-checklist: lit + dark, learned fill rate,
latency, hidden liquidity, and adverse selection — a venue whose fills are
systematically followed by reversion is charged that reversion per share,
because two identical quotes are not equal when one venue's fills fade).

**Where do those "live inputs" come from?** A dynamic algo is only as good
as the numbers you feed it, and each one is a small model:

- *"How much volume is left today?"* Markets trade in a U-shape — heavy at
  the open, quiet at lunch, heavy at the close. `VolumeCurve` learns that
  shape day after day and rescales it live ("today is running 2× normal").
- *"Is the market volatile right now?"* is the wrong question — the open is
  ALWAYS wild. The right question is "volatile *for this time of day*?",
  and `VolatilityCurve.regime()` answers exactly that (0 = normal for this
  hour, 1 = genuinely extreme).
- *"What will the spread cost me in a minute?"* `SpreadForecaster` knows
  the time-of-day baseline and how fast a spread spike decays back to it —
  so the algo slows down *before* the known-wide close, not after.
- *"If I queue at this price, will I ever get filled?"*
  `QueuePositionEstimator` tracks how many shares are ahead of you (from
  L2 alone), and `FillProbabilityModel` multiplies "does the price come to
  me?" by "does the queue ahead of me clear?".
- *"Who started that trade?"* Many feeds don't say. `TradeClassifier` is
  the classic Lee-Ready inference: at the ask = buyer was aggressive, at
  the bid = seller, in between = look at the tick direction (~85% right,
  which is why everything downstream uses decayed averages).
- *"Is there more size than I can see?"* `HiddenLiquidityDetector` flags
  icebergs the honest way: a single print bigger than what was displayed
  means hidden size was there.
- *"How much will MY order move the price?"* Instead of assuming a formula,
  `KylesLambda` learns it from the tape: regress price changes on signed
  flow and the slope IS the market's depth (Kyle, 1985). One caveat done
  right: a noisy negative estimate is clamped to zero cost — the model
  must never tell the algo it gets *paid* to trade.
- *"Was that a jump or a regime?"* A single headline print enters a squared-
  return estimator as r² and reads as high volatility for minutes.
  `JumpRobustVolatility` uses the product of *consecutive* returns
  (bipower variation) — a lone jump inflates only one factor, so the
  diffusion estimate barely moves and `jumpFraction()` tells you what
  share of the noise was jumps.
- *"How much should I leave for the closing auction?"* For liquid names
  the close is the deepest liquidity event of the day.
  `ClosingAuctionModel` learns the auction's typical share and tilts it by
  today's imbalance feed — with an honest caveat in its javadoc: the
  library ships the structure and the tests, but the imbalance-feed
  mapping must be validated against your venue's data.

**Executing a whole basket.** A pension fund rarely trades one stock — it
transitions a *portfolio* (sell the old holdings, buy the new ones). Run N
independent algos and a new risk appears that none of them can see: if the
buys fill fast and the sells lag, the fund is carrying a large unintended
market bet mid-flight. `execution/PortfolioExecutor.java` coordinates
per-symbol executors with the two rules that only exist at basket level: a
**leg-balance band** (the ahead leg slows down until the lagging leg
catches up — never the reverse, since pushing a child past its own
schedule would break its benchmark) and a **per-interval budget** that
goes to the riskiest names first when it binds. Both rules only ever
*reduce* what a child sends, so each symbol's own schedule stays honest.

### 7. Signals: reading the tape

Short-horizon traders read pressure in the book itself:

- **Microprice**: a mid that leans toward the heavier side. If there are
  900 bid and 100 ask, the "fair" price sits nearer the ask — the small ask
  queue will likely be eaten first. Formula: size-weighted average of bid
  and ask, weighted by the *opposite* side's size.
- **Queue imbalance**: (bidSize − askSize) / (bidSize + askSize). Ranges
  −1..+1. Positive = buying pressure.
- **Order-flow imbalance (OFI)**: track *changes* — a rising bid or growing
  bid size is buying pressure; a falling ask or growing ask size is selling
  pressure. Sum these signed changes over a short window (Cont, Kukanov &
  Stoikov formalized this).
- **Trade imbalance**: who's initiating — are trades hitting the bid
  (sellers aggressive) or lifting the offer (buyers aggressive)?

*In this library:* `pricing/FairValueEngine.java` (microprice + drift),
`microstructure/FlowSignals.java` (OFI, queue and trade imbalance, decayed
over time so the signal reflects the last few seconds, not the whole day),
and `microstructure/SignalEngine.java` — the unified multi-symbol engine
that computes all of these plus streaming volatility, liquidity and
momentum in one place, for equities and FX alike.

**Can the weights be learned instead of guessed?** `SignalEngine.alpha()`
blends its ingredients with fixed weights you configure.
`microstructure/OnlineAlphaLearner.java` learns them instead — a small
online regression from the signals to the next interval's return. The
interesting part is not the regression; it is the **honesty mechanism**.
Any self-updating signal faces the same trap: it grades its own homework.
Two safeguards close that trap here:

1. Every prediction is recorded *before* the outcome updates the weights,
   and the rolling **out-of-sample IC** is computed on those predictions —
   so the learner is always scored on what it genuinely didn't know.
2. Timing: at 10:00:01 the signals already *contain* the 10:00:00→10:00:01
   move (momentum IS the move). Fitting current signals to the return that
   just happened is a "nowcast" that looks brilliantly predictive and
   predicts nothing. The learner therefore trains each return against the
   signals **snapshotted one interval earlier**.

Even then, the learned alpha stays silent (emits 0) until its live IC is
positive over a meaningful window — and "meaningful" is enforced, not
hoped: a lucky first hour doesn't count. This is the same discipline as §8's
walk-forward validation, shrunk to streaming scale.

**Two more signal ideas worth knowing.** Related instruments don't move
simultaneously: EURUSD leads EURJPY, index futures lead the cash basket —
the liquid instrument moves first and the follower echoes it a moment
later. `LeadLagEstimator` measures that lead live (which lag, how strong)
for cross-hedging and pricing. And not all days share a shape: option-expiry
days trade 2-3× normal volume with a violent close, half days compress the
U-curve, FX fixing days spike at the fix — `DayTypeProfiles` gives each
day type its own independently learned curves instead of one wrong average.

### 8. Alpha research: signals over days, not milliseconds

Zoom out from microseconds to months and the game is **factors**: measurable
stock characteristics that predict relative returns. Momentum (winners keep
winning, mildly), value, low volatility, quality. The research loop:

1. Compute the factor for every stock (a **cross-section**).
2. Check whether high-factor stocks outperform low-factor stocks next
   period. The rank correlation between factor and future return is the
   **IC** (information coefficient) — a *good* monthly IC is 0.05. Edges
   are tiny; that's why diversification and discipline matter.
3. Guard against fooling yourself. This is most of the job:
   - **Walk-forward validation**: fit on the past, test on the *unseen*
     future, roll forward. Never let the test period leak into the fit.
   - **Permutation tests**: shuffle the signal; if random shuffles score
     almost as well, your "edge" is noise.
   - **Survivorship bias**: if your historical universe contains only
     companies that exist *today*, you've quietly deleted every bankruptcy
     from history and your backtest is fiction. You need **point-in-time**
     universes: who was actually in the index on that date, including the
     ones that later died. (This library ships a test where a naive
     backtest overstates final equity 2× against a delisting — bias you
     can measure.)
   - **Costs**: spreads, fees, and **market impact** (empirically ~√size —
     trading 4× the size costs ~2× the impact per share).

*In this library:* the `alpha` package — `Factors`, `SignalEvaluator` (IC),
`AlphaValidation` (walk-forward, permutation), `AlphaBacktester`
(cost-aware), `PortfolioConstruction` (neutralization); `data/PointInTimeUniverse.java`
and `backtest/portfolio/` for survivorship honesty.

### 8b. Portfolio construction: turning forecasts into weights

A validated signal (§8) still leaves the hardest practical question
unanswered: *how much* of each thing do you hold? The classical answer
is Markowitz **mean-variance optimization**: feed in expected returns
and a covariance matrix, get back the weights with the best return per
unit of risk. `optimization/PortfolioOptimizer.java` implements it —
`maxSharpe` for the best risk-adjusted portfolio, `minVolatility` for
the safest fully-invested one, `efficientFrontier` for the whole
risk/return menu.

Then the dirty secret every practitioner learns in week one:
**max-Sharpe portfolios are error-maximizers.** Expected returns are
estimated with enormous noise (a year of daily data pins volatility
reasonably well and the mean terribly), and the optimizer *loves*
noise: the asset whose return you overestimated the most looks the
best, so it gets the biggest weight. You end up systematically
overweighting your worst estimation mistakes, and a tiny change in the
inputs flips the entire allocation. Three professional fixes, in
increasing order of ambition:

- **Stop forecasting returns.** **Risk parity** uses only the
  covariance matrix (the estimable input) and asks for the portfolio
  where every asset contributes *equally* to total risk —
  `RiskParityOptimizer.equalRiskContribution` solves it by fixed-point
  iteration, and `riskContributions` audits any existing book ("that
  60/40 portfolio is actually ~90% equity risk" is this one function
  call).
- **Anchor the forecasts.** **Black-Litterman** starts from the
  returns *implied* by market weights — if the market holds this
  portfolio, what expectations justify it?
  (`BlackLitterman.impliedEquilibriumReturns`) — then blends in your
  views with explicit confidences (`posteriorReturns`). No views =
  hold the market; a view tilts you away from it in proportion to how
  confident you claimed to be. Garbage-in becomes
  garbage-shrunk-toward-sensible.
- **Bound the damage.** `ConstrainedPortfolioOptimizer` adds per-asset
  weight caps and floors (`withBounds`) and a turnover penalty against
  current holdings (`withTurnoverPenalty`) — the optimizer is charged
  the real cost of getting from here to there, so it stops chasing
  noise-sized improvements through cost-sized trades.

How does `alpha/PortfolioConstruction.java` (§8) relate? It's the
*cross-sectional* cousin. When your "assets" are 500 stocks ranked by
a factor, you don't estimate a 500x500 covariance and optimize — you
size by z-score, neutralize sector and beta, and budget by inverse
volatility (`zScoreWeights` → `sectorNeutralize` → `betaNeutralize` →
`inverseVolBudget`). The `optimization` package is for the *other*
shape of problem: a handful of assets or strategies whose covariance
you can actually know.

*Real-life case:* a fund allocates across its 5 strategies. Naive
`maxSharpe` on their track records puts 80% into the strategy with the
best — i.e. luckiest — backtest. Risk parity spreads the book so no
single strategy dominates the risk. Black-Litterman starts from the
current capital allocation as the equilibrium and lets the PM express
one view ("stat arb beats trend by 2%, modest confidence") as a tilt
rather than an upheaval. The constrained pass caps any strategy at
35% and charges turnover — and the resulting rebalance is small enough
to actually execute.

### 8c. The full trading pipeline in one line

Everything a systematic desk does fits on one line:

**Alpha discovery → signal generation → nested validation → out-of-sample
scoring → selection → risk-managed sizing → portfolio constraints →
optimal execution.**

Each arrow is a place where money is made or quietly lost, and each stage
has a precise statement and a home in this library:

1. **Alpha discovery** — research produces a candidate set
   `A = {a_1, a_2, ..., a_K}` of alpha rules. Nothing is trusted yet: K
   is exactly the "number of trials" that every later honesty statistic
   must know about. *In this library:* `alpha.Factors` and
   `alpha.AlphaFactor` (the candidate rules), `alpha.AlphaContext` (the
   frozen data panel they compute on).
2. **Signal generation** — `s_t(k) = a_k(X_{t-1})`: the signal at time t
   may use only information known BEFORE trade time. The subscript
   `t-1` is the entire defense against look-ahead bias, and it is a
   structural property, not a hope: the context panel is frozen and the
   rule reads yesterday's row. *In this library:* `alpha.AlphaContext`
   (point-in-time gating via `withUniverse`), the causal-indicator
   convention in `indicators`.
3. **Nested validation** — outer test fold ∉ inner discovery fold ⇒ no
   data leakage: the data that picks the winner must never be the data
   that grades it. Purging removes training samples whose label windows
   overlap the test fold; the embargo absorbs the serial-correlation
   echo. *In this library:* `backtest.validation.PurgedKFold`,
   `backtest.validation.WalkForwardAnalyzer` (warm folds),
   `alpha.AlphaValidation`.
4. **Out-of-sample score** — `Q_k = Score(PnL_OOS, DD, Turnover,
   Stability)`: a candidate is a VECTOR of qualities, not one Sharpe.
   Drawdown (depth AND duration), turnover (costs eat gross alpha) and
   stability across folds all enter before anyone looks at the mean.
   *In this library:* `alpha.SignalEvaluator` (IC/IR/turnover),
   `alpha.AlphaBacktester` (cost-aware PnL),
   `backtest.DrawdownAnalytics`, `backtest.BenchmarkComparison`.
5. **Selection** — accept iff `Q_k > tau_Q`. Because K rules were tried,
   the threshold must be a MULTIPLE-TESTING threshold: the expected
   best of K zero-skill candidates, not zero. *In this library:*
   `backtest.validation.SharpeValidation` (deflated Sharpe),
   `GridSearchOptimizer.deflatedSharpeOfWinner`,
   `backtest.validation.OverfitProbability` (is the selection process
   itself noise-mining?).
6. **Risk-managed sizing** — `w_t(k) = s_t(k) / (sigma_t(k) + eps)`
   subject to `DD < D_max`, `Vol < sigma_max`: divide conviction by
   risk so a 2-sigma signal in a quiet name and a 2-sigma signal in a
   wild one commit the same risk budget; eps keeps a dead-vol estimate
   from minting an infinite position. *In this library:*
   `alpha.PortfolioConstruction` (inverse-vol scaling),
   `backtest.portfolio.PositionSizing`, `volatility` estimators for
   the sigma in the denominator.
7. **Portfolio constraints** — `sum_i |w_i| <= L_max` (gross leverage),
   `beta' w ~ 0` (market-neutral), `|w_i| <= w_max` (single-name cap):
   the book-level promises that survive whatever any single signal
   says. *In this library:* `alpha.PortfolioConstruction` (caps +
   beta/sector neutralization),
   `optimization.ConstrainedPortfolioOptimizer`,
   `risk.ComponentVar` (who owns the risk after the caps).
8. **Optimal execution** — `min over dq_t of Slippage + Impact +
   SpreadCost`, yielding `dq_t* =` the order schedule that reaches
   `w_t` at minimum expected trading cost. The portfolio you decided
   and the portfolio you hold differ by exactly this optimization.
   *In this library:* `microstructure.AlmgrenChriss` (the classic
   impact-vs-risk schedule), `execution.ImplementationShortfallScheduler`,
   `execution.BenchmarkExecutor` + `execution.AdaptiveSor` (working the
   schedule), `execution.PortfolioExecutor` (basket version),
   `microstructure.TransactionCostAnalyzer` (did we actually pay what
   the objective predicted?).

The one-line pipeline is also the debugging map: when live PnL
disappoints, walk the arrows backwards — execution paid more than the
objective said (stage 8)? the constraints bound (7)? sizing divided by a
stale sigma (6)? the threshold was too soft for K trials (5)? the score
ignored turnover (4)? the folds leaked (3)? the signal peeked (2)? or the
candidate was never real (1). Every post-mortem lands on exactly one
arrow. Diagram 19 in [DIAGRAMS.md](DIAGRAMS.md) draws this pipeline
end-to-end.

### 9. Risk: the systems that say no

Every order passes a **pre-trade risk gate** before it can leave the
building: size limits, notional limits (shares × price), position limits,
**price collars** (reject orders absurdly far from the market — "fat
finger" protection), and halt flags. This check sits on the critical path
of every single order, which is why this library measures it in
*nanoseconds*.

Above the per-order gate sit slower controls: a firm-wide **kill switch**
(one flag that stops everything), aggregate gross-notional circuit breakers
across all trading engines, and portfolio measures like **VaR**
(value-at-risk: "on 99% of days, we shouldn't lose more than X") — plus
backtests of VaR itself, because a risk model that's never checked against
reality is decoration.

*In this library:* `trading/HftRiskGate.java` (~3ns per check),
`trading/GlobalRiskAggregator.java` (firm-wide breaker + kill),
`risk/RiskMetrics.java`, `risk/VarBacktest.java` (Kupiec/Christoffersen
tests).

**The full market-risk workflow** goes far beyond a daily VaR number,
and the library maps the whole thing in
[MARKET_RISK.md](MARKET_RISK.md): four flavors of portfolio VaR that
disagree exactly when they should (`VarEngine` — a short-gamma book's
tail is worse than the Gaussian formula admits, and the delta-gamma
method knows it), Expected Shortfall beside every VaR (what the average
BAD day costs, not just the threshold), extreme value theory for
quantiles beyond the sample (`ExtremeValueTheory` — which refuses to
report a finite tail mean when the fitted tail has none), stress tests
including the regulator's favorite inverted question "what move breaks
us?" (`StressTester.reverseStress`, answered in closed form with an
implausibility score in sigmas), and the Basel/FRTB layer
(`FrtbEs`, `PnlAttribution`) — styled after the regulation, tested on
its arithmetic, and honest that certification is a program, not a
library.

### 9b. Regulation and surveillance: proving you behaved

Risk systems protect the firm from itself; regulatory analytics prove
to everyone else that clients and markets were treated fairly. Two
obligations follow every desk around, and both are measurement
problems this library implements:

**Best execution.** If you execute client orders, you must get terms
as good as reasonably available — and *demonstrate* it, per order,
per venue. `regulatory/BestExecutionAnalyzer.java` (MiFID II
RTS 27/28 spirit) accumulates order outcomes and its `report()`
answers the questions an auditor asks: fill rate, average slippage in
bps versus the arrival mid, median latency to fill, the fraction
executed at-or-better than arrival, and slippage broken down by venue
(the venue whose fills are consistently worst has some explaining to
do). The shared vocabulary for venue quality lives in
`regulatory/MarketQualityMetrics.java`: **quoted spread** (what was
posted), **effective spread** (what the taker actually paid — twice
the signed distance from the mid), **realized spread** (the same
measured against the mid a horizon *later* — the part the maker kept
after adverse selection), and **price impact** (effective is roughly
realized plus impact — the decomposition that says whether a wide
spread was compensation for risk or extraction from customers). Plus the
**order-to-trade ratio** — messages per executed trade, the metric
venues fine you on when your algo sprays cancels.

**Banging the close.** A genuinely fun surveillance example. Benchmark
fixes are computed from prices inside a window
(`regulatory/FixAnalyzer.calculateFix` — the median of mid samples,
WM/R style), so a participant with a large position *marked* at the
fix has an incentive to push the price during it. The signature is
threefold, and `FixAnalyzer.analyze` flags only when all three hold:
an outsized share of window volume, a price run-up into the fix
*aligned* with the participant's net flow, and reversion after the
window — impact that decays is the footprint of pressure, not
information. Notice this is §5's markout logic wearing a compliance
badge: the same physics that tells a maker it was run over tells a
surveillance officer who did the running.

### 10. Derivatives in five minutes

A **forward** is an agreement to trade at a future date at a price fixed
now. The fair forward price isn't a prediction — it's arbitrage arithmetic:
spot adjusted for the interest-rate difference between the two currencies
(in FX these adjustments are quoted as **swap points**). An **NDF**
(non-deliverable forward) does the same for restricted currencies, settling
the *difference* in USD.

An **option** is the right, without the obligation, to buy (**call**) or
sell (**put**) at a fixed **strike** price. Its value has two parts: what
it's worth if exercised now, plus **time value** — the chance things
improve before expiry. **Black-Scholes** (1973) prices this under
simplifying assumptions; its real, enduring gift is the **greeks** — the
sensitivities (delta: per $1 of spot; gamma: how delta changes; vega: per
vol point; theta: per day) that let you *hedge*. Market makers in options
aren't betting on direction: they hedge delta continuously and trade
**volatility**.

One assumption Black-Scholes gets visibly wrong: constant volatility. The
market prices crashes as more likely than the model says, so implied vol
varies by strike — the **smile**. FX quotes the smile in its own dialect
(ATM / risk-reversal / butterfly, by delta), and pricing **exotics**
(barriers, touches, digitals) consistently with the smile needs corrections
like **vanna-volga** — implemented here and cross-checked with Monte Carlo,
because two independent methods agreeing is the cheapest form of truth.

*In this library:* `pricing/` (BlackScholes, BinomialTree, VolSurface,
SabrModel, exotics, VannaVolga), `fx/` (SwapPointsCurve, Ndf, FxVolSurface),
`hedging/` (DeltaHedger and friends), `rates/` (curves, day counts — yes,
finance has multiple definitions of how long a year is).

### 10b. Rates: the price of time

A dollar today and a dollar in ten years are different assets, and the
exchange rate between them is an interest rate. Money can be lent for
any horizon, so there isn't one rate but a **yield curve** — a rate
per maturity. Every future cash flow in finance (bond coupons, swap
legs, a forward's settlement) is valued by asking the curve one
question: what is $1 at date T worth today? That number is the
**discount factor**.

You can't observe the curve directly — the market quotes *instruments*
(deposits, futures, par swaps), each bundling many cash flows.
**Bootstrapping** unbundles them: solve for the 1-year discount factor
from the 1-year instrument, use it to solve the 2-year, and walk out
the curve one maturity at a time. `rates/YieldCurve.java` implements
the classic version (`bootstrapAnnualParSwaps`), stores zero rates at
pillar tenors, and answers the three questions everything else asks:
`zeroRate(t)`, `discountFactor(t)`, and `forwardRate(t1, t2)` — the
rate the curve *implies* for lending between two future dates. Forwards
are (roughly) the curve's derivative, so a small kink in the zeros
becomes a large swing in the forwards — which is why desks judge a
curve build by its forwards, not its zeros.

A **bond** is a bundle of cash flows on a schedule, so a curve prices
it directly (`rates/BondPricer.priceFromCurve`) — or you compress the
whole curve into one number, the **yield**, and convert both ways
(`priceFromYield`, `yieldToMaturity`). The sensitivities are the desk
vocabulary: **duration** (`macaulayDuration` — the PV-weighted average
time to cash flow; `modifiedDuration` — percentage price change per
unit yield), **convexity** (the curvature that makes duration hedges
drift as rates move), and above all **DV01** (`dv01` — dollars lost
per one-basis-point rise, the unit rates traders actually speak).

But "the yield rises 1bp" is a fiction: the curve rarely moves in
parallel — it steepens, flattens, twists. **Key-rate durations** slice
DV01 across the curve: bump ONE pillar by 1bp, hold the rest, reprice,
repeat per pillar. `rates/KeyRateDurations.keyRateDv01s` returns that
vector, and `parallelDv01` is the consistency check — the slices must
sum back to the parallel number, and the tests pin that they do.

*Real-life case:* a desk is long $50M of a 10-year corporate bond and
wants to be flat rates (keeping only the credit exposure). The naive
hedge — sell 10-year futures matching the bond's total DV01 — is flat
to parallel moves only; a steepening that lifts the 10y point while
the 5y sits still leaves the "hedged" book bleeding. The KRD vector
shows where the risk actually lives: mostly at the 10y node, but a
meaningful slice at 5y and 7y from the coupons. Hedge each node with
the futures contract that drives it and the position survives
steepeners, flatteners, and butterflies — not just the parallel shift
that never happens.

**Day counts: how long is a year?** Real term sheets disagree —
ACT/360 (money markets), ACT/365 (GBP), 30/360 (US corporates),
ACT/ACT (governments) — and `rates/DayCount.yearFraction` implements
each exactly. This is not pedantry: accrue a coupon on ACT/365 when
the term sheet says ACT/360 and every accrual is misstated by ~1.4% —
a real, recurring source of PnL breaks between you and your
counterparty. `rates/BusinessCalendar.java` handles the other half of
date reality: payment dates that land on weekends or holidays roll by
convention (`Roll.MODIFIED_FOLLOWING` — forward, unless that crosses
month-end), settlement is T+n *business* days (`addBusinessDays`), and
FX pairs settle on the union of two centers' calendars (`union`).
`BondPricer.dirtyPrice`/`cleanPrice`/`accruedInterest` put it all
together with real dates.

**Making rates move: short-rate models.** For risk simulation and
curve-dependent pricing you need a model of how rates evolve.
`rates/ShortRateModels.java` implements the workhorse trio in closed
form: **Vasicek** (Gaussian mean reversion — tractable everywhere,
honest about its flaw: rates can go negative, which post-2015 is a
feature as much as a bug), **CIR** (the square-root diffusion keeps
rates non-negative — `cirFeller` tells you when they stay strictly
positive), and **Hull-White** (Vasicek with the drift fitted so the
model reprices *today's* curve exactly — the production choice,
because a rates model that disagrees with the discount curve it hedges
against is wrong by construction). Each has a simulation step for
Monte Carlo scenarios: `vasicekStep` is exact (the transition is
Gaussian — no discretization error), `cirStep` is full-truncation
Euler (never sources volatility from a negative rate).

### 11. Backtesting honestly

A **backtest** replays history against your strategy. The ways it lies to
you are well-catalogued, and this library implements defenses for each:
costs and impact (unrealistically free fills), look-ahead (using data you
couldn't have had), survivorship (§8), overfitting (§8), execution fantasy
(assuming every limit order fills — reality depends on queue position;
assuming FX orders always fill — reality includes last-look rejects, which
cluster on exactly the trades that were about to be profitable). The
**deflated Sharpe ratio** even adjusts your performance stat for how many
strategies you tried before finding this one.

*In this library:* `backtest/` (bar-level, execution-aware, tick-level with
queue positions, last look), `backtest/validation/`, `data/TickCapture` +
replay for deterministic tick-level history.

**Was the *search* honest?** A single backtest can be clean and the
process that selected it still rotten. Four more defenses:

- **Purged K-fold with embargo.** Ordinary K-fold cross-validation
  leaks on financial data, and the leak is subtle: a sample's LABEL is
  usually computed from bars that come *after* it (a "5-bar forward
  return"). Put bar 99 in training and bar 100 in test, and bar 99's
  label was computed from bars 100–104 — the model has already seen
  the test answer. `backtest/validation/PurgedKFold.splits` purges
  every training sample whose label window overlaps the test fold and
  adds an **embargo** buffer after it (serial correlation means
  features just past the fold still echo test-period information) —
  and refuses any split that leaves a fold with no training data,
  because silently training on nothing is how "great" fold scores
  happen.
- **Probability of backtest overfitting.** You didn't run one
  backtest; you ran 400 parameter sets and reported the best. The
  right diagnostic is CSCV
  (`backtest/validation/OverfitProbability.java`): split time into
  blocks, form every symmetric in-sample/out-of-sample combination,
  and ask how often the in-sample winner lands *below the median* out
  of sample. That fraction is the **PBO** — below 0.1 the selection is
  finding something real; at 0.5 it's pure noise-mining and the "best"
  backtest is meaningless however good it looks.
- **The deflated Sharpe, wired in.** The deflated Sharpe ratio above
  isn't a separate ritual here — `GridSearchOptimizer.deflatedSharpeOfWinner`
  takes the ranked grid-search results and judges the winner's Sharpe
  against what the best of N zero-skill trials would have scored
  anyway. The search that created the multiple-testing problem hands
  you its own haircut.
- **Compared to what?** A standalone Sharpe answers "was this good?";
  an allocator asks "was this good *compared to just buying the
  index*?" — `backtest/BenchmarkComparison.java` computes beta (how
  much of the strategy is the benchmark in disguise), Jensen alpha,
  tracking error, information ratio, and up/down capture. And
  "max drawdown 18%" hides the number that actually fires clients:
  how LONG the pain lasted. `backtest/DrawdownAnalytics.java` walks
  every peak-to-recovery episode — depth, duration, time under water —
  and surfaces a drawdown still open at series end instead of hiding
  it, because that is often the honest state of the strategy today.

### The desk playbooks — pairs, toxicity, the central risk book, rolls

Four more things real desks do every day, each a small world of its own.
These build on everything above, so read §1–§11 first.

**Pairs trading, or: betting on a rubber band.** Some price
relationships are tethered — two share classes of one company, a stock
and its ADR, cash equities and index futures, on-the-run vs off-the-run
bonds. When the SPREAD between them stretches, you sell the expensive
leg, buy the cheap one, and wait for the tether to pull them back.
Three questions decide whether this is a strategy or a slow-motion
accident:

1. *Is the spread actually tethered?* A *cointegration test* asks
   whether the spread is statistically stationary — two random walks
   can look correlated for years and then never come back.
2. *How fast does it snap back?* Fit an **Ornstein-Uhlenbeck** process
   to the spread: the fitted **half-life** (how long the spread takes
   to close half its gap) is your expected holding period, and the
   **z-score** (how many stationary standard deviations you are from
   the mean) is your entry and exit signal. A 200-day half-life is not
   a trade — it's an index fund with extra steps. And a spread that
   shows NO mean reversion in-sample must be refused, not fitted:
   fitting a rubber-band model to a random walk is how pairs desks die
   (this library's fit literally throws in that case).
3. *How do you get in without owning half a trade?* The moment you own
   one leg without the other, you're not a pairs trader — you're just
   long (or short) in disguise. **Legging risk** is THE execution risk
   of spread trading: work the ILLIQUID leg patiently (it's the
   constraint), let the LIQUID leg chase each fill at the spread
   ratio, and cap the imbalance HARD — at the cap, stop adding risk
   entirely and cross the hedge leg to get flat, whatever it costs.

*Real-life case:* a desk sees two dual-listed shares 2.5 z-scores
apart with a fitted half-life of 8 days. Entry: sell the rich listing,
buy the cheap one, ratio-locked, legging cap at 5% of the position.
Exit: z back under 0.5, or the cointegration breaks (a takeover
announcement is a rubber band CUT — exit everything, the tether is
gone).

*In this library:* `hedging/CointegrationTest.java` (question 1),
`microstructure/OrnsteinUhlenbeck.java` (question 2 — κ/θ/σ, half-life,
z-score, and the refusal), `execution/SpreadExecutionAlgo.java`
(question 3 — the legging cap), cookbook recipe 15 runs the whole
chain. Two refinements the desks add:
`microstructure/VarianceRatio.java` answers the question BEFORE
question 1 — is this series trending, mean-reverting, or a random
walk? (under a random walk variance grows linearly with horizon, so
the ratio is 1; mean reversion cancels, momentum compounds) — and
`microstructure/KalmanBeta.java` replaces the static hedge ratio with
a time-varying one: relationships DRIFT, a β fitted on last year is
stale by spring, and the filter tracks the current β while a
full-sample OLS averages the drift into a number that was never true
on any single day (the test demonstrates exactly that).

**Flow toxicity, or: knowing when to stop quoting.** §5 explained
adverse selection — the people most eager to trade with you know
something. **VPIN** turns that into a live dial: bucket trades by
VOLUME (informed trading compresses clock time, but volume time it
cannot hide from), score each bucket's buy/sell imbalance, average the
last fifty buckets. Balanced two-way flow reads near 0; one-sided,
informed flow reads toward 1 — famously elevated in the hour before
the 2010 flash crash. A market maker watching VPIN climb doesn't argue
with it: widen the spread, cut the quote size, or stop quoting. Being
the last one quoting into informed flow is how you buy the top tick of
every move.

And when you have no quote data at all — a 20-year backtest, an
emerging market, history before your capture started — you can still
ESTIMATE liquidity from bars alone: Roll's estimator reads the spread
out of bid-ask bounce (trade prices ping-ponging leave a negative
autocorrelation fingerprint), Corwin-Schultz reads it from daily
high-low ranges, and Amihud's ratio (|return| per dollar traded) ranks
which names punish size. Estimators, not measurements — rank with
them, don't mark books with them.

*In this library:* `microstructure/Vpin.java`,
`microstructure/TradeClassifier.java` (feeds it aggressor sides when
the venue doesn't say), `microstructure/LiquidityMeasures.java`,
cookbook recipe 17 wires VPIN into a quoting defense.

**The central risk book, or: netting before hedging.** Walk a large
firm's trading floor: the cash equities desk just bought 2M of an
index's risk from a client, and three desks away the ETF desk sold 2M
of nearly the same risk. If both desks hedge with the street, the firm
paid the spread TWICE for risk it never net had. A **central risk
book** fixes this by seeing every desk's position in ONE factor space:
equity deltas per symbol (an option's delta and cash shares land on
the SAME factor), FX exposure per CURRENCY (so EURUSD spot, a EURUSD
option's delta, and an NDF's forward all net at the currency level),
vega and gamma per underlying. What survives the netting is the only
risk worth paying to hedge — and even then, the CRB hedges only the
EXCESS beyond its warehouse band, because inventory that will net
against tomorrow's flow is an asset, not a problem.

The CRB is also a BUSINESS, with three revenue mechanics you can now
read as code: it *internalizes* client flow that reduces its risk (and
gives the client back part of the saved spread — being worth trading
with is the moat), it *skews* its quotes to attract the flow it wants
(long inventory → shade both quotes down → the ask becomes the
attractive side), and it routes its own hedges through ITSELF first —
the firm's own offsetting inventory is a dark pool with zero cost and
zero information leakage. The close-of-day question is one number:
did the spread we captured pay for the hedging we did?

*Real-life case:* a pension unwinds 18M of index risk through the desk
in six tickets. The warehouse absorbs it (that's the service), the
equity band breaches, the cost-aware hedger notices the cheap ES-proxy
future can't satisfy a per-symbol band and escalates to the direct
program trade, the hedge routes to a clean midpoint pool while the
15bps-markout pool gets nothing (internal crossing is the router's
standing FIRST priority — that day there happened to be nothing
crossable) — and the day still nets positive after every cost. That
exact day runs in `crb/CrbRealWorldScenarioTest.java` (under
`src/test`).

*In this library:* the whole `crb/` package —
[CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md) is the guided tour,
cookbook recipe 14 the runnable day.

**Rolls and anti-gaming, or: the trades everyone can see coming.**
Two execution problems are special because your intentions are
PREDICTABLE. First, the futures roll: every futures position must
migrate from the expiring contract to the next one, everyone knows
roughly when, and liquidity migrates on a well-known S-curve — roll
too early and you pay wide back-month spreads, too late and you join
the last-day scramble. The answer is to roll IN STEP with the
migration, executing each day's slice as a calendar spread. Second,
the metronome problem: a TWAP that fires identical child orders on an
exact clock is detected by predators within minutes, and every
subsequent child gets leaned on. The fix is seeded randomness — jitter
child sizes and times enough to kill the pattern while preserving the
total EXACTLY and keeping every child auditable (same seed, same
schedule: compliance can replay why each child fired when it did).

*In this library:* `execution/FuturesRollAlgo.java` (the migration
curve, with a curve that doesn't end at 100% rejected as the delivery
risk it is), `execution/AntiGamingJitter.java`,
`execution/OrderPlacementPolicy.java` (the smallest repeated decision
— post passively or cross — as expected-cost arithmetic instead of
habit).

**The volatility zoo — six kinds, one map.** "Volatility" is six
different things wearing one word, and confusing them is expensive:

1. **Historical volatility** — how much the price DID move, measured
   from past returns: `risk/RiskMetrics.annualizedVolatility` (the
   plain standard deviation) and `volatility/EwmaVolatility` (recent
   moves weighted more — the RiskMetrics λ=0.94 classic).
2. **Implied volatility** — the market's forecast, read backwards out
   of an option's PRICE: `pricing/BlackScholes.impliedVol`,
   `pricing/Black76.impliedVol`, and the smile structures
   (`pricing/VolSurface`, `fx/FxVolSurface`, `pricing/VannaVolga`).
   Historical looks back; implied is what people are PAYING for.
3. **Realized volatility** — what actually happened over a window,
   built from intraday returns: `microstructure/JumpRobustVolatility`
   (and its key trick: bipower variation separates a headline JUMP
   from a genuine volatility regime), forecast by `volatility/HarRv`.
4. **Market volatility** — the whole market's expected turbulence,
   famously tracked by the VIX, the "fear index":
   `volatility/VolatilityIndex` computes it model-free from an option
   chain — every out-of-the-money option contributes its 1/K² slice
   to a portfolio whose payoff IS variance, so the portfolio's price
   reveals the market's expectation with no pricing model assumed.
   The put skew is IN the index, which is why VIX sits above ATM
   implied vol.
5. **Idiosyncratic volatility** — the company's own story: earnings,
   management, product launches. It diversifies away across names.
6. **Systematic volatility** — the economy's story: rates, inflation,
   macro shocks. It does NOT diversify away — only index hedges
   remove it. `volatility/VolatilityDecomposition` splits any stock's
   variance EXACTLY into 5 + 6 via the single-factor regression
   (β²·market variance + residual); the R² is the systematic share —
   a utility near 0.7 is mostly a market proxy, a biotech near 0.05
   is mostly its own story, and the two need entirely different
   hedges.

---

## Part II — The Technology

### 12. The latency ladder, or: what a nanosecond buys

- 1 **ns**: light travels ~30cm; a modern CPU core executes a few
  instructions.
- ~100 ns: a main-memory access (this is why cache locality matters).
- ~1 **µs** (1,000 ns): this library's *entire* tick-to-order path, twice over.
- ~50 µs: a fast Java garbage-collection pause (ZGC-class; older collectors
  pause for *milliseconds*) — even the fast one is 100× our whole budget.
- ~1 **ms**: network round trip within a city; an OS deciding to run
  another thread for "a moment."

Two lessons fall out. First, at these scales *the enemy is anything
irregular*: a code path that's usually 500ns but occasionally 50µs is worse
than one that's always 800ns, which is why we obsess over **tail latency**
(p99, p99.9) rather than averages. Second, some slowness you cannot code
away (the OS preempting your thread, the GC) — you can only *avoid
triggering it*, measure it, and attribute it honestly.

*In this repo:* `docs/ULTRA_LOW_LATENCY.md` maps four tiers of latency work,
from pure-JDK (this library) down to kernel bypass and FPGAs — knowing the
boundary is the skill.

### 13. Garbage collection, and why we allocate nothing

Java normally manages memory for you: you create objects, the **garbage
collector** (GC) later finds the dead ones and reclaims them — occasionally
pausing your program to do so. For a web app, a few milliseconds of pause
is invisible. For us it's catastrophe (see the ladder above).

The cleanest solution: **never give the GC anything to do**. If the hot
path allocates zero objects after startup, the GC never runs. So hot-path
code here pre-allocates everything up front (arrays, buffers, pools) and
then only *reuses*: no `new`, no autoboxing (`Long` vs `long` — the
capital-L one is an object!), no Strings, no iterators, no lambdas that
capture variables.

And because claims are cheap, it's **proven two ways**: (1) each hot class
has a test that reads the JVM's per-thread allocation counter before and
after 500,000 operations and asserts ~zero bytes; (2) benchmarks run under
**Epsilon GC** — a special JVM collector that *never collects* and simply
crashes when memory runs out. A million operations under Epsilon without a
crash is not an argument, it's a proof.

*Try it:* open any `*Test.java` with "AllocationFree" in a method name —
the pattern is 10 lines and you'll recognize it everywhere afterwards.

### 14. Data structures for speed

**Arrays of primitives beat objects.** A `double[]` is one contiguous block
of memory the CPU prefetches beautifully. An array of `Quote` objects is an
array of *pointers* to objects scattered across the heap — every access a
potential cache miss. So hot classes here store "an order" as *slot i
across six parallel arrays* (`nodeId[i]`, `nodeQty[i]`, `nodePrev[i]`…)
rather than as an Order object. Less pretty; measurably faster.

**Ring buffers** pass data between threads. A ring is a fixed-size array
with a write cursor and a read cursor that wrap around. The producer writes
and advances; the consumer reads and advances; power-of-two sizes make the
wrap a bit-mask. No locks, no allocation, and if the consumer falls behind
the ring fills up and the producer **drops and counts** — in trading you'd
rather skip a stale tick than block the feed (LMAX's "Disruptor" made this
pattern famous).

**SPSC and single-writer.** Every ring here is single-producer,
single-consumer, and every hot structure has exactly one writing thread.
The moment two threads write shared data you need coordination
(locks/atomics), and coordination costs. Architecture beats cleverness:
give each thread its own data.

**Open-addressing hash maps.** `HashMap<Long,Integer>` allocates a node per
entry and boxes every key. The primitive alternative: two flat arrays
(`long[] keys`, `int[] values`); hash the key, and if that slot is taken,
try the next one (**linear probing**). Deletion has a classic subtlety
(you can't just null a slot mid-probe-chain — this library uses
**backward-shift deletion**, worth reading with a coffee).

**Bitmaps.** "Which of my 65,536 price levels have orders?" One bit each =
1,024 longs. "Next occupied level above X" becomes a hardware instruction
(`Long.numberOfTrailingZeros`) instead of a loop.

**Flyweights.** To read a binary message, don't *parse* it into an object —
lay a "view" over the bytes and decode fields on demand, directly from the
buffer. Zero copies, zero allocation.

*In this library:* `marketdata/TickRingBuffer.java` (ring),
`orderbook/BookPrimitives.java` (bitmap scans + open addressing, shared by
the two hot-lane books: `HftOrderBook` and `L3BookBuilder` — the readable
`OrderBook` deliberately uses none of this), `sbe/*Flyweight.java` and
`fix/FixMarketDataView.java` (flyweights), `orderbook/HftOrderBook.java`
(all of it at once).

### 15. Threads and the memory model, in plain words

Modern CPUs and compilers reorder and cache aggressively. If thread A
writes `position = 5` and thread B reads it, B may see the *old* value —
indefinitely — unless the code establishes ordering. Java's rules for this
are the **Java Memory Model**.

The idiom used here is **release/acquire**: the writer publishes with a
*release* store ("everything I wrote before this is visible to whoever
acquires"), the reader reads with an *acquire* load. It's cheaper than
locks and expresses exactly what a ring buffer needs: *the cursor moved,
therefore the data behind it is ready.* In Java you spell this with
**VarHandles** (`setRelease`/`getAcquire`).

There's an honest war story in the docs: the risk gate's checks measured
~1ns single-threaded, but that speed relied on the JIT hoisting reads out
of loops — unsafe once other threads write fills concurrently. Making it
correct (per-element acquire/release) made it ~3ns. The library took the
3ns and documented why: **correct-but-3ns beats fast-but-wrong**, and
claims got updated everywhere. That's the culture the codebase tries to
teach.

### 16. Measuring latency without lying

Averages hide disasters — one 40ms pause in a million 400ns operations
barely moves the mean. Latency work uses **percentiles**: p50 (median),
p99, p99.9, max. Collecting them must itself be allocation-free, so
`util/LatencyRecorder.java` implements a log-scaled histogram (HdrHistogram
style): nanosecond resolution where it matters, fixed memory.

Java has a warm-up quirk: code starts interpreted and gets compiled by the
**JIT** only after thousands of executions. Benchmarks must warm up first
or they measure the interpreter. And when a benchmark *does* show a 1.6ms
spike, whose fault is it — your code or the OS? `util/HiccupMonitor.java`
answers by running a thread that does *nothing* but sleep 1ms and check how
late it wakes up: if the do-nothing thread also stalled 1.6ms, the
*platform* owns that tail, not your code. Every benchmark here prints both.

*Try it:* `examples/HftLatencyBenchmark.java` and friends — each is a
single-file, readable benchmark you can run from the CLI.

### 17. Protocols: how an order reaches the exchange

**FIX** is finance's venerable text protocol: `35=D|55=EURUSD|54=1|38=1000000`
(tag=value, delimited by an invisible byte-1 character). Human-debuggable,
universally supported, verbose. Tag 35 is the message type, and three of
them carry most of trading: `35=D` (NewOrderSingle — "I want to trade"),
`35=8` (ExecutionReport — the venue's answer), `35=W`/`35=X` (market data
snapshot / incremental — the prices).

**The life of an order, end to end.** Follow one BUY from decision to
position:

1. *The strategy decides.* A signal crosses a threshold (§7); the
   pre-trade risk gate (§9) approves in ~3ns.
2. *Encode.* The order becomes a `35=D` NewOrderSingle. Session lane:
   `FixSession.sendNewOrderSingle(clOrdId, symbol, side, qty,
   limitPrice, tif)` builds it with the String-based `FixMessage` —
   fine for its purpose. Hot lane: `fix/FixOrderEncoder.java` writes
   the same message with zero allocation — one reusable byte buffer
   (body first, then the `8=FIX.4.4|9=len|` prefix written *backwards*
   in front of it), digits rendered directly (no `Long.toString`),
   symbols pre-registered as bytes, the timestamp's date part cached
   until the UTC day rolls. Every encoded message is round-tripped
   through the validated parser in tests — fast and correct, proven
   together.
3. *The wire.* TCP to the venue's gateway, over a session (below).
4. *The venue matches.* Your order meets someone's resting order in
   the venue's book — exactly the mechanics of §2, run by them
   instead of you.
5. *The answer comes back.* An ExecutionReport (`35=8`): `execType`
   `'0'` = accepted, `'F'` = fill, `'8'` = rejected. The session
   surfaces it via `Listener.onExecutionReport` as a typed
   `fix/ExecutionReport.java` (cumQty, leavesQty, avgPrice); the hot
   lane reads the same bytes through the `fix/FixExecReportView.java`
   flyweight without materializing anything.
6. *Position updated.* The **ClOrdID** you chose in step 2 is the
   thread that ties every report back to its order — correlation is
   yours to manage, not the venue's.

**The session layer** is where the unglamorous reliability lives —
`fix/FixSession.java` implements all of it. A session starts with a
**Logon handshake** and stays alive by **heartbeats**: send one after
each idle heartbeat interval, and if nothing arrives for 1.5 intervals,
send a **TestRequest** ("are you alive? prove it") — a peer that stays
silent to 2.5 intervals is declared dead and disconnected. That
escalation matters because TCP won't tell you about a dead peer for
minutes; a trading system needs to know in seconds, because *its
orders may still be alive at the venue*.

Every message carries a **sequence number**. When the receiver sees
seq 4,835 while expecting 4,833, it sends a **ResendRequest** for the
gap; the peer replays application messages flagged
`PossDupFlag(43)` (so a fill is never booked twice) and coalesces
admin messages into a `SequenceReset-GapFill(4)` — nobody needs old
heartbeats back. A `FileSessionStore` persists sequence numbers and
sent messages so recovery works across a process restart, not just a
dropped socket. This dance is the part that pages you at 3am if done
wrong: a desync at reconnect means you don't know which of your
orders the venue thinks exist.

**The inbound side: market data.** Three dialects, one trade-off:

- **ITCH** (`marketdata/ItchCodec.java`): the L3 binary style —
  fixed-offset messages, native integers, millions per second (§3).
- **FIX market data** (`fix/FixMarketDataView.java`): `35=W` full
  snapshots and `35=X` incremental updates — the lingua franca of
  e-FX bank streams. It's still tag=value text, so this library reads
  it with a flyweight: one pass over the framed bytes into
  preallocated primitive arrays, entry order preserved (for tiered LP
  streams, position IS the tier).
- **SBE-style binary** (`sbe/` — `QuoteFlyweight`, `TradeFlyweight`,
  `OrderFlyweight`, with `BinaryMarketDataClient` feeding the bus):
  fixed offsets and native-endian integers. Reading a price is a
  *cast*, not a parse.

Why binary wins on the hot path: tag=value must scan every byte to
find delimiters, branch per tag, and convert ASCII digits to numbers;
a fixed-layout binary message needs none of that — field offsets are
compile-time constants. Same information, a fraction of the work; and
since a flyweight makes even the text path allocation-free, the
difference binary buys is work, not garbage.

Everywhere on these paths, prices travel as **scaled longs** — 1.08505
becomes mantissa 108505 with 5 decimals — because `double` cannot
represent most decimal fractions exactly, and rounding errors in
prices are how you fail audits. Feed-to-order never touches a double.

*Real-life: the 500ns after your strategy says BUY.* Signal update on
the incoming tick: ~50ns. Risk gate: ~3ns. FIX encode via
`FixOrderEncoder`: ~100ns of digit-writing into a buffer that already
exists. Handoff to the venue thread through a ring buffer (§14): one
release store. Socket write: the kernel takes over. Notice what is
*absent* from that path: no String, no parsing, no allocation, no
lock, no syscall until the very last step — every section of Part II
was about removing exactly those.

### 18. Scaling out and staying safe

One core, one engine has a throughput ceiling. The scaling answer here is
**shared-nothing sharding**: run N independent engine stacks (own bus, own
gate, own venue thread), partition symbols across them, and let *nothing*
be shared on the hot path — because one shared `synchronized` counter makes
N shards slower than one (that experiment is written up in
ULTRA_LOW_LATENCY.md; the fix was per-shard, cache-line-spaced counters).

What legitimately *must* span shards — firm-wide risk — runs as a slow
observer: a monitor thread sums positions across all gates every few ms
and flips per-gate **kill switches** on breach. The hot path only ever
*reads one boolean*. Fast paths stay fast; safety stays global.

*In this library:* `trading/ShardedTradingEngine.java`,
`trading/GlobalRiskAggregator.java`.

**Surviving the overnight.** Everything the models *learn* — volume
curves, venue fill rates, alpha weights — lives in memory, and a desk
that relearns it from zero every morning trades half-blind until lunch.
The fix is a **checkpoint**: at end of day, write every model's learned
state into one file; at the next session start, restore it. The two
details that make this production-grade rather than a toy: the save is
**atomic** (written to a temp file, then renamed over the old one — a
crash mid-save can corrupt nothing, yesterday's file survives), and the
restore is **honest about time** (learned state comes back; intraday
state — today's running totals, a pending spread spike — deliberately does
not, because it belonged to yesterday). *In this library:*
`persist/Checkpoint.java`, with `writeState`/`readState` on every model
that learns across days.

### 19. How this codebase tests itself

Worth studying as a checklist for your own projects:

- **Allocation-counter tests** — every zero-alloc claim, proven (§13).
- **Model-based equivalence** — the fast order book vs the readable one,
  identical random inputs, demand identical state. The readable version is
  the spec.
- **Property tests** — e.g. "a FOK either fills entirely or changes
  nothing," asserted over thousands of randomized books; "symmetric last
  look rejects both directions 50/50" asserted over randomized moves.
- **Differential tests** — the optimized NBBO fast path vs brute-force
  recomputation, 20,000 random updates.
- **Load floors** — throughput regression tripwires set ~20× below measured
  desktop numbers: CI fails if the pipeline ever gets *dramatically*
  slower, without flaking on slow runners.
- **Honest limits as tests** — the load test *budgets* dropped messages
  under OS preemption instead of pretending an unpinned desktop can promise
  zero drops. Tests encode what the platform can actually guarantee.

---

## Part III — Your learning path

**Run things (30 minutes).**

```bash
git clone https://github.com/AshJha0/Quant-Finance-Library && cd Quant-Finance-Library
mvn package                        # builds + runs all tests — watch them pass
java -cp target/classes com.quantfinlib.examples.QuickStartDemo
java -cp target/classes com.quantfinlib.examples.LiveTradingDemo   # live Binance ticks → paper trading → browser dashboard
```

**Read code in this order** (each step uses the previous ones):

1. `core/Bar.java`, `indicators/StreamingIndicators.java` — the simplest lane.
2. `orderbook/OrderBook.java` — matching, readable (§2).
3. `marketdata/TickRingBuffer.java` — your first lock-free structure (§14).
4. `trading/HftRiskGate.java` — risk + memory model (§9, §15).
5. `orderbook/HftOrderBook.java` — everything from §14 in one file.
6. `marketdata/L3BookBuilder.java` + `fx/FxTierBook.java` — the two market
   structures side by side (§3, §4).
7. `backtest/Backtester.java` → the `alpha` package (§8, §11), then the
   search-honesty tools: `backtest/validation/PurgedKFold.java` →
   `OverfitProbability.java` → `backtest/BenchmarkComparison.java` +
   `DrawdownAnalytics.java` (§11).
8. `optimization/PortfolioOptimizer.java` → `RiskParityOptimizer.java` →
   `BlackLitterman.java` → `ConstrainedPortfolioOptimizer.java` (§8b) —
   forecasts into weights, and why the naive optimizer can't be trusted
   with your forecasts.
9. `microstructure/VolumeCurve.java` → `SignalEngine.java` →
   `OnlineAlphaLearner.java` → `execution/BenchmarkExecutor.java` →
   `PortfolioExecutor.java` → `persist/Checkpoint.java` — the execution
   intelligence stack from live inputs to basket schedule to overnight
   (§6, §7, §18).
10. The desk playbooks (end of Part I): `microstructure/OrnsteinUhlenbeck.java`
    → `execution/SpreadExecutionAlgo.java` (a pairs trade end to end),
    `microstructure/Vpin.java` (toxicity), then the whole `crb/` package
    with [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md) as the guide and
    `crb/CrbRealWorldScenarioTest.java` (under `src/test`) as a
    realistic trading week you can step through in a debugger.
11. The rates lane (§10b): `rates/YieldCurve.java` → `BondPricer.java` →
    `KeyRateDurations.java` → `ShortRateModels.java` — curve to bond to
    hedge to simulation, each file under 200 lines.
12. The risk stack: [MARKET_RISK.md](MARKET_RISK.md) maps all fourteen
    steps from market data to Basel/FRTB; `risk/VarEngine.java` →
    `risk/StressTester.java` → `risk/FrtbEs.java` is the reading spine,
    and `risk/MarketRiskTest.java` (under `src/test`) shows every
    formula pinned by hand. Then the compliance view (§9b):
    `regulatory/MarketQualityMetrics.java` and `regulatory/FixAnalyzer.java`
    are both readable in one sitting.
13. Connectivity (§17): `fix/FixSession.java` (with `fix/FixSessionTest.java`
    under `src/test` — watch a logon, a fill and a gap-fill happen) and
    `fix/FixOrderEncoder.java`, the same message written the readable way
    and the fast way.
14. `docs/COOKBOOK.md` — one hundred end-to-end recipes to modify and re-run.
15. Part IV below — once the above feels familiar, test yourself: 500 real
    quant/trading/HFT practice questions, each answered and tied back to
    a class you've now read.

**Exercises** (in rough order of difficulty):

1. Add a new streaming indicator (e.g. rate-of-change) to
   `StreamingIndicators` with a test proving it matches a batch
   computation.
2. Write an allocation-counter test for it (copy any existing one).
3. `OrderBook` already has `mid()`; `HftOrderBook` deliberately doesn't.
   Add it (from `bestBidTick`/`bestAskTick`), then extend the equivalence
   test so both books' mids are compared after every random operation.
4. Build a tiny strategy in the backtester: buy when queue imbalance > 0.5
   on replayed ticks. Measure how costs change the result.
5. Run `HftBookBenchmark` before and after replacing `HftOrderBook`'s
   bitmap scan with a linear loop — *measure* what the bitmap buys.

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

One closing thought. This library's recurring lesson isn't a data structure
or a formula — it's a habit: **measure, don't assume; prove, don't claim;
and when reality imposes a limit, document the limit instead of hiding
it.** That habit transfers to every system you will ever build.

## Part IV — The exercise room

500 exercises — the questions quant, algo-trading and HFT desks actually ask —
each answered the way a strong candidate answers it, then tied to the
class in this library that implements the answer, so every answer is
**runnable**: read the answer, then read the code, then re-derive one
number yourself — that's the preparation that survives follow-up
questions. Grouped by round, the way desks group them: the quant/math round,
the derivatives round, the microstructure/execution round, the risk round,
and the low-latency engineering round — first a core pass through each
(Q1–35), then the deeper follow-on material (Q36–500): more quant
and math, rates and curves, volatility and options theory, risk, the
derivatives desk round, market structure and execution, the backtesting
and research process, and low-latency systems design. All class paths are
relative to `src/main/java/com/quantfinlib/`.

---

## Round 1 — Quant & math

### 1. "Mean-variance optimization is elegant. Why does nobody run it raw?"

Because the optimizer is an **error maximizer**. It treats the inputs —
expected returns and the covariance matrix — as exact, and loads up hardest
exactly where the estimates are most wrong: assets with flukishly high
sample means, directions the sample covariance wrongly calls near-riskless.
Expected returns are the worse offender (their estimation error dwarfs
covariance error), and max-Sharpe is the most sensitive objective to them.
The standard cures: shrink the covariance toward a structured target
(Ledoit-Wolf), and replace raw return forecasts with equilibrium-anchored
ones — Black-Litterman starts from the returns implied by the market
portfolio and blends views in with explicit confidences, so an absent view
means "hold the market," not "bet on noise."

*In this library:* `optimization/PortfolioOptimizer.java` (`maxSharpe` — the
naive optimizer, so you can watch it misbehave),
`risk/CovarianceShrinkage.java` (the Ledoit-Wolf fix),
`optimization/BlackLitterman.java` (the posterior formula, one screen).

### 2. "Here are par swap rates for 1–10 years. Build me a discount curve."

Bootstrap it, one maturity at a time. A par swap rate is the fixed rate that
makes the swap worth zero at inception, which for annual fixed payments
means `s_n · Σ df_i + df_n = 1`. The 1-year quote pins `df_1` directly; each
subsequent quote adds exactly one unknown discount factor, so you solve
forward: `df_n = (1 − s_n · Σ_{i<n} df_i) / (1 + s_n)`. The result reprices
every input instrument exactly — that's the defining property of a
bootstrap, and the first thing to test. Follow-up you should expect: "what
are level, slope and curvature?" — the three Nelson-Siegel factors (long-end
level, short-minus-long slope, mid-curve hump) that describe virtually all
curve movement, which is why central banks publish curves in that form.

*In this library:* `rates/YieldCurve.java`
(`bootstrapAnnualParSwaps` — the loop above, and the repricing test), then
`rates/NelsonSiegel.java` (shape vs. exact repricing: the javadoc contrasts
the two on purpose).

### 3. "Macaulay vs modified duration vs DV01 — and why do traders say convexity is in the bondholder's favor?"

Macaulay duration is a **time**: the PV-weighted average wait for your cash
flows, in years. Modified duration = Macaulay / (1 + y/f) is a
**sensitivity**: `dP/P ≈ −modDur · dy`. DV01 is the same derivative in
**currency per basis point** — and it's what desks actually use, because
DV01s add across positions while durations must be value-weighted.
Convexity is the second-order term, and for a plain bond it is positive:
the price-yield curve bends upward, so the bond gains more when yields fall
than it loses when they rise by the same amount. A symmetric yield shake
therefore has positive expected P&L — which is exactly why convexity isn't
free: you pay for it in yield (carry).

*In this library:* `rates/BondPricer.java` — all four numbers with the
javadoc spelling out what each is FOR; the test re-derives duration and
convexity by finite differences.

### 4. "Write down GARCH(1,1). What does α + β mean, and what does your 10-day forecast look like?"

`h_t = ω + α·r²_{t−1} + β·h_{t−1}`. α is the reaction to yesterday's shock,
β the memory of yesterday's variance, and **α + β is persistence**: how
slowly volatility shocks decay. The unconditional variance is
`ω / (1 − α − β)`, and the k-step forecast decays geometrically from
today's variance toward it — `h_{t+k} − h∞ = (α+β)^k (h_t − h∞)`. With
typical daily fits α + β ≈ 0.98, so vol shocks take months to fade; that's
volatility clustering, quantified. Expected follow-up: "what does GARCH miss
on equities?" The **leverage effect** — down moves raise future variance
more than up moves. GJR-GARCH adds a term γ that only fires on negative
returns; on equity indices the fitted γ is typically *larger* than α, so
asymmetry isn't a refinement, it's most of the effect. On FX, γ ≈ 0.

*In this library:* `volatility/Garch11.java` (fit + multi-step forecast),
`volatility/GjrGarch11.java` (the javadoc makes exactly the γ-vs-α point).

### 5. "Vasicek vs CIR. What's the Feller condition and why do I care?"

Both are mean-reverting short-rate models: `dr = a(b − r)dt + σ·dW`. Vasicek
has constant volatility, so it's Gaussian and tractable — bond prices in
closed form — but rates can go negative (a feature or a bug depending on the
decade). CIR scales the noise by `√r`: volatility dies as the rate
approaches zero, and if the **Feller condition** `2ab ≥ σ²` holds, the rate
stays strictly positive — mean reversion pulls up faster than diffusion can
push through zero. You care because it's a *parameter constraint you must
check after calibration*: a fitted CIR that violates Feller will happily
generate zero-touching paths in your simulation. Neither fits today's curve
by construction — that's what Hull-White's time-dependent drift buys you.

*In this library:* `rates/ShortRateModels.java` — `vasicekBond`, `cirBond`,
`cirFeller` (the ratio 2ab/σ², one line), and curve-fitted Hull-White for
the follow-up.

### 6. "Why would anyone hold risk parity instead of 60/40 or equal weight?"

Equal weight equalizes **capital**; risk parity equalizes **risk
contribution**. A 60/40 portfolio is ~90% equity risk, because equities are
several times more volatile than bonds — the capital split hides the risk
split. Risk parity solves for weights where each asset's contribution
`w_i · (Σw)_i / σ_p` is equal, which systematically levers up the
low-volatility, low-correlation assets. The honest caveats a good candidate
volunteers: it needs leverage to hit equity-like returns (so it embeds
financing and rate risk), and it's still only as good as the covariance
estimate — though notably it needs *no return forecasts at all*, which is
exactly what makes it robust where max-Sharpe is fragile.

*In this library:* `optimization/RiskParityOptimizer.java` — the fixed-point
iteration, with a test asserting the contributions actually come out equal;
compare against `PortfolioOptimizer.maxSharpe` on the same inputs.

### 7. "Your factor has an IC of 0.03. Is that good?"

Quite possibly excellent — the question is testing whether you know the
scale. IC is the **rank correlation** between today's scores and forward
returns (rank, not Pearson, because factor scores have arbitrary units and
fat tails). The **Fundamental Law of Active Management** converts it:
IR ≈ IC · √breadth. An IC of 0.03 applied across 3,000 stocks monthly is a
strong strategy; the same IC on five bets a year is noise. So the answer is
"good relative to what breadth, and is it *stable*?" — which is why you
report the mean IC over time divided by its own volatility (the ICIR), and
why IC decay across horizons tells you how fast to trade the signal.

*In this library:* `alpha/SignalEvaluator.java` — per-date Spearman IC,
ICIR, decay profile, and turnover; `alpha/AlphaBacktester.java` shows the
IC surviving (or not) out of sample.

---

## Round 2 — Derivatives & pricing

### 8. "When is it optimal to exercise an American option early?"

Never, for a **call on a non-dividend-paying stock**: the call is always
worth more alive than dead, because exercising forfeits the remaining time
value and pays the strike earlier than necessary (losing interest on K).
Sell the option instead. Dividends change it — right before an ex-dividend
date, exercising captures a dividend the option holder otherwise loses, so
early exercise can pay for deep-ITM calls with large imminent dividends.
Puts are different: a deep-ITM put has an incentive to exercise early even
without dividends, because you receive K *now* and earn interest on it.
In a binomial tree this becomes one line: at each node take
`max(continuation, intrinsic)` — the tree "discovers" the early-exercise
boundary rather than being told it.

*In this library:* `pricing/BinomialTree.java` — the `AMERICAN` branch is
exactly that `max`; a test shows the American call equaling the European one
at zero carry and pulling away as the carry/dividend yield grows.

### 9. "State put-call parity. Now decompose a vanilla call into digital options."

Parity: `C − P = df·(F − K)` — long call, short put is a forward, no model
required, so any violation is an arbitrage regardless of your volatility
opinion. The decomposition: a vanilla call pays `(S−K)⁺ =
S·1{S>K} − K·1{S>K}`, i.e. **long an asset-or-nothing digital, short K
cash-or-nothing digitals**. Under Black-Scholes those two pieces are the two
terms of the formula itself: `S·N(d1)` is the asset digital, `K·e^{−rt}·N(d2)`
is K cash digitals — so N(d2) is the (risk-neutral) probability of
finishing in the money, and N(d1) is that probability under the stock
measure. Teachers love this question because it explains the BS formula
term-by-term instead of as a memorized blob.

*In this library:* `pricing/DigitalOption.java` (cash-or-nothing,
asset-or-nothing, and a test reassembling the vanilla from the pieces),
`pricing/BlackScholes.java` for the terms being decomposed.

### 10. "How do you price a barrier option in closed form, and when does the closed form stop being the right answer?"

By the **reflection principle**: for a lognormal process, the law of paths
that touch a barrier H can be mapped to the law of paths started from the
reflected point H²/S. That gives Reiner-Rubinstein closed forms for
knock-ins, and knock-outs follow from in-out parity: KI + KO = vanilla —
touch it or don't, you own the vanilla either way. The closed form assumes
**continuous monitoring, flat vol, and a "regular" barrier** (barrier in the
OTM region). It stops being the right answer when monitoring is discrete
(daily fixings — the Broadie-Glasserman-Kou barrier shift), when the smile
matters (a reverse barrier's value concentrates near H, where flat-vol is
most wrong), or for reverse barriers generally, where the discontinuous
payoff at the barrier makes Greeks explode and desks price with a model.

*In this library:* `pricing/BarrierOption.java` — the regular cases in
closed form, in-out parity pinned by a test, and a javadoc that names the
reverse-barrier limitation instead of hiding it.

### 11. "Why does Black-76 exist when Black-Scholes already does?"

Because half the derivatives world is written on **forwards**, and a forward
has no carry: it costs nothing to hold, so the drift drops out and the
price is just the discounted Black formula on the forward itself —
`call = df·[F·Φ(d1) − K·Φ(d2)]`. Mathematically it *is* Black-Scholes with
spot = forward and carry = 0. It deserves its own name because the market
**quotes in it**: a cap vol, a swaption vol, a commodity futures option vol
are all Black-76 σ by convention, and mixing up which model a quoted vol
belongs to is a real (and expensive) rookie error. Bonus point: working on
the forward also sidesteps knowing the dividend/repo/convenience yield —
it's already inside F.

*In this library:* `pricing/Black76.java` — a test pins the equivalence to
`BlackScholes` with zero carry, which is the whole relationship in one
assertion.

### 12. "An FX broker screen shows ATM 8.5, RR25 −1.2, BF25 0.35. Price me a 1.0900 strike."

Two steps. First, decode the quotes: FX smiles are quoted in **delta**, not
strike — ATM is the delta-neutral straddle vol, the risk reversal is
`σ(25Δcall) − σ(25Δput)` (skew direction), the butterfly is the average of
the wings minus ATM (smile curvature). So `σ(25Δcall) = atm + bf + rr/2`,
`σ(25Δput) = atm + bf − rr/2`, and each delta is converted to its strike by
inverting the BS delta formula. Second, interpolate to your strike
**smile-consistently**: vanna-volga prices the flat-vol BS value plus the
market cost of the portfolio of the three pillars that hedges the vega,
vanna and volga — exact *at* the pillars (pricing a pillar strike returns
its own market vol), smooth between them. A negative RR means puts are
bid over calls: the market pays up for downside protection in that pair.

*In this library:* `fx/FxVolSurface.java` (quotes → pillar strikes/vols),
`pricing/VannaVolga.java` (the log-strike weights; the pillar-exactness
test is the one to re-run).

### 13. "What's the fair strike of a variance swap — and why is it model-free?"

Because of the log contract (Demeterfi-Derman-Kamal-Zou): realized variance
can be replicated by a **static portfolio of OTM options weighted 1/K²**
plus delta hedging in the underlying. The fair variance strike is therefore
readable directly off the option chain — no volatility model, no dynamics
assumption beyond continuity. This is exactly the integral a VIX-style
index computes: a VIX of 20 *is* a variance-swap strike of 0.04 — one
number, two names. The classic follow-ups: a **vol swap** strike is *below*
the square root of the variance strike (Jensen: √ is concave, so
E[√v] < √E[v] — the gap is a convexity adjustment growing with vol-of-vol),
and the 1/K² weighting means the strike loads heavily on deep OTM puts —
so variance swaps are short crash risk in a way ATM vol is not.

*In this library:* `pricing/VarianceSwap.java` (`fairVariance` literally
delegates to the index calculation and squares it),
`volatility/VolatilityIndex.java` (the replication integral itself).

### 14. "In SABR, what do ρ and ν control? Why does every rates desk use it?"

SABR makes the forward's volatility itself stochastic and lognormal-ish:
α sets the ATM vol level, β the backbone (how ATM vol moves as the forward
moves — fixed by convention, not fitted), **ρ the correlation between the
forward and its vol — the skew/slope of the smile**, and **ν the vol-of-vol
— the curvature/wings**. Desks use it because Hagan's approximation gives
implied vol in closed form (fast enough to calibrate per expiry in
microseconds), the parameters map one-to-one onto how traders talk about a
smile (level, skew, wings), and — critically for rates — it prescribes how
the smile *moves when the forward moves*, which is what you need for
correct Greeks, not just correct prices today.

*In this library:* `pricing/SabrModel.java` — Hagan's formula and a (α, ρ, ν)
calibration with β fixed; perturb ρ and ν in a test and watch slope and
curvature move independently.

### 15. "You've implemented a Heston pricer. How do you know it's right?"

Layered cross-checks, none of which share a failure mode with the code under
test. (1) **Degenerate limits**: kill the vol-of-vol and mean-revert vol to
a constant — Heston must collapse to Black-Scholes to near machine
precision. (2) **Independent method**: Monte Carlo simulate the same SDE
and demand the semi-analytic price sits inside the MC confidence interval.
(3) **Numerical traps**: the original Heston characteristic function has a
branch-cut discontinuity that silently corrupts long-dated prices; use the
"little Heston trap" formulation, whose complex log stays on the principal
branch. (4) **Parity and bounds**: put-call parity and no-arbitrage bounds
hold for any parameters. The meta-answer worth internalizing: semi-analytic
speed and simulation independence, each validating the other.

*In this library:* `pricing/Heston.java` — the little-trap characteristic
function with the branch-cut reasoning in comments; its BS-limit test
caught a genuine complex-square-root precision bug, which is the whole
argument for degenerate-limit tests in one anecdote.

---

## Round 3 — Market microstructure & execution

### 16. "You're a market maker and you're long 800 lots you don't want. What do your quotes look like?"

Shaded down — **both** of them. The Avellaneda-Stoikov reservation price
`r = mid − q·γ·σ²·τ` moves your personal "fair value" against your
inventory: being long makes selling attractive (better ask) and further
buying unattractive (worse bid), so the flow you attract is the flow that
flattens you. Around that reservation price you quote a half-spread
`γσ²τ/2 + (1/γ)·ln(1 + γ/κ)` — wider when volatility or your risk aversion
is higher, tighter when order-flow intensity κ is higher. Never let the
skew cross your own quotes. The natural follow-up — "will your bid actually
fill?" — is a **queue position** question: at a busy level your fill
probability is set by how much size rests ahead of you, which an L3 feed
lets you track exactly.

*In this library:* `trading/AvellanedaStoikov.java` (`reservationPrice`,
`optimalHalfSpread` — closed forms with the derivation in javadoc),
`crb/SkewedQuoter.java` (the same intuition, linear and never
self-crossing), `marketdata/L3BookBuilder.java` (`sharesAhead`) feeding
`microstructure/QueueModel.java` (queue position → fill probability).

### 17. "TWAP, VWAP, POV — when do you use which? And what's the classic POV bug?"

TWAP spreads evenly over time: right when you have no volume information or
must match a time benchmark (e.g. a fixing window). VWAP front-loads into
the market's own U-shaped volume curve: right when you're benchmarked to
the day's VWAP and want minimal tracking error. POV participates at a fixed
percentage of *realized* volume: right when urgency should scale with
liquidity, and the only choice when volume is unpredictable — but it has no
guaranteed end time. The classic bug: measuring participation against total
volume **including your own fills**. Then your own trading raises the
"market volume" you chase, and you converge to p/(1+p) instead of p — the
algo chases its own tail. Participation must be measured against *other
people's* volume.

*In this library:* `execution/TwapScheduler.java`,
`execution/VwapScheduler.java`, `execution/PovTracker.java` (the javadoc
states the p/(1+p) trap and the ledger excludes own fills),
`execution/BenchmarkExecutor.java` (all benchmarks as one live re-deciding
executor).

### 18. "You must sell 5 million shares by Friday. Fast or slow — what's the actual tradeoff?"

Almgren-Chriss makes it precise: minimize `E[cost] + λ·Var[cost]`. Trading
fast pays **temporary impact** (you demand liquidity faster than it
replenishes); trading slow accepts **price risk** (the market can move
against you while you hold). The optimal trajectory decays like
`sinh(κ(T−t))/sinh(κT)` — exponential-ish front-loading whose urgency κ
grows with risk aversion λ and volatility, and falls with market depth.
Two limits worth volunteering: λ→0 gives TWAP (risk-neutral traders
minimize impact only), and λ large sells almost everything immediately.
The model's inputs — temporary impact η, permanent impact γ — are
estimated from your own fills, which is where TCA feeds back into
scheduling.

*In this library:* `microstructure/AlmgrenChriss.java` — the closed-form
discrete trajectory, with the TWAP-recovery limit in a test;
`microstructure/MarketImpactModel.java` supplies the η/γ parameterization.

### 19. "You have trades but no quotes. Estimate the spread and the price impact."

Three classical estimators, in rising data requirements. **Roll (1984)**:
bid-ask bounce makes consecutive trade-price changes negatively
autocorrelated; the implied effective spread is `2·√(−cov(Δp_t, Δp_{t−1}))`.
**Corwin-Schultz**: two days' high-low ranges — the range contains both
variance (grows with time) and spread (doesn't), so comparing 1-day and
2-day ranges separates them. **Amihud**: impact per currency unit traded,
`mean(|return|/dollarVolume)`. For impact proper, **Kyle's lambda** is the
regression coefficient of price change on signed order flow — his model
says informed traders make price impact linear in net flow, and λ *is* the
market's illiquidity: flat-book venues have high λ. Estimate it recursively
from (mid change, signed volume) pairs and you get a live impact estimate
per symbol.

*In this library:* `microstructure/LiquidityMeasures.java` (`rollSpread`,
`corwinSchultzSpread`, Amihud), `microstructure/KylesLambda.java`
(streaming λ via `onSample`, plus `impactBps` for pre-trade cost),
`microstructure/MarketImpactModel.java` for the square-root-law comparison.

### 20. "Design a smart order router. What do you rank venues on besides price?"

Displayed price and fees/rebates are the entry ticket, not the answer. A
production SOR also weighs: **fill probability** (a venue that fades on
arrival has a worse *expected* price than its quote), **latency** (a stale
quote is a worse quote), **hidden liquidity** (venues that historically
fill more than displayed), and — the one that separates candidates —
**adverse selection**: measure post-fill markouts per venue, because a
venue where the price systematically moves against you right after filling
is *selling you toxic fills* at an invisible cost that can exceed the whole
spread. Two more marks of a real answer: check the NBBO state first (a
locked or crossed tape means your consolidated view is stale — don't route
on it), and if you run a central book, cross internally before going out;
your own book is the firm's best dark pool.

*In this library:* `execution/AdaptiveSor.java` (the full checklist,
including markout-based venue toxicity), `marketdata/Nbbo.java`
(`locked`/`crossed` flags), `crb/CrbRouter.java` (internal-first routing),
`execution/VenueScorecard.java` (where the per-venue evidence accumulates).

### 21. "What is last look, and what does 'symmetric' mean? Why does the FX Global Code care?"

In OTC FX a quote is an invitation, not a commitment: an LP receiving your
deal request may hold it briefly and re-check the market before accepting —
that's **last look**. The legitimate purpose is protecting the maker from
being picked off on a stale price by someone faster. The abuse is
**asymmetric application**: rejecting only when the market moved against
the maker, while happily accepting when it moved against *you* — that turns
the hold window into a free option on your flow. The FX Global Code
(Principle 17) requires symmetry: compare quoted price to current fair
price at the end of the hold, and reject when the move exceeds tolerance
in *either* direction. From the taker's side, you defend yourself by
measuring each LP's reject rate and markout behavior and routing away from
asymmetric ones.

*In this library:* `trading/LastLookGate.java` (the maker's gate, symmetric
by construction — the test rejects moves in both directions),
`fx/LpScorecard.java` + `fx/LpRouter.java` (the taker's defense).

### 22. "Your backtest Sharpe is 3. Walk me through why I shouldn't believe it."

Start with **look-ahead bias**: any indicator warm-up, normalization, or
parameter computed using data from the evaluation window leaks the future
into the signal. The clean protocol: indicators may warm up on bars before
`tradeFrom`, but P&L accrues strictly from `tradeFrom` — and each
walk-forward fold re-fits on its past only. Then **selection bias**: if 200
parameter combinations were tried, the winner's Sharpe is an order
statistic, not an estimate. Purge and embargo the CV splits (with
overlapping labels, adjacent folds share information), compute the
probability of backtest overfitting (does the in-sample winner rank well
out-of-sample?), and deflate the Sharpe against the best-of-N expectation.
A candidate who volunteers "the strategy also needs to survive costs and
its own impact" is having a very good round.

*In this library:* `backtest/Backtester.java` (the `tradeFrom` contract is
documented at the method), `backtest/validation/PurgedKFold.java`,
`backtest/validation/OverfitProbability.java` (PBO),
`backtest/validation/GridSearchOptimizer.java`
(`deflatedSharpeOfWinner` — the whole grid search is scored honestly).

---

## Round 4 — Risk

### 23. "You computed VaR four ways and got four numbers. Which is right?"

None — and knowing *why they disagree* is the job. **Delta-normal**
(`z·√(δ'Σδ)`) is instant and exactly wrong for optionality: it linearizes
the book. **Historical** replays actual factor moves — no distribution
assumed, but it can't see anything worse than the sample window. **Monte
Carlo** on Gaussian factors agrees with delta-normal on linear books (a
useful sanity check) and only earns its cost when you revalue non-linear
positions inside it. **Delta-gamma (Cornish-Fisher)** adds the second-order
term: a short-gamma book's VaR gets *worse* than delta-normal claims, and
that divergence appearing exactly where gamma says it must is the point.
Follow-up you must be ready for: "how do you know your VaR is calibrated?"
— Kupiec's test (do exceptions happen at the promised 1% rate?) and
Christoffersen's (do they *cluster*? — a model right on average but wrong
in crises fails independence).

*In this library:* `risk/VarEngine.java` (all four flavors over one
exposure/covariance input shape, disagreements pinned by tests),
`risk/VarBacktest.java` (Kupiec POF + Christoffersen LR tests).

### 24. "Portfolio VaR is $10m. Desk A asks what *their* share is. How do you answer — and can a share be negative?"

Euler allocation. Under delta-normal, portfolio σ is homogeneous of degree
one in the weights, so `component_i = w_i · ∂σ_p/∂w_i` sums **exactly** to
σ_p — no residual bucket, every desk's number adds to the total, which is
what makes it a governance tool and not just math. Know the distinction
that trips people: **marginal** VaR is the derivative (risk per extra unit),
**component** VaR is weight × marginal (the desk's share), **incremental**
VaR is the discrete change from removing the whole position — related but
not equal. And yes, a component can be negative: a hedge that's negatively
correlated with the rest of the book *reduces* total VaR, and the Euler
decomposition shows it as a negative share — the risk report literally
credits the hedger.

*In this library:* `risk/ComponentVar.java` — the exact-additivity identity
is in the javadoc and pinned by a test; marginal vs component vs incremental
are separate methods so the distinction is executable.

### 25. "You hold jet fuel exposure and can only trade crude futures. How much do you trade?"

The minimum-variance hedge ratio: `h* = cov(asset, hedge)/var(hedge)` — the
OLS slope of asset returns on hedge returns. It's *not* 1: it scales for
volatility mismatch and shrinks with imperfect correlation. Then quantify
what the hedge achieves: **hedge effectiveness** is the fraction of variance
removed at h*, which equals ρ² — with correlation 0.8 the best possible
hedge kills only 64% of the variance, and no ratio does better. That number
decides whether the hedge is worth its costs (and 80%+ effectiveness is the
standard hedge-accounting threshold). For index futures the same regression
becomes beta-targeting: contracts = (β_target − β_current) · V / (futures
notional).

*In this library:* `hedging/MinimumVarianceHedge.java` — ratio,
effectiveness (= ρ², asserted), and futures contract sizing in one small
class.

### 26. "Your options book has delta 500, gamma −80, vega 1200. Neutralize it."

Order of operations matters: **the underlying has gamma 0 and vega 0**, so
it can only ever fix delta. First kill gamma and vega with traded options —
two instruments with linearly independent (gamma, vega) profiles give a
2×2 linear system for the quantities — then mop up whatever delta those
option trades introduced with the underlying, which disturbs nothing else.
That triangular structure is why desks re-hedge delta continuously but
gamma/vega only periodically: the cheap instrument fixes the fast-moving
Greek. Caveats a strong candidate adds: the neutralization holds locally
(Greeks drift as spot and vol move), and hedging vega with one expiry
against another leaves you exposed to the vol *surface* twisting rather
than shifting.

*In this library:* `hedging/GreekHedger.java` — delta-gamma and
delta-gamma-vega recipes plus a general linear solver;
`hedging/OptionsBook.java` aggregates the book Greeks it consumes.

### 27. "Forget 'what if rates rise 100bp.' Ask the better question: what kills us?"

That's **reverse stress testing** — fix the loss, solve for the scenario.
For a linear book under Gaussian factors it's closed form: the
most-probable factor move losing exactly L points along Σδ (the gradient
direction, scaled), and its **Mahalanobis distance** tells you how many
"joint sigmas" away the killing scenario sits. That distance is the
verdict: a book that dies at 2.5 joint sigmas has a concentration problem
that no plausible-scenario stress list would have found, because scenario
lists only contain what someone imagined. Regulators push reverse stress
precisely because it finds the *unimagined* scenario — the answer comes out
of the book's own structure, not the risk manager's creativity.

*In this library:* `risk/StressTester.java` (`reverseStress` — returns the
shock vector and `mahalanobisSigmas`; also has the forward historical
scenarios to contrast against).

### 28. "Why is 99.9% VaR from two years of daily data basically fiction — and what's the honest alternative?"

Two years is ~500 observations; the 99.9% quantile is the worst *half* an
observation — you're reading a number the sample doesn't contain.
And scaling a Normal fit out there is worse: financial returns are
fat-tailed, so the Gaussian assigns astronomically too little probability
to exactly the moves you're asking about. The honest tool is **extreme
value theory**: the Pickands-Balkema-de Haan theorem says exceedances over
a high threshold converge to a Generalized Pareto Distribution for
essentially any underlying distribution — so fit the GPD to the tail you
*did* observe and extrapolate along it. The shape parameter ξ is the tail
index: ξ > 0 means power-law tails (equities, typically), and a fitted
ξ ≥ 1 is the data telling you the mean of the excess doesn't even exist —
a warning, not a number to hide.

*In this library:* `risk/ExtremeValueTheory.java` — peaks-over-threshold GPD
fit, the EVT VaR formula in the javadoc, and it refuses to return
infinite-mean expected shortfall rather than printing garbage.

### 29. "You ran 400 backtests and the best Sharpe is 2.1. What's it worth?"

Roughly nothing until deflated. The best of N random strategies has a
positive expected Sharpe purely from selection — an order statistic of
noise, `E[max] ≈ σ·√(2·ln N)` — so the right benchmark for your winner is
not zero but the Sharpe that pure luck would have produced across the same
number of *effectively independent* trials. The **Probabilistic Sharpe
Ratio** asks: given track length, skew and kurtosis, what's the probability
the true Sharpe exceeds a benchmark? The **Deflated Sharpe Ratio** sets
that benchmark to the best-of-N expectation. A DSR near 0.5 means "exactly
what luck predicts." The practical discipline: the grid search itself
should compute this — count trials honestly (correlated variants count
less), report the winner's deflated number, not its raw one.

*In this library:* `backtest/validation/SharpeValidation.java` (PSR and
DSR with the skew/kurtosis adjustment),
`backtest/validation/GridSearchOptimizer.java`
(`deflatedSharpeOfWinner` — deflation wired into the search, where it
belongs).

---

## Round 5 — Low-latency engineering

### 30. "Your hot path allocates nothing. Prove it."

Claiming is easy; the real answer is a measurement protocol. (1)
**Allocation-counter tests**: `ThreadMXBean.getThreadAllocatedBytes` around
the hot loop — warm up first (JIT compilation itself allocates), then
assert zero bytes over N operations. Make it a unit test so a regression
fails the build, not the trading day. (2) **Epsilon GC**: run the full
workload under the no-op collector (`-XX:+UseEpsilonGC`); GC pauses are
zero *by construction*, and any unexpected allocation crashes with OOM
instead of hiding in a young-gen collection — the harshest possible
allocation test. The design patterns that get you there: preallocated
primitive arrays instead of objects, flyweights over binary buffers instead
of decoded messages, scaled longs instead of BigDecimal, and no autoboxing
(a `HashMap<Long, Order>` allocates on every lookup).

*In this library:* the `sbe` package and its tests
(`src/test/.../sbe/QuoteFlyweightTest.java`, `SbeCodecTest.java` — the
ThreadMXBean pattern; the same harness guards `HftOrderBook`, `Nbbo`,
`L3BookBuilder` and dozens more); `docs/ULTRA_LOW_LATENCY.md` covers the
Epsilon GC run and sizing the heap to survive a session.

### 31. "Build a queue between a feed thread and a strategy thread. Why is SPSC special, and where exactly do the memory barriers go?"

Single-producer/single-consumer lets you drop locks *and* CAS entirely:
each index has exactly one writer, so plain writes suffice for data and the
only synchronization needed is **release/acquire on the sequence
counters**. The producer writes the slot's data with plain stores, then
publishes the sequence with a release store; the consumer reads the
sequence with an acquire load, and the happens-before edge guarantees it
sees fully-written data — publication order is the entire correctness
argument. Two more marks of a real answer: slots are preallocated primitive
arrays (zero allocation, cache-friendly), and head/tail counters are padded
to separate cache lines so producer and consumer cores don't false-share —
otherwise the "lock-free" queue serializes on cache-coherence traffic.

*In this library:* `marketdata/TickRingBuffer.java` — Disruptor-style,
VarHandle release/acquire with the reasoning in comments, cache-line
padding; `trading/OrderRingBuffer.java` is the same idea on the order path.

### 32. "You need a hash map on the hot path — order ID to array index. Why not HashMap, and how does deletion work in yours?"

`HashMap<Long, Integer>` allocates on the hot path (boxed keys, boxed
values, an Entry node per insert) and chases pointers across the heap on
every lookup. The fix is **open addressing with linear probing** over
parallel primitive arrays: a collision steps to the next slot, so probes
walk *contiguous memory* — one cache line often covers the whole probe run.
The subtle part is deletion. Tombstones are the textbook answer but they
accumulate and rot probe performance. **Backward-shift deletion** instead
re-places every entry in the probe run after the hole: each subsequent
entry moves back if its ideal slot position allows it, restoring the
invariant that every key is findable with no dead markers. It's the detail
worth probing because getting it wrong is silent — lookups just start
missing keys that exist.

*In this library:* `orderbook/BookPrimitives.java` — the primitive long→int
map with backward-shift deletion, commented step by step; its test hammers
random insert/delete sequences against a reference `HashMap`.

### 33. "How does a real matching engine store the book? Not a TreeMap."

A **dense price ladder**: prices become integer ticks, and the book is an
array with one slot per tick — price lookup is an array index, O(1), no
comparisons, no rebalancing, no node allocations. Sparsity is handled by
**occupancy bitmaps**: one bit per price level per side, so "find the best
bid" is a `Long.numberOfLeadingZeros` scan over a few machine words —
hardware-speed, branch-predictable. Orders at a level form an intrusive
FIFO linked list over preallocated arrays (price-time priority), and order
ID → node lookup is the open-addressing map from the previous question. The
tradeoff to state upfront: you commit to a bounded price band at
construction; a real engine sizes the band generously and treats a breach
as a halt-worthy event anyway (that's what circuit breakers are for).

*In this library:* `orderbook/HftOrderBook.java` — ladder + bitmaps +
intrusive queues in one file; `orderbook/OrderBook.java` is the readable
reference implementation, and an equivalence test drives both with the same
random flow and demands identical books.

### 34. "Your benchmark says p99 = 2 microseconds. Give me three reasons that number is a lie."

(1) **Coordinated omission**: if the load generator waits for each response
before sending the next request, a stall makes it *stop measuring* during
the worst moments — the pauses conveniently vanish from the histogram.
Record against the intended send schedule, not the actual one. (2)
**Platform pauses attributed to code**: GC, safepoints, JIT
deoptimizations, and OS scheduler preemptions corrupt the tail without
appearing anywhere in your code. Run a hiccup monitor — a thread that
sleeps a fixed interval and records how much *longer* it slept — alongside
the benchmark; if its spikes line up with your p99.9, the platform ate the
tail, not your algorithm. (3) **Measurement overhead and warm-up**: timing
with allocating instruments perturbs the thing measured, and pre-JIT
samples belong to the interpreter, not your system. And report percentiles,
never averages — latency distributions are multi-modal, and the mean is a
number nobody experiences.

*In this library:* `util/LatencyRecorder.java` (zero-allocation
HdrHistogram-style log-linear buckets — safe to keep on the hot path),
`util/HiccupMonitor.java` (the jHiccup pattern);
`docs/ULTRA_LOW_LATENCY.md` treats coordinated omission explicitly.

### 35. "Your FIX session drops mid-day. Walk me through the reconnect — what do the sequence numbers do?"

FIX guarantees ordered delivery with per-session monotonic **sequence
numbers** both ways. On reconnect, both sides exchange Logons carrying
their next expected numbers. If the peer's number is *higher* than you
expected, you missed messages: send a ResendRequest for the gap. The peer
replays the missed application messages flagged **PossDupFlag=Y** (with
OrigSendingTime), so your engine can distinguish a replay from an original
— you must be idempotent on PossDup duplicates below the expected number
and process the rest normally. Admin messages aren't replayed; the peer
coalesces those runs into a **SequenceReset-GapFill (35=4, 123=Y)** that
jumps your expected number past them. The asymmetric rule to volunteer: a
too-*low* number *without* PossDup is not recoverable — it means state
corruption, and the correct action is to disconnect, not to guess.

*In this library:* `fix/FixSession.java` — the full recovery state machine
(gap detection, resend, PossDup suppression, GapFill handling, the too-low
disconnect); `fix/FixSessionTest.java` (under `src/test`) plays out a
logon, a fill and a gap-fill you can step through.

---

---

## More quant & math (Q36-Q50)

### 36. "You regressed returns on twelve factors and every t-stat collapsed when you added the twelfth. What happened?"

Multicollinearity. OLS coefficients are only well-identified when the
regressors carry independent information; when two factors are nearly
collinear (say, "value" measured by book-to-price and by earnings yield),
the design matrix X'X is close to singular, and the coefficient estimates
become a knife-edge split of shared explanatory power. The fitted values
and R-squared barely change, but individual betas swing wildly with tiny
data perturbations and their standard errors explode -- which is exactly
the symptom described. The real-life version: a risk model with both
"momentum 6m" and "momentum 12m" that reports a huge positive loading on
one and a huge negative loading on the other, netting to something small.
The desk fixes are (1) orthogonalize -- regress factor 12 on the first
eleven and keep only its residual, (2) rotate into principal components,
where the collinearity becomes one small eigenvalue you can truncate, or
(3) shrink (ridge), which is what "adding a small diagonal" to X'X means
economically: refuse to take large offsetting bets on directions the data
cannot distinguish.

*In this library:* `risk/Pca.java` (the eigen-decomposition that exposes
near-singular directions as small eigenvalues), `alpha/FamaMacBeth.java`
(the cross-sectional regression where collinear exposures produce exactly
this coefficient instability).

### 37. "Your alpha regression has beautiful t-stats. Why might they still be fiction?"

Because OLS standard errors assume homoskedastic, uncorrelated residuals,
and financial residuals are neither. Heteroskedasticity: return variance
is not constant -- it clusters (calm 2017, violent March 2020) -- so the
usual variance formula for beta-hat is simply the wrong formula, and in
the classic case it *understates* the error, inflating t-stats. Serial
correlation in residuals compounds it: 1,000 autocorrelated observations
might carry the information of 300 independent ones, but OLS divides by
root-1000. The honest fixes are White or Newey-West standard errors
(estimate the error covariance rather than assume it), or the
Fama-MacBeth device: run the regression cross-sectionally each period and
take the time series of the estimated premia -- the period-to-period
variation of the estimates *is* the standard error, no residual
assumptions needed. A concrete check that costs nothing: split the sample
into high-vol and low-vol halves; if the coefficient only exists in one
half, the pooled t-stat was an average of two different regimes, not
evidence.

*In this library:* `alpha/FamaMacBeth.java` (the two-pass estimator; its
javadoc states explicitly that the plain time-series t-stats carry no
Newey-West correction -- the limitation is documented, not hidden),
`volatility/Garch11.java` (the model of exactly the heteroskedasticity
that breaks the naive formula).

### 38. "Why do we regress returns on returns, never prices on prices?"

Because prices are (near) unit-root processes, and regressing one
non-stationary series on another produces the **spurious regression** of
Granger and Newbold: two *independent* random walks will show a
significant OLS relationship most of the time, with R-squared that grows
with sample length. The intuition: a random walk spends long stretches
above or below any level, so two unrelated walks will share long
"trends" by chance, and OLS reads shared trend as relationship. The
classic classroom demo regresses the S&P on cumulative rainfall and gets
a t-stat of 8. Returns (differences of log prices) are approximately
stationary, so the usual asymptotics apply and a t-stat means what it
claims. The one legitimate exception is the pairs-trading case: if two
prices are **cointegrated**, the levels regression is not spurious -- the
residual is stationary and the slope is a real long-run hedge ratio --
but that is a property you must *test* for (Engle-Granger), never
assume. So the operational rule: difference first, unless you have run
and passed a cointegration test.

*In this library:* `hedging/CointegrationTest.java` (`engleGranger` --
the levels regression plus the ADF test on the residual that separates
the legitimate case from the spurious one), `hedging/PairsHedger.java`
(what you are allowed to do once the test passes).

### 39. "What is a unit root, and how would you convince me this spread is mean-reverting?"

Write the AR(1): `x_t = rho * x_{t-1} + eps`. If rho = 1 the series is a
random walk -- shocks are permanent, variance grows without bound, there
is no "level it returns to." If |rho| < 1, shocks decay geometrically
and the series is stationary around its mean. The trap is that you
cannot just fit rho and read a t-stat against 1: under the unit-root
null, the estimator's distribution is not Student-t (it is the
Dickey-Fuller distribution, shifted left), so the critical values are
different -- roughly -2.86 instead of -1.65 at 5% -- and using normal
critical values makes you find mean reversion everywhere. To convince a
desk the spread mean-reverts: (1) ADF test on the spread with the
correct critical values; (2) the Lo-MacKinlay variance ratio -- under a
random walk, variance grows linearly with horizon, so VR(q) well below 1
is direct evidence of mean reversion; (3) fit an Ornstein-Uhlenbeck and
report the **half-life** -- "this spread reverts with a 12-day half-life"
is a tradeable statement, while "the ADF stat is -3.1" is only a
statistical one. Bring all three; they fail in different ways.

*In this library:* `hedging/CointegrationTest.java` (`adfTStatistic`),
`microstructure/VarianceRatio.java` (the Lo-MacKinlay test with its
`rejectsRandomWalk` verdict), `microstructure/OrnsteinUhlenbeck.java`
(the AR(1)-to-OU mapping and the half-life a trader actually quotes).

### 40. "Coke and Pepsi are 90% correlated. Is that a pairs trade?"

Not yet -- correlation and cointegration answer different questions, and
pairs trading needs the second. Correlation says the *returns* move
together day to day; it says nothing about the *price levels* staying
attached. Two stocks can be 90% return-correlated while one drifts to
double the other over three years -- shorting the spread on that pair
bleeds forever, with high daily correlation the whole way down. What the
trade needs is a stationary spread: some combination P_a - h * P_b that
is pulled back to a level, so that divergence is a signal rather than a
regime change. That is cointegration, and it is tested, not eyeballed:
regress the levels, ADF-test the residual. Then quantify the reversion --
the half-life tells you the expected holding period, the stationary
stdev sets the entry z-score, and together they tell you whether the
reversion outruns your financing and borrow costs. The cautionary tale
is any pair broken by structural change: royal-family pairs like
Shell/RDS worked for years and then re-based on corporate events, which
is why production pairs desks re-test cointegration on a rolling window
and cut when the ADF stat decays -- the test failing *is* the exit
signal.

*In this library:* `hedging/CointegrationTest.java` (the gate),
`hedging/PairsHedger.java` (`analyze` -- hedge ratio, z-score, and
`halfLife` in one pass), `microstructure/OrnsteinUhlenbeck.java` (the
dynamics under the trade).

### 41. "Your signal fires and historically it is right 60% of the time. A colleague says 'so bet 60%.' Where is the Bayes error?"

The 60% is a likelihood-flavored number measured *conditional on the
regimes it was fit in*; the bet should be sized on the posterior, which
also needs the prior -- and the prior in markets is brutal. Suppose the
signal is a crash predictor: it fires before 60% of crashes (hit rate),
but crashes happen 2% of months and the signal also fires in 5% of
normal months. Bayes: P(crash | fire) = (0.60 * 0.02) / (0.60 * 0.02 +
0.05 * 0.98) = 0.012 / 0.061, about 20%. The signal quadrupled your
crash probability and it is *still* 80% likely nothing happens -- sizing
as if 60% would be ruinous. The general lesson: rare-event signals are
dominated by the base rate, and the false-positive rate matters more
than the hit rate. The portfolio-construction version of the same logic
is Black-Litterman: start from an equilibrium prior (market-implied
returns), state the view with an explicit confidence, and let the
posterior -- not the raw view -- set the weights. A view held with
realistic confidence moves the portfolio a little; only fake certainty
moves it a lot, which is exactly the discipline the colleague skipped.

*In this library:* `optimization/BlackLitterman.java` -- the posterior
blend of prior and view is the Bayes computation above wearing portfolio
clothes; set the view confidence low and watch the tilt shrink.

### 42. "Derive the Kelly fraction. Now tell me why nobody bets full Kelly."

For a return stream with mean mu and variance sigma^2, maximizing the
expected log growth rate g(f) = f * mu - f^2 * sigma^2 / 2 gives
f* = mu / sigma^2. Worked number: an edge of 5% annualized on 20% vol
gives f* = 0.05 / 0.04 = 1.25 -- Kelly says lever the strategy 1.25x.
Full Kelly is optimal only asymptotically and only if mu and sigma are
*known*. Neither holds. First, the drawdown profile is savage: full
Kelly has a 50% chance of halving your capital before doubling it, and
the time to recover is long because the bet shrinks with the equity.
Second, and worse, the inputs are estimates: overestimating mu by 2x
(trivially easy with noisy Sharpe estimates) means betting 2x Kelly,
and the growth-rate parabola is asymmetric -- betting double Kelly gives
*zero* long-run growth, and beyond that, ruin. Since the penalty for
over-betting exceeds the penalty for under-betting, uncertainty about
the edge rationally shrinks the bet. Half Kelly is the practitioner's
standard: it keeps 75% of the growth rate at half the variance, and it
is robust to the edge being half of what you think. That is not
timidity; it is the correct Bayesian response to estimated inputs.

*In this library:* `backtest/portfolio/PositionSizing.java` --
`kellyFraction` (the mu/sigma^2 formula) and `halfKelly` sit side by
side, with the growth-for-drawdown trade stated in the javadoc.

### 43. "The law of large numbers says the sample mean converges. So why did one week in 2008 undo five years of a strategy's P&L?"

Because LLN convergence has a *rate*, and the rate depends on the tails.
For thin-tailed returns, the sample mean stabilizes like 1/sqrt(n) and
five years of daily data pins the mean well. For fat-tailed returns --
power-law tails with index alpha -- a huge share of the total sum comes
from a handful of extreme observations, so the sample mean is hostage to
whether your window happened to contain them. If alpha <= 2 variance is
infinite and the CLT does not even apply in its usual form; if alpha is
just above 2, it applies but convergence is glacial. The trading
translation: a short-vol or short-crash strategy earns a small positive
mean on 1,250 ordinary days, and the entire compensation for that income
is a loss concentrated in a few days that may not be *in your sample at
all*. Five quiet years told you almost nothing about the mean, because
the mean lives in the tail you had not yet observed -- that is the
selling-earthquake-insurance problem, and it is why you must model the
tail explicitly (EVT on exceedances) instead of trusting the sample mean
of a fat-tailed process. LLN is true; it is just much slower than your
backtest window.

*In this library:* `risk/ExtremeValueTheory.java` -- the fitted GPD shape
parameter xi is the tail index in this story; xi near or above 0.5 is
the data announcing that sample means of squared losses are unstable,
and the class refuses to print infinite-mean expected shortfall.

### 44. "A monthly strategy reports Sharpe 1.8 annualized from monthly data. The daily version of the same P&L shows 1.1. Who is lying?"

Probably neither -- the annualization is. Multiplying by sqrt(12) or
sqrt(252) assumes i.i.d. returns; positive autocorrelation breaks that
in the flattering direction. If returns are positively autocorrelated
(momentum strategies, anything marked with stale or smoothed prices,
illiquid credit funds), the variance of long-horizon returns is *larger*
than n times the short-horizon variance, so short-horizon vol
understates true risk and sqrt-time annualization inflates the Sharpe.
Monthly aggregation can also hide daily autocorrelation inside the
month. The infamous real-world case is smoothed NAVs: funds holding
illiquid assets show gorgeous monthly Sharpes because stale marks act as
a low-pass filter on volatility -- Madoff's reported return stream was
the pathological limit. Diagnostics: compute the Sharpe at several
horizons and compare (they should roughly agree under i.i.d.); run a
variance-ratio test on the P&L itself; and get the confidence interval
from a block bootstrap, which resamples *blocks* of the P&L so the
autocorrelation structure survives into the resampled paths -- an i.i.d.
bootstrap would destroy exactly the feature under investigation and
give intervals that are too tight.

*In this library:* `backtest/validation/BlockBootstrap.java`
(`sharpeSamples` -- the autocorrelation-preserving Sharpe distribution),
`microstructure/VarianceRatio.java` (the horizon-scaling diagnostic),
`backtest/validation/SharpeValidation.java` (the skew/kurtosis-aware
significance test to use instead of naive sqrt-time).

### 45. "You ran PCA on the yield curve and got three components. What ARE they -- and when is factor two of an equity PCA meaningless?"

Mechanically, they are eigenvectors of the covariance matrix: orthogonal
directions ordered by variance explained. On a yield curve they have
names because the loadings have shapes: PC1 loads near-uniformly across
tenors (**level**, typically 85-90% of variance), PC2 loads with
opposite signs at the ends (**slope**), PC3 is hump-shaped
(**curvature**). That is why a rates risk report can compress a
12-tenor book into three exposures. The care required: PCA factors are
statistical, not causal. They are only nameable when the loading
pattern is stable and interpretable; on equities, PC1 is robustly "the
market," but PC2 and beyond are often unstable mixtures -- rotate the
estimation window six months and "factor two" reshuffles, because
nearby eigenvalues make their eigenvectors ill-determined (a nearly
degenerate pair of eigenvalues can rotate freely in their shared
plane). Practical rules: check eigenvalue separation before naming
anything; compare against random-matrix noise floors before keeping
small components; and remember PCA maximizes explained variance, not
tradability -- a component can be statistically real and still not
correspond to any portfolio you can hold at acceptable cost.

*In this library:* `risk/Pca.java` -- `loading` and `explainedVariance`
reproduce the level/slope/curvature result on curve covariance data
(the javadoc calls out exactly that classic), and eigenvalue spacing is
inspectable via `eigenvalue`.

### 46. "Why does the 12-parameter version of your alpha model backtest better and trade worse?"

Bias-variance. Expected out-of-sample error decomposes into bias squared
(model too rigid to capture the signal) plus variance (model flexible
enough to capture the noise) plus irreducible noise. In-sample fit
*always* improves with parameters; out-of-sample performance improves
only until the added flexibility starts fitting sampling noise, then
degrades. Alpha research lives at a signal-to-noise ratio where the
turn comes brutally early: daily equity returns are maybe 5% signal, so
a 12-parameter interaction model has ample capacity to memorize the
particular path of the backtest. The desk symptoms of a high-variance
model: parameters that change sign across estimation windows, an
in-sample-to-out-of-sample IC ratio far above 1, and performance that
concentrates in one historical episode. The defenses are structural,
not heroic: fewer parameters than you think you can afford,
regularization (shrinkage toward zero is a bias you *choose* to buy
variance reduction), ensembling, and walk-forward measurement so the
IS-OOS gap -- which is the overfitting, quantified -- is a reported
number rather than a hope. In alpha work, a biased simple model that
generalizes beats an unbiased flexible one that does not, essentially
always.

*In this library:* `alpha/AlphaValidation.java` (walk-forward and
blocked k-fold: the IS-to-OOS gap measured explicitly),
`ml/GradientBoostedRegressor.java` (the flexible end of the trade-off,
where depth and iterations are the variance knobs).

### 47. "Give me the full checklist you run before believing any backtest."

In the order the sins are usually committed. (1) **Look-ahead**: no
indicator, normalization, or parameter may use data from the evaluation
window; warm up before `tradeFrom`, accrue P&L only after. (2)
**Survivorship**: the universe must be as-of-date (a 2010 backtest on
today's S&P constituents already knows who survived). (3) **Costs and
capacity**: spreads, impact, borrow -- a 2-Sharpe strategy trading 40%
daily turnover in small caps is often a 0-Sharpe strategy after costs.
(4) **Selection**: if N configurations were tried, the winner is an
order statistic; use purged, embargoed cross-validation (overlapping
labels leak across adjacent folds without a purge), compute the
probability of backtest overfitting via CSCV -- does the in-sample
winner *rank* well out-of-sample? -- and deflate the Sharpe against the
best-of-N expectation, counting correlated variants as fewer effective
trials. (5) **Stability**: performance should degrade gracefully as
parameters move; a sharp peak at one setting is the signature of fitted
noise. (6) **Regime coverage**: demand the strategy was alive through
at least one episode that should hurt it. The meta-point this exercise
rewards: every defense here converts an unstated hope into a computed
number.

*In this library:* `backtest/validation/PurgedKFold.java`,
`backtest/validation/OverfitProbability.java` (`cscv` -- the PBO
computation), `backtest/validation/SharpeValidation.java`
(`deflatedSharpe`, `expectedMaxSharpe`),
`backtest/validation/WalkForwardAnalyzer.java`; `backtest/Backtester.java`
documents the `tradeFrom` contract that kills look-ahead.

### 48. "When do you bootstrap a confidence interval instead of using the formula?"

Use the closed form when its assumptions plausibly hold and you need
speed or analytic insight; bootstrap when the statistic is awkward or
the data violate the assumptions -- which for trading P&L is most of the
time. The textbook CI for a Sharpe ratio assumes i.i.d. normal returns;
real P&L has fat tails, skew (option-selling strategies especially),
and autocorrelation, all of which the formula silently ignores. There
are analytic corrections (Lo's adjustment, the Mertens variance with
skew and kurtosis terms), and a strong candidate mentions them -- but
the bootstrap handles all of it at once by resampling the data you
actually have. The crucial detail for time series: an i.i.d. bootstrap
scrambles away the autocorrelation, so you must resample in blocks --
the stationary block bootstrap draws blocks of random (geometric)
length, preserving local dependence while still mixing the sample.
Concrete use: a strategy with Sharpe 1.2 over 3 years; the block
bootstrap gives you the full sampling distribution, and the honest
question becomes "is the 5th percentile of that distribution still
positive?" rather than "is 1.2 a big number?" Caveats to volunteer: the
bootstrap cannot manufacture tail events absent from the sample, and it
assumes stationarity across the window -- it fixes the formula's lies,
not the sample's.

*In this library:* `backtest/validation/BlockBootstrap.java`
(Politis-Romano stationary blocks; the javadoc frames exactly the
5th-percentile question), `backtest/validation/SharpeValidation.java`
(`probabilisticSharpe` -- the analytic skew/kurtosis-adjusted
alternative, so both routes are runnable and comparable).

### 49. "MLE versus method of moments -- when would you ever prefer moments?"

MLE finds parameters maximizing the probability of the observed data;
it is asymptotically efficient (smallest possible variance) *when the
likelihood is correctly specified*. Method of moments matches sample
moments to model moments; it is less efficient but requires only that
the chosen moments be right, not the whole distribution. Prefer moments
when: (1) the likelihood is wrong in ways you cannot fix -- fitting a
Gaussian likelihood to fat-tailed data lets the extreme observations
bully every parameter, while a moment fit on robust moments shrugs; (2)
the likelihood is expensive or multi-modal -- MLE for GARCH-family
models needs numerical optimization with constraint handling and can
find local optima, while moment-style estimators are closed-form and
make excellent starting points; (3) you want transparency -- an
Ornstein-Uhlenbeck fit via the exact AR(1) regression mapping is three
lines you can verify by hand, and a desk debugging a live system at 2am
values that over asymptotic efficiency. The practical pattern is both:
initialize with moments, polish with MLE, and treat a large gap between
the two estimates as a specification alarm rather than picking your
favorite.

*In this library:* `volatility/Garch11.java` (`fit` -- the numerical
maximum-likelihood route, likelihood in the code),
`microstructure/OrnsteinUhlenbeck.java` (`fit` -- the exact AR(1)
moment mapping, closed form); running both philosophies side by side is
the whole comparison.

### 50. "Your covariance matrix is 500x500 estimated from 2 years of daily data. What is wrong with it and what do you do?"

It is rank-deficient in spirit and unstable in fact. With N = 500 assets
and T = 500 observations you are estimating 125,000 covariance entries
from 250,000 numbers -- the sample eigenvalue spectrum is badly
distorted (random-matrix theory: even for a true identity covariance,
sample eigenvalues spread across a wide Marchenko-Pastur band). Large
sample eigenvalues are biased up, small ones down, and the smallest
directions look spuriously near-riskless. Feed that to an optimizer and
it does the worst possible thing: it leverages hardest into exactly the
directions whose risk is most underestimated -- the error-maximizer
problem, now with a mechanical cause. The fix is shrinkage: pull the
sample matrix toward a structured target (constant-correlation or
identity), with intensity chosen to minimize expected estimation error
-- Ledoit-Wolf makes that intensity itself estimable from the data, so
there is no tuning parameter to overfit. Worked intuition: with T/N =
1, optimal shrinkage is heavy; with T/N = 20 it fades toward zero, and
the formula knows that. Alternatives on the same theme: factor-model
covariance (impose structure by construction), eigenvalue clipping, and
EWMA weighting for responsiveness -- but shrinkage is the default
because it is one call, provably never much worse, and usually much
better in the optimizer.

*In this library:* `risk/CovarianceShrinkage.java` (`ledoitWolf` --
target, intensity formula, and the reasoning in javadoc), fed into
`optimization/PortfolioOptimizer.java` to watch the weights stabilize;
`risk/Pca.java` shows the distorted eigenvalue spectrum directly.

---

## Rates & curves, deeper (Q51-Q60)

### 51. "You duration-hedged a 10-year bond with 2-year futures. The curve steepened 30bp and you lost money. Explain, then fix it."

Duration (and its currency version, DV01) is a *parallel-shift*
sensitivity: it collapses the whole curve into one number and promises
P&L only for the move where every tenor shifts together. Your hedge
matched total DV01 but placed it at the wrong *point* on the curve: the
long bond's risk lives at the 10-year node, the futures' risk at the
2-year node. In a steepener -- 2s roughly unchanged, 10s up 30bp -- the
bond lost its full DV01 x 30 while the hedge did nothing. Worked
numbers: $100m of a 10y with DV01 ~ $85k/bp loses ~$2.55m; the 2y
futures position sized to the same $85k/bp gains ~zero because its node
did not move. The fix is key-rate durations: bump one curve node at a
time holding the rest, reprice, and get a *vector* of DV01s that says
where on the curve the risk sits; then hedge node-by-node with the
instrument that drives each node (2y, 5y, 10y, 30y futures or swaps).
The parallel DV01 is just the sum of the KRDs -- a useful consistency
check and the reason the single number ever seemed adequate. Desks that
run material curve books also add explicit steepener/flattener and
butterfly scenarios, because the second PCA factor of every yield curve
*is* slope: the move that broke this hedge is the second most common
move there is.

*In this library:* `rates/KeyRateDurations.java` (`keyRateDv01s` -- the
bump-one-node loop; `parallelDv01` as the sum check),
`risk/Pca.java` (slope as the second eigenvector of curve covariance).

### 52. "The curve is upward sloping. Where is the P&L in just holding a 5-year bond for a year, if nothing happens?"

Two distinct pieces, and "nothing happens" must be defined. **Carry**:
you earn the bond's yield and pay your funding (repo); with a 5y at
4.0% funded at 3.0%, carry is ~100bp of running P&L. **Rolldown**: after
a year your 5y is a 4y, and on an upward-sloping static curve the 4y
point yields less -- say 3.85% -- so the bond reprices *up* as it rolls
down the curve: roughly 15bp of yield times the then-4-year duration
(~3.7), another ~55bp. Total ~1.55% for a scenario in which the curve
does not move at all. The crucial catch: "the curve stays where it is"
is a *bet*, not a neutral assumption -- the forwards already price the
5y rate drifting up along the curve, and if forwards realize instead,
carry-and-roll nets to approximately the repo rate and the trade earned
nothing extra. So carry-and-rolldown analysis is really the statement
"I am betting realized rates come in below the forwards," which has
been a historically profitable bet (term premium) but is exactly what
loses in a fast hiking cycle -- 2022 being the canonical recent example,
when rolldown P&L was swamped by the curve repricing. A good candidate
computes both numbers off the curve object and *names* the implicit
bet.

*In this library:* `rates/YieldCurve.java` -- `zeroRate` at shifting
tenors gives the rolldown, `forwardRate` gives the break-even path that
defines what "nothing happens" secretly assumes;
`rates/BondPricer.java` reprices the aged bond.

### 53. "It's 2016, EUR rates are negative, and your cap pricer just returned NaN. What happened and what did the market do about it?"

The pricer quotes in Black (lognormal) vol, and Black-76 prices a
caplet as an option on a *lognormal* forward -- a distribution whose
support is strictly positive. With the forward at -0.30%, ln(F/K) does
not exist: the model does not merely misprice, it stops being defined.
The market's two fixes: (1) **shifted lognormal** -- model F + s as
lognormal for a displacement s (say 1% or 2%), which restores
definitions down to F > -s at the cost of making every quoted vol
conditional on the shift convention (a "shifted Black vol" is
meaningless without stating s); (2) **normal (Bachelier) vols** -- model
dF = sigma_N dW so the forward is Gaussian, negative rates are natural,
and vol is quoted in absolute basis points per year (e.g. "72 bp
normal") rather than percent-of-forward. EUR and CHF swaption markets
switched their standard quotes to normal vols for exactly this reason,
and they largely stayed there even after rates turned positive, because
bp vols behave better when forwards are near zero (a lognormal vol
explodes as F approaches 0 just to keep the bp-vol finite). The
follow-through: know that sigma_LN ~ sigma_N / F links the
two regimes when F is comfortably positive, and that SABR's beta
parameter spans the same spectrum -- beta = 1 lognormal, beta = 0
normal.

*In this library:* `rates/RatesOptions.java` (the lognormal Black-76
cap/floor/swaption quoter -- the javadoc states the flat-lognormal
convention, which is precisely the assumption this question breaks),
`pricing/SabrModel.java` (beta as the lognormal-to-normal dial).

### 54. "A 10-year par swap and a 10-year par bond, same notional. Compare their DV01s -- and who actually pays for the difference?"

Close, but not equal, and the decomposition matters. A par bond's DV01
comes from discounting its coupons *and* its principal; a par swap has
no principal exchange, but the fixed leg's DV01 minus the floating
leg's near-zero DV01 (the floater reprices to par at each reset, so its
rate risk is only the stub to the next fixing) leaves the swap's DV01
close to the bond's -- the standard identity is that a par swap is long
a par fixed bond, short a floater. Numbers: a 10y par bond at 4% on
$100m has DV01 ~ $82k; the matching swap ~ $80k, the gap being the
floater's stub risk and any discounting-curve difference. Why desks
care: the swap achieves that DV01 with no upfront cash (it is unfunded
leverage -- the balance-sheet cost lives in margin and capital, not
purchase price), which is why swaps, not bonds, are the default hedging
instrument. The second-order point: bond DV01 is computed off
the bond's own yield; swap DV01 off the swap curve -- so hedging bonds
with swaps leaves you running **swap spread risk**, the basis between
the two curves. That basis is a real P&L line: swap spreads moved
violently in 2008 and again in the 2020 Treasury sell-off, and "I am
DV01-flat" was cold comfort to anyone long bonds versus paying on
swaps through either.

*In this library:* `rates/BondPricer.java` (bond DV01 with the
finite-difference test), `rates/YieldCurve.java` plus
`rates/RatesOptions.java` (swap annuity and forward-swap machinery the
swap-side DV01 is built from), `rates/KeyRateDurations.java` for where
each instrument's DV01 sits on the curve.

### 55. "Bootstrap a curve from deposits, futures and swaps. Why does the ORDER you process them in matter?"

Because bootstrapping is forward substitution: each instrument must pin
exactly one new discount factor, using only discount factors already
known. So you process instruments sorted by maturity, and each segment
of the curve is owned by one instrument type: deposits pin the short
end directly (a deposit *is* a discount factor); futures/FRAs extend it
piecewise (each future pins the forward over its 3-month window, so
df_end = df_start / (1 + f * tau) -- you need df_start first);
long-dated swaps come last, and each par swap rate pins df_n given the
full annuity sum of earlier dfs: df_n = (1 - s_n * sum(df_i, i<n)) /
(1 + s_n * tau_n). Get the order wrong and an instrument references a
discount factor that does not exist yet -- or worse, you silently
interpolate it, and the curve stops repricing its inputs. Two
real-world wrinkles that follow immediately: at the seams
(deposit-to-futures, futures-to-swaps) instruments *overlap*, and you
must choose which one owns the seam -- inconsistent choices show up as
kinks in the forward curve, which is why desks inspect forwards, not
zeros, for bootstrap bugs (forwards amplify every kink); and futures
need a convexity adjustment down to forward rates before they are
usable at all (next question). The defining test of any bootstrap: it
must reprice every input instrument to machine precision -- exactness,
not fit.

*In this library:* `rates/YieldCurve.java`
(`bootstrapAnnualParSwaps` -- the sorted forward-substitution loop with
the repricing property pinned by its test; `forwardRate` is the
kink-detector to inspect), `rates/NelsonSiegel.java` as the contrast:
smooth fit, no exact repricing.

### 56. "Before 2008 everyone discounted swap cash flows off LIBOR. Why did that stop, and what replaced it?"

Pre-crisis, LIBOR was treated as *the* risk-free proxy and the
LIBOR-OIS spread sat at ~10bp, so one curve did both jobs: projecting
forward LIBOR fixings and discounting the cash flows. In 2008 the
spread blew out to ~365bp (Lehman weeks), exposing the buried
assumption: LIBOR contains bank credit and liquidity premium, so
discounting at LIBOR was discounting at the wrong rate whenever
collateral was involved. The economics that forced the change:
collateralized swaps under a CSA earn/pay the overnight rate on posted
collateral, so the correct funding rate for their cash flows is the
OIS rate -- discounting is a *funding* question, and the funding of a
collateralized trade is overnight. The result is the multi-curve
framework: one curve (OIS, now SOFR/ESTR-based) for discounting, built
from OIS swaps; separate projection curves for each floating index
and tenor, built to reprice instruments on that index; the old
single-curve bootstrap survives as the special case where they
coincide. The switch was not cosmetic -- when clearinghouses moved
discounting to OIS, deep in-the-money swap books revalued by real
money, and desks that had ignored the basis found the P&L waiting for
them. Since LIBOR's 2021-23 cessation, the projection side has largely
collapsed onto compounded SOFR/ESTR, but the discounting lesson --
collateral rate, not index rate -- is permanent.

*In this library:* `rates/YieldCurve.java` -- the single-curve bootstrap
(`bootstrapAnnualParSwaps`), which is deliberately the textbook
pre-2008 setup; rebuilding it as two curves (OIS discounting, separate
projection) is the natural exercise on top, and the javadoc's
discount-factor framing is the piece that carries over unchanged.

### 57. "Eurodollar futures imply 3.20%, the FRA for the same period quotes 3.14%. Free money?"

No -- that gap is the **convexity adjustment**, and it is fair value.
A futures contract is margined daily: your P&L is settled in cash every
day, so when rates rise you *receive* variation margin and reinvest it
at the new higher rate, and when rates fall you post margin financed
at the new lower rate. The margining makes the futures P&L linear in
the rate, while a forward (FRA) settles once, discounted -- its PV is
convex in the rate. The daily-settlement timing works systematically
in favor of the futures *short*... so shorts accept a higher rate, and
the futures rate sits *above* the true forward: futures = forward +
adjustment. The adjustment grows with the rate variance accumulated to
expiry -- in a Ho-Lee world it is (1/2) sigma^2 t1 t2; in Hull-White it
carries a mean-reversion damping -- so it is negligible in the front
months (fractions of a bp) and real at the back: with sigma = 1%,
t1 = 4, t2 = 4.25, you get about (0.5)(0.0001)(17) ~ 8.5bp, which is
several ticks of a distant contract. Anyone building curve segments
off raw futures quotes without subtracting it embeds a systematic
upward bias in the forwards, which the swaps at the long end then
contradict -- a classic source of phantom "arbitrage" between the
futures strip and the swap curve.

*In this library:* `rates/ShortRateModels.java` -- the Vasicek /
Hull-White dynamics in which the adjustment is derived (sigma and
mean-reversion a are exactly its inputs), with the curve-fitted
Hull-White as the model desks actually use to compute it;
`rates/YieldCurve.java` `forwardRate` for the forward being adjusted
toward.

### 58. "The 2s10s curve just inverted. Your economist says recession. What is the mechanism, and how would you state the risk honestly?"

The mechanism: the 10-year rate is approximately the average of
expected future short rates plus a term premium. Inversion means the
market expects the central bank's *future* policy rates to sit well
below today's -- i.e., it prices cuts, and central banks cut into
weakness. So inversion is not a cause of recession; it is the bond
market's aggregated forecast that policy is currently tight enough to
produce one. The empirical record is unusually strong for a single
indicator: US 2s10s (or the 3m10y variant the Fed studies) inverted
ahead of essentially every US recession since the late 1960s, with
lead times of roughly 6 to 24 months -- 2006 before 2008 being the
canonical case, and 2019 before the 2020 downturn a debatable one.
Stating it honestly requires the caveats: the term premium is not
constant (QE-era distortions can invert measured spreads without the
same expectations signal, the standard critique of the 2022-23
inversion); the lead time is too variable to trade on timing; and with
so few recessions in sample, the "perfect record" is a handful of
observations. In curve-factor language, inversion is an extreme
reading of the slope factor -- the second Nelson-Siegel/PCA component
-- which is why "level, slope, curvature" is not just a compression
trick: the slope factor is the one carrying the macro forecast.

*In this library:* `rates/NelsonSiegel.java` (`shortRate`, `longRate`
-- their difference is the slope factor whose sign this question is
about), `risk/Pca.java` (slope as the second eigenvector, recovered
from data rather than assumed).

### 59. "A cap and a swaption both reference the same 5-year swap rate environment. Why can't you use one's vol for the other?"

Because they are options on *different underlyings* with different
optionality structure, even when the rates they reference overlap. A
cap is a strip of independent caplets: each caplet is an option on one
forward LIBOR/term-rate fixing, exercised (or not) period by period --
a portfolio of options. A (European) payer swaption is one option on
the whole forward *swap* rate -- an option on a portfolio (a basket of
the same forwards, weighted by the annuity). Options on a basket are
worth less than the basket of options, and the gap is governed by the
correlation between the forwards: perfectly correlated forwards would
close it; realistic decorrelation keeps cap vols and swaption vols
apart. That is why the market quotes them separately (a cap vol
surface by strike/expiry; a swaption cube by expiry/tenor/strike), and
why the ratio between them is precisely how desks *extract* implied
forward-rate correlation -- the cap/swaption relative-value trade is a
correlation position wearing vol clothes. Each quote is Black-76 by
convention, but on its own underlying: caplet vol on the forward rate,
swaption vol on the forward swap rate discounted by the annuity.
Plugging one into the other's formula produces numbers that look
plausible and are systematically wrong -- the rookie error the
question is screening for.

*In this library:* `rates/RatesOptions.java` -- caplet/cap and
swaption pricers side by side off the same `YieldCurve`, each taking
its own Black-76 vol, so the non-interchangeability is executable;
`pricing/Black76.java` underneath both.

### 60. "Two systems disagree by $40k on a $500m bond position. Trading blames your curve. It turns out to be day count. Convince me that's plausible."

Entirely plausible -- accrual conventions move real money at size. Day
count sets the year fraction between dates, and the conventions
genuinely disagree: ACT/360 makes a 365-day year accrue 365/360 =
1.39% *more* interest than ACT/365; 30/360 pretends every month has 30
days, so a Feb 28 to Mar 31 period differs from ACT by days of coupon.
On $500m of a 4% bond, one day of accrued interest is about $55k --
so a one-day disagreement in the accrual window, or a 360-vs-365
denominator mismatch on a few months of accrual, lands exactly in the
observed $40k range. The same class of bug hides in the business
calendar: whether a coupon dated on a Saturday rolls forward
(Following) or back (Modified Following when the roll crosses
month-end) changes the payment date, hence the accrual period, hence
the price. These are not exotic corner cases -- USD money markets are
ACT/360, US Treasuries ACT/ACT, many corporates 30/360, and swaps mix
conventions per leg, so any system that hard-codes one convention is
wrong on most instruments it touches. The deeper point: anyone
who wave this off as back-office trivia have not reconciled a
portfolio; the pedantry *is* the P&L, and the first question a rates
quant asks about any yield is "under which convention?"

*In this library:* `rates/DayCount.java` (the conventions, each
documented with when it applies), `rates/BusinessCalendar.java` (roll
rules -- its javadoc walks the weekend-roll-changes-the-payment case),
`rates/BondPricer.java` (the calendar-aware pricer where both plug in,
so the $40k experiment is directly runnable).

---

## Volatility & options theory (Q61-Q72)

### 61. "Implied vol has averaged a few points above realized for decades. Why does the gap persist -- and why can't you just harvest it naively?"

The gap is the **volatility risk premium**, and it persists because it
is compensation, not mispricing. Option buyers -- hedgers insuring
equity portfolios, structured-product desks covering short-gamma
exposure -- are systematically willing to pay above actuarial value for
protection, because options pay off exactly when marginal utility is
highest (crashes). The seller of that insurance earns a premium in
ordinary times and delivers a large payment in disasters; the average
gap of roughly 2-4 vol points on index options is the insurance
margin. Why naive harvesting fails: the P&L profile is short-tail --
years of steady income, then losses arriving in days. February 2018
("Volmageddon") is the cleanest exhibit: XIV, a short-VIX-futures
product, had compounded gains for years and lost ~95% in one session
when VIX spiked; short-vol funds in early 2020 repeated the lesson.
The premium is real *and* the strategy has crash-shaped risk, so
honest evaluation demands: sizing for the spike (vega that scales with
vol, not constant), measuring performance over windows that include a
disaster, and skepticism of any Sharpe computed between disasters --
which is the LLN-versus-fat-tails point wearing option clothes. The
premium also lives more in the wings (skew) than at-the-money, which
is why systematic sellers gravitate to strangles and why their tail
risk is even worse than the ATM version's.

*In this library:* `hedging/HedgingSimulator.java` -- hedging vol and
realized path vol are separate inputs precisely so the "sell implied,
realize less" P&L distribution is directly simulable, including its
tail (`hedging/HedgingErrorDistribution.java`);
`pricing/VarianceSwap.java` for the cleanest premium-harvesting
instrument.

### 62. "If Black-Scholes were right, the smile would be flat. It isn't. Walk me through why -- and why the smile appeared when it did."

Black-Scholes assumes returns are lognormal with constant volatility;
under that assumption, every strike of the same expiry must trade at
one vol, so implied vol plotted against strike would be a flat line.
The market disagrees in two persistent ways. **Fat tails**: real
return distributions put far more mass on extreme moves than the
lognormal -- a 20% index drop is a many-sigma impossibility under BS
but happened in a day in 1987 -- so OTM options (both wings) are worth
more than BS-at-ATM-vol says, bending the line up at the edges: a
smile. **Asymmetry**: equity crashes are bigger and faster than
rallies, and vol rises as markets fall (leverage effect), making OTM
puts worth more than symmetric-distance calls: the smile becomes a
skew, steep on the put side. History supports reading it as learned
crash-fear: pre-1987, index smiles were roughly flat; the skew
appeared after the October 1987 crash and has never left -- the market
repriced the left tail permanently once it saw one. Model-language
translations: stochastic vol (Heston) generates smile from vol-of-vol
and skew from spot-vol correlation; jumps (Merton) generate steep
short-dated smiles that flatten with maturity, matching the fact that
short-dated skew is the steepest. The one-line answer: the smile is
the market quoting a non-lognormal distribution through the only knob
BS gives it -- one vol per strike.

*In this library:* `pricing/VolSurface.java` (the smile as market
input, built from quotes or inverted from prices), `pricing/Heston.java`
and `pricing/SabrModel.java` (the two standard machines that
*generate* smiles from dynamics rather than storing them).

### 63. "Spot drops 2%. Does the 3400-strike option keep its vol, or does the 25-delta option keep its vol? Why does your delta depend on the answer?"

That is sticky-strike versus sticky-delta, and it is a claim about
smile *dynamics*, not statics. **Sticky-strike**: each fixed strike
keeps its implied vol as spot moves -- the smile is nailed to strike
space. **Sticky-delta (sticky-moneyness)**: vol attaches to moneyness,
so the smile slides along with spot and the 25-delta point keeps its
vol instead. Same smile today, different smiles tomorrow -- and your
delta already contains the choice: total delta = BS delta +
vega x (dSigma/dS), and dSigma/dS differs by regime. With equity skew
(vol falls as strike rises): under sticky-strike, a spot rally walks
you along the curve to lower vols, so calls are worth *less* than
constant-smile BS thinks -- your true delta is below BS delta; under
sticky-delta the smile moves with you and BS delta is closer to right.
Rule-of-thumb empirics a desk quotes: range-bound, orderly markets
behave sticky-strike; trending or stressed markets behave
sticky-delta-like, and in a crash the whole surface jumps anyway
(vol up, skew steeper -- neither pure rule). Concretely: a 1% rally on
a book with 100k vega per point of skew slope can move the hedge by
tens of thousands of deltas depending on the regime assumed -- which
is why sophisticated desks stop hand-picking a rule and use a model
with self-consistent smile dynamics: SABR's whole selling point in
rates is that it *prescribes* how the smile moves with the forward,
giving Greeks that do not depend on a folklore choice.

*In this library:* `pricing/SabrModel.java` (smile dynamics as model
output -- perturb the forward and re-read the smile),
`pricing/VolSurface.java` (the static surface on which either sticky
rule can be imposed and compared).

### 64. "VIX is at 45 and 1-month implied is 10 points over 6-month. What is the term structure telling you, and what trade does everyone get wrong here?"

Normal times: the vol term structure sits in **contango** -- short-dated
implied lowest, rising with maturity, because vol is mean-reverting
and long maturities average over the future's calm and storm plus a
risk premium. Stress inverts it into **backwardation**: the market
prices the *current* crisis as intense but temporary -- spot vol
enormous now, expected to mean-revert down over months. So an inverted
term structure is a statement of transience: 1-month at 45 over
6-month at 35 says "this is acute, not permanent." Mechanically it is
the same total-variance arithmetic as forward rates: implied variance
between months 1 and 6 is read from the difference of total variances,
and inversion means the forward vol strip is *below* spot vol. The
trade everyone gets wrong: shorting the front in backwardation because
"45 must come down." Vol can go to 80 first -- VIX touched ~80 in 2008
and again in March 2020 -- and the short-front position has its worst
losses precisely in the move from 45 to 80. Meanwhile the
systematic-roll crowd learns the mirror lesson: long-VIX-futures ETPs
bleed roll yield relentlessly in contango (buying expensive back
months, selling them cheaper as they roll down), which is why the
long-vol ETNs lose most of their value over any multi-year window.
The term structure is a mean-reversion forecast, not a mispricing --
trading it profitably means trading the *forward vol* against your
own view of the decay path, sized for the spike that hasn't happened
yet.

*In this library:* `pricing/VolSurface.java` (expiry interpolation is
done in total variance -- exactly the arithmetic that defines forward
vol and makes inversion precise), `volatility/VolatilityIndex.java`
(the VIX-style 30-day point being compared across expiries).

### 65. "VIX options trade on their own implied vol. What does 'vol of vol' mean, and why are VIX call skews upside-steep when equity skews are downside-steep?"

VIX options are options on forward VIX -- an option whose underlying
is itself a volatility. Their implied vol is therefore vol-of-vol: the
market's price for uncertainty about *volatility's own path*. Two
structural facts follow. First, volatility is far more violently
distributed than equities: it is bounded near some floor in calm
markets and can triple in days (VIX 12 to 80+ in 2008 and 2020), so
its risk-neutral distribution is heavily right-skewed. That flips the
skew: equity options are bid on the *put* side (crash down), VIX
options are bid on the *call* side (crash = vol up) -- the steep wing
points toward each underlying's disaster direction. A 2x-strike VIX
call is the cleanest crash hedge on the board, which is why tail funds
live in them and why their implieds are permanently rich. Second,
vol-of-vol is exactly the parameter that curves smiles everywhere
else: it is nu in SABR and sigma (the vol-of-variance) in Heston, and
the fact that VIX options trade *at all* -- with their own smile -- is
the market telling you nu is not only nonzero but stochastic itself.
Desk-level intuition worth quoting: variance-swap and VIX-option
desks are effectively making markets in the convexity of everyone
else's vega books, so vol-of-vol richens precisely when short-gamma
players are hurt -- it is correlated to its own demand shock, the same
wrong-way structure as equity skew one derivative up.

*In this library:* `pricing/Heston.java` (vol-of-vol as an explicit
parameter of the variance SDE), `pricing/SabrModel.java` (nu -- wing
curvature as vol-of-vol, testable by perturbation),
`volatility/VolatilityIndex.java` (the underlying these options
settle against).

### 66. "You bought a straddle at 16 vol and delta-hedge daily. Realized vol comes in at 20. Show me where the P&L actually comes from."

From gamma, harvested move by move. The delta-hedged long option's
daily P&L is (1/2) x Gamma x S^2 x (realized move^2 - implied move^2)
plus higher-order terms: theta paid per day is the rent,
(1/2) Gamma S^2 sigma_implied^2 dt exactly, and each day's actual move
earns (1/2) Gamma (dS)^2. Break-even move: at 16 vol, the daily
break-even is 16% / sqrt(252) ~ 1.0% -- move more, the day is a win;
move less, theta wins. Realizing 20 against 16 paid: your average day
moved ~1.26%, earning (1.26^2 - 1.00^2)/1.00^2 ~ 59% more gamma P&L
than rent each day. Worked number: on $10m of gamma-adjusted notional
with Gamma S^2 = $2m, a 1.26% day earns (1/2)(2m)(0.0159%) ~ $15.9k
gamma vs ~$10k theta -- roughly $6k/day of scalping profit. The two
honest caveats that separate real answers: (1) the P&L is weighted by
*where* gamma was when moves happened -- dollar gamma rides with spot,
so realizing 20 vol far from your strike pays little (the
strike-pinning problem); a variance swap exists precisely to pay
realized-minus-implied *unweighted*; (2) discrete hedging makes the
outcome noisy -- hedge daily and the realized-vs-implied edge arrives
with substantial path variance, hedge more often and you converge on
the edge but pay more costs. That trade-off has a shape you can
simulate rather than assert.

*In this library:* `hedging/DeltaHedger.java` (the rebalance-band
simulator: replication error vs transaction costs),
`hedging/HedgingSimulator.java` (hedge at 16, realize 20, read the
P&L distribution across thousands of paths),
`pricing/VarianceSwap.java` (the unweighted version of the same bet).

### 67. "Variance swap strike 21, ATM vol 19.5. Is someone wrong?"

No -- the gap is structural, and it has two stacked causes. First,
the variance strike is not an average vol; it is the square root of an
average *variance* across the whole smile: the replication weights OTM
options by 1/K^2, so the strike integrates the smile, loading heavily
on downside puts. With equity skew, wing vols sit well above ATM, so
K_var > sigma_ATM mechanically -- a steeper skew widens the gap with
no one mispricing anything. Rule of thumb desks quote: the variance
strike sits near the ~30-delta-put vol rather than ATM. Second,
convexity: variance-swap P&L is linear in *variance*, hence convex in
*vol* -- if vol doubles, the payout quadruples. A long variance
position therefore benefits from vol-of-vol (Jensen), and the strike
embeds a premium for that convexity; equivalently, a vol swap (linear
in vol) strikes *below* sqrt(K_var), the difference growing with
vol-of-vol. This convexity is also why the product blew up its
sellers in 2008: dealers short single-stock variance watched realized
vol print at multiples of strike and losses scale with the *square*
-- several desks lost enough that single-stock variance markets
effectively closed, and caps on realized variance became standard in
the product. So 21 vs 19.5 is the smile plus convexity premium,
readable off the option chain: recompute the 1/K^2 integral and the
"anomaly" reproduces to within the bid-ask.

*In this library:* `pricing/VarianceSwap.java` (`fairVariance` -- the
1/K^2 replication, delegating to the index integral),
`volatility/VolatilityIndex.java` (the same integral as a published
index), `pricing/VolSurface.java` supplies the smile that creates the
gap.

### 68. "Quick -- ATM straddle on a $100 stock, 25% vol, 3 months. No calculator."

The approximation to own: an ATM(-forward) straddle is worth about
0.8 x S x sigma x sqrt(T). Derivation in one line: each ATM leg under
Black-Scholes is approximately S x sigma x sqrt(T/(2*pi)) ~ 0.4 x S x
sigma x sqrt(T) (expand N(d1) - N(d2) around d = 0), and the straddle
is two legs. Here: 0.8 x 100 x 0.25 x 0.5 = $10. The reverse reading
is the one traders actually use: a quoted straddle price *is* a vol
quote -- straddle / (0.8 x S x sqrt(T)) recovers implied vol in your
head, which is how you sanity-check a broker quote or read an
earnings-move expectation off the screen (a $10 one-week straddle on
a $200 stock: 10/(0.8 x 200 x sqrt(1/52)) ~ 45% vol, i.e. the market
expects roughly a 10/200/2... call it a 2.5% weekly standard move).
Why it works so well: at the money, BS is nearly linear in vol (vega
is maximal and vol-convexity minimal), so the linear-in-sigma formula
is accurate to a percent or two for realistic inputs -- and that same
linearity is why ATM options are the natural vol-trading instrument:
their price is almost purely a vol position, with minimal
strike-dependent distortion. Follow-up you should expect: the
straddle's delta is not zero (each leg's d1 > 0 at-the-money-spot),
which is why FX quotes pin the *delta-neutral* straddle as ATM.

*In this library:* `pricing/BlackScholes.java` -- price the two legs
and verify 0.8 x S x sigma x sqrt(T) lands within ~1%;
`pricing/VolSurface.java` does the inverse reading (price to implied
vol) properly.

### 69. "You need tomorrow's vol for position sizing. GARCH says 14, implied says 19. Which do you use?"

Understand what each *is* before choosing. GARCH-family forecasts are
statistical extrapolations of realized history under the physical
measure -- excellent at capturing clustering and mean reversion, but
they only know what already happened; they cannot see tomorrow's CPI
print or an election. Implied vol is a *price*: the risk-neutral
expectation plus the volatility risk premium, incorporating forward-
looking information (event calendars most visibly -- implied jumps
ahead of earnings while GARCH is blind to them) but biased high on
average by the premium (the same 2-4 points from Q61). So 19 vs 14
decomposes into: forward-looking information GARCH lacks, plus premium
implied always carries. The empirical literature and desk practice
agree on the synthesis: implied is usually the better raw predictor
of next-period realized (information wins), but it is biased -- so
for *sizing*, an encompassing blend beats either: regress realized on
both, or take implied minus an estimated premium, or use HAR-RV
(realized-vol regressions at daily/weekly/monthly horizons), which is
the benchmark GARCH papers must beat and often the best pure-
statistical forecaster. The sharp practical rule: if implied is far
above GARCH, first check the calendar -- an event is usually the
explanation, and then implied is right and GARCH is wrong by
construction. If there is no event, you are being paid the premium to
take the other side, and sizing off ~15-16 with a stress overlay is
defensible.

*In this library:* `volatility/Garch11.java` (`forecastVariance` --
the statistical route), `volatility/HarRv.java` (the realized-vol
benchmark), `volatility/VolatilityIndex.java` (the implied,
model-free route); running all three on the same series is the whole
debate made executable.

### 70. "Equity vol rises when the market falls. Give me the two standard explanations, the evidence for which one is right, and what it does to your models."

The asymmetry is the **leverage effect**, and the two stories are: (1)
*financial leverage* (Black, 1976): a falling equity price raises the
firm's debt-to-equity ratio, so the equity becomes a riskier,
more-levered claim on the same assets -- vol rises mechanically; (2)
*volatility feedback / risk premium*: bad news raises expected future
vol, which raises the required return, which lowers the price *today*
-- causation running from vol to price rather than price to vol. The
evidence that the leverage story is incomplete: the effect is too
large and too fast for the balance-sheet mechanics (a 2% index drop
does not change leverage enough to move vol the observed amount), it
appears in assets with no debt at all, and it is much stronger at the
index level than for single names -- pointing to a risk-premium /
fear channel rather than accounting. Whatever the cause, the
modeling consequences are concrete: symmetric GARCH is misspecified
for equities -- GJR-GARCH adds a term that fires only on negative
returns, and on index data the fitted asymmetry parameter typically
*exceeds* the symmetric ARCH term (the asymmetry is most of the
effect, not a correction); EGARCH gets the same via a signed term in
log-variance. On the option side, the leverage effect *is* the skew:
negative spot-vol correlation is exactly rho < 0 in Heston/SABR, and
its sign and size are readable off the put wing. On FX, the fitted
asymmetry is near zero -- there is no "down" direction for an
exchange rate -- which is a clean falsification test for any story
that predicts asymmetry everywhere.

*In this library:* `volatility/GjrGarch11.java` (the javadoc makes
the gamma-vs-alpha magnitude point explicitly),
`volatility/Egarch11.java` (the log-variance version),
`pricing/Heston.java` / `pricing/SabrModel.java` (rho -- the same
effect priced into the smile).

### 71. "Your daily-close vol estimate says 12. The stock gapped 8% overnight on earnings twice this quarter. What is your estimator missing, and how do you fix it?"

It is missing the distinction between diffusion and jumps -- and
possibly the overnight session entirely. Two separate failures. First,
if the estimator uses intraday returns only (or close-to-open is
excluded), the overnight gap -- where a large fraction of total equity
variance lives, since earnings and macro news land outside trading
hours -- never enters at all: variance is understated for any purpose
that spans the close. Second, when the gap *does* enter a squared-
return estimator, it does the opposite damage: one 8% print enters as
r^2 = 64 daily-variance-units and reads as a volatility *regime
change* for the estimator's whole memory, contaminating weeks of
estimates with what was a single scheduled jump. The fix is to model
the two components separately, because they hedge and forecast
differently: **bipower variation** uses products of consecutive
absolute returns, (pi/2)|r_t||r_t-1|, instead of r^2 -- diffusion
moves both factors so the product still estimates sigma^2, but a
lone jump inflates only one factor of the pair and its influence is
bounded; the difference between realized variance and bipower
variation is then an estimate of the *jump* component. Desk
consequences: continuous vol is what gamma scalping harvests and
GARCH forecasts; jump risk is what you cannot delta-hedge (the
overnight gap crosses your strike while markets are closed -- there
is no rebalancing through it), which is why short-dated smiles
around earnings are steep and why market makers widen or flatten
into the close before announcements.

*In this library:* `microstructure/JumpRobustVolatility.java` -- the
bipower-variation estimator, with the news-gap-poisons-r-squared
failure mode described in its javadoc as the motivating case;
`volatility/HarRv.java` for the realized-vol framework the jump
split plugs into.

### 72. "Friday close to Monday close is three calendar days. Do options decay three days of theta over the weekend? What does realized data say?"

The clean empirical fact (French, 1980, and replicated since): the
Friday-to-Monday close-to-close return variance is only modestly
higher than a normal one-day variance -- roughly 1.1 to 1.2 daily
variances, nowhere near the 3.0 that calendar-time scaling predicts.
Volatility accrues with *trading* time, not calendar time: no market
hours, little variance -- which is evidence that trading itself (the
processing of dispersed information through order flow) generates
much of the variance, not just the arrival of news. Options markets
price this rather than debate it: if the weekend really cost three
calendar days of theta against ~1.1 days of realized variance, buying
Friday-expiring... rather, selling Friday and buying Monday would be
free money, so market makers use **weighted event/trading-time
calendars** -- weekends get a small weight, scheduled events
(earnings, CPI, FOMC) get extra variance-days -- and the implied vol
you see on screen for short-dated options already breathes with that
calendar: Friday-afternoon implieds on weekly options dip
mechanically ("weekend effect in vol") because the same option price
divided by more calendar-time-vol means a lower quoted sigma, then
recover Monday. For a candidate, the general principle is the
valuable part: sigma and t only ever appear as total variance
sigma^2 t, so *any* deterministic reweighting of time is equivalent
to a vol adjustment -- event calendars, weekends and holidays are
all the same trick. And honesty requires the caveat: the weekend
discount assumes no news; the gap risk of Q71 is exactly the
scenario where the 0.3-weight weekend delivers 3 days of variance.

*In this library:* `alpha/CalendarAnomalies.java` (`dayOfWeek` --
day-of-week return/variance profiles with the t-statistics that keep
the claims honest; its javadoc's whole point is that most published
calendar effects barely survive them), `pricing/VolSurface.java`
(total-variance time interpolation -- the object an event-weighted
calendar would plug into).

---

## Risk, deeper (Q73-Q85)

### 73. "Two loan books each have 1% VaR of zero. Merge them and the 1% VaR is positive. How is that possible, and why did regulators eventually care?"

Because VaR is not subadditive -- it can *penalize* diversification.
Construct it: each book holds one loan with a 0.8% default probability
losing $10m. At 99%, each book's VaR is zero (the loss sits beyond the
1% quantile). Merge them (independent loans): the probability of at
least one default is ~1.59% > 1%, so the merged 99% VaR is $10m.
Diversifying *raised* measured risk -- which violates the coherence
axioms (Artzner et al.): a risk measure should reward pooling, not
punish it. The deeper problem is the incentive: if VaR is the binding
constraint, desks are rewarded for *concentrating* tail risk into
sub-1% lumps (selling far-OTM options is the canonical trade: premium
income, zero VaR, catastrophic tail), and for splitting books to make
firm-wide numbers smaller. Expected shortfall -- the average loss
*beyond* the quantile -- is subadditive for continuous distributions,
sees the whole tail rather than one point on it, and cannot be gamed
by pushing losses just past the confidence level. That is precisely
why FRTB replaced 99% VaR with 97.5% ES as the market-risk capital
standard: the two calibrations match for normal distributions, but ES
keeps paying attention when the tail gets fat. The honest caveat a
strong candidate adds: ES is harder to backtest (it is not
elicitable), so the regime is ES for capital, VaR exceptions for
validation.

*In this library:* `risk/VarEngine.java` (`deltaNormalEs`,
`monteCarloVar` returning VaR and ES side by side -- the two-loan
counterexample is directly reproducible), `risk/FrtbEs.java` (`es975`
-- the regulatory replacement, liquidity horizons and all).

### 74. "Every major bank had a 99% 1-day VaR model in 2007. Explain, mechanism by mechanism, how they all failed at once."

Four stacked failures, each worth naming. (1) **The window was the
wrong regime**: models fed 1-2 years of Great-Moderation data had
never seen a funding crisis; historical VaR cannot exceed its own
sample, and parametric VaR fit to calm data had tiny sigmas. When the
regime broke, banks reported 10, 20, 30 VaR exceptions in a quarter --
UBS and others published exception counts in 2007-08 that a calibrated
99% model would produce once in millennia. (2) **Correlations went to
one**: diversification benefits computed from calm-period correlations
evaporated exactly when needed -- assets that decorrelated the book
suddenly moved together because the common factor was funding and
forced deleveraging, not fundamentals. (3) **The horizon lied**:
1-day VaR assumes you can exit or re-hedge tomorrow; structured-credit
positions could not be sold at *any* price for months, so the true
horizon was quarters, and losses compounded far beyond any 1-day
number times sqrt(10). (4) **The tails were fat and the models
Gaussian**: delta-normal machinery translated "99%" into 2.33 sigma,
while actual factor moves ran 5-10 calm-period sigmas. Meta-lesson to
state plainly: VaR answered the question it was asked -- "how bad is
an ordinarily bad day, judged by the recent past?" -- and management
had been reading it as "how bad can it get?" The post-mortem produced
stressed VaR (Basel 2.5), then stressed-calibrated ES with liquidity
horizons (FRTB): each patch encodes one of the four failures above.

*In this library:* `risk/VarEngine.java` (`historicalVar` -- run it
on a calm window, then replay `risk/StressTester.java`'s `lehman2008`
scenario against the same book and watch the gap),
`risk/VarBacktest.java` (the exception-count tests that flagged the
failure in real time), `risk/FrtbEs.java` (`stressCalibratedEs`,
`liquidityHorizonEs` -- the regulatory patches, one per mechanism).

### 75. "Your worst observed daily loss in 10 years is 6%. The board asks for the 1-in-1000-day loss. What is defensible and what is astrology?"

The honest boundary runs through extreme value theory. Astrology: (a)
reading the 99.9% quantile off the empirical distribution -- 10 years
is ~2,500 observations, so the 1-in-1000 point is supported by two or
three data points and the 1-in-10,000 by none; (b) fitting a Gaussian
and extrapolating -- normal tails decay like exp(-x^2/2), so the model
assigns essentially zero probability to moves that equity markets
deliver every decade or two (a 20-sigma day under Gaussian assumptions
"cannot happen in the life of the universe"; October 1987 was one).
Defensible: EVT. The Pickands-Balkema-de Haan theorem says exceedances
over a high threshold converge to a Generalized Pareto Distribution
for essentially any underlying law -- so you fit the GPD to the
tail you *did* observe (say, losses beyond the 95th percentile: ~125
points, a real sample) and extrapolate along the fitted tail shape
rather than along Gaussian fantasy. The shape parameter xi is the
verdict on the question itself: xi = 0 gives thin exponential tails;
equity-return fits typically give xi in the 0.2-0.4 range --
power-law tails where the 1-in-1000 loss is perhaps 1.5-2x the
1-in-20-year observed worst, not marginally above it. And EVT is
honest about its own limits: the confidence interval on the 99.9%
estimate is wide and should be reported with it, and a fitted xi >= 1
says the tail mean does not exist -- at which point the defensible
answer to the board is that number's refusal, not a number.

*In this library:* `risk/ExtremeValueTheory.java` -- `fitPot`
(peaks-over-threshold GPD fit), `GpdFit.var(0.999)` (the extrapolation
formula, documented in the javadoc), and the class throws rather than
print infinite-mean expected shortfall -- the "refusal" above is
implemented behavior.

### 76. "Design my stress-testing program. Historical, hypothetical, reverse -- what does each catch that the others miss?"

Three legs, deliberately non-overlapping. **Historical scenarios**
replay actual factor moves -- October 1987, Lehman September 2008,
COVID March 2020 -- through today's book. Strengths: internally
consistent by construction (these co-movements *happened*, no one can
call them implausible) and politically undeniable. Blind spot: only
disasters that already occurred, applied to a book that may have no
exposure shaped like 2008's. **Hypothetical scenarios** are committee-
designed: "China devalues 10%, oil +40%, credit gaps 200bp." Strengths:
forward-looking, tailorable to current concentrations and current
geopolitics. Blind spots: consistency (hand-picked shocks can violate
every correlation, or worse, sneak in implicit hedges that mute the
loss) and imagination -- the committee stresses what it can conceive,
and the 2008 lesson is that the killing scenario was in nobody's
deck. **Reverse stress testing** closes exactly that gap: fix the
loss that kills the firm and *solve* for the most probable scenario
producing it -- for a linear book under a factor covariance, that is
closed form along the gradient direction, and the scenario's
Mahalanobis distance says how many joint sigmas away death sits. A
book that dies at 2.5 joint sigmas has a problem no scenario list
would have surfaced. Program design around the legs: severity ladders
(don't just run the scenario, run 0.5x/1x/2x and look for
nonlinearity -- a book that loses 4x at 2x the shock is short gamma
somewhere), refresh cadence tied to book turnover, results wired to
limits rather than filed, and the discipline that scenarios are
specified in *factor* space so every desk prices the same world.

*In this library:* `risk/StressTester.java` -- `blackMonday1987`,
`lehman2008`, `covidMarch2020` (the historical leg), `scenarioPnl`
with the gamma overload (the nonlinearity check), `sensitivityLadder`
(the severity ladder), and `reverseStress` returning the shock vector
plus `mahalanobisSigmas` (the third leg, closed form).

### 77. "Two books, identical VaR. One is S&P futures, one is CCC bonds. Same risk?"

Obviously not, and the difference is liquidity -- the dimension plain
VaR does not have. VaR implicitly assumes you can exit (or at least
re-hedge) at the horizon at mid-market. The futures book can: it
liquidates in minutes at a fraction of a basis point. The CCC book
faces (1) an exogenous cost -- bid-ask spreads of points, not bps,
which *widen* exactly in stress -- and (2) an endogenous cost -- the
position may be many days of market volume, so exiting moves the
price against you, and fast exits cost more than slow ones while slow
exits carry more market risk (the Almgren-Chriss trade-off, now
inside the risk measure). Liquidity-adjusted VaR in its simplest form
adds half the stressed spread times position size; the honest version
scales the risk horizon per asset class instead: the loss that
matters is over the time it *actually takes to exit*, so illiquid
factors get 60-120 day horizons rather than 10. That is exactly the
structure FRTB adopted -- ES computed on a 10-day base and scaled up
across regulator-set liquidity horizons by risk-factor class (major
FX and rates 10 days, exotic credit 120), so the CCC book's identical
"10-day" risk becomes a multiple of the futures book's once each is
measured over its own exit horizon. The blow-up to cite: LTCM 1998 --
convergence positions whose *market* risk looked modest, in sizes
that were multiples of daily volume, so when spreads widened they
could not exit at any speed that mattered; the fund was killed by
the liquidity dimension its risk numbers did not carry.

*In this library:* `risk/FrtbEs.java` (`liquidityHorizonEs` -- the
per-horizon scaling formula, with the 10-to-120-day classes in the
javadoc), `microstructure/AlmgrenChriss.java` and
`microstructure/LiquidityMeasures.java` (the exit-cost machinery an
endogenous adjustment is built from).

### 78. "Your firm-wide correlation matrix looks fine, VaR is within limits -- and you still blow up like Archegos's brokers did. What did the risk system not see?"

Concentration -- and the fact that correlation-based measures assume
the *historical* joint distribution keeps holding at the size you now
are. Two distinct blind spots. First, plain concentration: VaR built
on a covariance matrix happily nets and diversifies, and a book that
is 40% one name can show moderate VaR if that name has been calm --
but the loss distribution is now hostage to idiosyncratic events the
covariance cannot forecast (fraud, a failed deal, a single earnings
gap). Concentration must be measured *separately* from correlation:
Herfindahl index, effective number of positions (a 20-name book with
one 40% position has an effective N of maybe 5), top-N shares,
single-name limits. Archegos is the exhibit: each prime broker saw
its own slice, leverage against a handful of names; the concentration
across brokers was invisible to every correlation matrix, and the
unwind of one oversized position *created* the correlated crash in
the others. Which is the second blind spot: at size, concentration
*becomes* correlation -- your own liquidation moves every name you
hold, so the calm-period correlations you measured are not the ones
you die at. Practical rule: correlation risk answers "what if the
market's joint behavior changes?"; concentration risk answers "what
if I am the market?" -- and a risk report needs both numbers because
each is designed to miss the other.

*In this library:* `risk/ConcentrationRisk.java` (`herfindahlIndex`,
`effectivePositions`, `topNShare`, `limitBreaches` -- the
non-covariance view), `risk/CorrelationMatrix.java` and
`risk/Dependence.java` (the covariance view it deliberately
complements), `risk/ComponentVar.java` to see how much of the VaR a
single name secretly carries.

### 79. "You face a counterparty on a 5-year swap, currently worth zero to you. Is your credit exposure zero? Draw me the profile."

No -- current exposure is zero, but exposure is an *option-shaped*,
forward-looking quantity. Credit exposure to a derivatives
counterparty is max(0, MtM): if they default when the trade is
against you, you still owe it (no gain); if they default when it is
in your favor, you lose the replacement value. So the number that
matters is **potential future exposure**: the high quantile (say
97.5%) of the trade's possible future MtM at each date. For the swap
the profile has a famous hump: exposure starts at zero, *rises* as
rate uncertainty accumulates (diffusion needs time to move the MtM),
then *falls* as remaining cash flows amortize toward maturity --
peaking around one-third to one-half of the way through. Contrast
the shapes a desk carries in its head: an FX forward's exposure
rises monotonically (single terminal exchange, diffusion all the
way); a cross-currency swap rises to the *end* (the notional
exchange at maturity dominates); an option you bought is exposure-
only (premium paid, always an asset). Then the mitigants, in order
of force: **netting** -- exposure is max(0, *net* MtM) per netting
set, so offsetting trades with the same counterparty collapse
(without netting you sum the positive sides only, which is why the
netting agreement is the single most valuable credit document);
**collateral/margin** -- cuts exposure to the gap risk over the
margin period; and the add-on shortcut -- notional times a
tenor-bucketed factor -- is the quick PFE approximation regulators
and pre-trade checks use when full simulation is too slow.

*In this library:* `risk/CounterpartyExposureTracker.java` --
`currentExposure` (max(0, net MtM) per netting set: netting is in
the definition), `potentialFutureExposure` and `addOnFactor` (the
tenor-bucketed add-on profile), `allExposures` for the
per-counterparty rollup that a credit officer actually reads.

### 80. "You bought CDS protection on a Russian bank from another Russian bank. Cheap, too. What is wrong-way risk, and where did it actually bite?"

Wrong-way risk is positive dependence between your *exposure* to a
counterparty and that counterparty's *probability of default*: the
trade is worth the most to you exactly when they are least able to
pay. The example in the question is the canonical **specific**
wrong-way trade: the CDS pays off precisely in the scenario (Russian
systemic stress) that also kills the protection seller -- so the
protection is worth far less than its price implies, which is *why*
it looked cheap. **General** wrong-way risk is the softer, pervasive
version: exposure and credit quality driven by a common macro factor
-- e.g., being owed money on received-fixed swaps by a leveraged
borrower whose default probability rises in the same recession that
drops rates and inflates your MtM. Where it bit: the monolines and
AIG in 2008 -- banks bought protection on super-senior CDOs from
insurers whose own solvency was wired to the same housing factor;
when the hedges finally paid, the sellers could not, and the "hedged"
banks took CVA losses in the tens of billions (a large share of
2008 credit losses were CVA markdowns, not defaults -- the hedge's
*value* collapsed with the seller's credit). Handling it honestly:
CVA with correlated exposure and default (a copula or joint
simulation, not independent modules), higher margins or outright
refusal for specific wrong-way trades (post-crisis rules effectively
ban self-referencing protection), and the desk-level screen: for
every large counterparty, ask "what world maximizes what they owe
me, and is their default probability higher or lower in that world?"
If higher, price it or decline it.

*In this library:* `risk/CounterpartyExposureTracker.java` (the
exposure leg -- what the dependence multiplies),
`risk/GaussianCopula.java` and `risk/Dependence.java` (the joint-
tail machinery a correlated exposure-default model is built from);
the wrong-way overlay is the natural exercise combining them.

### 81. "Explain a margin spiral to me like I'm the CFO -- then tell me why prudent risk management at each firm made it worse."

The mechanics, step by step. Volatility rises; margin models
(clearinghouse initial margin, prime-broker haircuts, VaR-based
limits) are calibrated to recent volatility, so required margin
jumps. Levered holders must post more cash *now*; those who cannot,
sell positions. Forced selling moves prices down and pushes realized
volatility up further -- which raises margin again, catching the
next tier of holders. Add the correlation twist: forced sellers sell
what they *can*, not what they should, so the liquid, unrelated
assets fall too and the spiral spreads across markets that share
holders rather than fundamentals. That is the fire-sale externality:
each firm's margin call is individually prudent -- collect collateral
from a shakier counterparty, cut a limit as VaR rises -- but the
*system-wide* effect of everyone tightening procyclically is to
manufacture the very stress being defended against. Brunnermeier and
Pedersen formalized it as the liquidity spiral: market liquidity and
funding liquidity feed on each other in both directions. Real
instances to cite: 1998 LTCM (haircut increases forcing convergence-
trade unwinds that widened the spreads further); March 2020, when
even Treasuries fell as the basis trade was margin-called and CCP
initial margins jumped double-digit percentages in days; the 2022
UK LDI episode, where gilt-collateral calls forced gilt sales in a
falling gilt market until the central bank stepped in. Mitigants
worth naming: anti-procyclical margin (floors, stressed-period
calibration, buffers drawn down in stress rather than hiked),
liquidity buffers sized to survive the *margin call*, not just the
P&L, and reverse-stressing your own funding: "what vol level makes
me a forced seller?" is a number every levered book should know.

*In this library:* `risk/StressTester.java` (`sensitivityLadder` and
`covidMarch2020` -- the shock side of the loop),
`risk/PreTradeLimitChecker.java` (the limit machinery whose
procyclical tightening is the mechanism in miniature); the funding
reverse-stress is `reverseStress` pointed at your own margin
constraint. See also docs/MARKET_RISK.md section 10 for where
scenario design meets funding.

### 82. "Your Heston calibrates beautifully and your VaR backtests clean. Why does the bank still make you register both in a model inventory with independent validation?"

Because fitting well is not the same as being right, and the people
who built a model are structurally the wrong people to judge it --
they chose the assumptions, so they cannot see them. Model risk
management (formalized in the Fed's SR 11-7 after 2008) rests on
three planks. (1) **Inventory and tiering**: know every model in
production, ranked by materiality -- the pre-2008 embarrassment was
banks discovering they could not even list the models their capital
depended on. (2) **Independent validation**: someone with no stake
re-derives the math, tests degenerate limits (does Heston collapse
to Black-Scholes when vol-of-vol goes to zero?), benchmarks against
an independent implementation (Monte Carlo vs semi-analytic), probes
the domain of validity (Hagan's SABR expansion is an approximation
that breaks at long expiry and high vol-of-vol -- *where*?), and
attacks the inputs, since most "model" failures are data failures.
(3) **Ongoing monitoring**: models decay silently as markets move
away from the calibration regime, so validation is a lifecycle, not
a gate -- backtesting, benchmarking, and P&L attribution watching
for growing unexplained residue. The named catastrophe: the Gaussian
copula for CDO correlation -- a formula so tractable it became
market infrastructure, whose flat-correlation assumption failed
precisely in the joint-tail region the product existed to tranche;
validation that tested tail dependence rather than calibration fit
would have flagged it. The London Whale is the process version: a
VaR model change (implemented in error-prone spreadsheets) halved
reported risk and let positions double -- model *governance*, not
model math, was the failure. Clean backtests are necessary, never
sufficient.

*In this library:* the practice is visible in the tests --
`pricing/Heston.java`'s BS-degenerate-limit and Monte Carlo cross-
checks (the validation playbook of Q15 *is* independent validation
in miniature), `risk/VarBacktest.java` (ongoing monitoring),
`risk/PnlAttribution.java` (the unexplained-P&L residue monitor).
See docs/MARKET_RISK.md section 13 (Model Validation) for the
program-level view.

### 83. "Design the limit structure for a new options desk. Why is a VaR limit alone not enough -- and why is a position limit alone not enough either?"

Because every limit type has a blind spot, and the structure works
only as overlapping coverage. **VaR/ES limits** aggregate everything
into comparable currency units and let the firm allocate a risk
budget top-down -- but VaR is model-dependent (wrong correlations,
wrong tails), backward-looking, and slow: it cannot stop a fat-finger
in the milliseconds that matter, and a short-gamma book can sit
inside its VaR limit while building a cliff just past the confidence
level. **Greek limits** (net and gross delta, gamma, vega per bucket)
are model-light and fast, and they see exactly the nonlinearity VaR
smooths over -- a gamma limit is the direct answer to the
short-tail-options game from Q73 -- but they do not aggregate across
desks and say nothing about names. **Position/notional and
concentration limits** are the crudest and the most robust: they
assume nothing, so they still bind when every model is wrong -- the
last line when correlations go to one. **Pre-trade controls** (max
order size, notional, price collars, restricted lists, counterparty
caps) are the only layer that acts *before* the trade exists; Knight
Capital 2012 -- $460m in 45 minutes from an un-gated deployment -- is
the permanent argument that post-trade risk reporting cannot
substitute for pre-trade gates. Design principles: hard limits with
defined escalation (a limit that can be quietly breached is a
suggestion); coverage so that gaming one layer trips another (the
short-gamma trade that hides from VaR hits the gamma limit; the
per-name buildup that hides from Greeks hits concentration);
utilization monitored, because a desk pinned at 95% of every limit
has no room to hedge in stress; and each limit owned by someone who
can *halt* trading, not just report.

*In this library:* `risk/PreTradeLimitChecker.java` (order size,
notional, position, price collar, counterparty, restricted symbols
-- the pre-trade layer as a builder), `hedging/OptionsBook.java`
(the Greek aggregation the Greek limits read),
`risk/ComponentVar.java` (allocating the VaR budget so desk limits
sum to the firm's), `risk/ConcentrationRisk.java` (`limitBreaches`
-- the per-name layer).

### 84. "Your 99% VaR model shows 5 exceptions in 250 days. The regulator's table says that's fine. Why might it still be a bad model?"

Because exception *count* is only half the test -- the other half is
exception *timing*, and the count test itself has weak power. Start
with Kupiec's proportion-of-failures test: 250 days at 99% expects
2.5 exceptions; 5 looks high, but the likelihood-ratio test does not
reject at 95% confidence -- with so few expected events, the binomial
is noisy, and Kupiec at 250 days will wave through models that are
materially wrong (this is a feature of the sample size, not the
model: the Basel traffic-light zones exist precisely because the
test is weak). Now the part the table misses: suppose all 5
exceptions landed in the same two weeks. A model can be right *on
average* and wrong in every crisis -- underestimating risk when
volatility rises, overestimating in calm, netting to a fine count.
Christoffersen's independence test targets exactly this: under a
correct model, exceptions are i.i.d. Bernoulli -- yesterday's
exception must not predict today's -- so clustered exceptions fail
the LR independence test even when the count passes, and the
combined (conditional-coverage) test requires both. Clustering is
the signature of a VaR that adapts too slowly (long unweighted
windows), and it is the practically dangerous failure: the days VaR
is wrong are consecutive, at the worst time, compounding. Complete
honesty in VaR backtesting also means: test at multiple confidence
levels (a model can pass at 99% and fail at 95%), keep the
evaluation strictly out-of-sample, and remember you cannot
meaningfully backtest what you have not lived through -- 250 clean
days containing no stress validate nothing about stress.

*In this library:* `risk/VarBacktest.java` -- `test` returns Kupiec
POF and Christoffersen independence LR statistics in one result,
with `calibrated`, `independent` and `passes` as separate verdicts
so the count-fine-but-clustered case is directly visible;
`risk/FrtbEs.java`'s `TrafficLight` implements the regulatory
zone mapping.

### 85. "Your model says the desk is over its limit. The head trader -- who makes $50m a year for the firm -- says the model is wrong. Walk me through what you actually do."

The honest answer has a process, because both sides are sometimes
right and the failure modes are asymmetric. First: **the limit binds
now**. Escalation happens with the exposure controlled, not while
debating -- a limit that can be argued into abeyance is not a limit,
and every post-mortem of a risk blow-up features the phrase "the
desk explained why the model overstated the risk." Second:
**investigate as an empirical question, fast**. The trader may well
be right -- models are wrong constantly, and a risk function that
never concedes loses credibility it will need later. Concretely:
re-price the disputed positions independently; check whether the
model's inputs are stale (a vol surface that did not update, a
correlation from the wrong regime); look at P&L attribution -- if
the desk's actual P&L variance is inconsistent with the model's
risk number, the *data* say who is right; check whether the
disagreement is a known model limitation (documented domain of
validity) or a new one. Third: **decide at the right level, on the
record**. If the model is wrong, fix it, document the override with
an expiry date -- permanent informal overrides are how model risk
becomes invisible. If the model is right, the position comes down,
and that requires the risk function to have real authority:
reporting lines outside the revenue chain, and management that
enforces the occasional expensive "no." The cases to cite: the
London Whale, where the desk's objections to the risk numbers led
to the *model* being changed to accommodate the positions --
backwards; and Archegos at Credit Suisse, where limit breaches were
escalated and then persistently waived. The exercise is not
testing whether you can win an argument with a trader; they are
testing whether you understand that risk management is the
institutional design that makes the argument unnecessary.

*In this library:* `risk/PnlAttribution.java` (the explained-vs-
unexplained P&L split that arbitrates the dispute with data),
`risk/VarBacktest.java` (the model's track record, which is the
context any override decision should be made in),
`risk/PreTradeLimitChecker.java` (limits as enforced code paths
rather than reports). See docs/MARKET_RISK.md sections 13-14 for
the validation-to-production governance chain.

---

---

## Derivatives desk round (Q86-Q98)

### 86. "Walk me up the Greeks -- what does each one mean to a trader, not a textbook?"

Delta is your share-equivalent position, and it is *probability-ish*:
N(d2) is the risk-neutral probability of finishing in the money, and
N(d1) -- the call's delta -- is that same probability under the stock
measure, so an ATM call has delta near 0.5 because it is roughly a coin
flip. Gamma is how fast that share position runs away from you: it is
the convexity you own, and you pay rent for it through theta -- the
Black-Scholes PDE says theta ~ -0.5 * gamma * S^2 * sigma^2, so gamma
and theta are the same trade with opposite signs. Vega is your exposure
to the implied-vol mark, and it has a term structure: vega scales like
S * sqrt(T), so a 1-year option has roughly 3.5x the vega of a 1-month
option, which is why long-dated vol trades are vega trades and short-
dated vol trades are gamma trades. A desk example that makes it click:
the day before an earnings print, a 1-week ATM straddle is almost pure
gamma/theta (huge daily rent, huge convexity), while the 1-year straddle
on the same stock barely notices the event and just marks off the vol
surface. Strong candidates volunteer the follow-up: delta is NOT the
probability of exercise -- N(d1) > N(d2) always, and for long-dated or
high-vol options the gap is large.

*In this library:* `pricing/BlackScholes.java` (all first-order Greeks
with N(d1)/N(d2) separated), `pricing/HigherOrderGreeks.java` (vanna,
volga, charm -- the second rung of the ladder),
`pricing/IncrementalGreeks.java`.

### 87. "Explain gamma scalping. Where is the breakeven?"

Buy a straddle, delta-hedge it mechanically. Every time the stock moves,
your delta drifts away from zero and re-hedging locks in a small profit:
buy low, sell high, forced on you by the hedge. The profit per re-hedge
is about 0.5 * gamma * (dS)^2 -- quadratic in the move, which is the
convexity paying out. The cost is theta: you bleed rent every day. The
breakeven is exactly where realized volatility equals the implied vol
you paid: with an implied of 16%, the daily breakeven move is
16% / sqrt(252) ~ 1.0% -- the famous "16 vol = 1% a day" desk rule. Move
more than 1% a day on average and the scalping P&L beats the theta
bill; move less and you bleed. This is why long-gamma desks *love*
choppy, directionless markets and hate quiet grinds: direction is
irrelevant, only the size of the wiggles matters. Classic desk story:
in the week after a vol spike, implieds often stay bid while realized
collapses -- the long-gamma book that looked brilliant during the spike
quietly gives it all back in theta.

*In this library:* `hedging/DeltaHedger.java` (the re-hedging loop and
the P&L decomposition), `hedging/HedgingSimulator.java` (run the same
straddle at different realized vols and watch the breakeven appear).

### 88. "Why is selling a straddle 'short gamma, long theta'? What's the failure mode?"

The straddle seller collects two premiums and profits if the stock sits
still: every day that passes, both options decay -- that is the long
theta. The price is negative convexity: as the stock rallies, the
seller's delta goes increasingly short exactly when the market is going
up; as it sells off, the delta goes long into the fall. Hedging that
means buying high and selling low -- gamma scalping run in reverse, a
guaranteed small loss per move, which is exactly what the theta is
compensation for. The failure mode is that the loss is quadratic in the
move while the gain is capped at the premium: a 3-sigma day costs nine
times what a 1-sigma day costs. The canonical incident is February 5,
2018 ("Volmageddon"): short-vol products like XIV had collected small
theta-like gains for years, then VIX roughly doubled in a day and XIV
lost ~95% overnight and was terminated. Picking up nickels in front of
a steamroller is the cliche; the precise phrasing is "short
gamma positions have unbounded, accelerating losses funded by bounded,
linear income."

*In this library:* `hedging/OptionsBook.java` (aggregate a short
straddle and watch the book's gamma and theta signs),
`hedging/HedgingSimulator.java` (simulate the short-straddle hedge
through a jump and see the quadratic loss).

### 89. "It's expiry day and the stock is sitting on the strike. What is pin risk?"

At expiry, an option's delta collapses to a step function: 0 just
out-of-the-money, 1 just in. With the stock pinned at the strike, gamma
becomes enormous -- a few cents through the strike flips your
share-equivalent position from 0 to 100% of notional. For the *short*
option holder the problem is worse than hedging: it is uncertainty of
assignment. If the stock closes within a penny of the strike, you do
not know whether the longs will exercise (some exercise slightly OTM
options for hedging reasons, some fail to exercise ITM ones), so you do
not know whether you will wake up Monday with a stock position. The
desk practice: flatten or roll short near-strike positions before the
close, or hedge to an assumed exercise probability and accept the
residual. There is also the pinning phenomenon itself -- stocks with
large open interest at a strike statistically gravitate toward it on
expiry Friday, because long-gamma market makers' re-hedging pushes the
price back toward the strike from both sides. Ask any index desk about
a quarterly triple-witching close: the last ten minutes are a fight
between expiring hedges.

*In this library:* `pricing/BlackScholes.java` (evaluate gamma with
days to expiry -> 0 and watch it spike at the strike),
`hedging/OptionsBook.java` (book-level delta uncertainty when a strike
is pinned).

### 90. "When do you exercise an American option early? Calls vs puts."

Never exercise an American call on a non-dividend stock: exercising
throws away remaining time value and pays the strike earlier than
necessary, forfeiting interest on K -- sell the option instead.
Dividends flip it: immediately before an ex-dividend date, a deep-ITM
call may be worth more dead than alive, because exercising captures a
dividend the option holder never receives. The test is roughly
"dividend > remaining time value + interest on K for the stub period"
--
concretely, dividend-capture desks screen deep-ITM calls the night
before every large ex-date, and failing to exercise when optimal is
simply handing money to whoever is short. Puts are the mirror image and
need no dividend: exercising a deep-ITM put pays you K *now*, and
interest on K can exceed the tiny remaining optionality, so American
puts have an early-exercise premium even on non-dividend stocks -- the
premium grows with rates, which is why the American-European put gap
was negligible in the 2010s zero-rate era and reappeared when rates
rose in 2022-23. In a binomial tree all of this is one line: value =
max(continuation, intrinsic) at every node; the tree discovers the
exercise boundary.

*In this library:* `pricing/BinomialTree.java` (the AMERICAN max at
each node), `pricing/DividendSchedule.java` (discrete dividends -- the
input that creates call early exercise).

### 91. "Why do barrier options blow up hedgers near the barrier?"

Because the Greeks explode there. Take a reverse knock-out: a call that
dies if spot rises through H. Just below the barrier the option is
worth a lot (deep ITM) but one tick higher it is worth zero -- the value
function has a cliff, so delta near the barrier can exceed 100% of
notional and flip sign, and gamma is effectively unbounded. A
delta-hedger must hold a huge position that must be dumped the instant
the barrier trades: hedging becomes a game of chicken with the market,
and the act of hedging (selling a large delta as spot approaches H) can
itself push spot into the barrier -- or defend it, which is where the
market-manipulation accusations around "barrier defense" come from. The
famous macro incident: January 15, 2015, the SNB abandoned the EUR/CHF
1.20 floor; the pair fell ~30% in minutes, tearing through years of
accumulated barrier and stop-loss levels with no liquidity, and several
FX brokers (Alpari UK) and dealers took losses far beyond any model's
"hedged" P&L. Standard mitigations a candidate should name: barrier
shifting (price and hedge to a barrier slightly beyond the contractual
one) and static replication with vanillas, both of which trade a known
cost for the unhedgeable cliff.

*In this library:* `pricing/BarrierOption.java` (closed-form knock-outs
-- plot delta as spot approaches the barrier),
`pricing/TouchOption.java` (the pure-cliff payoff whose hedge is the
worst case).

### 92. "Describe the risk profile of an autocallable book. Why do dealers fear a crash?"

An autocallable pays a fat coupon and redeems early the first time the
underlying is above a call level on an observation date; if it never
autocalls and the underlying finishes below a knock-in barrier, the
investor eats the downside like a short put. The investor is short a
down-and-in put and short the autocall optionality in exchange for
coupons; the dealer carries the mirror image and hedges it dynamically.
In calm or rising markets the book is easy: notes autocall within a
year, risk evaporates, reissue. In a crash everything goes wrong at
once and nonlinearly: autocall probability collapses, so the expected
life of every note extends from ~1 year to maturity -- vega, dividend
and rates exposure balloon precisely when hedges are most expensive;
spot approaches the knock-in cliff, where gamma explodes like any
barrier; and every dealer holds the same position on the same
underlyings (HSCEI, Eurostoxx, Nikkei, Korean single names), so hedge
flows are crowded and one-directional. This is not hypothetical: the
2015-16 HSCEI selloff hammered Korean autocallable issuers, and in
March 2020 European houses -- Societe Generale, Natixis, BNP -- reported
hundreds of millions in structured-equity losses as autocall books
extended and the dividends they were long were cancelled outright. The
one-line answer: dealers are structurally short vol-of-vol and short
crash, and the product's popularity means everyone is short it the same
way.

*In this library:* `pricing/Autocallable.java` (Monte Carlo pricer --
reprice the same note with spot 20% lower and watch the expected life
and vega jump).

### 93. "What is a quanto, and where does the correlation adjustment come from?"

A quanto pays off on a foreign asset but settles in your currency at a
*fixed* conversion rate: the classic is a Nikkei option that pays in
USD, one dollar per index point, so the investor gets Japanese equity
exposure with zero FX exposure. The FX risk does not vanish -- it moves
to the dealer, and hedging it changes the drift. The dealer hedging a
quanto holds the foreign stock, whose *domestic* value is S * FX; when
the stock and the exchange rate are correlated, the hedge P&L picks up
a systematic cross-term, and no-arbitrage forces the quanto forward to
grow at r_foreign - rho * sigma_S * sigma_FX rather than the plain
forward rate. Intuition check worth pushing on: if the
Nikkei tends to rally when the yen weakens (rho < 0 against USD/JPY
value of the payout currency), the dealer's stock hedge is worth fewer
dollars exactly when it needs to be worth more, and the quanto drift
adjustment compensates -- get the sign wrong and you misprice every
quanto on the book by a term that compounds with maturity. Quantos are
everywhere retail structured products reference foreign indices, which
is most of them, so this is bread-and-butter for any exotics desk.

*In this library:* `pricing/QuantoOption.java` (the drift adjustment
-rho * sigma_S * sigma_FX implemented and documented -- the javadoc
works the Nikkei-in-USD example).

### 94. "Walk me through the lifecycle of a structured product from the dealer's side."

Day one: structuring picks the payoff (say an autocallable on an index
basket), pricing marks it with a margin -- the note sells at 100 while
the hedge portfolio costs, say, 97.5, and that 2.5 is the day-one P&L
the desk books against a lifetime of hedging obligations. At issuance
the desk puts on the initial hedge: delta in futures, vega in listed or
OTC options, dividends and rates hedged or warehoused. Then the note
*lives*: every observation date is an event (autocall or not), barriers
must be monitored continuously, corporate actions on basket members
need term-sheet interpretation, and the Greeks drift daily so the hedge
is rebalanced within bands that trade transaction costs against risk.
The aging book is the real business: thousands of notes, each with its
residual risk, aggregated into one risk ledger that the desk hedges
net rather than note-by-note -- offsetting exposures cancel internally
and only the residual goes to the street, which is precisely the
central-risk-book economics. Finally, secondary market: clients want
early exits, the desk buys notes back at model value minus a spread,
and unwinding the associated hedge is itself a trade. The
point most people miss: the day-one margin is not profit until the
last note dies -- March 2020 clawed back years of autocallable margins
in weeks.

*In this library:* `pricing/Autocallable.java` (issuance pricing),
`hedging/GreekHedger.java` and `hedging/OptionsBook.java` (the
aggregated running hedge), `crb/CentralRiskBook.java` (netting the
aging book's residual risk).

### 95. "You can only re-hedge daily, not continuously. What does that cost you?"

Black-Scholes replication is exact only with continuous re-hedging;
discretely, each interval leaves an unhedged second-order term
0.5 * gamma * S^2 * (dS/S)^2 whose expectation theta already paid for
but whose *variance* is yours to keep. The standard result (Derman and
Kamal's rule of thumb): hedging N times over the option's life leaves a
P&L standard deviation of roughly sqrt(pi/4) * vega * sigma / sqrt(N)
-- proportional to the option's vega and shrinking only like one over
the square root of the hedge count. Double your hedging frequency and
you halve the variance, not the standard deviation: going from daily to
hourly hedging on a 1-month option cuts the error by ~sqrt(24) ~ 5x,
at 24x the transaction costs. That trade-off is the actual desk
problem, and the actual desk answer is hedge-to-band, not
hedge-on-schedule: let delta drift inside a no-trade band and rebalance
at the edges. Whalley-Wilmott derive the asymptotically optimal band
width -- it scales like (cost / risk-aversion)^(1/3) * gamma-dependent
term -- which formalizes what every options trader does by feel: hedge
wide when costs are high and gamma is low, tight when a pin or a
barrier concentrates gamma. Expected follow-up: "so is hedging error a
vol bet?" Yes -- discretely hedged P&L realizes (realized vol - implied
vol) noise, which is why hedged books still have vol-of-vol risk.

*In this library:* `hedging/HedgingSimulator.java` (same option, vary
hedge frequency, watch the P&L distribution tighten like 1/sqrt(N)),
`hedging/HedgingErrorDistribution.java` (the full distribution and its
tails), `hedging/WhalleyWilmott.java` (the optimal no-trade band).

### 96. "Put-call parity looks violated on your screen. Do you trade it?"

Almost certainly not -- first find the carry you forgot. Parity,
C - P = df * (F - K), is model-free, so a genuine violation is free
money via a conversion (buy stock, buy put, sell call) or a reversal
(the mirror). In practice, apparent violations are almost always one of
four things. Dividends: an unexpected dividend change moves the forward
-- check the estimate before celebrating. Borrow: if the stock is
hard-to-borrow, the reversal requires shorting stock you cannot borrow
cheaply, and the "violation" is exactly the borrow cost -- during the
2008 short-sale ban, puts on financials looked wildly expensive against
parity, and that premium *was* the price of unobtainable borrow, not an
arbitrage. Early exercise: American options break textbook parity into
an inequality, so a deep-ITM "violation" may be an exercise you should
have done. Stale quotes: one leg's screen price is not tradeable size.
The professional framing: parity deviations are not arbitrage signals,
they are *implied carry quotes* -- desks read implied borrow and
implied dividends off exactly these relationships all day.

*In this library:* `pricing/BlackScholes.java` (price both legs and
check the parity identity yourself), `pricing/DividendSchedule.java`
(the dividend input that explains most screen "violations").

### 97. "What is a box spread, and why do rates desks trade options to lend money?"

A box is a synthetic zero-coupon bond built from options: bull call
spread plus bear put spread on the same strikes pays exactly K2 - K1 at
expiry, whatever the stock does. Its price today is therefore
df * (K2 - K1), and the discount it trades at *is* an interest rate --
the options market's secured lending rate. Desks and even corporates
use long SPX boxes to lend and short boxes to borrow, often at rates
competitive with repo, because SPX options are European, cash-settled,
and centrally cleared: the credit risk is the clearinghouse's. The two
famous cautionary tales: first, boxes on *American*-style options are
not riskless -- early assignment tears one leg out of the structure.
The 2019 Robinhood "1R0NYMAN" incident is the canonical example: a
retail trader sold boxes on American-style options believing the
position was risk-free, got assigned early, and turned ~$5k into a
~$58k loss -- roughly a -2000% return on the "riskless" trade. Second,
even European boxes carry rates risk before expiry: a long box is long
duration, and 2022's hikes marked long-dated boxes down like any bond.
This question tests whether you see options as
payoff LEGO rather than directional bets.

*In this library:* `rates/YieldCurve.java` (turn the box price into a
discount factor and compare the implied rate to the curve),
`pricing/BlackScholes.java` (price the four legs and verify the payoff
is flat in S).

### 98. "When is the American feature actually worth paying for?"

Price the spread, do not memorize a slogan. The American premium over
European is the value of the exercise boundary, and it is material in
exactly three situations. Deep-ITM puts when rates are meaningful: the
premium scales with interest on the strike over remaining life -- at
zero rates (2010-2021) American and European puts were near-identical
and desks stopped thinking about it; at 5% short rates a 1-year
deep-ITM put's American premium is worth real basis points again. Calls
facing large discrete dividends: the premium concentrates entirely at
ex-dividend dates and is bounded by the dividends themselves -- no
dividends, no premium, sell not exercise. Options on futures with
futures-style vs equity-style margining: Black-76 assumptions shift and
some exchanges' American options on futures carry almost no premium
because there is no carry advantage to early exercise. The professional
check is always the same: run the same contract through a binomial or
LSMC pricer twice, European and American, and look at the difference --
if it is under the bid-ask spread, the distinction is academic; if it
is not, the exercise boundary is a real risk you must monitor daily,
because failing to exercise optimally is a pure transfer to your
counterparty (market makers systematically harvest retail's failure to
exercise dividend-capture calls).

*In this library:* `pricing/BinomialTree.java` (price EUROPEAN vs
AMERICAN on identical inputs -- the difference IS the answer),
`pricing/Black76.java` (the futures-option variant),
`pricing/DividendSchedule.java` (discrete dividends driving the call
premium).

---

## Market structure & execution, deeper (Q99-Q112)

### 99. "Limit, market, IOC, FOK, pegged, iceberg -- when do you use each?"

A limit order states your price and waits: it earns the spread but risks
non-execution and adverse selection (it fills exactly when someone
thinks your price is wrong). A market order demands liquidity now,
paying the spread and any depth you walk through -- use it when the
cost of *not* trading exceeds the cost of impact, e.g. hedging a fill
you just received. IOC (immediate-or-cancel) is a marketable limit that
takes what is there up to your price and cancels the rest -- the
workhorse of smart order routers, because it caps price while never
resting (no queue, no information leakage from a standing order). FOK
(fill-or-kill) adds all-or-nothing, used when a partial fill is
worthless -- one leg of a multi-leg arbitrage that only works at full
size. Pegged orders float with the market (midpoint peg being the dark
pool staple) so you keep price priority without re-quoting -- but a peg
is a free option for anyone faster than the reference feed you peg to.
Icebergs display a sliver and hide the rest, trading queue priority
(each refill goes to the back) for reduced signaling on size. The desk
answer ties them together: an execution algo is mostly a policy for
choosing among these, order by order.

*In this library:* `execution/IcebergOrder.java` (display/refill
mechanics and the priority cost), `execution/MidPegTracker.java`
(pegging and its stale-reference risk), `orderbook/HftOrderBook.java`
(the matching semantics all of these hit).

### 100. "Trace an order from your strategy to a fill. Every hop."

Strategy decides; the order goes first through pre-trade risk -- in the
US this is not optional, SEC rule 15c3-5 requires checks (fat finger,
price collars, position and credit limits) *before* the order leaves,
in-line, not asynchronously. Then the OMS/gateway assigns a client
order id, encodes it (FIX for most flows, binary native protocols like
OUCH for latency-sensitive ones), and writes it to the session with a
sequence number so it can be recovered. At the exchange: the gateway
validates the session and symbol, its own risk layer runs (port-level
limits set by the clearing member), then the order is sequenced --
given a place in the single total order of events -- and hits the
matching engine, which either matches it against resting orders
(generating trades, at the resting orders' prices) or rests it in the
book. Every outcome fans out twice: a private execution report back
down your session, and anonymous public market data (an add, or trades
plus book deltas) to everyone. Why it gets asked: each hop is a
failure mode. Knight Capital, August 1, 2012, is the canonical answer
-- a dead code path reactivated by a partial deploy sent millions of
unintended child orders; the missing hop was a working pre-trade risk
gate and a kill switch, and the bill was $460M in about 45 minutes.

*In this library:* `trading/HftRiskGate.java` (the in-line pre-trade
check), `trading/HftOrderGateway.java`, `fix/NewOrderSingle.java` and
`fix/FixSession.java` (the encoded, sequenced session),
`orderbook/HftOrderBook.java` (the matching step itself).

### 101. "Price-time vs pro-rata matching -- how does the allocation rule change behavior?"

Price-time (FIFO) fills the oldest order at the best price first: queue
position is everything, so it rewards being early, penalizes
cancelling, and creates the latency race -- the profitable skill is
joining a new price level first and *leaving* a stale level first.
Pro-rata (used in CME short-rate futures like SOFR, and options
markets) allocates each incoming aggressor across resting orders in
proportion to their size: time in queue barely matters, so it rewards
*size* instead -- and creates its own game: quote far more than you
want, because you receive fills proportional to displayed size, then
manage the overfill risk. The over-quoting equilibrium is well known --
books in pro-rata markets show inflated depth that would never all
trade -- which is why real allocation rules are hybrids: CME's
short-rate contracts give a top-of-book time-priority allocation to
whoever set the new best price first, then pro-rata the remainder, to
keep some incentive for price discovery. The follow-up is
"which market structure do HFTs prefer?" -- price-time, because speed
is their edge and queue position is a durable asset; in pro-rata,
capital (size) substitutes for speed.

*In this library:* `orderbook/HftOrderBook.java` (strict price-time
priority, exchange-style), `microstructure/QueueModel.java` and
`microstructure/QueuePositionEstimator.java` (why queue position is an
asset worth modeling under FIFO).

### 102. "Why do opening and closing auctions exist, and what is imbalance data?"

Continuous trading is bad at concentrated, lumpy demand: at the open,
overnight information must be aggregated into one price; at the close,
index funds and derivatives all need the *official* closing print. An
auction batches orders over a window and clears them at the single
price that maximizes executed volume -- no spread, no time priority
race, one price for everyone. The close has become the biggest
liquidity event of the day: on major expiry and rebalance days the
closing auction can print 10%+ of full-day volume in one trade, because
anything benchmarked to the close (index funds tracking S&P changes,
MOC-benchmarked algos) must trade there. Imbalance data is the feed
that makes it tradeable: exchanges (e.g. Nasdaq's NOII) publish, in the
minutes before the cross, the indicative clearing price and the excess
of buy over sell interest -- and liquidity providers trade against the
imbalance, offsetting it in continuous trading or the auction itself,
which is why huge MOC imbalances usually clear with modest price moves.
Cautionary example: August 24, 2015, chaotic opens -- many NYSE stocks
opened late or at dislocated prices, ETFs traded far from NAV because
their constituents had no opening prints yet, and LULD halts cascaded;
it is the standard case study for auction fragility feeding back into
continuous markets.

*In this library:* `microstructure/Auction.java` (the volume-maximizing
clearing-price algorithm plus indicative price/imbalance),
`microstructure/ClosingAuctionModel.java` (imbalance-aware close
modeling), `execution/BenchmarkExecutor.java` (executing against the
close benchmark).

### 103. "Explain maker-taker and payment for order flow. Where are the conflicts?"

Maker-taker: the exchange charges the aggressor (taker) ~30 mils a
share and rebates most of it to the resting order (maker), keeping the
difference. It subsidizes displayed liquidity, but it creates a routing
conflict: a broker choosing venues pockets the rebate while the
*client* eats the queue -- high-rebate venues have the longest queues
and the worst adverse selection, so routing to them is often good for
the broker and bad for the fill. That conflict is why Rule 606 routing
disclosures and the (litigated, stalled) SEC transaction-fee pilot
exist. PFOF is the off-exchange cousin: wholesalers (Citadel
Securities, Virtu) pay retail brokers for their marketable flow and
fill it internally at slight price improvement versus the NBBO. The
economics work because retail flow is *uninformed* -- a wholesaler
filling it faces far less adverse selection than quoting on an exchange
against everyone, so it can profitably quote better than NBBO and still
pay the broker. Critique: the price improvement is measured against a
NBBO that is itself wider because that benign flow never reaches the
lit market; defense: retail demonstrably gets better prices than
on-exchange execution would give. The GameStop episode (January 2021)
put the whole plumbing on television -- zero-commission brokers funded
by PFOF, and Robinhood's trading restrictions (driven by clearinghouse
margin, widely misread as wholesaler pressure) triggered congressional
hearings on exactly this question.

*In this library:* `regulatory/BestExecutionAnalyzer.java` (measuring
execution quality against the quoted benchmark -- the number this whole
debate is about), `crb/InternalizationEngine.java` (the economics of
filling flow internally).

### 104. "Why do dark pools exist, and what is the adverse-selection problem in them?"

They exist because displaying size moves price: an institution needing
to sell 5% of ADV cannot rest it on a lit book without every
participant adjusting against it. A dark pool crosses orders with no
pre-trade transparency, typically at the NBBO midpoint -- no spread
paid, no information leaked until the print. The catch is *who you
meet in the dark*. If the counterparty mix includes fast, informed
traders, your resting midpoint order fills exactly when the midpoint
is about to move against you -- you sell at mid the moment mid is about
to drop. That is adverse selection, and it is measurable: mark every
dark fill out 1-60 seconds and toxic pools show systematically negative
markouts. Pool operators segment and score participants for exactly
this reason, and buy-side desks maintain venue scorecards and cut pools
whose markouts rot. The area is also compliance-heavy because the
operator can cheat: Barclays LX settled for $70M (2016) over
misrepresenting how much predatory HFT flow was in the pool while
marketing it as safe, and Pipeline (2011) was fined for filling client
orders from its own affiliated prop desk. The complete answer
names both halves: dark pools reduce *impact* for size but concentrate
*selection* risk, and the trade-off is measured in markouts, not
promised in marketing.

*In this library:* `execution/DarkPoolSimulator.java` (midpoint
crossing with informed-flow adverse selection built in),
`execution/VenueScorecard.java` (the markout-based venue ranking a real
desk keeps).

### 105. "What is internalization, and when should a firm internalize instead of route?"

Internalization is filling a client's order from your own book rather
than sending it to the street. Every internalized share saves the
half-spread plus exchange fees twice over -- and, deeper, lets
naturally offsetting client flows cancel: if one client buys what
another sells within minutes, routing both to the exchange pays the
spread twice and moves the price for nothing. This is the economics
behind retail wholesalers and behind every bank's central risk book:
aggregate all desks' flows, net internally, and send only the residual
to the market. The decision rule is a comparison of costs: internalize
when expected cost of warehousing the resulting inventory (risk over
the expected holding period until offsetting flow arrives) is less
than the market-access cost saved (spread + fees + impact). That makes
it flow-dependent: benign, high-frequency two-way flow (retail) is
ideal to internalize; toxic, one-way flow (a client who is always right
just before the market moves) should be routed out immediately -- which
is precisely why internalizers score and segment their flow, and why a
CRB skews its quotes to *attract* the offsetting side when inventory
builds. Regulatory footnote worth volunteering: internalization must
still deliver best execution, and in equities the wholesaler must match
or improve NBBO -- internalizing is a cost decision, not a license to
fill at worse prices.

*In this library:* `crb/InternalizationEngine.java` (the
internalize-or-route cost comparison, explicitly),
`crb/SkewedQuoter.java` (skewing to attract offsetting flow),
`crb/CentralRiskBook.java` (the netting layer itself).

### 106. "Walk me through the 2010 flash crash and what LULD does about it."

May 6, 2010: with markets already stressed, a large institutional
seller (Waddell & Reed) executed ~$4.1B of E-mini S&P futures via a
volume-participation algo with no price limit; HFT market makers
absorbed inventory, hit position limits, and began passing the same
contracts among themselves ("hot potato") while liquidity evaporated;
the selling cascaded into the cash market through arbitrage, and the
Dow fell roughly 1,000 points in minutes. The ugliest prints were stub
quotes -- placeholder quotes at $0.01 and $99,999 that were never meant
to trade -- so Accenture printed at a penny and P&G collapsed double
digits before rebounding within minutes. Some 20,000 trades were later
busted as "clearly erroneous." The regulatory response: single-stock
circuit breakers, replaced by Limit Up-Limit Down -- each stock gets a
moving price band (typically 5-10% around a rolling reference price);
orders outside the band cannot execute, and if the market sits at the
band limit for 15 seconds the stock halts for 5 minutes. Market-wide
breakers (7%, 13%, 20% on the S&P) halt everything -- and the 7% level
actually fired four times in March 2020, working as designed. Stub
quotes were banned; market-maker quotes must now be within a band of
the NBBO. The lesson candidates should state: the crash was a
liquidity-withdrawal spiral, and the fix was mechanical speed bumps
that force time for liquidity to return, not a ban on any strategy.

*In this library:* `microstructure/CircuitBreakers.java` (LULD bands,
reference-price tracking, and market-wide halt levels implemented per
the specification).

### 107. "Why does tick size matter, and what is a good queue position actually worth?"

Tick size sets the economics of the queue. A wide tick (spread pinned
at one tick, like most sub-$50 liquid US names) makes the half-spread a
meaningful prize, so everyone wants to be passive, queues at the inside
grow enormous, and *time priority becomes the scarce resource* -- the
game is joining a level early and having the discipline to stay. A tiny
tick fragments the queue: undercutting by a meaningless increment
(penny-jumping) is cheap, priority is worthless, and depth thins out.
The SEC's Tick Size Pilot (2016-2018) tested widening ticks to 5 cents
for small caps hoping to improve liquidity; the measured result was
higher trading costs and no liquidity benefit -- the canonical evidence
that wider is not simply better. Queue value follows directly: a fill
at the front of a thick queue earns the half-spread with low adverse
selection (front-of-queue fills happen in benign two-way flow), while a
fill at the back happens disproportionately when the level is about to
break -- the whole queue trades through only when an informed sweep
takes it out. So the value of a queue slot is roughly
P(favorable fill) * half-spread - P(adverse fill) * expected loss,
front slots having a much better ratio. This is why HFTs treat queue
position as inventory, why cancelling and rejoining is costly, and why
pro-rata markets (Q101) have entirely different games.

*In this library:* `microstructure/TickSizeSchedule.java` (venue tick
regimes), `microstructure/QueuePositionEstimator.java` (inferring your
slot from L2 data), `microstructure/QueueModel.java` (fill probability
as a function of position).

### 108. "How do you measure adverse selection? Define a markout."

A markout is the mark-to-market of a fill at a fixed horizon after it
happened: for a buy at price p, the h-second markout is mid(t+h) - p,
signed so positive means the market moved your way. Average markouts
across fills at several horizons (100ms, 1s, 10s, 60s) and you have an
adverse-selection curve: passive fills almost always show *negative*
short-horizon markouts -- you get filled precisely when someone with
better information crosses the spread at you -- and the depth and decay
of that dip tells you who you are trading against. Fast decay to zero:
benign noise flow. Deep and persistent: informed flow, and your quoted
spread must cover it or you are the product. Desks use markouts
everywhere: market makers score client flows (an FX LP scorecards each
client's markouts to set spreads or decline flow), execution desks
score *venues* (Q104 -- a dark pool with rotting 1s markouts gets cut),
and prop firms score their own passive fills to price queue value.
Classic pitfall worth naming: markouts must be measured
against a mid that excludes your own trade's impact, and horizon choice
matters -- a market maker recycling inventory in 5 seconds does not
care about the 5-minute markout.

*In this library:* `microstructure/TransactionCostAnalyzer.java`
(per-fill markouts at multiple horizons against prevailing mid),
`fx/LpScorecard.java` (the client/LP flow-toxicity scorecard built on
exactly this measurement).

### 109. "What are the standard TCA benchmarks, and how does each get gamed?"

Arrival price (implementation shortfall): fill price versus the mid at
decision time -- the honest one, because it charges you for delay,
impact, and opportunity cost. VWAP: fill price versus the market's
volume-weighted average over the interval -- popular because it is easy
to beat *by construction*: trade proportionally to the volume curve and
you converge to it. That is also how it is gamed: a VWAP-benchmarked
trader has no incentive to trade well, only to trade *like the
average*, and large orders that themselves dominate volume drag the
benchmark toward their own fills, making terrible executions look
neutral. Close benchmark: gamed by concentrating your execution at the
close -- you nail the benchmark while worsening the very price you are
benchmarked to, which is one reason closing auctions keep growing
(Q102). Participation (POV) benchmarks: gamed by the cap itself --
volume spikes drag your algo into trading exactly when everyone else
is. The professional summary: every benchmark is a contract, and the
algo optimizes the contract, not your P&L -- so serious desks evaluate
on arrival-based shortfall decomposed into spread, impact, timing, and
opportunity legs, and treat VWAP as a client-communication device
rather than a quality measure.

*In this library:* `microstructure/TransactionCostAnalyzer.java`
(arrival, interval-VWAP, and per-fill mid benchmarks side by side),
`execution/BenchmarkExecutor.java` and `execution/VwapScheduler.java`
(the algos that target them -- read the scheduling logic and the gaming
becomes obvious).

### 110. "Why does a VWAP order underperform arrival on a trending day, and what are participation caps for?"

A VWAP algo spreads execution across the whole day following the volume
curve. If the price trends against you -- you are buying and the stock
grinds up all day -- every later slice fills worse than arrival, and
the average fill lands roughly at the day's VWAP, which on a trending
day is far through the arrival price. You will have *beaten the VWAP
benchmark* and still paid, say, 80bps of shortfall versus decision
price: the benchmark hid the cost, it did not remove it. This is the
core scheduling trade-off: front-loading (IS-style schedules from
Almgren-Chriss) minimizes exposure to drift but concentrates market
impact; stretching out (VWAP) minimizes impact but maximizes timing
risk. The optimal schedule depends on urgency -- your alpha decay and
risk aversion -- which is exactly the parameter an IS scheduler exposes
and a plain VWAP hides. Participation caps (e.g. "never exceed 10% of
volume") are the safety rail on all of this: they bound your footprint
so impact stays in the modeled regime, prevent the algo from becoming
the market in an illiquid name (the flash-crash seller's algo had a
participation target and *no price limit* -- the cap alone did not save
anyone, Q106), and control signaling, since sustained high
participation is detectable and front-runnable.

*In this library:* `execution/VwapScheduler.java` versus
`execution/ImplementationShortfallScheduler.java` (run both through a
trending tape and compare shortfall), `execution/PovTracker.java`
(participation measurement and capping),
`microstructure/AlmgrenChriss.java` (the urgency-optimal schedule).

### 111. "Defend last look. Now attack it."

Defense: FX is a quote-driven market where LPs stream prices to
hundreds of clients simultaneously over feeds they cannot update
atomically. Without a final validity check, every quote is a free
option to whoever has a faster market-data path -- latency arbitrageurs
would pick off stale quotes systematically, LPs would widen spreads to
price in that toxicity, and everyone else would pay more. Last look --
a final price check in the milliseconds between client request and LP
acceptance -- lets LPs quote tighter to everyone by rejecting only the
deals that are stale-price arbitrage. Attack: last look is a free
option *for the LP*. Held asymmetrically, the LP rejects when the price
moved against it and fills when the price moved its way -- heads I win,
tails you re-request at a worse price. Add a hold time ("additional
hold" beyond the technical check) and the LP is warehousing a
zero-premium option on every request; add pre-hedging during the hold
and it is trading on the client's information before deciding. This is
not hypothetical: Barclays paid $150M to the NY DFS in 2015 over its
last-look practices, and BNP Paribas and others followed; the FX Global
Code (2017, tightened since) now requires disclosure, symmetric
application, and no trading on the information during the window. The
grown-up desk position: symmetric, zero-additional-hold last look with
published reject statistics is defensible; anything else shows up in
your reject-rate and post-reject markout analysis, so measure your LPs.

*In this library:* `trading/LastLookGate.java` (a symmetric price-check
gate, implemented the way the Code says it should be),
`backtest/LastLookExecution.java` (what rejects do to your strategy's
realized P&L), `fx/LpScorecard.java` (reject rates and post-reject
markouts per LP -- the accountability tooling).

### 112. "FX has no exchange. Walk me through the plumbing an equities person doesn't know: the WMR fix, NDFs, and Herstatt risk."

Three pieces. The WM/Refinitiv 4pm London fix is the daily benchmark
rate used to value trillions in indices and funds; it is computed from
trades/quotes in a short window, which made it a target: from 2013 the
"Cartel" chat-room scandal revealed dealers at major banks sharing
client fix orders and coordinating trading into the window to push the
print. Combined FX-manipulation settlements ran over $10B across
Barclays, Citi, JPMorgan, RBS, UBS and others, five banks pleaded
guilty to US charges in 2015, and the fix window was widened from one
minute to five to make it harder to push. Executing fix orders is now
its own discipline: slice into the window, benchmark to the print,
monitor footprint. NDFs exist because some currencies (INR, KRW, TWD,
BRL, CNY) cannot be freely delivered offshore: a non-deliverable
forward is agreed on a notional and forward rate, and at maturity only
the *difference* between the forward and the fixing settles, in USD --
full economic FX exposure with no restricted-currency delivery, which
is why NDF pricing embeds offshore/onshore basis and fixing risk.
Herstatt risk is why CLS exists: in 1974 Bankhaus Herstatt was closed
by regulators after receiving Deutschmarks but before paying out
dollars in New York -- counterparties lost the full principal, not a
spread. CLS settles both legs payment-versus-payment: either both legs
move or neither does, removing principal settlement risk for the
currencies it covers; anything settled outside PvP still carries the
1974 lesson.

*In this library:* `execution/WmrFixingScheduler.java` (fix-window
execution scheduling), `fx/FixingRisk.java` (exposure to the fixing
print), `fx/Ndf.java` (the cash-settled forward, with the fixing
mechanics), `risk/SettlementRiskAnalyzer.java` (Herstatt exposure --
the javadoc tells the 1974 story).

---

## Backtesting & research process (Q113-Q122)

### 113. "What is survivorship bias, and how big is the damage in real numbers?"

It is testing today's universe on yesterday's data: every company that
went bankrupt, delisted, or was acquired at a discount has been quietly
removed, so the backtest only ever trades the winners. The magnitudes
are documented and large. Elton, Gruber and Blake measured roughly
0.9% per year of overstated mutual fund performance from dropping dead
funds; for hedge fund databases, Malkiel and Saha put the combined
survivorship and backfill bias at several percent per year -- funds
that blow up stop reporting, and funds that start reporting backfill
only their good early years. In equities the classic trap is running a
strategy on the *current* S&P 500 constituents back through 20 years:
you have pre-selected 500 survivors and future index entrants, and
value or distressed-looking signals become spectacular because every
cheap stock in your sample, by construction, recovered -- the ones that
went to zero are not in the file. Enron, Worldcom, and Lehman simply do
not exist in a survivorship-biased dataset. The fix is point-in-time
universe membership: on each historical date, trade only what you could
have known existed and held, including the stocks that later died, with
their delisting returns (often -30% to -100%) charged to the strategy.

*In this library:* `data/PointInTimeUniverse.java` (date-stamped
membership so the backtest sees the universe as it was, dead names
included).

### 114. "Look-ahead bias: give me the sneaky forms, not the obvious one."

The obvious form -- using today's close to trade at today's close --
everyone catches. The sneaky forms: restated fundamentals -- data
vendors overwrite history, so the "Q3 earnings" in the file are the
*restated* number published months later, not what the market saw on
the announcement date; strategies conditioned on clean fundamentals are
trading on corrections that had not happened yet (point-in-time
fundamentals databases exist precisely because backtests on restated
data are inflated). Index membership: using "S&P 500 members" as a
filter leaks the addition announcement -- membership on date t was
partly decided by performance after your signal date, and index adds
jump on announcement. Reporting lags: fundamentals dated to fiscal
quarter-end were not public until the filing, weeks later -- align to
filing dates or lag conservatively. Corporate-action adjustments:
split- and dividend-adjusted price files bake future adjustment factors
into past prices; harmless for returns, lethal for anything using price
*levels* (a "$5 stock" filter sees post-adjustment ghosts). Even vol
targeting can leak, if today's position is scaled by a vol estimate
whose window includes today. The test a good researcher applies to
every input column: "on the morning of date t, could I have queried
exactly this value?" -- if the answer needs the word "eventually," it
is look-ahead.

*In this library:* `data/PointInTimeUniverse.java` (as-of-date
membership queries), `data/CorporateActions.java` (explicit adjustment
handling instead of silently pre-adjusted files).

### 115. "You tried 200 strategy variants and the best has a Sharpe of 2. Impressive?"

Not until you correct for the search. The maximum of N independent
noise Sharpes grows like sqrt(2 * ln N) / sqrt(T): over a few years of
daily data, the best of 200 *pure-noise* backtests routinely shows a
Sharpe well above 1 -- your 2 might be skill, but the burden of proof
scales with how hard you searched. This is data snooping / multiple
testing, and finance is unusually exposed because the same few decades
of returns get mined by everyone (Harvey, Liu and Zhu catalogued 300+
published "significant" factors and argued the t-stat hurdle should be
3+, not 2, once you account for the profession's collective search).
The honest tools: record every variant you tried (the denominator is
the whole grid, not the survivors you remember); use White's reality
check / Hansen's SPA-style logic to test the best-of-family against the
family size; and compute the probability of backtest overfitting via
CSCV -- split the sample into blocks, select the best variant on each
in-sample combination, and measure how often that winner falls in the
bottom half out-of-sample. A PBO near 50% says your selection process
is a coin flip. The one-liner: the backtest is an experiment,
and the p-value belongs to the *search procedure*, not to the winning
strategy.

*In this library:*
`backtest/validation/OverfitProbability.java` (CSCV / PBO, per Bailey,
Borwein, Lopez de Prado and Zhu),
`backtest/validation/GridSearchOptimizer.java` (the honest grid --
every trial recorded so the denominator is real).

### 116. "What is the deflated Sharpe ratio, and when has it changed a real decision?"

The deflated Sharpe ratio (Bailey and Lopez de Prado) asks: given how
many strategies were tried, the variance across their Sharpes, the
track-record length, and the return distribution's skew and kurtosis,
what is the probability that the observed Sharpe exceeds the best
Sharpe you would expect from pure noise? Mechanically it deflates twice:
first it raises the benchmark from zero to the expected maximum of N
trials (the sqrt(2 ln N)-type term from Q115), then it widens the
Sharpe's own standard error for fat tails and negative skew -- crucial,
because strategy styles with the prettiest Sharpes (short vol, carry,
liquidity provision) have exactly the negative skew that makes their
Sharpe estimates least reliable; a Sharpe of 1.5 from a
negatively-skewed weekly strategy over three years can carry less
evidence than 0.8 from a symmetric daily one over ten. Where it changes
decisions in practice: allocation committees use it as a gate --
a pod's backtest Sharpe of 2.5 from a 400-variant sweep can come out
with a DSR indistinguishable from zero, killing the launch, while a
modest but long, out-of-sample-consistent record clears the bar. It is
the formalization of the folk wisdom "I don't believe backtested
Sharpes above 2" -- with the threshold made a function of exactly the
things people otherwise hand-wave: trials, tenure, tails.

*In this library:* `backtest/validation/SharpeValidation.java`
(deflated Sharpe and the probabilistic Sharpe ratio it builds on --
plug in your trial count and watch the verdict change).

### 117. "Walk-forward vs cross-validation for time series -- which do you trust?"

Standard K-fold CV shuffles, and shuffling destroys time: the model
trains on data from *after* the test fold, which is fine for i.i.d.
samples and cheating for markets (autocorrelated features, regimes,
overlapping labels). Walk-forward respects the arrow of time: fit on an
expanding or rolling window, trade the next out-of-sample block, roll
forward, and concatenate the out-of-sample blocks into one honest
equity curve -- it is a simulation of how the strategy would actually
have been researched and deployed, which is why practitioners default
to it. Its weaknesses: it uses data inefficiently (early data is only
ever training, late data only ever testing), a single path means the
verdict can hinge on which regime landed in the test years -- a
strategy walk-forward-validated over 2012-2019 met 2020 with no
evidence either way -- and iterating on walk-forward results until they
look good quietly turns the out-of-sample period in-sample. CV's
efficiency can be rescued for time series with purging and embargoes
(Q118), giving multiple train/test splits that are each temporally
legitimate. The mature answer: use purged CV for model and
hyperparameter selection where you need statistical efficiency, then a
final untouched walk-forward as the deployment rehearsal -- and treat
*that* result as spent the moment you look at it.

*In this library:* `backtest/validation/WalkForwardAnalyzer.java`
(rolling refit with concatenated out-of-sample results),
`backtest/validation/PurgedKFold.java` (the CV variant that is legal on
time series).

### 118. "Why does ordinary K-fold leak on financial data, and what do purging and embargo do?"

Because financial *labels overlap in time*. If your label is "the
return over the 10 days after t," then an observation at t and another
at t+3 share seven days of the same future: put one in the training
fold and the other in the test fold and the model has literally been
trained on part of the test answer. Shuffled K-fold does this
constantly, which is why models that are pure noise can post excellent
CV scores on financial data -- the classic symptom is a strategy whose
CV Sharpe is stellar and whose live Sharpe is zero. Purged K-fold
(Lopez de Prado) fixes it surgically: for each test fold, *purge* from
the training set every observation whose label window overlaps the test
fold's label windows; then add an *embargo* -- drop a further buffer of
training observations immediately after the test period, because serial
correlation in features and volatility leaks information even without
literal label overlap. The cost is a modest loss of training data; the
benefit is that a good CV score becomes evidence instead of an
artifact. A good follow-up probe: "your CV accuracy dropped from
0.62 to 0.51 after purging -- what happened?" The desired answer: the
0.62 *was the leak*; 0.51 is what your model actually knows, and you
just saved yourself from deploying it.

*In this library:* `backtest/validation/PurgedKFold.java` (purge +
embargo splits, with the overlap logic in the javadoc -- the class
exists because of exactly this question).

### 119. "Your strategy backtests beautifully over ten years. Why might it still be one regime's lucky child?"

Because ten years can be one macro regime wearing different clothes.
A strategy fitted 2010-2019 lived entirely inside falling-and-zero
rates, QE, low inflation and a grinding equity bull -- carry, short
vol, buy-the-dip and long-duration proxies all look like alpha in that
sample and are all the *same bet* on regime continuation. The
graveyard examples are standard: short-vol strategies compiled years of
smooth returns and died on one day in February 2018 (Q88); momentum
posted decades of strength and then lost ~70% in three months in the
2009 "momentum crash" as the junk rally inverted every trailing signal;
carry trades earned steadily until 2008 unwound them in weeks. The
research process answer: test across regimes explicitly -- split
performance by identified vol/rate/trend states rather than by
calendar; if returns come from one state, you own a regime bet and
should size and market it as one. Detect regimes with something honest
(rolling vol buckets, HMM-style classifiers), stress the strategy on
the historical windows that look least like the fit sample (1994, 2000,
2008, 2013 taper, 2020, 2022 -- the rates regime change that broke a
decade of bond-equity correlation assumptions), and prefer parameters
stable across states over parameters optimal in one. "Works in all
regimes" is rare; "knows which regime pays it" is the realistic bar.

*In this library:* `ml/RegimeDetector.java` (state classification to
condition performance on), `backtest/validation/BlockBootstrap.java`
(resampled paths that preserve regime clustering instead of shuffling
it away).

### 120. "Your backtest ignores costs. Add them honestly -- and then tell me the strategy's capacity."

Honest costs have three layers. Spread: every taker execution pays the
half-spread; at 5bps a side, a strategy turning over 100% weekly pays
roughly 5% a year before impact -- this single line item kills most
high-turnover retail backtests. Impact: the standard square-root law,
cost ~ sigma * sqrt(Q / ADV) in volatility units, meaning trading 1% of
daily volume in a 2%-vol stock costs on the order of a few basis
points, and 10x the size costs sqrt(10) ~ 3.2x, not 10x -- concave, but
relentless. Slippage on the model's own assumptions: passive fills you
assumed at the touch actually fill with adverse selection (Q108).
Capacity follows from inverting the impact term: as AUM grows, the
strategy trades a larger fraction of ADV, impact rises with the square
root of size, and net alpha crosses zero at a computable AUM -- that
crossing is the capacity estimate. Worked example: a signal worth 20bps
per trade on names doing $50M ADV, needing 5% of ADV per trade at 2%
daily vol, pays roughly sigma * sqrt(0.05) ~ 45bps of vol-units impact
scaled to ~9bps -- half the alpha at that size; push participation
higher and the strategy eats itself. This is why capacity, not Sharpe,
prices a strategy: a Sharpe-3 signal capped at $20M is a bonus, not a
business.

*In this library:* `backtest/TradeCostModel.java` (spread + commission
layers), `microstructure/MarketImpactModel.java` (the square-root law),
`backtest/ExecutionAwareBacktester.java` (the backtest that charges all
of it and prints gross-vs-net side by side).

### 121. "The strategy is live and losing. How do you decide between 'normal drawdown' and 'dead' -- and what does paper-vs-live slippage tell you?"

Decide *before* launch, in writing: given the backtest's return/vol, a
Sharpe-1 strategy spends a third of its life in drawdown and a 20%
drawdown is a when-not-if -- so pre-register the statistical kill line
(e.g. "drawdown whose probability under the backtest distribution is
under 1%") and the review triggers. Then separate the two failure
modes, because they have different evidence. Normal drawdown: losses
within modeled size, hit rate and per-trade P&L near historical, losses
explained by a regime the strategy is known to dislike. Model decay:
the *edge metrics* rot before the P&L does -- rolling IC trending to
zero, hit rate drifting from 54% to 50%, per-trade alpha shrinking
while costs hold, crowding signs (your entries increasingly coincide
with market-wide factor moves). Drawdown with intact edge metrics says
hold or resize; flat markets with decaying edge metrics say kill even
without a dramatic drawdown. Paper-vs-live slippage is the third
diagnostic: run the signal in paper alongside live, and the gap between
paper fills and live fills isolates execution from alpha. Stable gap
(say a consistent 3bps): that is your true cost of trading, reprice
capacity with it. *Growing* gap: the footprint is being detected or the
liquidity you modeled is gone -- the strategy may be being arbitraged
directly, which is a kill signal that P&L alone would show you months
too late.

*In this library:* `backtest/DrawdownAnalytics.java` (drawdown
probability under the fitted distribution -- the pre-registration
number), `trading/PaperTradingGateway.java` (the parallel paper run),
`alpha/SignalEvaluator.java` (rolling IC -- the decay detector).

### 122. "A backtest lands on your desk with a Sharpe of 4. Give me your checklist."

In rough kill-order: (1) Look-ahead -- are signals computable strictly
before the prices they trade (Q114), including restated fundamentals
and index membership? (2) Survivorship -- point-in-time universe with
delisting returns charged (Q113)? (3) Costs -- spread, impact, borrow;
Sharpe 4 gross frequently means Sharpe 0.5 net if turnover is high
(Q120). (4) Shortability and tradability -- half of small-cap "alpha"
lives in names you cannot borrow or whose ADV cannot absorb the
position. (5) Multiple testing -- how many variants were tried; run the
deflated Sharpe with the true trial count (Q115-116). (6) Sample --
does it span regimes, or is it a 2010s child (Q119)? (7) Concentration
-- is the P&L a handful of days or names? Remove the top 5 days and see
what survives; a "statistical" strategy that is secretly three events
is an event bet. (8) Execution realism -- are passive fills assumed
free of adverse selection, do limit orders fill at prices that were
never touched in size? (9) Reconciliation -- can a second implementation
reproduce the equity curve from raw data? Sign errors and timezone bugs
have produced more Sharpe-4 backtests than genuine edges. Meta-point
worth saying out loud: a true Sharpe 4 at scale would
compound absurdly -- extraordinary claims get the checklist in full,
and the honest prior is that beautiful backtests are bugs until proven
otherwise.

*In this library:* the `backtest/validation` package is this checklist
in code -- `PurgedKFold`, `OverfitProbability`, `SharpeValidation`,
`WalkForwardAnalyzer` -- with `backtest/ExecutionAwareBacktester.java`
and `data/PointInTimeUniverse.java` covering the execution and data
legs.

---

## Low-latency & systems design (Q123-Q135)

### 123. "Why would anyone build a trading system in Java? And where does that argument stop?"

The case for: JIT-compiled hot paths run within striking distance of
C++ once warmed, and the things that dominate real project outcomes --
development speed, tooling, profilers, hiring, memory safety (no
use-after-free taking down a trading engine at 2pm) -- favor Java
heavily. LMAX ran a retail FX exchange processing millions of events
per second on a single-threaded Java business-logic core, and much of
the industry's execution/OMS layer is JVM. The discipline that makes it
work: treat the JVM as a capable machine you must not provoke -- no
allocation on the hot path, primitives and arrays instead of object
graphs, warmed and JIT-stable code paths, cores pinned and isolated.
Where the argument stops: the last microseconds. GC exists even if you
never trigger it (Q124), object headers and pointer-chasing tax cache
behavior, there is no direct control over memory layout (Valhalla's
value types are the long-promised fix), and deterministic sub-10us
tick-to-trade with kernel-bypass NICs is a fight against the platform
rather than with it -- shops that need single-digit microseconds
consistently write that tier in C++ or increasingly FPGA, and keep Java
for strategy, risk, and everything that changes weekly. The honest
summary: Java buys you the fastest *team*; C++/hardware buys the
fastest *packet* -- most firms need both, at different layers.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` (the tiered argument,
with measured numbers), `examples/HftLatencyBenchmark.java` and
`examples/HftBookBenchmark.java` (the JVM hot path measured end to
end).

### 124. "How do you make GC a non-event in a trading system?"

Do not tune the collector -- starve it. A young-gen pause is
milliseconds; at market-data rates, milliseconds are thousands of
missed updates, so the hot path must allocate *zero* bytes per event:
preallocated primitive arrays and ring buffers instead of objects,
flyweights that read fields directly out of binary buffers instead of
decoding into DTOs, no boxing, no varargs, no string formatting, no
lambdas that capture, no iterators (indexed loops). Everything the path
needs is allocated at startup and reused forever. Then verify it:
allocation-counter tests that fail the build if a hot-path method
allocates, and benchmarks run under Epsilon GC -- the JDK's no-op
collector that never collects and simply crashes on heap exhaustion.
Epsilon flips the guarantee from "GC probably won't run" to "GC pauses
are zero *by construction*, and any allocation bug becomes a loud OOM
in testing instead of a silent p99.9 spike in production." Sized
generously (e.g. 8GB for a trading day), a genuinely zero-alloc system
restarts in the maintenance window and never collects. Fallbacks for
the paths that must allocate: ZGC/Shenandoah keep pauses sub-ms for
the warm lane, and the classic desk pattern is two lanes -- zero-alloc
hot lane, allocating convenience lane -- with an explicit boundary.

*In this library:* `sbe/OrderFlyweight.java` and
`sbe/QuoteFlyweight.java` (decode-in-place, no objects),
`marketdata/TickRingBuffer.java` (preallocated transport),
`trading/HftRiskGate.java` (zero-allocation checks -- the javadoc
states the contract), `docs/ULTRA_LOW_LATENCY.md` (the Epsilon GC
recipe and the benchmark results under it).

### 125. "What is mechanical sympathy? Cache lines, false sharing, branch prediction -- with a trading example each."

Mechanical sympathy (Martin Thompson's term, borrowed from racing) is
writing code shaped like the hardware. Cache lines: memory moves in
64-byte units, so data the hot path touches together should live
together -- a book level stored as parallel primitive arrays
(price[i], qty[i]) streams through the cache, while the same data as a
linked list of Level objects is a pointer chase where every hop is a
potential ~100ns cache miss; at millions of updates per second the
array wins by an order of magnitude. False sharing: two threads writing
*different* variables that happen to share one cache line ping-pong
that line between cores, silently serializing them -- the classic
victim is a producer counter and consumer counter declared side by
side in one class; the fix is padding each counter to its own line,
which is why lock-free queues are full of otherwise-unused long fields.
Branch prediction: a mispredicted branch costs ~15-20 cycles; hot loops
should make branches boringly predictable -- process the common message
type in a straight line and let rare paths be the branch, avoid
data-dependent branching in the inner loop (branchless min/max,
arithmetic instead of if), and keep the hot path monomorphic so the JIT
can inline instead of megamorphic-dispatching. The kicker:
none of this shows in big-O -- an O(log n) tree book and an O(1)-ish
array book differ far less on paper than their cache behavior differs
in silicon.

*In this library:* `marketdata/TickRingBuffer.java` (the padding fields
and the javadoc explaining exactly which false sharing they prevent),
`orderbook/BookPrimitives.java` (parallel primitive arrays as the book
representation, by design).

### 126. "SPSC vs MPMC queues -- why does the single-producer restriction buy so much?"

An MPMC queue must referee races on both ends: multiple producers
claiming slots need CAS retry loops, and under contention every core is
invalidating the same cache lines, so throughput can *fall* as you add
producers -- coordination cost, not work, dominates. An SPSC queue
needs no CAS at all: one thread owns the head, one owns the tail, and
correctness needs only ordered visibility -- the producer writes data
then publishes the counter with a release store; the consumer reads the
counter with an acquire load then reads data. Each counter has exactly
one writer, so there is no contention by construction, and a good SPSC
ring adds the cached-counter trick (the producer keeps a stale local
copy of the consumer's position and only re-reads the shared counter
when the ring *appears* full) so the common-case enqueue touches no
shared state at all -- single-digit nanoseconds per op versus tens to
hundreds for contended MPMC. The architectural consequence is the real
answer: low-latency systems are built as *pipelines of SPSC stages*
(feed handler -> book builder -> strategy -> order gateway), one thread
per stage, one queue per edge, giving MPMC-free wiring plus per-stage
observability -- the Disruptor/LMAX pattern. Use MPMC only where fan-in
is essential, and even then prefer one SPSC per producer with a
consumer that polls them round-robin.

*In this library:* `marketdata/TickRingBuffer.java` (SPSC ring with
cached counters -- read enqueue/dequeue side by side),
`trading/OrderRingBuffer.java` (the same pattern on the order path).

### 127. "volatile vs acquire/release -- what is the difference, and where does it show up in a queue?"

`volatile` in Java gives you sequentially consistent semantics: every
volatile write is globally ordered with respect to every other volatile
access, which on x86 costs a StoreLoad fence -- a locked instruction on
the write, tens of cycles, draining the store buffer. Acquire/release
is the weaker contract that most communication actually needs: a
release store says "everything I wrote before this is visible to
whoever acquires this," an acquire load says "everything published
before the value I just read is now visible to me." That pairwise
guarantee is exactly a producer publishing a queue slot and a consumer
observing it -- you do not need a total global order over all
operations, you need happens-before along one edge. On x86 the hardware
already gives release/acquire ordering on plain stores/loads, so
VarHandle setRelease/getAcquire compiles to a plain store/load with
compiler-reordering constraints -- nearly free -- while the volatile
store still pays the fence. In a hot SPSC ring doing tens of millions
of ops per second, replacing volatile counters with
setRelease/getAcquire removes a locked instruction per operation, a
measurable double-digit-percent throughput difference. The classic
follow-up -- "when do you still need volatile/seqcst?" -- when two
independent flags must be observed in a consistent order by a third
observer (Dekker-style patterns); queues are not that.

*In this library:* `marketdata/TickRingBuffer.java` -- the counters are
published with `setRelease` and read with `getAcquire` (VarHandles),
and the javadoc explains why that is sufficient for SPSC.

### 128. "What is kernel bypass, and at what point does a desk actually need it?"

The kernel's network stack costs you: syscalls, interrupt handling,
buffer copies between kernel and user space, socket-layer locking --
tens of microseconds of latency and jitter per packet, and worse under
load. Kernel bypass moves the NIC into user space: frameworks like
Solarflare/Xilinx Onload (transparent, socket-compatible) or ef_vi and
DPDK (raw, poll-mode) map the NIC's rings directly into the process, a
spinning thread polls for packets, and data goes NIC-to-strategy with
no kernel transition -- one-way latencies drop from ~20-50us to low
single digits, and, as important, the *variance* collapses because
interrupts and scheduler noise leave the path. When you need it: when
your edge is reaction time -- market making and latency arbitrage where
the race to cancel or take after a tick is won in microseconds; every
serious HFT runs bypass plus pinned, isolated cores, and the next rungs
(NIC-level timestamping, FPGA feed handlers, layer-1 switches) follow
the same logic. When you do not: if your strategy's alpha horizon is
minutes or your decision loop includes a model that takes 100us anyway,
bypass optimizes the doorstep of a slow house -- an execution desk
slicing a parent order over hours gains nothing that matters. The
honest engineering order: measure first (Q129); kernel-vs-bypass only
matters after allocation, cache misses, and coordinated-omission-honest
tails are already clean.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` -- Tier 4 ("Kernel
bypass & hardware") places it explicitly *beyond* what the pure-JVM
tiers buy you, with the prerequisite tiers measured first.

### 129. "Your benchmark says p99 = 2 microseconds. Why might that number be a lie?"

Coordinated omission -- Gil Tene's term for the standard way load
generators flatter themselves. If the benchmark issues a request,
waits for completion, then issues the next, any stall in the system
also *pauses the load generator*: during a 100ms GC pause the harness
politely stops measuring, so one horrible experience is recorded as
one bad sample instead of the ten thousand requests that *would* have
arrived and queued during it. The percentiles then describe a fantasy
client that never shows up during trouble -- exactly inverted from
markets, where message bursts and system stress coincide. Honest
measurement: fix the intended arrival schedule in advance and measure
each operation from its *intended* start time, so queueing delay
during stalls is charged to every operation it delays; or at minimum
run a hiccup monitor -- a thread that sleeps 1ms and measures how much
longer the sleep actually took -- which catches whole-platform stalls
(GC, safepoints, page faults, CPU throttling) that per-op timing hides.
Related honesty rules: never average latencies (report the full
percentile spectrum to max), warm up before recording, and correlate
spikes with the hiccup log -- if the benchmark's p99.9 spikes and the
hiccup monitor shows a matching stall, the platform, not the
algorithm, is your problem. This question separates people
who have *published* latency numbers from people who have been burned
by them.

*In this library:* `util/LatencyRecorder.java` (full percentile
spectrum p50-p99.9-max, no averages), `util/HiccupMonitor.java`
(jHiccup-style stall detection -- the javadoc describes exactly the
diagnosis workflow), `docs/ULTRA_LOW_LATENCY.md` (the coordinated
omission warning next to the paced benchmarks).

### 130. "Describe the LMAX-style sequencer architecture. Why does determinism fall out of it -- and how do you debug with replay?"

The core idea: all inputs -- orders, market data, timers, admin -- are
merged into a single totally ordered stream by a sequencer, and the
business logic is one single-threaded state machine that consumes that
stream. No locks, no shared mutable state, no thread scheduling in the
logic: state is a pure function of the input sequence. LMAX showed the
single thread is not the bottleneck -- with mechanically sympathetic
code, one core processed ~6M transactions per second, because the
alternative (locks and contention) wastes far more than a core buys.
Determinism falls out for free: replay the same input log through the
same code and you get bit-identical state and outputs. That gives you
high availability (replicas consume the same stream and stay in
lockstep; failover is promoting a replica at a known sequence number),
recovery (restart = replay from the last snapshot plus the log tail),
and the debugging superpower: a production incident is not a
reproduction hunt -- ship the input log to a developer, replay to
sequence N, attach a debugger, single-step the exact event that
corrupted the book. Firms build "time-travel" tooling on this: bisect
the log for the first divergent state, diff replicas event by event.
The design costs candidates should volunteer: everything must flow
through the sequencer (hidden inputs like wall-clock reads or iteration
order over hash maps silently break determinism), and the log is the
system of record so its persistence and shipping become the hard
engineering.

*In this library:* `data/TickCapture.java` and
`data/TickFileReader.java` (capture and deterministic replay of the
input stream), `persist/Checkpoint.java` (snapshot + replay-from
recovery), `trading/OrderRingBuffer.java` (the ordered single-consumer
input spine), `docs/ULTRA_LOW_LATENCY.md` (the architecture argument).

### 131. "The consumer can't keep up. Drop, block, or conflate? Defend a policy per stream."

Backpressure policy is a *semantics* question, not a queues question.
Market data: never block the feed handler -- if the strategy thread
stalls, blocking propagates the stall upstream until the exchange's
TCP window fills or the UDP feed gaps anyway. Old quotes are worthless
(a stale price is worse than no price), so the right policy is
conflation: keep only the latest state per instrument -- a consumer
that falls behind skips intermediate updates and resumes with fresh
state. Ring-buffer-with-overwrite implements this naturally; the
consumer detects it lapped and re-syncs. Orders and executions: the
opposite -- every message is precious and mutates position state, so
dropping is unacceptable; the queue must be bounded (unbounded queues
convert overload into an OOM an hour later) and *rejecting new orders
upstream* is the release valve: refuse to create work you cannot
finish, and never lose work in flight. Fill reports must be processed
even during overload, or risk state is wrong. Self-imposed throttles
are the third layer: exchanges enforce message-rate limits and punish
breaches with disconnects, so the gateway meters itself with a token
bucket and the strategy sees "throttled" as a first-class signal. The
cautionary tale is any exchange outage where participants' unbounded
retry queues turned a 1-second blip into a 10-minute storm -- policy
decided under load is policy decided wrong.

*In this library:* `marketdata/HftMarketDataBus.java` (the
latest-state-wins market-data path), `marketdata/TickRingBuffer.java`
(bounded transport with explicit full/empty handling),
`trading/OrderThrottle.java` (the token-bucket self-limit, with the
venue-rate-limit rationale in the javadoc).

### 132. "Design a matching engine's order book. Data structures, and why."

Start from the access pattern: the overwhelming majority of operations
touch the best few price levels, adds and cancels dominate, and
everything must be O(1)-ish with zero allocation. Structure: (1) Price
levels -- not a balanced tree; prices are integers in ticks within a
bounded band, so use a direct-indexed array of levels (price -> slot),
giving O(1) level lookup and cache-friendly scans for the next
populated level; keep best bid/ask cursors so the common case never
scans. A tree's O(log n) is fine on paper and loses in silicon (Q125).
(2) Within a level -- FIFO queue of orders implemented as an intrusive
doubly-linked list over preallocated order slots (indices, not
pointers): O(1) append for new orders, O(1) unlink for cancels, and
price-time priority falls out of the list order. (3) Order id -> slot
-- a dense array or open-addressing long->int map, because cancels and
replaces arrive by id and must find the order in O(1) without hashing
garbage. (4) All storage preallocated: order slots from a free list,
no node allocation per event (Q124). Matching is then: aggressor walks
levels from the top, consumes FIFO queues, emits trades; partial fills
update in place. Follow-ups to expect: self-trade prevention, iceberg
refill priority (back of queue), replace semantics (price change loses
queue position, size-down keeps it), and how the public feed is
generated from the same event stream that mutates the book (Q130).

*In this library:* `orderbook/HftOrderBook.java` (venue-grade
price-time matching built exactly this way -- banded price array,
intrusive FIFO, preallocated slots), `orderbook/BookPrimitives.java`
(the shared zero-allocation building blocks, documented
structure-by-structure).

### 133. "Design a market-data handler: binary feed in, clean book out. What are the hard parts?"

The decode is the easy part: fixed-layout binary messages (ITCH-style
add/execute/cancel/delete/replace) read via flyweights straight off the
buffer, no allocation (Q124), then applied to an L3 book keyed by order
id. The hard parts are the failure modes. Sequencing: every message
carries a sequence number; the handler must detect gaps instantly,
because applying post-gap messages to a pre-gap book silently corrupts
state -- a missed delete leaves a ghost order at the top of your book
and your strategy quotes against liquidity that is not there.
Recovery: on a gap, either request retransmission, or mark the book
stale and rebuild from a snapshot channel while buffering the live
stream, then replay the buffer from the snapshot's sequence number --
the snapshot-plus-buffered-replay dance is where most handler bugs
live. A/B feeds: exchanges broadcast every message on two independent
lines; the handler line-arbitrates -- take whichever copy of sequence N
arrives first, discard the duplicate -- so a single dropped packet on
one line costs nothing; you page only when *both* lines gap. Then the
operational layer: per-line gap statistics as a health signal
(rising A-line loss at 9:30 predicts trouble), latency measured from
exchange timestamp to book-updated, and a stale-book flag wired
directly into the risk gate so quoting stops the moment the book is
not trustworthy -- the handler's most important output is knowing when
not to trust itself.

*In this library:* `marketdata/ItchCodec.java` (the ITCH 5.0-style
binary codec with exact field layouts), `marketdata/L3BookBuilder.java`
(order-id-keyed book maintenance from the event stream),
`marketdata/Nbbo.java` (the consolidated top-of-book output),
`fix/FixSession.java` (sequence-gap detection at the session layer --
the same discipline one protocol up).

### 134. "Where do pre-trade risk checks live, and how do you keep them from costing you the race?"

They live *in-line, on the hot path, before the wire* -- that is the
non-negotiable, both by regulation (SEC 15c3-5 requires broker-dealers
to check before orders reach the market, not audit after) and by
arithmetic: Knight Capital's $460M in 45 minutes (Q100) is the price of
risk controls that could not stop a misbehaving order flow in real
time. The naive objection is latency: a risk system that consults a
database or takes a lock has no place in a microsecond path. The
resolution is to make the checks mechanically trivial: all limit state
lives in preallocated primitive arrays indexed by dense symbol id;
a check is a handful of integer compares -- price inside collar
(fat-finger), size under max, resulting position inside limit, message
rate inside the token bucket, kill switch not thrown -- no allocation,
no hashing, no branching beyond the compares, returning an int reason
code; total cost measured in nanoseconds, invisible next to the wire
time. The design subtleties worth pushing on: position state must
be updated *synchronously* with order submission (count in-flight
exposure, not just fills, or a burst of unacknowledged orders walks
through the limit); firm-wide aggregation cannot be a lock, so shards
maintain local limits with an async aggregator adjusting them; and the
kill switch must be a flag the gate reads every order, flippable by a
human in one action. Slow risk (VaR, margin, scenario) runs
asynchronously and adjusts the fast gate's *limits* -- the fast path
only ever compares integers.

*In this library:* `trading/HftRiskGate.java` (zero-allocation,
primitive-array checks with int reason codes -- the fast lane),
`risk/PreTradeLimitChecker.java` (its convenience-lane counterpart),
`trading/OrderThrottle.java` (the rate check),
`trading/GlobalRiskAggregator.java` (firm-wide limits fed to sharded
gates without hot-path locks).

### 135. "Your FIX session disconnects mid-day. Walk me through recovery without double-sending an order."

FIX keeps a sequence number in each direction, and both sides persist
them plus every sent message. On reconnect you log on with your next
outbound sequence number; each side compares the counterpart's number
against what it expected. If the venue's Logon shows a number *higher*
than expected, you missed messages -- send ResendRequest for the gap,
and the venue retransmits, marking each replayed message PossDup=Y so
your engine can deduplicate against what it already processed. If it
is *lower* than expected, something is badly wrong (their store lost
state) -- the safe reflex is do not trade until reconciled. For the
replayed stream, admin messages are not retransmitted: the sender skips
them with a SequenceReset-GapFill, because replaying old Heartbeats is
noise but *replaying application messages wrongly is dangerous* -- the
classic catastrophe is resending a NewOrderSingle from an hour ago into
a moved market. Disciplined engines therefore gap-fill over stale
orders on resend and reconcile positions via unsolicited execution
reports and drop-copy instead. The double-send question specifically:
an order sent just before the disconnect may or may not have reached
the matching engine -- the ack is what you lost, not necessarily the
order -- so the engine must treat "sent, unacknowledged" as *unknown
exposure*: block re-entry of that clOrdID, use OrderStatusRequest or
the resent execution reports to resolve it, and only then resume. Never
reset sequence numbers to paper over a gap during the day (ResetSeqNum
on an intraday logon throws away exactly the recovery information you
need); that flag is for scheduled session starts. The one-line answer:
sequence numbers plus persistent stores make the session log a ledger,
and recovery is reconciling ledgers -- never assuming.

*In this library:* `fix/FixSession.java` (Logon handshake,
sequence-number tracking with gap detection, and the resend flow),
`fix/FileSessionStore.java` and `fix/FixSessionStore.java` (the
persistent sequence/message store recovery depends on),
`fix/ExecutionReport.java` (the reconciliation input).

## Data pipeline & time series (Q136-Q150)

### 136. "An OHLCV bar looks like four honest prices and a volume. What is it hiding?"

Almost everything that happened inside it. A daily bar collapses 6.5 hours
of trading into four points and tells you nothing about the path: a bar
with high 102 and low 98 is consistent with a spike-and-fade, a fade-and-
spike, or three round trips -- and a stop at 99 fires in some of those
orderings and not others, which is why bar-level backtests must make a
path assumption and state it. Timing is the second lie: the "close" is a
closing auction print, not a price you could trade at during the bar, and
a signal computed on the close is only executable at the NEXT bar's open
-- act-on-close backtests embed a look-ahead. Volume is the third: it
mixes auction volume, block prints, and continuous trading, which matter
very differently to an execution model. A real desk case: a mean-reversion
strategy that "bought the close" of down days backtested beautifully until
someone re-ran it buying the next open -- half the edge was the overnight
gap it could never have captured. The defense is structural: keep bars as
immutable timestamped records and force strategies to declare which field
they trade on.

*In this library:* `core/Bar.java` (one immutable bar),
`core/BarSeries.java` (the series), and the backtesting caveats in
`docs/LEARN.md` Part on backtesting (Q113-Q122).

### 137. "The stock split 4-for-1 last year. Walk me through fixing the price history -- and which direction you adjust."

You back-adjust: divide every price BEFORE the ex-date by the split
factor and multiply the volume by it, leaving prices from the ex-date
onward untouched. The direction matters. Today's price must remain the
price on the screen -- a live system reconciles against the real market,
so history bends to the present, not the other way around. Done right,
the 75% overnight "crash" that a raw series shows at the split becomes a
smooth join, and every return computed across the boundary is a real
return. Done wrong -- or not at all -- your momentum signal sees a
massive one-day loss, your vol estimate quadruples, and your stop-loss
logic fires on an event that transferred zero wealth. The subtle detail
a desk will probe: the adjustment applies to bars strictly before the
ex-timestamp (the first bar trading on the new basis), and multiple
actions compound multiplicatively as you walk backward through time. The
input series should never be mutated -- adjustment is a derived view,
because the raw prints are the audit trail.

*In this library:* `data/CorporateActions.java` -- `adjust` returns a new
back-adjusted `BarSeries`, the `CorporateAction` record carries
`exTimestamp` ("first bar timestamp trading on the adjusted basis"), and
the input is documented untouched.

### 138. "Dividends: price return vs total return -- and how does a cash dividend become a price adjustment?"

A $2 dividend on a $100 stock knocks the price down ~$2 at the open of
the ex-date, but the holder is made whole in cash -- so a price-only
series shows a loss that never happened. Over decades this is not a
rounding error: for equity indices, dividends are on the order of a
third to half of total return. The standard fix is a multiplicative
back-adjustment: scale all prices before the ex-date by
`(close - dividend) / close` of the last cum-dividend bar, which makes
pre-ex returns equal the total return a reinvesting holder earned. The
trade-offs to name: adjusted prices are no longer prices anyone traded
at (a "support level at $50" from 2015 is fiction after adjustment),
long histories of high-yield names can adjust toward zero or even
negative with additive schemes -- which is why the multiplicative form
is used -- and every new dividend re-writes all of history, so cached
downstream features must be invalidated. A dividend-capture desk learns
this the painful way when its "alpha" turns out to be unadjusted ex-date
drops.

*In this library:* `data/CorporateActions.java` -- the `Type` enum covers
splits and dividends, and the javadoc states it "back-adjusts a raw price
series for splits and dividends."

### 139. "CSV is the lingua franca of market data. Give me the ways a CSV bar file will hurt you."

Let me count them. Quoting: RFC-4180 allows commas inside quoted fields
and doubled quotes as escapes, so a naive `split(",")` shreds any file
with a quoted field -- and vendors love writing volume as "1,234,500".
Thousands separators inside numbers are the same trap one layer down: a
parser must strip them or reject them explicitly, never silently truncate
at the comma. Delimiters: European vendors ship semicolon-delimited files
(because comma is their decimal point), so the splitter must handle both.
Headers: column order varies, names vary in case, and some files have no
header at all. Then the quiet ones: blank trailing lines, BOM bytes,
CRLF vs LF, and locale-dependent decimal points. The desk-level lesson is
that a CSV loader is not a one-liner; it is a small compiler front-end,
and every hazard you do not handle explicitly becomes a silent data
corruption -- the worst kind, because the backtest still runs and just
gives you confidently wrong numbers.

*In this library:* `data/CsvBarLoader.java` -- an "RFC-4180-style field
split: comma/semicolon delimiters, double quotes" and a numeric parse
"tolerant of vendor thousands separators" are separate, tested methods.

### 140. "The timestamp column is just numbers. Seconds or milliseconds -- and how do you decide without a config flag?"

A heuristic over the whole file, not per row. Epoch seconds and epoch
millis are both plausible integer columns, and misreading one as the
other puts your bars in the year 56,000 or in 1970 -- either way,
joins against other series silently produce empty intersections. The
robust rule: look at the RANGE of the numeric timestamps across the
entire file. Values in the low billions are seconds (2001-2033 in
seconds is roughly 1.0e9-2.0e9); values above ~1e11 can only be millis.
Deciding per-row is wrong -- a file is written by one process in one
unit, and a per-row heuristic would flip units mid-file at the boundary.
The same discipline applies to string timestamps: dates, date-times, and
ISO offsets each need explicit handling, and the internal representation
should be one canonical unit (epoch millis, UTC) so nothing downstream
ever asks the question again. A desk story: an FX backtest that "lost"
weekends because its loader read one vendor's seconds as millis, mapping
all of 2020 into a single January-1970 afternoon.

*In this library:* `data/CsvBarLoader.java` -- `isEpochSecondsFile` is
the whole-file heuristic, and everything normalizes to epoch millis;
date-only strings resolve at UTC start-of-day.

### 141. "I have bars for SPY, TLT and a thinly traded small-cap. Align them. Intersect or union?"

Wrong question -- the answer is "which error can you afford." Intersect
keeps only timestamps present in EVERY series: no fabricated data, but
you silently drop bars, and for a sparse small-cap you may throw away
most of your liquid names' history; worse, the drops cluster on exactly
the volatile days when some feed hiccuped, biasing vol estimates down.
Union with forward-fill keeps every timestamp and carries each series'
last observation forward: no data loss, but a stale price is now
pretending to be fresh -- correlations against the ffilled series are
biased toward zero, the small-cap looks artificially smooth, and a
"portfolio return" on a day the small-cap did not trade is partly
fiction. The honest workflow: intersect for anything estimating
covariance or running pairs logic; union+ffill for portfolio accounting
where you need a mark every day and accept it is a stale mark. Either
way, log how much was dropped or filled -- an alignment step that
reports nothing is where multi-asset backtests go to rot.

*In this library:* `data/SeriesAligner.java` -- `intersect` and
`unionForwardFill` are the two policies, side by side, so the choice is
explicit in the calling code.

### 142. "Your ten-year backtest picks stocks from today's S&P 500 list. What is wrong, and how do you fix it structurally?"

Survivorship bias: today's membership list has already deleted every
bankruptcy, acquisition, and delisting, so the backtest only ever
"selects" from names that we now know survived. The damage is not
subtle -- classic studies put it at 1-4% per year of phantom return,
concentrated exactly in the small/value/distressed corners where stock-
picking strategies live. Fixing it needs two halves. Data: historical
membership INTERVALS (a symbol can leave an index and rejoin) including
dead tickers, plus what holders actually received when a name died --
this comes from CRSP-style datasets, no engine can conjure it. Engine:
every screen and rebalance must query membership AS OF the simulation
date, never the present, and a position in a security that dies must
terminate through the delisting event rather than silently persisting at
its last price. The test that catches regressions: run the same strategy
on the point-in-time universe and on today's list; if the gap is not
visible, your dead tickers are not really in the data.

*In this library:* `data/PointInTimeUniverse.java` (membership intervals
plus terminal events, consumed by `screener/StockScreener.java`'s
`membersAsOf`), loaded from the CSV interchange format defined in
`data/UniverseCsvLoader.java`.

### 143. "A stock in your backtest gets delisted for cause. What return do you book on its last day?"

The delisting return: the final-day return relative to the last close,
representing what a holder actually received -- and for involuntary
delistings the truth is often "very little." Booking zero (position
quietly evaporates at the last price) overstates performance; booking
-100% for every delisting overstates the damage, because many delistings
are mergers where holders got paid. The literature's convention when the
true value is unknown for an involuntary delisting is Shumway (1997):
around -30% for NYSE/AMEX names delisted for cause, based on what
over-the-counter prints showed such shares actually fetched. Mergers are
the other terminal event and need per-share deal terms -- cash, acquirer
shares, or both -- so the position converts rather than vanishes. A
desk use case: a small-cap value strategy whose backtest Sharpe dropped
by a third when delisting returns were applied honestly -- that third
was never real, and a fund that raised money on the unadjusted number
would have discovered it live.

*In this library:* `data/PointInTimeUniverse.java` --
`EventType.DELISTING` carries a delisting return with
`DEFAULT_INVOLUNTARY_DELISTING_RETURN` (Shumway 1997) as the documented
fallback, and `EventType.MERGER` carries per-share cash and acquirer-
share terms.

### 144. "You want to record every tick your live system sees. The same thread is trading. Design the capture."

The naive design -- write each tick to disk on the bus consumer thread --
is fine until the operating system's page cache decides to flush, an
antivirus scan touches your file, or the disk hiccups, and suddenly your
TRADING loop stalls for milliseconds behind a file write. Capture on the
hot path must obey the first law of recorders: never block the thing you
are recording. The correct structure is a private ring buffer between
the consumer thread (which only publishes a tick into the ring -- a few
nanoseconds) and a dedicated writer thread that drains the ring to disk
at its leisure. Then you must choose a backpressure policy, and for a
recorder on a trading path the honest one is DROP: if the writer stalls
longer than the ring absorbs, ticks fall out of the CAPTURE (counted,
alarmed) but the trading loop never waits. A gap in a recording is an
incident report; a stalled trading loop is a P&L event. Keep the
blocking, simpler variant for research sessions where nothing downstream
is trading.

*In this library:* `data/TickCapture.java` (synchronous, documented as
fine for capture-only sessions) vs `data/AsyncTickCapture.java` (ring
buffer, dedicated writer thread, `droppedTicks` counter -- the javadoc
states the drop policy and why).

### 145. "Why do tick recorders write binary instead of CSV? Be quantitative."

Size, speed, and fidelity -- in that order of how often people cite them,
and reverse order of how much they matter. Size: a tick serialized as
text ("1719412345123,4512.25,4512.50,100\n") runs 40-60 bytes; a framed
binary record with a fixed layout is ~28 bytes, and at millions of ticks
per day across symbols that is the difference between a laptop-friendly
archive and a storage project. Speed: binary writes are a few field
stores into a buffer; CSV requires formatting doubles to text on write
and parsing them back on read -- easily 10-50x the CPU, which matters
when the writer shares a box with trading. Fidelity is the one that
bites: a double round-tripped through decimal text at the wrong
precision does not come back bit-identical, and a replay that differs in
the 15th digit can take a different branch in your strategy -- there
goes deterministic replay. A binary format needs discipline in exchange:
a magic number so you never misparse a foreign file, a version byte so
the format can evolve, and framed records so a torn final write is
detectable rather than corrupting.

*In this library:* `data/TickFileWriter.java` -- magic `"QFLT"`
(`0x51464C54`) plus version byte, then framed records (~28 bytes/tick per
`data/TickCapture.java`); `data/TickFileReader.java` is the replay side.

### 146. "What does 'deterministic tick replay' actually require, end to end?"

Three guarantees, each of which fails independently. First, the capture
must be lossless and ordered: every tick the strategy saw, in the order
it saw them, with the timestamps it saw -- which is why the recorder
attaches at the same bus the strategy consumes, not upstream of it.
Second, the file format must round-trip exactly: binary doubles and
longs, no text conversion, so the replayed tick is bit-identical to the
live one. Third -- the one people forget -- the CONSUMER must be
deterministic: no wall-clock reads, no iteration over hash-ordered
collections, no thread races in the decision path; given the same tick
sequence it must emit the same orders. When all three hold, you gain the
superpower: any production anomaly can be re-run in a debugger, at
human speed, as many times as needed, and a candidate fix can be
validated against the exact history that broke. A desk that has this
fixes incidents in hours from a tick file; a desk that does not writes
"could not reproduce" in the postmortem.

*In this library:* `data/TickCapture.java` ("replay the session
deterministically with `TickFileReader`"), `data/TickFileReader.java`,
and the determinism argument for the engine side in `docs/LEARN.md`
(Q130, the LMAX-style sequencer).

### 147. "Your vendor file is missing three days for one name. Fill, drop, or interpolate -- defend a policy."

First, diagnose before treating: missing because the exchange was closed
(not missing at all -- align calendars), missing because the stock was
halted (informative missingness -- the gap IS the event), or missing
because the vendor dropped it (fixable upstream)? Then the options.
Dropping the rows is honest for single-name statistics but poisons
multi-asset alignment. Forward-fill is the pragmatic default for marks
-- yesterday's price is a defensible stale mark -- but it manufactures
zero returns, deflating volatility and correlation exactly where you can
least afford it (halted names are not calm names). Interpolation is the
one to be suspicious of: a straight line through a gap creates prices
nobody could trade and, worse, uses INFORMATION FROM THE FUTURE (the
right-hand endpoint) -- a look-ahead smuggled in as data cleaning. The
policy a desk can defend: align on calendars first, forward-fill for
accounting marks with the fill count logged, drop for estimation, never
interpolate a tradable price, and treat any name with excessive gaps as
untradeable rather than repaired.

*In this library:* `data/SeriesAligner.java` -- `unionForwardFill` is the
explicit ffill policy and `intersect` the explicit drop policy; the
choice is forced at the call site rather than hidden in a loader default.

### 148. "Timestamps and timezones: what discipline keeps a multi-source system sane?"

One rule, applied everywhere: convert to UTC epoch millis at the border,
and never let a local time, an abbreviation, or a naive date-time past
the loader. Every pathology traces to violating it. Daylight saving:
US and Europe shift on DIFFERENT weeks, so "9:30 New York vs 8:30
London" changes offset twice a year and a naive join misaligns for two
weeks annually -- FX desks call the fall shift week "the week the
backtest lies." Ambiguity: 1:30 AM happens twice on the fall-back night;
epoch time does not care. Vendor habit: files arrive in exchange-local
time, UTC, or "whatever the export server was set to," often unlabeled
-- which is why detection must be structural (numeric range heuristics)
rather than trusting labels. Date-only bars need a convention (start of
day UTC) applied consistently, or daily and intraday series will never
join. Display formatting in local time is fine -- at the very last step,
for humans. The moment two internal components exchange anything but
epoch UTC, you have scheduled a future incident.

*In this library:* `core/Bar.java` and `core/BarSeries.java` carry epoch-
millis longs; `data/CsvBarLoader.java` normalizes every accepted input
form (epoch seconds, epoch millis, dates, ISO offsets) to epoch millis at
parse time, resolving date-only values at UTC start-of-day.

### 149. "Fetching bars over HTTP from a free data API. What goes wrong in production?"

Everything HTTP can do to you, plus everything CSV can. Timeouts: a
data API that usually answers in 200ms will occasionally hang for
minutes; without an explicit timeout your morning data job hangs with
it, and the strategy starts on stale data. Partial responses: a
connection dropped mid-body yields a truncated CSV that parses fine
until the last line -- frame your parse to tolerate or detect a torn
tail. Rate limits: free tiers return HTTP 429 or, more treacherously, a
200 with an error message as the body -- which your CSV parser will
cheerfully report as "no rows" or a parse error three columns in.
Schema drift: vendors rename columns and flip date formats without
notice, so parse defensively and alarm on shape changes. And silent
revisions: the same URL can return different history tomorrow (splits
applied, errors fixed) -- if you need reproducibility, snapshot what
you fetched, timestamped, and backtest from the snapshot. The
architectural point: keep the transport thin and reuse the battle-
tested CSV parser underneath, so the hazards concentrate in one place.

*In this library:* `data/HttpBarFetcher.java` -- pure JDK
`java.net.http` with an explicit timeout, delegating all parsing to
`data/CsvBarLoader.java` so the CSV hazards of Q139-Q140 are handled by
one tested code path.

### 150. "Why is the bar series backed by six primitive arrays instead of a List<Bar>?"

Because the access pattern is columnar and the hot loops care. A
`List<Bar>` is an array of POINTERS to heap objects: computing an SMA
over closes chases a pointer per bar, each landing on a cache line that
also drags in the open, high, low, and volume you did not ask for --
plus object headers. A structure-of-arrays layout puts all closes
adjacent in one `double[]`: an indicator streams through it linearly,
the hardware prefetcher sees the stride and hides memory latency, and
each 64-byte cache line delivers eight useful doubles instead of one
useful field. No boxing means no per-element allocation and nothing for
the garbage collector to trace -- a million-bar series is six flat
arrays, not a million objects. The design costs something: returning
internal arrays without copying is a zero-allocation win but a
discipline burden (callers must treat them as read-only), and
immutability of the series is what makes that bearable. This is the
same row-store vs column-store argument databases settled decades ago,
applied at cache-line scale.

*In this library:* `core/BarSeries.java` -- the javadoc says exactly
this: "Immutable, cache-friendly OHLCV time series backed by primitive
arrays (structure-of-arrays layout, no boxing)"; array accessors return
the internals uncopied for zero-allocation hot paths.

## Indicators & signals from bars (Q151-Q162)

### 151. "SMA vs EMA: same period, same data. Why do they disagree, and which lags more?"

They weight the past differently. An SMA gives the last N bars equal
weight and everything older zero -- a rectangular window -- so its
effective lag is (N-1)/2 bars, and it has the famous drop-off artifact:
the SMA jumps when a large OLD observation falls out of the window, a
move that has nothing to do with today's price. An EMA weights bars
geometrically -- alpha = 2/(N+1) on the newest, decaying forever -- so
it responds faster to fresh information (roughly half the SMA's lag for
the same nominal period), never exhibits drop-off, and needs only one
number of state, which is why streaming systems love it. The cost: the
EMA never fully forgets (a crash months ago still has epsilon weight),
and its "period" is a convention, not a window. Desk use case: crossover
systems built on SMAs generate signals when old data exits the window --
a 200-day SMA cross triggered by what happened 200 days ago is a real
and embarrassing failure mode; EMA crossovers only move on new prices.
Neither is "better"; they answer different questions about the past.

*In this library:* `indicators/Indicators.java` -- `sma`, `ema`, and
`wma` side by side; `indicators/StreamingIndicators.java` shows why the
EMA's one-value state makes it the natural streaming primitive.

### 152. "Build RSI from scratch. Then tell me why two vendors' RSI(14) numbers disagree."

Take bar-to-bar changes, split them into gains and losses, average each
over the period, and map: RSI = 100 - 100/(1 + avgGain/avgLoss). Bounded
0-100, above ~70 called overbought, below ~30 oversold. The vendor
disagreement is the smoothing. Wilder's original uses his recursive
smoothing -- avg = (prev*(N-1) + new)/N -- which is an EMA in disguise
(alpha = 1/N), so every RSI value depends on the entire history and two
implementations disagree unless they seed identically. Cutler's variant
replaces the recursion with a plain N-bar arithmetic mean of gains and
losses: exactly reproducible from the last N+1 bars, no seeding
sensitivity, at the cost of the SMA drop-off artifact inside the RSI.
Edge cases worth naming: an all-gains window makes avgLoss zero
(convention: RSI 100), a flat window makes both zero (convention: 50).
Desk relevance: if your screen says RSI 29.8 and the vendor's says 30.2,
the difference is smoothing choice plus seed, not a bug -- but if your
backtest threshold is exactly 30, that difference flips trades, which is
an argument against backtesting knife-edge thresholds.

*In this library:* `indicators/Indicators.java` -- `rsi` (documented
Wilder smoothing, with the avgLoss == 0 conventions in `toRsi`), and
`alpha/Factors.java` -- the factor version explicitly documented as
Cutler's RSI (arithmetic mean), with the reproducibility rationale.

### 153. "MACD has three numbers. What is each one actually measuring?"

The MACD line is EMA(fast) - EMA(slow) -- classically 12 and 26 -- which
is a momentum measure denominated in PRICE units: how far the short-term
average has pulled away from the long-term one. The signal line is an
EMA (classically 9) OF the MACD line: a smoothed version of the momentum
measure, so crossings of MACD through its signal mark momentum
accelerating or fading. The histogram is the difference of those two --
loosely a second derivative of price -- and it is the earliest of the
three to turn, which is why chartists watch histogram shrinkage as an
exhaustion cue before the crossover prints. Things a desk will probe:
MACD is unbounded and price-scaled, so it is not comparable across
symbols without normalizing (divide by price or use a percentage form);
it is built from EMAs, so all the lag arguments of Q151 apply doubly;
and its three parameters give a curve-fitter a large fast/slow/signal
grid -- the classic 12/26/9 survives mostly because everyone stopped
tuning it, which is itself a kind of out-of-sample argument.

*In this library:* `indicators/Indicators.java` -- `macd` returns the
`Macd(line, signal, histogram)` record; the streaming equivalent in
`indicators/StreamingIndicators.java` maintains all three incrementally.

### 154. "What is a Bollinger squeeze, and why do volatility bands 'work' at all?"

Bollinger bands are a rolling mean with a band at +/- k rolling standard
deviations (classically 20 bars, k = 2): a self-scaling envelope that is
wide when the market is volatile and tight when it is quiet. A squeeze
is the tight state -- band width near its own historical lows -- and the
trade behind it is volatility cycling, not direction: quiet periods are
followed by expansions (volatility clusters, per GARCH), so a squeeze
flags that a large move is coming WITHOUT saying which way. The common
refinement measures the squeeze against Keltner channels (ATR-based
bands around an EMA): when the Bollinger bands trade inside the Keltner
channels, standard-deviation vol has compressed below true-range vol --
the classic squeeze setup. Why bands work as an alerting tool: they are
a z-score visualized, so price at the upper band means "two sigmas rich
to the recent mean" -- useful mean-reversion context in ranges, and a
trap in trends, where price can ride the band for weeks. A desk uses
the squeeze less as an entry and more as a regime gate: size up
breakout systems and stand down mean-reversion when the bands pinch.

*In this library:* `indicators/Indicators.java` -- `bollinger` (mean +/-
k rolling sigma), `keltner` (EMA +/- ATR multiple) for the squeeze
comparison, and `rollingStd` underneath.

### 155. "Position sizing with ATR: why do trend followers size in 'ATR units' instead of dollars?"

ATR -- average true range -- measures how much an instrument actually
moves per bar, gaps included: true range is
max(high, prevClose) - min(low, prevClose), then Wilder-smoothed. Sizing
in ATR units inverts it into risk normalization: risk a fixed fraction
of equity per trade with the stop placed a multiple of ATR away, so
position size = (equity x riskFraction) / (k x ATR x pointValue). The
consequence is that every position carries the SAME expected P&L noise
regardless of instrument: the position in a sleepy utility is large,
the position in a crypto future is small, and a 1-ATR adverse move
costs each the same dollars. This is the sizing scheme under most
managed-futures programs, because it makes a 60-market portfolio's risk
additive by construction. The subtleties a desk probes: true range (not
high minus low) is essential for anything that gaps overnight; ATR
rises AFTER volatility arrives, so sizes shrink a beat late in a vol
spike -- an acceptable lag for daily systems, dangerous intraday; and
an ATR-multiple stop plus ATR-based size means stop distance and size
move together, keeping per-trade risk genuinely constant.

*In this library:* `indicators/Indicators.java` -- `trueRange` and `atr`
(Wilder smoothing), which plug directly into the stop and sizing rules
composed in `dsl/StrategyBuilder.java`.

### 156. "VWAP: the execution desk and the signal desk both use it. Same number, different meanings -- explain."

VWAP is cumulative (price x volume) / cumulative volume -- the average
price actually paid over the session, weighted by participation. To the
execution desk it is a BENCHMARK: an order worked across the day is
graded by fill price vs interval VWAP, since matching VWAP means you
traded with the crowd and imposed no measurable timing cost. The gaming
and its limits -- a VWAP-benchmarked algo that trades proportional to
volume matches the benchmark almost tautologically, even on a trending
day when arrival price would judge it harshly -- are why TCA never uses
one benchmark alone. To the signal desk, intraday VWAP is an INDICATOR:
price holding above a rising VWAP says buyers have paid up all session;
institutional programs use distance-from-VWAP for reversion entries.
The critical mechanical difference from every other indicator: session
VWAP is anchored, resetting at the open, so it is not a rolling window
-- and comparing VWAPs across desks that anchor differently (session vs
event-anchored vs rolling) is comparing different numbers. On a
daily-bar series, VWAP built from typical prices is only an
approximation of the exchange's official print, and a desk should know
which one its TCA report uses.

*In this library:* `indicators/Indicators.java` -- `vwap` (cumulative,
anchored); the `Vwap` class in `indicators/StreamingIndicators.java` for
live use; the benchmark-side treatment is Q109-Q110 in `docs/LEARN.md`.

### 157. "Your live system computes RSI per tick; research computes it over arrays. How do you guarantee they agree?"

Structurally, and then by test. Batch indicators recompute from full
arrays -- clear, easy to verify, O(n) per call, fine for research. Live
systems cannot recompute a 10,000-bar array per tick, so streaming
variants keep O(1) state: an SMA keeps a ring of the last N values and
a running sum, an EMA keeps one value, Wilder RSI keeps two smoothed
averages. The danger is drift: two implementations of "the same"
indicator, written twice, diverge in seeding, rounding order, or
edge-case conventions -- and then research signals and live signals
disagree at exactly the thresholds where money changes hands. The
guarantee is a parity test: feed the identical price sequence through
the batch function and through the streaming object one update at a
time, and assert the outputs match bar-for-bar (exactly where the
algorithm is exact; to tight tolerance where floating-point association
differs). That test belongs in CI forever, because indicator code
attracts "harmless" refactors. A desk that skips it eventually spends a
bad afternoon discovering its live EMA was seeded with the first price
while research seeded with an SMA of the first N.

*In this library:* `indicators/Indicators.java` (batch) vs
`indicators/StreamingIndicators.java` (O(1)-per-tick state machines:
Sma, Ema, Rsi, Macd, Vwap), with the parity test in
`src/test/java/com/quantfinlib/indicators/StreamingIndicatorsTest.java`.

### 158. "Why do your indicator arrays start with NaNs instead of zeros or partial averages?"

Because an indicator that has not seen enough data has no value, and
NaN is the only honest way to say so in a double array. A 20-bar SMA
needs 20 bars; emitting a partial average over the first five bars
produces a number that looks like an SMA but has four times the
variance, and emitting zero is worse -- zero is a VALUE, and a rule
like "price above SMA" will happily compare against it and fire phantom
signals across the entire warm-up. NaN has the right algebra for the
job: it propagates through arithmetic (any expression touching warm-up
data is itself NaN) and poisons comparisons to false, so a crossover
rule cannot trigger off uninitialized state -- silently correct instead
of silently wrong. The discipline this imposes downstream is a feature:
backtests must explicitly skip the warm-up, and multi-indicator
strategies must skip the LONGEST warm-up -- a classic bug family when
mixing a 200-bar SMA with a 14-bar RSI -- while statistics must use
NaN-aware reductions. A desk story: a "great" strategy whose entire
edge came from comparing prices against zero-filled warm-up SMAs -- it
bought the first 200 bars of everything it ever saw.

*In this library:* `indicators/Indicators.java` -- every function
allocates via `MathUtils.nanArray` and only writes from the first fully
warmed index; `util/MathUtils.java` provides the NaN-aware plumbing.

### 159. "Indicators lag. Is that a bug, a parameter choice, or a law?"

A law, with parameter-shaped consequences. Every indicator is a causal
filter: a function of PAST prices only. Any causal smoothing filter
must trade noise reduction against delay -- to know a move is real
rather than noise you must wait for confirming observations, and that
waiting IS the lag. Concretely, an N-bar SMA delays a trend by about
(N-1)/2 bars; halve the period and you halve the lag but double your
whipsaws. You cannot tune your way out: the zero-lag filter that also
smooths is a non-causal filter -- one that reads the future -- which is
exactly what an accidentally centered moving average does in a
backtest, and why it looks miraculous (a centered MA "calls every turn"
using data from after the turn). The professional conclusions: momentum
strategies EMBRACE lag -- late in and late out by design, paid by the
middle of the trend; mean-reversion wants raw or lightly filtered
prices; and any indicator pitched as predictive should be restated
honestly as descriptive of the recent past, useful only if the recent
past persists. The question to ask of every signal: what does its lag
cost at the turn, and what does its smoothing save during the chop?

*In this library:* `indicators/Indicators.java` -- compare `sma`, `ema`,
`wma` lags on one series; `dsl/Rules.java` (`crossAbove`, `crossBelow`)
makes the whipsaw-vs-lag trade-off directly backtestable through
`dsl/StrategyBuilder.java`.

### 160. "You have forty indicators. How do you combine a few into a strategy without curve-fitting the combination?"

Treat the combination as a hypothesis, not a search space. The failure
mode: forty indicators, each with parameters, plus AND/OR combinations
is a search space in the millions -- brute-forcing it WILL find a
spectacular backtest by chance, and that backtest is noise wearing a
suit. The defensible process: start from an economic story (trend
systems get chopped in ranges, so gate a crossover entry with a
trend-strength filter -- a two-indicator strategy with a REASON); use
indicators from different information families (one momentum, one
volatility, one volume -- correlated indicators add parameters without
adding information); prefer few, round parameters and demand the result
survive perturbing them (an edge at EMA 12/26 that dies at 13/25 is an
artifact); and count every variant you tried, because the
deflated-Sharpe arithmetic needs the true number of trials, including
the ones you deleted. A declarative strategy DSL helps in an underrated
way: entry, exit, stop, and size are stated in one readable block, so
the complexity of a strategy -- the thing multiple-testing corrections
punish -- is visible at a glance instead of smeared across a codebase.

*In this library:* `dsl/StrategyBuilder.java` (fluent entry/exit/stop/
take-profit composition), `dsl/Rules.java` (crossAbove, above, rising --
the combinator vocabulary), and the multiple-testing defenses of
Q115-Q116 in `docs/LEARN.md`.

### 161. "Screen a 3,000-name universe down to twenty candidates. Walk me through the pipeline and its traps."

A screener is sequential filtering: start from a universe, apply
technical predicates (RSI below a threshold, price above the 200-day
SMA, ADX confirming trend strength), apply fundamental predicates
(positive earnings, leverage caps, liquidity floors), then rank the
survivors and take the top twenty. The traps, in order of expense.
Survivorship: the universe you screen must be the universe AS OF the
screen date -- run against today's constituent list and every
historical screen picks from companies you already know survived
(Q142). Warm-up: a 200-day SMA filter silently excludes recent listings
-- decide explicitly whether insufficient history means fail or skip.
Liquidity: a screen that surfaces names you cannot trade at size is a
paper portfolio -- filter on dollar volume before anything clever.
Stale fundamentals: earnings arrive with a lag, so a screen using
fundamentals must use the report AVAILABLE on the screen date, not the
quarter it describes -- the point-in-time discipline again, one layer
down. And repeatability: a desk reruns screens daily, so results must
be exportable and the filter set versioned, or Monday's twenty names
cannot be explained on Friday.

*In this library:* `screener/StockScreener.java` (the pipeline, plus
`membersAsOf` for the point-in-time universe),
`screener/TechnicalFilters.java` and `screener/FundamentalFilters.java`
(the predicate vocabulary), with CSV export for the daily run.

### 162. "You rank stocks by blending ROE, momentum, and value. Why must you normalize first, and what does min-max normalization break?"

Because raw metrics live on absurdly different scales: ROE is ~0.15,
market cap is ~1e11, momentum is ~0.30. A weighted sum of raw values is
not a blend -- it is "whichever metric has the biggest units wins,"
with your carefully chosen weights as decoration. Min-max normalization
maps each criterion to [0,1] ACROSS THE CANDIDATE SET first, so a
weight of 0.4 on value and 0.6 on momentum actually expresses relative
importance. Now the breakage you accept. Relativity: scores are
relative to THIS run's universe -- the same stock scores differently in
a different candidate list, which is fine for "pick the best twenty
today" and wrong for tracking one name's score through time (use
z-scores against a fixed history for that). Outlier sensitivity:
min-max is defined by the extremes, so one absurd P/E of 4,000
compresses everyone else's value spread into a few percent of the range
-- one bad print can reorder the entire ranking. The intended pipeline
order follows: filter out garbage FIRST (the screener's job), rank the
clean survivors second. Rank-based normalization is the robust
alternative when the inputs cannot be trusted to be filterable.

*In this library:* `screener/RankingEngine.java` -- the javadoc makes
exactly these two caveats (run-relative scores, outlier compression) and
prescribes filtering via `screener/FundamentalFilters.java` before
ranking as the intended design.

## ML for markets (Q163-Q175)

### 163. "Why does every tabular-alpha shop reach for gradient boosting instead of deep nets?"

Because the problem shape favors trees. Alpha features are tabular:
dozens to hundreds of heterogeneous columns (a vol ratio, a rank, a
spread in bps) with no spatial or sequential structure for convolutions
or attention to exploit -- the inductive biases that make deep nets win
on images and text buy nothing here, and on tabular benchmarks boosted
trees still match or beat them while training in seconds. Financial
data adds three aggravators. Signal-to-noise is brutal: an R-squared of
0.01 on daily returns is a career, so model capacity must be rationed
ruthlessly -- boosting rations it explicitly via rounds, learning rate,
and weak learners, while a deep net's capacity is hard to even measure.
Data is small: a few thousand independent-ish observations after
accounting for overlap, where nets are data-hungry. And interpretability
is not optional: a risk committee will ask WHY the model is long, and
tree feature-importances survive that meeting. The honest flip side:
trees cannot extrapolate outside the training feature range -- they
predict a constant out there -- which matters exactly in crises, when
features leave their historical box.

*In this library:* `ml/GradientBoostedRegressor.java` -- XGBoost-style
additive boosting with squared-error loss in pure Java, the shared
engine under `ml/VolatilityForecaster.java` and
`ml/MarketImpactPredictor.java`.

### 164. "Your booster uses decision stumps as weak learners. Walk me through one boosting round -- and why stumps?"

Start from the baseline: the mean of the targets (that is the best
constant under squared error). Each round then fits a new weak learner
to the RESIDUALS -- for squared-error loss, the gradient is just
(actual - predicted), so gradient boosting reduces to "fit the errors,
add a damped correction." A stump is a depth-1 tree: scan every feature
and every candidate threshold, pick the split that most reduces squared
error, and emit two leaf values (left mean, right mean of residuals).
Multiply the stump's output by the learning rate -- the damping that
stops any single round from overcommitting -- add it to the ensemble,
recompute residuals, repeat for the configured rounds. Why stumps: each
one is a weak learner by construction (one feature, one threshold --
it cannot memorize), so model capacity grows in small, controllable
increments of rounds x learning rate; the classic trade is more rounds
at a lower rate. The limitation to name: stumps are purely additive in
single features, so feature INTERACTIONS (vol matters only when spread
is wide) must be engineered as explicit product features -- deeper
trees would learn them, at the cost of capacity you often cannot afford
at financial signal-to-noise.

*In this library:* `ml/GradientBoostedRegressor.java` -- the `Stump`
record (feature, threshold, leftValue, rightValue), the residual loop,
and the `rounds` and `learningRate` knobs are the whole story in one
file.

### 165. "Forecast next week's volatility: GARCH or machine learning? Argue both sides."

GARCH's case: it is a two-parameter-deep theory that encodes the one
robust stylized fact -- volatility clusters -- with a closed-form
multi-step forecast, decades of out-of-sample validation, and nothing
to leak. It is hard to beat and impossible to embarrass. ML's case:
GARCH sees only yesterday's return and yesterday's variance; a
tree-based forecaster can eat multi-horizon realized vols, momentum,
shock magnitudes -- and learn nonlinear maps (vol responds differently
to a shock in a calm regime than a stressed one, the leverage
asymmetry, threshold effects) that GARCH needs bespoke extensions to
express. The honest empirical read: ML wins modestly when features add
information beyond the return series, and loses embarrassingly when it
overfits its flexibility to noise -- so the target must be defined with
care (FORWARD realized vol over the horizon, which makes labels overlap
and demands purged validation, Q170), and the benchmark must always be
run. A desk use case: vol targeting, where a 0-100 risk score from the
forecaster gates leverage -- and where the first question at the review
is always "did it beat GARCH(1,1) out of sample, after the embargo?"

*In this library:* `ml/VolatilityForecaster.java` (gradient-boosted
trees over multi-horizon realized vol, momentum, and shock features;
forward-vol target; the 0-100 risk score) vs `volatility/Garch11.java`
(the benchmark it must beat).

### 166. "Explain how a two-state HMM detects market regimes. What do the fitted numbers mean?"

Model returns as generated by a hidden two-state Markov chain: each
state has its own Gaussian (mean, volatility), and the market switches
states with fixed transition probabilities. Fitting by Baum-Welch EM
alternates: the E-step runs the forward-backward algorithm to compute,
for every bar, the probability of being in each state given ALL the
data (smoothed probabilities); the M-step re-estimates each state's
mean, vol, and the transition matrix weighted by those probabilities.
Converged, the numbers read directly: the low-vol state on equities
typically shows a small positive mean and modest sigma (the grind up),
the high-vol state a negative mean and large sigma (the crisis) -- the
empirical basis for "risk off." The transition diagonal gives regime
persistence: p_stay = 0.98 means an expected duration of
1/(1-0.98) = 50 bars, which is why the filtered high-vol probability is
a usable de-leveraging signal rather than a strobe light. Desk use
case: vol targeting that halves gross when the smoothed high-vol
probability crosses 0.5 cuts drawdowns materially in backtests -- the
model formalizes what discretionary traders call "the tape has
changed."

*In this library:* `ml/RegimeDetector.java` -- Baum-Welch EM with
forward-backward scaling, the `RegimeModel` record (per-state
means/stdDevs, transition matrix, `smoothedHighVolProbability`,
`expectedDuration`), state 1 pinned to high-vol by construction.

### 167. "EM converged. Why don't you trust it yet?"

Three reasons, each a standard EM pathology. Local optima: EM is a
hill-climber -- it monotonically increases likelihood but climbs the
hill it started on, and a bad initialization converges to a bad answer
(both states nearly identical, or one state fitted to three outliers).
The defense is multiple restarts and keeping the best log-likelihood,
plus sanity checks that the states actually differ. Label switching:
the likelihood is symmetric in the state labels -- "state 0" and
"state 1" can swap between runs, so any consumer that hard-codes
"state 1 = crisis" breaks silently; the fix is a canonical ordering
imposed AFTER fitting (sort states by volatility). Degeneracy: a state
can collapse onto a handful of points with near-zero variance, sending
likelihood to infinity -- variance floors or priors prevent it. And a
numerical one for long series: raw forward-backward probabilities
underflow doubles within a few hundred observations, so implementations
must scale (normalize each step, accumulate log-likelihood from the
scale factors) or work in log space. A desk that re-fits regimes nightly
and alarms on regime CHANGE learns about label switching the first time
the alarm fires because the labels swapped, not the market.

*In this library:* `ml/RegimeDetector.java` -- forward-backward WITH
scaling, `logLikelihood` exposed for restart comparison, and the
documented convention that state 1 is always the high-volatility regime
(the label-switching fix baked into the contract).

### 168. "Your ops team wants alerts on manipulative or broken market activity. What do you detect, and how do you avoid alert fatigue?"

Start with the two detectable signatures. Quote stuffing: a burst of
message traffic with almost no trading behind it -- so the detector
needs BOTH conditions: message rate a z-score outlier against its own
recent history AND an abnormal order-to-trade ratio. Either alone
drowns you in false positives (an earnings release spikes message rates
with heavy trading -- that is a busy market, not an attack; a quiet
market has high order-to-trade ratios at tiny volumes). Price spikes:
interval returns far outside their recent distribution, which catch fat
fingers, broken feeds, and flash events -- the surveillance value is
less "manipulation" than "your data or the market just broke, stop
trusting downstream signals." The alert-fatigue discipline is the real
design problem: z-scores against ROLLING baselines (absolute thresholds
rot as activity grows), a severity score on every alert so ops can
triage, both conditions ANDed rather than ORed, and a weekly review of
the false-positive rate as a first-class metric -- an alerting system
that cries wolf trains humans to ignore the wolf. Regulators fine firms
for missing surveillance; desks lose money ignoring their own alarms.

*In this library:* `ml/AnomalyDetector.java` -- `QUOTE_STUFFING`
(z-score message spike AND minimum order-to-trade ratio, ANDed exactly
as argued) and `PRICE_SPIKE`, each returning scored `Anomaly` records
for triage.

### 169. "Predict the market impact of an order before you send it. What features, what target, what use?"

Target: realized impact in basis points -- arrival mid to your average
fill price, signed so adverse is positive -- from your own historical
executions, because impact is a property of YOUR flow in THIS market,
not a textbook constant. Features, in rough order of information:
order size as a fraction of average daily volume (the square-root-law
driver -- impact grows roughly with the square root of participation);
quoted spread at arrival (the toll per crossing); book depth imbalance
signed toward your side (thin opposite-side depth means you sweep
levels); and recent volatility (the same participation costs more in a
fast market). A second, cheaper output is often more actionable than
the regression: the probability a marketable order sweeps through the
visible top level -- a binary the router can use directly to decide
between crossing and posting. Desk use: pre-trade cost estimates that
feed the participation-rate choice (if predicted impact exceeds the
signal's expected alpha, the trade is dead before it starts), and
post-trade, the model's residuals become the TCA baseline -- "this fill
was 3 bps worse than the model expected" is a real finding, "impact was
5 bps" is not.

*In this library:* `ml/MarketImpactPredictor.java` -- the documented
feature vector (`sizeVsAdv`, `spreadBps`, `bookImbalance`,
`volatility`), gradient-boosted regression to realized bps, plus the
top-of-book sweep probability.

### 170. "Your labels are 5-day forward returns sampled daily. Why does ordinary cross-validation lie to you here?"

Because the labels overlap: Monday's label covers Mon-Fri and Tuesday's
covers Tue-Mon, sharing four days of the same price path -- adjacent
observations are not independent, they are mostly the SAME event
relabeled. Random K-fold then puts Monday in your training set and
Tuesday in your test set, and the model is "predicting" a return it
has already seen four-fifths of: test scores inflate, you pick the
leakiest model, and the live P&L delivers the correction. Financial ML
adds the second leak: features built from rolling windows also straddle
fold boundaries. The repairs are purging and embargo. Purging removes
from the training set every observation whose LABEL WINDOW overlaps the
test fold's label windows -- the direct de-duplication of shared price
path. The embargo goes further: drop an additional buffer of training
observations immediately AFTER the test fold, because serial
correlation in features (vol clustering above all) leaks test-period
information into training rows that begin just after the fold ends.
The desk-level tell that someone skipped this: a model whose CV Sharpe
is triple its walk-forward Sharpe -- the gap IS the leakage, measured.

*In this library:* `backtest/validation/PurgedKFold.java` -- `splits`
takes `labelHorizon` (the purge width) and `embargo` explicitly, so the
two leak-repairs are visible parameters rather than folklore.

### 171. "Markets are non-stationary. What does that break in a standard ML pipeline, concretely?"

Almost every default. Supervised learning's core assumption is that
training and deployment data come from the same distribution; markets
violate it on every axis -- volatility regimes shift the SCALE of every
feature, correlations flip sign across macro eras, microstructure
changes (decimalization, tick regimes, the maker-taker landscape)
redefine what a feature even measures, and other participants adapt to
the patterns you exploit, which is a form of concept drift unique to
adversarial domains: your own trading erodes your labels. Concrete
breakages: a model trained on 2017's vol scale sees 2020 features
outside its training range -- and trees predict a CONSTANT out there
(Q163), so the model goes quietly flat or quietly insane exactly when
it matters; standardization fitted on the full history leaks future
scale into the past (fit scalers on training windows only); and feature
importances estimated over one regime silently invert in the next. The
mitigations are all humility: train on rolling windows so old regimes
age out, prefer RATIOS and RANKS over levels (stationary-ish by
construction), monitor the live feature distribution against training
and alarm on drift, and expect decay -- retraining is not a failure of
the model, it is the maintenance schedule.

*In this library:* `ml/RegimeDetector.java` treats non-stationarity as
the OBJECT of modeling rather than a nuisance;
`ml/VolatilityForecaster.java`'s features are windowed realized vols and
normalized shocks -- ratios, not levels -- for exactly this reason.

### 172. "What features would you engineer from market microstructure, and why do they carry signal at all?"

Microstructure features measure the supply and demand you can SEE
before it becomes a price change. The staples: order-book imbalance --
depth on the bid vs the ask near the touch -- is the single most
documented short-horizon predictor (a heavily bid book means the next
mid move is more likely up, because market sells get absorbed while
market buys walk the book); quoted spread and its recent trajectory
(widening spreads mean makers are pricing adverse-selection risk --
information is arriving); trade signs and their runs (buyer-initiated
volume clustering signals an institutional parent order being worked --
Q169's impact model consumes this); message rate and order-to-trade
ratio (activity without trading is repositioning, Q168's stuffing
signature); and time-of-day seasonality, since liquidity has a daily
shape that turns every other feature's meaning on its head at the open
vs the close (Q173's profile). Why they work: they are readings of
unexecuted INTENT, and intent precedes prints. Why they decay: everyone
computes them, and their horizon is seconds to minutes -- which is why
they power execution models (where to post, when to cross) far more
durably than they power alpha.

*In this library:* `ml/MarketImpactPredictor.java` consumes book
imbalance, spread, and participation as its documented feature vector;
`ml/AnomalyDetector.java` and `ml/IntradayLiquidityForecaster.java`
build on message-rate and volume-clock features respectively.

### 173. "Forecast intraday liquidity for an execution schedule. Do you need ML for this?"

Mostly no -- and knowing that is the point. Intraday volume has a
strongly stable seasonal shape: the U-curve in equities (heavy open,
quiet lunch, heavy close), the London open and London/New York overlap
in FX. The workhorse forecaster is embarrassingly simple: bucket each
day's volume into N intraday buckets (24 hourly, 48 half-hourly),
average buckets ACROSS days into a profile, and normalize. That
captures the bulk of predictable variation, is robust with even a few
weeks of history, and is exactly what a VWAP scheduler needs -- slice
the parent order proportional to the expected volume curve so
participation rate stays constant while trading volume varies 5x
through the day. Where ML genuinely adds: conditioning the profile on
regime (high-vol days front-load volume), on events (expiry days,
rebalance days, half-days have their own shapes -- better handled as
separate profiles than as features), and on real-time deviation (if
today is running 2x profile by 11am, the close will not be normal).
The desk lesson: a simple model you can explain to the trader who
overrides it beats a clever one they will not trust -- and the override
rate is itself a model-quality metric.

*In this library:* `ml/IntradayLiquidityForecaster.java` -- per-bucket
accumulation across days (`addDay`) into a seasonal profile, documented
as feeding the VWAP scheduler; the London-open/overlap example is in the
javadoc.

### 174. "How do you validate an ML trading model so the number you quote is the number you get live?"

Walk-forward, because it is the only scheme that simulates the life the
model will actually live: train on a window, predict the period
immediately after, roll forward, repeat -- every prediction made with
only data that existed at prediction time, retraining included in the
loop exactly as production would retrain. The knobs and what they test:
expanding vs sliding training windows (does old data help or has the
regime moved?), refit frequency (monthly refits that beat quarterly
tell you the edge decays fast -- which is itself a capacity and cost
warning), and the gap between in-sample and out-of-sample performance,
which is your overfitting gauge read directly. The parts people skip:
hyperparameters must be chosen INSIDE each training window (nested
validation) -- tuning on the full history then "validating" walk-
forward is look-ahead at the tuning layer; the out-of-sample equity
curve should be stitched ONLY from test periods and inspected for
whether the edge is concentrated in one era; and the number of full
walk-forward runs you performed is itself a trial count for the
multiple-testing arithmetic -- running walk-forward fifty times while
tweaking features is K-fold overfitting with extra steps.

*In this library:* `backtest/validation/WalkForwardAnalyzer.java` --
rolling train/test folds with per-fold parameter selection and the
stitched `outOfSampleEquity`, alongside `PurgedKFold.java` for the
label-overlap repairs of Q170.

### 175. "Your model's out-of-sample correlation with forward returns is 0.04 and significant. Are you rich?"

Not yet -- prediction and profit are separated by three tolls. Costs:
an information coefficient of 0.04 monetizes through turnover, and if
capturing the signal requires daily rebalancing at 5 bps a round trip,
the alpha can be net negative while the correlation stays proudly
significant -- gross backtests are marketing, net backtests are
research. Capacity and impact: the signal is strongest in exactly the
small, illiquid names where your own trading moves the price (Q169);
scale the book up and the act of harvesting the signal destroys it --
the realistic question is not "is the correlation real" but "how many
dollars of it survive impact." Implementability: a signal computed on
the close but only tradable at the next open loses the overnight drift
(Q136); a signal that concentrates its hit rate in crisis months may be
unrunnable at the leverage the Sharpe assumed, because the drawdown
arrives before the payoff. The professional restatement: an edge is a
prediction PLUS an execution path PLUS a risk budget that survives its
own worst month. This is why validation ends with a full net-of-cost
walk-forward equity curve and not a correlation coefficient -- the
curve is the only number the desk actually gets paid.

*In this library:* the chain is explicit -- `ml/*` produces forecasts,
`ml/MarketImpactPredictor.java` prices the execution toll,
`backtest/validation/WalkForwardAnalyzer.java` stitches the
out-of-sample equity, and the cost/capacity treatment is Q120 in
`docs/LEARN.md`.

## Simulation, reporting & operations (Q176-Q187)

### 176. "Your Monte Carlo runs on all cores. Two runs with the same seed give different VaR. What did you get wrong?"

Per-thread randomness done naively. The classic bug: one shared random
generator across threads (contended AND schedule-dependent), or
generators seeded from thread identity or time -- in either case the
mapping from scenario number to random draws depends on which thread
picked up which scenario, so the same seed produces different numbers
run to run, and "deterministic" dies the moment you parallelize. The
correct structure: derive an independent random stream PER SCENARIO,
not per thread -- mix the master seed with the scenario index through a
strong bit-mixing function (a SplitMix-style multiply-xor-shift, so
adjacent indices give uncorrelated streams), and have whichever thread
executes scenario i construct its generator from that mixed seed.
Scenario 12,417 now consumes identical draws whether it runs on thread
1 of 4 or thread 7 of 32, and results are bit-identical across machine
sizes. Why a desk insists: risk numbers go in reports that get audited
-- "the VaR changed because the thread pool was busier" is not an
answer a regulator accepts, and a reproducible run is the first tool
for debugging a suspicious tail.

*In this library:* `simulation/MonteCarloSimulator.java` -- the `mix`
function (golden-ratio increment, multiply-xor-shift) derives a
per-scenario `SplittableRandom`; the javadoc's contract is "results are
deterministic for a given seed regardless of thread scheduling."

### 177. "What are antithetic variates, and when do they actually help?"

The cheapest variance-reduction trick in the book: for every Gaussian
draw sequence Z that drives a path, also run the mirrored path driven
by -Z, and average the pair. Both paths are individually valid samples
(the normal is symmetric), but their payoffs are negatively correlated
whenever the payoff is monotone in the draws -- and the variance of a
pair average is Var(A+B)/4 with a negative covariance term doing the
work. For a payoff close to linear in the terminal value the
cancellation is dramatic; for the price of ~zero extra random-number
generation you often save 2x or more in paths for the same standard
error. When it does NOT help: payoffs symmetric in the underlying
(a straddle -- both the up path and its mirror finish in the money, so
the correlation goes positive and the trick can mildly backfire) and
deep digital/barrier payoffs where most path pairs both miss. The
follow-up a desk asks: why not always? Because it complicates path
counting (quote paths as pairs), and for heavy variance reduction you
graduate to control variates (price the analytic cousin, correct by
the known error) -- but antithetics remain the first thing to switch
on for autocallable-style monotone structures.

*In this library:* `pricing/Autocallable.java` -- Monte Carlo with
antithetic variates ("2 per draw", a `sign` flip on the path draws);
`simulation/MonteCarloSimulator.java` is the plain-paths engine to
compare standard errors against.

### 178. "Why does a simulated 99% VaR need 10,000+ paths when the mean converges fine at 1,000?"

Because a tail quantile is estimated from the paths that land beyond
it, and almost none do. At 99% VaR with 1,000 paths, the estimate is
carried by the worst ~10 observations -- the standard error of an
empirical quantile scales like sqrt(p(1-p)/n) divided by the density at
the quantile, and that density is TINY in the tail, so the error blows
up exactly where you are looking. In practice a 1,000-path 99% VaR
bounces around by tens of percent between seeds -- an unusable number
that will pass or breach limits by luck. The mean has every path voting
and converges at sigma/sqrt(n) with a healthy constant; the tail has
one-hundredth of the votes and a worse constant. Rules of thumb a desk
uses: 10,000 paths for a stable 99% VaR, an order of magnitude more for
99.5% or for CVaR of a fat-tailed book (CVaR averages the tail, which
helps stability per path but pushes further into the region you sample
worst). And always report the Monte Carlo error alongside the number:
a VaR quoted as 4.2% when its own sampling noise is +/-0.4% deserves
the error bar, and running the simulator at three seeds is the cheap
way to expose it.

*In this library:* `simulation/MonteCarloSimulator.java` (the javadoc
recommends 10,000+ scenarios) and `simulation/SimulationResult.java` --
`valueAtRisk` and `conditionalValueAtRisk` read sorted-path quantiles,
so seed-to-seed wobble is directly observable by re-running.

### 179. "Your simulator assumes GBM. List the assumptions, and rank the violations by how much they hurt."

GBM says: returns are normally distributed, volatility and drift are
constant, increments are independent, and paths are continuous.
Ranked by damage to a RISK number. Worst: constant volatility --
real vol clusters and regime-switches, so a GBM calibrated to
full-sample vol understates the probability that a bad month happens
DURING a high-vol episode, which is when your losses actually cluster;
this alone can halve a simulated drawdown estimate. Second: fat tails
-- daily equity returns produce 5-sigma-and-worse days orders of
magnitude more often than the normal allows, so GBM tail VaR is
systematically optimistic; horizon matters, though -- aggregation makes
monthly and annual returns considerably more Gaussian, which is why GBM
is far more defensible for a retirement-horizon portfolio fan chart
than for a 1-day trading VaR. Third: independence -- vol clustering
(and mild return autocorrelation) means multi-day losses chain more
than independent draws admit. Fourth: continuity -- gaps and jumps
break any strategy logic involving stops, which fill at the gap, not
the stop. The honest usage: GBM for long-horizon planning fans and as
a variance-reduction baseline; historical or filtered bootstrap when
the tail is the deliverable.

*In this library:* `simulation/MonteCarloSimulator.java` -- daily-step
GBM with the parameters (annual drift, annual vol) explicit, so each
assumption is a visible input; the fat-tail and clustering correctives
live in `volatility/Garch11.java` and the risk round of
`docs/LEARN.md` (Q73-Q85).

### 180. "Compliance wants every month-end risk report reproducible and readable in ten years. What does that rule out?"

Almost the entire modern web stack. A report whose stylesheet loads
from a CDN, whose charts render through a JavaScript library fetched at
open time, or whose fonts come from a third party is a report that
ROTS: the CDN moves, the library version disappears, the viewer's
browser blocks the script -- and in ten years the file that the desk
signed off renders blank in front of an auditor. The requirements
falling out of "archivable" are strict: one self-contained file, inline
styles, no external assets, no script -- so what was archived is
EXACTLY what renders, byte for byte, on any machine, forever. It also
rules out screenshots-as-charts (raster images are illegible when a
compliance PDF prints at 300dpi) in favor of inline vector graphics.
And it imposes a security discipline that feels paranoid until it
saves you: every title, header, and cell must be escaped, because
report content is DATA -- the day a symbol or a client note contains
markup, it must render as text, not execute in the compliance officer's
browser. The structural pattern: build the report as a neutral model
(sections, tables, key-value blocks), and let exporters -- HTML, CSV,
PDF, XLSX -- render the same model for different consumers.

*In this library:* `report/Report.java` (the neutral model) with
`report/HtmlReportExporter.java` -- self-contained single file, inline
CSS, every cell HTML-escaped (tested with a `<script>` symbol name) --
beside `report/CsvReportExporter.java`, `report/PdfReportExporter.java`
and `report/XlsxReportExporter.java` over the same model.

### 181. "Why would anyone hand-roll SVG charts instead of using a charting library?"

Because of what the report must survive (Q180): open identically in a
browser, an email client, and an archive viewer years from now. Inline
SVG with no script and no fetch is the only chart format with that
guarantee -- a charting library is a JavaScript dependency executing at
VIEW time, which is precisely the thing an archivable report cannot
contain. The side benefits stack up: SVG is resolution-independent, so
the compliance print at 300dpi stays sharp where a PNG pixelates; it is
text, so two report versions DIFF meaningfully in version control ("the
equity curve changed" shows as changed path coordinates, not an opaque
binary blob); and an equity curve plus drawdown chart is only a few
hundred lines of coordinate arithmetic -- the dependency saved is worth
more than the generality lost. The trap that catches real systems:
locale. A JVM running under a German locale formats 1.5 as "1,5", and
an SVG path coordinate written with a comma-decimal silently produces a
blank or deformed chart -- charts must format every number with an
explicit fixed locale. That bug class -- works on the developer's
machine, blank on the Frankfurt server -- is why the locale pin is a
design decision, not a nicety.

*In this library:* `report/SvgCharts.java` -- dependency-free equity
and drawdown SVG, the ten-year-archive rationale and the
`Locale.US`-pinned formatting ("1,5 is the classic silently-blank-chart
bug") both documented in the javadoc; output plugs into
`Report.Builder#addHtmlSection`.

### 182. "Your trading process restarts mid-day. How do you persist model state so it comes back correct -- and never half-written?"

Two failure modes to kill: the torn write and the stale format. Torn
writes die by atomic rename: never write into the live checkpoint file
-- write the complete new state to a temp file BESIDE the target (same
filesystem, or the rename stops being atomic), fsync, then rename over
the old checkpoint. The filesystem guarantees a reader sees either the
old complete file or the new complete file, never a hybrid; a crash
mid-save costs you one checkpoint's freshness, not your state. Name
the degradation honestly: some network mounts do not support atomic
rename, and the fallback is a plain replace -- a documented weaker
guarantee, not a silent one. Stale formats die by versioning at two
levels: a format version for the container (magic number so you never
misread a foreign file), and a version byte INSIDE each section's
payload, because the vol model and the position tracker evolve their
state layouts on different schedules -- per-section versions let each
component migrate independently instead of forcing a big-bang format
change. Desk reality: the restart that matters happens at 14:03 on a
Fed day, and "the EMA re-warmed from scratch for twenty minutes" is a
real P&L cost -- checkpointing streaming state is not an optimization,
it is correctness of the resumed strategy.

*In this library:* `persist/Checkpoint.java` -- named sections
committed via temp-file-plus-`ATOMIC_MOVE` (with the
`AtomicMoveNotSupportedException` fallback documented), a container
`FORMAT_VERSION`, and the javadoc's contract that each section payload
carries its own version byte.

### 183. "How do you measure your own system's latency without the measurement lying?"

Histograms, not averages -- and a histogram implementation that does
not disturb the thing it measures. Averages are worthless for latency
(the mean of 10,000 fast operations and three 10ms stalls says
"fast"); the operating numbers are percentiles -- p50, p99, p99.9 --
because the tail is where the money and the incidents live. But a
naive recorder poisons the experiment: storing every sample allocates
(GC pauses caused by your profiler), locking a shared histogram
serializes your hot path, and floating-point bucket math costs more
than the operation being timed. The right shape is HdrHistogram-style
log-linear bucketing: an array of counts indexed by the sample's
power-of-two range plus a few sub-bucket bits -- recording is a couple
of array writes, allocation-free, with bounded relative error (~6% at
16 sub-buckets per octave) which is plenty for a percentile whose
neighboring values span microseconds. Single-writer by design: one
recorder per thread, merge at read time, rather than a contended
shared structure. And publish the error bar with the number -- a p99.9
from 10,000 samples rests on ten observations, the same tail-starvation
arithmetic as Q178.

*In this library:* `util/LatencyRecorder.java` -- zero-allocation
log-linear histogram (16 sub-buckets per power of two, ~6% worst-case
quantile error, `record` is "a couple of array writes"), explicitly
single-writer; wired into the live loop's dashboards.

### 184. "Your p99.9 spiked overnight. How do you know whether to blame your code or the platform?"

Run a witness. The pauses that corrupt latency percentiles -- GC, JVM
safepoints, JIT deoptimization storms, OS scheduler preemption, CPU
frequency transitions -- happen to EVERY thread in the process,
including one that does nothing at all. So run a thread that does
nothing at all, carefully: park for a fixed resolution (say 1ms),
measure how much LONGER than requested the park took, and record the
excess into a histogram. On an idle healthy system the excess is
microseconds; every spike is a platform stall, observed by code that
cannot possibly have caused it. Now the overnight triage is mechanical:
if the application's p99.9 spike lines up with a hiccup-monitor spike,
the platform ate the tail -- go read GC logs, check for a noisy
neighbor, a CPU governor change, a backup job -- and no amount of
application profiling will find it because it is not in your code. If
the application spiked while the witness stayed flat, the stall is
yours: a lock, an allocation storm, a slow branch of your own. This
one bit of routing -- ours vs the platform's -- is the difference
between a focused fix and a week of profiling the wrong layer.

*In this library:* `util/HiccupMonitor.java` -- a jHiccup-style daemon
thread recording park-excess into a `LatencyRecorder`; the javadoc
prescribes exactly this run-alongside triage, and
`docs/ULTRA_LOW_LATENCY.md` covers the JVM/kernel tuning that shrinks
what it observes.

### 185. "The library has a CLI. Why does a quant library need one at all?"

Because the operational unit of research is the RUN, and runs need to
be scriptable, schedulable, and reproducible by someone who is not the
author. A CLI entry point turns the library into an operable tool:
the nightly cron job backtests the production strategies against
yesterday's data and regenerates the HTML reports; the risk analyst
reruns a walk-forward with one command during the morning meeting
instead of opening an IDE; CI executes the same entry point on every
commit so a broken strategy configuration fails a build instead of a
trading day. The design pressures on a good CLI are the same ones as
the rest of this list: determinism (same inputs, same outputs -- seeds
and data paths as explicit arguments, so a run can be reproduced from
its command line alone), self-contained outputs (reports that can be
emailed, per Q180), and a text interface that composes with the rest
of the ops toolchain -- schedulers, log collectors, alerting. There is
also an honesty benefit: if a workflow cannot be expressed as a CLI
invocation, it is not reproducible, and finding that out at
library-design time is cheaper than at audit time.

*In this library:* `cli/Main.java` -- the command-line entry point for
backtests, walk-forward validation, and HTML report generation, gluing
`dsl/StrategyBuilder.java`,
`backtest/validation/WalkForwardAnalyzer.java` and
`report/HtmlReportExporter.java` into scriptable runs.

### 186. "You are paper-trading a strategy for a month before going live. What must be observable, live, without stopping anything?"

The whole loop, at a glance, from a browser -- because the point of the
paper month is to catch the discrepancies between backtest and reality,
and discrepancies announce themselves at random times. The minimum
surface: account state (cash, equity, realized P&L) and open positions,
updated live, so a drift from expectations is visible the hour it
starts; the REJECTIONS stream, which is where paper trading earns its
keep -- every order the risk gate refused is a bug or a config error
that would have been real money; and the system's own latency
histograms, because a strategy that behaves at paper speeds and stalls
at live message rates is a failure you want to see in the paper month
(Q183-Q184). Two design constraints matter more than features. Zero
new dependencies: an ops dashboard that drags in a web framework
becomes an attack surface and an upgrade treadmill -- an embedded HTTP
server serving one self-refreshing page and one JSON endpoint is
enough, and the JSON endpoint means monitoring systems can scrape it
without parsing HTML. And read-only: the dashboard OBSERVES the loop
through shared state; anything that can mutate the trading process
belongs behind the risk-checked order path, never behind a web page.

*In this library:* `trading/TradingDashboard.java` -- zero-dependency
(JDK `com.sun.net.httpserver`) live dashboard: self-refreshing HTML plus
a JSON status endpoint exposing the paper-trading account, positions,
rejections, and attached latency histograms from
`util/LatencyRecorder.java`.

### 187. "Sell me 'deterministic replay' as an operations capability, not an engineering aesthetic."

It converts your worst debugging problem into your easiest one.
Production trading incidents are the nightmare case of software
failure: they depend on exact message ordering and timing, they cost
money while you investigate, and they will not reproduce on demand --
UNLESS the system is built so that state is a pure function of the
input event sequence, and the input sequence is captured. Then the ops
story changes completely. Incident at 14:03? Pull the tick file, replay
into the same code in a debugger, and watch the exact decision path at
human speed, as many times as needed -- "could not reproduce" leaves
the vocabulary. Candidate fix? Replay the incident's history against
the patched build and confirm the bad order is not emitted, BEFORE the
fix ships -- regression testing against reality, not against synthetic
fixtures. Model validation? Replay yesterday through today's build
nightly and diff the decisions; any unexplained divergence is a
determinism leak (a wall-clock read, hash-order iteration, a data race)
caught by CI instead of by the market. The price is discipline, paid
up front: lossless ordered capture, bit-exact serialization, and a
decision path with no hidden inputs -- which is why replay is an
architecture, not a feature you add later.

*In this library:* the full chain -- `data/TickCapture.java` /
`data/AsyncTickCapture.java` (lossless capture, with an honest drop
counter), `data/TickFileWriter.java` / `data/TickFileReader.java`
(bit-exact QFLT round-trip), and the determinism argument for the
engine in `docs/LEARN.md` Q130.

---

## Market data feeds (Q188-Q201)

### 188. "Why does an ITCH feed send add/cancel/execute deltas instead of book snapshots?"

Because a delta stream is smaller, faster, and -- the part people miss --
**deterministic**. A full-depth book on a busy symbol holds tens of
thousands of resting orders; snapshotting it on every change would be
megabytes per second per symbol, and a consumer still could not tell WHAT
changed without diffing. A delta is one fixed-size message describing one
atomic event, so wire latency is minimal, and applying the numbered deltas
in sequence gives every subscriber a **bit-identical book** at every
sequence number -- which is what makes regulation, surveillance, replay
and backtesting possible: everyone reconstructs the same reality. It also
pushes work to the right place: the exchange emits what it already knows
(one event), and each participant maintains only the state it cares about
-- top of book, five levels, or the full L3 ladder. The price is that the
consumer becomes stateful: lose one delta and your book is silently wrong
forever, which is why sequence numbers and recovery (Q193) dominate feed
engineering.

*In this library:* `marketdata/ItchCodec.java` -- the ITCH 5.0-style
message subset (add, execute, cancel, delete, replace, trade) with the
exact big-endian field layouts; `marketdata/L3BookBuilder.java` is the
stateful consumer that turns the deltas back into a book.

### 189. "Walk me through the ITCH message types you need to maintain a full-depth book."

Seven types carry the whole lifecycle. **A** (Add) puts an anonymous limit
order on the book: reference number, side, shares, stock, price. **F** is
the same add with MPID attribution (you can see WHICH member posted it).
**E** (Order Executed) says shares were matched against a resting order --
partial fills arrive as repeated E's decrementing the same reference.
**X** (Order Cancel) removes SOME shares (partial cancel); **D** (Order
Delete) removes the order entirely. **U** (Replace) atomically retires one
reference and creates a new one with new price/shares -- and the new
reference goes to the **back of the queue**, which is why replace-vs-cancel
economics matter to market makers. **P** (Trade, non-cross) reports an
execution against non-displayed liquidity: it prints a trade but touches
no displayed book state. Everything is fixed-length (A=36 bytes, F=40,
E=31, X=23, D=19, U=35, P=44), big-endian, prices as unsigned 32-bit
integers with four implied decimals, timestamps as 48-bit nanoseconds
since midnight -- parseable with a handful of offset reads, no parser
generator needed.

*In this library:* `marketdata/ItchCodec.java` -- the seven type constants
and `length(type)` table, plus a zero-allocation flyweight `View` whose
type-specific getters `assert` the message type so a misdispatched read
fails loudly in tests and costs nothing in production.

### 190. "You are a participant, not the venue. How do you build the book from the feed?"

With the same data structures as a matching engine, driven by events
instead of by matching. Each add allocates a node from a **preallocated
pool** (no GC), links it into the FIFO at its price level, and registers
its reference in an **open-addressing long-to-int map** (backward-shift
deletion, no tombstones). Price levels live in a **dense integer-tick
ladder** -- price minus minTick is an array index -- with one bit per
level in an **occupancy bitmap**, so best-bid/ask updates after a level
empties are a couple of `Long.numberOfTrailingZeros` scans, not a tree
walk. Execute/cancel/delete look the node up by reference and adjust or
unlink it; replace is delete-then-add with a new reference. The whole
event path allocates nothing, so a day-long session produces zero garbage.
One discipline matters above all: **single writer** -- one feed-handler
thread owns the book, which removes every lock and memory-ordering
question from the hot path.

*In this library:* `marketdata/L3BookBuilder.java` -- javadoc spells out
the structure ("same engineering as `orderbook.HftOrderBook` ... but
driven by the feed instead of by matching"); `L3BookBuilderTest.java`
drives it with encoded ITCH messages.

### 191. "Your order is resting at 100.00. How many shares are ahead of you -- exactly, not approximately?"

You can know exactly, because price-time priority gives you two facts that
make the update O(1). Learn your order's feed reference from your
gateway ack, wait until its Add appears on the feed, then compute the
initial shares-ahead with **one walk of the level's FIFO** (everything
queued before your node). From then on: (1) **executions always consume
the queue head**, so any E at your price that is not your reference
happened ahead of you -- subtract its shares; (2) **a cancel is ahead of
you iff its order entered the queue before yours**, which you check by
comparing insertion sequence numbers -- no walk needed. Example: you
join with 300 behind 5,000 shares; an E for 800 at your level drops
ahead-of-you to 4,200; an X for 500 from an order with a later insertion
sequence changes nothing. When your own reference fills, deletes, or is
replaced, tracking ends. This number is the input to real decisions:
expected time-to-fill, whether to stay in queue or cancel-and-cross, and
the queue-value models behind maker strategies.

*In this library:* `marketdata/L3BookBuilder.java` -- `track(ref)` and the
javadoc "Queue position" section stating exactly these two facts; tracked
orders are parallel arrays (up to 64), maintained O(1) per event.

### 192. "Same stock trades on 16 venues. How do you compute the NBBO, and what does a router actually need from it?"

Keep per-venue top-of-book arrays (bid tick, bid size, ask tick, ask
size), and on each venue update recompute the consolidated best: highest
bid, lowest ask, **sizes summed across every venue at the inside**, and a
**bitmask of which venues are there** -- with at most 64 venues the venue
set fits one `long`, and a branch-predictable linear scan over four
parallel arrays beats any incremental structure at this size. The router
consumes exactly three things: the inside prices, the size available
there, and the venue bits telling it WHERE to route. Two engineering
details carry the load: a **fast path** that proves most updates cannot
change the NBBO (a venue not currently at either inside whose new quote
stays strictly off it) and skips the scan entirely, since consolidated
tapes are dominated by off-inside flicker; and a single **venue-down**
path that clears a dead venue's quotes through the same code as an empty
quote, so feed loss and emptiness can never drift apart.

*In this library:* `marketdata/Nbbo.java` -- `onVenueQuote` with the
provably-no-change fast path (the comment walks the proof),
`onVenueDown`, `nbbVenueBits`/`nboVenueBits`, and a listener that fires
only when the inside actually changes.

### 193. "The consolidated bid is 100.02 and the consolidated ask is 100.01. What just happened?"

A **crossed market** -- and it is almost never free money. Locked (bid ==
ask, zero spread) and crossed (bid > ask) conditions arise because the
consolidated view is stitched from venues you observe at different
latencies: one venue's quote is stale on your tape, an update is
in-flight, or a venue's feed is degraded. By the time your order reaches
the "arbitrage," the quote that created it is usually gone -- the cross
is a **latency illusion**, and firms that chase it systematically are
usually donating fees. The correct reading is as a **data-quality
signal**: mark the symbol suspect, widen quotes or pause aggressive
routing, and check per-venue feed health (in US equities, locked/crossed
protected quotes are also a regulatory condition venues actively avoid,
so a persistent cross on your tape points at YOUR feed, not at the
market). A router should branch on this flag before trusting the inside.

*In this library:* `marketdata/Nbbo.java` -- `crossed()` (NBB strictly
above NBO) and `locked()` (NBB equal to NBO), one comparison each, and
`NbboTest.java` exercises both conditions.

### 194. "You detect a gap in the feed sequence. What are your options?"

First, understand the stakes: after a missed delta your book is not
"slightly stale," it is **silently wrong forever** -- a missed Delete
leaves phantom liquidity you might route against. Detection is trivial
(expected sequence number increments by one per message; anything else is
a gap); recovery is the design space. Options, in rising cost: (1)
**retransmission request** to the exchange's request server for the
missed range -- right for small gaps, but the reply arrives late, so you
buffer live messages and replay in order; (2) **snapshot re-sync** --
rejoin via the venue's snapshot channel and replay buffered incrementals
past the snapshot sequence (Q196); (3) **failover to the redundant feed**
(Q195) which usually has the missing packet; (4) the honest last resort:
**declare the book untrusted and go flat** -- pull quotes, stop routing
on that symbol -- until re-synced. A production handler wires the gap
signal to the trading layer, because the worst response is trading
confidently on a broken book.

*In this library:* the gap discipline is implemented on the order channel:
`fix/FixSession.java` detects inbound gaps, fires
`Listener.onSequenceGap`, issues ResendRequest(2) and drops out-of-order
messages until redelivery; `marketdata/L3BookBuilder.java` keeps
`unknownRefCount`/`outOfBandCount` tripwires that catch a desynced book.

### 195. "Why do exchanges broadcast every market data packet twice, and what do you do with the copies?"

Because the feed is **UDP multicast** -- chosen for fan-out latency
(Q229) -- and UDP guarantees nothing: packets drop, reorder, or arrive
late whenever a switch queue hiccups. So the venue transmits identical
streams on two independent networks, the A feed and the B feed, ideally
over separate physical paths. The consumer's job is **arbitration**:
listen to both, and for each sequence number take **whichever copy
arrives first**, discarding the later duplicate -- a dedupe keyed by
sequence number, nothing more. The payoff is large: a drop on one network
is invisible as long as the other delivers, so the expensive recovery
paths of Q194 become rare instead of routine; the marginal cost is one
more NIC and a set-membership check. Serious shops also monitor the
A-minus-B arrival-time skew per line as a network health signal -- a feed
that suddenly always loses the race is degrading before it fails.

*In this library:* the library ingests a single feed (its scope is the
participant stack above the wire), but the identical
dedupe-by-sequence-number discipline appears in `fix/FixSession.java`,
which suppresses PossDup-flagged duplicates below the expected sequence
number during resend recovery.

### 196. "Your GUI cannot keep up with the feed. Your execution engine must see everything. Reconcile that."

This is the **conflation** question: when a consumer is slower than its
producer, you either queue (unbounded memory and, worse, unbounded
staleness -- the consumer processes ever-older data), drop (lose
information), or **conflate: keep only the latest value per key**. The
right choice is per-consumer, decided by what the data MEANS. Quotes are
idempotent state -- only the current value matters -- so a dashboard, a
risk mark, or a pricing input should be conflated: skipping from tick
1,000 to tick 5,000 is correct behavior. Trades, fills and order events
are **facts**: each one changes position or P&L, so conflating them
corrupts state -- those consumers need every event or an explicit gap
signal. So a real system runs both lanes off one feed: every-tick into
the strategy and the recorder, conflated last-value into everything
merely observing.

*In this library:* both lanes exist. `marketdata/Nbbo.java`'s listener
fires only when the inside changes ("downstream logic is naturally
conflated to inside-quote updates"); `marketdata/HftMarketDataBus.java`
keeps a `latestPriceBits` last-price cache (conflated reads for anyone)
while listeners receive every tick.

### 197. "It is 11:40 and your feed handler just restarted. How do you join a delta stream mid-day?"

The **snapshot + incremental** dance, and the order of operations is the
whole answer. (1) Join the incremental (delta) stream FIRST and buffer
everything -- you cannot apply it yet, but you must not miss it. (2)
Request or await a **snapshot**: the venue's full book image stamped with
the sequence number it represents. (3) Load the snapshot. (4) Replay the
buffered deltas **strictly after** the snapshot's sequence, discarding
those at or before it -- they are already reflected in the image. (5) Go
live. Get the order wrong -- snapshot before subscribing -- and deltas
emitted between snapshot generation and your join are lost, leaving the
silent corruption of Q194. FIX market data encodes the same idea as
message types: MarketDataSnapshotFullRefresh (35=W) then
MarketDataIncrementalRefresh (35=X).

*In this library:* `fix/FixMarketDataView.java` decodes both halves --
35=W snapshots (entries implicitly NEW) and 35=X incrementals carrying
MDUpdateAction(279) new/change/delete; `data/TickFileWriter.java` bakes
the same "state before deltas" contract into the QFLT format: a symbol
definition must precede the first tick that references it.

### 198. "Why do people say sequence numbers rule everything in market data?"

Because every hard property of a feed reduces to them. **Ordering**:
deltas only mean something applied in emission order; the sequence number
IS that order, independent of network arrival. **Loss detection**: a gap
in the sequence is the only reliable signal that UDP dropped something --
silence is indistinguishable from a quiet market without it. **Dedupe**:
A/B feed arbitration (Q195) is "first copy of each sequence number wins."
**Recovery**: retransmission requests, snapshot joins (Q197) and failover
are all expressed as sequence ranges -- "give me 100,231 through
100,244," "this snapshot is as-of 4,401,882." **Determinism**: two consumers
that applied the same numbered stream hold bit-identical books, which is
what makes replay-based testing and post-trade reconstruction possible at
all. The same logic runs the order channel: FIX numbers every message in
both directions precisely so that gaps, duplicates and resends are
decidable (Q216). A feed without sequence numbers is not a data product;
it is rumor.

*In this library:* `fix/FixSession.java` (gap detection, ResendRequest,
duplicate suppression -- all sequence arithmetic);
`marketdata/L3BookBuilder.java` assigns internal insertion sequence
numbers (`nodeSeq`) because even WITHIN one price level, priority is
sequence order.

### 199. "Market-by-order vs market-by-price -- what does each give you, and what does MBP cost?"

**Market-by-order (MBO, L3)** publishes every individual order with its
own reference: adds, cancels, executions per order -- ITCH-style. From it
you can reconstruct the exact FIFO at every price. **Market-by-price
(MBP, L2)** publishes aggregated state per level -- "100.00 bid, 12,400
shares, 17 orders" -- as many derivatives feeds do. MBP is cheaper to
carry and trivial to consume, but it destroys the information that
maker strategies live on: with aggregates you cannot know **your own
queue position** (Q191 is impossible -- you cannot tell whether a
1,000-share reduction at your level was ahead of you or behind you),
cannot attribute cancels, cannot watch a specific participant's order
lifecycle, and cannot model queue dynamics except statistically. Rule of
thumb: takers and risk systems live happily on MBP or even top-of-book;
makers and microstructure research need MBO. If the venue offers both,
the MBO feed is the ground truth from which MBP is derivable -- never
the reverse.

*In this library:* the MBO consumer is `marketdata/L3BookBuilder.java`
(per-order references, exact queue position); the price-aggregated views
are `marketdata/Nbbo.java` (consolidated top-of-book) and
`fix/FixMarketDataView.java` (price/size entries per message).

### 200. "You want real ticks tonight, not a simulator, and you are not colocated. What do you build?"

A WebSocket feed -- the retail/crypto last mile -- engineered with the
same discipline as the pro path. The pieces: a pure-JDK WebSocket client
(no framework), a **pluggable per-exchange parser** so venue quirks stay
at the edge, an optional subscribe message for exchanges that require
one, and **automatic reconnection with exponential backoff** because
consumer-grade connections WILL drop. Three details separate a toy from
a keeper. First, timestamps: publish the **exchange's event time**, not
local receive time, so recorded sessions replay with true market pacing.
Second, poison tolerance: a malformed message is skipped, never allowed
to kill the feed thread -- one bad JSON frame must not end your capture.
Third, threading: the JDK invokes WebSocket listener methods sequentially
per connection, and reconnect epochs never overlap, so the downstream
bus's single-producer contract (Q201) survives reconnection -- an
easy invariant to break accidentally with a naive reconnect.

*In this library:* `feed/WebSocketFeed.java` (backoff, subscribe message,
the single-producer argument in the javadoc), `feed/BinanceTradeParser.java`
(flat-field extraction, no JSON library, malformed returns null "never
kill the feed"); the test harness `src/test/.../feed/TestWebSocketServer.java`
is a minimal RFC 6455 server, so the reconnect logic is tested against a
real socket.

### 201. "One instrument, five venues, five different symbol strings. How do you keep the hot path clean, and how does the bus fan out?"

Normalize once, at the edge, into a **dense int id**: intern each venue
string ("BTCUSDT", "XBT/USD", "AAPL.O") into a registry that hands out
0, 1, 2, ... and keep a reverse array for logging. After subscription
time the hot path never touches String hashing or map lookups -- listener
dispatch and the last-price cache are **plain array indexing** by id.
Fan-out then works like this: one producer publishes ticks into a
preallocated primitive SPSC ring; one consumer thread drains it and
dispatches to per-symbol listener arrays. The **single-producer /
single-consumer constraint is the design**, not a limitation: it is what
permits a lock-free, CAS-free ring (Q237) with plain and release/acquire
accesses only. Multiple upstream sources must therefore be serialized
before the ring, or use a multi-producer facade. One JIT subtlety worth
knowing: three or more distinct listener implementations on a symbol make
the dispatch call site **megamorphic** (~10-20 ns per listener, no
inlining), so keep one or two listeners per symbol and fan out inside
your own listener beyond that.

*In this library:* `marketdata/SymbolRegistry.java` (intern to dense ids),
`marketdata/HftMarketDataBus.java` (SPSC ring, array-indexed dispatch,
busy-spin or park consumer, and the megamorphic-dispatch warning verbatim
in the javadoc).

---

## Binary protocols & codecs (Q202-Q213)

### 202. "Why is tag=value FIX slow, and what does a fixed-offset binary format buy you?"

Count the work per field. Tag=value FIX ("55=EURUSD|44=1.08505|") makes a
decoder SCAN for delimiters, parse ASCII tag numbers, parse ASCII values
into binaries, and usually allocate a String or map entry per field --
per message, per day, forever. A fixed-offset binary format (the SBE/ITCH
family) removes every one of those steps: field N of message type T lives
at byte offset K, always, so decoding a price is ONE absolute primitive
read -- no scan, no parse, no allocation, and the field count no longer
matters. Encoding is symmetric: a handful of absolute writes. The trade
is flexibility for speed: binary schemas are rigid (offsets are the
contract, so evolution needs explicit versioning, Q207) and opaque to a
human with tcpdump, while FIX is self-describing and greppable. That is
why the industry splits: FIX tag=value survives on the session/order edge
where volumes are thousands per second and debuggability wins; market
data at millions of messages per second is binary everywhere.

*In this library:* the `sbe` package is the statement of this philosophy
-- its `package-info.java` calls it "the professional-grade alternative
to the text edges (JSON WebSocket in, FIX tag-value out)"; compare
`fix/FixMessage.java` (String parse, right for session plumbing) against
`sbe/TradeFlyweight.java` (five absolute reads).

### 203. "Explain the flyweight pattern over a ByteBuffer. Why not decode into a message object?"

A flyweight is a stateless-except-position cursor: `wrap(buffer, offset)`
points it at a message, and every getter reads the underlying bytes
directly at fixed offsets. The buffer IS the object; the flyweight is
just typed eyeglasses for looking at it. Decoding into a message object
(a POJO per message) costs an allocation plus a field-by-field copy per
message -- at one million messages per second that is a million objects
per second of garbage, and GC pauses are exactly the tail latency an HFT
path cannot have. With a flyweight you allocate ONE instance per thread
at startup and reuse it for millions of messages: wrap, read the two
fields you need (often not all of them -- lazy by construction), move on.
Zero allocation, zero copy, zero parsing. The costs are real and worth
naming: the flyweight is only valid until the buffer is reused, sharing
it across threads is a bug by construction, and reading a field the
message type does not carry returns garbage -- like pointing a C struct
at the wrong bytes -- so type discipline must be enforced by asserts or
dispatch structure.

*In this library:* `sbe/TradeFlyweight.java`, `sbe/OrderFlyweight.java`,
`sbe/QuoteFlyweight.java` (wire layouts in the javadoc, "reuse one
instance for millions of messages"), and `marketdata/ItchCodec.java`'s
`View`, whose getters assert the message type so misdispatch fails loudly
in tests.

### 204. "Prices on the wire: scaled longs or doubles?"

For a venue-facing contract, **scaled longs** -- an integer mantissa plus
a decimal exponent, e.g. 1.08505 encoded as mantissa 108505 with 5
decimals. Three reasons. Exactness: 0.1 has no finite binary
representation, so double-encoded prices accumulate representation error
and two parties can disagree about whether a price equals a tick --
integers make price equality and tick arithmetic exact. Comparability:
order books, bands, and risk limits are integer comparisons, branchless
and fast. Convention: it is what exchange protocols actually do (ITCH
carries prices as unsigned 32-bit integers with four implied decimals).
Doubles remain defensible on INTERNAL hops where both ends are your own
code, the tick semantics are preserved end to end, and convenience wins
-- but the moment a counterparty, a matching engine, or persistent
storage is involved, scaled integers are the professional answer. The
follow-up trap: "just use BigDecimal" fails the latency test -- it
allocates per operation.

*In this library:* both choices, each deliberate: `fix/FixOrderEncoder.java`
takes prices as mantissa x 10^-decimals ("doubles never touch the
encoder"), `fix/FixMarketDataView.java` exposes MDEntryPx the same way so
feed-to-order never converts through a double, and `marketdata/ItchCodec.java`
uses 4-implied-decimal integers; the internal `sbe` flyweights carry
doubles on trusted intra-process hops, documented in their layouts.

### 205. "Big-endian or little-endian on the wire -- does it matter, and what do real feeds do?"

It matters exactly once: when you pick, and forever after if you pick
inconsistently. Big-endian is traditional "network byte order" and what
the classic exchange specs use -- Nasdaq ITCH is big-endian, so any
compliant decoder must read it that way regardless of host CPU.
Little-endian is what x86 and ARM servers are natively, so modern
performance-first protocols (SBE among them) default to little-endian to
make encode/decode a plain load/store with no byte swap. The engineering
truth is that a byte swap costs about a cycle and is rarely measurable;
what IS measurable is the bug class from mixing conventions -- a price
read with the wrong endianness is not garbage-looking, it is a
plausible-looking WRONG number, which is far more dangerous. So:
document the choice in the schema, put it in the layout comment of every
codec, and pin it with round-trip tests against hand-crafted byte arrays
whose expected values were computed by hand (Q213), which catch
endianness mistakes on day one.

*In this library:* both conventions live side by side, each documented:
`marketdata/ItchCodec.java` is big-endian ("the exact field layout and
big-endian encoding of the Nasdaq TotalView-ITCH 5.0 specification");
the `sbe/*Flyweight` layouts are declared little-endian in their javadoc
wire diagrams.

### 206. "What does zero-copy actually mean in a feed handler?"

That message bytes are written once by the NIC/socket layer into a buffer
and **never copied again** on their way to the numbers your strategy
consumes. The anti-pattern chain is: socket buffer, to byte[], to String,
to split fields, to parsed objects, to a queue element -- five copies and
several allocations per message. The zero-copy chain is: bytes land in a
buffer you own; a flyweight wraps them IN PLACE (Q203); getters read
primitives straight out; and what crosses threads is either the buffer
region itself or the few primitives you extracted. The wins compound:
no allocation (no GC), no memcpy bandwidth, and better cache behavior --
the message bytes are hot in L1 when you read them. The discipline it
imposes: buffer lifetime management becomes YOUR problem (the flyweight
dangles if the buffer is recycled underneath it), and ownership hand-off
between threads must be explicit -- which is why zero-copy designs pair
naturally with single-writer rings where slot reuse is governed by
sequence arithmetic.

*In this library:* `marketdata/ItchCodec.java` ("Decoding is a flyweight
... every getter reads the bytes directly -- no parsing step, no objects,
zero allocation"); `marketdata/L3BookBuilder.java` feeds its book from a
wrapped view of the caller's buffer; the `sbe` flyweights are the same
idea on the outbound side.

### 207. "Your binary schema needs a new field. How do you version it without breaking anyone?"

Fixed offsets are the contract, so evolution must be explicit. The
standard toolkit: (1) a **message type discriminator** (and in full SBE,
a schema id + version) in a fixed header, so decoders dispatch before
touching any field; (2) **append-only extension** -- new fields go AFTER
the existing block, never between fields, so an old decoder reading a new
message still finds every field it knows at the same offset and simply
ignores the tail (this is why SBE headers carry a block length: "skip
this many bytes to the end of the fixed block"); (3) new SEMANTICS get a
new message type, not a reinterpreted field -- reusing offset 8 to mean
something else is the unforgivable sin; (4) version-gated decode on the
reader, negotiated or declared per stream, never guessed from message
size. And the operational rule that saves you in practice: decoders must
treat an unknown message type as skippable (length-prefixed framing makes
that possible, Q208), so a venue adding message types does not take your
handler down.

*In this library:* the ingredients are visible in miniature: every
`sbe/*Flyweight` starts with a `MESSAGE_TYPE` int at offset 0 and
declares `BLOCK_LENGTH`; `data/TickFileWriter.java` writes a magic plus
an explicit `VERSION` byte at the head of every QFLT file, so the reader
can refuse or adapt before parsing a single record.

### 208. "TCP gives you a byte stream, not messages. How do you frame?"

Two honest options. **Length-prefix**: every message starts with its
length (or has a type byte implying a fixed length); the reader
accumulates bytes until it has a full frame, slices it, repeats. O(1) per
message, unknown types are skippable by length, binary-safe -- the
professional default. **Delimiters**: messages end with a sentinel
(newline, SOH-pattern); simple and greppable, but the reader must SCAN
every byte, the sentinel must never appear in the payload (or needs
escaping), and a corrupted delimiter desynchronizes the stream. FIX is
the interesting hybrid: it LOOKS delimited (SOH between fields) but is
actually length-prefixed -- BodyLength(9) near the front declares the
body size, so a framer reads the BeginString and BodyLength fields, then
exactly that many bytes, then the 7-byte checksum trailer, regardless of
how TCP fragmented the stream. The invariant every framer must honor:
**feed me bytes in any fragmentation and I emit exactly the messages
that were sent** -- one message per read() is a bug that only shows up
in production networks.

*In this library:* `fix/FixDecoder.java` is exactly this incremental
framer ("feed raw socket bytes in any fragmentation, poll complete
validated FixMessages out ... message boundaries are exact regardless of
TCP segmentation"); `data/TickFileWriter.java` uses type-byte framing
with fixed-size records on the file side.

### 209. "Design a file format for capturing tick sessions for deterministic replay."

Requirements first: append-only sequential writes (capture must never
stall the feed), compact (days of ticks), self-describing enough to open
cold, and replayable in exact order with original timestamps. A good
answer: a **magic number + version byte** header (so a reader can reject
or adapt before parsing anything -- Q207), then framed records led by a
type byte. Two record types suffice: a **symbol definition** (int id +
UTF-8 name) and a **tick** (int symbolId, price, size, int64 event-time
nanos) -- a type byte plus a 28-byte payload per tick. The subtle
contract: a symbol definition must appear **before the first tick that
references it**, but may appear anywhere, so new symbols can join
mid-capture without rewriting the header -- the same state-before-deltas
rule as snapshot+incremental feeds (Q197). Store the EXCHANGE event time,
not write time, so replays reproduce true market pacing; buffer writes
(a megabyte-class BufferedOutputStream) so capture is sequential I/O; and
keep the writer off the trading thread entirely (Q236).

*In this library:* `data/TickFileWriter.java` -- magic "QFLT", VERSION
byte, TYPE_SYMBOL/TYPE_TICK framing, the defineSymbol-before-write
contract enforced with an IllegalStateException; `data/AsyncTickCapture.java`
is the off-thread writer around it.

### 210. "A single byte corrupts in an incoming FIX BodyLength. What happens to a naive decoder, and how do you harden it?"

The nightmare failure is not a crash -- it is a **zombie session**. The
framer parses BodyLength, and if a corrupted byte silently inflates the
parsed value (a digit became another digit, or a parser shrugged past a
non-digit), the framer waits for a frame end that never comes: the
connection stays up, heartbeats may even flow, and every subsequent
valid message is swallowed into the phantom frame. Nothing errors;
trading just quietly stops. Hardening is three checks that all fail
LOUDLY: (1) validate every BodyLength byte is a digit -- one non-digit
means stream corruption, throw and disconnect; (2) cap the plausible
message size and check it **inside the digit loop**, because ten
corrupt-but-numeric digits can wrap the int and sneak under a post-loop
cap; (3) verify the structural frame (BodyLength must immediately follow
BeginString) and the trailing CheckSum(10) -- an additive mod-256 sum
that catches the flips framing missed. The principle generalizes to
every binary protocol: **corruption must kill the session**, because a
desynchronized stream is worse than a dead one -- the session layer can
reconnect and recover by sequence numbers (Q216); it cannot recover from
silently eaten messages.

*In this library:* `fix/FixDecoder.java` -- the code comment tells this
exact story ("the framer would wait FOREVER ... a zombie session
swallowing every later valid message"), with the digit check, the
inside-the-loop 1MB cap, and the structural check; `fix/FixMessage.java`
validates CheckSum and BodyLength on parse.

### 211. "Symbols are strings. Hot paths hate strings. What are your options on the wire?"

Three tiers, all in production use. (1) **Dense integer ids**: both ends
share a symbol directory (sent at session start or startup), and the wire
carries an int -- smallest, fastest, but requires the directory handshake
and ids are meaningless without it. (2) **Packed fixed-width ASCII**: up
to 8 characters space-padded into one 64-bit integer -- the ITCH "alpha"
convention. It is self-describing (unpack and read it), compares and
hashes as a single long, needs no directory, and still never touches
String on the hot path; the cost is the 8-character ceiling. (3) A
**locate code**: the feed assigns a small per-day integer per symbol
(ITCH stockLocate) and every message carries it -- effectively tier 1
with the directory embedded in the feed's start-of-day messages. The
anti-pattern is variable-length strings per message: length-prefix
parsing, allocation on decode, and unbounded field size in the middle of
a fixed layout. Internally, resolve to a dense id once at the edge and
be int-only from there.

*In this library:* all three tiers: `marketdata/ItchCodec.java` has
`packStock`/`unpackStock` (8 ASCII bytes in a big-endian long) plus
`stockLocate()`; the `sbe` flyweights carry dense int `symbolId` as part
of the wire contract; `fix/FixOrderEncoder.java` pre-registers symbol
bytes by id so the FIX hot path never encodes a String; and
`marketdata/SymbolRegistry.java` is the internal directory.

### 212. "One buffer, many message types. How do you dispatch without allocating?"

Put a **type discriminator at a fixed offset** (offset 0) in every
message, read it with a static peek that does not require wrapping
anything, and branch -- a switch over an int or byte compiles to a jump
table, costs nanoseconds, and allocates nothing. Each branch then wraps
its **preallocated, reusable flyweight** (one per type per thread) over
the same bytes and reads fields. Message length comes from either the
type (fixed-size messages: a static type-to-length table tells the framer
how far to advance) or an explicit length prefix (Q208). What to avoid:
polymorphic decode-to-object factories (allocation per message), map
lookups keyed by type (hash + boxing where an array or switch would do),
and instanceof chains over decoded objects. The pattern scales: ITCH
handlers are exactly a byte-switch over 20-odd types; a well-built one
runs an entire trading day without a single decode-path allocation.

*In this library:* `sbe/TradeFlyweight.typeAt(buffer, offset)` is the
static peek ("reads the message-type discriminator without wrapping"),
each flyweight declares its `MESSAGE_TYPE`; `marketdata/ItchCodec.java`
gives the byte-switch vocabulary and the `length(type)` table that
advances the framer; `sbe/BinaryMarketDataClient.java` and
`sbe/BinaryOrderReceiver.java` are the dispatch loops built on them.

### 213. "How do you prove a codec is correct -- not that it seems to work, prove it?"

Three complementary attacks, each catching what the others miss. (1)
**Round-trip tests**: encode, decode, field-compare -- necessary but NOT
sufficient, because a matching encoder/decoder pair can share the same
bug (both write and read the wrong offset, both use the wrong
endianness) and round-trip green forever. So (2) **hand-crafted frames**:
build the byte array by hand from the SPEC -- place the type bytes and
mantissa bytes manually -- and assert the decoder reads the right
values; symmetrically, encode and assert the exact expected bytes. This
pins the WIRE contract, not just internal consistency, and is the only
test that catches shared-bug symmetry. (3) **Independent validators in
the loop**: decode your encoder's output with a separately written,
validating parser (checksum, length checks) so the two implementations
audit each other. For hot-path codecs add the fourth pillar: an
**allocation-counter test** asserting zero allocation per operation,
because "garbage-free" is a correctness claim too -- regressions arrive
silently with an innocent refactor.

*In this library:* the test suite does all four:
`src/test/.../marketdata/ItchCodecTest.java` and
`src/test/.../sbe/SbeCodecTest.java` round-trip the codecs;
`fix/FixOrderEncoder.java`'s javadoc states the discipline -- every
encoded message is re-parsed by the validating `FixMessage.parse`
(BodyLength + CheckSum) and field-compared, and zero allocation per
encode is asserted with the allocation-counter test.

---

## FIX protocol mastery (Q214-Q228)

### 214. "FIX has a session layer and an application layer. Draw the line."

The **session layer** exists to make an unreliable TCP connection behave
like a reliable, ordered, recoverable message channel between two named
parties (SenderCompID/TargetCompID): Logon and Logout handshakes,
Heartbeats and TestRequests to prove liveness, sequence numbers on every
message in both directions, gap detection, ResendRequests and
SequenceResets to repair holes. It knows nothing about trading. The
**application layer** rides on top and carries the business:
NewOrderSingle (35=D), ExecutionReport (35=8), cancels and replaces,
market data. The line matters operationally: session problems (gaps,
heartbeat timeouts, seqnum disputes) are fixed by session mechanics --
resend, reset, reconnect -- and MUST NOT be handled by the trading logic;
application problems (order rejected, unknown symbol) are business events
that the session dutifully delivers without understanding. Most
production FIX incidents are session-layer incidents, which is why a
desk's FIX engine is judged almost entirely on its session
implementation, not its message coverage.

*In this library:* `fix/FixSession.java` is the session layer (logon,
heartbeats, gap recovery, stores) with the application types --
`fix/NewOrderSingle.java`, `fix/ExecutionReport.java`,
`fix/OrderCancelRequest.java`, `fix/OrderCancelReplaceRequest.java` --
delivered through typed `Listener` callbacks.

### 215. "Walk me through a FIX session's lifecycle, logon to logout."

The initiator opens TCP and sends **Logon (35=A)** carrying its
HeartBtInt (108) and optionally credentials; the acceptor validates and
replies with its own Logon -- the handshake is complete only when both
sides have seen one, and nothing else may be sent before it. Then the
steady state: each side sends a **Heartbeat (35=0)** whenever it has been
quiet for the heartbeat interval, so silence always has an upper bound.
If a peer has been silent LONGER than the interval (plus tolerance), you
do not disconnect yet -- you send **TestRequest (35=1)** with a
TestReqID, demanding a Heartbeat that echoes it; only if that also goes
unanswered do you declare the peer dead and disconnect (Q219). Sequence
numbers tick on every message throughout, including heartbeats.
Termination is also a handshake: one side sends **Logout (35=5)**, the
peer replies with its own Logout, then the TCP connection closes --
which distinguishes an orderly goodbye from a crash and tells both sides
the sequence state is clean for the next session.

*In this library:* `fix/FixSession.java` -- `initiate()` blocks until the
Logon handshake completes (10s establish timeout), a dedicated heartbeat
thread runs the quiet-interval and TestRequest logic, and the logout
handshake gates `close()`; `FixSessionTest.java` drives all of it over
real sockets.

### 216. "Why does EACH side of a FIX session keep TWO sequence numbers?"

Because reliability must be symmetric and neither side trusts the
transport. Each party tracks its **outgoing** sequence (stamped into
MsgSeqNum(34) of every message it sends, incremented every time, admin
messages included) and the **expected incoming** sequence (what the
peer's next 34 should be). That pair makes every failure mode decidable
locally. Receive a seqnum HIGHER than expected: messages were lost in
transit -- issue a ResendRequest for the gap (Q217). Receive one LOWER
than expected without PossDupFlag: something is deeply wrong -- state was
lost or you are talking to an impostor -- disconnect immediately (Q218).
Receive lower WITH PossDup: a resend duplicate, suppress it. The genius
of the design is that a dropped TCP connection loses nothing that
matters: both sides persist their counters, reconnect, compare Logon
seqnums, and each side can immediately tell what the OTHER missed and
resend exactly that. TCP guarantees ordering within one connection;
FIX sequence numbers guarantee continuity ACROSS connections -- that is
the whole reason they exist.

*In this library:* `fix/FixSession.java` keeps `outgoingSeq` (guarded by
the write lock) and `expectedIncomingSeq` (reader thread only), seeded
from a `FixSessionStore` so both survive reconnects;
`fix/FixSessionStore.java` defines the persistence contract.

### 217. "You detect an inbound gap: you expected seqnum 52 and message 87 arrives. Walk me through the recovery."

The peer sent 35 messages you never got. You send **ResendRequest (35=2)**
with BeginSeqNo=52, and you drop further out-of-order messages until the
hole is filled -- processing message 88 before 52-87 would corrupt order
state. The peer now replays from its message store with two crucial
transformations. Application messages (orders, executions) are re-sent
with **PossDupFlag(43)=Y** and **OrigSendingTime(122)** set to the
original send time -- the flag warns you this may be a duplicate of
something you actually did receive, so your handler must be idempotent
about it. Admin messages (heartbeats, test requests) would be noise to
replay, so consecutive runs of them are **coalesced into
SequenceReset-GapFill (35=4, GapFillFlag=Y)**: "nothing you care about
lived in 53-61; advance your expectation to 62." The receiving side
applies the replayed messages in order, sequence sanity is restored, and
the session continues as if the gap never happened. This mechanism is
why a FIX engine is only as good as its message store (Q220).

*In this library:* `fix/FixSession.java` implements both directions --
the javadoc states it exactly: "application messages are replayed with
PossDupFlag(43)/OrigSendingTime(122) and admin runs are coalesced into
SequenceReset-GapFill(4)"; the `Listener.onResendServed` callback reports
the serviced range, and `FixSessionTest.java` asserts the recovery.

### 218. "Why does a too-LOW sequence number without PossDup mean immediate disconnect, not repair?"

Because unlike a gap, it has no innocent explanation. A too-high seqnum
means "the network lost something" -- repairable, ask for a resend. But
the peer sending 40 when you expect 52 claims to be BEHIND its own
history: either it lost its persistent state (crashed and restarted with
a wiped store), or your two views of the session have diverged
irreconcilably, or -- the security case -- someone is replaying an old
captured session at you. In every one of those worlds, continuing is
dangerous: the peer that lost state does not know which of your orders
it acknowledged; processing its messages as fresh could double-execute
business you already booked. There is no protocol move that repairs it,
because the disagreement is about the PAST, not about missing messages.
So the spec's answer is the honest one: disconnect, and force human or
explicit operational intervention -- typically an agreed sequence reset
or store reconciliation -- before trading resumes. The exception that
proves the rule: too-low WITH PossDupFlag=Y is just a resend duplicate,
silently suppressed.

*In this library:* `fix/FixSession.java` -- the javadoc contract:
"Duplicates (PossDup below the expected seqnum) are suppressed; a
too-low seqnum without PossDup disconnects the session," surfaced through
`Listener.onDisconnect(reason)`.

### 219. "The peer has gone quiet. Exactly when do you declare it dead?"

FIX makes dead-peer detection a two-stage escalation with tunable
timing, because TCP alone can leave a broken connection looking alive
for many minutes (half-open connections survive until a send fails).
Stage one: if you have received nothing for the negotiated HeartBtInt
(say 30s), the peer is not necessarily dead -- it may simply have had
nothing to say and ITS heartbeat may be in flight -- so you send a
**TestRequest** with a unique TestReqID, which obliges the peer to
answer with a Heartbeat echoing that id. Stage two: if the response does
not arrive within a further tolerance (commonly the interval again, or a
fraction of it), NOW you disconnect -- roughly two heartbeat intervals
of provable silence, not one. Your own sending side is symmetric: any
interval in which you sent nothing gets a Heartbeat, so the peer's timer
never fires spuriously against you. Tuning is a real trade: 5s intervals
detect death in ~10s but generate chatter and false positives over WAN
jitter; 60s is quiet but leaves you blind for two minutes while orders
may be resting. Desks pick per-counterparty values and monitor staleness
continuously, not just at the timer edges.

*In this library:* `fix/FixSession.java` -- the heartbeat thread sends
keep-alives on send-side quiet, probes with TestRequest on receive-side
staleness (`testRequestPending`), and disconnects on continued silence:
"Heartbeats with TestRequest probing and staleness disconnect."

### 220. "Your FIX engine restarts at 14:03 mid-session. What must have been persisted for trading to resume safely?"

Two things, with different durability profiles. First, the **sequence
counters** -- next outgoing, expected incoming -- persisted
synchronously on every change, because they are the session's identity:
come back with wrong counters and you either trigger the Q218 disconnect
or, worse, silently reuse seqnums the peer already saw. Sixteen bytes
written with synchronous semantics is cheap insurance. Second, the
**outbound message store**: every application message you ever sent,
keyed by sequence number, because after reconnecting the peer may
ResendRequest a range from BEFORE your restart -- and you must replay
those messages with PossDup, not shrug. An append-only log replayed into
memory on open is the classic shape: crash-safe by construction, and
reopening rebuilds the resend index. What does NOT need persisting:
inbound messages (the peer keeps those for you -- that is the symmetry
of Q216) and admin traffic (coalesced to GapFill on replay anyway). The
one test that proves the whole design: kill the process between two
orders, restart, and assert the peer sees a seamless sequence with no
manual reset.

*In this library:* `fix/FileSessionStore.java` -- `seqnums.dat` (16
bytes, RandomAccessFile "rwd" synchronous writes) plus an append-only
`messages.dat` replayed into memory on open; `FixPersistenceTest.java`
exercises restart continuity; the in-memory `FixSessionStore.inMemory()`
default makes non-durability an explicit choice.

### 221. "Anatomy of a NewOrderSingle: which fields, and which mistakes hurt?"

The load-bearing fields of 35=D: **ClOrdID (11)** -- YOUR unique id for
this order, the key every later message chains back to (Q223);
**Symbol (55)**; **Side (54)** ('1' buy, '2' sell); **OrderQty (38)**;
**OrdType (40)** -- '1' market, '2' limit; **Price (44)** -- required
for limits, ABSENT for markets (sending a price on a market order is a
classic venue-certification failure); **TimeInForce (59)** -- Day if
omitted, '3' IOC; plus TransactTime (60) and the session envelope. The
mistakes that hurt in production: reusing a ClOrdID (the venue rejects
or, far worse, treats it as a duplicate and drops it silently depending
on their dedupe), price-on-market as above, wrong TIF default
assumptions (an order resting all day that you believed was IOC), and
encoding a price with more decimals than the instrument's tick allows.
Everything the venue tells you afterwards -- ack, fills, rejection --
arrives as ExecutionReports referencing your ClOrdID, so the field
discipline here IS your order-state bookkeeping.

*In this library:* `fix/NewOrderSingle.java` -- the typed record
(clOrdId, symbol, side, quantity, ordType, price, timeInForce) with
`price = NaN` for market orders and TIF defaulting to Day on parse;
`fix/FixOrderEncoder.java` is the garbage-free writer of the same
message.

### 222. "An ExecutionReport arrives. What is the state machine, and which field pair do people confuse?"

The confused pair first: **ExecType (150)** says what JUST HAPPENED --
this event; **OrdStatus (39)** says where the order NOW IS -- the
resulting state. They often coincide ('0'/'0' for an ack) but must not
be conflated: a partial fill is ExecType=Trade ('F') with
OrdStatus=PartiallyFilled ('1'); the LAST fill is also ExecType='F' but
OrdStatus=Filled ('2') -- same event type, different resulting state.
The core machine: New ('0') on acceptance; zero or more Trade events
walking cumQty up; terminal states Filled ('2'), Canceled ('4'),
Rejected ('8'); Replaced ('5') re-parents the chain to a new ClOrdID
(Q223). The quantity algebra is the self-check built into every report:
**LastQty (32)** is this fill's size, **CumQty (14)** total filled,
**LeavesQty (151)** still working, and CumQty + LeavesQty must equal the
order quantity -- reconcile it on EVERY report, because a broken
invariant means you and the venue disagree about your position, which is
the most expensive disagreement in trading. AvgPx (6) carries the
volume-weighted fill price for the P&L side.

*In this library:* `fix/ExecutionReport.java` -- the typed record with
both `execType` and `ordStatus` constants ('0','4','5','F','8' and
'0','1','2','4','5','8'), lastQty/cumQty/leavesQty/avgPrice fields, and
venue-side factories (`accepted`, `filled`, `canceled`, `replaced`) that
keep the algebra consistent; `fix/FixExecReportView.java` is the
garbage-free reader of the same message.

### 223. "Explain ClOrdID discipline through a cancel/replace chain."

Every mutation of an order gets a **fresh ClOrdID** and names the id it
mutates in **OrigClOrdID (41)** -- so an order's life is a chain: D with
ClOrdID=A; cancel/replace (35=G) with OrigClOrdID=A, ClOrdID=B; another
replace with OrigClOrdID=B, ClOrdID=C. The venue confirms each link with
ExecType=Replaced, after which C is the live id and A, B are history.
Why so ceremonial? Because the chain keeps an asynchronous,
possibly-resent protocol unambiguous: a fill can arrive AFTER you sent
the replace but BEFORE its ack (it references the old id -- and it also
changes LeavesQty, which may cause the venue to reject or trim your
replace); a resend can deliver the same G twice (same ClOrdID = same
request, dedupe is trivial); and a reject of B leaves A still working --
your state machine must know that. The classic production bug is a
**chain fork**: sending a second replace referencing A while the first
(A-to-B) is still pending -- one of them must lose, and venues differ on
which; disciplined engines allow one in-flight mutation per chain and
refuse to issue another until the previous is acked or rejected.

*In this library:* `fix/OrderCancelRequest.java` and
`fix/OrderCancelReplaceRequest.java` carry the clOrdId/origClOrdId pair;
`fix/ExecutionReport.java` has EXEC_TYPE_REPLACED and the `replaced(...)`
factory computing the new leavesQty; `FixProtocolExtrasTest.java`
exercises the flows.

### 224. "Session Reject vs business-level rejection -- who sends what, when?"

Three refusal channels, three altitudes. **Session Reject (35=3)** is
the FIX engine itself refusing a message it cannot even accept
syntactically or session-wise: missing required tag, wrong data type,
seqnum trouble -- it references the offending message's sequence number
(RefSeqNum 45) and carries no business meaning; your ENGINE handles it,
and a burst of them means a schema or codec bug, not a trading problem.
**BusinessMessageReject (35=j)** is the application layer saying
"well-formed, but I cannot process this KIND of message right now" --
unsupported message type, market closed for that flow -- business-level,
but not about one order's merits. An **ExecutionReport with
ExecType=Rejected (150=8)** is the narrowest and most common: this
specific ORDER was refused -- price band, unknown symbol, risk limit --
and it flows into the order state machine like any other exec event,
moving that ClOrdID to a terminal state. Routing matters: wiring session
rejects into the order path (or order rejects into session alarms) is a
classic integration bug; the three belong to engine telemetry, gateway
telemetry, and order state respectively.

*In this library:* the outer two channels are implemented:
`fix/FixSession.java` delivers 35=3 through
`Listener.onReject(session, refSeqNum, text)`, and
`fix/ExecutionReport.java` carries EXEC_TYPE_REJECTED and
ORD_STATUS_REJECTED for the per-order channel (35=j is described for
completeness; this engine's declared scope is the session and
single-order message types).

### 225. "How does market data travel over FIX, and how do you read it without allocating?"

Two application messages: **MarketDataSnapshotFullRefresh (35=W)** -- the
full current picture for an instrument -- and
**MarketDataIncrementalRefresh (35=X)** -- deltas, each entry tagged with
MDUpdateAction (279: new/change/delete). Entries live in a repeating
group counted by NoMDEntries (268), each carrying MDEntryType (269: '0'
bid, '1' offer), MDEntryPx (270) and MDEntrySize (271). This is the
lingua franca of e-FX: bank streams and many ECNs quote this way, often
as TIERED streams where the Nth entry is the Nth liquidity tier -- so
preserving entry ORDER within a message is semantically required, not a
nicety. Reading it fast means refusing the String-per-tag decode: one
pass over the framed bytes, recording primitives into preallocated
parallel arrays (action, type, price mantissa/decimals, size), skipping
unknown tags without materializing anything, and exposing primitive
getters -- with prices kept as scaled longs so the feed-to-order path
never rounds through a double (Q204). Snapshot entries are implicitly
"new"; the consumer applies W by rebuild and X by delta, the same
startup dance as Q197.

*In this library:* `fix/FixMarketDataView.java` -- a flyweight over
framed bytes handling both 35=W and 35=X, tags 268/269/270/271/279,
scaled-long MDEntryPx, order-preserving entries ("for tiered LP streams,
position IS the tier"), and per-message entry caps with a
dropped-entries counter; `FixMarketDataViewTest.java` pins it.

### 226. "FIX is text. Your order path is measured in nanoseconds. Reconcile that with a garbage-free encoder."

When the venue only speaks FIX, order entry IS the FIX edge, and
String-building per order would put allocation right back on the
measured path -- so you build the message into a **reusable byte
buffer** with techniques standard in commercial garbage-free engines.
(1) Write the BODY first at a fixed offset, then write the
"8=FIX.4.4|9=len|" prefix BACKWARDS in front of it -- the length is only
known after the body exists, and writing backwards avoids both a second
pass and a copy; append "10=checksum|" last. (2) Render numbers with
ASCII digit writers -- longs and scaled-decimal prices digit by digit,
no Long.toString, no format calls. (3) Cache the timestamp's date part:
"yyyyMMdd-" changes once per UTC day, so recompute it off the per-order
path and render only the intraday time from millis by division. (4)
Pre-register symbols as byte arrays keyed by dense id, so the hot path
never encodes a String. (5) Precompute the constant session chunk
("35=D|49=...|56=...|34=") once per session. Correctness is pinned by
round-tripping every encoded message through a separate validating
parser and by an allocation-counter test asserting zero garbage per
encode (Q213).

*In this library:* `fix/FixOrderEncoder.java` -- every one of those five
techniques is named in its javadoc, including the backwards prefix and
the mantissa-times-10^-decimals price representation;
`FixOrderEncoderTest.java` does the round-trip through
`FixMessage.parse` plus the zero-allocation assertion.

### 227. "Where do credentials live in a FIX session, and what should you assume about their protection?"

On the **Logon (35=A)** message: Username (553) and Password (554),
carried once at session establishment -- plus the identity that matters
just as much, the SenderCompID/TargetCompID pair (49/56), which the
acceptor must validate against the connection (a valid password with the
wrong CompID is still an impostor). Assume the worst about transport:
classic FIX is plaintext, so 553/554 cross the wire readable -- which is
why real deployments run FIX inside TLS tunnels or on private lines, and
why venues layer on source-IP allowlists, per-session credentials, and
password rotation. Defense in depth continues after logon:
sequence-number continuity itself is a weak authenticity check (an
impostor without your session state trips the Q218 disconnect), and
venues cap logon attempts to slow credential guessing. Engineering
hygiene on your side: credentials belong in config or secret stores,
never in code; they must never be logged (FIX message logs are the
classic leak -- mask tag 554 in stores and logs); and a logon rejection
must not auto-retry in a tight loop with the same credentials, or you
will lock the session out at the venue.

*In this library:* `fix/FixSession.java` --
`Config.withCredentials(username, password)` "Adds
Username(553)/Password(554) to the initiator's Logon"; the config record
carries them per session, and the session store persists sequence state,
not secrets.

### 228. "The venue hands you a certification script. How do you get your engine ready before touching their test gateway?"

By making certification boring: run BOTH ends of the protocol yourself,
in-process, over real sockets -- a **loopback harness** where your
engine's initiator talks to your engine's acceptor. That gives you,
cheaply and deterministically, the exact scenarios every venue script
contains: the logon handshake, heartbeat exchange and TestRequest
response, an order round-trip (D out, 8 back), cancel and cancel/replace
chains, forced sequence gaps (send with a skipped seqnum, assert the
ResendRequest and the PossDup replay), the too-low-seqnum disconnect,
the logout handshake, and kill-and-restart with a durable store to prove
resend across process death. The harness pattern that makes it testable:
a listener that records every callback and exposes latches for the
interesting events, so tests assert "gap detected with expected=5,
received=9" instead of sleeping and hoping. Only after the loopback
suite is green do you spend time on the venue's gateway, where iteration
is slow and test sessions are rationed -- at that point the surprises
left are their nonstandard fields and timing quirks, not your session
logic.

*In this library:* `src/test/.../fix/FixSessionTest.java` is exactly this
harness -- initiator and acceptor over a local ServerSocket, a Recorder
listener full of CountDownLatches (logon, gap, resendServed,
disconnected), and scenario tests for orders, gaps and recovery;
`FixPersistenceTest.java` adds the restart-with-FileSessionStore leg.

---

## Transport & reliability engineering (Q229-Q239)

### 229. "Why do orders travel over TCP but market data over UDP multicast?"

Because the two flows want opposite guarantees. An order is a
**one-to-one, must-not-be-lost, must-not-be-duplicated** conversation:
TCP's retransmission, ordering and connection semantics map onto it
naturally, and the FIX session layer adds cross-connection continuity on
top (Q216). Losing an order silently is unacceptable; a few hundred
microseconds of retransmission delay on a rare loss is survivable.
Market data is **one-to-thousands and latest-wins**: with TCP the
exchange would maintain a connection per subscriber, and one slow
consumer's backpressure would either buffer unboundedly or slow the
send loop -- the fast consumers pay for the slowest. UDP multicast
inverts it: the exchange transmits each packet ONCE, the network fabric
replicates it, every subscriber receives at wire speed, and a slow
consumer hurts only itself. The lost guarantees are then rebuilt one
layer up, cheaply: sequence numbers detect loss (Q198), A/B feeds make
it rare (Q195), and retransmission/snapshot channels repair it (Q194).
That layering -- unreliable transport plus sequence-numbered recovery --
is deliberately cheaper than making the transport reliable for
everyone.

*In this library:* the participant edge mirrors the split:
`fix/FixSession.java` runs orders over TCP with full session recovery,
while the feed side (`marketdata/ItchCodec.java`,
`marketdata/L3BookBuilder.java`) is built around a sequenced datagram
world where the consumer, not the transport, owns integrity.

### 230. "Your order gateway shows a mysterious extra 40ms occasionally. What TCP settings do you check first?"

**Nagle's algorithm and delayed ACK**, separately harmless, jointly
poisonous. Nagle holds back small writes while any previously sent data
is unacknowledged, hoping to coalesce them into fewer segments -- a 1980s
bandwidth optimization. Delayed ACK makes the receiver wait (classically
up to 40ms, or until a second segment arrives) before acknowledging a
lone segment, hoping to piggyback the ACK on reply data. Put a
small-message request/response protocol -- which is exactly what FIX
order entry is -- on top of both, and you get the classic deadlock-ish
stall: the sender holds a small message waiting for an ACK; the receiver
holds the ACK waiting for more data; the 40ms timer breaks the tie. Your
p50 looks fine and your p99 grows a 40ms shelf. The fix is one line:
**TCP_NODELAY** (disable Nagle) on every latency-sensitive socket, and
it should be reflexive in trading code -- the pennies of extra packets
are irrelevant next to a 40ms tail on an order path. Check it on BOTH
ends; a venue-side Nagle stalls you just as effectively.

*In this library:* `fix/FixSession.java` sets it in the constructor --
`socket.setTcpNoDelay(true)` -- before any message flows, on initiator
and acceptor alike.

### 231. "A venue restarts and 3,000 clients reconnect at once. What goes wrong, and what is the polite client?"

The **reconnect storm**: every client detects the drop within the same
few milliseconds and dials back simultaneously; the venue's accept queue
and logon path saturate; most connections fail or time out; every client
retries -- again in lockstep, because they all run the same retry timer
-- and the herd hammers the recovering service back down. Two mechanisms
break it. **Exponential backoff** spreads attempts over time: wait 500ms,
then 1s, 2s, 4s ... capped at some maximum, so pressure decays instead
of repeating. **Jitter** breaks the synchronization: randomize each
delay (full jitter -- uniform between zero and the exponential bound --
is the standard choice), so the herd's retries smear into a flat trickle
instead of arriving in waves. Two more marks of the polite client: a
**bounded attempt count** with a loud failure to operations rather than
an eternal silent retry, and resetting the backoff only after a
connection has proven HEALTHY (logon complete, first heartbeat), not
merely accepted -- otherwise a flapping listener resets everyone's timer
and the storm recurs on each flap.

*In this library:* `feed/WebSocketFeed.java` implements bounded
exponential backoff (`withReconnect(maxAttempts, initialBackoffMillis)`,
defaults 10 attempts from 500ms) with reconnect epochs that never
overlap; `WebSocketFeedTest.java` drives drop-and-reconnect against a
local RFC 6455 server.

### 232. "You sent an order, the connection dropped before any reply. Do you send it again?"

Not blindly -- this is the **duplicate-order problem**, and naive retry
is how firms end up with twice the position they wanted. The drop left
you in genuine ignorance: the order may have died in transit, may be
resting on the book, may already be filled. The discipline: (1)
reconnect and let the session layer recover first -- FIX resend
mechanics (Q217) will redeliver any ExecutionReports you missed, which
usually resolves the ambiguity by itself; (2) if still unknown, ASK
rather than assume -- an OrderStatusRequest against your ClOrdID, or
reconcile against a drop-copy feed; (3) only then re-submit, and with a
**new ClOrdID** chained by your own bookkeeping, never a blind repeat.
The structural protection is that ClOrdID makes submission
**idempotent at the venue**: venues reject or ignore a duplicate
ClOrdID, so re-sending the SAME id is safe against doubling -- but you
must then handle the reject as "it got through the first time."
Wrapping it together: order submission after reconnect is a state
reconciliation problem, not a retry problem, and the ClOrdID chain
(Q223) is the ledger that makes reconciliation decidable.

*In this library:* `fix/FixSession.java` provides the recovery half --
PossDup-flagged redelivery of missed ExecutionReports and duplicate
suppression -- and `fix/NewOrderSingle.java`'s clOrdId is the
idempotency key; the design intent of one-id-one-intent runs through
`fix/OrderCancelRequest.java`/`OrderCancelReplaceRequest.java` as well.

### 233. "Fills: do you want at-least-once or exactly-once delivery? Defend it."

Wanting exactly-once is natural; ENGINEERING exactly-once over failing
networks is famously impossible at the transport level (the two
generals' problem) -- any acknowledgment can itself be lost, forcing a
resend and hence a possible duplicate. So mature systems choose
**at-least-once delivery plus idempotent application**: the transport
may redeliver, and the consumer deduplicates on a unique key, achieving
exactly-once EFFECT rather than exactly-once delivery. Fills come with
the perfect key: **ExecID (17)** is unique per execution event, so a
position keeper that ignores already-seen ExecIDs books each fill
precisely once no matter how many times the report arrives -- and FIX
resend semantics lean into this, redelivering with PossDupFlag=Y
precisely because the protocol admits it cannot know what you received
(Q217). The other direction is far worse: at-most-once (never resend)
converts every network blip into a silently missing fill, and a
position you believe is flat but is not. Rule: for anything that mutates
money -- fills, transfers, cancels -- prefer duplicate-plus-dedupe over
silence, and put the dedupe at the LAST stateful consumer, not in the
transport where the key's meaning is invisible.

*In this library:* `fix/ExecutionReport.java` carries `execId` as the
dedupe key alongside cumQty/leavesQty (whose running algebra is itself a
second dedupe check -- a replayed fill that double-counts breaks the
CumQty+LeavesQty invariant); `fix/FixSession.java` implements the
at-least-once redelivery side with PossDup marking.

### 234. "Two servers both timestamp the same fill. The timestamps disagree by 800 microseconds. Which one is lying?"

Both, probably -- the real question is by how much, and whether you can
bound it. **NTP** disciplines a clock to within milliseconds over a
WAN, maybe a few hundred microseconds on a good LAN: fine for humans,
useless for microstructure, where an entire tick-to-trade happens in
single-digit microseconds. **PTP (IEEE 1588)**, with hardware timestamp
support in the NIC and PTP-aware switches, reaches sub-microsecond
discipline -- the difference is that PTP timestamps the sync packets in
HARDWARE at the wire, removing the OS jitter that poisons NTP. Even
then, timestamps lie in structured ways: the clock drifts between syncs,
`System.nanoTime` is monotonic but shares no epoch across machines
(never difference it across hosts), a software timestamp includes
unbounded scheduler delay between wire arrival and the stamping
instruction, and virtualized clocks add their own fiction. The
professional posture: never compare one-way timestamps across machines
whose sync error you have not measured; prefer round-trip measurements
on ONE clock when possible; and for real one-way latency numbers, use
NIC hardware timestamps on PTP-disciplined clocks -- anything else is
storytelling with error bars you have not earned.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` Tier 4 names this
explicitly -- "Hardware timestamps & PTP ... measurement at wire
precision instead of System.nanoTime at syscall precision. Prerequisite
for honest wire-to-wire numbers"; feed code keeps exchange event time
and local time as separate, never-conflated notions
(`feed/WebSocketFeed.java` publishes exchange event time).

### 235. "What is a sequencer-bus architecture, and why do deterministic trading systems use it?"

Put ONE component -- the sequencer -- between all producers and all
consumers: every input (order, tick, timer, admin command) is sent to
the sequencer, which assigns it a global sequence number and publishes
the totally-ordered stream to every consumer. Consumers are then
**deterministic state machines**: same input stream in, same state out,
bit for bit. That single decision buys a remarkable set of properties.
**Recovery**: a crashed consumer replays the sequenced log from its last
checkpoint and arrives at exactly the state it lost. **Hot-hot
redundancy**: run two copies of the matching engine off the same stream;
they stay identical without any state synchronization protocol -- the
stream IS the synchronization. **Testing and forensics**: capture the
stream and every production incident is replayable in the lab,
identically, forever. **Fairness and audit**: the sequence number is
THE arrival order, adjudicating disputes by construction. The cost is
equally clear: the sequencer is a single point of serialization -- its
throughput and latency bound the system -- so it is engineered as the
most paranoid, minimal component in the building, and everything
downstream stays single-writer and allocation-free to keep replay exact.

*In this library:* the determinism half is the design of the whole hot
path -- `marketdata/TickRingBuffer.java` hands one consumer a totally
ordered stream, `data/TickFileWriter.java` captures sequenced sessions
for exact replay, and the single-writer discipline in
`marketdata/L3BookBuilder.java`/`HftMarketDataBus.java` is precisely
what makes replaying a sequenced stream reproduce identical state.

### 236. "The disk hiccups for two seconds while you are recording ticks on the trading bus. What SHOULD happen?"

The recorder loses data and the trading loop never notices -- and that
must be a DESIGN decision, not an accident. When a consumer cannot keep
up, there are exactly three honest policies. **Block**: propagate
backpressure upstream -- correct for order flow (never drop an order),
catastrophic on a trading tick path, where the recorder's disk stall
would become the strategy's latency spike. **Conflate**: keep latest per
key -- right for quote-consuming dashboards (Q196), wrong for a
recorder whose purpose is completeness. **Drop and count**: shed the
overflow, increment a visible counter, keep the hot path unconditional
-- the honest choice for a recorder riding a trading bus, captured in
one sentence: a recording gap you can SEE beats latency you cannot
explain. Implementation shape: the bus consumer does one ring publish
(never blocks); a dedicated writer thread drains ring-to-disk and
absorbs stalls up to the ring's capacity; beyond that, ticks drop into
the counter. The counter is the contract -- zero means the capture is
complete; nonzero tells you exactly how much to distrust, and alerting
on it converts silent loss into an operations event.

*In this library:* `data/AsyncTickCapture.java` -- the javadoc argues
this policy verbatim ("ticks are dropped from the capture -- counted in
droppedTicks, never blocking the consumer. A recording gap you can see
beats latency you can't explain"); `marketdata/TickRingBuffer.publish`
returns false on full so "the caller chooses the backpressure policy
(spin, drop, or count)."

### 237. "Size the ring buffer between the bus and the tick recorder. Show the arithmetic."

Three numbers: slot size, peak sustained rate, and the worst stall you
intend to survive. Slot size: a tick is an int symbolId + double price +
double size + long nanos = 28 bytes across the parallel arrays, so
capacity C costs 28C bytes. Stall math: to absorb a stall of T seconds
at R ticks/second you need C >= R x T. Concretely: a busy feed at
200,000 ticks/s and a 5-second worst-case disk stall (page-cache
writeback, AV scan) needs a million slots -- about 28 MB, which is
nothing at server scale, so err large. Two constraints refine it: the
capacity should be a **power of two**, because index = sequence AND
(capacity-1) replaces a modulo on every operation; and bigger is not
free forever -- a ring vastly larger than the L2/L3 cache means the
consumer reads cold lines when it lags far behind, so you buy stall
absorption with a little per-tick latency while catching up. State the
policy at the boundary too: the ring absorbs stalls up to C, and beyond
that your drop-or-block choice (Q236) takes over -- sizing and policy
are one decision, not two.

*In this library:* `marketdata/TickRingBuffer.java` rounds capacity up
to a power of two and documents the Disruptor-style padding and
acquire/release publication; `data/AsyncTickCapture.java` does this
exact arithmetic in its javadoc: "capacity 1M ~ 28 MB ~ several seconds
of a busy feed."

### 238. "Your JVM stack is tuned to the practical floor. What are the next tiers, down to the physics?"

Ascending commitment. **Kernel-bypass networking** (DPDK, Solarflare
Onload/ef_vi, Mellanox VMA): the NIC DMA-writes packets into user-space
rings -- no syscalls, no kernel stack, no interrupts -- taking
wire-to-application from roughly 5-15 microseconds on a tuned kernel
TCP stack to 1-2 microseconds; reachable from Java via JNI/FFM, though
most shops move the I/O layer to C++ at this point. **Hardware
timestamps and PTP-disciplined clocks**: not a latency win but the
measurement prerequisite -- without wire-precision stamps you cannot
even prove the other tiers (Q234). **RDMA / shared-memory transports**
for intra-colo hops: single-digit microseconds machine-to-machine with
neither kernel involved. **FPGA/ASIC**: feed handler, risk check and
order generation in silicon -- wire-to-wire in tens to hundreds of
NANOseconds; the software stack becomes the control plane configuring a
hardware data plane. Finally **colocation and the physical layer**:
rack position, switch hops, cut-through switching, microwave links
between exchanges -- where the budget line is literally the speed of
light. The discipline that survives every tier: measure with
percentiles including max, pace to avoid coordinated omission, and run
a hiccup monitor so you attribute tails to platform vs code before
optimizing the wrong one.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` -- "Tier 4 -- Kernel
bypass & hardware (beyond the JVM entirely)" lays out exactly this
ladder with the numbers above, then the measurement-discipline section
that applies at every tier.

### 239. "When is a paper trading gateway enough, and when is it lying to you?"

It is enough when the question is about YOUR code, and lying when the
question is about the MARKET. A quote-driven paper venue -- market
orders fill at the touch, resting limits fill when the market crosses
them, every order passes the same pre-trade risk gate as production,
full account tracking of positions, cash, realized/unrealized P&L and
commission -- is exactly the right tool for closing the
research-to-production loop: does the strategy wired to real feeds emit
sane orders, do the risk limits fire, does the P&L accounting reconcile,
does the whole stack survive a full day of live data? Those are
software-correctness questions, and simulated fills answer them
faithfully. What it structurally CANNOT tell you: **queue position**
(your limit order fills when price crosses it, with no model of the
5,000 shares ahead of you -- Q191), **market impact** (your size never
moves the quote), adverse selection on your passive fills, real venue
latency and race outcomes, and venue-specific rejects. Paper profits on
a maker strategy are therefore systematically optimistic -- fills it
grants free are exactly the ones the queue would deny you. Honest
usage: paper-trade to certify the machinery, then measure the
microstructure effects with small REAL orders, because those effects
are properties of the market, not of your code.

*In this library:* `trading/PaperTradingGateway.java` -- quote-driven
fills at the touch, the optional `risk/PreTradeLimitChecker.java` gate
("rejected orders never reach the market, exactly like production"),
full account snapshots, and a capped rejection log; the queue-position
truth it cannot simulate is what `marketdata/L3BookBuilder.java`
measures on real feeds.

---

## Order book mechanics (Q240-Q254)

### 240. "Walk me through price-time priority. Two orders at the same price -- who trades first, and why does the market insist on it?"

The book is two sorted queues. Levels are ordered by price -- best bid
highest, best ask lowest -- and within a level, orders queue by arrival
time: first in, first filled. So when a sell order crosses, it lifts the
highest bid level first, and within that level the *oldest* resting order
first. Concretely: A posts a bid for 100 at 50.00 at 09:30:01, B posts 100
at 50.00 at 09:30:02, C posts 100 at 50.01 at 09:30:03. A market sell of
150 fills C's 100 first (better price), then 50 of A (same level, earlier
time). B still waits with 100; A waits with 50 but keeps its place at the
front. The time leg matters because it is the incentive to quote early and
honestly: if fills were pro-rata or random, nobody would race to post the
first, tightest quote, and displayed liquidity would rot. Price-time is
the property every matching invariant in this codebase is checked against
-- executions always consume the queue head, which is also what makes
queue-position tracking possible at all.

*In this library:* `orderbook/OrderBook.java` (the readable engine -- every
rule spelled out; per-level `ArrayDeque` FIFO), `orderbook/HftOrderBook.java`
(the same semantics rebuilt venue-grade), and `marketdata/L3BookBuilder.java`
(exploits "executions consume the head" to track your queue position in O(1)).

### 241. "I send a market buy for 15 when the whole ask side only shows 10. What happens?"

The order matches as deep as the book goes and the excess evaporates -- a
market order never rests, because it has no price to rest at. In this
library's venue-grade book the call returns the *filled quantity*, so the
caller sees 10 back from a 15-lot request and knows 5 went unfilled: with
asks of 10 at 1001 and 10 at 1005, a market buy of 15 sweeps 10 at 1001
and 5 at 1005 and returns 15; against an empty opposite side it returns 0
and the book is unchanged. Note what the sweep did to price: the taker
paid 1001 for the first ten lots and 1005 for the rest -- "walking the
book" is exactly why large marketable orders move the market, and why
execution desks slice them. Real venues often refuse the raw version of
this order (or convert it to a limit at a protected band) precisely
because an unbounded sweep through a thin book is how flash-crash prints
happen; the price-collar and LULD machinery exists for the same reason.

*In this library:* `orderbook/HftOrderBook.java` (`submitMarket` -- returns
filled quantity, never rests; the sweep test in
`src/test/java/com/quantfinlib/orderbook/HftOrderBookTest.java`
`marketOrdersSweepAndNeverRest`), `orderbook/BookAnalytics.java` (`sweep`
simulates the same walk non-destructively and reports VWAP and impact bps).

### 242. "Enumerate the lifecycle states of an order. Which transitions are legal?"

The canonical lifecycle is small and worth knowing cold: NEW (accepted,
working), PARTIALLY_FILLED (some quantity executed, remainder working),
FILLED (terminal -- nothing left), CANCELED (terminal -- remainder pulled),
REJECTED (terminal -- never worked at all). Legal transitions: NEW ->
PARTIALLY_FILLED -> ... -> FILLED, NEW -> FILLED directly (one print),
NEW or PARTIALLY_FILLED -> CANCELED (you can cancel a remainder but not a
completed fill), and nothing leaves FILLED, CANCELED or REJECTED. The
trap: REJECTED is not CANCELED -- a rejected order never consumed queue
priority, never printed, and needs no cancel; conflating them corrupts
position accounting because a cancel-after-partial still leaves you with
the filled part. Inside a matching engine the resting order itself needs
none of this ceremony: the book only stores the *remaining* quantity, and
"filled" is simply the moment that remainder hits zero and the node is
unlinked -- state machines live at the gateway, not in the ladder.

*In this library:* `trading/OrderStatus.java` (exactly these five states),
`trading/PaperTradingGateway.java` (the working-order state machine driving
them), `orderbook/LimitOrder.java` (the book-side view: `quantity` IS the
remaining amount, nothing else).

### 243. "A limit buy for 25 crosses a resting ask of 10. Describe every mutation."

One order, two roles. The incoming buy is a *taker* for its first 10 lots:
it matches at the resting ask's price (price improvement goes to the
aggressor if it was willing to pay more -- the maker's limit sets the
print). The maker's remaining quantity drops 10 to zero, its node is
unlinked from the level FIFO, its pool slot and id-map entry are freed,
and a trade (makerId, takerId, price, 10) is emitted. The taker's
remainder of 15 then flips role and becomes a *maker*: it rests at its own
limit price, at the *back* of that level's queue, with a fresh timestamp
priority. Both counters move -- one trade, one new resting order. The
subtle accounting point: even a fully filled incoming order was still
*accepted*, so it gets a positive order id (it appears as the taker id in
the trade prints); acceptance and execution are different events. Desks
care because fills reconcile against order ids -- an id that never comes
back in a trade is a working order, not an error.

*In this library:* `orderbook/HftOrderBook.java` (`submitLimit` -- "matches
while it crosses, rests any remainder"; the javadoc makes the
even-fully-filled-orders-get-ids point explicitly),
`orderbook/OrderBook.java` (same semantics, readable).

### 244. "IOC, FOK, post-only -- give me the exact semantics, and what the venue answers when each cannot be honored."

Three different contracts about *resting* and *taking*. IOC
(immediate-or-cancel): take everything you can within the limit right now;
the remainder expires -- never rests. It cannot be "rejected" for lack of
liquidity, it just fills less (possibly zero). FOK (fill-or-kill): execute
the FULL quantity within the limit or do nothing at all -- no partial, no
resting, no trades on a kill. Post-only: the inverse personality -- rest or
die; if the order would trade on arrival it is rejected outright, because
its owner is a maker who wants the rebate/spread and refuses to pay the
taker fee. The venue's answers in this library are honest return codes:
IOC and FOK return the filled quantity (0 = nothing crossed / killed);
post-only returns `REJECT_WOULD_CROSS` (-4) when it would take, alongside
the general codes `REJECT_POOL_FULL`, `REJECT_OUT_OF_BAND` and
`REJECT_INVALID`. Rejections are return codes, never exceptions -- a venue
must not unwind its matching loop over one bad order.

*In this library:* `orderbook/HftOrderBook.java` (`submitIoc`, `submitFok`,
`submitPostOnly`, and the four `REJECT_*` constants with javadoc), tested
for exact venue semantics in
`src/test/java/com/quantfinlib/orderbook/HftOrderBookTifTest.java`.

### 245. "FOK is 'all or nothing.' How do you implement that atomically -- and how would you PROVE the 'nothing' half?"

Two-phase: probe, then take. The probe walks the opposite side from the
touch toward the limit price, summing available quantity, and stops early
the moment the running total reaches the requested size; only if the full
quantity is reachable within the limit does the order execute -- and then
it executes as a plain IOC, which is now guaranteed to fill completely
because the book cannot change between probe and take (the matching core
is single-threaded; that is not an optimization, it is what makes the
two-phase approach correct without locks). The "nothing" half is the part
sloppy implementations get wrong: a killed FOK must emit zero trades,
mutate zero levels, and ideally consume no order id. The proof is a
property test, not an example: over thousands of randomized books, assert
"a FOK either fills entirely or changes nothing" by snapshotting book
state before, submitting, and demanding either full fill or bit-identical
state after. This library states that property verbatim in its testing
checklist and asserts it in the TIF tests.

*In this library:* `orderbook/HftOrderBook.java` (`submitFok` +
`fillableWithin` -- the early-exit bitmap probe; javadoc: "a killed order
emits no trades", zero allocation), the FOK property in
`docs/LEARN.md` section 19, tests in
`src/test/java/com/quantfinlib/orderbook/HftOrderBookTifTest.java`.

### 246. "What is self-trade prevention, why do venues offer it, and what are the standard flavors?"

Self-trade prevention (STP) stops one firm's buy order from matching its
own sell order. Why it matters: a self-match transfers nothing
economically but *prints* -- it inflates volume, can move the tape, and
looks exactly like wash trading, which is illegal in most jurisdictions.
Market makers quoting both sides across many strategies would self-match
constantly by accident, so venues offer STP keyed on a participant/group
id, with standard flavors describing WHICH order dies on a would-be
self-match: cancel-newest (the incoming order is rejected -- protects the
resting order's queue priority), cancel-oldest (the resting order is
pulled and the incoming one proceeds -- useful when the new order carries
fresher intent), cancel-both, and decrement-and-cancel (reduce the larger
by the smaller). The design question to reason through: STP is a *venue*
feature keyed on ids the venue knows, so in a matching core it is one
extra comparison in the match loop before `fillLevel` -- this library's
books do not carry participant ids, which is exactly why they don't
implement it, and the surveillance side of the same coin (detecting
self-matched prints after the fact) belongs to the regulatory layer.

*In this library:* conceptual -- `orderbook/HftOrderBook.java` is where the
check would live (the match loop already has maker and taker ids in hand);
`docs/LEARN.md` flags STP as expected follow-on material to matching-engine
design, and the `regulatory/` package covers the surveillance vocabulary.

### 247. "I want to change my resting order's price -- or just its size. What happens to my queue position, and why is the rule asymmetric?"

The universal rule: anything that could disadvantage those behind you
costs you your place. A price change is a new order -- you leave one queue
and join the back of another, full stop. A size *increase* also sends you
to the back: the people queued behind you at that price agreed to wait
behind your original size, not your new one. A size *decrease* usually
keeps priority -- shrinking helps everyone behind you, so venues let you
keep your spot. That asymmetry is why desks treat modify as
cancel-and-replace in their models: this library's books implement modify
exactly that way (cancel, then submit), which is honest about the venue
economics -- your amended order re-queues. The practical consequence is
large: at a busy level, queue position IS the asset (front of queue fills
first and captures spread with less adverse selection), so a market maker
re-pricing a quote pays for the new price with a trip to the back, and
strategies weigh "better price, worse position" explicitly using
fill-probability-vs-position models.

*In this library:* `orderbook/OrderBook.java` / `orderbook/HftOrderBook.java`
(`cancel` + resubmit is the modify path -- re-queueing is implicit and
honest), `marketdata/L3BookBuilder.java` (replace events end position
tracking -- same rule from the participant side),
`microstructure/QueuePositionEstimator.java` +
`microstructure/QueueModel.java` (what that position is worth).

### 248. "Why do cancels outnumber trades ten to one on modern venues? Is that a problem?"

Because quotes are *conditional* and conditions change faster than trades
arrive. A market maker's fair value moves with every tick of the
underlying, of correlated instruments, of the queue itself -- and every
move that does not produce a fill produces a re-quote, which is a cancel
plus a new order. Add fleeting cross-venue arbitrage (quote here against
liquidity there; when the far side fades, pull), pegged and layered
execution algos re-slicing, and you get message-to-trade ratios of 10:1 as
routine and far higher for pure makers. Is it a problem? Two honest
answers. Economically, mostly no -- fast cancellation is the price of
tight spreads, since makers quote tighter when they can exit stale quotes
quickly. Operationally, yes enough that venues meter it: order-to-trade
ratio is the metric venues fine you on when your algo sprays cancels, and
message-rate limits (see the throttle question later) exist because the
venue's own infrastructure pays for your churn. That is why both books
here count orders, cancels and trades natively -- OTR is a first-class
surveillance number, not an afterthought.

*In this library:* `orderbook/OrderBook.java` (`orderCount`/`cancelCount`/
`tradeCount`, `orderToTradeRatio()`), `regulatory/MarketQualityMetrics.java`
(OTR among the venue-quality metrics), `docs/LEARN.md` section 9b (the
fines context).

### 249. "Top of book: 900 bid, 100 offered. What do you predict, and what number do you quote as the prediction?"

Predict a tick up -- and quote the *microprice* as the fair value. Depth
imbalance is among the most robust short-horizon signals in
microstructure: when the bid queue is much larger than the ask queue, the
small ask queue is likelier to be consumed first, so the next mid move is
more likely up. The two standard quantities: imbalance
I = bidSize / (bidSize + askSize) (here 0.9), and the size-weighted
microprice I*ask + (1-I)*bid -- note it weights by the *opposite* side's
size, pulling fair value TOWARD the thin side, which is where price is
about to go. With bid 50.00 x 900, ask 50.02 x 100: microprice =
0.9*50.02 + 0.1*50.00 = 50.018, i.e. fair value sits two-tenths of a
cent below the ask, not at the 50.01 mid. Uses on a desk: quote around
microprice instead of mid (you will shade correctly for free), gate
passive child orders on imbalance (do not join a queue about to be run
over), and feed multi-level imbalance into short-horizon alpha. Caveat a
good answer volunteers: displayed imbalance is gameable (spoofing) and
blind to hidden size, so it degrades where hidden liquidity dominates.

*In this library:* `orderbook/BookAnalytics.java` (`imbalance(book, levels)`,
`microprice` -- javadoc: "a better short-horizon fair value than the mid
when the book is imbalanced"), `microstructure/FlowSignals.java` (imbalance
as input to flow alpha).

### 250. "How does an iceberg order actually work at the venue, and how would you detect one from the tape?"

At the venue an iceberg (reserve) order has a total size and a display
size: the book shows only the tip (say 100 of 5,000), and each time the
tip fully executes, the venue reloads a fresh tip from reserve -- crucially
at the BACK of the price level's queue, with a new timestamp (some venues
also randomize the reload size to blunt detection). Hidden reserve
typically has lower priority than any displayed size at the same price.
Detection from the lit tape uses the one signature that cannot lie: a
single execution LARGER than the size displayed at that moment. Displayed
liquidity cannot fill more than it shows, so the excess in that one print
necessarily executed against hidden size. The tempting cumulative version
-- "this level traded more than it ever displayed" -- is NOT sound at L2,
because a busy level legitimately trades many times its instantaneous
display through ordinary adds and refreshes; that formulation false-flags
normal flow. The payoff of detecting: an execution algo can size child
orders against true depth rather than the tip, and a maker can avoid
queueing behind a reloading whale.

*In this library:* `microstructure/HiddenLiquidityDetector.java` (exactly
the per-print signature above, with the javadoc explaining why the
cumulative version is unsound; `hiddenMultiplier` estimates how much size
lurks behind the tip).

### 251. "Walk me through an opening auction uncross. Buys: market 500; limit 300 at 10.02. Sells: limit 400 at 10.00, limit 300 at 10.01. Where does it clear?"

The rulebook hierarchy, in order: (1) maximize executable volume, (2)
among volume ties, minimize surplus (leftover imbalance), (3) among
remaining ties, pick the price closest to the reference (typically last
trade). Build cumulative curves. Eligible buys (willing to pay >= p): at
10.00 -> 800 (market 500 + limit 300), at 10.01 -> 800, at 10.02 -> 800.
Eligible sells (willing to sell <= p): at 10.00 -> 400, at 10.01 -> 700,
at 10.02 -> 700. Executable volume = min(buys, sells): 400 / 700 / 700.
Volume is maximized (700) at both 10.01 and 10.02 -- so go to rule 2:
surplus at 10.01 is 800-700 = 100 buy-side, at 10.02 also 100; still tied,
so rule 3 picks whichever is closer to the reference price. Uncross: 700
shares print at one price, market orders and the most aggressive limits
fill first, and 100 lots of buy interest remain unfilled. During the call
phase the venue disseminates exactly this triple -- indicative price,
matchable volume, imbalance -- and imbalance-chasing strategies trade the
continuous market against it. The close is the auction that matters most:
every benchmark-tracking fund trades it, which is why a dedicated model of
closing-auction volume exists here.

*In this library:* `microstructure/Auction.java` (the three-rule hierarchy
verbatim in the javadoc; `indicative()` and `uncross()` return
price/volume/imbalance), `microstructure/ClosingAuctionModel.java` (why the
close is special).

### 252. "Price bands, collars, LULD -- three layers of 'this price is not allowed.' Distinguish them."

Layer one, the participant's own fat-finger collar: reject any order priced
more than x% from a reference price BEFORE it leaves the building. This is
a pre-trade risk check -- it catches the 105.00 order meant to be 10.50,
and it must be cheap because it runs on every order. Layer two, the
venue's price band: orders outside the band the venue itself maintains are
rejected at the gate (in a band-limited matching core, that is literally
`REJECT_OUT_OF_BAND` -- the ladder does not represent those prices). Layer
three, the regulator's market-wide machinery: US LULD computes per-symbol
bands around a rolling reference; quoting AT a band edge enters a limit
state, and a limit state persisting 15 seconds becomes a 5-minute trading
pause; separately, market-wide circuit breakers halt everything on S&P 500
declines of 7/13/20%. The layering logic: your collar protects you from
yourself at nanosecond cost; the venue's band protects the book's data
structure and its members from each other; LULD protects the market from
cascades no single participant can see forming. A desk needs all three --
they fail independently.

*In this library:* `trading/HftRiskGate.java` (`priceCollarPct` --
"fat-finger guard versus the reference price", `REJECT_PRICE_COLLAR`),
`orderbook/HftOrderBook.java` (`REJECT_OUT_OF_BAND` -- the band as a ladder
fact), `microstructure/CircuitBreakers.java` (LULD state machine + the
7/13/20 market-wide rule, styled after the SEC plan).

### 253. "Your consolidated feed shows the NBB above the NBO. Is that an arbitrage? What do you actually do?"

Almost never an arbitrage -- it is a *tape condition*. A single venue's
book cannot cross (matching would have uncrossed it instantly); what
crosses is your CONSOLIDATED view across venues, and the usual causes are
latency skew between feeds (venue A's quote is 200 microseconds staler
than venue B's), a quote in transition mid-update, or an access problem
(the far side is on a venue you cannot reach fast enough or at all). By
the time you route both legs, the ghost is gone -- and if it is not,
Reg NMS locked/crossed rules mean venues themselves will not let new
quotes lock or cross the market, so persistent crosses signal something
broken, not something free. What you actually do: flag it, don't trade
it. A crossed NBBO should gate signal generation (microprice and imbalance
are garbage when bid > ask), widen or pull quotes, and increment a counter
an operator can see -- a rising crossed-rate on one venue's feed is how
you discover ITS feed handler is sick. This library's NBBO tracker exposes
exactly that flag for exactly that purpose.

*In this library:* `marketdata/Nbbo.java` (`crossed()` -- "NBB above NBO --
locked/crossed tape condition"), maintained venue-by-venue with a
differential test against brute-force recomputation.

### 254. "The venue has a book, and your process has a book. Same data structure -- what is different?"

Direction of causality. The venue's book is written by *matching*: orders
arrive, the engine executes the crossing part, rests the remainder, and
the book state is the OUTPUT -- it is authoritative, and its invariants
(never crossed, price-time honored) are guaranteed by its own logic. Your
book is written by *the feed*: you replay the venue's L3 events (add,
execute, cancel, delete, replace) and reconstruct a copy that is always a
wire-latency behind and only as good as your handler -- you do not match,
you mirror. The responsibilities differ accordingly. Venue-side: accept
or reject (return codes), match, emit trades, survive capacity pressure
without unwinding executions. Participant-side: apply events in sequence,
detect gaps, and answer the questions an execution engine asks -- best
bid/ask, depth, and above all "how many shares are queued AHEAD of my
order," which only makes sense in the copy, because the venue knows your
position but does not tell you in real time. Both use the same speed
machinery here (dense tick ladder, bitmaps, pooled nodes) -- the split is
who drives the mutations, and it is why they are two classes, not one
with a flag.

*In this library:* `orderbook/HftOrderBook.java` (venue-side: "driven by
matching"), `marketdata/L3BookBuilder.java` (participant-side: "driven by
the feed instead of by matching -- this is the consumer of a venue's L3
feed, not the venue"; queue-ahead tracking is its headline feature).

## Matching engine internals (Q255-Q268)

### 255. "How does a real matching engine store the book? And defend NOT using a sorted map."

A dense array indexed by price tick, plus a bitmap to find the next
occupied level -- not a TreeMap. Prices on a venue are integers (ticks)
in a bounded band, so "the level at price p" is `levels[p - minTick]`:
one array slot per tick per side, O(1) access, no comparator, no boxing,
no tree rebalancing, and perfect spatial locality since the hot levels
near the touch are adjacent in memory. The one operation arrays make
awkward -- "best price emptied; where is the next occupied level?" -- is
solved by an occupancy bitmap: one bit per level, and finding the next
occupied level is a word scan with `Long.numberOfTrailingZeros`, checking
64 levels per instruction. Against that, a TreeMap gives O(log n) pointer
chasing through scattered heap nodes, allocates on insert, and boxes
Double keys -- fine for research (this library's readable book uses
exactly that, deliberately), fatal at venue speed. The measured gap is
the argument: this design runs ~204 ns median per operation and 10M+
fills/sec across a 20,001-level band. The tradeoff you must volunteer:
the band is fixed at construction -- prices outside it are rejected
(`REJECT_OUT_OF_BAND`), which is also how real engines behave.

*In this library:* `orderbook/HftOrderBook.java` (dense integer-tick ladder
+ per-side occupancy bitmaps; the javadoc is the design document),
`orderbook/OrderBook.java` (the TreeMap version, kept as the readable
specification), numbers in `docs/ULTRA_LOW_LATENCY.md`.

### 256. "Why intrusive linked lists and node pools for the orders, instead of an ArrayDeque per level?"

Three reasons: allocation, locality, and O(1) unlink. Pool: every order
lives in preallocated parallel primitive arrays (`nodeId`, `nodeQty`,
`nodeTick`, `nodePrev`, `nodeNext`, `nodeSide`), where an order IS an
index; placing, filling and cancelling recycle slots through a free list
threaded through `nodeNext` and allocate nothing -- no garbage, ever, in
a matching session. Intrusive: the FIFO links are fields OF the node
(prev/next indices), not entries in a container that wraps the node; so
cancelling an order in the middle of a queue is two index writes --
`nodePrev`/`nodeNext` splice -- with no container search. That last point
is the killer for ArrayDeque: a deque gives you the head cheaply, but
cancels hit ANYWHERE in the queue (and cancels outnumber trades 10:1),
and removing from the middle of an ArrayDeque is O(n) with element
shifting. The id map (next question) hands you the node index in O(1);
intrusive links turn that index into an O(1) unlink. Parallel primitive
arrays rather than a Node class also mean no object headers, no pointer
indirection, and fields of hot orders packed densely in cache. This is
the standard shape of every serious matching engine and kernel scheduler
queue -- intrusive containers are what you use when membership is known
and removal is hot.

*In this library:* `orderbook/HftOrderBook.java` ("pooled intrusive order
nodes" -- the parallel arrays and `freeHead` free list are at the top of
the class), same machinery participant-side in `marketdata/L3BookBuilder.java`.

### 257. "You need order-id to book-node lookup on the hot path. Why not HashMap, and how does DELETE work in an open-addressing table without tombstones?"

HashMap costs you boxing (Long keys), node allocation per insert, and
pointer-chasing through bucket chains -- three allocations-and-a-cache-miss
where the book needs none. The replacement: open addressing over two
primitive arrays, `long[] keys` and `int[] vals`, power-of-two capacity,
linear probing, load factor <= 0.5, key 0 as the empty sentinel (safe
because ids start at 1). Lookup: hash, mask, walk forward until key or
empty slot. The interesting part is delete. Naive open-addressing delete
leaves a tombstone so probe chains stay connected -- but a matching engine
does millions of cancels, and tombstones accumulate until every lookup
scans junk ("tombstone soup"). The fix is backward-shift deletion: on
removing a key, walk the probe run that follows the hole, and for each
entry check whether its HOME slot is at-or-before the hole in circular
probe order; if so, move it back into the hole and continue with the new
hole. Every entry stays reachable from its home, no tombstones exist, and
probe lengths cannot degrade over a session no matter the churn. The
circular-order comparison is the subtlest code in this library, which is
exactly why it exists once, as a shared static primitive, with a churn
stress test hammering it.

*In this library:* `orderbook/BookPrimitives.java` (`mapPut`/`mapFind`/
`mapRemoveAt` -- the javadoc on `mapRemoveAt` states the probe-order rule),
shared by `orderbook/HftOrderBook.java` and `marketdata/L3BookBuilder.java`;
id-map churn stress in `HftOrderBookTest`.

### 258. "Your book's order pool is full. An order arrives that would partially match and partially rest. What is the correct behavior -- and what invariant falls out?"

The matched portion must stand -- trades, once made, are real and a venue
never unwinds executions -- and only the resting remainder can be refused
for capacity. So the naive worry is a horror sequence: trades print, THEN
the venue says "rejected." But work through the mechanics and a lovely
invariant appears: a capacity reject can never follow executions. Reason:
a taker only has a remainder after FULLY filling every maker it crossed --
and fully filling a maker frees that maker's pool slot. So by the time the
remainder needs a slot, matching has already freed at least one (unless
the order never crossed anything, in which case there were no executions
to conflict with). A pool-full reject therefore only ever happens to a
pure resting order that traded nothing, and a crossing order through a
completely full pool works fine -- the fill frees the maker's slot and
the remainder rests in it. This library's test suite states that
invariant in a comment that begins "the invariant this test originally
got wrong," which is the honest way to document a bug that taught you
something: the test asserts zero trades accompany every `REJECT_POOL_FULL`.

*In this library:* `orderbook/HftOrderBook.java` (`submitLimit` -- the
pool-full branch and its comment: "the matched portion stands... exactly
how a venue sheds load without unwinding executions"),
`src/test/java/com/quantfinlib/orderbook/HftOrderBookTest.java`
(`rejectionCodesAreVenueSemantics` -- the invariant, proven).

### 259. "You claim the matching session allocates nothing. Prove it two independent ways."

Way one, count the bytes: `ThreadMXBean.getThreadAllocatedBytes` gives
per-thread allocation counters straight from the JVM; snapshot before,
run a session of mixed adds/cancels/aggressions (with the TEST itself
pre-sizing its own structures so it does not allocate either -- the id
ring for cancel targeting uses swap-remove on a preallocated array), and
assert the delta is near zero. This library's book-churn test asserts
under 100,000 bytes across a session with thousands of trades --
tolerance for JIT and test harness noise, zero budget for per-operation
allocation, which would show up as megabytes. Way two, remove the
collector: run the full benchmark under Epsilon GC
(`-XX:+UseEpsilonGC -Xms8g -Xmx8g`), the JVM's no-op collector that
never frees anything -- an allocating hot path exhausts the heap and the
process DIES, so completing a full session is proof by survival. The
committed result: 5.6M orders, 5.3M trades, collector never ran. The two
proofs fail differently -- the counter catches slow drips a big heap
would hide; Epsilon catches allocation on paths the counter's thread
does not own -- which is why you want both, and why "proven, like every
hot-path claim in this library" is written into the book's javadoc.

*In this library:* `src/test/java/com/quantfinlib/orderbook/HftOrderBookTest.java`
(the allocation-counter session, including the pre-sized-ring discipline),
`docs/ULTRA_LOW_LATENCY.md` (the Epsilon GC run and flags).

### 260. "Your fast book is 3,000 lines of bit tricks. How do you know it MATCHES correctly?"

By making the readable book the executable specification. This library
keeps two matching engines on purpose: `OrderBook` (TreeMap, per-order
objects, iterators -- optimized for being obviously correct) and
`HftOrderBook` (ladder, bitmaps, pools -- optimized for speed). The
equivalence test drives BOTH with identical randomized operation streams
-- same seeded sequence of limits, markets, IOCs, cancels at the same
prices and sizes -- and then demands identical observable state: same
best bid/ask, same quantity at every price level, same total traded
volume, same trade prints. Any divergence means one book is wrong, and
since the readable one can be verified by eye against the rulebook, the
fast one is the suspect. This is model-based testing, and it is
qualitatively stronger than example-based tests: you are not asserting
"this input gives that output," you are asserting semantic equality over
an unbounded family of inputs, so bugs in rare interleavings (cancel the
queue head mid-sweep; pool exhaustion during a partial fill) get found by
volume rather than by imagination. The discipline that makes it work:
the two books must expose comparable state cheaply, and the random
stream must be seeded so any failure replays deterministically.

*In this library:* `src/test/java/com/quantfinlib/orderbook/HftOrderBookTest.java`
("model-based equivalence: the readable book is the specification" --
identical random streams must produce identical books and identical traded
volume); the relationship is declared in both books' javadocs.

### 261. "Beyond equivalence -- how do you fuzz a single order book, and what invariants do you assert?"

Hammer it with random operations while maintaining an independent
reference model, and check STRUCTURAL truths after every step -- the
things example-based tests never think to ask. This library's invariant
test runs 100k random submits/cancels/markets against a
`LinkedHashMap`-based model of what should be resting (insertion-ordered,
so time priority is checkable), wires a trade listener to decrement the
model on every print, and asserts invariants like: the book is never
crossed (best bid < best ask whenever both exist); every order the model
says is resting is findable in the book with the model's exact remaining
quantity, and nothing else is; level quantities equal the sum of their
orders; fills at a price never violate that price (buy fills at <= limit,
sell at >= limit); maker fills come off the front of the queue; counters
reconcile (orders = trades' takers + rests + rejects). The deep reason
fuzzing earns its keep on books specifically: matching state space is
combinatorial (order types x crossing depth x queue positions x
cancellations in flight), and real bugs live in the interactions. A
seeded `SplittableRandom` makes every failure a replayable script --
which converts "the fuzzer failed once" from a nightmare into a unit test.

*In this library:*
`src/test/java/com/quantfinlib/orderbook/OrderBookInvariantTest.java`
(100k ops, the LinkedHashMap reference model, and the invariant checks
"example-based tests can miss").

### 262. "You measured ~10M fills per second. Do the arithmetic that makes that credible -- or incredible."

Sanity-check it against the nanosecond budget, which is the only honest
way to hear a throughput claim. 10M fills/sec means 100 ns per fill.
What must happen in a fill: bitmap scan to the best level (a couple of
instructions when the touch is cached in `bestBidIdx`/`bestAskIdx`),
read the queue head node (one indexed load into parallel arrays), compare
quantities, decrement, maybe unlink (two index writes), maybe free the
pool slot and fix the id map, emit one callback with five primitive
arguments. On a ~4-5 GHz core that is a few dozen instructions with
near-perfect cache locality -- 100 ns (400-500 cycles) is comfortable,
NOT miraculous, and that is the point: the number is credible precisely
because the design removed everything that costs unpredictable time
(allocation, pointer chasing, tree rebalancing, virtual dispatch through
listener lists). The companion measurements triangulate it: ~204 ns
median per mixed operation (70/20/10 add/cancel/aggress -- adds and
cancels do map work fills don't), and 7M+ add/cancel ops/sec across a
20,001-level band. If someone claims 10M fills/sec from a TreeMap book,
the same arithmetic convicts them: log2(1000 levels) pointer-chased hops
at ~100 ns cache-miss each blows the budget before matching begins.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` (204 ns/op, 10M+ fills/s,
7M+ add-cancel/s, measured by `HftBookBenchmark` and re-stated in
`docs/DIAGRAMS.md`); the design that makes the budget close is
`orderbook/HftOrderBook.java`.

### 263. "You are building the participant side, not the exchange. Which book responsibilities do you keep, which do you drop, and what do you gain?"

Drop matching, keep everything else, and gain the one number only a copy
can give you. The participant-side book never decides who trades -- the
venue did that; you receive the decisions as an L3 event stream (add /
execute / cancel / delete / replace) and apply them. So you drop: order
acceptance policy, TIF semantics, the match loop, trade emission. You
keep: the ladder, the bitmaps, the pooled nodes, the id map -- because
the questions you answer (best bid/ask, depth at level, sweep cost) need
the same data structure at the same speed, driven by a feed that bursts
just as hard as an order flow. And you gain queue position: when your
own order's add appears on the feed, one walk of its level's FIFO counts
the shares ahead of you; from then on it is maintained in O(1) per event
using two facts of price-time priority -- executions always consume the
queue head (so any execution at your level that is not you was ahead of
you), and a cancel is ahead of you iff its insertion sequence number is
older than yours. That number -- shares ahead -- prices your passive
child order's fill probability, and no venue tells you it in real time.
One process often runs both classes: the venue book in simulation and
testing, the feed book in production.

*In this library:* `marketdata/L3BookBuilder.java` (the feed-driven book;
`track()` and the O(1) queue-position maintenance are the javadoc's
centerpiece), `orderbook/HftOrderBook.java` (the venue side it mirrors),
`microstructure/QueuePositionEstimator.java` (the L2 fallback when you
have no L3 feed).

### 264. "Tell me about a real integer-overflow bug in book code. Where do sentinels bite?"

The classic: representing "pure market order" as a limit at
`Integer.MAX_VALUE` (buy) or `Integer.MIN_VALUE` (sell) -- a sentinel
meaning "no price constraint" -- and then converting that limit to a
ladder index with int arithmetic: `idx = priceTick - minTick`. For a buy
at MAX_VALUE with any positive minTick, the subtraction overflows and
wraps NEGATIVE -- and a negative index means "uncrossable" in the buy
convention, so the most aggressive possible order silently becomes the
least aggressive one: the market sweep matches nothing. The wrap turned
the sentinel into its opposite meaning. The fix in this library is
documented at the call site: do the subtraction in LONG, then clamp into
the valid range -- buy limits clamp to [-1, ladder-1] (-1 = uncrossable,
ladder-1 = full sweep), sell limits to [0, ladder] -- so sentinel limits
saturate to "market" instead of wrapping to "dead." Two transferable
lessons: first, any time a sentinel travels through arithmetic, the
arithmetic must be widened or the sentinel checked first; second, clamp
only the AGGRESSIVE end -- an off-band-aggressive limit IS a market
order, while an off-band-passive limit simply cannot cross, and those
need different clamps. The comment in the code exists because the bug
existed.

*In this library:* `orderbook/HftOrderBook.java` (`buyLimitIdx`/
`sellLimitIdx` -- "overflow-safe" in the javadoc, and the `submitIoc`
comment: "the subtraction is done in long so sentinel limits
(Integer.MIN/MAX_VALUE for 'pure market') cannot wrap into the opposite
meaning").

### 265. "Your model says quote 101.2371. The venue tick is 0.005 -- and it depends on the price. What do you send?"

Not 101.2371 -- the venue would reject it. Order prices must lie on the
venue's tick grid, and under MiFID II / ESMA RTS 11 the grid itself is
price-banded: the minimum increment depends on the instrument's price
level and liquidity band (a 9.98 stock and a 101.24 stock tick
differently), with US equities as the degenerate single-band case (one
cent above a dollar). So the quoter must snap -- and the direction of the
snap is not stylistic: round TOWARD passivity, buys down and sells up.
Rounding a buy up could turn it more aggressive than the model intended
(possibly crossing), and some venues reject off-grid prices outright, so
directional rounding is the only exchange-valid choice: the buy at
101.2371 with tick 0.005 goes out at 101.235, a sell at 101.240. The
engineering constraint: this lookup runs per quote update inside the
quoting loop, so it must be a binary search over primitive band arrays --
allocation-free and a few nanoseconds -- not a config-file lookup.
Snapping also belongs at the EDGE: this library's fast book takes integer
ticks and converts once on entry, so the matching core never sees a
floating-point price at all.

*In this library:* `microstructure/TickSizeSchedule.java` (banded ticks,
`builder()` for real venue tables, `esmaStyle(int)` for the 1-2-5
progression, directional rounding helpers -- "order prices must round
toward passivity (buys down, sells up) to stay exchange-valid");
`trading/HftQuoter.java` wires it as the tick-grid snap stage.

### 266. "Why must a matching engine be deterministic -- and what design choices does that force?"

Because three different consumers replay it and must get the same answer:
the regulator ("show me why order X traded before order Y" -- the replay
must reproduce the sequence exactly), your own recovery (a failover
engine rebuilding state from the input log must arrive at bit-identical
books, or positions diverge between primary and standby), and your tests
(a fuzz failure you cannot replay is a fuzz failure you cannot fix).
Determinism means: identical input sequence, identical outputs, every
time. What it forces: single-threaded matching -- one engine thread owns
the book, because any internal concurrency makes outcome depend on thread
scheduling; sequencing at the boundary -- concurrency lives in the ring
buffer that fans orders IN, and the sequence the engine dequeues IS the
official order of events; no hidden clocks -- timestamps ride on the
orders as data rather than being read inside matching (this library's
throttle applies the same rule for the same reason: the caller passes
nanoTime, so tests are deterministic); and no iteration over
hash-ordered collections in any decision path. Note the free lunch:
single-threaded is also the FAST choice here (no locks, no coherence
traffic), so determinism and latency point the same way -- one of the
rare times two requirements agree.

*In this library:* `orderbook/HftOrderBook.java` (javadoc: "single-threaded
by design, like real matching cores -- one engine thread owns the book;
fan orders in via trading.OrderRingBuffer"), seeded-stream reproducibility
throughout `HftOrderBookTest` / `OrderBookInvariantTest`.

### 267. "Production printed a trade your model says should not exist. Walk me through the debugging session."

Replay, bisect, diff -- never stare. Step one: capture the exact input
sequence up to the discrepancy (this is why deterministic engines log
inputs, not state -- the input log plus the code IS the state). Step two:
replay the sequence into two implementations side by side -- in this
library, the fast book and the readable reference book, which is exactly
what the equivalence harness already does with seeded random streams; a
production discrepancy is just an equivalence failure with a non-random
script. Step three: bisect the stream -- binary-search the shortest
prefix that produces divergence, because a 40-message repro is debuggable
and a 5M-message one is not (shrinking is the fuzzing world's word for
it). Step four: diff STATE, not prints -- compare best bid/ask, per-level
quantities and the id map after the divergent message; the first field
that differs names the broken subsystem (level qty wrong = fill
arithmetic; order findable in one book only = id-map delete; wrong maker
= queue order). The historical bugs this catches are always interaction
bugs: the sentinel-wrap market order (Q264), the pool-full-after-partial
path (Q258) -- each one now lives on as a comment plus a regression test
at the exact site, which is the correct funeral for a bug.

*In this library:* the equivalence harness in
`src/test/java/com/quantfinlib/orderbook/HftOrderBookTest.java` is the
replay-diff machine; `orderbook/OrderBook.java` is the oracle;
seeded `SplittableRandom` streams make every failure a script.

### 268. "The docs call the fast book 'venue-grade.' What specific, testable behaviors earn that word?"

Venue-grade is a checklist, not a compliment -- each item is a behavior a
real exchange core exhibits and a test here asserts. (1) Rejections are
return codes, never exceptions: a venue must not unwind its matching loop
over a bad order, so invalid quantity, out-of-band price, pool
exhaustion and would-cross each map to a distinct negative code the
caller can switch on. (2) Executions are never unwound: capacity
pressure sheds RESTING load only (and Q258's invariant makes even that
conflict impossible). (3) Id and counter discipline is uniform across
entry points: a pool-full reject still consumed an id and counts as an
order, so id sequences and order counts reconcile identically regardless
of which submit method the order came through -- auditability is a
venue property. (4) Cancel of an unknown or already-gone order returns
false, never throws -- races between fills and cancels are normal
traffic, not errors. (5) TIF semantics are exact: FOK all-or-nothing,
IOC never rests, post-only never takes. (6) The performance envelope is
proven, not asserted: zero allocation by counter and by Epsilon, floors
in CI. The test class name for this is literal: rejection codes' "venue
semantics."

*In this library:* `orderbook/HftOrderBook.java` (every numbered behavior
has a javadoc sentence -- see the `submitPostOnly` comment on id/count
discipline and `cancel`'s "never throws"),
`HftOrderBookTest.rejectionCodesAreVenueSemantics`, TIF exactness in
`HftOrderBookTifTest`.

## The trading lane (Q269-Q280)

### 269. "Design the pre-trade risk gate. What do you check, in what order -- and when is it correct to let a limit BREACH pass?"

Order the checks cheapest-and-most-global first: (1) kill switch -- one
boolean, firm-level, flipped by the risk aggregator; (2) per-symbol halt;
(3) quantity cap (fat-finger on size); (4) notional cap (quantity x
price -- catches the size that is fine at 1.08 and insane at 108); (5)
position projection; (6) price collar versus reference (fat-finger on
price). Every rejection increments a per-reason counter, because a gate
that silently rejects is undebuggable. The subtle part is (5). Naive
version: reject if |position + signed qty| > cap. But suppose two 800-lot
fills against a 1000 cap leave you at 1600 (fills can legally overshoot
-- each order passed when checked): the naive gate now rejects a 500-lot
SELL because |1100| > 1000 -- refusing the exact trade that de-risks the
book. So risk-REDUCING orders must pass. And then the trap inside the
exception: "reducing" must mean BOTH smaller absolute position AND same
sign -- an order taking +1600 through zero to -1400 shrinks nothing; it
is a brand-new over-cap short wearing a hedge's clothes. The gate here
encodes exactly that two-clause test, with the comment telling the story.

*In this library:* `trading/HftRiskGate.java` (`check` -- the six checks
in this order, the reducing exception with the flip-through-zero guard
`(newPosition ^ current) < 0`, and per-reason rejection counters with
`reasonName`).

### 270. "Your risk gate runs in about 3 nanoseconds. Why does that number matter, and what did it cost to get it?"

It matters because the gate is on EVERY order's critical path -- it is
the one component you can never bypass, so its cost is a floor under
your entire reaction time. At ~3 ns the gate is economically invisible
(the tick-to-order path is ~504 ns p50; the gate is under 1% of it),
which kills the most dangerous argument on any fast desk: "we'll skip
the risk check on the fast path and reconcile after." When the check
costs less than a cache miss, there is no performance case for trading
unchecked -- Knight Capital is the canonical story of what unchecked
fast paths cost. What it took: all state in primitive arrays indexed by
dense symbol id (no hashing, no boxing, no string formatting on the hot
path -- reason names are looked up only when someone asks); an int
return code instead of exceptions; and the honest part -- when
cross-thread correctness demanded VarHandle acquire/release element
access (next question), the gate got slower than its naive version, and
the library took the 3 ns and documented why: correct-but-3ns beats
fast-but-wrong, verified by re-running the benchmark after the change.
That sentence -- keep the slowdown, write down the reason -- is the
whole engineering culture in miniature.

*In this library:* `trading/HftRiskGate.java` (the javadoc tells the
correct-but-3ns story), `docs/LEARN.md` sections 15 and its Q&A (~3 ns
per check; the tick-to-order budget it sits inside).

### 271. "The quoter reads positions the ack thread writes. No locks anywhere. Why is a plain long[] wrong, and why is a synchronized one also wrong?"

A plain `long[]` is wrong because the Java memory model makes no
visibility promise without synchronization: the JIT may hoist the read
of `positions[sym]` out of the quoting loop and serve the quoter a
STALE position forever -- your inventory skew quotes as if you were
flat while the book is long 1600, and the position limit never fires.
Torn reads of longs are also legal on paper. Synchronized (or one big
lock) is wrong the other way: it is correct but puts a lock acquisition
on a path budgeted in nanoseconds, and creates contention between the
quoting thread and the ack thread exactly when traffic is heaviest. The
right tool is per-element acquire/release via
`MethodHandles.arrayElementVarHandle`: writers publish with a release
store, readers load with acquire, and the JMM guarantees fresh, untorn
values with a happens-before edge. The punchline that makes it free-ish:
on x86, an acquire load compiles to a plain load and a release store to
a plain store -- the cost is only a compiler-reordering fence, so the
~3 ns check survives. The arrays stay primitive and cache-friendly; no
AtomicLongArray object per symbol, no boxing. This is the pattern for
ALL cross-thread state on the lane: single conceptual writer per slot,
release on write, acquire on read.

*In this library:* `trading/HftRiskGate.java` (the Threading javadoc
section is a tutorial on exactly this -- "a plain long[] would let the
JIT serve a stale position to the quoter's skew and the position limit
forever"); the same idiom in `trading/GlobalRiskAggregator.java` reads.

### 272. "Fills arrive on the venue-ack thread while the gate is checking on the trading thread. Why is onFill an atomic add rather than a release store?"

Because fills can have MULTIPLE concurrent sources, and add is the
operation that cannot lose updates. A release store publishes a value;
if two threads each read position 100, add their fill locally (+10 and
+20), and store back, one store overwrites the other and the book is
wrong by a fill -- the classic lost update. `getAndAdd` on the array
element (VarHandle again) makes read-modify-write a single atomic
operation: two simultaneous fills from two venue sessions both land,
order irrelevant, final position exact. The design conversation behind
this: positions are the one piece of state where several writers are
architecturally plausible -- multiple venue connections, a manual
adjustment from ops, the paper-trading loop -- so the write side pays
for full atomicity (a lock-prefixed instruction, a few ns on the ACK
thread, which is not the quoting hot path). Reads stay cheap acquire
loads. Contrast with the rejection counters in the same class: those
have a SINGLE writer (the checking thread), so they use plain-read plus
release-store -- cheaper, and safe only because the writer is unique.
Choosing the memory-ordering strength per field, by writer cardinality,
instead of sprinkling `synchronized`, is exactly the skill this class
demonstrates.

*In this library:* `trading/HftRiskGate.java` (`onFill` -- "atomic add:
concurrent fill sources cannot lose updates"; compare `bumpRejection`,
the single-writer release counter).

### 273. "One strategy on one shard goes insane. How does the FIRM stop trading -- and why is there a hysteresis band on the way back?"

Layered response, cheap where it must be cheap. Per-shard, the gate
already rejects per-order breaches in nanoseconds. Firm-wide, a monitor
thread polls every shard's gate -- positions and reference prices are
already published with release semantics for the gate's own
correctness, so cross-thread aggregation is free-riding on work already
done -- and computes gross notional = sum of |position| x referencePrice
across all gates and symbols. On breach it calls kill() on EVERY gate:
one released boolean per gate, which each shard's check reads as a
single acquire load, first thing. Detection latency is the poll
interval (default 1 ms) -- a deliberate trade, stated in the javadoc:
the firm-wide cap is a circuit breaker, not a per-order gate, so the
hot paths never pay for it. The hysteresis: trading resumes only when
gross drops below cap x resumeFraction (say 0.9), not the cap itself.
Without the band, a book sitting AT the limit flaps -- kill, one fill
rolls off, resume, one tick of mark-to-market, kill again -- and a
flapping kill switch trains operators to ignore it, which is worse than
no switch. Hysteresis converts the boundary into two: trip high, reset
meaningfully lower, resume deliberately.

*In this library:* `trading/GlobalRiskAggregator.java` (poll -> gross ->
kill-all -> hysteretic resume; the javadoc explains each choice),
`trading/HftRiskGate.java` (`kill` / `REJECT_KILLED` as the first check),
wired over `trading/ShardedTradingEngine.gates()`.

### 274. "Venues fine you for exceeding message rates. Design the self-limiter -- and tell me what your design does when the clock goes backward."

A token bucket in nanoseconds: sustained rate r msgs/sec with bursts up
to b. State is two numbers -- current tokens and the last refill
timestamp. On each send attempt: refill tokens by elapsed x r (capped
at b), then spend one if available, else refuse and count. The bucket
starts FULL so a session's opening burst is allowed, and a quiet spell
banks at most one burst of headroom -- that shape (sustained rate plus
bounded burst) is exactly how venues themselves meter, so the limiter
mirrors the contract. Three honesty details separate a real
implementation from a whiteboard one. First, no internal clock reads:
the caller passes nanoTime, so tests are deterministic and the hot path
controls its own syscalls. Second, floating-point drift: refilling in
two steps can sum a hair under one step, so acquisition allows a 1e-9
slack -- granting at most sub-nanosecond-early permits, never extra
ones (the comment quantifies the bound, which is what makes it honest).
Third, the backward clock: `if (dt > 0)` -- a time step backward or
zero neither mints tokens nor destroys them, and does not update the
timestamp, so the limiter cannot be tricked into over-granting by clock
adjustment and imposes no permanent penalty either. Refusal is a count,
not an exception: queue or drop is the CALLER's policy.

*In this library:* `trading/OrderThrottle.java` (all three details are
comments in the code; `nanosUntilAvailable` supports pacing loops that
would rather sleep than spin-fail; `throttledCount` -- "a persistent
nonzero rate means the strategy outruns the venue limit").

### 275. "You trade five venues. One throttle or five -- and what happens to the order that gets throttled?"

Five -- rate limits are a per-venue (often per-session) contract, so the
limiter must be scoped to what the venue meters. One global throttle
set to the sum of the limits is wrong in both directions: it lets a
burst aimed at a single venue exceed THAT venue's limit while the
others idle, and it starves a quiet venue because a busy one consumed
the shared budget. So the gateway owns a map of venue -> throttle
(each a few dozen bytes -- two doubles, two longs, two counters -- so
per-session granularity is free), configured from each venue's
published numbers, and the order path asks the destination's bucket.
The second half of the question is policy, and the throttle explicitly
refuses to decide it ("queue or drop per your policy -- this class only
counts"): a NEW order can usually wait in a pacing loop
(`nanosUntilAvailable` says exactly how long) or be re-routed to
another venue with headroom -- a smart order router treats throttle
headroom as a venue-quality input; a CANCEL should almost never wait --
pulling a stale quote is risk reduction, so desks reserve headroom for
cancels or prioritize them in the queue. And when throttling is
chronic rather than bursty, the fix is upstream: the quoter's
conflation (minimum re-quote interval / minimum move) reduces demand
instead of rationing supply.

*In this library:* `trading/OrderThrottle.java` (per-instance = per-venue
by construction; deterministic caller-supplied clock),
`trading/HftQuoter.java` (conflation as the demand-side fix),
`execution/` venue-quality machinery for the routing answer.

### 276. "Tick to two-sided quote in 592 nanoseconds. Decompose the pipeline and tell me where the time goes."

The loop is: tick in -> mid -> inventory skew -> tick-grid snap -> two
orders out through the risk gate and gateway, all primitive arithmetic,
zero allocation per tick. Decompose the measured 592 ns p50: the tick
arrives over the SPSC ring (~40 ns publish + consumer handoff), the
quote math itself is trivial (skew = -position x skewPerUnit; bid =
mid - halfSpread + skew rounded DOWN to the grid; ask = mid +
halfSpread + skew rounded UP -- note the asymmetric rounding keeps both
sides passive), the tick-schedule snap is a small binary search, each
side passes the ~3 ns risk gate, and each side is published into the
order ring. Two sides means the back half runs twice -- which is why
tick-to-QUOTE (592 ns) costs more than tick-to-ORDER (504 ns). The
non-obvious features are what make it deployable rather than a demo:
conflation -- venues throttle quote updates and stale requotes waste
the wire, so a re-quote is suppressed unless the mid moved minMove or
the minimum interval elapsed, and suppression is COUNTED, never silent;
per-symbol config -- a EURUSD half-spread is ~100x too tight for
USDJPY, so spreads, skews and grids are per-instrument overrides; and
the inventory loop closes through the risk gate's position (updated by
fills via onFill), so the quoter cannot out-trade its own limits.

*In this library:* `trading/HftQuoter.java` (the pipeline, the skew
formula, conflation and per-symbol config are all in the javadoc),
measured numbers in `docs/ULTRA_LOW_LATENCY.md` (tick-to-two-sided-quote
p50 592 ns, p99 912 ns).

### 277. "Your quoter accumulated +1,400 lots against a band of 1,000. The auto-hedger fires. How big is the hedge -- and why NOT hedge to flat?"

400 lots -- back to the band edge, not to zero. The band logic: while
|position| <= band, do nothing (inventory inside the band is the
raw material of market making -- you cannot earn the spread without
warehousing some); on breach, submit an opposite-side order for the
EXCESS over the band. Hedging to the band edge rather than flat is
deliberate, for three reasons the javadoc spells out: it removes the
limit breach with the smallest order (least market impact, least
spread paid -- a hedge is a taker, it COSTS the spread your quotes
earn); it avoids ping-ponging around zero (hedge to flat, get filled
on your bid, hedge again -- you would churn your own P&L away); and it
matches how dealer books actually run inventory. The second control is
the cooldown: positions only update when fills CONFIRM via onFill, so
without a minimum interval between hedges, the hedger would see the
still-breached position on the next tick -- while the first hedge is
in flight -- and stack a second order, then a third; the cooldown
holds fire until the ack loop catches up. Sequencing detail: the
hedger registers on the bus AFTER the strategy/quoter listeners, so it
reacts to the same tick they acted on. For an options book, feed it
delta instead of raw inventory -- the band logic is identical.

*In this library:* `trading/AutoHedger.java` (band, hedge-to-band
rationale, cooldown-vs-in-flight-fills, listener ordering -- all in the
javadoc), positions via `trading/HftRiskGate.onFill`.

### 278. "Derive where a market maker's TRUE mid sits when they are long inventory. What does Avellaneda-Stoikov actually give you?"

Two closed-form answers from one utility-maximization problem. First:
your mid is not the market's mid. The reservation price shades against
inventory -- r = mid - q * gamma * sigma^2 * tau, where q is signed
inventory, gamma risk aversion, sigma^2 the PRICE variance per second,
tau the horizon until you must be flat. Long inventory (q > 0) pushes
BOTH your quotes down: you want to sell, so you make selling attractive
and buying not. The shade grows with each factor for readable reasons
-- a big position (q), in a wild market (sigma^2), that you must carry
for hours (tau), held by a nervous desk (gamma), is worth shading hard.
Second: the optimal total spread, delta = gamma sigma^2 tau +
(2/gamma) ln(1 + gamma/kappa), balances inventory-holding risk against
fill-rate, where kappa is how fast fill intensity decays as you quote
away from the touch -- thicker flow near the mid means quote tighter;
as gamma -> 0 the spread collapses to the pure liquidity floor 2/kappa.
The trap answer-graders listen for: UNITS. The shade q gamma sigma^2
tau is in price units only when q counts the same instrument units
sigma^2 is quoted per -- pass round lots instead of shares and the
shade silently shrinks 100x while the spread term (which never sees q)
is unchanged, gutting the skew that is the model's point. Wiring:
reservation-minus-mid is the skew input, optimalHalfSpread the width --
the model decides, the quoter executes.

*In this library:* `trading/AvellanedaStoikov.java` (both formulas, the
units contract as an explicit javadoc section, FX-horizon guidance for
tau), feeding `trading/HftQuoter.java` (the heuristic skew it replaces).

### 279. "Paper trading: what must be faithful for the exercise to mean anything, and what is honestly unfaithful?"

The rule: run the REAL code against simulated fills, and be precise
about which side of that line each behavior sits on. Faithful here, by
construction: the strategy and risk-gate code paths are the production
classes, not reimplementations -- every order passes the pre-trade
checker first and rejected orders never reach the market, exactly like
production (so a strategy that only works when risk is off gets caught
in paper, which is the whole point); fill mechanics are quote-driven --
market orders fill at the touch, resting limits fill when the market
CROSSES them; and accounting is complete -- signed positions with
average cost, cash, realized/unrealized P&L, commission, mark-to-market
equity, readable as an internally consistent snapshot from another
thread. Honestly unfaithful, and you must say so: no queue position
(a real passive order waits behind others at its price; here crossing
fills it -- optimistic for makers), no market impact (your fills don't
move the simulated market -- optimistic for size), and no adverse
selection in the fill timing (the quotes that fill you are not
reacting to you). Consequence: paper P&L is an UPPER bound for
passive/impact-sensitive strategies, and paper's real product is not
P&L at all -- it is proof the plumbing (signals -> orders -> risk ->
fills -> positions -> dashboard) runs whole days without error.

*In this library:* `trading/PaperTradingGateway.java` (quote-driven
fills, real `risk/PreTradeLimitChecker` in the path, full account
snapshot; thread-safe snapshot for dashboards), the queue-position gap
is what `microstructure/QueuePositionEstimator.java` models when you
need it.

### 280. "You get one screen to watch a live trading session. What is on it, and why those numbers?"

The operator's questions, in order of how fast they kill you: Am I
trading? Am I losing? Am I about to be stopped? Is the system healthy?
So the screen shows: equity and cash (mark-to-market, the heartbeat),
realized vs unrealized P&L split (realized is banked; unrealized is
opinion -- a flat realized with plunging unrealized means you are
holding a loser, not trading badly), positions per symbol (the risk you
are carrying RIGHT NOW, the number the kill switch acts on), rejection
counts BY REASON (the most underrated panel: a rising PRICE_COLLAR
count means your reference prices are stale; rising MAX_POSITION means
the strategy is leaning on its cap; any KILLED count means the
aggregator fired and someone must decide about resuming), and latency
histograms -- percentiles, never averages, because the p99 is where the
losses live. Engineering choices worth defending: it is a pull-model
web page polling a JSON endpoint -- the dashboard must be un-crashable
and must never touch the hot path, so it reads the gateway's
synchronized snapshot and the latency recorders, costing the trading
threads nothing they didn't already pay; zero dependencies (JDK http
server) because the observability tool must not be the fragile
component; and it serves a browser because the consumer is a human at
2 am, not another system.

*In this library:* `trading/TradingDashboard.java` (self-refreshing HTML
+ `/api/status` JSON: account, positions, rejections, attached
`util/LatencyRecorder` histograms), fed by
`trading/PaperTradingGateway.snapshot()`.

## Scaling & resilience (Q281-Q291)

### 281. "One engine core is maxed out. Scale it -- and defend the architecture where shards share NOTHING."

Run N independent engine stacks -- each with its own market-data bus,
its own risk gate, its own order gateway, its own consumer and venue
threads -- and partition SYMBOLS across them, behind a thin routing
facade. Nothing on the hot path is shared: no common queue, no shared
book, no global lock, so aggregate capacity is per-shard throughput x
shard count, and shards cannot contend because there is nothing to
contend ON. Symbols work as the partition key because most strategy
state is per-symbol; the exception -- a synthetic cross needing two
legs -- is handled by registering a symbol on MULTIPLE shards, which
costs one extra ring publish (~40 ns) per tick to duplicate the leg's
feed: paying a copy to preserve isolation is the shared-nothing move
in miniature. The measured result on a 12-logical-core desktop, 300
symbols quoted two-sided: 1 shard = 4.3M ticks/s, 2 shards = 6.2M
(+46%), 4 shards plateau at 6.7M -- and the honest reading of each
number matters: +46% not +100% because the box has finite cores and
one producer; the plateau is core oversubscription (8 spinning threads
plus the producer), NOT contention -- cross-shard drops stayed near
zero. On pinned dedicated cores, scaling approaches k-times. What
sharding deliberately does not solve -- firm-wide risk -- is the next
question.

*In this library:* `trading/ShardedTradingEngine.java` (the facade,
multi-shard symbol registration, per-shard `bus(int)` attachment),
measurements and their interpretation in `docs/ULTRA_LOW_LATENCY.md`.

### 282. "Your first sharding benchmark showed 2 shards SLOWER than 1. The fix was deleting one line. What was it?"

A shared counter. The first version of the scaling probe put ONE
synchronized counter across all shards' venue threads -- just
instrumentation, just counting messages -- and measured sharding as a
slowdown. The mechanism: every increment from every shard fights for
the same lock and, beneath the lock, the same cache line; each write
invalidates that line in every other core's cache, so cores stall in
line waiting for exclusive ownership. That coherence traffic scales
WITH shard count -- more shards, more fighting -- so the measurement
inverted the truth: the instrumentation itself was the shared state
sharding exists to eliminate. Even lock-free sharing (one AtomicLong)
keeps the cache-line ping-pong; even INDEPENDENT counters that happen
to sit adjacent in one array false-share the line. The fix: per-shard,
cache-line-spaced counters, aggregated only when someone asks. Two
lessons worth stating explicitly. Measurement is intrusion: anything
your benchmark adds to the hot path is part of the system under test,
and a profiler-friendly design exposes per-shard counters it can sum
cold. And "shared-nothing means nothing": the discipline fails on the
LAST shared byte, not the first -- the same reason the ring buffers
here pad producer and consumer sequences onto separate cache lines.

*In this library:* the war story is written up in
`docs/ULTRA_LOW_LATENCY.md` (sharding section) and `docs/LEARN.md`
section 18; the padding discipline lives in `marketdata/TickRingBuffer.java`
/ `trading/OrderRingBuffer.java` ("cache-line padding prevents false
sharing").

### 283. "Firm-wide exposure spans all shards, but shards share nothing. Reconcile that without slowing the hot path."

Split the requirement by latency class, because it IS two requirements.
Per-order enforcement (this order would breach) must be nanosecond-fast
and needs only per-shard state -- the shard's own gate does it in ~3 ns.
Firm-wide limits (total gross notional across everything) need
cross-shard VISIBILITY but not per-order LATENCY: a firm cap is a
circuit breaker, and a breach detected within a millisecond is
operationally identical to one detected within a nanosecond. So the
aggregator runs as a slow observer: a monitor thread polls every
shard's gate, summing |position| x referencePrice -- and here is the
elegant part -- reading state the gates ALREADY publish with
release/acquire semantics for their own correctness, so aggregation
adds zero new synchronization to any hot path; the VarHandle work done
for the quoter's benefit is exactly what makes cross-thread reads free.
On breach, one kill boolean per gate is released; each shard's check
reads it as a single acquire load -- a plain load on x86 -- as its very
first test. So the hot path's total price for firm-wide safety is one
already-cached boolean read per order. The javadoc names the trade:
detection latency equals the poll interval (default 1 ms), and that is
how real risk stacks layer it -- fast local gates, slow global
breakers, hysteresis on the way back.

*In this library:* `trading/GlobalRiskAggregator.java` (the whole
answer, argued in the javadoc), `trading/HftRiskGate.java` (kill as
first check), `trading/ShardedTradingEngine.gates()` (the wiring).

### 284. "The process restarts overnight. Walk me through everything that has to come back -- and how you make the save crash-proof."

Two different kinds of state cross a restart, and they use two
different mechanisms because their write patterns differ. Learned model
state -- volume/vol/spread baselines, alpha weights and their
out-of-sample IC evidence, venue scorecards -- is written ONCE at end
of day into a single checkpoint file of named sections
(`model.symbol`), and restored into FRESH instances at session start.
Crash-proofing is the atomic-rename pattern: buffer sections in
memory, write a temp file in the target directory, then rename over
the old checkpoint -- a crash mid-save leaves yesterday's file intact,
never a torn one; if any section writer threw, nothing commits.
Format-proofing: each section carries its own version byte (models
evolve formats independently), the reader skips unknown sections
(forward compatibility), rejects a section the model did not fully
consume (the loudest signal of writer/reader drift), and a
configuration mismatch throws rather than silently misaligning arrays.
Protocol state -- FIX sequence numbers and the resend history -- cannot
wait for end of day: it must survive a mid-session crash, so it gets
the opposite design: synchronous 16-byte writes for the seqnums plus
an append-only message log replayed on open, so reopening the store
resumes the session with sequence continuation and a serviceable
resend history. One state, one write-pattern, one mechanism each.

*In this library:* `persist/Checkpoint.java` (sections, version bytes,
atomic rename -- and the honest caveat about filesystems without atomic
rename), `fix/FileSessionStore.java` (seqnums.dat + messages.dat),
proven end-to-end by `integration/OvernightLearningLoopTest` and
`fix/FixPersistenceTest`.

### 285. "Which state MUST survive a restart, which MUST NOT -- and why is restoring too much as bad as restoring too little?"

Three buckets. Must survive: anything expensive to relearn or
contractually continuous -- model baselines (a desk that relearns its
volume curve from zero trades half-blind until lunch), alpha weights
WITH their evidence (the IC gate's track record is what justifies the
weights), venue scorecards, and FIX sequence numbers (the counterparty
remembers them even if you don't; mismatch means reject-and-resend
pain at reconnect). Must NOT survive: intraday state -- today's
running totals, a pending spread spike, half-formed EWMA excursions --
because it belonged to yesterday; restore it and the models act on a
market that no longer exists. The checkpoint contract makes this
structural rather than disciplinary: each model persists its LEARNED
(cross-day) state only, and intraday state resets on read -- you
restore at session start, not mid-stream. Positions are the trick
case: the honest answer is that your local position variable is a
CACHE -- the venue/prime broker's record is the truth, so restart
recovery reconciles from their drop-copy or start-of-day files rather
than trusting your own last write. Restoring too much fails
differently than too little: too little costs you a slow morning;
too much injects stale beliefs that look like fresh data -- a
yesterday's-spike spread forecast quoting today, a stale position
skewing quotes wrong-way -- which is worse because nothing looks
broken. The restore must be "honest about time."

*In this library:* `persist/Checkpoint.java` (the contract paragraph:
learned state only, intraday resets, config mismatch throws),
`docs/LEARN.md` section 18 ("the restore is honest about time"),
`fix/FileSessionStore.java` (the must-survive protocol case).

### 286. "Your load test asserts zero dropped messages under saturation. Why is that assertion a lie on CI -- and what do you assert instead?"

Because zero-drop under saturation is a property of a PINNED system,
and CI is unpinned. Even a busy-spinning consumer thread gets
preempted by the OS scheduler -- on a desktop for tens of
microseconds, on a shared 2-vCPU CI runner for entire timeslices --
and while it is off-core, producers fill the ring and publishes fail.
That is physics, not a bug: zero-drop under saturation is what Tier-3
core pinning buys in deployment, not something an unpinned box can
promise, and a test asserting it will flake forever (or worse, pass
by never actually saturating). The honest replacement is a drop
BUDGET calibrated to the platform: this library's quoting-under-load
test budgets 0.5% of quote sides on desktop and 8% on CI (2.5-4.3%
was actually measured on shared runners), with the number documented
in the assertion message. The budget still catches real regressions
-- a broken consumer drops 50-100%, orders of magnitude past any
preemption budget -- so it works as a tripwire without encoding a
promise the platform cannot keep. And the accounting must still
balance: processed + dropped = published, exactly -- drops may be
budgeted but they may never be UNCOUNTED. Same philosophy as the
throughput floors: set ~20x below measured, so slow runners never
flake but an accidental lock or O(n) slip -- the mistakes that cost
an order of magnitude -- fails the build.

*In this library:*
`src/test/java/com/quantfinlib/trading/LoadAndSoakTest.java` (the
budget, the CI-vs-desktop split, and the comment citing what Tier-3
pinning buys), `docs/ULTRA_LOW_LATENCY.md` (the pinning tiers),
`docs/LEARN.md` section 19 ("honest limits as tests").

### 287. "The system passes every unit test, then dies at 2 pm on day three. What class of bug is that, and what test catches it before production does?"

A leak -- and more generally, any UNBOUNDED-GROWTH bug: heap growth
from a slow allocation drip (an object per 10,000th event escapes the
zero-alloc discipline), an id map that only ever grows because some
path forgets to remove, a listener list appended on every reconnect,
a latency histogram accumulating unbounded samples. Unit tests never
see these because they run seconds; the bug needs millions of
iterations to matter. The catching test is a soak: sustained mixed
load -- ticks, quotes, fills, cancels, the real mix -- long enough
for steady state, with a heap-stability assertion: measure used heap
after warmup and again after the soak (with forced GCs bracketing
both measurements so you compare live sets, not garbage), and assert
steady-state growth near zero. The key design point is what "steady
state" means: hot paths that are genuinely zero-allocation plus
bounded cold-path structures imply the live set after N operations
equals the live set after 10N -- so ANY trend line is a bug, not a
tuning question. The soak complements rather than repeats the
per-thread allocation counters: counters prove a specific path
allocates nothing per operation; the soak catches the drips and
growth on paths the counters don't own -- background threads,
reconnect handling, accumulating diagnostics. Time-in-test is the
one thing you cannot unit your way out of; you can only compress it
with load.

*In this library:*
`src/test/java/com/quantfinlib/trading/LoadAndSoakTest.java` (the soak
job: "steady-state hot paths must not grow the heap... complementing
the per-thread allocation-counter proofs").

### 288. "Under a burst, your rings are full. Contrast the three possible behaviors and defend the one you shipped."

The three options when a bounded queue is full: block, throw, or
refuse-and-count. Blocking is the worst on a trading path -- the
producer is usually the market-data or venue thread, and stalling IT
turns local overload into global deafness (you stop hearing the
market precisely when it is moving fastest); backpressure is correct
for batch pipelines, deadly for real-time ones. Throwing is
structurally dishonest: a full ring is not an exceptional state, it
is the DESIGNED state under burst -- that is what the bound is for --
and exceptions on the hot path unwind matching loops, allocate, and
convert a capacity event into a control-flow event. The shipped
contract is degrade-and-count: publish returns false, submits return
0, a counter ticks, the caller applies ITS policy (drop the tick --
market data is self-healing since the next tick supersedes it;
retry-spin for an order that must not vanish), and the system
recovers FULLY once the burst passes -- the overload test deliberately
undersizes rings, slams them, and asserts exactly that: never throw,
count everything, full recovery after. The quiet prerequisite is that
degradation must be OBSERVABLE: a return code plus a counter turns
overload into a dashboard number an operator sees rising, whereas a
silent drop is a mystery bug six weeks later. Crash is a fourth
option and it is chosen surprisingly often -- by systems that never
decided, via the OutOfMemoryError their unbounded queue eventually
threw.

*In this library:* `LoadAndSoakTest` (the overload job: "the contract
is degrade-and-count... never throw, and full recovery once the burst
passes"), `marketdata/HftMarketDataBus.publish` returning false,
`orderbook/HftOrderBook` return codes as the same philosophy at the
book.

### 289. "Design failover for the trading engine: hot-hot, hot-warm, or cold -- and what does the standby actually need to take over?"

Pick hot-warm and be able to say why the extremes lose. Hot-hot (two
engines trading simultaneously) is a determinism trap: both must make
bit-identical decisions from identical sequenced inputs or you get
duplicate/conflicting orders -- exchanges themselves do this with
sequencer-replicated deterministic cores, but a participant rarely
can. Cold standby (start from nothing) fails the clock: process
start, JIT warmup (a cold JVM trades tens-of-microseconds slow --
the warmup problem is real), state rebuild, session re-establishment
-- minutes of blindness while positions sit unmanaged. Hot-warm: the
standby runs warmed up, consuming state passively, trading nothing,
and takeover means flipping it active. What it needs, in this
library's terms: learned model state (restore yesterday's checkpoint
-- same file, same restore path as every morning); FIX session
continuity (the durable session store's seqnums and resend log are
readable by the successor, so it resumes the SAME session with
sequence continuation rather than negotiating a new one -- the venue
never needs to know); positions (reconciled from the venue's
drop-copy, per Q285 -- your own copy is a cache); and a fencing rule
so exactly one instance trades -- kill-switch semantics repurposed:
the standby comes up with its gate killed and only un-kills on an
explicit, human-or-consensus takeover signal, because two live
traders is worse than zero. The library ships the state pieces;
orchestration is deliberately a deployment concern.

*In this library:* `persist/Checkpoint.java` +
`fix/FileSessionStore.java` (the two state carriers a standby reads),
`trading/HftRiskGate.kill` (the fencing primitive),
`docs/ULTRA_LOW_LATENCY.md` (warmup and the deployment/library boundary).

### 290. "Prove the learn -> save -> restart -> keep-learning loop actually works. What does the test have to demand?"

The failure modes of persistence are silent, so the test must demand
CONVERGENCE ACROSS restarts, not just save/load symmetry. This
library's integration test simulates five trading days: synthetic
quotes with planted structure (a U-shaped volume curve, a real alpha
signal, one deliberately toxic venue) flow through the full stack --
models, executor, router, scorecards -- and every "night" the learned
state crosses the overnight through a checkpoint into FRESH instances;
state must arrive via the file, because the old objects are gone.
What it asserts is the loop's substance: the volume/vol/spread curves
converge to the planted shapes ACROSS days -- which can only happen
if each day's learning genuinely accumulates on the last; the online
alpha learner's IC gate opens only because the planted signal is real
(a poisoned restore would break the out-of-sample evidence chain that
opens it); the toxic venue is FOUND by the scorecard across sessions;
and nothing is lost or poisoned at the write/restore seam. Contrast
with a naive round-trip test (write, read, compare fields): that
catches serialization bugs but not the semantic ones -- state that
restores bit-perfectly yet stops learning (a step-count that resets
and re-inflates the learning rate, an EWMA whose warmup flag didn't
persist) passes round-trip and fails the loop. Each piece is
unit-tested elsewhere; this is the one test that proves the LOOP.

*In this library:*
`src/test/java/com/quantfinlib/integration/OvernightLearningLoopTest.java`
(`fiveDaysOfLearningSurviveFourOvernights` -- the javadoc is the spec
above), over `persist/Checkpoint.java` and the learning models'
`writeState`/`readState`.

### 291. "The desk wants to quote 1,200 symbols next quarter. Size the system -- from measured numbers, not vendor claims."

Capacity planning is arithmetic over measured per-unit costs plus
honest headroom -- never extrapolation from marketing. Start from
this library's committed measurements: one shard sustains 4.3M
ticks/s quoting 300 symbols two-sided on a 12-core desktop; 2 shards
= 6.2M; 4 plateau at 6.7M because 8 spinning threads oversubscribe
the box -- so the FIRST sizing conclusion is about cores, not
software: each shard needs a dedicated core pair, and the plateau
tells you when a box is full. Demand side: estimate peak tick rate
per symbol at the open (not the daily average -- capacity is bought
for the worst minute, and opens run 10x+ average), say 1,200 symbols
x a few thousand ticks/s peak = order of a few million ticks/s.
Supply side: divide by measured per-shard throughput ON THE TARGET
HARDWARE -- rerun the probe there, since the 4.3M is one desktop's
number -- then apply a utilization ceiling (~50%, because Q286 taught
that saturation behavior is drops, and you want budget for the day
volatility doubles). Cross-check the latency budget separately:
throughput headroom does not buy back the 592 ns quote path, but
queueing delay explodes near saturation, so the utilization ceiling
protects p99 too. Then verify in CI forever: the throughput floors
(set ~20x under measured) catch the regression that silently halves
your capacity plan. The method is the answer: measure per-unit,
model peak demand, divide, halve, keep a tripwire.

*In this library:* `docs/ULTRA_LOW_LATENCY.md` (the shard scaling
numbers and their interpretation), `trading/ShardedTradingEngine.java`
(the unit of scaling), `LoadAndSoakTest` (the floors that keep the
plan true), `docs/DIAGRAMS.md` (the measured-numbers map).

---

## Execution algorithms in depth (Q292-Q307)

### 292. "Explain TWAP. Why is a textbook TWAP the easiest algo on the street to exploit?"

TWAP splits a parent order into evenly spaced, equally sized child slices:
100,000 shares over 100 minutes is 1,000 shares every minute, and the
average fill price approximates the time-weighted average price of the
interval. The strength is also the weakness: the schedule is a metronome.
Identical children arriving at identical intervals are detectable from the
tape in a handful of observations, and once a predator has the clock it can
lean on every remaining child -- fade the quote just before each firing,
reload just after. The standard counter-measure is controlled randomization:
jitter the slice sizes (and, separately, the firing times) so the pattern
dissolves while the schedule stays honest -- the parent must still complete
exactly, so size perturbations have to be redistributed to preserve the
total. A subtle correctness point that trips people up in practice: naive
`parent/N` integer division leaves a remainder, so a correct scheduler
allocates slices that always sum exactly to the parent quantity.

*In this library:* `execution/TwapScheduler.java` (even slices, plus
`scheduleRandomized` for size jitter; quantities always sum to the parent)
and `execution/AntiGamingJitter.java` (the generic size + time overlay).

### 293. "How does VWAP execution work, and what happens when your volume curve is wrong?"

VWAP allocates child slices proportionally to an expected intraday volume
profile -- the classic U-shape: heavy open, quiet lunch, heavy close -- so
your participation tracks the market's own trading and your average price
tracks the session VWAP. The whole algo therefore stands on the curve. A
historical average curve is wrong on exactly the days that matter: today
rarely trades the average day's volume, and a 2x-volume morning means the
historical curve has you badly under-participating. The fix is a live
curve: learn a per-bucket EWMA profile across days, then rescale the
remainder of today's curve by the realized-vs-expected ratio, shrunk toward
1 early in the day when the ratio is mostly noise (`scale = 1 + w*(ratio -
1)` with `w` = fraction of the expected day elapsed). And when the curve
input is simply unavailable (NaN), the failure must degrade, not stall: a
naive `(long)` cast of NaN is 0, which reads as "nothing due" forever --
the honest fallback is the elapsed time fraction, i.e. VWAP degrades to
TWAP until the curve returns.

*In this library:* `execution/VwapScheduler.java` (profile-proportional
slices), `microstructure/VolumeCurve.java` (learned profile + intraday
rescale; "VWAP degrades to TWAP, the honest default"), and
`execution/BenchmarkExecutor.java` (the explicit NaN-curve fallback to the
time fraction).

### 294. "POV at 10% of volume. What's the classic self-referential bug, and what do the size clamps buy you?"

POV (percentage-of-volume) cannot be prescheduled -- it chases realized
market volume, targeting `executed = p x marketVolume`. The classic bug is
measuring participation against the total tape, which includes your own
prints. Run the arithmetic: an algo correctly executing `p` of other
people's volume M has printed `pM` shares, so its measured share of the
total tape is `pM / (M + pM) = p/(1+p) < p`. The controller reads itself as
behind target, speeds up, and every catch-up fill inflates the denominator
again -- the algo chases its own flow, and its true participation against
other people's trading overshoots the mandate. The cure is definitional:
market volume must exclude your own fills, so participation is measured
against other people's trading only. The size clamps solve two separate
problems: a minimum slice suppresses dribble orders (a 3-share child after
every odd-lot print is pure message cost and signaling), and a maximum
slice caps the information leaked by any single child when a volume burst
suddenly makes a large quantity "due."

*In this library:* `execution/PovTracker.java` -- the streaming
participation ledger; the javadoc derives the p/(1+p) trap and documents
the `[minSlice, maxSlice]` clamps. `dueQuantity` is the "how much am I
allowed to send right now" answer.

### 295. "Why does implementation-shortfall execution front-load, and how do you turn a trader's 'do 60% in the first half' into a risk-aversion parameter?"

Arrival-price (IS) execution minimizes cost against the price at decision
time, so every minute the order is unexecuted is exposure to price drift --
timing risk. The optimal trajectory trades that risk against temporary
impact: trade more now to cut drift exposure, but not so fast that impact
dominates. Almgren-Chriss makes it exact: holdings decay as `x_j = X *
sinh(kappa*(T - t_j)) / sinh(kappa*T)`, where the urgency kappa grows with
risk aversion lambda -- and as lambda -> 0 the sinh ratio flattens to a
straight line, i.e. IS degrades to TWAP exactly as the math says. Traders,
however, think in front-load fractions, not risk-aversion units. The bridge
is numerical inversion: "fraction done by the halfway point" is monotone in
lambda, so bisect on lambda until the trajectory achieves the requested
front-load. One engineering detail matters: sinh overflows around
`kappa*T ~ 710`, so the search needs an overflow guard that treats the
saturated regime as "more than enough front-load" and lets bisection
converge back below the boundary. Slices are integer-allocated
largest-remainder style so they sum exactly to the parent.

*In this library:*
`execution/ImplementationShortfallScheduler.java` (the bisection on lambda
and the sinh overflow guard are both in the source, with comments) built on
`microstructure/AlmgrenChriss.java` (the closed-form trajectory).

### 296. "Arrival price vs closing price as a benchmark -- which is harder to game, and why did FX fixings become a scandal?"

Arrival price is fixed the moment the decision is made -- nothing you do
afterwards moves it, so the benchmark itself is ungameable; all you can do
is execute well against it (the cost of that honesty is front-loaded,
impact-heavy execution). A closing or fixing benchmark is the opposite: it
is computed from trading inside a future window, so anyone with flow to do
can move the benchmark itself -- trade ahead of the window and you shift
the very price you are measured against. That is precisely what the
2013-15 FX fixing scandals were about: dealers pre-hedging and skewing
inside the WM/Refinitiv 4pm window, betting against their clients'
benchmark. The clean mechanics: a benchmark computed from observations
inside a window is neutrally replicated by TWAP across that window --
spreading the parent evenly over the calculation window makes your realized
cost track the benchmark by construction. Executing early is pre-hedging
risk against the client; skewing inside the window is a bet, not
replication. If you want the bet, use an arrival-price schedule and own it
explicitly.

*In this library:* `execution/WmrFixingScheduler.java` (TWAP across the
fixing window; the javadoc names the excluded behaviors and why) and
`execution/BenchmarkExecutor.java` (`ARRIVAL_PRICE` front-loaded,
`CLOSING_PRICE` back-loaded toward the close).

### 297. "Design one executor that can work an order to VWAP, TWAP, arrival, IS, open, close, or POV -- what's the architecture?"

Two layers. Layer one is the benchmark curve: each benchmark defines the
fraction of the parent that should be complete by now. Time-driven
benchmarks read the elapsed schedule fraction -- TWAP linear, arrival/IS
front-loaded, close back-loaded, open aggressively front-loaded --
while volume-driven benchmarks read the realized volume curve: VWAP against
the expected cumulative profile, POV against a fixed share of actual
volume (time-agnostic). Layer two shapes the raw "behind schedule"
quantity with live inputs: alpha (trade faster when the price is about to
move against you), spread and volatility (wide spread damps aggression;
volatility damps passive benchmarks but RAISES urgency for arrival/IS,
because there it is timing risk), a liquidity cap (the interval child is
capped at a participation fraction of displayed depth -- never ask for
more than the book can give), and fill feedback (falling behind pulls the
next child up). Input hygiene is part of the design: every field has a
neutral value and NaN maps to it, because `(long)` of NaN is 0 and a
transient bad tick must weaken an input, never silently stall the parent.

*In this library:* `execution/BenchmarkExecutor.java` -- seven benchmarks
in one stateful executor (`TWAP, VWAP, ARRIVAL_PRICE,
IMPLEMENTATION_SHORTFALL, CLOSING_PRICE, OPENING_PRICE, PARTICIPATION`),
re-deciding every interval from `MarketState`; the units contract in the
javadoc names the live producer for each input.

### 298. "What's a liquidity-seeking algo, and how do you stop patience from becoming a missed order?"

A schedule algo asks "am I behind the curve?"; a liquidity seeker asks "is
the market cheap RIGHT NOW?" and trades in bursts when it is. Cheapness is
always relative to context, not absolute: 2 pips is cheap at the London
open and expensive at midnight, so the spread is compared to its
time-of-day FORECAST, the volatility regime must be calm, and estimated
impact must be under a cap. When all three hold, take an aggressive clip
(a fraction of displayed depth); otherwise sit still. The discipline every
seek-style algo needs is the completion floor: patience with no deadline is
a missed parent, so over the final stretch of the horizon a floor ramps in
-- zero until a configured fraction of the horizon, then the remaining
quantity spread linearly over what is left, so that at the horizon the
floor IS the remainder. Failure handling is fail-closed on the opportunity
side only: a NaN spread forecast means the moment cannot be judged cheap
(no burst fires), but the completion floor still guarantees the parent
completes. Unknown inputs should cost you opportunity, never completion.

*In this library:* `execution/LiquiditySeekingAlgo.java` (score-the-moment
gate, `maxDepthFraction x displayedDepth` clips, `forceCompleteFrom`
floor, honest NaN degradation), fed by
`microstructure/SpreadForecaster.java` for the time-of-day baseline.

### 299. "How does an iceberg order work, and what does randomizing the display size buy?"

An iceberg rests a large order while displaying only a small tranche; when
the visible portion fills, the next tranche reloads automatically at the
same price. It is a state machine: total quantity, display size, visible
remainder, and a reload rule. The economics: you keep the parent's
footprint off the book (displayed size is what predators read), at the
cost of queue priority -- each reload joins the back of the queue, so an
iceberg trades queue position for concealment. The detection risk is the
reload pattern itself: a level that refills with the same size after every
fill is a fingerprint, and iceberg-detection logic (your counterparties
run it) keys on exactly that regularity -- which is why display sizes are
randomized, making consecutive tranches different and the reload harder to
distinguish from ordinary new orders arriving. The mirror image is worth
knowing: the sound tape-side signature of someone else's iceberg is a
single execution larger than the size displayed at that moment, since
displayed liquidity cannot fill more than it shows.

*In this library:* `execution/IcebergOrder.java` (the display/reload state
machine with randomized display sizes) and
`microstructure/HiddenLiquidityDetector.java` (the detection side, from
the lit tape).

### 300. "You're executing a pairs trade. What is legging risk, and what does a real spread algo do about it?"

The trade is the SPREAD; the risk is the moment you own one leg without
the other -- an outright directional position you never wanted. The
discipline: work the LEAD leg (the illiquid one -- single name,
off-the-run, basis leg) patiently, because it is the constraint; let the
HEDGE leg (the liquid one -- future, ETF, benchmark) CHASE the lead leg's
fills at the spread ratio, because liquidity is cheap there. Then cap the
legging imbalance `|executedLead*ratio - executedHedge|`: at the cap the
algo stops adding lead risk entirely and the hedge child becomes the full
imbalance -- cross the spread, pay up, get flat. An imbalance cap that
yields is not a cap. Two bounding details separate a correct
implementation from a plausible one. First, the lead child is sized so
that even a FULL fill cannot push the projected imbalance past the cap --
the cap protects against the hedge child not filling, so you size against
the worst case. Second, a livelock guard at construction: if the legging
limit cannot cover even one lead unit's hedge (`limit < ratio`), the algo
could never emit a lead child at all, so the configuration is rejected
up front rather than silently doing nothing forever.

*In this library:* `execution/SpreadExecutionAlgo.java` -- the constructor
guard, the at-cap flatten, and the projected-imbalance lead sizing are all
commented in the source.

### 301. "How do you roll a large futures position, and why is 'roll it all on day one' wrong?"

A futures position must migrate from the expiring front contract to the
back over the roll window, and both extremes lose: rolling everything on
day one pays wide back-month spreads (liquidity hasn't moved there yet),
while waiting for expiry pays the congestion of everyone else's last day.
The right answer follows the liquidity migration instead of fighting it:
track the cumulative fraction of open interest that has moved to the back
by each day of the window, and roll in step -- `target(day) = round(
position * curve[day]); due = target - rolled`. The default migration
curve is the classic roll S-shape -- slow start, concentrated middle,
fully complete before the final day's scramble -- a smoothstep. And each
day's due quantity is not two independent orders: it executes as a
CALENDAR SPREAD (sell front / buy back for a long), which is exactly a
two-legged spread execution with ratio 1 and the calendar spread's own
legging cap, so a day when one leg fills and the other doesn't cannot
leave you doubled up or flat by accident.

*In this library:* `execution/FuturesRollAlgo.java` (migration-curve
targets, smoothstep default, each day's due delegated to
`execution/SpreadExecutionAlgo.java` with ratio 1).

### 302. "A client benchmarks you to the 4pm London fix. Describe the correct execution and the two behaviors you must refuse."

The WM/Refinitiv fix (and its cousins) is computed from observations
inside a calculation window -- 5 minutes for major pairs. Because the
benchmark IS the window, the neutral replication is mechanical: spread the
parent evenly across the window (TWAP-in-window), and your realized cost
tracks the benchmark by construction, with no bet in either direction.
The two refusals: (1) executing AHEAD of the window to "get done early" is
pre-hedging risk against the client's own benchmark -- your early flow
moves the market the fix will then be computed from, which is exactly the
conduct the 2013-15 FX fix scandals and the resulting fines were about;
(2) skewing WITHIN the window (front- or back-loading it) is a directional
bet dressed up as execution -- if the desk wants that bet, it should use
an arrival-price/IS schedule and own the risk explicitly on its own book,
not embed it in a client's fixing order. The design lesson generalizes:
when the benchmark is computable from a window, neutral execution is
defined, and every deviation from it is a position that belongs on
someone's risk report.

*In this library:* `execution/WmrFixingScheduler.java` -- the javadoc
documents both exclusions and the reasons; the window slices delegate to
the TWAP machinery.

### 303. "Your TWAP is getting picked off. How do you randomize without breaking the schedule, and why must it stay deterministic?"

Two independent dimensions of jitter. Size jitter perturbs each child by
up to a configured fraction -- but the differences are redistributed so
the TOTAL is preserved exactly, because anti-gaming never changes what
gets done, only how recognizable it looks; a parent that completes 98% is
not an anti-gaming success. Time jitter moves each firing time by up to a
fraction of its own interval, with two invariants: monotonicity (children
never reorder) and the end time is never exceeded (the horizon is a
promise to the client). The determinism requirement is the
production-grade part: jitter is generated from a seed, so the same seed
reproduces the same perturbed plan. That makes it replayable in backtests
(you can grade the jittered algo, not just the ideal one) and auditable in
production -- compliance can reconstruct exactly why each child fired when
it did, which matters the day a regulator asks. Randomness you cannot
replay is randomness you cannot defend. As an overlay it applies to any
child plan -- TWAP, VWAP, benchmark-executor output, hand-built -- and
adds the time dimension the schedulers themselves do not randomize.

*In this library:* `execution/AntiGamingJitter.java` (size redistribution,
time monotonicity, per-seed determinism), complementing
`TwapScheduler.scheduleRandomized` which jitters sizes at construction.

### 304. "You have three algo variants and no history. How do you pick which one to run, order by order?"

This is a multi-armed bandit, and the classical answer is UCB1 (Auer et
al. 2002): pick the arm maximizing `mean reward + sqrt(2*ln N / n_i)`. The
second term is an optimism bonus that shrinks as an arm accumulates
trials, so exploration decays exactly as fast as evidence accumulates,
with logarithmic regret guaranteed -- always-exploit never learns whether
another variant improved, and uniform rotation wastes flow on known-bad
arms; UCB1 is the principled middle. Two implementation rules carry the
theory: first, every untried arm is selected before any UCB comparison
happens ("every arm earns one look" -- the bonus is undefined at zero
pulls); second, rewards must be mapped into [0, 1] and the range is
enforced with a gate, because the theory's exploration balance is
calibrated to that scale and a mis-scaled reward silently breaks it. Map
fill quality, negated cost in bps, or markout onto [0, 1]. Scope matters:
once hundreds of fills per venue exist, a proper scorecard modeling fill
rate, latency and markout separately beats a single scalar; UCB1 is for
the cold start and for A/B-ing algo variants where a regret guarantee
beats a half-warmed-up model.

*In this library:* `execution/Ucb1Selector.java` (untried-arms-first loop,
the [0,1] reward gate, deterministic ties to the lowest index), with
`execution/VenueScorecard.java` as the thick-data successor.

### 305. "Executing a 200-name transition basket: what do you coordinate at the portfolio level that per-name algos can't see?"

Each symbol keeps its own benchmark executor -- its curve, benchmark and
per-symbol shaping stay intact -- and the portfolio layer adds the two
overlays that only exist at basket level. (1) Leg balance: in a two-sided
transition, the buy leg and sell leg must stay in step or the basket
carries unintended net market exposure mid-flight. When the projected net
filled notional would breach the band, throttle the leg that is AHEAD --
never accelerate the lagging leg, because pushing a child past its own
schedule breaks the benchmark it is measured against. (2) Capacity: a
per-interval notional budget allocated risk-weighted -- by default weight
proportional to `(1 + volatility regime) x due notional`, and with a
streaming covariance model, proportional to marginal contribution to
BASKET variance, so two correlated legs read as one concentrated risk and
a natural hedge earns no urgency. A correctness subtlety: the risk weights
cut the two legs asymmetrically, so the capacity pass can push net
exposure back over the band the first pass enforced -- the band must be
RE-APPLIED after capacity, and because the band only ever reduces dues, it
cannot re-violate the budget, so the sequence terminates by construction.
Both overlays only reduce; a binding cap can leave an honest residual.

*In this library:* `execution/PortfolioExecutor.java` (the re-applied
`applyLegBand` after the capacity cut is commented in `decide`), with
`microstructure/EwmaCovariance.java` pluggable via `useRiskModel`.

### 306. "You're pegged to the mid. The mid ticks. Do you reprice? What's the trade-off?"

Not necessarily. A mid-pegged order tracks `mid + offset` (optionally
capped by a limit price), but every reprice has two real costs: a message
(exchanges meter and sometimes fine message traffic, and your own gateway
capacity is finite) and -- the bigger one -- queue priority: cancelling
and replacing puts you at the back of the new level's queue, so a peg that
chases every tick never accumulates the queue position that makes passive
fills valuable in the first place. The standard answer is a reprice
threshold: track the target continuously, but only actually move the
resting order when the peg has drifted far enough from the current resting
price to justify paying those costs; small oscillations are ignored. The
threshold is the knob that expresses your priorities -- tight for a peg
whose job is price accuracy (e.g. hedging a known exposure), loose for a
peg whose job is earning the spread through queue position. A worked
intuition: on a book that ticks back and forth between two mids all day, a
zero-threshold peg pays two messages per oscillation and holds zero queue
priority forever; a one-tick-threshold peg never moves and sits at the
front.

*In this library:* `execution/MidPegTracker.java` -- offset, optional
limit cap, and the drift threshold that decides when a reprice is
justified ("each reprice costs queue priority and a message").

### 307. "Post or cross? Write the expected-cost inequality, and tell me when MORE fill probability makes posting WORSE."

Relative to mid, for a buy: crossing now costs the half spread `h`.
Posting at the bid either fills (probability `p`): you earn `h`, collect
the rebate `r`, but pay adverse selection `a` -- a passive fill happens
exactly when the market comes through you; or it doesn't fill (1-p): you
cross later after the market drifted `d` against you, paying `h + d`. So
`E[post] = p*(a - h - r) + (1-p)*(h + d)`, and you post iff that beats
`h`. Rearranged: post iff `d < p * coef` where `coef = 2h + r + d - a`.
The interesting regime is the sign flip: normally `coef > 0` and posting
pays ABOVE a threshold fill probability `p* = d/coef` -- more fill
probability, more posting. But when adverse selection is large enough
(`a > 2h + r + d`), the slope flips: each marginal fill now LOSES money
(`a - h - r > h + d`), so posting pays only BELOW the threshold -- a high
fill probability means you will almost surely receive one of these toxic
fills, and you are better off crossing. The inputs are the honest part:
`p` from a fill-probability model, `a` from post-fill markouts, `d` from
your alpha, all in the same per-unit currency.

*In this library:* `execution/OrderPlacementPolicy.java` -- `postRegion`
computes `coef = 2*halfSpread + rebate + adverseDrift - adverseSelection`
and returns the post-region on either side of `p*` depending on the sign;
the flip is commented at the `coef < 0` branch.

## Smart order routing (Q308-Q318)

### 308. "Walk me through everything a production smart order router actually prices, beyond the displayed quote."

The full checklist, item by item. (1) Displayed liquidity and fees/rebates
-- the all-in price, the naive router's whole model. (2) Probability of
fill / venue reliability: a venue's measured fill rate discounts its
quote -- expected cost adds `(1 - fillRate) x missPenalty`, the
spread-ish cost of re-routing a faded child -- and venues below a
reliability floor are vetoed outright. (3) Latency: slower venues pay
`latency x urgency`, because in a moving market microseconds of delay are
adverse selection; measured latency overrides the venue's advertised
number once observed. (4) Adverse selection: a venue whose fills are
followed by reversion (negative post-fill markout) charges that reversion
as a per-share cost. (5) Hidden liquidity: dark pools are probed with
sizes learned from realized probe fills, seeded by a configurable default
while a pool is still unknown. (6) Queue position: for the passive leg of
a child, fill probability delegates to a queue model so placement can
weigh the queue it would join. The classic worked example -- Exchange A:
10,000 shares at 120us, Exchange B: 8,000 at 80us at the same price, dark
pool unknown -- resolves to B's 8,000 first, the remaining 2,000 to A,
and a simultaneous dark probe.

*In this library:* `execution/AdaptiveSor.java` -- the javadoc IS this
checklist, and the worked example above is implemented as the plan;
statistics come from `execution/VenueScorecard.java`.

### 309. "Two venues quote the same price; one charges 30 mils take fee, the other pays a 20-mil rebate. How does the router think, and what changes on the hot path?"

The router thinks in all-in price: displayed price adjusted by the venue's
fee or rebate per share. Fifty mils of fee difference on the same quote is
half a cent per share -- routinely bigger than the price improvement
routers chase -- so ranking on raw displayed price is simply wrong; the
research-lane router splits a marketable order across venues to minimize
the fee-adjusted execution price, respecting each venue's displayed size,
with dark venues priced at midpoint. On the hot path the same idea has to
survive without allocation: venue state lives in parallel primitive
arrays, updated in place from per-venue feeds; a route decision is a
greedy sweep by all-in price with no sorting, no lists, no boxing. The fee
representation is the neat trick: fees are converted to TICKS at the
instrument's tick size once, at configuration time, so the comparison
stays in integer-friendly arithmetic on the tick path -- and negative fee
ticks model rebates for free. With the handful of venues real equities
routing faces, an O(V^2) selection sweep beats any heap and allocates
nothing.

*In this library:* `execution/SmartOrderRouter.java` (research lane,
fee-adjusted split) and `execution/HftSor.java` (zero-allocation sibling;
fees in ticks, negative = rebates; single-writer per routing thread).

### 310. "How do you route to a venue whose liquidity you cannot see? The economics of dark-pool probing."

A dark pool has no pre-trade transparency: resting interest is only
observable through fills, so the only way to learn what a pool holds is to
send something and remember what came back. The mechanics: midpoint-cross
pools match hidden resting orders at the current lit-market midpoint,
honoring minimum-execution-quantity constraints (MEQ is a standard
anti-gaming feature -- it stops pingers from discovering size with
1-share probes). The router's side of the economics: probes are ADDITIVE
and CONTINGENT -- sent alongside the lit legs, not instead of them, and on
dark fills the executor cancels lit remainder; the router plans, the
executor manages overfill. Probe sizing is a learning problem: an EWMA of
the shares each probe actually found hardens "unknown" into an estimate
the only honest way -- by probing and remembering -- with a configurable
default seeding a pool that has never been tried. Why bother at all:
midpoint fills pay no spread, so every dark share is half a spread saved;
the cost side is adverse selection (see the markout question), which is
why a dark venue is only worth using while its measured toxicity undercuts
the lit alternative.

*In this library:* `execution/DarkPoolSimulator.java` (midpoint matching,
MEQ), `execution/AdaptiveSor.java` (additive contingent probes), and
`execution/VenueScorecard.java` (`onDarkProbe` -- the probe-size EWMA).

### 311. "What goes on a venue scorecard, and why does the markout leg force one card per symbol?"

Displayed prices tell you where a venue CLAIMS you'll trade; the scorecard
tells you what actually happens when you send there. Four legs, all
exponentially weighted per event so the card tracks current behavior:
(1) fill rate -- EWMA of {1 fill, 0 miss} per marketable child; quotes
fade, systems reject, and a venue that fills 80% of what you send is worth
less than its displayed price says; (2) response latency -- send-to-ack
time as YOU measure it, which routinely disagrees with the advertised
number; (3) hidden liquidity -- for dark venues, the probe-fill EWMA;
(4) post-fill markout -- what the mid does one horizon after your fill,
signed in your trading direction: positive means the price kept going your
way (clean fill), negative means it reverted (you traded against informed
or stale flow -- the "fade" venues). Two seeding disciplines: each EWMA
seeds from its first observation so a venue is never scored below "never
tried" by its own first data point, and before any data the fill rate
returns an optimistic prior -- a new venue deserves flow until it proves
otherwise. The markout leg is what makes the card single-symbol: mids
mature every pending fill against the one mid stream they are given, and
the markout is in absolute price units, so one card per symbol once you
arm it (fill-rate and latency alone were symbol-agnostic).

*In this library:* `execution/VenueScorecard.java` -- streaming, zero
allocation per event; `execution/VenueBenchmark.java` is the batch
counterpart for post-trade analysis.

### 312. "Two venues show identical quotes. Prove they are not equal."

Price is a promise about now; a fill is a fact with a future. Suppose both
venues show 10,000 at 50.00, and your post-fill markouts say venue X's
fills are followed by a mid that holds or improves, while venue Y's fills
are followed on average by a 0.8 bps reversion against you. On Y you are
being selected against: the counterparties who fill you there
systematically know something -- your buy fills right before the price
drops, meaning you could have bought cheaper a moment later. That
reversion is a real per-share cost, as real as a fee, and the router
should charge it: expected cost on Y = quote + adverse-selection charge,
so X wins at identical displayed prices. The same logic prices the other
scorecard legs -- Y's 80% fill rate adds `0.2 x missPenalty` of expected
re-route cost, and Y's extra 40us of measured latency costs `latency x
urgency` in a moving market. The trap to avoid in
production: adverse selection is a property of a venue's FLOW, not its fee
schedule, so it must be measured from your own fills' markouts -- no feed
publishes it.

*In this library:* `execution/AdaptiveSor.java` (the markout charge and
miss penalty are explicit cost terms) fed by
`execution/VenueScorecard.java` (`postFillMarkout`, signed in trading
direction).

### 313. "Why is latency a routing input rather than just an infrastructure concern?"

Because in a moving market, delay converts directly into adverse
selection: the quote you saw is a snapshot, and every microsecond between
decision and arrival is time for the quote to fade or the price to move
against you. A venue 40us further away doesn't just fill you later -- it
fills you at prices that are 40us staler, and the staleness is asymmetric:
fast counterparties cancel the quotes that were about to be good for you
and leave the ones that weren't. So the router prices it as `latency x
urgency`: when urgency is low (patient parent, calm market) the latency
term vanishes and pure price wins; when urgency is high the nearer venue
wins ties and sometimes beats a slightly better displayed price. Two
disciplines make the input honest. First, use MEASURED latency -- your own
send-to-ack EWMA -- which routinely disagrees with the venue's advertised
figure, and let the measurement override the advertisement once observed.
Second, remember the classic worked example: two venues at the same price,
8,000 at 80us vs 10,000 at 120us -- take the 8,000 from the faster venue
first, then route the residual, because at equal prices latency is the
tiebreaker.

*In this library:* `execution/AdaptiveSor.java` (the `latency x urgency`
cost term; scorecard latency overrides `VenueQuote.latencyNanos`) and
`execution/VenueScorecard.java` (the measured send-to-ack EWMA).

### 314. "You need 10,000 shares and no single venue shows that much. How does the split work?"

Greedy sweep by all-in cost, respecting displayed size. Rank every venue
by fee-adjusted price (plus, in the full router, the reliability, latency
and adverse-selection charges), take as much as the best venue displays,
then the next, until the parent is covered -- each venue contributes at
most its displayed/estimated size, so the split falls out of the ranking
rather than being a separate algorithm. The standard example: Exchange B
shows 8,000 at the best price with lower latency, Exchange A shows 10,000
at the same price -- take B's full 8,000, route the remaining 2,000 to A,
and send a contingent dark probe alongside (dark fills cancel lit
remainder). Two refinements matter in production. First, expected-fill
discounting: a venue's displayed 5,000 at a 70% fill rate is not 5,000 --
ranking must charge the expected miss. Second, the split itself leaks: N
simultaneous child orders across N venues is a recognizable parent-order
signature (see the signaling question), which is why probe legs are
contingent and sizes are learned rather than uniform. On the hot path the
same greedy sweep runs over primitive arrays with no sorting -- O(V^2)
selection beats a heap at real venue counts.

*In this library:* `execution/SmartOrderRouter.java` (the greedy
fee-adjusted split), `execution/AdaptiveSor.java` (the discounted ranking
and the worked A/B/dark plan), `execution/HftSor.java` (the
zero-allocation sweep).

### 315. "Dark-first or lit-first? Argue both sides, then give the honest rule."

Lit-first is the pure-price argument: every venue competes on all-in
price; a dark midpoint is just another price level, and if a lit quote
beats it, take the lit quote -- no special cases. Dark-first is the
information argument: lit child orders are visible and move the market
against your remainder, while dark fills leak nothing pre-trade, so sweep
the dark venues first regardless of ranking and only show the street what
the dark pools couldn't absorb. The honest rule prices the choice instead
of hard-coding it. A dark midpoint fill saves the half spread but carries
that venue's measured adverse-selection charge and an expected-fill
discount (dark liquidity is a probability, not a promise); the lit quote
pays the half spread plus impact, but it FILLS. So: route dark only while
its charge undercuts the lit cost, discount dark legs by fill probability,
and route everything the dark legs are not EXPECTED to fill to lit as
well -- hedges that might fill are not hedges. And before any of it, cross
internally: the firm's own offsetting inventory is the one venue with
zero cost and zero leakage.

*In this library:* `execution/SmartOrderRouter.java` (`preferDark` -- the
sweep-dark-first mode vs pure all-in competition) and `crb/CrbRouter.java`
(internal first, dark second only while the adverse-selection charge
undercuts lit, lit for the expected shortfall).

### 316. "Why does a central risk book internalize before routing anything, and where must internalization stop?"

Every internalized unit of flow saves the street's spread and market
impact TWICE: once on the client's execution (they don't cross the street
spread) and once on the eventual hedge (the book doesn't have to go out
and trade the offset). That makes the CRB the firm's first and best dark
pool -- crossing against the book's own offsetting inventory costs zero
bps and leaks nothing. The decision splits by what the flow does to the
book's risk. Risk-REDUCING flow (opposite sign to the book's net exposure)
is internalized up to the offsetting inventory, and the client is given
back a share of the saved spread as price improvement -- the book was
going to pay to shed that risk anyway, so sharing the saving still wins.
Risk-ADDING flow is warehoused -- internalized without improvement -- but
only while post-trade inventory stays inside the warehouse limit; beyond
that it routes out, full stop, because a warehouse limit that yields to
one more trade is not a limit. The desk-level metric is the
internalization rate, and the router ordering follows: internal cross
first, dark pools second (each priced with its adverse-selection charge),
lit last.

*In this library:* `crb/InternalizationEngine.java` (the
internalize-or-route economics, warehouse limit, `internalizationRate()`)
and `crb/CrbRouter.java` (internal -> dark -> lit, each leg priced
honestly).

### 317. "Your router learns from its own fills. Describe the feedback loop that can permanently blacklist a good venue, and the three defenses."

The loop: the scorecard has a bad afternoon for venue X (a few faded
quotes, one toxic fill), the router responds by sending X less flow, which
means fewer new observations, which means the bad score never updates --
the router has abandoned a venue based on a sample it then refuses to
refresh. Carried across the overnight, a stale blacklist can persist for
weeks. Three defenses, all principled rather than ad hoc. (1) Optimistic
priors and honest seeding: before any data the fill rate returns an
optimistic prior (a new venue deserves flow until it proves otherwise),
and each EWMA seeds from its first observation so a single early miss
cannot score a venue below "never tried." (2) Explicit exploration: when
data is thin, selection should carry an optimism bonus that shrinks with
evidence -- the UCB1 term `sqrt(2 ln N / n_i)` guarantees a written-off
arm is retried at logarithmic cost, so no venue is abandoned forever on
finite data. (3) Keep the veto for what deserves it: a hard reliability
floor should reflect persistent measured toxicity, not one bad EWMA print
-- genuinely toxic venues (persistent negative markout) SHOULD be
abandoned; the defenses exist so the good ones aren't.

*In this library:* `execution/VenueScorecard.java` (optimistic
`fillRate` prior, seed-from-first-observation), `execution/Ucb1Selector.java`
(the exploration bonus and regret guarantee), and
`execution/AdaptiveSor.java` (the reliability floor veto).

### 318. "When does smart order routing make execution worse?"

When the routing itself becomes the signal. A router that sprays child
orders across five venues simultaneously has painted the parent order on
the consolidated tape: anyone merging feeds sees five same-side children
arrive within microseconds at multiple venues -- a signature no single
venue would have revealed -- and can fade quotes or front-run the
remainder everywhere at once. Dark probing has the same failure mode:
uniform-size probes fired at every pool on every parent teach the street
your probing pattern, and a pool operator's other clients can ping you
back. The defenses are the mirror image of good routing: make dark legs
contingent rather than duplicative (probes are sent alongside lit legs
and dark fills cancel lit remainder -- never double-committed); respect
minimum-execution-quantity so size-discovery pings are unprofitable
against you; learn probe sizes per venue instead of broadcasting a
uniform footprint; and jitter the child plan so the multi-venue pattern
doesn't repeat on a clock. And sometimes the honest answer is not to
route at all: for a small child inside the displayed size at one venue,
a single-venue order leaks strictly less than any split.

*In this library:* `execution/AdaptiveSor.java` (contingent probes),
`execution/DarkPoolSimulator.java` (MEQ as the anti-ping feature),
`execution/AntiGamingJitter.java` (killing the repeated pattern).

## Microstructure models (Q319-Q331)

### 319. "State the square-root impact law. Why is it surprising, and what do you use it for?"

The empirical standard for the cost of a large trade:
`impact = Y * sigma_daily * sqrt(Q / ADV)` -- impact in basis points of
the arrival price, proportional to daily volatility and to the SQUARE ROOT
of the order's size as a fraction of average daily volume, with Y a
calibrated constant of order one. The surprise is the concavity: doubling
your size multiplies cost by 1.41, not 2 -- naive linear intuition
overprices big orders and underprices small ones -- and the law holds
remarkably well across equities, futures and FX over several orders of
magnitude of Q/ADV, which is why it is the default pre-trade cost model on
most desks. Worked number: sigma = 2% daily, Q = 1% of ADV, Y = 1 gives
impact = 200bps * sqrt(0.01) = 20bps. For scheduling you also want the
Almgren-Chriss style decomposition -- linear temporary impact in
participation rate, linear permanent impact in size, expected schedule
cost `E[cost] = permanent/2 + temporary` -- because the square-root law
prices the parent while the decomposition prices the TRAJECTORY. Both
need per-market calibration; the shipped coefficients are commonly cited
magnitudes, not truths.

*In this library:* `microstructure/MarketImpactModel.java` -- both
parameterizations, results in bps of arrival, `withCoefficients` for
calibration.

### 320. "How do you LEARN impact from the tape instead of assuming it -- and why do you clamp the estimate?"

Kyle (1985): price change is linear in signed order flow, `dp = lambda*q
+ noise`, and lambda -- price per unit of signed volume -- is the market's
depth read off its own behavior. The streaming estimator is a
through-origin regression on time-decayed moments: `lambda = E[q*dp] /
E[q^2]`, fed one sample per aggregation window (the mid change over the
window and the signed volume that traded in it, with a trade classifier
supplying aggressor signs when the feed doesn't). Through-origin is the
standard form because both signed flow and mid change are zero-mean at
these horizons. The learned lambda then prices a contemplated child order
in bps -- the live producer for the impact input a dynamic executor
expects. The clamp is the production lesson: a noisy sample can produce a
NEGATIVE lambda, which literally means "the market pays you to trade."
That is an estimation artifact, and feeding it downstream would
ACCELERATE the execution schedule on garbage -- so the impact output
clamps negative lambda to zero, while the raw lambda stays available for
diagnostics. Gap discipline completes it: non-finite inputs are skipped
whole, and depth is a slowly-moving property worth persisting across the
overnight.

*In this library:* `microstructure/KylesLambda.java` (`impactBps` clamps,
`lambda` stays raw; checkpoint-persistable), against
`microstructure/MarketImpactModel.java` as the assumed-form prior.

### 321. "You're resting in a queue with only L2 data. Where are you in the queue, and how do cancels ruin the easy answer?"

With L3 (order-by-order) data, queue position is bookkeeping. With L2 you
only see aggregate level sizes, so you estimate. The standard assumptions:
on join you rest at the back, so shares-ahead starts at the level's
current displayed size; executions at your level hit the FRONT (price-time
priority), so they reduce shares-ahead one-for-one. Cancels are the hard
part: a decrease in level size that isn't a trade is a cancel, and it
could have been ahead of you or behind you -- you cannot tell. The
workable assumption from the literature is PRO-RATA: a cancel removes
shares-ahead in proportion to the fraction of the queue that is ahead of
you, which makes the tracked `ahead` an unbiased expected value rather
than a bound. Feed-ordering is a real correctness contract: report each
trade BEFORE the depth update that reflects it, and give level resizes net
of trades already reported -- otherwise the same execution is counted
once as a trade and again as an inferred cancel, and shares-ahead falls
twice per fill. The estimate feeds a closed-form fill model: executed
volume over a horizon ~ exponential with mean `expectedTradedQty`, giving
`P(fill) = exp(-(ahead + qty) / expectedTradedQty)`.

*In this library:* `microstructure/QueuePositionEstimator.java` (pro-rata
cancels, the feed-ordering contract in the javadoc) and
`microstructure/QueueModel.java` (the exponential fill probability).

### 322. "Fill probability for a limit order resting AWAY from the touch -- what two events must both happen?"

Two independent-ish events, and the model composes them. First, the price
must REACH your level: under a driftless diffusion with volatility sigma,
the probability the price travels distance `d` within horizon `T` is the
reflection-principle barrier-touch probability `2*Phi(-d/(sigma*sqrt(T)))`
-- twice the terminal probability, because paths that touch and come back
count too; it is 1 when you are already at or through the level. Second,
the queue at the level must CLEAR to you: the exponential queue model,
`P = exp(-(qtyAhead + qty)/expectedTraded)`. Multiply them. The honest
footnote is the independence approximation: touch and queue-clearing are
POSITIVELY correlated -- the flow that moves the price toward your level
is the same flow eating the queue at it -- so the product is a mild
UNDERestimate; treat it as a conservative placement score, not a
calibrated probability. Units discipline makes it usable live: volatility
enters as return-per-sqrt-second (what a streaming signal engine emits),
converted to price units against the current price. This composed number
is exactly the `p` the post-or-cross policy consumes.

*In this library:* `microstructure/FillProbabilityModel.java`
(`passiveFillProbability` -- the documented under-estimate), composing
`microstructure/QueueModel.java`; consumed by
`execution/OrderPlacementPolicy.java`.

### 323. "How do you detect an iceberg you cannot see?"

By the tell it cannot avoid leaving: a level that trades more than it ever
displayed, and keeps quoting. The sound per-print signature is A SINGLE
EXECUTION LARGER THAN THE SIZE DISPLAYED AT THAT MOMENT -- displayed
liquidity cannot fill more than it shows, so the excess in that one print
necessarily executed against hidden size at the level. Be careful with the
plausible-but-wrong version: a CUMULATIVE executed-vs-displayed comparison
is not sound at L2, because a busy level legitimately trades many times
its instantaneous display through ordinary order arrivals and
replenishment -- that formulation false-flags normal flow all day. Per
level, keep an EWMA of the print-to-displayed ratio at those hidden
events: a multiplier near 1 means "what you see is what's there"; 3 means
roughly three times the tip is likely lurking, and an execution algo can
size its child against the true depth rather than the tip. The
cross-check: this infers LIT-venue hidden size without sending an order,
while dark-venue liquidity is learned the other way -- by probing and
remembering. The tell is identical on an equity exchange level and an FX
ECN level; icebergs are standard on both.

*In this library:* `microstructure/HiddenLiquidityDetector.java`
(`hiddenMultiplier`; the javadoc explains why the cumulative version is
rejected), complementing `execution/VenueScorecard.onDarkProbe`.

### 324. "Forecast the spread I'll pay in a few seconds. What two components, and what does the model do before it has learned anything?"

Two components a live feed gives you for free. (1) A time-of-day baseline:
spreads are wide at the open, tight midday, wide into the close -- and in
FX, wide across the rollover and thin-liquidity hours -- so each session
accumulates a per-bucket mean, folded into a baseline with a day-over-day
EWMA at session roll. The seeding rule matters: the FIRST session seeds
the baseline directly rather than blending against an empty prior --
blending observation one against zero would fabricate a tight baseline no
market ever showed. (2) A fast mean-reverting deviation: the current
spread relative to its time-of-day baseline, blended per observation and
decayed toward zero with a configured half-life -- spreads spike on
events and revert, and the blend of live deviation with baseline
forecasts the near term better than either alone. The payoff is
anticipation rather than reaction: an executor consuming the FORECAST
damps aggression BEFORE a known-wide window like the close, instead of
paying the first wide spread and adapting after. Before the first session
roll there is no baseline at all, so the forecast degrades honestly to
the last observed spread -- a known-imperfect answer beats a fabricated
one.

*In this library:* `microstructure/SpreadForecaster.java` (`rollDay`
day-EWMA with first-session direct seeding, `DEVIATION_ALPHA` blend);
consumed by `execution/LiquiditySeekingAlgo.java` and as the
`BenchmarkExecutor.MarketState.spread` input.

### 325. "Your feed prints trades without aggressor flags. How do you classify them, and how accurate is it?"

Lee-Ready (1991), the standard two-rule cascade. Quote rule first: a trade
at or above the ask was buyer-initiated (someone lifted the offer); at or
below the bid, seller-initiated; between the quotes, above the mid leans
buy and below leans sell. Tick test as the fallback -- exactly at the mid,
or when there is no quote: an uptick from the previous trade price is a
buy, a downtick a sell, and an unchanged price REPEATS the last
classification (the "zero-tick" rule, which is what keeps runs of
same-price prints from flapping). Accuracy is roughly 85% on modern equity
data and similar on FX ECN prints -- and that number is the literature's,
not a defect of any implementation: with hidden orders, midpoint fills and
odd-lot slicing, perfect classification from trades and quotes alone is
not achievable. The design consequence is the important part: everything
downstream that consumes the inferred signs -- signed-flow imbalances,
VPIN buckets, Kyle-lambda samples -- should be built as exponentially
decayed AVERAGES rather than per-trade truths, so that a 15% error rate
washes out instead of compounding.

*In this library:* `microstructure/TradeClassifier.java` (quote rule,
tick test, zero-tick rule; one instance per symbol), feeding
`microstructure/FlowSignals.java`, `microstructure/Vpin.java` and
`microstructure/KylesLambda.java`.

### 326. "What flow signals does an execution engine read before crossing a spread, and what do you do when a venue drops?"

Three imbalances, all streaming, all primitives. (1) Order-flow imbalance
(OFI), Cont-Kukanov-Stoikov best-level formulation: a bid price or size
increase, or an ask decrease, is buying pressure; the mirror is selling
pressure -- exponentially time-decayed so the signal reads as "recent net
flow" with a configurable memory rather than an all-history sum. (2)
Queue imbalance: `(bidSize - askSize)/(bidSize + askSize)` at the inside
-- the classic next-tick-direction predictor, free from the book you
already have. (3) Trade imbalance: time-decayed signed aggressor volume
over time-decayed total volume, +1 all buying, -1 all selling (aggressor
signs from Lee-Ready when the feed lacks them). The venue-down question is
the production trap: when the last venue on a side drops, a consolidated
feed emits sentinels -- one-sided quotes with zero size, or non-dealable
placeholder prices (NaN, zero, negative, infinite). Booking OFI off a
sentinel manufactures a huge fake flow event exactly when the data is
least trustworthy, so the correct handling is a GAP: treat the sentinel
as no-quote, re-anchor on the next good quote, and book no flow across
the discontinuity.

*In this library:* `microstructure/FlowSignals.java` -- the dealable-price
gate (`!(x > 0)` also catches NaN) and the "don't book flow off a
sentinel" gap handling are commented in `onQuote`.

### 327. "What is VPIN, and why volume time instead of clock time?"

VPIN -- volume-synchronized probability of informed trading (Easley,
Lopez de Prado, O'Hara) -- is the flow-toxicity gauge a market maker
watches to decide when quoting is no longer a business. Construction:
trades fill fixed-VOLUME buckets; each completed bucket scores its
absolute buy/sell imbalance; VPIN is the average over the last `window`
buckets: `VPIN = (1/n) * sum |buyVol - sellVol| / bucketVolume`. Balanced
two-way flow reads near 0; one-sided flow reads toward 1 -- famously
elevated in the hour before the 2010 flash crash, which is the paper's
headline exhibit. Volume time is the point of the design: informed traders
compress CLOCK time (they trade fast, before their information decays)
but they cannot compress VOLUME time -- their urgency shows up as
one-sided volume regardless of how many milliseconds it took -- so
bucketing by volume makes the toxicity measure invariant to activity
level, where a clock-bucketed version confuses "busy" with "toxic." Two
implementation details carry the construction: aggressor sides come from
a trade classifier when the venue doesn't disclose them, and a trade
larger than a bucket's remaining capacity SPLITS across buckets, as the
original construction requires.

*In this library:* `microstructure/Vpin.java` -- O(1) per trade,
deterministic, with the bucket-splitting rule; signs from
`microstructure/TradeClassifier.java`.

### 328. "Order flow arrives in bursts, not a Poisson drizzle. Model it -- and tell me when the model explodes."

The exponential Hawkes process: `lambda(t) = mu + S(t)`, with `S(t) = sum
over past events of alpha * e^(-beta*(t - t_i))` -- a baseline arrival
rate mu, an excitation alpha that each event adds, and a decay beta.
"Activity breeds activity" made quantitative: one trade raises the
probability of the next. The exponential kernel is what makes it
streaming-friendly: S updates in O(1) per event -- decay what is there,
add alpha -- where richer clustering kernels are not. The stability
condition is the question's teeth: the branching ratio `alpha/beta` is
the expected number of children each event spawns, and at >= 1 the
process is explosive -- each event begets more than one on average and
the intensity diverges -- so a correct implementation REJECTS such
parameters at construction rather than sampling garbage. (Empirical
market fits routinely sit uncomfortably close to 1, which is itself a
finding: markets are near-critically self-exciting.) The usable output is
a burst score -- 0 at baseline flow, 1 when the self-excited component
equals the baseline (activity running 2x) -- feeding pre-positioning
(bursts consume queues faster, so queue-model horizons shorten) and
conflation-pressure forecasting. Timestamps must be non-decreasing;
feed-merge jitter must not inject negative decay.

*In this library:* `microstructure/HawkesIntensity.java` -- the
constructor enforces `alpha/beta < 1`; `burstScore` is the regime signal.

### 329. "EURUSD and EURJPY. How do you decide whether one tradeably LEADS the other?"

Fix a sampling clock (say 100ms for FX majors, 1s for equities) and feed
one sample per interval with both instruments' returns over that interval.
Keep a small ring of the candidate leader's recent returns and, for each
lag `k = 0..maxLag`, a time-decayed correlation between the leader's
return k intervals ago and the follower's return now -- O(maxLag) work
per sample, no allocation. Reading the output is where the discipline
lives. The best lag is the `k > 0` with the largest absolute correlation
-- a GENUINE lead, because the leader's return was observable before the
follower's -- but it must be compared against the contemporaneous (lag-0)
correlation: if lag 0 dominates every positive lag, the pair simply
co-moves and neither side is tradeably ahead; you have a hedge
relationship, not a signal. Turn the best lag into a prediction via the
regression beta at that lag (expected follower return given the leader's
move k intervals ago). And apply the streaming-estimate hygiene rule: a
lead that appears and disappears within the decay window is noise; only
a persistent best lag with stable sign is structure. The classic pairs --
index futures lead the cash basket, the liquid large-cap leads its sector
peers -- are exactly this computation.

*In this library:* `microstructure/LeadLagEstimator.java` (`bestLag`,
`correlationAtLag(0)` comparison, `expectedFollowerReturn`).

### 330. "'The market is volatile right now.' Why is that statement meaningless without a clock, and what breaks on expiry days?"

Because intraday volatility is seasonal: U-shaped through an equity day
(wild open, quiet lunch, busy close) and session-humped through an FX day
(London open, NY overlap). Raw volatility at 9:31 is always high -- an
algo that reads it as urgency will sprint every single morning. The fix
is a normalized REGIME: learn a per-bucket time-of-day baseline
(per-session per-bucket means, folded across days with an EWMA), then
score current volatility against the baseline for THIS bucket, mapped to
roughly 0 (calm for this hour) through 1 (extreme). Now "the open is
always wild" reads as neutral, but a genuinely wild lunchtime reads as
the signal it is; before any baseline is learned the regime is 0 -- the
honest neutral default. The expiry-day question is the second layer: not
every day has the same shape. Options-expiry days trade 2-3x normal
volume with a violent close, half days compress the whole U-curve into a
morning, FX fixing days concentrate flow around the fix -- a single
averaged profile is wrong on exactly the days that matter most. So keep
one independently learned curve per day type and select today's profile
at session start, accepting the honest trade-off: rare types (12 expiries
a year) converge slowly.

*In this library:* `microstructure/VolatilityCurve.java` (`regime` -- the
executor's normalized volatility input) and
`microstructure/DayTypeProfiles.java` (one curve per day type, for
`VolumeCurve`, `VolatilityCurve` or `SpreadForecaster` alike).

### 331. "Derive the shape of the Almgren-Chriss frontier, and show me its two limits."

The problem: liquidate X shares over horizon T minimizing `E[cost] +
lambda * Var[cost]`, where cost has two sources -- temporary impact
(trading fast is expensive: you pay a concession proportional to your
trading rate) and price risk (trading slow is risky: unexecuted shares
ride the volatility). Each lambda gives one optimal trajectory; sweeping
lambda traces the efficient frontier in (expected cost, variance) space
-- no schedule beats a frontier point on both coordinates. The discrete
closed form: holdings decay as `x_j = X * sinh(kappa*(T - t_j)) /
sinh(kappa*T)`, with the urgency kappa increasing in lambda (and in
volatility, and decreasing in temporary impact). The two limits are the
sanity check the closed form must pass. As lambda -> 0 (risk-neutral),
kappa -> 0 and sinh(x) ~ x makes the trajectory linear: holdings decline
evenly -- TWAP, the pure impact-minimizer. As lambda grows, kappa grows
and the sinh ratio becomes sharply front-loaded: dump early, because
variance hurts more than impact. Parameters: temporary impact eta (price
concession per unit trade rate), permanent impact gamma (per share),
sigma per sqrt-time -- all sharing one time unit, a units contract that
silently breaks more implementations than the math does.

*In this library:* `microstructure/AlmgrenChriss.java` (the closed-form
trajectory; "lambda -> 0 recovers TWAP" is in the javadoc), sharing
`microstructure/MarketImpactModel.java`'s parameterization;
`execution/ImplementationShortfallScheduler.java` makes it executable.

## TCA & measuring execution (Q332-Q343)

### 332. "Decompose implementation shortfall for me, and explain why your TCA code should refuse a zero arrival price."

Implementation shortfall is the difference between the paper portfolio
(everything fills at the decision-time price) and the real one, and TCA
decomposes it against a ladder of benchmark prices per parent order: cost
vs the ARRIVAL MID (the full shortfall: delay + impact + spread), cost vs
the INTERVAL MARKET VWAP (how you scheduled relative to the market's own
trading -- session drift nets out), and cost vs the PREVAILING MID AT
EACH FILL (the effective spread: what you paid to demand liquidity at
that instant, with everything before the fill excluded). Sign convention
is half the battle: all costs signed so positive = cost to the trader,
for BUYS AND SELLS ALIKE -- a sell filled below the arrival mid is a
positive cost, and a report that flips signs per side will eventually be
read wrong. The gating question is the quiet production lesson: benchmark
prices are denominators and subtrahends, so a zero, negative, NaN or
infinite arrival mid, market VWAP, or per-fill mid must be REJECTED, not
propagated -- a NaN benchmark yields a NaN cost that averages into a NaN
report, and a zero yields infinities; fills and mids must also align
one-to-one. Garbage benchmarks make confident-looking garbage TCA.

*In this library:* `microstructure/TransactionCostAnalyzer.java` -- the
three benchmarks, the positive-equals-cost convention, and the
IllegalArgumentException gates on every benchmark price.

### 333. "Effective spread, realized spread, price impact -- define them and tell me what the split reveals."

For a trade at price p against prevailing mid m, taker direction known:
EFFECTIVE spread = 2|p - m| (in bps of the mid) -- what the liquidity
taker actually paid at the instant of the trade, which beats the quoted
spread as a cost measure because trades happen inside, at, and outside
the quote. REALIZED spread replaces the trade-time mid with the mid some
horizon AFTER the trade: it is what the liquidity PROVIDER actually
earned once the market's subsequent move is accounted for. PRICE IMPACT
is the difference: effective minus realized -- the part of the taker's
payment that reflected genuine information moving the price rather than
compensation to the maker. The split is the diagnostic: a venue where
effective spread is 4bps and realized is 3.8bps hosts mostly
uninformed flow (makers keep what they charge); one where effective is
4bps and realized is NEGATIVE means makers are being run over by informed
flow -- and will widen or leave. For the taker, the same split reads in
reverse: high impact means your own flow (or the flow you trade with) is
informative, so your fills predict prices. Convention throughout:
positive = cost to the liquidity taker.

*In this library:* `regulatory/MarketQualityMetrics.java` --
quoted/effective/realized spread and price impact in bps, with exactly
this sign convention.

### 334. "You compute markouts at 100ms, 1s and 30s after each fill. What does each horizon tell you?"

A markout is the mid's move after your fill, signed in your trading
direction: positive = the price kept going your way (a clean fill),
negative = it reverted (you were adversely selected). The horizon chooses
the question. Very short horizons (tens to hundreds of milliseconds)
isolate MICROSTRUCTURE adverse selection: did the counterparty that
filled you know something on the tick scale -- stale-quote sniping, a
faster reader of the same signal? This is the venue-quality horizon: a
venue whose 100ms markouts run persistently negative is a fade venue, and
a router should charge that as a per-share cost. Middle horizons
(seconds) mix in the market's absorption of your own footprint --
temporary impact decaying -- and grade child-order timing. Long horizons
(30s and beyond) are dominated by whether the PARENT's alpha was real:
persistent adverse long-horizon markouts on your passive fills mean your
resting orders systematically stand in front of informed flow. Practical
constraints follow from the definition: a streaming scorecard fixes ONE
horizon per card and matures each pending fill against the mid stream --
and because the markout is in absolute price units against a single mid,
the card becomes single-symbol the moment you arm it.

*In this library:* `execution/VenueScorecard.java` (`markoutHorizonNanos`,
100ms default; the single-symbol consequence is documented) and
`execution/VenueBenchmark.java` (batch per-venue markout for post-trade
tables).

### 335. "Your buy order shows +35bps slippage vs arrival and -2bps vs interval VWAP. Interpret."

The two benchmarks answer different questions, and the gap between them
is the diagnosis. Arrival slippage (+35bps: you paid 35bps above the mid
at decision time) contains everything: the market's drift over the
execution interval, your own impact, and the spread you paid. VWAP
slippage (-2bps: you did 2bps BETTER than the market's volume-weighted
price over the same interval) nets out the common drift -- every
participant in that interval faced the same rising tape -- and grades
only your scheduling and liquidity capture. Together: the stock ran
+37bps while you worked, your execution rode that move no worse than the
market's own trading, and you even captured 2bps of spread or timing
against it. Verdict: the execution desk did its job; the 35bps belongs to
the DECISION-to-completion delay -- either accept it as the cost of the
alpha (if the signal predicted the run, trading faster was right and an
arrival-price schedule should front-load next time) or fix the process
upstream. The reverse pattern (+5bps arrival, +8bps VWAP) is the damning
one: a flat tape and you still underperformed the market's own prices --
that is bad execution, not bad luck. Signs use the positive-equals-cost
convention for both sides.

*In this library:* `microstructure/TransactionCostAnalyzer.java` (arrival
mid vs interval market VWAP vs per-fill mid, all signed as cost).

### 336. "How do you make execution cost measurable per order in a backtest, rather than one blended number?"

Give every signal a PARENT ORDER object and record the full lineage: the
signal that created it, the arrival price -- the close at signal time,
frozen as the TCA benchmark the moment the decision exists -- and every
child fill with the bar it filled on, plus the reason the parent exists
(entry, or exit via signal / stop-loss / take-profit / end-of-data). Then
work the parent through an execution model instead of filling it
instantly: fills can span multiple bars, the position accumulates
gradually, and unfilled remainder carries forward or gets cancelled when
a new signal supersedes it. Now per-parent TCA is just arithmetic:
volume-weighted fill price vs the recorded arrival price, per parent --
and you can aggregate by reason (are stop-loss exits systematically more
expensive than signal exits? they should be -- they trade WITH the
adverse move), by size, by regime. The blended-number alternative hides
exactly this structure: a strategy whose entries cost 5bps and whose
stops cost 40bps has a risk-management problem, not an execution problem,
and one averaged slippage figure will never say so. Semantics that keep
it honest: stops evaluate intrabar against the volume-weighted average
ENTRY price, and triggered exits are worked through the model too -- a
patient model exits slowly, and that realism is the point.

*In this library:* `backtest/ParentOrder.java` (signal, arrival price,
child fills, reason) and `backtest/ExecutionAwareBacktester.java` (the
engine that works parents through an `ExecutionModel`).

### 337. "You backtest TWAP vs VWAP vs IS over a session and VWAP wins. Name three reasons the number is flattered, and one config bug the harness must prevent."

Grading algos over bars means replaying the session, letting the executor
re-decide each bar exactly as it would live, filling against the bar with
a cost model, and scoring like a TCA desk: shortfall vs arrival, slippage
vs session VWAP -- one parent, N bars, a number per benchmark. Three
honest simplifications flatter the results and must be stated, not
hidden. (1) The VWAP volume curve uses the session's REALIZED cumulative
volume -- an oracle: live, you would forecast the curve and forecast
error costs money, so the backtest VWAP number is an upper bound. (2)
POV sees each bar's volume BEFORE that bar trades -- one bar of
look-ahead versus a strictly reactive live POV, so POV completion is
mildly optimistic. (3) Fills print at the bar close plus cost-model bps
-- no intra-bar microstructure, no queue, no fades. The config bug is
the single-cap lesson: the harness caps each bar's fill at
`participationCap x bar volume`, and the executor ALSO has an internal
displayed-depth participation knob -- if both are active they compound
silently (10% of 10% = 1%). So the harness constructs the executor with
its internal depth fraction at 1, making the harness cap the ONLY cap.
Two knobs that own the same constraint must be collapsed to one.

*In this library:* `backtest/ExecutionAlgoBacktester.java` -- all four
points are documented as "honest simplifications" in the javadoc;
executors from `execution/BenchmarkExecutor.java`.

### 338. "Build me the quarterly venue table: what columns, and from what data?"

Four columns per venue, all computable from your own execution records --
no vendor data required. (1) Fill rate: of the marketable children sent
to the venue, what fraction filled -- the reliability column; a venue
that fades 20% of what you send costs you re-route time and signaling on
every miss. (2) Latency-to-fill: send-to-execution time as you measured
it, which is the number that matters (advertised latencies describe the
venue's matching engine, not your path to it). (3) Effective spread
paid: for each fill, `2|price - mid|` against the prevailing mid at
execution -- what demanding liquidity there actually cost, comparable
across venues quoting the same instrument. (4) Post-trade markout: the
mid's move after your fills, signed in your direction -- the adverse
selection column, the one that distinguishes a cheap venue from a toxic
one (see the two-equal-quotes question). Rank venues on the combination,
not one column: the fastest venue is often the most toxic (that's WHO is
fast), and the tightest-spread venue may fade. Batch tables and streaming
scorecards are complements, not rivals: the table is the quarterly
review and the router's streaming card is the same four legs updated per
event -- if they disagree, your routing changed the flow mix, and that
is itself a finding.

*In this library:* `execution/VenueBenchmark.java` (batch: fill rate,
latency-to-fill, effective spread, markout, ranked) and
`execution/VenueScorecard.java` (the streaming counterpart).

### 339. "Your FX backtest fills every order at the bar open. Name the realism layers you're missing, and the subtle time-travel bug in modeling last look."

Layer them from naive upward. INSTANT execution -- full quantity at bar
close with commission and slippage folded in -- is the baseline every
classic backtester assumes. SOR execution derives a synthetic fragmented
top-of-book from each bar (close +/- half spread, per-venue touch sizes
from liquidity shares) and splits the parent through a real router, so
finite per-bar liquidity makes large parents fill across bars: liquidity
cost and fill DURATION become results instead of assumptions. ICEBERG
execution caps each bar at the visible-tranche state machine plus a
participation cap. LAST-LOOK execution adds the FX-specific truth: the
LP holds your order and REJECTS if the price moves against them beyond a
threshold during the hold -- and rejects cluster exactly on the flow that
was about to be profitable, so unconditional fills are fiction. The
time-travel bug: the parent is created at the signal bar's CLOSE, so
filling at that same bar's OPEN credits a price from before the signal
existed -- the model must HOLD on the signal bar (no fill, no reject
counted) and first attempt at the NEXT bar's open. Two more honesty
rules: position sizing divides cash by `price * (1 +
worstCaseCostFraction)` so a worst-case fill can never overdraw cash, and
the model's asymmetric rejection is deliberately a taker's worst case --
the Code-compliant symmetric maker mechanism is a different class.

*In this library:* `backtest/InstantExecution.java`,
`backtest/SorExecution.java`, `backtest/IcebergExecution.java`,
`backtest/LastLookExecution.java` (signal-bar hold documented), and
`backtest/ExecutionModel.java` (`worstCaseCostFraction` sizing contract).

### 340. "What goes in a best-execution report, and what is each statistic protecting the client from?"

The MiFID II-style (RTS 27/28 spirit) core, per instrument and period.
(1) Slippage versus arrival mid: the headline -- did clients' orders,
on average, execute near the price standing when the order arrived?
Persistent positive slippage is the first-order harm best-ex regimes
exist to surface. (2) The latency-to-fill DISTRIBUTION, not just the
mean: a firm can average 80ms while its slowest decile sits at 2 seconds
-- and slow fills in a moving market are adverse selection delivered to
the client; the tail is where the harm lives. (3) Fraction executed at
or better than arrival: a robustness complement to mean slippage --
means are dominated by outliers, and "61% of fills at-or-better" is
readable by a non-quant compliance officer and hard to game with one
lucky block. (4) Per-venue slippage breakdown: the routing-conflict
detector -- if the firm routes heavily to a venue (an affiliate, a
rebate payer) whose slippage is persistently worse than alternatives,
the report shows the conflict in one table. The theme: each statistic is
a different way the same fills can look fine on average while a subset
of clients is systematically worse off.

*In this library:* `regulatory/BestExecutionAnalyzer.java` -- exactly
these four analytics; fills feed in from the execution records.

### 341. "Compliance flags your order-to-trade ratio. What is it, why do regulators and venues watch it, and when is a high number legitimate?"

Order-to-trade ratio: messages sent (orders, cancels, modifies) divided
by executions received. Regulators watch it because the manipulative
strategies they prosecute -- quote stuffing (flooding the book to slow
competitors' feeds), spoofing and layering (displaying size you intend
to cancel to move the price) -- all have the same signature: enormous
message traffic with negligible execution, so OTR is the cheapest first
filter. Venues watch it for capacity economics: messages cost matching
engine and market-data bandwidth whether or not they trade, so exchanges
impose OTR thresholds with fees or throttles above them. When is a high
number legitimate? Market making: a two-sided quoter repricing with
every underlying tick generates hundreds of modifies per fill in a fast
market -- and pegged orders, per the reprice-threshold logic, message on
every threshold-crossing move. The defense is not a low number, it is an
EXPLAINABLE number: message rates that correlate with volatility and
repricing need, cancels that follow quote moves rather than precede
fills in the opposite direction, and thresholds (like a peg's reprice
band) that demonstrably suppress economically pointless messages. Desks
therefore monitor their own OTR per symbol and per algo BEFORE the venue
letter arrives.

*In this library:* `regulatory/MarketQualityMetrics.java` (the
order-to-trade ratio metric) and `execution/MidPegTracker.java` (the
reprice threshold -- a message-suppression control by design).

### 342. "Your signal predicts +20bps over 10 minutes. Execution takes 8 of those minutes. How does alpha decay change how you execute -- and how do you see it in TCA afterwards?"

Alpha decay means the edge is perishable: each minute unexecuted forfeits
a pro-rata slice of the 20bps, so the execution problem is no longer
impact vs timing-RISK (Almgren-Chriss) but impact vs timing-CERTAINTY --
a known adverse drift, not a variance. Mechanically that enters in three
places. Scheduling: an expected move in your trading direction raises
urgency -- the dynamic executor takes alpha as a first-class input and
trades faster when the price is about to move against you, which for a
decaying long signal it always is. Placement: the post-or-cross
inequality's drift term `d` IS your alpha -- expected adverse move while
a passive order waits -- and a large `d` shrinks the post region toward
"always cross"; patient posting is a luxury of slow signals.
Opportunism: a seek-style algo's patience budget must be shorter than
the signal half-life, or the completion floor fires after the alpha is
gone. In TCA it shows up as a signature: large positive slippage vs
ARRIVAL alongside near-zero slippage vs interval VWAP -- the market
moved as predicted while you worked, and the "cost" is captured alpha
you handed back by trading slowly. That decomposition, per parent, is
how a desk calibrates urgency against measured decay instead of guessed
decay.

*In this library:* `execution/BenchmarkExecutor.java` (the alpha input),
`execution/OrderPlacementPolicy.java` (drift `d`),
`microstructure/TransactionCostAnalyzer.java` (arrival-vs-VWAP split),
`backtest/ParentOrder.java` (per-parent arrival benchmarks).

### 343. "Your CRB internalized 60% of flow this month. The desk head asks: did it MAKE anything? What ledger answers that -- and what must it exclude?"

Netting statistics are not economics: a CRB that nets beautifully but
hedges expensively is a cost center with good graphics. The ledger that
answers the question tracks realized flow economics on both sides of the
book's bargain. Credits: internalized client flow captures the street
half spread minus whatever was given back as price improvement --
`notional * (halfSpread - improvement) / 1e4` per trade -- because that
is the spread the firm kept instead of paying the street twice. Routed
flow captures NOTHING: it went to the street, and pretending otherwise
double-counts. Debits: hedge executions cost their all-in bps, and
router allocations cost their blended expected bps. The close-of-day
question is then one subtraction: did the spread captured by
internalizing pay for the hedging the residual risk required? The
exclusion is the discipline: inventory MARK-TO-MARKET P&L is deliberately
out of scope -- that belongs to the risk report -- because mixing
realized spread economics with unrealized inventory marks is how desks
fool themselves: a warehouse position that drifted favorably can mask a
quarter of value-destroying internalization, and the flattering blend
survives exactly until the mark reverses. Keep the two reports separate
and read them side by side; persist the ledger across the overnight.

*In this library:* `crb/CrbPnlLedger.java` (the accounting model, the
MTM exclusion and the reason, checkpoint persistence), beside
`crb/CentralRiskBook.java`'s risk report and
`crb/InternalizationEngine.java`'s internalization rate.

---

## FX conventions & instruments (Q344-Q359)

### 344. "EURUSD is 1.0850. What exactly does that number mean, and what is a pip worth on 10M?"

Read every pair as base/quote: the price is units of QUOTE currency per one
unit of BASE. EURUSD 1.0850 means one euro costs 1.0850 dollars; buying
EURUSD means buying euros and paying dollars. The pip is the conventional
last full quote digit: 0.0001 for 5-decimal pairs, 0.01 for JPY-quoted
pairs (which trade to 3 decimals) -- the extra decimal ECNs stream is the
half-pip "point". Pip value follows directly: base notional times pip
size, paid in quote currency. On 10M EURUSD one pip is 10,000,000 x 0.0001
= 1,000 USD; on 10M USDJPY one pip is 10,000,000 x 0.01 = 100,000 JPY.
Notice the asymmetry that trips people: the pip value lands in the QUOTE
currency, so equal-pip moves on EURUSD and USDJPY are not equal dollar
amounts until you convert the yen. Desks resolve these conventions once at
startup, not per tick -- pip size, precision and spot lag are static per
pair, and re-parsing them on the hot path is wasted work.

*In this library:* `fx/CurrencyPair.java` -- `pips`, `priceFromPips`,
`round` and the JPY 3-decimal rule; the javadoc states the conventions and
resolves them to primitives at construction so hot-path code holds one
instance per dense symbol id.

### 345. "You trade EURJPY spot on a Thursday. When does it settle, and whose holidays can move that date?"

Spot FX settles T+2, counted in business days that must be good in BOTH
currencies' main centers -- a EURJPY settlement date must be a TARGET
business day and a Tokyo business day. Thursday + 2 business days lands on
Monday (the weekend does not count), and a Tokyo holiday on that Monday
pushes settlement to Tuesday. A handful of pairs settle T+1 -- USDCAD is
the classic, along with USDTRY, USDRUB and USDPHP -- so "spot" is not one
number, it is a per-pair convention. The practical consequence: value-date
bugs are P&L bugs, because forward points accrue per calendar day and a
one-day settlement error misprices every outright built on top. This
library computes the date against the union of both calendars, and the
javadoc flags its one documented simplification: it requires intermediate
days to be jointly good, where the full market rule lets a USD holiday
block only the settlement day itself.

*In this library:* `fx/CurrencyPair.java` -- `spotDate`,
`tradeDateForSpot`, `isJointBusinessDay`, `withCalendars` (attach real
holiday calendars; the default is weekends-only), and the T+1 exception
list in the javadoc.

### 346. "Spot is the last business day of April. Where does the 1M forward date land, and why?"

On the last business day of May -- the end-end rule. Forward tenor dates
roll modified-following: add the calendar month, then roll forward to a
joint business day, unless that roll crosses into the next month, in which
case roll backward. The end-end rule is the extra clause: when spot is the
LAST business day of its month, the forward date is the last business day
of the target month, not "same day number, adjusted". Example: spot April
30, the naive 1M date is May 30 -- but end-end sends it to the last good
day of May. Why the market keeps this rule: it makes month tenors
consistent for the month-end dates corporates and funds actually roll, so
a strip of 1M swaps always spans whole months instead of drifting backward
through the calendar. Get this wrong and your pillar dates disagree with
every counterparty's by a day -- which shows up as a phantom points
discrepancy in curve marking, not as an obvious date error.

*In this library:* `fx/CurrencyPair.java` -- `tenorDate` implements
modified-following plus end-end, delegating all roll logic to
`rates.BusinessCalendar` so the rules live in exactly one place;
`fx/SwapPointsCurve.java` resolves its pillars through it.

### 347. "Why does forward FX trade as points rather than outright prices, and how do you price a broken date?"

Because the two components move on different clocks. The outright is
`spot + points`, and spot ticks continuously while the points -- which
encode the interest-rate differential to the value date -- move slowly.
Quoting points lets the forward desk keep a quote alive without re-quoting
every spot tick, and lets a customer trade the differential separately
from the spot level. Standard tenors (ON, 1W, 1M, 3M, ...) form the
pillars; a broken date between pillars interpolates points LINEARLY IN
ACTUAL DAYS -- the interbank convention -- and beyond the last pillar you
extrapolate the final segment's slope. Covered interest parity connects
points to rates: `F = S * exp((r_quote - r_base) * tau)` in continuous
terms, so the points curve is a rate-differential curve wearing FX
clothing. Inverting that gives the implied carry -- the differential the
market is actually charging, which doubles as the rates input NDF pricing
needs when the restricted currency has no clean offshore deposit market.

*In this library:* `fx/SwapPointsCurve.java` -- the builder takes
tenor/pips quotes, `forwardPoints`/`outright` interpolate linearly in
days (allocation-free binary search over primitive arrays),
`impliedCarry` inverts CIP; pillar dates come pre-rolled from
`CurrencyPair.tenorDate`.

### 348. "Explain an FX swap. Why do funding desks describe it as collateralized lending?"

An FX swap is two offsetting exchanges: buy base currency on the near
date, sell it back on the far date (or the reverse), both legs struck so
that only the POINTS DIFFERENTIAL is economically exchanged. Strip the FX
wrapper and the economics are a loan: you hand over dollars today, receive
euros, and reverse at a pre-agreed rate -- you have lent dollars against
euro collateral, and the points differential IS the interest. That is why
FX swaps carry most of the daily FX forward volume (funding and position
rolls), and why banks in a dollar squeeze pay up through the points rather
than the unsecured market. An at-market swap has zero value at inception
-- both legs at prevailing outrights -- and value appears only as the
points move. One subtlety this library makes explicit: an AGED swap whose
near leg has already settled marks that leg at ZERO -- the cash is
realized, and marking it again would double-count. Rolling a spot position
via tom-next is the one-day special case a prop desk pays every night.

*In this library:* `fx/FxSwap.java` -- `atMarket` (zero inception value by
construction), `markToMarket` against a current `SwapPointsCurve` with the
settled-leg-is-zero rule documented on the leg valuation, `rollCost` for
tom-next, `swapPointsPips` for the traded differential.

### 349. "Walk me through an NDF: the two dates, the settlement formula, and why you divide by the fixing."

A non-deliverable forward cash-settles because the local currency (INR,
KRW, BRL, ...) never moves. Two dates matter and they differ: the FIXING
date, when the official reference rate publishes (the RBI reference rate
for INR, KFTC18 for KRW, PTAX for BRL), and the SETTLEMENT date, when the
USD difference pays. The fixing precedes settlement by a per-currency lag
-- two LOCAL business days for the Asian fixings, one for BRL, PHP and CLP
-- counted on the restricted currency's calendar, because PTAX publishes
on Brazilian business days regardless of USD holidays. Settlement to the
base buyer is `baseNotional * (fixing - contractRate) / fixing`: the
numerator is the quote-currency difference, and the division by the fixing
converts it into the DELIVERABLE currency, since that is the only currency
that can actually pay. Marking is ordinary forward machinery -- the
settlement formula evaluated at the curve's forward to the FIXING date --
with one honest edge case: an NDF already inside its fixing window has no
forward to read (the curve starts at spot), so the mark degrades to the
spot outright rather than throwing mid-lifecycle. Once the official print
exists, use it; a curve cannot know it.

*In this library:* `fx/Ndf.java` -- the `FIXING_LAG` map per currency
(`fixingLagDays`), `settlementAmount`, and `markToMarket` with the
in-window spot-clamp documented in the javadoc.

### 350. "Why do INR and KRW trade as NDFs at all? What would break if you booked a deliverable forward?"

Capital controls. A deliverable forward requires both currencies to move
freely at settlement, and restricted currencies cannot be freely delivered
offshore -- an offshore counterparty has no clean way to receive or pay
INR. The NDF removes the delivery: both parties settle the DIFFERENCE in
USD against the official onshore fixing, so the offshore market can trade
the currency's forward value without ever touching the currency. The
consequences follow from the structure. First, an onshore/offshore basis:
the NDF-implied yield can diverge from onshore rates because arbitrage
across the control boundary is limited -- the basis is a live barometer of
how binding the controls are. Second, fixing concentration: every offshore
position in the currency settles against one official print, so fixing
days carry event risk (and manipulation incentive) that deliverable pairs
spread across the whole day. Third, pricing is unchanged: an NDF desk
quotes points exactly like a deliverable desk, because the machinery is
the same forward curve -- only settlement differs.

*In this library:* `fx/Ndf.java` (the javadoc opens with exactly this
"the local currency never moves" framing and lists the restricted set);
`fx/SwapPointsCurve.java` supplies the points machinery its pricing
reuses; `crb/CentralRiskBook.java` (`bookNdf`) carries full currency delta
until the fixing, tracked per pair via `pendingFixing`.

### 351. "Your order is benchmarked to the 4pm WMR fix. What risk are you actually running, and how do you size it pre-trade?"

Tracking risk between what you can execute and what you owe. A fix order
cannot execute at a single instant: you work it through the calculation
window and receive roughly the window TWAP/VWAP, while your liability
references the fix PRINT computed from observations inside that window.
The difference is your slippage, and it has two drivers you can quantify
before trading: diffusion -- the fix and your average diverge as price
moves within the window, an ex-ante 1-sigma tracking error a pre-trade
check compares against limits -- and impact: your own participation moves
the window, and participation share of window volume is the standard red
flag, because heavy participation around a fix is also exactly what a
regulator screens for. Post-trade, realized slippage-vs-fix in pips is the
TCA number. Concrete use: an NDF book with a large INR fixing tomorrow
computes the ex-ante tracking error and the expected participation
tonight, and shrinks the clip or extends the window if either breaches
policy.

*In this library:* `fx/FixingRisk.java` -- `windowTwap`, `windowVwap`,
`slippageVsFix` (pips), `trackingErrorStd` (the ex-ante 1-sigma),
`participationRate`; all static and allocation-free so a pre-trade gate
can call them, not just post-trade TCA.

### 352. "What does 'banging the close' look like in the data?"

Three signatures together, none sufficient alone. A participant banging a
fix (1) holds a LARGE SHARE of the window's volume, (2) the price runs up
INTO the fix in the direction of that participant's net flow, and (3) the
price REVERTS afterwards -- reversion is the tell, because impact from
uninformed size decays while genuine information does not. The mechanism:
a dealer holding client fix orders knows the net imbalance in advance and
can trade ahead of and into the window to push the print, profiting on the
difference between the print (which the client pays) and the dealer's own
average (which the dealer paid). The WM/R methodology computes the fix
from mid samples inside the window (a median-style calculation), which is
robust to a single outlier but not to sustained one-sided pressure. A
surveillance system therefore reconstructs, per participant: the fix, the
pre-window run-up in bps, the post-window reversion in bps, and the
participation share -- and alerts on the joint pattern, not any single
leg of it.

*In this library:* `regulatory/FixAnalyzer.java` -- computes the fix from
window mids per the WM/R-style median methodology and emits a
`FixImpactReport` with `runUpBps`, `reversionBps` and
`participationShare`: exactly the three-part signature.

### 353. "An FX options screen shows ATM, 25-delta RR and 25-delta BF -- no strikes anywhere. How do you price a specific strike off that?"

Convert the quotes to pillar vols, then solve each pillar's strike from
its own delta. The market quotes the smile in delta space: ATM (the
delta-neutral straddle), risk reversal `RR25 = vol(25dCall) -
vol(25dPut)` (the skew), butterfly `BF25 = (vol(25dCall) + vol(25dPut))/2
- ATM` (the wings). Invert: `vol(25dC) = atm + bf25 + rr25/2`, `vol(25dP)
= atm + bf25 - rr25/2`, same shape at 10-delta. Each pillar now has a
delta and a vol, so its STRIKE solves from the delta equation -- and here
the convention matters: forward delta `N(d1)` is standard for long-dated
and EM pairs, but pairs whose premium is paid in base currency (USDJPY)
quote PREMIUM-ADJUSTED delta `(K/F) * N(d2)`, which is non-monotone in
strike for calls; the market resolves the ambiguity by taking the OTM
(higher-strike) solution. With five absolute (strike, vol) pillars per
expiry you interpolate -- linear in vol against log-moneyness within an
expiry, linear in total variance sigma^2 * tau across expiries -- and any
strike-based pricer can consume the result.

*In this library:* `fx/FxVolSurface.java` -- the builder takes ATM/RR/BF
quotes per expiry, solves pillar strikes (with `premiumAdjusted`
selecting `(K/F)N(d2)` and the OTM root, as the javadoc documents), and
feeds strike-based pricers like `pricing/BlackScholes.java` and
`pricing/VannaVolga.java`.

### 354. "Only EURUSD and USDJPY are liquid right now. How do you make an EURJPY price, and what keeps the triangle honest?"

Compose the legs. Two operations cover every triangulation: MULTIPLY when
the legs share the middle currency on opposite sides (EURUSD x USDJPY =
EURJPY -- the USD cancels) and DIVIDE when they share the quote currency
(EURUSD / GBPUSD = EURGBP). A streaming engine maintains the synthetic
cross live from leg ticks -- each EURUSD or USDJPY update recomputes
EURJPY with zero allocation per tick -- so downstream logic (indicators, a
quoter) chains off the cross exactly as off a native symbol. What keeps
the triangle honest is arbitrage: if the direct EURJPY market deviates
from the leg product by more than the combined spreads, buying the cheap
route and selling the rich one is riskless profit, and arbitrageurs
compress the gap within milliseconds. So triangular consistency is not an
assumption, it is an enforced equilibrium -- and checking it is both a
data-quality test (a stale leg shows up as a phantom arbitrage) and,
rarely, a genuine opportunity detector.

*In this library:* `fx/CrossRateEngine.java` -- `Op.MULTIPLY`/`Op.DIVIDE`
streaming composition on the tick path (delivered via `TickListener`, not
re-published onto the single-producer bus -- the javadoc explains why);
the static one-shot math and the consistency check live in
`pricing/TriangularArbitrage.java`.

### 355. "Direct EURJPY or synthetic through the USD legs -- how do you decide, per clip?"

By cost after crossing EVERY spread involved, recomputed per quote. The
synthetic route pays two half-spreads: a synthetic buy under MULTIPLY pays
the ask on both legs (`askA * askB`), and under DIVIDE pays one ask and
hits one bid (`askA / bidB`); the direct route pays one half-spread on a
book that may be much wider. Direct cross books are thin outside London
hours while the USD legs stay tight around the clock, so the winner flips
intraday: mid-London the direct EURJPY spread often beats the two-leg
cost; in the Asian afternoon the legs usually win. The comparison is a
few multiplications, so it can run per clip. One engineering detail worth
stating: an UNQUOTED book must never masquerade as an attractive route --
a zero bid read from an empty tier would turn `askA / bidB` into infinity
or a nonsense saving, so the savings calculation treats NaN, zero and
negative inputs as "unpriced" and returns NaN rather than a signal.

*In this library:* `fx/SyntheticCross.java` -- `syntheticAsk`,
`syntheticBid`, `buySavings` with the documented NaN/zero/negative guard
(including the divide-by-zero infinity case, and the note that zero is
what an empty `FxTierBook` tier reads as); leg rates stream from
`fx/CrossRateEngine.java`.

### 356. "The market forward disagrees with the deposit-implied forward. Free money?"

Almost never -- check your conventions first, then call it basis. Covered
interest parity with simple deposit rates says `F = S * (1 + r_d*t) / (1 +
r_f*t)`; the deviation of the market forward from that, in bps, is the
CROSS-CURRENCY BASIS. Since 2008 the basis is persistently nonzero
(classically negative for USD-funding pairs): balance-sheet costs,
leverage ratios and dollar funding demand mean the arbitrage that would
close it consumes scarce bank balance sheet, so it stays open at exactly
the width those constraints justify. But before declaring basis, audit
compounding conventions: a rate differential quoted CONTINUOUS pushed
through a SIMPLE-rate forward formula manufactures a spurious ~12bp
"basis" at 1y/5% levels that is pure convention mismatch, not economics.
This library documents that trap explicitly: its implied-differential
method is continuous while its theoretical-forward method uses simple
rates, because each matches how its own input is quoted -- convert before
comparing, or the round trip will hand you an arbitrage that does not
exist.

*In this library:* `pricing/ForwardCurve.java` -- `theoreticalForward`
(simple-rate CIP), `impliedRateDifferential` (continuous), the CIP
arbitrage check in bps, and the convention note in the javadoc spelling
out the ~12bp spurious-basis example at 1y/5%.

### 357. "Covered versus uncovered interest parity -- which one actually holds, and what is the carry trade?"

Covered parity is enforced by arbitrage and holds to within the
cross-currency basis: hedge the currency leg with a forward and the rate
differential is locked -- no bet remains, so deviations are bounded by
transaction and balance-sheet costs. Uncovered parity is a HYPOTHESIS:
that the high-yield currency will depreciate by exactly the differential,
making unhedged positions equal in expectation. Empirically it fails --
the forward premium puzzle: high-yield currencies have historically
depreciated less than the differential implies, sometimes appreciated.
The carry trade is the monetization of that failure: borrow the low
yielder, hold the high yielder unhedged, collect the differential (the
carry the swap points quote you every day), and accept the tail -- carry
profits accrue steadily and evaporate violently in risk-off episodes,
because everyone unwinds the same crowded position at once. The honest
framing: CIP is an accounting identity up to basis; UIP is a risk premium
in disguise, and the swap points tell you exactly what you are being paid
to hold it.

*In this library:* `fx/SwapPointsCurve.java` (`impliedCarry` -- the
locked, covered differential); `pricing/ForwardCurve.java` provides the
CIP check; the uncovered bet is precisely that spot will not eat the
carry the points promise.

### 358. "A client asks for '10 million USDJPY'. Ten million of what -- and why has that ambiguity burned real desks?"

Convention says base currency: 10M USD. But clients -- especially
corporates hedging a JPY payable -- sometimes mean 10M USD worth of yen,
or 10 million yen outright, and at 150 the difference between 10M USD and
10M JPY is a factor of 150 in economic size. Book the wrong one and
everything downstream is wrong by that factor: the hedge, the pip value,
the risk report, the confirmation. The defenses are boring and absolute:
notionals are ALWAYS stored and confirmed with an explicit currency,
never as a bare number with an implied unit. This library enforces the
discipline structurally: instruments carry `baseNotional` by name with
the sign convention documented, and the central risk book decomposes
every FX trade into CURRENCY legs in NATIVE units -- a 10M EURUSD buy
books `CCY:EUR +10,000,000` (euros) and `CCY:USD -10,850,000` (dollars)
-- so a unit confusion produces a visibly absurd exposure instead of a
silently scaled one. The general lesson: any ambiguity the representation
can express will eventually be booked; make the representation refuse it.

*In this library:* `fx/FxSwap.java` and `fx/Ndf.java` (explicit
`baseNotional`, sign conventions in the javadoc);
`crb/CentralRiskBook.java` (`bookFxSpot`) books `CCY:` legs in native
units, and `docs/CENTRAL_RISK_BOOK.md` notes the covariance fed to risk
must match those units -- no hidden FX conversion service.

### 359. "What is Herstatt risk, and how do you measure it on a settlement ledger?"

Settlement risk: you pay away your leg of an FX trade before receiving
the other leg, and your counterparty dies in between. Named for Bankhaus
Herstatt, closed by regulators in 1974 AFTER it had received Deutsche
marks but BEFORE it paid out the corresponding dollars in New York -- the
time-zone gap turned a market counterparty into an unsecured creditor for
full principal. Note the size: unlike mark-to-market counterparty risk
(replacement cost, a fraction of notional), settlement risk is the WHOLE
receive amount. Measurement is a ledger exercise: for each instruction
pair, flag the Herstatt window (pay time before receive time), sum
at-risk receive amounts per counterparty, and compute the PEAK intraday
exposure -- the maximum paid-but-not-received amount at any instant. One
conservative detail: when a payment and a receipt share a timestamp,
apply the PAYMENT first, because a worst-case exposure metric must not
let a simultaneous receipt mask the window. The systemic fix is CLS
(payment-versus-payment), which is why CLS-eligible flow carries
near-zero settlement risk and exotic-currency flow does not.

*In this library:* `risk/SettlementRiskAnalyzer.java` -- `SettlementLeg`
with `hasHerstattWindow`, `herstattExposure` per counterparty, and
`peakExposure` with the payments-first tie-break at equal timestamps
documented in the implementation; the Herstatt 1974 story opens the
javadoc.

## e-FX market structure (Q360-Q371)

### 360. "What is last look, and what does the FX Global Code actually require of it?"

Last look is the maker's option to hold an incoming deal request briefly
and reject it if the market has moved -- a consequence of FX liquidity
being QUOTES, not firm orders. The legitimate purpose is protection
against being picked off by faster takers on a stale price. The abuse is
asymmetry: accepting when the move favors the maker and rejecting when it
does not, which converts the hold window into a free option written by
the taker. The FX Global Code (Principle 17) requires the check to be
SYMMETRIC: at the end of the hold, compare the quoted price to current
fair value and reject when the move exceeds tolerance in EITHER direction
-- including moves where rejecting protects the TAKER from dealing on a
now-stale price that favors the maker. A Code-compliant gate is therefore
tiny arithmetic plus disclosure statistics: accept/reject counts split by
who each reject protected, which is exactly what LPs publish and what
takers audit from the other side.

*In this library:* `trading/LastLookGate.java` -- the symmetric decision
(`accept`: reject iff |fair - quoted| > tolerance, direction only affects
classification), with `makerProtectiveRejects`/`takerProtectiveRejects`
disclosure counters; the javadoc cites Principle 17 by name.

### 361. "How do you measure whether an LP's rejects are hurting you -- and why does the implementation use a 4-slot ring?"

Post-reject markout: after each reject, record the mid and your intended
side, then read the mid again one horizon later (say 100ms). If the
market moved the way you were trying to trade, the reject cost you real
money -- the LP declined exactly the flow that was about to pay you, the
signature of asymmetric last look. Track it per LP as an EWMA alongside
reject rate, hold time and effective spread, all exponentially weighted
so the card reflects the LP's CURRENT engine, not the session average of
one that changed behavior at lunch. The ring lesson is a bias subtlety:
rejects burst precisely when the market runs -- which is when markouts
are largest -- and a single pending-markout slot per LP would be
overwritten mid-burst, systematically DROPPING the worst observations and
biasing the stat low for exactly the LPs it must expose. A small ring (4
slots per LP) samples bursts instead of overwriting them; only a burst
deeper than the ring within one horizon loses its oldest entry.

*In this library:* `fx/LpScorecard.java` -- `PENDING_RING = 4` with the
bias rationale in the javadoc; EWMA reject rate, hold nanos, effective
spread and reject markout per LP; markouts mature against mids fed via
`onMid`; persists via `persist.Checkpoint`.

### 362. "FX has no consolidated order book. What does LP liquidity actually look like, and what are the two questions a desk asks of it?"

Each LP streams a PRIVATE ladder of size tiers: 1M at 1.08500/02, 5M at
1.08498/04, 10M wider still -- price deteriorates with clip size within
one relationship, unlike an equities book where depth is anonymous and
shared. The two practical per-clip questions: first, "what does 20M cost
if I SWEEP?" -- walk the frontier of all LPs' tiers cheapest-first and
total the cost, accepting that you traded with several counterparties;
second, "which single LP fills the whole clip at ONE price?" -- the
full-amount quote, usually worse than the sweep arithmetic but leaking
intent to exactly one counterparty. The data structure is fixed
`lpCount x maxTiers` primitive arrays, single writer, tiers stored
best-first per LP exactly as the streams publish them -- because the
sweep-vs-full-amount comparison runs per clip on the execution path and
cannot afford allocation.

*In this library:* `fx/FxTierBook.java` -- `sweepBuyCost` /
`sweepSellProceeds` (frontier walk with caller-owned scratch),
`bestFullAmountAsk` / `bestFullAmountBid`, `sweepPlan` for the deliberate
multi-LP route; the javadoc contrasts the equities single-book structure
directly.

### 363. "Why does FX convention favor full-amount trading when sweeping several LPs is arithmetically cheaper?"

Because every child order is a signal delivered to a counterparty who has
a last-look window to act on it. Spray a 20M parent across five LPs and
five dealing engines simultaneously learn a buyer is working size; each
can reject its slice (last look), widen its next quote, or hedge ahead --
and the rejects land precisely on the slices that were about to be
profitable, so the arithmetic saving of the sweep is eaten by markout and
requote slippage. Full-amount trading sends the whole clip to ONE LP at
one all-in price: the information leaks to a single counterparty with a
fill obligation on the whole amount, no partial-reject games, and TCA
that is trivially attributable. This is the opposite of the equities
instinct (slice small, hide everywhere), and it is rational because the
FX leak channel is not a public tape -- it is the counterparty itself.
The honest statement of the trade-off: sweeps are cheaper on screen and
dearer after markout; deliberate sweeps remain available when you accept
the signaling.

*In this library:* `fx/LpRouter.java` is full-amount only, one LP per
clip -- its javadoc states the child-leak rationale and points takers who
want multi-LP sweeps to `FxTierBook.sweepPlan` "and accept the
signaling".

### 364. "Two LPs: one shows 1.08500 but rejects 20% of the time, one shows 1.08503 firm. Where do you route, and what can silently break the decision?"

Route on expected ALL-IN cost, not displayed price. Charge each LP its
reject economics: `expected = quoted +/- rejectRate x max(postRejectMarkout, 0)`
(worse for the taker on either side) -- the tight quote that rejects
often, with adverse markout after rejects, is more expensive than the
wider firm one. Example: 20% rejects with 3 pips of adverse markout adds
0.6 pips of expected cost, overwhelming the 0.3 pip display advantage.
Two refinements: LPs above a reject-rate cap are VETOED outright whatever
their price (a router that will still deal with a 60% rejecter is not
enforcing anything), and hold time can be charged like latency -- bps per
millisecond held -- since the market drifts against you while the LP
deliberates. The silent failure mode is a wiring bug: the markout penalty
only exists if the scorecard is fed composite mids on the same clock as
the rejects; without the mid feed, markouts never mature, the penalty is
silently zero, and routing degrades to displayed-price-plus-veto.
Monitoring watches matured-markout counts for exactly that.

*In this library:* `fx/LpRouter.java` -- the expected-cost formula, the
`maxRejectRate` veto, `holdUrgencyBpsPerMs`, and the "wiring requirement"
javadoc telling you to watch `card.maturedMarkouts()` stay nonzero while
rejects accrue.

### 365. "What is an aggregated book, and what is phantom liquidity in it?"

The core e-FX data structure: each LP/ECN streams its own two-sided
quote, and the aggregator maintains the composite best bid/offer with
venue attribution -- your private best-of-market. Engineering follows the
venue count: with 5-30 venues, a linear rescan over primitive arrays
beats a heap, makes venue removal (a NaN quote) trivially correct, and
keeps the whole thing zero-allocation single-writer. Two realities the
structure must report honestly: CROSSED composites (best bid >= best ask
across venues) are real in e-FX -- feed latency, not arbitrage -- and
should be flagged, not silently "fixed". And PHANTOM LIQUIDITY: the same
underlying LP often quotes through several ECNs at once, so summing
displayed size across venues counts one dealer's risk appetite three
times; hit all three and you get one fill and two rejects, because the LP
pulls the duplicates the moment one is dealt on. Aggregate DEPTH is
therefore an upper bound, and honest sizing dedupes by underlying LP or
routes full-amount to one of them.

*In this library:* `fx/AggregatedBook.java` -- composite BBO with venue
attribution, NaN-quote removal, `isCrossed()` reported rather than fixed;
per-LP tier structure (the dedup-relevant view) lives in
`fx/FxTierBook.java`.

### 366. "Why is there no NBBO in FX?"

Because there is no consolidated tape, no regulator to mandate one, and
-- more fundamentally -- no single market. FX is over-the-counter:
liquidity is bilateral quote streams, each priced to the RELATIONSHIP
(your credit, your flow toxicity, your tier), so the "best bid" your
aggregator shows is not the best bid someone else's shows, and neither is
publicly disseminated. Equities' NBBO exists because regulation (Reg NMS
in the US) forces venues to publish quotes into a consolidated feed and
route to protect the best of them; FX has no equivalent, so every
participant assembles a PRIVATE composite from the streams it has
credit lines to see. Consequences: "best execution" in FX means process
(measure your own composite, TCA against it) rather than compliance with
a public benchmark; crossed composites are normal feed-latency artifacts;
and two desks can both truthfully claim they crossed the spread --
against different spreads. Your NBBO is the aggregated book you build
yourself, and it is only as good as your venue set.

*In this library:* `fx/AggregatedBook.java` IS that private composite --
per-venue streams into one best bid/offer with attribution;
`fx/LpScorecard.java` and `regulatory/BestExecutionAnalyzer.java` supply
the process-based best-execution measurement the missing tape forces on
you.

### 367. "Single-dealer platform versus ECN -- what is the actual trade-off for a taker?"

A single-dealer platform is one LP's private stream: pricing tailored to
your relationship -- often TIGHTER than any public venue for benign flow,
because the dealer prices in the expectation of internalizing against
other clients -- but with last look, and with your flow fully visible to
that dealer. An ECN is multilateral: many makers, an anonymous or
semi-anonymous central book, firmer liquidity and brokerage fees, wider
top-of-book but no single counterparty seeing your whole footprint. The
trap is comparing them on displayed spread: the SDP's tighter quote can
be more expensive all-in if its reject rate and post-reject markout are
worse, and cheaper if your flow is the kind dealers compete to
internalize. So the professional answer is: measure both on the SAME
axis -- fills, rejects, holds, effective spread, markout -- and let the
router's expected-cost arithmetic decide per clip, rather than adopting
a venue ideology.

*In this library:* `fx/LpScorecard.java` puts SDPs and ECN streams on one
measurement axis (reject rate, hold, effective spread, markout);
`fx/LpRouter.java` converts that into the per-clip routing decision;
`fx/FxTierBook.java` holds each stream's private ladder.

### 368. "How does the WMR fix actually work, and what is the neutral way to execute a fix order?"

The WM/Refinitiv fix is computed from observations inside a calculation
window centered on the fix time -- 5 minutes for major pairs (widened
from 1 minute after the fixing scandals) -- so the benchmark is a
window statistic, not an instant. The neutral replication is therefore
mechanical: spread the parent EVENLY across the window, so realized cost
tracks the benchmark instead of betting against it -- TWAP-in-window IS
the fix replication. Everything else is a bet someone must own:
executing AHEAD of the window ("get done early") is pre-hedging risk
against the client's benchmark -- the conduct at the center of the
2013-15 fix cases -- and skewing inside the window is a directional view
wearing an execution costume. Implementation details that matter: slices
must be exact (largest-remainder, no dust), zero-quantity children are a
schedule-time error (a venue reject mid-window is the worst place to
discover one), and offset arithmetic must be overflow-checked so no slice
can land BEFORE the window -- the exact behavior the scheduler exists to
refuse.

*In this library:* `execution/WmrFixingScheduler.java` --
`WINDOW_MILLIS = 5 minutes`, even slicing delegated to the TWAP scheduler
so "TWAP-in-window is neutral" holds by construction; the javadoc lists
pre-window execution and in-window skew as deliberately excluded, with
`ImplementationShortfallScheduler` named for those who want the bet
explicitly.

### 369. "Your FX backtest fills every order at the touch. What is wrong, and how do you model it honestly?"

It ignores last look, and last look's rejects are not random -- they
cluster on exactly the fills that would have been profitable, so the
backtest overstates the strategy by construction. Honest bar-level model:
the order arrives at the bar open; the LP watches the intra-bar move
during its hold; a move in the TAKER's favor beyond a threshold (price
rising on your buy -- adverse to the LP who would sell) triggers a
reject, and the quantity carries to the next bar exactly like real
requote-and-chase; otherwise you fill at the open plus the half-spread.
Two deliberate modeling choices to state out loud: the asymmetry is a
taker's WORST-CASE -- it simulates the adverse behavior the Global Code
prohibits but a taker must still budget for, so when calibrating the
threshold from an LP's published symmetric disclosures, remember those
count rejects in both directions while this model rejects in one. And
the signal bar is a pure HOLD (no fill, no reject): the parent was
created at that bar's close, so filling at its open would credit a price
from before the signal existed -- intrabar time travel.

*In this library:* `backtest/LastLookExecution.java` -- reject threshold
in bps, spread on fills, carry-to-next-bar rejects, the signal-bar hold
under `ExecutionAwareBacktester`, and reject statistics exposed for TCA;
the javadoc contrasts it with the symmetric maker-side
`trading/LastLookGate.java`.

### 370. "What is an internalization ratio, and why do large FX dealers compete on it?"

The share of client flow a dealer fills from its own book -- crossing one
client's buy against another's sell, or against warehouse inventory --
rather than hedging in the external market. Top FX dealers internalize
the large majority of their G10 spot flow, and the economics explain the
obsession: every internalized unit saves the street spread AND market
impact twice over (once on the client execution the dealer would
otherwise source externally, once on the eventual hedge), and it leaks
nothing to competitors. High internalization is why an SDP can quote
tighter than the ECN top-of-book and still profit. The decision logic per
flow: risk-REDUCING flow (opposite the book's net) is internalized up to
the offsetting inventory and can even hand the client back price
improvement -- the book was going to pay to shed that risk anyway;
risk-ADDING flow is warehoused only while post-trade inventory stays
inside the warehouse limit, and routes out beyond it. The ratio is the
headline stat, but the honest companion metric is what warehousing cost
in hedges -- a ratio bought with unhedged inventory is not skill.

*In this library:* `crb/InternalizationEngine.java` -- the
internalize-or-route decision with `improvementShare` on risk-reducing
flow and the warehouse limit on risk-adding flow;
`internalizationRate()` is the reported ratio; `crb/CrbPnlLedger.java`
keeps the companion economics honest.

### 371. "What actually happened in the 2013-14 FX scandal, and what changed because of it?"

Traders at competing dealers shared confidential client order information
in chat rooms ("The Cartel" is the notorious one), coordinated trading
around the 4pm WMR fix, and pushed the print in the direction of their
pooled positions -- banging the close with the clients' own orders as
ammunition. Investigations from 2013 led to guilty pleas and roughly
$10bn in fines across major dealers. The structural responses map
one-to-one onto mechanism: the WMR calculation window was widened from 1
to 5 minutes (a longer window is harder to push); the FX Global Code
(2017) codified conduct -- symmetric last look under Principle 17,
disclosure of hold and reject practices, no trading on client fix
information; and fix execution became auditable benchmark replication
rather than discretionary dealing. The engineering lesson: conduct rules
you can encode, you should -- a scheduler that structurally refuses
pre-window execution and a gate that is symmetric by construction cannot
commit the violation, whatever the operator's incentives that day.

*In this library:* the responses, encoded: `trading/LastLookGate.java`
(symmetric per Principle 17), `execution/WmrFixingScheduler.java`
(pre-window execution deliberately excluded, javadoc citing the 2013-15
conduct), `regulatory/FixAnalyzer.java` (the banging-the-close detector),
`fx/LpScorecard.java` (the taker-side audit of LP behavior).

## Equities market structure (Q372-Q382)

### 372. "AAPL closed at 500 and opened at 125 after a 4-for-1 split. Your momentum signal just went short. Fix the data."

Back-adjust for corporate actions, CRSP-style. The -75% overnight "move"
is mechanical, not economic: a holder has four shares at 125 instead of
one at 500, wealth unchanged. The adjustment is multiplicative and
applies to bars BEFORE the ex-date: for an r-for-1 split, divide prices
by r and multiply volume by r; for a cash dividend d, multiply prior
prices by `(prevClose - d) / prevClose` where prevClose is the last close
before the ex-date -- so the return across the ex-date reflects the total
economics (holder received the dividend) rather than the mechanical price
drop. Adjusting BACKWARD keeps today's price real -- the number you can
actually trade -- while historical returns become economically true.
Actions compound: a series with three splits and forty dividends applies
all factors cumulatively. Every equity backtest sits on this; skip it and
each ex-date injects a fake negative return the strategy will happily
"learn" to predict.

*In this library:* `data/CorporateActions.java` -- `CorporateAction`
records (SPLIT ratio or CASH_DIVIDEND amount, ex-timestamp) and `adjust`
producing a new back-adjusted `BarSeries` with exactly the two
multiplicative rules above; the javadoc calls it the difference between
toy and usable equity backtests.

### 373. "Why does a continuous dividend yield misprice single-stock options, and what is the escrowed model?"

Because single stocks do not bleed value continuously -- they drop by
discrete cash amounts on known ex-dates. An option expiring the day
BEFORE an ex-date sees none of that dividend; the day AFTER, all of it; a
yield smears the difference across the calendar and misprices both. The
escrowed dividend model handles it cleanly: strip the present value of
all dividends with ex-dates before expiry out of spot, `S* = S - sum(d_i
* exp(-r*t_i))`, let S* diffuse lognormally, and build the forward as
`F = S* * exp((r - borrow) * T)`. Borrow cost enters exactly like a
continuous yield -- a hard-to-borrow name prices calls down and puts up
the same way a dividend yield does, which is also how short-sale
tightness shows up in options space. For American exercise, the same
adjusted spot feeds a binomial tree -- early exercise of calls
concentrates just before large ex-dates, and the tree only sees that if
the dividends are discrete. This class is the forward-looking counterpart
of the historical back-adjustment: one fixes your data, the other your
pricing.

*In this library:* `pricing/DividendSchedule.java` -- `adjustedSpot`, the
escrowed forward with `borrow` as carry, `NONE` for the dividend-free
collapse; the javadoc pairs it explicitly with `data.CorporateActions`
and routes American exercise through `pricing/BinomialTree.java`.

### 374. "Why do European venues make the tick size depend on the stock's price and liquidity, and what must a quoter do about it?"

Because one flat tick cannot be right for both a 0.50 EUR bank and a
2,000 EUR luxury stock. Too small a tick relative to price: spreads
collapse to noise, queue priority becomes worthless, quote flicker
explodes as makers leapfrog by economically meaningless increments. Too
large: the tick floors the spread wider than competition would set it,
taxing every taker. MiFID II (ESMA RTS 11) therefore bands the minimum
increment by PRICE and by LIQUIDITY (more-traded names get finer ticks at
the same price); US equities are the degenerate single-band case ($0.01
above $1). A quoter must (a) look up the tick for the CURRENT price band
-- a binary search cheap enough to run per quote update -- and (b) round
directionally TOWARD PASSIVITY: buys down, sells up, because rounding a
buy upward can cross the market or price an order the venue rejects.
Honest caveat this library states: its generated ESMA-style table is
faithful in structure (1-2-5 per decade, shifted by liquidity band) but
is not a certified copy of the RTS 11 annex -- compliance work loads the
venue's published table.

*In this library:* `microstructure/TickSizeSchedule.java` -- `builder()`
for real venue tables, `esmaStyle(liquidityBand)` with the documented
not-certified caveat, `flat` for the US case, and directional rounding
helpers with the round-toward-passivity rule in the javadoc.

### 375. "Walk me through LULD and market-wide circuit breakers -- and the units bug that puts a symbol in the wrong tier."

Two separate safeguards. LULD (Limit Up-Limit Down) is PER-SYMBOL:
price bands around a reference price (a rolling average); quoting at a
band edge enters a LIMIT STATE, and a limit state persisting 15 seconds
becomes a 5-minute trading pause. Band width depends on tier (Tier 1 --
S&P 500 / Russell 1000 / designated ETPs -- gets 5% above $3.00, Tier 2
gets 10%) and price (20% for $0.75-$3.00; below that, the lesser of 75%
or $0.15), and the bands DOUBLE near the open and close. Market-wide
circuit breakers are INDEX-LEVEL: S&P 500 declines of 7% and 13% each
halt everything for 15 minutes -- each AT MOST ONCE PER DAY, and not
after 15:25 -- while 20% halts for the day. The units bug: the LULD tier
thresholds ($3.00 / $0.75 / $0.15) are DOLLAR amounts from the plan, so a
price fed in 0.0001-dollar tick ints (a common feed-handler convention)
lands every symbol in the wrong tier -- a $50 stock arrives as 500,000
and looks expensive; convert at the seam before classifying. Not exotic:
unit seams between feed convention and regulatory constants are where
this class of bug lives.

*In this library:* `microstructure/CircuitBreakers.java` --
`luldBandPct`/`luldUpperBand`/`luldLowerBand` with the "dollars, not
ticks" warning in the javadoc, the allocation-free `Luld` state machine
(limit state -> 15s -> pause), and `MarketWide` with the
once-per-day / not-after-15:25 rules.

### 376. "How does a call auction pick its clearing price?"

By the standard exchange rulebook hierarchy, applied to the accumulated
book at uncross time. First, MAXIMUM EXECUTABLE VOLUME: find the price(s)
where cumulative eligible buys (limits at-or-above, plus market orders)
meet cumulative eligible sells -- the price that lets the most shares
trade. Second, among volume ties, MINIMUM SURPLUS: prefer the price
leaving the smallest unfilled imbalance. Third, among remaining ties,
REFERENCE PROXIMITY: closest to the reference price, typically the last
trade. Example: buys of 10k at market and 5k limit 100.10; sells of 8k at
market and 6k limit 100.05 -- cumulative curves cross over a price range,
volume is maximized across it, and the surplus and reference rules pick
the point. During the call phase the venue disseminates the INDICATIVE
triple -- price, matchable volume, imbalance -- which is what imbalance
strategies and closing-participation models consume; the uncross
additionally reports which side holds the surplus. The same mechanism
runs the open, the close, and every halt reopening.

*In this library:* `microstructure/Auction.java` -- the three-rule
hierarchy in `uncross`, `indicative` for the disseminated triple, market
orders always eligible on their side, `Result` with signed imbalance and
`hasBuySurplus`.

### 377. "Why is the closing auction called the liquidity event of the day, and how much of a parent should you reserve for it?"

Because index funds, benchmarked mandates and anyone marked at the close
must trade AT the closing price, and the auction is the only place that
price is manufactured -- for many liquid names it prints 5-15% of the
day's volume in one event, via MOC/LOC orders. A close-benchmarked parent
that ignores it works the continuous session against a curve that is
blind to the day's deepest pool. Two inputs decide the reservation:
first, how big is this name's auction TYPICALLY -- learnable as a
day-over-day EWMA of the auction's share of daily volume; second, which
way is TODAY's auction leaning -- from the venue's imbalance
dissemination in the final minutes (Nasdaq NOII, NYSE imbalance): an
imbalance OPPOSITE your side means the auction is looking for exactly
your shares -- reserve more; an imbalance ON your side means you would
join a crowd -- reserve less and work the continuous market. The honest
engineering caveat: this library ships no venue imbalance feed, so its
model is a documented-contract structure -- the learning and reserve
logic are tested on synthetic inputs, and the mapping from your venue's
message format must be validated against real dissemination data before
the output steers size.

*In this library:* `microstructure/ClosingAuctionModel.java` --
`onAuctionResult` (EWMA auction share), `onImbalance` (the documented
NOII-style contract), `reserveFraction` capping the continuous child;
persistence of the learned share via `persist.Checkpoint`.

### 378. "An index announces its rebalance. What flows follow, and why are they so predictable?"

Index funds hold tracking error, not views: when a name enters the index,
every fund benchmarked to it MUST buy, and must buy AT THE CLOSE of the
effective date, because the index itself incorporates the change at that
close -- executing anywhere else creates tracking error even if it saves
money. So rebalance flow is unusual in that its direction, approximate
size (index weight times indexed AUM) and TIMING are all public weeks in
advance. The observed pattern follows: run-up in adds and decline in
deletes between announcement and effective date, a very large closing
auction on the day, and partial reversion after -- the flow is
information-free, so its impact decays. For an execution desk the event
is a closing-auction sizing problem (the imbalance is structural and
one-sided); for a backtester it is a membership-accuracy problem: the
universe on any historical date must be the membership AS OF that date,
including names that later left, or the rebalance effect contaminates
the signal history.

*In this library:* `data/PointInTimeUniverse.java` records the membership
intervals (symbols can leave and rejoin) that make historical rebalances
representable; `microstructure/ClosingAuctionModel.java` handles the
day's oversized auction; `microstructure/Auction.java` is the uncross
mechanism the flow prints through.

### 379. "Your backtest picks from today's S&P 500 going back twenty years. What is wrong, and what does fixing it require?"

Survivorship bias: today's membership list is a list of WINNERS -- every
bankruptcy, acquisition and delisting has been silently removed, so the
strategy "picks" from companies certified to have survived, and the
backtest inherits an optimistic tilt that can exceed the entire reported
alpha. Fixing it needs two halves. DATA: historical membership including
dead tickers, and DELISTING RETURNS -- what a holder actually received on
the way out; when the true value of an involuntary delisting is unknown,
the literature's convention (Shumway 1997) is a strongly negative default
rather than a convenient zero. ENGINE: screens and rebalances must only
see members AS OF each date, and positions must TERMINATE correctly when
a security dies -- a merger pays its per-share cash and/or acquirer
shares; a bankruptcy applies the delisting return; nothing quietly holds
a dead ticker at its last price forever. Membership is intervals, not a
set: names leave and rejoin. The bias is invisible in-sample -- which is
exactly why the engine must enforce point-in-time discipline rather than
trusting the researcher to remember.

*In this library:* `data/PointInTimeUniverse.java` -- membership
intervals, terminal events (`DELISTING` with
`DEFAULT_INVOLUNTARY_DELISTING_RETURN` per Shumway 1997, `MERGER` with
per-share terms), consumed by `screener.StockScreener#membersAsOf` and
the universe-aware `backtest/portfolio/PortfolioBacktester.java`
overload.

### 380. "A stock is halted mid-day. What kinds of halt exist, and how does trading resume?"

Three families. REGULATORY halts (news pending, T1-style): the listing
exchange stops trading everywhere until material news disseminates.
VOLATILITY halts: the LULD mechanism -- a limit state at a band edge that
persists 15 seconds becomes a 5-minute pause; on US equities this is
per-symbol and mechanical, no human judgment. MARKET-WIDE halts: the
S&P-level 7%/13%/20% circuit breakers stopping everything at once. The
resumption is the part systems get wrong: trading does not resume by
flipping the continuous book back on at the last price -- it resumes
through a REOPENING AUCTION, because the halt exists precisely because
the last price stopped being trustworthy; orders accumulate during the
halt and a fresh uncross (maximum volume, minimum surplus, reference
proximity) manufactures a new price with real two-sided interest behind
it. Execution logic must therefore treat a halt as a state, not a gap:
cancel-on-halt policies, re-pricing against the indicative during the
call phase, and no assumption that pre-halt queue positions survive.

*In this library:* `microstructure/CircuitBreakers.java` -- the `Luld`
state machine (limit state, 15s, 5-minute pause) and `MarketWide` levels;
`microstructure/Auction.java` is the reopening uncross -- its javadoc
lists LULD/volatility-halt reopenings alongside the open and close.

### 381. "Odd lots versus round lots -- why does the distinction exist, and why does it matter more now?"

A round lot is the standard quoting unit -- 100 shares for most US
equities -- and market structure privileges it: historically, only
round-lot quotes set the NBBO, and odd lots (fewer than 100 shares) were
excluded from the protected quote and parts of public dissemination. The
distinction mattered little when stocks traded at $30; it matters greatly
when a single share costs $500-$3,000 -- a 100-share round lot of a
high-priced name is a six-figure quote, so an ever-larger fraction of
genuine liquidity and trading migrated inside the round-lot spread as odd
lots, invisible to the headline NBBO. Consequences: the displayed spread
on high-priced names OVERSTATES true cost for small orders, odd-lot
prints must be handled in TCA even where quote rules ignore them, and
regulation has been catching up (round-lot-size reform, odd-lot quote
dissemination). Honest scope note: this library has no odd-lot or
round-lot class -- quantity granularity is not modeled; the analogous
PRICE-granularity machinery (increments, banding, directional rounding)
is what it does implement.

*In this library:* no dedicated class -- said plainly. The nearest
implemented analog is `microstructure/TickSizeSchedule.java` (price
increments and banded granularity); order sizes throughout the engine
are plain quantities with no lot classification.

### 382. "What actually happens operationally when you sell a stock short?"

Four steps the long side never sees. LOCATE: before the sale, your broker
must reasonably believe shares can be borrowed (Reg SHO) -- naked
shorting without a locate is prohibited. BORROW: on settlement you
deliver borrowed shares, paying a borrow fee -- from a few bps for
general collateral to double digits annualized for hard-to-borrow names
-- while posting collateral and passing through any dividends to the
lender (you pay them, since the real holder still expects them). CARRY:
the fee accrues daily and can reprice violently when crowding rises.
RECALL: the lender can demand shares back; if no other borrow exists you
are bought in at the market's price, not yours -- shorts can be forced
out of correct positions. Honest scope note: this library ships no
locate/borrow machinery, and its single-instrument backtester is
documented long-only -- an honest boundary rather than a fake fill; where
shorting economics DO enter is pricing, since borrow cost flows through
the forward exactly like a dividend yield, pricing calls down and puts
up on hard-to-borrow names.

*In this library:* no locate/borrow class -- stated plainly.
`backtest/Backtester.java` declares itself an "event-driven,
single-instrument, long-only" engine in its javadoc;
`pricing/DividendSchedule.java` carries `borrow` in the escrowed forward,
which is where hard-to-borrow shows up in this codebase.

## Central risk book (Q383-Q395)

### 383. "What is a central risk book, and why do banks build one?"

One netted view of the firm's market risk across desks and products --
because two desks paying the street to shed OPPOSITE risks is money
burned twice. The equities desk sells impact and spread unwinding a long
the FX desk's structured-products flow would have happily absorbed; a
CRB nets them before either pays. The operating doctrine is three verbs:
NET first (decompose every instrument into a common factor space at
booking, so an FX-option delta cancels a spot position and two desks'
opposite flows annihilate); WAREHOUSE what nets against future flow
(inventory inside limits is an asset -- it will meet its offset); HEDGE
only the excess -- and route that hedge through yourself before paying
anyone else, because the firm's own offsetting inventory is its first
and best dark pool. The commercial case is measurable, not rhetorical:
netting efficiency (how much gross flow cancelled) and the
diversification benefit (netted VaR versus sum of standalone desk VaRs)
are the two numbers that justify the desk's existence to management.

*In this library:* `crb/CentralRiskBook.java` (the netted factor ledger)
with the full architecture -- quoting, internalization, hedging, routing,
economics -- laid out in `docs/CENTRAL_RISK_BOOK.md`; the whole package
is deterministic research/warm lane by design.

### 384. "How do you put an equity option, an FX swap and an NDF into ONE risk ledger -- and why does the FX swap book NEGATIVE points exposure on a buy-sell?"

Decompose at booking time into shared factors with declared units --
the decomposition IS the netting. Conventions: `EQ:<sym>` is equity
delta in book-currency notional (cash shares contribute qty x price;
options contribute delta x spot x contract units, so they net against
shares); `EQGAMMA:`/`EQVEGA:` carry dollar gamma per 1% and vega per vol
point; `CCY:<ccy>` is currency exposure in NATIVE units (a 10M EURUSD
buy books CCY:EUR +10M euros, CCY:USD -10M x rate dollars) -- currency
level netting is what lets spot, swaps, NDFs and option deltas cancel
across products; `FXPOINTS:<pair>` is P&L per +1.0 move in the far-near
differential. The sign story: a buy-sell swap of N base books
`FXPOINTS: -N`, because if the points WIDEN (far-near increases) the
buy-sell loses -- it sold the far leg it must now buy back richer; the
base legs of the swap cancel exactly, leaving only the quote-currency
cash-flow imbalance. And the boundary is explicit: this is a RISK
ledger, not a cash ledger -- premium and settlement cash legs are
deliberately untracked, and lifecycle events re-book as offsetting
flows.

*In this library:* `crb/CentralRiskBook.java` -- `bookCashEquity`,
`bookEquityOption`, `bookFxSpot`, `bookFxSwap` (the `-baseNotional` on
FXPOINTS is in the code), `bookNdf`; the factor table with units is both
in the class javadoc and `docs/CENTRAL_RISK_BOOK.md`.

### 385. "The CFO asks what the CRB is worth. What two numbers do you show?"

Netting efficiency and the diversification benefit -- flow cancelled and
risk cancelled. Netting efficiency is `1 - net/gross` summed over the
factor space: if desks booked 500M gross of EURUSD-equivalent exposure
and the netted book holds 80M, efficiency is 84% -- that 420M is flow
nobody paid the street to shed, and multiplying it by a realistic
half-spread-plus-impact cost converts it straight into saved dollars.
The diversification benefit prices the RISK side: compute VaR/ES on the
netted book against a factor covariance, and compare with the SUM of
standalone per-desk VaRs on the same covariance; the difference is what
central management of the risk bought versus each desk hedging its own
silo. Both numbers need the per-desk attribution to be kept alongside
the net -- a CRB that can only show the net cannot prove it improved on
the parts. Present them together: efficiency without the risk number
hides concentration (flows can net while risks compound in one factor),
and the risk number without efficiency hides how much of the benefit
was luck of offsetting client flow.

*In this library:* `crb/CentralRiskBook.java` -- `nettingEfficiency()`
(1 - net/gross) and `report(cov, confidence)` producing the `CrbReport`
with netted VaR/ES via `risk/VarEngine.java` against summed standalone
desk VaRs; per-desk attribution is maintained in the booking path.

### 386. "A client sells you 5M EURUSD. Your book is long 8M. What do you do with the flow, and what does the client get?"

Internalize it, and pay the client for the privilege. The flow is
risk-REDUCING -- opposite the book's net -- so absorbing it moves the
book from +8M toward +3M: risk the desk was otherwise going to PAY the
street to shed (spread plus impact, twice -- once executing, once
hedging). So the engine crosses it against inventory up to the
offsetting amount and returns a share of the saved spread to the client
as PRICE IMPROVEMENT -- economically rational generosity, and the reason
internalizing dealers win benign flow. The mirror case has a hard edge:
a client SALE when the book is already long is risk-ADDING; the engine
warehouses it only while post-trade inventory stays inside the warehouse
limit, and routes the excess to the street -- because a warehouse limit
that yields to one more trade is not a limit. Partial cases split: a 12M
risk-reducing sale against +8M nets 8M as reducing and treats the 4M
overshoot under the warehouse rule. Counters accumulate into the
internalization rate the desk reports.

*In this library:* `crb/InternalizationEngine.java` -- `Decision`
(internalized, routed, improvementBps), `improvementShare` in [0,1],
the warehouse-limit rule quoted above from its javadoc, and
`internalizationRate()`.

### 387. "Your CRB is long. How should its quotes change, and what stops the skew from breaking the quote?"

Shade BOTH quotes downward. A long book wants to sell and stop buying:
lower the ask (the attractive side -- flow that reduces inventory gets a
better price) and lower the bid (accumulating more gets less attractive).
That is the Avellaneda-Stoikov reservation-price intuition applied at
book level without the vol/horizon machinery: skew linearly in inventory
as a fraction of the inventory limit, `skew = -(inventory/limit) x
skewFraction x halfSpread`, clamped at one full half-spread. Two
structural guards keep the quote valid: the inventory ratio is clamped to
[-1, +1] so an over-limit book cannot skew unboundedly, and
`skewFraction` is strictly below 1 -- at exactly 1 a full-limit inventory
would shade one side by the entire half spread and quote a ZERO-WIDTH
side, so the constructor stops short of it. Worked example: mid 100, half
spread 5bps, inventory 80% of limit, skewFraction 0.5 -- skew is -2bps,
quoting 99.93 / 100.03 instead of 99.95 / 100.05: still two-sided, still
profitable, but leaning the market toward taking your inventory off you.

*In this library:* `crb/SkewedQuoter.java` -- the formula and both clamps
are in the javadoc and constructor checks; returns a `Quote(bid, ask,
skewBps)` record; static and deterministic.

### 388. "Before you can optimize a hedge, you need the loadings matrix. Why is that step dangerous, and how do you build it safely?"

Because `loadings[factor][instrument]` is hand-assembled coupling between
two systems -- one transposed index, one sign error, one row misaligned
with the registry, and the optimizer CONFIDENTLY hedges the wrong thing;
nothing throws, the hedge just quietly increases risk. The safe pattern:
build the matrix through the SAME conventions the book books with, on
the SAME factor registry. An FX forward hedge declares that one unit of
base notional loads `CCY:<base>` +1 and `CCY:<quote>` -rate -- exactly
like a booked trade, so hedge and exposure can never disagree about what
a forward does. An index future is a single-factor instrument on
`EQ:<index>`: the covariance carries its correlation to the single
names, so the cross-hedge regression falls out of the OPTIMIZER, not a
hand-maintained beta table. Hedge-only factors (an index the book holds
no position in) register on the shared registry with zero book exposure.
And materialize the matrix only AFTER all booking and adding is done --
it is sized to the registry's current size, and a factor registered
after the build silently has no row.

*In this library:* `crb/CrbHedgeUniverse.java` -- `addSingleFactor`,
`addFxForward`, generic `add`; built on the book's own `FactorRegistry`
(`book.factors()`); `loadings()` and `costs()` feed
`crb/HedgeOptimizer.java` directly. The javadoc calls hand-assembly "the
most error-prone step in the whole hedging workflow".

### 389. "Minimum-variance hedging says trade twelve instruments in dust sizes. How do you make the optimizer respect transaction costs -- and when should it refuse to answer?"

Add an L1 cost term and solve by coordinate descent. The objective is
`(e + L*h)' Sigma (e + L*h) + lambda * sum(c_i * |h_i|)`: residual
factor variance plus a per-unit cost on each instrument's absolute
notional -- costs fed from spread plus a market-impact estimate (a Kyle
lambda slots in directly). The L1 term does what a hedging desk actually
wants and a quadratic penalty cannot: instruments whose marginal risk
reduction is worth less than their cost get EXACTLY zero via the
soft-threshold update, not a dusty small position -- so the answer is
"hedge with the future and one forward", not twelve trades. At lambda=0
it recovers the closed-form minimum-variance hedge, pinned in tests
against the normal equations -- the discipline of an exact special case.
The refusal matters as much: near-collinear instruments (two
0.999-correlated futures) make Gauss-Seidel contract slowly, and a
silent max-iteration exit would return a plausible-LOOKING, grossly
unconverged hedge for a live breach; the implementation instead throws,
telling you to drop the redundant twin. A wrong answer that looks like
an answer is worse than an exception.

*In this library:* `crb/HedgeOptimizer.java` -- the exact soft-threshold
coordinate descent, RELATIVE convergence tolerance (notionals run in
millions), the lambda=0 closed-form recovery pinned in tests, and the
documented `IllegalStateException` on non-convergence.

### 390. "Design the auto-hedging policy for a CRB. When does it trade, how much, and what overrides what?"

Two-speed by design. INSIDE per-factor exposure bands: warehouse and do
nothing -- the whole point of a CRB is that inventory nets against
future flow for free, and hedging inside the band pays the street for
risk the next client was about to take off you. ON a breach: hedge the
breached factors' EXCESS back to `resetFraction x limit` (not to zero --
flattening surrenders the warehouse), cost-aware through the optimizer
so the cheapest basket does the work. Then three overrides, in rank
order. The HARD LIMIT outranks thrift: if the cost-aware hedge leaves
any factor still outside its limit, rerun at ZERO cost weight -- a limit
the cost model can veto is not a limit. A COOLDOWN stops the book
chasing its own hedges (hedge, drift, hedge again every interval --
paying the spread repeatedly for the same risk). And a DUST FILTER drops
orders below ~1e-6 of the largest notional: coordinate descent converges
should-be-zero instruments only to tolerance, and no desk sends a
sub-cent hedge to the street. Time is a caller-supplied interval
counter, not a wall clock -- deterministic and replayable.

*In this library:* `crb/CrbAutoHedger.java` -- `breached`, the
resetFraction target, the cost-blind rerun ("the limit is hard; the cost
preference is not" in the code comments), the `1e-6 * maxH` dust filter,
and cooldown intervals.

### 391. "You have a hedge to execute: 50M of index futures equivalent. Route it."

In honest cost order: internal, dark, lit. INTERNAL first -- crossing
against the book's own offsetting inventory costs ZERO bps and leaks
nothing; the CRB is the firm's first and best dark pool, capped at
whatever crossable inventory actually exists. DARK second -- midpoint
fills pay no spread, but each venue is priced honestly: an
ADVERSE-SELECTION charge in bps (post-fill markout -- fills that
systematically fade are not free liquidity, and a venue-scorecard
estimate slots in directly) and expected liquidity DISCOUNTED by fill
probability; a venue whose charge exceeds the lit cost gets nothing,
whatever size it advertises. LIT last -- half spread plus expected
impact, but it fills. The subtle correctness rule: whatever the dark
legs are not EXPECTED to fill routes lit as well -- a hedge that might
fill is not a hedge, and sizing the lit leg to the optimistic dark
scenario leaves the book carrying the breach the hedge existed to fix.
Allocation is greedy by expected cost and deterministic; the caller
owns the venue statistics and their honesty.

*In this library:* `crb/CrbRouter.java` -- `DarkVenue(name,
expectedLiquidity, fillProbability, adverseSelectionBps)`, the
internal -> dark -> lit allocation with blended `expectedCostBps`;
`docs/CENTRAL_RISK_BOOK.md` section 4 states the "first dark pool" and
"hedges that might fill are not hedges" doctrine.

### 392. "At the close, the desk head asks: did the CRB make money today? What do you show, and what do you deliberately leave out?"

Realized flow economics, and ONLY realized flow economics. The ledger:
internalized client flow captures the street half spread minus whatever
improvement was given back -- `notional x (halfSpread - improvement) /
1e4`; routed flow captures nothing (it went to the street); hedge
executions cost their all-in bps; router allocations cost their blended
expected bps. Net economics answers the only question that matters at
the close: did the spread we captured by internalizing pay for the
hedging we did? A CRB that nets beautifully but hedges expensively is a
cost center with good graphics. What is deliberately EXCLUDED: inventory
mark-to-market. Unrealized marks belong to the risk report -- blending
realized spread economics with unrealized inventory P&L is how desks
fool themselves, because a good marks day hides an uneconomic flow
franchise and vice versa. The ledger also refuses nonsense at the door:
improvement exceeding the half spread throws, because that would mean
the desk is paying clients to trade. Persistable, so the week's
economics survive the nightly restart.

*In this library:* `crb/CrbPnlLedger.java` -- `onInternalized`,
`onRouted`, `onHedge`, `netEconomics()`; the MTM exclusion and the
"paying clients to trade" guard are both explicit in javadoc and code;
`writeState`/`readState` for persistence.

### 393. "The CRB restarts every night. What state must survive, and what should a restore refuse to do?"

Survive: the risk positions and the learned/accumulated state --
factor names, net and gross exposures, per-desk attribution, pending NDF
fixings, and the internalization counters -- written as sections of the
same checkpoint the rest of the system uses, with atomic-rename
semantics so a crash mid-write leaves the previous good file. Two design
details carry the weight. Determinism: factor maps serialize in sorted
(TreeMap) order, so the same book always writes byte-identical state --
diffable, hashable, testable. And REFUSAL on restore: reading state into
a book that already holds positions throws -- a book that relearns its
positions every morning must start empty, and merging two position sets
silently is how a desk doubles its risk without noticing. Configuration
mismatches fail equally loudly: an internalization history earned under
one warehouse limit refuses to restore under another, because the
counters' meaning depends on the limit that produced them. The house
rule generalizes: persistence must never launder an inconsistency into
a running system.

*In this library:* `crb/CentralRiskBook.java` -- `writeState`/`readState`
as `persist.Checkpoint` section bodies, the fresh-instance requirement
in the javadoc, TreeMap ordering noted in the code;
`docs/CENTRAL_RISK_BOOK.md` section 6 documents the warehouse-limit
mismatch refusal for `InternalizationEngine`.

### 394. "Why does the factor registry map names to dense integers instead of just using a HashMap of exposures?"

Because the hot arithmetic should run over primitive arrays while the
factor names stay readable. Exposure math -- netting, band checks,
covariance products, the optimizer's inner loops -- touches every factor
on every pass; with dense ids those are `double[]` operations with no
boxing, no hashing per access, and matrix rows that align by index.
The registry is the boundary: `EQ:AAPL` or `FXVEGA:EURUSD` resolves to
an int once at booking or setup, and everything downstream speaks
arrays. Design choices that matter: GROW-ONLY, ids are registration
order -- an id, once handed out, never moves, so serialized state and
loadings matrices stay valid as new factors appear; a non-registering
lookup (`idIfPresent`, returning -1) exists so read paths can query
without polluting the space; and the same pattern is reused across the
library for symbols, so the idiom is learned once. This is the
`SymbolRegistry` pattern applied to risk factors -- the general rule:
translate names to dense ints at the edge, and keep the interior of the
system numeric.

*In this library:* `crb/FactorRegistry.java` -- `id` (register-on-first-
sight), `idIfPresent` (-1, never registers), `name`, `size`; the javadoc
names the `SymbolRegistry` pattern explicitly, and
`crb/CrbHedgeUniverse.java` aligns its loadings matrix to it.

### 395. "Describe a realistic week for a central risk book -- not the demo, the desk."

Four days, each stressing a different subsystem, is how this library
proves the design -- as an executable scenario test at realistic sizes,
vols and costs. Day one, quiet two-way flow: clients buy and sell
against each other, the internalization rate runs above 50%, no bands
breach, and the day's economics are simply the captured spread -- the
franchise working as designed. Day two, a one-way 18M institutional
order: the warehouse absorbs until per-factor bands breach; the
cost-aware hedge fires, and -- the instructive detail -- ESCALATES to
the direct instrument because a cheap proxy cannot satisfy a per-factor
band (a proxy hedges the covariance, not the specific factor the band
constrains); the day still nets positive after hedge costs. Day three, a
COVID-template stress replay priced to the dollar, with reverse-stress
sigmas showing exactly what the netting bought relative to standalone
desks. Day four, an NDF fixing day rolled THROUGH the overnight
checkpoint -- pending fixings survive the restart and settle correctly
the next morning. A CRB that only works on the quiet day is a demo;
the test is the claim, runnable.

*In this library:* `src/test/java/com/quantfinlib/crb/CrbRealWorldScenarioTest.java`
-- all four days as described in `docs/CENTRAL_RISK_BOOK.md` (which cites
the test and notes Cookbook recipe 14 as the runnable version).

---

## Vanilla options & Greeks (Q396-Q409)

### 396. "List the Black-Scholes assumptions one by one. Which breaks worst in practice?"

The full list: (1) the underlier follows geometric Brownian motion with
constant volatility; (2) constant risk-free rate; (3) continuous,
frictionless trading -- no transaction costs, no bid/ask, any size; (4) no
jumps; (5) short selling allowed with full use of proceeds; (6) known,
continuous dividend yield. The one that breaks worst is **constant
volatility** -- not by a little, but structurally: implied vols differ by
strike (the smile/skew) and by expiry (the term structure), which is the
market telling you flatly that returns are not lognormal with one sigma.
Second worst is continuous frictionless trading: real hedging is discrete
and costly, so replication error is unavoidable (that is a P&L
distribution, not a rounding error). Jumps break both at once -- a gap
through your hedge point is exactly where gamma cannot save you. The rate
and dividend assumptions are the tame ones: violations are small and
mostly first-order correctable. The useful framing: Black-Scholes survives
not because the assumptions hold but because desks quote vol as the free
parameter and hedge the rest -- the model is a translation device, not a
description of the world.

*In this library:* `pricing/BlackScholes.java` (the baseline),
`pricing/VolSurface.java` (the smile that contradicts assumption 1),
`hedging/DeltaHedger.java` (what discrete costly hedging does to
assumption 3).

### 397. "What do d1 and d2 actually mean? Not the formulas -- the meaning."

`d2 = (ln(F/K) - sigma^2 T / 2) / (sigma sqrt(T))` measures how many
standard deviations the log-forward sits above the log-strike, so
`N(d2)` is the **risk-neutral probability the option finishes in the
money**. d1 = d2 + sigma sqrt(T) is the same quantity computed under a
different probability measure -- the one that uses the asset itself as
numeraire -- so `N(d1)` is the probability of finishing in the money
*weighted by how big the asset is when it does*. That weighting is why
the call price is `S e^{-qT} N(d1) - K e^{-rT} N(d2)`: the first term is
the expected value of receiving the asset (big outcomes count more), the
second the expected cost of paying the strike (all in-the-money outcomes
count equally). A concrete check: for a far out-of-the-money call, N(d2)
is tiny (rarely exercised) but N(d1) > N(d2) always, because *given*
exercise the asset tends to be large. Knowing the two-measure story is
what lets you rederive digital prices (pure N(d2) claims) and
asset-or-nothing prices (pure N(d1) claims) without opening a book.

*In this library:* `pricing/BlackScholes.java` (d1/d2 in `price`),
`pricing/DigitalOption.java` -- cash-or-nothing is the discounted N(d2)
term isolated, asset-or-nothing the N(d1) term, and the javadoc shows a
vanilla decomposing exactly into the two.

### 398. "Is delta the probability the option expires in the money?"

No -- and the distinction is worth being precise about. Delta is a
**hedge ratio**: the number of units of the underlying whose price
movement locally offsets the option's. For a call it is `e^{-qT} N(d1)`.
The probability of finishing in the money (risk-neutral) is `N(d2)`,
which is always *smaller* for a call. The gap is sigma sqrt(T) inside the
normal CDF: for an at-the-money one-year option at 20 percent vol, delta
is about 0.54 while the exercise probability is about 0.46 -- an 8-point
difference a "delta = probability" heuristic silently eats. The nuance
that makes the heuristic *approximately* respectable: the undiscounted
N(d1) is a genuine probability, just under the share-measure (asset as
numeraire), and for short-dated, low-vol options d1 and d2 converge, so
traders' shorthand "a 25-delta option" as "roughly one-in-four to pay
off" is fine for talking and wrong for pricing digitals. If someone
prices a cash-or-nothing off delta instead of N(d2), they overpay
systematically -- that is the practical cost of the confusion.

*In this library:* `pricing/BlackScholes.java` (`delta` returns the
hedge ratio; d2 lives one line away), `pricing/DigitalOption.java`
(the instrument whose fair value IS the discounted probability, so the
two concepts are forced apart in code).

### 399. "Explain the gamma-theta relationship. Why do people call theta 'rent'?"

For a delta-hedged option under Black-Scholes, the P&L over a small step
is `0.5 Gamma (dS)^2 + Theta dt` (rate terms aside): gamma pays you the
squared move, theta charges you for time passing. The pricing PDE forces
them to offset *in expectation*: `Theta = -0.5 sigma^2 S^2 Gamma` for a
hedged, zero-rate book. So theta is exactly the **rent you pay for
holding convexity**: if the underlier realizes daily moves of the implied
sigma, gamma gains equal theta losses and the hedged book breaks even.
The trade this creates is the purest expression of a vol view: long
gamma profits when realized vol beats implied (each rebalance sells high
/ buys low more than the rent), short gamma is a landlord collecting
theta and praying against a gap. Concrete example: hold a delta-hedged
ATM straddle with implied 16 vol (about 1 percent per day). Each day the
stock moves 1.5 percent, your gamma P&L beats the theta bill by roughly
`0.5 Gamma S^2 (0.015^2 - 0.01^2)`; each quiet day, you pay full rent
for an empty apartment. That per-day accounting is exactly what a
hedging simulation makes visible.

*In this library:* `pricing/BlackScholes.java` (`greeks` returns gamma
and theta together), `hedging/DeltaHedger.java` and
`hedging/HedgingSimulator.java` -- run realized above/below implied and
watch the gamma-theta ledger settle into the P&L distribution.

### 400. "Where does vega live on the term structure, and why do desks bucket it?"

Vega is `S e^{-qT} n(d1) sqrt(T)` -- it grows with sqrt(T), so long-dated
options carry far more vega per contract than short-dated ones, while
short-dated options carry the gamma. That split is the desk's whole
topology: the front of the curve is a gamma/theta business (realized vol
matters), the back is a vega business (implied repricing matters). But a
single "portfolio vega" number is dangerous precisely because implied
vols do not move in parallel: the front end can jump 10 points on an
event while the 2-year point barely stirs -- short-dated implieds are
several times more volatile than long-dated ones. So desks bucket vega
by expiry (and often by strike) and hedge each bucket against the
instruments that actually trade there. A book that is vega-flat in
aggregate but long 1-month / short 1-year vega is a term-structure
steepener wearing a "flat" label -- it makes or loses real money when
the curve pivots. The practical rule: report vega as a vector, hedge it
against a curve scenario, and never let the sqrt(T) weighting hide a
calendar position.

*In this library:* `pricing/BlackScholes.java` (`greeks` -- the
sqrt(T) factor in vega), `pricing/VolSurface.java` (per-expiry smiles:
the object a bucketed vega report is written against),
`hedging/OptionsBook.java` (`scenarioGrid` reprices the book across vol
shifts -- the honest version of a vega number).

### 401. "Does anyone care about rho? When do rates actually matter for options?"

Rho is the forgotten Greek right up until it is not. For a call, rho is
`K T e^{-rT} N(d2)`: it scales with T and with moneyness, so short-dated
options have negligible rho and long-dated deep-in-the-money options have
a lot. Three places it earns attention. First, long-dated structures --
a 5-year autocallable or LEAPS position has rho comparable to its vega,
and a 200bp rate cycle moves its value materially. Second, regime
changes: from 2009 to 2021 everyone set r roughly 0 and rho was a
footnote; the 2022-2023 hiking cycle repriced every long-dated book and
desks that had never hedged rho discovered they owned a rates position.
Third, the carry channel: rates enter twice -- discounting (rho proper)
and the forward `F = S e^{(r-q)T}`, and the forward effect usually
dominates: higher r lifts call values through the forward before
discounting claws part back. The honest summary: rho is a second-tier
Greek on a 1-month book and a first-tier one on a structured-products
shelf, and the difference is just T.

*In this library:* `pricing/BlackScholes.java` (`greeks` -- rho with the
K T e^{-rT} N(d2) shape visible), `pricing/Black76.java` (the forward
formulation that isolates the discounting role of r from the carry
role).

### 402. "What happens to Black-Scholes at zero volatility? Why does naive code break there?"

At sigma = 0 the world is deterministic: the stock grows at the carry
rate to `F = S e^{(r-q)T}`, and the option is worth the **discounted
forward intrinsic** -- `e^{-rT} max(F - K, 0)` for a call. Naive code
breaks because d1 and d2 divide by sigma sqrt(T): off the money the
division blows up to +/- infinity and N() saturates to the right answer
by luck, but at the money *forward* (F = K exactly) the numerator is
also zero, the expression is 0/0, and the price comes back NaN. The same
failure hits T = 0. So a production pricer needs an explicit branch: if
sigma or T is (near) zero, return the deterministic value directly.
This is not pedantry -- zero-vol inputs happen constantly in real
systems: deep-ITM ultra-short options, calibration loops probing the
bracket edge, risk scenarios that shock vol to the floor. The guard also
gives you a free correctness test: the sigma -> 0 limit of the full
formula must converge to the branch value, which pins down that you put
the carry in the right place.

*In this library:* `pricing/BlackScholes.java` -- the explicit guard
returns the discounted forward intrinsic, with a comment noting the
ATM-forward 0/0 in d1 that motivates it, plus an `intrinsic` helper for
the T = 0 case.

### 403. "Why do options trade in implied vol rather than price?"

Because price is a terrible coordinate and vol is a good one. An option
price confounds five inputs -- spot, strike, time, rates, and the one
thing the market actually debates. Quoting in implied vol inverts
Black-Scholes to strip out the four mechanical inputs and leaves the
**price expressed in units of expected movement**, which is comparable
across strikes, expiries and underliers in a way dollars never are: "the
1-month ATM trades at 18 vol" means the same kind of thing on any stock,
while "$2.35" means nothing without the whole context. It also makes
quotes stable: as spot ticks around, fair option prices change every
second but the implied vol barely moves, so market makers can hold a vol
quote and let the machine translate to price at trade time -- this is
literally how vol markets, variance swaps and the VIX are built.
Important honesty: implied vol is *not* a forecast that the model
believes; it is the market-clearing price wearing vol units, smile and
all. The smile across strikes is then readable as the market's pricing
of non-lognormality -- information you could never see in a price grid.

*In this library:* `pricing/BlackScholes.java` (`impliedVol` -- the
inversion that defines the quoting convention),
`pricing/VolSurface.java` (the object a vol-quoted market maintains),
`volatility/VolatilityIndex.java` (a whole index built by reading
prices back as vol).

### 404. "Your implied-vol solver gets a price it can't match. What should it return?"

`NaN` -- loudly, not a clamped number quietly. Option prices live in a
bounded band: a call is worth at least its discounted forward intrinsic
(the sigma -> 0 limit) and at most the sigma -> infinity limit
(approaching `S e^{-qT}`). A quoted price outside that band is not a
volatility at all -- it is a stale print, a crossed market, a fat-finger
mid, or rounding on a penny option. Bisection makes the honesty
mechanical: bracket vol in something like [1e-4, 5.0], check that the
target price actually lies between the prices at the two bracket ends,
and if it does not, return NaN instead of converging to a bracket
endpoint and reporting 0.0001 or 5.0 as if it were information. The
failure mode this prevents is real: a surface builder that clamps
instead of rejecting will happily ingest a stale deep-ITM quote as
"0.01 percent vol," poison the smile interpolation around it, and feed
garbage Greeks to the hedger. NaN forces the caller to decide -- drop
the quote, widen the bracket knowingly, or investigate -- which is
exactly the decision that should not be made silently inside a solver.

*In this library:* `pricing/BlackScholes.java` -- `impliedVol` bisects
inside the documented [1e-4, 5.0] bracket and returns `NaN` for prices
below intrinsic or above the maximum attainable Black-Scholes price,
with the javadoc naming stale/rounded quotes as the expected cause.

### 405. "State put-call parity and explain why it needs no model."

`C - P = S e^{-qT} - K e^{-rT}`: a long call plus short put with the
same strike and expiry *is* a forward -- at expiry the pair pays
`S_T - K` in every state of the world, no exceptions. Because the
replication is exact and static (buy, hold, done -- no rebalancing),
parity holds under any model, any distribution, any smile: it is an
**arbitrage constraint**, not a pricing formula. Violate it and the
trade writes itself: if C - P is rich versus the carry-adjusted forward,
sell the call, buy the put, buy the stock, borrow K e^{-rT}; the expiry
cash flows cancel and the entry mispricing is locked in. Practical uses
follow directly. It is the first sanity check on any pricer -- a
Black-Scholes implementation whose call and put disagree with parity has
a carry or discounting bug, full stop. It is how desks read the implied
dividend/borrow: given traded C, P, S and r, solve parity for q -- the
market's own estimate of dividends and hard-to-borrow cost. And it is
why calls and puts at the same strike must carry the *same* implied vol:
the smile is a function of strike, never of option type -- a surface
that says otherwise is broken.

*In this library:* `pricing/BlackScholes.java` -- `price` for CALL and
PUT differ exactly by the parity relation (a one-line test to write),
and `pricing/ForwardCurve.java` holds the carry-adjusted forward that
the parity right-hand side is.

### 406. "Where do dividends and carry enter option pricing, and what is the cost-of-carry b?"

Everything routes through the forward. Holding the stock instead of the
option means earning the dividend yield q and forgoing interest r, so
the arbitrage-free forward is `F = S e^{(r-q)T}` and the generalized
Black-Scholes just prices against that forward with discounting at r.
The cost-of-carry parameter `b = r - q` unifies the asset classes: b = r
is the no-dividend stock, b = r - q the dividend payer, b = 0 the
futures/forward case (Black-76), b = r - r_foreign the FX case
(Garman-Kohlhagen) where the foreign rate plays the role of a dividend.
Getting carry wrong is the classic silent pricing bug because it shifts
the *forward*, not the vol: your smile calibration will absorb the error
into implied vols that look plausible and hedge wrong. Concrete case: a
3 percent dividend yield on a 1-year option moves the forward about 3
percent -- on an ATM option that is roughly a 1.5-vol-point-sized price
error if you drop q. Discrete dividends are the harder real-world
version: a known cash dividend is a forward drop on the ex-date, not a
smooth yield, which matters for early exercise of American calls.

*In this library:* `pricing/BlackScholes.java` (the carry parameter in
d1 and the forward), `pricing/Black76.java` (the b = 0 specialization),
`pricing/DividendSchedule.java` (discrete dividends the smooth-yield
shortcut mishandles), `pricing/ForwardCurve.java`.

### 407. "Why does Black-76 quote delta against the forward, and when do you prefer it?"

Black-76 prices options on a **forward or futures price**: the payoff is
on F, the hedge instrument is the future, so the natural delta is
dV/dF -- the number of futures contracts to hold -- and the whole carry
question disappears because a futures position costs nothing to carry
(b = 0). The formula is Black-Scholes with F replacing S e^{(r-q)T}:
`C = e^{-rT} (F N(d1) - K N(d2))`, discounting applied once at the end.
You prefer it whenever the deliverable hedge is a forward: interest-rate
caps/floors and swaptions (quoted in Black-76 vol for decades),
commodity options (the future IS the underlying), and FX desks quoting
forward delta so the hedge maps to a forward contract rather than spot
plus a carry position. The forward-delta convention also cleans up the
edge cases: at zero vol the delta is just the discounted step function
around F = K, no spot-carry adjustment to fumble. The practical
takeaway -- know which delta your counterparty quotes: a
25-delta FX option means forward delta on some desks, spot delta with or
without premium adjustment on others, and the same option can differ by
a couple of vol points across conventions on the smile.

*In this library:* `pricing/Black76.java` -- forward-based price and
`delta` (dV/dF), including the zero-vol branch returning the discounted
intrinsic delta, mirroring the `BlackScholes` guard on the spot side.

### 408. "What are vanna, volga and charm, and who actually uses them?"

They are the second-order Greeks that tell you how your *hedges* decay.
**Vanna** = d2V/dS dsigma: how delta drifts when vol moves (equally, how
vega moves when spot moves). A delta-hedged book with vanna is unhedged
through a correlated spot-vol move -- exactly what happens in an equity
selloff, where spot down and vol up arrive together, so skew traders
price and hedge vanna explicitly. **Volga** = d2V/dsigma^2: vega
convexity -- a vega-hedged book with volga re-exposes itself the moment
vol moves, which is why wings (strangles) trade over a volga-flat
combination. FX desks charge the smile for exactly these two: the
vanna-volga method prices an exotic as Black-Scholes plus the market
cost of the vanna and volga it carries. **Charm** = d2V/dS dT: delta
decay -- the overnight drift of your hedge with no market move at all,
the reason a pin-risk book re-hedges at the open even on a flat tape;
it dominates in the last days before expiry. Users, concretely: FX
option desks (vanna-volga pricing), index-skew and structured-product
hedgers (vanna from the short-put inventory), and anyone running
short-dated books (charm at expiry week).

*In this library:* `pricing/HigherOrderGreeks.java` -- `vanna`, `volga`
(with javadoc tying them to the `VannaVolga` smile charge) and
`exchangeCrossGamma` for two-asset books; charm is not implemented --
finite-difference `BlackScholes.delta` across a small time step to get
it, which is also a good exercise.

### 409. "How do you keep Greeks fresh at tick frequency without repricing every tick?"

You do not reprice -- you Taylor-expand from an anchor. A full
Black-Scholes evaluation (with all Greeks) anchors the position at spot
S0; then every tick updates price and delta by the delta-gamma
expansion: `price(S) ~= price0 + Delta0 (S - S0) + 0.5 Gamma0 (S -
S0)^2` and `delta(S) ~= Delta0 + Gamma0 (S - S0)` -- a handful of
multiplies, zero allocation, safe on the tick thread. The error term is
third-order (O((S - S0)^3), scaled by speed = dGamma/dS), which is
negligible for the sub-half-percent moves that occur between anchor
refreshes. The engineering discipline is the split: a `needsReprice`
threshold watches how far spot has drifted from the anchor, and when it
trips, a *pricing thread* -- not the tick thread -- recomputes the full
anchor. This is the standard architecture of live options risk: exact
math off the hot path, cheap local expansion on it, and an explicit,
tested bound on the staleness in between. The same pattern generalizes:
add vega times a vol delta if your vol feed ticks too, and the expansion
is just the first terms of the P&L-explain identity.

*In this library:* `pricing/IncrementalGreeks.java` -- anchor via
`reprice` (allocates, slow path), `onTick` (allocation-free delta-gamma
update), and `needsReprice` as the drift tripwire; the javadoc states
the O((S - S0)^3 x speed) error bound.

## Exotics & structured products (Q410-Q423)

### 410. "Decompose a digital option into vanillas. Why is hedging it near expiry a nightmare?"

A cash-or-nothing call paying 1 above K is the limit of a call spread:
`(C(K - h) - C(K + h)) / 2h` as h -> 0 -- which is minus the derivative
of the call price in strike, `-dC/dK = e^{-rT} N(d2)`. That
decomposition is also the hedge: a dealer short a digital buys a tight
call spread around K, sized 1/(2h) per unit width, and the residual
error is bounded by the spread width. The nightmare is the limit
itself: as expiry approaches with spot near K, the digital's delta is a
spike -- the price jumps from 0 to the discounted payout across an
ever-narrower band, so delta ~ n(d2)/(sigma sqrt(T) S) grows without
bound and gamma flips sign violently across the strike. No dynamic
hedge survives that: you would be trading enormous, oscillating spot
positions against a payoff that a one-tick move decides. The
professional answer is to never hedge a digital dynamically near the
strike: hedge it as the call spread from day one (over-hedging
conservatively by choosing the spread on the safe side), accept the
width as your pricing margin, and let pin risk be a position limit
question rather than a Greeks question. Vanillas decompose the other
way too: a vanilla call = asset-or-nothing minus K cash-or-nothing --
useful for reading structured payoffs.

*In this library:* `pricing/DigitalOption.java` -- cash-or-nothing
(discounted N(d2)) and asset-or-nothing (S e^{-qT} N(d1)) with the
javadoc showing the exact vanilla decomposition; sanity-check the call
spread convergence yourself with `pricing/BlackScholes.java`.

### 411. "Price a one-touch. What principle gives you the touch probability, and what edge case must the code pin?"

The reflection principle. For a driftless Brownian motion, every path
that touches an upper barrier H and ends below it has a mirror twin
ending above it, so P(touch) folds into normal CDFs of the terminal
distribution. With drift mu = b - sigma^2/2 and h = ln(H/S), the touch
probability by T becomes `N((mu T - h)/(sigma sqrt(T))) + e^{2 mu
h/sigma^2} N((-mu T - h)/(sigma sqrt(T)))` -- terminal-beyond-barrier
probability plus the drift-tilted reflected term -- and symmetrically
for a lower barrier. A one-touch paying at expiry is then just `payout
x e^{-rT} x P(touch)`; a no-touch is the complement, and desks quote
one-touches *as* the probability (a "35 percent one-touch"), which is
why exposing P itself matters. The edge case the code must pin: **spot
already at or through the barrier**. The formulas assume h has a
definite sign; at S = H the log is zero and naive evaluation returns
formula noise instead of certainty. The honest guard returns P = 1
exactly -- already touching -- making the one-touch worth its
discounted payout and the no-touch worth zero, with no branch cuts to
stumble over. (Pay-at-hit variants discount to the hitting time, not
expiry -- a different, slightly richer formula.)

*In this library:* `pricing/TouchOption.java` -- `touchProbability`
with the explicit `return 1; // already touching` pin, `oneTouch` /
no-touch as discounted probability and complement; javadoc notes the
pay-at-expiry vs pay-at-hit distinction.

### 412. "Explain in/out parity for barriers, and why a closed-form pricer should refuse reverse barriers."

In/out parity: holding a knock-in plus a knock-out with the same
barrier, strike and expiry replicates the vanilla -- every path either
touches the barrier (KI alive, KO dead) or does not (KO alive, KI
dead), so `KI + KO = vanilla` identically, model-free. That identity is
both a pricing shortcut (price the KI via Reiner-Rubinstein, get the KO
by subtraction) and the first test any barrier implementation must
pass. The regular/reverse distinction is about *where* the barrier sits
relative to the payoff. A regular barrier (e.g. down-and-out call with
H < K) knocks out where the option is nearly worthless anyway --
Greeks stay tame and the closed form is trustworthy. A **reverse**
barrier (up-and-out call with H > K) knocks out exactly where the
payoff is largest: value is a tent that collapses to zero at the
barrier, delta flips sign, and gamma explodes near H. The flat-vol
closed form still produces *a* number there, but it is the wrong number
in exactly the region that matters -- reverse barriers are acutely
smile-sensitive, and desks price them on local/stochastic vol with
barrier shifts. So the honest engineering choice is refusal: reject
reverse configurations with a clear exception rather than return a
plausible-looking price that mis-hedges.

*In this library:* `pricing/BarrierOption.java` -- Reiner-Rubinstein
knock-ins, knock-outs derived through in/out parity (`KO = vanilla -
KI` in the code), and explicit `IllegalArgumentException`s naming
reverse barriers (H > K calls, H < K puts) as unsupported in closed
form, with the reason.

### 413. "Why does hedging a barrier option blow up near the barrier, and what do desks do about it?"

Because the payoff has a cliff and Greeks are derivatives of cliffs. Take
a knocked-not-yet up-and-out call near its barrier: one tick below H the
option has value; at H it is zero, permanently. The value function is
continuous but its slope is violent -- delta is large and *opposite* in
sign to a vanilla's (the option loses value as spot rises toward H),
gamma is huge and negative, and both grow without bound as (time to
expiry) x (distance to barrier) shrinks. A delta hedger there is selling
size into a rising market, rebuying on every downtick -- maximal
transaction costs at maximal position size -- and a gap through the
barrier realizes the whole discontinuity in one unhedgeable jump. What
desks actually do: (1) **barrier shift** -- price and hedge as if the
barrier were moved conservatively away by an amount reflecting gap risk
and their own hedging impact, turning the cliff cost into an explicit
premium; (2) static replication -- match the barrier value profile with
a portfolio of vanillas (put spreads at the barrier) that needs no
rebalancing in the danger zone; (3) inventory limits per barrier level,
because ten notes with the same popular barrier concentrate a book-sized
cliff at one price. The lesson generalizes: discontinuous payoffs are
priced with models but *managed* with structure.

*In this library:* `pricing/BarrierOption.java` -- the javadoc's
regular/reverse discussion is exactly this failure mode (reverse
barriers rejected because the closed form is least trustworthy where
gamma explodes); `hedging/HedgingSimulator.java` shows the discrete
version of the cost/error blowup mechanics on plain options.

### 414. "Walk me through an autocallable: what is the client long, what is the dealer short, and where are the hidden risks?"

The classic single-underlier autocall: on each observation date, if spot
closes at or above the autocall barrier (say 100 percent of initial),
the note redeems early at par plus a fat coupon; below the coupon
barrier nothing is paid that period (with **memory** -- the Phoenix
feature -- missed coupons are paid retroactively at the next good
observation); if it limps to maturity, principal is protected *unless*
the knock-in barrier (say 60 percent) was breached, in which case the
holder takes the full equity loss. Economically the client owns a bond
plus a **sold down-and-in put**, with the put premium funding the
coupons -- yield manufactured from crash insurance written by the
client. Hidden risk one: **extension risk** -- the note's expected life
is short in calm markets (it calls at the first good observation) but
stretches toward full maturity exactly when the underlier has fallen,
so duration extends in the bad state. Hidden risk two: the dealer's
book -- hedging the client's sold KI put leaves dealers systematically
short downside vol and long skew-sensitive barriers; when spot falls
toward the knock-in zone, an entire street of dealers has the *same*
vega and gamma flips to hedge, which is why autocall inventories move
vol markets. Risk three: the KI put is deeply smile-sensitive, so a
flat-vol price understates it -- feed downside-strike vol at minimum.

*In this library:* `pricing/Autocallable.java` -- Monte Carlo pricer
with memory coupons, coupon/autocall/knock-in barriers, and a "model
honesty" javadoc naming flat-vol GBM a first pricer (feed downside vol
from `VolSurface` as the first-order smile correction), antithetic
variates, seeded reproducibility.

### 415. "Why is a variance swap model-free, how do you mark a seasoned one, and why is a vol swap different?"

The Demeterfi-Derman-Kamal-Zou result: the payoff `ln(S_T/S_0)` can be
replicated by a static strip of options weighted `1/K^2` (puts below the
forward, calls above), plus dynamic trading in the underlying -- so the
fair variance strike is readable off today's option chain with **no
model of vol at all**, only the smile. That is why variance is the
cleanest way to trade realized vs implied vol: no gamma-weighted path
dependence, no delta hedging by the client. Marking a seasoned swap is
disarmingly simple because variance is **additive in time**: after
fraction t/T has elapsed, the fair value per unit variance notional is
`(t/T) x (realized so far - strike) + (1 - t/T) x (fair strike for the
remainder - strike)`, discounted -- realized variance is locked in,
remaining variance is re-struck at today's chain. A vol swap cannot do
either trick: volatility is a square root, sqrt is concave, so
E[sqrt(V)] < sqrt(E[V]) (Jensen) -- the fair vol strike sits *below* the
square root of fair variance by a convexity adjustment (Brockhaus-Long:
roughly minus var(V)/(8 K_var^{3/2})) that depends on vol-of-vol, i.e.
on a model. Hence the desk folklore: variance swaps are model-free,
vol swaps are a vol-of-vol trade wearing a simple name. Caveats the
replication carries: discretization bias from a coarse strike grid and
truncation from finite wings -- and it prices *continuous* sampling
while contracts sample daily.

*In this library:* `pricing/VarianceSwap.java` -- `fairVariance` (the
1/K^2 strip), `markToMarket` (the additive seasoned mark),
`volSwapStrike` (the Brockhaus-Long convexity adjustment, with the
javadoc making the model-free-vs-not point), `varianceNotional` for
vega-notional conversion.

### 416. "Price an option to exchange one asset for another. What is the zero-vol insight?"

Margrabe: the option to receive asset 1 in exchange for asset 2 pays
`max(S1 - S2, 0)`, and the trick is to use asset 2 as **numeraire** --
measure everything in units of asset 2 and the payoff becomes a plain
call on the ratio S1/S2 struck at 1, with no interest rate anywhere
(the "cash" is asset 2, which earns its own drift). The ratio's
volatility comes from both assets and their correlation: `sigma^2 =
sigma1^2 + sigma2^2 - 2 rho sigma1 sigma2`, and the price is
Black-Scholes on the ratio with that sigma. Everything about the
instrument lives in that variance expression, which is where the
zero-vol insight sits: with **rho = 1 and sigma1 = sigma2**, the ratio
has exactly zero volatility -- two perfectly correlated, equally
volatile assets never cross, so the exchange option is worth precisely
its intrinsic value, `max(S1 - S2, 0)` (spot-measure discounted). That
limit is the fastest sanity test of an implementation and the cleanest
intuition for why exchange options are really **correlation
instruments**: vega in rho is first-class, and a dealer selling
stock-vs-index switch options is short the pair de-correlating.
Real uses: stock-for-stock merger arbitrage optionality, outperformance
notes, and switching options between two commodity grades.

*In this library:* `pricing/ExchangeOption.java` -- Margrabe with the
combined-variance line explicit, and the javadoc calling out the rho =
1, sigma1 = sigma2 zero-vol edge as the case worth knowing.

### 417. "Why is there no exact formula for a spread option with nonzero strike, and what does Kirk's approximation get wrong?"

Margrabe works because `max(S1 - S2, 0)` is homogeneous -- divide by S2
and it is a call on a lognormal ratio. Add a cash strike, `max(F1 - F2 -
K, 0)`, and the trick dies: the sum of a lognormal and a constant
(F2 + K) is **not lognormal**, so no change of numeraire produces a
Black-Scholes problem, and the exact price is a two-dimensional integral
with no closed form. Kirk (1995) approximates F2 + K as a *single*
lognormal asset with volatility scaled by the weight `f = F2/(F2 + K)`:
effective variance `sigma1^2 - 2 rho sigma1 sigma2 f + sigma2^2 f^2`,
then applies Margrabe machinery. It is excellent when K is small
relative to F2 (the near-exchange regime -- crack spreads, calendar
spreads with modest strikes) and degrades as K grows or as the spread
distribution's skew starts to matter: the lognormal-plus-constant has
different tail behavior than a lognormal, so Kirk misprices the wings
and, worse, its Greeks (especially the correlation sensitivity) drift
from truth exactly where spread desks live -- low or negative spreads.
Practical hygiene: cross-check Kirk against a 2D quadrature or Monte
Carlo at your actual (K, rho, vol) point before trusting its risk, and
never use it for spreads that can go deeply negative (where even the
payoff geometry stops resembling the approximation).

*In this library:* `pricing/ExchangeOption.java` -- `kirkSpreadCall`
with the f = F2/(F2 + K) weighting visible in the variance line, next
to the exact Margrabe case it collapses to at K = 0; the javadoc states
the no-closed-form reason (a spread is not lognormal).

### 418. "A quanto pays a foreign asset's return in your currency at a fixed rate. Where does the drift correction come from, and which sign?"

From the covariance between the asset and the FX rate you are ignoring.
Hedging a quanto forces the dealer to hold the foreign asset and
constantly rebalance an FX hedge whose size tracks the asset's value;
when asset and FX are correlated, that rebalancing has systematic
expected cost, and it shows up as a **drift correction**: the asset's
risk-neutral drift in the quanto measure is shifted by `- rho x sigma_S
x sigma_FX`, while the volatility that prices the payoff stays the
asset's own sigma_S -- only the drift moves. The sign exercise: let rho
be the correlation between the asset and the value of the *foreign
currency in domestic terms*. If rho > 0 (asset rallies when the foreign
currency strengthens), the unhedged foreign investor would have enjoyed
a double win; the quanto strips that, so the quanto forward sits
*below* the plain forward -- negative correction. If rho < 0, the
quanto protects you from the usual FX drag on good asset days, which is
worth paying for -- the drift correction is positive. Classic example:
Nikkei quantoed into USD, where equity-yen correlation makes the
correction economically visible in the forward. Implementation honesty:
a quanto pricer is a change of drift feeding a standard pricer -- if
your code does anything to sigma, it is wrong.

*In this library:* `pricing/QuantoOption.java` -- the javadoc derives
the `- rho sigma_S sigma_FX` correction, walks the sign intuition
explicitly, and the implementation is deliberately "honest about being
a change of drift": adjust the carry, delegate the pricing.

### 419. "How do you price American options on a tree, and what low-vol edge case must the implementation catch?"

Cox-Ross-Rubinstein: discretize time into n steps, let spot move up by
`u = e^{sigma sqrt(dt)}` or down by d = 1/u, with risk-neutral up
probability `p = (e^{b dt} - d)/(u - d)`. Roll back from the terminal
payoffs, discounting each node; for American style, replace each node's
continuation value with `max(continuation, intrinsic)` -- that single
max IS early exercise, and (American - European) from the same tree is
the early-exercise premium, cleanly isolated. Why trees for American:
the exercise decision is a free boundary, closed forms do not exist
(the perpetual case aside), and the tree solves the dynamic program
directly with O(n^2) work and O(1/n) convergence. The edge case: p must
lie in (0, 1) to be a probability. When volatility is very low relative
to carry, `e^{b dt}` can escape the [d, u] interval -- the drift
outruns the lattice spacing -- and p goes negative or above 1. The tree
is then **degenerate**: it will still produce a number, computed with
negative "probabilities," which is arbitrage-in-the-code. The honest
implementation checks p and throws with the diagnosis (increase steps
so sqrt(dt) shrinks relative to b dt, or question the inputs) rather
than returning nonsense. Same family of honesty as the zero-vol
Black-Scholes guard: lattice methods have validity conditions, and
production code states them.

*In this library:* `pricing/BinomialTree.java` -- CRR with
EUROPEAN/AMERICAN styles, an `earlyExercisePremium` helper, and an
explicit degenerate-tree exception ("increase steps or check inputs")
when p leaves (0, 1).

### 420. "In SABR, what do rho and nu each do to the smile? Why do desks fix beta?"

SABR models the forward with CEV-type backbone `F^beta` and a lognormal
stochastic vol: alpha sets the overall vol level (it pins the ATM
point), **rho** -- the correlation between the forward and its vol --
controls the smile's *tilt*: negative rho means vol rises as the
forward falls, producing the downward skew every equity and rates
market shows; **nu** (vol-of-vol) controls the *curvature*: higher nu
fattens both wings symmetrically, lifting strangles relative to the
ATM. You can read this straight from Hagan's expansion: rho enters the
first-order term in log(F/K) (the slope), nu^2 enters the symmetric
second-order term (the smile), and the `(2 - 3 rho^2) nu^2 / 24`
correction shifts the ATM level itself. Beta also bends the smile
(backbone skew), which is exactly why desks **fix it by convention**
rather than fit it: beta and rho produce nearly the same skew signature
over a normal strike range, so fitting both is an identifiability
swamp -- calibrations wander, Greeks jump day to day. Fix beta (0.5 in
rates by tradition, ~1 in FX/equity), then (alpha, rho, nu) calibrate
stably to three features -- level, slope, curvature -- of each expiry's
smile, giving a parametric, arbitrage-aware smile from pillar quotes
plus meaningful, comparable parameters across days.

*In this library:* `pricing/SabrModel.java` -- Hagan's implied-vol
approximation with every term above visible in the code, `Params`
carrying (alpha, beta, rho, nu, rmse), and calibration with beta fixed
by the caller, fitting pillar smiles a la `VolSurface`.

### 421. "Explain vanna-volga pricing. What exactness property makes it usable, and what are its limits?"

The FX desk's smile method: given exactly **three market pillars** --
in practice the 25-delta put, the ATM, and the 25-delta call -- price
any instrument as its Black-Scholes value at the ATM vol *plus the
market cost of the smile risk it carries*. Mechanically: compute the
instrument's vega, vanna and volga; find the portfolio of the three
pillar options that matches those three exposures (a 3x3 linear solve);
the smile adjustment is what the market charges for that portfolio over
its flat-vol value -- i.e., you pay for vanna and volga at the prices
the three pillars imply. The property that makes it usable is
**exactness at the pillars**: ask it for a pillar strike and the
construction returns that pillar's own market vol by design, so the
method interpolates the smile through the quotes rather than merely
near them, and pricing a quoted instrument reproduces its quote --
the minimal consistency a desk demands. Read the smile out by
converting adjusted prices back through Black-Scholes and you get a
full strike-continuous smile from three quotes. Limits, honestly: it is
an interpolation dressed as a model -- extrapolation into far wings is
unreliable, there is no guarantee of no-arbitrage (butterflies can go
negative outside the pillar span), no dynamics (so it prices
path-dependent exotics only via ad hoc weightings), and with three
pillars it cannot express smiles with more than three features.

*In this library:* `pricing/VannaVolga.java` -- three ascending pillars
enforced at construction (25d put / ATM / 25d call), the
vega/vanna/volga replication against the pillars, exactness at pillar
strikes stated and delivered, smile read-back through Black-Scholes.

### 422. "You implemented Heston. What is the 'little trap', and how do you know your integral is right?"

Heston prices via an integral over the characteristic function of
log-spot, and the characteristic function contains a complex logarithm.
The original 1993 formulation picks a branch that forces the log's
argument to wind around the origin as the integration variable u grows
-- naive principal-branch evaluation then produces **discontinuities in
the integrand**, silently wrong prices, and the failure concentrates at
long maturities and high vol-of-vol, exactly where markets calibrate.
The "little Heston trap" (Albrecher et al.) is the fix: an
algebraically equivalent form of the CF whose log argument -- the ratio
g2 = (b - i rho sigma u - d)/(b - i rho sigma u + d) -- stays away from
the negative real axis, so the principal branch is valid for all u and
the integrand is smooth. No approximation, just the numerically honest
branch. How you know the integral is right: **cross-check against a
method with independent failure modes.** A full-truncation Euler Monte
Carlo shares no code path, no branch choices and no quadrature with the
CF integral; when a semi-analytic price and an MC price with its
standard error agree across the parameter grid (including the
long-T/high-nu corner where the trap bites), each validates the other.
Also test the Feller-violating region (2 kappa theta < sigma^2), where
variance paths pile up at zero -- full truncation handles it, and
agreement there is the strongest evidence.

*In this library:* `pricing/Heston.java` -- little-trap characteristic
function with the g2 ratio and a comment noting the principal branch is
valid *only* for that form, plus `callMonteCarlo` (full-truncation
Euler) documented as the pricing cross-check.

### 423. "How does an RFQ auction work in equity derivatives, and why does everyone record the cover price?"

Structured products and large delta-one blocks do not trade on a
screen: the client sends a request-for-quote to a panel of dealers,
collects binding quotes for a response window, and deals on the best.
The **cover** is the second-best price, and it is the single most
informative number in the auction: winner minus cover is exactly what
the winner "left on the table" -- how much better their price was than
necessary. Dealers obsess over it because it calibrates quoting
aggression: winning by a wide cover margin means you are systematically
overpaying for flow (tighten next time); never winning means you are
too wide. Clients and platforms record it as auction quality evidence
-- a thin best-to-cover spread means the panel is genuinely competing.
Aggregated over many auctions, per-dealer statistics -- hit rate,
average distance to best, cover-margin when winning -- become a
**dealer scorecard** that drives panel selection: invite the dealers
who price competitively on this product type, drop the ones who quote
wide and never trade. The other anchor worth carrying into every
auction is your own model fair value (an autocallable RFQ should be
compared against your Monte Carlo price, not just against the panel):
winner-versus-fair in basis points tells you whether the *whole panel*
was off, which the cover alone cannot.

*In this library:* `rfq/RfqAuction.java` -- one auction: quotes in,
`winner`, cover tracking, and `winnerSpreadToFairBps` anchored on a
model fair value (the javadoc points at `pricing.Autocallable.price`);
`rfq/RfqDealerScorecard.java` -- EWMA per-dealer stats across auctions,
the dealer-selection input.

## Volatility modeling (Q424-Q435)

### 424. "EWMA vs GARCH(1,1): same recursion, different soul. What does GARCH add and when does it matter?"

Write both: EWMA is `h_t = lambda h_{t-1} + (1 - lambda) r_{t-1}^2`;
GARCH(1,1) is `h_t = omega + alpha r_{t-1}^2 + beta h_{t-1}`. EWMA is
the special case omega = 0, alpha + beta = 1 -- an integrated process
with **no unconditional variance and no mean reversion**: its k-step
forecast is flat at today's estimate forever, and a vol spike never
decays in expectation. GARCH's omega > 0 with alpha + beta < 1 buys the
two things EWMA cannot express: a long-run anchor `omega / (1 - alpha -
beta)` and geometric decay toward it -- `h_{t+k} - h_inf = (alpha +
beta)^k (h_t - h_inf)` -- so a post-shock forecast relaxes at a fitted
speed instead of freezing. When does the difference matter? Horizon.
For 1-day risk (RiskMetrics-style VaR), lambda ~ 0.94 EWMA and a fitted
GARCH give nearly identical numbers, and EWMA wins on simplicity, no
estimation risk, and streaming cost. For anything multi-day -- 10-day
regulatory VaR, option-horizon realized forecasts, margin models -- the
term structure IS the forecast, and EWMA's flat line is simply wrong
after a spike: it overstates vol for weeks. Typical fitted persistence
on daily equity data is alpha + beta ~ 0.98, so shocks take months to
fade -- volatility clustering, quantified in one number.

*In this library:* `volatility/EwmaVolatility.java` (the RiskMetrics
recursion, lambda validated in (0,1)), `volatility/Garch11.java` (MLE
fit plus the geometric multi-step forecast) -- run both on the same
series after a vol spike and compare the 10-day paths.

### 425. "What does GJR-GARCH add over GARCH, and what does the fitted gamma-versus-alpha comparison tell you?"

One indicator. GJR-GARCH(1,1) is `h_t = omega + alpha r_{t-1}^2 + gamma
r_{t-1}^2 1(r_{t-1} < 0) + beta h_{t-1}` -- the gamma term fires only
on negative returns, so a down move raises next-period variance by
(alpha + gamma) times the squared shock while an equal up move raises
it by only alpha. That is the **leverage effect** made estimable:
falling equity prices raise volatility more than rallies do (mechanical
leverage, plus risk-aversion feedback). The comparison worth
internalizing: on equity indices the fitted gamma is typically
**larger than alpha** -- often alpha ~ 0.02-0.05 versus gamma ~
0.08-0.12 -- meaning asymmetry is not a small correction to a symmetric
model; it is *most of the news response*. Symmetric GARCH on an equity
index is mis-specified in the direction that matters for risk: it
under-reacts to selloffs (understating VaR into a drawdown) and
over-reacts to rallies. The cross-asset control makes the story
convincing: on FX pairs, where "down" has no economic meaning (every
down move in one currency is an up move in the other), fitted gamma ~
0 -- the asymmetry is an equity phenomenon, not an artifact of the
estimator. Persistence bookkeeping changes too: with symmetric shock
distribution the persistence is alpha + gamma/2 + beta, which is what
the multi-step forecast must decay with.

*In this library:* `volatility/GjrGarch11.java` -- the indicator term,
MLE fit, and a javadoc that makes exactly the gamma-versus-alpha point
(asymmetry as most of the effect on equities, ~0 on FX).

### 426. "Why does EGARCH model log-variance, and why would a library refuse to give you its multi-step forecast?"

EGARCH(1,1) writes the recursion on `ln(h_t)`: log-variance is
unconstrained, so no positivity constraints on parameters are needed
(alpha and gamma roam free where GARCH's must be boxed), and the
leverage effect enters as a clean **sign**: gamma < 0 means negative
returns raise variance -- read the asymmetry straight off the
coefficient. The cost hides in the log. To forecast k steps ahead you
need E[h_{t+k}] = E[exp(ln h_{t+k})], but iterating the log recursion
gives you E[ln h_{t+k}], and by Jensen's inequality `exp(E[ln h]) <
E[h]` -- exponentiating the iterated log-forecast yields the **median**
of the variance distribution, not its mean, and the gap grows with
horizon and with vol-of-vol. Quietly returning that number as "the
forecast" is a systematic *downward-biased* risk forecast wearing an
authoritative method name. The honest library choices are: refuse --
offer one-step (where the recursion is exact) and direct multi-step
users to GARCH/GJR, whose linear-in-variance recursions forecast the
mean exactly; or implement the correct simulation/moment-correction
machinery and say so. Refusal with a documented reason beats silent
bias: the caller who needed a 10-day number learns *why* the model
cannot cheaply give it, instead of shipping an understated VaR.

*In this library:* `volatility/Egarch11.java` -- log-variance
recursion, gamma-as-sign leverage, and multi-step forecasts
deliberately NOT offered, the javadoc naming the median-vs-mean Jensen
problem and pointing multi-step users to `Garch11`/`GjrGarch11`.

### 427. "Explain HAR-RV. Why does a three-term linear regression beat GARCH on realized-vol data?"

Corsi's Heterogeneous AutoRegressive model forecasts tomorrow's
realized variance as a linear function of three of its own averages:
`RV_{t+1} = c + b_d RV_t + b_w RV_t^{(5)} + b_m RV_t^{(22)}` -- the
daily value, the trailing weekly (5-day) mean, and the trailing monthly
(22-day) mean. Fit by OLS, forecast by plugging in: no likelihood, no
constraints, no nonlinear optimizer. The "heterogeneous" story is the
**volatility cascade**: markets contain traders on different horizons
-- intraday/daily players, weekly swing accounts, monthly allocators --
and each horizon's activity feeds volatility at the horizons below it,
so the three averages proxy three persistence timescales at once. A
single GARCH decay is one geometric memory; the HAR sum of three
horizons mimics the *long memory* (slow hyperbolic-ish decay of vol
autocorrelation) that realized vol actually exhibits, which one
exponential cannot. And the input data is better: realized variance
from intraday returns is a far less noisy measurement of a day's
variance than one squared daily return (GARCH's food), so the
regression sees signal where GARCH sees noise. Result, robust across
dozens of studies: HAR-RV matches or beats far fancier models
out-of-sample at daily-to-monthly horizons. Practical care: feed it
genuine daily realized variance (intraday squared returns summed) and
give the fit enough history for the monthly window plus a margin.

*In this library:* `volatility/HarRv.java` -- OLS fit returning
(intercept, betaDaily, betaWeekly, betaMonthly), the daily/weekly/
monthly construction, and a javadoc insisting on realized daily
variance as input; combine with intraday RV built from
`microstructure/JumpRobustVolatility.java`.

### 428. "Implied vol is usually above subsequent realized. Is that a free lunch?"

The gap is the **volatility risk premium**, and it is compensation, not
a lunch. Index implied vol (and variance-swap strikes) systematically
exceed subsequently realized vol -- on major equity indices the average
gap is a few vol points, positive in most months. It exists because
short-vol positions lose exactly when everything else does: the buyer
of index options holds crash insurance, and insurance carries premium
above actuarial cost. Selling it means collecting a steady spread and
occasionally handing back years of income in one event -- the P&L is
short a deep tail, with brutal skew (see any short-vol product in Feb
2018 or Mar 2020). How to measure it honestly: compare a *tradable*
implied level -- the variance-swap strike or a VIX-style model-free
index, not a single ATM vol -- against realized variance over the same
window with matching sampling conventions; the variance-swap
mark-to-market IS the running P&L of that trade. Two classic mistakes:
comparing implied variance to realized *vol* (units), and letting the
1/K^2 strip's wing truncation understate the implied leg. The
defensible statement: harvesting the premium is a legitimate risk
business when sized to the tail (small, hedged, diversified), and a
blowup pattern when sized to the carry.

*In this library:* `volatility/VolatilityIndex.java` (the model-free
implied leg), `pricing/VarianceSwap.java` -- `fairVariance` versus
realized in `markToMarket` is exactly the premium's running score;
realized measurement done properly via `volatility/HarRv.java` inputs.

### 429. "One jump wrecks your realized-vol estimate. How does bipower variation separate jumps from diffusion?"

A squared-return estimator charges the full jump to variance: one
8-sigma print enters as its square and sits in the estimator's memory
for its whole window -- your "volatility" is then mostly one event.
Bipower variation (Barndorff-Nielsen & Shephard) replaces squares with
**products of adjacent absolute returns**: sum of |r_i| |r_{i-1}|
(scaled by pi/2). For a continuous diffusion this estimates the same
integrated sigma^2 as the squared sum, but a single jump lands in only
*one* factor of each of two adjacent products, multiplied by ordinary-
sized neighbors -- its contribution is dampened toward zero as sampling
gets finer instead of entering squared. So run both estimators
side by side: the squared-return (realized variance) leg measures
total variation, the bipower leg measures the diffusion part, and the
difference -- the **jump fraction**, max(0, 1 - BV/RV) -- attributes a
share of raw variance to jumps. Why you want the separation: the
diffusive part is the persistent, forecastable component (feed it to
HAR-RV; jumps barely predict future vol), position sizing off
jump-inflated vol whipsaws you (de-lever after the jump, re-lever too
late), and the jump fraction itself is a signal -- a regime where
variance is jump-dominated calls for different risk limits than a
smoothly turbulent one. Streaming caveat: after a data gap, adjacency
is broken -- the first return after a gap must update only the raw
leg, never the bipower product.

*In this library:* `microstructure/JumpRobustVolatility.java` --
streaming raw and bipower legs, `volPerSqrtSecond` (jump-robust),
`jumpFraction`, and the documented gap rule (post-gap return updates
only the raw estimator).

### 430. "Build a VIX-style index from an option chain. Where does discretization bias creep in, and what is the K0 correction for?"

The construction is the variance-swap strip made official: variance =
`(2 e^{rT} / T) x sum over strikes of (dK_i / K_i^2) x mid_i - (1/T)
(F/K0 - 1)^2`, using out-of-the-money puts below the forward, OTM calls
above, and the put/call average at the pivot. Every piece is a
discretization decision. The strip approximates a continuous 1/K^2
integral with a finite sum -- coarse strike spacing biases the level;
finite wings truncate the tails -- strikes should span several
sigma sqrt(T) or the index reads structurally low, precisely in calm
markets where listed wings are sparse. The `e^{+rT}` factor
forward-values the option mids (the CBOE convention). And the pivot:
K0 is defined as the **highest listed strike at or below the forward**
-- the forward almost never lands exactly on a strike, so the strip
switches from puts to calls at K0, not at F, and the segment between K0
and F is covered by the wrong option type. The final term, `-(1/T)
(F/K0 - 1)^2`, is exactly the correction for that mismatch -- it
converts the K0-pivot strip into the F-pivot integral to second order.
The lesson in that term: get the F-versus-K0 handling wrong (pivot at
the nearest strike, or drop the correction) and the index carries a
small systematic bias that moves with where the forward sits inside
the strike grid -- invisible day to day, visible in any careful
replication test. Also enforce F strictly inside the listed strike
range, else the construction is undefined and should throw.

*In this library:* `volatility/VolatilityIndex.java` -- the CBOE-style
construction: OTM strips, put/call average at K0, e^{rT}
forward-valuing, the (F/K0 - 1)^2 correction term in the code, an
explicit throw when F is outside the strike range, and javadoc on
truncation bias.

### 431. "Split a stock's volatility into systematic and idiosyncratic. Why is the decomposition exact, and what is R-squared here?"

Regress the asset's returns on the market's: `r_asset = a + beta
r_mkt + eps`. Then `var(asset) = beta^2 var(mkt) + var(eps)` --
systematic variance plus idiosyncratic variance -- and with OLS this is
an **identity, not an approximation**: OLS chooses beta precisely so
that the residuals are uncorrelated with the regressor (the normal
equations), so the cross term 2 beta cov(mkt, eps) is exactly zero in
sample and the two pieces add to the total to machine precision. That
exactness is a free unit test: decompose, re-add, assert equality.
R-squared is then the **systematic share** -- the fraction of the
asset's variance that is market variance in disguise: a utility at 0.7
is mostly a market position wearing a ticker; a biotech at 0.15 is
mostly its own story. Why desks compute it: idiosyncratic vol is what
single-name option desks are actually pricing after the index hedge
(the index option carries the systematic part); it is the denominator
in dispersion trades (index vol versus average single-name vol); risk
models cap idiosyncratic concentration per name; and the low-idio-vol
anomaly literature keys on exactly this split. Bookkeeping: variances
here are per-period -- annualize variances with x periodsPerYear and
vols with the square root; and a flat benchmark decomposes nothing, so
validate var(mkt) > 0.

*In this library:* `volatility/VolatilityDecomposition.java` --
`decompose` returning (beta, totalVariance, systematic, idiosyncratic,
R-squared), the javadoc proving the exact-identity point from OLS
orthogonality and flagging the annualization convention.

### 432. "Intraday volatility is U-shaped. Why, and what breaks if your systems ignore it?"

An equity day opens wild (overnight information reprices at once,
auction imbalances resolve, spreads are widest), goes quiet through
lunch, and re-accelerates into the close (index rebalancing, MOC flow,
hedgers finishing their day) -- per-second volatility can differ by a
factor of several between 9:35 and 12:30. Ignore that and a whole floor
of systems mis-fires in the same direction. A vol-scaled alert or
risk gate calibrated on the full-day average fires spuriously every
open and sleeps through genuine lunch anomalies. An execution algo
using flat vol mis-sizes its urgency: Almgren-Chriss-style schedules
trade too fast at the open (paying impact into thick vol) and too slow
at the close. Realized-vol estimators mix buckets and hand a biased
number to anything downstream. And a market maker quoting flat-vol
spreads is picked off at the open, uncompetitive at noon. The fix is a
learned **intraday baseline**: bucket the session into time-of-day
slots, accumulate each bucket's observed vol, and blend across days
with an EWMA so the curve adapts to regime changes without chasing one
day's noise; live systems then work in *deseasonalized* units --
current vol divided by the bucket baseline -- so "2x normal" means
2x normal for 9:35, not 2x the daily average. It is the third leg of
the seasonality trio alongside the intraday volume curve and
day-type effects.

*In this library:* `microstructure/VolatilityCurve.java` -- per-bucket
session accumulation, `rollDay` folding sessions into an EWMA baseline
(per sqrt-second units), the U-shape named in the javadoc; siblings
`microstructure/VolumeCurve.java` and `DayTypeProfiles.java`.

### 433. "How do you detect volatility regimes, and what would you actually do with the answer?"

Fit a two-state Gaussian hidden Markov model to returns: each state has
its own mean and variance, and a Markov chain switches between them.
Baum-Welch EM (with forward-backward scaling so long series do not
underflow) learns the two states and the transition matrix; the
filtered/smoothed state probabilities then say, day by day, "calm" or
"turbulent" with a probability attached. Two conventions make the
output usable: pin state 1 as the high-volatility regime (otherwise
label switching makes runs incomparable), and read the transition
matrix's diagonal as regime *stickiness* -- 0.98 self-transition means
the turbulent state lasts ~50 days on average, which is the number
that separates a regime model from a threshold on rolling vol. What
you do with it: **volatility targeting** -- de-lever when P(turbulent)
rises (turbulent regimes carry higher vol AND typically worse mean
returns, so the sizing signal is doubly aligned); switching strategy
parameters (wider stops, wider market-making spreads, slower execution
in turbulence); and gating other signals -- many alphas earn in one
regime and pay in the other, and conditioning on the regime is the
cheapest ensemble there is. Honesty items: two states is a modeling
choice, EM finds local optima (seed it sensibly), and the filtered
probability lags a true regime break by construction -- it is a
smoother of evidence, not a crystal ball.

*In this library:* `ml/RegimeDetector.java` -- two-state Gaussian
Markov-switching fit by Baum-Welch with forward-backward scaling,
state 1 fixed as high-vol, javadoc pointing at vol targeting as the
natural consumer.

### 434. "You stream a covariance matrix with EWMA updates. Why must a sample with one bad symbol be dropped whole?"

The streaming update is `cov <- lambda cov + (1 - lambda) r r^T` --
each sample folds in as a **rank-1 outer product** of the whole return
vector. That structure is what keeps the matrix positive-semidefinite
forever: a convex combination of a PSD matrix and a rank-1 outer
product (which is PSD by construction) is PSD, by induction from a PSD
seed. Now let one symbol print garbage -- a NaN, an infinite return
from a crossed feed. The tempting fix is to update only the clean
pairs and skip entries touching the bad symbol. But a partial update is
**no longer an outer product**: you have added a matrix that is the
outer product with a row and column carved out, which is not PSD in
general, and the invariant silently dies. A non-PSD "covariance" has
negative eigenvalues -- portfolio variances can come out negative,
Cholesky factorization fails, optimizers and risk parity solvers
produce nonsense -- and worse, partial updates skew correlations
between clean symbols and the dirty one (their entries age at
different effective rates than the variances, so implied correlations
drift out of [-1, 1]). The disciplined rule is therefore all-or-
nothing: validate the entire vector; if ANY entry is non-finite, drop
the whole sample for that interval and keep the previous matrix. One
symbol's bad print must never corrupt the matrix everyone else shares.
Cheap and worth it: an occasional dropped interval is noise to an
EWMA; a broken invariant is an incident.

*In this library:* `microstructure/EwmaCovariance.java` -- full-vector
rank-1 updates with the documented whole-vector drop on any non-finite
entry, the javadoc spelling out the PSD-by-induction argument and the
correlation-skew hazard of partial updates.

### 435. "You have EWMA, GARCH, HAR-RV, implied surfaces, bipower. Which volatility do you use for which job?"

Match the estimator's horizon, information set and failure mode to the
job. **Pricing and hedging options: implied, always** -- the smile is
the market's price of vol and hedging happens at market prices; using
a statistical forecast to mark options is marking to your own opinion.
Pull it from the surface at the right strike/expiry (downside strikes
for knock-in puts). **Short-horizon risk (1-day VaR, margin calls,
position limits): EWMA or GARCH on returns** -- reactive, cheap,
streaming; EWMA for 1-day, GARCH/GJR when the multi-day term structure
matters (and GJR on equities, where asymmetry is most of the response).
**Realized-vol forecasting for signals and vol targeting: HAR-RV on
intraday realized variance** -- the better-measured input and the
cascade memory beat daily-return GARCH at 1-22 day horizons; feed it
the **bipower** leg so one jump does not poison three weeks of
forecasts. **Intraday systems (alerts, execution urgency, quoting):
deseasonalized streaming vol** -- current jump-robust vol divided by
the time-of-day baseline. **Vol as a tradable view: implied-versus-
realized** -- variance-swap strike or a model-free index against
forecast realized -- because that is the pair the P&L actually settles
on. The anti-pattern is one blessed "volatility" number flowing to
every consumer: the pricing desk, the risk system and the signal stack
are asking three different questions, and the honest answer differs.

*In this library:* `pricing/VolSurface.java` (pricing),
`volatility/EwmaVolatility.java` / `Garch11.java` / `GjrGarch11.java`
(risk), `volatility/HarRv.java` +
`microstructure/JumpRobustVolatility.java` (forecasting),
`microstructure/VolatilityCurve.java` (intraday),
`volatility/VolatilityIndex.java` + `pricing/VarianceSwap.java` (the
tradable comparison).

## Hedging in practice (Q436-Q447)

### 436. "You can't hedge continuously. What does discrete delta hedging actually cost, and what does the error look like?"

Black-Scholes replication is exact only in the continuous limit; hedge
at discrete times and the residual P&L is a random variable, not zero.
Between rebalances you are running unhedged gamma: the per-step error
is approximately `0.5 Gamma S^2 (dS/S)^2 - `(the theta you accrued) --
zero in expectation if realized vol equals implied, but noisy. The
classic result: with n evenly spaced rebalances the hedging-error
standard deviation shrinks like **1/sqrt(n)** -- quadruple your trading
to halve your noise -- and the error distribution for a short option is
left-skewed (occasional large losses when a big move lands between
rebalances). Add transaction costs and the trade-off closes in from
the other side: each rebalance pays spread proportional to the delta
traded, so total cost *grows* with n while error shrinks -- there is an
interior optimum, and "hedge every tick" is bankruptcy by a thousand
spreads. The honest way to see all this is simulation, not formulas:
run the hedger over thousands of GBM paths, collect final P&L per
path, and look at the whole distribution -- mean (cost drag), standard
deviation (replication risk), skew (the gap-risk signature), and how
each responds to the rebalance band and the cost assumption. That
distribution, not a single Greek, is what a desk's hedging policy is
actually choosing.

*In this library:* `hedging/DeltaHedger.java` (path-level ledger:
rebalance count, costs, final P&L = replication error; delta band and
per-trade bps configurable), `hedging/HedgingSimulator.java` (parallel
Monte Carlo across thousands of paths producing the full
`HedgingErrorDistribution` -- vary band and frequency and watch
1/sqrt(n) appear).

### 437. "How wide should your delta no-trade band be? Derive the shape of the answer."

Whalley-Wilmott answered it with asymptotic stochastic control: for
proportional transaction costs k and risk aversion lambda, the optimal
no-trade band around the Black-Scholes delta is `band = ((3/2) k S
Gamma^2 / lambda)^{1/3}`. The structure is readable: more gamma means
delta churns faster, so the band widens with Gamma^2 (do not chase a
delta that will chase itself back); higher costs widen it (trade
less when trading is expensive); higher risk aversion narrows it. The
**cube root is the interesting part**: costs must rise 8x to double
the band -- band width is remarkably insensitive to the cost estimate,
which is why a roughly-calibrated band captures most of the available
improvement and why agonizing over the exact k is wasted effort. The
policy detail that people get wrong: when delta drifts outside the
band, trade back to the **nearest edge, not to the center**. Hedging
to the mid throws away the band's entire logic -- you pay the maximum
trade size and immediately restart the drift from the middle; hedging
to the edge trades the minimum quantity that restores compliance and
lets the band absorb the next drift. Compare against a fixed-width
band in simulation: at equal risk, the WW band with edge-hedging
spends visibly less in costs, and the gap widens exactly where gamma
is large -- short-dated, near-the-money books.

*In this library:* `hedging/WhalleyWilmott.java` -- the cube-root band
formula with the javadoc making both the 8x-to-2x insensitivity point
and the hedge-to-nearest-edge policy argument; plug it into
`hedging/DeltaHedger.java` / `HedgingSimulator.java` to score it
against naive bands.

### 438. "You run an options book with residual delta, gamma and vega. How do you flatten all three at once?"

As a linear solve, because Greeks are (locally) linear in position
sizes. Each hedge instrument contributes its per-unit Greeks: the
underlying is (delta 1, gamma 0, vega 0); each hedge option contributes
its own (delta, gamma, vega). Stack the instruments' Greek vectors as
columns of a matrix A, let g be the book's residual Greek vector, and
solve `A x = -g`: x is the quantity of each instrument that neutralizes
everything simultaneously. The classic recipes fall out as small cases:
delta-gamma hedging is a 2x2 (one option kills gamma, then the
underlying -- gamma-free -- cleans up the delta it introduced;
sequencing matters, which the simultaneous solve handles for free);
delta-gamma-vega is a 3x3 needing two distinct options with genuinely
different gamma/vega ratios -- pick two options too similar (same
expiry, near strikes) and the matrix is near-singular: the solver
returns enormous offsetting positions, which is the algebra telling you
those instruments do not span the risk. Practicalities: the hedge is
local -- re-solve as spot, vol and time move the book's Greeks;
validate afterward against full revaluation, not just the linear
Greeks, via a spot-x-vol scenario grid; and run a P&L explain
(delta/gamma/vega/theta terms versus actual reval) so the *unexplained*
residual tells you when the linear hedge frame itself is breaking --
large unexplained is vanna/volga/higher-order territory.

*In this library:* `hedging/GreekHedger.java` -- Instrument records
(underlying = delta 1, gamma 0, vega 0), delta-gamma and
delta-gamma-vega recipes plus the general linear solver;
`hedging/OptionsBook.java` -- `scenarioGrid` (full-revaluation spot x
vol grid) and `pnlExplain` (delta/gamma/vega/theta attribution with the
unexplained remainder made explicit).

### 439. "Derive the minimum-variance hedge ratio, and tell me what 'hedge effectiveness' means before you trade it."

Hedge a position in asset A with h units short of hedge instrument B:
portfolio P&L per period is `r_A - h r_B`, with variance `var(A) - 2h
cov(A,B) + h^2 var(B)`. Minimize over h: differentiate, set to zero,
and `h* = cov(A, B) / var(B)` -- which is exactly the OLS slope of A's
returns on B's, so "run the regression" and "compute the
minimum-variance hedge" are the same act. Substituting h* back, the
residual variance is `var(A)(1 - rho^2)`: the fraction of variance the
hedge removes is **rho^2** -- correlation squared, which is also the
regression R-squared. That number is the go/no-go: hedge effectiveness
of rho^2 = 0.8+ is the standard bar (and the hedge-accounting
threshold); at rho = 0.7 you remove only half the variance while paying
full transaction costs and taking on basis risk -- often not worth it.
Concrete example: hedging a jet-fuel exposure with heating-oil futures
-- no jet-fuel future trades in size, HO correlates at maybe 0.9, so
h* comes from the regression (not 1.0 -- the naive one-to-one hedge
over- or under-hedges by the beta), and effectiveness ~0.81 says a
fifth of the variance stays as basis risk you must still carry limits
for. Honesty items: h* is sample-dependent (estimate on returns, not
prices; watch the window), and correlation is regime-dependent --
effectiveness measured in calm markets overstates crisis performance.

*In this library:* `hedging/MinimumVarianceHedge.java` -- `h* =
cov/var` and hedge effectiveness (= rho^2, javadoc citing the 80
percent test) as the two first-class outputs.

### 440. "Your book holds assets in five currencies. How do you hedge the FX, and what does the hedge cost?"

Three steps, each a distinct decision. First, **net before you hedge**:
sum signed exposures per currency across the whole book -- a long EUR
asset and a short EUR liability cancel, and hedging gross instead of
net pays double spread for zero risk reduction; the netting table is
the cheapest hedge you will ever put on. Second, size each currency's
hedge: for a plain cash exposure the ratio is ~1, but for a *foreign
asset* (foreign equity, say) the variance-minimizing ratio is not 1 --
the asset's local-currency value and the FX rate are correlated, so the
optimal ratio is the min-variance h* against the forward, and can be
materially below 1 when asset and currency co-move (the correlation
does part of the hedging for you). Third, price what the hedge
costs: an FX forward's price is spot plus **forward points**, which
encode the interest differential (covered interest parity) -- hedging
a high-yield currency exposure from a low-rate base means selling the
currency forward at a discount, i.e. paying the carry differential,
period after period. That cost is not a fee, it is the expected-return
transfer of the hedge, and it belongs in the decision: a 4 percent
annual carry cost to remove a 10-vol exposure is a very different
proposition from 0.5 percent to remove the same vol. Hedge the net,
size by min-variance, and put the carry cost on the same page as the
risk removed.

*In this library:* `hedging/FxHedger.java` -- nets signed currency
exposures across a book, computes the variance-minimizing ratio for
foreign-asset positions, and prices the forward hedge's carry cost from
forward points, in one class because the three steps are one decision.

### 441. "Set up a pairs trade: how do you size the two legs, and what tells you how long you'll wait?"

The hedge ratio comes from regression, not from price levels: regress
price A on price B and the slope beta is the units of B per unit of A
that makes the **spread** `A - beta B - intercept` (approximately)
stationary -- trading one-for-one or dollar-neutral instead of
beta-neutral leaves a residual market exposure that will dwarf the
mean-reversion edge you are harvesting. The spread then gets two
statistics that run the trade. The **z-score** -- current spread minus
its mean, over its standard deviation -- is the entry/exit signal:
enter short-spread at z = +2, exit near 0, symmetric on the other
side; it normalizes "how stretched" across pairs so one book can rank
them. The **half-life** answers "how long will I wait": fit an AR(1)
of spread *changes* on spread *levels* -- dSpread = a + b Spread --
and b < 0 is mean reversion with half-life `ln(2) / -ln(1 + b)`
periods, the expected time for a deviation to close half of itself. A
pair with a 60-day half-life is a position-sizing and financing
problem, not a trade; 5-10 days is tradeable. The half-life also
disciplines the stop: if you have waited three half-lives and the
spread has widened, the relationship has likely broken -- exit on
relationship failure, not on pain. All of this presumes the spread IS
stationary, which is a testable claim, not an assumption (next
question).

*In this library:* `hedging/PairsHedger.java` -- OLS hedge ratio,
spread construction, z-score for entry/exit and AR(1) half-life for
expected holding time, all returned together because they are one
trade's parameters.

### 442. "Before you trade that pair -- how do you test the relationship is real, and why isn't correlation enough?"

Correlation measures co-movement of *returns*; cointegration is about
*levels*: two prices are cointegrated when some linear combination of
them is stationary -- the spread is tethered, deviations mean-revert.
The difference is everything for pairs trading: two random walks with
correlated increments can drift apart forever while showing 0.9 return
correlation the whole way down -- correlation tells you the daily
wiggles rhyme, cointegration tells you the *gap* comes back, and only
the second pays a convergence trade. The Engle-Granger two-step test:
(1) regress price A on price B by OLS -- the slope is the candidate
hedge ratio; (2) test the residual (the spread) for a unit root with an
ADF regression -- change in spread on lagged spread level. Under the
null the spread is a random walk (no cointegration); more negative ADF
t-statistics reject it. Crucial subtlety: because the spread was
*estimated* to look as stationary as possible, ordinary ADF critical
values are too lenient -- you must use the Engle-Granger two-variable
asymptotics (about -3.34 at 5 percent, versus -2.86 for a plain ADF).
Skip that correction and you certify spurious pairs at scale. Remaining
honesty: cointegration is a sample property that breaks (index
deletions, mergers, business-model drift) -- retest on a rolling
window, and treat a failing retest as a structural exit signal, not
noise.

*In this library:* `hedging/CointegrationTest.java` -- Engle-Granger
two-step with ADF on the residual spread and the correct two-variable
no-trend critical values (-3.90 / -3.34 / -3.04 at 1/5/10 percent)
hard-coded and documented; run it before `PairsHedger` sizes anything.

### 443. "Your pair's hedge ratio isn't constant. How do you track a time-varying beta, and when is that better than rolling OLS?"

Put the regression inside a Kalman filter: the state is [alpha, beta],
assumed to follow a random walk with (small) process noise Q, and each
observation `y_t = alpha + beta x_t + noise` updates the state by the
standard predict/correct recursion. The filter is a rolling regression
done right: instead of a hard window (where an observation matters
fully for n days and then vanishes -- causing beta jumps at the window
edge), influence decays smoothly, and the **process noise sets the
adaptation speed** explicitly -- large Q tracks fast and noisily,
Q -> 0 recovers static OLS; that dial is a modeling statement about
how fast you believe the relationship truly drifts. Two outputs matter
beyond beta itself. The filter's own **beta variance** is an honesty
meter: when it balloons, the filter is telling you it no longer knows
the hedge ratio -- widen bands or step aside, which no rolling OLS
will ever volunteer. And the innovation sequence (prediction errors)
IS the trading spread, already deseasonalized of the drifting beta.
When is it better than static or rolling OLS? Whenever the relationship
genuinely drifts: an index-futures hedge whose composition shifts, a
pairs ratio through an earnings-cycle divergence, an FX proxy hedge
across a policy regime change. When the true beta is stable, static OLS
with all the data wins -- the filter's adaptivity just adds tracking
noise. Seed it with an OLS fit; that is the right prior.

*In this library:* `microstructure/KalmanBeta.java` -- [alpha, beta]
random-walk state, streaming updates, `betaVariance()` exposed as the
filter's own uncertainty (the javadoc: the pairs desk's upgrade over
static OLS; seed initialBeta from an OLS fit).

### 444. "Design an auto-hedger for a dealer's inventory. Why hedge to the band edge, and why do you need a cooldown?"

The core loop is a position band: while |position| stays inside the
band, do nothing; the moment it breaches, submit an opposite-side order
for **the excess over the band** -- hedging back TO the band edge, not
to flat. Edge-hedging is deliberate and mirrors the delta-band logic:
flattening entirely maximizes the trade size (paying maximal spread and
impact), destroys the inventory a dealer needs to internalize the next
client flow against, and immediately restarts the random walk from
zero so the next breach comes sooner. Hedging the excess trades the
minimum quantity that restores compliance and lets natural two-way flow
mean-revert the rest for free -- this is how dealer books actually run
inventory. The **cooldown** is the loop-stability guard: after firing a
hedge, suppress further hedges for a fixed interval. Without it, the
hedger can self-oscillate -- its own child-order fills and the market's
reaction re-trip the band while the first hedge is still working, and
you machine-gun orders into your own impact (the auto-hedger version of
a control loop with no damping). The cooldown is also the humility
window for fill latency: position updates lag order submission, and
re-deciding before the first decision has settled is acting on a stale
state. Everything above is Greek-agnostic -- run the same band logic on
share inventory, delta, or DV01; only the position measure changes.

*In this library:* `trading/AutoHedger.java` -- streaming position-band
hedger on the fast lane: breach -> opposite-side order for the excess
over the band via `HftOrderGateway`, explicit hedge-to-band-edge
rationale in the javadoc, and a configurable cooldown; the band logic
is documented as measure-agnostic.

### 445. "When do you hedge gamma with options versus just delta-hedging with futures more often?"

They are not substitutes -- one removes convexity, the other manages
its consequences. Delta-hedging with futures, however frequently,
leaves gamma untouched: between any two rebalances you are exposed to
the squared move, and no rebalancing frequency protects against a
**gap** -- the overnight 5 percent open goes straight through your
schedule, and short gamma realizes the full quadratic loss. Futures
are linear instruments; only another convex instrument -- an option --
can cancel convexity. So the decision hinges on three things. Gap
exposure: if the book is short gamma into event risk (earnings, CPI,
elections) or in a jump-prone underlier, buy gamma -- the futures
hedge fails precisely in the scenario that matters. Cost structure:
futures hedging pays *spread per rebalance*, cumulatively, and rises
with realized vol (more delta churn); buying options pays *theta*,
up front and predictable. Short-dated near-the-money options carry the
most gamma per unit of theta rent, so a small tail of long options can
halve the rebalancing burden of the whole book -- the mixed policy
(partial gamma hedge, then band-based delta hedging on the residual)
usually dominates either extreme. And magnitude: a large short-gamma
book near expiry pins you into hedging into every move -- buying back
convexity converts an unbounded quadratic exposure into a fixed,
known rent. Run the comparison honestly: simulate the P&L distribution
each way and look at the left tail, not the mean -- the mean barely
moves; the tail is the whole argument.

*In this library:* `hedging/GreekHedger.java` (the delta-gamma solve:
which option, how many, and the futures cleanup),
`hedging/HedgingSimulator.java` (the delta-only error distribution
whose left tail makes the case), `pricing/BlackScholes.java` (gamma
and theta -- the rent quote per unit of convexity).

### 446. "Hedging costs money and removes risk. How do you optimize the trade-off instead of eyeballing it?"

Write it as one objective and let a solver draw the curve: minimize
`portfolio variance after hedging + lambda x sum of c_i |x_i|` over
hedge notionals x, where c_i is each instrument's all-in cost per unit
notional (spread, fees, impact) and lambda is the risk/cost trade-off
dial. The L1 (absolute-value) cost term is the load-bearing choice:
because its penalty is non-differentiable at zero, instruments whose
marginal variance reduction is worth less than their marginal cost get
weight **exactly zero** -- not a dusty 0.3 percent position that pays
spread forever -- so the optimizer performs hedge *selection*, not just
sizing, the same mathematics as lasso regression. Solve it by cyclic
coordinate descent with the exact soft-threshold update: one instrument
at a time, closed-form shrink toward zero, deterministic, no external
optimizer. Sweep lambda and you trace the efficient frontier of
hedging: at lambda = 0 the full minimum-variance hedge (every
instrument, maximal cost); as lambda rises, expensive marginal hedges
drop out one by one and residual variance climbs -- the desk then
picks a point on the curve as *policy* ("hedge until the next unit of
variance costs more than X bps") instead of re-arguing every hedge ad
hoc. This is the standing problem of a central risk book: many desks'
residuals netted into one portfolio, a menu of futures and liquid
options as hedge candidates, and a nightly solve that says which few
are worth paying for tonight.

*In this library:* `crb/HedgeOptimizer.java` -- cost-aware
minimum-variance hedging of the central risk book's netted factor
exposures, L1 cost term with exact soft-threshold coordinate descent,
lambda = 0 documented as pure min-variance, exact zeros called out in
the javadoc; fed by `crb/CentralRiskBook.java` /
`crb/FactorRegistry.java`.

### 447. "When should you NOT hedge?"

Whenever the risk removed is worth less than what removal costs -- and
that is more often than hedging culture admits. Case one:
**diversifiable noise**. A book of fifty small, roughly independent
idiosyncratic exposures already enjoys sqrt(50) diversification;
hedging each name individually pays fifty spreads to remove risk the
portfolio was mostly cancelling internally -- net first, hedge the
netted residual, and let diversification do the unpaid work (the whole
premise of a central risk book). Case two: **ineffective hedges** --
minimum-variance logic says a proxy at rho = 0.5 removes only 25
percent of variance (rho^2) at full transaction cost plus basis risk
that itself needs monitoring; below the effectiveness bar, the hedge is
mostly a new position wearing a hedge's name. Case three: **negative
expected-value removal** -- hedges have carry: selling a high-yield
currency forward, rolling puts, paying variance-swap premium; removing
a risk you are structurally paid to hold (and can afford to hold)
converts expected return into insurance-company profit. Case four:
**the band's interior** -- the Whalley-Wilmott and inventory-band
results formalize that small deviations are optimally left alone; a
hedger with no dead zone is a cost machine. The honest test is always
the same pair of numbers side by side: variance removed (times your
risk price) versus all-in cost including carry -- an L1-penalized
optimizer setting a hedge to exactly zero is that comparison, decided
mechanically. Hedge the tail you cannot survive; carry the noise you
are paid to carry.

*In this library:* `crb/HedgeOptimizer.java` (zero weights ARE the
"do not hedge" verdicts), `hedging/MinimumVarianceHedge.java` (the
rho^2 effectiveness bar), `hedging/FxHedger.java` (carry cost priced
next to the risk), `hedging/WhalleyWilmott.java` and
`trading/AutoHedger.java` (the no-trade regions where not hedging is
the theorem, not the shortcut).

---

## Market risk workflow (Q448-Q462)

### 448. "Walk me through a market risk system end to end -- from raw ticks to a number the regulator will accept."

Fourteen steps, and the order matters because each one consumes the last:
(1) collect market data, (2) clean it -- a fat-fingered print becomes a fake
VaR exception two years later, (3) identify risk factors, because you model
a 5,000-position book through a few hundred rates, spreads and vols, not
5,000 price series, (4) build pricing models that map factors to position
values, (5) differentiate them into sensitivities (Greeks), (6) model
volatility, (7) model correlation and dependence, (8) aggregate into VaR,
(9) go beyond the quantile into tail risk (ES, EVT), (10) stress test with
scenarios VaR cannot see, (11) backtest the VaR against realized P&L,
(12) map it all into the regulatory framework (Basel III / FRTB),
(13) validate the models independently, and (14) deploy to production with
monitoring. The classic failure is jumping from (1) to (8): a VaR number
computed on dirty data through unvalidated pricers is precise nonsense.
Steps (11) and (13) are what make the number defensible -- a VaR you never
backtested is an opinion, not a measurement.

*In this library:* `docs/MARKET_RISK.md` -- the whole document is this
roadmap, one section per step, each pointing at the classes that implement
it, ending with a reading order for the risk-curious.

### 449. "Your book has options. Delta-normal VaR says risk is X. Why is it wrong, and what is the cheapest honest fix?"

Delta-normal assumes P&L is linear in factor moves: `dP = delta' * dr`, so
portfolio P&L is normal with variance `delta' * Sigma * delta` and VaR is a
z-score times sigma. Options break the linearity: a short-gamma book loses
MORE than delta predicts on both large up and large down moves, so the true
loss distribution is left-skewed and delta-normal understates the tail --
systematically, not randomly. The cheapest honest fix is delta-gamma with a
Cornish-Fisher correction: keep the quadratic term
`dP = delta'dr + 0.5 dr'Gamma dr`, compute the first three cumulants of
that quadratic form in closed form (the third cumulant is
`3 delta'Sigma Gamma Sigma delta + tr((Gamma Sigma)^3)`), and adjust the
normal quantile for the resulting skewness. Concrete case: a book short
1,000 ATM straddles shows near-zero delta -- delta-normal calls it riskless;
delta-gamma sees the negative gamma and reports the real tail. The sanity
check that keeps the implementation honest: with Gamma = 0 the formula must
reduce EXACTLY to the delta-normal answer.

*In this library:* `risk/VarEngine.java` -- `deltaNormalVar`/`deltaNormalEs`
for the linear baseline, `deltaGammaEs` for the closed-form quadratic with
Cornish-Fisher; the javadoc states the reduces-to-delta-normal property
explicitly.

### 450. "Historical simulation: what does the window length actually buy you, and what is the ghost effect?"

Historical simulation replays each day in the window through today's
exposures and reads the loss quantile straight off the empirical
distribution -- no distributional assumption, correlations and fat tails
come for free because they are IN the data. The window length is the whole
model: a 250-day window in calm 2017 contains no crisis, so the VaR is
tiny; a 4-year window still containing March 2020 is dominated by it. The
ghost effect is the exit, not the entry: the day COVID-19's worst prints
roll out of the window, VaR drops sharply overnight with no change in the
book and no change in current market conditions -- the ghost of the crisis
leaves, and risk appears to fall. Desks handle this with overlapping
windows, exponential weighting, or (under FRTB) a stressed period that
never rolls off. The honest statement of the method's limit: it produces
no more tail than the window actually contained -- if the worst day in your
window is -3%, your 99% VaR cannot warn you about -10%.

*In this library:* `risk/VarEngine.java` -- `historicalVar` replays each
row of factor returns through the exposures; its javadoc states the limit
verbatim: "no more tail than the window actually contained." It refuses
windows under 20 scenarios.

### 451. "Monte Carlo VaR: why do you need a copula at all, and why did the Gaussian one get blamed for 2008?"

Monte Carlo VaR needs a joint distribution of factor moves, and a copula
is how you separate the two decisions: what each factor's marginal looks
like (fat-tailed, skewed, whatever fits) versus how factors move TOGETHER.
Sample correlated uniforms from the copula, then push each through its own
marginal's inverse CDF. The Gaussian copula's fatal property is zero tail
dependence: as you go deeper into the tail, the probability that two
factors crash together GIVEN one crashed goes to zero, no matter how high
the correlation. Pre-2008 CDO models used it to price correlation among
mortgage defaults -- and defaults cluster in the tail precisely where the
Gaussian copula says they do not. The Student-t copula with few degrees of
freedom fixes this with a shared chi-square shock in the denominator: one
bad draw of the shared shock drags every factor's tail simultaneously,
producing strong symmetric tail dependence. The instructive experiment: a
moment-matched normal approximation has the same correlation matrix as a
t-copula yet materially thinner joint tails -- correlation alone does not
determine what happens in a crash.

*In this library:* `risk/GaussianCopula.java` -- Gaussian and Student-t
samplers; the javadoc names the CDO blame, and the t-copula draw shows the
shared shock that creates tail dependence, contrasted against the
moment-matched normal.

### 452. "When do Greeks lie badly enough that you must fully revalue, and what does full revaluation cost you?"

Greeks are a Taylor expansion around today's market; they lie whenever the
scenario moves you far from the expansion point or across a kink. Three
standard cases: (1) barrier options near the barrier -- delta and gamma
change sign discontinuously as spot crosses, and no polynomial tracks that;
(2) large stress moves -- a -20% equity shock is far outside the radius
where delta+gamma approximates a put's convexity; (3) path-dependent or
callable structures where the "Greek" itself is scenario-dependent. Full
revaluation abandons the expansion: for each scenario, reprice every
position with the actual pricing model and read P&L as the difference. It
is exact by construction and expensive by construction -- 10,000 scenarios
times 100,000 positions times a PDE or Monte Carlo pricer is why risk
farms exist. The pragmatic middle ground desks actually run: full
revaluation for the exotic book, delta-gamma for the linear-ish rest, and
a periodic reconciliation of the two on the overlap to detect when the
cheap method has started lying. The same scenario matrix serves both --
historical rows for historical full-reval, simulated rows for Monte Carlo.

*In this library:* `risk/VarEngine.java` -- `fullRevaluationVar` takes the
scenario matrix and a `ScenarioReval` pricer callback, deliberately
agnostic about where scenarios came from.

### 453. "Estimate the 99.9% loss when your window barely contains a 99% event. And when should the method itself refuse to answer?"

That is extreme value theory's one job: extrapolate beyond the sample,
lawfully. Peaks-over-threshold: pick a high threshold u, keep only the
exceedances over u, and fit a Generalized Pareto Distribution to them --
the Pickands-Balkema-de Haan theorem says GPD is the limiting form of
threshold exceedances for essentially any underlying distribution. The
shape parameter xi is the number to stare at: xi near 0 means an
exponential (thin) tail, xi > 0 means a power-law (fat) tail, and the
fitted xi tells you HOW fat. From the fit you get quantiles far beyond the
data -- a 99.9% VaR from a window that only contains a 99% event. The
refusal: when xi >= 1 the fitted tail has no finite mean, so expected
shortfall -- the mean of the tail -- mathematically does not exist.
Returning a number there would be the exact lie EVT exists to prevent, so
the correct implementation throws rather than extrapolates. Practical
traps: threshold choice (too low biases the fit, too high starves it) and
serial correlation in exceedances (cluster, then fit).

*In this library:* `risk/ExtremeValueTheory.java` -- POT fitting into a
`GpdFit` record; `expectedShortfall` throws `IllegalStateException` when
`shape >= 1` ("the fitted tail has no finite mean"), and the fitter guards
against threshold placement biasing the shape estimate.

### 454. "PCA of a yield curve covariance: what do the first three components mean, and what numerical lesson did implementing it teach?"

Run PCA on the covariance of daily yield-curve moves and the first three
eigenvectors are always the same shapes: level (all tenors move together,
typically ~80-90% of variance), slope (short end versus long end), and
curvature (the belly against the wings). That is why desks hedge to three
factors instead of thirty tenors -- three numbers capture nearly all curve
risk, and scenario generators can shock "level" as one coherent move
instead of thirty inconsistent ones. The numerical lesson: eigenvalue
routines care about SCALE. The cyclic Jacobi algorithm is exact and
dependable for symmetric matrices, but convergence thresholds written as
absolute numbers silently fail on matrices whose entries live at 1e-8
(variances of daily rate moves) or 1e+155 -- so normalize the matrix to
unit scale before iterating and rescale the eigenvalues after. And bound
the iteration: Jacobi converges quadratically, so a symmetric matrix that
has not converged in a fixed sweep budget is telling you the input is not
what you think it is -- fail loudly rather than spin.

*In this library:* `risk/Pca.java` -- cyclic Jacobi with explicit
pre-normalization (the comment cites entries near 1e155) and a hard
failure after 100 sweeps rather than a silent hang.

### 455. "Why do risk systems use Spearman and Kendall instead of Pearson correlation? And what is the bridge back?"

Pearson correlation measures LINEAR association and is wrecked by exactly
what financial data has: fat tails (one outlier day can dominate the
estimate) and nonlinear-but-monotone relationships. Spearman's rho is
Pearson on ranks -- invariant to any monotone transform of either variable,
so it measures "do these move together" without caring about units or
tails. Kendall's tau counts concordant versus discordant pairs -- even more
robust, with a clean probabilistic reading (probability a random pair
agrees in direction, minus disagrees). The bridge back matters for copula
calibration: for elliptical distributions, `rho_pearson = 2 sin(pi/6 *
rho_spearman)` and `rho_pearson = sin(pi/2 * tau)`. So the honest workflow
is: estimate Kendall's tau from dirty real data (robust), convert through
the sine bridge to a Pearson parameter, and feed THAT into your Gaussian
or t-copula -- you get the copula's correlation input without ever exposing
the estimate to Pearson's outlier sensitivity. Estimating Pearson directly
on raw returns and using it as the copula parameter conflates marginal fat
tails with dependence.

*In this library:* `risk/Dependence.java` -- `spearman` and `kendallTau`
alongside the conversion functions, so the tau-to-copula workflow is a
two-call pipeline.

### 456. "Design a stress testing program. Then: the board asks 'what kills us?' -- answer it without guessing scenarios."

Three legs. Historical scenarios: replay the worst days on record --
Black Monday 1987, Lehman 2008, COVID March 2020 -- with shock magnitudes
taken from the public record, because "it has happened" ends every
plausibility argument. Hypothetical scenarios: shocks designed around
YOUR book's concentrations, since history never stresses the exact thing
you are long. Sensitivity ladders: one factor swept across a grid while
others stay put, to expose nonlinearity (a short-gamma book looks fine at
+/-1% and terrible at +/-5%). The board's question is reverse stress, and
it has a closed form: given a loss level L and factor covariance Sigma,
the MOST PROBABLE shock vector producing loss L for a linear book lies
along Sigma*delta, and its Mahalanobis distance is `L / sqrt(delta'Sigma
delta)` -- no scenario search, no guessing. The output reads directly:
"the cheapest way for the market to take L from us is this specific
combination of moves, and it is a 4.2-sigma event." If the answer is
2 sigma, the book is fragile; if it is 12 sigma, the loss level is
practically unreachable through correlated market moves.

*In this library:* `risk/StressTester.java` -- `blackMonday1987`,
`lehman2008`, `covidMarch2020` from the public record, `scenarioPnl`
(with an optional gamma overload), `sensitivityLadder`, and
`reverseStress` returning the shock vector plus its Mahalanobis distance
in sigmas.

### 457. "FRTB replaced 10-day VaR with liquidity-adjusted ES. Explain the liquidity horizon cascade."

Basel 2.5's flaw: it scaled everything to 10 days as if every position
exits in two weeks. FRTB's internal models approach uses 97.5% expected
shortfall at a base 10-day horizon, then assigns every risk factor to one
of five regulatory liquidity horizons -- 10, 20, 40, 60, 120 days -- by how
long it realistically takes to exit or hedge: major FX at 10 days, credit
spreads longer, exotic vol surfaces at the far end. The cascade computes
nested ES terms: ES_j is the 10-day ES with only the factors of liquidity
horizon >= LH_j shocked, and the aggregate is
`ES = sqrt( sum_j [ ES_j * sqrt((LH_j - LH_{j-1})/10) ]^2 )`. The square
root scaling stretches each layer to its incremental horizon; the
sum-of-squares aggregation assumes independence across layers. The
economic effect is the point: a book of illiquid credit exotics carries
materially more capital than an equally volatile book of liquid futures,
because its factors sit deep in the cascade -- capital finally prices the
time it takes to get out, not just the volatility while you are in.

*In this library:* `risk/FrtbEs.java` -- the five horizons as `LH_10`
through `LH_120` constants, the MAR33.5 cascade formula implemented as
given in the javadoc.

### 458. "What is the PLAT test, and tell me about a bug class that only shows up in production P&L feeds."

PLAT -- the P&L attribution test -- is FRTB's check that the risk model's
view of the desk agrees with the front office's. Two daily series:
hypothetical P&L (front-office pricers, positions frozen) and
risk-theoretical P&L (the risk model's factors through the risk model's
pricers). Two statistics: Spearman correlation between them, and a
two-sample Kolmogorov-Smirnov statistic between their empirical
distributions. Zones per the regulation: GREEN when correlation > 0.80
and KS < 0.09; RED when correlation < 0.70 or KS > 0.12 -- which kicks the
desk off internal models onto the punitive standardized approach; AMBER
between, with a capital surcharge. The production bug class: NaN. A
KS implementation that walks two sorted arrays with a tie-consuming
advance (`while a[i] == a[i-1]`) freezes forever when a NaN arrives,
because NaN == NaN is false in every comparison -- one corrupt P&L day
turns the nightly batch into an infinite loop, not an exception. Sorted
arrays put -Inf first and NaN/+Inf last, so validating the array ENDS
rejects every non-finite value in O(1) before the walk starts.

*In this library:* `risk/PnlAttribution.java` -- Spearman + KS with the
regulatory zone thresholds, and the comment block explaining exactly how
a NaN would freeze the tie-consuming advance and how the ends-check
prevents it.

### 459. "Your 99% VaR had 6 exceptions in 250 days. Walk me through deciding whether the model is broken."

Expected count is 2.5, but "6 feels high" is not a test. Kupiec's
proportion-of-failures test makes it one: a likelihood ratio comparing
the binomial likelihood of 6/250 at the claimed 1% rate against the
observed 2.4% rate; the statistic is asymptotically chi-square(1), and
6 exceptions yields a p-value low enough to flag at 5% but not
overwhelming -- one more year of data would settle it. But coverage is
only half the question. Christoffersen's independence test asks whether
exceptions CLUSTER: a model with exactly 2.5 exceptions per year that
produces them on consecutive days is broken in the way that matters --
it fails to update after the first hit, exactly when you are bleeding.
The test compares transition probabilities (exception following
exception versus exception following quiet day). Conditional coverage
combines both LR statistics into a joint chi-square(2) test: right
frequency AND no clustering. Report all three; a model can pass Kupiec
while failing independence, and that pattern -- correct on average,
blind in crises -- is the most dangerous failure mode a VaR can have.

*In this library:* `risk/VarBacktest.java` -- Kupiec POF, Christoffersen
independence, and the joint conditional coverage test, each returning
statistic and p-value with a significance-threshold pass method.

### 460. "Component, marginal, incremental VaR -- three numbers about one position. Which answers which question?"

The risk committee's actual question is rarely "what is VaR" -- it is
"who is using it." Marginal VaR is the derivative: how fast portfolio
VaR moves per unit added to position i -- `(Sigma w)_i / sigma_p`, the
gradient. It answers the TRADING question: which position should the
next dollar of risk reduction come from. Component VaR is
`w_i * marginal_i`, and by Euler's theorem the components SUM EXACTLY
to portfolio VaR -- so it answers the ATTRIBUTION question: of today's
100 VaR, desk A owns 40, desk B owns 35, desk C owns 25, and the
numbers reconcile. Incremental VaR is the finite difference: recompute
VaR with the position fully removed. It answers the EXIT question --
what do we save by closing it -- and it does NOT equal component VaR;
the difference is the whole point. A large position negatively
correlated with the book can have NEGATIVE component VaR (it is a
hedge, it earns a risk rebate in attribution) while its incremental
VaR says removing it INCREASES portfolio risk. Presenting the wrong
one of the three to the wrong question is how hedges get cut in a
de-risking.

*In this library:* `risk/ComponentVar.java` -- all three from one
covariance matrix, with the Euler identity (components sum exactly to
portfolio sigma) stated in the javadoc and verified in tests.

### 461. "You have 500 assets and 2 years of daily data. Why is the sample covariance matrix unusable, and what do you do?"

500 assets means 125,250 free parameters estimated from ~500
observations of 500 numbers -- the sample covariance is rank-deficient
(rank at most T), its smallest eigenvalues are spuriously near zero,
and its largest are inflated. Any optimizer or VaR engine downstream
will treat those artifacts as information: the near-zero eigenvalue
directions look like free risk-free leverage, and the portfolio loads
maximally on estimation error. Ledoit-Wolf shrinkage is the standard
cure: replace S with `delta*F + (1-delta)*S`, a convex blend of the
sample matrix and a structured target F (scaled identity `mu*I`, or the
constant-correlation variant that trades a bit of bias for capturing
the market factor). The elegance is that delta is not tuned -- it is
ESTIMATED from the data itself: one statistic measures how far the
sample matrix is from the target, another how much of that gap is
noise; their ratio is the optimal shrinkage. When T is small relative
to N, delta approaches 1 and you trust the boring target; when data is
plentiful, delta approaches 0 and the sample speaks. The result is
always well-conditioned, always invertible, and empirically dominates
the raw sample matrix in out-of-sample portfolio risk.

*In this library:* `risk/CovarianceShrinkage.java` -- Ledoit-Wolf 2004
with the scaled-identity target and the estimated shrinkage intensity;
the javadoc states the constant-correlation trade-off explicitly.

### 462. "Your Monte Carlo engine threw 'matrix not positive semi-definite' at 3am. What happened, and how should the Cholesky have been written?"

Cholesky decomposition (A = LL') is how correlated normals get
generated, and it requires positive semi-definiteness. Real correlation
matrices break it constantly: a matrix assembled from PAIRWISE
correlations estimated on different date windows need not be PSD as a
whole; stress overrides ("set equity-credit correlation to 0.9") push
it outside the PSD cone; and rank-deficient factor models produce
pivots a hair below zero from nothing but floating-point rounding. The
naive implementation `if (pivot < 0) throw` treats -1e-17 -- pure
rounding noise on a legitimate matrix -- the same as -0.4, a genuinely
broken input. The correct discrimination is RELATIVE: tolerate a pivot
tiny relative to the diagonal scale (clamp it to zero, the matrix is
PSD-up-to-rounding), but a pivot grossly negative relative to that
scale means the input truly is not a covariance matrix, and THAT must
throw with the pivot index and value in the message so the 3am page is
diagnosable. Upstream, the durable fixes are estimating the full matrix
on one window, shrinkage (Q461), or a nearest-PSD projection after
manual overrides.

*In this library:* `util/MathUtils.java` -- `cholesky` implements
exactly this relative-pivot policy; the javadoc names the
rank-deficient-factor-model case, and the exception reports which pivot
failed and by how much.

## Credit, limits & operational risk (Q463-Q470)

### 463. "Model counterparty exposure for a derivatives book. What does netting buy you, and what is the caveat everyone forgets?"

Three numbers per counterparty. Current exposure: max(0, net
mark-to-market) -- what you lose if they default RIGHT NOW, floored at
zero because owing them money is not an exposure. Potential future
exposure (PFE): what the exposure could GROW to before default, since a
trade at zero MTM today can be deep in your favor in a year -- the quick
regulatory version is notional times a tenor-dependent add-on factor;
the full version is Monte Carlo over the netting set. Expected positive
exposure feeds CVA pricing. Netting is the single biggest mitigant: with
an enforceable master agreement, a +100 swap and a -80 swap with the
same counterparty net to 20 of exposure, not 100 -- often an order of
magnitude reduction across a dealer book. The forgotten caveat: netting
applies WITHIN a netting set, and a netting set is a legal construct,
not a counterparty name. Trades under different master agreements,
different legal entities of the same group, or in jurisdictions where
close-out netting is unenforceable do NOT net -- summing MTM across all
trades with "Bank X" silently assumes a single enforceable agreement
that may not exist, and 2008 was full of that discovery.

*In this library:* `risk/CounterpartyExposureTracker.java` -- current
exposure netted within the netting set and floored at zero, add-on-based
PFE by tenor; the javadoc scopes everything to the netting set.

### 464. "What is Herstatt risk, exactly -- and why does a 'fully hedged' FX book still have it?"

Settlement risk: the exposure created when you pay away one currency
before receiving the other leg of the same trade. Named for Bankhaus
Herstatt, closed by German regulators on 26 June 1974 AFTER it had
received Deutsche Mark payments in Frankfurt but BEFORE it made the
corresponding dollar payments in New York -- counterparties lost the
full principal, not a spread. That is the defining feature: unlike
market risk (you lose the move) or pre-settlement credit risk (you lose
replacement cost), settlement risk is exposure to the ENTIRE notional
during the window between paying and receiving. A "fully hedged" book
still has it because hedging nets market risk, not settlement flows:
your EUR/USD position may be flat, but on settlement day you are still
paying EUR in one time zone hours before receiving USD in another. The
analysis is therefore per-leg and time-ordered: for each settlement,
does a window exist where we have paid and not yet received, and what
is the notional at risk per counterparty during it. The systemic fix is
payment-versus-payment (CLS Bank), which makes the two legs atomic --
but not all currencies and counterparties are in it, so the residual
must still be measured and limited.

*In this library:* `risk/SettlementRiskAnalyzer.java` --
`SettlementLeg.hasHerstattWindow()` (true when we pay before we
receive) and `herstattExposure` aggregating at-risk notional per
counterparty; the javadoc tells the 1974 story.

### 465. "How do you measure concentration -- and why is 'largest position is only 8%' not an answer?"

Because 8% tells you about one position and nothing about the shape of
the rest: twelve positions at 8% each is a very different book from one
at 8% and 200 at 0.46%. The Herfindahl-Hirschman index sums squared
shares of absolute exposure: 1/N for a perfectly diversified book, 1.0
for a single name -- it weights the whole distribution, and large shares
quadratically. Its reciprocal, the effective number of positions, is
the version committees actually understand: "you hold 200 names but
your HHI says you effectively hold 14" ends the diversification
argument. Top-N share (what fraction of gross sits in the biggest 5 or
10) catches the case HHI can soften, and single-name limit breaches are
the hard gate. The crucial discipline is running the same measures
across MULTIPLE groupings -- issuer, sector, currency, country -- because
books diversify beautifully on one axis while concentrating fatally on
another: fifty different names that are all US regional banks is one
position wearing fifty tickers, which is precisely what the sector
grouping exposes and the issuer grouping hides.

*In this library:* `risk/ConcentrationRisk.java` -- HHI, effective
number of positions, top-N share and single-name limit breaches, all
taking exposures grouped by whatever axis the caller chooses.

### 466. "Design a pre-trade risk gate. Include the subtle rule about over-limit positions."

Every order passes through a checklist in microseconds, and the gate
returns ALL violations, not the first one (a rejected trader needs the
full list, not a retry loop of one-at-a-time discoveries). The
taxonomy: max order quantity and max order notional (fat-finger
guards -- the 10,000-lot order that should have been 100); a price
collar versus reference mid (reject prices more than x% away -- the
protection against stale quotes and decimal-point slips); restricted
symbols (compliance lists: names under investigation, IPO quiet
periods); counterparty credit limits (this order's exposure plus
current exposure versus that counterparty's cap); and max position
quantity post-trade. The subtle rule is on that last check:
risk-REDUCING orders must pass even from an over-limit position,
because a gate that rejects the trade that shrinks the breach wedges
the book at its worst point -- you are over limit and now forbidden to
fix it. But "reducing" must mean smaller AND the same sign: an order
that flips through zero into an over-cap position on the OTHER side is
a new breach wearing a hedge's clothing. The sign-flip check is one
XOR of the old and new position.

*In this library:* `risk/PreTradeLimitChecker.java` -- the full
taxonomy as a builder, violations accumulated into a list, and the
reduce-but-not-flip rule implemented with the sign XOR
(`(newPosition ^ currentPositionQty) < 0`), with a comment explaining
why the gate must not wedge the book.

### 467. "Position limits, VaR limits, Greek limits -- why do desks need all three? Doesn't one imply the others?"

They fail independently, which is the whole argument. A position limit
(max quantity or notional per name) is model-free: it cannot be fooled
by a broken volatility estimate, and it caps the damage from risks the
model does not see at all -- fraud, legal, operational. But it is blind
to riskiness: 10m notional of T-bills and 10m of a biotech before an
FDA ruling are the same number. A VaR limit is risk-sensitive and
aggregates across the book -- but it is exactly as good as its model:
in 2017-calm markets VaR shrinks, mechanically inviting positions to
grow into the quiet, and it says nothing about risks outside the
window (Q450). Greek limits (delta, vega, gamma per bucket) catch what
BOTH miss: a delta-neutral, VaR-modest options book can carry enormous
short gamma that only manifests on a large move -- a gamma limit is the
only one of the three that sees it before the move happens. The layered
design is deliberate redundancy: any single limit type has a known
blind spot, and the book that hurts you is the one engineered
(intentionally or not) into exactly that blind spot. Cheap, dumb,
model-free limits are the last line when the sophisticated ones are
wrong.

*In this library:* `risk/PreTradeLimitChecker.java` (position/notional
gates), `risk/VarEngine.java` and `risk/PortfolioRiskAnalyzer.java`
(the VaR layer), `hedging/` (the Greeks the Greek limits would bound).

### 468. "Who should set risk limits, who should be able to waive them, and what does Archegos teach about the answer?"

Setting: the risk function, independent of the desk, calibrated to the
firm's stated risk appetite and approved by a risk committee -- never
the traders whose compensation the limits constrain. Waiving: a
temporary breach approval must come from someone senior in the RISK
chain, be time-boxed, documented, and reported upward; a waiver that
auto-renews is not a waiver, it is a limit increase that skipped
governance. The failure mode is always the same drift: breaches become
"exceptions," exceptions become routine, and the limit quietly stops
existing. Archegos (2021) is the canonical case study: prime brokers
had margin frameworks and counterparty limits on paper, but a large,
fee-generating client's breaches were met with raised limits and
delayed margin calls rather than enforcement -- at one bank the
escalation sat unresolved for weeks while exposure grew to billions.
When the concentrated swap positions fell, the firms that enforced
their limits early (sold collateral immediately) lost little; the firm
that had waived longest lost $5.5bn. The lesson is structural, not
personal: a limit whose waiver is decided by people paid on the
client's revenue is not a control. Governance questions -- who sets,
who waives, who gets told -- ARE the risk system; the math is just its
sensor.

*In this library:* the enforcement layer this governance would drive:
`risk/PreTradeLimitChecker.java` (hard gates, counterparty caps) and
`risk/CounterpartyExposureTracker.java` (the exposure number the
Archegos desks were not acting on).

### 469. "A fund allocator asks: 'where does your risk actually come from?' Build the report."

Two layers. Portfolio level: annualized volatility, VaR and CVaR
(historical and parametric, so the two can disagree informatively --
parametric far below historical means fat tails the normal assumption
is hiding), Sharpe, and max drawdown -- the summary a committee reads
in ten seconds. Attribution level: per-asset VaR contributions and the
correlation structure that explains them, because the portfolio
number alone cannot distinguish "twenty diversified bets" from "one
bet in twenty wrappers." The covariance matrix is the hinge: asset
VaRs computed in isolation sum to far more than portfolio VaR, and the
gap IS the diversification benefit -- report it explicitly, because it
is also the number that evaporates in a crisis when correlations go to
one. A real use case: a multi-strategy fund shows 8% vol overall; the
attribution reveals 70% of VaR sits in two rates positions that the
equity sleeves were assumed to offset -- the allocator's follow-up
("what happens to the offset in a risk-off day") is answered by the
stress leg, not the covariance leg. Consistent conventions matter more
than sophistication: annualization factors, return frequency, and
confidence levels must match across every number on the page or the
report contradicts itself.

*In this library:* `risk/PortfolioRiskAnalyzer.java` -- the full report
record (portfolio VaR/CVaR/vol, parametric VaR, Sharpe, per-asset VaR
map) built on `risk/CorrelationMatrix.java`; the single-series metrics
come from `risk/RiskMetrics.java` (historical/parametric/conditional
VaR, Sharpe, annualization constants).

### 470. "Why do allocators fire managers over drawdown DURATION rather than depth -- and how should risk control reflect that?"

Because investors experience time, not just magnitude. A -20% drawdown
recovered in three months is a war story; a -12% drawdown still
underwater after two years is a redemption -- clients leave, the
manager de-risks at the bottom to stop the bleeding, and the fund
never participates in the recovery. Depth is one bad moment; duration
is a sustained failure to add value, and it compounds behaviorally:
every month underwater raises the odds of forced selling. Risk control
should therefore track the drawdown EPISODE structure, not just the
max-drawdown scalar: per episode, the depth (1 - trough/peak), the
duration from peak to recovery, and the time-to-trough versus
time-to-recover asymmetry (fast crash + slow climb is the equity
signature; slow bleed is worse news about the alpha itself). Two
control rules follow: a duration-based de-risking trigger (e.g., cut
gross after N months underwater regardless of depth -- it caps the
behavioral spiral), and honest handling of the OPEN episode: a
drawdown still running at the end of the series has no recovery date,
and reporting its duration as if it had closed understates the current
pain -- the statistic must mark it open-ended.

*In this library:* `backtest/DrawdownAnalytics.java` -- full episode
decomposition (depth, duration, peak/trough/recovery indices), explicit
handling of the still-open episode at series end, and exact agreement
with `PerformanceAnalytics`'s max-drawdown scalar (tested).

## Alpha research pipeline (Q471-Q484)

### 471. "What is the first infrastructure decision in cross-sectional alpha research, and how does it prevent survivorship bias?"

Freeze the panel. Before any signal is computed, fix the symbol axis --
an ordered list of symbols that every score array, weight array, and
return array aligns with for the entire study, missing data marked NaN
rather than symbols silently dropped. Without it, every downstream
array is "whatever symbols happened to have data that day" and two
arrays from different dates cannot be compared element-wise -- the bug
class this kills is misalignment, the quiet one that produces plausible
wrong numbers. The second half is point-in-time membership: attach a
universe object that answers "was this symbol in my investable set ON
date t," so every cross-sectional operation -- ranking, demeaning,
neutralizing -- automatically excludes names that had not yet listed,
had already delisted, or were not yet index members. The survivorship
trap it prevents: computing momentum ranks over today's S&P 500
constituents back through 2010 means every bankrupt or acquired name
is excluded from history -- the panel is pre-filtered to winners, and
mean-reversion signals in particular look spuriously good because the
names that kept falling are simply absent. The frozen axis stays
frozen; the universe gates who participates in each date's
cross-section.

*In this library:* `alpha/AlphaContext.java` -- the frozen symbol order
("the panel's axis"), NaN for missing entries, and `withUniverse`
attaching a `PointInTimeUniverse` so every built-in cross-sectional
operation sees only point-in-time members.

### 472. "Name the classic factor families and sketch how you'd compute one of each from prices and fundamentals."

Four families recur in every equity factor library. Momentum: winners
keep winning over 3-12 months -- the academic 12-1 construction is
`close[t-21]/close[t-252] - 1`, skipping the most recent month because
1-month returns REVERSE (short-term reversal is its own, opposite
factor; skipping it is not a detail, it is the difference between the
factor working and not). Value: cheap outperforms expensive --
earnings yield or book-to-price from fundamentals, with the
point-in-time discipline of Q471 doing the heavy lifting (using
restated earnings is lookahead). Quality: profitable, conservatively
financed firms outperform junk -- the quality-minus-junk shape combines
profitability and leverage. Low volatility: the flattest empirical
violation of CAPM -- low-beta, low-vol names earn similar returns to
high-vol names with far less risk, so risk-adjusted they win; compute
trailing return volatility over a lookback and score it inverted.
Alongside these sit the time-series/trend constructions (MA crossover,
MACD, RSI, Bollinger z-scores) which score a symbol against its own
history rather than against the cross-section. Every one of them
outputs a score per symbol per date into the same frozen panel, which
is what makes them comparable and combinable.

*In this library:* `alpha/Factors.java` -- `momentum(252, 21)` (the
javadoc names the 12-1 form and the skip's purpose), `value()`,
`quality()` (quality-minus-junk shape), `lowVolatility(lookback)`,
plus `movingAverageCrossover`, `macd`, `rsi`, `bollinger`,
`meanReversion`.

### 473. "Define IC and IR for a factor, and connect them through the Fundamental Law of Active Management."

IC -- the information coefficient -- is the Spearman rank correlation
between factor scores at date t and forward returns over (t, t+h],
computed per evaluation date. Rank, not Pearson, because factor scores
have arbitrary units and fat tails: rank IC asks the only fair
question, "did the ordering predict the ordering." Real ICs are small:
0.03 is respectable, 0.10 is exceptional -- the follow-up trap is
scoffing at 0.05 "correlation," which misunderstands the business.
IR -- the information ratio of the IC series -- is mean IC over its
standard deviation across dates: not just whether the signal works on
average, but how consistently. The Fundamental Law connects them to
money: IR_portfolio ~= IC * sqrt(breadth), where breadth is the number
of INDEPENDENT bets per year. That square root is why a 0.03-IC signal
applied to 2,000 stocks monthly can fund a business while a 0.15-IC
signal on one asset traded quarterly cannot -- and why breadth claims
must be audited: 500 names in one sector at one rebalance is nowhere
near 500 independent bets. Alongside IC/IR, report the t-stat of mean
IC (is it distinguishable from zero given the number of dates) and hit
rate (fraction of dates with positive IC).

*In this library:* `alpha/SignalEvaluator.java` -- per-date Spearman
rank IC against forward returns, IR, t-stat, hit rate, plus turnover
and factor-exposure checks, in one evaluation record.

### 474. "Two signals, same mean IC. One has a 2-day decay half-life, the other 3 months. Which do you want, and what decides it?"

Costs decide it -- specifically whether the alpha survives its own
turnover. Alpha decay is mean IC as a function of the forward horizon:
the fast signal's IC dies within days, so harvesting it requires
trading at its rhythm -- daily or intraday rebalancing, near-100%
turnover, and every rebalance pays spread plus impact. The slow signal
tolerates monthly rebalancing at a fraction of the cost. The
arithmetic that settles arguments: net alpha ~= gross alpha - turnover
* cost per unit traded. A fast signal with 3% annual gross edge and
50x annual turnover needs all-in costs below 6bps per trade to keep
ANY of it; the slow signal with the same gross edge and 4x turnover
survives 75bps. So the fast signal is only "better" for someone with
an execution machine -- internalized flow, maker rebates, sub-bp
impact; for everyone else it is a backtest artifact that costs money
in production. The decay curve also dictates evaluation honesty:
measure IC at the horizon you can actually trade, not the horizon
that flatters the signal. Turnover is measured as half the L1 change
in normalized weights between consecutive rebalances -- report it next
to IC always, because IC-per-unit-turnover is the number that
predicts net performance.

*In this library:* `alpha/AlphaReport.java` -- the `Decay` record
(mean IC per forward horizon, fitted half-life in bars);
`alpha/SignalEvaluator.java` -- turnover as half the L1 weight change,
reported beside IC/IR in the same summary line.

### 475. "Your momentum factor backtests beautifully. How do you check you haven't just rediscovered the tech sector -- and what alignment bug lurks in the fix?"

Neutralize and re-test. Sector neutralization demeans scores WITHIN
each sector, so the factor can only express "best in class" views --
if the alpha survives, it is stock selection; if it dies, your
momentum factor was a sector bet wearing a factor's clothing (in
2020-21, long-tech-short-energy explained a lot of "momentum").
Beta neutralization does the same against market beta: regress scores
on betas cross-sectionally and keep residuals, otherwise the "factor"
is partly just leveraged market exposure. Dollar neutralization
(demean weights) makes the book long-short balanced by construction.
The lurking bug is alignment: sector labels arrive as a map keyed by
symbol, scores live in an array ordered by the frozen panel axis --
zipping them by position instead of by symbol scrambles every label,
and the neutralization silently neutralizes nonsense. The safe API
takes the map and aligns it to the panel's symbol order internally;
symbols missing from the map get a singleton sentinel sector (they
demean against themselves to zero -- conservative: an unlabeled name
cannot leak sector risk in). Second trap: neutralization changes gross
exposure, so re-normalize weights AFTER, not before, or position
sizes drift with each step in the pipeline.

*In this library:* `alpha/PortfolioConstruction.java` --
`sectorNeutralize` (map-keyed overload aligns by symbol; missing
symbols get singleton sectors), `betaNeutralize`, demeaning for
dollar neutrality, and the documented re-normalize-after rule.

### 476. "Estimate the premium on your factor with an honest standard error. Why is pooled OLS wrong, and what data reality must the estimator survive?"

Pooled OLS on a stacked panel is wrong because residuals are
cross-sectionally correlated -- every stock's return on a given day
shares the market's move, so the effective sample is far smaller than
N*T and pooled standard errors are fantasy-tight. Fama-MacBeth fixes
it in two passes: each period, run one CROSS-SECTIONAL regression of
returns on factor exposures, yielding that period's factor premium;
then treat the time series of premia as the sample -- the mean is the
premium estimate, and its t-stat uses the time-series standard error,
which correctly absorbs cross-sectional correlation because each
period contributed exactly one observation. A factor with mean premium
t > 3 across 300 months is evidence; the same coefficient from pooled
OLS with t = 12 is the same information with a miscounted sample. The
data realities a production estimator must survive: NaN scores
(symbols outside the point-in-time universe) are skipped per period by
convention, but an INFINITE score is a data error and must throw, not
be skipped -- silence would hide a broken feed; periods with fewer
assets than factors + 2 are skipped entirely (the regression is
underdetermined); and a period whose design matrix is singular
(perfectly collinear exposures) is caught and skipped with a count,
so the reported number of contributing periods is honest.

*In this library:* `alpha/FamaMacBeth.java` -- per-period
cross-sectional regressions with the NaN-skip / infinity-throw
distinction, the thin-period rule, and the singular-period catch, all
counted and reported.

### 477. "Are calendar effects real? How would you test the January effect today, and what result should you expect?"

Test it like any anomaly, with the null hypothesis armed. Build
day-of-week and turn-of-month profiles: mean return, t-stat, and count
per bucket (Monday..Sunday; the last few and first few CALENDAR days
of each month). The t-stat is the entire point -- a per-bucket mean
without one is a horoscope. What the literature and live data say:
most published calendar anomalies decayed or died after publication.
The weekend effect (negative Mondays) was strong in pre-1980s data,
publicized in academic journals, and subsequently vanished -- arguably
arbitraged away, arguably never robust. The January effect
(small-caps rallying early January, blamed on tax-loss selling)
weakened dramatically post-publication. This is the cleanest natural
experiment on alpha decay through crowding that finance has: the
publication date is a known event, and the before/after difference is
measurable. So the expected result of your test on modern data is: a
few buckets with |t| around 2 out of ~10 buckets tested -- which is
exactly what multiple comparisons produce under the null. The mature
conclusion is not "calendar effects are fake" but "any effect whose
entire identity is public and costless to trade should be presumed
dead until current out-of-sample data says otherwise" -- and
turn-of-month flows (pension contributions, index rebalances) retain
a structural cause worth monitoring even so.

*In this library:* `alpha/CalendarAnomalies.java` -- day-of-week and
turn-of-month profiles with per-bucket mean, t-stat and count; the
javadoc states the post-publication decay lesson as the class's point.

### 478. "You have one alpha candidate and one history. Design the validation gauntlet -- and explain the test that correctly humiliates a static signal."

Three independent attacks. Walk-forward: repeatedly pick the best
factor variant on a training window, measure it on the following test
window -- the in-sample versus out-of-sample IC gap is your overfitting
measurement, and a variant that wins training folds but loses test
folds is curve-fit. Blocked K-fold: recompute IC on k contiguous
(never shuffled -- shuffling time series leaks adjacent-day
information) blocks; the dispersion across folds tells you whether the
"signal" is one lucky regime. Permutation test: re-pair score dates
with return dates at random many times, build the null distribution of
mean IC, and report where the observed value falls -- a p-value with no
normality assumption. The permutation test has a deliberately
conservative property worth understanding: a signal whose
cross-section barely changes over time (a static ranking) is
INVARIANT under date permutation, so it earns p ~= 1 regardless of
its in-sample IC -- correctly so, because a time-invariant
cross-section against persistent return drifts is ONE effective
observation, however many dates you sample it on. Only signals whose
time variation aligns with return variation can earn a small p. Add a
parameter sensitivity sweep (a real effect degrades gracefully as
parameters move; an artifact falls off a cliff) and the gauntlet is
complete.

*In this library:* `alpha/AlphaValidation.java` -- `walkForward` over
factor candidates, blocked K-fold IC, the permutation robustness test
(javadoc explains the static-signal p ~= 1 property), and the
parameter sensitivity sweep.

### 479. "Your alpha has IC 0.04 and looks great gross. What does a cost-aware backtest add, and what is the capacity question?"

It adds the number that decides whether the strategy is a business:
net-of-cost performance at a stated AUM. The cost model must be
size-dependent -- a flat bps assumption is the classic self-deception,
because the dominant institutional cost is market impact, which grows
as the SQUARE ROOT of participation: trade 4x the size, pay 2x the
impact per share, 8x total. So the backtest needs per-symbol ADV and
volatility estimated from trailing bars, and each rebalance's trades
costed as commission + half-spread + sqrt-impact. The capacity
question follows immediately: as notional grows, gross alpha is fixed
but impact grows with sqrt(size), so net alpha crosses zero at some
AUM -- THAT crossing is the strategy's capacity, and it is a number,
not a vibe. A signal rebalancing daily across small-caps might net
12% at $10m and zero at $300m; the same pipeline at monthly
rebalancing might carry $2bn. The honest engine surfaces this rather
than hiding it: when positions are large relative to ADV, the impact
term should dominate the report loudly -- that IS the capacity answer,
not an error to suppress. Sharing one impact estimator between the
research backtest and the execution stack means a costed research
number and a TCA number can disagree only about timing, never about
the cost model itself.

*In this library:* `alpha/AlphaBacktester.java` -- cost-aware panel
backtests using `microstructure/MarketImpactModel.estimate` for the
square-root impact term (the comment marks the large-size case as
"this IS the capacity answer"), sharing the estimator and cost
decomposition with the execution engines.

### 480. "You have five signals. How do you combine them -- and why must the combined weights NOT sum to one?"

Weight each signal by its demonstrated predictive quality: an
IC-weighted ensemble sizes each component by its trailing out-of-sample
IC, so signals that have recently predicted get weight and signals
that have decayed fade automatically -- no committee meeting required.
This beats equal weighting (which funds dead signals forever) and
beats in-sample regression stacking (which overfits the combination
itself). The subtle design decision is normalization: the combined
weight vector must NOT be renormalized to sum to 1. The weights ARE
the sizing. If four signals have gone quiet (IC ~ 0) and one limps
along at IC 0.01, renormalizing would let that lone near-dead
component emit at full portfolio size -- the ensemble would take its
largest positions precisely when its evidence is weakest. Without
renormalization, total exposure shrinks as aggregate conviction
shrinks: five strong signals trade big, one weak signal trades tiny,
zero working signals trade nothing. That conviction-scaled exposure
is the feature, not a bug to "fix" -- and while a track record is
still building, the honest emitted weight is zero, not equal-weight
hope. The general lesson: any normalization step in a pipeline is a
claim that scale is meaningless; in signal combination, scale IS the
conviction.

*In this library:* `microstructure/AlphaEnsemble.java` -- IC-weighted
combination with the deliberate no-renormalize rule (the javadoc
explains the IC-0.01 full-size failure it prevents) and zero weight
until the track record exists.

### 481. "You want to fit alpha weights online, on live data. What is the honesty mechanism, and what off-by-one destroys it?"

Prequential evaluation: predict THEN train, in that order, every
interval. The model must emit its prediction for interval t using only
information available before t; only after the realized return arrives
does it update its weights. The running out-of-sample IC that this
produces -- correlation of predictions with outcomes, where every
prediction was genuinely made in advance -- is the gate: the learner's
output should carry zero weight until its prequential IC clears a
threshold, so a model that has not yet proven itself live cannot trade.
This is walk-forward validation collapsed into the learning loop
itself. The off-by-one that destroys it: feature snapshotting. The
regression pairs features with the NEXT interval's return, so when the
realized return for interval t arrives, the correct training example
is (features as of t-1's end, return over t) -- which means the engine
must snapshot its feature vector ONE INTERVAL EARLIER and train on the
stored snapshot, not on the current features. Training on current
features pairs the return with information from DURING the interval it
spans -- lookahead, and the model's "out-of-sample" IC becomes quietly
fraudulent. The bug is invisible in the aggregate numbers (they just
look better than reality); only the snapshot discipline prevents it.

*In this library:* `microstructure/OnlineAlphaLearner.java` --
prequential predict-then-train with time-decayed IC statistics as the
gate, and the stored previous-interval snapshot (the field comment
explains it must be "as of the PREVIOUS" interval).

### 482. "The PM asks for a one-page alpha report. What goes on it?"

Four blocks, each answering a question the PM will actually ask.
(1) Headline evaluation: mean rank IC, t-stat, IR, hit rate, turnover
-- is there a signal, how consistent, at what trading cost profile
(Q473, Q474). (2) Alpha decay: mean IC as a function of forward
horizon with the fitted half-life -- tells the PM the rebalance
frequency the signal demands and whether it fits the book's rhythm; a
half-life of 3 bars on a weekly-rebalanced book is a mismatch visible
in one chart. (3) Factor attribution: OLS of the strategy's portfolio
returns on known factor returns -- how much of the "alpha" is actually
momentum/value/market beta in disguise; the intercept after
attribution is the alpha the fund cannot already buy cheaper
elsewhere, and it is the only number that justifies fees. (4) The
validation verdict: walk-forward out-of-sample IC versus in-sample,
permutation p-value, sensitivity sweep (Q478) -- one line each. The
discipline is that every number is out-of-sample or attributed;
gross in-sample Sharpe appears nowhere, because it is the one number
guaranteed to flatter. A report in this shape also fails gracefully:
a signal that is really the momentum factor shows up in block (3), a
static ranking shows up in block (4), a cost-doomed fast signal shows
up in blocks (1)+(2) -- each failure mode has a designated place to
become visible.

*In this library:* `alpha/AlphaReport.java` -- the decay block
(horizons, mean ICs, half-life) and factor attribution via OLS of
portfolio returns on factor returns, designed to sit alongside
`alpha/SignalEvaluator.java`'s headline record and
`alpha/AlphaValidation.java`'s verdicts.

### 483. "A spread looks mean-reverting. Fit an OU process and tell me when the answer is 'don't trade it.'"

The Ornstein-Uhlenbeck process `dX = kappa(theta - X)dt + sigma dW`
is the mean-reversion trader's model: theta is the level the spread
reverts to, kappa the speed, and the tradable summary is the
half-life, ln(2)/kappa -- how long a deviation takes to decay halfway
back. Fit by regressing X_{t+1} on X_t (the discrete AR(1) form):
slope maps to kappa, intercept to theta, residual variance to sigma.
The half-life then drives everything practical: entry z-score
thresholds, expected holding period, capital rotation speed, and --
critically -- the costs test: a spread with a 3-day half-life and a
2-sigma entry can pay its way; a 200-day half-life is not a trade,
it is a buy-and-hold position wearing statistics (your capital is
locked for months per round trip, and the "edge" per unit time is
noise). The refusal case: if the fitted slope is >= 1, kappa <= 0 --
the series is not mean-reverting at all (random walk or trending),
theta is meaningless, and the half-life is infinite or negative. A
fitter that reports an infinite half-life as a tradable number is
lying; the correct behavior is to refuse the fit, forcing the caller
to confront that the premise failed. Always pair the fit with a
stationarity check (Q484) rather than assuming reversion because the
chart looks wiggly.

*In this library:* `microstructure/OrnsteinUhlenbeck.java` -- AR(1)
fit into a `Params` record (kappa, theta, sigma, halfLife); the
javadoc warns that a 200-day half-life is a filter not a trade, and
the fitter refuses non-reverting series rather than reporting an
infinite half-life.

### 484. "Before choosing momentum or mean-reversion for a series -- what one test do you run, and how do you read it?"

The Lo-MacKinlay variance ratio test -- the question that comes before
every strategy choice. For a random walk, variance scales linearly
with horizon: Var(q-period returns) = q * Var(1-period returns). So
the ratio `VR(q) = Var_q / (q * Var_1)` is 1 for a random walk,
ABOVE 1 when returns are positively autocorrelated (moves continue:
trending -- momentum strategies have material), and BELOW 1 when
returns negatively autocorrelate (moves partially undo: mean-reverting
-- reversion strategies have material). Lo-MacKinlay supply the
heteroskedasticity-robust standard error, so you get a z-statistic,
not just a point estimate -- essential because sample VRs wander far
from 1 on short windows under the null. Read it across several q
(2, 5, 10, 20): a series can trend at daily horizons and revert at
monthly, and the profile across q tells you WHICH horizon your
strategy should live at. Workflow in practice: VR significantly
below 1 at your horizon -> proceed to the OU fit (Q483) for entry
levels and half-life; VR indistinguishable from 1 -> no structure,
decline to trade; VR above 1 -> momentum toolkit, not reversion. This
one cheap test, run first, prevents the most expensive category
error in systematic trading: running a reversion strategy on a
trending series, which sells every breakout of a trend that keeps
going.

*In this library:* `microstructure/VarianceRatio.java` -- VR(q) with
the Lo-MacKinlay robust z-statistic; the javadoc frames it as the
gate before `OrnsteinUhlenbeck` (which refuses non-reverting series
downstream).

## Backtesting & validation mastery (Q485-Q500)

### 485. "A multi-asset backtest holds a stock that gets acquired mid-sample. What must the engine do, and why does event ORDER matter?"

The engine must consume corporate events, not just prices, or it is
survivorship-biased by construction: dead names vanish from a
price-only dataset, and vanishing is not what happened to your money.
Delistings must terminate the position at
`lastClose * (1 + delistingReturn)` on the event bar -- for a
bankruptcy that return is near -100%, and an engine that instead
silently drops the symbol lets the backtest keep the pre-collapse
value, a pure fiction. Mergers must convert the position on the
effective bar into cash and/or acquirer shares at the deal terms.
Order matters within a single bar: process MERGERS first, THEN
delistings -- because a target's shares flow into the acquirer, and if
the acquirer itself has an event that same bar, the converted shares
must exist before it fires; run delistings first and the target that
was supposed to become acquirer stock gets marked as dead money
instead. Equally important is what the engine must NOT do: a name
dropped from the INDEX (not the exchange) on its own ex-date is still
tradable -- terminating it at zero confuses index membership with
existence. And all of it presumes the events come from a
point-in-time dataset that still contains the dead names; the best
engine cannot un-bias a survivor-only feed.

*In this library:* `backtest/portfolio/PortfolioBacktester.java` --
the survivorship-aware overload consuming `PointInTimeUniverse`
events; the code comments spell out the mergers-first-then-delistings
ordering and the index-drop-is-not-a-delisting distinction.

### 486. "Your backtest charges 5bps per trade, flat. What is that assumption actually claiming, and what does the honest model look like?"

Flat bps claims that cost is independent of size, symbol, and market
state -- true only for a small retail account in liquid names. The
honest institutional model decomposes cost into four parts:
commission (genuinely flat, and the smallest); half-spread (you cross
it on every aggressive fill -- symbol-dependent: 1bp in mega-caps,
30bp in small-caps); slippage/timing noise; and market impact, which
is the dominant term at size and follows the square-root law -- cost
in bps scales with sigma * sqrt(size/ADV), so it needs PER-SYMBOL
average daily volume and volatility, estimated from the trailing bars
of the same data the backtest runs on. The practical consequence is
that the two models diverge exactly where it matters: a small-cap
strategy trading 5% of ADV might show +9% annually under flat-5bps
and -2% under the institutional model -- the flat assumption did not
approximate the cost, it deleted the strategy's binding constraint.
The design seam worth insisting on: research backtests and execution
analysis should express costs through one shared interface, with the
impact term computed by the same estimator both sides use -- then a
strategy that passes research costing cannot fail TCA because the
two disagreed about arithmetic.

*In this library:* `backtest/TradeCostModel.java` -- `flat(bps)` for
the classic assumption and `institutional(...)` for the
four-component model with per-symbol ADV/vol from trailing bars via
`microstructure/MarketImpactModel.estimate`, the shared seam named in
the javadoc.

### 487. "Bar-close fills say your strategy works. What does an execution-aware backtest change, and what accounting rule keeps it honest?"

It inserts the layer real trading has and naive backtests skip: the
strategy's signal creates a PARENT order (the intent: "get long
10,000 shares"), and an execution model works that parent across
subsequent bars as child fills -- TWAP-like slicing, iceberg behavior,
whatever the model implements. Three truths emerge that bar-close
fills hide. Fills take TIME: the position you wanted at Monday's
close arrives across Tuesday-Thursday, and the signal may have
decayed meanwhile -- fast-alpha strategies die here, visibly.
Execution cost becomes MEASURABLE per parent order: implementation
shortfall against the arrival price (the price when the parent was
created), which is TCA run inside the backtest -- the gap between
paper return and executed return is now a reported number, not a
hope. And accounting must stay conservative under uncertainty: an
unfilled or partially-filled parent cannot be marked at fantasy
prices, so open exposure is valued at the close LESS the execution
model's own declared worst-case cost fraction -- unconditionally. The
model that promises tight fills gets marked tighter; the model that
admits wide worst cases pays for the admission in its own equity
curve. That self-declared-worst-case rule prevents the classic
cheat where an optimistic execution model flatters the strategy it
executes.

*In this library:* `backtest/ExecutionAwareBacktester.java` -- parent
orders from signals (entry sized to available cash, exit for the
whole position), arrival-price TCA per `backtest/ParentOrder.java`,
and open exposure marked at close less the model's worst-case cost
fraction, "unconditional" per the javadoc.

### 488. "Walk-forward analysis: what is the cold-start bug, and when must the efficiency ratio refuse to be a number?"

Walk-forward splits history into rolling train/test windows,
optimizes on train, evaluates on test, and concatenates the test
segments into one honest out-of-sample record. The cold-start bug:
if each test window's backtest starts from bar zero OF THE TEST
WINDOW, every indicator (a 200-bar moving average, a GARCH state)
begins empty and the first weeks of every test segment trade on
garbage -- the measured out-of-sample performance is polluted by an
artifact no live system would have, because live systems have
yesterday. The fix is WARM evaluation: the backtest sees the
preceding train bars for indicator warm-up but only TRADES from the
test boundary -- an engine-level `tradeFrom` parameter, so equity and
trades cover only the test window while state is fully formed at its
first bar. The efficiency ratio -- out-of-sample performance over
in-sample -- is the overfitting gauge (near 1: robust; near 0:
curve-fit). But it is a RATIO, and when the in-sample objective
itself is negative (the optimizer failed to find anything that even
backtests well), a ratio of two losses can print "0.5" and read as
robust. The honest output there is NaN: the question "how much of
the in-sample edge survived" has no meaning when there was no
in-sample edge to survive.

*In this library:* `backtest/Backtester.java` -- the `tradeFrom`
overload (warm-up bars visible, trading and equity from the boundary
only) and `backtest/validation/WalkForwardAnalyzer.java`, whose
javadoc states both the warm-evaluation design and the
NaN-efficiency rule verbatim.

### 489. "You grid-searched 400 parameter combinations and the winner Sharpes 2.1. What number do you compute before believing it?"

The deflated Sharpe ratio of the winner -- the multiple-testing
haircut a grid search owes by construction. The logic: even if all
400 combinations were pure noise, the MAXIMUM of 400 noisy Sharpe
estimates is far above zero; extreme value theory gives the expected
maximum Sharpe under the null as a function of the number of
effectively independent trials and the variance of Sharpe estimates
across them. The deflated Sharpe is then the probabilistic Sharpe of
the winner measured against THAT expected-max benchmark rather than
against zero: the probability the winner's true Sharpe exceeds what
the best of 400 noise series would show. Values near 1 mean the
winner survives its own search; below roughly 0.95, the "best"
combination is indistinguishable from being merely the luckiest --
your 2.1 might be exactly what the lottery of 400 tickets pays.
Practical notes: the trials' Sharpe dispersion comes from the grid
results you already have; correlated combinations (neighboring
parameters) reduce the effective trial count, so the raw-count
version is conservative in the right direction; and non-finite
objectives must rank last in the search itself so a NaN never
"wins." The workflow discipline: the grid search API should hand its
winners straight to the deflation calculation -- making the haircut a
default step, not a virtue.

*In this library:*
`backtest/validation/GridSearchOptimizer.java` -- the grid search
with non-finite objectives ranked last, plus the deflation hook whose
javadoc calls it "the MULTIPLE-TESTING HAIRCUT for the grid's
winner," delegating to
`backtest/validation/SharpeValidation.deflatedSharpe`.

### 490. "Why does K-fold cross-validation leak on financial data, and what is the purge/embargo arithmetic that fixes it?"

Two leaks. Label overlap: financial labels span TIME -- a sample at t
labeled with the return over (t, t+h] contains information about
every bar up to t+h; if a training sample's label window overlaps
the test fold, the model trains on the test answer. Shuffled K-fold
maximizes this leak by design (adjacent days land in different
folds). Serial correlation: even with non-overlapping label windows,
returns adjacent to the test fold carry information about it (vol
clusters, trends persist), so training samples immediately after the
test fold still leak. The fix is purged K-fold with an embargo, and
the arithmetic is hand-checkable: for a test fold spanning [t0, t1),
the training set excludes (purges) every sample whose label window
(s, s+h] intersects the test fold -- so samples in
[t0 - h, t1) are gone -- and additionally embargoes a further
`embargo` samples after the fold: training resumes only at
t1 + h + embargo. What remains is [0, t0 - h) on the left and
[t1 + h + embargo, n) on the right. The cost is deliberate: purging
and embargoing SHRINK the training set, and if your result only
holds without them, the result was the leak.

*In this library:* `backtest/validation/PurgedKFold.java` -- the
javadoc gives exactly this arithmetic (training resumes at
`t1 + labelHorizon + embargo`) and the tests hand-check the fold
boundaries.

### 491. "Define the probability of backtest overfitting, and describe the two calibration cases any implementation must pass."

PBO, via combinatorially symmetric cross-validation (CSCV --
Bailey, Borwein, Lopez de Prado & Zhu): take the per-period returns
of ALL n strategy configurations you tried (not just the winner),
split time into blocks, and enumerate every way to choose half the
blocks as in-sample. For each split: pick the configuration that
wins in-sample, then find its RANK among all n configurations
out-of-sample; convert the rank to a relative percentile w and
record the logit `lambda = ln(w / (1 - w))` -- positive means the
in-sample winner stayed above-median out-of-sample. PBO is the
fraction of splits with negative logit: the probability that your
selection process picks something that underperforms the median of
its own rivals out-of-sample. PBO near 0: selection finds something
real; PBO >= 0.5: pure noise-mining -- selecting the winner is no
better than picking randomly. The two calibration cases that keep
an implementation honest: feed it configurations where one has
genuine skill (a persistent mean shift) -- PBO must come out near 0;
feed it pure iid noise configurations -- PBO must come out near 0.5
(NOT 1.0: noise means the winner is random out-of-sample, i.e.,
below median half the time). An implementation that returns 1.0 on
noise has confused "no skill" with "anti-skill" and will slander
real strategies too.

*In this library:*
`backtest/validation/OverfitProbability.java` -- CSCV over the full
return matrix with the logit distribution in the result; the javadoc
states the PBO >= 0.5 noise reading, and the skill/noise calibration
pair is exactly how the tests pin it.

### 492. "Your Sharpe is 1.4. Give me a confidence interval -- and why is the iid bootstrap the wrong tool?"

Resample the return series many times, recompute Sharpe on each
resample, and read the interval off the resulting distribution --
that is the bootstrap. The iid version (draw individual returns with
replacement) is wrong for exactly one reason: it DESTROYS the serial
structure of returns. Real return series have volatility clustering
and short-horizon momentum/reversal; shuffling returns into iid
soup produces resamples that are smoother than reality, so the
Sharpe sampling distribution comes out too tight, and the interval
is falsely confident -- the classic way to report "1.4 +/- 0.2" when
the honest answer is "1.4 +/- 0.6". The stationary block bootstrap
(Politis-Romano) fixes it: resample contiguous BLOCKS of returns,
preserving local dependence inside each block, with block lengths
drawn geometrically (mean length L) and wrap-around at the series
end -- the geometric lengths keep the resampled series stationary,
avoiding the seam artifacts of fixed-length blocks. The honest
question the interval answers is not "was my Sharpe 1.4" (it was)
but "how different could it have been" -- and for strategy decisions
the actionable output is the LOWER percentile: a 1.4 Sharpe whose
5th percentile is 0.1 is a coin you have flipped once. Compare the
block-bootstrap interval to the iid one on your own data; the
widening you observe is the autocorrelation you were about to
ignore.

*In this library:* `backtest/validation/BlockBootstrap.java` --
Politis-Romano stationary block bootstrap with geometric mean block
length; the javadoc names the falsely-confident iid failure as the
class's reason to exist.

### 493. "PSR, DSR, minimum track record length -- one family, three questions. Sort them out."

All three live on the same result: the sampling distribution of an
estimated Sharpe ratio, adjusted for the sample's skewness and
kurtosis (fat tails and negative skew widen it -- a Sharpe from
option-selling returns is less trustworthy than the same Sharpe
from a symmetric series, and the correction quantifies by how
much). Probabilistic Sharpe (PSR) answers: given n observations
with this skew and kurtosis, what is the probability the TRUE
Sharpe exceeds a benchmark (often zero)? It converts "Sharpe 1.2"
into "94% probable the real Sharpe is positive" -- a statement a
committee can calibrate against. Deflated Sharpe (DSR) answers the
selection question: it is PSR with the benchmark raised from zero
to the Sharpe you would EXPECT the best of N tried strategies to
show under pure noise -- the multiple-testing correction of Q489;
report PSR for a strategy you designed once, DSR for anything that
won a search. Minimum track record length (minTRL) inverts PSR:
given the observed Sharpe and moments, how many observations are
needed before PSR would clear a stated confidence that the true
Sharpe beats the benchmark? It answers the allocator's actual
question -- "how much longer must we watch this fund" -- in months,
and for modest Sharpes the answer (often years) is itself the
lesson: track records short enough to market are usually too short
to prove anything.

*In this library:*
`backtest/validation/SharpeValidation.java` --
`probabilisticSharpe` (skew/kurtosis-adjusted),
`deflatedSharpe` (PSR against the expected-max-of-trials
benchmark), and `minTrackRecordLength`, all in the same units so
the three answers reconcile.

### 494. "Your fund returned 18% and the benchmark 15%. Prove you added value -- what four numbers, and what alignment trap first?"

Raw outperformance proves nothing at unknown risk: 18% via beta 1.4
is UNDERperformance in disguise. The four numbers: beta --
Cov(r_s, r_b)/Var(r_b), how much of the strategy is just the
benchmark amplified; at beta 1.4, "beating by 3%" means the market
did the lifting. Alpha -- the annualized Jensen intercept,
mean(r_s) - beta * mean(r_b): the return the benchmark cannot
explain; THIS is the value-add claim, and at beta 1.4 the example
above has negative alpha. Information ratio -- annualized active
return over tracking error: alpha per unit of benchmark-relative
risk, the number allocators compare across managers (sustained IR
above 0.5 is genuinely good). Up/down capture -- mean strategy
return in benchmark-up periods versus benchmark-down periods,
separately: the asymmetry summary; capturing 90% of up moves and
60% of down moves is the profile everyone wants, and it does not
show up in any single moment statistic. The trap before any of it:
ALIGNMENT. The two return series must be same-frequency,
same-dates, element-by-element -- one misaligned index destroys beta
(it silently becomes a lead-lag estimate near zero, which then
inflates "alpha" to the whole return). Align first, then compare;
a suspiciously low beta with a suspiciously high alpha is the
misalignment signature.

*In this library:* `backtest/BenchmarkComparison.java` -- beta,
Jensen alpha, information ratio, and up/down capture from aligned
series; the javadoc warns that misalignment turns beta into a
lead-lag estimate.

### 495. "Your limit-order strategy backtests at Sharpe 3 with fills whenever price touches your level. What is wrong, and what does the tick-level fix look like?"

Touch-equals-fill is the most flattering lie in backtesting: at a
real venue your limit order joins the BACK of a price-time priority
queue, and a touch that trades 200 shares against a level where
8,000 rest ahead of you fills none of yours. Worse, the fills
touch-based logic grants most eagerly are the adverse-selected
ones: the level trades through when everyone ahead of you got
taken -- i.e., when the price is moving against you -- so the
fictional fills cluster on exactly the trades you least wanted.
The tick-level fix, with trade-print data (no book snapshots):
market orders fill instantly at last trade price plus/minus a
half-spread charge -- aggression pays the spread, always; limit
orders fill fully only when a print trades THROUGH the price
(strictly better than your level -- everyone at your level must be
gone), and at exactly your price the order starts behind a
configured `defaultQueueAhead` of simulated resting quantity that
prints at that price consume first -- your fill arrives only after
the queue ahead has traded. This one-parameter queue model is
still optimistic (no cancels ahead of you modeled), which is the
honest direction to state: if the strategy dies under it, it
certainly dies live; surviving it earns a live pilot, not a
launch.

*In this library:* `backtest/tick/TickBacktester.java` -- the fill
model exactly as above (through-prints fill, at-price prints drain
`defaultQueueAhead` first, half-spread on market orders), driving a
`TickStrategy` on trade prints.

### 496. "State the execution conventions of a bar-based single-instrument backtester -- and why must stops be checked INTRABAR?"

Every bar engine embeds an execution model whether its author knows
it or not, so state it explicitly. The defensible convention set:
signals generated from bar t's data fill at bar t's CLOSE (adjusted
for slippage) -- filling at the same bar's open is lookahead
(the signal used data from a bar that had not opened yet), and
filling at next open is defensible but must be stated, because
strategy ranks reshuffle under the change. Stop-loss and
take-profit levels are different in kind: they are RESTING orders,
live continuously, so they must be evaluated INTRABAR against the
bar's high and low -- a stop 2% below entry, on a bar that plunges
5% intrabar and recovers by the close, fired in reality at the
stop price; a close-only check misses the exit entirely and books
the recovery you never got. The residual ambiguity to acknowledge:
when one bar's range crosses BOTH stop and target, bar data cannot
tell you which hit first -- the conservative engine assumes the
stop. Costs per side, position sizing rule, and whether equity is
marked on close complete the contract. The meta-point: none of
these choices is "correct," but all of them must be documented and
constant across every strategy you compare, or the comparison
measures conventions, not strategies.

*In this library:* `backtest/Backtester.java` -- the javadoc states
the contract: fills at bar close adjusted for slippage, stop-loss /
take-profit evaluated intrabar against the bar's high/low, with
per-strategy overrides falling back to `BacktestConfig` defaults.

### 497. "How much should you bet? Derive the Kelly answer, then explain why nobody runs it at full size."

For a return stream with mean mu and variance sigma^2 per period,
the growth-optimal (Kelly) fraction is f* = mu / sigma^2 -- the
leverage maximizing expected LOG wealth, i.e., long-run compound
growth. Example: mu = 8% annually, sigma = 16% -> f* = 0.08/0.0256
~= 3.1x leverage. Nobody runs that, for three compounding reasons.
Estimation error: f* is quadratically sensitive to mu, the
parameter you know worst (Q461's lesson, squared) -- overestimate
mu by 2x and you bet 2x optimal, which under Kelly math has the
same growth as HALF optimal but vastly worse drawdowns; the
penalty for over-betting is asymmetric. Drawdown reality: even
true-parameter full Kelly routinely visits 50%+ drawdowns --
mathematically survivable, but investors redeem and firms shut
strategies long before the math recovers (Q470). Non-stationarity:
mu and sigma drift, so yesterday's f* is aimed at a distribution
that no longer exists. Hence the practitioner's standard:
half-Kelly, which sacrifices ~25% of growth rate for roughly half
the variance -- trading growth for drawdown. The neighboring rules
in the same toolkit: fixed-fractional risk (risk a constant
fraction of equity per trade via the stop distance),
inverse-volatility weighting across assets, and volatility
targeting over time -- all are cousins of the same idea: size
positions by risk, not by conviction adjectives.

*In this library:*
`backtest/portfolio/PositionSizing.java` -- `kellyFraction`
(mu/sigma^2), `halfKelly` (the javadoc calls it the practitioner's
standard, "trading growth for drawdown"), fixed-fractional,
inverse-volatility, and volatility-targeting rules.

### 498. "The backtest spans 2016-2024 and Sharpes 1.5. What regime question do you ask before allocating?"

"Where did the money come from?" -- as a distribution across market
regimes, not a single aggregate. Slice the equity curve by regime:
calm bull (2017), vol spike (Feb 2018, Mar 2020), grinding bear
with rate shocks (2022), rotation-heavy recovery (2023). Then ask
three questions of the slices. Concentration: does one regime
contribute most of the P&L? A "1.5 Sharpe strategy" that made
everything in 2020's vol explosion is a long-vol bet with seven
years of costume. Sign stability: does it merely earn LESS in
hostile regimes, or does it LOSE -- a strategy that degrades
gracefully is robust; one that flips sign is two strategies, and
you own both. Mechanism coherence: does the per-regime pattern
match the claimed edge? A mean-reversion strategy SHOULD suffer in
trending regimes -- if it doesn't, it is not what its author thinks
it is, which is worse than losing. The tooling is everything from
this section pointed at slices: per-regime benchmark comparison
(Q494), drawdown episodes mapped onto regime boundaries (Q470),
block-bootstrap intervals per slice (Q492) since each slice is
short. The honest conclusion format: "earns in A and B, bleeds
controlled amounts in C, has never seen D" -- with position sizing
(Q497) set by the bleed, and D acknowledged as an open risk rather
than assumed away.

*In this library:* the slicing toolkit --
`backtest/BenchmarkComparison.java` per regime window,
`backtest/DrawdownAnalytics.java` for episode-to-regime mapping,
`backtest/validation/BlockBootstrap.java` for honest per-slice
intervals over short samples.

### 499. "The validated backtest says Sharpe 1.2. What should you EXPECT live, and where does the gap come from?"

Expect materially less -- experienced desks plan for one-half to
two-thirds of validated backtest performance, and the decomposition
of the gap tells you which discount applies. Selection residue:
even after deflation (Q489, Q493), the strategy you chose to launch
was still the best-looking of everything you researched -- some
regression to the mean is baked in; DSR shrinks this discount but
cannot zero it. Execution reality: backtest fills, however careful
(Q495, Q487), are a model; live fills include venue quirks, queue
losses to cancels, requotes, outages, and the impact of YOUR OWN
flow -- TCA against arrival price, run from day one, converts this
from anxiety into a measured number you can compare with the cost
model's prediction. Alpha decay and crowding: the inefficiency was
being consumed while you validated it (Q477 is the natural
experiment). Data mismatch: the live feed differs from the
research feed in timestamps, adjustments, and coverage in ways
that only surface in production. The management discipline that
follows: launch at a fraction of target size; define the
statistical tripwire IN ADVANCE (e.g., "live falls below the
backtest's 5th bootstrap percentile for a quarter" -- Q492) so the
kill decision is a rule, not a mood; and treat a live-versus-cost-
model TCA gap as the first diagnostic to check, because it is the
only one you can fix without a new idea.

*In this library:* the paper-live instrumentation:
`backtest/ExecutionAwareBacktester.java` (arrival-price TCA in
research), `microstructure/TransactionCostAnalyzer.java` (the same
discipline on live fills), `backtest/validation/BlockBootstrap.java`
(the pre-declared tripwire percentile).

### 500. "You are the final gate before a strategy goes live. Write the checklist."

Ten gates, each earlier in this section, now in firing order.
(1) Data honesty: point-in-time universe, corporate events
processed in the right order, no survivor-only feeds (Q471, Q485).
(2) Leak-free validation: purged K-fold with embargo, warm
walk-forward with the NaN-efficiency rule respected (Q490, Q488).
(3) Multiple-testing discipline: DSR of the winner against
everything tried, PBO from the FULL trial matrix -- both demand you
kept records of every configuration, which is itself a gate
(Q489, Q491, Q493). (4) Uncertainty stated: block-bootstrap
interval on the headline Sharpe; the LOWER bound is the number
that goes in the memo (Q492). (5) Costs at intended size:
institutional cost model, capacity stated as the AUM where net
alpha crosses zero (Q486, Q479). (6) Execution realism: parent-
order or queue-aware backtest appropriate to the strategy's
frequency (Q487, Q495, Q496). (7) Benchmark honesty: alpha after
beta, IR, capture asymmetry (Q494). (8) Regime map: where it
earns, where it bleeds, what it has never seen (Q498). (9) Risk
plumbing BEFORE the first order: pre-trade limits with the
reduce-not-flip rule, position/VaR/Greek limits set by named
owners, drawdown-duration triggers (Q466, Q467, Q468, Q470).
(10) The live plan: fractional initial size, pre-declared
statistical tripwires, TCA from day one, and expectations set at
half the backtest (Q499, Q497). The theme the whole library keeps
repeating: every gate exists because something, somewhere, refused
to return a flattering number -- measure, don't assume; prove,
don't claim; and when reality imposes a limit, document the limit
instead of hiding it.

*In this library:* the closing thought is quoted from
`docs/LEARN.md` Part III's last paragraph; the gates map one-to-one
onto `alpha/`, `backtest/`, `backtest/validation/` and `risk/` --
the checklist IS the package structure.

---

## How to use this guide

Don't read it like a script — scripted answers die on the first follow-up. For
each question you want to own, do three things:

1. **Run the class's test.** Every class cited above is pinned by tests
   under `src/test/java/com/quantfinlib/` — usually `<Class>Test.java`,
   sometimes a themed suite (`ComponentVar` lives in
   `RiskAllocationTest`). Run it and read what it asserts — the
   assertions are the claims from the model answer, pinned to numbers
   (`mvn test -Dtest=RiskAllocationTest` style).
2. **Read the javadoc, then the code.** The javadoc headers in this library
   are written as explanations, not API listings — most of the model
   answers above are compressed from them. The classes are deliberately
   small; almost every one cited here reads in one sitting.
3. **Re-derive one number.** Pick a single assertion — the Feller ratio, a
   component-VaR share, the deflated Sharpe of a grid winner, a
   backward-shift probe run — and reproduce it by hand or in a scratch
   `main`. Explaining a number you have personally re-derived sounds
   completely different from reciting one, and it shows.

If a term here is unfamiliar, Parts I–III above teach all of them from
zero; [COOKBOOK.md](COOKBOOK.md) has runnable recipes for the workflows
these questions come from; [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) is
the deep end of Round 5.
