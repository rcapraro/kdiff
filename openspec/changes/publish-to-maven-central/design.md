## Context

See `proposal.md` — *Why*. What the build does today, from the root `build.gradle.kts` and
`.github/workflows/publish.yml`.

Publishing is `maven-publish` applied to the three modules named in `publishedModules`, with one
`MavenPublication` from `components["java"]`, a POM carrying name, description, URL, MIT licence, one
developer and SCM, and one repository, `GitHubPackages`, credentialled from `gpr.user`/`gpr.key` or
`GITHUB_ACTOR`/`GITHUB_TOKEN`. No `withSourcesJar()`, no javadoc jar, no signing. Dokka is applied to
the same three modules and `dokkaGenerate` works, but nothing packages its output.

The workflow triggers on `v*` tags, checks the tag against `version` in `build.gradle.kts`, runs
`./gradlew check`, runs `./gradlew publish`, then builds release notes from `CHANGELOG.md` with
`changelog-section.sh` and creates or updates the GitHub release. `workflow_dispatch` re-runs a tag.

`DocumentationSamplesSpec` scans every docs page for `io\.github\.kdiff:[\w-]+:<version>` and fails
when a literal version differs from the one the build publishes.

## Goals / Non-Goals

**Goals**

- A consumer with `mavenCentral()` and three coordinates is done. No repository block, no token.
- Sources and KDoc reach the IDE.
- The release path stays one tag, one workflow, one changelog section, with the tag-version check kept.
- Everything a release will produce can be inspected locally before any tag exists.

**Non-Goals**

- Publishing from anywhere but the workflow. A maintainer's machine never holds the signing key.
- Keeping GitHub Packages as a second target.

## Decisions

### D1 — One publishing plugin rather than hand-wired `maven-publish` + `signing` + upload

Central's Portal has no first-party Gradle plugin; publishing to it means producing a signed bundle in
the layout it expects and uploading it through its API. The `com.vanniktech.maven.publish` plugin does
all of it — sources jar, javadoc jar from Dokka, in-memory signing, POM, Portal upload and release — from
one configuration block, is the plugin most Kotlin libraries on Central use, and is maintained against
current Gradle and Dokka. The alternative, wiring `maven-publish`, `signing`, Dokka's javadoc packaging
and a Portal upload task by hand, is more build logic in a repository whose stated rule is to add build
logic only when it repeats.

Its version is not stated here. The rule against guessing a version-specific API applies to build
plugins too: the implementer reads the plugin's release page at apply time and records the current
version in `gradle/libs.versions.toml`, the one place versions live.

The configuration, in the `publishedModules` branch that already exists:

```kotlin
// illustrative — the plugin's own DSL names are confirmed against its documentation at apply time
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), project.name, version.toString())
    pom { /* the existing name, description, url, licence, developer and scm blocks, moved here */ }
}
```

with the javadoc jar configured to package the module's Dokka HTML output, which the existing Dokka
application already produces.

### D2 — The group id, and the one open question

Central verifies a namespace once, by proof of ownership. For `io.github.<name>` the proof is a public
repository in the `<name>` account or organisation, named by the Portal at verification time.

- If the maintainer owns a GitHub organisation named `kdiff`, `io.github.kdiff` is verifiable and
  nothing moves: the group id, the packages and every coordinate in the docs stay as they are.
- If not, the group id becomes `io.github.rcapraro`, which the maintainer's account verifies. Packages
  stay `io.github.kdiff.*`: Central has no opinion on package names, a group id and a package prefix
  differing is common, and renaming packages would be a source-breaking change for every consumer in
  exchange for symmetry.

The task list writes the group id as *the verified group id* and has one task to set it. The changelog
entry is **BREAKING** only in the second case, and says so in coordinate terms.

Recommendation: `io.github.rcapraro`. It is verifiable today with no new organisation to create and
keep, and the packages already carry the library's name.

### D3 — Sources and documentation jars

`withSourcesJar()` and a javadoc jar are what Central requires and what an IDE resolves. The plugin
produces both; the javadoc jar is Dokka HTML, which is what Kotlin libraries ship under that
classifier. `dokkaGenerate` stays out of `check`, as the `build-quality-gates` spec requires: the
publishing task depends on it, the aggregate check does not.

### D4 — Signing with an in-memory key from secrets

The plugin reads `signingInMemoryKey` and `signingInMemoryKeyPassword` as Gradle properties, which the
workflow supplies as `ORG_GRADLE_PROJECT_signingInMemoryKey` and
`ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` from repository secrets, alongside
`ORG_GRADLE_PROJECT_mavenCentralUsername` and `ORG_GRADLE_PROJECT_mavenCentralPassword` for the Portal
user token. The key is an ASCII-armoured private key generated for this purpose, its public half
published to a keyserver Central consults. Nothing is written to disk in the workflow, and a local build
without the properties skips signing so `publishToMavenLocal` works for inspection.

### D5 — GitHub Packages goes; the GitHub release stays

Two publishing targets mean two credential sets, two failure points in one workflow, and a README that
has to explain which to use. Central is the one consumers expect. The `GitHubPackages` repository block
and its credentials are deleted; the release step is unchanged and gains one line linking the release
to its Central coordinates.

A consumer who resolved from GitHub Packages before this change switches repository and, per D2,
possibly group id; both are in the changelog entry. Pre-1.0, with no compatibility promise, this is the
moment to do it.

### D6 — The workflow

`publish.yml` keeps its trigger, its tag-version check and its `./gradlew check`. The `publish` step
becomes the plugin's publish-and-release task with the four `ORG_GRADLE_PROJECT_*` environment
variables, and the release step follows as today. `workflow_dispatch` re-runs remain possible; the
Portal rejects a re-upload of an already released version, which is the right outcome — a version is
published once.

Central's sync to the public repository takes minutes; the workflow does not wait for it. A one-line
check in `CONTRIBUTING.md`'s release section says how to confirm resolution afterwards.

### D7 — Local verification before any tag

`./gradlew publishToMavenLocal` produces, for each published module, the jar, the sources jar, the
javadoc jar and the POM under `~/.m2`. The task list has the implementer inspect them: the sources jar
holds the module's Kotlin sources, the javadoc jar holds Dokka's `index.html`, and the POM carries the
name, description, URL, licence, developer and SCM entries Central validates. This is how the change is
verified without publishing anything.

### D8 — The docs coordinate check follows the group id

`DocumentationSamplesSpec`'s `COORDINATE` regex names `io.github.kdiff`. It becomes a match on the
verified group id, read from the same `kdiff.version`-style system property mechanism or from a constant
beside it, so a README that keeps an old coordinate fails the build exactly as an old version does.

## Risks / Trade-offs

**The namespace verification is a one-time manual step outside the repository** → documented in
`CONTRIBUTING.md`'s release section, with the fact that it happens once per group id and before the
first publish. The first tagged release after this change is the one that needs a maintainer watching.

**The signing key is lost** → artifacts already published stay valid; a new key is generated and its
public half published before the next release. Rotation is the same as first setup and is documented.

**A plugin DSL name in D1 is wrong** → the block is marked illustrative and the task says to confirm
against the plugin's documentation at apply time. Nothing here is asserted about the plugin beyond what
it is for.

**Consumers on GitHub Packages break** → pre-1.0, and stated in the changelog with the three lines to
change. There is no known consumer outside this repository's own modules.

**Central rejects the bundle for a POM field** → D7's local inspection checks the fields Central
validates, and the plugin validates them again before upload.

## Migration Plan

Release `N+1` is the first published to Central. Steps, in order, all recorded in `CONTRIBUTING.md`:
verify the namespace on the Portal; generate the signing key and publish its public half; add the four
secrets; merge this change; tag. Rollback is re-adding the GitHub Packages block, which `git revert`
does; artifacts already on Central are permanent by design and are not rolled back.

## Open Questions

- Does the maintainer own a GitHub organisation named `kdiff`? If yes the group id stays
  `io.github.kdiff`; if no it becomes `io.github.rcapraro` (D2). Either answer changes one task's
  value and the changelog wording, not the approach.

## Documentation

- `README.md` — *Install* loses the warning box and the repository block; the three coordinates sit
  under `mavenCentral()`; *Status* mentions Central.
- `CONTRIBUTING.md` — a *Releasing* section: the namespace verification, the signing key, the four
  secrets and how to rotate them, the tag, and how to confirm resolution afterwards.
- `CHANGELOG.md` under `[Unreleased]` — *Changed*: published to Maven Central with sources and
  documentation jars; GitHub Packages removed; **BREAKING** coordinate change if D2's second case
  applies, with the before-and-after block.
- `docs/faq.md` — *Will the code generator end up on my runtime classpath?* keeps its answer; the
  install pointer beside it no longer mentions a token.
