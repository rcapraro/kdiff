<div align="center">

<img src="assets/logo.svg" alt="" width="120" height="120">

# kdiff

**Annotation-driven structural diff, patch and change tracking for Kotlin**

[![CI](https://github.com/rcapraro/kdiff/actions/workflows/ci.yml/badge.svg)](https://github.com/rcapraro/kdiff/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-blue.svg?logo=kotlin)](https://kotlinlang.org)

</div>

---

Annotate a data class with `@Diffable` and a KSP processor generates, at compile time, a differ that
compares two instances property by property, applies a diff back to rebuild an instance, and — with
`@Trackable` — reports changes to a lambda as a value evolves.

No reflection. No runtime cost beyond the generated code. Generated Kotlin you would be happy to
read.

## Why

Comparing two objects in Kotlin usually means `equals`, which tells you only *whether* they differ.
Getting *what* differs, at a path you can act on, normally costs either reflection at runtime or a
hand-written comparison per type that rots the moment someone adds a property.

kdiff generates that comparison from the declaration, so it cannot fall behind the type. The result
is a flat, ordered list of typed changes, each carrying a path from the root:

```
reference                 "R-1" -> "R-2"
billing.city              "Paris" -> "Nice"
addresses[id=A2].street   "2 Rue Y" -> "9 Rue Q"
addresses[id=A3]          ADDED Address(id=A3, ...)
payment                   TYPE Card -> Transfer
```

Those changes are values. You can log them, render them, filter them by path, feed them back through
`apply` to rebuild the target, or hand a subset to a tracker so a lambda fires only for the fields
you care about.

## Install

Published to Maven Central, so the repository every Gradle build already declares is all you need.
Apply KSP and declare the dependencies:

<!-- illustrative -->
```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.11"
}

dependencies {
    implementation("io.github.rcapraro:kdiff-annotations:0.7.0")
    implementation("io.github.rcapraro:kdiff-runtime:0.7.0")
    ksp("io.github.rcapraro:kdiff-processor:0.7.0")
}
```

Imports are `io.github.kdiff.*`: Central verifies group ids, not package names, and the packages carry
the library's name.

The processor goes on the `ksp` configuration, never `implementation`. That is what keeps it off your
runtime classpath — it runs inside the compiler and has no business being shipped.

Requires JDK 21 or later, and a JVM target of 21: the library is built against a JVM 21 toolchain, and
its builder entry points are `inline` so they can state that they run your block exactly once — which
Kotlin will not inline into a module compiling for an older target.

## Quickstart

Annotate a data class:

<!-- from: kdiff-sample/src/main/kotlin/demo/Address.kt -->
```kotlin
@Diffable
data class Address(@DiffKey val id: String, val street: String, val city: String)
```

Compile, and `AddressDiffer` exists in the same package. Nothing to register:

<!-- illustrative -->
```kotlin
val before = Address("A1", "1 Rue X", "Paris")
val after = Address("A1", "1 Rue X", "Nice")

val diff = AddressDiffer.diff(before, after)

println(diff.render())
// city  "Paris" -> "Nice"

diff.changes.single()
// ValueChanged(path=city, before=Paris, after=Nice)
```

The same object applies a diff back, rebuilding the target from the source:

<!-- illustrative -->
```kotlin
val result = AddressDiffer.apply(before, diff.changes)

result.value      // Address(id=A1, street=1 Rue X, city=Nice)
result.isClean    // true — every change applied
result.getOrThrow()  // or the value outright, raising if anything failed
```

A tracker fires a lambda as a value evolves, holding the last instance it saw and comparing the next
one against it. Naming no property tracks every property compared, and `@Trackable` on the type is how
you narrow that once rather than at each call site:

<!-- illustrative -->
```kotlin
val tracker = tracker(AddressDiffer, before) {
    onFieldChange { path, from, to -> println("$path: $from -> $to") }
}

tracker.update(after)
// city: Paris -> Nice
```

Deciding what a change *means* is a routing, with every property named by reference — so a typo is a
compile error rather than a string that matches nothing for ever. A value object is *framed* with
`under`, and frames nest as deep as the model does:

<!-- illustrative -->
```kotlin
diff.route<Order> {
    on(Order::reference) { changes -> audit(changes) }

    under(Order::billing) {
        on(Address::city) { relocate(order.billing.city) }
    }

    onEach(Order::addresses, Address::id) {
        added { address -> register(address) }
        moved { id, from, to -> reorder(id, from, to) }
    }

    otherwise { unhandled -> audit(Diff(unhandled)) }
}
```

A handler runs once per property however many changes lie under it, elements and keys arrive at their
own types, and whatever no handler names at any depth reaches the single `otherwise`.

Not your type to annotate? Describe it in ordinary Kotlin instead — same comparisons, same paths, and
everything above works unchanged:

<!-- illustrative -->
```kotlin
val AddressDiffer = differ<Address> {
    field(Address::street)
    field(Address::city)
}
```

## Documentation

The full documentation is in **[docs/](docs/README.md)**. Three routes in:

- **Learning it** — [the tutorial](docs/tutorial.md) builds a DDD application: an annotation-free
  domain, commands diffed into domain events, and the same model annotated.
- **Using it** — [how do I…](docs/how-to.md) for recipes, [errors](docs/errors.md) for any message
  kdiff produces, [the FAQ](docs/faq.md) for the rest.
- **Looking something up** — [diffing](docs/diffing.md), [patching](docs/patching.md),
  [tracking](docs/tracking.md), [hand-written differs](docs/hand-written.md),
  [annotations](docs/annotations.md), and [architecture](docs/architecture.md) for why it is shaped
  this way.

## Status

Released and published to Maven Central, with sources and documentation jars. The
[changelog](CHANGELOG.md) says what each version changed and the
[releases](https://github.com/rcapraro/kdiff/releases) page carries the same notes — between them they
are the only description of a version, so this page does not repeat one.

No compatibility guarantee is offered before `1.0.0`.
[API stability](docs/api-stability.md) says what `1.0.0` will promise — what is recorded, what is
closed, and which questions were answered "no" — and
[what kdiff does not do](docs/architecture.md#what-kdiff-does-not-do) is worth reading before adopting
it.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: this repository is spec-driven, and
`./gradlew check` is the definition of done.

## License

[MIT](LICENSE)
