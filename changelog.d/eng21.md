### Added
- API compatibility gate: the `api-compat` Maven profile (japicmp) compares the build against the released 2.0.0 jar and fails on binary-incompatible changes in the stable packages; reference packages are reported only. Runs in CI on Ubuntu.
- `scripts/fetch-api-baseline.sh` downloads the released jar used as the compatibility baseline.
- JSpecify `@NullMarked` on the stable packages: parameters and return values there are non-null unless marked `@Nullable`. JSpecify is a compile-time (provided, optional) dependency; the library still has no runtime dependencies.
- Mutation testing: the `mutation` Maven profile runs PIT on `pricing`, `risk`, `rates` and `volatility`; the nightly workflow publishes the per-package score and the HTML report.
- `scripts/run-benchmarks.sh` runs the four latency benchmarks three times with the documented JVM flags and prints the min / median / max table used in docs/BENCHMARKS.md.
