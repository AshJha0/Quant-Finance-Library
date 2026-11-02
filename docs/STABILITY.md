# API stability

The library has three kinds of code, and they come with different promises. This page
says which package is which and how versions are numbered from 2.0.0 onward.

## Stable research and risk APIs

These packages are the library's main purpose. Their public classes and methods are
covered by the versioning policy below, and numerical results change only to fix a
documented error.

| Area | Packages (under `com.quantfinlib`) |
|---|---|
| Data and series | `core` (including the exception types), `data` (CSV loading, alignment, corporate actions, point-in-time universes), `stats`, `util.MathUtils` |
| Pricing and instruments | `pricing`, `rates`, `credit`, `fx` (conventions, curves, instruments, volatility surface), `commodities`, `markets` |
| Risk | `risk`, `crb`, `volatility`, `hedging` |
| Portfolio and research | `optimization`, `alpha`, `backtest` and its subpackages, `indicators`, `simulation`, `ml`, `screener`, `dsl` |
| Execution research | `microstructure`, `execution`, `regulatory`, `rfq` |
| Output and tools | `report`, `cli` (commands and options), `persist` |

Types added in 2.1 in these packages are stable on the same terms: in `rates`,
`DiscountCurve`, `MultiCurveBootstrap`, `MultiCurveRisk`, `CurvePair` and the curve
instruments (`CurveInstrument`, `Deposit`, `Fra`, `RateFuture`, `OisSwap`,
`InterestRateSwap`); in `credit`, `IsdaCdsModel`, `IsdaCurve`, `ExposureSimulator`
and `ExposureProfile`; in `risk`, `SaCcr`, `SaCcrTrade` and `FactorRiskModel`; in
`pricing`, `SviSlice`, `SsviSurface`, `SviSurface`, `TotalVarianceSurface`,
`DupireLocalVol`, `LocalVolMonteCarlo`, `LocalVolPde`, `HestonCos`,
`HestonCalibrator` and `DividendTree`; `fx.VannaVolgaTouch`; in `alpha`,
`AlphaRiskModels`, `AlphaCombiner`, `BacktestAttribution` and `CapacityCurve`; and
`backtest.StrategyRunner`; the `stats` package (`Stats`); and in `core` the exception
types `QuantFinException`, `WireProtocolException`, `MalformedDataException` and
`ConfigurationException`. Types added in 2.2 are stable on the same terms:
`rates.HolidayCalendars`; in `credit`, `IsdaCdsModel.Conventions`,
`MultiCurrencyExposureSimulator` and `CvaApproximator.Bilateral`; in `risk`,
`SaCcrHaircuts`, `FrtbSa`, `FrtbDrc`, `FrtbRrao` and `FrtbNmrf`; in `data`,
`CorporateEvents` and `CorporateEventPositions`; `StrategyRunner.Sizing`; and
`core.MalformedFileException`. Which exception type a site throws is part of its documented
behaviour; the message text is not.

The jar is the named module `com.quantfinlib`, and it exports the reference packages
as well as the stable ones. The module descriptor does not change the tiers on this
page. Adding a package to the exports is a minor change; removing one is a major
change.

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
| `trading` | Risk gate, order gateway, order state machine, quoter, auto hedger, paper venue, sharding; from 2.1 `PositionLedger`, `DropCopyReconciler`, `LimitsConfig` and `LimitsManager`; from 2.2 `FillListener` and `LedgerFillListener` |
| `marketdata` | Ring buffers, market-data bus, ITCH 5.0 codec, L3 book builder, NBBO |
| `orderbook` | Reference matching engine (`OrderBook`), price-ladder engine (`HftOrderBook`) and call auctions (`AuctionBook`, from 2.1) |
| `fix` | FIX 4.4 session, journal store, garbage-free encoders and views; from 2.1 session schedules (`FixSessionSchedule`, `FixSessionOptions`); from 2.2 FIX 4.2 and 5.0 SP2 (`FixVersion`) and repeating groups (`FixDictionary`, `FixGroupDef`, `FixGroup`) |
| `risk.simm` | ISDA SIMM calculator (2.2); structure checked against the public methodology, no official calibration shipped, so results depend on the calibration file you supply |
| `entitlements` | Entitlement service and pre-trade check (2.2) |
| `reporting` | MiFIR RTS 22 transaction report builder and identifier checks (2.2); not validated against the ESMA schema |
| `sbe` | SBE-style binary codecs (schema 2) and channel adapters |
| `feed` | WebSocket feed, MoldUDP64 receiver |
| `data` tick files | `TickFileWriter`, `TickFileReader`, `TickCapture`, `AsyncTickCapture` |
| `util` measurement | `LatencyRecorder`, `HiccupMonitor` |

The versioning policy applies to these packages too, with one exception: a minor
release may change a wire or file format (SBE schema, QFLT tick file, FIX journal)
when it fixes a correctness or safety problem. Such a change bumps the format version,
keeps reading the previous version, and is listed under "Changed" in the changelog.

## How compatibility is checked

Compatibility is checked by japicmp against the released 2.1.0 jar
(`./mvnw -Papi-compat -DskipTests verify` after `scripts/fetch-api-baseline.sh`; the
CI job is `API compatibility`). The stable packages fail the build on any
binary-incompatible change. The reference packages are compared and reported in
`target/japicmp/api-all.html` but do not fail it. The package list lives in the
`api-compat` profile of `pom.xml` and matches the stable table above, with the tick
file classes of `data` excluded.

Stable packages are `@NullMarked` (JSpecify 1.0): unannotated parameters and return
values are non-null, and a member that accepts or returns null says so with
`@Nullable`. `util` (where only `MathUtils` is stable) and the reference packages are
not marked. JSpecify is a compile-time, optional dependency, so the library still
has no runtime dependencies; a module that reads the annotations should declare
`requires static org.jspecify;`.

The nightly workflow runs PIT mutation testing (`-Pmutation`) on `pricing`, `risk`,
`rates` and `volatility` and publishes the per-package score and report. The score is
reported, not enforced.

## Examples

`com.quantfinlib.examples` holds demos and hand-written benchmarks. It ships in the
separate `examples` jar, not in the library jar, and carries no compatibility
promise. Benchmark output formats can change in any release.

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

The public surface is larger than it needs to be: some types are public only so
that other packages can reach them. Reducing it is a breaking change and is planned
for 3.0; 2.x keeps every public type of 2.0.

Deprecated API is marked with `@Deprecated` and stays for at least one minor release
before removal in the next major.
