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

Tests use JUnit 5. Test classes run in parallel, methods within a class run in
order, and every test has a two-minute timeout.

Some tests are tagged:

| Tag      | Meaning                                                   | Default run |
|----------|-----------------------------------------------------------|-------------|
| `load`   | throughput floors, soak and stress runs                   | skipped     |
| `timing` | asserts on wall-clock time or waits with `Thread.sleep`   | skipped     |
| `socket` | opens real loopback sockets                               | runs        |

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
