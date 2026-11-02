# Migrating to 4.0

Status: written for 3.2.0, which announces every change below; 4.0.0 follows 3.2.0
directly and makes them. The design and its reasons are in
[DESIGN_4.0.md](DESIGN_4.0.md). On 3.2.0, compiling with `-Xlint:removal` lists every
line that uses a type 4.0 removes, and each of those has a replacement you can switch to
on 3.2.0 already.

The plain idea: 4.0 keeps the package names of everything that moves. What changes is
which jar a package lives in. So most users change a dependency, not their code. Only
four classes change their import, and one deprecated class goes away.

## What breaks, row by row

All coordinates are on JitPack (`maven("https://jitpack.io")` or the equivalent
`<repository>`), group `com.github.AshJha0.Quant-Finance-Library`, version `v4.0.0`.

### 1. `cli` and `backtest.strategies` move to the examples jar

They were demonstration code under the stable promise. If you used a sample strategy,
copy it into your code, or depend on the examples jar:

```xml
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library-lowlatency</artifactId>
  <version>v4.0.0</version>
  <classifier>examples</classifier>
</dependency>
```

The examples jar is not a JPMS module; put it on the class path. It needs the lowlatency,
io and research jars at run time.

### 2. `fx.AggregatedBook` becomes `marketdata.AggregatedBook` (lowlatency)

```xml
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library-lowlatency</artifactId>
  <version>v4.0.0</version>
</dependency>
```

```java
// 3.x
import com.quantfinlib.fx.AggregatedBook;
// 3.2 and 4.0
import com.quantfinlib.marketdata.AggregatedBook;
```

Same constructor, methods and results; `module-info.java`: `requires com.quantfinlib.lowlatency;`.

### 3. `rfq`, `marketdata.tick` and `backtest.tick` move to lowlatency

Package names unchanged. Add the lowlatency dependency (snippet in row 2) and
`requires com.quantfinlib.lowlatency;`. That module requires the stable module
transitively, so nothing else changes.

### 4. `data.CsvBarLoader`, `data.HttpBarFetcher`, `data.UniverseCsvLoader` become `io.*`

```xml
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library-io</artifactId>
  <version>v4.0.0</version>
</dependency>
```

```java
// 3.x
import com.quantfinlib.data.CsvBarLoader;
// 3.2 and 4.0
import com.quantfinlib.io.CsvBarLoader;
```

Same for the other two. `module-info.java`: `requires com.quantfinlib.io;`. The rest of
`data` (`PointInTimeUniverse`, `CorporateActions`, `CorporateEvents`,
`CorporateEventPositions`, `SeriesAligner`) stays in the stable jar.

### 5. `report` moves to `-io`

Package name unchanged. Add the io dependency (snippet in row 4) and
`requires com.quantfinlib.io;`.

### 6. `dsl` moves to `-research`

```xml
<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library-research</artifactId>
  <version>v4.0.0</version>
</dependency>
```

Package name unchanged; `requires com.quantfinlib.research;`.

### 7. Core no longer requires `java.net.http`

`com.quantfinlib` used to declare `requires transitive java.net.http`. If your module
read `java.net.http` only through it, add `requires java.net.http;` yourself. The io
module requires it (not transitively).

### 8. `rates.SwapPricer` is removed

Deprecated since 3.1.0, marked for removal in 3.2.0. The replacements are in
[MIGRATION_3.1.md](MIGRATION_3.1.md), section 1. No dependency change.

## What does not break

Everything else in the stable jar keeps its package, class and signature. In particular
`execution`, `microstructure`, `persist`, `screener`, `ml` and the rest of `data` stay in
the stable jar; the 3.1 draft of DESIGN_4.0 proposed moving them, and DESIGN_4.0 explains
why they stay.

## Worked example

A user on 3.1 with `quant-finance-library` who loads bars from CSV and prices options:

1. On 3.2.0, change `import com.quantfinlib.data.CsvBarLoader;` to
   `import com.quantfinlib.io.CsvBarLoader;`. It compiles with no removal warning.
2. On 4.0.0, add the `quant-finance-library-io` dependency and
   `requires com.quantfinlib.io;`. The pricing code is untouched.
