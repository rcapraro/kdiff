# Patching

For turning a diff back into an object: what `apply` guarantees, what it reports instead of applying,
and why the result is partial rather than all-or-nothing.

The same generated object that compares a type also applies changes back to it. One import gives you
both directions.

<!-- from: kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Patcher.kt -->
```kotlin
public fun interface Patcher<T> {
    public fun apply(before: T, changes: List<Change>): PatchResult<T>
}
```

`apply` is pure: it never modifies its argument, and applying the same changes twice to the same
source produces equal results.

## The round-trip property

The headline guarantee: a diff applied to its own source reproduces the target.

<!-- from: kdiff-sample/src/test/kotlin/demo/RoundTripSpec.kt -->
```kotlin
private fun roundTrip(before: Order, after: Order) {
    val result = OrderDiffer.apply(before, OrderDiffer.diff(before, after).changes)

    result.failures.shouldBeEmpty()
    OrderDiffer.diff(result.value, after).changes.shouldBeEmpty()
}
```

That holds across every shape kdiff supports: values, enums, nullables in both directions, nested
types, keyed lists (including a modification, addition, removal and move at once), reordered lists,
unkeyed lists that changed length, sets, maps, and sealed subclass changes.

## The result is partial, never all-or-nothing

<!-- from: kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Patcher.kt -->
```kotlin
public data class PatchResult<T>(public val value: T, public val failures: List<PatchFailure> = emptyList()) {
    public val isClean: Boolean
        get() = failures.isEmpty()
}
```

Changes that can be applied *are* applied even when others fail, so `value` is always usable and
`failures` says exactly what it is missing. A caller wanting strictness checks `isClean`.

Each `PatchFailure` carries the `Change` it could not apply and a `reason`, and renders as
`path: reason`.

A `reason` is a case, not a sentence. `PatchFailure.Reason` is sealed and closed the way `Change` is,
so a caller can branch on *why* a change did not apply and read off which property, key or index it
concerned — without matching prose that may be reworded:

<!-- illustrative -->
```kotlin
result.failures.forEach { failure ->
    when (val reason = failure.reason) {
        is PatchFailure.Reason.NotConstructorProperty -> audit(reason.property)
        is PatchFailure.Reason.UnpatchableProperty -> escalate(reason.property)
        PatchFailure.Reason.NoElementForKey -> retryLater(failure.change)
        else -> log("$failure")
    }
}
```

Adding a case to that vocabulary is a breaking change, for the same reason adding a sixth `Change`
variant would be: it breaks every exhaustive `when`.

### Requiring the whole patch

`apply` stays partial, because that is what lets a caller keep what applied. A caller who wants
all-or-nothing says so at the call site:

<!-- illustrative -->
```kotlin
val rebuilt = OrderDiffer.apply(order, changes).getOrThrow()
```

That returns the value when every change applied, and raises `PatchFailedException` — carrying every
failure — when any did not.

## What produces a failure

The three below are the ones that carry the concept. All fourteen cases are documented in
[errors.md](errors.md#3-changes-that-did-not-apply), grouped by the five causes they fall into — that
page is the complete list.

**A property that is not a constructor parameter.** Reconstruction goes through `copy`, so a
property declared in the class body cannot be rebuilt. The reason says so rather than silently
dropping the change.

**A `@DiffWith` property whose differ can only compare.** kdiff will not attempt to reconstruct a
type it was only taught to compare — that would mean constructing a value whose shape it does not
know. The change is reported instead, and crucially, the rest of the instance still patches:

<!-- from: kdiff-sample/src/test/kotlin/demo/RoundTripSpec.kt -->
```kotlin
        val after = order.copy(reference = "R-2", weight = Weight("750"))

        val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

        result.value.reference shouldBe "R-2"
        result.value.weight.grams shouldBe "500"
        result.failures.single().reason shouldBe
            PatchFailure.Reason.UnpatchableProperty("weight")
```

`reference` applied; `weight` did not and was reported. See
[hand-written.md](hand-written.md) for how to make such a property patchable.

**A change whose path names no compared property.** A hand-constructed or stale change targeting
something the type does not compare is reported, not ignored.

## The one thing `apply` refuses outright

Everything above is a *change* that could not be applied, reported alongside a usable `value`. There
are two cases where there is no value to report at all, and `apply` throws instead.

**A list matched by key whose elements do not carry unique keys** — `DuplicateDiffKeyException`,
carrying the list property, the key property and the duplicated key value.

Two elements sharing a `@DiffKey` value cannot be told apart — `addresses[id=A1]` names neither of them
in particular — so the list cannot be rebuilt without writing one out twice and dropping the other.
That is the source being uninterpretable rather than a change failing, which is why it is not a
`PatchFailure`. It happens whatever you are applying, an empty change list included.

The comparison refuses the same list for the same reason, so the two directions agree; see
[diffing.md](diffing.md#lists). A list nested under a property that no change addresses is never
rebuilt, so it is carried through untouched rather than examined.

**A source whose graph contains a cycle** — `CyclicStructureException`, naming the path the descent
stopped at. Applying descends no deeper than `MAX_DESCENT` nested levels, so a cycle is reported
rather than exhausting the stack. Comparison refuses it identically.

Both are `IllegalArgumentException` subtypes, so a `catch` written before they were declared still
catches them.

## What each kind of change does when applied

| Change | Effect |
|---|---|
| `ValueChanged` | takes `after` as the property's new value |
| `Added` | inserts the element or entry |
| `Removed` | drops the element or entry |
| `Moved` | repositions a keyed element |
| `TypeChanged` | substitutes the value wholesale, using the carried `after` |

A `TypeChanged` at a sealed value substitutes rather than descends — which is why the change carries
both values, not just their type names. A subclass change therefore yields the target's subclass, an
`object` subclass included.

A `ValueChanged` **at** a nullable collection property sets it wholesale, to a collection or to `null`,
because that is what the comparison reported for it appearing or disappearing. Changes *beneath* one
are applied to the collection when it is there, and reported as
`NothingBeneathNull` when it is not — the same rule a nullable nested property follows.

Two consequences worth knowing. A wholesale replacement makes the property a value for that
application, so any other change addressed to it is reported as `NotApplicableToValue` rather than
dropped — the last change at the property wins, exactly as it does for a property compared as a value.
And a present source is still held to its shape's preconditions whatever is being applied to it: a
nullable keyed list whose source holds a repeated key is refused on an empty change list and on a
wholesale replacement alike, so making a property nullable never buys it out of a check.

Applying an empty change list returns an equal instance and reports nothing.

## Ordering and collections

A keyed list is rebuilt by computing its target state rather than by replaying operations in
sequence. Replaying would read indices off a list that is being mutated underneath, so a move plus a
removal in the same diff could land an element at the wrong position. Computing the target state
sidesteps it entirely.

A set is rebuilt by membership: removals dropped, additions added, never reordered. An individual set
element cannot be modified in place, so a change beneath one is reported as a failure.

## Ignored properties

A `@DiffIgnore` property produces no changes, so it keeps the source value through a patch and
reports nothing. That is consistent with diffing: what is not compared is not reconstructed.

## Where to go next

- [Errors](errors.md) — all fourteen failure reasons, grouped by what to do about each
- [How do I…](how-to.md) — requiring the whole patch, and handling failures by cause
- [Hand-written differs and scopes](hand-written.md) — writing the `Patcher` a builder cannot generate,
  and making a `@DiffWith` property patchable
- [Diffing](diffing.md) — where the changes being applied come from
- [Tracking](tracking.md) — a narrowed scope gives selective propagation for free
- [Architecture](architecture.md) — why patching has no builder
