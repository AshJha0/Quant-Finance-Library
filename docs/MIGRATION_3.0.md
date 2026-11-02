# Migrating to 3.0

Status: draft, published with 2.4.0. 3.0 is not released yet. Everything below
is the owner-signed plan (docs review folder, `v3.0_API_Proposal.md`, decisions
D1 to D9, 2026-11-02). 2.4.0 already deprecates every member that 3.0 removes,
so compiling against 2.4.0 with `-Xlint:removal` lists every line you need to
change. Nothing in 2.4.0 changes a result.

## 1. Who needs to read this

If you use only the research, pricing and risk packages and never call
`util.MathUtils`, you probably have nothing to do. Compile against 2.4.0: no
`removal` warnings means no work. If you use the trading lane (`trading`,
`fix`, `marketdata`, `orderbook`, `sbe`, `feed`, `entitlements`, `reporting`)
you change one dependency line; if you use `reporting` you also change imports.

## 2. Dependency change: the low-latency artifact

The plain idea: the jar should say what `docs/STABILITY.md` says. In 3.0 the
main artifact carries only the stable tier; the reference tier (the low-latency
trading lane) ships as a second artifact.

| Artifact (3.0) | JPMS module | Contents |
|---|---|---|
| `quant-finance-library` | `com.quantfinlib` | stable packages (31), no `jdk.httpserver` |
| `quant-finance-library-lowlatency` | `com.quantfinlib.lowlatency`, `requires transitive com.quantfinlib` | `trading`, `marketdata`, `orderbook`, `fix`, `sbe`, `feed`, `entitlements`, `regreporting` (+ `cat`, `emir`, `registry`), the latency helpers of `util`, the tick-file types |

Package names do not change (D2), except `reporting` (section 7) and the tick
files (section 8). Before and after, Maven:

```xml
<!-- 2.x: one artifact -->
<dependency>
  <groupId>com.github.AshJha0</groupId>
  <artifactId>quant-finance-library</artifactId>
  <version>2.4.0</version>
</dependency>

<!-- 3.0, lane users: the low-latency artifact pulls in the main one -->
<dependency>
  <groupId>com.github.AshJha0</groupId>
  <artifactId>quant-finance-library-lowlatency</artifactId>
  <version>3.0.0</version>
</dependency>
```

and in `module-info.java`:

```java
// 2.x
requires com.quantfinlib;
// 3.0, lane users
requires com.quantfinlib.lowlatency;   // reads com.quantfinlib transitively
```

The groupId follows whatever the README install snippet uses at release time.

**Build status in 2.4.0.** 2.4.0 does not publish the second artifact. A
classifier jar of the lane packages was considered and rejected: the same
classes would sit in two jars, which is a split-package error on the module
path, and a classifier cannot carry its own `module-info`. Producing a real
`quant-finance-library-lowlatency` needs the Maven build split into two modules
(a parent POM, two `module-info.java` files, the japicmp, SpotBugs, JaCoCo and
JitPack configuration per module). That is phase 2 of the plan, done once on
`release/3.0`:

1. Parent POM `quant-finance-library-parent` (packaging `pom`) holding today's
   plugin management and profiles; modules `core` (artifactId
   `quant-finance-library`) and `lowlatency` (artifactId
   `quant-finance-library-lowlatency`).
2. `git mv` the lane packages and their tests into `lowlatency/src`. Tests in
   the stable module that touch lane types move with them or get a test-scope
   dependency (the proposal lists this as a risk).
3. `jdk.httpserver` moves to the lowlatency `module-info`; check with
   `jdeps --check com.quantfinlib` that the stable module no longer needs it.
4. `jitpack.yml` builds both modules; verify a JitPack build of a test tag
   before the release tag (the proposal's main risk).
5. japicmp per module against 2.4.0, with every intended break listed by an
   `<exclude>` that points to its row in this file.

## 3. Everything removed, moved or renamed

| 2.x | 3.0 | Deprecated in 2.4.0 | Section |
|---|---|---|---|
| `util.MathUtils` (whole class) | `stats.Stats`, `stats.SpecialFunctions`, `stats.LinearAlgebra`, `stats.Numerics` (added in 2.4.0) | yes | 4 |
| `PortfolioOptimizer(double[], double[][], long)` | `PortfolioOptimizer(double[], double[][])` | yes | 5 |
| `ConstrainedPortfolioOptimizer(double[], double[][], long)` | `ConstrainedPortfolioOptimizer(double[], double[][])` | yes | 5 |
| `VenueQuote(String, double, long, double, long, double, long, boolean)` | canonical 9-argument constructor | yes | 5 |
| `ExposureSimulator.Market`, `Market.ratesOnly`, `ExposureSimulator.simulate` | `MultiCurrencyExposureSimulator.Market` and `simulate` | yes | 6 |
| `MultiCurrencyExposureSimulator.Market.fromLegacy` | build the `Market` directly | yes | 6 |
| `ExposureSimulator.Swap`, `.FxForward`, `.Csa` | nested records of `MultiCurrencyExposureSimulator`, same components | no (see 6) | 6 |
| `backtest.strategies.EmaCrossStrategy`, `RsiStrategy`, `MacdStrategy`, `BollingerBandsStrategy` | `examples.strategies.*` in the `examples` jar (copy into your code) | yes | 9 |
| `backtest.strategies.SmaCrossStrategy` | unchanged (D3) | no | |
| package `com.quantfinlib.reporting` (+ `.cat`, `.emir`, `.registry`) | `com.quantfinlib.regreporting` (+ same subpackages), lowlatency artifact | notice in package-info | 7 |
| lane packages listed in section 2 | lowlatency artifact, same names | notice in package-info | 2 |
| `data.TickFileWriter`, `TickFileReader`, `TickCapture`, `AsyncTickCapture` | lowlatency artifact, planned package `com.quantfinlib.marketdata.tick` | notice in package-info | 8 |
| `util.LatencyRecorder`, `HiccupMonitor`, `IdleStrategy`, `NanoClock`, `ThreadPlacement` | lowlatency artifact, package `util` unchanged | notice in package-info | 2 |
| `util.ValueArrays`, `util.Immutable` | non-exported internal package of the main module | notice in package-info | 8 |
| `fix.FileSessionStore` reading the v1 layout (`seqnums.dat`, `messages.dat`) | not read; migrate with 2.4 first | notice in class javadoc | 10 |

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

What changes in the numbers: the multi-currency engine reproduces the 2.1
single-currency results to rounding, path for path, from the same seed, not
bit for bit (own test: `MultiCurrencyExposureSimulatorTest`, which compares the
two engines). Expect differences in the last digits only.

The trade-term records `ExposureSimulator.Swap`, `FxForward` and `Csa` are not
deprecated in 2.x, because the multi-currency API takes them as parameters and
a replacement would need a parallel set of overloads. In 3.0 they become nested
records of `MultiCurrencyExposureSimulator` with the same components, so the
change is an import:

```java
// 2.x
ExposureSimulator.Swap swap = new ExposureSimulator.Swap(1e7, 0.035, true, 0, 5, 2);
// 3.0
MultiCurrencyExposureSimulator.SwapTerms swap = new MultiCurrencyExposureSimulator.SwapTerms(1e7, 0.035, true, 0, 5, 2);
```

The 3.0 names (`SwapTerms`, `FxForwardTerms`, `Csa`) avoid a clash with the
existing nested `Swap` and `FxForward` wrappers; they are the plan, to be
confirmed in phase 2.

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

## 8. Types that change package

The tick-file types leave `data`, because the rest of `data` stays in the main
module and one package cannot live in two modules. Planned home:
`com.quantfinlib.marketdata.tick` in the low-latency artifact.

```java
// 2.x
import com.quantfinlib.data.TickFileWriter;
// 3.0 (planned)
import com.quantfinlib.marketdata.tick.TickFileWriter;
```

`util.ValueArrays` and `util.Immutable` are used by stable packages (`core`,
`fx`, `rates`, `risk`, `ml`, `optimization`), so they cannot move to the
low-latency artifact. The plan is a non-exported `com.quantfinlib.internal`
package in the main module; they leave the public API. This corrects the
proposal, which listed them with the lane helpers. If you use `ValueArrays`,
copy the few lines you need.

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
In 3.0 the CLI keeps those names with private copies inside `cli`, so command
lines keep working; only the public types go.

## 10. Format note: FIX session store v1 layout (D8)

A 2.x `FileSessionStore` opened on a directory written by the v1 store
(`seqnums.dat`, `messages.dat`) imports it and renames the old files with a
`.migrated` suffix. 3.0 drops that import: it starts a fresh journal and leaves
the v1 files untouched, so sequence numbers would restart. Migrate before you
upgrade, with any 2.x jar:

```sh
java -cp quant-finance-library-2.4.0.jar:quant-finance-library-2.4.0-examples.jar \
     com.quantfinlib.examples.FixJournalMigration /var/fix/session1 /var/fix/session2
```

(use `;` as the class-path separator on Windows). Running it twice is harmless.

## 11. What does not change

Numbers (apart from the rounding-level exposure note in section 6),
conventions, the stable package list in `docs/STABILITY.md`, and every package
name other than `reporting` and the tick-file types.
