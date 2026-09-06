# Tracking

For reacting to change rather than inspecting it: trackers, the scope that decides what is worth
reporting, and the depth rule that is the easiest thing here to get wrong.

Diffing answers "how do these two differ?". Tracking answers "tell me when the parts I care about
change" — you feed instances in, and a lambda fires.

kdiff's model is immutable data classes, so there is no in-place mutation to intercept. A `Tracker`
holds a baseline, and `update` compares the new instance against it, reports what your scope selects,
and adopts the new instance as the baseline.

<!-- illustrative -->
```kotlin
val tracker = tracker(OrderDiffer, order) {
    field(Order::reference)
    onFieldChange { path, from, to -> log("$path: $from -> $to") }
}

tracker.update(order.copy(reference = "R-2"))
// reference: R-1 -> R-2
```

`update` returns the selected changes as an ordinary `Diff`, so a caller who prefers a return value
over a callback needs no callback at all.

`reset(baseline)` adopts a new baseline without comparing and without firing, for when you have
adopted a new state by other means.

### When you already hold both instances

A tracker is for an instance that keeps evolving. A command handler holds two instances — what is
stored, and what a caller is asking for — and wants the tracked view of the difference between them,
not a baseline:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
        val events = eventsFor(current, desired, PersonDiffer.trackedDiff(current, desired, PersonScope))
```

`trackedDiff` reports exactly what a tracker carrying that scope would report, and remembers nothing.
Passing no scope uses the one the type declared with `@Trackable`.

> A `Tracker` is **not thread-safe** — `update` reads the baseline, compares, dispatches and writes
> it back, and making that atomic would mean holding a lock across your callbacks. Confine one to a
> thread. A `TrackScope` is immutable and can be shared freely.

## Declaring a scope on the type

`@Trackable` on a `@Diffable` class tracks every compared property. `@TrackIgnore` opts one out, and
`@TrackDepth` gives one its own depth:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@Diffable
@Trackable(depth = 1)
data class Order(
    val reference: String,
    val status: Status,
    @TrackIgnore val note: String?,
    @DiffIgnore val lastTouched: String,
    @TrackDepth(2) val billing: Address,
```

As with `@Diffable`, the class opts in and properties opt out — there is no property-level way to opt
*in*. A `@TrackIgnore` property is still compared; only tracking passes over it. A `@DiffIgnore`
property produces no changes at all, so it can never be tracked, and annotating it for tracking is a
compile error rather than a silent no-op.

The generated differ then exposes the scope, and a tracker that names no property of its own uses it:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("a tracker with no field of its own honours the declared depth of 1") {
            paths({}, order.copy(reference = "R-2", billing = a1.copy(city = "Nice"))) shouldContainExactly
                listOf("reference", "billing.city")
        }
```

## Depth

Depth bounds how far into nested objects a change may lie and still be reported. **It counts property
steps only** — a collection index or key identifies a sibling, not a level of nesting:

| change path | property steps |
|---|---|
| `total` | 1 |
| `addresses[id=A3]` (an added element) | 1 |
| `amounts[key=eur]` | 1 |
| `addresses[id=A2].street` | 2 |
| `billing.city` | 2 |

If keys counted, `@Trackable(depth = 1)` on a `List<Address>` property would report nothing at all —
an added element already sits two segments deep.

Reading a path as steps, then, means counting only the segments that go *down*:

```
   path                        segments                    steps

   total                       total                         1
                               ^^^^^ property

   addresses[id=A3]            addresses [id=A3]             1
                               ^^^^^^^^^ property  ^^^^^^^^ identity: sideways, not down

   amounts[key=eur]            amounts   [key=eur]           1
                               ^^^^^^^ property    ^^^^^^^^ identity

   billing.city                billing . city                2
                               ^^^^^^^   ^^^^ both properties

   addresses[id=A2].street     addresses [id=A2] . street    2
                               ^^^^^^^^^         ^^^^^^ properties
                                         ^^^^^^^ identity


   depth = 1   keeps  |  total, addresses[id=A3], amounts[key=eur]
               cuts   |  billing.city, addresses[id=A2].street
```

An index or a key answers *which one*; a property answers *what inside it*. Only the second is a level
of nesting, so only the second consumes depth.

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("an element added to the keyed addresses list is reported at depth 1") {
            paths({}, order.copy(addresses = order.addresses + a3)) shouldContainExactly
                listOf("addresses[id=A3]")
        }
```

`UNLIMITED_DEPTH` excludes nothing. A depth of zero, or any other negative value, is rejected where
it is declared.

### Depth filters — it does not roll up

A change deeper than the limit is **not reported at all**. It is not rewritten to a shallower path,
and not summarised as a change at its nearest reported ancestor:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("a change inside an addresses element is excluded at depth 1") {
            paths({}, order.copy(addresses = listOf(a1, a2.copy(city = "Nice")))).shouldBeEmpty()
        }
```

Two reasons. Reporting "`billing` changed" would need a change *at* `billing` carrying the old and
new `Address`, which cannot be read without reflection; and a valueless variant would break the
closed `Change` vocabulary. If you want "something under `billing` changed", say
`under(Order::billing)` and coalesce in your own callback.

## Scopes at the call site

A scope names properties by reference, so a typo does not compile:

| Verb | Tracks |
|---|---|
| `field(Order::total)` | that property itself, nothing nested |
| `field(Order::billing, depth = 2)` | that property, to the stated depth |
| `under(Order::billing)` | that property and its whole subtree |
| `except(Order::lastTouched)` | every compared property *but* that one, however deep |
| `depth = n` | applies when the scope names no property, and overrides a depth the type declared |

Naming no property at all tracks every compared property.

`except` is the one that avoids depth entirely: a scope naming only exclusions reaches as deep as the
model goes, so nobody has to count property steps. It narrows — a prepared scope, a declared scope and
a stated depth all keep their meaning, minus what is excluded — and a scope naming both tracked and
excluded properties is rejected rather than resolved by a precedence rule.

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("under() reaches a whole subtree the declared depth would have cut off") {
            paths(
                { under(Order::addresses) },
                order.copy(addresses = listOf(a1, a2.copy(city = "Nice"))),
            ) shouldContainExactly listOf("addresses[id=A2].city")
        }
```

### How the sources combine

One rule: **naming a property at the call site replaces the type's declared scope outright**, so you
can read a call site and know what will fire without consulting the type. A `depth` stated at the
call site instead applies *to* what the scope names — it never discards it, and never widens the
scope to a property it did not name.

Naming a property twice takes the widest depth, whichever order the selectors are written in.

## Callbacks

Two shapes, and a tracker accepts both, and more than one of either:

<!-- illustrative -->
```kotlin
onFieldChange { path, before, after -> }   // once per selected change
onChange { before, after, changes -> }     // once per update, with the whole set
```

`onFieldChange` fires in the order the differ found the changes. `onChange` fires **only when
something matched**, so a call means "something I asked about changed" — you never write
`if (changes.isEmpty()) return`.

The two sides a per-field callback receives, by kind of change:

| Change | `before` | `after` |
|---|---|---|
| `ValueChanged` | the old value | the new value |
| `TypeChanged` | the old value | the new value |
| `Added` | `null` | the new value |
| `Removed` | the old value | `null` |
| `Moved` | the old index | the new index |

A `Moved` delivering its two indices is deliberate — they genuinely are its old and new state, and
the alternative is dropping moves from the callback without saying so. The batched callback carries
the typed `Change`, so branch on that if you need to tell a move from a value change.

## A change at the tracked object itself

A sealed tracked type whose two instances are different subclasses reports a `TypeChanged` at the
root path. That belongs to no property, so no selector could name it — and it is therefore **always
reported**, whatever the scope. Suppressing it would hide the tracked object being replaced
wholesale.

## Tracking composes with patching

`update` returns a `Diff`, which is exactly what `Patcher.apply` consumes. So a tracker's output can
be applied to its baseline with no conversion — and a *narrowed* scope gives you selective
propagation for free:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("a narrowed scope propagates only the tracked field") {
            val next = order.copy(reference = "R-2", status = Status.CLOSED)
            val watcher = tracker(OrderDiffer, order) { field(Order::reference) }

            val patched = OrderDiffer.apply(order, watcher.update(next).changes)

            patched.failures.shouldBeEmpty()
            patched.value.reference shouldBe next.reference
            patched.value.status shouldBe order.status
        }
```

No failures are reported for the excluded change, because an excluded change was never in the report.
An unrestricted scope reproduces the target exactly, as any diff does.

## Types you cannot annotate

A scope can be built by hand for a type whose source you do not own — see
[hand-written.md](hand-written.md).

## Where to go next

- [Hand-written differs and scopes](hand-written.md) — `trackScope { }`, and why tracking needs no
  escape hatch of its own
- [Diffing](diffing.md) — `route` decides what a reported change *means*
- [Patching](patching.md) — what `update`'s `Diff` can be applied to
- [Annotation reference](annotations.md) — `@Trackable`, `@TrackIgnore`, `@TrackDepth` and what they
  reject
- [Tutorial](tutorial.md) — a scope written as `except`, so nobody counts depth
