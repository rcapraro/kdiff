## Why

kdiff generates a differ for every `@Diffable` data class, and that differ compares nothing. The
pipeline is proved; the library does not yet do the one thing it exists to do.

This change makes generated differs actually compare two instances field by field, across the
shapes real Kotlin models are made of: nested types, collections, maps, enums, nullable
properties, and sealed hierarchies.

## What Changes

- **BREAKING** — a generated differ now reports differences. Any code written against the previous
  baseline, where `diff` returned an empty result for every pair, will start seeing changes. This
  is the behaviour the baseline existed to be replaced by, and `kdiff-sample`'s test is updated in
  this change.

- The change model in `kdiff-runtime` gains its cases: `ValueChanged`, `Added`, `Removed`,
  `TypeChanged` and `Moved` implement `Change`; `Segment` gains `Index` and `Key` alongside
  `Field`. All additive — no existing declaration changes shape.

- Three new annotations in `kdiff-annotations`:
  - `@DiffKey` marks the property that identifies an element inside a collection, which is what
    lets a list diff report a moved or modified element instead of a wholesale replacement.
  - `@DiffIgnore` excludes a property from comparison.
  - `@DiffWith` names a hand-written differ to use for a property whose type cannot be annotated.

- `@Diffable` gains meaning on sealed classes and interfaces. Annotating a sealed type whose
  subclasses are all `@Diffable` generates a differ that dispatches on the runtime subclass. When
  both sides are the same subclass it delegates to that subclass's differ; when they differ it
  reports a `TypeChanged` together with changes to the properties the sealed parent itself
  declares. Annotating a data class behaves exactly as before.

- Generated differs compare, per property type: scalars, `String` and enums by value; nullable
  properties with null on either side as a value change; nested `@Diffable` types by delegation
  with the path prefixed; `List` by key when the element type declares one and positionally
  otherwise; `Set` as unordered membership; `Map` by entry key.

- A differ can now be written by hand: `differ<T> { field(T::x) }` builds a `Differ<T>` for a type
  that cannot be annotated. `@DiffWith` points a property at one. Both produce the same interface
  generated code produces, so the two compose.

- New compile-time diagnostics, each an error naming the offending declaration: a property whose
  type kdiff cannot compare and that carries no `@DiffWith`; a generic annotated class; a sealed
  type with a subclass that is not `@Diffable`; `@DiffKey` on more than one property of a type.

- `Diff` gains `tree()` for a hierarchical view of the same changes and `render()` for a
  human-readable text form.

- Cyclic object graphs remain unsupported and are documented as such: generated differs recurse
  structurally, so a cycle in the data recurses without bound. This is not statically detectable in
  general, so it is a documented constraint rather than a diagnostic.

## Capabilities

### New Capabilities

None. Everything here extends the behaviour of the existing capability.

### Modified Capabilities

- `diff-generation`: the capability stops being a generation pipeline that reports nothing and
  becomes an actual structural diff. The requirement fixing the empty-result baseline is retired;
  requirements for per-type comparison, keyed collection matching, sealed dispatch, the
  hand-written differ escape hatch, the new diagnostics, and the tree and text views are added; and
  the requirement governing which declarations `@Diffable` accepts is widened to admit sealed types.

## Impact

- **Affected modules**: `kdiff-annotations` (three new annotations), `kdiff-runtime` (change model
  cases, `Segment` cases, the DSL, `tree()`, `render()`), `kdiff-processor` (all comparison
  generation and every new diagnostic), `kdiff-sample` (new annotated models exercising each shape;
  its existing test asserting an always-empty diff is replaced).
- **Affected APIs**: the generated differ's signature is unchanged — `object <Type>Differ :
  Differ<Type>` with `diff(before, after): Diff` — which is what the skeleton change bought. Only
  the body changes. `Diff`, `Change` and `Segment` grow additively.
- **Dependencies**: none added. The charter's pinned versions are unchanged.
- **Incremental processing**: a generated differ now depends on more than one source file, because
  it delegates to the differs of nested and sealed-subclass types. Every such file becomes an
  originating dependency of the generated output. Getting this wrong yields stale generated code
  that only appears on incremental builds, so it is called out in the design and verified by a task.
- **Risk**: this is a large change — the full type-resolution surface plus the tree and renderer in
  one step, at the user's explicit direction. The mitigation is that each property shape is an
  independently compile-tested scenario, so a failure localises to one shape rather than to
  "comparison is broken".
