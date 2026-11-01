# Central risk book

A central risk book (CRB) holds one netted view of a firm's market risk across desks
and products. When two desks would each pay the market to shed opposite risks, the
CRB nets them first, keeps the risk that is likely to offset against future flow, and
hedges only what is left, crossing internally before going to external venues.

Everything described here is in `com.quantfinlib.crb`. It runs in the research or
warm lane: single-threaded, deterministic, re-deciding on an interval rather than per
tick. Tests are in `src/test/java/com/quantfinlib/crb` (`CrbBookTest`,
`CrbExecutionTest`, `CrbPersistenceAndUniverseTest`, `CrbRealWorldScenarioTest`).

## 1. One factor space

`CentralRiskBook` decomposes each instrument into named risk factors when it is
booked. Netting is then a sum per factor.

| Product | Booked via | Factors |
|---|---|---|
| Cash equity | `bookCashEquity` | `EQ:<sym>` (delta in currency notional) |
| Listed equity option | `bookEquityOption` | `EQ:<sym>` (delta times spot times units), `EQGAMMA:<sym>` (dollar gamma per 1 %), `EQVEGA:<sym>` (per vol point), from `pricing.BlackScholes` |
| FX spot | `bookFxSpot` | `CCY:<base>` and `CCY:<quote>`. Currency legs, so EURUSD and USDJPY net their USD. |
| FX swap | `bookFxSwap` | `FXPOINTS:<pair>` (-N for a buy-sell: P&L per +1.0 move in far minus near points) and the quote-currency cash-flow imbalance. The base legs cancel. |
| NDF | `bookNdf` | Full currency delta until fixing, plus gross notional pending fixing (`pendingFixing(pair)`), released by `settleFixing`. |
| FX option | `bookFxOption` | Garman-Kohlhagen delta on the currency legs, `FXGAMMA:<pair>`, `FXVEGA:<pair>` |

`nettingEfficiency()` is `1 - net / gross` summed over factors.

**Risk report.** `report(covariance, confidence)` returns a `CrbReport` with gross and
net exposure, netting efficiency, VaR and ES of the netted book, the sum of standalone
desk VaRs, and the diversification benefit (standalone sum minus book VaR). The
covariance is over factors in registry order (`factors().name(i)`), in the units of
each factor's exposure.

VaR and ES are second order: `risk.VarEngine.deltaGammaVar` and `deltaGammaEs`, with
the Cornish-Fisher expansion. Dollar-gamma rows are not exposures to a return of their
own, so they are moved into the gamma matrix on their underlying factors, with factor
returns read as relative moves:

- `EQGAMMA:s` with dollar gamma G per 1 % (G = Gamma S^2 / 100) contributes
  `0.5 * 100 * G * r_s^2`, that is `Gamma[EQ:s][EQ:s] += 100 G`.
- `FXGAMMA:BBBQQQ` contributes `0.5 * 100 * G * (r_BBB - r_QQQ)^2` on the two `CCY:`
  factors.

Vega rows stay linear; their factor return is the change in implied volatility in
points. For a book without options the report equals delta-normal VaR.

## 2. Quoting and internalisation

- `SkewedQuoter` shades two-way prices by inventory. A long book moves both quotes
  down, so its offer becomes the more attractive side. The shift is linear in
  inventory over the limit and capped so the quote never crosses itself.
- `InternalizationEngine` decides whether to internalise or route. Flow that reduces
  the book's risk is crossed against inventory and the client receives part of the
  saved spread as price improvement. Flow that adds risk is kept only inside the
  warehouse limit. `internalizationRate()` reports the share internalised.

## 3. Hedging the residual

- `CrbHedgeUniverse` builds the `loadings[factor][instrument]` matrix the optimiser
  needs, aligned to the book's factor registry. `addFxForward` loads both currency
  legs as a booked trade would; `addSingleFactor` covers index futures (`EQ:<index>`,
  whose correlation to single names comes from the covariance), vega hedges and
  outrights. Factors used only by hedges are registered with zero book exposure.
- `HedgeOptimizer` minimises variance plus an L1 cost:

  ```
  min over h:  (e + L h)' Sigma (e + L h)  +  lambda * sum_i c_i |h_i|
  ```

  where `e` is the factor exposure, `L` the loadings, `Sigma` the factor covariance,
  `c_i` the all-in cost per unit notional of instrument i (spread plus expected impact,
  for example from `microstructure.KylesLambda`), and `lambda` the risk/cost trade-off.
  It is solved by cyclic coordinate descent with the soft-threshold update. Every 50
  sweeps the solver solves the optimality conditions on the current support and sign
  pattern and accepts the result if the subgradient condition holds off the support.
  A ridge of `1e-12 tr(G) / m` on the instrument Gram matrix keeps the solution unique
  when instruments are collinear. Instruments whose risk reduction is worth less than
  their cost get exactly zero. With `lambda = 0` the result is the minimum-variance
  hedge from the normal equations.
- `CrbAutoHedger` sets a band per factor with a cooldown. Inside the band the book keeps
  the risk. On a breach it hedges only the excess beyond `resetFraction * limit`. If the
  cost-aware hedge would leave a hard-limit breach, it reruns without costs, because
  the limit takes priority over cost.

## 4. Routing

`CrbRouter` allocates an order in order of expected cost:

1. **Internal.** Crossing against the book's own offsetting inventory costs nothing
   and leaks no information.
2. **Dark venues.** Midpoint fills pay no spread, but each venue carries an
   adverse-selection charge in basis points (post-fill markout, for example from
   `execution.VenueScorecard`), and its size is discounted by fill probability. A venue
   whose charge exceeds the lit cost gets nothing.
3. **Lit.** Half spread plus impact. Whatever the dark venues are not expected to fill
   goes lit.

## 5. Economics

`CrbPnlLedger` records realised flow economics: internalised flow earns the street half
spread minus the improvement given to the client; routed flow earns nothing; hedges and
router allocations cost their basis points. `netEconomics()` says whether the captured
spread paid for the hedging. Inventory mark-to-market is kept out of the ledger; it
belongs to the risk report, and mixing realised spread with unrealised marks makes
both harder to read.

`CrbRealWorldScenarioTest` runs a week at realistic sizes, volatilities and costs: a
two-way day, a one-way institutional day that breaches bands and escalates the hedge to
the direct instrument, a stress replay using the March 2020 template, and an NDF fixing
day carried through an overnight checkpoint. The cookbook has a runnable version.

## 6. Overnight persistence

`CentralRiskBook.writeState`/`readState` and the `InternalizationEngine` counters use
`persist.Checkpoint` sections. Factor names, net and gross exposure, per-desk
attribution, pending fixings and the internalisation history survive a restart into
fresh instances. Restoring into a book that already holds positions throws, and an
internalisation history recorded under one warehouse limit refuses to restore under
another.

## Limits of the model

- Exposures are kept in native units per factor (`CCY:JPY` in yen). The covariance
  passed to `report` and `HedgeOptimizer` must use the same units; there is no FX
  conversion step.
- `FXGAMMA` is in quote-currency units, so the report is in one currency only when the
  quote currency is the reporting currency or the caller has converted.
- The NDF fixing figure is gross notional pending, not a dated fixing ladder;
  `fx.FixingRisk` handles dated fixings.
- Booking decomposes positions; it does not model a lifecycle. Expiries, exercises and
  settlements are booked as offsetting flows.
- Single-threaded.

## Not implemented

- Rates, credit and commodity products in the factor space.
- Full revaluation in the book report; VaR is delta-gamma only.
- Multi-threaded or per-tick operation.
