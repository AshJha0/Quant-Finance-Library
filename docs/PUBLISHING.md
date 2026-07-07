# Publishing to Maven Central

The build is Central-ready: the POM carries the required `licenses`,
`developers`, `scm` and `url` metadata, sources + javadoc jars attach on
every build, and an inert `central-release` profile holds the signing and
upload plugins. What remains is one-time account setup (only the project
owner can do this), then a two-command release.

## One-time setup (project owner)

1. **Central Portal account** — sign in at
   <https://central.sonatype.com> with GitHub. Register the namespace
   `com.quantfinlib`: for a custom domain you verify DNS; the zero-friction
   alternative is the GitHub-backed namespace `io.github.ashjha0` (verified
   automatically) — if chosen, change `groupId`/package docs accordingly.
2. **Generate a user token** — Central Portal → Account → Generate User
   Token. Put it in `~/.m2/settings.xml`:

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

3. **GPG key** — Central requires signed artifacts:

   ```bash
   gpg --gen-key                                     # RSA, your name + email
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   ```

   The `maven-gpg-plugin` picks up the default key; set
   `<gpg.keyname>` / `<gpg.passphrase>` in settings.xml if you keep several.

## Releasing a version

```bash
mvn -P central-release clean deploy
```

That builds, tests, attaches sources/javadoc, signs everything, uploads the
bundle to the Central Portal and (with `autoPublish=true`) releases it.
Artifacts appear on <https://central.sonatype.com> immediately and sync to
Maven Central search within a few hours. Consumers then use:

```xml
<dependency>
  <groupId>com.quantfinlib</groupId>
  <artifactId>quant-finance-library</artifactId>
  <version>1.6.0</version>
</dependency>
```

## Automating in GitHub Actions (optional, after manual publishing works)

Add repository secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`; then extend `release.yml` with a job
that imports the key (`gpg --import`), writes the settings.xml servers
entry, and runs the same `mvn -P central-release deploy`. Keep it gated on
tags only — GitHub Releases with attached jars (the current mechanism)
continue to work regardless and remain the fallback distribution channel.

## Until then

Every `v*` tag already publishes runnable/sources/javadoc jars on GitHub
Releases — usable today by dropping the jar on a classpath or via JitPack
(`com.github.AshJha0:Quant-Finance-Library:v1.6.0`) with zero setup.
