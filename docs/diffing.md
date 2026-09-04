# Diffing

`@Diffable` on a data class makes the processor generate `object <Type>Differ` in the same package,
implementing `Differ<Type>`:

<!-- from: kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Differ.kt -->
```kotlin
public interface Differ<T> {
    public fun diff(before: T, after: T): Diff
}
```

`diff` is pure: it never modifies either argument, and diffing the same pair twice returns equal
results.

## What you get back

A `Diff` is a flat, ordered `List<Change>`. Order is declaration order, so changes read in the same
sequence as the properties they came from.

<!-- illustrative -->
```kotlin
val diff = OrderDiffer.diff(before, after)

diff.isEmpty          // true when the two compared equivalent
diff.changes          // List<Change>, in declaration order
diff.render()         // human-readable text, one line per change
diff.tree()           // the same changes as a DiffNode hierarchy
```

`Diff` is a value — two results built from the same changes are equal.

## The change vocabulary is closed

`Change` is a sealed interface with exactly five implementations, so a `when` over it can be
exhaustive and stay that way:

| Change | Means | Carries |
|---|---|---|
| `ValueChanged` | a property or element compared unequal | `before`, `after` |
| `Added` | an element or entry present only in the new instance | `value` |
| `Removed` | an element or entry present only in the old instance | `value` |
| `TypeChanged` | a sealed-typed value that is a different subclass on each side | `beforeType`, `afterType`, `before`, `after` |
| `Moved` | a keyed element that kept its identity but changed position | `from`, `to` |

This is deliberate. Adding a sixth variant would grow a branch in every exhaustive `when` in every
consumer, so the vocabulary is treated as part of the compatibility surface.

`TypeChanged` carries the values as well as the type names because a type name alone cannot be turned
back into an instance — without them a subclass change could be reported but never replayed.

## Paths

Every change carries a `FieldPath` locating it relative to the root being diffed. A path is a list of
`Segment`s:

| Segment | Renders as | Used for |
|---|---|---|
| `Segment.Field(name)` | `name`, dot-separated | navigating into a property |
| `Segment.Index(i)` | `[i]` | an element of a positional collection |
| `Segment.Key(property, value)` | `[property=value]` | an element or entry identified by a key |

```
reference
billing.city
addresses[id=A2].street
tags[1]
amounts[key=eur]
```

An empty path is the root object itself. A key segment retains the key **as its own value**, not as
text, so an entry added to a `Map<Int, V>` can be reconstructed from the `Int` — a rendered `"1"`
could not be.

### Deciding what a change means: `route`

`FieldPath.rootName()` gives the property a path begins with, and `null` for the root path — a change
there belongs to no property.

Matching on that name works, but it is a `String`: a typo compiles and matches nothing. Route the diff
instead, naming each property by reference:

<!-- illustrative -->
```kotlin
diff.route<Order> {
    on(Order::reference) { changes -> … }    // once, with every change under it
    on(Order::status) { … }

    otherwise { unhandled -> audit(Diff(unhandled)) }
}
```

A handler runs **once** per routing, and only when its property has at least one change — a rename
reported at both `name.given` and `name.family` is one rename, not two. What no handler names reaches
`otherwise`, as does a change at the root of the routed type.

A collection property is routed by element, and the element and its key arrive at their own types, so
a caller never casts:

<!-- illustrative -->
```kotlin
onEach(Order::addresses, Address::id) {
    added { address -> … }                   // Address
    removed { address -> … }
    moved { id, from, to -> … }              // AddressId, and both positions
    changed { id -> … }                      // once per element, however much of it changed
}
```

`moved` and `changed` exist only for a collection whose elements carry a key, because without one
there is nothing to identify the element by. Route an unkeyed collection and you get `added` and
`removed`; anything else it reports — a change *inside* one of its elements — reaches `otherwise`
rather than disappearing.

Routing reads only the diff, so it works the same whether the differ came from `@Diffable` or from
`differ { }`. See [the tutorial](tutorial.md) for the pattern worked through end to end.

## Values, enums and nullables

Primitives, `String` and enums are compared by equality and report a single `ValueChanged`:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
enum class Status { OPEN, CLOSED }
```

A nullable property reports a null on either side as a value change, never as an addition or a
removal — a property's path exists on both sides or on neither, so `Added` and `Removed` are reserved
for collection elements and map entries:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderDiffSpec.kt -->
```kotlin
    test("a null becoming a value is reported at the property, not as an addition") {
        val changes = diff(order.copy(note = "rush")).changes

        changes.map { it.path.toString() } shouldContainExactly listOf("note")
        changes.filterIsInstance<Added>().shouldBeEmpty()
    }
```

Null on both sides is not a change.

## Excluding a property

`@DiffIgnore` removes a property from comparison entirely. It never contributes a change, however
much it differs:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderDiffSpec.kt -->
```kotlin
    test("an ignored property never reports, however much it differs") {
        diff(order.copy(lastTouched = "friday")).isEmpty shouldBe true
    }
```

## Nested types

A property whose type is itself `@Diffable` is compared by delegating to that type's differ, and the
nested changes come back prefixed with the property's own segment. So a change three levels down
reports at `company.address.city`, and the path reads from the root.

A nullable nested property delegates when both sides are non-null, and reports a plain value change
at the property when either side is null — with nothing reported beneath it.

## Lists

How a list is compared depends on whether its element type declares a key.

**With `@DiffKey`**, elements are matched by that key rather than by position. A matched element
reports its own changes at its key, an unmatched one reports as added or removed, and one that kept
its identity but changed position reports as `Moved` — not as a removal plus an addition:

```
addresses[id=A2].street   "2 Rue Y" -> "9 Rue Q"
addresses[id=A3]          ADDED
addresses[id=A1]          REMOVED
addresses[id=A2]          MOVED 0 -> 1
```

A type may declare at most one `@DiffKey`; two is a compile error.

**Without a key**, elements are compared index by index. Trailing indices present on only one side
report as added or removed. A move is never reported — with no key there is nothing to recognise a
moved element by.

## Sets

Compared by membership. An element in the new set and not the old reports as added, and vice versa.

A set never reports a move, and never reports a change to an individual element: set elements have no
stable identity, so a modified element is indistinguishable from one removed and another added.

## Maps

Compared by entry key. A key present on one side only reports as added or removed; a key present in
both whose value differs is compared by the rules for the value's type, at a path identifying the
entry.

## Sealed types

`@Diffable` works on a sealed class or interface whose subclasses are all themselves `@Diffable`. The
generated differ dispatches on the runtime type:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@Diffable
sealed interface Payment {
    val amount: String
}

@Diffable
data class Card(override val amount: String, val last4: String) : Payment

@Diffable
data class Transfer(override val amount: String, val iban: String) : Payment
```

- **Same subclass on both sides** — delegates to that subclass's differ and reports its changes
  unchanged.
- **Different subclasses** — reports a `TypeChanged` carrying both type names and both values, *and*
  compares the properties the sealed parent declares itself. It does not descend into properties
  belonging to only one of the two subclasses, since there is nothing to compare them against.

When the parent declares no properties of its own, a subclass swap produces the type change alone.

## Viewing a diff

`render()` gives one aligned line per change, with strings quoted and each kind distinguishable:

```
reference   "R-1" -> "R-2"
status      OPEN -> CLOSED
payment     TYPE Card -> Transfer
```

`tree()` gives the same changes grouped into a hierarchy mirroring the object graph, so changes
sharing a path prefix sit under it. The tree holds precisely the changes the flat list holds —
neither view invents or drops one. `DiffNode.allChanges()` flattens it back.

## When kdiff refuses

A property whose type is not a value type, not `@Diffable`, not a supported collection, and not
pointed at a hand-written differ is a **compile error** naming the property and its type. It is never
silently compared by equality: comparing a rich type as an opaque value produces a diff that is
technically correct and useless.

See [hand-written.md](hand-written.md) for the `@DiffWith` escape hatch, and
[annotations.md](annotations.md) for the full list of what is rejected.
