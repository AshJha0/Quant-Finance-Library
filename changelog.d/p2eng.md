### Added
- `stats.Stats`: one home for mean, sample and population variance and standard deviation, sample covariance, Pearson correlation, Hyndman-Fan type 7 and type 1 quantiles, median and average-tie midranks, each with its convention and edge cases stated.
- `module-info.java`: the library jar is now the named module `com.quantfinlib`, exporting every package that was public in 2.0 (`requires java.net.http`, `jdk.httpserver`, `static org.jspecify`); `examples` is not exported and stays out of the library jar.
- Library exception types in `core`: `QuantFinException` (marker interface), `WireProtocolException` (an `IllegalStateException`), `MalformedDataException` and `ConfigurationException` (both `IllegalArgumentException`s). `fix.FixParseException` and `data.TickFileReader.TruncatedTickFileException` implement the marker.

### Changed
- `MathUtils.mean`, `variance`, `stdDev`, `stdDevP`, `stdDevSample`, `percentile`, `percentileSorted` and `covariance`, and `risk.Dependence.ranks`, delegate to `Stats`; results are unchanged bit for bit on valid input.
- `MathUtils.percentile` and `percentileSorted` now reject a probability outside [0, 1] or NaN with `IllegalArgumentException`; before, such a value threw `ArrayIndexOutOfBoundsException` or returned the minimum.
- `MathUtils.mean(v, from, to)` and the range standard deviations now throw `IndexOutOfBoundsException` for a range outside the array or with `from > to`.
- FIX BodyLength and SBE header framing faults throw `WireProtocolException`; CSV bar and universe file format faults throw `MalformedDataException`; `trading.LimitsConfig` faults throw `ConfigurationException`. Each is a subtype of the type thrown before, so existing catch blocks still work.
- Classes in exported packages that relied on the implicit default constructor now declare it explicitly (same signature).

### Fixed
- `crb.InternalizationEngine`: the constructor Javadoc sat on a field and did not appear in the API docs.
