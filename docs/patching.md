# Patching

For turning a diff back into an object: what `apply` guarantees, what it reports instead of applying,
and why the result is partial rather than all-or-nothing.

The same generated object that compares a type also applies changes back to it. One import gives you
both directions.

<!-- from: kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Patcher.kt -->
```kotlin
public interface Patcher<T> {
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
public data class PatchResult<T>(
    public val value: T,
    public val failures: List<PatchFailure> = emptyList(),
) {
    public val isClean: Boolean
        get() = failures.isEmpty()
}
```

Changes that can be applied *are* applied even when others fail, so `value` is always usable and
`failures` says exactly what it is missing. A caller wanting strictness checks `isClean`.

Each `PatchFailure` carries the `Change` it could not apply and a `reason`, and renders as
`path: reason`.

## What produces a failure

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
        result.failures.single().reason shouldContain "weight"
        result.failures.single().reason shouldContain "cannot patch"
```

`reference` applied; `weight` did not and was reported. See
[hand-written.md](hand-written.md) for how to make such a property patchable.

**A change whose path names no compared property.** A hand-constructed or stale change targeting
something the type does not compare is reported, not ignored.

## The one thing `apply` refuses outright

Everything above is a *change* that could not be applied, reported alongside a usable `value`. There is
one case where there is no value to report at all, and `apply` throws `IllegalArgumentException`
instead: a list matched by key whose elements do not carry unique keys.

Two elements sharing a `@DiffKey` value cannot be told apart — `addresses[id=A1]` names neither of them
in particular — so the list cannot be rebuilt without writing one out twice and dropping the other.
That is the source being uninterpretable rather than a change failing, which is why it is not a
`PatchFailure`. It happens whatever you are applying, an empty change list included.

The comparison refuses the same list for the same reason, so the two directions agree; see
[diffing.md](diffing.md#lists). A list nested under a property that no change addresses is never
rebuilt, so it is carried through untouched rather than examined.

## What each kind of change does when applied

| Change | Effect |
|---|---|
| `ValueChanged` | takes `after` as the property's new value |
| `Added` | inserts the element or entry |
| `Removed` | drops the element or entry |
| `Moved` | repositions a keyed element |
| `TypeChanged` | substitutes the value wholesale, using the carried `after` |

A `TypeChanged` at a sealed value substitutes rather than descends — which is why the change carries
both values, not just their type names. A subclass change therefore yields the target's subclass.

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

- [Hand-written differs and scopes](hand-written.md) — writing the `Patcher` a builder cannot generate,
  and making a `@DiffWith` property patchable
- [Diffing](diffing.md) — where the changes being applied come from
- [Tracking](tracking.md) — a narrowed scope gives selective propagation for free
- [Architecture](architecture.md) — why patching has no builder
