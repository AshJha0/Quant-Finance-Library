# Design for 4.0: a narrower stable promise

Status: design, written for 3.1.0. Nothing on this page changes 3.x behaviour. The
rules in [STABILITY.md](STABILITY.md) govern when any of it may ship: a deprecation lives at least one minor release and three months, 3.x
has no breaking changes, and 4.0 ships no earlier than three months after 3.2.0.

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

## Target modules

| 4.0 artifact | JPMS module | Tier | Packages (under `com.quantfinlib`) |
|---|---|---|---|
| `quant-finance-library` | `com.quantfinlib` | Stable | `core`, `stats`, `pricing`, `rates`, `credit`, `risk`, `crb`, `volatility`, `hedging`, `fx` (less `AggregatedBook`), `commodities`, `markets`, `indicators`, `optimization`, `alpha`, `backtest`, `backtest.portfolio`, `backtest.tick`, `backtest.validation`, `simulation`, `regulatory`, `internal` (qualified export) |
| `quant-finance-library-io` | `com.quantfinlib.io` | Incubating | `data` (CSV, alignment, corporate actions, `HttpBarFetcher`), `persist`, `report` |
| `quant-finance-library-research` | `com.quantfinlib.research` | Incubating | `screener`, `dsl`, `ml` |
| `quant-finance-library-lowlatency` | `com.quantfinlib.lowlatency` | Reference | its 3.x packages, plus `execution` (`HftSor` and the router family), `microstructure` simulators, `rfq`, `marketdata.tick`, `fx.AggregatedBook` (as `marketdata.AggregatedBook`) |
| examples classifier | none | Examples | `cli`, `backtest.strategies`, `examples` |

Incubating means: semantic versioning applies to the artifact, but a minor release may
change its API with one minor of notice. Only the stable artifact carries the full
promise.

## The moves, one by one

Each row says what moves, why, what a user does, and the earliest dates. "3.2" means
the first release that announces the move in the class javadoc; removal from the old
location is in 4.0, which is at least three months after 3.2.0 by policy.

| Item | From | To (4.0) | Why | User action |
|---|---|---|---|---|
| `cli`, `backtest.strategies` | core | examples classifier | toy code under a stability promise | copy the strategy, or depend on the examples jar |
| `execution.HftSor` and routing | core | lowlatency | hot-lane code | add the lowlatency dependency; package name unchanged |
| `fx.AggregatedBook` | core | lowlatency `marketdata` | hot-lane code | change one import |
| `microstructure` simulators | core | lowlatency | hot-lane code | add the dependency |
| `rfq` | core | lowlatency | execution workflow, not pricing | add the dependency |
| `marketdata.tick` | core | lowlatency | one `marketdata` hierarchy, one module | add the dependency; package name unchanged |
| `data`, `persist`, `report` | core | `-io` | I/O and HTTP are not pricing | add `quant-finance-library-io` |
| `screener`, `dsl`, `ml` | core | `-research` | research tools, incubating | add `quant-finance-library-research` |
| `requires transitive java.net.http` | core | `-io` | one class needs it | none unless you used `HttpBarFetcher` |

### Why most moves cannot be `@Deprecated` in 3.x

A move to another jar keeps the package and class names. JPMS forbids the same package
in two modules, so 3.x cannot ship the new location next to the old one, and there is
no replacement type to point `@Deprecated(forRemoval = true)` at. Marking these
classes deprecated would tell users the API is going away, which is false: it is
changing jar. So in 3.x the moves are announced in three places instead:

1. This page.
2. From 3.2, a "Moves in 4.0" paragraph in each affected package's `package-info.java`.
3. A MIGRATION_4.0.md written with the 4.0 branch, with a dependency snippet per row.

`@Deprecated(since = "3.x", forRemoval = true)` is used only where a replacement type
exists in the same 3.x release. The one 3.1.0 deprecation, `rates.SwapPricer`
(`@Deprecated(since = "3.1.0")`, replaced by the convention-driven leg model; see
MIGRATION_3.1.md), is outside Phase D. No Phase D item has a replacement type in 3.1,
so 3.1.0 adds no Phase D deprecation.

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
2. 3.2.0: "Moves in 4.0" paragraphs in each affected `package-info.java`; the `-io`
   and `-research` modules built in a branch, not released.
3. 4.0.0, no earlier than three months after 3.2.0: modules split, `java.net.http`
   gone from core, MIGRATION_4.0.md, japicmp run with the 4.0 break list.

## Worked example: a pricing user in 4.0

A user who prices swaptions and computes VaR depends today on `quant-finance-library`
and transitively gets `java.net.http`. In 4.0 the same dependency gives the same
classes (`pricing`, `rates`, `risk`) and no HTTP client: their `module-info.java` and
build file do not change. A user who loads bars with `HttpBarFetcher` adds one
dependency, `quant-finance-library-io`, and one `requires com.quantfinlib.io;`; the
import line stays `com.quantfinlib.data.HttpBarFetcher`.
