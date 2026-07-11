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
14. `docs/COOKBOOK.md` — twenty-two end-to-end recipes to modify and re-run.
15. Part IV below — once the above feels familiar, test yourself: 35 real
    quant/trading/HFT interview questions, each answered and tied back to
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

## Part IV — The interview room

135 questions actually asked in quant, algo-trading and HFT interviews —
each answered the way a strong candidate answers it, then tied to the
class in this library that implements the answer, so every answer is
**runnable**: read the answer, then read the code, then re-derive one
number yourself — that's the preparation that survives follow-up
questions. Grouped by round, because interviews are: the quant/math round,
the derivatives round, the microstructure/execution round, the risk round,
and the low-latency engineering round — first a core pass through each
(Q1–35), then the deeper second-interview material (Q36–135): more quant
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
measure. Interviewers love this because it explains the BS formula
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
hold for any parameters. The meta-answer interviewers want: semi-analytic
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

Claiming is easy; the interview answer is a measurement protocol. (1)
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
interviewers probe because getting it wrong is silent — lookups just start
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
at least one episode that should hurt it. The meta-point interviewers
reward: every defense here converts an unstated hope into a computed
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
interview follow-through: know that sigma_LN ~ sigma_N / F links the
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
instrument. The second-order interview point: bond DV01 is computed off
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
wrong on most instruments it touches. The interview point: candidates
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
escalated and then persistently waived. The interviewer is not
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
a steamroller is the cliche; the interview-grade phrasing is "short
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
forward rate. Intuition check an interviewer will push on: if the
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
and unwinding the associated hedge is itself a trade. The interview
point most candidates miss: the day-one margin is not profit until the
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
Interviewers use this question to test whether you see options as
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
plus book deltas) to everyone. Why interviewers ask: each hop is a
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
keep some incentive for price discovery. The interview follow-up is
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
orders from its own affiliated prop desk. The interview-complete answer
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
Classic pitfall the interviewer wants named: markouts must be measured
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
is a coin flip. The interview one-liner: the backtest is an experiment,
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
artifact. Interviewers often probe with: "your CV accuracy dropped from
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
worth saying out loud in the interview: a true Sharpe 4 at scale would
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
can inline instead of megamorphic-dispatching. The interview kicker:
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
algorithm, is your problem. Interviewers ask this to separate people
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
time. The design subtleties interviewers push on: position state must
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

## How to use this guide

Don't read it like a script — interviews die on the first follow-up. For
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
   completely different from reciting one, and interviewers can tell.

If a term here is unfamiliar, Parts I–III above teach all of them from
zero; [COOKBOOK.md](COOKBOOK.md) has runnable recipes for the workflows
these questions come from; [ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) is
the deep end of Round 5.
