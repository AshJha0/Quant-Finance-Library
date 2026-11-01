### Changed
- The library jar no longer contains `com.quantfinlib.examples`; demos and hand-rolled benchmarks ship as a separate jar with classifier `examples`.
- Load, soak and wall-clock tests are tagged `load` or `timing` and skipped by the default build; `./mvnw -Pfull test` runs them, and so does the nightly workflow.
- Test classes now run in parallel, and every test has a 120-second timeout.
- `./mvnw verify` fails if line coverage falls below 90 % overall or 80 % in any package.
- CI runs `./mvnw verify` on Ubuntu and Windows with JDK 25, with read-only token permissions, a concurrency group and job timeouts.
- The release workflow refuses to publish when the tag does not match the pom version.

### Added
- `-Xlint:all` compiler warnings, and an `analysis` profile running Error Prone and SpotBugs (warnings only for now).
- Nightly workflow for the tagged tests and static analysis.
- Dependabot for Maven and GitHub Actions, SECURITY.md, CONTRIBUTING.md, issue and pull request templates.
