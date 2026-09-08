# How do I…

Recipes, named for what you are trying to do. Each is the smallest working code plus one link to the
page that explains the rule behind it.

Every Kotlin block here is lifted from a passing test, so a recipe cannot fall behind the API.

| I want to… | |
|---|---|
| see what changed between two instances | [compare two instances](#compare-two-instances) |
| log or audit every change | [audit a change](#audit-a-change) |
| turn changes into domain events | [emit domain events](#emit-domain-events) |
| act on one property only | [narrow a diff to one property](#narrow-a-diff-to-one-property) |
| rebuild an instance from a diff | [apply a diff back](#apply-a-diff-back) |
| fail unless every change applied | [require the whole patch](#require-the-whole-patch) |
| deal with changes that did not apply | [handle failures by cause](#handle-failures-by-cause) |
| compare a type I do not own | [compare a type you cannot annotate](#compare-a-type-you-cannot-annotate) |
| make such a property patchable too | [make a `@DiffWith` property patchable](#make-a-diffwith-property-patchable) |
| compare a value by something other than `equals` | [compare a value your own way](#compare-a-value-your-own-way) |
| react as a value evolves | [fire a callback when a value changes](#fire-a-callback-when-a-value-changes) |
| watch one property and ignore the rest | [track only what you care about](#track-only-what-you-care-about) |
| propagate only some changes | [propagate a subset of changes](#propagate-a-subset-of-changes) |
| tell a reorder from an add and a remove | [tell a move from an add and a remove](#tell-a-move-from-an-add-and-a-remove) |

## Compare two instances

Annotate the type; the differ appears in the same package at compile time.

<!-- from: kdiff-sample/src/main/kotlin/demo/Address.kt -->
```kotlin
@Diffable
data class Address(@DiffKey val id: String, val street: String, val city: String)
```

<!-- illustrative -->
```kotlin
val diff = AddressDiffer.diff(before, after)

println(diff.render())
// city  "Paris" -> "Nice"
```

`@DiffKey` matters as soon as this type is an element of a list — it is what lets a reorder report as
a move. → [Diffing](diffing.md)

## Audit a change

A `Diff` is an `Iterable<Change>`, so the standard library is all you need. Each change carries its
path and both sides.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            val diff = OrderDiffer.diff(order, order.copy(reference = "R-2", status = Status.CLOSED))

            val audit = diff.map { "${it.path}: $it" }
```

For a human reader, `diff.render()` gives one line per change and `diff.tree()` gives the same changes
as a hierarchy. → [What you get back](diffing.md#what-you-get-back)

## Emit domain events

Route the diff and name each property by reference, so a typo is a compile error rather than a string
that matches nothing for ever. A handler runs once per property however many changes lie beneath it.

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
        changes.route<Person> {
            on(Person::name) { add(PersonRenamed(desired.id, current.name, desired.name)) }
            on(Person::nickname) { add(NicknameChanged(desired.id, current.nickname, desired.nickname)) }

            under(Person::contact) {
                on(Contact::email) { add(EmailChanged(desired.id, current.contact.email, desired.contact.email)) }
```

`under` frames a value object so its own properties can be named, and frames nest as deep as the model
does. `onEach(Person::addresses, Address::id)` routes a collection by element, delivering `added`,
`removed`, `moved` and `changed` at their own types. Whatever no handler names at any depth reaches the
single `otherwise`. → [Deciding what a change means](diffing.md#deciding-what-a-change-means-route),
and [the tutorial](tutorial.md) for this worked end to end.

## Narrow a diff to one property

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            diff.at<Order>(Order::billing).changes.shouldContainExactly(emptyList())
            diff.under<Order>(Order::billing).changes.map { it.path.toString() } shouldContainExactly
                listOf("billing.city")
```

`at` is the property's own change; `under` is that plus everything beneath it. **Name the type** —
`at<Order>(…)` — or the property is checked against nothing and a property of an unrelated type
compiles while matching nothing. Routing has no such hole. →
[Narrowing to one property](diffing.md#narrowing-to-one-property)

## Apply a diff back

The same generated object rebuilds the target from the source.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.value.reference shouldBe "R-2"
            result.value.weight.grams shouldBe "500"
            result.isClean shouldBe false
```

The result is partial by design: what could be applied *is* applied, so `value` is always usable and
`failures` says what is missing. Here `reference` applied and `weight` did not, because `weight` is
compared by a differ that cannot patch. → [Patching](patching.md)

## Require the whole patch

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            val raised = shouldThrow<PatchFailedException> { OrderDiffer.apply(order, changes).getOrThrow() }

            raised.failures.map { it.reason } shouldContainExactly
                listOf(PatchFailure.Reason.UnpatchableProperty("weight"))
```

`getOrThrow()` returns the value when every change applied and raises `PatchFailedException` — carrying
every failure — when any did not. `apply` stays partial; strictness is stated at the call site. →
[Requiring the whole patch](patching.md#requiring-the-whole-patch)

## Handle failures by cause

Fourteen reasons, five causes. Branch on the cause, not the sentence.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            val byCause = OrderDiffer.apply(order, changes).failures.groupBy { it.reason.cause() }
```

The `cause()` this calls is an exhaustive `when` over the closed vocabulary — copy it from
[the error page](errors.md#branching-on-a-cause), where every case is documented with what to change.
Two of the five causes are problems in your model; three mean a diff met a source it did not come
from. → [Errors](errors.md#3-changes-that-did-not-apply)

## Compare a type you cannot annotate

Describe it in ordinary Kotlin. The result is indistinguishable from a generated differ to anything
consuming it.

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })
```

Then point the property at it, and the generated differ delegates that property's comparison,
prefixing paths as it would any nested type:

<!-- illustrative -->
```kotlin
@Diffable
data class Order(
    @DiffWith(WeightDiffer::class) val weight: Weight,
)
```

Property references are stdlib, so this needs no `kotlin-reflect`. →
[Hand-written differs](hand-written.md)

## Make a `@DiffWith` property patchable

A `differ { }` differ compares but cannot reconstruct, so its property is unpatchable. Implement
`Patcher` alongside — there is deliberately no builder, because a builder cannot know a constructor.

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

Group first, then one `patch*` helper per property, then construct and gather the failures. That is the
same shape the processor generates. → [Adding `Patcher` by hand](hand-written.md#adding-patcher-by-hand)

## Compare a value your own way

`field` compares by equality. To compare numerically — so `"10"` and `"10.00"` agree — write the
comparison out over the same runtime helpers the DSL calls:

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
object NumericMoneyDiffer : Differ<Money> {
    override fun diff(before: Money, after: Money): Diff = Diff(
        buildList {
            if (BigDecimal(before.amount).compareTo(BigDecimal(after.amount)) != 0) {
                add(ValueChanged(FieldPath.of("amount"), before.amount, after.amount))
            }
            compareValue("currency", before.currency, after.currency)
        },
    )
}
```

The change is added directly rather than through `compareValue` so it reports the values the model
holds, not the normalised ones — which is exactly why the DSL cannot express this. Note that custom
comparison is **per property, not per type**: there is no global "compare every `BigDecimal` this way".
→ [When the DSL is not enough](hand-written.md#when-the-dsl-is-not-enough)

## Fire a callback when a value changes

A tracker holds a baseline and compares each new instance against it.

<!-- illustrative -->
```kotlin
val tracker = tracker(OrderDiffer, order) {
    onFieldChange { path, from, to -> log("$path: $from -> $to") }
}

tracker.update(next)
```

`update` also returns the selected changes as a `Diff`, so a caller who prefers a return value needs no
callback at all. A `Tracker` is not thread-safe — confine one to a thread.

If you already hold both instances — a command handler comparing stored against requested — call
`trackedDiff` on the differ instead, which reports what a tracker would and remembers nothing:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/app/UpdatePersonHandler.kt -->
```kotlin
        val events = eventsFor(current, desired, PersonDiffer.trackedDiff(current, desired, PersonScope))
```

Passing no scope uses the one the type declared with `@Trackable`. → [Tracking](tracking.md)

## Track only what you care about

Name properties at the call site, and the type's declared scope is replaced outright:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
                val watcher = tracker(OrderDiffer, order) { field(Order::reference) }
```

For a domain model, saying it the other way round is usually what you want — `except` reaches as deep
as the model goes, so nobody counts property steps:

<!-- from: kdiff-tutorial/src/main/kotlin/tutorial/diff/PersonDiffing.kt -->
```kotlin
val PersonScope = trackScope<Person> { except(Person::lastSeenAt) }
```

A scope names what it tracks *or* what it excludes, never both. →
[Scopes at the call site](tracking.md#scopes-at-the-call-site)

## Propagate a subset of changes

`update` returns a `Diff`, which is what `apply` consumes — so a narrowed scope gives selective
propagation with no conversion and no filtering of your own:

<!-- from: kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt -->
```kotlin
                val patched = OrderDiffer.apply(order, watcher.update(next).changes)

                patched.failures.shouldBeEmpty()
                patched.value.reference shouldBe next.reference
                patched.value.status shouldBe order.status
```

No failure is reported for the excluded change, because an excluded change was never in the report. →
[Tracking composes with patching](tracking.md#tracking-composes-with-patching)

## Tell a move from an add and a remove

Give the element type a `@DiffKey`. With one, a reordered element keeps its identity and reports as
`Moved`; without one, there is nothing to recognise it by.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactlyInAnyOrder
                listOf("addresses[id=A1]", "addresses[id=A2]")
            changes.filterNot { it is Moved }.map { it.path.toString() } shouldContainExactly
                listOf("tags[0]", "tags[1]")
```

`addresses` is keyed, so swapping two elements reports two moves — each describing where that element
ended up. `tags` is an unkeyed `List<String>`, so the same swap reports as value changes at the two
positions instead. A key must be unique in the list, or comparing it raises
[`DuplicateDiffKeyException`](errors.md#duplicatediffkeyexception). → [Lists](diffing.md#lists)

## Model a state with a payload-free case

Declare it as a `data object` and leave it unannotated. The sealed parent dispatches on it, and
`@Diffable` on the object itself is
[an error](errors.md#diffable-on-the-wrong-declaration) because it would configure nothing.

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
data object Unpaid : Payment {
    override val amount: String get() = "0"
}
```

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            // Entering the state: a type change carrying both instances, plus whatever the sealed
            // parent declares itself.
            val entering = OrderDiffer.diff(order, unpaid)
            entering.changes.filterIsInstance<TypeChanged>().single().afterType shouldBe "Unpaid"
            entering.changes.map { it.path.toString() } shouldContainExactly
                listOf("payment", "payment.amount")
```

Staying in the state reports nothing, because a singleton has no state to differ in. Leaving it is
applied by substitution, like any type change. →
[A payload-free case](diffing.md#a-payload-free-case)

## Diff a collection that can be absent

Declare the property nullable and nothing else changes: the same annotation, the same builder call.
Appearing or disappearing is one change at the property; present on both sides it is compared as that
collection always is.

<!-- from: kdiff-sample/src/test/kotlin/demo/RecipesSpec.kt -->
```kotlin
            OrderDiffer.diff(withoutCoupons, withCoupons).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("couponCodes"), null, listOf("SAVE10")))

            // Present on both sides it is compared as a list, element by element.
            OrderDiffer.diff(withCoupons, order.copy(couponCodes = listOf("SAVE20")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes[0]")
```

An absent collection is not an empty one: `null` to `[]` is a change at the property, and `[]` to
`["a"]` is an addition at `couponCodes[0]`. What kdiff will not take is a nullable *element* reached
through a differ, such as `List<Address?>` — that is
[a compile error](errors.md#a-collection-whose-elements-are-nullable-and-reached-through-a-differ). →
[Values, enums and nullables](diffing.md#values-enums-and-nullables)

## Where to go next

- [Tutorial](tutorial.md) — these pieces assembled into one worked application
- [Errors](errors.md) — every message kdiff produces, and what to change
- [FAQ](faq.md) — the questions behind several of these recipes
- [Diffing](diffing.md) — the comparison rules the recipes rely on
- [Patching](patching.md) — the guarantees `apply` makes
- [Tracking](tracking.md) — scopes, depth and callbacks in full
- [Hand-written differs and scopes](hand-written.md) — the escape hatch, in full
