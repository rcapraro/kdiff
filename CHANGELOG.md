# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before `1.0.0` a minor version may
carry a breaking change; each one is called out under **Changed** with the migration.

The section for a version is what the release notes for its tag are built from, so keep a version's
entry written for someone deciding whether to upgrade.

## [0.7.0] - 2026-09-09

Two things a stranger meets before the first line of Kotlin: the coordinates, and how much annotating
a domain model costs. This release settles both — kdiff resolves from Maven Central with no repository
block and no token, with sources and KDoc reaching the IDE, and the value types a domain model is full
of now compare by equality with nothing declared on them.

It also writes down what `1.0.0` will promise, in [`docs/api-stability.md`](docs/api-stability.md),
before the tag rather than after it.

### Added

- **The types a domain model is full of are values now, with no annotation.** `BigDecimal`,
  `BigInteger`, `UUID`, `Currency`, `Locale`, `URI`, every `java.time` value type (`Instant`,
  `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`,
  `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`), `kotlin.time.Instant`
  and `kotlin.uuid.Uuid` are compared by equality wherever they appear — as a property, as a list
  element, as a map value. The criterion is written down so the list can be read rather than
  remembered: immutable, equal by value, from the JDK or the Kotlin standard library. `java.util.Date`
  is deliberately not on it.

  Each of these previously cost an `object` implementing `Differ` plus a `@DiffWith` on every property
  holding one, to express the one comparison kdiff already knew how to do. If you wrote such a differ,
  you may delete it and the annotation; nothing forces you to.

  One caveat in writing: `BigDecimal`'s `equals` is scale-sensitive, so `10` and `10.00` report a
  change. A numeric comparison is still a `@DiffWith` differ's business.

- **An inline `value class` is a value.** A class declared `value class` is compared by equality
  wherever it appears, with no annotation on it or on the property. `kotlin.time.Duration` is one, so
  it needs no place on the list above.

- **`@DiffAsValue` declares that a type, or one property, compares as a single value.** On a class,
  every property, list element and map value of that type is compared by equality, in every
  `@Diffable` class that reaches it — the per-type declaration the FAQ used to say did not exist. On a
  property, that property alone is compared as one value whatever its type: `@DiffAsValue val billing:
  Address` reports one change at `billing`, and `@DiffAsValue val tags: List<String>` one change at
  `tags`. It is the annotation counterpart of the `differ { }` builder's `field`, so the annotated and
  hand-written routes can once again describe the same model.

  A `@DiffAsValue` that could change nothing is a compile error — on an enum, a `value class`, or a
  property whose type is already a value — and so is one that contradicts another declaration: beside
  `@Diffable` on a class, or beside `@DiffWith` or `@DiffIgnore` on a property.

- The "cannot compare" diagnostic now names three ways out instead of two: `@Diffable` on the type,
  `@DiffAsValue` on the property, or `@DiffWith`.

- **`Differ` and `Patcher` are functional interfaces.** A one-off differ can be written where it is
  used — `Differ<Money> { before, after -> … }` — and so can a patcher. Source- and binary-compatible;
  the recorded API does not move. The descent-bound caveat an `object : Differ<T>` already carried
  applies to the lambda form too, and is now stated for both.

- **`Segment.Key.MAP_ENTRY` is the declared name a map entry's key segment carries**, replacing the
  `"key"` literal in the runtime. Its value is unchanged, so every rendered `amounts[key=eur]` and
  every assertion on one reads as before — but a hand-written differ or patcher building a map path can
  now name the constant, and a reader of that path can find where `key` comes from.

- **[`docs/api-stability.md`](docs/api-stability.md)** states what `1.0.0` will promise: the recorded
  public API of the three published modules, the two closed vocabularies and the three declared
  exception types, which types are data classes and by what criterion, what generated code guarantees
  and what in a generated file is not API, what is explicitly not API at all, and the JVM platform. It
  also records the questions answered "no" — a typed `Diff<T>`, `TypeChanged` carrying classes rather
  than names, a common exception supertype — each with its reason.

### Changed

- **BREAKING** — **kdiff is published to Maven Central, and the group id changes.** A consumer needs
  `mavenCentral()`, which every Gradle build already declares, and nothing else: no repository block, no
  personal access token, no `401`. GitHub Packages is no longer published to.

  The group id is now `io.github.rcapraro` — a namespace Central can verify against the maintainer's
  GitHub account, which `io.github.kdiff` is not. **Package names do not change**, so no import moves:

  ```kotlin
  // before — GitHub Packages, with a repository block and a token
  implementation("io.github.kdiff:kdiff-annotations:0.6.0")
  implementation("io.github.kdiff:kdiff-runtime:0.6.0")
  ksp("io.github.kdiff:kdiff-processor:0.6.0")

  // after — Maven Central, nothing else to declare
  implementation("io.github.rcapraro:kdiff-annotations:0.7.0")
  implementation("io.github.rcapraro:kdiff-runtime:0.7.0")
  ksp("io.github.rcapraro:kdiff-processor:0.7.0")
  ```

  *Migration*: change the group id on the three coordinates and delete the
  `maven.pkg.github.com/rcapraro/kdiff` repository block, along with the `gpr.user`/`gpr.key`
  properties it read. Imports stay `io.github.kdiff.*`.

- **Every published module now ships a sources jar and a documentation jar**, both signed, as Central
  requires. *Go to declaration* on `Differ`, `Change` or any runtime helper lands on the Kotlin source
  with its KDoc instead of decompiled bytecode — the KDoc in `kdiff-runtime` carries the *why* of every
  helper, and until now it was only readable by cloning the repository.

- **BREAKING** — `Patched<T>` is removed; the patch helpers return `PatchResult<T>`. `patchValue`,
  `patchNested`, `patchNestedNullable`, `patchNullable`, `patchKeyedList`, `patchPositionalList`,
  `patchSet`, `patchMap`, `unpatchable` and `notConstructorProperty` all return the type a
  `Patcher.apply` returns, so a helper's result carries `isClean` and `getOrThrow()` too. The two types
  had identical shape, and `patchNested` existed partly to convert one into the other.

  *Migration*: rename `Patched` to `PatchResult`; the members are the same. A hand-written patcher that
  only reads `.value` and `.failures` — which is every example in the docs — compiles unchanged, and so
  does generated code, which reads the same two members.

- **BREAKING** — `Diff` is no longer a data class. It keeps its constructor, its `changes`, its
  equality over those changes and every member it had; it loses `copy` and `component1`, which would
  have fixed `Diff`'s shape forever. Its `toString()` is now the text `render()` produces, so a diff
  printed in a log line or an assertion failure reads as a diff — over several lines when it holds
  several changes.

  *Migration*: build a new `Diff(changes)` instead of `diff.copy(changes = …)`, and read `diff.changes`
  instead of destructuring. A caller that wants a diff on one line has `changes.toString()` or `size`.

### Fixed

- **BREAKING** — a comparison annotation on a property a `@Diffable` **sealed parent** declares is now
  honoured by a subclass that overrides it. Kotlin puts none of an overridden declaration's annotations
  on the `override`, so `@DiffAsValue`, `@DiffIgnore` and `@DiffWith` on a sealed parent's property held
  across a subclass swap — where the parent's own properties are compared — and were silently ignored
  for two instances of one subclass, which is the common case. If your model looks like this, its diffs
  change:

  ```kotlin
  @Diffable
  sealed interface Shipment {
      @DiffAsValue val origin: Address
  }

  @Diffable
  data class Parcel(override val origin: Address, val tracking: String) : Shipment
  ```

  Two `Parcel`s differing inside `origin` now report one change at `origin`; they reported changes at
  `origin.city` and the like before. `@DiffIgnore` there now excludes the property where it did not.

  *Migration*: nothing to edit — the new behaviour is what the annotated source always asked for. But a
  test or a consumer asserting on the old paths sees them move, so check any assertion naming a path
  beneath such a property. To keep the old behaviour, remove the annotation from the parent; to have it
  apply to one subclass only, annotate that subclass's override, which wins over the parent's.

- A generated differ is now regenerated when a `value class` or `enum class` it compared as a value is
  edited. Only `@DiffAsValue` recorded the declaring file before, so an incremental build could keep a
  differ that a clean build refuses: redeclaring `@JvmInline value class Sku(val code: String)` as an
  ordinary type left the generated `compareValue("sku", …)` in place and compiling, while a clean build
  failed with *kdiff cannot compare sku of type demo.Sku*. Builds now agree; the cost is that editing
  such a type regenerates the differs that read it.

Nothing else that compiled before means anything different, and the generated code for an existing
class is unchanged unless it is a subclass overriding an annotated property. `kdiff-annotations` gains
one annotation; `kdiff-runtime`'s recorded API moves exactly where the two breaks above say it does,
and `kdiff-processor` adds no public declaration.

## [0.6.0] - 2026-09-08

Three ordinary Kotlin shapes that the processor could not handle now work, and each of them failed in a
way the project's own rules forbid: an error inside generated code, an annotation that silently did
nothing, or two diagnostics pointing at each other with no way through.

### Added

- **A nullable collection property is compared and applied.** `List<E>?`, `Set<E>?` and `Map<K, V>?`
  follow the rule a nullable nested property already followed: a null on one side is one `ValueChanged`
  at the property carrying both sides, two nulls are no change, and two present collections are compared
  as that collection always is — by key, by position, by membership or by entry key. Applying mirrors it:
  a change at the property sets it wholesale, and a change beneath a null one is reported as
  `NothingBeneathNull`.

  Before this, such a property made the *generated* file fail to compile, with a type mismatch in a file
  the author had not written. The hand-written route gains it through the same builders — `list`,
  `keyedList`, `set` and `map` now accept a nullable property exactly as `nested` did, so no call site
  changes.

- **An `object` subclass of a `@Diffable` sealed type needs no annotation.** `data object Unpaid :
  Payment` alongside `@Diffable data class Card(...)` now compiles and is dispatched on: two references
  to one singleton report nothing, and a swap to or from it reports the type change and the sealed
  parent's own properties, as any subclass swap does. Applying a change addressed *beneath* a singleton
  reports it as naming a property the type does not have.

  Before this the shape was a dead end — `@Diffable` rejected the object, and the sealed parent required
  every subclass to carry it. `@Diffable` on the object stays an error, and now says the object needs no
  annotation of its own.

- **A collection whose elements are nullable and reached through a differ is a compile error at the
  property**, naming the element type and the two ways out, rather than an error inside generated code.
  `List<String?>` is unaffected, because equality is defined for null, and so is every `Set`.

- Two new runtime helpers, `patchNullable` and `patchSingleton`, which is the whole of the public API
  change. Widening the four collection compare helpers to accept a nullable side is source- and
  binary-compatible, so nothing else in the dump moved.

### Changed

- **BREAKING** — `@DiffKey`, `@DiffIgnore` and `@DiffWith` on a property of a class that is not
  `@Diffable` now fail the build, naming the property and the missing annotation. They are only ever read
  off a `@Diffable` class, so anywhere else each one silently configured nothing while its author
  believed comparison was set up. This is the rule the tracking annotations have carried since `0.1.0`,
  applied to the three that lacked it.

  *Migration*: add `@Diffable` to the class, or remove the annotation. A module carrying a stray one
  compiled before and does not now, which is the point.

- **BREAKING** — `@DiffWith` together with `@DiffIgnore` on one property is rejected as a conflict: an
  ignored property is never compared, so a differ named for it could never run. `@DiffKey` beside
  `@DiffIgnore` is deliberately **not** a conflict and still compiles — a key identifies the element
  while `@DiffIgnore` keeps it out of that element's own comparison, which is what you want.

- A change addressed to a **nullable nested property** that a value change at that same property
  replaces wholesale is now reported as `NotApplicableToValue` instead of being dropped in silence. The
  rebuilt value is unchanged; what changes is that `failures` accounts for every change it was given,
  which is what the library promises everywhere else. A caller asserting on an empty `failures` list for
  such a diff sees the new entry.

  The same rule now covers nullable lists, sets and maps, which is where it was noticed: a wholesale
  replacement makes the property a value for that application, so the last change at it wins and the
  rest could not be used.

- The generated API surface is otherwise **unchanged**. A class that compiles today regenerates
  byte-identically: the nullable-collection wrapper and the singleton branches appear only for shapes
  that could not compile before.

## [0.5.0] - 2026-09-08

One comparison behaves differently and nothing else moves: `kdiff-runtime` compares an unkeyed list by
excluding the tail the two sides already agree on. No signature changed, no annotation means anything
different, the processor is untouched, and an annotated class compiles to byte-identical generated
code — so upgrading is a re-baselining of assertions over unkeyed lists that change length, and nothing
else.

### Changed

- **BREAKING** — an unkeyed list whose length differs between the two sides now reports the insertion
  or deletion instead of a cascade. Before comparing, kdiff excludes the tail the two lists already
  agree on, so one contiguous edit — at the head, in the middle, or at the tail — reports as exactly
  those additions or removals.

  ```
  before = ["a", "b", "c"]        was                            now
  after  = ["x", "a", "b", "c"]     3 value changes + 1 addition   1 addition, at index 0
  ```

  Nothing in the API moved: no signature changed, no annotation means anything different, the
  processor is untouched and generated code is byte-identical. What changed is the *content* of the
  diff for that one case.

  *Migration*: re-baseline assertions over unkeyed lists that change length. **Two lists of the same
  length are unaffected** — they are still compared index by index, and that is now a stated guarantee
  rather than an accident, because for a fixed-arity list (seven weekday slots, a coordinate triple, a
  three-place ranking) the index *is* the element's identity.

  Two scattered edits still smear: `["a","b","c"] -> ["x","a","b","c2"]` agrees at neither end, and
  recovering it would need the edit-distance search kdiff's linear bound rules out. `@DiffKey` remains
  the answer wherever elements have an identity.

  A position is excluded only when comparing it would report nothing — equality where elements are
  compared as values, the differ reporting no change where they are compared by a differ. A type whose
  `equals` is looser than the properties its differ reads is therefore still compared in full.

## [0.4.0] - 2026-09-07

What changes here is `kdiff-runtime`'s API. No annotation means anything different, the processor is
untouched, and an annotated class compiles to byte-identical generated code — so upgrading is a
recompile of the code you wrote *around* the generated code, guided by the breaking items below.

### Added

- **A diff is a collection.** `Diff` implements `Iterable<Change>` and gains `size`, `isNotEmpty()`,
  `plus`, a `vararg` constructor and `Diff.EMPTY`, so the standard library's operators apply to a diff
  directly instead of through `changes`. It is deliberately `Iterable` rather than `List`: a `Diff`
  equals only another `Diff`, and advertising list-ness while equalling no list would mislead.

- **A diff narrows by property reference.** `diff.at(Order::billing)` keeps the change reported at that
  property; `diff.under(Order::billing)` keeps that plus everything beneath it. Both return a `Diff`,
  so they compose with each other and with `+`, and neither matches a string — renaming the property
  reaches the call site.

  Name the type — `diff.at<Order>(Order::billing)` — to have the property checked against it. `Diff`
  carries no type argument, so an inferred type parameter comes from the property alone and
  `orderDiff.at(Address::street)` compiles and matches nothing. `route<Order> { }` has no such hole.

- **`PatchResult.getOrThrow()`** returns the value when every change applied and raises
  `PatchFailedException`, carrying every failure, when any did not. Applying itself stays partial —
  that is what lets a caller salvage what applied — so all-or-nothing is now a choice at the call site.

- **A cyclic structure is reported instead of overflowing the stack.** Comparing and applying descend
  at most `MAX_DESCENT` (512) nested levels and then raise `CyclicStructureException`, naming the path
  they stopped at and saying whether an instance was re-entered — a cycle — or the structure is simply
  deeper than kdiff descends. This costs about 9% of comparison throughput on a model with nested
  `@Diffable` properties and 13% at six levels of nesting; collections and flat types are unaffected.

- **Builder blocks are scoped and run exactly once.** `@KdiffDsl` closes every builder scope, so a
  block nested in another can no longer reach the outer builder's members — a `route` frame calling the
  enclosing routing's `on` used to compile and quietly register a handler against the wrong frame. Each
  builder function also declares `callsInPlace(block, EXACTLY_ONCE)`, so a `val` can be assigned inside
  a block and read after it.

- **Build gates.** `./gradlew check` now also runs ktlint, detekt, `allWarningsAsErrors` and ABI
  validation against a checked-in API dump per published module, so removing a public declaration fails
  the build rather than reaching a consumer. `dokkaGenerate` builds reference documentation, and is
  deliberately not part of `check`. See [CONTRIBUTING.md](CONTRIBUTING.md).

### Changed

- **BREAKING** — `PatchFailure.reason` is a sealed `PatchFailure.Reason` instead of a `String`. The
  fourteen reasons the runtime reports are now declared cases, each carrying what it knows — the
  property, the key, the index — so a caller can branch on why a change did not apply instead of
  matching prose.

  *Migration*: `failure.reason == "no element with this key to patch"` becomes
  `failure.reason is PatchFailure.Reason.NoElementForKey`. A failure logged as text is unchanged:
  `PatchFailure.toString()` renders the same sentence it always did. Like `Change`, the vocabulary is
  closed, so adding a case is itself breaking.

- **BREAKING (source only)** — `Diff.isEmpty` is now a function, `isEmpty()`, to match the shape the
  standard library uses for every other collection.

  *Migration*: add the parentheses. Kotlin compiles both a `val isEmpty: Boolean` and a
  `fun isEmpty(): Boolean` to the same `isEmpty()Z` signature, so this is a recompile rather than a
  link error — already-compiled callers keep working. `DiffNode.isEmpty` is unchanged and remains a
  property.

- **BREAKING** — the duplicate-`@DiffKey` refusal raises `DuplicateDiffKeyException`, carrying the list
  property, the key property and the duplicated key value as inspectable properties. It is an
  `IllegalArgumentException`, which is what was raised before, so an existing `catch` keeps working and
  the message is unchanged.

- **BREAKING** — `TrackScopeBuilder` and `TrackerBuilder` declare their five shared members through one
  `ScopeDeclaration<T>` interface over one implementation, rather than one re-declaring and forwarding
  the other's. Source-compatible for every call site inside a builder block; binary-incompatible, which
  is what the new API dump exists to make visible.

- **BREAKING** — a `differ { }` naming no property and no subtype is rejected where it is built. Such a
  differ reported every pair of instances as equivalent however much they differed, which is the same
  mistake the processor already rejects for an annotated class with nothing to compare.

- **BREAKING** — consumers must compile at JVM target 21. The builder entry points are `inline` so they
  can state `callsInPlace`, and Kotlin will not inline bytecode built for a higher target than the
  module being compiled. Loading these classes already required a JVM 21; compiling against them now
  does too.

- The generated API surface is **unchanged**. No annotation means anything different, the processor is
  untouched, and a `@Diffable` class compiles to byte-identical generated code — the breaking items
  above are all in `kdiff-runtime`, in what a consumer writes around that code.

## [0.3.1] - 2026-09-07

### Changed

- The runtime's three hot paths — comparison, application and tracking — allocate substantially less
  for the same results. No API is removed or changed, no annotation means anything different, and the
  change vocabulary is untouched: upgrading is a version bump.

  Comparison builds a lifted path in one copy instead of two and lifts a nested collection change once
  instead of twice; sets are compared by membership rather than by building two intermediate sets; a
  keyed list indexes by key without wrapping every element, and detects a repeated key from that index
  rather than from a second walk. Applying returns a property no change addresses as the source
  instance rather than rebuilding it. Tracking resolves each property's depth once when the scope is
  resolved, so deciding whether to report a change allocates nothing. Routing groups changes once
  instead of scanning them once per handler.

- Applying a diff no longer rebuilds a property that nothing in the change list addresses: the source
  instance is carried through, as it already was for a nested value and for a property excluded from
  comparison. Results compare equal either way; what changes is that the rebuilt object now shares an
  untouched collection with its source, exactly as `copy()` already did for every property a patcher
  does not name.

  The one precondition this does not skip is a keyed list holding a repeated key, which is still
  rejected whether or not a change addresses it.

- A generated `apply` declares the set of compared property names once as a private property instead
  of rebuilding it on every call, and gathers its failures into one list instead of folding them with
  `+`. The generated API is unchanged — the new property is private — but regenerating is needed to
  pick this up.

### Added

- `kdiff-benchmarks`, an unpublished module carrying JMH benchmarks for comparison, application,
  tracking and routing. It is deliberately not part of `./gradlew check`; run it with
  `./gradlew :kdiff-benchmarks:jmh`, adding `-Pjmh.profilers=gc` for allocation rate.

## [0.3.0] - 2026-09-06

### Added

- A `@DiffKey` value must identify at most one element in a list. Comparing or applying a keyed list
  whose elements share a key throws `IllegalArgumentException`, naming the property, the key property
  and the duplicated value.

  Such a list has no diff to report: a path identifies a keyed element by its key value, so
  `addresses[id=A1]` cannot say which of two elements it means, and distinguishing them would need a
  change variant the closed vocabulary does not have. It cannot be caught at compile time either —
  uniqueness is a property of the data, not of the declaration — so it is a runtime precondition, and
  both directions enforce it identically.

  If a key is not unique it is not an identity: drop `@DiffKey` from the element type, or describe the
  property with `list` rather than `keyedList`, and the list is compared by position instead, giving up
  move reporting.

- `patchKeyedList` takes `name` and `keyProperty`, so it can name the offending property in that
  message. Both default to null; a hand-written `Patcher` that omits them gets a message without them.
  Generated code passes both.

### Changed

- The guides are corrected and extended: diagrams for the change and path model, `under` frame
  dispatch, depth counting, the module graph and the tutorial's command-to-events flow; a
  "Where to go next" footer on every page; sections in `docs/architecture.md` on what a comparison
  costs and what kdiff does not do; and `DiffNode`/`tree()` given a worked example.

- Documented coordinates are pinned to the published version by a test, so an install snippet cannot
  survive a release that leaves it behind.

## [0.2.0] - 2026-09-04

### Added

- `under(property) { }` inside `Diff.route` routes the changes beneath one property against that
  property's own type, so a model whose value objects nest more than one level deep can be routed at
  the granularity its domain speaks in. Frames nest as deep as the model does, and every route works
  inside one — `on`, `onEach` in both forms, and `otherwise`. Purely additive: a routing that declares
  no frame behaves exactly as before.

  Two rules worth reading before you rely on them. A change no handler in a frame names goes to that
  frame's `otherwise` if it declares one, and otherwise back out to the enclosing routing at the path
  it arrived with — so one `otherwise` at the top still sees everything unnamed at any depth. And a
  change reported *at* a framed property rather than beneath it, which is what a nullable value object
  reports when it appears or disappears, is treated as unhandled rather than delivered to a handler.

## [0.1.0] - 2026-09-04

First release.

### Added

- `@Diffable` generates `object <Type>Differ` at compile time, carrying comparison, application and
  tracking through one declaration: `Differ<T>`, `Patcher<T>` and — with `@Trackable` — `Tracked<T>`.
- A closed change vocabulary: `ValueChanged`, `Added`, `Removed`, `TypeChanged` and `Moved`, each
  carrying a `FieldPath` that retains keys as their own values rather than as text.
- Comparison for values, enums, nullables, nested types, keyed lists (a reorder reports as `Moved`,
  not as a removal plus an addition), positional lists, sets, maps and sealed hierarchies.
- Patching: the same object applies a diff back and reports what it could not apply.
- Tracking: `Tracker` reports what a scope selects as a value evolves, with per-field and batched
  callbacks; `Differ.trackedDiff` does the same for a caller holding both instances.
- Routing: `Diff.route<T> { on(…) / onEach(…) / otherwise(…) }` decides what a change means, naming
  every property by reference and supplying elements and keys at their own types.
- A hand-written route that reaches everything the annotations can: `differ { }` covers values,
  nested properties, keyed and positional lists, sets, maps and sealed subtypes, and
  `trackScope { }` covers scopes, including `except` for the properties not worth tracking.
- `kdiff-tutorial`, a worked DDD application written against a domain that imports nothing from
  kdiff, with an annotated mirror held to identical output by `AnnotatedParitySpec`.

### Requirements

- JDK 17 or later; built against a JVM 21 toolchain, Kotlin 2.4.10 and KSP 2.3.11.

[Unreleased]: https://github.com/rcapraro/kdiff/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/rcapraro/kdiff/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/rcapraro/kdiff/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/rcapraro/kdiff/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/rcapraro/kdiff/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/rcapraro/kdiff/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/rcapraro/kdiff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/rcapraro/kdiff/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rcapraro/kdiff/releases/tag/v0.1.0
