# Contributing

Thanks for taking the time. This file covers how to build, how the tests are
organised, and what a pull request needs before it is merged.

## Build

You need JDK 25. Maven comes with the repository through the wrapper.

    ./mvnw verify          # compile, unit tests, coverage gate, jars
    ./mvnw -Pfull test     # every test, including load and timing tags
    ./mvnw -Panalysis -DskipTests clean verify   # Error Prone and SpotBugs

On Windows use `mvnw.cmd` in place of `./mvnw`.

`verify` fails if line coverage drops below 90 % for the library as a whole or
below 80 % for any package. The `examples` package is excluded from coverage and
ships in its own jar (classifier `examples`), not in the library jar.

## Tests

Tests use JUnit 6 (Jupiter). Test classes run in parallel, methods within a class run in
order, and every test has a two-minute timeout.

Some tests are tagged:

| Tag      | Meaning                                                   | Default run |
|----------|-----------------------------------------------------------|-------------|
| `load`   | throughput floors, soak and stress runs                   | skipped     |
| `timing` | asserts on wall-clock time or waits with `Thread.sleep`   | skipped     |
| `socket` | opens real loopback sockets                               | runs        |

Seven tests need files that cannot be redistributed, or the network. They skip
unless the matching environment variable is set:

| Variable | Points at | Unlocks |
|----------|-----------|---------|
| `QFL_ISDA_GRID_DIR` | directory of ISDA CDS Standard Model test grids (converted) | `IsdaGridVerificationTest` against the official grids |
| `QFL_ESMA_XSD` | ESMA auth.016.001.01 XSD | schema validation in `Auth016XmlTest` and `Auth016EnvelopeTest` |
| `QFL_ESMA_HEAD001_XSD` | ISO 20022 head.001 (business application header) XSD | envelope validation in `Auth016EnvelopeTest` and `StatusAdviceTest` |
| `QFL_ESMA_HEAD003_XSD` | ISO 20022 head.003 (business file header) XSD | envelope validation in `Auth016EnvelopeTest` and `StatusAdviceTest` |
| `QFL_ESMA_AUTH031_XSD` | ESMA auth.031.001.01 XSD | feedback-file validation in `StatusAdviceTest` |
| `QFL_SIX_CFI_XLSX` | SIX ISO 10962 CFI code list (xlsx) | full-list check in `CfiCodeRegistryTest` |
| `QFL_NETWORK_TESTS=1` | (flag) | live GLEIF API call in `RegistryTest` |

Reference tests call `ReferenceFiles.require(...)`, which skips when the file is
missing unless `QFL_REQUIRE_REFERENCE_FILES=true`, and then fails. The nightly
`reference-files` job fetches every file with `scripts/fetch-reference-files.sh`
(SHA-256 pinned in `scripts/reference-files.sha256`) and sets that flag, so a new
reference test must be added to that job and the pin file. The PR-time stand-ins
are `IsdaGoldenSubsetTest` (`isda-regression-set.csv`) and `EsmaXsdGoldenTest`.

The nightly PIT jobs fail when a package falls below its floor in
`scripts/pit-thresholds.txt`. Raise a floor when a score rises; lowering one
needs a reason in the commit message. A new loader states its limits in
[docs/INPUT_LIMITS.md](docs/INPUT_LIMITS.md).

Randomised tests (property tests, fuzz tests) are plain JUnit tests driven by a
`SplittableRandom` with a fixed seed per test and `qfl.cases` cases (default
1000); the failure message must name the seed and case index. Fuzz test classes
end in `FuzzTest`: `./mvnw -Pfuzz -Djacoco.skip=true -pl lowlatency -am test`
runs only those, at 200,000 cases each (`-Dfuzz.tries=N`), as the nightly `fuzz`
job does. A new parser should get one, with a small seed corpus.

The nightly workflow runs everything. If you add a test that depends on how fast
the machine is, tag it. Prefer polling with a deadline over a fixed sleep.

A test should pin an independent reference value: a published table, a closed
form, an exact hand calculation, or a brute-force or Monte Carlo result with a
stated tolerance and a fixed seed. A test that only checks the code agrees with
itself does not count.

## Code rules

- No runtime dependencies. Pure JDK 25, no preview features.
- Random numbers come from `java.util.random.RandomGenerator` (usually
  `SplittableRandom`) with an explicit seed.
- No silent NaN: validate inputs and throw, or document exactly when NaN is
  returned.
- If a class says it is allocation-free or thread-safe, a test must show it.
- Javadoc states units and conventions (day count, compounding, sign) and cites
  the source of any model: author, year, equation number.
- Write docs and comments plainly. Avoid marketing words such as "robust",
  "production-ready" or "world-class", avoid capitals for emphasis, and leave
  latency numbers out of javadoc; measured numbers belong in the benchmark notes
  with the machine they came from.

## Pull request checklist

- [ ] `./mvnw verify` passes locally.
- [ ] New behaviour has a test with an independent reference value.
- [ ] Machine-speed-dependent tests are tagged `load` or `timing`.
- [ ] Javadoc matches the behaviour and cites sources.
- [ ] A line in `CHANGELOG.md` under Unreleased for anything a user would notice.
- [ ] Breaking API changes are called out in the description.
