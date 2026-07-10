# Central Risk Book

One netted view of the firm's market risk across desks and products,
and the machinery that monetizes it. A CRB exists because two desks
paying the street to shed opposite risks is money burned twice: net
first, warehouse what nets against future flow, hedge only the excess
— and hedge it through yourself before anyone else.

Everything lives in `com.quantfinlib.crb` (research/warm lane,
deterministic, tested in `CrbBookTest` and `CrbExecutionTest`).

## 1. One factor space for every product

`CentralRiskBook` decomposes each instrument into common risk factors
at booking time — the decomposition IS the netting:

| Product | Booked via | Factors it lands on |
|---|---|---|
| Cash equity | `bookCashEquity` | `EQ:<sym>` (currency notional delta) |
| Listed equity option | `bookEquityOption` | `EQ:<sym>` (Δ·S·units), `EQGAMMA:<sym>` ($ per 1%), `EQVEGA:<sym>` (per vol pt) via `pricing.BlackScholes` |
| FX spot | `bookFxSpot` | `CCY:<base>` and `CCY:<quote>` — CURRENCY legs, so EURUSD and USDJPY net their shared USD |
| FX swap | `bookFxSwap` | `FXPOINTS:<pair>` (−N for a buy-sell: P&L per +1.0 far−near move — points widening hurts the buy-sell) + the quote-ccy cash-flow imbalance; the base legs cancel exactly |
| NDF | `bookNdf` | full currency delta until the fixing + `pendingFixing(pair)` gross notional, released via `settleFixing` once fixings occur |
| FX option | `bookFxOption` | Garman-Kohlhagen delta split onto the currency legs, `FXGAMMA:`/`FXVEGA:<pair>` |

An equity-option delta nets against cash shares; an FX-option delta
nets against spot; a second desk's opposite flow nets against the
first. `nettingEfficiency()` reports how much gross flow annihilated,
and `report(cov, confidence)` prices the arrangement: netted book
VaR/ES (via `risk.VarEngine`) against the sum of standalone desk VaRs
— the difference is the **diversification benefit**, the number that
justifies the desk.

## 2. Quoting and internalization (Quant)

- `SkewedQuoter` — inventory-shaded two-way prices: a long book shades
  both quotes down (the ask becomes the attractive side), linear in
  inventory/limit, capped so the quote never crosses itself.
- `InternalizationEngine` — the internalize-or-route decision:
  risk-REDUCING flow is crossed against inventory and earns the client
  price improvement (a share of the saved spread — the book was going
  to pay to shed that risk anyway); risk-ADDING flow is warehoused
  only inside the warehouse limit. `internalizationRate()` is the
  desk's headline stat.

## 3. Hedging the residual (Algo)

- `CrbHedgeUniverse` — builds the `loadings[factor][instrument]`
  matrix the optimizer needs, aligned to the book's factor registry
  (hand-assembling it is the most error-prone step in the workflow):
  `addFxForward` loads the two currency legs exactly like a booked
  trade; `addSingleFactor` covers index futures (`EQ:<index>` — the
  covariance carries the correlation to single names, so the
  regression hedge falls out of the optimizer, not a beta table),
  vega hedges and outrights; hedge-only factors register with zero
  book exposure.
- `HedgeOptimizer` — minimum variance with an L1 cost term:
  `min (e+Lh)'Σ(e+Lh) + λ·Σc|h|`, solved by coordinate descent with
  the exact soft-threshold update. Instruments whose marginal risk
  reduction isn't worth their cost get EXACTLY zero; `λ = 0` recovers
  the closed-form minimum-variance hedge (pinned in the tests against
  the regression-beta formula). Feed `c` from a `KylesLambda` impact
  estimate plus the spread.
- `CrbAutoHedger` — per-factor bands with a cooldown: inside the band
  the book warehouses (inventory is the edge, not the problem); on a
  breach it hedges only the EXCESS beyond `resetFraction·limit`, and
  if the cost-aware hedge refuses to fix a hard-limit breach it reruns
  cost-blind — the limit outranks thrift.

## 4. Routing, dark pools included (SOR)

`CrbRouter` allocates in honest cost order:

1. **Internal** — crossing the book's own offsetting inventory costs
   zero and leaks nothing; the CRB is the firm's first dark pool.
2. **Dark venues** — midpoint fills pay no spread, but each venue
   carries an adverse-selection charge in bps (post-fill markout — a
   `VenueScorecard` estimate slots in directly) and its liquidity is
   discounted by fill probability. A venue whose charge exceeds the
   lit cost gets nothing, whatever its size.
3. **Lit** — half spread plus impact, but it fills; whatever the dark
   legs are not *expected* to fill routes lit too.

## 5. Overnight persistence

`CentralRiskBook.writeState/readState` and the
`InternalizationEngine` counters plug into `persist.Checkpoint`
sections — factor names, net/gross, per-desk attribution, pending
fixings and the internalization history all survive the night into
fresh instances. Restoring into a book that already holds positions
throws, and an internalization history earned under one warehouse
limit refuses to restore under another (configuration mismatches
fail loudly, the house rule).

## Deliberate boundaries

- Exposures are kept in native units per factor (`CCY:JPY` in yen);
  the covariance supplied to `report`/`HedgeOptimizer` must match —
  no FX conversion service is smuggled in.
- The NDF fixing flag is gross notional pending, not a dated fixing
  ladder — `fx.FixingRisk` owns dated fixing analysis.
- Booking is position-decomposition, not lifecycle: expiries,
  exercises and settlements re-book as offsetting flows.
- Single-threaded by design: a CRB re-decides on interval cadence
  (the warm lane), not per tick — the ULL lanes stay in `trading`
  and `marketdata`.
