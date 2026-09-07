## Why

kdiff promises "no runtime cost beyond the generated code", but the generated code delegates almost
everything to `kdiff-runtime`, and the runtime's hot paths allocate far more than the work requires.
Reading the three hot paths — compare, apply, track — turns up allocation and complexity that is
paid on every call, not just when something changed:

- **Lifting a nested path copies the whole path, twice per element change.**
  `FieldPath.prefixedWith` is `listOf(segment) + segments`: two allocations and a full copy per lift.
  `compareKeyedList`, `comparePositionalList` and `compareMap` lift each nested change *twice*
  (`prefixedWith(element).prefixedWith(field)`), so each nested change costs two `Change` copies and
  two path copies at every level it bubbles through. A change `d` levels deep pays `O(d²)` segment
  copies.
- **A fresh `Segment.Field(name)` is allocated per change** inside the per-change loops of
  `compareNested`, `compareKeyedList` and `comparePositionalList`, though the name is a constant of
  the call. `compareNested` also builds a throwaway list via `map` before `addAll`.
- **`compareSet` copies both sets.** `(before - after)` and `(after - before)` each build a full
  `LinkedHashSet` copy of the receiver; two membership walks need neither.
- **Tracking allocates two lists per change, per update.** `ResolvedScope.selects` does
  `fields.filter { it.name == root.name }.map { it.depth }` for *every* change it is asked about,
  re-deriving a widest-depth answer that is fixed the moment the scope is resolved. `Tracker.update`
  builds a `Pair` per selected change even when no per-field listener is registered.
- **Applying allocates a result and a failure list for every property, changed or not.** A generated
  `apply` calls one patch helper per compared property; each allocates a `mutableListOf<PatchFailure>`
  and a `Patched` even for an empty change list, and `patchSet` / `patchPositionalList` / `patchMap`
  additionally copy the whole untouched collection. The generated `apply` then allocates a fresh
  `setOf(...)` of property names on *every call*, and folds the per-property failures with a chain of
  `+`, which is `O(p²)` list copies over `p` properties.
- **Routing is quadratic twice over.** `ChangeRoutes.dispatch` filters the whole change list once per
  registered handler, then computes leftovers with `it in unroutable` — a linear scan of a `List` using
  deep data-class `equals` on every element.

None of this is a correctness problem, and none of it has a spec that forbids it — which is exactly
why it has accumulated. The library's value proposition is being fast and reflection-free; the
runtime should be worth the promise.

## What Changes

All of it is behaviour-preserving in value. Nothing is added to the change vocabulary, no annotation
gains or loses meaning, and no signature is removed.

**`kdiff-runtime` — comparison**

- `FieldPath.prefixedWith` builds one right-sized list instead of `listOf(x) + segments`; a
  two-segment lift is added so the collection helpers lift a nested change once instead of twice.
- `FieldPath.withoutFirst` and `Change.withoutFirstSegment` return a `subList` view instead of
  `drop(1)`'s array copy.
- `compareNested`, `compareKeyedList`, `comparePositionalList` hoist the `Segment.Field(name)` out of
  their per-change loops and append directly instead of building an intermediate list.
- `compareSet` walks membership instead of building two set copies.
- `compareKeyedList` indexes by key without `withIndex()`'s per-element `IndexedValue`.

**`kdiff-runtime` — tracking**

- `ResolvedScope` becomes a sealed pair — `Everything` and `Named` — so that "tracks every compared
  property" and "names properties, none of them" are two types rather than a nullable field one
  dereference apart. That is what the existing requirement *A tracking scope with no selectors tracks
  the whole object* already demands ("two distinct things, and the library SHALL keep them distinct"),
  and it makes the conflation behind this repo's one recorded silent-widening bug inexpressible.
- `Named` resolves the per-property widest depth once, at construction, into a lookup; the per-change
  `filter`/`map`/`max` disappears. The rewrite ships with an exhaustive differential oracle against the
  current rule, plus an invariant test stating the widening failure direction directly.
- `Tracker.update` computes a change's two sides only when a per-field listener is registered, and
  without a `Pair`.

**`kdiff-runtime` — application**

- Patch helpers take a fast path when no change addresses their property: the source value is carried
  through as-is rather than defensively rebuilt. `patchKeyedList` keeps its specified duplicate-key
  rejection — which holds "whatever is being applied to it and an empty change list included" — by
  validating first and exiting second, so the fast path sits after the check by position rather than
  by a special case.
- `patchKeyedList` derives each element's key once instead of twice, detects a repeated key from the
  map build itself, and drops the parallel `order` list a `LinkedHashMap` makes redundant — removing
  two linear scans per removal and per addition along with it.

**`kdiff-runtime` — routing and rendering**

- `ChangeRoutes.dispatch` groups changes by root property in one pass instead of one filter pass per
  handler, and tracks unrouted changes by identity rather than by deep equality.
- `renderChanges` renders each path once instead of twice.

**`kdiff-processor` — generated code**

- The property-name set a generated `apply` passes to `groupByProperty` is hoisted to a `private val`
  on the generated object, so it is allocated once per type rather than once per call.
- The failure list is emitted as one `buildList` rather than a chain of `+`.

The generated API surface does not change: `object <Type>Differ : Differ<T>, Patcher<T>, Tracked<T>`
keeps exactly the members it has today. The hoisted property set is `private`, so it is not part of
the surface a consumer can see or depend on. **Not breaking.** No consumer recompiles or adapts.

**Verification — new `kdiff-benchmarks` module**

- A new, unpublished `kdiff-benchmarks` module using the `me.champeau.jmh` Gradle plugin, measuring
  throughput and allocation rate (`-prof gc`) for compare, apply and track over the `kdiff-sample`
  model. It is deliberately *not* wired into `./gradlew check`, which must stay fast; it runs on
  demand with `./gradlew :kdiff-benchmarks:jmh`. Baseline numbers are recorded in `design.md` before
  the optimisations land, so the change can be judged on measurements rather than on reasoning.

**Three edge effects, accepted deliberately**

1. A collection property no change addresses is now returned as the source instance rather than a
   defensive copy, so the rebuilt object shares it with the source — which is already what
   `data class copy()` does for every property nobody touched. Equal by `==`, no longer a distinct
   instance. This is spec'd, below.
2. Routing leftovers are tracked by identity rather than by deep `equals`. This turns out to be
   **unobservable** through the public routing API rather than an edge effect at all: two changes can
   only be equal if they share a path, hence a root property, hence a handler — and a handler's verdict
   is a function of a change's kind and values, so it cannot accept one and decline its equal twin. It
   is therefore an internal optimisation, not a behavioural change, and is not spec'd as one. What *is*
   spec'd is the property it makes explicit and that `ChangeRoutes.under` already relies on: the
   fallback receives the change instances the diff holds.
3. `FieldPath.withoutFirst` returns a view that keeps the parent segment list reachable for as long as
   the derived path lives. `equals`, `hashCode` and `toString` are unaffected.

## Capabilities

### New Capabilities

None. This change adds no behaviour; it makes existing behaviour cheaper.

### Modified Capabilities

- `diff-application`: the requirement *Applying a diff to its source reproduces the target* already
  states that a nested value no change addresses is skipped rather than rebuilt. That is extended to
  every property shape — value, set, positional list and map — so "not reached, not rebuilt" is the
  rule rather than a nested-value special case, and so edge effect (1) is a stated contract instead of
  an accident.
- `diff-generation`: two additions. First, that a differ reads each compared property exactly once per
  `diff` call — the invariant the comparison rewrite must not break, observable through a counting
  property getter, and stated nowhere today. Second, that a routing's fallback receives the change
  instances the diff reported, in the diff's order — observable, relied on by `ChangeRoutes.under`, and
  stated nowhere today: the requirement *A diff can be routed to handlers named by property reference*
  says which handler runs and that unhandled changes reach the fallback, but not what the fallback is
  handed.

`change-tracking` needs no delta: `ResolvedScope` and `Tracker.update` change how the same selection
is computed, not what it selects. Its existing requirements — in particular *A property named more
than once is tracked at the widest depth named* and the `TrackScopeCompositionSpec` guard against
scope widening — are the regression net for that rewrite.

## Impact

- **Modules**: `kdiff-runtime` (`Compare.kt`, `FieldPath.kt`, `Patch.kt`, `Patcher.kt`, `Route.kt`,
  `Select.kt`, `Track.kt`, `Render.kt`), `kdiff-processor` (`DiffProcessor.kt` — the `apply` body
  emitter only), plus a new `kdiff-benchmarks` module. `kdiff-annotations` is untouched.
- **Generated output**: `kdiff-sample`'s generated differs change shape slightly (a hoisted `private
  val`, a `buildList` for failures). Regenerate and re-run its tests; the processor's snapshot
  assertions on generated text need updating in step.
- **Public API**: additive only — a two-segment `prefixedWith`. No removals, no signature changes, no
  new `Change` variant. `kdiff-runtime` still depends only on the Kotlin standard library.
- **Dependencies**: one new Gradle plugin, `me.champeau.jmh`, and a version-catalog entry for it. It
  applies to `kdiff-benchmarks` only, which is not published and is excluded from `publishedModules`.
  No library version is bumped.
- **Annotation semantics**: unchanged. Existing annotated code compiles and behaves identically; only
  the generated `apply` body's shape differs.
- **Not in scope**: `Diff.tree()` and `DiffNode.allChanges()`, which are opt-in presentation calls
  rather than hot paths, and the deeper "push the path prefix down into nested differs" redesign —
  see `design.md` for why that is deferred.
