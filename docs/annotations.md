# Annotation reference

Eight annotations, all in `io.github.kdiff.annotations`, all `BINARY` retention.

| Annotation | Target | Parameters | Purpose |
|---|---|---|---|
| `@Diffable` | class | — | opt a data class or sealed type into comparison and patching |
| `@DiffKey` | property | — | mark the property that identifies an element inside a collection |
| `@DiffIgnore` | property | — | exclude a property from comparison entirely |
| `@DiffWith` | property | `differ: KClass<*>` | compare this property with a hand-written differ |
| `@DiffAsValue` | class, property | — | compare the type, or one property, as a single value |
| `@Trackable` | class | `depth: Int = UNLIMITED_DEPTH` | declare a tracking scope over every compared property |
| `@TrackIgnore` | property | — | exclude a property from the declared tracking scope |
| `@TrackDepth` | property | `depth: Int` | give one property its own tracking depth |

Comparison and tracking follow the same shape: **the class opts in, properties opt out.** No property
opts its class into comparison or tracking; `@DiffAsValue` and `@DiffWith` say *how* a property
already being compared is compared.

## `@Diffable`

Accepted on data classes, and on sealed classes and sealed interfaces whose subclasses are all either
themselves `@Diffable` or declared as an `object`. Generates `object <Type>Differ` in the same package,
implementing `Differ<Type>` and `Patcher<Type>`.

Rejected on anything else — a plain class, an interface, an object, an enum class, an annotation
class — with an error naming the declaration and stating what `@Diffable` accepts. Also rejected on a
class with type parameters: a differ for a generic type would need a differ per type argument, which
cannot be resolved at the declaration.

**An `object` subclass of a `@Diffable` sealed type carries no annotation.** It is dispatched on
without one, because there is nothing an annotation could configure — a singleton has no property to
compare. `@Diffable` on it is an error, and the message says so. See
[a payload-free case](diffing.md#a-payload-free-case).

The four property annotations below are read **only** off a `@Diffable` class. On any other class each
is an error naming the property and the missing `@Diffable`, rather than an annotation that silently
configures nothing. `@DiffAsValue` on a *class* is the one exception: it is a statement about the type
itself, read by every `@Diffable` class that holds one.

## `@DiffKey`

Makes a list of that element type compare by key rather than by position, so a reordered element
reports as `Moved` and a changed one reports at its key. A type may declare **at most one**; two or
more is an error naming the type and the competing properties.

Combining it with `@DiffIgnore` is allowed, and useful: the key identifies the element, while
`@DiffIgnore` keeps it out of that element's own comparison — where it could only ever report nothing,
since two elements matched by key are equal in it.

## `@DiffIgnore`

The property is never compared and never contributes a change, however much it differs. It follows
that it is never reconstructed by a patch, and never tracked.

Honoured on a subclass that overrides the property — see
[an overridden property](#a-comparison-annotation-on-an-overridden-property).

## `@DiffWith`

The escape hatch for a property whose type cannot be annotated. `differ` must name an `object`
implementing `Differ` of that property's type — build one with `differ { }`, see
[hand-written.md](hand-written.md).

If the named object also implements `Patcher` of that type, the property is patchable. If it can only
compare, changes beneath it are reported as failures rather than applied, and the rest of the instance
still patches.

Not combinable with `@DiffIgnore`: an ignored property is never compared, so a differ named for it
could never run. That pair is an error.

Honoured on a subclass that overrides the property — see
[an overridden property](#a-comparison-annotation-on-an-overridden-property).

## `@DiffAsValue`

Compares as a single value, by equality, reporting one `ValueChanged` carrying both sides and nothing
beneath it. Honoured in two places, and the two mean different things:

**On a class**, every property of that type, every list element of that type and every map value of
that type is compared by equality — in every `@Diffable` class that reaches it, including classes in
other modules. This is the per-type declaration, and like `@Diffable` it can only be made by whoever
declares the type:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@DiffAsValue
data class Coordinates(val lat: Double, val lon: Double)
```

Honoured on any class kind you might reasonably compare whole: a small data class, a plain class with
its own `equals`, an interface whose implementations are all values. Not combinable with `@Diffable` —
one compares the type as a whole, the other property by property, and the pair is an error.

kdiff trusts the equality it is pointed at and never inspects it. On a class that inherits identity
equality, every independently built instance differs from every other, so such a property reports a
change on every diff — writing `equals` is the declaration's other half, and yours to make.

**On a property**, that property alone is compared by equality, whatever its type. It is the annotation
counterpart of the `differ { }` builder's `field`, so the two routes describe the same model:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@Diffable
data class Site(val name: String, @DiffAsValue val at: Address)
```

`Address` stays `@Diffable` and is still compared property by property everywhere else; here a change
inside it reports at `at` alone. A collection works the same way: `@DiffAsValue val tags: List<String>`
reports one change at `tags` rather than one per index, and is applied by setting the whole list.

Not combinable with `@DiffWith` (one says "by equality", the other names code) or with `@DiffIgnore`
(an ignored property is never compared). **And rejected wherever it would change nothing** — on an
enum, on a `value class`, or on a property whose type is already a value — because an annotation that
reads as configuration should be configuration. Every message is in
[errors.md](errors.md#diffasvalue-that-changes-nothing-or-contradicts-something).

Tracking is unaffected: a value has nothing beneath it, and depth already stops at one.

## A comparison annotation on an overridden property

`@DiffAsValue`, `@DiffIgnore` and `@DiffWith` are honoured on a property that **overrides** an
annotated one — typically a property a `@Diffable` sealed parent declares:

<!-- from: kdiff-sample/src/main/kotlin/demo/Model.kt -->
```kotlin
@Diffable
sealed interface Shipment {
    @DiffAsValue val origin: Address
}
```

Every subclass overriding `origin` compares it as one value, and so does the sealed parent's own differ
when the two instances are different subclasses. The two dispatch branches agree, which is the point:
Kotlin puts none of an overridden declaration's annotations on the `override`, so without this the
annotation would hold across a subclass swap and be ignored for two instances of one subclass — the
common case.

**An annotation on the override wins.** A subclass saying `@DiffIgnore override val origin: Address`
excludes the property for itself, whatever the parent declared. Only the nearest declaration carrying
one of the three is read, so its annotations are never merged with a parent's into a pair the compiler
would reject on a single declaration.

A plain `override` in the middle of a hierarchy does not cancel anything: the search continues past it
to the nearest annotated declaration above.

This covers only the three annotations that configure *how a property is compared*. `@DiffKey` is read
off a collection's element type rather than off the property holding it, and the tracking annotations
describe a scope belonging to the annotated class — neither has an overridden declaration to read.

## What counts as a value

A value is compared by its own `equals` and reported as one change carrying both sides. kdiff treats
these as values with no annotation at all:

- a primitive, an unsigned integer type, a `Char`, a `String`, or an enum;
- an inline `value class` — its equality is its single property's, which is what a diff compares by;
- these immutable, value-equal types from the JDK and the Kotlin standard library:
  `BigDecimal`, `BigInteger`, `UUID`, `Currency`, `Locale`, `URI`, the `java.time` value types
  (`Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
  `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`),
  `kotlin.time.Instant` and `kotlin.uuid.Uuid`;
- a type declared [`@DiffAsValue`](#diffasvalue).

The criterion for that third group, so it can be read rather than remembered: **immutable, equal by
value, from the JDK or the Kotlin standard library.** `java.util.Date` is not on it because it is
mutable, and comparing a mutable object by equality at one instant says nothing about it at another.
`File` and `Path` are not, because they name something rather than being a value. `kotlin.time.Duration`
needs no entry — it is a `value class`. `kotlin.uuid.Uuid` is still `@ExperimentalUuidApi` in the
current standard library, so declaring a property of that type is your own opt-in to make.

A value is a value in **every** position: as a property, as a list element, as a map value.

`BigDecimal` carries the one caveat worth stating: its `equals` is scale-sensitive, so
`BigDecimal("10")` and `BigDecimal("10.00")` report a change. kdiff compares by equality on purpose and
substitutes no other comparison for any type; for numeric equality, write it out with
[`@DiffWith`](#diffwith) — the how-to's
[*compare a value your own way*](how-to.md#compare-a-value-your-own-way) is that recipe.

## `@Trackable`

Declares the tracking scope for a class: every compared property, at `depth`. The generated differ
then also implements `Tracked<Type>` and exposes the scope, which a tracker uses when its caller names
no property.

`depth` counts **property steps** from the tracked object — a collection index or key is not a step.
The default, `UNLIMITED_DEPTH`, excludes nothing. Requires `@Diffable` on the same class.

Deciding what to *do* about a reported change needs no annotation: route the diff and name each
property by reference, see [diffing.md](diffing.md#deciding-what-a-change-means-route).

## `@TrackIgnore`

Excludes the property from its class's declared scope. The property is **still compared** — a diff
reports its changes exactly as before, and only tracking passes over it. Requires `@Trackable` on the
class.

## `@TrackDepth`

Sets the depth for one property, taking precedence over the class's `@Trackable` depth for that
property alone. Requires `@Trackable` on the class.

## `UNLIMITED_DEPTH`

The depth at which no change is excluded for lying too deep, and the default for `@Trackable`.

It is declared **twice** — in `io.github.kdiff.annotations` and again in `io.github.kdiff.runtime`.
An annotation default has to be a compile-time constant in the module declaring the annotation, and
`kdiff-runtime` depends on nothing but the Kotlin standard library, so neither module can reach the
other's copy. Import whichever your file already depends on.

## What is rejected at compile time

Every one of these is a `KSPLogger` error reported at the offending declaration, so an IDE and a build
log both point at the right line. Unsupported shapes are never silent fallbacks.

The table paraphrases what each message says. For the message text itself — searchable, as it appears
in your build log — see [errors.md](errors.md#1-compile-time), which is the complete list.

| Rejected | Error says |
|---|---|
| `@Diffable` on a non-data, non-sealed declaration | names it and what `@Diffable` accepts |
| `@Diffable` on a class with type parameters | names it, states type parameters are unsupported |
| `@Diffable` on a sealed type with an unannotated subclass | names both the parent and the subclass |
| `@Diffable` on an `object` | names it, and adds that an object in a sealed hierarchy needs no annotation |
| Two or more `@DiffKey` on one type | names the type and the competing properties |
| A property kdiff cannot compare, with no `@DiffWith` | names the property and its type, points at the escape hatch |
| A `List` or `Map` whose elements are nullable and `@Diffable` | names the property and the element type, and the two ways out |
| `@DiffKey`, `@DiffIgnore`, `@DiffWith` or `@DiffAsValue` on a class that is not `@Diffable` | names the property and the missing `@Diffable` |
| `@DiffWith` together with `@DiffIgnore` | names the property, states the two conflict |
| `@DiffWith` naming something that is not an `object` | names it, states an object is required |
| `@DiffWith` naming an object that differs the wrong type | states it does not implement `Differ` of that property's type |
| `@DiffAsValue` together with `@Diffable` on one class | names the type, states the two are opposite instructions |
| `@DiffAsValue` on an enum or a `value class` | names it, states it is already compared as a value |
| `@DiffAsValue` on a property whose type is already a value | names the property and the type, states the same |
| `@DiffAsValue` together with `@DiffWith` | names the property, states a property is compared one way |
| `@DiffAsValue` together with `@DiffIgnore` | names the property, states an ignored property is never compared |
| `@Trackable(depth = 0)`, or negative other than `UNLIMITED_DEPTH` | names the declaration and the accepted values |
| `@TrackDepth` with the same invalid depth | as above, reported at the property |
| `@Trackable` without `@Diffable` | names the class, states `@Trackable` requires `@Diffable` |
| `@TrackIgnore` or `@TrackDepth` on a class that is not `@Trackable` | names the property and the missing annotation |
| `@TrackIgnore` together with `@TrackDepth` | names the property, states the two conflict |
| `@TrackIgnore` or `@TrackDepth` on a `@DiffIgnore` property | states an ignored property can never be tracked |

The rows about an annotation on the wrong class, and about two that conflict, exist because the
alternative is an annotation that silently does nothing — the author believes they have configured
comparison or tracking and has not. A single rejected declaration fails the whole build, and the
failure is attributable to it rather than to a valid class alongside it.

Each row above corresponds to a requirement in `openspec/specs/diff-generation/spec.md` and a test in
`kdiff-processor`'s `DiagnosticSpec`.

## Where to go next

- [Errors](errors.md) — the message text behind every rejection tabulated above
- [Diffing](diffing.md) — what `@Diffable` generates and how each shape is compared
- [Tracking](tracking.md) — what `@Trackable` declares, and the depth these annotations carry
- [Hand-written differs and scopes](hand-written.md) — the route for a type you cannot annotate at all
- [Patching](patching.md) — why a compare-only `@DiffWith` object leaves its property unpatchable
- [Architecture](architecture.md) — why an unsupported shape is a compile error rather than a fallback
