# API stability

The library has three kinds of code, and they come with different promises. This page
says which package is which and how versions are numbered from 2.0.0 onward.

## Stable research and risk APIs

These packages are the library's main purpose. Their public classes and methods are
covered by the versioning policy below, and numerical results change only to fix a
documented error.

| Area | Packages (under `com.quantfinlib`) |
|---|---|
| Data and series | `core`, `data` (CSV loading, alignment, corporate actions, point-in-time universes), `util.MathUtils` |
| Pricing and instruments | `pricing`, `rates`, `credit`, `fx` (conventions, curves, instruments, volatility surface), `commodities`, `markets` |
| Risk | `risk`, `crb`, `volatility`, `hedging` |
| Portfolio and research | `optimization`, `alpha`, `backtest` and its subpackages, `indicators`, `simulation`, `ml`, `screener`, `dsl` |
| Execution research | `microstructure`, `execution`, `regulatory`, `rfq` |
| Output and tools | `report`, `cli` (commands and options), `persist` |

Within these packages, stability covers method signatures, record components and
documented behaviour (conventions, units, default parameters). It does not cover
`toString()` output, the exact text of exception messages, or the last digits of
results computed by iterative solvers.

## Reference implementations

These packages show how a low-latency trading lane is put together on the JVM, and
they are tested. They are not a certified trading or connectivity stack: they have not
been through venue certification, have not run against a production venue, and leave
out parts a production system needs (session schedules, entitlements, drop copies,
regulatory reporting, operational tooling).

| Package | What it is |
|---|---|
| `trading` | Risk gate, order gateway, order state machine, quoter, auto hedger, paper venue, sharding |
| `marketdata` | Ring buffers, market-data bus, ITCH 5.0 codec, L3 book builder, NBBO |
| `orderbook` | Reference matching engine (`OrderBook`) and price-ladder engine (`HftOrderBook`) |
| `fix` | FIX 4.4 session, journal store, garbage-free encoders and views |
| `sbe` | SBE-style binary codecs (schema 2) and channel adapters |
| `feed` | WebSocket feed, MoldUDP64 receiver |
| `data` tick files | `TickFileWriter`, `TickFileReader`, `TickCapture`, `AsyncTickCapture` |
| `util` measurement | `LatencyRecorder`, `HiccupMonitor` |

The versioning policy applies to these packages too, with one exception: a minor
release may change a wire or file format (SBE schema, QFLT tick file, FIX journal)
when it fixes a correctness or safety problem. Such a change bumps the format version,
keeps reading the previous version, and is listed under "Changed" in the changelog.

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

Deprecated API is marked with `@Deprecated` and stays for at least one minor release
before removal in the next major.
