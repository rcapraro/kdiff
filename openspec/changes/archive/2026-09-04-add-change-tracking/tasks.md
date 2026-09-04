## 1. Annotations

- [x] 1.1 Add `kdiff-annotations/src/main/kotlin/io/github/kdiff/annotations/Tracking.kt` with
  `UNLIMITED_DEPTH: Int = -1`, `@Trackable(val depth: Int = UNLIMITED_DEPTH)` targeting `CLASS`,
  `@TrackIgnore` targeting `PROPERTY`, and `@TrackDepth(val depth: Int)` targeting `PROPERTY`, all
  `BINARY` retention, with KDoc written to mirror `@Diffable`/`@DiffIgnore` (design D11) and stating
  the depth semantics (D4) — verify `./gradlew :kdiff-annotations:compileKotlin` succeeds and the
  module still declares no dependencies

## 2. Runtime — the scope, reachable by both routes

- [x] 2.1 Add `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/TrackScope.kt` with
  `TrackedField(name, depth)`, `TrackScope<T>` (`fields: List<TrackedField>?`, `null` meaning no scope
  declared), `Tracked<T>` exposing `trackScope`, and `trackScopeOf(vararg)` for generated code, per
  design D2/D16 — verify `./gradlew :kdiff-runtime:compileKotlin` passes under `explicitApi()` with
  KDoc on every public declaration
- [x] 2.2 Add `trackScope { }` and `TrackScopeBuilder<T>` (`depth`, `field(property)`,
  `field(property, depth)`, `under(property)`) to `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Dsl.kt`,
  beside `differ { }`, using `KProperty1.name` only — no `kotlin-reflect` (design D5, D16) — verify a
  Kotest spec builds a scope for a type carrying no annotation and confirms the module has no
  `kotlin-reflect` dependency
- [x] 2.3 Reject an invalid depth (`0`, or negative other than `UNLIMITED_DEPTH`) wherever a
  hand-written scope declares one, with a message naming the field and the accepted values — verify a
  Kotest spec asserts the exception and message for the builder's `depth` and for
  `field(property, depth)`, covering the spec scenario "A zero depth in a hand-written scope is
  rejected"
- [x] 2.4 Add internal `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Select.kt` with
  `FieldPath.fieldDepth()` counting `Segment.Field` only (D4), the root-segment field match, the
  always-report rule for a `FieldPath.ROOT` change, and `Change.sides()` as an exhaustive `when` with
  no `else` (D7) — verify a Kotest `SelectSpec` covers: depth 1 excludes `billing.city`, depth 1
  includes `addresses[id=A3]` and `amounts[key=eur]`, depth 2 includes `addresses[id=A2].street`, an
  unselected field is excluded, `FieldPath.ROOT` is always included, and each of the five `Change`
  kinds maps to the sides the spec table states
- [x] 2.5 Implement scope resolution per design D6 — call-site fields replace declared ones, a
  call-site `depth` overrides every selected field's depth, `null` means track everything, and a
  declared scope is read by testing the differ for `Tracked<T>` — verify a Kotest spec covers the four
  scenarios of "An annotated type's declared scope is used when the caller declares no field", using
  one hand-written differ that also implements `Tracked` and one that does not

## 3. Runtime — the tracker

- [x] 3.1 Add `kdiff-runtime/src/main/kotlin/io/github/kdiff/runtime/Track.kt` with `Tracker<T>`
  (`current`, `update(next): Diff`, `reset(baseline)`), `tracker(differ, initial, scope) { }`,
  `tracker(differ, initial) { }`, and `TrackerBuilder<T>` delegating its three selector verbs to
  `TrackScopeBuilder` and adding `scope(...)`, `onFieldChange`, `onChange` (design D16), with KDoc
  stating the tracker is not thread-safe (D10) — verify `./gradlew :kdiff-runtime:compileKotlin` passes
  under `explicitApi()`
- [x] 3.2 Implement dispatch: filter the diff through the resolved scope, fire every per-field callback
  once per matching change in the differ's order, fire every batched callback once per update only
  when the match is non-empty, then adopt `next` as the baseline and return the filtered `Diff` —
  verify a new Kotest `TrackingSpec` covers every scenario of "A tracker observes an evolving instance
  against a baseline", "A per-field callback reports each matching change separately" and "A batched
  callback reports one update as a whole", including several callbacks of the same style and an
  equal-instance update firing nothing
- [x] 3.3 Verify `reset` replaces the baseline without comparing or firing, and that a following update
  against the reset value reports nothing — `TrackingSpec` scenario for "A tracker's baseline can be
  reset without reporting"
- [x] 3.4 Verify selection and depth end to end through the tracker against a hand-written differ
  covering a nested object, a keyed list, a positional list, a set, a map and a sealed type — Kotest
  scenarios for "A tracking scope can select individual fields", "…select a field and its whole
  subtree", "Depth bounds how deep a reported change may lie", "Collection and map element identity
  does not consume depth", "A change excluded by depth is dropped, not relocated" and "A change at the
  tracked object itself is always reported"
- [x] 3.5 Verify an update mutates neither instance and that a reported change keeps the path the differ
  gave it — `TrackingSpec` scenarios for the immutability and path-preservation assertions

## 4. Runtime — coherence with diff and patch

- [x] 4.1 Verify a hand-written scope is indistinguishable from a declared one: two trackers naming the
  same fields at the same depths, one from `trackScope { }` and one from a differ implementing
  `Tracked`, report identically for the same update — Kotest scenarios for "A tracking scope can be
  written by hand for a type that cannot be annotated", including a type carrying no kdiff annotation
  at all, compared by `differ { }` and tracked by `trackScope { }`
- [x] 4.2 Verify a property delegated to a hand-written differ tracks by its path with no special
  handling, including a compare-only differ that makes the property unpatchable but still trackable
  (design D3) — Kotest scenarios for "A property compared by a hand-written differ tracks like any
  nested property"
- [x] 4.3 Verify a tracker's report applies to its baseline (design D8) — Kotest scenarios for "A
  tracker's report can be applied to its baseline": an unrestricted scope reproduces the target with no
  failures, a narrowed scope propagates only the tracked fields and leaves the rest at baseline with no
  failures, and an empty report applies cleanly

## 5. Processor — resolution

- [x] 5.1 Extend `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/Resolution.kt` to resolve
  `@Trackable`, `@TrackIgnore` and `@TrackDepth` into a declared scope, reusing the existing
  `hasAnnotation` and `comparableProperties` shape (design D11, D13): class opts in, `@TrackIgnore`
  opts out, `@TrackDepth` overrides one property's depth, unlimited by default — verify a new kctfork
  `TrackingGenerationSpec` compiles a class per case and asserts on the scope read back off the
  generated declaration
- [x] 5.2 Add member names for `TrackedField`, `TrackScope`, `Tracked` and `trackScopeOf` to
  `kdiff-processor/src/main/kotlin/io/github/kdiff/processor/Names.kt` — verify the generated file
  imports them from `io.github.kdiff.runtime`, asserted in `TrackingGenerationSpec`

## 6. Processor — generation

- [x] 6.1 In `DiffProcessor.generate()`, add `Tracked<T>` as a supertype and emit
  `override val trackScope: TrackScope<T> = trackScopeOf(...)` only when the class is `@Trackable`,
  keeping `diff` and `apply` byte-for-byte unchanged (design D9, D15) — verify
  `TrackingGenerationSpec` compiles and invokes the generated declaration, asserting the scope for a
  class-level depth, an unlimited default, a `@TrackDepth` override, and a `@TrackIgnore` exclusion
- [x] 6.2 Verify a class with no `@Trackable` gains no supertype and no property, and that `diff`
  reports identical changes with and without `@Trackable` — kctfork scenarios for "An unannotated class
  exposes no scope" and "Declaring a scope leaves comparison unchanged"
- [x] 6.3 Verify all three capabilities are reached through the one generated declaration with one
  import, and that a `@Trackable` sealed type's scope covers the properties the sealed parent declares
  — kctfork scenarios for "The scope is reached through the same declaration as comparison and
  application" and "An annotated sealed type declares a scope"
- [x] 6.4 Confirm each generated file still declares `Dependencies(aggregating = false,
  originatingFile)` and that the declared scope consults no other file (design D14) — verify by
  inspecting the emission call site and by a kctfork scenario where a nested `@Diffable` type carries
  its own `@Trackable` and the outer type's scope is unaffected

## 7. Processor — diagnostics

- [x] 7.1 Reject `@Trackable(depth = 0)` and any negative depth other than `UNLIMITED_DEPTH`, on the
  class and on `@TrackDepth`, via `KSPLogger.error(message, symbol)` at the annotated declaration —
  verify `DiagnosticSpec` asserts a failing `exitCode`, a message naming the declaration and the
  accepted values, and the reported location, for both targets
- [x] 7.2 Reject `@Trackable` on a class that is not `@Diffable` — verify `DiagnosticSpec` asserts the
  message names the class and states that `@Trackable` requires `@Diffable`, reported at the class
- [x] 7.3 Reject `@TrackIgnore` or `@TrackDepth` on a property of a class that is not `@Trackable`
  (design D12) — verify `DiagnosticSpec` asserts the message names the property and states which
  annotation is missing, for both annotations
- [x] 7.4 Reject `@TrackIgnore` together with `@TrackDepth` on the same property — verify
  `DiagnosticSpec` asserts the message names the property and states the conflict, reported at that
  property
- [x] 7.5 Reject `@TrackIgnore` or `@TrackDepth` on a `@DiffIgnore` property — verify `DiagnosticSpec`
  covers both combinations with a message stating an ignored property cannot be tracked
- [x] 7.6 Verify one contradictory annotation fails the whole build even alongside a valid trackable
  class — `DiagnosticSpec` scenario for "A rejected tracking annotation blocks the build"

## 8. Sample — both routes, end to end

- [x] 8.1 Annotate `Order` in `kdiff-sample/src/main/kotlin/demo/Model.kt` with `@Trackable`, giving one
  property `@TrackDepth` and one `@TrackIgnore` — verify `./gradlew :kdiff-sample:kspKotlin`
  regenerates `OrderDiff.kt` with `Tracked<Order>` and the expected `trackScope`
- [x] 8.2 Add `kdiff-sample/src/test/kotlin/demo/OrderTrackingSpec.kt` exercising the annotation route:
  the declared scope with no call-site field, a call-site field replacing it, `under(Order::billing)`,
  and depth filtering across the keyed `addresses` list — verify `./gradlew :kdiff-sample:test` passes
- [x] 8.3 Add to the same spec the hand-written route over the existing `MoneyDiffer` and the
  compare-only `WeightDiffer`: a `trackScope { }` for a type with no tracking annotation, and tracking
  changes beneath the compare-only `weight` property that `apply` reports as unpatchable — verify
  `./gradlew :kdiff-sample:test` passes and demonstrates trackable-but-unpatchable (design D3)
- [x] 8.4 Add a track-then-patch round trip to the sample alongside the existing `RoundTripSpec`:
  `OrderDiffer.apply(baseline, tracker.update(next).changes)` reproduces `next` under an unrestricted
  scope, and propagates only the tracked fields under a narrowed one (design D8) — verify
  `./gradlew :kdiff-sample:test` passes
- [x] 8.5 Re-run the existing sample specs (`OrderDiffSpec`, `RoundTripSpec`) unchanged to confirm the
  regenerated output is otherwise identical — verify `./gradlew :kdiff-sample:test` passes with no edits
  to those specs

## 9. Verification

- [x] 9.1 Confirm no version in `gradle/libs.versions.toml` moved and no new dependency was added to any
  module — verify by diffing the build files
- [x] 9.2 Run `./gradlew check` and report the result verbatim
