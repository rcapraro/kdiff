## 1. A resolution's sources are reachable from a test

- [x] 1.1 Add a test-only `SymbolProcessor` to `kdiff-processor`'s test source set that resolves the
  properties of a named class and records, per property, the `KSFile`s the resolution read — the
  processor's test classpath already carries `symbol-processing-api`, and `Resolution.kt`'s helpers are
  `internal`, so the same module's tests can call them. Register it through `configureKsp` beside
  `DiffProcessorProvider` as `CompileTesting.kt` does. Verify by asserting the recorded file for a
  property whose type is `@DiffAsValue`, which already returns its declaring file today.
- [x] 1.2 Add the failing cases before fixing anything: assert the recorded files for a property whose
  type is an inline `value class` declared in its own file, and one whose type is an `enum class`, and
  verify both tests **fail** — each records no file where it must record the declaring one. Assert also
  that a `BigDecimal` property records none, and verify that test passes.

## 2. A user-declared value type records its declaration

- [x] 2.1 Split `isIntrinsicValueType()` in `Resolution.kt` into the standard-library list and a new
  predicate for a value the author declares — an `enum class` or an inline `value class` (design D1) —
  keeping `isIntrinsicValueType()` as their disjunction so every existing caller is unchanged. Verify
  `./gradlew :kdiff-processor:test` still passes with no test edited.
- [x] 2.2 Widen `declaredValueSources()` to return the declaring file for a value the author declared as
  well as for `@DiffAsValue` (design D1). Verify the two tests from 1.2 now pass, the `BigDecimal` case
  still records none, and `./gradlew :kdiff-processor:test` passes.

## 3. A comparison annotation is read from the declaration that carries it

- [x] 3.1 Add `comparisonDeclaration()` to `Resolution.kt`: the property itself when it carries
  `@DiffWith`, `@DiffAsValue` or `@DiffIgnore`, else the nearest overridden declaration that carries one,
  else the property (design D2). Verify with a test asserting it resolves through a two-level and a
  three-level hierarchy, that a plain override does not cancel a grandparent's annotation, and that an
  annotation on the override wins over one on the overridee.
- [x] 3.2 Read `resolve(property)`'s three annotation rows off `property.comparisonDeclaration()` while
  the type stays `property.type` (design D2), and filter `comparableProperties()` on the same function so
  `@DiffIgnore` is inherited by the filter that applies it. Verify `./gradlew :kdiff-processor:test`
  passes with no existing test edited — nothing without an override may change.
- [x] 3.3 Add the overridee's file to the resolved `Comparison`'s `sources` where
  `comparisonDeclaration()` returned something other than the property itself (design D3). Verify with a
  test from 1.1's harness asserting the recorded file for a subclass property that inherits
  `@DiffAsValue` from a sealed parent in another file.

## 4. The behaviour the specs describe

- [x] 4.1 Add a kctfork spec for the sealed-override shape: `@Diffable sealed interface Doc` declaring
  `@DiffAsValue val meta: Meta`, `@Diffable data class Letter(override val meta: Meta, val body: String)
  : Doc`, and `@Diffable data class Meta(val title: String)`. Verify through `diffFixture` that two
  `Letter` instances differing inside `meta` report exactly one change at `meta` carrying both `Meta`
  instances and nothing at `meta.title`, and that the same shape reports the property identically across
  a subclass swap.
- [x] 4.2 Extend that spec to `@DiffIgnore` on a sealed parent property (no change reported at it from a
  subclass) and to `@DiffWith` (compared by the named differ, paths beneath the property), plus the
  precedence case where the override's own `@DiffIgnore` beats the parent's `@DiffAsValue`. Verify each
  by compiling and invoking, not by asserting on generated text.
- [x] 4.3 Verify the unannotated-parent case is untouched: a sealed parent declaring `val meta: Meta`
  with no comparison annotation still reports changes beneath `meta` from a subclass.
- [x] 4.4 Add the round-trip: `roundTripFixture` over two `Letter` instances differing inside an
  inherited `@DiffAsValue val meta`, verifying the rebuilt instance equals the target with no failures,
  and that a change at `meta.title` is reported as not applicable to a value property.

## 5. The one validation that asks about effect

- [x] 5.1 Change `reportsHonourableTrackingAnnotations`'s `@DiffIgnore` check to read
  `comparisonDeclaration()` (design D4), leaving every other annotation diagnostic on the declared
  annotations. Verify with a `DiagnosticSpec` case where a subclass override carries `@TrackDepth` and
  inherits `@DiffIgnore`, asserting the existing conflict message and its location at the override; and
  verify no diagnostic fires against a subclass that merely inherits `@DiffAsValue` where its own type is
  already a value.

## 6. Consumers and the sample

- [x] 6.1 Add the sealed-override shape to `kdiff-sample`'s `Model.kt` so the integration path covers it,
  regenerate with `./gradlew :kdiff-sample:kspKotlin --rerun-tasks`, and read the generated file to
  confirm the subclass emits the property's declared comparison rather than descending into it. Verify
  `./gradlew :kdiff-sample:test :kdiff-tutorial:test` passes.
- [x] 6.2 Confirm no published API moved: run `./gradlew checkLegacyAbi` and verify it passes with no
  dump change, since this change adds no public declaration.

## 7. Documentation

- [x] 7.1 Update `docs/annotations.md` (`@DiffAsValue`, `@DiffIgnore`, `@DiffWith` each honoured on an
  overriding subclass; an annotation on the override wins), `docs/diffing.md` (the sealed section: a
  parent's comparison annotations apply in both branches), and `docs/errors.md` (the
  `@TrackDepth`/`@DiffIgnore` conflict may arise from an inherited `@DiffIgnore`), per design
  *Documentation*. Verify every `<!-- from: -->` block still matches its source through
  `DocumentationSamplesSpec`.
- [x] 7.2 Add the `CHANGELOG.md` entries under `[Unreleased]`: **BREAKING** *Fixed* for the
  sealed-override behaviour, naming the shape and its migration, and *Fixed* for the stale incremental
  build after editing a value class or enum. Verify both read for someone deciding whether to upgrade.

## 8. Review follow-ups

Two defects a code review found in groups 3 and 5, both of them the change's own rule applied
incompletely. The specs gained four scenarios; D3 was rewritten and D4 restated.

- [x] 8.1 Replace the per-`Comparison` source addition with `comparisonChain()`/`comparisonSources()`,
  drained in `generate()` over **every declared property** (design D3, revised). Recording only what
  `comparisonDeclaration()` settled on missed the two edits that matter: a property excluded by an
  inherited `@DiffIgnore` produces no `Comparison` at all, and a property annotated nowhere recorded
  nothing, so *adding* an annotation to its parent could not reach it. Verify with four cases in the
  1.1 harness — inherited annotation, annotated nowhere, excluded, and chain stopping at the nearest
  annotated declaration — each asserting the whole consulted list.
- [x] 8.2 Derive inherited-ness from `declared !== property` rather than from the annotation source
  being non-empty (design D3). `containingFile` is null for a declaration read from a class file, so
  the old test reported a local override for a cross-module annotation it does not carry, and refused
  it — leaving the consumer no differ over a declaration it cannot edit. Verify with a two-stage
  kctfork compile (`compileDependency` then `compileAgainst`), and verify the test discriminates by
  restoring the old expression and watching it fail.
- [x] 8.3 Split the two resolution rejections' suppression rules (design D4, extended): a redundant
  inherited `@DiffAsValue` is never reported and never fails a build, while an inherited `@DiffWith`
  naming an unusable differ is reported unless the declaration carrying it is in this compilation.
  Verify all three cross-module cases: redundant value declaration honoured silently, usable differ
  honoured, unusable differ reported rather than silently dropped.

## 9. Done

- [x] 9.1 Run `./gradlew check` and verify it passes.
