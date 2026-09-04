## Context

See `proposal.md — Why` for motivation. This change declares `skip_specs: true`, so there is no delta
spec to satisfy; the constraint it must not break is an existing one, quoted in D2.

A design is warranted here despite the absence of code: the change spans four build scripts, has to
keep a published POM honest about a spec'd classpath guarantee, introduces a credentials story, and
needs a decision on how samples are prevented from rotting. What it does *not* have is anything the
schema's design rules ask about — no type resolution, no incremental processing, no generated file,
nothing moving between `kdiff-runtime` and generated code. Those four rules are recorded as
inapplicable rather than answered with invented content.

Current state that shapes the approach:

- `build.gradle.kts` at the root applies the Kotlin JVM plugin to every subproject and calls
  `explicitApi()` for the three in `publishedModules`. That set already exists and is the natural
  place to hang publishing.
- `kdiff-processor` declares `implementation(project(":kdiff-annotations"))` and
  `testImplementation(project(":kdiff-runtime"))`. Project dependencies only become resolvable
  coordinates once every project has a group and version.
- `kdiff-sample` applies the KSP plugin and consumes the processor through `ksp(...)`. It is the only
  place in the repo where the library is used the way a consumer uses it, which is what makes it
  usable as the sample source of truth.
- Kotest runs on the JUnit platform in every module; `kdiff-sample` already has the two Kotest
  dependencies.
- `CLAUDE.md` documents the architecture for agents. `docs/architecture.md` will cover the same
  ground for humans, and the two must not drift apart.

## Goals / Non-Goals

**Goals:**

- A reader arriving at the GitHub page can understand what kdiff is, decide whether they want it, and
  have it working, without reading source.
- Every Kotlin sample in the documentation is traceable to compiled, tested code, and a failing build
  is what happens when one drifts.
- The published POMs are honest — in particular about not putting the processor on a consumer's
  runtime classpath.

**Non-Goals:**

- **Cutting a release.** No tag, no publish workflow, no Sonatype or GitHub Packages credentials
  committed. This change makes releasing possible.
- **A Dokka API reference or a Pages site.** The KDoc is already thorough; rendering it is a separate
  concern with its own dependency and workflow.
- **Versioned or translated docs**, and no compatibility matrix at `0.1.0`.
- **Rewriting `CLAUDE.md`.** It stays the agent-facing file; `docs/` is the human-facing one.

## Decisions

### D1. Publishing hangs off the existing `publishedModules` set, not each build script

The root `build.gradle.kts` already knows which three modules are published. Group and version go
there, and the `maven-publish` plugin plus the `java` component registration are applied inside the
same `if (project.name in publishedModules)` branch that already calls `explicitApi()`.

```kotlin
group = "io.github.kdiff"
version = "0.1.0"

subprojects {
    // ... existing
    if (project.name in publishedModules) {
        explicitApi()
        apply(plugin = "maven-publish")
        // publication registering the java component, plus POM metadata
    }
}
```

Why: one list, one place, and adding a fourth published module later is a one-line edit. The
alternative — a publishing block in each of the three module scripts — triples the POM metadata
(licence, developer, SCM) with nothing gained, and lets the three drift.

`maven-publish` ships with Gradle, so `gradle/libs.versions.toml` is untouched and no plugin or
dependency version enters the project. That keeps this change clear of the project's rule against
version moves inside a feature change.

### D2. The processor must publish as a tool, not a library — this preserves a spec'd guarantee

`diff-generation` requires that a consumer's runtime classpath contains "nothing that performs
generation". Publishing is the first point at which that guarantee could be broken by accident, so
it gets a verification task of its own rather than being assumed.

Two separate things have to hold:

1. **The consumer's declaration.** `ksp("io.github.kdiff:kdiff-processor:0.1.0")` puts the processor
   on the KSP compile-time configuration only. The documentation must never show it as
   `implementation`, because that single word is what would violate the requirement. This is why the
   install snippet is one of the samples that gets checked, not prose.
2. **The processor's own POM.** Its `implementation(project(":kdiff-annotations"))` becomes a runtime
   dependency of `kdiff-processor` — which is correct and harmless, because the processor itself never
   reaches a consumer's runtime classpath.

The verification task inspects the generated POMs (`./gradlew publishToMavenLocal`, then read them)
rather than trusting the configuration. A POM is the artefact a consumer actually resolves.

`kdiff-annotations` and `kdiff-runtime` are ordinary `implementation` dependencies for a consumer:
annotations are `BINARY` retention and the runtime carries the result types the generated code
references.

### D3. Samples are anchored to `kdiff-sample` by an explicit source marker

Each fenced Kotlin block in the documentation that claims to be real code is preceded by an HTML
comment naming the file it mirrors:

```markdown
<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@Diffable
@Trackable(depth = 1)
data class Order(
    val reference: String,
    ...
)
```
```

A Kotest spec walks `README.md` and `docs/*.md`, and for every marked block asserts that the named
file exists and that the block's **distinctive lines** appear in it. Matching distinctive lines
rather than whole blocks is deliberate: a sample legitimately elides properties with `...` and
reflows for width, and a whole-block comparison would fail on formatting while catching nothing real.
What must not drift is the API surface — annotation names, function names, signatures — and those are
exactly the distinctive lines.

An unmarked block is allowed and means "illustrative, not from source": consumer-side snippets like
the `settings.gradle.kts` install block have no file in this repo to mirror. The spec asserts that
unmarked Kotlin blocks are confined to the pages where that is expected, so the escape hatch cannot
quietly spread.

*Alternative rejected:* mechanically splicing tagged regions out of source into the markdown at build
time. It makes drift impossible rather than merely detected, but introduces a docs toolchain, a
generated-file-in-git question, and a build step that must run before the markdown is readable on
GitHub. Detection is enough when the build fails on it.

*Alternative rejected:* a custom Gradle verification task instead of a test. It would keep
`src/test` untouched, but the project's convention is Kotest specs and its definition of done is
`./gradlew check`; a spec in `kdiff-sample/src/test` inherits both for free.

### D4. The verification spec lives in `kdiff-sample`, and reads the repo root through a property

`kdiff-sample/src/test/kotlin/demo/DocumentationSamplesSpec.kt`. That module already has Kotest, and
it is the module the samples come from, so the spec sits next to its subject.

It needs the repository root, which is not the test's working directory. Rather than
`File("..")` — which silently depends on Gradle's working-directory behaviour — the module's build
script passes it explicitly:

```kotlin
tasks.test {
    systemProperty("kdiff.repoRoot", rootProject.projectDir.absolutePath)
}
```

Why: an absolute path from Gradle is unambiguous and survives being run from an IDE, where the
working directory is frequently not what Gradle would have used.

### D5. Ordering: license first, then publishing, then docs, then CI, then badges

Not arbitrary. The licence is a legal precondition for the repo being usable at all and blocks
nothing, so it goes first. Publishing has to exist before the install section can be written from
real coordinates rather than guessed ones. The docs come next. CI follows, because the build badge
in the README is a broken image until the workflow file exists at the path the badge points to —
badges are therefore last, after the workflow they reference.

### D6. The GitHub Packages authentication caveat leads the install section

GitHub Packages requires a token for every consumer, including for public packages. A reader who
copies a bare `implementation("io.github.kdiff:kdiff-runtime:0.1.0")` gets a 401 that does not
obviously mean "add a token", and the natural conclusion is that the library is broken.

So the install section opens with the requirement, shows the `repositories` block in full including
`credentials`, and names the token scope (`read:packages`). It also shows reading the token from an
environment variable or `gradle.properties` rather than inlining it, because the first thing a reader
does is paste the snippet somewhere.

### D7. `docs/architecture.md` is the human view; `CLAUDE.md` stays the agent view

They overlap heavily — module graph, the "does it construct?" axis, the closed `Change` vocabulary,
the depth rule. Duplicating prose across two files that must agree is how they come to disagree.

The split: `docs/architecture.md` explains *why the library is shaped this way* for someone deciding
whether to use or extend it, with diagrams and samples. `CLAUDE.md` stays a terse operating manual —
commands, invariants, the things an agent will otherwise get wrong. Where a fact belongs in both (the
`UNLIMITED_DEPTH` duplication, `aggregating = false`), the docs page carries the explanation and
`CLAUDE.md` carries the one-line rule. A task checks the two do not contradict each other.

### Schema design rules recorded as inapplicable

The schema asks a design to cover four things. None has a subject in this change, and answering them
would mean inventing content:

- *Type resolution (nullability, generics, collections, maps, enums, nested `@Diffable`)* — no
  resolution logic is touched. The documentation *describes* the existing rules; `docs/diffing.md`
  covers each case with a sample, which is documentation of behaviour, not a design decision.
- *Incremental processing and whether a generated file is aggregating* — no file is generated and
  `DiffProcessor` is not modified. Every generated file keeps
  `Dependencies(aggregating = false, originatingFile)` because nothing in this change goes near it.
- *A sample of the generated file for this change* — nothing is generated. The nearest equivalent is
  the generated POM, and D2 makes reading it a verification task.
- *What lives in `kdiff-runtime` versus what is generated* — unchanged; no code moves.

## Risks / Trade-offs

- **The GitHub owner is assumed to be `rcapraro`** (the tree is not a git repository, so there is no
  remote to read) → it appears in the Packages URL, the CI badge and the POM SCM block. A task
  isolates it so a single find-and-replace corrects it, and the assumption is stated in the proposal
  rather than buried.
- **Distinctive-line matching can pass a sample that is subtly wrong** — a renamed parameter inside an
  unmatched line would slip through → the matched lines are chosen to be the API surface itself
  (annotation, function and type names), which is what a reader copies. Whole-block matching was
  rejected in D3 for failing on formatting instead.
- **Publishing configuration that is never exercised rots** → `publishToMavenLocal` runs as part of
  the POM verification task, so the configuration is executed by `check` even though nothing is
  released.
- **Version `0.1.0` hardcoded in docs** → it appears in the install snippets, which are unmarked
  illustrative blocks and therefore not checked against source. A task confines the version string to
  as few places as possible and lists them, so the next release knows what to update.
- **`docs/` and `CLAUDE.md` drifting** → D7 splits them by audience rather than by topic, and a task
  checks for contradiction. This is mitigation, not prevention; two files about one architecture is an
  accepted cost of having both a human and an agent audience.

## Migration Plan

None required. Nothing is released, so no consumer exists to migrate. Adding a group and version to
projects that had neither changes no compiled output — verified by `./gradlew check` continuing to
pass and the sample's generated sources being unchanged. Rollback is deleting the new files and
reverting four build scripts.
