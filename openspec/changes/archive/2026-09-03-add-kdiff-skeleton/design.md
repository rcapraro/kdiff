## Context

See `proposal.md` — Why. Requirements are in `specs/diff-generation/spec.md`.

The repository is empty apart from `openspec/`. Everything this design describes is created by
this change, so there is no existing code to accommodate — only the project charter, which fixes
the module layout, the toolchain versions, the KSP conventions and the testing conventions. This
design does not revisit those; it decides the shape of the generated API and the boundary between
generated code and the runtime, because both are load-bearing for the two changes that follow.

Constraint that drives most decisions: the generated API introduced here is the baseline every
later change extends. Getting the shape wrong is cheap to fix now and expensive once `kdiff-sample`
and the diff semantics are built on it.

## Goals / Non-Goals

**Goals**

- Prove the pipeline end to end: annotation → generation → a consumer module that compiles and runs
  against generated code, all under `./gradlew check`.
- Fix the shape of the generated API — name, package, and the interface it implements — so that the
  next change adds comparison logic without changing any signature.
- Fix the boundary between `kdiff-runtime` and generated code.
- Establish the diagnostic path with a real error, so later unsupported-shape errors slot into an
  existing mechanism rather than inventing one.
- Establish correct incremental-processing declarations from the first generated file, since these
  are easy to get wrong and silently produce stale output.

**Non-Goals**

- Any field comparison. The generated differ deliberately reports nothing.
- Any annotation parameter. `@Diffable` is a marker here.
- Publishing: no `maven-publish`, no signing, no coordinates. Adding it later touches only build
  files.
- Convention plugins under `build-logic/`. The charter says to add them once build logic repeats;
  with four modules and one repeated block, inline configuration in the root build is still the
  smaller thing. Revisit when a third module needs the same non-trivial setup.

## Decisions

### D1 — Four modules, with the processor strictly compile-time for consumers

`kdiff-annotations` ← `kdiff-processor` → `kdiff-runtime` is the only allowed direction, plus
`kdiff-sample` depending on `kdiff-annotations` and `kdiff-runtime` as `implementation` and on
`kdiff-processor` through the `ksp` configuration.

The processor must not reach a consumer's runtime classpath: it drags in `symbol-processing-api`
and KotlinPoet, neither of which a consumer should ship. The `ksp` configuration is what enforces
this, and the spec requirement "Generating differs adds no runtime dependency beyond the result
types" is what tests it.

`kdiff-runtime` never depends on `kdiff-processor` — the charter states this and it matters
structurally: generated code links against the runtime, so a cycle would make the runtime
un-consumable.

*Alternative considered*: a single module with source sets. Rejected — it cannot express the
compile-time-only boundary, which is the property most likely to be broken silently.

### D2 — Generated shape is `object <Type>Differ : Differ<Type>` in `<Type>Diff.kt`

The file name follows the charter (`<Type>Diff.kt`, in the annotated class's package). The
declaration inside it is an `object` named `<Type>Differ` implementing `Differ<Type>`.

Why an object implementing an interface rather than a top-level function:

- **Composition.** The next change diffs nested `@Diffable` types by delegating to the nested
  type's differ. Delegating to a named object is a plain reference the processor can emit from
  the nested type's fully-qualified name alone; delegating to a top-level function means resolving
  an overload set.
- **A common type.** The DSL escape hatch planned for the next change hand-builds a `Differ<T>`
  for types that cannot be annotated. It must produce the same type generated code produces, so
  that nesting composes regardless of which side produced the differ. That only works if there is
  an interface.
- **Uniform naming.** `<Type>Differ` is derivable from the type name with no ambiguity, which the
  processor and a human reader both need.

Separating the file name (`<Type>Diff.kt`) from the declaration name (`<Type>Differ`) is
deliberate: the file is expected to hold more than the differ in later changes — a generated patch
applier is planned as change three, and it belongs in the same file, sharing the same originating
dependency.

*Alternative considered*: an extension function `Person.diff(other: Person)`. Rejected — it
pollutes the annotated type's completion, and it has no stable name for the processor to reference
when composing nested differs.

### D3 — What lives in `kdiff-runtime` versus what is generated

**In the runtime — everything that is not per-type:**

| Type | Why it is not generated |
|---|---|
| `Differ<T>` | The contract both generated and hand-written differs implement. |
| `Diff` | The result value, plus everything computed *from* changes (in later changes: the tree view, the renderer). Identical for every type. |
| `Change` and its subtypes | The change vocabulary is closed and type-independent. |
| `FieldPath`, `Segment` | Path construction and rendering are shared logic. |

**Generated — only the per-type comparison:** which fields exist, in what order they are compared,
what each field's path segment is called, and which nested differ each field delegates to. That is
the only knowledge the runtime cannot have without reflection, which is exactly the knowledge the
processor exists to supply.

The rationale is maintenance asymmetry: generated code can only be fixed by recompiling every
consumer, while runtime code ships as a normal dependency bump. So the split is drawn to keep
generated code as small and as dumb as possible — ideally a straight-line sequence of comparisons
with no branching cleverness. It also keeps generated files readable, which the charter names as a
goal.

In this change the generated body is a single `return Diff(emptyList())`, and the runtime is at its
minimum:

```kotlin
public interface Differ<T> {
    public fun diff(before: T, after: T): Diff
}

public sealed interface Segment {
    public data class Field(public val name: String) : Segment
}

@JvmInline
public value class FieldPath(public val segments: List<Segment>)

public sealed interface Change {
    public val path: FieldPath
}

public data class Diff(public val changes: List<Change>) {
    public val isEmpty: Boolean get() = changes.isEmpty()
}
```

`Change` is a sealed interface with no implementations yet, and `Segment` has only `Field`. Both
are sealed so the next change adds cases without a source-compatibility break for the library's own
`when` expressions. `Diff` is deliberately a declared type rather than a `List<Change>` typealias:
`tree()` and `render()` land on it next change, and callers should not have to migrate. It is a
`data class` because the spec requires a diff result to be a value — diffing the same pair twice
returns equal results.

Growth planned for the next change, all additive: `ValueChanged`, `Added`, `Removed`,
`TypeChanged`, `Moved` as `Change` implementations; `Index` and `Key` as `Segment` cases;
`Diff.tree()` and `Diff.render()`.

### D4 — Type resolution in this change, and the policy the next change inherits

This change resolves **nothing about property types**. The processor inspects only the annotated
declaration itself: its `classKind`, whether it carries the `DATA` modifier, its
`simpleName` and its `packageName`. No `KSTypeReference.resolve()` call is made, because no field
is compared. That is the entire reason this change is cheap and the next one is not.

Recording the policy now, because the module boundaries and the generated shape above are chosen to
support it and would be wrong if the policy were different:

| Field type | Policy | Introduced in |
|---|---|---|
| Scalars, `String`, enums | Compare with `!=`, emit `ValueChanged(path, old, new)`. Enums need no special casing — they are values with sane `equals`. | change 2 |
| Nullable `T?` | `null → v` and `v → null` are `ValueChanged` with a null on one side, not `Added`/`Removed`. `Added`/`Removed` are reserved for collection elements and map entries, so that a path either exists on both sides or does not exist at all. | change 2 |
| Nested `@Diffable` type | Delegate to `<Nested>Differ`, prefixing each returned path with this field's segment. Non-null on both sides only; a nullable nested field where one side is null is `ValueChanged`. | change 2 |
| `List<E>` | Keyed if `E` has a `@DiffKey` property: match by key, emit `Added`/`Removed`/`Moved` plus nested changes. Otherwise positional by index. | change 2 |
| `Set<E>` | Unordered by definition: `Added`/`Removed` only, never `Moved`, never element-level modification. | change 2 |
| `Map<K, V>` | Key is the path segment; `Added`/`Removed` for entries, delegate for values. | change 2 |
| Generic annotated class (`@Diffable data class Box<T>`) | Compile error. A differ for `Box<T>` needs a `Differ<T>` it cannot obtain, and erasure makes a guess unsafe. Rejecting is honest; supporting it needs a `Differ<T>` constructor parameter, which contradicts the `object` shape in D2 and is its own change. | change 2, as a diagnostic |
| Any other type | Compile error naming the field and its type, suggesting the DSL escape hatch. Never a silent `!=` fallback — silently comparing a rich type by `equals` would produce a technically-correct but useless diff. | change 2, as a diagnostic |
| Cyclic object graphs | Unsupported. Generated differs recurse structurally, so a cycle in the *data* is unbounded recursion. Not statically detectable in general; documented as a constraint. | documented in change 2 |

`resolve()` is expensive, so when change 2 arrives each property's `KSType` is resolved once and
passed down, per the charter.

### D5 — Diagnostics are `KSPLogger.error`, never exceptions, never silent skips

The non-data-class rejection is implemented as `logger.error(message, declaration)` and then
skipping that declaration. KSP fails the compilation because an error was logged; the symbol
argument is what makes Gradle and the IDE point at the user's declaration instead of at the
processor's stack.

Throwing would surface as an internal processor failure with a stack trace the user cannot act on.
Skipping silently would produce a "cannot resolve `PersonDiffer`" error at the use site — a
confusing second-order symptom. Both are worse than a direct message at the annotation.

The message names the declaration and states the requirement, e.g.
`@Diffable is only supported on data classes; Person is a class`.

Establishing this here is the point: change 2 adds several unsupported-shape diagnostics per the
table in D4, and they all reuse this mechanism.

### D6 — Rounds and deferral

The processor holds no state between rounds. `process()` returns the symbols it could not validate
so KSP retries them next round; everything else is generated immediately and `finish()` does
nothing.

In this change deferral is close to vacuous — validity is "is a data class", which does not depend
on other symbols being generated. It is implemented anyway because the structure is what change 2
needs (a nested `@Diffable` type may be generated in a later round), and retrofitting round
handling into a processor written without it is the kind of change that quietly breaks incremental
builds.

### D7 — Incremental processing: `aggregating = false`, originating files are the inputs actually read

Each generated file declares `Dependencies(aggregating = false, sourceFile)`, where `sourceFile` is
the `containingFile` of the annotated class.

`aggregating = false` is correct because a generated differ is a function of exactly one input
file: the file declaring the annotated class. It does not depend on the set of other annotated
classes in the module, so adding an unrelated `@Diffable` class must not invalidate it.
`aggregating = true` would be a correctness-preserving but wasteful lie, invalidating every
generated file on any source change.

The forward-looking part, which is why this is worth deciding now rather than later: once change 2
delegates to nested differs, a generated file is a function of *more than one* input file — the
annotated class's file, **plus** the containing file of every nested `@Diffable` type it delegates
to, because a change to the nested type (say, adding a field) must regenerate the outer differ.
Those files all go into the same `Dependencies(aggregating = false, ...)` varargs list. Getting
this wrong produces stale generated code that only appears on incremental builds and never on CI's
clean builds — the worst possible failure signature. This change sets the pattern (originating
files are "every file I read to produce this output", not "the file with the annotation") so change
2 extends a correct rule instead of discovering it.

Generation goes through `CodeGenerator` via KotlinPoet's `FileSpec.writeTo(codeGenerator, deps)`.

### D8 — `kdiff-sample` is the integration test, not a demo

The sample module exists to fail when the pipeline is broken in ways the processor's own tests
cannot see: the Gradle plugin wiring, the `ksp` configuration, generated sources landing on the
compile classpath, the runtime dependency actually being present. Compile-testing the processor
in-process does not exercise any of that.

So the sample carries a real Kotest spec, not a `main` function, and it is part of `check`.

### D9 — Verifying the pinned version set is task one

The charter pins Kotlin 2.4.10, KSP 2.3.11, KotlinPoet 2.3.0, Gradle 9.7.1, Kotest 6.2.4 and
kctfork 0.13.0, and forbids bumping versions inside a feature change. These have never been
resolved together here. The first task therefore is a build that resolves them, before any
processor code exists, so an incompatibility is found in isolation.

Note the known-tight coupling: kctfork 0.13.0 is built against Kotlin 2.4.0 / KSP 2.3.9 while the
project targets Kotlin 2.4.10 / KSP 2.3.11. Patch-level skew across that boundary is normally fine,
but it is the single most likely failure in this change — see Risks.

## Sample of the generated file

For this input in `kdiff-sample`:

```kotlin
package demo

import io.github.kdiff.annotations.Diffable

@Diffable
data class Person(val id: String, val name: String)
```

the processor generates `demo/PersonDiff.kt`:

```kotlin
package demo

import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.Differ
import kotlin.collections.emptyList

public object PersonDiffer : Differ<Person> {
  override fun diff(before: Person, after: Person): Diff = Diff(emptyList())
}
```

Called as:

```kotlin
PersonDiffer.diff(Person("1", "Ada"), Person("1", "Grace")).isEmpty  // true, for now
```

The signature is the contract. Change 2 replaces only the body — the `Diff(emptyList())` becomes a
sequence of comparisons accumulating into a list — and the package, object name, interface and
method signature stay exactly as they are here. That is the property this change is buying.

## Risks / Trade-offs

- **kctfork 0.13.0 is built against Kotlin 2.4.0 / KSP 2.3.9, the project targets 2.4.10 / 2.3.11**
  → Highest-likelihood failure in this change, and it appears only when processor tests run. It is
  isolated to `kdiff-processor`'s test source set, so it cannot break consumers. If the skew is
  fatal, the fallback is to cover the processor through `kdiff-sample` plus a Gradle
  TestKit-style build test, and to raise the kctfork version as its own change per the charter.
  Surface this early: write the simplest possible compile-testing spec first, before the
  diagnostic spec.

- **The spec requires a generated differ to report no changes even for differing instances** → A
  reader could mistake the baseline for a bug, and the sample's test asserts something that will be
  inverted next change. Mitigated by stating it as a temporary baseline in the spec, and by naming
  the sample test for what it proves ("the generated differ is callable"), not for the emptiness.

- **`Change` is a sealed interface with zero implementations** → Nothing can construct a non-empty
  `Diff`, so the runtime's own tests can only assert on the empty case. Accepted: the alternative
  is inventing change types now and re-litigating them in change 2, when the diff semantics that
  justify them are actually being decided.

- **Generated code is compiled into consumers, so a bug in it needs a consumer recompile** →
  Mitigated by D3: keep generated code minimal and put every non-trivial behaviour in the runtime,
  where it ships as a dependency bump.

- **Incremental-processing mistakes produce stale output that clean CI builds never catch** →
  Mitigated by fixing the originating-files rule in D7 now, while there is exactly one input file
  and the rule is obvious, rather than when nested types make it subtle.

- **Four modules is heavy for a library this small** → Accepted; the compile-time-only boundary for
  the processor is not expressible with fewer, and it is the charter's layout.

## Migration Plan

No migration: nothing exists, nothing consumes kdiff, no data or API is being moved. Rollback is
deleting the added files. The change is complete when `./gradlew check` passes from a clean clone.

## Open Questions

- Publishing coordinates and the `maven-publish` setup. Deferrable: it touches only build files,
  and no spec or task here depends on the answer.
- Whether `Diff` should eventually expose changes as a `Sequence` for very large graphs. Cannot be
  answered before the diff algorithm exists; revisit in change 2 if the list shape becomes a
  constraint.
