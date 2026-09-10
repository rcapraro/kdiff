## Why

A library is judged first from a stranger's build file, and kdiff's install story has two problems a
stranger meets before the first line of Kotlin:

```
  what they do                              what happens
  ----------------------------------------  ------------------------------------------------------
  copy the coordinates into build.gradle    401. GitHub Packages requires a token even for a public
                                            package; the README has to warn about it in bold.

  Cmd-click into Differ in the IDE          decompiled bytecode. No sources jar is published, so
                                            none of the KDoc that carries the library's design is
                                            reachable from the editor.
```

Both are publishing decisions, not library ones. Maven Central is public without credentials, and a
sources jar is one line of build configuration. The KDoc in `kdiff-runtime` is unusually thorough — it
is where the *why* of every helper lives — and today no consumer can read it without cloning the
repository.

## What Changes

**The three published modules are published to Maven Central**, through the Central Portal, from the
existing tag-triggered workflow. A consumer declares `mavenCentral()` — which every Gradle build already
does — and the three coordinates, and nothing else.

**Every published module ships a sources jar and a documentation jar.** The documentation jar is the
Dokka output the build already knows how to produce, packaged as the javadoc jar Central expects. The
IDE resolves both automatically, so *go to declaration* lands on the Kotlin source with its KDoc.

**Artifacts are signed**, as Central requires, with a key held in the repository's secrets and never on
disk in the workflow.

**BREAKING (coordinates) — the group id becomes one the maintainer can verify.** Central verifies the
namespace behind a group id, and `io.github.<name>` is verified by owning the GitHub account or
organisation `<name>`. `io.github.kdiff` is claimable only if a `kdiff` GitHub organisation is the
maintainer's; otherwise the group id becomes `io.github.rcapraro`. Package names do not change either
way: `io.github.kdiff.runtime` stays the import, because Central verifies group ids and not packages.
Which of the two applies is the one open question in `design.md`, and the task list is written to work
for either.

**GitHub Packages publishing is removed.** One repository, one credential set, one failure mode. The
GitHub *release* stays: it is where the changelog section becomes release notes, and it links to the
Central coordinates.

**The install snippet loses its warning box.** `README.md`'s *Install* section becomes the three
coordinates under `mavenCentral()`, and the spec that pins documented coordinates to the published
version is pointed at the new group id.

**Not in scope**

- Snapshot publishing. A release is a tag; pre-release builds are not published anywhere.
- A BOM or a Gradle plugin. Three coordinates sharing one version is what the changelog and the
  version-pinning spec already keep honest.
- Any change to what the modules contain. This is packaging and distribution only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `build-quality-gates`:
  - ADDED *Published artifacts are publicly resolvable and carry their sources and documentation* —
    what a release produces and where it can be resolved from, verifiable locally before any tag.

No other capability is touched: nothing about comparing, applying or tracking changes.

## Impact

- **Modules**: the root `build.gradle.kts` (publishing configuration for the three published modules),
  `gradle/libs.versions.toml` (one publishing plugin), `.github/workflows/publish.yml` (the publish
  step and its secrets), `kdiff-sample`'s `DocumentationSamplesSpec` (the coordinate regex), `README.md`,
  `CONTRIBUTING.md`, `CHANGELOG.md`. No Kotlin source in any published module.
- **Public API**: unchanged. The ABI dumps do not move.
- **Generated API surface**: unchanged.
- **Breaking for a consumer only in their build file**: three coordinates change group id (if it
  changes) and one repository block is deleted. Imports are untouched. Stated in `CHANGELOG.md` with
  the before and after.
- **Secrets**: four repository secrets — Central Portal user token and password, signing key and its
  passphrase — documented in `CONTRIBUTING.md`'s release section with how to rotate them. None appears
  in a file.
- **Dependencies**: one Gradle plugin for publishing, at the version current when the change is
  applied and recorded in the catalog. It is build-time only and reaches no consumer.
