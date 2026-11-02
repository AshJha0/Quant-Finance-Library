# Part I, The Finance: risk, derivatives, rates and credit (sections 9 to 10u)

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: markets, execution and alpha (sections 1 to 8h)](01-markets-execution-alpha.md) | [Next: Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)](03-backtesting-and-desk-playbooks.md)

---

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

A position limit has to count orders already working at the venue, not
only fills: otherwise ten resting orders can each pass a check that their
sum breaks. `HftRiskGate` reserves each order's quantity when it accepts
it and releases the reservation on fill, cancel or reject. It also
rejects non-finite, zero and negative prices, and carries daily order,
notional and loss limits.

*In this library:* `trading/HftRiskGate.java` (per-order gate; measured
cost per check is in [BENCHMARKS.md](../BENCHMARKS.md)),
`trading/GlobalRiskAggregator.java` (firm-wide breaker and kill switch;
it fails closed, so an exposed symbol without a reference price trips
it),
`risk/RiskMetrics.java`, `risk/VarBacktest.java` (Kupiec/Christoffersen
tests).

**The full market-risk workflow** goes far beyond a daily VaR number,
and the library maps the whole thing in
[MARKET_RISK.md](../MARKET_RISK.md): four flavors of portfolio VaR that
disagree exactly when they should (`VarEngine`: a short-gamma book's
tail is worse than the Gaussian formula admits, and the delta-gamma
method knows it), Expected Shortfall beside every VaR (what the average
bad day costs, not just the threshold; every class uses the
Acerbi-Tasche 2002 estimator from `risk/TailRisk.java`), extreme value
theory for
quantiles beyond the sample (`ExtremeValueTheory`, which refuses to
report a finite tail mean when the fitted tail has none), stress tests
including the regulator's favorite inverted question "what move breaks
us?" (`StressTester.reverseStress`, answered in closed form with an
implausibility score in sigmas), and the Basel/FRTB layer
(`FrtbEs`, `PnlAttribution`), styled after the regulation and tested on
its arithmetic. `FrtbEs.stressCalibratedEs` follows BCBS MAR33.5-33.6: stressed
reduced-set ES scaled by `max(1, ES_full,current / ES_reduced,current)`,
and it refuses a reduced set that explains less than 75% of current
full-set ES. Regulatory model approval is a programme of work at a bank;
a library can only supply the arithmetic.

### 9b. Regulation and surveillance: proving you behaved

Risk systems protect the firm from itself; regulatory analytics prove
to everyone else that clients and markets were treated fairly. Two
obligations follow every desk around, and both are measurement
problems this library implements:

**Best execution.** If you execute client orders, you must get terms
as good as reasonably available, and *demonstrate* it, per order and
per venue. `regulatory/BestExecutionAnalyzer.java` (MiFID II
RTS 27/28 spirit) accumulates order outcomes and its `report()`
answers the questions an auditor asks: fill rate, average slippage in
bps versus the arrival mid, median latency to fill, the fraction
executed at-or-better than arrival, and slippage broken down by venue
(the venue whose fills are consistently worst has some explaining to
do). The shared vocabulary for venue quality lives in
`regulatory/MarketQualityMetrics.java`: **quoted spread** (what was
posted), **effective spread** (what the taker actually paid: twice
the signed distance from the mid), **realized spread** (the same
measured against the mid a horizon *later*, which is the part the maker
kept after adverse selection), and **price impact** (effective is
roughly realized plus impact; the decomposition says whether a wide
spread was compensation for risk or extraction from customers). Plus the
**order-to-trade ratio**: messages per executed trade, the metric
venues fine you on when your algo sprays cancels.

**Banging the close.** A good surveillance example. Benchmark
fixes are computed from prices inside a window
(`regulatory/FixAnalyzer.calculateFix` takes the median of mid samples,
WM/R style, and `calculateFix(bids, offers)` the mid of separate bid
and offer medians), so a participant with a large position *marked* at the
fix has an incentive to push the price during it. The signature is
threefold, and `FixAnalyzer.analyze` flags only when all three hold:
an outsized share of window volume, a price run-up into the fix
*aligned* with the participant's net flow, and reversion after the
window. Impact that decays is the footprint of pressure, not
information. Notice this is §5's markout logic wearing a compliance
badge: the same physics that tells a maker it was run over tells a
surveillance officer who did the running.

### 9c. Positions, P&L and the drop copy

Every fill changes a position and, when it closes part of one, books
realised P&L. How much is realised depends on which earlier purchase you
say you sold: average cost pools all open lots at one price, FIFO closes
the oldest lot first. The total P&L, realised plus unrealised, is the same
either way; only the split differs, and the split matters for tax,
accounting and trader pay.

A firm also needs an independent check that its own record of fills is
complete. The venue or broker sends a drop copy, a second stream of the
same executions. Reconciling the two at the end of the day (or during it)
finds fills one side has and the other does not.

Formally, for a book with position `n`, average cost `a` and contract
multiplier `m`, a fill of `q` at price `p` that adds to the position
updates `a' = (n a + q p) / (n + q)`; a fill that closes `q` realises
`q (p - a) sgn(n) m`. Under FIFO the realised P&L of a close is
`sum_k q_k (p - p_k) sgn(n) m` over the lots it consumes. Both satisfy
the cash-flow identity

```
realised + unrealised = m (n M - sum_i s_i q_i p_i)
```

with mark `M` and signed fills `s_i q_i` at `p_i` (IAS 2, para. 25, for
the FIFO and weighted-average cost formulas).

Worked example. Buy 100 at 10, buy 100 at 12, sell 150 at 11. Average
cost is 11, so the sale realises 0. FIFO closes 100 at 10 (+100) and 50 at
12 (-50), realising 50. Marked at 13 with 50 shares left, average cost
has unrealised 100 and FIFO unrealised 50; both totals are 100.

*In this library:* `trading/PositionLedger.java` keeps positions per
(account, strategy, symbol) with `onFill`, `realised`,
`unrealised` and `totalPnl` taking a `CostMethod` and a `PriceSource`
of marks, and `ANY` as a wildcard to aggregate.
`trading/DropCopyReconciler.java` matches fills by execution id and
reports four break types: MISSING (on the drop copy, not booked), EXTRA
(booked, not on the drop copy), MISMATCH (same id, different symbol, side,
quantity or price beyond tolerance) and DUPLICATE. Recipes 327 and 328.

### 9d. Limits that change during the day

Pre-trade limits (maximum order size, position, daily loss) are set each
morning, tightened when something goes wrong, and loosened again when it
is over. Each change is a risk event in its own right: a typo can turn a
limit off, and a regulator will ask who changed what and when.
MiFID II RTS 6 (Commission Delegated Regulation 2017/589, Article 15)
requires investment firms to have pre-trade controls and to be able to
change them under control.

Two properties matter. A change must be atomic: an order check sees
either the old set of limits or the new one, never half of each. And
every version must be kept, with author, time and reason, so that any
version can be restored and the history audited.

*In this library:* `trading/LimitsConfig.java` parses a desk file in
`key = value` form; all nine keys are required (`unlimited` is allowed)
so a missing key is an error rather than a silent default.
`trading/LimitsManager.java` applies a new config to a list of
`HftRiskGate`s (`apply`), restores an earlier version (`rollback`) and
keeps the `auditTrail()`. `HftRiskGate.limits(config)` swaps one immutable
limits object, and each check reads it once, which is what makes the
change atomic. Recipe 329.

### 10. Derivatives in five minutes

A **forward** is an agreement to trade at a future date at a price fixed
now. The fair forward price isn't a prediction; it is arbitrage arithmetic:
spot adjusted for the interest-rate difference between the two currencies
(in FX these adjustments are quoted as **swap points**). An **NDF**
(non-deliverable forward) does the same for restricted currencies, settling
the *difference* in USD.

An **option** is the right, without the obligation, to buy (**call**) or
sell (**put**) at a fixed **strike** price. Its value has two parts: what
it's worth if exercised now, plus **time value**: the chance things
improve before expiry. **Black-Scholes** (1973) prices this under
simplifying assumptions; its lasting contribution is the **greeks**, the
sensitivities (delta: per $1 of spot; gamma: how delta changes; vega: per
vol point; theta: per day) that let you *hedge*. Market makers in options
aren't betting on direction: they hedge delta continuously and trade
**volatility**.

One assumption Black-Scholes gets visibly wrong: constant volatility. The
market prices crashes as more likely than the model says, so implied vol
varies by strike: the **smile**. FX quotes the smile in its own dialect
(ATM / risk-reversal / butterfly, by delta), and pricing **exotics**
(barriers, touches, digitals) consistently with the smile needs corrections
like **vanna-volga**, implemented here and cross-checked with Monte Carlo:
if two independent methods disagree, one of them has a bug.

One FX trap is worth knowing early. The quoted butterfly is a **broker
(market) strangle**: the price of the 25-delta strangle at the single vol
ATM + BF. It is not the average wing vol of the smile. `FxVolSurface` by
default solves for the smile butterfly that reprices the quoted strangle
(Clark 2011, *Foreign Exchange Option Pricing*, section 3.6);
`ButterflyQuote.SMILE_STRANGLE` reads the input as a smile butterfly
instead.

*In this library:* `pricing/` (BlackScholes, BinomialTree, VolSurface,
SabrModel, exotics, VannaVolga), `fx/` (SwapPointsCurve, Ndf, FxVolSurface),
`hedging/` (DeltaHedger and friends), `rates/` (curves and day counts;
finance has several definitions of how long a year is).

### 10b. Rates: the price of time

A dollar today and a dollar in ten years are different assets, and the
exchange rate between them is an interest rate. Money can be lent for
any horizon, so there isn't one rate but a **yield curve**, a rate
per maturity. Every future cash flow in finance (bond coupons, swap
legs, a forward's settlement) is valued by asking the curve one
question: what is $1 at date T worth today? That number is the
**discount factor**.

You can't observe the curve directly. The market quotes *instruments*
(deposits, futures, par swaps), each bundling many cash flows.
**Bootstrapping** unbundles them: solve for the 1-year discount factor
from the 1-year instrument, use it to solve the 2-year, and walk out
the curve one maturity at a time. `rates/YieldCurve.java` implements
the classic version (`bootstrapAnnualParSwaps`), stores zero rates at
pillar tenors, and answers the three questions everything else asks:
`zeroRate(t)`, `discountFactor(t)`, and `forwardRate(t1, t2)`, the
rate the curve *implies* for lending between two future dates. Forwards
are (roughly) the curve's derivative, so a small kink in the zeros
becomes a large swing in the forwards. That is why desks judge a
curve build by its forwards, not its zeros.

A **bond** is a bundle of cash flows on a schedule, so a curve prices
it directly (`rates/BondPricer.priceFromCurve`), or you compress the
whole curve into one number, the **yield**, and convert both ways
(`priceFromYield`, `yieldToMaturity`). The sensitivities are the desk
vocabulary: **duration** (`macaulayDuration`, the PV-weighted average
time to cash flow; `modifiedDuration`, the percentage price change per
unit yield), **convexity** (the curvature that makes duration hedges
drift as rates move), and above all **DV01** (`dv01`, the dollars lost
per one-basis-point rise, the unit rates traders actually speak).

But "the yield rises 1bp" is a fiction: the curve rarely moves in
parallel; it steepens, flattens and twists. **Key-rate durations** slice
DV01 across the curve: bump one pillar by 1bp, hold the rest, reprice,
repeat per pillar. `rates/KeyRateDurations.keyRateDv01s` returns that
vector, and `parallelDv01` is the consistency check: the slices must
sum back to the parallel number, and the tests pin that they do.

*Real-life case:* a desk is long $50M of a 10-year corporate bond and
wants to be flat rates (keeping only the credit exposure). The naive
hedge (sell 10-year futures matching the bond's total DV01) is flat
to parallel moves only; a steepening that lifts the 10y point while
the 5y sits still leaves the "hedged" book bleeding. The KRD vector
shows where the risk actually lives: mostly at the 10y node, but a
meaningful slice at 5y and 7y from the coupons. Hedge each node with
the futures contract that drives it and the position survives
steepeners, flatteners and butterflies, not just the parallel shift
that rarely happens.

**Day counts: how long is a year?** Real term sheets disagree: ACT/360
(money markets), ACT/365 fixed (GBP), 30/360 and 30E/360 (US and euro
corporates), ACT/ACT ISDA (swap legs) and ACT/ACT ICMA (government
bonds). `rates/DayCount` implements each. ACT/ACT ICMA depends on the
coupon period, so it has its own method,
`DayCount.actActIcma(start, end, periodStart, periodEnd, frequency)`,
which gives exactly `1/frequency` for a full regular period. This is not
pedantry: accrue a coupon on ACT/365 when the term sheet says ACT/360 and
every accrual is misstated by about 1.4%, a real and recurring source of
PnL breaks between you and your counterparty. `rates/BusinessCalendar.java` handles the other half of
date reality: payment dates that land on weekends or holidays roll by
convention (`Roll.MODIFIED_FOLLOWING` — forward, unless that crosses
month-end), settlement is T+n *business* days (`addBusinessDays`), and
FX pairs settle on the union of two centers' calendars (`union`).
`BondPricer.dirtyPrice`, `cleanPrice` and `accruedInterest` put it all
together with real dates, using the street convention: round coupons of
`c/f`, ACT/ACT ICMA (or the bond's 30/360 variant) for the accrued
fraction, and discounting on unadjusted coupon dates with fractional
period exponents. `BondPricer.yieldFromCleanPrice` inverts it.

**Making rates move: short-rate models.** For risk simulation and
curve-dependent pricing you need a model of how rates evolve.
`rates/ShortRateModels.java` implements the workhorse trio in closed
form: **Vasicek** (Gaussian mean reversion, tractable everywhere, with
one known flaw: rates can go negative, which after 2015 is a feature as
much as a bug), **CIR** (the square-root diffusion keeps
rates non-negative, and `cirFeller` tells you when they stay strictly
positive), and **Hull-White** (Vasicek with the drift fitted so the
model reprices *today's* curve exactly; desks prefer it because a rates
model that disagrees with the discount curve it hedges against is wrong
by construction). Each has a simulation step for
Monte Carlo scenarios: `vasicekStep` is exact (the transition is
Gaussian, so there is no discretization error), `cirStep` is full-truncation
Euler (never sources volatility from a negative rate).

### 10c. Credit, commodities, indexes and private markets

One instrument was still missing from 10b: the swap itself. The curve
was bootstrapped from par swaps, and the swap pricer (in 4.0 `rates/VanillaSwap.java`; up to 3.x also a single-curve helper class) closes the
loop by pricing an actual swap position off it: the fixed-leg annuity is
`sum DF(t_i)`, the par rate is `(1 - DF(T)) / annuity` (the float leg is
worth par in a single-curve world, so it needs no forecasting), and a
payer swap's PV is `annuity * (parRate - K)` -- which is why a swap
struck at par PVs to exactly zero, an identity the tests pin at 1e-12.
DV01 is bump-and-reprice; for a fresh par swap it is approximately
`annuity * 1bp * notional`, the unit rates desks quote risk in. With
time priced, four more asset classes follow, each with its own version
of "the curve is the trade."

**Credit: pricing maybe-not-getting-paid.** The credit market's state
variable is the **hazard rate** h(t) -- conditional on surviving to t,
the annualized probability of defaulting right now. Survival is its
exponential integral:

```
Q(t) = exp(-integral_0^t h(s) ds)
```

`credit/CreditCurve.java` bootstraps a piecewise-constant h from CDS par
spreads exactly the way `YieldCurve` bootstraps zeros from par swaps:
walk the quotes short to long, solving one pillar at a time. A **credit
default swap** is insurance with a running premium, and its two legs
(`credit/CdsPricer.java`) are the whole pricing theory: the premium leg
is `spread * riskyAnnuity` where the risky annuity is
`sum dt * DF(t_i) * Q(t_i)` plus a half-period accrual-on-default term;
the protection leg is `(1-R) * sum DF(t_i) * (Q(t_i-1) - Q(t_i))`. Set
them equal and the **par spread** is `protectionPv / riskyAnnuity`;
since 2009 contracts trade a fixed coupon instead, and the difference is
the **upfront**, `protectionPv - coupon * riskyAnnuity`. Collapsing the
sums on a flat curve gives the rule of thumb every desk carries -- the
**credit triangle** `S ~ h * (1 - R)`: a 300bp spread at 40% recovery
means h of roughly 5% per year. The bridge back to bonds is the
**Z-spread** (`credit/CreditSpreads.java`): the constant z added to
every point of the risk-free curve that reprices the bond,
`price = sum cf_i * exp(-(z(t_i) + z) * t_i)` -- credit-plus-liquidity
compensation with the curve shape stripped out, unlike a naive yield
spread over one government point.

*Real-life case:* a PM sees the same issuer's 5-year bond at a Z-spread
of 350bp while 5-year CDS protection costs 280bp. That gap,
`zSpread - cdsParSpread`, is the **CDS-bond basis** (here negative from
the CDS side's view -- the bond is cheap relative to protection): buy
the bond, buy CDS, and in theory collect 70bp a year with default risk
insured away. In practice the trade is short funding -- it blew through
repo constraints spectacularly in 2008 -- which is exactly why the basis
persists and why the number is a risk premium, not free money.

**Commodities: the curve eats the spot view.** A commodity futures
curve in **contango** slopes up (deferred above spot); in
**backwardation** it slopes down. `commodities/CommodityCurve.java`
turns the shape into the number a position actually earns, the
annualized **roll yield**:

```
rollYield = ln(F_near / F_far) / (t_far - t_near)
```

positive in backwardation (rolling down the curve pays the long),
negative in contango (every monthly roll sells cheap and buys rich).
The shape itself comes from storage arbitrage -- **cash-and-carry**:

```
F = S * exp((r + u - y) * t)
```

with storage cost u and **convenience yield** y (the value of holding
the physical -- heating oil before a cold snap); `impliedCarry` inverts
this to read the market's `u - y` off the quotes. *Real-life case:* the
USO oil fund in 2020. Retail investors bought it as a bet on spot oil
recovering. Spot oil did recover -- and the fund still lost heavily,
because deep contango charged the long roll yield month after month.
Over a decade the roll term, not the spot view, has dominated most
commodity index returns; it is the single most misunderstood fact about
the asset class.

**Indexes: the arithmetic behind "the market was up 1%".**
`markets/IndexConstruction.java` implements the three weighting schemes
that produce three different markets from the same stocks:
**cap-weighted** (weight = float-adjusted market cap share --
self-rebalancing, since a stock that doubles doubles its own weight with
no trading), **price-weighted** (the Dow's historical accident: a $400
stock moves the index 8x as much as a $50 one regardless of company
size), and **equal-weighted** (1/N -- a small-cap tilt that must trade
every rebalance to stay equal). A level series survives membership and
share changes through the **divisor**: `level = sum(price * shares *
float) / divisor`, and on any change the divisor rescales so the level
is continuous -- `newDivisor = oldDivisor * newAggregate /
oldAggregate` -- so the index only ever moves for price reasons. The
cost of any scheme is `turnover(w1, w2) = 0.5 * sum |w1 - w2|`, the
one-way fraction of the portfolio that must trade. *Real-life case:* a
fund tracking an equal-weight index computes the turnover between this
quarter's weights and 1/N, multiplies by its impact model's cost per
dollar traded, and knows its tracking bleed before the rebalance --
which is how equal-weight products decide between exact replication and
optimized sampling.

**Private markets: returns without prices.** Private equity breaks the
usual machinery on purpose: no daily prices, cash flows the manager (not
the investor) times, and NAVs that are appraisals.
`markets/PrivateMarketAnalytics.java` is the toolkit for that world.
**IRR** -- the rate that zeroes `sum cf_t / (1+irr)^t` -- is
money-weighted, so it rewards the manager's cash-flow timing, which a
time-weighted return ignores by design; that is why PE quotes IRR and
mutual funds must not. The multiples cross-check it: **TVPI** =
(distributions + NAV) / contributions, **DPI** = distributions /
contributions, **RVPI** = NAV / contributions. DPI is the one that
counts, because you cannot spend RVPI. Benchmarking against "the S&P
returned X%" is meaningless for irregular cash flows, so **Kaplan-Schoar
PME** grows every flow forward at the index's return and takes
`(FV(distributions) + NAV) / FV(contributions)`: above 1, the fund beat
buying the index with the same cash flows on the same dates. Finally,
appraisal NAVs are AR(1)-smoothed versions of true returns, which
understates volatility and correlation to public markets --
**volatility laundering**. Geltner desmoothing inverts it:

```
r_true_t = (r_obs_t - phi * r_obs_{t-1}) / (1 - phi)
```

*Real-life case:* an endowment's PE sleeve reports 8% volatility against
public equity's 16%, and the optimizer wants to triple the allocation.
Desmooth the NAV series first (phi fitted from the reported returns' own
autocorrelation) and the underlying volatility comes back near public-market
levels -- the diversification was an artifact of the appraisal lag, and
the allocation decision changes accordingly.

### 10d. Two curves: OIS discounting and term projection

Before 2008 a bank discounted swap cash flows and projected the floating
rate off the same curve. The crisis opened a gap between term rates such
as 3-month LIBOR or EURIBOR, which carry bank credit and liquidity risk,
and overnight rates. Collateralised trades earn the overnight rate on
their collateral, so their cash flows are discounted at the OIS rate,
while the floating leg still fixes on the term index. That needs two
curves: an OIS discount curve and a projection curve per index tenor
(Bianchetti 2010; Ametrano and Bianchetti 2013).

The OIS curve is built first from overnight deposits and OIS swaps. The
projection curve is then built from deposits, FRAs, futures and swaps on
the index, with every cash flow discounted on the OIS curve already
fixed.

Formally, an OIS's compounded overnight leg telescopes, so its par rate is

```
K_OIS = (P(s) - P(e)) / sum_i tau_i P(t_i)
```

A term swap's par rate uses forwards from the projection curve `P_3m`
and discount factors from the OIS curve:

```
K_IRS = sum_j tau_j F_j P_ois(t_j) / sum_i tau_i P_ois(t_i)
F_j   = (P_3m(t_{j-1}) / P_3m(t_j) - 1) / tau_j
```

Futures are margined daily, so a futures rate sits above the forward by a
convexity adjustment. In Hull-White with mean reversion `a` and
volatility `sigma` (Hull, *Options, Futures and Other Derivatives*,
Technical Note 1; Kirikos and Novak 1997):

```
CA = B(t1,t2)/(t2 - t1) [B(t1,t2)(1 - e^{-2a t1}) + 2a B(0,t1)^2] sigma^2/(4a)
```

with Ho-Lee limit `sigma^2 t1 t2 / 2` as `a` goes to 0.

Between pillars the curve is interpolated on `-ln P`. Log-linear gives
piecewise-flat forwards, which jump at every pillar. Monotone-convex
(Hagan and West 2006) gives continuous forwards, keeps each interval's
average forward equal to the bootstrapped one, and keeps monotone
discrete forwards monotone. The library applies no positivity amendment,
so negative rates are allowed.

Worked example. A one-period OIS from spot to one year at 3.6% with
accrual `tau = 365/360` gives `P(e) / P(s) = 1 / (1 + 0.036 x 1.01389) =
0.96479`. For the convexity adjustment on a 9x12 future with `sigma` =
1%, the Ho-Lee value is `0.0001 x 0.75 x 1 / 2`, 0.375bp; Hull-White with
`a` = 3% gives about 0.365bp.

Risk on a two-curve book is the sensitivity to each quote with both
curves rebuilt. With log-linear interpolation the bucketed DV01s add to
the parallel DV01 to about 1e-7. With monotone-convex they agree only to a
few percent at a 1bp bump, because that interpolator is not
differentiable in its inputs where an interval changes zone.

*In this library:* `rates/MultiCurveBootstrap.java` (`bootstrap` for
OIS, `bootstrapProjection` for an index curve), the instruments
`Deposit`, `OisSwap`, `Fra`, `RateFuture` (with `hullWhiteConvexity`)
and `InterestRateSwap`, all implementing `CurveInstrument`;
`rates/CurveInterpolation.java` (`LOG_LINEAR_DISCOUNT`,
`MONOTONE_CONVEX`); `rates/MultiCurveRisk.java` (`buildCurves`,
`bucketedDv01`, `keyRateDv01` with Ho 1992 hat weights, `parallelDv01`);
two-curve methods on `InterestRateSwap` and `RatesOptions.swaption` and
`capFloor`. Recipes 301 and 302. A traded swap is priced with the leg model
below; the old single-curve swap pricer, deprecated in 3.1, was removed in 4.0
(`docs/MIGRATION_4.0.md`).

#### 10d.1 Lookback, observation shift, lockout and payment lag

The plain idea. An overnight-indexed coupon (SOFR, SONIA, ESTR) is the
daily rate compounded over the period, so you only know it at the end of
the period. That is awkward: the payer needs a few days to compute and pay.
Four conventions buy that time, and they differ in which day's rate goes
into the product and how many days it is weighted by.

- **Payment lag** of P days: keep the coupon as is, pay it P business days
  after the accrual end. Only the discount factor changes.
- **Lookback** of L days: for each accrual day, use the rate published L
  business days earlier, but weight it by the accrual day's own length
  (a Friday still counts 3 days).
- **Observation shift** of L days: move the whole observation window L
  business days back and take both the rates and the day weights from the
  shifted window. The compounded rate is annualised over the shifted
  window's days, then applied to the accrual period's year fraction.
- **Lockout** of k days: the rates for the last k business days are frozen
  at the rate of the day before them.

Formal statement (`rates/OvernightConvention.java`), with `r_i` the rate
used for day i, `n_i` its weight in calendar days, `d` the day-count
denominator (360 for ACT/360) and `N = sum_i n_i`:

```
R      = ( prod_i (1 + r_i n_i / d) - 1 ) * d / N
coupon = (R + spread) * tau(accrualStart, accrualEnd)
```

Lookback and shift both use `r_i` = the rate L business days before day i;
they differ in `n_i`: the accrual day's length (lookback) or the observed
day's length (shift). With no convention and a single curve `P` for both
forecasting and discounting, the product telescopes and the coupon times
tau is `P(s)/P(e) - 1`, so an unlagged leg is worth `P(start) - P(end)`.

Worked example. One accrual week Mon 2 Nov to Mon 9 Nov 2026 (weekends-only
calendar, L = 2, k = 2). Accrual days Mon to Fri, weights 1, 1, 1, 1, 3.

| accrual day | lookback: rate of, weight | shift: rate of, weight | lockout: rate of, weight |
|---|---|---|---|
| Mon 2 | Thu 29 Oct, 1 | Thu 29 Oct, 1 | Mon 2, 1 |
| Tue 3 | Fri 30 Oct, 1 | Fri 30 Oct, 3 | Tue 3, 1 |
| Wed 4 | Mon 2, 1 | Mon 2, 1 | Wed 4, 1 |
| Thu 5 | Tue 3, 1 | Tue 3, 1 | Wed 4, 1 |
| Fri 6 | Wed 4, 3 | Wed 4, 1 | Wed 4, 3 |

The shifted window is Thu 29 Oct to Thu 5 Nov, 7 days, the same total as
the accrual week here; the weekend has moved from Friday's weight onto Fri
30 Oct's. Under lookback the rate is known two business days before the
week ends; under lockout two days of the period have a known rate early.
With a payment lag of 2 the coupon is paid Wed 11 Nov. Past fixings come
from `FixingHistory`: every date before the valuation date must be there
(a missing one throws), today's is optional.

*Evidence.* Externally anchored: three seasoned SOFR swaps (lookback 2,
shift 2, lockout 2, each with a 2-day payment lag, 111 published fixings)
and a forward-starting front-stub swap match QuantLib 1.44 to 3e-12 in NPV
and 1e-11 in fair rate (`QuantLibGoldenTest`, generator
`scripts/quantlib-golden-sofr.py`; inputs are illustrative, not market
data). Independent recomputation: `VanillaSwap` equals `OisSwap` and
`InterestRateSwap` in their special cases to 1e-14 (`VanillaSwapTest`).

*In this library:* `rates/LegConvention.java`, `FixedLeg`, `OvernightLeg`,
`TermRateLeg`, `OvernightConvention`, `FixingHistory`, `VanillaSwap`.
Recipes 104, 177 and 360.

#### 10d.2 The analytic bootstrap Jacobian

The plain idea. Bump-and-rebuild risk asks the bootstrap the same question
n times. But the bootstrap already says how quotes and curve are tied: each
instrument reprices to its quote. Differentiate that tie once and you get
the curve's sensitivity to every quote from one linear solve.

Formal statement. Let `Y` be the curve pillar values (minus log discount
factors) and `I_k(Y)` the implied quote of instrument k. The bootstrap
solves `I(Y) = q`. The implicit-function theorem gives
`dY/dq = J^-1` with `J_kj = dI_k/dY_j`, so for a trade with value `V(Y)`

```
dV/dq = (dV/dY) J^-1,   equivalently solve  J^T z = (dV/dY)^T
```

and the per-quote DV01 is `z * 1bp`. `BootstrapJacobian.quoteJacobian`
writes `J` in closed form per instrument type (deposits, FRAs, futures,
OIS and IRS) through the interpolation weights `dY(t)/dY_j`; with two
curves the matrix is block lower-triangular (OIS quotes move both curves,
projection quotes only their own).

Worked example. On log-linear discount interpolation, `-log P(t)` is linear
between pillars, so the weight of `t = 3` between pillars at 2 and 4 is 0.5
on each, and extrapolating to `t = 6` gives weights 2 and -1
(`BootstrapJacobianTest.logLinearWeightsAreExact`).

*Evidence.* Self-consistency: on log-linear curves the buckets equal
`MultiCurveRisk`'s 1bp rebuild to 1e-6 of the largest bucket, and `J`
equals a finite difference of the implied quotes to 1e-6. For
`MONOTONE_CONVEX` the interpolation weights are numerical (stated in the
javadoc; the piecewise zones have no clean closed form), and the buckets
match a 1e-7 rebuild to 1e-4, while the 1bp rebuild is off by up to about
10% in single buckets because it crosses zone kinks.

*In this library:* `rates/BootstrapJacobian.java` (`quoteJacobian`,
`bucketedDv01`, same contract and ordering as `MultiCurveRisk.bucketedDv01`).
Recipe 359.

### 10e. The ISDA standard CDS model

Since the 2009 "Big Bang", single-name CDS trade on a fixed coupon (100bp
or 500bp in North America) plus an upfront payment, rather than at a par
spread. Fixed coupons make contracts fungible, so offsetting trades net.
The market still quotes a spread; the ISDA standard model is the agreed
way to turn the quoted spread into the upfront.

The contract terms are standardised too. Maturities fall on IMM dates (20
March, June, September, December) with a semi-annual roll, so new 5-year
contracts mature on 20 June or 20 December. Protection steps in at T+1,
the upfront settles at T+3 business days, and the first coupon accrues
from the previous IMM date.

Formally, on each segment where the hazard `lambda` and the forward rate
`f` are constant, with `k = lambda + f` and `Q0 P0` the survival and
discount factor at the segment start, the protection leg contributes
`lambda Q0 P0 (1 - e^{-k dt}) / k`. Accrual on default adds
`lambda Q0 P0 [a (t0 - tS)(1 - e^{-k dt}) / k + a (1 - e^{-k dt}(1 + k dt)) / k^2]`
with `a` the accrual rate and `tS` the accrual start. These are the ISDA
CDS Standard Model v1.8.2 routines (`JpmcdsCdsContingentLegPV`,
`JpmcdsFeeLegPV`) with the 2009 Markit numerical fix for small `k`
(O'Kane 2008, ch. 6). The quoted spread converts to the upfront through a
flat hazard rate that reprices a par CDS at that spread.

Worked example. A 5-year contract traded 2 November 2026 matures 20
December 2031 and accrues from 21 September 2026. Quoted at 300bp on a
100bp coupon with 40% recovery and a flat 3.5% discount rate, the flat
hazard is 5.05% (the credit triangle `S / (1 - R)` gives 5.0%) and the
upfront is 8.39% of notional, paid by the protection buyer.

*In this library:* `credit/IsdaCdsModel.java` (`standardContract`,
`standardMaturity`, `schedule`, `protectionLeg`, `riskyAnnuityClean`,
`parSpread`, `quotedSpreadToUpfront`, `upfrontToQuotedSpread`,
`bootstrap`, `Conventions`) and `credit/IsdaCurve.java`, with
`rates/HolidayCalendars.java` for the New York Fed, London, TARGET and
Tokyo calendars. The legs are checked against quadrature to 1e-10, and
the model against the official ISDA RFR test grids for 2021-04-26 and
2022-06-21 (14,328 rows; cash settlement within 1e-6 USD per 10mm, own
test, run only when the licensed grids are present). The default
conventions are weekends only without the half-day accrual-on-default
adjustment, as in 2.1; the 2022 grid needs both switched on. Recipes 303,
304 and 333.

### 10f. Counterparty exposure, CVA, DVA and wrong-way risk

A swap worth zero today can be worth a lot to you in two years, and if
the counterparty defaults then you lose that value. Exposure is the
positive part of the future value, and since the future is uncertain it
is a distribution at each date. CVA is the price of the counterparty's
default risk: expected exposure times the chance of default in each
period times the loss given default, summed and discounted.

The profile measures are expected exposure EE (mean of the positive
part), expected negative exposure ENE (what you owe them), potential
future exposure PFE (a high quantile, say 97.5%) and expected positive
exposure EPE (the time average of EE). The discounted EE of a swap at a
reset date equals the price of a swaption on the remaining swap, which is
why EE profiles rise and then fall as cash flows run off. A CSA reduces
exposure, but a threshold, a minimum transfer amount and the margin
period of risk (the days between the last good margin call and closing
out a defaulted counterparty) leave a residual.

Formally, the simulator uses a one-factor Hull-White short rate with the
bond price (Brigo and Mercurio 2006, eq. 3.39)

```
P(t,T) = P(0,T)/P(0,t) exp((V(t,T) - V(0,T) + V(0,t))/2 - B(t,T) x(t))
D(0,t) = P(0,t) exp(-V(0,t)/2 - I(t))
```

and for FX `S(t) = S0 P_f(0,t)/D(0,t) exp(-sigma^2 t/2 + sigma W(t))`.
CVA is `LGD sum_i E[D(0,t_i) E(t_i)] (PD(t_i) - PD(t_{i-1}))`. Wrong-way
risk, when default is more likely exactly when exposure is high, is
modelled with a Gaussian copula on a standardised market driver `X`
(Gregory 2015, ch. 17):

```
P(bucket i | X) = Phi((Phi^-1(PD_i) + rho X) / sqrt(1 - rho^2))
                - Phi((Phi^-1(PD_{i-1}) + rho X) / sqrt(1 - rho^2))
```

Positive `rho` with the rates driver is wrong-way for a payer swap.

Worked example. A 5-year payer swap on 100 notional at 3.2%, Hull-White
mean reversion 5% and volatility 1%, 10,000 paths. Uncollateralised, EE
at two years is about 1.67 and EPE about 1.30. A counterparty curve at
100 to 150bp with LGD 60% gives CVA of about 0.083; with `rho` = 0.5 the
wrong-way CVA is about 0.146. Under a zero-threshold CSA with a 10-day
margin period, EPE falls to about 0.23 and CVA to about 0.015.

The sum above charges each bucket at its end-point exposure, so its error
shrinks only in proportion to the bucket width. Since 3.1
`CvaApproximator.cvaBucketAverage` averages the discounted EE over each
bucket (the trapezoid rule), and its error falls with the square of the
width. On EE = 1mm (1 + t^2) with annual buckets the end-point sum is 27.6 %
above a 200,000-step integral and the averaged one 1.8 %. Recipe 362.

*In this library:* `credit/MultiCurrencyExposureSimulator.java` (`simulate` over
`Swap`s and `FxForward`s with an optional `Csa`), `credit/ExposureProfile.java`
(`expectedExposure`, `expectedNegativeExposure`, `pfe`, `epe`), and
`credit/CvaApproximator.java` (`cva`, `dva`, `cvaWrongWay`, and
`bilateral`, which conditions on who defaults first with independent
defaults). `credit/MultiCurrencyExposureSimulator.java` adds multi-curve
swaps with a fixed basis and several FX pairs with correlated Hull-White
foreign rates; section 10s covers the 2.3 additions (foreign-currency
swaps, a stochastic basis, wrong-way bilateral CVA). Not implemented: a
stochastic basis for foreign swaps, and more than one basis factor.
Recipes 305, 306, 334 and 345.

### 10g. SA-CCR: the regulatory exposure

Banks must hold capital against counterparty exposure. SA-CCR is the
Basel standardised formula for exposure at default (EAD) of a netting
set: replacement cost today plus a formula add-on for how much exposure
could grow, scaled by 1.4.

Formally (BCBS 279, March 2014, Annex 4):

```
EAD        = 1.4 (RC + PFE)
RC         = max(V - C, 0)                   unmargined
RC         = max(V - C, TH + MTA - NICA, 0)  margined
PFE        = multiplier x AddOn
multiplier = min(1, 0.05 + 0.95 exp((V - C) / (2 x 0.95 x AddOn)))
```

For interest rates, each trade's adjusted notional is notional times the
supervisory duration `SD = (e^{-0.05 S} - e^{-0.05 E}) / 0.05`, times its
delta and maturity factor. Trades are bucketed by end date (under 1y, 1
to 5y, over 5y) within each currency, and the effective notional is
`sqrt(D1^2 + D2^2 + D3^2 + 1.4 D1 D2 + 1.4 D2 D3 + 0.6 D1 D3)`, times the
0.5% supervisory factor. Credit, equity and commodities aggregate with a
systematic plus idiosyncratic formula,
`sqrt((sum rho_k A_k)^2 + sum (1 - rho_k^2) A_k^2)`.

Worked example (BCBS 279 Annex 4a, Example 1). A USD 10-year payer and
4-year receiver on 10,000 each and a EUR swaption on 5,000 from year 1 to
11, with values 30, -20 and 50. RC is 60. `SD(0, 10)` is 7.87. The
interest-rate add-on is 346.8, the multiplier is 1 because the set is in
the money, and `EAD = 1.4 x (60 + 346.8) = 569`.

*In this library:* `risk/SaCcr.java` (`unmargined`, `margined`,
`supervisoryDuration`, `optionDelta`, `maturityFactor`) and
`risk/SaCcrTrade.java` (`interestRate`, `fx`, `credit`, `equity`,
`commodity`, `asBasis`, `asVolatility`), with
`oneMarginAgreementManyNettingSets` and `subNettingSets` for margin
agreements that do not line up with netting sets, and
`risk/SaCcrHaircuts.java` for the supervisory haircuts: the Basel II
table (BCBS 128 para 151, 8% currency add-on para 152, square-root-of-time
scaling paras 168-169) or the current CRE22.49 table (section 10t). The
tests reproduce Annex 4a Examples 1 to 5. Recipes 307, 308 and 335.

### 10h. Smiles without arbitrage: SVI and SSVI

A volatility smile fitted point by point can imply negative
probabilities: a butterfly spread with a negative price, or a calendar
spread where the longer option is cheaper. SVI is a five-parameter shape
for one expiry's total variance that fits equity smiles well; SSVI
extends it to a whole surface with conditions that rule out both kinds of
arbitrage.

Formally, with log-moneyness `k = ln(K/F)` and total variance
`w = sigma_BS^2 T`, raw SVI is (Gatheral 2004; Gatheral and Jacquier
2014, eq. 3.1)

```
w(k) = a + b (rho (k - m) + sqrt((k - m)^2 + sigma^2))
```

A slice is free of butterfly arbitrage if and only if (Gatheral and
Jacquier 2014, Lemma 2.2)

```
g(k) = (1 - k w'/(2w))^2 - (w'^2/4)(1/w + 1/4) + w''/2 >= 0
```

SSVI writes the surface through the ATM total variance `theta_t`:

```
w(k, theta) = theta/2 (1 + rho phi k + sqrt((phi k + rho)^2 + 1 - rho^2))
phi(theta)  = eta / (theta^gamma (1 + theta)^(1 - gamma))
```

With `gamma` in [0, 1/2], `eta (1 + |rho|) <= 2` and `theta_t`
non-decreasing, the surface is free of static arbitrage (Gatheral and
Jacquier 2014, Theorems 4.1 and 4.2, Remark 4.4). Each SSVI slice is a raw
SVI slice with `a = theta (1 - rho^2)/2`, `b = theta phi / 2`,
`m = -rho / phi` and `sigma = sqrt(1 - rho^2) / phi`.

Worked example. Vogt's parameters `a = -0.0410`, `b = 0.1331`,
`rho = 0.3060`, `m = 0.3586`, `sigma = 0.4153` form a valid SVI curve
(positive total variance everywhere) yet `g` dips to about -0.033, so the
slice has butterfly arbitrage. For SSVI with `theta = 0.04`,
`rho = -0.3`, `eta = 1`, `gamma = 0.5`: `phi = 4.90`, and the raw slice
is `a = 0.0182`, `b = 0.0981`, `m = 0.0612`, `sigma = 0.1946`, which
gives back `w(0) = 0.0400`.

*In this library:* `pricing/SviSlice.java` (`calibrate`, with an
optional butterfly penalty; `densityFactor`, `minDensityFactor`),
`pricing/SviSurface.java`, `pricing/SsviSurface.java` (`calibrate`,
`slice`, `totalVariance`) and the interface
`pricing/TotalVarianceSurface.java` (`impliedVol`, `price`). Recipes 309
and 310.

### 10i. Dupire local volatility

Given an arbitrage-free surface of vanilla prices, there is exactly one
diffusion `dS = (r - q) S dt + sigma_loc(S, t) S dW` that reprices all of
them. Its volatility depends on spot and time and is read off the surface
(Dupire 1994). Local vol is used to price path-dependent products
consistently with today's vanillas; its weakness is that its implied
smile dynamics (how the smile moves when spot moves) are poor.

Formally, in total variance with `y = ln(K/F)` (Gatheral 2006, eq. 1.10):

```
sigma_loc^2(y, T) = (dw/dT) / g(y)
```

The denominator is the SVI butterfly factor `g` of section 10h and the
numerator is the calendar condition, so the two no-arbitrage conditions
are exactly what keeps the local variance positive and finite.

Worked example. On a flat surface `w = sigma^2 T`, `dw/dT = sigma^2` and
`g = 1`, so local vol equals `sigma` everywhere, which the tests check to
1e-8.

*In this library:* `pricing/DupireLocalVol.java` (`localVol`,
`localVolAtMoneyness`) on any `TotalVarianceSurface`;
`pricing/LocalVolPde.java` (Crank-Nicolson in log spot with a Rannacher start) and
`pricing/LocalVolMonteCarlo.java` (`price` returns price and standard
error). The tests pin the PDE within 2e-3 in implied vol of the SSVI
vanillas on a 601 by 400 grid, and the Monte Carlo within four standard
errors plus 0.5% of price. Recipe 311.

### 10j. Exact vanna-volga

FX desks see three liquid quotes per expiry: ATM, the 25-delta risk
reversal and the 25-delta butterfly, which give three pillar strikes.
Vanna-volga prices any other option as its flat-vol value plus the cost
of the pillar options needed to hedge its vega, vanna and volga.

Formally, solve the 3 by 3 system for weights `x_i` on the pillar
options so that the hedge matches the target's vega, vanna and volga.
The price is

```
V = V_BS(sigma_ATM) + sum_i x_i (C_mkt,i - C_BS,i(sigma_ATM))
```

(Castagna and Mercurio 2007). For vanillas the exact solve gives the same
weights as the closed-form log-strike formula. For a one-touch the smile
cost is scaled by the survival probability, `TV + p_survival sum_i x_i
cost_i` (Wystup 2006).

Worked example. Six-month pillars at 1.05, 1.10 and 1.15 with vols 10.5%,
9.5% and 10.0%, spot 1.10, rates 3% and 2%. A 1.12 call prices at 0.022874
by both the exact and the closed-form method. A 1.20 one-touch on 1m is
172,605 with the smile cost against 202,659 at the flat ATM vol.

*In this library:* `pricing/VannaVolga.java` (`priceExact`,
`exactWeights`, `weightsFor`, `price` for the closed form) and
`fx/VannaVolgaTouch.java` (`oneTouch`, `smileCost`). Recipe 312.

### 10k. Heston by the COS method

Heston prices need an integral of the characteristic function. The COS
method of Fang and Oosterlee (2008) replaces the density with a cosine
series on a finite interval. The coefficients come straight from the
characteristic function, so a price is one sum of `N` terms. It is fast
enough to sit inside a calibration loop.

Formally (Fang and Oosterlee 2008, eqs. 30 to 33), with `x = ln(S/K)` and
interval `[a, b] = [x + c1 - L sqrt(c2), x + c1 + L sqrt(c2)]` from the
first two cumulants (their Table 11):

```
V = K e^{-rT} sum'_{k=0}^{N-1} Re{ phi(k pi/(b-a)) e^{i k pi (x - a)/(b-a)} } U_k
```

where the prime halves the first term and `U_k` are the payoff's cosine
coefficients. The put is priced and the call follows by parity. The
characteristic function is the "little Heston trap" form (Albrecher et
al. 2007), which needs no branch tracking.

Worked example. `S = K = 100`, `r = q = 0`, `T = 1`, `kappa = 1.5768`,
`theta = 0.0398`, `sigma_v = 0.5751`, `rho = -0.5711`, `v0 = 0.0175` (the
paper's test case). With the defaults `N = 1024` and `L = 32` the call is
5.7851554344, 1.6e-8 from the paper's printed 5.785155450 and 2e-9 from
the library's Simpson-rule Heston pricer. The paper's `L = 12` leaves an
error of 4.5e-5 here, because the parameters are far from the Feller
condition and the left tail is fat.

*In this library:* `pricing/HestonCos.java` (`price`, `delta`, `gamma`,
`vegaV0`, `parameterGradient`, all exact derivatives of the truncated
series with the interval held fixed) and `pricing/HestonCalibrator.java`
(Levenberg-Marquardt on vega-weighted price residuals with an analytic
Jacobian and box bounds; Feller is not imposed). Recipes 313 and 314.

### 10l. Touch options paid at hit, and double touches

A one-touch pays a fixed amount if spot touches a barrier before
expiry. If the payment is made at expiry, its value is the discounted hit
probability. If it is paid at the moment of the hit, you receive it
earlier, so it is worth more by the discounting from the hit time to
expiry. A double no-touch pays if spot stays between two barriers; a
double one-touch pays if it touches either.

Formally, the pay-at-hit value is `payout x E[e^{-r tau} 1{tau <= T}]`,
the Laplace transform of the first hitting time `tau`. With carry
`b = r - q`, `mu = (b - sigma^2/2)/sigma^2`,
`lambda = sqrt(mu^2 + 2r/sigma^2)` and
`z = ln(H/S)/(sigma sqrt T) + lambda sigma sqrt T`, it is

```
payout [ (H/S)^{mu+lambda} N(eta z) + (H/S)^{mu-lambda} N(eta z - 2 eta lambda sigma sqrt T) ]
```

with `eta = 1` for a down barrier and -1 for an up barrier (Reiner and
Rubinstein 1991; Haug 2007, section 4.17.3). The double-barrier survival
probability is the Ikeda-Kunitomo (1992) image series, a sum over
reflections in both barriers, truncated at `n = -50..50`; the tail is
negligible once `ln(U/L)/(sigma sqrt T) >= 0.1`. A double no-touch plus a
double one-touch is a zero-coupon bond.

Worked example. EURUSD 1.10, barrier 1.20, six months, 12% vol, USD rate
3%, EUR rate 4.5%, 1m payout. Paid at expiry the one-touch is 261,679;
paid at hit it is 263,521. A double no-touch on 1.05 and 1.15 is 17,012
and the double one-touch 968,100, which add to `1m x e^{-0.015} =
985,112`.

*In this library:* `pricing/TouchOption.java` (`oneTouch`, `noTouch`,
`oneTouchPayAtHit`, `doubleNoTouch`, `doubleOneTouch`,
`doubleNoTouchProbability`). For FX, `rate` is the domestic rate and
`carry` the foreign rate. Recipe 315.

### 10m. Seasoned Asian options

An Asian option pays on the average of fixings. Once some fixings have
set, part of the average is known, and the option is equivalent to a
smaller option on the remaining fixings with an adjusted strike.

Formally, with `n` fixings in total, `m` remaining and observed sum
`A_obs`, the adjusted strike is `K* = (n K - A_obs) / m` and the price is
`(m/n)` times an option on the average of the remaining fixings struck at
`K*` (Haug 2007, section 4.20.1). If `K* <= 0` the call is certain to
finish in the money and is worth its discounted forward; the put is worth
zero. The remaining-average option is priced by Turnbull-Wakeman moment
matching or by Curran (1994), which conditions on the geometric average
and is accurate to well under 0.2% at 30% vol against a geometric
control-variate Monte Carlo. Levy's formula is the continuous-average
version (Haug 2007, section 4.20.2).

Worked example. A one-year call struck at 100 on six fixings, three set
at 102, 99 and 101 (sum 302) and three left at 0.5, 0.75 and 1 year.
`K* = (600 - 302) / 3 = 99.33`. With spot 100, 3% rate and 30% vol,
Curran gives 5.418 and Turnbull-Wakeman 5.426.

*In this library:* `pricing/AsianOption.java` (`turnbullWakemanSeasoned`,
`curranPrice`, `levyPrice`, plus `geometricPrice` and `arithmeticPrice`
for new options). Recipe 316.

### 10n. Discrete dividends on a tree

A cash dividend drops the stock by a known amount on the ex-date. A
standard binomial tree assumes a continuous yield, and the common
workaround (a tree on the stock less the present value of dividends)
misprices American calls, whose early-exercise decision sits right
before the ex-date.

Formally, the Vellekoop and Nieuwenhuis (2006) method keeps a recombining
CRR lattice of stock levels. At an ex-date the value just before the drop
is

```
V_cum(S) = max(exercise(S), V_ex(S - D))
```

with `V_ex` interpolated linearly in `S` on the lattice. Ex-dates snap to
the nearest step, so the timing error is at most half a step.

Worked example. Spot 100, strike 90, 5% rate, 25% vol, six months, a
dividend of 6 at three months, 2,000 steps. The American call is 12.521
and the European 10.232; the difference is the value of exercising just
before the ex-date.

*In this library:* `pricing/DividendTree.java` (`price`) with
`pricing/DividendSchedule.java`. The tests check it equals
`BinomialTree` with no dividends and matches the exact one-dividend
integral for European and American calls. Recipe 317.

### 10o. The FRTB standardised approach

The standardised approach turns a book's sensitivities into capital with
fixed regulatory weights and correlations, so two banks with the same
book get the same number. It has three parts: the sensitivities-based
method (delta, vega and curvature), a default risk charge for jump to
default, and a small add-on for risks the first two do not capture.

Formally, within a bucket `b` each sensitivity `s_k` becomes a weighted
sensitivity `WS_k = RW_k s_k`, and

```
K_b = sqrt( max(0, sum_k WS_k^2 + sum_k sum_{l != k} rho_kl WS_k WS_l) )
S_b = sum_k WS_k
Delta = sqrt( sum_b K_b^2 + sum_b sum_{c != b} gamma_bc S_b S_c )
```

(BCBS d457, MAR21.4 to 21.5). The calculation is repeated with every
`rho` and `gamma` scaled for low, medium and high correlation, and the
capital is the largest of the three totals. Across buckets the first
pass uses `S_b = sum_k WS_k` as it stands; only when the sum under the
root is negative does the calculation switch to the alternative
`S_b = max(min(sum_k WS_k, K_b), -K_b)` (MAR21.4(5)). Until 2.3 the
library clamped first, which understates offsetting buckets, so results
for books with several buckets can change. Curvature takes the worse of an up
and a down shock less the delta part, `CVR`, chooses up or down per
bucket as MAR21.5 prescribes, and aggregates with squared correlations
(MAR21.101).

Worked example (own computation). Two GIRR sensitivities in one currency
with weighted values 30 and -20 and a correlation of 0.6. Then
`K_b = sqrt(900 + 400 - 2 x 0.6 x 600) = sqrt(580) = 24.1`. With the high
scenario, `rho = min(1, 1.25 x 0.6) = 0.75`, and `K_b = sqrt(400) = 20`.
The hedge is worth more when correlation is high, so here the low
scenario, not the high one, can set the capital.

*In this library:* `risk/FrtbSa.java` (`Bucket`, `bucket`, `deltaVega`,
`curvature`, `cvr`, `RiskClass`, `sbmCapital`, and the `Girr`, `Fx`,
`Equity`, `Commodity`, `Csr` parameter tables), `risk/FrtbDrc.java`
(jump to default, hedge benefit ratio, bucket charge), `risk/FrtbRrao.java`
and `risk/FrtbNmrf.java` (MAR33 stress scenario capital). The classes
follow d457 but are not certified. From 2.3 every supervisory value carries
its MAR paragraph in the javadoc, including the CSR risk weights, CSR index
buckets and inter-bucket correlations, GIRR inflation and cross-currency
correlations, equity repo, CTP and securitisation non-CTP. NMRF uses
separate roots for the credit and equity idiosyncratic groups, verified
against the printed formula in MAR33.17. The default risk charge for
securitisations is in `risk/FrtbDrcSecuritisation.java` (3.0, recipe 351).
Recipe 336.

Since 3.1 the parameters are also checked table by table. `FrtbSaTableTest`
reads three CSVs transcribed by hand from the consolidated Basel Framework,
each row citing its paragraph, table and page: the full 10x10 GIRR tenor
correlation matrix (MAR21.46 Table 2), 89 risk weights (MAR21.42 Table 1,
MAR21.53 Table 4, MAR21.77 Table 10, MAR21.82 and MAR21.83 Tables 11 and 12,
MAR22.24 Table 2 and the MAR22.12 LGDs) and the 55 off-diagonal CSR sector
correlations of MAR21.57 Table 5. Each library value must match the printed
one to within half its rounding (0.05 percentage points for Table 2).
MAR21 prints no worked capital calculation, so the class example K = 0.98182
is an independent recomputation from the printed Table 1 and Table 2 values,
not a BCBS number; the only printed worked numbers are two correlations
(footnotes 284 and 292), pinned in `FrtbSaParametersTest`.

### 10p. Removed in 2.3.0

This section covered a margin calculator that was removed in 2.3.0 pending a licensing review.

### 10q. Corporate events beyond splits and dividends

A position is a number of shares of one ISIN, and corporate events
change all three: the number, the security and sometimes the cash. A
backtest that ignores them books a 10-for-1 reverse split as a 900%
gain, or a spin-off as a loss equal to the child's value.

Formally, each event maps a holding `(symbol, isin, shares, cost)` and an
ex-date price to new holdings and cash. A rights issue of `r` new shares
per old at subscription price `X` has the theoretical ex-rights price

```
TERP = (P_cum + r X) / (1 + r)
```

and a holder who sells the rights receives `P_cum - TERP` per old share.

Worked example (own computation). Cum price 10, one new share for every
four old at 6, so `r = 0.25`. `TERP = (10 + 1.5) / 1.25 = 9.20`, and each
old share's right is worth 0.80. A holder of 100 shares who sells the
rights ends with 100 shares at 9.20 and 80 in cash, 1,000 in all, as
before.

*In this library:* `data/CorporateEvents.java` (bonus issue, reverse
split, regular and special dividends with a policy, spin-off, rights with
`terp`, symbol and ISIN change, cash-and-stock merger, `priceFactor`,
`volumeFactor`), `data/CorporateEventPositions.java` (`apply`,
`takeUpRights`, `merger` with proration). `PortfolioBacktester.run`
takes a map of events as an opt-in overload; in it a merger assumes the
holder elects cash on exactly the maximum fraction and no cost basis is
kept. Recipe 341.

### 10r. The ISDA rate curve from swaps only

The ISDA CDS standard model needs a discount curve, and everyone must
build it the same way or the upfront for the same quote differs between
two dealers. Since the move to risk-free rates the standard curve is built
from overnight index swaps only, with no money-market deposits.

Formally, with trade date `T` and spot `S = T + 2` business days, a swap
of tenor `m` months matures at `M = MF(S + m months)` and pays an annual
fixed coupon with ACT/360 accruals. The zero rate at `t = (M - T)/365`
solves `P(M) + c sum_k tau_k P(d_k) - P(S) = 0`, with the swaps solved
shortest first and `r t` linear between knots.

Worked example (own test). Trade date 2021-04-26, weekends-only calendar,
so spot is 2021-04-28. A 6-month swap at 2% pays once, 183/360 of the
coupon, and gives a zero rate of `ln(1 + 0.02 x 183/360) / (183/365)`,
about 2.0175%. On the official ISDA SOFR test grids the curve reproduces
cash settlement within 1e-6 USD on 10mm notional (published reference).

*In this library:* `credit/IsdaRateCurve.java` (`bootstrapSwaps`,
`parSwapValue`), returning an `IsdaCurve` for `IsdaCdsModel`. Recipe 346.

### 10s. Foreign-currency swaps, a moving basis, and wrong-way bilateral CVA

Three gaps in the 2.2 exposure model closed in 2.3. A swap in euros held
by a dollar bank is exposed to both euro rates and EURUSD. The spread
between the curve a swap projects on and the curve it discounts on is not
fixed; it widens in stress. And a counterparty that tends to default when
our exposure to it is high (wrong-way risk) matters for bilateral CVA too,
where we may default first.

Formally, a foreign swap is valued on its currency's Hull-White state and
converted at the simulated spot, so `S_c(t) V_c(t)` discounted at the
domestic bank account is a martingale. The basis is one factor,
`dy_b = -a_b y_b dt + sigma_b dW_b`, added to the log projection ratio with
the drift that keeps forward projection rates fair. For wrong-way
bilateral CVA both hazards load on a market driver,
`L_c = exp(alpha_i + b_c X_i)` and `L_o = exp(beta_i + b_o X_i)`, with
`alpha_i`, `beta_i` solved so that each name's survival curve is matched
on the simulated paths, and each path charges CVA or DVA according to who
defaults first.

Worked example (own arithmetic). With `sigma_b` = 0.3% and `a_b` = 0.5, a
period fixing in five years has a log-basis standard deviation of
`0.003 x sqrt(1 - e^-5)`, about 0.30%. With `b_c = b_o = 0` the wrong-way
result equals plain `bilateral` to rounding (own test).

*In this library:* `credit/MultiCurrencyExposureSimulator.java`
(`ForeignSwap`, `BasisFactor`, the ten-argument `simulate`) and
`credit/CvaApproximator.java` (`bilateralWrongWay`). Without the new
arguments every path equals 2.2. Recipe 345.

### 10t. CRE22 collateral haircuts and sub-netting sets

Collateral the bank holds may lose value before it can be sold, so it
counts for less than its market value. The Basel II table of 2006 and the
current CRE22 table disagree: CRE22 has more maturity buckets and larger
haircuts on equities and long corporate bonds.

Formally, held collateral counts `X (1 - H - H_fx)` and posted collateral
`-X (1 + H + H_fx)`, with `H_fx` = 8% on a currency mismatch (BCBS 128
para 152; CRE22.52) and `H_N = H_10 sqrt(N/10)` (paras 168-169). For a
netting set split by margin agreements, BCBS 279 para 185 applies the
formula to each sub-netting set, replacement cost included, and sums.

Worked example (own hand computation). 1,000 of A-rated corporate bonds
with three years left, held, in another currency, 10-day period: Basel II
gives `H` = 6% and `C = 1,000 x (1 - 0.06 - 0.08) = 860`; CRE22.49 gives
`H` = 4% and `C = 880`. For sub-netting sets, a margined part with
`V = -30` and floor 5 and an unmargined part with `V = 50` give
`RC = 5 + 50 = 55`; netting the parts first, as 2.2 did, gave 20.

*In this library:* `risk/SaCcrHaircuts.java` (`Table.BASEL_II_2006`, the
default, `Table.CRE22`, and since 2.4 `Table.CRE22_NO_EXTERNAL_RATINGS` with
`haircut10DayNoExternalRatings` for CRE22.50) and `risk/SaCcr.subNettingSets`.
Securitisation collateral uses the CRE22.49 securitisation rows (2.4) and is
refused under the 2006 table, which has none. Since 3.0 fund units take
`fundHaircut10DayMandate` (the highest haircut the mandate allows) or
`fundHaircut10DayLookThrough` (value-weighted average of the holdings, when
the bank may look through), both from the "UCITS/mutual funds" row of
CRE22.49 and CRE22.50 (published reference). Example: a fund that may hold
main-index equities (20%) and A-rated 3-year bonds (4%) takes 20% by
mandate; holding 60/40 and looked through it takes
`0.6 x 0.20 + 0.4 x 0.04 = 13.6%`. Recipes 335, 352 and 355.

### 10u. Default risk charge for securitisations

A securitisation tranche can lose its whole value when enough loans in the pool
default. The banking book already has risk weights for that loss, so FRTB does
not model the pool again. It takes the tranche's market value as the amount at
risk and borrows the banking-book weight.

Formally (MAR22.27 to 22.45, published reference): the gross jump-to-default
of a tranche is its market value (MAR22.27, 22.36). Longs and shorts net only
within exactly the same tranche of the same pool, scaled by maturity
`min(max(M, 0.25), 1)` (MAR22.29, 22.39). Outside the correlation trading
portfolio (non-CTP), each bucket charge is
`max(sum RW x long - HBR x sum RW x |short|, 0)` with
`HBR = sum long / (sum long + sum |short|)`, and buckets are summed with no
diversification (MAR22.33, 22.35). Inside the CTP a bucket (one per index) may
be negative, and `DRC = max(sum_b (max(DRC_b, 0) + 0.5 min(DRC_b, 0)), 0)`
(MAR22.44, 22.45). The weight is the banking-book weight with a one-year
maturity (MAR22.34(1), 22.42), from SEC-SA (CRE41) or SEC-ERBA (CRE42). The
banking-book weight is a multiple of exposure that already contains the 8%
capital ratio; the library multiplies by 8% to get a weight per unit of JTD.
MAR22 does not print that step, so it is the library's reading, and the
reasons are printed elsewhere in the framework: RBC20.1(3) sets total capital
at 8.0% of RWA, MAR20.1 turns market-risk capital into RWA by x12.5, and
CRE41.4 footnote 211 converts a risk weight to capital as "risk weight times
8%". It is also the reading under which a 1250% weight gives a charge equal
to the market value, the fair-value cap of MAR22.34(3).

Worked example (own test, `FrtbDrcSecuritisationTest`). In the RMBS Europe
bucket, a long of 100 with a 90% banking-book weight (DRC weight 7.2%) and a
short of 50 with 50% (4%): `HBR = 100 / 150 = 2/3`, charge
`7.2 - 2/3 x 2.0 = 5.87`. A net short of 10 in another bucket is floored at 0.
In the CTP, buckets of +100 and -100 give `100 - 0.5 x 100 = 50` (the MAR22.45
example).

*In this library:* `risk/FrtbDrcSecuritisation.java` (`grossJtd`,
`netTranche`, `nthToDefaultAttachment`, `kA`, `secSaRiskWeight`,
`erbaRiskWeight`, `drcRiskWeight`, `nonCtpTotal`, `ctpTotal`). Left to the
caller: SEC-IRBA weights and the valuation model behind a single-name
decomposition.

Since 3.0 (published reference, CRE41 and MAR22 text on disk):

- Unknown delinquency status. If W is unknown for no more than 5% of the pool
  by EAD, `kAPartlyUnknownW` charges that part at 100%:
  `K_A = (EAD_known / EAD_total) x K_A_known + EAD_unknown / EAD_total`
  (CRE41.9). Above 5% the tranche takes 1250% (CRE41.10), and the method
  throws so that the case cannot slip through. With 3% unknown and
  `K_A_known = 0.10`, `K_A = 0.97 x 0.10 + 0.03 = 0.127`.
- Unrated junior tranche under rated seniors. `juniorUnratedFloor` keeps its
  SEC-SA weight at or above the next more senior rated tranche (CRE41.15).
- Decomposition. `decomposeIndex` splits an index position into tranches that
  span 0 to 100%, each getting its thickness share (MAR22.28; "proportionately"
  read as by thickness, the split under which the tranches add back to the
  index). `offsetByReplication` offsets a tranche against sub-tranches that
  tile it (MAR22.30(1), 22.39(3)): long 50 in 10-15% against shorts of 20 in
  10-12% and 30 in 12-15% leaves nothing. `checkSingleNameDecomposition`
  refuses exotic structures and single-name amounts that do not add up to the
  tranche value (MAR22.39(2)).

Recipes 351 and 355.

---

[Index: LEARN.md](../LEARN.md) | [Previous: Part I, The Finance: markets, execution and alpha (sections 1 to 8h)](01-markets-execution-alpha.md) | [Next: Part I, The Finance: backtesting and the desk playbooks (sections 11, 11b)](03-backtesting-and-desk-playbooks.md)
