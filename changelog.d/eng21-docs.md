# Docs notes from eng21 (W5.1, W5.5 nullness, W5.6)

## docs/STABILITY.md
- Add: "Compatibility is checked by japicmp against the released 2.0.0 jar (`-Papi-compat`, CI job `API compatibility`). The stable packages fail the build on any binary-incompatible change; the reference packages are compared and reported in `target/japicmp/api-all.html`." The include list lives in pom.xml (profile `api-compat`); keep it in step with the table on that page.
- Add: "Stable packages are `@NullMarked` (JSpecify 1.0). Unannotated parameters and return values are non-null; a member that accepts or returns null says so with `@Nullable`." `util` (only `MathUtils` is stable) and the reference packages are not marked.
- After 2.1.0 ships, bump `api.baseline.version` in pom.xml and the argument in ci.yml to 2.1.0.

## CONTRIBUTING / README (build section)
- Compatibility check: `scripts/fetch-api-baseline.sh && ./mvnw -Papi-compat -DskipTests verify`. The baseline jar is cached in `~/.m2/api-baseline/` so `clean` does not remove it.
- Mutation testing: `./mvnw -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage`, report in `target/pit-reports/index.html`; narrow with `-Dpit.targets=com.quantfinlib.volatility.*`; `python3 scripts/pit-summary.py target/pit-reports/mutations.xml` prints the per-package table.
- Users of JPMS: JSpecify is not needed at run time. If a module-info.java is added (W5.2), use `requires static org.jspecify;`.

## docs/BENCHMARKS.md ("Updating this page")
- Replace the manual loop with: `scripts/run-benchmarks.sh` (three runs of the four programs, documented JVM flags; `RUNS=5` or a benchmark name to narrow). It prints min / median / max for every figure the programs report and keeps the raw logs in `target/bench-runs/`.

## Testing notes for a quality page
- Mutation score per package (PIT 1.30.1, default mutators, nightly): see the eng21 report figures; quote as "mutation score (killed / all mutants)" and "test strength (killed / covered mutants)".
