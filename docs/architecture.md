# Architecture

Why kdiff is shaped the way it is — for anyone deciding whether to use it, or wanting to extend it.

## Five modules, and the dependency rules matter

Three modules are published. Two consume them the way you would, and exist to prove the published
three work.

```
  published                                       |  not published
                                                  |
  +---------------------+                         |
  | kdiff-annotations   |  annotations only,      |   +----------------+
  | @Diffable, @DiffKey |  no dependencies at all |   | kdiff-sample   |
  +---------------------+                         |   | every shape,   |
             ^                                    |   | end to end     |
             | depends on                         |   +----------------+
             |                                    |
  +---------------------+                         |   +----------------+
  | kdiff-processor     |  runs inside the        |   | kdiff-tutorial |
  | SymbolProcessor     |  compiler; never on a   |   | a domain that  |
  | + KotlinPoet        |  runtime classpath      |   | imports no     |
  +---------------------+                         |   | kdiff at all,  |
             |                                    |   | plus an        |
             | emits code calling                 |   | annotated      |
             v                                    |   | mirror         |
  +---------------------+                         |   +----------------+
  | kdiff-runtime       |  Diff, Change,          |
  | results, interfaces |  FieldPath, differ { }  |   neither depends on the other.
  | and the DSL         |  Kotlin stdlib only     |   each declares annotations and
  +---------------------+                         |   runtime, plus ksp(processor)
```

`kdiff-annotations` and `kdiff-runtime` do not depend on each other, and neither depends on the
processor. The only arrow into `kdiff-runtime` is *generated code calling it* — a compile-time arrow
that leaves no build-time edge, which is what lets a consumer take the runtime alone.

`kdiff-sample` and `kdiff-tutorial` are ordinary consumers: both apply the KSP plugin and declare the
same three dependencies a reader would. `kdiff-sample` is the integration test and the source every
documentation sample is checked against; `kdiff-tutorial` is the worked application behind
[the tutorial](tutorial.md), and its `AnnotatedParitySpec` holds the hand-written and annotated routes
to identical output.

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

One question sorts the three:

```
                    does this capability need to CONSTRUCT
                       the property's owner, or only NAME it?
                                      |
                +---------------------+---------------------+
                |                                           |
             only NAME                                  CONSTRUCT
                |                                           |
     +----------+----------+                                |
     v                     v                                v
  Differ<T>            Tracked<T>                       Patcher<T>
  compare              track                            apply
     |                     |                                |
  differ { }          trackScope { }                   no builder
  reads a property    names a property                 a builder cannot
  through its         and a depth                      know a constructor
  reference               |                                |
     |                    |                           object : Patcher<T>
  @Diffable            @Trackable                      written out in full
```

The consequences fall out of the split rather than being decided separately: a `@DiffWith` object that
only compares sits on the left, so its property is **trackable but unpatchable**; and tracking needs no
`@TrackWith`, because nothing on the left can fail the way construction can.

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

## What a comparison costs

Every collection comparison is a small, fixed number of passes, and none of them searches.

| Shape | How it is compared | Cost |
|---|---|---|
| keyed list | one `associateBy` per side, then one pass per side over the resulting maps | linear |
| positional list | one pass to the shorter length, then the leftover tail | linear |
| set | two set differences, `before - after` and `after - before` | linear |
| map | one pass over each side, with a lookup into the other | linear |

Linear in the number of elements, given that the key or element type hashes in constant time — a
pathological `hashCode` is the one thing that can spoil it. There is no quadratic pairwise matching and
no edit-distance search anywhere in the library, so no collection size makes a comparison fall off a
cliff.

Nesting composes the obvious way: a keyed list of `@Diffable` elements delegates once per matched
element, so the total is linear in the number of *compared properties actually reached*, not in the
top-level collection size alone.

No benchmark ships with the library, so no figure is quoted here. The claim is the shape of the work,
which is readable in `Compare.kt`, not a measurement.

## What kdiff does not do

Worth knowing before adopting it, because none of these is a bug to be fixed later — each is the other
side of a decision on this page.

**An unkeyed list is compared position by position.** Insert an element at the head and every following
position reports as changed, plus one addition at the tail. There is no edit-distance matching to fall
back on — that is the cost of the linear bound above. `@DiffKey` on the element type is the answer, and
it is why keyed lists are the shape the library is built around.

**Custom comparison is per property, not per type.** `@DiffWith` points one property at one differ.
Comparing every `BigDecimal` in a model by `compareTo` rather than `equals` means an annotation at each
site; there is no global registration that says "compare this type this way everywhere".

**A comparison walks a tree, not an object graph.** There is no cross-graph identity: the same
instance reached by two paths is compared twice, as two separate values. A self-reference through a
nullable property is fine and terminates because the data does — `Node(name, next: Node?)` compares
happily and reports at `next.next.name`.

A genuine cycle no longer overflows the stack, **as long as the comparison descends through kdiff's
own helpers** — which a generated differ always does, and a `differ { }` one does too. The bound lives
in `compareNested`, the collection helpers and their patching counterparts; a hand-written
`object : Differ<T>` that calls another differ directly bypasses it and can still overflow. Delegating
through `compareNested` rather than calling `diff` yourself is what keeps that from being possible.

Where it applies, comparing and applying descend at most `MAX_DESCENT` (512) nested levels and then
raise `CyclicStructureException`, naming the path they stopped at and saying whether an instance was
re-entered — a cycle — or the structure is simply deeper than kdiff descends. Identity is only recorded over the last stretch of the descent, so a cycle longer than that
window is still refused, with a message that says no repeat was observed rather than claiming there is
none.

This costs about 9% of comparison throughput on a model with nested `@Diffable` properties, and 13% at
six levels of nesting; collections and flat types pay nothing. That was measured, not estimated, and
accepted: a `StackOverflowError` is not a diagnosis.

What the guard does *not* do is make a cyclic graph comparable. It reports the cycle and stops.

**A `@DiffKey` must actually be unique, and you find out at runtime.** Two elements sharing a key have
no representable diff — a path names a keyed element by its key value alone — so comparing or patching
such a list throws rather than guessing. Uniqueness is a property of the data, so no compile error is
possible; this is the one input the library refuses instead of describing.

**Kotlin/JVM only.** No multiplatform targets, and the annotations are not designed for Java
consumers.

The compatibility position is in the README's [Status](../README.md#status); it is not repeated here.

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

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the workflow, and [the tutorial](tutorial.md) for a
worked application that puts all three capabilities together — including the module that doubles as
the library's ergonomics test.

## Where to go next

- [Tutorial](tutorial.md) — the reasoning here, applied to a whole domain
- [Diffing](diffing.md) — the change model this page describes, in use
- [Hand-written differs and scopes](hand-written.md) — the "does it construct?" axis at the call site
- [Annotation reference](annotations.md) — every compile error this page's diagnostics policy produces
- [CONTRIBUTING.md](../CONTRIBUTING.md) — the workflow, and what will fail review
