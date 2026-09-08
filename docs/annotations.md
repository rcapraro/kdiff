# Annotation reference

Seven annotations, all in `io.github.kdiff.annotations`, all `BINARY` retention.

| Annotation | Target | Parameters | Purpose |
|---|---|---|---|
| `@Diffable` | class | — | opt a data class or sealed type into comparison and patching |
| `@DiffKey` | property | — | mark the property that identifies an element inside a collection |
| `@DiffIgnore` | property | — | exclude a property from comparison entirely |
| `@DiffWith` | property | `differ: KClass<*>` | compare this property with a hand-written differ |
| `@Trackable` | class | `depth: Int = UNLIMITED_DEPTH` | declare a tracking scope over every compared property |
| `@TrackIgnore` | property | — | exclude a property from the declared tracking scope |
| `@TrackDepth` | property | `depth: Int` | give one property its own tracking depth |

Comparison and tracking follow the same shape: **the class opts in, properties opt out.** There is no
property-level way to opt into either.

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

All three annotations below are read **only** off a `@Diffable` class. On any other class each is an
error naming the property and the missing `@Diffable`, rather than an annotation that silently
configures nothing.

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

## `@DiffWith`

The escape hatch for a property whose type cannot be annotated. `differ` must name an `object`
implementing `Differ` of that property's type — build one with `differ { }`, see
[hand-written.md](hand-written.md).

If the named object also implements `Patcher` of that type, the property is patchable. If it can only
compare, changes beneath it are reported as failures rather than applied, and the rest of the instance
still patches.

Not combinable with `@DiffIgnore`: an ignored property is never compared, so a differ named for it
could never run. That pair is an error.

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
| `@DiffKey`, `@DiffIgnore` or `@DiffWith` on a class that is not `@Diffable` | names the property and the missing `@Diffable` |
| `@DiffWith` together with `@DiffIgnore` | names the property, states the two conflict |
| `@DiffWith` naming something that is not an `object` | names it, states an object is required |
| `@DiffWith` naming an object that differs the wrong type | states it does not implement `Differ` of that property's type |
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
