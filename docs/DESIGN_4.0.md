# Design for 4.0: a narrower stable promise

Status: implemented in 4.0.0, as revised in 3.2.0 and accepted by the owner on 2026-11-02. Written for 3.1.0. The
rules in [STABILITY.md](STABILITY.md) govern when any of it may ship: each removal is deprecated, with its replacement, in at
least one 3.x minor (3.2.0) before 4.0 removes it, and 3.x has no breaking changes.
Revised in 3.2.0: the move list is smaller than the 3.1 draft, for the reasons in
"What changed from the 3.1 design".

## Why

The plain idea: the stable jar promises more than one maintainer can verify. In 3.0 it
exports 33 packages under the stability promise, including a DSL, a screener, a CLI,
persistence, reports and an HTTP client. Every pricing user also gets
`java.net.http`, because core declares `requires transitive java.net.http` for one
class, `data.HttpBarFetcher`. And two package names are split across the tiers in a
way that fits neither: `marketdata.tick` is in core while `marketdata` is in the
low-latency module.

Stated formally, 4.0 makes three changes:

1. The stable tier is pricing, rates, credit, risk, stats, volatility and hedging,
   plus the shared types they need (`core`, `fx`, `commodities`, `markets`,
   `indicators`, `optimization`).
2. Each package hierarchy lives in exactly one module.
3. Core has no `java.net.http` and no `jdk.httpserver` dependency.

Point 1 was narrowed less than planned; see the revised tables below.

## Target modules

Revised in 3.2.0 (see "What changed from the 3.1 design" below).

| 4.0 artifact | JPMS module | Tier | Packages (under `com.quantfinlib`) |
|---|---|---|---|
| `quant-finance-library` | `com.quantfinlib` | Stable | `core`, `stats`, `pricing`, `rates`, `credit`, `risk`, `crb`, `volatility`, `hedging`, `fx` (less `AggregatedBook`), `commodities`, `markets`, `indicators`, `optimization`, `alpha`, `backtest`, `backtest.portfolio`, `backtest.validation`, `simulation`, `regulatory`, `data` (less its three I/O classes), `execution`, `microstructure`, `persist`, `screener`, `ml`, `internal` (qualified export) |
| `quant-finance-library-io` | `com.quantfinlib.io` | Incubating | `io` (`CsvBarLoader`, `HttpBarFetcher`, `UniverseCsvLoader`), `report` |
| `quant-finance-library-research` | `com.quantfinlib.research` | Incubating | `dsl` |
| `quant-finance-library-lowlatency` | `com.quantfinlib.lowlatency` | Reference | its 3.x packages, plus `rfq`, `marketdata.tick`, `backtest.tick`, `marketdata.AggregatedBook` (new in 3.2) |
| `examples` classifier of the lowlatency artifact | none | Examples | `cli`, `backtest.strategies`, `examples` |

Incubating means: semantic versioning applies to the artifact, but a minor release may
change its API with one minor of notice. Only the stable artifact carries the full
promise.

The graph is acyclic: lowlatency requires core transitively and io and research
statically (only the examples jar uses them); io and research require core. Nothing
requires lowlatency.

## The moves, one by one

Each row says what moves, why and what a user does. Under the release policy of
2026-11-02, 3.2.0 announces each move (and deprecates where a replacement type exists),
and 4.0.0 follows 3.2.0 directly and makes the move.

| Item | From | To (4.0) | Why | User action | 3.2.0 |
|---|---|---|---|---|---|
| `cli`, `backtest.strategies` | core | examples classifier | toy code under a stability promise | copy the strategy, or depend on the examples jar | announced |
| `fx.AggregatedBook` | core | lowlatency `marketdata.AggregatedBook` | hot-lane code | change one import | replacement added, old class deprecated for removal |
| `rfq` | core | lowlatency | execution workflow, not pricing | add the lowlatency dependency; package name unchanged | announced |
| `marketdata.tick` | core | lowlatency | one `marketdata` hierarchy, one module | add the dependency; package name unchanged | announced |
| `backtest.tick` | core | lowlatency | replays `marketdata.tick` files; it cannot stay in core once they leave | add the dependency; package name unchanged | announced |
| `data.CsvBarLoader`, `data.HttpBarFetcher`, `data.UniverseCsvLoader` | core `data` | `-io`, package `io` | I/O and HTTP are not pricing | change the import to `com.quantfinlib.io`, add `quant-finance-library-io` | `io` package added in core, old classes deprecated for removal and delegating |
| `report` | core | `-io` | file output, not pricing | add `quant-finance-library-io` | announced |
| `dsl` | core | `-research` | research tool, incubating | add `quant-finance-library-research` | announced |
| `requires transitive java.net.http` | core | `-io` | one class needs it | none unless you used `HttpBarFetcher` | - |
| `rates.SwapPricer` | core | removed | replaced by `VanillaSwap` and `LegConvention` in 3.1 | see MIGRATION_3.1.md | deprecated since 3.1.0 |

### What changed from the 3.1 design

The 3.1 table also moved `execution`, the `microstructure` simulators, `persist`,
`screener`, `ml` and the whole of `data`. A package-level dependency scan of 3.1.0
(`grep` of imports across `core/src/main`) showed that stable packages use each of
them:

| Package | Used by stable code in |
|---|---|
| `microstructure` (`Execution`, `MarketImpactModel`, `TickSizeSchedule`, ...) | `backtest` (9 files), `backtest.tick`, `alpha`, `execution` |
| `execution` (`SmartOrderRouter`, `IcebergOrder`, ...) | `backtest` (`ExecutionAlgoBacktester`, `IcebergExecution`, `SorExecution`) |
| `persist.Checkpoint` | `crb`, `fx`, `execution`, `microstructure`, `rfq` |
| `screener` (`Fundamentals`, ...) | `alpha` |
| `ml.IntradayLiquidityForecaster` | `execution.VwapScheduler` |
| `data.PointInTimeUniverse`, `CorporateActions`, ... | `alpha`, `backtest.portfolio`, `screener` |

Moving any of them out would make core require the module it moved to while that
module requires core: a cycle, which JPMS rejects. Splitting a package between jars is
also forbidden. So these stay in the stable jar; for `data`, only the three I/O
classes leave, under a new package name with a deprecation path. The stable tier is
therefore wider than the 3.1 page hoped. The alternative, moving `backtest` and
`alpha` out of the stable tier too, would break far more users for little gain.

**Owner decision, 2026-11-02.** The revised design is accepted as it stands: the packages
that stable code depends on (`execution`, `microstructure`, `persist`, `screener`, `ml`
and most of `data`) stay in the stable jar, because moving them would create module
cycles, and `-research` holds only `dsl`. A later major may move more once the stable
code no longer depends on it; nothing in 4.x will.

### Deprecation versus announcement

A move to another jar keeps the package and class names. JPMS forbids the same package
in two modules, so 3.x cannot ship the new location next to the old one, and there is
no replacement type to point `@Deprecated(forRemoval = true)` at. Marking such a class
deprecated would tell users the API is going away, which is false: it is changing jar.
These moves are announced in three places: this page, a "Moves in 4.0" paragraph in each
affected `package-info.java` (from 3.2.0), and docs/MIGRATION_4.0.md.

`@Deprecated(since = "3.2.0", forRemoval = true)` is used only where a replacement type
exists in 3.2.0: `fx.AggregatedBook` (replacement `marketdata.AggregatedBook` in
lowlatency) and the three `data` I/O classes (replacements in `io`). With
`rates.SwapPricer` (deprecated since 3.1.0), these are all the 4.0 removals.

## The large classes

`FixSession` (1,530 lines in 3.0), `Indicators` (1,317) and
`MultiCurrencyExposureSimulator` (1,137) are split in 3.1 internally. The public
classes stay as facades with identical signatures and behaviour, which japicmp against
3.0.0 and the unchanged test suite confirm:

| Facade | 3.1 package-private parts | Facade lines, 3.0 to 3.1 |
|---|---|---|
| `indicators.Indicators` | `MovingAverages`, `Oscillators`, `RangeIndicators`, `VolumeIndicators`, `TrendIndicators`, `RollingWindows`, `IndicatorSupport` | 1,317 to about 890 (the nested result types must stay nested to keep their binary names) |
| `credit.MultiCurrencyExposureSimulator` | `MultiCurrencyExposureEngine` (Hull-White state, step covariance, path valuation, collateral lag) | 1,137 to about 610 |
| `fix.FixSession` | `FixSessionMessages` (Logon, Reject and order-entry builders), `FixSessionParsing` (sequence numbers, UTCTimestamp) | 1,530 to about 1,410 |

`FixSession` stays the largest: its remaining code is one state machine (logon,
sequence recovery, resend, timers) guarded by one lock, and cutting it further in 3.x
would move lock-protected state across classes for no user benefit. In 4.0 the planned
shape is a `FixSessionState` object owned by the session, with the logon and resend
handlers as separate classes that take it as an argument. That change is internal too
and may land in a 3.x minor if it passes the conformance suite unchanged.

## Allocation promises

From 3.1 every "allocation-free" claim in the javadoc has a test
(`AllocationClaimsTest` in core, `LaneAllocationClaimsTest` in lowlatency) that counts
heap bytes with `ThreadMXBean.getCurrentThreadAllocatedBytes` after warm-up. A claim
without a test is removed or softened. 3.1 softened two: `feed.BinanceTradeParser`
allocates strings per message, and the Hull-White functions of `rates.ShortRateModels`
box tenors through the `TreeMap`-backed `YieldCurve`. In 4.0 the rule becomes part of the stable
promise: a stable class documented as allocation-free stays allocation-free, and a
change that breaks it is a breaking change.

## Order of work

1. 3.1.0: this page; internal splits; trade-buffer presize; allocation tests.
2. 3.2.0: the revised tables above; "Moves in 4.0" paragraphs in each affected
   `package-info.java`; the `io` package and `marketdata.AggregatedBook` with
   deprecations of the types they replace; MIGRATION_4.0.md.
3. 4.0.0, directly after 3.2.0: modules split, `java.net.http` gone from core, the
   deprecated types removed, japicmp run against 3.2.0 with the 4.0 break list.

## Worked example: a pricing user in 4.0

A user who prices swaptions and computes VaR depends today on `quant-finance-library`
and transitively gets `java.net.http`. In 4.0 the same dependency gives the same
classes (`pricing`, `rates`, `risk`) and no HTTP client: their `module-info.java` and
build file do not change. A user who loads bars with `HttpBarFetcher` adds one
dependency, `quant-finance-library-io`, and one `requires com.quantfinlib.io;`; the
import line changes once, from `com.quantfinlib.data.HttpBarFetcher` to
`com.quantfinlib.io.HttpBarFetcher` (a change they can make on 3.2.0 already).
