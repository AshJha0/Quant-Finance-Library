# Getting started

This page takes you from a fresh clone to a backtest report, the paper-trading demo
and a benchmark run. Commands are shown for a POSIX shell; on Windows run `mvnw.cmd`
instead of `./mvnw` and use `^` instead of `\` for line continuation in `cmd.exe`.

## 1. Prerequisites

- A JDK 25 (any distribution, for example Eclipse Temurin 25). The build refuses
  older JDKs through the Maven enforcer plugin.
- `JAVA_HOME` pointing at that JDK, or `java` 25 first on the `PATH`.
- Git. Maven is not needed: the repository includes the Maven wrapper (`mvnw`), which
  downloads the pinned Maven version on first use.

Check:

```bash
java -version      # should report version 25
```

## 2. Clone and build

```bash
git clone https://github.com/AshJha0/Quant-Finance-Library.git
cd Quant-Finance-Library
./mvnw verify
```

`verify` compiles with `-Xlint:all`, runs the default test set, checks line coverage
(the build fails below 90 % overall or 80 % in any package) and packages three jars in
`target/`:

| Jar | Contents |
|---|---|
| `quant-finance-library-<version>.jar` | The library. Its manifest main class is the command line, `com.quantfinlib.cli.Main`. |
| `quant-finance-library-<version>-examples.jar` | Demos and hand-written benchmarks (`com.quantfinlib.examples`). |
| `-sources.jar`, `-javadoc.jar` | Sources and API documentation. |

For a faster loop while editing, `./mvnw -DskipTests compile` builds `target/classes`
only.

The library jar is the named module `com.quantfinlib`. From your own modular code,
add `requires com.quantfinlib;` to `module-info.java`; on the class path nothing is
needed. The examples jar has no module descriptor, so run the demos on the class
path, for example
`java -cp quant-finance-library-2.2.0.jar:quant-finance-library-2.2.0-examples.jar com.quantfinlib.examples.QuickStartDemo`
(use `;` as the separator on Windows).

Two invalid-input behaviours changed in 2.1. `MathUtils.percentile` throws
`IllegalArgumentException` for a probability outside [0, 1] or NaN, and the range
means and standard deviations throw `IndexOutOfBoundsException` for a bad range. Bad
input files and configuration throw `MalformedDataException` or
`ConfigurationException`, both `IllegalArgumentException`s that implement
`core.QuantFinException`.

## 3. First backtest from a CSV file

The command line reads daily bars from a CSV file. The header must name the columns;
names are matched case-insensitively:

- a time column called `date`, `time`, `timestamp` or `datetime`, holding
  `yyyy-MM-dd`, an ISO date-time, or epoch seconds or milliseconds;
- `open`, `high`, `low`, `close`, and optionally `volume`.

Rows may be in any order; they are sorted by time. A semicolon-delimited file is read
with a comma as the decimal separator.

```csv
date,open,high,low,close,volume
2024-01-02,472.16,473.67,470.49,472.65,123623700
2024-01-03,470.43,471.19,468.17,468.79,103585900
...
```

Run a moving-average crossover and write an HTML report:

```bash
java -jar target/quant-finance-library-*.jar backtest \
    --csv bars.csv --symbol SPY --strategy sma --fast 10 --slow 30 \
    --capital 100000 --out report.html
```

`--strategy` accepts `sma`, `ema`, `rsi`, `macd` and `bollinger`; `sma` and `ema` take
`--fast` and `--slow`, `rsi` takes `--period`. The backtest uses
`BacktestConfig.defaults()`: orders decided on a bar's close fill at the next bar's
open, with 1 bp commission and 3 bp slippage.

Two more commands use the same CSV input:

```bash
# Walk-forward: optimise the SMA windows on 252 bars, test on the next 63, roll forward.
java -jar target/quant-finance-library-*.jar walkforward \
    --csv bars.csv --symbol SPY --train 252 --test 63 --fast 5,10,20 --slow 40,60 \
    --out walkforward.html

# Market report: price chart, drawdown from peak and an indicator summary.
java -jar target/quant-finance-library-*.jar report --csv bars.csv --symbol SPY --out market.html
```

`java -jar target/quant-finance-library-*.jar help` prints the usage. The exit code
is 0 on success, 1 for a usage error and 2 when the run fails. If your shell does
not expand the `*`, write the full jar name.

## 4. Library tour and paper-trading demo

Both demos live in the examples jar and also in `target/classes` after a compile.

```bash
./mvnw -DskipTests compile
java -cp target/classes com.quantfinlib.examples.QuickStartDemo
```

`QuickStartDemo` runs offline. It walks through indicators, backtesting, risk,
optimisation, pricing, fixed income and a risk-gated paper-trading session on
synthetic data.

```bash
java -cp target/classes com.quantfinlib.examples.LiveTradingDemo
```

`LiveTradingDemo` needs internet access. It subscribes to the public Binance trade
stream over a WebSocket (no account or key), runs a streaming strategy, sends its
orders to `PaperTradingGateway` (a simulated venue that fills at most the displayed
size), and serves a dashboard at <http://localhost:8080>. The dashboard binds to the
loopback interface only. Stop the demo with Ctrl-C.

## 5. Running a benchmark

There are two kinds of benchmark. Results, the machine they ran on and the caveats
are recorded in [BENCHMARKS.md](BENCHMARKS.md).

Hand-written end-to-end benchmarks, in `com.quantfinlib.examples`, print latency
percentiles for closed-loop runs and for open-loop runs at a fixed rate (each sample
timed from its intended start, so a stall is charged to every message it delays), plus
a platform hiccup summary:

```bash
./mvnw -DskipTests compile
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes \
    com.quantfinlib.examples.HftLatencyBenchmark
java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -cp target/classes \
    com.quantfinlib.examples.HftOrderBenchmark
```

`HftQuoterBenchmark`, `HftBookBenchmark`, `ScaleBenchmark` and
`ShardScaleBenchmark` run the same way. `HftLatencyBenchmark` and `HftOrderBenchmark`
take an optional open-loop rate in messages per second as their only argument.

To repeat the four latency benchmarks used in [BENCHMARKS.md](BENCHMARKS.md) with
the documented JVM flags, run `scripts/run-benchmarks.sh`. It runs each one three
times (`RUNS=5` for more, or name one benchmark as the argument), prints the minimum,
median and maximum of every reported figure, and keeps the raw logs in
`target/bench-runs/`. Run it on an otherwise idle machine.

JMH microbenchmarks live in the test sources:

```bash
./mvnw test-compile exec:java -Dexec.mainClass=com.quantfinlib.bench.BenchRunner \
    -Dexec.classpathScope=test -Dexec.args=CoreBenchmarks
```

Numbers from a laptop or a shared CI runner say little about tails. See
[ULTRA_LOW_LATENCY.md](ULTRA_LOW_LATENCY.md) for JVM and operating-system settings.

## 6. Tests and tags

| Command | What runs |
|---|---|
| `./mvnw test` | The default set. Test classes run in parallel; each test has a 120-second timeout. |
| `./mvnw -Pfull test` | Also the tests tagged `load` (soak and load tests) and `timing` (wall-clock assertions). They run serially. The nightly workflow uses this profile. |
| `./mvnw -Panalysis verify` | Adds Error Prone and SpotBugs. Findings are reported as warnings. |
| `scripts/fetch-api-baseline.sh && ./mvnw -Papi-compat -DskipTests verify` | The API compatibility check: japicmp compares the build with the released 2.1.0 jar and fails on a binary-incompatible change in a stable package. The script caches the baseline jar in `~/.m2/api-baseline/`. The report is `target/japicmp/api-all.html`. |
| `./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage` | PIT mutation testing on `pricing`, `risk`, `rates` and `volatility`; the report is `target/pit-reports/index.html`. Narrow it with `-Dpit.targets=com.quantfinlib.volatility.*`; `python3 scripts/pit-summary.py target/pit-reports/mutations.xml` prints the per-package scores. The nightly workflow runs it. |
| `./mvnw test -Dtest=VarEngineTest` | One test class (standard Surefire filter). |

Tests tagged `socket` open loopback sockets and run in the default set. If a firewall
blocks loopback connections, exclude them with
`./mvnw test -Dtest.excludedGroups=load,timing,socket`.

## 7. Troubleshooting

**`Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion failed`.** Maven
is running on a JDK older than 25. Check `./mvnw -v`; it prints the JDK it uses. Set
`JAVA_HOME` to a JDK 25 and open a new shell.

**`invalid target release: 25` or `release version 25 not supported`.** Same cause: the
compiler comes from an older JDK.

**The build fails at `jacoco:check`.** Coverage fell below the thresholds. This only
happens after code changes; run `./mvnw verify` and open
`target/site/jacoco/index.html` to see which package dropped.

**A test times out after 120 seconds.** The default timeout catches hangs. On a very
slow or heavily loaded machine, rerun the single class; if it still times out, report
it with the thread dump Surefire prints.

**`./mvnw: Permission denied`.** The execute bit was lost (for example after copying
from a Windows share). Run `chmod +x mvnw`, or `sh mvnw verify`.

**`ClassNotFoundException: com.quantfinlib.examples...`** when running from the
library jar. Demos are not in the library jar; use `target/classes` or the
`-examples` jar.

**`LiveTradingDemo` prints connection errors.** The demo needs outbound access to the
Binance WebSocket endpoint. Corporate proxies and some regions block it. The feed
reconnects on its own; nothing else in the library needs the network.

**`Address already in use` from `LiveTradingDemo`.** Something else is listening on
port 8080. Stop it, or change the port passed to `TradingDashboard` in the demo.
