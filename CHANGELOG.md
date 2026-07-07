# Changelog

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
