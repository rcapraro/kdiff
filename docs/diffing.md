# Diffing

The reference for comparison: what a diff contains, how a path locates a change, how each shape of
property is compared, and how to decide what a change means. Start with
[the tutorial](tutorial.md) if you would rather see it working first.

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

diff.isEmpty()        // true when the two compared equivalent
diff.isNotEmpty()     // and its opposite
diff.size             // how many changes were found
diff.changes          // List<Change>, in declaration order
diff.render()         // human-readable text, one line per change
diff.tree()           // the same changes as a DiffNode hierarchy
```

`Diff` is itself an `Iterable<Change>`, so the standard library's operators apply to it without
reaching for `changes` first:

<!-- illustrative -->
```kotlin
diff.filter { it is Removed }
diff.groupBy { it.path.rootName() }
diff.joinToString("\n")
```

It is `Iterable` rather than `List` on purpose: a `Diff` equals only another `Diff`, so advertising
list-ness while equalling no list would mislead, and `subList`, `indexOf` and `listIterator` are
surface nobody asked for.

Two diffs combine with `+`, which concatenates rather than reconciles — two changes at one path are
both kept, because nothing here can decide what they mean together. `Diff.EMPTY` is the diff that
found nothing, and `Diff(a, b)` builds one from changes you already hold.

`Diff` is a value — two results built from the same changes are equal.

### Narrowing to one property

A diff narrows by property *reference*, so no string is matched and renaming the property reaches the
call site:

<!-- illustrative -->
```kotlin
diff.at(Order::billing)      // the billing property itself changed
diff.under(Order::billing)   // billing, or anything inside it, changed
```

`at` is the property's own change; `under` is that plus everything beneath it. Both return a `Diff`,
so they compose with each other and with `+`.

**Name the type to have the property checked against it.** A `Diff` carries no type argument, so the
type parameter is inferred from the property alone — which means a property of an unrelated type
compiles and quietly matches nothing:

<!-- illustrative -->
```kotlin
orderDiff.at<Order>(Order::billing)     // checked
orderDiff.at<Order>(Address::street)    // does not compile
orderDiff.at(Address::street)           // compiles, and matches nothing
```

Routing has no such hole: `route<Order> { }` fixes the type at the call site, so every property named
inside it is checked. Prefer routing when you are dispatching on several properties; narrowing is for
picking one out.

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

Every change is one of five kinds at one path, and a path is a list of three kinds of segment. Those
two vocabularies are the whole model:

```
   a Change                            its FieldPath
   --------                            -------------
                                  segments, outermost first
   ValueChanged  before, after
   Added         value          +----------+----------+--------------+
   Removed       value          | Field    | Index    | Key          |
   TypeChanged   beforeType,    | (name)   | (i)      | (property,   |
                 afterType,     |          |          |  value)      |
                 before, after  +----------+----------+--------------+
   Moved         from, to         "city"      [1]       [id=A2]
                                    |          |           |
                                    v          v           v
                              a property   a position   an identity
                              step         in a list    that survives
                                           or the tail  reordering

   addresses  [id=A2]  .  street       "2 Rue Y" -> "9 Rue Q"
   ---------  -------     ------       ---------------------
     Field      Key       Field            ValueChanged
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

A property whose own type is a value object is reached by **framing** it with `under`, and frames nest
as deep as the model does:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
under(Person::contact) {
    on(Contact::email) { add(EmailChanged(desired.id, current.contact.email, desired.contact.email)) }

    under(Contact::phone) {
        on(Phone::number) { add(PhoneNumberChanged(desired.id, wasPhone.number, nowPhone.number)) }
        on(Phone::country) { add(PhoneCountryCorrected(desired.id, wasPhone.country, nowPhone.country)) }
    }
}
```

A frame's body **declares** its routes, and runs once when the routing is declared — unlike an `on`
handler, which runs only when its property has a change. So a statement in a frame body that is not a
route declaration runs whether or not anything changed, and a read that is only safe when the nested
value changed (`current.fiscal!!.tin`, say) belongs inside a handler, not beside one.

Inside a frame every route means what it means at the top level, one level down, and the changes a
handler receives are rooted at the framed type — so `onEach` finds an element's key in there too.
Dispatching through a frame is the same as comparing the nested value on its own and routing *that*
diff; the two are interchangeable.

Two edges are worth knowing. A change reported *at* the framed property rather than beneath it — what
a nullable value object reports when it appears or disappears — has nothing left to descend into, and
is treated as unhandled. And a change no handler in a frame names goes to that frame's own `otherwise`
if it declares one, and otherwise back out to the enclosing routing, at the path it arrived with: so a
single `otherwise` at the top sees everything unnamed at any depth.

Where a change ends up, then, is one walk downwards and — if nothing claims it — one walk back out:

```
   change at contact.phone.number
              |
              v
   route<Person> { ... }
              |
      does a handler name `contact`?
              |
        yes: under(Person::contact) -- the frame strips `contact`,
              |                        leaving phone.number
              v
   frame<Contact> { ... }
              |
      does a handler name `phone`?
              |
        yes: under(Contact::phone) -- strips `phone`, leaving number
              |
              v
   frame<Phone> { on(Phone::number) }  -->  HANDLED
                     |
                     | nothing named it
                     v
              frame declares its own otherwise?
                     |
          yes -->  that otherwise, rooted at Phone
                     |
           no  -->  back out to the ENCLOSING routing,
                    at the full path it arrived with
                    (contact.phone.number), and onwards
                    to the top-level otherwise
```

Two things follow. A change at the framed property itself — `contact` becoming null — never enters the
frame, because there is no segment left to strip; it is unhandled. And because an unclaimed change
walks back out rather than being swallowed, one `otherwise` at the top is genuinely a complete
backstop, however deep the frames go.

A frame dispatches at the property's *declared* type, which is what it can name. Frame a sealed
property and you can name the properties the sealed type declares itself, but not a subclass's own —
and the `TypeChanged` a subclass swap reports sits at the property, so it is unhandled and reaches the
fallback. Framing a collection property names nothing at all, because a path beneath it begins with an
element rather than a property; `onEach` is the call for that.

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
        diff(order.copy(lastTouched = "friday")).isEmpty() shouldBe true
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

**A swap reports two moves**, not one. Both elements changed position, so both are reported, and each
change describes where that element ended up rather than an operation to replay:

<!-- from: kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/CompareSpec.kt -->
```kotlin
            changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactlyInAnyOrder
                listOf("addresses[id=A1]", "addresses[id=A2]")
```

**A key must identify at most one element in each list, and a repeated one is rejected.** Two elements
carrying the same key leave the comparison with nothing it could report: a path names a keyed element
by its key value, so `addresses[id=A1]` cannot say which of the two it means. Rather than matching one
and discarding the other, the comparison throws:

<!-- from: kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/CompareSpec.kt -->
```kotlin
            shouldThrow<IllegalArgumentException> { keyed(listOf(a1, shadowed), listOf(a1)) }
                .message shouldBe
                "addresses is keyed by id, but two elements share the key A1. " +
                "A keyed element must be uniquely identified; addresses[id=A1] cannot name one of them."
```

The same rule applies when applying a diff, so both directions agree about the same list — and it
applies however few changes are involved, an empty list included, because such a list cannot be
rebuilt on its own terms.

Uniqueness is a property of the data, not of the declaration: nothing in the source says the key values
will differ, so this cannot be a compile error. If your key genuinely is not unique, it is not an
identity — drop `@DiffKey` (or describe the property with `list` rather than `keyedList`) and the list
is compared by position instead, giving up moves.

**Without a key**, elements are matched by position after the tail the two lists already agree on is
excluded, so one contiguous insertion or deletion reports as exactly that rather than shifting
everything after it. A removal is reported at its index in the old list and an addition at its index in
the new one; an element change always names a position *both* lists hold, and one comparison reports
additions or removals but never both. Two lists of the same length are always compared index by index.
A move is never reported — with no key there is nothing to recognise a moved element by.

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
sharing a path prefix sit under it. Use it when you are rendering — a UI or a report that indents by
structure — and the flat list for everything else, which is why the flat list is what `route` and
`apply` consume.

A `DiffNode` is the segment that got you there, the changes reported at exactly that point, and the
nodes below:

<!-- from: kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/DiffNode.kt -->
```kotlin
public data class DiffNode(
    public val segment: Segment?,
    public val changes: List<Change>,
    public val children: List<DiffNode>,
)
```

The root node's `segment` is `null`, because no segment leads to the root. Changes sharing a prefix
gather under one node:

<!-- from: kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/ViewSpec.kt -->
```kotlin
        val diff = Diff(
            listOf(
                ValueChanged(path("address", "street"), "Rue X", "Rue Y"),
                ValueChanged(path("address", "city"), "Paris", "Lyon"),
            ),
        )

        val children = diff.tree().children

        children.size shouldBe 1
        children.single().segment shouldBe Segment.Field("address")
        children.single().children.size shouldBe 2
```

```
   flat                                tree

   address.street  "Rue X" -> "Rue Y"   (root)
   address.city    "Paris" -> "Lyon"      |
                                          +-- Field(address)
                                                |
                                                +-- Field(street)   ValueChanged
                                                +-- Field(city)     ValueChanged
```

Two guarantees make the two views interchangeable. A change reported at the root sits on the root node
rather than being given a child it has no segment for, and `allChanges()` flattens a tree back to
exactly the changes it was built from — neither view invents or drops one:

<!-- from: kdiff-runtime/src/test/kotlin/io/github/kdiff/runtime/ViewSpec.kt -->
```kotlin
        Diff(changes).tree().allChanges() shouldContainExactlyInAnyOrder changes
```

An empty diff gives a tree whose `isEmpty` is true and which has no children at all.

## When kdiff refuses

A property whose type is not a value type, not `@Diffable`, not a supported collection, and not
pointed at a hand-written differ is a **compile error** naming the property and its type. It is never
silently compared by equality: comparing a rich type as an opaque value produces a diff that is
technically correct and useless.

See [hand-written.md](hand-written.md) for the `@DiffWith` escape hatch, and
[annotations.md](annotations.md) for the full list of what is rejected.

## Where to go next

- [How do I…](how-to.md) — comparing, auditing and routing as recipes lifted from tests
- [Errors](errors.md) — the duplicate-key and cyclic-structure refusals this page describes
- [Patching](patching.md) — turning the changes you have just read back into an object
- [Tracking](tracking.md) — reporting only the changes worth reacting to
- [Hand-written differs and scopes](hand-written.md) — describing a type you cannot annotate
- [Annotation reference](annotations.md) — every annotation, and what each rejects
- [Tutorial](tutorial.md) — routing worked through as a whole application
