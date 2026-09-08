# Errors

Every message kdiff produces, quoted as it appears, with what to change. If you have an error in
front of you, search this page for its text.

kdiff reports a problem at one of four moments, and which one you are at decides what you can do
about it:

```
   +---------------------------------------------------------------------+
   | 1  COMPILE TIME        the build fails, KSP names a declaration     |
   |                        eighteen diagnostics -- your model or your   |
   |                        annotations. Nothing runs until you fix it.  |
   +---------------------------------------------------------------------+
   | 2  CONSTRUCTION        building a differ, scope or routing throws   |
   |                        six messages, all IllegalArgumentException   |
   |                        Raised before any comparison happens.        |
   +---------------------------------------------------------------------+
   | 3  APPLYING, REPORTED  apply() declines a change and carries on     |
   |                        fourteen PatchFailure.Reason cases           |
   |                        You still have a usable value.               |
   +---------------------------------------------------------------------+
   | 4  APPLYING, REFUSED   there is no result to hand back              |
   |                        three declared exception types               |
   +---------------------------------------------------------------------+
```

Tiers 1 and 2 are always something to change in your code. Tier 3 is usually a diff meeting a source
it did not come from. Tier 4 is an input kdiff will not interpret.

## 1. Compile time

Every one is a `KSPLogger` error reported at the offending declaration, so your IDE and your build
log both point at the right line. An unsupported shape is never a silent fallback. A single rejected
declaration fails the whole build, and the failure names it rather than a valid class alongside it.

`<Type>`, `<prop>` and `<n>` below stand for the names your code supplies.

### `@Diffable` on the wrong declaration

> `@Diffable is only supported on data classes and sealed types; <Type> is <kind>`

A plain class, an interface, an enum class or an annotation class. Make it a data class, or
— if the type is not yours — leave it unannotated and point the property at a hand-written differ with
[`@DiffWith`](annotations.md#diffwith).

> `@Diffable is only supported on data classes and sealed types; <Type> is an object, and an object in a @Diffable sealed hierarchy needs no annotation of its own`

An `object` gets the extra clause because that is the one place the annotation is reached for and not
needed: an `object` or `data object` subclass of a `@Diffable` sealed type is dispatched on with no
annotation at all. Delete it. See [sealed types](diffing.md#sealed-types).

> `@Diffable does not support type parameters; <Type> is generic`

A differ for a generic type would need a differ per type argument, which cannot be resolved at the
declaration. Annotate the concrete types you actually compare.

> `@Diffable on a sealed type requires every subclass to be @Diffable; <Type> has <subclasses>`

The generated differ dispatches on the runtime subclass, so it needs a differ for each. Annotate the
subclasses it names. See [sealed types](diffing.md#sealed-types).

### A property kdiff cannot compare

> `kdiff cannot compare <prop> of type <T>; annotate its type with @Diffable or point the property at a hand-written differ with @DiffWith`

> `kdiff cannot compare elements of <prop> of type <T>; annotate that type with @Diffable or point the property at a hand-written differ with @DiffWith`

The first is the property itself; the second is the element type of a collection or the value type of
a map. kdiff compares primitives, `String`, enums, `@Diffable` types, and collections, sets and maps
of those. Anything else needs one of the two routes the message names — and comparing a rich type as
an opaque value is deliberately *not* offered, because it produces a diff that is technically correct
and useless.

Both are the escape hatch's entry point: see [hand-written differs](hand-written.md).

### A collection whose elements are nullable and reached through a differ

> `kdiff cannot compare <prop>: its elements are nullable <T>, and elements compared by a differ cannot be null; declare them non-null, or point the property at a hand-written differ with @DiffWith`

> `kdiff cannot compare <prop>: its values are nullable <T>, and values compared by a differ cannot be null; declare them non-null, or point the property at a hand-written differ with @DiffWith`

`List<Address?>` and `Map<String, Address?>`, where `Address` is `@Diffable`. A differ takes an
instance, so there is nothing for it to compare a null element against, and a keyed list could not
read a key off one either.

Three neighbouring shapes are **not** affected. A nullable element compared as a *value* —
`List<String?>` — is fine, because equality is defined for null. Any `Set` is fine whatever its element
type, because a set is compared by membership alone. And a nullable *collection* —
`List<Address>?` — is fine: that is [the null rule](diffing.md#values-enums-and-nullables), reported as
one change at the property.

### `@DiffWith` that cannot work

> `@DiffWith needs a differ class`

The annotation was written without its argument.

> `@DiffWith requires an object; <Name> is not one`

`differ` must name an `object`, not a class — kdiff has to reach the instance without constructing it.
`object Foo : Differ<T> by differ({ … })` is the shape.

> `@DiffWith on <prop> names <Name>, which does not implement Differ of that property's type`

The object compares something else. Check its type argument against the property's declared type.

If the named object implements only `Differ`, that compiles — and leaves the property
[unpatchable](patching.md#what-produces-a-failure), which is a runtime report, not a compile error.

### More than one key

> `a type may declare at most one @DiffKey; <Type> declares <props>`

A path names a keyed element by one key value, so a second key has nothing to mean. Keep one; if
elements are identified by a composite, make that composite a property.

### Comparison annotations that could do nothing

These two exist for the reason the tracking ones below do: the alternative is an annotation that
silently does nothing while its author believes comparison is configured.

> `@<DiffKey|DiffIgnore|DiffWith> on <prop> requires @Diffable on <Type>; without a generated differ it has no effect`

The three comparison annotations are read only off a `@Diffable` class. On any other class nothing
reads them, so the property is not keyed, not ignored and not pointed anywhere — it is simply not
compared, because nothing about that class is. Add `@Diffable` to the class, or remove the annotation.

> `@DiffWith on <prop> conflicts with @DiffIgnore; an ignored property is never compared`

`@DiffIgnore` excludes the property from comparison, so a differ named for it could never run. Pick
one.

`@DiffKey` beside `@DiffIgnore` is deliberately **not** a conflict, and compiles: a key identifies an
element, while ignoring the same property excludes it from that element's *own* comparison — which is
what you want, since two elements matched by key are equal in it by construction. The
[tutorial](tutorial.md) does exactly this.

### Tracking annotations that could do nothing

These five exist because the alternative is an annotation that silently does nothing while its author
believes tracking is configured.

> `@Trackable requires @Diffable; <Type> is not @Diffable`

Tracking reports changes, and nothing generates changes for an unannotated type. Add `@Diffable`.

> `@<TrackIgnore|TrackDepth> requires a @Trackable class; the class declaring <prop> is neither @Diffable nor @Trackable, and needs both`

> `<@TrackIgnore|@TrackDepth> on <prop> requires @Trackable on <Type>; without a declared scope it has no effect`

A property opts *out* of a scope its class declares. With no declared scope there is nothing to opt
out of. Add `@Trackable` to the class — or drop the property annotation and
[narrow at the call site](tracking.md#scopes-at-the-call-site) instead.

> `<@TrackIgnore|@TrackDepth> on <prop> conflicts with @DiffIgnore; an ignored property produces no changes and so can never be tracked`

`@DiffIgnore` already excludes the property from comparison, so no change about it ever exists. Remove
the tracking annotation, or remove `@DiffIgnore` if you did mean to compare it.

> `@TrackIgnore and @TrackDepth conflict on <prop>; one excludes the property from the scope and the other configures it within it`

Pick one.

### An invalid depth

> `<@Trackable|@TrackDepth> on <name> declares depth <n>; depth must be at least 1, or UNLIMITED_DEPTH (-1)`

Depth counts property steps and a scope reporting zero of them would report nothing. Use `1` or more,
or `UNLIMITED_DEPTH` to exclude nothing. See [depth](tracking.md#depth).

## 2. Building a differ, a scope or a routing

A hand-written differ has no compile step to reject it, so these are checked where the differ, scope
or routing is declared — before any comparison runs, and before a tracker can hold it. All six are
`IllegalArgumentException`.

> `a differ compares something: name a property, or declare a subtype to dispatch on`

`differ<T> { }` with an empty block would report every pair of instances as equivalent however much
they differ. Name a `field`, `nested`, `list`, `keyedList`, `set` or `map` — or a `subtype`, since
dispatching on the runtime subclass is itself a comparison. See
[the whole vocabulary](hand-written.md#the-whole-vocabulary).

> `a scope names the properties it tracks or the properties it excludes, never both`

`field` and `except` say opposite things about every property named in neither, and a precedence rule
between them would silently decide which one widens the scope. Use one or the other. See
[scopes at the call site](tracking.md#scopes-at-the-call-site).

> `depth must be at least 1, or UNLIMITED_DEPTH; was <n>`

> `<name>: depth must be at least 1, or UNLIMITED_DEPTH; was <n>`

The same rule as the annotation, checked wherever a depth is stated: the first for a scope's own
depth, the second for one property's.

> `<property> is named by more than one handler`

Two handlers in one routing name the same property, so which of them should run is undecidable. A
handler already runs **once** per routing however many changes lie beneath its property — so a second
`on(…)` for the same property is not how you handle two of its changes. See
[deciding what a change means](diffing.md#deciding-what-a-change-means-route).

> `a routing declares one otherwise handler; this is the second`

`otherwise` is the single backstop for everything no handler named at any depth. A second one would
split that, leaving it undecided which sees an unclaimed change.

## 3. Changes that did not apply

`apply` is partial, never all-or-nothing: what can be applied *is* applied, `value` is always usable,
and `failures` says what is missing. Each `PatchFailure` carries the `Change` it declined and a
`reason`, and renders as `<path>: <sentence>`.

`PatchFailure.Reason` is sealed and closed, with fourteen cases. They fall into five causes, and the
cause is what tells you what to do:

```
   your model cannot rebuild it        ->  change the declaration
   ----------------------------------------------------------------------
   the path names nothing compared     ->  the change is stale or foreign
   the change's shape does not fit
   the target is not there
   ----------------------------------------------------------------------
   the elements have no identity       ->  give them a @DiffKey, or accept
                                           add-and-remove
```

Every case below is covered by a test in `kdiff-runtime`'s `PatchFailureCaseSpec`.

### Cause A — your model cannot rebuild that property

Two declaration problems. Both are fixed in your code, and both keep patching the rest of the
instance.

| Case | Renders as | What to change |
|---|---|---|
| `NotConstructorProperty(property)` | `<property>: only constructor properties can be reconstructed` | reconstruction goes through `copy`, so a property declared in the class body cannot be rebuilt — make it a constructor parameter |
| `UnpatchableProperty(property)` | `<property> is compared by a differ that cannot patch` | the property's `@DiffWith` object implements only `Differ` — [give it a `Patcher`](hand-written.md#adding-patcher-by-hand) |

### Cause B — the path names nothing this type compares

| Case | Renders as | What it means |
|---|---|---|
| `UnknownProperty(type)` | `<type> has no compared property at this path` | the change is hand-built, stale, or came from a different type. A `@DiffIgnore` property lands here too: it is not compared, so it is not reconstructed |

### Cause C — the change's shape does not fit the property's

The path addresses a property in a way its comparison shape cannot express — a change that diffing
this type would never have produced.

| Case | Renders as |
|---|---|
| `NotApplicableToValue` | `not applicable to a value property` |
| `NotApplicableToKeyedList` | `not applicable to a keyed list` |
| `NotApplicableToPositionalList` | `not applicable to a positional list` |
| `NotApplicableToMap` | `not applicable to a map` |

### Cause D — the target is not there to patch

The key, index or entry the change names is absent from the source. Almost always a diff applied to a
source it did not come from.

| Case | Renders as |
|---|---|
| `NothingBeneathNull` | `nothing to patch beneath a null property` |
| `NoElementForKey` | `no element with this key to patch` |
| `NoElementAtIndex` | `no element at this index to patch` |
| `NoEntryForKey` | `no entry with this key to patch` |

### Cause E — the container's elements have no patchable identity

| Case | Renders as | What to change |
|---|---|---|
| `ElementComparedAsValue` | `element is compared as a value` | the element type is compared as an opaque value — annotate it `@Diffable` |
| `EntryComparedAsValue` | `entry value is compared as a value` | as above, for a map's values |
| `SetElementNotModifiable` | `a set element cannot be modified in place` | a set element has no identity, so it can be added or removed but never modified. Use a keyed list if elements need to change |

### Branching on a cause

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
private enum class Cause { CANNOT_REBUILD, UNKNOWN_PATH, WRONG_SHAPE, TARGET_ABSENT, NO_IDENTITY }

private fun PatchFailure.Reason.cause(): Cause = when (this) {
    is PatchFailure.Reason.NotConstructorProperty, is PatchFailure.Reason.UnpatchableProperty ->
        Cause.CANNOT_REBUILD

    is PatchFailure.Reason.UnknownProperty -> Cause.UNKNOWN_PATH

    PatchFailure.Reason.NotApplicableToValue,
    PatchFailure.Reason.NotApplicableToKeyedList,
    PatchFailure.Reason.NotApplicableToPositionalList,
    PatchFailure.Reason.NotApplicableToMap,
    -> Cause.WRONG_SHAPE

    PatchFailure.Reason.NothingBeneathNull,
    PatchFailure.Reason.NoElementForKey,
    PatchFailure.Reason.NoElementAtIndex,
    PatchFailure.Reason.NoEntryForKey,
    -> Cause.TARGET_ABSENT

    PatchFailure.Reason.ElementComparedAsValue,
    PatchFailure.Reason.EntryComparedAsValue,
    PatchFailure.Reason.SetElementNotModifiable,
    -> Cause.NO_IDENTITY
}
```

The vocabulary is closed so that a `when` like this can be exhaustive and stay that way. Adding a
fifteenth case is therefore a breaking change, on the same terms as a sixth `Change` variant.

## 4. What kdiff refuses outright

Three declared types. Each carries the facts as properties, so you can act on which list, which key
or which path was at fault instead of matching the message.

There is deliberately no common supertype: a marker interface cannot be caught, and a shared class
would have to root at one stdlib type and misfile the other.

### `DuplicateDiffKeyException`

An `IllegalArgumentException`, carrying `property`, `keyProperty` and `key`.

> `<property> is keyed by <keyProperty>, but two elements share the key <key>. A keyed element must be uniquely identified; <property>[<keyProperty>=<key>] cannot name one of them.`

> `two elements of a keyed list share the key <key>, and a keyed element must be uniquely identified.`

The second form appears when the caller had no name to give — a hand-written `Patcher` calling a
runtime helper directly.

Two elements sharing a `@DiffKey` value cannot be told apart, so the list has no representable diff
and no rebuildable form. Comparing and applying refuse it identically, and it happens whatever you
are applying, an empty change list included.

**Declaring the list nullable does not change this.** Whichever side is present is examined, so the
refusal reaches the transition that merely reports the property appearing or disappearing, and it
reaches an apply that is about to replace the list wholesale. A nullable declaration refuses whatever
its non-null twin refuses. A `null` has nothing to examine and is never refused.

**Uniqueness is a property of your data, not of your declaration**, which is why this cannot be a
compile error. If your key genuinely is not unique it is not an identity: drop `@DiffKey`, or describe
the property with `list` rather than `keyedList`, and the list is compared by position instead —
giving up moves. See [lists](diffing.md#lists).

### `CyclicStructureException`

An `IllegalArgumentException`, carrying `path` and `repeated`.

> `kdiff stopped at <where> after 512 steps: the same instance was reached again along that path, so the structure contains a cycle.`

> `kdiff stopped at <where> after 512 steps. No instance was seen twice in the steps before the limit, so this is a structure deeper than kdiff descends rather than a cycle it recognised.`

`<where>` is the path the descent stopped at, or `the root`. `repeated` distinguishes the two: a model
that is wrong, versus a bound that is too low. Identity is only recorded near the bound, so a cycle
longer than that stretch reports the second message — which says no repeat was *observed*, not that
there is none.

Comparing and applying descend at most `MAX_DESCENT` (512) levels, which is what makes a cycle a
diagnosis rather than a `StackOverflowError`. The guard does not make a cyclic graph comparable; it
reports the cycle and stops. A self-reference through a nullable property is not a cycle and compares
happily — `Node(name, next: Node?)` reports at `next.next.name`.

One caveat: the bound lives in kdiff's own helpers. A generated differ always goes through them and a
`differ { }` one does too, but a hand-written `object : Differ<T>` that calls another differ's `diff`
directly bypasses the bound and can still overflow. Delegate through `compareNested` instead.

### `PatchFailedException`

An `IllegalStateException` — a caller asking a partial result for a value it has not got — carrying
every `failures` entry.

> `<n> of the changes could not be applied: <failures, joined with "; ">`

Raised only by `PatchResult.getOrThrow()`. `apply` itself always returns the partial result.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            val raised = shouldThrow<PatchFailedException> { OrderDiffer.apply(order, changes).getOrThrow() }

            raised.failures.map { it.reason } shouldContainExactly
                listOf(PatchFailure.Reason.UnpatchableProperty("weight"))
```

Both `IllegalArgumentException` subtypes above were introduced where the runtime already raised that
type, so a `catch` written before they were declared still fires.

## Where to go next

- [How do I…](how-to.md) — the recipes, including handling failures by cause
- [FAQ](faq.md) — the questions these errors most often turn out to be
- [Annotation reference](annotations.md) — every annotation and what it rejects
- [Patching](patching.md) — why the result is partial, and the round-trip guarantee
- [Hand-written differs and scopes](hand-written.md) — the escape hatch several messages point at
- [Architecture](architecture.md) — why an unsupported shape is a compile error rather than a fallback
