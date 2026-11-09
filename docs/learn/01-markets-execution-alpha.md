# Part I, The Finance: markets, execution and alpha (sections 1 to 8h)

[Index: LEARN.md](../LEARN.md) | [Next: Part I, The Finance: risk, derivatives, rates and credit (sections 9 to 10u)](02-risk-derivatives-rates-credit.md)

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
mid). `fx/AggregatedBook.java` is a good first read: it is short, uses
plain arrays, and its javadoc explains each decision.

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
engine; start there, since every rule is spelled out. `orderbook/HftOrderBook.java`
is the same logic rebuilt for speed (Part II explains how), with
`submitIoc`/`submitFok`/`submitPostOnly`. A test drives both with identical
random orders and demands identical books, so the readable one is the
executable specification of the fast one.

### 2b. Call auctions: one price for everyone

Continuous trading matches each order the moment it arrives. At the open,
at the close and after a halt, venues do something different: they
collect orders for a while without matching any of them (the call), then
match everything at once at a single price (the uncross). Everyone who
trades in the uncross gets the same price, which is why closing auctions
set the official close that index funds and benchmarks use.

During the call the venue publishes an indicative price, volume and
imbalance so participants can react before the book freezes. At the
uncross it picks the price that trades the most shares.

Formally, let `D(p)` be the buy quantity willing to pay at least `p` and
`S(p)` the sell quantity willing to accept at most `p`. Market orders
count at every price. The executable volume is `V(p) = min(D(p), S(p))`.
The uncross price is chosen by three rules in order: maximise `V(p)`;
among ties, minimise the imbalance `|D(p) - S(p)|`; among remaining ties,
take the price nearest the reference price. Tied prices form one
contiguous range, so the result is unique. This is the standard
maximum-executable-volume rule; Xetra and Euronext add a market-pressure
step between the second and third rules, which the library leaves out
(Deutsche Börse, Xetra market model for auctions; Euronext, Trading
Manual, auction section).

Worked example. Buys: 100 at 10, 200 at 9. Sells: 150 at 8, 100 at 9.

| p  | D(p) | S(p) | V(p) |
|----|------|------|------|
| 8  | 300  | 150  | 150  |
| 9  | 300  | 250  | 250  |
| 10 | 100  | 250  | 100  |

The uncross price is 9 with volume 250 and a buy imbalance of 50. Orders
fill in price-time priority at the single price 9: the 100 bid at 10
fills first, then 150 of the 200 bid at 9, leaving 50 on the bid.

*In this library:* `orderbook/AuctionBook.java` (`openCall`,
`submitLimit`, `submitMarket`, `publishIndicative`, `uncross`, and
`transferResidual`, which moves the unfilled orders into an
`HftOrderBook` when continuous trading starts). Prices are integer ticks,
as in `HftOrderBook`. The older `microstructure/Auction.java` is the
simple analytics version. See COOKBOOK recipe 330.

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
determines your probability of getting filled, which makes it a primary
input to any market-making decision.

*In this library:* `marketdata/ItchCodec.java` decodes ITCH-style messages;
`marketdata/L3BookBuilder.java` rebuilds the full book *and* tracks your own
order's exact queue position (`sharesAhead`); `microstructure/QueueModel.java`
turns queue position into a fill probability.

### 4. Market structure, or: equities and FX are different planets

**US equities** are exchange-traded: a share of AAPL trades on ~16 exchanges
plus **dark venues** (trading places that don't display their orders;
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
  the WM/R 4pm London benchmark, computed over a 5-minute window.
  Executing evenly across that window replicates the benchmark.

*In this library:* on the equities side, `marketdata/Nbbo.java` and
`microstructure/CircuitBreakers.java`. On the FX side, `fx/FxTierBook.java`
(tiers), `fx/LpScorecard.java` + `fx/LpRouter.java` (measuring and routing
around last look), `trading/LastLookGate.java` (the maker's side, done
symmetrically), `execution/WmrFixingScheduler.java` (the fix).

### 5. Market making: selling immediacy for a living

A **market maker** quotes both sides at once (bid 1.0850, ask 1.0852),
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
- **POV** (percentage of volume): react to *actual* volume: "be 10% of
  whatever trades." One subtlety: a 10% share of *total* volume, your own
  fills included, means trading `p / (1 - p)` times *other people's*
  volume (11.1% of it at p = 10%). Targeting `p` times total volume
  including your own fills chases its own tail and settles at
  `p / (1 + p)`, about 9.1%.
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
`execution/BenchmarkExecutor.java` is the **dynamic** version. It is one executor
covering all of VWAP/TWAP/Arrival/IS/Close/Open/POV that re-decides each
interval from live spread, depth, volatility, the volume curve, estimated
impact and an alpha signal. Its implementation-shortfall benchmark follows
the Almgren-Chriss trajectory `1 - sinh(kT(1 - f)) / sinh(kT)` for elapsed
fraction f (urgency kT set by `withShortfallUrgency`, default 2). TCA is
`microstructure/TransactionCostAnalyzer.java`. Smart order
routing (which venue gets each child) is `execution/SmartOrderRouter.java`
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
  always wild. The right question is "volatile *for this time of day*?",
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
  icebergs from direct evidence: a single print bigger than what was displayed
  means hidden size was there.
- *"How much will MY order move the price?"* Instead of assuming a formula,
  `KylesLambda` learns it from the tape: regress price changes on signed
  flow and the slope is the market's depth (Kyle, 1985). One caveat done
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
  today's imbalance feed. Its javadoc carries a caveat: the library
  ships the structure and the tests, but the imbalance-feed mapping must
  be validated against your venue's data.

**Executing a whole basket.** A pension fund rarely trades one stock; it
transitions a *portfolio* (sell the old holdings, buy the new ones). Run N
independent algos and a new risk appears that none of them can see: if the
buys fill fast and the sells lag, the fund is carrying a large unintended
market bet mid-flight. `execution/PortfolioExecutor.java` coordinates
per-symbol executors with the two rules that only exist at basket level: a
**leg-balance band** (the ahead leg slows down until the lagging leg
catches up, never the reverse, since pushing a child past its own
schedule would break its benchmark) and a **per-interval budget** that
goes to the riskiest names first when it binds. Both rules only ever
*reduce* what a child sends, so each symbol's own schedule stays intact.

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
interesting part is not the regression; it is the **evaluation
discipline**. Any self-updating signal faces the same trap: it grades its
own homework.
Two safeguards close that trap here:

1. Every prediction is recorded *before* the outcome updates the weights,
   and the rolling **out-of-sample IC** is computed on those predictions —
   so the learner is always scored on what it genuinely didn't know.
2. Timing: at 10:00:01 the signals already *contain* the move from
   10:00:00 to 10:00:01 (momentum is the move). Fitting current signals to the return that
   just happened is a "nowcast" that looks brilliantly predictive and
   predicts nothing. The learner therefore trains each return against the
   signals **snapshotted one interval earlier**.

Even then, the learned alpha stays silent (emits 0) until its
out-of-sample IC is significant: the IC minus two EWMA standard errors
(`microstructure/IcSignificance.java`) must be above zero, so a lucky
first hour does not count. This is the same discipline as §8's
walk-forward validation, shrunk to streaming scale.

**Two more signal ideas worth knowing.** Related instruments don't move
simultaneously: EURUSD leads EURJPY, index futures lead the cash basket:
the liquid instrument moves first and the follower echoes it a moment
later. `LeadLagEstimator` measures that lead live (which lag, how strong)
for cross-hedging and pricing, and reports no lead unless the best lag
clears a band corrected for the number of lags tried and beats the
contemporaneous correlation. And not all days share a shape: option-expiry
days trade 2-3× normal volume with a violent close, half days compress the
U-curve, FX fixing days spike at the fix. `DayTypeProfiles` gives each
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
     ones that later died. (This library ships a test in which a naive
     backtest overstates final equity against a delisting, so the bias
     is measured rather than asserted.)
   - **Look-ahead in fills**: a signal computed from today's close cannot
     trade at today's close. The bar backtesters fill at the next bar's
     open by default (`backtest/FillPolicy.java`, `NEXT_OPEN`).
   - **Overlapping samples**: an IC computed every day on 20-day forward
     returns shares 19 days with its neighbour, so the plain `s/√n`
     standard error is far too small. IC, Fama-MacBeth and calendar
     t-stats use Newey-West (1987) HAC standard errors
     (`alpha/NeweyWest.java`).
   - **Costs**: spreads, fees, and **market impact** (empirically about
     √size: trading 4× the size costs about 2× the impact per share).

*In this library:* the `alpha` package — `Factors`, `SignalEvaluator` (IC),
`AlphaValidation` (walk-forward, permutation), `AlphaBacktester`
(cost-aware), `PortfolioConstruction` (neutralization); `data/PointInTimeUniverse.java`
and `backtest/portfolio/` for survivorship control.

### 8b. Portfolio construction: turning forecasts into weights

A validated signal (§8) still leaves the hardest practical question
unanswered: *how much* of each thing do you hold? The classical answer
is Markowitz **mean-variance optimization**: feed in expected returns
and a covariance matrix, get back the weights with the best return per
unit of risk. `optimization/PortfolioOptimizer.java` implements it:
`maxSharpe` for the best risk-adjusted portfolio, `minVolatility` for
the safest fully-invested one, `efficientFrontier` for the whole
risk/return menu. Each is a convex quadratic program, solved exactly by
an active-set method (`optimization/QuadraticProgram.java`).

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
  where every asset contributes *equally* to total risk.
  `RiskParityOptimizer.equalRiskContribution` solves it through Spinu's
  (2013) convex formulation with damped Newton steps, `riskBudget`
  handles unequal budgets, and `riskContributions` audits any existing
  book ("that 60/40 portfolio is actually about 90% equity risk" is this
  one function call).
- **Anchor the forecasts.** **Black-Litterman** starts from the
  returns *implied* by market weights (if the market holds this
  portfolio, what expectations justify it?
  `BlackLitterman.impliedEquilibriumReturns`), then blends in your
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
volatility (`zScoreWeights`, then `sectorNeutralize`, `betaNeutralize`
and `inverseVolBudget`). The `optimization` package is for the *other*
shape of problem: a handful of assets or strategies whose covariance
you can actually know.

*Real-life case:* a fund allocates across its 5 strategies. Naive
`maxSharpe` on their track records puts 80% into the strategy with the
best (that is, luckiest) backtest. Risk parity spreads the book so no
single strategy dominates the risk. Black-Litterman starts from the
current capital allocation as the equilibrium and lets the PM express
one view ("stat arb beats trend by 2%, modest confidence") as a tilt
rather than an upheaval. The constrained pass caps any strategy at
35% and charges turnover, and the resulting rebalance is small enough
to actually execute.

### 8c. The full trading pipeline

A systematic desk's work can be written as one chain of eight stages:

```
alpha discovery -> signal generation -> nested validation -> out-of-sample
scoring -> selection -> risk-managed sizing -> portfolio constraints ->
optimal execution
```

Each step in the chain is a place where money is made or quietly lost,
and each stage has a precise statement and a home in this library:

1. **Alpha discovery** — research produces a candidate set
   `A = {a_1, a_2, ..., a_K}` of alpha rules. Nothing is trusted yet: K
   is exactly the "number of trials" that every later multiple-testing
   statistic must know about. *In this library:* `alpha.Factors` and
   `alpha.AlphaFactor` (the candidate rules), `alpha.AlphaContext` (the
   frozen data panel they compute on).
2. **Signal generation** — `s_t(k) = a_k(X_{t-1})`: the signal at time t
   may use only information known before trade time. The subscript
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
   Stability)`: a candidate is a vector of qualities, not one Sharpe.
   Drawdown (depth and duration), turnover (costs eat gross alpha) and
   stability across folds all enter before anyone looks at the mean.
   *In this library:* `alpha.SignalEvaluator` (IC/IR/turnover),
   `alpha.AlphaBacktester` (cost-aware PnL),
   `backtest.DrawdownAnalytics`, `backtest.BenchmarkComparison`.
5. **Selection** — accept iff `Q_k > tau_Q`. Because K rules were tried,
   the threshold must be a multiple-testing threshold: the expected
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

The pipeline is also the debugging map: when live PnL disappoints, walk
the stages backwards. Did execution pay more than the
objective said (stage 8)? the constraints bound (7)? sizing divided by a
stale sigma (6)? the threshold was too soft for K trials (5)? the score
ignored turnover (4)? the folds leaked (3)? the signal peeked (2)? or the
candidate was never real (1). A post-mortem usually lands on one stage.
Diagram 19 in [DIAGRAMS.md](../DIAGRAMS.md) draws this pipeline end to end.

**The same line, every asset class.** The pipeline above used equity
nouns, but the arrows are asset-blind. The middle of the line
(validation, scoring, selection) literally runs the same classes on any
return series -- a fold does not care whether the bars are a stock, a
currency pair or a CDS spread. Only the ends change: what counts as a
signal, what "size" is denominated in, and what execution physically
means. One table, five markets:

| Stage | Equities | FX | Rates | Credit | Commodities |
|---|---|---|---|---|---|
| Discovery | `alpha.Factors`, `screener.StockScreener` | carry from forward points, `fx.SwapPointsCurve.impliedCarry` | curve factors (level/slope/curvature), `rates.NelsonSiegel` | hazard curve from CDS quotes, `credit.CreditCurve` | curve shape (contango/backwardation), `commodities.CommodityCurve` |
| Signals | `alpha.AlphaFactor` on `alpha.AlphaContext` | points vs realized drift (carry signal) | rolldown/carry via `rates.YieldCurve.forwardRate` | CDS-bond basis via `credit.CreditSpreads.zSpread` minus `CdsPricer.parSpread` | roll yield via `CommodityCurve.annualizedRollYield` |
| Validation | `backtest.validation.PurgedKFold` + `WalkForwardAnalyzer` | same folds; embargo spans the weekend gap | same folds, on curve-factor series | same folds, on spread series | same folds; purge across roll dates |
| Scoring | `alpha.SignalEvaluator`, `alpha.AlphaBacktester` | same, with spread + last-look reject costs | same, PnL stated in DV01 terms | same, PnL stated in spread-DV01 terms | same, PnL includes the roll term |
| Selection | `backtest.validation.SharpeValidation`, `OverfitProbability` | same -- and K counts trials across columns | same | same | same |
| Sizing | inverse-vol, `alpha.PortfolioConstruction` | vol-target, `backtest.portfolio.PositionSizing` + `volatility.EwmaVolatility` | DV01-matched, `rates.SwapPricer.dv01` | spread-DV01 (risky annuity), `credit.CdsPricer.riskyAnnuity` | inverse-vol on futures returns, `PositionSizing` |
| Constraints | beta/sector caps, `optimization.ConstrainedPortfolioOptimizer` | settlement + counterparty limits, `risk.SettlementRiskAnalyzer`, `risk.CounterpartyExposureTracker` | key-rate bucket limits, `rates.KeyRateDurations` | issuer concentration caps, `risk.ConcentrationRisk` | scenario limits, `risk.StressTester` |
| Execution | `execution.BenchmarkExecutor`, `execution.AdaptiveSor` | `fx.LpRouter` over `fx.FxTierBook`; fixings via `execution.WmrFixingScheduler` | par-swap DV01 hedging, `rates.SwapPricer` | upfront on the standard coupon, `credit.CdsPricer.upfront` | calendar-spread rolls, `execution.FuturesRollAlgo` |

Private markets get stages 1-5 only: capital calls and distributions
arrive quarterly on the manager's clock, so there is no intraday
execution to optimize -- the pipeline ends at selection, and the tooling
(`markets.PrivateMarketAnalytics`, section 10c) is built for exactly
that truncation.

### 8d. Factor risk models: covariance for thousands of stocks

A sample covariance matrix of 3,000 stocks has about 4.5 million entries
and is estimated from a few hundred days of returns, so most of it is
noise and an optimiser will find and exploit that noise. A factor model
replaces it with a small structure: each stock's return is explained by a
market factor, its industry, and a handful of styles (size, momentum,
volatility), plus a part specific to the stock. Risk is then exposures
times a small factor covariance plus a diagonal of specific variances.

Formally, for stock `i` on day `t`:

```
r_it = f_mkt,t + f_sector(i),t + sum_k x_ik,t f_k,t + u_it
V    = X F X' + D
```

`X` holds the exposures, `F` the factor covariance and `D` the specific
variances. The construction follows the Barra USE4 methodology (Menchero,
Orr and Wang 2011):

- Styles are standardised each day to a cap-weighted mean of 0 and an
  equal-weighted standard deviation of 1, clipped at plus or minus 3 and
  re-standardised (USE4 section 3).
- Factor returns come from a weighted least-squares regression with
  weights `sqrt(cap)`. The sector dummies sum to the market column, so the
  regression imposes `sum_s c_s f_s = 0`, with `c_s` the sector's cap
  share, and solves it exactly by substitution (Menchero 2010).
- `F` is a zero-mean EWMA covariance with a Newey-West (1987) correction
  for serial correlation, `F = C0 + sum_l (1 - l/(L+1)) (C_l + C_l')`, then
  scaled by a volatility regime adjustment `lambda_F^2`, an EWMA of
  `B_t^2 = mean_k (f_kt / sigma_k,t-1)^2` (USE4 section 4.3).
- Specific variances are EWMA residual variances shrunk toward the mean of
  their size bucket, `sigma_SH = v mean_n + (1 - v) sigma` with
  `v = q |sigma - mean_n| / (Delta_n + q |sigma - mean_n|)` (USE4 section
  5.3).

The decomposition of a portfolio `w` with risk `sigma = sqrt(w'Vw)` gives
the marginal contribution `(Vw)_i / sigma`, the percent contribution
`w_i (Vw)_i / sigma^2` (these sum to 1), and the factor contribution
`x_k (F x)_k / sigma^2` for portfolio exposure `x = X'w`, which with the
specific share also sums to 1. This is Euler allocation (Litterman 1996).

Worked example. A portfolio has exposure 1.2 to a single factor with 15%
volatility and specific variance 0.0004 (2% specific risk). Factor
variance is `1.2^2 x 0.0225 = 0.0324`, total variance 0.0328, total risk
18.1%. The factor's share is `0.0324 / 0.0328 = 98.8%`: diversifying names
does almost nothing for this book, cutting the factor exposure does.

*In this library:* `risk/FactorRiskModel.java` (`estimate`,
`decompose`, `Settings.defaults()` and `Settings.plain()`, which drops the
Newey-West, regime and shrinkage steps), and `alpha/AlphaRiskModels.java`,
which builds the inputs from a price panel, using log market cap for size
or trailing dollar volume when there are no fundamentals. Recipe 318.

### 8e. Turning a forecast into a portfolio with a risk model

A z-score says which names look good; it does not say how much to hold.
The Grinold rule converts scores to expected returns, and an optimiser
with the factor model's covariance turns expected returns into weights
that respect risk, trading cost and liquidity.

Formally, `alpha_i = IC x sigma_i x sqrt(H) x z_i` (Grinold 1994,
"Alpha is volatility times IC times score"), with `IC` the information
coefficient, `sigma_i` the name's volatility and `H` the holding horizon.
Each rebalance then solves

```
maximise  alpha'w - lambda w'(H V)w - kappa |w - w0|_1
```

with `kappa` the flat one-way cost and each trade capped at
`advTradeFraction x ADV x price / (capital x equity)` of the portfolio.

*In this library:* `alpha/AlphaBacktester.java`,
`run(ctx, factor, config, RiskOptions)` and `runTraced`, which returns
per-bar weights, P&L per name, cost and financing that sum to the net
return exactly. For bar-level portfolio strategies,
`backtest/portfolio/PortfolioBacktester.Participation` caps each symbol at
a fraction of its average volume and carries the unfilled target forward.
Recipes 319 and 323.

### 8f. Combining signals without double counting

Two momentum signals with slightly different lookbacks are mostly the
same bet. Adding them with equal weight counts that bet twice. A
combination should credit each signal only for what the earlier ones do
not already say, and weight it by how well it forecasts at the horizon
you actually hold.

Formally, on each date the factor z-scores are orthogonalised in the
order given (Gram-Schmidt), so `z'_k` is the part of factor `k` not
explained by factors `1..k-1`. The horizon-matched information
coefficient is

```
IC_k(t) = (1/H) sum_{l=0}^{H-1} corr(z'_k(t), r_{t+l -> t+l+1})
```

averaged over dates with exponential weights. A fast-decaying signal
loses weight as `H` grows (Grinold and Kahn 2000, ch. 13). Weights are
`w_k = (1 - delta) IC_k / sum_j |IC_j| + delta / K`, the IC-proportional
combination shrunk toward equal weights (Grinold and Kahn 2000, ch. 10).

Worked example. Orthogonalised ICs of 0.04, 0.02 and 0.01 give raw
weights 0.571, 0.286 and 0.143. With `delta = 0.3` the weights are
`0.7 x 0.571 + 0.1 = 0.5`, then 0.3 and 0.2.

*In this library:* `alpha/AlphaCombiner.java` (`fit` reads data only up
to `toIndex`, so later dates stay out of sample; the result is an
`AlphaFactor`). `alpha/AlphaReport.icTable` prints mean IC per factor per
horizon. Recipe 320.

### 8g. Where the P&L came from

A strategy that makes money may be making it for the wrong reason, for
example a value strategy that is mostly long momentum. Attribution splits
each day's P&L into the part explained by factor returns, the part from
stock-specific moves, the part from trading during the day, and costs.

Formally, with dollar holdings `h_i`, exposures `X_ik`, factor returns
`f_k` and specific returns `u_i`:

```
factor_k = (sum_i h_i X_ik) f_k
specific = sum_i h_i u_i
trading  = sum_i pnl_i - sum_k factor_k - specific
net      = sum_k factor_k + specific + trading + cost + financing
```

The last line holds exactly by construction. The same split is reported
for the long and short legs and by sector.

Worked example. Hold $600k of A with momentum exposure 1.0 and short
$400k of B with exposure -0.5. The book's momentum exposure is
`600k x 1.0 + (-400k) x (-0.5) = 800k`. A day with a 0.1% momentum factor
return books $800 to momentum, whatever the two stocks did on their own.

*In this library:* `alpha/BacktestAttribution.java` (`attribute`,
`cumulativeFactor`, `cumulativeSector`, `format`), fed by
`AlphaBacktester.runTraced`. Recipe 321.

### 8h. Capacity: how much money a strategy can take

A strategy's gross return does not depend on its size, but its impact
cost does. Trading twice as much moves prices more, so at some capital
the cost eats the edge.

Formally, under square-root impact the cost per dollar traded is
proportional to `sqrt(participation)`, and participation is proportional
to capital, so the drag on return grows as `sqrt(C)`. Net Sharpe falls
roughly as `(mu - k sqrt(C)) / sigma`. The capacity at a Sharpe floor is
the capital where the net Sharpe curve crosses the floor (Ratcliffe,
Miranda and Ang 2017).

Worked example. If impact costs 0.3 of Sharpe at $100m, it costs 0.6 at
$400m and 1.2 at $1.6bn. A strategy with gross Sharpe 1.5 has net Sharpe
1.2, 0.9 and 0.3 at those sizes, so its capacity at a Sharpe of 1 lies
between $100m and $400m.

*In this library:* `alpha/CapacityCurve.java` runs the same backtest at
each capital level; `capacityAt(floor)` interpolates in log capital and
returns NaN when even the smallest capital misses the floor. Recipe 322.

---

[Index: LEARN.md](../LEARN.md) | [Next: Part I, The Finance: risk, derivatives, rates and credit (sections 9 to 10u)](02-risk-derivatives-rates-credit.md)
