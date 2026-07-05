# Changelog

## Unreleased

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
