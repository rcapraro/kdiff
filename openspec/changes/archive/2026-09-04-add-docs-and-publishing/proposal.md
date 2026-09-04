## Why

kdiff has no README, no documentation, no license and no CI. The public API is thoroughly KDoc'd and
`kdiff-sample` exercises every capability end to end, so the material exists — it is just not
reachable by anyone arriving at a GitHub page.

Two of the gaps are worse than missing prose. Without a `LICENSE` the code is All Rights Reserved by
default, so nobody may legally use it whatever the README says. And with no `maven-publish`
configuration there are no coordinates at all, so an install section cannot be written truthfully.

This change makes the project consumable by someone who did not write it: documentation with samples
that cannot rot, real coordinates, a license, and a build that proves itself on every push.

## What Changes

- **New: `README.md`** — what kdiff is, a quickstart that gets someone diffing in under a minute,
  install instructions, and links onward. Kept short enough to actually be read.
- **New: `docs/` guides** — `diffing.md`, `patching.md`, `tracking.md`, `hand-written.md`,
  `annotations.md`, `architecture.md`. One page per thing a reader needs, with code samples
  throughout.
- **New: publishing configuration** — `maven-publish` on the three published modules
  (`kdiff-annotations`, `kdiff-runtime`, `kdiff-processor`), group `io.github.kdiff`, version
  `0.1.0`, targeting **GitHub Packages**. `kdiff-sample` is not published.
- **New: `LICENSE`** — MIT, with the licence named in each published POM.
- **New: `.github/workflows/ci.yml`** — `./gradlew check` on push and pull request, JDK 21.
- **New: `CONTRIBUTING.md`** — the OpenSpec propose/apply/archive workflow, the module dependency
  rules, and that `./gradlew check` gates every change.
- **New: README badges** — CI status, licence, Kotlin version.

**Modules affected:** `kdiff-annotations`, `kdiff-runtime` and `kdiff-processor` each gain publishing
configuration in their build script. `kdiff-sample` gains nothing but is the source of truth every
documentation sample is checked against.

**The generated API surface does not change.** No annotation is added, removed or altered, no
generated declaration changes shape, and no source file under any `src/main` changes behaviour. A
consumer who somehow already depended on these modules would see identical generated output.
**Annotation semantics are unchanged**: `@Diffable`, `@DiffKey`, `@DiffIgnore`, `@DiffWith`,
`@Trackable`, `@TrackIgnore` and `@TrackDepth` behave exactly as `diff-generation` specifies, and
existing annotated code compiles to the same thing afterwards. **Not BREAKING.**

### Samples are verified, not illustrated

Every code sample is derived from real code in `kdiff-sample`, which compiles through KSP and is
covered by `OrderDiffSpec`, `RoundTripSpec` and `OrderTrackingSpec`. A verification task traces each
fenced Kotlin block in the documentation back to the file it mirrors.

This is a deliberate constraint rather than extra ceremony: the tracking API changed twice in the two
preceding changes, and hand-written samples would already be wrong. It also needs no new tooling —
the sample module and its tests already exist and already run under `./gradlew check`.

### One caveat the docs must state prominently

GitHub Packages requires authentication for *every* consumer, including for public packages. An
install snippet that omits the repository block and a token will fail with a 401 that does not
obviously mean "you need a token". The install section therefore leads with that requirement rather
than burying it, and shows the `repositories` block in full.

### Assumptions

- **GitHub owner/repo is `rcapraro/kdiff`**, giving
  `https://maven.pkg.github.com/rcapraro/kdiff`. The working tree is not a git repository, so there
  is no remote to read this from; it is inferred from the local path and the account. A single
  find-and-replace fixes it if wrong.
- **Version `0.1.0`**, publishing configured but no release performed. Cutting an actual release —
  tagging, and a workflow to publish on tag — is deliberately left out; this change makes releasing
  possible, it does not do it.
- The docs are written for the current API. They are not versioned, and no compatibility table is
  introduced at `0.1.0`.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. This change declares skip_specs: true. -->

Documentation, licensing, CI and publishing configuration change no observable behaviour of the
library, so no spec changes and none is invented to satisfy validation. The one requirement that
touches publishing already exists and must be *preserved* rather than added: `diff-generation`
requires that a consumer's runtime classpath excludes the generator, so the published POM for
`kdiff-processor` must not place it on a consumer's runtime classpath. That is a verification task,
not a new requirement.

## Impact

- **New files:** `README.md`, `LICENSE`, `CONTRIBUTING.md`, `docs/*.md` (6 files),
  `.github/workflows/ci.yml`.
- **Modified:** `kdiff-annotations/build.gradle.kts`, `kdiff-runtime/build.gradle.kts`,
  `kdiff-processor/build.gradle.kts` (publishing blocks); root `build.gradle.kts` for the shared
  group/version; `kdiff-sample/build.gradle.kts` for the sample-verification test's repository-root
  property. `gradle/libs.versions.toml` is untouched — `maven-publish` ships with Gradle, so no
  dependency or plugin version is added.
- **No `src/main` file changes at all** — no production code is touched, so no library behaviour can
  move. One new test, `kdiff-sample/src/test/kotlin/demo/DocumentationSamplesSpec.kt`, checks the
  samples against their sources; `kdiff-sample/build.gradle.kts` passes it the repository root. The
  existing samples themselves are read, not rewritten.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
- `CLAUDE.md` already documents the architecture for agents; `docs/architecture.md` covers the same
  ground for humans and the two must not contradict each other.
