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
chain.

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
program trade, the hedge routes internal-first then to a clean
midpoint pool while the 15bps-markout pool gets nothing — and the day
still nets positive after every cost. That exact day runs in
`crb/CrbRealWorldScenarioTest.java`.

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

### 17. Protocols: talking to venues

**FIX** is finance's venerable text protocol: `35=D|55=EURUSD|54=1|38=1000000`
(tag=value, delimited by an invisible byte-1 character). Human-debuggable,
universally supported, verbose. This library has a full FIX engine — but a
naive implementation allocates Strings for every tag of every message. The
hot-path answer: **encode into reusable byte buffers** (writing digits
directly, no String.format) and **decode via flyweights** (§14). Prices
travel as **scaled longs** — 1.08505 becomes mantissa 108505 with 5
decimals — because `double` cannot represent most decimal fractions
exactly, and rounding errors in prices are how you fail audits.

**Binary protocols** (ITCH for market data in, OUCH-style for orders,
SBE-style internally) skip text entirely: fixed offsets, native integers.
That's the difference between parsing and *casting*.

**Sessions and recovery**: TCP connections drop. FIX numbers every message,
so on reconnect each side says "I'm at sequence 4,832" and the counterparty
**resends** what was missed. This library implements the full recovery
dance (gap fill, possible-duplicate flags, sequence resets) — unglamorous,
and exactly the part that pages you at 3am if done wrong.

*In this library:* `fix/` (engine + garbage-free `FixOrderEncoder`,
`FixExecReportView`, `FixMarketDataView`), `marketdata/ItchCodec.java`, `sbe/`.

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
7. `backtest/Backtester.java` → the `alpha` package (§8, §11).
8. `microstructure/VolumeCurve.java` → `SignalEngine.java` →
   `OnlineAlphaLearner.java` → `execution/BenchmarkExecutor.java` →
   `PortfolioExecutor.java` → `persist/Checkpoint.java` — the execution
   intelligence stack from live inputs to basket schedule to overnight
   (§6, §7, §18).
9. The desk playbooks (end of Part I): `microstructure/OrnsteinUhlenbeck.java`
   → `execution/SpreadExecutionAlgo.java` (a pairs trade end to end),
   `microstructure/Vpin.java` (toxicity), then the whole `crb/` package
   with [CENTRAL_RISK_BOOK.md](CENTRAL_RISK_BOOK.md) as the guide and
   `crb/CrbRealWorldScenarioTest.java` as a realistic trading week you
   can step through in a debugger.
10. The risk stack: [MARKET_RISK.md](MARKET_RISK.md) maps all fourteen
    steps from market data to Basel/FRTB; `risk/VarEngine.java` →
    `risk/StressTester.java` → `risk/FrtbEs.java` is the reading spine,
    and `MarketRiskTest.java` shows every formula pinned by hand.
11. `docs/COOKBOOK.md` — seventeen end-to-end recipes to modify and re-run.

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
permutation test, survivorship bias, point-in-time (§8), notional, collar,
kill switch, VaR (§9), forward, swap points, NDF, call/put, strike, greeks,
delta/gamma/vega/theta, implied vol, smile, risk reversal, butterfly,
vanna-volga (§10), deflated Sharpe (§11), GC, Epsilon GC, autoboxing (§13),
ring buffer, SPSC, single-writer, open addressing, linear probing,
backward-shift deletion, bitmap, flyweight, cache locality (§14), memory
model, release/acquire, VarHandle (§15), percentile, p99, JIT, warm-up,
hiccup (§16), FIX, tag=value, scaled long, SBE, sequence recovery (§17),
sharding, shared-nothing, checkpoint, atomic rename (§18).

One closing thought. This library's recurring lesson isn't a data structure
or a formula — it's a habit: **measure, don't assume; prove, don't claim;
and when reality imposes a limit, document the limit instead of hiding
it.** That habit transfers to every system you will ever build.
