# API stability

The library has three kinds of code, and they come with different promises. This page
says which package is which and how versions are numbered from 2.0.0 onward.

Since 3.0 the tiers are the Maven modules, so the jar you depend on tells you the
promise:

| Tier | Artifact | JPMS module | Source tree |
|---|---|---|---|
| Stable | `quant-finance-library` | `com.quantfinlib` | `core/` |
| Reference | `quant-finance-library-lowlatency` | `com.quantfinlib.lowlatency` (`requires transitive com.quantfinlib`) | `lowlatency/` |
| Examples | `quant-finance-library-lowlatency`, classifier `examples` | not a module export | `lowlatency/src/main/java/com/quantfinlib/examples` |

## Stable research and risk APIs

These packages are the library's main purpose. Their public classes and methods are
covered by the versioning policy below, and numerical results change only to fix a
documented error.

| Area | Packages (under `com.quantfinlib`) |
|---|---|
| Data and series | `core` (including the exception types and, since 3.0, the order `Side`), `data` (CSV loading, alignment, corporate actions, point-in-time universes), `marketdata.tick` (the QFLT tick-file reader and writer, since 3.0), `stats` |
| Pricing and instruments | `pricing`, `rates`, `credit`, `fx` (conventions, curves, instruments, volatility surface; `CrossOp` since 3.0), `commodities`, `markets` |
| Risk | `risk`, `crb`, `volatility`, `hedging` |
| Portfolio and research | `optimization`, `alpha`, `backtest` and its subpackages, `indicators`, `simulation`, `ml`, `screener`, `dsl` |
| Execution research | `microstructure`, `execution`, `regulatory`, `rfq` |
| Output and tools | `report`, `cli` (commands and options), `persist` |

Types added in 2.1 in these packages are stable on the same terms: in `rates`,
`DiscountCurve`, `MultiCurveBootstrap`, `MultiCurveRisk`, `CurvePair` and the curve
instruments (`CurveInstrument`, `Deposit`, `Fra`, `RateFuture`, `OisSwap`,
`InterestRateSwap`); in `credit`, `IsdaCdsModel`, `IsdaCurve`, `ExposureSimulator`
(folded into `MultiCurrencyExposureSimulator` in 3.0) and `ExposureProfile`; in `risk`, `SaCcr`, `SaCcrTrade` and `FactorRiskModel`; in
`pricing`, `SviSlice`, `SsviSurface`, `SviSurface`, `TotalVarianceSurface`,
`DupireLocalVol`, `LocalVolMonteCarlo`, `LocalVolPde`, `HestonCos`,
`HestonCalibrator` and `DividendTree`; `fx.VannaVolgaTouch`; in `alpha`,
`AlphaRiskModels`, `AlphaCombiner`, `BacktestAttribution` and `CapacityCurve`;
`backtest.StrategyRunner` (in the reference tier as `trading.StrategyRunner` since
3.0, because its paper engine is the lane's paper gateway); the `stats` package (`Stats`); and in `core` the exception
types `QuantFinException`, `WireProtocolException`, `MalformedDataException` and
`ConfigurationException`. Types added in 2.2 are stable on the same terms:
`rates.HolidayCalendars`; in `credit`, `IsdaCdsModel.Conventions`,
`MultiCurrencyExposureSimulator` and `CvaApproximator.Bilateral`; in `risk`,
`SaCcrHaircuts`, `FrtbSa`, `FrtbDrc`, `FrtbRrao` and `FrtbNmrf`; in `data`,
`CorporateEvents` and `CorporateEventPositions`; `StrategyRunner.Sizing`; and
`core.MalformedFileException`. Which exception type a site throws is part of its documented
behaviour; the message text is not.

The stable jar is the named module `com.quantfinlib`. It exports exactly the
packages in the table above, plus `internal` to `com.quantfinlib.lowlatency` only
(shared helpers, not API). Adding a package to the exports is a minor change;
removing one is a major change. Up to 2.4 one jar exported both tiers and the tier
was set by this page alone.

Within these packages, stability covers method signatures, record components and
documented behaviour (conventions, units, default parameters). It does not cover
`toString()` output, the exact text of exception messages, or the last digits of
results computed by iterative solvers.

## Reference implementations

These packages show how a low-latency trading lane is put together on the JVM, and
they are tested. They are not a certified trading or connectivity stack: they have not
been through venue certification, have not run against a production venue, and leave
out parts a production system needs (a drop-copy session, regulator-grade order
audit trails, operational tooling).

| Package | What it is |
|---|---|
| `trading` | Risk gate, order gateway, order state machine, quoter, auto hedger, paper venue, sharding; from 2.1 `PositionLedger`, `DropCopyReconciler`, `LimitsConfig` and `LimitsManager`; from 2.2 `FillListener` and `LedgerFillListener`; from 2.4 paper-gateway busts and corrections |
| `marketdata` | Ring buffers, market-data bus, ITCH 5.0 codec, L3 book builder, NBBO; since 3.0 the bus recorders `TickCapture` and `AsyncTickCapture` and the streaming `CrossRateEngine` |
| `orderbook` | Reference matching engine (`OrderBook`), price-ladder engine (`HftOrderBook`) and call auctions (`AuctionBook`, from 2.1) |
| `fix` | FIX 4.4 session (4.2 and 5.0 SP2 from 2.2; the hot-path encoder and views in all three from 2.3), journal store, garbage-free encoders and views; from 2.1 session schedules (`FixSessionSchedule`, `FixSessionOptions`); from 2.2 FIX 4.2 and 5.0 SP2 (`FixVersion`) and repeating groups (`FixDictionary`, `FixGroupDef`, `FixGroup`) |
| `entitlements` | Entitlement service and pre-trade check (2.2) |
| `regreporting` (`reporting` up to 2.4) | MiFIR RTS 22 transaction report builder and identifier checks (2.2); auth.016 output and data-only ESMA rules (2.3); reference-data rules and the head.001/head.003 submission envelope (2.4) |
| `regreporting.registry` | GLEIF, FIRDS and ISO 10383 reference data and a checking sink (2.3) |
| `regreporting.cat` | CAT order events as JSON lines (2.3); indicative, not certified |
| `regreporting.emir` | EMIR Refit core fields, UTI and action/event pairing (2.3); indicative, not checked against Regulation 2022/1860 |
| `sbe` | SBE-style binary codecs (schema 2) and channel adapters |
| `feed` | WebSocket feed, MoldUDP64 receiver |
| `util` | `LatencyRecorder`, `HiccupMonitor`, `IdleStrategy`, `NanoClock`, `ThreadPlacement` |

The versioning policy applies to these packages too, with one exception: a minor
release may change a wire or file format (SBE schema, QFLT tick file, FIX journal)
when it fixes a correctness or safety problem. Such a change bumps the format version,
keeps reading the previous version, and is listed under "Changed" in the changelog.

## How compatibility is checked

Compatibility is checked by japicmp against the released 3.0.0 jars, one per
module (`./mvnw -Papi-compat -DskipTests verify` after
`scripts/fetch-api-baseline.sh`; the CI job is `API compatibility`). Every package of
the stable module fails the build on a binary-incompatible change (`core/pom.xml`,
profile `api-compat`; report `core/target/japicmp/api-stable.html`). There is no
allow-list: 3.x makes no breaking changes, so a break is fixed in code. (3.0 listed
its intended breaks against 2.4.0 one by one, each with its row in
[MIGRATION_3.0.md](MIGRATION_3.0.md); that list was emptied in 3.1.0.) The reference
module is compared with the 3.0.0 low-latency jar and reported in
`lowlatency/target/japicmp/api-lowlatency.html` but does not fail the build.

Stable packages are `@NullMarked` (JSpecify 1.0): unannotated parameters and return
values are non-null, and a member that accepts or returns null says so with
`@Nullable`. The reference packages are not marked. JSpecify is a compile-time, optional dependency, so the library still
has no runtime dependencies; a module that reads the annotations should declare
`requires static org.jspecify;`.

The nightly workflow runs PIT mutation testing (`-Pmutation`) on `pricing`, `risk`,
`rates` and `volatility` and publishes the per-package score and report. The score is
reported, not enforced.

## Examples

`com.quantfinlib.examples` holds demos, hand-written benchmarks and the sample
strategies (`examples.strategies`). It is compiled with the low-latency module and
ships in that artifact's separate `examples` jar, not in either library jar, and
carries no compatibility promise. Benchmark output formats can change in any release.

## Versioning policy from 2.0.0

Version numbers follow [Semantic Versioning 2.0.0](https://semver.org/).

- **Major** (3.0.0): removes or changes a public signature incompatibly, or changes a
  default so that existing code computes different numbers without a code change.
  Each major release has a migration guide like [MIGRATION_2.0.md](MIGRATION_2.0.md).
- **Minor** (2.1.0): adds classes, methods, overloads or optional behaviour. Existing
  code compiles and computes the same results, except where a result was wrong (see
  below).
- **Patch** (2.0.1): fixes bugs and documentation only.

A fix that changes a result which was wrong (it disagreed with the cited reference
or the documented convention) may ship in a minor or patch release. The changelog
lists it under "Fixed" with the old and new values where a test pinned them.

Things that are not part of the public API and can change in any release:

- members that are package-private or private, and classes documented as internal;
- anything in `src/test`, including the JMH benchmarks;
- default thread counts, buffer sizes and timeouts that are documented as tuning
  values;
- the Java version requirement, which only moves in a major release.

3.0 reduced the public surface: the reference tier moved to its own artifact,
helpers that were public only so other packages could reach them moved to the
non-exported `internal` package, and everything deprecated for removal in 2.4.0 was
deleted. [MIGRATION_3.0.md](MIGRATION_3.0.md) lists each item and its replacement.

Deprecated API is marked with `@Deprecated` and stays for at least one minor release
before removal in the next major.

## Release cadence from 3.1.0

The 2.x to 3.0 releases came too fast for anyone to depend on: six releases in about
a day, and one deprecation that lived 45 minutes. What made that hard to depend on
was the breaks and the short deprecation, not the number of releases, so from 3.1.0
the project keeps these rules. Minor and patch releases ship when they are ready;
there is no cap on how often. (3.1.0 also capped minors at one a month and required
three months between a deprecation and its removal; both rules were dropped on
2026-11-02.)

- A deprecation ships in at least one minor release, with its replacement already
  available, before the major that removes it. The changelog names the release that
  removes it.
- A major version has no breaking changes after its .0 release. New behaviour arrives
  as new classes, methods or options; the old path stays and is deprecated.
- Each major has a migration guide listing every removal and its replacement. The
  4.0 module split is planned in [DESIGN_4.0.md](DESIGN_4.0.md).
- Tags are never moved. A release that fails after tagging is fixed by a new patch
  release.
