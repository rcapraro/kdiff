## Context

See `proposal.md` — *Why*. Four facts about the current code shape the approach.

**Nullability is read in one place and consulted for one shape.** `resolve()` in `DiffProcessor.kt`
calls `property.type.resolve()` once and classifies the type by its *declaration*: `isList()`,
`isSet()` and `isMap()` compare the declaration's qualified name, so `List<String>?` and
`List<String>` classify identically. `isMarkedNullable` is read later, in `emit` and `emitPatch`, and
only for `Comparison.Nested`. So a nullable collection reaches the collection emitters, which pass
`before.tags` to a helper whose parameters are non-null.

**The null rule already exists for nested types.** `compareNestedNullable` and `patchNestedNullable`
state it: both null is nothing, one null is a `ValueChanged` at the property, both present delegates.
The requirement *A nullable property reports a null on either side as a value change* is the written
form. Nothing about that rule is specific to nested types.

**The tracking annotations have the check the comparison annotations lack.**
`reportTrackingWithoutDiffable` asks the resolver for every `@Trackable`, `@TrackIgnore` and
`@TrackDepth` symbol and reports the ones whose class is not `@Diffable`. `@DiffKey`, `@DiffIgnore`
and `@DiffWith` are only ever read *from* a `@Diffable` class, so on any other class they are never
looked at.

**A sealed differ is a `when` over `getSealedSubclasses()`.** `sealedBody` emits one
`before is S && after is S -> addAll(SDiffer.diff(before, after).changes)` branch per subclass and
requires each to be `@Diffable`, because each branch names a differ. `isSupported()` rejects an
`object` before either check runs.

## Goals / Non-Goals

**Goals**

- A shape the processor accepts compiles, and a shape it does not accept is one diagnostic at the
  property or class. No error ever originates in a generated file.
- The null rule is one rule: nullable collections behave exactly as nullable nested types, and a reader
  who knows one knows the other.
- Generated code for every class that compiles today is byte-identical.
- The hand-written route gains nullable collections through its existing builders, with no new member.

**Non-Goals** (beyond the proposal's exclusions)

- A `Change` variant, a `PatchFailure.Reason` case, or a `Segment` kind. Everything here is expressed
  in the existing vocabulary.
- Reading nullability of a collection's *elements* for anything but the diagnostic.

## Decisions

### D1 — The compare helpers accept nullable sides; no `compare*Nullable` twins

`compareKeyedList`, `comparePositionalList`, `compareSet` and `compareMap` take `before` and `after`
as `List<T>?`, `Set<T>?` and `Map<K, V>?`, and open with the three-way null test that
`compareNestedNullable` performs. The nested pair is left as it is.

Why widen rather than add four `…Nullable` helpers: the JVM signature of `List<T>` and `List<T>?` is
the same, so widening is binary-compatible and the ABI dump does not move; every existing call site,
generated or hand-written, compiles unchanged; and the generated line for a nullable list is the *same*
line as for a non-null one — `comparePositionalList("tags", before.tags, after.tags, null)` — which is
what keeps the generated file readable. Four more public helpers would double the collection vocabulary
to express one rule.

Why not do the same for the nested pair: `compareNested` takes `T` with no bound, so widening it would
mean a `T?` overload that collides with itself. It already has its nullable twin, and that twin becomes
the *only* place the rule is written twice. Acceptable: the alternative is a breaking rename.

The null branch takes no descent step. Nothing recurses.

```kotlin
public fun <T> MutableList<Change>.comparePositionalList(
    name: String,
    before: List<T>?,
    after: List<T>?,
    differ: Differ<T>?,
) {
    if (before == null || after == null) {
        if (before !== after) add(ValueChanged(FieldPath.of(name), before, after))
        return
    }
    // unchanged from here
}
```

`before !== after` rather than `!=`: both null is the one case where the two are the same reference,
and comparing two lists by `==` here would walk them for nothing.

### D2 — Patching gets one generic `patchNullable`, not four widened helpers

The patch helpers cannot be widened the way the compare helpers are: they *return* the rebuilt
collection, and `Patched<List<T>?>` in place of `Patched<List<T>>` breaks every non-null caller,
generated code included. So the null rule is applied around them by one helper:

```kotlin
public fun <C : Any> patchNullable(
    source: C?,
    changes: List<Change>,
    patch: (C, List<Change>) -> Patched<C>,
): Patched<C?>
```

It is `patchNestedNullable` with the delegation abstracted: a `ValueChanged` at the property sets the
value wholesale; a null source with changes beneath reports each as `NothingBeneathNull`; a present
source hands its changes to `patch`. `patchNestedNullable` is reimplemented as
`patchNullable(source, changes) { s, c -> patchNested(s, c, patcher) }` so the rule has one body, and
keeps its signature so no caller moves.

The generated line reads:

```kotlin
val tagsPatched = patchNullable(before.tags, grouped.forProperty("tags")) { tags, changes ->
  patchPositionalList(tags, changes, null)
}
```

Alternative rejected: a `patchNullablePositionalList` and three siblings. Eight patch helpers for four
shapes, each restating the null rule, versus one helper that states it once.

### D3 — Resolution reads collection nullability; the emitters branch on it

`resolve()` is unchanged in what it returns; `Comparison.KeyedList`, `PositionalList`, `AsSet` and
`AsMap` describe the collection regardless of the property's nullability. `emit` already reads
`isMarkedNullable` for `Nested`; it now reads it for the collection cases too, and the *compare* side
needs no branch at all, because D1 made the helper accept either. Only `emitPatch` branches: a nullable
collection property is wrapped in `patchNullable`, a non-null one calls the helper directly as today.

So the generated `diff` is identical for a nullable and a non-null collection property, and the
generated `apply` differs by exactly the wrapper. Incremental processing is unaffected: nullability is a
fact about the annotated class's own file, which is already the originating file.

### D4 — Nullable elements reached through a differ are a diagnostic

`resolveList` and `resolveMap` resolve the element or value type and, when its declaration is
`@Diffable`, now also check `isMarkedNullable` on that *element* type. When set, they report:

> `kdiff cannot compare <prop>: its elements are nullable <T>, and an element compared by a differ
> cannot be null. Make the element type non-null, or point the property at a hand-written differ with
> @DiffWith`

`List<String?>` passes untouched — a value element compared by `==` is null-safe already, and
`compareWindow` uses `==`. `Set<X?>` is never checked, because a set's elements are compared by
membership whatever they are.

Alternative rejected for this change: supporting it. It would mean `comparePositionalList` and
`compareMap` taking `List<T?>` and `Map<K, V?>` with `T : Any`, per-element null branches, and a keyed
list that must *still* refuse a null element because `keyOf(null)` has no answer — a second diagnostic
for a narrower shape. Additive, separable, and not what anyone has asked for.

### D5 — The comparison annotations get the tracking annotations' check, in the same place

`reportTrackingWithoutDiffable` becomes `reportAnnotationsWithoutDiffable` and gains a second loop over
`DIFF_KEY`, `DIFF_IGNORE` and `DIFF_WITH`, filtering property symbols whose parent class is not
`@Diffable`, with one message template:

> `@<DiffKey|DiffIgnore|DiffWith> on <prop> requires @Diffable on <Type>; without a generated differ
> it has no effect`

The `@DiffWith` + `@DiffIgnore` conflict cannot be reported from `resolve()`, which is reached only for
`comparableProperties()` — and that already filters `@DiffIgnore` out, so the conflicting property
never arrives. It is reported instead by a per-class walk over `getDeclaredProperties()`,
`reportsHonourableComparisonAnnotations()`, guarding `generate()` the way
`reportsHonourableTrackingAnnotations()` guards `resolveTrackScope()`. Same message, same location:

> `@DiffWith on <prop> conflicts with @DiffIgnore; an ignored property is never compared`

`@DiffKey` together with `@DiffIgnore` is *not* a conflict and is not reported. A key identifies an
element; ignoring the same property excludes it from the element's own comparison, which has an effect
wherever the element type appears outside a keyed list. The two answer different questions.

### D6 — An `object` subclass is a branch that names no differ

`sealedBody` and `sealedApplyBody` partition `getSealedSubclasses()` by `classKind == OBJECT`. An
object subclass is exempt from the `@Diffable` requirement — the *unannotated subclass* diagnostic is
raised only for non-object subclasses — and gets its own branch shape:

```kotlin
// diff
before is Unpaid && after is Unpaid -> Unit

// apply
is Unpaid -> patchSingleton(before, changes, "Unpaid")
```

`patchSingleton(before: T, changes: List<Change>, type: String): PatchResult<T>` returns `before`
with every change reported as `UnknownProperty(type)`. It exists for the reason `unpatchable` and
`notConstructorProperty` exist: a one-line rule stated once in the runtime rather than as an expression
the processor spells out into every consumer.

A swap to or from a singleton is the existing `else` branch: `TypeChanged` at the root carrying both
values, then the parent's declared properties. Applying a swap is the existing wholesale substitution,
because the type change carries the target instance — a singleton included.

`isSupported()` keeps rejecting `@Diffable` on an object, with the message for that kind extended:

> `@Diffable is only supported on data classes and sealed types; <Type> is an object, and an object
> in a @Diffable sealed hierarchy needs no annotation of its own`

The alternative — accepting `@Diffable` on an object and generating an empty differ for it — would put
an annotation on a declaration where it configures nothing, which is the rule D5 exists to enforce.

Tracking is untouched: a sealed type's scope covers the properties the parent declares, and a singleton
declares none.

### D7 — The DSL widens four parameter types and adds nothing

`list`, `keyedList`, `set` and `map` take `KProperty1<T, List<E>?>`, `KProperty1<T, Set<*>?>` and
`KProperty1<T, Map<K, V>?>`. `KProperty1` is covariant in its value type, so every existing call
compiles, and the builders pass the nullable read straight to the widened helpers of D1. This is the
shape `nested` already has, so the builder table in `hand-written.md` gains a clause rather than a row.

Sealed singletons need no DSL work: the existing *undeclared subtype* rule already compares two
instances of an undeclared subtype by the parent's named properties with no type change, which for a
singleton is exactly the generated behaviour. A parity scenario pins it.

### D8 — Generated sample

`kdiff-sample`'s `Order` gains `val couponCodes: List<String>?`, and `Payment` gains
`data object Unpaid : Payment`. The generated `OrderDiff.kt` gains one line in `diff` (identical in
shape to `tags`) and one wrapped line in `apply`; `PaymentDiff.kt` gains one branch in each function:

```kotlin
public object PaymentDiffer : Differ<Payment>, Patcher<Payment> {
  override fun diff(before: Payment, after: Payment): Diff = Diff(
    buildList<Change> {
      when {
        before is Card && after is Card -> addAll(CardDiffer.diff(before, after).changes)
        before is Transfer && after is Transfer -> addAll(TransferDiffer.diff(before, after).changes)
        before is Unpaid && after is Unpaid -> Unit
        else -> {
          add(TypeChanged(FieldPath.ROOT, before::class.simpleName.orEmpty(), after::class.simpleName.orEmpty(), before, after))
          compareValue("amount", before.amount, after.amount)
        }
      }
    },
  )

  override fun apply(before: Payment, changes: List<Change>): PatchResult<Payment> {
    val swap = changes.firstOrNull { it is TypeChanged && it.path.segments.isEmpty() } as? TypeChanged
    if (swap != null) return PatchResult(swap.after as Payment)

    return when (before) {
      is Card -> { val result = CardDiffer.apply(before, changes); PatchResult(result.value, result.failures) }
      is Transfer -> { val result = TransferDiffer.apply(before, changes); PatchResult(result.value, result.failures) }
      is Unpaid -> patchSingleton(before, changes, "Unpaid")
    }
  }
}
```

`Unpaid` must then implement `amount`; the sample gives it `override val amount: String get() = "0"`,
which is also the case that shows a swap to a singleton still comparing the parent's property.

Every generated file keeps `Dependencies(aggregating = false, originatingFile, subclassFiles)`. An
object subclass's file joins the sealed parent's originating set exactly as a data class subclass's
does.

### D9 — The emitters move to their own file

Added during implementation, and agreed with the maintainer rather than decided here: the diagnostics
and helpers this change adds pushed `DiffProcessor` past detekt's `LargeClass` threshold, which it had
been sitting just under at 708 lines.

The class is split along the seam it already had. `Emitters.kt` takes `emit`, `emitPatch`, `patchCall`
and `trackScopeInitializer` — every one a pure function from a property and its `Comparison` to a
`CodeBlock`, reaching neither the logger nor the code generator. `DiffProcessor` keeps what needs them:
driving KSP, reporting diagnostics, resolving a scope and assembling the `TypeSpec`.

Two pure predicates move to `Resolution.kt`, where `Comparison` and the other `KSType`/`KSClassDeclaration`
helpers already live: `rebuildsACollection`, and the `propertiesOutsideDiffable` / `ownerName` pair the
D5 diagnostic reads.

Rejected: disabling `LargeClass` in `config/detekt/detekt.yml`, which is what the four sibling
complexity rules already disabled for this class did. It would have been consistent with that
precedent and with the config's stated policy, and it needed no code to move — but the rule was
pointing at something real, and the seam was already there to cut along.

Emission is unaffected: `./gradlew :kdiff-sample:kspKotlin --rerun-tasks` produces byte-identical
output before and after the split, which is what makes it a move rather than a change.

## Risks / Trade-offs

**A nullable collection is compared by a helper that used to assume non-null** → the null branch is
the first statement and returns; the body below it is unchanged and unreachable with a null side. Pinned
by runtime scenarios for all four shapes on both sides and both-null.

**`patchNullable`'s lambda in generated code reads as logic** → it is delegation, not logic: the lambda
names the helper and forwards its arguments. Kept to one line where it fits; the sample in D8 shows the
wrapped form. If it proves noisy, the processor can emit function references, which changes nothing
here.

**The new `@Diff*` diagnostics break a build that passed** → BREAKING and stated so. The message names
the property and the fix, and the change is the same one made for tracking annotations in 0.1.0. The
`CHANGELOG.md` entry says which annotations and why.

**An object subclass in a consumer's sealed hierarchy suddenly compiles** → that is the fix. Nothing
about a hierarchy that compiled before changes, because such a hierarchy had no object subclass.

**The `else` branch of a sealed `diff` grows unreachable for an all-object hierarchy** → it does not:
two different singletons still meet in `else` and report the type change. An all-object sealed type is
an enumeration with a type change per transition, which is correct and stated in a scenario.

**Widened DSL parameter types could accept a property the author did not mean** → `list(Order::tags)`
accepted `List<E>` before and accepts `List<E>?` now; nothing that was rejected on a *different* type
is admitted, since the element bound is unchanged.

## Migration Plan

A library version bump. A consumer with a nullable collection property, a stray comparison annotation,
or an `object` subclass sees the new behaviour on recompiling. Rollback is pinning the previous
versions of all three published modules together — the processor and the runtime move in step here,
because the processor emits calls to the two new helpers.

## Documentation

Passages to add or amend in the same commit as the code:

- `docs/errors.md` — the two new compile-time messages from D4 and D5, the amended object message from
  D6, and the tier-1 count ("fifteen diagnostics") updated to match.
- `docs/diffing.md` — *Values, enums and nullables* gains the sentence that a nullable collection
  follows the same rule as a nullable nested type; *Sealed types* gains a paragraph on `object`
  subclasses with the `Unpaid` sample.
- `docs/annotations.md` — the rejection table gains the D4 and D5 rows; `@Diffable`'s section says an
  object subclass needs no annotation.
- `docs/hand-written.md` — the builder table notes that `list`, `keyedList`, `set` and `map` accept a
  nullable property, as `nested` does.
- `docs/faq.md` — one entry under *Comparison*: "Why can I not put `@Diffable` on my `data object`?"
- `docs/patching.md` — one sentence under *What each kind of change does when applied*: a change at a
  nullable collection property sets it wholesale.
- `CHANGELOG.md` under `[Unreleased]`: an *Added* entry for nullable collections and object subclasses,
  and a **BREAKING** *Changed* entry for the comparison-annotation diagnostics.
