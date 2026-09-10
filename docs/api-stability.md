# API stability

What `1.0.0` will promise, what it will not, and which questions were answered "no" and why. Written
before the tag rather than after it, so that adopting kdiff before `1.0.0` is a decision made with the
same information a reader will have afterwards.

## 1. What is recorded

The public API of the three published modules — `kdiff-annotations`, `kdiff-runtime` and
`kdiff-processor` — is recorded as a dump in each module's `api/` directory, and `./gradlew check`
fails when the code and the dump disagree. Adding or removing a public declaration therefore cannot
happen quietly: the dump moves in the same commit, and that diff is the review surface for the change.

A change that **removes or alters** an entry in one of those dumps is breaking. Before `1.0.0` a minor
version may make one, with a migration note in [the changelog](../CHANGELOG.md); after `1.0.0` only a
major version may. Additions are not breaking, with the two exceptions in §2.

## 2. What is closed

Two vocabularies are sealed *and closed*: callers handle them exhaustively, so adding a case breaks
every exhaustive `when` in every consumer. Adding one is breaking, on the same terms as a removal.

| Vocabulary | Cases | Where |
|---|---|---|
| `Change` | `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved` | [diffing.md](diffing.md#the-change-vocabulary-is-closed) |
| `PatchFailure.Reason` | fourteen | [errors.md](errors.md#3-changes-that-did-not-apply) |

The refusals are declared types rather than a message to match on:

| Type | Extends | Raised when |
|---|---|---|
| `DuplicateDiffKeyException` | `IllegalArgumentException` | two elements of a keyed list share one key |
| `CyclicStructureException` | `IllegalArgumentException` | a structure is deeper than `MAX_DESCENT` |
| `PatchFailedException` | `IllegalStateException` | `PatchResult.getOrThrow()` is asked for a value it has not got |

There is deliberately **no common supertype**. A marker interface cannot be caught, and a shared class
would have to root at one of those two stdlib types and misfile the other. The first two extend
`IllegalArgumentException` because that is what the runtime raised before they were declared, so a
`catch` written against it keeps working.

## 3. What is a data class

A data class fixes a type's shape: `copy` and `componentN` are public API, so a property cannot be
added without breaking them. kdiff keeps `data` exactly where that cost buys something, by one
criterion:

> **A type stays a data class when its properties are its whole meaning, destructuring them is a use,
> and the set will not grow.**

| Type | Data class? | Why |
|---|---|---|
| `ValueChanged`, `Added`, `Removed`, `TypeChanged`, `Moved` | yes | a closed vocabulary; `(path, before, after)` destructuring is idiomatic, and the fields cannot grow without a new variant, which §2 already makes breaking |
| `Segment.Field`, `Segment.Index`, `Segment.Key` | yes | the same |
| `PatchResult` | yes | `val (value, failures) = OrderDiffer.apply(…)` is the natural read |
| `PatchFailure` | yes | `(change, reason)` |
| `DiffNode` | yes | `(segment, changes, children)`; a tree node's shape is fixed |
| `TrackedField` | yes | `(name, depth)` |
| `Diff` | **no** | a container with behaviour. `copy(changes = …)` is the constructor spelled longer, nothing destructures a diff, and both would fix `Diff`'s shape forever |

`Diff` keeps its constructor, its `changes`, and equality and hash code over those changes. Its
`toString()` is its [rendering](diffing.md#viewing-a-diff).

## 4. What generated code promises

`@Diffable` on a type generates, in that type's own package, exactly this:

<!-- illustrative -->
```kotlin
public object <Type>Differ : Differ<Type>, Patcher<Type>          // and Tracked<Type> with @Trackable
```

That declaration — its name, its package, the interfaces it implements and their members — is the
promise. **Nothing else in the generated file is API**: not its private members, not the local variable
names inside `apply`, not which runtime helpers it calls, and not the layout of the emitted text. Those
change whenever a fix or an optimisation calls for it, and a consumer that reads the generated source
rather than the interfaces is reading an implementation.

The helpers themselves are public, because generated code has to name them, and they are the same ones
a [hand-written differ](hand-written.md#when-the-dsl-is-not-enough) composes. They are recorded in the
dump like anything else.

## 5. What is not API

- **Message text.** Every diagnostic and every failure sentence is quoted in [errors.md](errors.md) so
  it can be recognised, and any of them may be reworded. Branch on a `PatchFailure.Reason` case or an
  exception type, never on a string.
- **`render()` and `tree()` output layout.** Column alignment, padding and the wording of `ADDED`,
  `REMOVED`, `MOVED` and `TYPE` are for a human reader.
- **`toString()` layout**, on any type — `Diff` included, whose `toString` is `render()` and inherits
  that freedom.
- **The order of failures within one `apply`.** Which changes failed is API; the sequence they are
  reported in is not.

## 6. Platform

Kotlin/JVM only, built and published against a JVM 21 toolchain. No reflection library: property
references are stdlib, so `kotlin-reflect` is not a dependency and is not required at runtime.
`kdiff-annotations` and `kdiff-runtime` each depend on no kdiff module and no third-party library,
carrying nothing but the Kotlin standard library; `kdiff-processor` is compile-time only, applied
through the `ksp` configuration, and is never on a consumer's runtime classpath.

## 7. Decided, and not done

Three questions that get asked, with the answer and the reason, so that the next person to ask finds
one.

**A typed `Diff<T>`.** Declined. A type argument would make `plus` across two diffs of different types
awkward, would leave `EMPTY` needing a type it does not have, and would fight the lifting of a nested
differ's changes into its parent — which is exactly a diff of one type becoming part of a diff of
another. Where dispatch needs a type, [`Diff.route<T> { }`](diffing.md#deciding-what-a-change-means-route)
already provides one and checks every property it names against it.

**`TypeChanged` carrying `KClass` sides rather than type names.** Declined. The change already carries
both instances, so `before::class` is one expression away, and a name is what survives being logged,
serialised or compared across a process boundary.

**A common `KdiffException` supertype.** Declined, per §2: a marker interface cannot be caught, and the
two roots are different stdlib types on purpose.

## Where to go next

- [Errors](errors.md) — every message, quoted, with what to change
- [Architecture](architecture.md) — the module graph and [what kdiff does not do](architecture.md#what-kdiff-does-not-do)
- [Changelog](../CHANGELOG.md) — the only description of what a version changed
