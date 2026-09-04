# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew check                                   # the definition of done for every task
./gradlew :kdiff-runtime:test                     # one module
./gradlew :kdiff-runtime:test --tests '*SelectSpec*'   # one spec class (Kotest on the JUnit platform)
./gradlew :kdiff-sample:kspKotlin                 # regenerate the sample's differs
./gradlew :kdiff-sample:kspKotlin --rerun-tasks   # force regeneration when inspecting output
```

Generated code lands in `kdiff-sample/build/generated/ksp/main/kotlin/demo/<Type>Diff.kt`. Read it
after any processor change — it is the review surface for what the processor emits.

Kotest test names are sentences, so `--tests` filters on the spec *class*, not the test name.

## What this is

An annotation-driven structural diff library for Kotlin. `@Diffable` on a data class makes a KSP
processor generate `object <Type>Differ` at compile time. No reflection, no runtime cost beyond the
generated code, and generated Kotlin a human would be happy to read.

## Module graph — the dependency rules are load-bearing

- `kdiff-annotations` — annotations only, **no dependencies at all**.
- `kdiff-runtime` — result types (`Diff`, `Change`, `FieldPath`), the capability interfaces generated
  code targets, and the hand-written DSL. **Depends only on the Kotlin stdlib** — not on the
  annotations module, not on the processor.
- `kdiff-processor` — `SymbolProcessor` + KotlinPoet. Compile-time only for consumers, applied
  through the `ksp` configuration. Never a runtime dependency.
- `kdiff-sample` — consumes the processor end to end; doubles as the integration test.

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

A hand-written differ or scope must be indistinguishable from a generated one to anything consuming
it — that is a spec'd requirement, not just a convention.

## The change model

A `Diff` is a flat, ordered `List<Change>`. `Change` is a **sealed, deliberately closed** vocabulary
(`ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved`) so callers can handle it exhaustively.
Adding a sixth variant breaks every exhaustive `when` in every consumer — treat it as breaking.

`FieldPath` is a value class over `List<Segment>`; `Segment` is `Field(name)` | `Index(i)` |
`Key(property, value)`. A key retains the value itself, not a rendering of it, so a keyed element can
be reconstructed. Nested differs report paths relative to themselves and the caller lifts them with
`prefixedWith`.

Runtime helpers (`compareValue`, `patchNested`, `trackScopeOf`, …) live in `kdiff-runtime` rather than
being generated, so an algorithm fix ships as a dependency bump instead of a recompile of every
consumer. **Keep new logic in the runtime; have the processor emit calls to it, not the logic itself.**

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

## OpenSpec workflow

This repo is spec-driven. `openspec/config.yaml` is the authoritative source for the tech stack,
per-artifact rules and operation guidance — **read it before planning a change**; do not restate or
duplicate it elsewhere. `openspec/specs/` holds the current behaviour contracts
(`diff-generation`, `diff-application`, `change-tracking`); `openspec/changes/archive/` holds
completed changes with their proposal, design, delta specs and tasks.

Work through the slash commands rather than editing specs directly: `/opsx:propose` →
`/opsx:apply` → `/opsx:archive` (archive is what syncs a delta into the main specs). Editing a main
spec outside a change bypasses the workflow.

Do not bump Kotlin, KSP, KotlinPoet, Gradle or Kotest versions inside a feature change — version
upgrades are their own change. Versions live in `gradle/libs.versions.toml`.
