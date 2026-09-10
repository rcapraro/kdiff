## Context

Three of the four gates here defend a promise that already exists and is already written down. Nothing
about the library's behaviour changes; what changes is whether a break in it is noticed.

The constraint that shapes all of them is the one `build-quality-gates` already states:
**`./gradlew check` is the only command, and continuous integration runs nothing beyond it.** A gate
that lives in a release script, a CI step, or a maintainer's memory is not a gate. Everything below has
to be reachable from `check`, has to be hermetic enough to run on a contributor's machine, and has to
be worth what it costs in wall-clock time — because `check` is run on every commit.

## Goals / Non-Goals

**Goals**

- Compile and run a consumer against the *published artefacts*, from `check`.
- Make a reworded diagnostic fail `check` rather than leave `docs/errors.md` wrong.
- Make the incremental-regeneration requirement checkable, in both directions: what is regenerated and
  what is not.
- Run the one command somewhere other than one Ubuntu image on one JDK.

**Non-Goals**

- Testing against Kotlin versions other than the declared one. Separate change, separate problems.
- Publishing anything from `check`, to `~/.m2` or anywhere else.
- Making `check` fast. It gets slower; the design's job is to bound by how much.

## Decisions

### D1 — A new module, `kdiff-integration`

The consumer build and the incremental test are both "run a real Gradle build and look at what it did".
Neither belongs in `kdiff-sample`, which is a consumer *of the project* — its
`implementation(project(":kdiff-runtime"))` never reads a descriptor, which is exactly the gap.

```
  kdiff-sample                        kdiff-integration
  ---------------------------------   ---------------------------------------------
  consumes via project(...)           consumes via io.github.rcapraro:kdiff-*:<v>
  compiled by this build              compiled by a second Gradle build, in TestKit
  proves the processor works          proves the artefacts work
  fast; runs on every commit          slower; also runs on every commit -- see D6
```

**Chosen**: a new module, not published, not on any consumer's path — the same standing
`kdiff-benchmarks` has. `settings.gradle.kts` includes it; the root build's `publishedModules` set does
not, so it gets no `explicitApi()`, no ABI dump, no Dokka and no publication.

`CLAUDE.md`'s module-graph section gains a line for it, because that section is load-bearing and a
module absent from it reads as an accident.

### D2 — The artefacts come from a repository inside `build/`

TestKit needs the three modules to exist as real artefacts. `publishToMavenLocal` would do it and is
what `CONTRIBUTING.md` tells a maintainer to run by hand — but making `check` write to `~/.m2` means
every contributor's local repository grows a kdiff on every build, and a stale one there would then
shadow what the next build produces.

**Chosen**: each published module gains a Maven repository whose location is a directory under the root
build directory, and `kdiff-integration`'s test task depends on publishing to it. The consumer build's
settings declare that directory plus `mavenCentral()`, in that order.

**Rejected — `publishToMavenLocal`.** Writes outside `build/`, and its output outlives a `clean`.

**Rejected — a composite build with `includeBuild`.** Gradle substitutes project dependencies for the
coordinates, which is precisely the substitution this gate exists to avoid making.

Signing stays off: the root build already signs only when `signingInMemoryKey` is present, so a local
publication is unsigned and resolves fine.

### D3 — What the consumer build proves

The TestKit project is deliberately the smallest thing that is still a consumer:

```
  settings.gradle.kts   pluginManagement { gradlePluginPortal() }
                        repositories { maven(<root>/build/repo); mavenCentral() }

  build.gradle.kts      kotlin("jvm"); id("com.google.devtools.ksp")
                        implementation("io.github.rcapraro:kdiff-annotations:<version>")
                        implementation("io.github.rcapraro:kdiff-runtime:<version>")
                        ksp("io.github.rcapraro:kdiff-processor:<version>")

  src/main/kotlin       one @Diffable data class with a @DiffKey list of a nested @Diffable
  src/test/kotlin       diff two instances, assert the changes; apply the diff, assert the value
```

Four things become checkable that are not checkable today:

1. **Three coordinates are sufficient.** Nothing else is declared, so a descriptor that omits a needed
   dependency fails to resolve or to compile.
2. **The processor works as an artefact.** Its service registration is read from a jar, not from a
   project's resources directory.
3. **Nothing leaks onto the consumer's compile classpath.** The test asserts that the resolved compile
   classpath carries the annotations and runtime and carries neither KotlinPoet nor the
   symbol-processing API. `kdiff-processor` declares both as `implementation`, which puts them in its
   descriptor's runtime scope — harmless on the `ksp` configuration and worth being sure of.
4. **The generated code runs.** Diffing and applying, not merely compiling.

The version is passed in as a system property, as `kdiff-sample` already passes `kdiff.version`, so the
consumer's coordinates cannot go stale.

### D4 — The incremental test uses the same harness, twice

The requirement `diff-generation` already states — *a build that reuses previous output reports what a
build from nothing reports* — is untestable in kctfork, which compiles once. It needs two builds with an
edit between them, which is what TestKit gives.

**Chosen**: three scenarios, all on the same consumer project:

| edit between the builds | asserted |
|---|---|
| an inline `value class` redeclared as an ordinary class | the second build fails with the same diagnostic a clean build gives |
| an annotated class edited | its generated file is rewritten |
| the same edit | an unrelated annotated class's generated file is byte-identical to the first build's |

The third is the half the specification did not state and that `docs/architecture.md` claims: the
non-aggregating dependency declaration is what makes it true, and nothing checks it.

**Rejected — asserting on Gradle task outcomes** (`UP_TO_DATE`, `FROM_CACHE`). Those describe how
Gradle decided, and would break on an unrelated caching change. The assertion is on the generated files,
which is what the requirement is about.

### D5 — `errors.md` is checked by literal fragments, after joining concatenated literals

The obvious check — does this quoted string appear in that source file — fails immediately, because
every message long enough to matter is split to fit the column limit:

```
  errors.md quotes            kdiff cannot compare elements of <prop> of type <T>; annotate
                              that type with @Diffable or @DiffAsValue, or point the property
                              at a hand-written differ with @DiffWith

  DiffProcessor.kt holds      "kdiff cannot compare elements of ${...} of type " +
                              "${...}; annotate that type with " +
                              "@Diffable or @DiffAsValue, or point the property at a hand-written
                               differ with @DiffWith"
```

**Chosen**, in three steps:

1. **Join the source.** Collapse every `" + "` join between two string literals — quote, whitespace,
   `+`, whitespace, quote — so adjacent literals become one contiguous run of text.
2. **Cut every source message into its literal runs.** Extract each string literal from the joined
   text and split it at its own interpolations, `${…}` and `$name` alike. What is left is the part of
   the message the code states literally.
3. **A quoted message is verified when some source message's runs all appear in it, in order.**
   Only runs of substance count — the same threshold `DocumentationSamplesSpec` uses for a distinctive
   line, and for the same reason: three words carry the message, one carries nothing and would make the
   check brittle.

The failure names the page, the quoted message, and the run of the closest source message that did not
appear in it.

**Amended during implementation. The original had this backwards** — split the *quoted* message at its
`<…>` placeholders, and require each fragment to appear in the joined source — and it produced six
failures on a correct page and no true findings at all. Joining the concatenations worked exactly as
designed. What was missed is that a page expands more than placeholders:

```
  the page quotes                          the source states
  ---------------------------------------  ------------------------------------------
  <Type> is an object, and an object in    is ${describeKind()}${objectHint()}
  a @Diffable sealed hierarchy…            a helper's return value

  its elements are nullable  /             its $part are nullable
  its values are nullable                  one message, two renderings

  …or UNLIMITED_DEPTH (-1)                 …or UNLIMITED_DEPTH ($UNLIMITED_DEPTH)
  after 512 steps: …                       after $MAX_DESCENT steps: …
                                           a constant's value
```

A placeholder is only the *page's* name for a variable part. Matching from the page inward asks the
source to contain text the source never states, and a gate that fails a correct page fails `check` on a
green repository. Reversing it compares only what both sides state literally, and every case above
passes for the right reason while a reworded message still fails.

**The constraint this creates, stated rather than discovered**: a diagnostic is one string expression
built from adjacent literals and interpolations. A message assembled with `buildString`, `trimIndent`,
or a `when` returning halves would defeat the join and fail the check for the wrong reason. That is a
reasonable rule for diagnostics to follow and it goes in `CONTRIBUTING.md` beside the rule it replaces.

**Rejected — an inventory written by the tests.** Having every spec that asserts a diagnostic record it,
and comparing that inventory to the page, is more robust and checks the messages *as emitted*. It also
requires that every message be asserted somewhere, which `CONTRIBUTING.md` says several are not — so it
would fail on the messages the check most needs to cover. Worth revisiting if the fragment matching
proves noisy.

**Home**: a spec beside `DocumentationSamplesSpec` in `kdiff-sample`, which already resolves the
repository root, already declares the documentation tree as an input, and already declares
`kdiff-runtime/src/main` as one. `kdiff-processor/src/main` joins the input list — the sources are read
as text, so no dependency is added.

### D6 — What this costs, and the bound on it

A TestKit build is a second Gradle invocation: configuration, dependency resolution, Kotlin compilation
and KSP, for a project of two files. On a warm dependency cache that is seconds, not minutes, but it is
the largest single addition `check` has taken.

**Chosen bounds**:

- One TestKit project, configured once, shared by the consumer spec and the incremental spec — the
  incremental scenarios need a second build of the same project anyway.
- TestKit is pointed at the build's own Gradle user home so the dependency cache is shared with the
  outer build rather than populated from scratch.
- The Kotlin and KSP plugin versions the consumer applies come from the version catalog, so the second
  build resolves exactly the plugins the outer build already has.

If it proves too slow to live in `check`, the honest response is to say so in `build-quality-gates`
rather than to move it to a CI step, because a gate CI runs and `check` does not is the thing that
requirement exists to prevent.

### D7 — No CI matrix. Rejected after implementing it

A matrix over `{ubuntu, windows} × JDK {21, 25}` was proposed, written, and then removed. The reasoning
for it was that the build filters generated sources on `File.separator` and the documentation specs
resolve a repository root from a system property, and neither had ever run where a path separator is a
backslash. That is true, and it is not a reason.

**It tests the build, not the library.** kdiff ships jars. Nothing in the annotations, the runtime or
the generated code is OS-dependent — a consumer on Windows runs the same bytecode, and their JVM
decides that, not this repository's CI. A red Windows job would mean *a contributor cannot run
`./gradlew check` on Windows*, which is a real claim but a much smaller one, and it is a claim about a
contributor who does not exist: the maintainer is on macOS, CI is on Ubuntu, and no Windows contributor
appears anywhere in the history.

The cost is not nominal either. TestKit spawns a second Gradle build, which is worst on Windows — the
slowest square of the matrix, on every push — and the standing obligation is to fix Windows-specific
path bugs for that same absent contributor.

The JDK row is weaker still. `jvmToolchain(21)` means every job compiles for 21 whatever runs Gradle,
so it tests Gradle and the Kotlin plugin on a newer runtime rather than anything kdiff produces: a
modest early warning, and the row most likely to go red for reasons unrelated to this repository.

**Chosen**: CI stays one job, `ubuntu-latest` at JDK 21, running `./gradlew check` and nothing else. The
three gates this change adds hang off that command, so they run there without CI changing at all.

What survives from the idea is worth stating: **the build is tested on one operating system only**, and
macOS — the one the maintainer actually uses — is not that one. It is covered by `check` being run
locally before anything is pushed, which is a person rather than a gate. If a contributor on another
platform ever appears, this is the decision to reopen.

### D8 — The documented plugin versions join the coordinate check

`README.md`'s install block hand-maintains two versions the coordinate check does not look at:

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"                       // libs.versions.kotlin
    id("com.google.devtools.ksp") version "2.3.11"       // libs.versions.ksp
}
```

The rationale already written for the coordinate check applies unchanged — *"a coordinate is the first
thing a reader copies"* — and these sit in the same fenced block. `kdiff-sample`'s test task gains
`kdiff.kotlinVersion` and `kdiff.kspVersion` alongside the two properties it already passes, and the
spec matches the two plugin declarations wherever they appear on a page.

A version written as a variable is skipped, exactly as a coordinate's is.

## Risks / Trade-offs

**`check` gets slower for every contributor** → Bounded by D6, and the alternative is a gate that runs
only in CI, which `build-quality-gates` forbids for good reasons. Measure it on the first green build
and record the figure in `CONTRIBUTING.md`; if it is worse than expected, that is a conversation with a
number in it.

**The build is exercised on one operating system only** → Accepted, and it is the position the
repository was already in; D7 says why adding another was rejected rather than left implicit.

**The fragment check can pass on a message that is wrong in a way no fragment captures** → True, and it
is a floor rather than a proof: it catches the rewording, which is the failure `CONTRIBUTING.md`
describes. The stronger check is D5's rejected alternative, and the note saying so stays in the design
so the upgrade path is written down.

**A file repository under `build/` is not a Maven Central** → It exercises descriptors, coordinates and
resolution, which is where the failures live. It does not exercise signing, the Portal's validation, or
Central's own resolution — those are exercised by the release path itself, once per release, and the tag
check already refuses a mismatched version before publishing anything.

**A new module for two specs** → The alternative is putting artefact-level tests in the module whose
whole point is that it is not artefact-level. The module is small, unpublished, and carries its
justification in `CLAUDE.md` beside the others.

## Migration Plan

Nothing to migrate: no published behaviour changes and no artefact moves. For a contributor, `check`
grows two tasks and takes longer; `CONTRIBUTING.md` says so, says how to run each new gate on its own,
and loses the paragraph admitting the `errors.md` hole.

Release: one minor version, no breaking change. The changelog entry describes what is now checked, since
that is what a reader deciding whether to upgrade would want to know about a release that changes no
behaviour.

## Open Questions

Both are settled.

- **Which JDK the extra CI row tracks** — moot: there is no extra row. See D7.
- **Whether the consumer spec asserts the compile classpath by resolving a configuration or by
  compiling against it** — the second. A source file naming `FileSpec` fails with an unresolved
  reference, which is a shorter statement than reading Gradle's resolution result and does not couple
  the test to Gradle's API.
