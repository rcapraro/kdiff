## Why

The documentation is 2,450 lines of accurate, well-illustrated prose, and every page of it is
organised by *library concept* — diffing, patching, tracking, annotations, architecture. Nothing is
organised by *reader's task*. A reader who arrives knowing what they want to accomplish, or holding an
error message, has no page to land on: the answer exists, in a paragraph, on a page named after the
subsystem that happens to own it.

Two consequences are measurable rather than a matter of taste.

**`PatchFailure.Reason` is documented at one fifth of its size.** `docs/patching.md` hands the reader
an exhaustive `when` over the vocabulary and calls it part of the compatibility surface — then names
three of its fourteen cases. `UnknownProperty`, `NotApplicableToValue`, `NotApplicableToKeyedList`,
`NotApplicableToPositionalList`, `NotApplicableToMap`, `NothingBeneathNull`, `NoElementAtIndex`,
`NoEntryForKey`, `ElementComparedAsValue`, `EntryComparedAsValue` and `SetElementNotModifiable` appear
nowhere in `docs/` or `README.md`. A caller told to branch on a closed vocabulary can see a fifth of
it.

**No error message in this library is greppable from its own documentation.** The thirteen compile-time
diagnostics are tabulated in `docs/annotations.md` as paraphrases of what they say — "names the
property and its type, points at the escape hatch" — not as the text Gradle prints. A reader who sees
`kdiff cannot compare elements of tags of type java.time.Instant` and searches the documentation for
it finds nothing. The rest of the error surface is scattered: two `IllegalArgumentException`s from DSL
validation split across `hand-written.md` and `tracking.md`, three declared exception types across
`patching.md`, `diffing.md` and `architecture.md`.

Both gaps are of a kind: the library's behaviour is documented, and the reader's *situation* is not.

## What Changes

- **A how-to layer.** `docs/how-to.md`: recipes named for what the reader is trying to do, not for the
  subsystem involved — emit domain events from a diff, audit-log a change, compare a type you do not
  own, make a `@DiffWith` property patchable, compare a value by something other than `equals`, fire a
  callback only for one property, require a patch to be total, tell a move from an add-plus-remove.
- **Every recipe is a passing test.** A new `kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt`, one
  test per recipe, cited by `<!-- from: -->` so `DocumentationSamplesSpec` checks it. A recipes page
  written as `illustrative` blocks would re-open exactly the hole the previous documentation change
  was written to close.
- **An error index.** `docs/errors.md`, spanning the three tiers the reader actually meets them in —
  compile time, differ/scope construction, runtime — carrying the **verbatim message text** so the
  page is reachable by searching for what went wrong. The fourteen `PatchFailure.Reason` cases are
  documented in full there, grouped by the five causes they fall into rather than listed as fourteen
  independent facts, because the grouping is what tells a reader what to *do*.
- **A FAQ.** `docs/faq.md`, held to a bar: a question earns a slot only when its answer is not already
  one click away. Why a property is not compared; why an unkeyed list reported an addition and a
  removal rather than a move; whether `kotlin-reflect` is needed; whether a `Tracker` is thread-safe;
  Java, Android and multiplatform; whether a diff can be serialised.
- **One index, moved.** `docs/README.md` becomes the single documentation index, rendered by GitHub at
  `docs/`. `README.md` drops its seven-row table for a short pointer, so a ten-row table does not
  accumulate on the front page and there is still exactly one index to keep in step. This
  deliberately revisits the previous change's decision to keep the index in `README.md`; the reason
  that decision was taken — two indexes — is met by moving rather than adding.
- **`docs/patching.md` links out rather than duplicating.** Its "What produces a failure" section
  keeps the three cases that carry the concept and points at `docs/errors.md` for the vocabulary.
  `docs/annotations.md`'s rejection table gains the same pointer.
- **Existing rationale prose stays exactly where it is.** No *why* paragraph is relocated, condensed or
  deleted. The new task layer sits above the reference pages so a working reader reaches an answer
  without walking through the reasoning; the reasoning remains for the reader who wants it.

**Modules affected:** `kdiff-sample` only, and only its test source set — one new spec file,
`RecipesSpec.kt`. No file under any `src/main` in any module is touched. No `build.gradle.kts`
changes: `kdiff-sample` already passes `kdiff.repoRoot` and `kdiff.version` to its tests, which is
everything the new page checks need.

**The generated API surface does not change.** No annotation is added, removed or altered; no
generated declaration changes shape; no runtime type gains or loses a member. A consumer recompiling
against this change gets byte-identical generated output. **Annotation semantics are unchanged** —
`@Diffable`, `@DiffKey`, `@DiffIgnore`, `@DiffWith`, `@Trackable`, `@TrackIgnore` and `@TrackDepth`
behave exactly as `diff-generation` specifies, and existing annotated code compiles to the same thing
afterwards. **Not BREAKING.**

### Explicitly out of scope

- **Changing library behaviour to match a page.** Where documentation and behaviour disagree, the
  documentation is what is wrong. If writing the error index turns up a diagnostic whose text is
  genuinely unhelpful, that is a finding to report, not a fix to make here.
- **A published API reference (Dokka).** Still a real gap, still a build and publishing change, still
  its own change under this project's rules.
- **Comparison with other diff libraries.** Dropped at the user's direction in the previous
  documentation change; no page names or benchmarks another library.
- **New prose about behaviour that does not exist.** Every recipe and every FAQ answer is backed by a
  test or by an existing documented rule.

## Capabilities

### New Capabilities
<!-- None. -->

### Modified Capabilities
<!-- None. This change declares skip_specs: true. -->

Documentation is not behaviour. `diff-generation`, `diff-application`, `change-tracking` and
`build-quality-gates` describe what the library does, and this change alters none of it — it documents
an existing closed vocabulary in full, indexes existing error messages, and adds a task-shaped route
into prose that already exists. No requirement changes and none is invented to satisfy validation.

Two existing requirements are *relied upon* rather than modified. `diff-application` owns the
`PatchFailure.Reason` vocabulary that `docs/errors.md` documents; the page must describe the fourteen
cases the spec defines and must not introduce a fifteenth by inference. `build-quality-gates` owns the
documentation-sample guard; every new fenced Kotlin block must satisfy it rather than opt out of it.

## Impact

- **New documentation:** `docs/README.md`, `docs/how-to.md`, `docs/errors.md`, `docs/faq.md`.
- **Modified documentation:** `README.md` (index becomes a pointer), `docs/patching.md` and
  `docs/annotations.md` (link out to the error index), and the "Where to go next" footer on all seven
  existing pages, which must reach the new pages or they are unreachable from anywhere but the index.
- **New source:** `kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt`.
- **Modified source:** none.
- **`CONTRIBUTING.md`** documents the `from:` / `illustrative` marker convention and the version
  guard. It must say that a how-to recipe is backed by a test rather than written as prose, or the
  next contributor adding a recipe will reach for `illustrative`.
- **Unchanged:** every `src/main` in every module; every `build.gradle.kts`;
  `gradle/libs.versions.toml`; both GitHub workflows; `CHANGELOG.md`, which describes releases;
  `CLAUDE.md`, whose statements this change does not contradict.
- No Kotlin, KSP, KotlinPoet, Gradle or Kotest version moves.
