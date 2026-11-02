# Migrating to 3.0

Status: implemented for 3.0.0 (owner-signed plan, decisions D1 to D9,
2026-11-02). 2.4.0 deprecated every member that 3.0 removes, so compiling
against 2.4.0 with `-Xlint:removal` lists most lines you need to change; the
package moves in sections 7 and 8 show up as import errors instead. 3.0 changes
no result, apart from the rounding-level exposure note in section 6.

## 1. Who needs to read this

If you use only the research, pricing and risk packages, never call
`util.MathUtils` and never name `orderbook.Side`, you probably have nothing to
do: compile against 2.4.0, and no `removal` warnings means no work. If you pass
an order side to `execution`, `backtest`, `microstructure`, `regulatory` or
`risk`, change one import (section 8). If you use the trading lane (`trading`,
`fix`, `marketdata`, `orderbook`, `sbe`, `feed`, `entitlements`, `reporting`,
`util`) you change one dependency line; if you use `reporting` you also change
imports.

## 2. Dependency change: the low-latency artifact

The plain idea: the jar should say what `docs/STABILITY.md` says. In 3.0 the
main artifact carries only the stable tier; the reference tier (the low-latency
trading lane) ships as a second artifact.

| Artifact (3.0) | JPMS module | Contents |
|---|---|---|
| `quant-finance-library` | `com.quantfinlib` | the stable packages (33 exported, `marketdata.tick` included), no `jdk.httpserver`; `internal` exported only to the low-latency module |
| `quant-finance-library-lowlatency` | `com.quantfinlib.lowlatency`, `requires transitive com.quantfinlib` | `trading`, `marketdata`, `orderbook`, `fix`, `sbe`, `feed`, `entitlements`, `regreporting` (+ `cat`, `emir`, `registry`), `util` (the latency and threading helpers); the `examples` classifier jar |

Package names do not change (D2), except `reporting` (section 7) and the few
types in section 8. Before and after, Maven (JitPack names the group
`com.github.<user>.<repo>` for a multi-module build):

```xml
<!-- 2.x: one artifact -->
<dependency>
  <groupId>com.github.AshJha0</groupId>
  <artifactId>quant-finance-library</artifactId>
  <version>2.4.0</version>
</dependency>

<!-- 3.0, research users: the stable artifact -->
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library</artifactId>
  <version>v3.0.0</version>
</dependency>

<!-- 3.0, lane users: the low-latency artifact pulls in the main one -->
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library-lowlatency</artifactId>
  <version>v3.0.0</version>
</dependency>
```

and in `module-info.java`:

```java
// 2.x
requires com.quantfinlib;
// 3.0, lane users
requires com.quantfinlib.lowlatency;   // reads com.quantfinlib transitively
```

The build is a parent POM (`quant-finance-library-parent`) with two modules,
`core/` (artifact `quant-finance-library`) and `lowlatency/` (artifact
`quant-finance-library-lowlatency`). Each has its own `module-info.java`, JaCoCo
gate and japicmp profile; `jitpack.yml` installs both. `jdk.httpserver` (behind
`trading.TradingDashboard`) and `java.xml` (the FIRDS parser) are required only by
the low-latency module; `ModuleDescriptorTest` runs `jdeps` on the stable classes
and fails if `jdk.httpserver` appears. If you used the 2.x `examples` jar, it is
now the `examples` classifier of the low-latency artifact.

## 3. Everything removed, moved or renamed

| 2.x | 3.0 | Deprecated in 2.4.0 | Section |
|---|---|---|---|
| `util.MathUtils` (whole class) | `stats.Stats`, `stats.SpecialFunctions`, `stats.LinearAlgebra`, `stats.Numerics` (added in 2.4.0) | yes | 4 |
| `PortfolioOptimizer(double[], double[][], long)` | `PortfolioOptimizer(double[], double[][])` | yes | 5 |
| `ConstrainedPortfolioOptimizer(double[], double[][], long)` | `ConstrainedPortfolioOptimizer(double[], double[][])` | yes | 5 |
| `VenueQuote(String, double, long, double, long, double, long, boolean)` | canonical 9-argument constructor | yes | 5 |
| `ExposureSimulator.Market`, `Market.ratesOnly`, `ExposureSimulator.simulate` | `MultiCurrencyExposureSimulator.Market` and `simulate` | yes | 6 |
| `MultiCurrencyExposureSimulator.Market.fromLegacy` | build the `Market` directly | yes | 6 |
| `ExposureSimulator.Swap`, `.FxForward`, `.Csa` | `MultiCurrencyExposureSimulator.SwapTerms`, `.FxForwardTerms`, `.Csa`, same components | no (see 6) | 6 |
| `backtest.strategies.EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy`, `BollingerBandsStrategy` | `examples.strategies.*` in the `examples` jar (copy into your code); `cli` keeps private copies | yes | 9 |
| `backtest.strategies.SmaCrossStrategy` | unchanged (D3) | no | |
| package `com.quantfinlib.reporting` (+ `.cat`, `.emir`, `.registry`) | `com.quantfinlib.regreporting` (+ same subpackages), lowlatency artifact | notice in package-info | 7 |
| lane packages listed in section 2 | lowlatency artifact, same names | notice in package-info | 2 |
| `data.TickFileWriter`, `TickFileReader` | `com.quantfinlib.marketdata.tick.TickFileWriter`, `TickFileReader`, main artifact | notice in package-info | 8 |
| `data.TickCapture`, `AsyncTickCapture` | `com.quantfinlib.marketdata.TickCapture`, `AsyncTickCapture`, lowlatency artifact | notice in package-info | 8 |
| `orderbook.Side` | `com.quantfinlib.core.Side`, main artifact, same constants and methods | no | 8 |
| `backtest.StrategyRunner` (+ nested `Engine`, `Sizing`, `Fill`, `Run`) | `com.quantfinlib.trading.StrategyRunner`, lowlatency artifact | no | 8 |
| `fx.CrossRateEngine` | `com.quantfinlib.marketdata.CrossRateEngine`, lowlatency artifact | no | 8 |
| `fx.CrossRateEngine.Op` | `com.quantfinlib.fx.CrossOp`, main artifact | no | 8 |
| `util.LatencyRecorder`, `HiccupMonitor`, `IdleStrategy`, `NanoClock`, `ThreadPlacement` | lowlatency artifact, package `util` unchanged | notice in package-info | 2 |
| `util.ValueArrays`, `util.Immutable` | `com.quantfinlib.internal`, not exported (only to the low-latency module) | notice in package-info | 8 |
| `fix.FileSessionStore` reading the v1 layout (`seqnums.dat`, `messages.dat`) | not read; migrate with 2.4 first | notice in class javadoc | 10 |
| `examples.FixJournalMigration` (2.4 examples jar) | not in the 3.0 examples jar; run the 2.4 one | no | 10 |

The rows marked "no" in the third column are moves that 2.4.0 could not
deprecate in place: the type keeps its name and members but changes package,
because its 2.x package stays in one module while the type depends on the other,
and one package cannot live in two modules. The proposal did not list them; they
came out of the module split and are explained in section 8.

## 4. MathUtils to the stats package, method by method

`MathUtils` goes with no alias (D9). In 2.4.0 every method already delegates to
its replacement, and the library itself calls the replacements, so switching
changes no result. The routines that moved (rather than already delegating) are
pinned bit for bit against the published 2.3.0 jar (independent computation:
`MovedFromMathUtilsTest` holds the IEEE-754 bits that the 2.3.0 `MathUtils`
printed for the same inputs).

| `MathUtils` | Replacement |
|---|---|
| `mean(v)`, `mean(v, from, to)` | `Stats.mean(v)`, `Stats.mean(v, from, to)` |
| `variance(v)` | `Stats.sampleVariance(v)` |
| `stdDev(v)` | `Stats.sampleStdDev(v)` |
| `stdDevP(v, from, to)` | `Stats.populationStdDev(v, from, to)` |
| `stdDevSample(v, from, to)` | `Stats.sampleStdDev(v, from, to)` |
| `percentile(v, p)` | `Stats.quantile(v, p)` |
| `percentileSorted(v, p)` | `Stats.quantileSorted(v, p)` |
| `covariance(a, b)` | `Stats.sampleCovariance(a, b)` |
| `correlation(a, b)` | `Stats.pearsonOrZero(a, b)` (0, not NaN, at zero dispersion; `Stats.pearson` gives NaN) |
| `skewness(v)`, `kurtosis(v)` | `Stats.skewness(v)`, `Stats.kurtosis(v)` |
| `normCdf`, `normInv`, `normPdf`, `logNormPdf` | `SpecialFunctions.` same name |
| `logGamma`, `regularizedIncompleteBeta`, `tCdf` | `SpecialFunctions.` same name |
| `dot`, `matVec`, `quadraticForm`, `cholesky`, `solveLinear`, `inverse` | `LinearAlgebra.` same name |
| `clamp`, `nanArray`, `decayFactor`, `pairSort` | `Numerics.` same name |

Before and after:

```java
// 2.x
import com.quantfinlib.util.MathUtils;
double vol = MathUtils.stdDev(returns);
double z = MathUtils.normInv(0.99);
double[][] l = MathUtils.cholesky(cov);

// 2.4 and 3.0
import com.quantfinlib.stats.LinearAlgebra;
import com.quantfinlib.stats.SpecialFunctions;
import com.quantfinlib.stats.Stats;
double vol = Stats.sampleStdDev(returns);
double z = SpecialFunctions.normInv(0.99);
double[][] l = LinearAlgebra.cholesky(cov);
```

A mechanical rewrite for the same-name groups (check the diff; `mean`,
`variance`, `stdDev`, `percentile`, `covariance`, `correlation` need the
renames in the table):

```sh
sed -i -E 's/MathUtils\.(normCdf|normInv|normPdf|logNormPdf|logGamma|regularizedIncompleteBeta|tCdf)\(/SpecialFunctions.\1(/g;
           s/MathUtils\.(dot|matVec|quadraticForm|cholesky|solveLinear|inverse)\(/LinearAlgebra.\1(/g;
           s/MathUtils\.(clamp|nanArray|decayFactor|pairSort)\(/Numerics.\1(/g' $(git ls-files '*.java')
```

## 5. Constructors removed

The two optimizer constructors took a seed the deterministic solver ignores.

```java
// 2.x
var opt = new PortfolioOptimizer(mu, cov, 42L);
var copt = new ConstrainedPortfolioOptimizer(mu, cov, 42L);
// 2.4 and 3.0: identical results
var opt = new PortfolioOptimizer(mu, cov);
var copt = new ConstrainedPortfolioOptimizer(mu, cov);
```

The 8-argument `VenueQuote` constructor set `lastUpdateNanos` to
`Long.MAX_VALUE`, so the quote could never go stale and a dead feed stayed
invisible to `SmartOrderRouter`. Pass the feed timestamp instead:

```java
// 2.x
new VenueQuote("LIT_A", 99.98, 400, 100.02, 400, 0.0, 0L, false);
// 2.4 and 3.0
new VenueQuote("LIT_A", 99.98, 400, 100.02, 400, 0.0, 0L, false, feedTimestampNanos);
// exact 2.x behaviour, if you really mean "never stale"
new VenueQuote("LIT_A", 99.98, 400, 100.02, 400, 0.0, 0L, false, Long.MAX_VALUE);
```

## 6. Exposure simulator collapse (D4)

`ExposureSimulator` folds into `MultiCurrencyExposureSimulator`. A
single-currency market is the multi-currency market with zero or one pair and a
deterministic foreign rate; this is exactly what `Market.fromLegacy` builds
today.

```java
// 2.x
var mkt = ExposureSimulator.Market.ratesOnly(usdCurve, 0.03, 0.01);
ExposureProfile p = ExposureSimulator.simulate(mkt, List.of(swap), List.of(), csa, times, 20_000, 7L);

// 2.4 and 3.0
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Curve;
import com.quantfinlib.credit.MultiCurrencyExposureSimulator.Domestic;
var mkt = new MultiCurrencyExposureSimulator.Market(
        new Domestic(Curve.of(usdCurve), 0.03, 0.01), List.of(), new double[][]{{1}});
// swap is now a MultiCurrencyExposureSimulator.SwapTerms, csa a MultiCurrencyExposureSimulator.Csa
ExposureProfile p = MultiCurrencyExposureSimulator.simulate(mkt,
        List.of(new MultiCurrencyExposureSimulator.Swap(swap, null)), List.of(), csa, times, 20_000, 7L);
```

With FX (one pair, foreign rate vol 0, FX-rate correlation `rho`):

```java
var mkt = new MultiCurrencyExposureSimulator.Market(
        new Domestic(Curve.of(usd), a, sigma),
        List.of(new MultiCurrencyExposureSimulator.Foreign(Curve.of(eur), a, 0, spot, fxVol)),
        new double[][]{{1, 0, rho}, {0, 1, 0}, {rho, 0, 1}});
// forwards: new MultiCurrencyExposureSimulator.FxForward(0, fwdTerms)
```

What changes in the numbers: the multi-currency engine reproduces the 2.4
single-currency results to rounding, path for path, from the same seed, not
bit for bit. `ExposureSimulatorFoldTest` pins it: the expected values are the
EE, ENE, 95 % PFE and wrong-way CVA printed by the 2.4.0 `ExposureSimulator`
(tag `r24-base`) for a one-pair market with a swap, an FX forward and a CSA
(2,000 paths, seed 11) and for a rates-only market (1,000 paths, seed 3); the
3.0 engine must match each to a relative 1e-12. The largest relative difference
seen when the values were taken was 6.7e-14, on a PFE; expected exposures agreed
to about 5e-15. Evidence class: own test against the previous release's output.

The trade-term records `ExposureSimulator.Swap`, `FxForward` and `Csa` were not
deprecated in 2.x, because the multi-currency API takes them as parameters and
a replacement would have needed a parallel set of overloads. In 3.0 they are
nested records of `MultiCurrencyExposureSimulator` with the same components and
the same validation, so the change is a rename:

```java
// 2.x
ExposureSimulator.Swap swap = new ExposureSimulator.Swap(1e7, 0.035, true, 0, 5, 2);
// 3.0
MultiCurrencyExposureSimulator.SwapTerms swap = new MultiCurrencyExposureSimulator.SwapTerms(1e7, 0.035, true, 0, 5, 2);
```

The names `SwapTerms` and `FxForwardTerms` avoid a clash with the existing
nested `Swap` and `FxForward` wrappers (a swap with its projection curve, a
forward with its pair index); `Csa` keeps its name.

## 7. Renamed package: reporting to regreporting (D7)

`report` (PDF, HTML, XLSX exporters) and `reporting` (MiFIR RTS 22, CAT, EMIR)
were one letter apart with unrelated meanings. In 3.0 `reporting` becomes
`regreporting` and moves to the low-latency artifact with the rest of the
reference tier. Types and members are unchanged.

```java
// 2.x
import com.quantfinlib.reporting.TransactionReport;
import com.quantfinlib.reporting.emir.EmirActionType;
// 3.0
import com.quantfinlib.regreporting.TransactionReport;
import com.quantfinlib.regreporting.emir.EmirActionType;
```

```sh
sed -i 's/com\.quantfinlib\.reporting\b/com.quantfinlib.regreporting/g' $(git ls-files '*.java')
```

EMIR and CAT validation changed in 3.0, because it was checked against the
published texts for the first time (ESMA74-362-2683 for EMIR Refit, CAT IM
Technical Specifications v4.1.0 r14 for CAT). EMIR messages now start with the
ESMA error code, and some reports that passed in 2.4 now fail:

| 2.4 behaviour | 3.0 | Why |
|---|---|---|
| EMIR `direction` accepts MAKE/TAKE | BYER/SLLR only; use `legDirections("MAKE", "TAKE")` | EMIR-VR-1017-02, -1018-02 |
| EMIR report without 1.2 or 2.153 passes | fails; `counterparties` sets 1.2 and `terms` sets 2.153 | columns L-S 'M' |
| EMIR CORR with an event type passes | fails; leave event type blank | EMIR-VR-2152-05 |
| EMIR TERM with only an expiration date passes | needs `earlyTerminationDate` equal to `eventDate` | row 70, EMIR-VR-2153-07 |
| EMIR event type PTNG accepted | rejected | not in EMIR-VR-2152-01 |
| EMIR natural-person client code any text | up to 72 alphanumerics, starting with 1.4 for NEWT/POSC | EMIR-VR-1009-06/07 |
| CAT COR needs errorROEID | only RPR does; NEW must leave it blank | Table 16 seq 2, section 7.6 |
| CAT MEOT/MEOF need orderID, orderKeyDate | use `tradeKey`/`fillKey` | Tables 48, 52 |
| CAT quantity must be positive | not negative, no trailing decimal zeros | Table 3 |

`EmirRefitReport.rules()` and `CatEvent.rules()` list each check with its source.

## 8. Types that change package

The plain idea: a package lives in exactly one module. Where a 2.x package
stays in the main module but one of its types depends on the trading lane (or a
lane package holds a type the stable packages need), that type moves to a
package on the right side of the line. Members do not change.

| 2.x | 3.0 | Artifact | Why |
|---|---|---|---|
| `data.TickFileWriter`, `data.TickFileReader` | `marketdata.tick.TickFileWriter`, `TickFileReader` | main | the stable tick backtester (`backtest.tick.TickBacktester`) reads this format, so it cannot leave the main artifact; it leaves `data` to sit with the other tick types |
| `data.TickCapture`, `data.AsyncTickCapture` | `marketdata.TickCapture`, `AsyncTickCapture` | lowlatency | they record the market-data bus |
| `orderbook.Side` | `core.Side` | main | `execution`, `backtest`, `microstructure`, `regulatory` and `risk` take a side in their signatures |
| `backtest.StrategyRunner` | `trading.StrategyRunner` | lowlatency | its `PAPER` engine is the lane's `PaperTradingGateway` |
| `fx.CrossRateEngine` | `marketdata.CrossRateEngine` | lowlatency | it runs on the market-data bus |
| `fx.CrossRateEngine.Op` | `fx.CrossOp` | main | `fx.SyntheticCross` takes it |
| `util.ValueArrays`, `util.Immutable` | `internal.ValueArrays`, `internal.Immutable` | main, not exported | used by `core`, `fx`, `rates`, `risk`, `ml`, `optimization`; implementation helpers, not API |

The tick-file reader and writer were planned for `marketdata.tick` in the
low-latency artifact. They stay in the main artifact because the stable tick
backtester depends on them; `marketdata.tick` is a package of the main module
even though `marketdata` is a package of the low-latency one (JPMS treats the two
names as unrelated). The QFLT format keeps its reference-tier rule: a minor
release may bump the format version to fix a correctness problem, and keeps
reading the old version.

```java
// 2.x
import com.quantfinlib.data.TickFileWriter;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.backtest.StrategyRunner;
// 3.0
import com.quantfinlib.marketdata.tick.TickFileWriter;
import com.quantfinlib.core.Side;
import com.quantfinlib.trading.StrategyRunner;     // lowlatency artifact
```

```sh
sed -i -E 's/com\.quantfinlib\.orderbook\.Side\b/com.quantfinlib.core.Side/g;
           s/com\.quantfinlib\.data\.(TickFileWriter|TickFileReader)\b/com.quantfinlib.marketdata.tick.\1/g;
           s/com\.quantfinlib\.data\.(TickCapture|AsyncTickCapture)\b/com.quantfinlib.marketdata.\1/g;
           s/com\.quantfinlib\.backtest\.StrategyRunner\b/com.quantfinlib.trading.StrategyRunner/g;
           s/com\.quantfinlib\.fx\.CrossRateEngine\.Op\b/com.quantfinlib.fx.CrossOp/g;
           s/com\.quantfinlib\.fx\.CrossRateEngine\b/com.quantfinlib.marketdata.CrossRateEngine/g' $(git ls-files '*.java')
```

Code in the `orderbook` package itself used `Side` without an import and now
needs one; code that wrote `CrossRateEngine.Op.MULTIPLY` becomes
`CrossOp.MULTIPLY`. If you used `ValueArrays`, copy the few lines you need.

## 9. Sample strategies (D3)

`EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy` and `BollingerBandsStrategy`
are samples, not library function. 2.4.0 adds identical copies in
`com.quantfinlib.examples.strategies` (the `examples` jar) and deprecates the
library ones. `SmaCrossStrategy` stays.

```java
// 2.x
import com.quantfinlib.backtest.strategies.RsiStrategy;
// 2.4 and 3.0: copy examples/strategies/RsiStrategy.java into your code, then
import com.mycompany.strategies.RsiStrategy;
```

The CLI (`cli.Main --strategy ema|rsi|macd|bollinger`) uses the four samples.
In 3.0 the CLI keeps those names with package-private copies inside `cli`, so
command lines keep working; only the public types went.

## 10. Format note: FIX session store v1 layout (D8)

A 2.x `FileSessionStore` opened on a directory written by the v1 store
(`seqnums.dat`, `messages.dat`) imports it and renames the old files with a
`.migrated` suffix. 3.0 drops that import: it starts a fresh journal and leaves
the v1 files untouched, so sequence numbers would restart. Migrate before you
upgrade. Opening the store once with any 2.x jar does it; the 2.4 examples jar
has a command that does it for a list of directories:

```sh
java -cp quant-finance-library-2.4.0.jar:quant-finance-library-2.4.0-examples.jar \
     com.quantfinlib.examples.FixJournalMigration /var/fix/session1 /var/fix/session2
```

(use `;` as the class-path separator on Windows). Running it twice is harmless.
The command is in the 2.4 examples jar only: it relies on the 2.x import, which
3.0 no longer has.

## 11. What does not change

Numbers (apart from the rounding-level exposure note in section 6),
conventions, and every package name other than `reporting` and the types in
section 8. The stable package list in `docs/STABILITY.md` is the 2.x list minus
`util` and `backtest.StrategyRunner`, plus `marketdata.tick`.

Additions in the stable `risk` package need no migration: new methods on
`FrtbDrcSecuritisation` (`kAPartlyUnknownW`, `secSaPermittedWithUnknownW`,
`juniorUnratedFloor`, `decomposeIndex`, `offsetByReplication`,
`checkSingleNameDecomposition`) and on `SaCcrHaircuts`
(`fundHaircut10DayMandate`, `fundHaircut10DayLookThrough`). If you applied
CRE41.9, CRE41.15 or the fund-haircut rule yourself before calling the 2.4
methods, you can drop that code; results for the same inputs are the same.

## 12. How the breaks are checked

`./mvnw -Papi-compat -DskipTests verify` compares the stable module with the
2.4.0 jar and fails on any binary-incompatible change that is not listed. The
list is the `<excludes>` block of `core/pom.xml`: one entry per removed or moved
type and per method whose signature changed because `Side` moved, each commented
with the section of this file that explains it. The low-latency module is
compared and reported only (`lowlatency/target/japicmp`).
