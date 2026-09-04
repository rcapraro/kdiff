## Why

kdiff claims two authoring routes — annotations, or the hand-written DSL — but they are not
equivalent. `differ { }` offers only `field` and `nested`, so a domain that cannot (or will not) carry
annotations gets no keyed lists, no sets, no maps, no sealed dispatch: the comparisons the runtime
already implements are reachable only through the processor. And dispatching on a change is worse —
field tokens are generated from `@Trackable`, so an unannotated domain must match on property names
written as text, the exact failure mode tokens exist to prevent.

The `kdiff-tutorial` module shows the cost. Its domain is annotated throughout, it carries a
`PersonState` distinct from the `Person` aggregate purely so there is something annotatable to diff,
and its command handler spends thirty lines on a stateful tracker plus a two-level token `when` to
reach six aggregate methods. That is not what using this library should look like.

## What Changes

- **`differ { }` gains the full comparison vocabulary**: keyed lists, positional lists, sets, maps,
  nullable nested properties and sealed-subtype dispatch. Anything `@Diffable` can compare, the
  builder can now describe — same runtime helpers, same paths, same changes.
- **A change router replaces field tokens.** `diff.route<Person> { on(Person::name) { … } }` dispatches
  a diff to handlers named by property reference, with typed element and key access for collections
  and an `otherwise` branch for everything unhandled. It works identically whether the differ was
  generated or hand-written, because it reads only the diff.
- **BREAKING — field tokens are removed.** The generated `<Type>Field` sealed hierarchy, its companion,
  `FieldToken`, `FieldTokens`, `ElementField`, `KeyedField`, `Change.fieldOf`, `FieldToken.owns`,
  `elementValueOf` and `elementKeyOf` all go. Callers matching on tokens migrate to `route`. The
  trade-off is deliberate and stated in the design: routing is not an exhaustive `when`, so a property
  added to a model no longer breaks a consumer's dispatch at compile time.
- **A tracking scope can name what it excludes**: `trackScope<Person> { except(Person::lastSeenAt) }`
  is the hand-written counterpart of `@TrackIgnore`, and tracks everything else.
- **A scope can filter a one-shot comparison**: `differ.trackedDiff(before, after, scope)` applies a
  scope without constructing a `Tracker`, for a caller that holds two instances rather than a baseline.
- **The tutorial is rebuilt around an annotation-free domain.** Plain Kotlin data classes with no kdiff
  import, one file describing them with `differ { }` and `trackScope { }`, an immutable decider in
  place of the mutable aggregate (no `PersonState`, no rehydrate/snapshot, no no-op guards, no index
  clamping), and a handler whose body is load, project, `trackedDiff`, `route`, save. A small
  `tutorial.annotated` mirror shows the same model annotated, with a parity spec asserting both routes
  report identical diffs.

Annotation semantics do not change: `@Diffable`, `@Trackable`, `@DiffKey`, `@DiffWith`, `@DiffIgnore`,
`@TrackIgnore` and `@TrackDepth` keep their meaning, and annotated code compiles unchanged except
where it referenced a token.

## Capabilities

### New Capabilities

None. Routing consumes a diff, so it joins the existing `diff-generation` capability where paths,
trees and rendering already live.

### Modified Capabilities

- `diff-generation`: the hand-written differ requirement grows to cover every comparison shape the
  annotations support; the six field-token requirements are removed; a new requirement covers routing
  a diff to handlers named by property reference.
- `change-tracking`: a hand-written scope can name excluded properties; a scope can filter a one-shot
  comparison without a tracker.

## Impact

- `kdiff-runtime`: `Dsl.kt` (builder vocabulary, `except`), `Track.kt` (`except`, `trackedDiff`),
  `Select.kt` (exclusions), new `Route.kt`; `Tokens.kt` deleted.
- `kdiff-processor`: token generation deleted from `DiffProcessor.kt`, with the token-only plumbing in
  `Resolution.kt` (`elementType()`, `Comparison.element`, `Comparison.keyType`) and `Names.kt` going
  with it. Generated output shrinks to the differ object alone.
- `kdiff-sample`: generated files no longer contain a token hierarchy; sources are unaffected.
- `kdiff-tutorial`: rewritten — domain, differ description, events, handler, main, specs.
- `kdiff-annotations`: unchanged.
- Docs: `tutorial.md` rewritten; `hand-written.md`, `diffing.md`, `tracking.md` updated for the new
  vocabulary and for routing replacing tokens.
- **BREAKING for consumers** on the token API only; comparison, patching and tracking results are
  byte-for-byte unchanged.
