# Releasing and publishing

## Releases on GitHub

A release is cut by pushing a tag `v<version>` whose version matches `project.version`
in `pom.xml` (tag `v2.1.0` for version `2.1.0`). Tags are immutable: a tag is never
moved, deleted and re-pushed, or force-pushed. If a release build fails, fix it and
cut the next patch version.

### Release checklist

1. On the release branch: set the version in `pom.xml` (no `-SNAPSHOT`), update
   `CHANGELOG.md`, open the PR and wait for CI to pass.
2. **Tag first, then merge.** Tag the PR head commit that CI passed and push the tag:

   ```bash
   git tag -a v2.1.0 -m "quantfinlib 2.1.0"
   git push origin v2.1.0
   ```

   The Docs workflow publishes the javadoc of the newest `v*` tag when `main` is
   pushed. If the merge lands before the tag exists, that Docs run builds the
   previous release and has to be dispatched again; tagging first avoids it. The
   Release workflow also dispatches Docs on `main` at its end, so the site is right
   either way, but tag-first is the order to follow.
3. Watch the Release run, then merge the PR (`--merge`, so the tagged commit is on
   `main`).
4. Check the release page (jars attached) and the Pages site (version shown).

The `Release` workflow (`.github/workflows/release.yml`) then runs on JDK 25:

- `tag-guard` fails if the tag was force-pushed, already has a release, or was used
  by an earlier Release run at another commit.
- `ci` runs the whole CI workflow (`ci.yml`, called as a reusable workflow) on the
  tagged commit, and `docs` builds the javadoc as the Docs workflow does. Nothing is
  published unless both pass.
- It reads `project.version` with `./mvnw help:evaluate` and fails if the tag without
  its leading `v` differs from it. Nothing is published in that case; cut a new
  patch version with the right number (do not move the tag).
- It runs `./mvnw verify` (tests and coverage check).
- It creates a GitHub release with generated notes and attaches the jars of both
  modules: `core/target/quant-finance-library-*.jar` (library, `-sources`,
  `-javadoc`) and `lowlatency/target/quant-finance-library-lowlatency-*.jar` (library,
  `-examples`, `-sources`, `-javadoc`).
- It dispatches the Docs workflow on `main`.

After a release, move the API compatibility baseline to it: set
`api.baseline.version` in the parent `pom.xml` and the version passed to
`scripts/fetch-api-baseline.sh` in the `api-compat` job of `.github/workflows/ci.yml`
to the version just released. The gate compares against 3.0.0: the fetch script
downloads both 3.x jars (`quant-finance-library` and
`quant-finance-library-lowlatency`), and `api.baseline.jar` and
`api.baseline.lowlatency.jar` in the parent POM name them. There is no allow-list in
3.x.

## Build integrity: SBOM, reproducible jars, supported JDKs

**SBOM.** Each GitHub release carries a CycloneDX software bill of materials per
module (`quant-finance-library-<version>-sbom.cdx.json` and the `-lowlatency-`
one). It lists the runtime dependency closure only; test-scope libraries are left
out, so for 3.x it records the zero-runtime-dependency claim in a machine-readable
form. The release job makes it with the `sbom` profile
(`org.cyclonedx:cyclonedx-maven-plugin`); to make it locally:

```bash
./mvnw -Psbom -DskipTests package   # writes core/target/bom.json, bom.xml (and lowlatency/)
```

**Reproducible jars.** `project.build.outputTimestamp` in the parent `pom.xml` fixes
the timestamp and order of every jar entry, so the same commit built twice gives
byte-identical jars. The CI job `reproducible build` checks this on every PR:

```bash
bash scripts/check-reproducible.sh   # two clean builds, SHA-256 of every jar, diff
```

Anyone can check a release the same way: check out the tag, run the script (or one
`./mvnw -DskipTests package` on JDK 25) and compare its hashes with the jars on the
release page. Bump `project.build.outputTimestamp` when you cut a release; it does
not have to be exact, it only has to be fixed.

**Supported JDK.** Java 25 only. The build's enforcer rule refuses an older JDK, and CI runs the full `verify` on JDK 25 on Ubuntu, Windows and macOS.

## JitPack

JitPack builds from a tag on request, so a release is usable without further setup:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.AshJha0.Quant-Finance-Library</groupId>
  <artifactId>quant-finance-library</artifactId>   <!-- or quant-finance-library-lowlatency -->
  <version>v3.1.0</version>
</dependency>
```

Since 3.0 the build is multi-module, so JitPack names the group
`com.github.AshJha0.Quant-Finance-Library` and publishes each module under its own
artifactId (2.x was `com.github.AshJha0:Quant-Finance-Library`). Check a JitPack
build of a test tag before the release tag.

JitPack must build with JDK 25; `jitpack.yml` at the repository root selects
`openjdk25` and runs `./mvnw install` on the reactor, which installs both modules.

## Maven Central (not yet set up)

The POM carries the metadata Central requires (`licenses`, `developers`, `scm`,
`url`), sources and javadoc jars attach on every build, and a `central-release`
profile holds the signing and upload plugins. What remains is one-time setup by the
project owner.

### One-time setup

1. **Central Portal account.** Sign in at <https://central.sonatype.com> with GitHub
   and register a namespace. `com.quantfinlib` needs DNS verification of a domain;
   the GitHub-backed namespace `io.github.ashjha0` is verified automatically, but
   using it means changing the `groupId`.
2. **User token.** Central Portal, Account, Generate User Token. Put it in
   `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username><!-- token username --></username>
         <password><!-- token password --></password>
       </server>
     </servers>
   </settings>
   ```

3. **GPG key.** Central requires signed artifacts:

   ```bash
   gpg --gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   ```

   `maven-gpg-plugin` uses the default key; set `gpg.keyname` and `gpg.passphrase` in
   `settings.xml` if you have several.

### Publishing a version

```bash
./mvnw -P central-release clean deploy
```

This builds, tests, attaches sources and javadoc, signs, uploads the bundle to the
Central Portal and, with `autoPublish=true`, releases it. Consumers then use:

```xml
<dependency>
  <groupId>com.quantfinlib</groupId>
  <artifactId>quant-finance-library</artifactId>
  <version>2.1.0</version>
</dependency>
```

### Automating it

After a manual publish has worked, add repository secrets `CENTRAL_USERNAME`,
`CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`, and add a job to
`release.yml` that imports the key, writes the `settings.xml` server entry and runs the
same deploy command. Keep it after the tag check, so a mismatched tag cannot publish.
