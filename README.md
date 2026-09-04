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

> **GitHub Packages requires authentication for every consumer**, including for public packages.
> Without a token you will get a `401` that does not obviously mean "add a token". You need a
> personal access token with the `read:packages` scope.

Add the repository, reading credentials from the environment or `~/.gradle/gradle.properties` rather
than inlining them:

<!-- illustrative -->
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/rcapraro/kdiff")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Then apply KSP and declare the dependencies:

<!-- illustrative -->
```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.10"
    id("com.google.devtools.ksp") version "2.3.11"
}

dependencies {
    implementation("io.github.kdiff:kdiff-annotations:0.1.0")
    implementation("io.github.kdiff:kdiff-runtime:0.1.0")
    ksp("io.github.kdiff:kdiff-processor:0.1.0")
}
```

The processor goes on the `ksp` configuration, never `implementation`. That is what keeps it off your
runtime classpath — it runs inside the compiler and has no business being shipped.

Requires JDK 17 or later; the library is built against a JVM 21 toolchain.

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
```

And with `@Trackable`, a lambda fires as a value evolves:

<!-- illustrative -->
```kotlin
val tracker = tracker(AddressDiffer, before) {
    onFieldChange { path, from, to -> println("$path: $from -> $to") }
}

tracker.update(after)
// city: Paris -> Nice
```

Deciding what a change *means* is a routing, with every property named by reference:

<!-- illustrative -->
```kotlin
diff.route<Address> {
    on(Address::city) { relocate(after.city) }
    otherwise { audit(Diff(it)) }
}
```

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

| Guide | Covers |
|---|---|
| [**Tutorial**](docs/tutorial.md) | A worked DDD application: an annotation-free domain, commands diffed into domain events, and the same model annotated |
| [Diffing](docs/diffing.md) | `@Diffable`, the change vocabulary, paths, routing, collections, maps, sealed types |
| [Patching](docs/patching.md) | Applying a diff, failures, the round-trip property |
| [Tracking](docs/tracking.md) | `@Trackable`, trackers, scopes, depth, callbacks |
| [Hand-written differs and scopes](docs/hand-written.md) | `differ { }` and `trackScope { }` for types you cannot annotate |
| [Annotation reference](docs/annotations.md) | All seven annotations, their parameters, and what they reject |
| [Architecture](docs/architecture.md) | The module graph and why the API is shaped the way it is |

## Status

`0.1.0`, unreleased. The API is documented and tested but has not been published, and no
compatibility guarantee is offered yet.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: this repository is spec-driven, and
`./gradlew check` is the definition of done.

## License

[MIT](LICENSE)
