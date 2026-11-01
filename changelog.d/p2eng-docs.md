# Docs notes for p2eng (W5.2, W5.4, W5.5)

## stats.Stats (new package com.quantfinlib.stats)
- Why a package of its own: the definitions sit in one small, documented, stable surface; every older helper delegates to it. Add `stats` to the stable list in STABILITY.md (it is already in the api-stable japicmp includes) and to the package map in ARCHITECTURE.md.
- Conventions (LEARN): sample variance divides by n - 1, population by n. Quantiles follow Hyndman and Fan (1996), The American Statistician 50(4), 361-365: `quantile` is type 7 (R and NumPy default), h = (n - 1) p, Q = x[floor h] + (h - floor h)(x[floor h + 1] - x[floor h]); `quantileType1Sorted` is type 1, the inverse empirical CDF x[ceil(n p) - 1]. Median = type 7 at 0.5. Ranks are 1-based midranks (ties averaged).
- Edge cases: empty input gives NaN (mean, quantiles, median); n < 2 gives NaN for the sample variance; p outside [0, 1] or NaN throws IllegalArgumentException; `pearson` is NaN for zero dispersion while `MathUtils.correlation` keeps its 0 convention.
- COOKBOOK: `double[] v = {1, 2, 3, 4}; Stats.quantile(v, 0.25); // 1.75 (type 7)  Stats.quantileType1Sorted(v, 0.25); // 1  Stats.sampleVariance(v); // 5/3`
- Duplicates that remain on purpose: `alpha.AlphaCombiner.pearson` (pairwise-complete, NaN below three pairs), `risk.TailRisk.varSorted` (type 1 with confidence snapping for the Basel convention), `util.LatencyRecorder.percentile` (histogram buckets), `alpha.NeweyWest` autocovariances (mean-supplied, lagged).

## Module descriptor (W5.4)
- README / ARCHITECTURE: the jar is the named module `com.quantfinlib`. `requires com.quantfinlib;` in a module-info, or keep using the class path. Exports every 2.0 public package, reference packages included; stability tiers are still those of STABILITY.md. `jdk.httpserver` is needed for `trading.TradingDashboard`, `java.net.http` for `data.HttpBarFetcher` and `feed.WebSocketFeed`. JSpecify is `requires static`.
- The examples classifier jar has no descriptor; run demos on the class path: `java -cp quant-finance-library-2.1.0.jar:quant-finance-library-2.1.0-examples.jar com.quantfinlib.examples.QuickStartDemo`.
- `java -jar quant-finance-library-2.1.0.jar` and `java -p quant-finance-library-2.1.0.jar -m com.quantfinlib` both start the CLI.
- CONTRIBUTING: tests compile into the module (the compiler plugin patches them in) and run on the class path (`useModulePath=false`); `ModuleDescriptorTest` fails if a new package is added without an `exports` line.

## Exception policy (W5.5), for ARCHITECTURE.md
- A library exception marks a fault in external input; it always extends the JDK type the same site threw before, so a catch block written for 2.0 still catches it. `core.QuantFinException` is a marker interface (Java has single inheritance, so a common base class could not extend both IllegalArgumentException and IllegalStateException); test `e instanceof QuantFinException` to tell a library input fault from any other failure.
- Which type where:
  - `WireProtocolException extends IllegalStateException`: a byte stream that breaks its framing (FIX BodyLength in `fix.FixDecoder`, SBE schema/template header in `sbe.SbeFrames`). Response: drop the connection, recover by sequence number.
  - `MalformedDataException extends IllegalArgumentException`: a file or record that does not follow its format (`data.CsvBarLoader.parse`, `data.UniverseCsvLoader.parse`).
  - `ConfigurationException extends IllegalArgumentException`: an invalid configuration or limit (`trading.LimitsConfig`).
  - `fix.FixParseException` (IllegalArgumentException) and `data.TickFileReader.TruncatedTickFileException` (IOException) predate the scheme and implement the marker.
- Plain argument validation (negative size, null factory, p out of range) stays `IllegalArgumentException`: it is a programming error at the call site, not an input fault.
- Not converted in 2.1: IOException sites (`TickFileReader` header checks, `persist.Checkpoint` format checks) and UncheckedIOException persistence failures in `fix.FileSessionStore`; a subtype there would need an IOException-based type and is left for a later release.
- COOKBOOK: `try { LimitsConfig.parse(text); } catch (ConfigurationException e) { log(e.getMessage()); }`
