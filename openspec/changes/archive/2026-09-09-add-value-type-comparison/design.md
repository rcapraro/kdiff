## Context

See `proposal.md` — *Why*. Three facts about the current code shape the approach.

**What counts as a value is one function over one set.** `KSType.isValueType()` in `Resolution.kt`
returns true for a declaration whose qualified name is in `VALUE_TYPES` — thirteen Kotlin names — or
whose class kind is an enum. `resolve()`, `resolveList()` and `resolveMap()` all consult it, so widening
it widens every position a value can occupy: property, list element, map value.

**Value comparison is already the simplest path through the runtime.** A value property emits
`compareValue(name, before.x, after.x)` and `patchValue(before.x, changes)`. Neither knows the type.
Nothing in `kdiff-runtime` has to change for a `BigDecimal` to travel through them.

**The comparison annotations are resolved in `resolve()`, in a stated order.** `@DiffWith` is consulted
first, then the value test, then `@Diffable`, then the three collection shapes. A new annotation slots
into that order and inherits the diagnostics discipline around it.

## Goals / Non-Goals

**Goals**

- A value is a value in every position. Whatever `isValueType()` says about a type, it says the same
  for a property, a list element and a map value.
- The three routes compose by one rule, so a reader can predict what a property does from its
  declaration alone: the property's own annotation wins, then the type's declaration, then the built-in
  set.
- Generated code for every class that compiles today is byte-identical.
- A `@DiffAsValue` that changes nothing is a compile error, so the annotation never lies about being in
  effect.

**Non-Goals** (beyond the proposal's exclusions)

- Reading a type's `equals` to decide anything. The library trusts equality where it has been told to
  and nowhere else.
- Making `@DiffAsValue` reach a type through a `@DiffWith` differ. A hand-written differ decides its
  own comparisons.

## Decisions

### D1 — Three routes, not one

Any one of the three would leave a common case awkward. The built-in set alone leaves the author's own
`Money` on `@DiffWith`. `value class` alone leaves `BigDecimal` on `@DiffWith`. `@DiffAsValue` alone
would ask the author to annotate a property for every `Instant` in the model, or to write a `@Diffable`
mirror of `Instant`, which cannot be done. Together they cover the standard library, the author's
Kotlin-idiomatic wrappers, and the author's own multi-field values, each with the cheapest declaration
that fits.

The three compose by precedence, resolved once per property in `resolve()`:

```
  property carries @DiffWith ..................... hand-written differ   (unchanged, first)
  property carries @DiffAsValue .................. value
  type is a value:
      built-in set, enum, value class, or
      the type declares @DiffAsValue ............. value
  type is @Diffable .............................. nested
  List / Set / Map ............................... collection, elements resolved by the same test
  otherwise ...................................... "cannot compare" diagnostic
```

`@DiffWith` staying first is deliberate: a hand-written differ is the most specific statement an author
can make, and a property-level `@DiffAsValue` beside it is a conflict rather than a tie (D5).

### D2 — The built-in set and its criterion

`VALUE_TYPES` gains the qualified names listed in the proposal. The criterion is written into
`annotations.md` so a reader can predict membership rather than look it up: **immutable, equal by
value, from the JDK or the Kotlin standard library**. Every `java.time` value type qualifies. `Date`
does not — it is mutable, and comparing a mutable object by equality at one instant says nothing about
it at another. `File` and `Path` do not — they name something rather than being a value.

`BigDecimal` qualifies and carries the one caveat worth stating: its `equals` is scale-sensitive, so
`BigDecimal("10")` and `BigDecimal("10.00")` report a change. That is a fact about `BigDecimal`, and
kdiff compares by equality on purpose; an author who wants numeric equality has `@DiffWith`, and the
existing how-to recipe *Compare a value your own way* is that case exactly. The alternative — special
casing `BigDecimal` to `compareTo` — would make one type compare by a rule no other type follows, and
would report a change whose two sides are `equals` to each other, which downstream code has no reason
to expect.

`kotlin.time.Instant` and `kotlin.uuid.Uuid` are named strings in the set. If either is not yet stable
in the pinned standard library, its name matches nothing and costs nothing; the task list has the
implementer confirm which is the case and document only what is present.

### D3 — A `value class` is detected by its modifier

`isValueType()` returns true when the declaration carries `Modifier.VALUE`. No allowlist entry, no
annotation: the declaration already says "this is a value". The property is compared by equality
through `compareValue`, whose `Any?` parameters box the inline class exactly as `==` on it would.

A `value class` in a list is a positional list of values, and as a map value it is a value — the same
`isValueType()` path the built-in set takes.

### D4 — `@DiffAsValue`, with two targets

```kotlin
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class DiffAsValue
```

The name reads as an instruction — *diff this as a value* — and sits beside `@DiffKey`, `@DiffIgnore`
and `@DiffWith`, each of which names what it does to a comparison. `@DiffValue` reads as a noun and
`@DiffByEquality` describes the mechanism rather than the intent.

**On a class**, `isValueType()` returns true for it, so it is a value in every position by the same
test the built-in set passes. This is the per-type declaration, and it is bounded the way `@Diffable`
is: only a type the author owns can carry it. Honoured on any class kind the author might reasonably
compare as a whole — a data class of two or three fields, a plain class with its own `equals`, an
interface whose implementations are all values.

**On a property**, `resolve()` returns `Comparison.ByValue` for that property whatever its type. The
type may be `@Diffable`, a collection, or anything else; the property is one value. This is the
counterpart of the DSL's `field`, and it is what makes an opaque comparison of a collection possible
on the annotation route: `@DiffAsValue val tags: List<String>` reports one `ValueChanged` at `tags`.

One `@DiffAsValue` cannot mean two things. A class-level one changes the type's classification; a
property-level one overrides classification for one property. Both end at `Comparison.ByValue`, and
`emit` and `emitPatch` do not know which route produced it.

Tracking is unaffected. A value property has nothing beneath it, and depth already stops at a value.
`@TrackDepth(2)` on a `@DiffAsValue` property is not an error: depth is a bound, and a bound nothing
reaches is still a valid bound.

### D5 — Every `@DiffAsValue` that changes nothing, or contradicts something, is a diagnostic

Reported at the annotated declaration, all from `resolve()` or from `isSupported()`'s neighbour that
checks class-level annotations:

| shape | message |
|---|---|
| `@DiffAsValue` and `@Diffable` on one class | `@DiffAsValue on <Type> conflicts with @Diffable; one compares the type as a single value and the other property by property` |
| `@DiffAsValue` on an enum, a `value class`, or a listed type's own declaration | `@DiffAsValue on <Type> has no effect; <Type> is already compared as a value` |
| `@DiffAsValue` on a property whose type is already a value | `@DiffAsValue on <prop> has no effect; <T> is already compared as a value` |
| `@DiffAsValue` and `@DiffWith` on one property | `@DiffAsValue on <prop> conflicts with @DiffWith; a property is compared one way` |
| `@DiffAsValue` and `@DiffIgnore` on one property | `@DiffAsValue on <prop> conflicts with @DiffIgnore; an ignored property is never compared` |
| `@DiffAsValue` on a property of a class that is not `@Diffable` | `@DiffAsValue on <prop> requires @Diffable on <Type>; without a generated differ it has no effect` |

The last row is the template `close-generation-gaps` introduces for the other three comparison
annotations; this change adds `DIFF_AS_VALUE` to that loop, or introduces the loop for this one
annotation if it lands first. `@DiffKey` on a `@DiffAsValue` class is already covered: the class is not
`@Diffable`, so the key is rejected by that same rule.

The "already a value" rejection is the one an author might find strict. It is kept because the
alternative is an annotation that reads as configuration and is not: a reader of `@DiffAsValue val
total: BigDecimal` would reasonably conclude that removing it changes the comparison.

### D6 — The "cannot compare" message names the cheapest fix first

> `kdiff cannot compare <prop> of type <T>; annotate its type with @Diffable, mark the property
> @DiffAsValue to compare it by equality, or point the property at a hand-written differ with @DiffWith`

and for elements:

> `kdiff cannot compare elements of <prop> of type <T>; annotate that type with @Diffable or
> @DiffAsValue, or point the property at a hand-written differ with @DiffWith`

The element form names the type-level annotation and not the property-level one, because
`@DiffAsValue` on a collection property compares the whole collection, which is not what an author who
wants to compare its elements is asking for.

### D7 — Nothing generated changes shape

A property admitted by any of the three routes emits exactly what a `String` property emits:

```kotlin
compareValue("total", before.total, after.total)
val totalPatched = patchValue(before.total, grouped.forProperty("total"))
```

So type resolution is the only processor surface touched; incremental processing is unaffected, because
a class-level `@DiffAsValue` is read off the *referenced* type's declaration exactly as `@Diffable` is
today — and that declaration's file is already added to the originating set when it is `@Diffable`.
The same must hold here: a class-level `@DiffAsValue` type's containing file joins the originating set
of every generated file that reads it, so removing the annotation regenerates the classes that depended
on it. `Comparison.ByValue` becomes a data class carrying `sources` for that case, or a sibling
`Comparison.ByDeclaredValue(sources)` is added; the implementer picks whichever keeps `emit` a single
branch.

### D8 — The sample and the tutorial

`kdiff-sample`'s `Order` gains `val discount: BigDecimal`, `val placedAt: Instant`, a
`@JvmInline value class Sku(val code: String)` property, and `@DiffAsValue data class
Coordinates(val lat: Double, val lon: Double)` as a `location` property, so the integration test walks
each route and `RecipesSpec` can back a how-to recipe for each.

`kdiff-tutorial`'s `Person.kt` comment — "Identifiers are single-property data classes rather than
inline value classes: a `value class` cannot be a data class, and equality is what a diff compares by"
— and the matching sentence in `tutorial.md` are revised to give the reason that survives this change:
the identifiers stay data classes because the tutorial renders them in paths and overrides `toString`
to do so, not because a value class could not be compared. The tutorial's model does not otherwise
change, so `AnnotatedParitySpec` is untouched.

## Risks / Trade-offs

**A type on the built-in list has an `equals` a consumer did not expect** → the list is short, every
member is a documented JDK or stdlib value type, and the criterion is written down. `BigDecimal` is the
one with a surprise, and it is stated in three places (`annotations.md`, `diffing.md`, the FAQ).

**A class-level `@DiffAsValue` in one module silently changes comparisons in another** → it does, in
every `@Diffable` class that holds the type, which is what a type-level declaration is for. The
originating-file rule in D7 ensures those classes regenerate when the declaration changes, so the
change is never stale; and the type's author is the only one who can make it.

**"Already a value" rejections annoy an author who annotates defensively** → the message says exactly
why and the fix is deletion. Consistent with every other no-effect rejection the processor makes.

**`compareValue` boxes a `value class`** → one allocation per compared inline property, the same
allocation `==` on `Any?` would make. Not on any benchmarked hot path; no claim about cost is made in
the docs.

**Widening what compiles hides a type the author meant to annotate `@Diffable`** → only for a type that
is now a value, and the list is of types nobody could annotate anyway. A data class the author forgot
to annotate is rejected exactly as before.

## Migration Plan

A library version bump. A consumer with a `@DiffWith` object that only does what equality does may
delete it and the annotation; nothing forces them to. The `CHANGELOG.md` entry lists the newly accepted
types so a reader can decide.

## Documentation

- `docs/annotations.md` — a `@DiffAsValue` section after `@DiffWith`; a *What counts as a value* list
  with the criterion; the rejection table gains six rows.
- `docs/diffing.md` — *Values, enums and nullables* opens with the widened definition and carries the
  `BigDecimal` caveat.
- `docs/architecture.md` — the bullet *Custom comparison is per property, not per type* becomes
  *A hand-written comparison is per property; comparing a type as one value is per type*, stating both
  halves and why they differ (a differ is code that cannot be attached to a type one does not own; a
  value declaration is one bit and can).
- `docs/faq.md` — *Can I compare every `BigDecimal` in my model by `compareTo`?* is answered in two
  parts: by equality, yes, without doing anything; by `compareTo`, still per property with `@DiffWith`.
- `docs/errors.md` — the five D5 messages and the two D6 amendments, verbatim; the tier-1 count.
- `docs/how-to.md` — one recipe, *Compare a type as a single value*, backed by a `RecipesSpec` test.
- `docs/hand-written.md` — the builder table's `field` row gains "the counterpart of `@DiffAsValue`".
- `docs/tutorial.md` and `Person.kt` — the revised identifier sentence from D8.
- `CHANGELOG.md` under `[Unreleased]` — *Added*, listing the three routes and the accepted types.
