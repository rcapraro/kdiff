# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew check                                   # the definition of done for every task
./gradlew :kdiff-runtime:test                     # one module
./gradlew :kdiff-runtime:test --tests '*SelectSpec*'   # one spec class (Kotest on the JUnit platform)
./gradlew :kdiff-sample:kspKotlin                 # regenerate the sample's differs
./gradlew :kdiff-sample:kspKotlin --rerun-tasks   # force regeneration when inspecting output

./gradlew ktlintFormat                            # fix formatting; `check` runs ktlintCheck
./gradlew updateKotlinAbi                         # record a deliberate public API change
./gradlew :kdiff-benchmarks:jmh -Pjmh.includes=compare   # measure before claiming a hot path is free
./gradlew dokkaGenerate                           # build the reference docs from KDoc
```

JVM 21 toolchain; Gradle provisions it. `.github/workflows/ci.yml` runs `./gradlew check` and
nothing else — CI adds no second command, by design (`build-quality-gates`).

`check` also runs detekt (config: `config/detekt/detekt.yml`, deviations only, each with its reason),
`allWarningsAsErrors`, and ABI validation against the dump in `<module>/api/`. **Adding or removing a
public declaration in a published module fails `check` until `updateKotlinAbi` is run** — that diff is
the review surface for API changes, so read it rather than regenerating it reflexively.

Generated code lands in `kdiff-sample/build/generated/ksp/main/kotlin/demo/<Type>Diff.kt`. Read it
after any processor change — it is the review surface for what the processor emits.

Kotest test names are sentences, so `--tests` filters on the spec *class*, not the test name.

## What this is

An annotation-driven structural diff library for Kotlin. `@Diffable` on a data class makes a KSP
processor generate `object <Type>Differ` at compile time. No reflection, no runtime cost beyond the
generated code, and generated Kotlin a human would be happy to read.

## Module graph — the dependency rules are load-bearing

- `kdiff-annotations` — annotations only. **Depends on no kdiff module and no third-party library**;
  like every Kotlin module it carries the Kotlin stdlib.
- `kdiff-runtime` — result types (`Diff`, `Change`, `FieldPath`), the capability interfaces generated
  code targets, and the hand-written DSL. **Depends only on the Kotlin stdlib** — not on the
  annotations module, not on the processor.
- `kdiff-processor` — `SymbolProcessor` + KotlinPoet. Compile-time only for consumers, applied
  through the `ksp` configuration. Never a runtime dependency.
- `kdiff-sample` — consumes the processor end to end; doubles as the integration test.
- `kdiff-tutorial` — the worked example behind `docs/tutorial.md`: an annotation-free domain described
  with `differ { }`, plus an annotated mirror that `AnnotatedParitySpec` holds to the same output.
- `kdiff-benchmarks` — JMH, not published, not on any consumer's path. Where a performance claim is
  settled before it is written down.

`UNLIMITED_DEPTH = -1` is deliberately declared **twice**, in `kdiff-annotations` and in
`kdiff-runtime`, with a comment in each saying why: an annotation default must be a compile-time
constant in the module declaring the annotation, and neither module may depend on the other. Do not
"fix" this by adding a dependency.

## Three capabilities on one generated object

`object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order>` — comparison, application and
tracking reached through one declaration and one import.

| capability | interface | from an annotation | written by hand |
|---|---|---|---|
| compare | `Differ<T>` | `@Diffable` | `differ { }` builder |
| apply | `Patcher<T>` | `@Diffable`, same object | `object : Patcher<T>` — **no builder** |
| track | `Tracked<T>` | `@Trackable` | `trackScope { }` builder |

**A capability gets a DSL builder exactly when it only needs to *name* properties.** Comparison and
tracking read/name properties, so both have builders. Patching must *construct* the property's owner,
which a builder cannot know how to do — hence `Patcher` has none, and a `@DiffWith` object that can
only compare makes its property unpatchable (`unpatchable()` reports the changes as failures). This
axis is why the API is shaped as it is; extend along it rather than across it.

Extending along it is now a **promise**, not only a design principle: `docs/api-stability.md` §4 says a
capability interface may gain a member carrying its own implementation, and a generated object may
declare a further capability interface, in a minor version. A member *without* an implementation is
breaking. So a new capability is a new interface, and a widened one keeps every hand-written
implementation compiling.

A hand-written differ or scope must be indistinguishable from a generated one to anything consuming
it — that is a spec'd requirement, not just a convention.

## The change model

A `Diff` is a flat, ordered `List<Change>`, and an `Iterable<Change>` in its own right. `Change` is a
**sealed, deliberately closed** vocabulary (`ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved`)
so callers can handle it exhaustively. Adding a sixth variant breaks every exhaustive `when` in every
consumer — treat it as breaking.

`PatchFailure.Reason` is sealed on **different** terms: fourteen cases so far, and **adding one is a
minor version, not a break**. A reason is reported rather than dispatched on, so the safety comes from
elsewhere — nothing outside the library can declare a case, and `describe()`'s exhaustive `when` keeps
`toString()` total for every case including a new one. Callers are told to branch with an `else`. A
failure carries a case, never a sentence; `toString()` is where the sentence lives.

Refusals are declared types: `DuplicateDiffKeyException` and `CyclicStructureException` (both
`IllegalArgumentException`, so old `catch` clauses still fire) and `PatchFailedException` (an
`IllegalStateException`, because it is a caller asking a partial result for a value it has not got).
There is deliberately no common supertype — a marker interface cannot be caught.

Comparing and applying descend at most `MAX_DESCENT` levels through `Descent.into`, which is why a
cycle is a diagnostic rather than a `StackOverflowError`. Step **once per helper call, never per
element**: the bound constrains recursion, and a nested level enters a collection helper exactly once
however many elements it holds. Stepping per element cost 4–10% throughput and tightened nothing.

`FieldPath` is a value class over `List<Segment>`; `Segment` is `Field(name)` | `Index(i)` |
`Key(property, value)`. A key retains the value itself, not a rendering of it, so a keyed element can
be reconstructed. Nested differs report paths relative to themselves and the caller lifts them with
`prefixedWith`.

Runtime helpers (`compareValue`, `patchNested`, `trackScopeOf`, …) live in `kdiff-runtime` rather than
being generated, so an algorithm fix ships as a dependency bump instead of a recompile of every
consumer. **Keep new logic in the runtime; have the processor emit calls to it, not the logic itself.**

Deciding what a change *means* is `Diff.route<T> { }` (`Route.kt`), which names properties by
`KProperty1` and reads only the diff — so it serves the annotated and hand-written routes identically.
It replaced generated per-type field tokens, which only `@Trackable` could reach; the trade-off, taken
deliberately, is that dispatch is no longer an exhaustive `when`, and a new property reaches
`otherwise` instead of breaking the build.

## Tracking: depth counts property steps only

`Segment.Index` and `Segment.Key` identify siblings, not nesting, so they do not consume depth:
`addresses[id=A3]` is 1 step, `addresses[id=A2].street` is 2.

Depth **filters**; it never rewrites a path, relocates a change to a shallower ancestor, or
synthesises a value. A roll-up would need a new `Change` variant (closed) plus reflection to read the
nested values.

When touching scope resolution (`TrackerBuilder.build`, `TrackScope.atDepth`,
`ResolvedScope.selects`): the dangerous failure direction is **widening**. A tracker reporting too
little is noticed the first time an expected callback does not arrive; a tracker reporting a property
the caller scoped out looks like a change they asked for. A real bug here silently reported the whole
object; `TrackScopeCompositionSpec` exists to catch its return.

## Docs are checked prose

`docs/` is the published surface for behaviour, and three pages are pinned to code rather than to
memory: `how-to.md` recipes are one-per-test in `kdiff-sample`'s `RecipesSpec`, `tutorial.md` is
`kdiff-tutorial`, and `errors.md` quotes every diagnostic **verbatim**. Nothing in `check` compares a
quoted message to the string the processor emits — so changing a `KSPLogger.error` message or a
failure sentence means editing `errors.md` in the same commit. `docs/README.md` is the index.

`CONTRIBUTING.md` covers the same build for a human contributor; when a command changes, both move.

## Processor conventions

- KSP2 only; no KSP1 compatibility paths.
- User-facing problems are `KSPLogger.error(message, symbol)` diagnostics reported at the offending
  symbol — **never exceptions, never silent fallbacks**. An unsupported shape or an annotation that
  could do nothing is a compile error that names the declaration and points at what is missing.
- Every generated file declares `Dependencies(aggregating = false, originatingFile)`. Use
  `aggregating = true` only for a file that genuinely depends on the whole module, and say why in the
  design.
- `KSTypeReference.resolve()` is expensive: resolve each type once and pass the `KSType` around.
- Processor tests compile Kotlin snippets in-test with kctfork and prefer compiling and *invoking* the
  generated code over asserting on its text. `CompileTesting.kt` holds the helpers (`compile`,
  `runDiffer`, `diffFixture`, `roundTripFixture`, `trackedFields`, `trackFixture`). Snapshot generated
  text only where the shape of the generated API is itself the contract.

## Commits and releases

Every commit message follows [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope)!: subject`, imperative mood, lowercase subject, no trailing period. History is rewritten
rather than left non-compliant, so a new commit that does not parse is the only kind that stands out.

Types in use: `feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `build`, `ci`, `chore`. Scope is the
module without its prefix (`runtime`, `processor`, `annotations`, `sample`, `tutorial`) or the area
touched (`readme`, `changelog`, `openspec`, `claude`); omit it when a change genuinely spans the repo.
Merge commits keep GitHub's default subject.

**A breaking change carries both signals**: `!` before the colon *and* a `BREAKING CHANGE:` footer
saying what was removed and what replaces it. The generated API is a published surface — removing a
generated type is breaking even when every test still passes.

Releasing is a tag. `vX.Y.Z` must match `version` in the root `build.gradle.kts`, and pushing it runs
`.github/workflows/publish.yml`: check, publish the three published modules to GitHub Packages, then
create or update the GitHub release with that version's `CHANGELOG.md` section as its notes. So the
changelog entry is written *before* the tag, and it is the only place a version is described — release
notes are generated from it, never typed separately.

## OpenSpec workflow

This repo is spec-driven. `openspec/config.yaml` is the authoritative source for the tech stack,
per-artifact rules and operation guidance — **read it before planning a change**; do not restate or
duplicate it elsewhere. `openspec/specs/` holds the current behaviour contracts
(`diff-generation`, `diff-application`, `change-tracking`, `build-quality-gates`);
`openspec/changes/archive/` holds completed changes with their proposal, design, delta specs and
tasks.

Work through the slash commands rather than editing specs directly: `/opsx:propose` →
`/opsx:apply` → `/opsx:archive` (archive is what syncs a delta into the main specs). Editing a main
spec outside a change bypasses the workflow.

Do not bump Kotlin, KSP, KotlinPoet, Gradle or Kotest versions inside a feature change — version
upgrades are their own change. Versions live in `gradle/libs.versions.toml`.
