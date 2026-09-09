# kdiff documentation

Annotation-driven structural diff, patch and change tracking for Kotlin. Installation and a
five-minute overview are on the [project README](../README.md).

## Start here

| Guide | Covers |
|---|---|
| [**Tutorial**](tutorial.md) | A worked DDD application: an annotation-free domain, commands diffed into domain events, and the same model annotated |

## When you are working

| Guide | Covers |
|---|---|
| [**How do I…**](how-to.md) | Recipes named for the task — auditing, events, patching strictly, tracking one property, custom comparison. Every one lifted from a passing test |
| [**Errors**](errors.md) | Every message kdiff produces, quoted as it appears, with what to change |
| [**FAQ**](faq.md) | The questions whose answers are otherwise spread across several pages |

## Reference

| Guide | Covers |
|---|---|
| [Diffing](diffing.md) | `@Diffable`, the change vocabulary, paths, routing, collections, maps, sealed types |
| [Patching](patching.md) | Applying a diff, failures, the round-trip property |
| [Tracking](tracking.md) | `@Trackable`, trackers, scopes, depth, callbacks |
| [Hand-written differs and scopes](hand-written.md) | `differ { }` and `trackScope { }` for types you cannot annotate |
| [Annotation reference](annotations.md) | All seven annotations, their parameters, and what they reject |

## Why it is built this way

| Guide | Covers |
|---|---|
| [Architecture](architecture.md) | The module graph, the axis the API is shaped along, what kdiff costs, and [what it deliberately does not do](architecture.md#what-kdiff-does-not-do) |
| [API stability](api-stability.md) | What `1.0.0` will promise: what is recorded, what is closed, which types are data classes, and the questions answered "no" |

---

Contributing is described in [CONTRIBUTING.md](../CONTRIBUTING.md); the short version is that this
repository is spec-driven and `./gradlew check` is the definition of done.
