## Context

See `proposal.md — Why` for motivation and `specs/diff-generation/spec.md` for the requirements.

What already exists and shapes the approach:

- `FieldPath` is a `@JvmInline value class` over `List<Segment>`; `Segment` is sealed with
  `Field(name)`, `Index(index)` and `Key(property, value)`. `Key` retains the key **as its own value**,
  which is what makes typed key access possible at all.
- `Change` is sealed and closed. `Added`/`Removed` carry `value: Any?`; `Moved` carries two indices and
  no value.
- The processor resolves each property once into a `Comparison` — `ByValue`, `Nested`, `KeyedList`,
  `PositionalList`, `AsSet`, `AsMap` — and generated code is a straight-line sequence of calls into
  `kdiff-runtime` helpers.
- `@Trackable` already marks the classes that want dispatch support, so no new annotation is needed.
- Generated code never uses reflection, and `kdiff-runtime` depends only on the Kotlin standard
  library.

## Goals / Non-Goals

**Goals:**

- A mistyped property name does not compile.
- A property added to a tracked model does not compile until the dispatch handles it.
- A consumer dispatching over changes writes no casts.
- Nothing about comparison, application or tracking changes.

**Non-Goals:**

- **Typed access to a change's value.** Unsound for nested properties; see `proposal.md — Impact`.
- **Tokens for every `@Diffable` type.** Gated on `@Trackable`; see D2.
- **A dispatch router or handler registry.** The point is to make the ordinary `when` safe, not to
  replace it with machinery. A reader should still see the control flow.
- **Typed access to a map entry's key**, as distinct from a keyed list's element key. `Segment.Key`
  already carries it, and no worked example needs it typed yet.
- **Changing `Change`, `FieldPath` or `Segment`.** The vocabulary stays closed and the path model
  stays as it is; this change only adds ways to *read* them.

## Decisions

### D1. A sealed hierarchy of `data object`s, because only a sealed `when` can be exhaustive

Exhaustiveness is the whole point, and Kotlin gives it for a `when` over a sealed type or an enum.
A sealed hierarchy of `data object`s is chosen over an enum because a token needs to carry **different
capabilities per property** — a keyed list's token exposes an element and a key, a scalar's token
exposes neither — and enum entries cannot implement different interfaces.

```kotlin
public sealed interface PersonStateField : FieldToken {
    public data object Name : PersonStateField {
        override val propertyName: String = "name"
    }

    public data object Addresses : PersonStateField, KeyedField<Address, AddressId> { /* D4 */ }

    public companion object : FieldTokens<PersonStateField> { /* D3 */ }
}
```

*Alternative rejected:* an enum with a nullable `elementType`. Exhaustive too, and simpler to emit, but
every token would then expose element access whether or not it means anything, and the accessor could
not be typed — which is half of what this change is for.

### D2. Gated on `@Trackable`, covering every compared property

**Gate.** `@Trackable` marks the classes whose changes get dispatched. Generating ~30 lines per type
for every `@Diffable` class would inflate every consumer's generated code for an API most never use.

*Cost, stated plainly:* a consumer who diffs without tracking must add `@Trackable` to get tokens, and
`@Trackable` also declares a tracking scope they may not want. That is a real wart. The alternative —
a second annotation such as `@WithFieldTokens` — buys precision at the cost of an annotation whose only
job is to switch on codegen, and the project already has seven. Revisit if anyone asks.

**Coverage.** Tokens cover the *compared* set (`comparableProperties()`), not the tracked subset. A
dispatch may run over a raw `Diff`, which reports `@TrackIgnore` properties; if those had no token,
`null` would mean both "a change at the root" and "a property I chose not to track". Keeping `null` to
one meaning matters more than a slightly smaller token set. `@DiffIgnore` properties get no token
because they can never appear in a change at all.

### D3. Resolution is a generated `when` over names, reached through a runtime interface

```kotlin
// kdiff-runtime
public interface FieldToken {
    public val propertyName: String
}

public interface FieldTokens<F : FieldToken> {
    public fun byName(propertyName: String): F?
}

public fun <F : FieldToken> Change.fieldOf(tokens: FieldTokens<F>): F? =
    path.rootName()?.let(tokens::byName)

public fun FieldPath.rootName(): String? = (segments.firstOrNull() as? Segment.Field)?.name
```

```kotlin
// generated companion
public companion object : FieldTokens<PersonStateField> {
    override fun byName(propertyName: String): PersonStateField? = when (propertyName) {
        "name" -> Name
        "addresses" -> Addresses
        else -> null
    }
}
```

The interface lives in the runtime and the table is generated, which is the split `Compare.kt` already
documents: an algorithm that could need fixing lives where a dependency bump fixes it, and the
per-type data is generated. Here the "algorithm" is one line, but the shape matters — `fieldOf` is
where a future refinement would go.

`rootName()` is the tutorial's local `root()` promoted verbatim. It yields nothing for the root path
and nothing when the first segment is an index or key, which cannot happen at the top of a path today
but is the correct answer if it ever does.

*Alternative rejected:* a generated `Map<String, F>`. A `when` over string literals compiles to a
lookup and allocates nothing at class-init time.

### D4. Typed access casts inside generated code, where the type is already known

```kotlin
// kdiff-runtime
public interface ElementField<E : Any> : FieldToken {
    public fun elementOf(change: Change): E?
}

public interface KeyedField<E : Any, K : Any> : ElementField<E> {
    public fun keyOf(change: Change): K?
}
```

**Corrected during implementation.** This design first had the generated token do the whole job —
inspect the change kind and cast. A test proved it wrong: `Numbers.elementOf` returned a value for a
change under a *different* property, because that change happened to carry a value of the element type.
Checking the change kind is not enough; the token must also check the change belongs to it.

So the rule lives in the runtime, where it exists once, and generated code adds only the cast:

```kotlin
// kdiff-runtime
public fun FieldToken.owns(change: Change): Boolean = change.path.rootName() == propertyName

public fun FieldToken.elementValueOf(change: Change): Any? = when {
    !owns(change) -> null
    change is Added -> change.value
    change is Removed -> change.value
    else -> null
}

public fun FieldToken.elementKeyOf(change: Change): Any? = when {
    !owns(change) -> null
    else -> (change.path.segments.getOrNull(1) as? Segment.Key)?.value
}
```

```kotlin
// generated for a keyed List<Address> whose @DiffKey property is AddressId
public data object Addresses : PersonStateField, KeyedField<Address, AddressId> {
    override val propertyName: String = "addresses"
    override fun elementOf(change: Change): Address? = elementValueOf(change) as? Address
    override fun keyOf(change: Change): AddressId? = elementKeyOf(change) as? AddressId
}
```

Better on both counts this way: one line of generated code per accessor instead of six, and the
ownership rule cannot be got wrong per-type. It is also the split `Compare.kt` documents — behaviour in
the runtime, per-type data generated.

The casts are `as?` on a concrete, non-generic type, so they compile to `instanceof` — no reflection
and no unchecked-cast warning. The consumer writes none of it.

`elementOf` returning `null` for `Moved` is not an oversight: a move carries two positions and no
value, and the spec says so.

`E : Any`, so a nullable element type (`List<Address?>`) generates the non-null element type. An added
null element yields `null` from `elementOf`, which is indistinguishable from "no element" — an accepted
limitation, noted because it is the one place typed access loses information.

### D5. Which token interface each `Comparison` maps to — and what the processor must learn

| `Comparison` | Token | Type arguments |
|---|---|---|
| `ByValue` | plain | — |
| `Nested` | plain | — |
| `KeyedList` | `KeyedField<E, K>` | element type, `@DiffKey` property's type |
| `PositionalList` | `ElementField<E>` | element type |
| `AsSet` | `ElementField<E>` | element type |
| `AsMap` | `ElementField<V>` | map **value** type |

A plain token for `Nested` is deliberate: a nested property's changes arrive *below* it
(`billing.city`), so there is no element and no meaningful typed value (see Non-Goals).

**This needs the resolution model extended.** `Comparison` today carries only what emission needed:
`KeyedList` has `element` but not the key's *type*; `PositionalList` has a differ but not the element
type; `AsSet` and `AsMap` are a `data object` and a value-differ respectively and carry no element type
at all. So `Resolution.kt` gains those type names, filled in where the types are already resolved in
`resolveList` and `resolveMap`.

That is the largest single piece of work in this change, and it is additive — the existing fields keep
their meaning, so `emit` and `emitPatch` are untouched.

### D6. Token names are PascalCase of the property, and a collision is a compile error

`externalIds` → `ExternalIds`. Two properties can collide (`externalIds` and `external_ids` both
produce `ExternalIds`), and Kotlin would reject the duplicate declaration with an error pointing at
generated code — which is exactly the diagnostic style the project forbids.

So the processor detects the collision and reports it at the class, naming both properties, per the
project's rule that user-facing problems are diagnostics at the offending symbol. Renaming one token
silently is worse than stopping: a caller would match a token they did not expect.

A property whose name does not yield a valid identifier after conversion is the second diagnostic.

### D7. Emitted into the existing `<Type>Diff.kt`

One generated file per annotated type, as now. The token hierarchy is a second top-level declaration in
that file alongside the differ object.

*Alternative rejected:* a separate `<Type>Fields.kt`. It would double the generated file count for a
gain of nothing — the tokens' dependencies are identical to the differ's.

### D8. Incremental processing is unchanged

`Dependencies(aggregating = false, originatingFile)` as before.

The tokens derive from the annotated class's own declarations plus the element and key types, and every
one of those files is *already* a dependency of the generated file because the differ delegates to
their differs. So the dependency set does not grow, and touching an unrelated file still regenerates
nothing.

### D9. Type resolution: what the tokens must get right

- **Nullability** — a nullable *property* is irrelevant to a token, which names the property. A
  nullable *element* type is D4's noted limitation.
- **Generics** — `@Diffable` already rejects a generic class, so no token is ever generic over the
  owning type. Element types are concrete.
- **Collections and maps** — D5's table, and the reason `Resolution.kt` must carry element types.
- **Enums** — compared by value, so a plain token.
- **Nested `@Diffable`** — a plain token, because changes arrive below the property.
- **Sealed types** — a sealed *property* gets a plain token. A change at the root of a sealed type being
  compared has no first `Field` segment, so `rootName()` yields nothing and `fieldOf` resolves to
  `null` — which is the `null` branch every exhaustive `when` must already handle.
- **`@DiffIgnore`** — no token, per D2.
- **`@DiffWith`** — resolved as `Nested`, so a plain token. The tutorial's `salary: Money` gets one.

### D10. The generated shape, reviewable now

For `kdiff-tutorial`'s `PersonState`, abbreviated:

```kotlin
public sealed interface PersonStateField : FieldToken {
  public data object Id : PersonStateField { override val propertyName: String = "id" }
  public data object Name : PersonStateField { override val propertyName: String = "name" }
  public data object Nickname : PersonStateField { override val propertyName: String = "nickname" }

  public data object Addresses : PersonStateField, KeyedField<Address, AddressId> {
    override val propertyName: String = "addresses"
    override fun elementOf(change: Change): Address? = when (change) {
      is Added -> change.value as? Address
      is Removed -> change.value as? Address
      else -> null
    }
    override fun keyOf(change: Change): AddressId? =
      (change.path.segments.getOrNull(1) as? Segment.Key)?.value as? AddressId
  }

  public data object Contacts : PersonStateField, ElementField<ContactMethod> { /* elementOf */ }
  public data object Employment : PersonStateField { /* plain */ }
  public data object Salary : PersonStateField { /* plain: @DiffWith resolves as Nested */ }
  public data object Tags : PersonStateField, ElementField<String> { /* elementOf */ }
  public data object ExternalIds : PersonStateField, ElementField<String> { /* map value type */ }
  public data object LastSeenAt : PersonStateField { /* @TrackIgnore, still tokenised */ }
  // version: no token, @DiffIgnore

  public companion object : FieldTokens<PersonStateField> { /* byName */ }
}
```

And the dispatch it makes possible, which is what the tutorial will show:

```kotlin
when (val field = change.fieldOf(PersonStateField)) {
    PersonStateField.Name -> person.rename(dto.name)
    PersonStateField.Nickname -> person.changeNickname(dto.nickname)

    PersonStateField.Addresses -> when (change) {
        is Added -> field.elementOf(change)?.let(person::addAddress)
        is Removed -> field.elementOf(change)?.let(person::removeAddress)
        is Moved -> field.keyOf(change)?.let { person.reorderAddress(it, change.from, change.to) }
        else -> field.keyOf(change)?.let { desired.addressBy(it) }?.let(person::editAddress)
    }

    PersonStateField.Contacts -> person.replaceContacts(dto.contacts)
    PersonStateField.Employment -> person.changeEmployment(dto.employment)
    PersonStateField.Salary -> person.adjustSalary(dto.salary)

    PersonStateField.Id,
    PersonStateField.Tags,
    PersonStateField.ExternalIds,
    PersonStateField.LastSeenAt,
    null -> Unit   // audited, no domain operation
}
```

Note what improved beyond the typing: `field` is smart-cast to `KeyedField<Address, AddressId>` inside
its branch, so `elementOf` and `keyOf` are available and typed with no cast anywhere. The audit case is
now an explicit list of the properties the domain has no opinion about, rather than an `else` that also
swallows a typo. And there is no `else` at all — adding a property to `PersonState` stops this
compiling.

### D11. What lives in the runtime versus what is generated

Runtime: `FieldToken`, `FieldTokens`, `ElementField`, `KeyedField`, `fieldOf`, `rootName`. Generated:
the token objects, their `propertyName`s, their typed accessors, and the `byName` table.

The split is the project's usual one — interfaces and behaviour in `kdiff-runtime` where a fix ships as
a dependency bump, per-type data generated. Every cast lives in generated code precisely because that
is the only place the element type is statically known, which is what lets the consumer have none.

## Risks / Trade-offs

- **`@Trackable` becomes overloaded**, meaning both "declare a tracking scope" and "generate tokens" →
  named in D2 with its cost and the rejected alternative. The two do travel together in practice.
- **Generated code grows for `@Trackable` types** — roughly three lines per property, plus more for
  each collection → gated so only types that want dispatch pay, and the tokens sit in the file that
  already exists.
- **An exhaustive `when` breaks *consumer* code when a model gains a property** → that is the feature,
  not a side effect. Worth stating in the tutorial so nobody is surprised: the compiler error is the
  point.
- **PascalCase collisions** → a diagnostic (D6) rather than a confusing error in generated code.
- **A nullable element type loses the difference between "null element" and "no element"** → D4, noted
  and accepted; no worked example depends on it.
- **`Resolution.kt` gaining type names touches the processor's core model** → additive only; existing
  fields keep their meaning and `emit`/`emitPatch` are untouched, so the existing generation specs are
  the regression net.
- **`kdiff-sample`'s `Order` is `@Trackable`**, so its generated file grows too and its specs must keep
  passing → a verification task, not an expected problem.

## Migration Plan

Additive. No existing generated declaration changes name, package or signature; a class that is not
`@Trackable` gets an identical file. Consumers adopt tokens by rewriting a dispatch, or ignore them
entirely and keep matching on `rootName()`, which is itself new but equivalent to what the tutorial did
by hand. Rollback is reverting the processor emission and the runtime interfaces.
