## Context

Every comparison algorithm already lives in `kdiff-runtime` (`compareValue`, `compareNested`,
`compareNestedNullable`, `compareKeyedList`, `comparePositionalList`, `compareSet`, `compareMap`); the
processor only decides which one each property needs and emits the call. `DifferBuilder` reaches two
of the seven. That asymmetry, not any missing algorithm, is why an unannotated domain is a second-class
citizen — and closing it is mostly wiring, since the builder can call exactly what the generated code
calls.

Dispatch is the other half. Field tokens are generated per `@Trackable` class, so the hand-written
route has nothing equivalent, and the tutorial's dispatch is a two-level `when` over a generated sealed
hierarchy. The decision recorded here is to route on property references instead and delete tokens
outright, rather than carry two dispatch mechanisms that differ only in which authoring route can reach
them.

Constraints that shape everything below: `kdiff-runtime` depends on the Kotlin standard library alone,
so `KProperty1` is the only reflection available (reading `name` and calling `get` needs no
`kotlin-reflect`); `Change` is sealed and closed; and a hand-written differ must stay indistinguishable
from a generated one.

## Goals / Non-Goals

**Goals:**

- A domain with no kdiff import can be compared, tracked and dispatched on with no loss against the
  annotated route.
- One dispatch mechanism, reachable identically from both routes.
- A command handler whose kdiff usage is two lines: compare through a scope, route the result.
- The tutorial teaches the pattern, not the library's edge cases.

**Non-Goals:**

- A patching builder. `Patcher` still has no DSL, for the reason it never had one: a builder cannot
  know a constructor. A hand-written differ's property stays unpatchable unless a `Patcher` is written
  alongside.
- Compile-time exhaustiveness over a model's properties. That is what tokens bought and what routing
  gives up; see Risks.
- Inferring a model's shape at runtime. Nothing here reads a type's structure; every element and key
  type is supplied by the caller through a property reference.
- Any change to annotation semantics, to the change vocabulary, or to what a path looks like.

## Decisions

### D1 — `differ { }` gains one builder call per comparison shape

The builder collects closures over `(MutableList<Change>, before, after)`, exactly as today; each new
call appends a closure that invokes the matching runtime helper. No new comparison logic is written.

```kotlin
public fun <V : Any> field(property: KProperty1<T, V?>)
public fun <V : Any> nested(property: KProperty1<T, V?>, differ: Differ<V>)
public fun <E : Any> list(property: KProperty1<T, List<E>>, differ: Differ<E>? = null)
public fun <E : Any, K : Any> keyedList(
    property: KProperty1<T, List<E>>,
    key: KProperty1<E, K>,
    differ: Differ<E>,
)
public fun set(property: KProperty1<T, Set<*>>)
public fun <V : Any> map(property: KProperty1<T, Map<*, V>>, values: Differ<V>? = null)
public fun <S : T & Any> subtype(type: KClass<S>, differ: Differ<S>)
```

`KProperty1<T, out R>` is covariant in its value type, so `KProperty1<Person, FullName>` *is* a
`KProperty1<Person, FullName?>`: one `nested` overload serves both the nullable and the non-null case
by always calling `compareNestedNullable`, whose behaviour for two non-null values is identical to
`compareNested`. That removes an overload pair rather than adding one.

`keyedList` takes the key as a property reference and reads both halves the runtime needs from it —
`key.name` for the path segment (`addresses[id=A1]`) and `key::get` as the extractor — so the call site
cannot name a key property that does not exist, and cannot get the two out of step.

`subtype` is the sealed case. When at least one subtype is declared, `diff` first tests the runtime
type, and reports a `TypeChanged` only when the two runtime types actually differ — a hand-written
differ can omit a subtype, and two instances of one *undeclared* subtype changed no type:

```kotlin
val EmploymentDiffer: Differ<Employment> = differ {
    subtype(Employed::class, EmployedDiffer)
    subtype(Retired::class, RetiredDiffer)
}
```

Both sides the same declared subtype delegates to that subtype's differ; anything else reports one
`TypeChanged` at `FieldPath.ROOT` and then runs the field comparisons declared alongside — the same
two branches `sealedBody` generates, and the same reason: the parent's own properties are the only ones
comparable across a swap. `KClass.isInstance` is stdlib, so this needs no reflection library. A
`when`-style ordering hazard does not arise: subtypes are matched by exact registration, and a value
matching none takes the swap branch.

### D2 — Routing replaces tokens

New file `kdiff-runtime/Route.kt`. Routing is a pass over the diff's changes, grouped by the root
segment of each path:

```kotlin
val events = buildList {
    changes.route<Person> {
        on(Person::name) { add(PersonRenamed(id, current.name, desired.name)) }
        on(Person::nickname) { add(NicknameChanged(id, current.nickname, desired.nickname)) }

        onEach(Person::addresses, Address::id) {
            added { add(AddressAdded(id, it)) }
            removed { add(AddressRemoved(id, it)) }
            moved { key, from, to -> add(AddressesReordered(id, key, from, to)) }
            changed { key -> add(AddressEdited(id, current.addressBy(key)!!, desired.addressBy(key)!!)) }
        }

        on(Person::employment) { add(EmploymentChanged(id, current.employment, desired.employment)) }
        on(Person::salary) { add(SalaryAdjusted(id, current.salary, desired.salary)) }

        otherwise { audited += Diff(it) }
    }
}
```

Shape:

```kotlin
public fun <T> Diff.route(block: ChangeRoutes<T>.() -> Unit)

public class ChangeRoutes<T> internal constructor() {
    public fun on(property: KProperty1<T, *>, handler: (List<Change>) -> Unit)
    public inline fun <reified E : Any, reified K : Any> onEach(
        property: KProperty1<T, Collection<E>>,
        key: KProperty1<E, K>,
        block: KeyedElementRoutes<E, K>.() -> Unit,
    )
    public inline fun <reified E : Any> onEach(
        property: KProperty1<T, Collection<E>>,
        block: ElementRoutes<E>.() -> Unit,
    )
    public fun otherwise(handler: (List<Change>) -> Unit)
}
```

`ElementRoutes<E>` carries `added` and `removed`; `KeyedElementRoutes<E, K>` adds `moved` and
`changed`. Two types rather than one with a `K` that may be absent, because a `moved` handler on a
collection with no key can never fire — a silent no-op the type system can rule out instead.

An element routing returns the changes it has **no shape for**, and the caller sends those to
`otherwise`: an in-place change inside an unkeyed collection's element, or a value that is not of the
element type. Declining a kind of change it *can* express — declaring `removed` but not `added` — is a
decision, and those changes are dropped as before. The distinction matters because the first kind is
silent loss and the second is not.

Four decisions inside that shape, each of which could reasonably have gone the other way:

**A handler fires once per property, not once per change.** `Person::name` is a `FullName`, so a rename
reports two changes (`name.given`, `name.family`); firing per change would emit two `PersonRenamed`
events for one rename. Once per property, with the changes handed over for the callers that want them,
is the granularity a domain operation is written at. The same argument makes `changed` fire once per
element rather than once per change inside it.

**Element access is `reified`, not a cast the caller writes.** `onEach` is `inline` with `reified E` and
`reified K`, so `value as? E` is a checked cast inside the runtime — the guarantee tokens gave through
generated code, obtained here from the call site instead. A value of the wrong type yields nothing
rather than a `ClassCastException`, matching what the tokens did.

**`otherwise` receives the leftovers as a list, once.** It is the audit branch, and an audit entry for a
transition is one entry. A change at the root of the routed type (a sealed swap) belongs to no property
and lands here, as it did with tokens resolving to `null`.

**Naming a property twice throws at routing time.** Two handlers for one property is a mistake with no
sensible resolution — running both duplicates events, running the first silently drops the second.
`require` in `on`/`onEach`, caught by the first test that exercises the route.

Handlers registered eagerly, changes dispatched when the `route` block returns, so registration order
does not constrain the diff's order and `otherwise` can be declared anywhere.

### D3 — Deleting tokens

Removed from `kdiff-runtime`: `Tokens.kt` entire (`FieldToken`, `FieldTokens`, `ElementField`,
`KeyedField`, `Change.fieldOf`, `FieldToken.owns`, `elementValueOf`, `elementKeyOf`) and `TokensSpec`.

Removed from `kdiff-processor`: `tokenType`, `tokenObject`, `tokenCompanion`, `tokenName`,
`fieldTokenName`, the token entries in `Names.kt`, and the token-only plumbing in `Resolution.kt` —
`Comparison.elementType()`, `Comparison.KeyedList.keyType`, and the `element` field on `KeyedList`,
`PositionalList`, `AsSet` and `AsMap`. `KSType.tokenElement()` goes with them. That plumbing exists
solely so a token can name its element type; the comparison itself never reads it.

Generated output afterwards is the differ object alone:

```kotlin
// build/generated/ksp/main/kotlin/demo/OrderDiff.kt
public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order> {
    override val trackScope: TrackScope<Order> = trackScopeOf(TrackedField("reference", 1))

    override fun diff(before: Order, after: Order): Diff = Diff(buildList { … })

    override fun apply(before: Order, changes: List<Change>): PatchResult<Order> { … }
}
```

`Dependencies(aggregating = false, originatingFile)` is unchanged — one fewer type in the same file,
from the same sources. Incremental behaviour does not change.

### D4 — `except` on a scope, and exclusions in `ResolvedScope`

`TrackScopeBuilder` and `TrackerBuilder` gain `except(property: KProperty1<T, *>)`. `TrackScope` carries
an `excluded: Set<String>` alongside `fields`, and `ResolvedScope.selects` rejects a change whose root
segment is excluded before applying any depth rule — so an exclusion cannot be widened back by a stated
depth. Building a scope that both names tracked properties and excludes others is a `require` failure:
the two say opposite things about every property named in neither, and picking a precedence would be
exactly the silent widening `TrackScopeCompositionSpec` guards against.

`except` composes with a prepared scope the way a stated depth does — it narrows what the prepared scope
names, never discards it.

### D5 — `trackedDiff`, so a handler needs no `Tracker`

```kotlin
public fun <T> Differ<T>.trackedDiff(before: T, after: T, scope: TrackScope<T>? = null): Diff
```

Implemented as `Diff(diff(before, after).changes.filter(resolved::selects))`, resolving the scope
through the existing `resolveAgainst(this)` — literally what `Tracker.update` does minus the baseline
and the callbacks, so the two cannot drift. A `Tracker` is the right tool for an evolving instance; a
command handler holds two instances and wants the tracked view of the difference, and paying for a
mutable baseline plus a callback to get it is the ceremony the tutorial currently displays.

### D6 — The tutorial

Rebuilt around a domain that imports nothing from kdiff.

```
tutorial/domain/         Person, Address, Employment, FullName, ids, Money — plain data classes
tutorial/diff/           PersonDiffing.kt: PersonDiffer + PersonScope, the only kdiff-aware file
tutorial/event/          PersonEvent and its subclasses
tutorial/app/            PersonRepository, UpdatePersonHandler, Main
tutorial/annotated/      the same model annotated, plus nothing else
```

`PersonState` is gone: `Person` is the data class, and the handler is a decider rather than a mutable
aggregate.

```kotlin
fun handle(command: UpdatePerson): List<PersonEvent> {
    val current = repository.load(command.id) ?: error("no person ${command.id}")
    val desired = command.applyTo(current)
    val changes = PersonDiffer.trackedDiff(current, desired, PersonScope)

    val events = buildList { changes.route<Person> { … } }

    repository.save(desired)
    return events
}
```

Three pieces of the current tutorial disappear with the mutable aggregate, and each was teaching a
workaround rather than the pattern: the no-op guards (the diff already proves every change is real),
the index clamping in `reorderAddress` (nothing is applied incrementally, so `Moved`'s indices are only
reported), and `rehydrate`/`snapshot` (the state after the command *is* `desired`).

The whole kdiff surface the tutorial uses fits in one file:

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

val PersonScope = trackScope<Person> { except(Person::lastSeenAt) }
```

`except` makes the depth trap the current tutorial devotes a whole section to structurally
unreachable: a hand-written scope naming no tracked property tracks everything at unlimited depth, so
`name.family` and `addresses[id=A1].postalCode.value` are reported without anyone choosing a number.
The annotated mirror still needs `@Trackable(depth = …)`, and the tutorial says so in one paragraph
instead of three.

`tutorial/annotated/` holds the same model annotated in a single file with no descriptor object, and
`AnnotatedParitySpec` asserts that both routes report equal changes for the same transition — the
"indistinguishable" requirement, tested where a reader can see it.

Money keeps its hand-written differ (a foreign type is why `@DiffWith` exists) but loses its
hand-written `Patcher`: the tutorial never patches, and thirty lines of `groupByProperty` /
`unmatchedFailures` in the first example a reader meets sells the library badly. Patching stays where it
is taught, in `docs/patching.md` and `kdiff-sample`.

## Risks / Trade-offs

**Losing exhaustive dispatch is a real regression for annotated consumers.** A property added to a
`@Trackable` class used to break every `when` over its tokens until handled; now its changes quietly
reach `otherwise`. The mitigations are that `otherwise` is a visible, audited branch rather than a
silent `else`, and that property references still make a typo a compile error — what is lost is
*completeness*, not *safety*. Callers who depended on the compiler catching an unhandled new property
have no equivalent, and the proposal marks this BREAKING for that reason.

**Routing is order-independent, dispatch was not.** With tokens, one change hit one branch of one
`when`. With routing, a property handler and `otherwise` partition the changes, and the partition is
computed before any handler runs — so a handler cannot see what another handler did to the diff. That
is a simplification, but a caller relying on `when` fall-through semantics has to restructure.

**The builder can now describe a model wrongly in ways the processor would have rejected.** Naming a
`List<Address>` with `field` compares it as one value; naming the wrong key property compares by the
wrong identity. The processor diagnoses those at compile time; the builder cannot, because it is
ordinary Kotlin. The parity spec in the tutorial is the pattern for catching it — describe once,
compare against the annotated truth — and `docs/hand-written.md` states the hazard plainly.

**`reified` makes `onEach` inline and therefore public-API-fragile.** An inline function with reified
type parameters cannot access internal state, so `ChangeRoutes` needs a `@PublishedApi internal`
registration hook. That is a known cost of typed element access without reflection; the alternative —
taking `KClass<E>` explicitly — puts a second thing to get wrong at every call site.

**Deleting a capability shipped one change ago.** Field tokens were added in
`2026-09-04-add-typed-field-tokens`; removing them so soon costs the archived design its conclusion.
The reason is precisely what that change could not see: tokens are unreachable from the authoring route
this change makes first-class, and two dispatch mechanisms for one job is worse than one that serves
both.
