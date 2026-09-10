## 1. Decide the group id

- [x] 1.1 Answer design D2's open question — whether a `kdiff` GitHub organisation is the maintainer's —
  and record the verified group id in `build.gradle.kts`. Verify by searching the repository for the old
  group id in coordinates (`io.github.kdiff:`) and confirming every hit is either updated or a package
  name, which does not change.

## 2. Build

- [x] 2.1 Read the publishing plugin's current release and record its version in
  `gradle/libs.versions.toml`; apply `com.vanniktech.maven.publish` to the `publishedModules` branch of
  the root `build.gradle.kts`, moving the existing POM block into its configuration and enabling Central
  publishing and signing of all publications (design D1, D3, D4). Verify `./gradlew help` configures
  cleanly and `./gradlew check` still passes.
- [x] 2.2 Configure the javadoc jar to package each module's Dokka HTML output, keeping `dokkaGenerate`
  out of `check` (design D3). Verify `./gradlew check` produces no documentation and
  `./gradlew publishToMavenLocal` does.
- [x] 2.3 Delete the `GitHubPackages` repository block and its credential properties (design D5).
  Verify no reference to `maven.pkg.github.com` or `gpr.` remains outside `CHANGELOG.md`.
- [x] 2.4 Run `./gradlew publishToMavenLocal` and inspect `~/.m2/repository/<group path>/` for each
  published module (design D7): the jar, `-sources.jar` holding the module's `.kt` files, `-javadoc.jar`
  holding Dokka's `index.html`, and the `.pom` carrying name, description, url, licence, developer and
  scm. Verify no artifact exists for `kdiff-sample`, `kdiff-tutorial` or `kdiff-benchmarks`.
- [x] 2.5 Update `DocumentationSamplesSpec`'s coordinate regex to the verified group id (design D8).
  Verify `./gradlew :kdiff-sample:test` fails when a docs page carries the old group id and passes once
  every page is updated in 4.1.

## 3. Workflow and secrets

- [x] 3.1 Replace the `publish` step in `.github/workflows/publish.yml` with the plugin's
  publish-and-release task, supplying `ORG_GRADLE_PROJECT_mavenCentralUsername`,
  `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey` and
  `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` from repository secrets (design D4, D6); keep the tag
  check, `./gradlew check` and the release step; add the Central coordinates to the release body.
  Verify the workflow file parses with `gh workflow view` or an action linter, and that `packages: write`
  is removed from its permissions.
- [x] 3.2 Outside the repository, once: verify the group id namespace on the Central Portal, generate
  the signing key pair, publish the public key to a keyserver Central consults, and add the four
  secrets. Verify by recording in `CONTRIBUTING.md` that each was done and how to rotate it — no secret
  value appears anywhere in the repository.

## 4. Documentation

- [x] 4.1 Rewrite `README.md` *Install*: remove the warning box and the repository block, show the three
  coordinates under `mavenCentral()`, and mention Central in *Status*. Verify `DocumentationSamplesSpec`
  passes with the new coordinates.
- [x] 4.2 Add a *Releasing* section to `CONTRIBUTING.md` covering the namespace verification, the
  signing key, the four secrets and their rotation, the tag, and how to confirm resolution from Central
  afterwards (design D6, *Migration Plan*). Verify it names no secret value.
- [x] 4.3 Add the `CHANGELOG.md` entry under `[Unreleased]`: published to Maven Central with sources and
  documentation jars, GitHub Packages removed, and — if the group id changed — a **BREAKING**
  coordinates note with the before-and-after block. Verify the entry reads for someone deciding whether
  to upgrade.
- [x] 4.4 Update `docs/faq.md`'s install pointer to drop the token mention. Verify the page's
  `<!-- from: -->` blocks are unaffected.

## 5. Done

- [x] 5.1 Run `./gradlew check` and verify it passes with no ABI dump change, since no published source
  moved.
- [x] 5.2 Tag the next release and watch the first Central publish through; confirm the three
  coordinates resolve from a scratch Gradle project declaring only `mavenCentral()`, and that the IDE
  shows KDoc on `Differ`. Record the outcome in the release's notes if anything needed a manual step.
