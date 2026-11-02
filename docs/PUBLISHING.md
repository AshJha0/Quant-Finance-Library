# Releasing and publishing

## Releases on GitHub

A release is cut by pushing a tag `v<version>` whose version matches `project.version`
in `pom.xml` (tag `v2.1.0` for version `2.1.0`).

1. Set the version in `pom.xml` (no `-SNAPSHOT`), update `CHANGELOG.md`, and merge to
   `main` with CI green.
2. Tag the merge commit and push the tag:

   ```bash
   git tag -a v2.1.0 -m "quantfinlib 2.1.0"
   git push origin v2.1.0
   ```

The `Release` workflow (`.github/workflows/release.yml`) then runs on JDK 25:

- It reads `project.version` with `./mvnw help:evaluate` and fails if the tag without
  its leading `v` differs from it. Nothing is published in that case; delete the tag,
  fix the version, and tag again.
- It runs `./mvnw verify` (tests and coverage check).
- It creates a GitHub release with generated notes and attaches the jars from
  `target/`: the library, `-examples`, `-sources` and `-javadoc`.

After a release, move the API compatibility baseline to it: set
`api.baseline.version` in `pom.xml` and the version passed to
`scripts/fetch-api-baseline.sh` in the `api-compat` job of `.github/workflows/ci.yml`
to the version just released. The gate currently compares against 2.1.0.

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
  <groupId>com.github.AshJha0</groupId>
  <artifactId>Quant-Finance-Library</artifactId>
  <version>v2.1.0</version>
</dependency>
```

JitPack must build with JDK 25. If its default JDK is older, the first request for a
tag fails in the enforcer check. A `jitpack.yml` at the repository root that selects
a JDK 25 build image fixes that; check JitPack's documentation for the current image
name.

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
