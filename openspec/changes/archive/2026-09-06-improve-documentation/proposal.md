## Why

The documentation shipped whole in `add-docs-and-publishing` and has drifted in the two releases
since. The README still tells a new consumer to depend on `0.1.0` while the build publishes `0.2.0`,
still says "`0.1.0`, released" under Status, and never mentions `under()` — the entire content of the
0.2.0 release. `docs/architecture.md` says "Four modules" and draws four; there are five.

None of that was caught, and the reason is structural rather than careless. `DocumentationSamplesSpec`
verifies a fenced Kotlin block only when it carries a `<!-- from: -->` marker, and every line a new
reader copies first — the whole install section, the whole quickstart — is marked `illustrative` and
therefore checked against nothing. The previous change anticipated this and left task 4.3 as a manual
instruction to track where the version string appears. It drifted at the first release anyway, which
is the evidence that a checklist item is the wrong instrument.

Beyond the drift, the pages are strong prose on weak scaffolding. Seven guides carry one diagram
between them, six of the seven end with no way onward, and the concepts that most need a picture —
the depth rule, `under` dispatch, the path model — are carried entirely by paragraphs and tables. The
tutorial states its thesis in its second paragraph and never draws it. And the set says nothing at all
about what kdiff costs or what it deliberately does not do, so a reader deciding whether to adopt it
has to infer both.

## What Changes

- **Accuracy.** Correct every statement that is now false: the `0.1.0` coordinates and Status line in
  `README.md`; "Four modules" and the four-module diagram in `docs/architecture.md`; the README's
  claim that `@Trackable` is what makes its tracking snippet work, when the `Address` it shows carries
  no such annotation and the snippet works because a tracker naming no property tracks everything.
- **Currency.** Add `under()` to the README's routing example, so the feature the last release was
  about is visible from the front page.
- **A guard rail that replaces the checklist.** `kdiff-sample` passes its tests the project version,
  and `DocumentationSamplesSpec` asserts every `io.github.kdiff:<module>:<version>` coordinate in the
  documentation matches it. The Status section stops repeating the version so exactly one place
  states it.
- **Diagrams.** Six ASCII diagrams, on the pages whose concepts are currently carried by prose alone:
  the three capabilities, the change-and-path model, `under` frame dispatch, depth counting, the
  corrected module graph, and the tutorial's command-to-events flow.
- **Navigation.** A "Where to go next" footer on all seven pages, matching the one `tutorial.md`
  already has, and a one-line orientation at the top of the three guides that currently open on an
  API block.
- **Completeness.** A "What kdiff does not do" section in `docs/architecture.md`; a "What a
  comparison costs" section stating that every comparison is linear and that there is no edit-distance
  matching; `DiffNode`/`tree()` given a worked example instead of one paragraph; and the general rules
  currently reachable only from the tutorial — a swap reporting two moves, and what a duplicate key in
  a keyed list does — moved to the reference page that owns the behaviour.
- **Tutorial.** The flow diagram up front, and the page saying in its opening where the annotated
  mirror is, for a reader who came for annotations and would otherwise read 300 lines first.

**Modules affected:** `kdiff-sample` only, and only its test source set — `DocumentationSamplesSpec`
gains the version assertion and `kdiff-sample/build.gradle.kts` gains one `systemProperty` line
alongside the `kdiff.repoRoot` it already passes. No file under any `src/main` in any module is
touched.

**The generated API surface does not change.** No annotation is added, removed or altered; no
generated declaration changes shape; no runtime type gains or loses a member. A consumer recompiling
against this change gets byte-identical generated output. **Annotation semantics are unchanged** —
`@Diffable`, `@DiffKey`, `@DiffIgnore`, `@DiffWith`, `@Trackable`, `@TrackIgnore` and `@TrackDepth`
behave exactly as `diff-generation` specifies, and existing annotated code compiles to the same thing
afterwards. **Not BREAKING.**

### Explicitly out of scope

- **A comparison with other libraries.** Considered and dropped at the user's direction. No page
  names, cites or benchmarks JaVers or any other diff library.
- **A published API reference (Dokka).** The KDoc in `kdiff-runtime` is thorough and nothing renders
  it, which is a real gap — but closing it means a new plugin, a new Gradle task and a change to
  `.github/workflows/publish.yml`. Build and publishing changes are their own change under this
  project's rules, so it stays out.
- **A documentation site, or `docs/index.md`.** GitHub renders `docs/` directly and the README's table
  is the index. Adding an entry point would mean two indexes to keep in step.
- **New prose about behaviour that does not exist.** Where a documented rule turns out to be wrong,
  the fix is the documentation. Nothing in this change alters library behaviour to match a page.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. This change declares skip_specs: true. -->

Documentation is not behaviour. `diff-generation`, `diff-application` and `change-tracking` describe
what the library does, and this change alters none of it — it corrects, illustrates and extends the
prose describing it, and adds one assertion to an existing test. No requirement changes and none is
invented to satisfy validation.

One existing requirement is *relied upon* rather than modified: `diff-generation` requires the
consumer's runtime classpath to exclude the generator, which is why the README's install snippet puts
`kdiff-processor` on the `ksp` configuration. The version guard rail must not weaken that snippet
while rewriting its coordinates.

## Impact

- **Modified documentation:** `README.md`, `docs/architecture.md`, `docs/diffing.md`,
  `docs/tracking.md`, `docs/patching.md`, `docs/hand-written.md`, `docs/annotations.md`,
  `docs/tutorial.md`. No documentation file is added or removed.
- **Modified source:** `kdiff-sample/src/test/kotlin/demo/DocumentationSamplesSpec.kt` (one new test),
  `kdiff-sample/build.gradle.kts` (one `systemProperty`). Nothing else.
- **Unchanged:** every `src/main` in every module; `gradle/libs.versions.toml`; the root
  `build.gradle.kts`; both GitHub workflows; `CHANGELOG.md`, which describes releases and gains
  nothing for a documentation pass.
- **`CLAUDE.md` and `CONTRIBUTING.md` must stay true.** `CONTRIBUTING.md` documents the `from:` /
  `illustrative` marker convention that this change extends, so the new version assertion has to be
  described there. `CLAUDE.md` states the module graph that `docs/architecture.md` is being corrected
  to match — the two must agree afterwards, and `docs/architecture.md` explains while `CLAUDE.md`
  states.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
