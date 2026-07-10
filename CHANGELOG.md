# Changelog

## Unreleased

- **Quant & signals round: toxicity, mean reversion, vol forecasting,
  bar-only liquidity** (tested in `QuantSignalsTest`):
  - `microstructure.Vpin` — volume-synchronized probability of
    informed trading (Easley/López de Prado/O'Hara): fixed-VOLUME
    buckets (informed trading compresses clock time, not volume time),
    trades split across bucket boundaries, NaN before the first bucket
    (an empty average pretending to be calm is the wrong default for a
    risk signal).
  - `microstructure.OrnsteinUhlenbeck` — the pairs desk's engine:
    κ/θ/σ by exact AR(1) mapping, half-life ln2/κ, stationary z-score
    σ/√(2κ) — and it REFUSES a series whose fitted AR coefficient
    shows no in-sample mean reversion, because fitting OU to a random
    walk is how pairs desks die. Planted-parameter recovery tested.
  - `volatility.HarRv` — Corsi's HAR realized-vol model
    (daily/weekly/monthly horizons, plain OLS on the normal
    equations), forecast floored at zero; the planted HAR process's
    coefficients are recovered in tests.
  - `microstructure.LiquidityMeasures` — liquidity from bars alone:
    Roll's bounce-implied spread (NaN — not zero — when the bounce
    signature is absent), Corwin-Schultz high-low spread (negative
    estimates clamp to 0, stated), Amihud illiquidity (zero volume
    throws as the data gap it is).

- **Execution algo round: the four missing desk staples**
  (`execution`, tested in `AdvancedExecutionAlgosTest`):
  - `SpreadExecutionAlgo` — two-legged pairs/basis execution with a
    HARD legging-risk cap: the illiquid lead leg worked patiently, the
    liquid hedge leg chasing at the spread ratio; at the cap the lead
    stops entirely and the hedge child becomes the full imbalance.
  - `OrderPlacementPolicy` — post-or-cross as expected-cost
    arithmetic (`p·(a − h − r) + (1−p)·(h + d)` vs `h`), with the
    break-even fill probability in closed form; adverse selection and
    drift are the caller's honest inputs (post-fill markouts, alpha).
  - `AntiGamingJitter` — seeded size/time randomization for
    schedule-driven children: totals preserved exactly, monotonicity
    preserved, deterministic per seed (replayable, auditable). The
    generic overlay beside `TwapScheduler.scheduleRandomized`'s
    construction-time size jitter — and it adds the TIME dimension.
  - `FuturesRollAlgo` — roll across the window on the liquidity
    migration S-curve (falling behind makes later days bigger, never
    incomplete); each day's due executes as a calendar spread through
    `SpreadExecutionAlgo`; curves that do not end at exactly 1 are
    rejected.

- **CRB real-world layer**:
  - `CrbPnlLedger` — the desk's realized economics: internalized flow
    captures the street half spread minus improvement given back,
    hedges and router allocations cost their bps, and
    `netEconomics()` answers the close-of-day question (did the
    netting pay for its own risk management?). Mark-to-market is
    deliberately NOT mixed in. Checkpointable.
  - `CrbRealWorldScenarioTest` — the desk's actual week at realistic
    sizes/vols/costs: quiet two-way day (internalization rate > 50%,
    warehouse limit held as an invariant), one-way 18M institutional
    day (band breach → cost-aware hedge escalates to the DIRECT
    instrument because a proxy cannot satisfy a per-factor band — and
    the day still nets positive after hedge + routing costs),
    COVID-template stress replay priced to the dollar with
    reverse-stress sigmas, NDF fixing day rolling through the
    overnight checkpoint.
  - `CrbAutoHedger` dust filter: coordinate descent converges
    should-be-zero instruments only to tolerance, and the hedger no
    longer emits sub-cent hedge orders (caught by the realistic
    scenario, not the unit tests).
  - Cookbook recipe 14: run a central risk book day.

- **Central Risk Book** (`com.quantfinlib.crb`, mapped in
  `docs/CENTRAL_RISK_BOOK.md`) — one netted view of the firm's risk
  across desks and products, plus the machinery that monetizes it:
  - `CentralRiskBook` — cash equities, listed equity options (Black-
    Scholes greeks land on the SAME factors as cash shares), FX spot
    (currency-level legs, so EURUSD and USDJPY net their shared USD),
    FX swaps (points risk with exactly-cancelling base legs), NDFs
    (full delta until fixing + gross pending-fixing notional per pair)
    and FX options (Garman-Kohlhagen delta onto the currency legs) —
    all decomposed into one factor space at booking; netting
    efficiency, per-desk attribution, and a `report()` that prices the
    diversification benefit (standalone desk VaRs minus the netted
    book's VaR, via `VarEngine`).
  - `SkewedQuoter` — inventory-shaded two-way prices: a long book
    shades both quotes down, linear in inventory/limit, capped so the
    quote can never self-cross.
  - `InternalizationEngine` — risk-reducing flow is crossed against
    inventory and earns the client a share of the saved spread;
    risk-adding flow is warehoused only inside the warehouse limit;
    flow through zero blends the improvement pro-rata.
  - `HedgeOptimizer` — minimum-variance hedging with an L1 cost term,
    solved by exact soft-threshold coordinate descent: uneconomic
    hedges get exactly zero, λ = 0 recovers the closed-form regression
    hedge (pinned in tests against ρ²-floor residual risk).
  - `CrbAutoHedger` — per-factor bands with cooldown: warehouse inside
    the band, hedge only the EXCESS beyond `resetFraction·limit` on a
    breach, and escalate to cost-blind when the cost-aware hedge would
    leave a hard limit breached.
  - `CrbRouter` — internal cross first (the book is the firm's first
    dark pool: zero cost, zero leakage), dark venues ranked by
    adverse-selection charge and discounted by fill probability, lit
    last; a dark venue whose charge exceeds the lit cost gets nothing.
  - `CrbHedgeUniverse` — builds the loadings matrix the optimizer
    needs, aligned to the book's registry: FX forwards load currency
    legs exactly like booked trades, index futures hedge single names
    through the covariance (the regression hedge falls out of the
    optimizer, not a beta table), hedge-only factors register with
    zero book exposure.
  - Overnight persistence: `CentralRiskBook.writeState/readState`
    (factor names, net/gross, desk attribution, pending fixings) and
    `InternalizationEngine` counters plug into `persist.Checkpoint`;
    restores into fresh instances only, and configuration mismatches
    throw.
  - Review-round fixes (independent math review, every finding pinned
    by a regression test): the FX-swap points factor sign corrected to
    the true sensitivity (−N for a buy-sell — points widening HURTS
    it; the original +N was inverted and its test pinned the wrong
    sign); book-level views hardened for hedge-only factors registered
    past the booked arrays; every multi-leg booking is now
    compute-validate-COMMIT (a rejected leg can never leave a
    half-booked flow) with rate/carry gates added; `HedgeOptimizer`
    validates covariance (NaN used to come back as a silent all-zero
    "hedge"), throws on non-PSD input and on non-convergence
    (relative tolerance, 20k iterations — the Pca lesson applied to
    its sibling); `CrbAutoHedger` rejects NaN exposures
    (`Math.abs(NaN) > limit` is false — a corrupted feed silently
    disabled the bands); `SkewedQuoter` rejects half-spread/skew
    combinations that could quote a non-positive bid;
    `settleFixing(pair, notional)` releases pending NDF fixings
    (over-settling throws — that is a reconciliation break).
  - 24 tests across `CrbBookTest`/`CrbExecutionTest`/
    `CrbPersistenceAndUniverseTest`: cross-product
    netting pinned against `BlackScholes` greeks, swap base legs
    cancel exactly, diversification benefit exact on a hand-built
    two-desk book, internalization economics (improvement blending,
    warehouse limits), optimizer vs closed form, band/cooldown/
    escalation behavior, router cost ordering, and a six-product
    full-loop integration.

## 1.11.0 — 2026-07-09

- **Full-library review round 3 + ULL hardening** (three independent
  audits over new AND old code: hot-path latency, documentation, code
  quality — several findings verified by execution before fixing):
  - `VarEngine.deltaGammaEs` — the Cornish-Fisher tail mean in CLOSED
    form (`ES = −μ + σ·φ(z)/(1−c)·(1 + z·s/6)`), reducing exactly to
    `deltaNormalEs` at Γ = 0 — so all four VaR flavors now genuinely
    carry ES, as the docs already claimed.
  - `PnlAttribution.ksStatistic`: one NaN P&L day used to hang the
    caller in an INFINITE LOOP (NaN ties froze the two-pointer advance);
    non-finite series now throw.
  - Relative Cholesky pivots: `GaussianCopula`'s factorization no longer
    rejects genuinely positive-definite covariances quoted in small
    units (two 0.5bp-vol rate factors used to read "not
    positive-definite"), and `MathUtils.cholesky` now throws on grossly
    indefinite input (a typo'd correlation of 1.3) instead of silently
    simulating a clamped, different dependence structure.
  - `Pca`: input normalized to unit scale before Jacobi (entries near
    1e155 overflowed the norm accumulators into spurious
    non-convergence), and the per-element skip threshold is now
    consistent with the convergence stop (large books no longer spin
    100 no-op sweeps on a converged matrix).
  - Input gates hardened to the house convention: `Heston.callMonteCarlo`
    (was silently NaN where `call` threw), negative valuation time in
    `instantaneousForward`, NaN exposures/shocks/gammas in
    `StressTester`, `GpdFit.var(99.9)`-style confidence typos, non-finite
    losses in `fitPot`, and aliased out/scratch arrays in
    `GaussianCopula.sample`/`sampleT` (dim ≥ 3 silently corrupted the
    dependence).
  - `GjrGarch11`'s fit box now truly spans the admissible region
    (α to 0.99, γ to 1.9), matching what its comment promised.
  - ULL lane: the producer/consumer sequence caches in `TickRingBuffer`
    and `OrderRingBuffer` are padded onto their own cache lines (they
    shared a line with each other and the array refs — cross-core RFO
    on every publish); `HftMarketDataBus`/`HftOrderGateway` counters
    moved off the per-event path onto per-batch release stores;
    `PaperTradingGateway`'s rejection log is capped (was unbounded
    String growth under a misconfigured limit checker); the bus
    documents its megamorphic-dispatch cost. The audit confirmed the
    market-risk batch itself adds zero hot-path regressions and all
    latency/soak budgets remain real.
  - Landing page: market-risk card + MARKET_RISK.md link added;
    Heston/Black-76/higher-order Greeks added to the derivatives card.

- **Review-round hardening** (two independent review passes over the
  market-risk batch; the math pass verified every formula line-by-line):
  - `Heston`: integration window now stretches with the integrand's
    actual decay scale ~1/(σ_eff·√T) — a fixed u-limit of 200 silently
    truncated short-dated/low-vol prices (1-week 4%-vol pinned by a new
    BS-limit test); the branch-cut safety of the principal-arg log is
    now documented as a little-trap-only invariant.
  - `util.MathUtils` gains `logGamma`, `regularizedIncompleteBeta`, and
    an exact `tCdf`; `GaussianCopula.sampleT` now maps t variates
    through the exact CDF instead of a moment-matched normal, which put
    ~37% excess mass below the 1% level at df = 3 — tail uniformity is
    now tested directly.
  - `ShortRateModels.instantaneousForward`: second-order one-sided
    stencil inside the first day (t < 1/365), where the clamped central
    difference reported the forward near (t+h)/2 instead of t.
  - `Garch11`/`GjrGarch11`: fit grids now start from the full
    admissible box — the narrower starting boxes were hard caps the
    refinement passes could barely creep past, silently pinning
    low-persistence/high-ARCH fits (regression test plants α = 0.45,
    β = 0.15).
  - `Pca`: Jacobi convergence thresholds are now relative to the
    matrix's own norm (currency-unit covariances no longer burn all 100
    sweeps), and non-convergence throws instead of returning silently.
  - `ExtremeValueTheory.fitPot`: ties exactly at the threshold no longer
    enter as zero exceedances (discretized P&L no longer biases ξ).
  - `StressTester`: delta-gamma `sensitivityLadder` overload — the
    linear ladder is explicitly documented as delta-only, so a
    short-gamma book's down rungs are no longer silently symmetric.

- **Market Risk Modeling — the 14-step workflow** (mapped end-to-end in
  the new `docs/MARKET_RISK.md`; regulatory pieces styled after BCBS,
  not certified, with SA/NMRF explicitly out of scope):
  - Pricing models (step 4): `pricing.Black76` (the convention futures
    options and caps are quoted in; identity to Black-Scholes with q = r
    pinned), `pricing.Heston` (semi-analytic stochastic vol in the
    stable little-trap form — its BS-limit test caught a genuine
    complex-sqrt precision bug that biased every price ~0.5%, fixed with
    the numerically stable imaginary-part form and locked in at a 5e-4
    test tolerance; MC cross-checked in the Feller-violated regime, produces
    the equity skew for ρ < 0), `rates.ShortRateModels` (Vasicek, CIR,
    curve-fitted Hull-White — reprices today's curve by construction).
  - Greeks (step 5): `pricing.HigherOrderGreeks` (vanna, volga,
    exchange-option cross-gamma — pinned as finite differences of the
    first-order Greeks with convention-coincidence-proof parameters) and
    `rates.KeyRateDurations` (per-node DV01s whose slices sum back to
    the parallel move — tested).
  - Volatility (step 6): `volatility.GjrGarch11` — the leverage effect:
    a down move raises tomorrow's variance by α + γ; on symmetric data
    the honest fit is γ ≈ 0, and on planted-asymmetry data it
    likelihood-beats the symmetric `Garch11` (both tested).
  - Dependence (step 7): `risk.Dependence` (Spearman, Kendall's τ, the
    elliptical τ→ρ bridge), `risk.Pca` (Jacobi eigen-decomposition;
    rejects asymmetric input), `risk.GaussianCopula` (Gaussian +
    Student-t samplers — the t's joint-extreme clustering vs the
    Gaussian's asymptotic independence is pinned by test).
  - VaR & tails (steps 8-9): `risk.VarEngine` — delta-normal, Monte
    Carlo (agrees with delta-normal on linear books), delta-gamma
    Cornish-Fisher (short gamma worsens the tail, long gamma cushions
    it — both tested) and historical, each returning ES beside VaR;
    `risk.ExtremeValueTheory` — POT/GPD via probability-weighted
    moments (recovers a planted Pareto tail index), refusing a finite
    ES when ξ ≥ 1.
  - Stress (step 10): `risk.StressTester` — named scenarios (stylized
    1987/2008/2020 templates documented as starting points),
    sensitivity ladders, and CLOSED-FORM reverse stress: the
    most-probable breaking move and its implausibility in sigmas.
  - Regulatory (steps 11-12): `risk.FrtbEs` (97.5% ES, the
    liquidity-horizon cascade per MAR33.5 with hand-computed test
    arithmetic, stressed calibration floored at 1, Basel traffic
    light) and `risk.PnlAttribution` (the FRTB PLAT: Spearman + KS with
    green/amber/red zones; a model missing a risk factor cannot pass —
    tested).

## v1.10.0 (2026-07-08)

- **Review round 1 over the batch** (3 finder angles, verified then fixed):
  - `ExecutionAlgoBacktester`'s participation cap silently COMPOUNDED with
    the executor's internal 25% depth fraction (a configured 10% cap
    filled at 2.5% — confirmed empirically); the executor is now
    constructed with its depth fraction at 1 so the Config cap is the
    single cap, the doc says so, and the tests assert the EXACT fill
    (6,500) and the exact POV participation (2.6M at 10%) instead of
    bounds loose enough to pass with the cap deleted.
  - `LiquiditySeekingAlgo`: NaN volatility/impact PASSED the calm and
    low-impact gates (a vol-feed outage during a spike would have fired a
    full burst — the exact opposite of the documented "unknowable is
    never cheap"); both gates now fail closed, regression-tested.
  - `Autocallable`: NaN terms constructed successfully and priced as NaN
    (validation is NaN-proof now); knock-in above the autocall barrier
    rejected; per-observation discount factors precomputed (~800k
    redundant `exp` calls per 100k-path price removed).
  - `AlphaEnsemble`: an observation where NOTHING scored no longer counts
    toward the track-record gate; the non-finite test now proves the
    poison-guard preserves a skipped component's evidence instead of
    asserting a counter.
  - `RfqAuction.responseNanos` now reports the FIRST response, not the
    last refresh (a dealer who showed up in 50ms and refreshed at the
    close is fast, not slow); derivable quoteCount state replaced by a
    scan; stray editing artifacts removed from the tree.

- **Five new capabilities across the stack** (each with tests, docs and
  the established disciplines):
  - `microstructure.HawkesIntensity` — **self-exciting event intensity**
    (exponential Hawkes): activity breeds activity, in O(1) per event.
    Stability is enforced, not assumed — a branching ratio α/β ≥ 1
    (explosive) is rejected at construction; `burstScore()` is the
    dimensionless activity-regime signal, out-of-order timestamps are
    dropped rather than becoming negative decay.
  - `microstructure.AlphaEnsemble` — **IC-weighted blend of alpha
    components**, the layer above `OnlineAlphaLearner`: one prequential
    IC per component (snapshot-aligned, the same nowcast trap closed),
    weights `max(0, IC)` deliberately NOT renormalized — a barely-trusted
    blend is a barely-sized signal, never a lone IC-0.01 component at
    full strength. Silent before one IC memory of track record.
    Persistable.
  - `execution.LiquiditySeekingAlgo` — **the opportunistic execution
    archetype** beside the schedule-driven `BenchmarkExecutor`: burst
    when the spread is at/under its `SpreadForecaster` forecast in a calm
    regime with low learned impact; a completion floor ramps over the
    final stretch so patience can never miss the parent. NaN inputs are
    never "cheap" but the floor never depends on observability.
  - **Equity derivatives**: `pricing.Autocallable` (the flagship
    structured note — autocall observations, memory coupons, European
    knock-in; Monte Carlo with antithetic variates; zero-vol cases
    collapse to exact arithmetic in tests, and the GBM/flat-vol/no-credit
    simplifications are documented, not hidden) + the new `rfq` package:
    `RfqAuction` (best/cover by client direction, spread to a model
    fair-value anchor) and `RfqDealerScorecard` (streaming quote rate,
    response time, spread-to-fair, win rate per dealer — the
    panel-selection input, persistable). Three market structures — order
    book, FX quote streams, RFQ — one learned-counterparty discipline.
  - `backtest.ExecutionAlgoBacktester` — **the execution desk's
    backtest**: replays `BenchmarkExecutor` over a session's bars with a
    `TradeCostModel`, grading each benchmark TCA-style (shortfall vs
    arrival, slippage vs session VWAP, both signed so positive = cost on
    either side). Honest simplifications stated: close-price fills,
    participation-capped liquidity, oracle volume curve for VWAP.

## v1.9.0 (2026-07-08)

- **Review round over rounds 4-5** (5 finder angles, verified then fixed):
  - `JumpRobustVolatility` originally normalized the bipower product by
    the current Δt only — on an alternating 10s/0.1s clock a pure
    diffusion read `jumpFraction ≈ 0.74` (confirmed by simulation); now
    `√(Δtₜ·Δtₜ₋₁)`, regression-tested on exactly that clock.
  - `fx.LpScorecard` backported the markout fix its equities twin shipped
    with: the post-reject markout EWMA now seeds from its first matured
    observation instead of ramping from 0 (a toxic LP was under-penalized
    for its first ~1/α rejects — during the burst that revealed it), and
    all mid/price gates strengthened from NaN-only to full non-finite.
    State format v2, still reads v1 (a restored nonzero markout counts as
    seeded).
  - `VenueScorecard`: non-finite (not just NaN) fill-mid and mid-update
    guards — one +Inf sentinel would have seeded the markout EWMA at ±Inf
    and NaN-poisoned it on the next blend; documented that the markout
    leg makes the card SINGLE-SYMBOL (onMid matures every pending fill
    against one mid in absolute price units).
  - `KylesLambda.impactBps` now ignores the SIGN of the quantity (a
    signed sell size read as zero impact — sells were free to the
    executor) and gates infinite quantities.
  - `AvellanedaStoikov`: `log1p` keeps the liquidity floor exact for
    arbitrarily small γ (plain `log(1+x)` quoted a zero-width spread
    below γ/κ ~ 1e-16); the units contract now pins inventory to the
    instrument the mid prices — passing lots instead of shares silently
    gutted the skew.
  - `EwmaCovariance`: constructor refuses basket sizes whose triangle
    index leaves int range; `marginalContribution` returns the portfolio
    variance so `PortfolioExecutor` gates and computes in one O(n²) pass.
  - The overnight simulator now ASSERTS routing abandons the toxic venue
    (zero day-5 flow), not just that its markout reads adverse; docs
    corrected (cookbook recipe 10's undefined `childSize`, index.html
    cards, diagram 11/12 staleness, LEARN §6 model list, lane-map row for
    `ClosingAuctionModel`).
- **Quant models, round 5 — the last live producers + the loop test**:
  - `microstructure.KylesLambda` — **impact learned from the tape**
    (Kyle 1985): streaming through-origin regression of mid change on
    signed flow, `λ = E[q·Δp]/E[q²]` on decayed moments. `impactBps()` is
    the live producer for `MarketState.impactBps` — the last executor
    input that lacked one; a noisy negative λ is clamped there (the
    executor is never "paid to trade") while `lambda()` stays raw for
    diagnostics. Persistable — depth moves slowly enough to carry
    overnight.
  - `microstructure.JumpRobustVolatility` — **bipower variation**
    (Barndorff-Nielsen & Shephard 2004): `(π/2)·|rₜ||rₜ₋₁|` beside the
    classic r², so one headline print reads as a jump, not a volatility
    regime. The two-return product is normalized by `√(Δtₜ·Δtₜ₋₁)`, so
    irregular event-time sampling is handled exactly (a cadence burst is
    not a jump). Exposes robust and raw vols plus `jumpFraction()`; a
    feed gap breaks the consecutive-return pairing rather than inventing
    neighbors. Feed its robust vol to `VolatilityCurve` for cleaner
    baselines.
  - `microstructure.ClosingAuctionModel` — **closing-auction reserve**,
    shipped honestly as a documented-contract structure: the library has
    no venue imbalance feed, so the NOII/imbalance field mapping and the
    `imbalanceSensitivity` calibration are the user's to validate (the
    javadoc says so). Learns the auction's share of daily volume across
    days (persisted); today's imbalance tilts the reserve — opposite-side
    imbalance means the auction wants your shares.
  - `integration.OvernightLearningLoopTest` — **the multi-day session
    simulator**: five synthetic days flow through models → executor →
    router → scorecards, and each night the learned state crosses into
    FRESH instances through a real `Checkpoint` file. Asserts the loop,
    not the pieces: U-shaped curves converge, the alpha learner's IC gate
    opens on planted signal (and a noise-fed control learner earns
    nothing), Kyle's λ recovers the planted depth through four restores,
    the toxic venue's fills read adverse and routing abandons it.
- **Quant models, round 4 — closing the self-documented seams**
  (cross-asset, streaming, allocation-free):
  - `microstructure.EwmaCovariance` — **streaming RiskMetrics-style
    covariance matrix** (flat lower triangle, full-vector rank-1 updates so
    the matrix stays PSD; a sample with any non-finite return is dropped
    whole; pairs seed from first observation). Exposes correlations,
    `portfolioVariance`, `marginalContribution` (sums to 1; a natural
    hedge contributes negatively) and `minVarianceHedgeRatio` — the live
    hedge beta. Persistable via `Checkpoint`.
    `execution.PortfolioExecutor.useRiskModel(EwmaCovariance)` upgrades
    the capacity allocation from the diagonal approximation its javadoc
    always disclosed to true basket marginal risk: two correlated legs
    read as one concentrated risk, a natural hedge earns no urgency, and
    an unlearned matrix falls back to the vol-regime weights.
  - `trading.AvellanedaStoikov` — **closed-form optimal market-making
    quotes** (2008): inventory-shaded reservation price
    `mid − q·γ·σ²·τ` and optimal spread `γσ²τ + (2/γ)ln(1+γ/κ)`, the
    principled version of `HftQuoter`'s heuristic skew. Explicit units
    contract (PRICE variance per second from
    `(mid × SignalEngine.volPerSqrtSecond)²`; τ = risk horizon for 24/5
    FX); NaN/negative variance is neutral (reservation = mid, spread at
    the liquidity floor — never an infinite quote); pure primitive math,
    hot-lane safe.
  - `execution.VenueScorecard` — **post-fill markout (adverse
    selection)**: the `fx.LpScorecard` pending-ring mechanism pointed at
    fills — arm via the extended `onFill(venue, latency, buy, midAtFill,
    ts)`, mature via `onMid`; positive = the price kept going your way,
    negative = your fills fade. `AdaptiveSor` now charges that reversion
    per share in its expected cost (`+ adverseSelection` term), so two
    identical quotes split when one venue's fills systematically revert.
    Scorecard checkpoint format bumped to v2; v1 files (pre-markout) are
    still read, restoring what they hold and leaving markout state cold.

## v1.8.0 (2026-07-07)

- **Full review round over the whole uncommitted batch** (8 independent
  finder angles, every finding verified then fixed):
  - `OnlineAlphaLearner.trainFrom` had **lookahead leakage** — it paired the
    engine's current (post-return) ingredients with the just-realized
    return, so the "out-of-sample" IC could be earned by a momentum echo.
    Now trains on the ingredients snapshotted at the previous call;
    `normalizedPrediction` additionally requires a track record of at least
    one IC memory, and its scale seeds from the first observation instead
    of ramping from 0 (which rail-pinned early signals at ±1).
  - `PortfolioExecutor`: the risk-weighted capacity cut could push |net|
    back over the leg-balance band it had just enforced — the band is now
    re-applied after the capacity pass (band only reduces, so the sequence
    terminates); removed a dead store; clamps via `MathUtils.clamp`.
  - `LeadLagEstimator`: post-restore samples paired real follower returns
    with the zeroed ring for the first `maxLag` updates, diluting the
    persisted correlations every morning — lag updates now gate on the
    ring's actual fill, not the lifetime sample count. Gap javadoc now
    states honestly that lag alignment is in valid samples, not wall-clock
    intervals.
  - `VolatilityCurve`/`SpreadForecaster`: baseline seeding is now **per
    bucket** — a bucket first observed after day 1 (mid-session feed start,
    half day) seeds from its own first session instead of EWMA-ramping from
    0, which read as a false "extreme vol" regime / injected a spread-sized
    deviation shock for weeks. `VolumeCurve.rollDay` documents why volume
    cannot make the same distinction (zero volume is a real observation).
  - `FlowSignals` gap gate strengthened to full dealable-price semantics
    (zero/infinite prices with positive sizes were booking phantom OFI and
    latching sentinel sizes into queue imbalance) — now identical to
    `SignalEngine`'s gate.
  - `BenchmarkExecutor.MarketState.impactBps` was documented but never
    read; it now damps aggression alongside the spread (both are the cost
    of trading now), pinned by test.
  - Hot-path polish: `SignalEngine` hoists a constant `sqrt` and the
    composite weight sum out of the per-event path; the `LpRouter`
    allocation proof now covers the hold-urgency branch; hand-rolled
    `finite()` helpers replaced with `Double.isFinite`; the seven
    `readState` version checks share `Checkpoint.requireVersion`;
    `DayTypeProfiles`' duplicate constructor now delegates.
  - Docs: recipe count corrected (twelve, not nine) in four files; recipe
    10's router snippet now uses `AdaptiveSor`'s actual `RoutingDecision`
    return; recipe 12 guards the first-morning missing checkpoint file and
    the lead-lag "extra alpha input" suggestion now states the rescaling
    and validation it actually requires; the `ULTRA_LOW_LATENCY.md` lane
    map classifies the whole batch (new Warm lane for interval-cadence
    zero-alloc components; `Checkpoint` declared Edge); `ARCHITECTURE.md`
    invariant 5 (recovery over reset) now names the persistence contract;
    `Checkpoint`'s durability javadoc discloses the non-atomic-rename
    fallback; `PortfolioExecutor` docs forbid the `child(h).onFill()`
    ledger bypass explicitly.
- **Two new subsystems** — the pieces beyond the model layer:
  - `persist.Checkpoint` — **multi-day persistence of learned state**: one
    binary file of named, length-prefixed sections written at end of day
    (buffered, then temp-file + atomic rename — a crash mid-save never
    corrupts yesterday's checkpoint; a section writer that throws commits
    nothing) and restored at session start. The models that learn across
    days gained `writeState`/`readState` pairs: `VolumeCurve`,
    `VolatilityCurve`, `SpreadForecaster` (baselines), `OnlineAlphaLearner`
    (weights AND the prequential IC evidence — a learner restored without
    its track record would rightly be silent again), `LeadLagEstimator`
    (correlations; the ring resets so post-restore samples never span the
    overnight gap), `VenueScorecard` and `fx.LpScorecard` (venue/LP quality
    is exactly what a router should not relearn every morning). Intraday
    state resets on read; configuration mismatches, unknown versions,
    unconsumed payload bytes and non-checkpoint files all throw instead of
    misaligning. `HiddenLiquidityDetector` is deliberately NOT persistable
    (price-level-keyed state is stale overnight).
  - `execution.PortfolioExecutor` — **true multi-symbol portfolio-level
    scheduling**: a basket executed as one coordinated schedule over
    per-symbol `BenchmarkExecutor` children (each keeps its own benchmark
    and shaping). The two overlays that only exist at basket level: a
    leg-balance band (projected |buys − sells| notional stays inside
    `maxNetNotional`; the ahead leg throttles, the lagging leg is never
    accelerated past its own benchmark) and a per-interval notional budget
    (`maxIntervalNotional`) allocated risk-weighted — weight ∝
    (1 + volatility regime) × due notional, the honest diagonal
    approximation of multi-asset Almgren-Chriss (the full treatment needs a
    covariance matrix a streaming layer should not pretend to have).
    Overlays only ever reduce a child's own due, so per-symbol benchmark
    integrity holds by construction; deferred quantity reappears via each
    child's behind-schedule catch-up. Zero allocation per decide.
- **Quant models, round 3 — the adaptive layer** (cross-asset, streaming,
  allocation-free):
  - `microstructure.OnlineAlphaLearner` — **online alpha-weight learning**:
    upgrades `SignalEngine.alpha()`'s fixed composite weights to an online
    ridge regression (SGD + L2) from the four dimensionless signal
    ingredients to next-interval returns. The honesty mechanism is
    **prequential evaluation**: every prediction is recorded with the
    current weights *before* the realized return updates them, and the
    rolling out-of-sample IC is computed on those predictions — the learner
    cannot grade its own homework. `trainFrom` closes the other leakage
    door: it fits each return against the ingredients **snapshotted at the
    previous call** (the current ones already contain the move — training
    on them would be a nowcast the IC scores as skill).
    `normalizedPrediction()` (the `MarketState.alpha`-ready form) emits 0
    until the IC is positive AND the track record spans at least one IC
    memory, so an unvalidated learner is silent by construction; full
    validation still belongs to the `alpha` package's walk-forward
    machinery.
  - `microstructure.LeadLagEstimator` — **streaming cross-asset lead-lag**
    (EURUSD leads EURJPY; futures lead cash): per-lag time-decayed
    correlations over a small lag grid from one `onSample(leaderReturn,
    followerReturn)` per interval, with `bestLag()`/`bestCorrelation()`
    detection against the contemporaneous baseline and
    `expectedFollowerReturn()` (regression beta at the best lag) for cross
    hedging/pricing. Gap-disciplined: a non-finite return drops the sample
    entirely — no moment updates, ring untouched. (Alignment is in valid
    samples: across a gap, lag k spans more than k wall-clock intervals —
    documented rather than papered over.)
  - `microstructure.DayTypeProfiles` — **day-type awareness** for the
    seasonality trio: expiry days trade 2-3× with a violent close, half
    days compress the U-curve, FX fixing days concentrate flow at the fix
    — one independently learned `VolumeCurve`/`VolatilityCurve`/
    `SpreadForecaster` per day type, selected once at session start
    (allocation-free selection); the `IntFunction` factory lets rare types
    seed from the regular-day shape instead of ramping cold.
- **Quant models, round 2** (cross-asset, streaming, allocation-free):
  - `microstructure.VolatilityCurve` — intraday **volatility seasonality**,
    completing the seasonality trio beside `VolumeCurve` and
    `SpreadForecaster`: per-bucket baseline learned across sessions, and
    `regime(bucket, currentVol)` producing exactly the normalized 0..1
    volatility signal `BenchmarkExecutor.MarketState` documents — elevated
    *for this time of day*, so the always-wild open doesn't read as
    urgency but a genuinely wild lunchtime does. Closes the units seam the
    review round exposed with a shipped producer (cookbook recipe 10 now
    wires it).
  - `microstructure.TradeClassifier` — **Lee-Ready aggressor inference**
    (quote rule, then tick test with the zero-tick convention) for feeds
    that print trades without saying who initiated — the missing glue for
    `FlowSignals`/`SignalEngine.onTrade`; documented ~85% accuracy per the
    literature, which is why the imbalances it feeds are decayed averages.
  - `microstructure.FillProbabilityModel` — **passive-fill probability
    away from the touch**: reflection-principle touch probability
    (`2·Φ(−d/σ√T)`, reusing `MathUtils.normCdf`) × `QueueModel`'s
    queue-clear probability, documented as a mildly conservative
    independence approximation — the placement score for passive children.
- **Quant model layer for the benchmark algos** — the eight models that
  feed `execution.BenchmarkExecutor.MarketState`, completing the set
  (several already existed; these fill the gaps and make the schedule
  dynamic):
  - `microstructure.VolumeCurve` — **intraday volume prediction**: a
    learned per-bucket profile (EWMA across days) rescaled by today's
    realized-vs-expected ratio, shrunk toward 1 early when the ratio is
    noise. This is the live VWAP curve (`expectedFractionElapsed`), the
    dynamic sibling of the static `ml.IntradayLiquidityForecaster`.
  - `microstructure.QueuePositionEstimator` — **queue position from L2**
    (no L3 needed): join-at-back, trades consume the front, and cancels
    are attributed **pro-rata** to the fraction of the queue ahead — an
    unbiased shares-ahead estimate, fed to `QueueModel` for fill
    probability. (Exact L3 tracking remains `marketdata.L3BookBuilder`.)
  - `microstructure.HiddenLiquidityDetector` — **iceberg / hidden-liquidity
    detection** from the lit tape: a level that trades more than it ever
    displayed and keeps quoting is refilling; the per-level refill-ratio
    EWMA yields `hiddenMultiplier` and `estimatedTrueDepth`. Complements
    `execution.VenueScorecard`'s probe-based dark-venue learning.
  - `microstructure.SpreadForecaster` — **spread prediction**: a
    time-of-day baseline (EWMA per bucket) plus a mean-reverting live
    deviation, so an algo damps aggression *before* a known-wide window
    (the close) rather than reacting after.
  - Already present, mapped for completeness: **volatility forecasting**
    (`ml.VolatilityForecaster` batch, `microstructure.SignalEngine.volPerSqrtSecond`
    streaming), **market-impact estimation** (`microstructure.MarketImpactModel`
    square-root/AC + `ml.MarketImpactPredictor`), **venue fill probability**
    (`execution.VenueScorecard` / `fx.LpScorecard`), **short-term alpha**
    (`pricing.FairValueEngine` microprice + `SignalEngine.alpha`).
- **8-angle review round on the signal/execution/model layer** (every fix
  regression-tested): `VenueScorecard` EWMAs seed from the prior/first
  observation (a venue's first successful fill no longer scored 0.05 and got
  it vetoed); `SignalEngine` rejects zero/infinite prices as gaps (a
  placeholder quote could NaN-poison volatility forever) and re-seeds every
  estimator symmetrically after a gap; `SpreadForecaster` genuinely folds
  days into the baseline via `rollDay` (the cross-day learning the docs
  promised but never did), with a separate deviation-blend weight and
  Inf-safe input; `HiddenLiquidityDetector` flags on a single print larger
  than the display (the cumulative-run form false-flagged ordinary
  fragmented flow); `QueuePositionEstimator.queueProgress` measures against
  the join baseline (was pinned at 0.5); `BenchmarkExecutor` treats NaN
  `MarketState` inputs as neutral (was a silent stall), documents the
  normalized units contract so `SignalEngine.alpha`/vol plug in directly,
  and names the spread-sensitivity and default-urgency constants; shared
  `MathUtils.decayFactor`/`clamp`/`nanArray` replace re-spelled copies;
  `VolumeCurve` volume/prefix sums are O(1); a new cookbook recipe wires the
  models → executor → router pipeline end to end; VenueScorecard allocation
  test added; README/index/LEARN/DIAGRAMS refreshed.
- **`execution.BenchmarkExecutor`** — the dynamic benchmark execution
  algorithm: one stateful, cross-asset executor that works a parent toward
  any standard benchmark — **VWAP, TWAP, Arrival Price, Implementation
  Shortfall, Closing Price, Opening Price, Participation (POV)** — and,
  unlike the precomputed slice lists (`TwapScheduler`, `VwapScheduler`,
  `ImplementationShortfallScheduler`), re-decides each interval from live
  `MarketState`. Two layers: a per-benchmark completion curve (TWAP linear,
  Arrival/IS front-loaded, Close back-loaded `f²`, Open aggressively
  front-loaded `√f`, VWAP on the expected volume profile, POV on realized
  volume) and a dynamic adjustment consuming the real-time inputs a
  production algo watches — bid/ask spread (cost damping), volatility (the
  timing-risk trade-off: raises IS/Arrival urgency, lowers passive
  aggression), alpha (accelerate into an adverse move, ease on a favorable
  one), and a liquidity cap (each child bounded by displayed depth) — with
  `onFill`/`onMarketVolume` feedback so schedule drift self-corrects.
  Allocation-free decisions.
- **`execution.AdaptiveSor` + `execution.VenueScorecard`** — the
  full-checklist smart order router, completing the routing family beside
  `SmartOrderRouter` (readable) and `HftSor` (zero-alloc tick path). It
  ranks lit venues by *expected cost per share* — all-in price (fees/
  rebates) discounted by `(1−fillRate)×missPenalty` and `latency×urgency` —
  vetoes venues below a reliability floor, and emits contingent dark-pool
  probes sized by *learned* hidden liquidity (default until known). All the
  per-venue inputs — fill rate, measured response latency, realized
  dark-probe fills — stream from `VenueScorecard` (the equities counterpart
  of `fx.LpScorecard`), and the passive-leg queue-position probability
  delegates to `QueueModel`. Worked example (A: 10k@120µs, B: 8k@80µs same
  price, dark unknown) routes 8k→B, 2k→A and probes dark, as expected.
  Cross-asset by construction (raw double prices): a pinning test routes FX
  ECN venues with a mid-match pool through the identical path. And
  `fx.LpRouter` gained the missing latency dimension for the LP-stream
  side: an optional hold-time urgency (bps per ms held) prices an LP's
  last-look deliberation into the expected cost, so a slow holder loses
  ties exactly like a high-latency venue (non-breaking overload).
- **`microstructure.SignalEngine`** — the unified streaming signal engine:
  one multi-symbol, hot-lane component (dense int symbol ids, zero
  allocation per event, single writer) computing the five signal families
  for equities and FX alike: **imbalance** (Cont-Kukanov OFI, inside queue
  imbalance, signed trade flow — via a per-symbol `FlowSignals`),
  **fair value** (size-weighted microprice), **volatility** (streaming
  EWMA realized-variance rate over irregular tick arrivals, exposed per
  √second), **liquidity** (time-decayed spread/spread-bps, displayed
  depth, quote arrival intensity), **momentum** (time-aware fast/slow
  EMAs — decay by elapsed time, not update count) and a weighted
  **composite** of dimensionless ingredients (OFI normalized by displayed
  depth, momentum scaled by horizon volatility, both clamped) —
  documented as a research scaffold to be validated through the `alpha`
  package, not a tradable signal. Gap discipline throughout: one-sided or
  NaN quotes update nothing and poison nothing, and the move across a gap
  is never counted as a return.
- **`FlowSignals` generalized to double prices** (non-breaking): the
  tick-based API remains and delegates — integer ticks are exact in a
  double — so one implementation serves equity ticks and raw FX rates; an
  exact-agreement test pins the two entry points together.

## v1.7.0 (2026-07-07)

The FX market-structure layer: the mirror image of the equities stack —
quotes, not orders; last look, not price-time priority. Plus a from-zero
learning guide for students and juniors, four new architecture diagrams,
and two full review rounds with every finding fixed and regression-tested.

- **`docs/LEARN.md`** — a beginner's tutorial covering every concept in the
  library in plain language: the finance (two-sided prices, order books and
  price-time priority, L1/L2/L3 and book building, equities-vs-FX market
  structure, last look, market making and adverse selection, TWAP/VWAP/POV/
  implementation-shortfall, tape signals, factor research and its
  self-deceptions, risk gates, derivatives, honest backtesting) and the
  technology (the latency ladder, GC and why zero allocation, rings and
  single-writer discipline, primitive maps/bitmaps/flyweights, the memory
  model in plain words, percentile-honest measurement, FIX and binary
  protocols, sharding, and this repo's testing philosophy) — each section
  tied to the class that implements it, with a guided reading path,
  exercises, and a glossary. Linked from the README, the docs site nav and
  the cookbook.

- **FIX market data in**: `fix.FixMarketDataView` — garbage-free 35=W
  (snapshot) / 35=X (incremental) decoding in the `FixExecReportView`
  mold: one-pass flyweight, preallocated entry arrays, scaled-long
  prices, entry position preserved (for tiered LP streams position IS
  the tier), bounded entries with drop counting.
- **Tiered LP book**: `fx.FxTierBook` — per-LP size-tier ladders under
  the `AggregatedBook` composite; sweep cost across LPs (NaN when the
  book cannot fill the size — a partial sweep is not a price), sweep
  plans with per-LP attribution, and best single-LP full-amount quotes
  (the no-signaling convention). Zero allocation.
- **Last-look analytics**: `fx.LpScorecard` — streaming per-LP EWMA
  reject rate, hold time, effective spread, and post-reject markout
  (the market's move after a decline = the realized cost of that LP's
  last look).
- **Last-look-aware routing**: `fx.LpRouter` — expected-all-in routing:
  quoted price + rejectRate × adverse markout, reject-rate veto,
  full-amount only (deliberate: multi-LP sweeps leak intent; use
  `FxTierBook.sweepPlan` and own it).
- **Maker side**: `trading.LastLookGate` — symmetric price check per FX
  Global Code Principle 17, with disclosure statistics split by who the
  reject protected; a randomized test asserts the 50/50 split symmetric
  handling produces.
- **Benchmark fixings**: `execution.WmrFixingScheduler` — TWAP across
  the WMR calculation window as neutral benchmark replication;
  pre-hedging deliberately not implemented, reason documented.
- **Cross execution**: `fx.SyntheticCross` — direct-vs-legs all-in
  comparison with correct spread composition (a synthetic crosses two
  spreads), NaN-safe so unquoted routes never win.
- **8-angle review round on the layer** (every fix regression-tested):
  `FxTierBook.tier` now bounds-checks (an unchecked tier overflowed into
  the NEXT LP's ladder silently); sweeps rewritten on per-LP frontier
  cursors — NaN tiers can no longer poison selection, and picks scan
  O(lpCount) instead of every tier; `LpScorecard` ignores NaN mids (one
  poisoned the markout EWMA forever, permanently capturing the router)
  and matures markouts from a 4-slot ring per LP so reject bursts are
  sampled, not overwritten (single-slot markout was anticorrelated with
  reject rate — underpenalizing exactly the bursty rejectors); router
  candidates with non-finite expected prices never win; `SyntheticCross`
  treats zero/negative legs as unpriced (a zero divide produced +∞
  "savings"); negative FIX prices parse correctly and negative quantities
  fail loudly (shared `FixParse`, also fixing the committed
  `FixExecReportView`); `FixMarketDataView` zeroes entries on open (35=X
  deletes no longer expose a prior message's stale price) and gains a
  per-entry-decimals-safe `price()` getter; `WmrFixingScheduler`
  delegates to `TwapScheduler` (the neutral-replication claim is now true
  by construction) and rejects zero-quantity children and overflowing
  windows; `LpScorecard.maturedMarkouts()` exposes the router-degradation
  canary; four package-infos updated; `SyntheticCross` allocation test
  added.
- **Second review pass (fixes-of-the-fixes + docs)**: counterparty-format
  ClOrdIDs (unsolicited venue reports) map to the -1 "not ours" sentinel
  instead of throwing mid-`wrap` (the quantity-hardening had made tag 11
  brittle); a NaN reference mid on `onReject` can no longer start a
  markout (the second NaN door into the router); `FxTierBook.bestBid/Ask`
  read each LP's frontier tier so a malformed tier 0 can't mask a live
  deeper quote or NaN the composite mid; dealable = strictly positive
  everywhere in the tier book (a 0.0 decoded price could win every buy
  sweep); `FixMarketDataView.price()` clamps absurd decimal counts;
  zero-size clips are not quote requests. Docs: `docs/DIAGRAMS.md` gains
  four diagrams (equities participant stack, FX last-look loop, shard
  topology, execution-algorithm decision map); `docs/ARCHITECTURE.md`
  package map brought fully current (~30 classes across two layers);
  README layout tree gains `sbe`/`cli` and the full examples list;
  LEARN.md fact-check fixes (BookPrimitives attribution, exercise 3,
  GC-pause and CPU-speed calibration, tape/dark-venue definitions);
  benchmark-count and orders/s claims made exact.

## v1.6.0 (2026-07-07)

The equities market-structure release: the participant side of the market —
raw L3 feed in, routed orders out — hot-lane end to end, hardened by an
8-angle review round.

- **L3 market data in**: `marketdata.ItchCodec` (ITCH 5.0-style binary
  codec — add/execute/cancel/delete/replace/trade with exact big-endian
  layouts; flyweight decode, packed-long symbols, 0.0001-tick int prices;
  encoders for simulators/replay) and `marketdata.L3BookBuilder`
  (full-depth book reconstruction with the matching engine's disciplines —
  tick ladder, occupancy bitmaps, pooled intrusive nodes, backward-shift
  ref map — plus exact own-order queue position: one FIFO walk to
  initialize, O(1) maintenance per event from price-time priority facts,
  `sharesAhead(ref)` in constant time; zero allocation proven).
- **NBBO**: `marketdata.Nbbo` — multi-venue inside consolidation with
  venue bitmasks at the touch, locked/crossed detection, and a listener
  that fires only on inside changes.
- **Flow signals**: `microstructure.FlowSignals` — time-decayed
  Cont-Kukanov best-level OFI, inside queue imbalance, signed trade-flow
  imbalance; allocation-free streaming.
- **Equities order types on the venue book**: `HftOrderBook.submitIoc`,
  `submitFok` (bitmap-walk liquidity probe; a kill emits no trades),
  `submitPostOnly` (`REJECT_WOULD_CROSS`).
- **Hot-lane routing**: `execution.HftSor` — zero-allocation greedy
  all-in-price routing (fees/rebates in ticks) over parallel venue arrays.
- **Execution algos**: `execution.PovTracker` (streaming participation
  ledger measured against others' flow) and
  `execution.ImplementationShortfallScheduler` (Almgren-Chriss-optimal
  slicing + front-load→λ calibration by bisection).
- **Venue self-protection**: `trading.OrderThrottle` (caller-clocked
  nanosecond token bucket for message-rate limits) and
  `microstructure.CircuitBreakers` (LULD bands, 15s limit-state → 5-min
  pause machine, market-wide 7/13/20% halts — styled after the SEC plan,
  not certified).
- **8-angle review round on the layer** (every fix regression-tested):
  overflow-proofed the IOC/FOK sentinel limit clamps (an extreme passive
  limit could wrap into a market sweep); L3BookBuilder rejects duplicate
  wire refs (replay would corrupt the ref map) and `track` is idempotent;
  MWCB no longer downgrades a Level-3 day into 15-minute halts and
  validates its time unit; FlowSignals treats one-sided/sentinel quotes as
  signal gaps instead of maximal pressure; `Nbbo.midTick` sums in long;
  IS calibration survives sinh overflow and fails loudly when it cannot
  land; LULD pause expiry is pollable without quotes; shared
  `orderbook.BookPrimitives` (bitmap scans + backward-shift map exist
  once); six new allocation-counter tests back every zero-alloc claim
  (Nbbo, throttle, LULD, wire-decode path, POV, TIF ops) plus a randomized
  FOK-atomicity property test and an NBBO fast-path differential test.

## v1.5.0 (2026-07-06)

The scale-and-usability release: the venue-grade matching engine, horizontal
sharding with firm-wide risk, the garbage-free FIX round trip, unified trade
costs across engines, CI-enforced performance floors, architecture diagrams,
a task-shaped cookbook, and a five-minute live trading demo — plus two full
review rounds' worth of fixes with every finding regression-tested.

- **Real-world usability**: `docs/COOKBOOK.md` (nine task-shaped recipes,
  each complete and under ~20 lines — CSV backtests, screening, factor IC
  + overfitting defenses, survivorship-honest portfolios, FX derivatives,
  live paper trading, the nanosecond hot path, capture/replay, venue
  matching); `examples.LiveTradingDemo` (live Binance trades → streaming
  EMA crossover → paper venue → browser dashboard, one command, no keys);
  Maven Central publishing wired as an inert `central-release` profile
  (gpg + central-publishing plugins) with the owner's one-time setup steps
  in `docs/PUBLISHING.md`; README gains the five-minute demo, cookbook
  pointer and getting-the-library section (JitPack usable today).
- **Load & performance test layer** (`trading.LoadAndSoakTest` + promoted
  benchmarks): CI-enforced throughput FLOORS as regression tripwires
  (~20× below desktop-measured, so shared runners never flake but an
  accidental lock/allocation/O(n) slip on a hot path fails the build:
  bus ≥ 500k ticks/s, gateway ≥ 1M orders/s, matching engine ≥ 500k ops/s,
  full quoting pipeline ≥ 100k ticks/s with ZERO dropped quotes); a 5M-op
  soak test asserting the heap ends where it started (the leak/drip
  detector complementing the allocation-counter proofs); and an overload
  test with deliberately tiny rings proving the degrade-and-count contract
  (publish false / submit 0 / counters tick, nothing throws, full recovery
  after the burst). The scale probes are promoted to committed benchmarks:
  `examples.ScaleBenchmark` (N crosses / conflation sweep) and
  `examples.ShardScaleBenchmark` (shard-count sweep).
- **Horizontal scaling machinery** ("how scalable is this", answered with
  code + measurements): `trading.ShardedTradingEngine` — N shared-nothing
  bus→gate→gateway stacks behind one zero-alloc symbol-routing facade,
  with multi-shard symbol registration for cross-leg co-location;
  `HftRiskGate.kill()` gate-wide kill switch (`REJECT_KILLED`, one acquire
  read on the check path) + `referencePrice`/`symbolCapacity` accessors;
  `trading.GlobalRiskAggregator` — the firm-wide gross-notional circuit
  breaker across every shard's gate (poll-based monitor over the gates'
  already-acquire-readable state, hysteretic resume). Probed on a
  12-core desktop: 1→2 shards +46% aggregate throughput with zero
  cross-shard contention; the 4-shard plateau is core count + single
  producer, documented as such.
- **Hot-lane completeness audit** ("make every part ULL", answered
  honestly): every package now has a DECLARED lane in the ULL doc's new
  lane map — hot (proven zero-alloc), edge (buffered I/O adapters), or
  research (clarity first, off any tick path by construction). The audit
  found two components that genuinely belonged in the hot lane and weren't:
  - **`fix.FixOrderEncoder`** — garbage-free FIX 4.4 NewOrderSingle
    encoding for venues that only speak FIX: reusable byte buffer with the
    BodyLength prefix written backwards, ASCII digit writers, prices as
    scaled longs, per-day cached timestamp prefix, symbols pre-registered
    as bytes. Round-trip-verified against the validated `FixMessage.parse`
    (BodyLength + CheckSum checked) and allocation-proof tested.
  - **`fix.FixExecReportView`** — the inbound half of the garbage-free FIX
    round trip: a flyweight ExecutionReport reader over the framed bytes
    (one pass, primitive getters, LastPx as a scaled long, symbol compared
    in place) so fills feed `HftRiskGate.onFill` without a String or a
    double anywhere. Proven field-identical to messages built by the
    validated `FixMessage.Builder`, with a loud failure on truly fractional
    quantities and the allocation-counter test.
  - **`data.AsyncTickCapture`** — tick recording with file I/O moved off
    the bus consumer thread through a private `TickRingBuffer` and writer
    thread; backpressure drops-and-counts (`droppedTicks`) instead of ever
    stalling the trading loop. `TickCapture`'s javadoc now states its
    consumer-thread I/O trade-off and points here for trading sessions.
- **`HftQuoter.Config.withMinMove`**: purely move-gated conflation (interval
  gate set effectively infinite) — the fan-out control for dense synthetic
  cross books. Scale-probed: 10,000 crosses over 200 direct pairs on one
  shard run at ~410k inbound ticks/sec with a 2-pip gate (~99% of cross
  updates suppressed, zero drops) vs ~50k ticks/sec quote-everything.
  `withConflation`'s javadoc now warns about the pitfall this solves:
  interval 0 disables conflation entirely (BOTH gates must pass to
  suppress), it does not mean "suppress on move alone".
- **Backlog burn-down** (the four deferred quality items, closed):
  `BusinessCalendar.union` + `subtractBusinessDays` — the FX joint-calendar
  rule lives in ONE place, with `CurrencyPair` delegating every roll
  convention (and the triplicated walk-back loops in Ndf/FxSwap/
  SwapPointsCurve replaced by `CurrencyPair.tradeDateForSpot`);
  `HftQuoter.configureSymbol` — per-instrument half-spreads/skews/grids so
  one quoter serves a mixed EURUSD/USDJPY book; `MathUtils.pairSort`
  (primitive dual-array quicksort) replaces the boxed `Integer[]` sorts in
  `CrossSectionalMomentum`, and `SignalEvaluator.ranks` computes midranks
  via sorted-copy binary search — no boxing anywhere in the rank path;
  `AlphaValidation.walkForward`/`parameterSensitivity` compute the IC
  matrix once on a global date grid (scores per candidate-date evaluated
  once, forward returns shared across candidates) instead of recomputing
  per overlapping fold — up to ~trainBars/testBars× less factor work in
  sweeps, with window containment (no train/test leakage) preserved.
- **Unified trade costs** (`backtest.TradeCostModel`): one pluggable
  per-trade cost definition shared by the engines — `flat(bps)` reproduces
  the legacy commission exactly; `institutional(commission, halfSpread,
  slippage, impactWindow)` adds square-root market impact from the shared
  `MarketImpactModel.estimate` bar-data bridge (extracted from
  AlphaBacktester, so the two engines can never disagree on impact).
  `PortfolioBacktester.Config.withCostModel(...)` closes the review's
  known gap: a single run is now survivorship-aware AND execution-aware —
  lifecycle events and size-dependent costs together, proven by test.
- **HftOrderBook** (`orderbook`): venue-grade matching engine — dense
  integer-tick price ladder with per-side occupancy bitmaps, pooled
  intrusive order nodes, primitive open-addressing id map with
  backward-shift deletion, single primitive trade sink; zero allocation in
  steady state (allocation-counter test) and correctness pinned by a
  model-based equivalence test against the readable reference `OrderBook`
  (identical random operation streams → identical books and traded volume).
  Measured by the new `HftBookBenchmark`: ~204 ns/op (p99 504 ns) on a
  70/20/10 add/cancel/aggress mix, 10M+ fills/sec, 7M+ add/cancel ops/sec
  over a 20,001-level band; the full session also completes under Epsilon
  GC. Prompted by an external reviewer correctly noting the reference
  book's allocation profile — the participant/venue boundary is now both
  documented AND covered on the venue side.

The memory-model honesty patch: the pre-trade risk gate is now provably
safe under the multi-threaded wiring it was always documented to serve,
and every latency claim was re-measured and corrected accordingly.

- **HftRiskGate cross-thread safety**: positions/halts/reference prices now
  use per-element VarHandle acquire/release (fills via atomic add) — the
  production wiring reads positions on the bus consumer thread while fills
  land from the venue-ack thread, and the previous plain arrays were a data
  race (stale skew/limits, potential lost fills under concurrent sources).
  Honest cost: the risk-check microbenchmark moves from ≈1 ns to ≈3 ns,
  because the ≈1 ns figure relied on the JIT hoisting loads a concurrent
  gate must re-read; end-to-end tick-to-order and throughput are unchanged
  (p50 408 ns, 21M orders/s on the same box). All latency claims updated.
- `FxVolSurface.vol()` does one bracket search instead of two;
  `atmVol()` no longer scans the expiry list twice.

## v1.4.0 (2026-07-06)

The alpha research release: a full factor pipeline (signals → IC evaluation →
overfitting defenses → cost-aware backtests → neutral construction →
attribution reports), survivorship-honest end to end — plus the
survivorship engine itself (point-in-time universes, delisting returns,
merger conversions, ex-date dividends) and two hardening review rounds
with every finding fixed and regression-tested.

- **Second review round** (7-angle review of the survivorship+alpha batch):
  fixed walk-forward **train/test leakage** (forward-return windows spilled
  past the training boundary — in-sample selection was reading up to
  horizon−1 bars of test returns), walk-forward NPE when every candidate's
  training IC is NaN, `HftQuoter` throwing on the bus consumer thread when
  inventory skew pushed a quote below a banded schedule's first band (new
  `TickSizeSchedule.roundDown/UpClamped`), `TickBacktester` restored to
  total bucket semantics on the finer clamped tick (the distance form had a
  dead zone at half-tick boundaries where midpoint prints neither filled
  nor accrued queue), `AlphaBacktester` failing loud on cost ≥ 100% of
  equity (capacity answer, not negative-equity garbage) and validating
  `startIndex ≥ impactWindow`, MACD factor rewritten as one incremental
  pass (~50× fewer reads, and no zero-bias from pre-history bars),
  `SignalEvaluator` turnover sharing the IC series' date denominator,
  `AlphaReport` NaN-guarding attribution, using sample-stdDev rolling
  Sharpe (matches `summarize()` exactly) and the `peak > 0` drawdown guard,
  `PortfolioConstruction.trailingBetas` delegating to `RiskMetrics.beta`,
  a `sectorNeutralize(ctx, weights, map)` overload that cannot misalign
  with the sorted panel, deterministic same-bar lifecycle ordering in
  `PortfolioBacktester` (mergers before delistings, sorted symbols), and
  the epoch-seconds heuristic shared from `CsvBarLoader` with line-tagged
  errors in both loader passes.
- **Survivorship-aware alpha research**: `AlphaContext.withUniverse` gates
  every built-in factor through point-in-time membership (`isActive`) —
  the alpha pipeline and the survivorship engine now compose.

- **Review fixes** (multi-angle code review of the v1.3.0 batch): dividends
  now credit before same-bar lifecycle events (a name delisting on its own
  ex-date still pays its holder); NDF fixings walk back in the restricted
  currency's LOCAL business days, not joint (`CurrencyPair` gained
  `baseCalendar()`/`quoteCalendar()` accessors); aged `FxSwap`s mark against
  later curves — settled legs contribute zero instead of throwing;
  `AutoHedger`'s cooldown uses a sentinel so startup hedges fire on clocks
  starting at/below zero; `TickBacktester`'s banded price-level comparison
  is total (clamped below the first band via
  `TickSizeSchedule.tickForClamped`) and band-coherent (finer tick of the
  print/limit pair, fixing cross-band trade-through misclassification);
  `UniverseCsvLoader` applies the same whole-file epoch-seconds detection
  as `CsvBarLoader`, and its row errors carry line numbers for all failures
  including malformed dates.

- **Alpha research pipeline** (`alpha`, new package): `AlphaContext`
  (deterministic frozen symbol panel), `Factors` (MA crossover, MACD, 12-1
  momentum, contrarian RSI, Bollinger reversion, mean reversion, value,
  quality, low volatility — stateless, no-look-ahead contract),
  `SignalEvaluator` (rank IC on non-overlapping windows, IR, t-stat, hit
  rate, implied turnover, cross-factor exposure), `AlphaValidation`
  (walk-forward with OOS efficiency, blocked k-fold, Monte Carlo
  permutation p-values — conservative for time-invariant signals by
  design, parameter sensitivity), `AlphaBacktester` (commission, spread,
  slippage, square-root market impact with per-symbol ADV/vol; gross-vs-net
  with per-component cost drag), `PortfolioConstruction` (winsorized
  z-score sizing, caps, inverse-vol budgeting, exact sector/beta
  neutrality, mean-variance tilt), `AlphaReport` (decay half-life, OLS
  attribution, drawdowns, rolling Sharpe; ratio set shared with the
  backtest engine).

- **Survivorship-bias defense (engine half)**: `data.PointInTimeUniverse` —
  point-in-time membership intervals (drop-and-re-add supported) plus
  terminal events (delisting with delisting return incl. the Shumway −30%
  involuntary-delisting constant; merger with cash and/or acquirer-share
  terms). `PortfolioBacktester` gained a universe-aware overload: positions
  terminate at `lastClose × (1 + delistingReturn)` on the event bar, mergers
  convert at deal terms, index drops force liquidation, and explicit cash
  dividends credit holders / debit shorts on the ex-date (feed unadjusted
  prices). `StockScreener.membersAsOf` filters snapshot universes
  point-in-time. Tests quantify the bias itself: the naive run overstates
  final equity 2× against a wipe-out delisting. The data half (dead-ticker
  histories, delisting returns) still requires a CRSP-style dataset — by
  nature not solvable in code.
- **Universe CSV interchange format**: `data.UniverseCsvLoader` — documented
  `symbol,event,date,end_date,value,acquirer_shares,acquirer` rows
  (MEMBER/DELIST/MERGER; ISO or epoch dates parsed exactly like bar files;
  empty delisting return defaults to the Shumway −30%), row-numbered error
  reporting for hand-curated files. Free constituent lists (e.g.
  `datasets/s-and-p-500-companies`) load directly as open-ended memberships.
- **Point-in-time cross-sectional momentum**:
  `backtest.portfolio.CrossSectionalMomentum` — Jegadeesh-Titman 12-1
  long/short factor that ranks only the universe members alive at each
  rebalance timestamp; equal-weight dollar-neutral books with side-size
  shrinking under scarce members. End-to-end test shows the naive
  (no-universe) run overstating momentum P&L by keeping a delisted short
  alive.

## v1.3.0 (2026-07-05)

Release: https://github.com/AshJha0/Quant-Finance-Library/releases/tag/v1.3.0

The FX & equities instruments release — every layer of an FX desk (spot
conventions through NDFs and the delta-quoted smile) plus exchange mechanics
for equities, with the market-making loop measured at sub-microsecond:

- **FX asset class** (`fx`, new package): `CurrencyPair` market conventions
  (pips, precision, T+1/T+2 spot lags, dual-calendar tenor dates with
  modified-following/end-end), `SwapPointsCurve` (quoted points → outrights,
  broken dates, CIP implied carry), `FxSwap` (near/far legs, points MTM,
  roll cost), `Ndf` (per-currency fixing lags, USD-settled difference),
  `FxVolSurface` (delta-quoted smile: ATM DNS + RR/BF, premium-adjusted
  delta↔strike solving), `FixingRisk` (fix-window TWAP/VWAP tracking error).
- **e-FX hot path**: `fx.AggregatedBook` (zero-alloc multi-venue composite
  BBO with venue attribution), `fx.CrossRateEngine` (streaming synthetic
  crosses on the bus consumer thread), `sbe.QuoteFlyweight` (48-byte binary
  two-sided quote codec), `trading.HftQuoter` (streaming market maker:
  inventory skew, tick-grid snap, conflation — measured tick-to-two-sided-
  quote p50 592 ns via the new `HftQuoterBenchmark`), `trading.AutoHedger`
  (live position-band hedging with cooldown), `pricing.IncrementalGreeks`
  (zero-alloc delta-gamma tick updates with off-path re-anchoring).
- **Options exotics** (`pricing`): `DigitalOption` (cash/asset-or-nothing),
  `TouchOption` (one-touch/no-touch hit probabilities), `BarrierOption`
  (regular knock-in/out closed form, in-out parity, reverse barriers
  rejected explicitly), `VannaVolga` (three-pillar smile-consistent pricing,
  exact at pillars) — all Monte Carlo cross-checked in tests.
- **Equities mechanics**: `pricing.DividendSchedule` (escrowed discrete
  dividends + borrow cost), `microstructure.TickSizeSchedule` (MiFID II-style
  price-banded ticks, wired into `TickBacktester.Config.withTickSchedule`),
  `microstructure.Auction` (call-auction uncross: max volume → min surplus →
  reference proximity, market-on-auction orders).
- **FX backtest realism**: `backtest.LastLookExecution` (LP rejects on
  adverse hold-window moves; reject-rate TCA).
- **License**: released under the MIT License (`LICENSE` file added; license
  metadata in `pom.xml`, badge and section in the README, docs-site footer).

## v1.2.0 (2026-07-04)

Everything since v1.1.0 — the trading-system release:

- **Live market data** (`feed`): WebSocket adapter (pure JDK) with Binance
  reference parser, reconnect with backoff, exchange event-time stamps;
  tested against an in-repo RFC 6455 loopback server.
- **FIX 4.4 engine** (`fix`): validated codec, fragmentation-safe decoder,
  initiator/acceptor sessions (logon, heartbeats/TestRequest, logout),
  NewOrderSingle / ExecutionReport, and full ResendRequest recovery
  (message store, PossDup replays, SequenceReset-GapFill, duplicate
  suppression).
- **Tick capture & replay** (`data`): QFLT binary format, bus capture,
  as-fast-as-possible and paced replay; **tick-level backtesting**
  (`backtest.tick`) with queue-position-aware limit fills.
- **HFT order fast lane** (`trading`): zero-allocation HftRiskGate
  (~1 ns/check), OrderRingBuffer, HftOrderGateway — tick-to-order
  end-to-end p50 ≈ 504 ns, 15M orders/sec; allocation-free proven by test.
- **Research validation** (`backtest.validation`): grid search,
  walk-forward analysis with stitched OOS equity and efficiency ratio,
  probabilistic + deflated Sharpe.
- **Portfolio engine** (`backtest.portfolio`): multi-asset long/short
  backtester, position sizing (Kelly, fixed-fractional, inverse-vol,
  vol targeting); **portfolio construction** (`optimization`): risk
  parity, Black-Litterman, constrained optimizer with turnover penalty.
- **Quant models**: yield-curve bootstrap + bond analytics with real
  day-count/calendar conventions (`rates`); EWMA + GARCH(1,1) MLE
  (`volatility`); CRR American options + SABR calibration (`pricing`);
  Engle-Granger cointegration, options-book Greeks/scenarios/P&L-explain
  (`hedging`); 2-state Markov-switching regimes (`ml`); corporate-action
  back-adjustment (`data`).
- **Operations**: CLI (backtest / walkforward / report), live paper-trading
  dashboard (JDK httpserver), paper gateway with pre-trade risk gate,
  SVG charts in HTML reports, CSV/HTTP data loading.
- **Engineering**: model-based fuzz tests, ring-buffer stress tests, JMH
  microbenchmarks (test scope), GitHub Actions CI with JaCoCo coverage,
  runnable jar (`java -jar ... backtest ...`), sources + javadoc jars.

## v1.1.0 (2026-07-04)

- Initial GitHub release: 11 research capabilities (indicators, backtesting,
  DSL, risk, ML, optimization, Monte Carlo, screener, market data, reports),
  microstructure/execution/regulatory stack, hedging suite with
  Black-Scholes/vol surface, HFT market-data hot path (p50 ≈ 204 ns
  publish-to-strategy), execution-aware backtesting with TCA.
