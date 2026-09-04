# Hand-written differs and scopes

You cannot annotate a type you do not own. kdiff's answer is a small DSL that builds the same things
the processor generates, from ordinary Kotlin — and the result is indistinguishable to anything
consuming it, so the two compose in either direction.

## Which capabilities have a builder, and why

| Capability | Interface | From an annotation | By hand |
|---|---|---|---|
| compare | `Differ<T>` | `@Diffable` | `differ { }` |
| apply | `Patcher<T>` | `@Diffable`, same object | `object : Patcher<T>` — **no builder** |
| track | `Tracked<T>` | `@Trackable`, same object | `trackScope { }` |

The rule: **a capability gets a builder exactly when it only needs to *name* properties.**

Comparing reads properties, so a builder can do it generically. Tracking only names properties and
depths, so it can too. But patching must *construct* the property's owner, and a builder cannot know
a constructor — which is why `Patcher` has none, and why you write one out by hand when you need it.

## `differ { }`

Name the properties to compare, by reference:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })
```

Property references are stdlib — reading `name` and calling `get` needs no `kotlin-reflect`, so this
adds no dependency.

### The whole vocabulary

One call per comparison shape, and between them they cover everything `@Diffable` can declare:

| Call | Compares | Annotated equivalent |
|---|---|---|
| `field(p)` | by value | a scalar or enum property |
| `nested(p, differ)` | by delegating, nullable or not | a `@Diffable` property type |
| `list(p, differ?)` | index by index; never a move | a `List` whose element declares no key |
| `keyedList(p, key, differ)` | by element identity; a reorder is a move | a `List` whose element declares `@DiffKey` |
| `set(p)` | as unordered membership | a `Set` |
| `map(p, values?)` | by entry key | a `Map` |
| `subtype(Type::class, differ)` | by dispatching on the runtime subclass | `@Diffable` on a sealed type |
| *naming it nowhere* | not at all | `@DiffIgnore` |

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val PersonDiffer: Differ<Person> = differ {
    nested(Person::name, FullNameDiffer)
    field(Person::nickname)
    keyedList(Person::addresses, Address::id, AddressDiffer)
    nested(Person::employment, EmploymentDiffer)
    nested(Person::salary, MoneyDiffer)
    set(Person::tags)
    field(Person::lastSeenAt)
}
```

`keyedList` takes the key as a property reference and reads both halves the comparison needs from it —
the name a path segment carries, and the value elements are matched by — so the two cannot get out of
step.

`subtype` is the sealed case. Two instances of one declared subtype are compared by that subtype's
differ; anything else reports a type change at the root and then compares whatever `field` calls sit
alongside, because a parent's own properties are the only ones comparable across a swap:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val EmploymentDiffer: Differ<Employment> = differ {
    subtype(Employed::class, differ { field(Employed::employer); field(Employed::since) })
    subtype(Retired::class, differ { field(Retired::since) })
}
```

### What the builder cannot tell you

The processor rejects a model it cannot compare; the builder is ordinary Kotlin and cannot. Naming a
`List<Address>` with `field` compares the whole list as one value, and naming the wrong key property
matches elements by the wrong identity — both compile, and both are wrong at runtime.

The answer is a parity test: describe the model by hand, describe it once with annotations, and hold
the two to the same output. `kdiff-tutorial` does exactly that in `AnnotatedParitySpec`.

Hold the result in an `object` so a property can point at it with `@DiffWith`:

<!-- illustrative -->
```kotlin
@Diffable
data class Order(
    @DiffWith(WeightDiffer::class) val weight: Weight,
)
```

The generated differ then delegates that property's comparison, prefixing the paths like any nested
delegation. A change inside it reports at `weight.grams`.

## Adding `Patcher` by hand

A `differ { }` differ can compare but not reconstruct, so a property using it is **unpatchable** — its
changes come back as failures and the rest of the instance still patches. To make it patchable,
implement `Patcher` alongside:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
object MoneyDiffer :
    Differ<Money> by differ({
        field(Money::amount)
        field(Money::currency)
    }),
    Patcher<Money> {
    override fun apply(before: Money, changes: List<Change>): PatchResult<Money> {
        val grouped = groupByProperty(changes, setOf("amount", "currency"))
        val amount = patchValue(before.amount, grouped.forProperty("amount"))
        val currency = patchValue(before.currency, grouped.forProperty("currency"))
        return PatchResult(
            Money(amount.value, currency.value),
            grouped.unmatchedFailures("Money") + amount.failures + currency.failures,
        )
    }
}
```

That is the whole pattern, and it is the same shape the processor generates:

1. `groupByProperty(changes, names)` sorts changes into the property each belongs to, stripping that
   property's segment from the path. Grouping first is what lets you rebuild through a single
   constructor call: reconstruction needs the final value of every property at once, and a property
   with several changes beneath it is rebuilt once rather than once per change.
2. One `patch*` helper per property — `patchValue`, `patchNested`, `patchNestedNullable`,
   `patchKeyedList`, `patchPositionalList`, `patchSet`, `patchMap`. Each returns the new value plus
   whatever it could not use.
3. Construct, and gather the failures — including `unmatchedFailures`, for changes that named no
   property you handle.

`MoneyDiffer` round-trips like a generated one; `WeightDiffer` is deliberately left compare-only so
the unpatchable path has something exercising it.

## `trackScope { }`

The same idea for tracking. A scope names properties and depths, so it needs no annotation:

<!-- illustrative -->
```kotlin
val scope = trackScope<Money> {
    field(Money::amount)
}

val tracker = tracker(MoneyDiffer, Money("10", "EUR"), scope) {
    onFieldChange { path, from, to -> log("$path: $from -> $to") }
}
```

A hand-written scope behaves identically to one a `@Trackable` class declares — the tracker cannot
tell them apart, because there is nothing on the type to tell it with.

`except(property)` says the same thing the other way round, and is usually what a domain model wants:
track everything, as deep as the model goes, bar the bookkeeping.

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val PersonScope = trackScope<Person> { except(Person::lastSeenAt) }
```

A scope names what it tracks or what it excludes, never both — the two say opposite things about every
property named in neither, and guessing which one wins is how a tracker ends up reporting more than
its caller asked for.

So a type carrying no kdiff annotation at all can be both compared and tracked:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("Money is compared by a hand-written differ and tracked by a hand-written scope") {
            val watcher = tracker(MoneyDiffer, Money("10", "EUR"), trackScope { field(Money::amount) })

            watcher.update(Money("12", "USD")).changes.map { it.path.toString() } shouldContainExactly
                listOf("amount")
        }
```

## Tracking needs no escape hatch of its own

There is no `@TrackWith`, and there is nothing for one to do.

Comparison needs `@DiffWith` because a property's *type* may be uncomparable by kdiff's rules.
Tracking has no such failure mode: whatever produced a change, it arrives with an ordinary
`FieldPath`, and selection and depth apply to it unchanged. A property delegated to a hand-written
differ tracks like any nested property.

Which yields a case worth knowing: a compare-only differ makes a property **unpatchable yet still
trackable**.

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
        test("the compare-only Weight differ is unpatchable yet still trackable") {
            val next = order.copy(weight = Weight("600"))

            val reported = tracker(OrderDiffer, order) { under(Order::weight) }.update(next)

            reported.changes.map { it.path.toString() } shouldContainExactly listOf("weight.grams")
```

## When the DSL is not enough

Every builder call is a call to a runtime compare helper — `compareValue`, `compareNested`,
`compareNestedNullable`, `compareKeyedList`, `comparePositionalList`, `compareSet`, `compareMap` — and
those are the same helpers the generated code calls. For a comparison the builder cannot express, use
them directly in a plain `Differ<T>` implementation: the result is still indistinguishable from a
generated differ, because it is made of the same parts.
