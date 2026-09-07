## Why

kdiff's behaviour is well covered, but its *surface* is not yet at the level the behaviour deserves:
a patch failure can only be inspected by matching on an English sentence, four nesting DSL builders
have no `@DslMarker` between them so an outer scope's members leak into an inner block, `Diff` holds a
list of changes but is not iterable, a cyclic object graph overflows the stack with no diagnostic, and
nothing in the build guards the published API or enforces a single formatting. Every one of these is
cheap to fix now and expensive to fix after `1.0.0`, which is exactly what pre-`1.0.0` is for.

## What Changes

### Typed error handling

- **BREAKING** — `PatchFailure.reason` becomes a sealed `PatchFailure.Reason` instead of a `String`.
  The twelve reasons the runtime reports today become twelve declared cases, each carrying what it
  knows (the property name, the key, the index), so a caller can branch on *why* a change did not
  apply instead of matching on prose. `PatchFailure.toString()` keeps rendering the same sentence, so
  logs are unchanged.
- `PatchResult.getOrThrow()` returns the value or raises the failures, for the caller who wants a
  patch to be all-or-nothing. `Patcher` itself keeps returning a partial result.
- **BREAKING** — the duplicate-`@DiffKey` refusal raises a declared
  `DuplicateDiffKeyException : IllegalArgumentException` carrying the property, the key property and
  the offending key value, rather than a bare `IllegalArgumentException`. Existing `catch` clauses on
  `IllegalArgumentException` still fire.
- A cyclic object graph raises a declared `CyclicStructureException` naming the path it re-entered,
  from both comparison and patching, instead of a `StackOverflowError`. Today this is documented as
  an unguarded sharp edge.
- The `differ { }` builder rejects at build time a shape the processor already rejects at compile time:
  a differ naming no property and no subtype, which reports every pair of instances as equivalent
  however much they differ. A hand-written definition then fails the same way an annotated one does.

  *(An earlier draft of this proposal claimed `field(property, depth)` skipped the depth validation
  that `TrackScopeBuilder.depth` performs. It does not — `TrackedField`'s own `init` checks it, and
  names the property while doing so. There was no inconsistency to fix, and none is claimed now.)*

### DSL safety and ergonomics

- A `@KdiffDsl` `@DslMarker` annotation on every builder receiver (`DifferBuilder`,
  `TrackScopeBuilder`, `TrackerBuilder`, `ChangeRoutes`, `ElementRoutes`, `KeyedElementRoutes`). A
  `route { }` nested inside `under { }` can currently call the outer routing's `on`, which registers a
  handler for a property of the wrong type against the wrong frame.
- `contract { callsInPlace(block, EXACTLY_ONCE) }` on `differ`, `trackScope`, `tracker`, `trackedDiff`
  and `Diff.route`, so a `val` can be initialised inside a builder block.
- **BREAKING** — `TrackScopeBuilder` and `TrackerBuilder` declare their identical
  `field`/`field(depth)`/`under`/`except`/`depth` members once, through a shared `ScopeDeclaration`
  interface with one implementation, rather than `TrackerBuilder` re-declaring all five by hand and
  forwarding each. Nothing about behaviour changes; what changes is that the two can no longer drift,
  since there is now one declaration and one implementation instead of two of each.

### `Diff` as a first-class value

- **BREAKING** — `Diff` implements `Iterable<Change>` and gains `size`, `isNotEmpty` and
  `Diff.plus(Diff)`. `isEmpty` becomes `isEmpty()` to match the stdlib shape.
- `Diff.at(property)` and `Diff.under(property)` filter by `KProperty1` rather than by string, the
  same naming-by-reference `route` established.
- `Diff.EMPTY` and a `Diff` factory over `vararg Change`, so a caller assembling changes need not
  build a list first.

### Build and toolchain gates

- `binary-compatibility-validator` with a checked-in `.api` dump per published module, so removing a
  public declaration fails `check` instead of being noticed by a consumer. The generated API is a
  published surface; nothing currently guards it.
- `detekt` and `ktlint`, plus an `.editorconfig` that states the formatting the sources already
  follow, wired into `check` and therefore into CI. detekt is pinned to `2.0.0-alpha.6`, deliberately:
  it is the release built against Kotlin 2.4.10, where stable `1.23.8` still embeds Kotlin 2.0.21.
- Dokka HTML for the three published modules, so the KDoc that is already written is readable outside
  an IDE.
- CI gains nothing new to run: `./gradlew check` stays the single command, and the new gates hang off
  it.

Nothing about annotation semantics changes. No annotation is added, removed or reinterpreted, and the
processor emits calls to the same runtime helpers under the same names — so an annotated class
compiles to the same generated code, and the generated API surface is unchanged apart from the
`Diff`/`PatchFailure` types that generated code returns.

## Capabilities

### New Capabilities

- `build-quality-gates`: what the build must refuse — a public declaration removed without an `.api`
  dump update, a source file that does not match the project's formatting, a detekt finding — and what
  it must produce (an API dump, Dokka HTML). Developer-facing behaviour of `./gradlew check`.

### Modified Capabilities

- `diff-generation`: `Diff` becomes an iterable, filterable, combinable value; the duplicate-key
  refusal and the new cyclic-structure refusal are declared exception types; the `differ { }` builder
  rejects a definition naming no property; DSL scoping is closed.
- `diff-application`: a patch failure carries a typed, inspectable reason instead of a sentence;
  `PatchResult` offers an all-or-nothing accessor; patching refuses a cyclic source the way comparison
  does.
- `change-tracking`: the hand-written and the tracker-side scope builders offer one identical,
  identically-validated set of members; depth validation applies wherever a depth is stated.

## Impact

- **`kdiff-runtime`** — every listed API change lands here. `Diff.kt`, `Patcher.kt`, `Patch.kt`,
  `Compare.kt`, `Dsl.kt`, `Track.kt`, `Route.kt`, and a new file for the exception types and the DSL
  marker.
- **`kdiff-processor`** — no change expected. Generated code constructs `Diff(List<Change>)` and
  `PatchResult(value, failures)`, names `PatchFailure` only as a type argument, and gets every reason
  string from runtime helpers — so none of the new signatures reach the emitters. Its compile-testing
  specs still run against the new runtime, which is what confirms this.
- **`kdiff-annotations`** — unchanged.
- **`kdiff-sample`, `kdiff-tutorial`, `kdiff-benchmarks`** — call sites adapt (`diff.isEmpty` →
  `diff.isEmpty()`, failure-reason assertions become case matches). The tutorial is the ergonomics
  test, so its diff naturally shows whether the new surface reads better.
- **Consumers** — must adapt to the breaking items above; all are mechanical and all are pre-`1.0.0`,
  where the README offers no compatibility guarantee.
- **Build** — three new Gradle plugins and an `.editorconfig`. No Kotlin, KSP, KotlinPoet, Gradle or
  Kotest version moves.
- **Docs** — `docs/architecture.md` loses "a genuine cycle overflows the stack" from *what kdiff does
  not do*; `docs/patching.md` documents the typed reasons; `docs/diffing.md` and
  `docs/hand-written.md` pick up the `Diff` and DSL changes; `CONTRIBUTING.md` documents the API dump
  and the formatting gate.
