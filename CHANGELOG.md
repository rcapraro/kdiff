# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before `1.0.0` a minor version may
carry a breaking change; each one is called out under **Changed** with the migration.

The section for a version is what the release notes for its tag are built from, so keep a version's
entry written for someone deciding whether to upgrade.

## [Unreleased]

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

[Unreleased]: https://github.com/rcapraro/kdiff/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/rcapraro/kdiff/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/rcapraro/kdiff/releases/tag/v0.1.0
