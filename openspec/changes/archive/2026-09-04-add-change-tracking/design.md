## Context

See `proposal.md — Why` for motivation and `specs/change-tracking/spec.md` +
`specs/diff-generation/spec.md` for the requirements this design has to satisfy.

The constraints that shape the approach, all of them already in the codebase:

- **Two routes to every capability, one consumed type.** `@Diffable` generates
  `object <Type>Differ : Differ<T>, Patcher<T>`; `differ { }` in `Dsl.kt` builds a `Differ<T>` by
  hand from `KProperty1` references. `diff-generation` requires the hand-written one be
  "indistinguishable from a generated one to any code that consumes it", and `@DiffWith` lets a
  generated differ delegate to one. The two compose in either direction.
- **The DSL/no-DSL line is drawn on construction, not on taste.** `Patcher`'s KDoc states it:
  *"Implemented by generated code, which is the only thing that can construct the type it serves. A
  hand-written differ built with the `differ { }` DSL can read properties but not construct their
  owner, so `Differ` deliberately does not require this."* `Patcher<T>` is still an open interface —
  `diff-application` requires a `@DiffWith` object that can patch to be used — but there is no
  builder for it, because a builder cannot know a constructor.
- **A `Diff` is a flat, ordered `List<Change>` with `FieldPath` locations.** `FieldPath` is a
  `@JvmInline value class` over `List<Segment>`; `Segment` is sealed with `Field(name)`,
  `Index(index)` and `Key(property, value)`. Paths are relative to the root and lifted by
  `prefixedWith` as nested results come back up.
- **`Change` is deliberately sealed and closed** — "so the change vocabulary stays closed: every kind
  of difference kdiff can report is declared here, and callers can handle them exhaustively".
- **No reflection anywhere.** `DifferBuilder` uses `kotlin.reflect.KProperty1` and only reads `.name`
  and calls `.get` — stdlib intrinsics, no `kotlin-reflect` artifact.
- **`explicitApi()` is on.** Every new public declaration needs explicit visibility and KDoc.
- **Annotation vocabulary.** `@Diffable` opts a class in; `@DiffIgnore` opts a property out;
  `@DiffKey` and `@DiffWith` qualify one property. There is no property-level opt-in to comparison.

## Goals / Non-Goals

**Goals:**

- Tracking is reachable by both routes, with the same type consumed at the end — the property
  `diff-generation` already demands of comparison.
- Tracking is a *consumer* of the existing pipeline. No change to `Differ.diff`, to `Patcher.apply`,
  to any `compare*` or `patch*` helper, or to the shape of an existing generated file.
- The selection-and-depth decision is a pure function of a `Change`'s `FieldPath` and the resolved
  scope, so it is unit-testable without constructing a single tracked object.
- Compile-time selection safety: a mistyped field should not compile, rather than silently match
  nothing.

**Non-Goals:**

- **Observing mutation.** No property delegates, no proxies, no bytecode. kdiff's model is immutable
  data classes; the tracker is fed instances.
- **Rolling a deep change up to a shallow one.** Decision D4.
- **A tracking escape hatch of its own.** Decision D3 — there is nothing for a `@TrackWith` to do.
- **Cross-instance identity.** A tracker follows one value, not a collection of tracked entities.
- **Thread safety.** Decision D9.
- **Coroutines or `Flow`.** A `Flow`-shaped API is a wrapper over `update` and belongs in its own
  change if it is wanted at all.

## Decisions

### D1. The three capabilities sit on one axis, and the axis is "does it construct?"

This is the decision the rest follow from, and it is already latent in the codebase — this change
makes it explicit and extends it.

| capability | interface | from an annotation | written by hand | why |
|---|---|---|---|---|
| compare | `Differ<T>` | `@Diffable` | `differ { }` builder | reads properties |
| apply | `Patcher<T>` | `@Diffable`, same object | `object : Patcher<T>`, no builder | must construct the owner |
| track | `Tracked<T>` | `@Trackable`, same object | `trackScope { }` builder | only names properties |

A capability gets a DSL *builder* exactly when it can be expressed by naming properties. Comparison
qualifies, so `differ { }` exists. Application does not, so no builder exists and `diff-application`
instead requires a hand-written patcher to be written out in full — with `unpatchable()` reporting
failures when a `@DiffWith` object can only compare.

Tracking names properties and depths and nothing else. It never reads a property value and never
constructs anything: it filters an already-computed `List<Change>` by path. So it lands squarely on
the comparison side and gets a full builder, and a type with no kdiff annotation anywhere can be both
compared (`differ { }`) and tracked (`trackScope { }`).

*Consequence for review:* tracking is the second capability in the library with a DSL builder, not
the first, and it is spec'd with the same "indistinguishable from a generated one" clause comparison
carries.

### D2. `TrackScope<T>` is the one type both routes produce

```kotlin
public data class TrackedField(public val name: String, public val depth: Int)

public class TrackScope<T> internal constructor(internal val fields: List<TrackedField>?)

public interface Tracked<T> { public val trackScope: TrackScope<T> }

public fun <T> trackScope(block: TrackScopeBuilder<T>.() -> Unit): TrackScope<T>
```

`@Trackable` makes the generated object implement `Tracked<T>`; `trackScope { }` builds the same
`TrackScope<T>` by hand. The tracker takes a `TrackScope<T>` and cannot tell which route produced it,
because there is nothing on the type to tell it with. That is exactly how `Differ<T>` behaves for
`differ { }` versus `@Diffable`.

`fields == null` means "no scope was declared" — distinct from an empty scope. Only the first means
"report everything", and keeping them distinct is what makes D6's resolution rule expressible.

*Alternative rejected:* having the generated object *be* a `TrackScope<T>`, for exact symmetry with
`Differ`. A scope is configuration, not a capability — a caller composes, narrows and reuses one, and
a generated singleton is the wrong lifetime for that. Exposing one keeps the generated object a
provider of values, which is also what makes a hand-written scope substitutable for a generated one
at any call site.

### D3. Tracking needs no escape hatch, because paths are already the interface

Comparison needs `@DiffWith` because a property's *type* may be uncomparable by kdiff's rules.
Tracking has no such failure mode: whatever produced a change, the change arrives with an ordinary
`FieldPath`, and selection and depth apply to it unchanged.

So a property compared by a hand-written differ — `@DiffWith(MoneyDiffer::class)`, reporting at
`total.amount` — is tracked by `under(Invoice::total)` with no special handling, and is excluded by
`depth = 1` like any other nested property. A compare-only differ, which makes the property
*unpatchable*, is still fully *trackable*. Spec'd as its own requirement, because it is the clearest
demonstration that the three capabilities compose rather than merely coexist.

There is deliberately no `@TrackWith`. Adding one would be an annotation with nothing to do.

### D4. Depth counts `Segment.Field` steps only, and filters rather than rolls up

`Segment.Index` and `Segment.Key` identify a *sibling within* a collection, not a level of nesting.
Counting them would make depth mean something different for a list field than for a plain one, and
`@Trackable(depth = 1)` on `addresses: List<Address>` would then report nothing at all — an added
element sits at `addresses[id=A3]`, two segments deep.

```
total                     -> 1 field step
addresses[id=A3]          -> 1 field step   (an added element)
addresses[id=A2].street   -> 2 field steps
billing.city              -> 2 field steps
amounts[key=eur]          -> 1 field step   (a map entry)
```

```kotlin
private fun FieldPath.fieldDepth(): Int = segments.count { it is Segment.Field }
```

A change the depth excludes is **dropped**, not reported at its nearest included ancestor. Two
reasons, in order of weight:

1. A roll-up is not expressible. Reporting "`billing` changed" needs a `Change` at `billing` — a
   `ValueChanged(billing, oldAddress, newAddress)`. The tracker holds both root instances, but
   reaching `oldAddress` from them means resolving the property at runtime, i.e. reflection. The
   alternative, a valueless `Change` variant, breaks the sealed vocabulary's closedness and forces
   every exhaustive `when` in every consumer to grow a branch — genuinely BREAKING, for a feature
   nobody asked for.
2. Filtering is what granularity means for the motivating case: *tell me when the order's own fields
   change, do not wake me for nested detail.*

Both are named requirements with scenarios, not emergent behaviour. *Trade-off, stated plainly:* a
caller who wants "something under `billing` changed" writes `under(Order::billing)` and coalesces in
their callback. A first-class roll-up is a separate change and will need a decision about where the
values come from.

### D5. Selection is typed at the call site, string-keyed underneath — as `differ { }` already is

```kotlin
public class TrackScopeBuilder<T> internal constructor() {
    public var depth: Int = UNLIMITED_DEPTH
    public fun field(property: KProperty1<T, *>) { add(property.name, 1) }
    public fun field(property: KProperty1<T, *>, depth: Int) { add(property.name, depth) }
    public fun under(property: KProperty1<T, *>) { add(property.name, UNLIMITED_DEPTH) }
}
```

`.name` on a property reference is a stdlib intrinsic — exactly what `DifferBuilder.field` does, and
why the existing DSL needs no `kotlin-reflect`. The caller gets compile-checked, refactor-safe
selectors; the matcher gets a `String` to compare against `Segment.Field(name).name`, which is what
the paths were built from in the first place (`compareValue("reference", …)`).

The annotation route produces the identical `TrackedField` list, so both scopes are the same data
through the same matcher. There is no second code path to keep honest.

*Alternative rejected:* string path patterns (`"lines[*].qty"`). More expressive, but a typo matches
nothing silently — the exact failure mode tracking exists to remove, and a departure from how
`differ { }` selects. If wildcards are ever needed, the natural extension is `matching { path: FieldPath -> Boolean }`
over the existing `Segment` model, which composes with this design.

### D6. Scope resolution is one rule

> Naming a property at the call site replaces the declared scope entirely. A `depth` stated at the
> call site overrides the *declared* depths.

A scope therefore carries two things, and both have a "not stated" state: `fields` (null = name no
property, so track them all) and `depth` (null = state no depth). Keeping "not stated" distinct from
`UNLIMITED_DEPTH` is what lets a caller explicitly widen a type that declared `depth = 1`.

```kotlin
internal fun <T> TrackScope<T>.resolveAgainst(differ: Differ<T>): ResolvedScope {
    fields?.let { return ResolvedScope(it, depth ?: UNLIMITED_DEPTH) }

    val declared = (differ as? Tracked<T>)?.trackScope
    val declaredFields = declared?.fields
    val effective = when {
        depth != null && declaredFields != null -> declaredFields.map { it.copy(depth = depth) }
        else -> declaredFields
    }
    return ResolvedScope(effective, depth ?: declared?.depth ?: UNLIMITED_DEPTH)
}
```

`ResolvedScope(fields = null, depth)` is "every property, to `depth`" — the case a single nullable
field list cannot express, since a scope-wide depth has to survive into the matcher.

A call-site `depth` deliberately does *not* touch the depth of a property named at that same call
site: `field(p)` means "p itself" and keeps saying so. Overriding only the declared depths is what
"a property's own declared depth takes precedence over the scope's" requires, and the two rules would
contradict each other if `depth` reached across both.

*Alternative rejected:* merging declared and call-site fields. "Narrow" and "widen" then need
separate verbs, and a reader can no longer look at a call site and know what will fire.

### D7. Callbacks: the `Change` → `(path, before, after)` mapping is exhaustive by construction

```kotlin
private fun Change.sides(): Pair<Any?, Any?> = when (this) {
    is ValueChanged -> before to after
    is TypeChanged -> before to after
    is Added -> null to value
    is Removed -> value to null
    is Moved -> from to to
}
```

A `when` with no `else`, so a sixth `Change` variant would be a compile error here rather than a
silent drop. `Moved` yielding its two indices is the debatable one: `from`/`to` genuinely *are* that
change's old and new state, and the alternative — omitting moves — drops information without telling
anyone. The batched callback carries the typed `Change`, so a caller needing to distinguish a move
has an exact route.

The batched callback fires only for a non-empty match, so "it was called" means "something I asked
about changed" — otherwise every caller writes the same `if (changes.isEmpty()) return`.

### D8. `update` returns a `Diff`, which makes track and patch compose for free

`Tracker.update` returns the filtered changes as a `Diff` — the same type `Patcher.apply` consumes.
Nothing needs building for this; it falls out of reusing the existing types rather than inventing a
tracking-specific result. What it buys:

```kotlin
val tracker = tracker(OrderDiffer, local) { field(Order::reference) }
val diff = tracker.update(incoming)
val synced = OrderDiffer.apply(local, diff.changes)   // only `reference` propagated
```

An unrestricted scope reproduces the target exactly, as `diff-application` requires of any diff. A
narrowed scope reproduces the tracked part and leaves the rest at baseline — selective propagation as
a property of the design rather than something a caller assembles. No failures are reported either
way, because an excluded change was never in the report; `groupByProperty`/`unmatchedFailures` only
report changes that *are* present and match no compared property.

Both are requirements with scenarios, and the sample gets a round trip alongside the existing
`RoundTripSpec` — so track-then-patch is a tested invariant, not a claim in a design document.

### D9. Runtime holds the algorithm; the processor emits only data

`kdiff-runtime` gets the tracker, both builders and the filter. The processor emits nothing but a
`List<TrackedField>` behind `Tracked<T>`.

Why: the same split `Compare.kt` documents — "they live here rather than being generated so that the
comparison algorithms exist once, where a fix ships as a dependency bump instead of a recompile of
every consumer". A bug in depth counting must be fixable by bumping `kdiff-runtime`, not by
recompiling every annotated class downstream. It also keeps the generated file boring, an explicit
project goal, and it is what makes a hand-written scope and a generated one genuinely the same thing
(D2) rather than two parallel implementations.

Generated code calls a runtime factory rather than constructing the scope inline, mirroring how
generated `diff` bodies call `compareValue` and generated `apply` bodies call `patchValue`.

*Alternative rejected:* generating a per-type `OrderTracker` with typed callbacks. Better per-field
typing, but it multiplies generated code by the number of tracked properties, bakes the filter into
every consumer, and gives the processor a second, larger emission path to maintain.

### D10. The tracker is mutable and single-threaded

`Tracker.update` reads the baseline, diffs, dispatches, and writes the baseline. Making that safe
would mean a lock held across user callbacks — an invitation to deadlock — or a compare-and-set retry
that re-runs them.

So: documented as not thread-safe, like a `MutableList`. `TrackScope` is immutable and freely shared;
a caller needing concurrency confines the tracker.

### D11. Annotations mirror the comparison annotations exactly

| comparison | tracking | role |
|---|---|---|
| `@Diffable` (class) | `@Trackable(depth)` (class) | opt the class in |
| `@DiffIgnore` (property) | `@TrackIgnore` (property) | opt a property out |
| `@DiffKey`, `@DiffWith` (property) | `@TrackDepth(n)` (property) | qualify one property |
| — | — | no property-level opt-in |

Dropping property-level opt-in is a deliberate simplification driven by this symmetry: with
`@Diffable`, the class opts in and properties opt out, and tracking has no reason to differ. It also
removes a diagnostic — there is no longer a "property opted in on a class that did not" case, because
`@TrackIgnore`/`@TrackDepth` simply require `@Trackable`.

`UNLIMITED_DEPTH = -1` lives in `kdiff-annotations` (an annotation default must be a compile-time
constant there) and is re-exported from `kdiff-runtime` for callers using only the DSL.

### D12. A tracking annotation that could do nothing is a compile error

The project's rule is that unsupported shapes are compile errors, never silent fallbacks — and
`diff-generation` already applies it to an uncomparable property, a generic `@Diffable`, a sealed type
with an unannotated subclass, and two `@DiffKey`s. Tracking gets the same treatment:

- `@Trackable` without `@Diffable` — no declaration is generated, so nothing could expose the scope.
- `@TrackIgnore` or `@TrackDepth` on a property of a class that is not `@Trackable` — the author
  believes they have configured tracking and has done nothing.
- `@TrackIgnore` with `@TrackDepth` — one excludes, the other configures within.
- `@TrackIgnore` or `@TrackDepth` with `@DiffIgnore` — an ignored property yields no changes, so both
  mechanisms would stay silent.
- `depth` of `0`, or negative other than `UNLIMITED_DEPTH`.

Each message names the offending declaration and points at the annotation that is missing, and each
is reported at that declaration via `KSPLogger.error(message, symbol)`.

### D13. Type resolution: the processor's new work is orthogonal to it

The rules the project requires a design to cover are all resolved by `DiffProcessor.resolve()` into a
`Comparison`, and this change touches none of it. The new emission consumes only the *names* of the
properties `comparableProperties()` already yields:

- **Nullability** — irrelevant to a name. A nullable nested property reports a `ValueChanged` at its
  own path when either side is null (existing behaviour): 1 field step, reported at depth 1.
- **Generics** — irrelevant to a name; `@Diffable` already rejects a generic class.
- **Collections and maps** — resolution unchanged; the consequence for tracking is D4, and it is
  spec'd (element identity does not consume depth).
- **Enums** — compared by value, 1 field step, nothing special.
- **Nested `@Diffable`** — the reason depth exists. The nested differ reports relative paths and the
  parent lifts them; the tracker only reads the resulting path.
- **Sealed types** — a subclass swap at the tracked root reports at `FieldPath.ROOT`: zero field
  steps, no first segment to match a field against. Hence the requirement that a root-path change is
  always reported — it is unattributable to any field, and suppressing it would hide the tracked
  object being replaced wholesale. A `@Trackable` sealed parent's scope covers the properties it
  declares itself.
- **`@DiffIgnore`** — not in `comparableProperties()`, so it cannot enter a scope and produces no
  change to filter. Tracking it is an error precisely because both mechanisms would otherwise stay
  silent (D12).
- **`@DiffWith`** — D3. No interaction beyond ordinary path filtering.

### D14. Incremental processing is unchanged

Each generated file keeps `Dependencies(aggregating = false, originatingFile)`.

The emitted scope derives from exactly two sources, both already dependencies of that file: the
annotated class's own declarations and the annotations on them. It never consults another file — a
nested `@Diffable` type's own scope is *not* read, because a scope describes the class it is declared
on and depth is resolved at match time from the path. So no generated file becomes aggregating, and
touching `Address.kt` still does not force `OrderDiff.kt` to regenerate for tracking reasons.

### D15. Generated shape

Reviewable before implementation. `OrderDiffer` today is
`object OrderDiffer : Differ<Order>, Patcher<Order>`; with `@Trackable(depth = 1)` on `Order` and
`@TrackDepth(2)` on `billing`:

```kotlin
public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order> {
  override val trackScope: TrackScope<Order> = trackScopeOf(
    TrackedField("reference", 1),
    TrackedField("status", 1),
    TrackedField("note", 1),
    TrackedField("billing", 2),
    TrackedField("addresses", 1),
  )

  override fun diff(before: Order, after: Order): Diff = Diff(
    buildList<Change> {
      compareValue("reference", before.reference, after.reference)
      // ... unchanged
    },
  )

  override fun apply(before: Order, changes: List<Change>): PatchResult<Order> {
    // ... unchanged
  }
}
```

`trackScopeOf` is a `kdiff-runtime` factory, as `compareValue` and `patchValue` are (D9). A class with
no `@Trackable` gets no supertype and no property — byte-for-byte the file it gets today, which is
what makes the change additive rather than BREAKING.

### D16. Runtime shape

```kotlin
// TrackScope.kt — the scope, both routes
public data class TrackedField(public val name: String, public val depth: Int)
public class TrackScope<T> internal constructor(internal val fields: List<TrackedField>?)
public interface Tracked<T> { public val trackScope: TrackScope<T> }
public fun <T> trackScope(block: TrackScopeBuilder<T>.() -> Unit): TrackScope<T>
public fun <T> trackScopeOf(vararg fields: TrackedField): TrackScope<T>   // called by generated code
public class TrackScopeBuilder<T> internal constructor() {
    public var depth: Int
    public fun field(property: KProperty1<T, *>)
    public fun field(property: KProperty1<T, *>, depth: Int)
    public fun under(property: KProperty1<T, *>)
}

// Track.kt — the observer
public class Tracker<T> internal constructor(...) {
    public val current: T
    public fun update(next: T): Diff
    public fun reset(baseline: T)
}
public fun <T> tracker(differ: Differ<T>, initial: T, scope: TrackScope<T>, block: TrackerBuilder<T>.() -> Unit): Tracker<T>
public fun <T> tracker(differ: Differ<T>, initial: T, block: TrackerBuilder<T>.() -> Unit): Tracker<T>
public class TrackerBuilder<T> internal constructor() {
    public var depth: Int
    public fun field(property: KProperty1<T, *>)
    public fun field(property: KProperty1<T, *>, depth: Int)
    public fun under(property: KProperty1<T, *>)
    public fun scope(scope: TrackScope<T>)
    public fun onFieldChange(listener: (FieldPath, Any?, Any?) -> Unit)
    public fun onChange(listener: (T, T, List<Change>) -> Unit)
}
```

`TrackerBuilder` repeats the three selector verbs so the common case is one call —
`tracker(OrderDiffer, order) { field(Order::total); onChange { … } }` — and `scope(…)` takes a
prebuilt or generated one for reuse. Both delegate to the same `TrackScopeBuilder`, so there is one
implementation of each verb.

`trackScope { }` goes in `Dsl.kt` beside `differ { }`: they are the hand-written half of the library
and belong in one file, which is also where a reader looking for "how do I do this without
annotations" will look.

`Select.kt` is `internal`: the filter and `Change.sides()`. Tested through the tracker, so the matcher
stays free to change.

## Risks / Trade-offs

- **Depth dropping surprises someone who expected a roll-up** → a named requirement with scenarios,
  one sentence of KDoc on `depth`, and `under(...)` as the documented way to get a subtree. Revisiting
  it is a separate change (D4).
- **`Moved` delivering `Int`s through an `Any?` slot reads oddly** → documented as a table in the spec
  and in KDoc; the batched callback carries the typed `Change`. The rejected alternative loses moves
  silently (D7).
- **`Tracked<T>` on the generated object is public API and hard to change later** → one property of
  one class, deliberately the smallest thing that can carry a scope. Everything that could plausibly
  change — matcher, resolution, dispatch — sits behind it in `kdiff-runtime` (D9).
- **Two builders (`TrackScopeBuilder`, `TrackerBuilder`) share three verbs** → the second delegates to
  the first; the verbs have one implementation. The alternative, one builder, forces every call site
  through `scope(trackScope { … })` for the common case.
- **A property is renamed and the emitted `TrackedField` string goes stale** → it cannot: the string
  is emitted from the property declaration at compile time, in the same file and compilation. The DSL
  is typed. Neither can drift.
- **The tracker is not thread-safe and someone shares one** → documented on the class; `TrackScope` is
  immutable and shareable (D10).
- **Five new diagnostics is five new ways to fail a build that used to compile** → all five require an
  annotation that does not exist yet, so no existing code can trip them. Each gets a kctfork scenario
  asserting the message and its location.
- **`@Trackable` alongside `@Diffable` is two annotations where one might do** → deliberate. Making
  `@Diffable` imply tracking would put a scope on every generated object whether or not the author
  wants one, and would remove the author's ability to say "compare this, do not track it". The pairing
  matches `@Diffable` + `@DiffKey`, which is already two annotations on one class for related reasons.

## Migration Plan

None needed. Purely additive: no existing annotation changes meaning, no generated file changes for a
class that does not adopt `@Trackable`, and no consumer must recompile or adapt. Rollback is reverting
the change; nothing persists state and nothing is written to disk at runtime.
