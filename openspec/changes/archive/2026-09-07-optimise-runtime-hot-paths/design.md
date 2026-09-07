## Context

See `proposal.md` — Why, for the catalogue of allocations and the two quadratic paths. This document
covers how they are removed without moving logic out of `kdiff-runtime` and without changing what any
of the three capabilities observably does.

Three constraints shape every decision below.

1. **`kdiff-runtime` depends only on the Kotlin standard library.** No fastutil, no Guava, no
   primitive-specialised collections. Every win has to come from allocating less of what the stdlib
   already offers, not from a better collection library.
2. **Algorithms live in the runtime; the processor emits calls to them.** So the great majority of this
   change is `kdiff-runtime` edits that ship as a dependency bump. The processor is touched only where
   the *generated call site* is what allocates — the per-call `setOf(...)` and the `+`-fold of failure
   lists — because no runtime edit can fix a cost the generated code pays before it calls in.
3. **A hand-written differ, patcher or scope must stay indistinguishable from a generated one.** Every
   optimisation is inside a shared helper or inside `FieldPath`, so both routes get it. Nothing is
   optimised into generated code that the `differ { }` route cannot reach.

## Goals / Non-Goals

**Goals:**

- Cut per-change allocation on the compare path from five objects per nested change per level to two.
- Make path lifting a single copy per level rather than two, and remove the `drop(1)` copy on the
  descent.
- Remove per-change allocation from tracking's selection test entirely.
- Make application allocate nothing for a property no change addresses.
- Reduce routing dispatch from `O(handlers × changes) + O(changes × leftovers)` with deep `equals` to
  one grouping pass plus identity lookups.
- Land a benchmark harness so all of the above is measured, not asserted.

**Non-Goals:**

- **Changing `Differ<T>`'s shape.** `diff(before, after): Diff` stays the whole contract. See
  Decision 8 for the `diffInto` redesign that is deliberately deferred.
- **`Diff.tree()` and `DiffNode.allChanges()`.** Both allocate freely (`partition` + `groupBy` per
  level; `changes + flatMap` per node), and both are opt-in presentation calls a caller makes once to
  display a diff. Optimising them buys nothing on the paths that run per update.
- **Thread safety, laziness, or streaming results.** `Diff` stays an eagerly-built `List<Change>`.
- **Any change to the `Change` vocabulary.** It is closed; a sixth variant is breaking.
- **Micro-tuning `toString`/`render`.** One free fix is included (render each path once) because it is
  a two-line change in the same file; nothing further.

## Decisions

### D1 — `FieldPath.prefixedWith` builds one right-sized list; a two-segment overload exists for collections

Today: `FieldPath(listOf(segment) + segments)` — a singleton list, then `Collection.plus` allocating an
`ArrayList(1 + n)` and copying. Two allocations and a copy for a one-segment lift.

Instead, build the target list directly at size `n + 1`. And because the three collection helpers
always lift by exactly two segments — the element segment then the field segment — add a two-segment
form so that lift is one allocation and one copy rather than two of each:

```kotlin
public fun prefixedWith(segment: Segment): FieldPath =
    FieldPath(ArrayList<Segment>(segments.size + 1).also { it += segment; it += segments })

internal fun prefixedWith(outer: Segment, inner: Segment): FieldPath =
    FieldPath(ArrayList<Segment>(segments.size + 2).also { it += outer; it += inner; it += segments })
```

The two-segment form is `internal`: its only callers are the collection helpers in `Compare.kt`, and
`Change.prefixedWith(segment)` remains the single public lifting contract that `Change`'s KDoc
describes. Making it public would offer a second way to say the same thing with no caller for it.

`Change` gains a matching internal two-segment lift. `Change` is a **sealed** interface, so no consumer
outside `kdiff-runtime` implements it and adding a member is not a source break for anyone — but the
new form is added as an internal extension function over the existing private `withPath` in
`Patcher.kt` (promoted to `internal` in `Diff.kt`, beside the variants it switches over) rather than as
a sixth interface member, so the public contract stays "a change can be lifted by one segment".

*Alternative considered:* a persistent cons-list `Segment` chain, making a lift `O(1)`. Rejected:
`FieldPath.segments` is public and typed `List<Segment>`, callers index into it (`segments[depth]` in
`buildTree`, `getOrNull(1)` in routing), and a cons-list would either break that type or need a
materialising wrapper that reintroduces the copy on first read.

### D2 — `withoutFirst` and `withoutFirstSegment` return a `subList` view

`drop(1)` allocates an `ArrayList(n - 1)` and copies. `segments.subList(1, segments.size)` is a view:
no allocation of elements, `equals`/`hashCode` inherited from `AbstractList` and therefore identical to
the copy's, and `toString()` unaffected because `FieldPath.toString` walks segments itself.

The descent is where this matters: applying a diff strips one segment at every level, so a change `d`
levels deep is copied `d` times today, `O(d²)` element copies in total. With a view it is `O(d)`
wrapper objects and no element copying at all.

**Retention:** a view keeps the parent list — and therefore the parent's backing array — reachable for
as long as the derived path lives. That is bounded here: derived paths live inside one `apply` or one
`route` call and are not retained afterwards, and the parent is a path of the same object graph, not a
large unrelated buffer. Approved as an accepted edge effect in the proposal.

**Correctness note:** the view must be over a list nobody mutates. Every `FieldPath` in the runtime is
built from `ArrayList` instances the runtime itself creates and never hands out mutably, plus
`emptyList()` and `listOf(...)`. A `FieldPath` a *caller* constructs from a `MutableList` they retain
was already able to mutate that path today; a view does not widen it.

### D3 — Hoist the segment, append in place

`compareNested` today:

```kotlin
addAll(differ.diff(before, after).changes.map { it.prefixedWith(Segment.Field(name)) })
```

Three costs: a `Segment.Field` per change, the intermediate list from `map`, and `addAll`'s copy out
of it. All three go away by hoisting the segment and appending in a loop. The same hoist applies in
`compareKeyedList` and `comparePositionalList`, which allocate `Segment.Field(name)` inside their
per-change bodies; `compareSet` and `compareMap` already hoist it and are the model.

### D4 — `compareSet` walks membership instead of subtracting

`(before - after)` on two `Set`s goes through `Set.minus(Iterable)`, which copies the receiver into a
`LinkedHashSet` and then removes. Two full set copies for what two `contains` walks give directly:

```kotlin
before.forEach { if (it !in after) add(Removed(FieldPath.of(name), it)) }
after.forEach { if (it !in before) add(Added(FieldPath.of(name), it)) }
```

Reporting order is unchanged: `minus` preserves receiver iteration order, and so does the walk.

Note the shape change alongside it — today each element is built at `FieldPath.ROOT` and then lifted
with `prefixedWith(field)`, which allocates a `Change` and a path per element. Building at
`FieldPath.of(name)` directly gives the same path with neither.

### D5 — `compareKeyedList` indexes by key without `IndexedValue`, and detects a duplicate while doing it

`before.withIndex().associateBy { keyOf(it.value) }` allocates an `IndexedValue` per element on both
sides purely to keep the position for move detection. Two `Map<Any?, Int>` index maps plus the original
lists give the same information: `beforeIndex[key]` is the old position, `after[afterIndex[key]!!]` the
new element.

That build also **subsumes the uniqueness check**. Today the check is a separate concern bolted onto
the result: `associateBy` silently collapses a repeated key, the caller compares the map's size against
the list's, and on a mismatch a second walk rebuilds a `HashSet` to name the offending key. But a map
built element by element already reveals the collision at the exact element that caused it — the map
grew by one entry per element until it did not:

```kotlin
val index = LinkedHashMap<Any?, Int>(elements.size * 2)
elements.forEachIndexed { position, element ->
    val key = keyOf(element)
    index[key] = position
    if (index.size != position + 1) duplicateKey(name, keyProperty, key)
}
```

So `requireUniqueKeys(name, keyProperty, elements, distinctKeys, keyOf)` collapses into
`duplicateKey(name, keyProperty, key): Nothing`, a message builder with no detection logic left in it.
The check stops costing a size comparison against a separately built map plus a conditional second
pass, and starts costing one field read. It is `internal`, so the signature change reaches no consumer.

**Size, not `put`'s return value.** `put` returning null is the obvious collision test and it is wrong
for the patch-side map, whose values are the elements themselves: a null element returns null from
`put` whether or not its key was already present, so two null elements sharing a key would pass the
check and one would be silently dropped — the precise loss the check exists to prevent, and which the
spec's "SHALL NOT drop an element" forbids. The size test cannot be fooled, costs the same, and is used
in both maps so the two loops read identically and neither invites the unsound shortcut back.

Three details the implementation must preserve exactly:

- **The message text is unchanged, both branches of it.** `duplicateKey` keeps the named form and the
  `name == null || keyProperty == null` form for a hand-written patcher verbatim, and keeps throwing
  `IllegalArgumentException` — which is what `require(false)` threw.
- **The key reported is the same key.** Today's walk names the first element whose key was already
  seen; `put`-time detection fires at that same element, iterating in the same order.
- **`before` is still rejected before `after`.** Today both maps are built and then both are checked,
  so a duplicate in `before` wins. Building-and-checking `before` first throws before `after`'s map
  exists — the same exception, reached with less work done.

**Type resolution is untouched by all of D1–D5.** Which helper the processor emits for a property —
value, nullable nested, nested, keyed list, positional list, set, map, sealed dispatch — is decided in
`Resolution.kt` and is not modified by this change. Nullability still routes through
`compareNestedNullable`, whose null-on-either-side rule is unchanged; generics, enums and nested
`@Diffable` types resolve exactly as they do today. This change alters what each helper *costs*, never
which one is chosen or what it reports.

### D6 — `ResolvedScope` becomes two types, and resolves depths once at construction

`selects` currently runs, per change:

```kotlin
val depths = fields.filter { it.name == root.name }.map { it.depth }
if (depths.isEmpty()) return false
if (UNLIMITED_DEPTH in depths) return true
return within(steps, depths.max())
```

Two list allocations and two passes, to compute an answer that is fixed once the scope is resolved. The
"widest depth wins" rule from the *A property named more than once is tracked at the widest depth
named* requirement is a fold over the field list, so fold it at construction into a `Map<String, Int>`
of property name to widest depth, treating `UNLIMITED_DEPTH` as the maximum. Then `selects` is a hash
lookup and one comparison, allocating nothing.

**The dangerous part of that is not the fold; it is `fields == null`.** `ResolvedScope` today encodes
"track every compared property" as a null field list, one dereference away from "names properties, but
none of them" — and `CLAUDE.md` records that a real bug here once silently reported the whole object.
Replacing a nullable `List` with a nullable `Map` would carry that hazard across unchanged, and the
mitigation would be a comment asking the next reader to be careful.

The `change-tracking` requirement *A tracking scope with no selectors tracks the whole object* already
says what to do instead: a scope naming no property "SHALL be one of two distinct things, and the
library SHALL keep them distinct". So encode it as two things:

```kotlin
internal sealed interface ResolvedScope {
    fun selects(change: Change): Boolean

    class Everything(private val depth: Int, private val excluded: Set<String>) : ResolvedScope
    class Named(private val depths: Map<String, Int>, private val excluded: Set<String>) : ResolvedScope
}
```

`Named(emptyMap())` is now unmistakably "names none, selects none" — the `@Trackable` class whose every
compared property is `@TrackIgnore`d — and there is no value of `Named` that can decay into
`Everything`. The conflation stops being something the implementation must avoid and becomes something
it cannot express. The choice between the two is made in exactly one place, `resolveAgainst`, which is
about ten lines and is the whole of what a reviewer needs to check.

Both classes still handle the two rules that precede depth: a change at the tracked object itself
(empty path) is always selected, and an excluded root property is never selected, checked before any
depth rule so a depth stated elsewhere cannot widen an exclusion back.

**Equivalence is proved, not asserted.** Because this rewrites the most dangerous function in the
library, the change carries a differential spec rather than relying on the existing suite to notice a
regression. The current eight-line rule is copied into the test source as an oracle, and the new
implementation is checked against it by **exhaustive enumeration** of the interesting domain, not by
sampling:

- scopes over property names `{a, b}`, each named zero to twice, at depths `{1, 2, 3, UNLIMITED_DEPTH}`
- both `Everything` and `Named`, `Named` including the empty case
- exclusion sets over `{}`, `{a}`, `{b}`, `{a, b}`
- every change path of length 0 to 3 drawn from `Field(a)`, `Field(b)`, `Index(0)`, `Key(k, v)`

That is a few thousand combinations, runs in milliseconds, and settles the question by exhaustion over
the domain the rule is defined on. A single disagreement is a build failure naming the scope and the
path. Alongside it sits one invariant test that does not need the oracle and states the failure
direction directly: for any `Named` scope, no change whose root property the scope does not name is
ever selected.

The oracle stays in the test source after the change lands. It is eight lines, it guards the function
with the worst recorded failure mode in this repo, and it costs nothing to keep.

`Tracker.update` additionally computes `change.sides()` — a `Pair` per selected change — unconditionally,
then destructures it. Guard the whole per-change loop on `fieldListeners.isNotEmpty()` and pass the two
sides as arguments instead of a `Pair`. `sides()` stays an exhaustive `when` over `Change` so that a
sixth variant still fails to compile there.

### D7 — Application carries an unaddressed property through, with one exception

Today every patch helper allocates a `mutableListOf<PatchFailure>()` and a `Patched` even when handed
an empty change list, and `patchSet` / `patchPositionalList` / `patchMap` additionally copy the whole
collection. For a twelve-property type where one property changed, that is eleven wasted results and up
to three wasted collection copies per `apply`.

Each helper gains `if (changes.isEmpty()) return Patched(source)`. `patchNested` already does this and
is the model; the new spec text in `diff-application` generalises the rule it already states for nested
values.

**`patchKeyedList` is not an exception to that rule; it is where the rule's second half bites.** The
spec — as reworded in this change's `diff-application` delta — states it as one rule rather than a rule
and a carve-out: *carrying a property through skips its reconstruction and does not skip a precondition
its shape requires*. A keyed list holding a repeated key is the only shape with such a precondition, so
`patchKeyedList` validates on entry and takes the empty-changes exit **after** the check. Ordering the
function to match the rule is all it takes; there is nothing to special-case:

```kotlin
public fun <T> patchKeyedList(source: List<T>, changes: List<Change>, patcher: Patcher<T>, ...): Patched<List<T>> {
    val byKey = LinkedHashMap<Any?, T>(source.size * 2)
    source.forEachIndexed { position, element ->
        val key = keyOf(element)
        byKey[key] = element
        if (byKey.size != position + 1) duplicateKey(name, keyProperty, key)
    }
    if (changes.isEmpty()) return Patched(source)   // reached and checked; nothing to rebuild
    ...
}
```

Read top to bottom, that says exactly what the requirement says, which is the point: the previous
draft's "the one exception, carved out in design and spec" became a plain sequence once the check was
folded into the build (D5) and the spec was stated as one rule.

The same `LinkedHashMap` **removes the parallel `order` list** the function keeps today. `byKey` and
`order` are two representations of one thing: today `Removed` does `byKey.remove(key)` *and*
`order.remove(key)` — the second a linear scan, so `O(n·r)` over `r` removals — and `Added` does
`byKey[key] = value` guarded by `if (key !in order)`, another linear scan. A `LinkedHashMap` maintains
insertion order across removals, and re-`put`ting an existing key leaves it in place (insertion-order,
not access-order, is the default), which is precisely what that guard was hand-rolling. So `positioned`
reads straight off `byKey.keys` and both scans disappear along with the list.

Net for a keyed-list property in the common case where no change addresses it: today two full key
derivations per element, a `HashMap`, a key `List`, a `MutableList`, three more maps, an array and two
list builds; after, one key derivation per element and one `LinkedHashMap`. The mandated check survives
intact and gets cheaper, which is a better outcome than exempting it would have been.

The aliasing consequence — a rebuilt object sharing an untouched collection with its source rather than
holding a copy — is approved, spec'd, and is what `data class copy()` already does for every property
the patcher does not name. `Patcher`'s purity contract is "`apply` never modifies its argument", which
returning the argument's own value does not violate.

### D8 — The `Differ.diffInto(sink, prefix)` redesign is deferred, not rejected on the merits

The remaining structural cost is that a nested differ builds a `Diff` and a `List<Change>` of its own,
which its caller then copies and re-paths. A change `d` levels deep is re-pathed `d` times. Threading a
prefix and a sink *down* instead — `differ.diffInto(sink, prefix, before, after)`, defaulting to today's
lift-and-copy so hand-written differs keep working — makes it `O(d)` and removes the intermediate
`Diff` per level.

Deferred, for three reasons:

1. Every public compare helper would need a prefix parameter, so `Compare.kt`'s whole signature set
   grows an overload each, and generated code and hand-written code would have visibly different
   shapes at the call site — which cuts against the "indistinguishable" requirement rather than
   supporting it.
2. The win scales with nesting depth, which in the models this library is for is 2–4. D1–D3 already
   halve the per-level constant, which at that depth is the larger share.
3. It is a change to the generated `diff` body and to a published interface, so it deserves its own
   proposal with its own benchmarks — which is precisely what the harness in this change makes
   possible.

The benchmark suite added here is designed to answer whether it is worth doing: one of its cases is a
deliberately deep graph, so the lift cost is separable in the results.

**Verdict from the measurements: not worth a follow-up as things stand.** `compareSixLevelsDeep` — one
change carried up six levels, the case built to isolate exactly what `diffInto` would remove — went from
4.634 to 7.568 ops/µs and from 1624 to 1296 B/op on D1–D3 alone. At 1296 B/op for six levels the
remaining lift cost is roughly 220 bytes a level, of which the `Change` copy is unavoidable while
`Differ.diff` returns a `Diff`; `diffInto` would reclaim the path array, not the change. Against that,
it grows every public compare helper by an overload and makes generated and hand-written call sites
look different, which cuts against the requirement that they be indistinguishable. The deep case is also
now the *fastest* of the nine, so the shape it was meant to indict is not where the time goes. Revisit
only if a consumer reports a genuinely deep model — say ten-plus property steps — where this shows up in
their own profile.

### D9 — Generated `apply`: hoist the property set, fold failures once

Two costs are paid by the generated call site, not by the runtime, so only the processor can remove
them.

`groupByProperty(changes, setOf("reference", "status", ...))` allocates a `LinkedHashSet` of every
compared property name **on every `apply` call**. It is a constant of the type. Hoist it to a `private
val` on the generated object.

`grouped.unmatchedFailures("Order") + a.failures + b.failures + ...` is a left-fold of `List.plus`, each
step allocating and copying a new `ArrayList` — `O(p²)` copying over `p` properties, and for the
overwhelmingly common all-clean case, thirteen allocations to produce an empty list. One `buildList`
with a sequence of `addAll` gives the same list, in the same order, with one allocation.

The generated file after this change, for the sample's `Order` (only the two changed regions shown; the
`diff` body, the `trackScope` property and the whole `Differ`/`Patcher`/`Tracked` surface are byte-for-
byte as they are today):

```kotlin
public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order> {
  private val comparedProperties: Set<String> = setOf("reference", "status", "note", "billing",
      "shipping", "addresses", "tags", "labels", "amounts", "payment", "total", "weight")

  // ... trackScope and diff unchanged ...

  override fun apply(before: Order, changes: List<Change>): PatchResult<Order> {
    val grouped = groupByProperty(changes, comparedProperties)
    val referencePatched = patchValue(before.reference, grouped.forProperty("reference"))
    // ... one per compared property, unchanged ...
    return PatchResult(
      before.copy(
        reference = referencePatched.value,
        // ... unchanged ...
      ),
      buildList {
        addAll(grouped.unmatchedFailures("Order"))
        addAll(referencePatched.failures)
        // ... one per compared property ...
      },
    )
  }
}
```

`comparedProperties` is `private`, so the generated API surface — what a consumer can name — is
unchanged, and `explicitApi()` does not require a visibility modifier beyond the one shown. The name is
chosen not to collide with a compared property's own `<name>Patched` local or with the `trackScope`
override; a type with a property literally called `comparedProperties` still generates a local
`comparedPropertiesPatched`, so there is no clash.

**Incremental processing is unchanged.** Each generated file still declares
`Dependencies(aggregating = false, *sources)` over the annotated class's own file plus the files of the
types it resolves into — nothing in this change makes a generated file depend on anything new, and
nothing becomes aggregating. The hoisted `private val` is derived from the same property list the
`apply` body already reads, so it introduces no new originating file.

**Processor test impact:** the specs that snapshot generated text — the ones for which "the shape of the
generated API is itself the contract" — must be updated to the shape above. Specs that compile and
invoke the generated code are unaffected by construction, which is the point of preferring them.

### D10 — Routing dispatches in one grouping pass, with identity for leftovers

`ChangeRoutes.dispatch` today:

```kotlin
val unroutable = handlers.flatMap { (property, handler) ->
    changes.filter { it.path.rootName() == property }...
}
val unhandled = changes.filter { it.path.rootName() !in handlers || it in unroutable }
```

The first line walks every change once per handler. The second calls `it in unroutable` — `List.contains`,
a linear scan comparing with data-class `equals`, which for a `ValueChanged` deep-compares the path
list and both values. On a diff of `n` changes with `h` handlers that is `O(n·h)` plus `O(n·u)` deep
comparisons.

Instead: group changes by `rootName()` once into a `LinkedHashMap` (preserving report order within each
group, which the *A handler ... SHALL receive the changes under that property, in the order the diff
reports them* requirement demands), dispatch each handler against its own group, and collect what comes
back into an identity set — `java.util.IdentityHashMap`'s key set, or equivalently a `HashSet` of
wrapped references. Then the final pass is one walk with a constant-time identity test.

Identity is not merely faster here, it is what the surrounding code already assumes: `ChangeRoutes.under`
matches returned changes to given ones with `!==` and documents that "every re-rooted change is a fresh
instance". Making `dispatch` agree removes an inconsistency rather than introducing one.

**It is not, however, an observable change, and the change's spec delta was corrected during
implementation to stop claiming that it is.** Two changes can be equal only if they share a path, hence
a root property, hence one handler; and a handler's verdict is a pure function of a change's kind and
values, so no routing can accept one change and decline another equal to it. The scenario the delta
originally carried — "exactly one change reaches the fallback" for two value-equal changes — is
unconstructible through the public API. Identity is therefore an internal optimisation. The observable
property worth stating, and now stated, is the one `under` depends on: the fallback receives the change
instances the diff holds, in the diff's order.

`LinkedHashMap` is a stdlib type reachable from `kotlin.collections`; `IdentityHashMap` is `java.util`
and therefore JVM-only — which `kdiff-runtime` already is (Kotlin/JVM only, JVM toolchain 21). If a
future multiplatform move makes that a problem, a `HashSet` keyed on a small identity-wrapper value class
is the drop-in, and the choice is local to one function.

### D11 — Benchmarks: a separate unpublished module, off the `check` path

A new `kdiff-benchmarks` module applying `me.champeau.jmh`. It is **not** added to `publishedModules` in
the root build, so it gets neither `explicitApi()` nor a `maven-publish` configuration.

It is deliberately **not** wired into `./gradlew check`: a JMH run is minutes, and `check` is the
definition of done for every task in this repo. It runs on demand:

```
./gradlew :kdiff-benchmarks:jmh                    # throughput
./gradlew :kdiff-benchmarks:jmh -Pjmh.profilers=gc # allocation rate, which is the real target here
```

The plugin version is pinned in `gradle/libs.versions.toml` alongside the others, at whatever the
current release on the Gradle Plugin Portal is when the change is applied — it is verified there rather
than written from memory, and it is a new entry rather than a bump of an existing one, so it does not
run against the "no version bumps inside a feature change" rule.

**What it measures**, over the `kdiff-sample` model and one deliberately deep synthetic model:

| case | what it isolates |
|---|---|
| compare, no differences | the floor: per-property work when nothing changed |
| compare, one leaf property changed | single-change cost end to end |
| compare, keyed list of 100 with one element edited, one added, one moved | D5, and the two-segment lift of D1 |
| compare, a set and a map property changed | D4 |
| compare, a change 6 levels deep | the lift cost D8 would attack, isolated |
| apply, one change against a twelve-property type | D7 and D9 together |
| apply, round trip of the keyed-list case | the patch path's own key handling |
| track, narrow scope, 50 changes of which 2 selected | D6 |
| route, 6 handlers over 50 changes | D10 |

Baselines are captured on the current `main` **before** any optimisation lands (that is its own task),
and the same suite is rerun after, with both sets of numbers recorded in this file. A case that does not
improve is reported as not improving.

### Baseline

Captured with the runtime and processor changes stashed, so this is unmodified `main` measured through
the new module. JDK 21.0.7, JMH 1.36, 2 forks × (3 × 1s warmup + 5 × 1s measurement), throughput in
ops/µs and allocation in bytes per operation.

| case | ops/µs | B/op |
|---|---|---|
| compareUnchanged | 3.736 ± 0.136 | 1696 |
| compareOneLeafChanged | 3.190 ± 0.450 | 1968 |
| compareKeyedList | 0.194 ± 0.017 | 31320 |
| compareSetAndMap | 1.951 ± 0.178 | 2840 |
| compareSixLevelsDeep | 4.634 ± 0.360 | 1624 |
| applyOneChange | 1.654 ± 0.108 | 4384 |
| applyKeyedListRoundTrip | 0.178 ± 0.016 | 21916 |
| trackNarrowScope | 0.230 ± 0.007 | 27336 |
| routeSixHandlers | 0.865 ± 0.021 | 1752 |

### Result

Same machine, same settings, after the change — remeasured as a whole suite once the code-review fixes
were in, so both columns come from comparable runs.

| case | ops/µs before → after | B/op before → after |
|---|---|---|
| compareUnchanged | 3.736 → 4.533 (**+21%**) | 1696 → 840 (**−50%**) |
| compareOneLeafChanged | 3.190 → 3.879 (**+22%**) | 1968 → 1552 (**−21%**) |
| compareKeyedList | 0.194 → 0.260 (**+34%**) | 31320 → 23896 (**−24%**) |
| compareSetAndMap | 1.951 → 3.509 (**+80%**) | 2840 → 1848 (**−35%**) |
| compareSixLevelsDeep | 4.634 → 7.568 (**+63%**) | 1624 → 1296 (**−20%**) |
| applyOneChange | 1.654 → 6.269 (**+279%**) | 4384 → 1320 (**−70%**) |
| applyKeyedListRoundTrip | 0.178 → 0.341 (**+92%**) | 21916 → 12280 (**−44%**) |
| trackNarrowScope | 0.230 → 0.438 (**+90%**) | 27336 → 14560 (**−47%**) |
| routeSixHandlers | 0.865 → 1.527 (**+77%**) | 1752 → 2072 (**+18%**) |

`applyKeyedListRoundTrip` is the noisiest case at ±0.091; the rest are within a few percent run to run.

Eight of nine improved on both axes. **`routeSixHandlers` did not improve on allocation** and is reported
as such: it is 77% faster but allocates 18% more. Grouping trades the per-handler filter lists for one
`HashMap` plus a list per named property, and the map is not free. Three rounds of tuning took it from
3136 B/op to 2072 — dropping the `IdentityHashMap` unless a handler actually declines something, not
grouping changes no handler named, and using a `HashMap` rather than a `LinkedHashMap` once it was clear
the map's own iteration order is never read — but the map itself remains. Accepted deliberately: routing
runs once per diff rather than once per change, so 320 bytes buys nearly double the throughput on the
path that dominates it.

The two keyed-list cases are also the two that improved least in relative terms, because both are
dominated by the per-element `Change` and `FieldPath` objects the comparison must produce, which this
change makes cheaper but does not remove.

*Alternative considered:* `kotlinx-benchmark`, which would keep the harness Kotlin-first and
multiplatform-ready. Rejected for now: this repo is Kotlin/JVM only, `me.champeau.jmh` is the thinner
wrapper over JMH proper, and `-prof gc` — the profiler that actually answers "did allocation drop" — is
JMH's own and reached most directly through it.

## Risks / Trade-offs

- **A widening tracking bug is the dangerous direction, and D6 rewrites exactly the code that once had
  one.** `CLAUDE.md` records that a real bug here silently reported the whole object, and that
  `TrackScopeCompositionSpec` exists to catch its return. → Addressed on three levels rather than by
  care: the `Everything` / `Named` split makes the conflation that caused the original bug
  inexpressible; the exhaustive differential oracle proves the new rule agrees with the old one over
  the whole interesting domain; and the standalone invariant test states the failure direction itself,
  so it catches a widening the oracle could only catch if the oracle were right. On top of those,
  `TrackScopeCompositionSpec`, `TrackingSpec`, `TrackedDiffSpec` and `SelectSpec` must pass
  **unchanged** — a test needing adaptation is evidence the selection moved, not a test that needs
  updating.

- **A `subList` view is a live view.** If any `FieldPath` were ever built over a list its creator keeps
  mutating, a derived path would change under the caller. → Audit is small and bounded: every
  `FieldPath` constructed inside `kdiff-runtime` is over a fresh `ArrayList`, `emptyList()` or
  `listOf(...)`. A caller-supplied mutable list was already aliased by the `FieldPath` value class
  itself before this change, so nothing new is exposed.

- **Empty-change fast paths could skip a check that mattered.** → The keyed-list duplicate rejection is
  the only such check the library has, and D7 puts the fast path physically after it rather than
  guarding it with a condition someone could later invert. The rule is now stated once in the spec
  ("carrying a property through skips its reconstruction, not a precondition its shape requires")
  rather than as an exception, so a future shape with a precondition inherits the right behaviour by
  default. `DuplicateKeySpec` in both `kdiff-runtime` and `kdiff-sample` must pass unchanged, and the
  new scenario *A keyed list with a repeated key is rejected even when no change addresses it* pins the
  interaction directly.

- **Folding duplicate detection into the map build could change what the error says.** The message is
  observable, and `require(false)` and an explicit `throw` are easy to make subtly different. → Both
  message branches move verbatim into `duplicateKey`, which still throws `IllegalArgumentException`;
  D5 records the two ordering properties that must hold (same key named, `before` rejected before
  `after`). The existing duplicate-key specs assert on the message and must pass unchanged.

- **Identity-based routing narrows what reaches `otherwise` in one corner.** Two changes equal in kind,
  path and values, where one is routed and one is not, previously both reached the fallback. → This is
  the more correct behaviour, it matches what `under` already relies on, and it is now a stated
  requirement with a scenario rather than an emergent property.

- **Snapshot processor tests will fail on the D9 shape change, and that is the intended signal.** →
  Update them deliberately, in the same task, and regenerate `kdiff-sample` so the committed
  expectations and the generated output cannot drift apart.

- **The benchmark harness adds a Gradle plugin, which is a build dependency the repo did not have.** →
  Contained: one module, unpublished, off the `check` path, one version-catalog entry. If the numbers
  turn out not to justify it the module can be dropped without touching a line of `kdiff-runtime`.

- **Micro-optimisation can cost readability, which this codebase values highly.** An `ArrayList` built
  with `also { }` reads worse than `listOf(x) + xs`. → Confined to `FieldPath`'s two lifting functions
  and the loop bodies of `Compare.kt`, each of which carries a short comment saying *why* the obvious
  form was not used — a non-obvious why, which is exactly what this project's comment rule permits.
  Nothing else in the runtime changes shape.

## Migration Plan

No consumer migration. The change is additive at the API level (one `internal` overload, one `private`
generated property), and everything else is an implementation change behind unchanged signatures.

A consumer picks it up by bumping the `kdiff-runtime` version; recompiling against the new processor is
needed only to get D9, and is not required for the runtime wins. Rollback is a version pin — there is no
data format, no persisted state and no wire protocol involved.

Ordering within the change matters in one place: the benchmark baseline must be captured before any
optimisation lands, or there is nothing to compare against.

## Open Questions

- **Exact `me.champeau.jmh` version.** Resolved at implementation time from the Gradle Plugin Portal
  rather than written from memory. It does not affect the specs, the approach or the task breakdown.
- **Whether D8 is worth its own change.** Deliberately left open; the deep-graph benchmark case exists
  to answer it, and the answer belongs in a follow-up proposal, not here.
