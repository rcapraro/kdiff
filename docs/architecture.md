# Architecture

Why kdiff is shaped the way it is — for anyone deciding whether to use it, or wanting to extend it.

## Four modules, and the dependency rules matter

```
kdiff-annotations   annotations only, no dependencies at all
        ▲
        │ (compile-time only)
kdiff-processor     SymbolProcessor + KotlinPoet, never on a consumer's runtime classpath
        │
        │ emits code referencing
        ▼
kdiff-runtime       result types, capability interfaces, the hand-written DSL
                    depends only on the Kotlin standard library

kdiff-sample        consumes the processor end to end; the integration test
```

Two rules are load-bearing rather than stylistic:

**`kdiff-runtime` depends only on the Kotlin standard library** — not on the annotations module, not
on the processor. That is what lets a consumer take the runtime without dragging in a code generator,
and it is why `UNLIMITED_DEPTH` is declared in both the annotations and the runtime module: an
annotation default must be a compile-time constant where the annotation lives, and neither module can
reach the other's copy. The duplication is the price of the independence, and it is deliberate.

**The processor never reaches a consumer's runtime classpath.** It runs inside the compiler, applied
through the `ksp` configuration. Declaring it as `implementation` would ship a code generator to
production — which is why the install snippet in the README is a checked sample rather than prose.

## Three capabilities, one generated object

`@Diffable` generates a single object per type, and each capability is a mixed-in interface:

<!-- illustrative -->
```kotlin
public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order>
```

One declaration, one import, three directions: compare two instances, apply a diff back, read the
declared tracking scope.

## The axis that decides the API's shape

| Capability | Interface | From an annotation | By hand |
|---|---|---|---|
| compare | `Differ<T>` | `@Diffable` | `differ { }` builder |
| apply | `Patcher<T>` | `@Diffable`, same object | `object : Patcher<T>` — no builder |
| track | `Tracked<T>` | `@Trackable`, same object | `trackScope { }` builder |

**A capability gets a DSL builder exactly when it only needs to *name* properties.**

Comparing reads properties, so `differ { }` can do it for any type generically. Tracking only names
properties and depths, so `trackScope { }` can too. Patching has to *construct* the property's owner,
and no builder can know a constructor — so `Patcher` deliberately has none, and a hand-written patcher
is written out in full.

That single distinction explains several things that otherwise look inconsistent: why there are two
builders and not three; why a `@DiffWith` object that only compares makes its property *unpatchable*
but still fully *trackable*; and why tracking needs no `@TrackWith` escape hatch at all. If you extend
kdiff, extend along this axis rather than across it.

## The change model

A `Diff` is a flat, ordered `List<Change>`, and `Change` is a **sealed, closed** vocabulary of five
variants. Closed is a promise: a `when` over a change can be exhaustive and stay that way, so adding
a sixth variant is treated as a breaking change rather than an addition.

Locations are a `FieldPath` — a value class over a list of `Segment`s that are either a property name,
an index, or a key. A key segment retains the key *as its own value* rather than as text, so an entry
added to a `Map<Int, V>` can actually be reconstructed; a rendered `"1"` could not be. Nested differs
report paths relative to themselves and the caller lifts them, which is how a change three levels down
arrives as `company.address.city`.

## Why the algorithms are not generated

The generated code is a straight-line sequence of calls into runtime helpers — `compareValue`,
`compareKeyedList`, `patchNested`, `trackScopeOf` — rather than inlined logic.

That is a maintenance decision. An algorithm that lives in `kdiff-runtime` exists once, and a fix to
it ships as a dependency bump. The same algorithm generated into every consumer's build would need
every one of them to recompile. It also keeps the generated file short enough that reading it is a
reasonable way to check what the processor did.

If you extend the library, put new logic in the runtime and have the processor emit a call to it.

## Compile-time diagnostics, never silent fallbacks

A shape kdiff cannot handle is a compile error naming the declaration, not a fallback. A property of
an unsupported type is not quietly compared by equality — comparing a rich type as an opaque value
produces a diff that is technically correct and useless. An annotation that could do nothing is
rejected rather than ignored, because the author believes they configured something.

See [annotations.md](annotations.md) for the full list.

## Incremental compilation

Every generated file declares its originating file and is non-aggregating, so touching one annotated
class does not regenerate the others. A type's tracking scope describes only the class it is declared
on, so a nested `@Diffable` type's own scope is never read into its parent's — which is what keeps the
dependency graph narrow.

## Notes for contributors

`CLAUDE.md` at the repository root is the terse operating manual: commands, invariants, and the things
that are easy to get wrong. This page explains the reasoning behind them; that one states the rules.
Where a fact appears in both, the explanation belongs here.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the workflow.
