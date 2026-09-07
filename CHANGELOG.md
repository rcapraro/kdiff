# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before `1.0.0` a minor version may
carry a breaking change; each one is called out under **Changed** with the migration.

The section for a version is what the release notes for its tag are built from, so keep a version's
entry written for someone deciding whether to upgrade.

## [Unreleased]

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

[Unreleased]: https://github.com/rcapraro/kdiff/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/rcapraro/kdiff/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/rcapraro/kdiff/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rcapraro/kdiff/releases/tag/v0.1.0
